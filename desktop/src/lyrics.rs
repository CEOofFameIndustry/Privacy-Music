//! Lyrics: YouTube Music (plain text) and, if the user opts in, LRCLIB (timed lines, Spotify-style).
//! Same rules as the phone: only title, artist, album and length are ever sent to lrclib.net.
use crate::model::Song;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use regex::Regex;
use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq)]
pub struct SyncedLine { pub time: i64, pub text: String, pub marks: Vec<(i64, usize)> }

#[derive(Clone, Debug, PartialEq)]
pub struct Lyrics { pub text: String, pub source: String, pub synced: Option<Vec<SyncedLine>> }

static CACHE: Lazy<Mutex<HashMap<(String, bool), Option<Lyrics>>>> = Lazy::new(|| Mutex::new(HashMap::new()));
pub fn clear() { CACHE.lock().clear() }

pub async fn get(song: &Song) -> anyhow::Result<Option<Lyrics>> {
    let sync_enabled = crate::settings::get().lrclib;
    let key = (song.id.clone(), sync_enabled);
    if let Some(l) = CACHE.lock().get(&key) { return Ok(l.clone()) }
    let mut out = None;
    if sync_enabled { out = lrclib(song).await }
    if out.is_none() {
        out = crate::yt::lyrics(&song.id).await?.map(|(body, footer)| Lyrics {
            text: body, source: footer.trim_start_matches("Source: ").trim_start_matches("Джерело: ").to_string(), synced: None,
        });
    }
    CACHE.lock().insert(key, out.clone());
    Ok(out)
}

fn clean(s: &str) -> String {
    static NOISE: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\(.*?\)|\[.*?]|\s-\s.*(remaster|version|live|edit|mix).*").unwrap());
    static WS: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s+").unwrap());
    WS.replace_all(&NOISE.replace_all(s, " "), " ").trim().to_string()
}

fn to_lyrics(o: &serde_json::Value) -> Option<Lyrics> {
    let synced = o["syncedLyrics"].as_str().filter(|s| !s.trim().is_empty()).map(parse_lrc).filter(|l| l.len() >= 2);
    let plain = o["plainLyrics"].as_str().filter(|s| !s.trim().is_empty()).map(String::from)
        .or_else(|| synced.as_ref().map(|l| l.iter().map(|x| x.text.clone()).collect::<Vec<_>>().join("\n")))?;
    Some(Lyrics { text: plain, source: "LRCLIB".into(), synced })
}

async fn lrclib(song: &Song) -> Option<Lyrics> {
    let artist = song.artists.first()?.name.clone();
    let title = clean(&song.title);
    let http = &*crate::firewall::HTTP;
    let mut q = vec![("track_name", title.clone()), ("artist_name", artist.clone())];
    if let Some(a) = &song.album { q.push(("album_name", a.name.clone())) }
    if let Some(d) = song.duration { q.push(("duration", d.to_string())) }
    if let Ok(r) = http.get("https://lrclib.net/api/get").query(&q).send().await {
        if r.status().is_success() {
            if let Some(l) = r.json::<serde_json::Value>().await.ok().as_ref().and_then(to_lyrics) { if l.synced.is_some() { return Some(l) } }
        }
    }
    let hits: Vec<serde_json::Value> = http.get("https://lrclib.net/api/search").query(&[("q", format!("{artist} {title}"))]).send().await.ok()?
        .json().await.ok()?;
    let dur = song.duration.map(|d| d as f64);
    let close = |o: &serde_json::Value| dur.map(|d| (o["duration"].as_f64().unwrap_or(0.0) - d).abs() <= 6.0).unwrap_or(true);
    let best = hits.iter().filter(|o| o["syncedLyrics"].as_str().is_some_and(|s| !s.is_empty()) && close(o))
        .min_by(|a, b| {
            let da = dur.map(|d| (a["duration"].as_f64().unwrap_or(0.0) - d).abs()).unwrap_or(0.0);
            let db = dur.map(|d| (b["duration"].as_f64().unwrap_or(0.0) - d).abs()).unwrap_or(0.0);
            da.partial_cmp(&db).unwrap_or(std::cmp::Ordering::Equal)
        })
        .or_else(|| hits.iter().find(|o| o["plainLyrics"].as_str().is_some_and(|s| !s.is_empty()) && close(o)))?;
    to_lyrics(best)
}

fn ms(min: &str, sec: &str, frac: &str) -> i64 {
    let f = if frac.is_empty() { 0 } else { format!("{frac}00")[..3].parse::<i64>().unwrap_or(0) };
    min.parse::<i64>().unwrap_or(0) * 60_000 + sec.parse::<i64>().unwrap_or(0) * 1000 + f
}

/// [mm:ss.xx] lines (several stamps per line allowed); enhanced-LRC <mm:ss.xx> word stamps become karaoke marks.
pub fn parse_lrc(text: &str) -> Vec<SyncedLine> {
    static LINE: Lazy<Regex> = Lazy::new(|| Regex::new(r"^((?:\[\d+:\d+(?:[.:]\d+)?])+)\s*(.*)$").unwrap());
    static STAMP: Lazy<Regex> = Lazy::new(|| Regex::new(r"\[(\d+):(\d+)(?:[.:](\d+))?]").unwrap());
    static WORD: Lazy<Regex> = Lazy::new(|| Regex::new(r"<(\d+):(\d+)(?:[.:](\d+))?>").unwrap());
    static WS: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s+").unwrap());
    let mut out = Vec::new();
    for line in text.lines() {
        let Some(m) = LINE.captures(line.trim()) else { continue };
        let raw = m[2].trim().to_string();
        let mut marks = Vec::new();
        let mut clean = String::new();
        let mut last = 0;
        for w in WORD.captures_iter(&raw) {
            let whole = w.get(0).unwrap();
            clean.push_str(&raw[last..whole.start()]);
            last = whole.end();
            marks.push((ms(&w[1], &w[2], w.get(3).map(|x| x.as_str()).unwrap_or("")), WS.replace_all(clean.trim_start(), " ").chars().count()));
        }
        clean.push_str(&raw[last..]);
        let words = WS.replace_all(&clean, " ").trim().to_string();
        let len = words.chars().count();
        for s in STAMP.captures_iter(&m[1]) {
            let t = ms(&s[1], &s[2], s.get(3).map(|x| x.as_str()).unwrap_or(""));
            out.push(SyncedLine { time: t, text: words.clone(), marks: marks.iter().copied().filter(|m| m.1 <= len).collect() });
        }
    }
    out.sort_by_key(|l| l.time);
    out
}

/// Karaoke fill — identical to the phone (data/Models.kt Karaoke).
pub mod karaoke {
    use super::SyncedLine;
    const VOWELS: &str = "aeiouyáéíóúàèìòùäëïöüâêîôûåæøœąęаеєиіїоуюяыэё";

    fn words_end(lines: &[SyncedLine], i: usize) -> i64 {
        let line = &lines[i];
        let len = line.text.chars().count();
        let last = *line.marks.last().unwrap();
        if last.1 >= len { return last.0 }
        let next = lines.get(i + 1).map(|l| l.time).unwrap_or(i64::MAX);
        next.min(last.0 + (len - last.1) as i64 * 90 + 300)
    }

    pub fn weights(text: &str) -> Vec<f64> {
        text.chars().map(|ch| {
            let c = ch.to_lowercase().next().unwrap_or(ch);
            if VOWELS.contains(c) { 1.0 }
            else if c as u32 >= 0x2E80 && c.is_alphabetic() { 1.0 }
            else if c.is_alphanumeric() { 0.25 }
            else if matches!(c, ',' | ';' | ':' | '—' | '–') { 0.8 }
            else { 0.0 }
        }).collect()
    }

    pub fn pace(lines: &[SyncedLine]) -> f64 {
        let mut r: Vec<f64> = (0..lines.len()).filter_map(|i| {
            let units: f64 = weights(&lines[i].text).iter().sum();
            let next = lines.get(i + 1)?.time;
            if units < 2.0 { None } else { Some((next - lines[i].time) as f64 / units) }
        }).collect();
        r.sort_by(|a, b| a.partial_cmp(b).unwrap());
        r.get((r.len() as f64 * 0.35) as usize).copied().unwrap_or(260.0).clamp(110.0, 700.0)
    }

    /// Time and character offset at each change of speed. Equal times skip silent characters.
    pub fn timeline(lines: &[SyncedLine], i: usize, pace: f64) -> Vec<(i64, usize)> {
        let line = &lines[i];
        let weights = weights(&line.text);
        let len = weights.len();
        if !line.marks.is_empty() {
            let mut marks = line.marks.clone();
            marks.push((words_end(lines, i), len));
            let mut seen = std::collections::HashSet::new();
            marks.retain(|m| seen.insert(m.1));
            marks.sort_by_key(|m| m.0);
            return marks;
        }
        let units: f64 = weights.iter().sum();
        let gap = (lines.get(i + 1).map(|l| l.time).unwrap_or(line.time + 6000) - line.time) as f64;
        if units == 0.0 {
            return vec![(line.time, 0), (line.time + (gap * 0.9).max(300.0).round() as i64, len)];
        }
        let est = units * pace;
        let duration = (if gap <= est * 1.4 { gap * 0.92 } else { est }).clamp(300.0, (gap * 0.95).max(300.0));
        let mut points = vec![(line.time, 0)];
        let mut elapsed = 0.0;
        for (index, weight) in weights.iter().enumerate() {
            elapsed += weight;
            let time = line.time + (duration * elapsed / units).round() as i64;
            points.push((time, index + 1));
        }
        points
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn position(points: &[(i64, usize)], time: i64) -> f64 {
        let next = points.partition_point(|p| p.0 <= time);
        if next == 0 { return 0.0 }
        let (t, offset) = points[next - 1];
        let Some(&(end, target)) = points.get(next) else { return offset as f64 };
        offset as f64 + (target as f64 - offset as f64) * (time - t) as f64 / (end - t) as f64
    }

    #[test] fn syllables() {
        let l = parse_lrc("[00:00.00]la la la la\n[00:01.00]la la la la\n[00:02.00]la la la la\n[00:10.00]la la la la\n[00:11.00]x");
        let pace = karaoke::pace(&l);
        assert!((pace - 200.0).abs() < 0.01);
        assert!((position(&karaoke::timeline(&l, 2, pace), 2_500) - 6.0).abs() < 0.01);
        assert!((position(&karaoke::timeline(&l, 2, pace), 3_000) - 11.0).abs() < 0.01);
        assert!((position(&karaoke::timeline(&l, 0, pace), 460) - 6.0).abs() < 0.01);
    }
    #[test] fn word_timings() {
        let l = parse_lrc("[00:01.00]<00:01.00>Hello <00:01.50>bright <00:03.50>world<00:04.00>\n[00:06.00]x");
        assert_eq!(l[0].text, "Hello bright world");
        assert!((position(&karaoke::timeline(&l, 0, 260.0), 2_500) - 9.5).abs() < 0.01);
        assert!((position(&karaoke::timeline(&l, 0, 260.0), 4_000) - 18.0).abs() < 0.01);
    }
}

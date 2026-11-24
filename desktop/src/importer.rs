//! Importing music: Telegram Desktop export, CSV / TSV tables, YouTube Music and Spotify playlist links.
//! Matching on YouTube Music sends only "artist title" searches; Telegram files can instead be kept as local songs.
use crate::model::{Ref, Song};
use anyhow::{anyhow, Result};
use sha2::Digest;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq)]
pub struct Entry { pub title: String, pub artist: String, pub duration: Option<u32>, pub file: Option<PathBuf> }

/// A Telegram Desktop export (Settings → Advanced → Export Telegram data, or "Export chat history" on a chat,
/// format JSON). Every audio file message becomes an entry; the file is used if it was exported too.
pub fn telegram(result_json: &Path) -> Result<(String, Vec<Entry>)> {
    let text = std::fs::read_to_string(result_json)?;
    let v: serde_json::Value = serde_json::from_str(&text)?;
    let dir = result_json.parent().unwrap_or(Path::new("."));
    let mut out = Vec::new();
    let mut name = v["name"].as_str().unwrap_or("Telegram").to_string();
    let mut scan = |chat: &serde_json::Value| {
        for m in chat["messages"].as_array().into_iter().flatten() {
            if m["media_type"].as_str() != Some("audio_file") { continue }
            let file_rel = m["file"].as_str().unwrap_or("");
            let fname = m["file_name"].as_str().unwrap_or(file_rel);
            let stem = Path::new(fname).file_stem().and_then(|s| s.to_str()).unwrap_or("").to_string();
            let mut title = m["title"].as_str().unwrap_or("").trim().to_string();
            let mut artist = m["performer"].as_str().unwrap_or("").trim().to_string();
            if title.is_empty() {
                // No tags: try "Artist - Title" from the file name.
                match stem.split_once(" - ") { Some((a, t)) => { artist = a.trim().into(); title = t.trim().into() } None => title = stem.clone() }
            }
            if title.is_empty() { continue }
            let path = dir.join(file_rel);
            let file = (!file_rel.is_empty() && !file_rel.starts_with("(File not included") && path.exists()).then_some(path);
            out.push(Entry { title, artist, duration: m["duration_seconds"].as_u64().map(|d| d as u32), file });
        }
    };
    if v["chats"]["list"].is_array() {
        name = "Telegram".into();
        for c in v["chats"]["list"].as_array().unwrap() { scan(c) }
    } else { scan(&v) }
    // Same song forwarded many times → once.
    let mut seen = std::collections::HashSet::new();
    out.retain(|e| seen.insert(format!("{}|{}", e.artist.to_lowercase(), e.title.to_lowercase())));
    Ok((name, out))
}

/// A local song for an exported audio file (plays from disk; never sent anywhere).
pub fn local_song(e: &Entry) -> Option<Song> {
    let path = e.file.as_ref()?;
    let id = format!("file:{:x}", sha2::Sha256::digest(path.to_string_lossy().as_bytes()))[..21].to_string();
    let song = Song { id, title: e.title.clone(), artists: if e.artist.is_empty() { vec![] } else { vec![Ref { name: e.artist.clone(), id: None }] },
        album: None, duration: e.duration, thumbnail: None };
    // Imported again: keep the cover (and album) found for it last time.
    let song = match crate::db::song(&song.id) { Some(old) => Song { thumbnail: old.thumbnail, album: old.album, ..song }, None => song };
    crate::db::set_download(&song, &path.to_string_lossy());
    Some(song)
}

/// CSV / TSV (Exportify, TuneMyMusic, Google Takeout…): finds title / artist / duration columns by name.
pub fn table(path: &Path) -> Result<Vec<Entry>> {
    let text = std::fs::read_to_string(path)?;
    let sep = if text.lines().next().unwrap_or("").contains('\t') { '\t' } else if text.lines().next().unwrap_or("").contains(';') { ';' } else { ',' };
    let rows: Vec<Vec<String>> = text.lines().map(|l| split_csv(l, sep)).filter(|r| r.iter().any(|c| !c.trim().is_empty())).collect();
    let head: Vec<String> = rows.first().ok_or_else(|| anyhow!("empty"))?.iter().map(|h| h.trim().to_lowercase()).collect();
    let find = |names: &[&str]| names.iter().find_map(|n| head.iter().position(|h| h == n));
    let ti = find(&["track name", "song title", "title", "song", "track", "name", "назва", "пісня", "трек", "название"]).ok_or_else(|| anyhow!("no title column"))?;
    let ai = find(&["artist name(s)", "artist names", "artist name 1", "artist name", "artists", "artist", "виконавець", "исполнитель"]);
    let di = find(&["duration (ms)", "track duration (ms)", "duration_ms", "duration", "тривалість"]);
    Ok(rows.iter().skip(1).filter_map(|r| {
        let title = r.get(ti)?.trim().to_string();
        if title.is_empty() { return None }
        let artist = ai.and_then(|i| r.get(i)).map(|a| a.split([',', ';']).next().unwrap_or("").trim().to_string()).unwrap_or_default();
        let duration = di.and_then(|i| r.get(i)).and_then(|d| d.trim().parse::<f64>().ok()).map(|d| if d > 10_000.0 { (d / 1000.0) as u32 } else { d as u32 });
        Some(Entry { title, artist, duration, file: None })
    }).collect())
}

fn split_csv(line: &str, sep: char) -> Vec<String> {
    let mut out = vec![String::new()]; let mut q = false; let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' if q && chars.peek() == Some(&'"') => { out.last_mut().unwrap().push('"'); chars.next(); }
            '"' => q = !q,
            c if c == sep && !q => out.push(String::new()),
            c => out.last_mut().unwrap().push(c),
        }
    }
    out
}

/// A YouTube Music / YouTube playlist or album link, or a public Spotify playlist / album link.
pub async fn link(url: &str) -> Result<(String, Vec<Entry>, Vec<Song>)> {
    if url.contains("spotify.com") {
        let re = regex::Regex::new(r"spotify\.com/(?:intl-\w+/)?(playlist|album|track)/([A-Za-z0-9]+)")?;
        let c = re.captures(url).ok_or_else(|| anyhow!("unknown link"))?;
        let html = crate::firewall::HTTP.get(format!("https://open.spotify.com/embed/{}/{}", &c[1], &c[2])).send().await?.text().await?;
        let jre = regex::Regex::new(r#"(?s)<script id="__NEXT_DATA__" type="application/json">(.*?)</script>"#)?;
        let json: serde_json::Value = serde_json::from_str(&jre.captures(&html).ok_or_else(|| anyhow!("unexpected page"))?[1])?;
        let e = &json["props"]["pageProps"]["state"]["data"]["entity"];
        let name = e["name"].as_str().or(e["title"].as_str()).unwrap_or("Spotify").to_string();
        let list = e["trackList"].as_array().cloned().unwrap_or_default();
        let entries = if list.is_empty() { vec![Entry { title: name.clone(), artist: e["subtitle"].as_str().unwrap_or("").into(), duration: None, file: None }] }
            else { list.iter().map(|t| Entry { title: t["title"].as_str().unwrap_or("").into(), artist: t["subtitle"].as_str().unwrap_or("").replace('\u{a0}', " "),
                duration: t["duration"].as_u64().filter(|d| *d > 0).map(|d| (d / 1000) as u32), file: None }).collect() };
        return Ok((name, entries, vec![]));
    }
    match crate::yt::parse_link(url) {
        Some(('p', id)) => { let p = crate::yt::playlist_page(&id).await?; Ok((p.title, vec![], p.songs)) }
        Some(('a', id)) => { let p = crate::yt::album_page(&id).await?; Ok((p.title, vec![], p.songs)) }
        _ => Err(anyhow!(crate::tr!("Невідоме посилання"))),
    }
}

fn norm(s: &str) -> String {
    let re = regex::Regex::new(r"(?i)\(.*?\)|\[.*?]|feat\.?.*|ft\..*|[^\p{L}\p{N} ]").unwrap();
    re.replace_all(&s.to_lowercase(), " ").split_whitespace().collect::<Vec<_>>().join(" ")
}

fn score(e: &Entry, s: &Song) -> f64 {
    let (t1, t2) = (norm(&e.title), norm(&s.title));
    let mut sc = if t1 == t2 { 3.0 } else if t2.contains(&t1) || t1.contains(&t2) { 2.0 } else {
        let a: std::collections::HashSet<_> = t1.split(' ').collect(); let b: std::collections::HashSet<_> = t2.split(' ').collect();
        2.0 * a.intersection(&b).count() as f64 / (a.len() + b.len()).max(1) as f64
    };
    let ea = norm(&e.artist);
    if !ea.is_empty() && s.artists.iter().any(|a| { let n = norm(&a.name); n.contains(&ea) || ea.contains(&n) }) { sc += 2.0 }
    if let (Some(d1), Some(d2)) = (e.duration, s.duration) { if (d1 as i64 - d2 as i64).abs() <= 3 { sc += 1.0 } }
    sc
}

/// Finds each entry on YouTube Music (4 searches at a time). Returns (matched songs in order, not found).
pub async fn match_all(entries: Vec<Entry>, progress: impl Fn(usize, usize) + Send + Sync + 'static) -> (Vec<Song>, Vec<Entry>) {
    use futures::StreamExt;
    let total = entries.len();
    let progress = std::sync::Arc::new(progress);
    let done = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let results: Vec<(usize, Entry, Option<Song>)> = futures::stream::iter(entries.into_iter().enumerate()).map(|(i, e)| {
        let (p, d) = (progress.clone(), done.clone());
        async move {
            let q = format!("{} {}", e.artist, e.title);
            let best = crate::yt::search_songs(q.trim()).await.ok().and_then(|r| {
                r.into_iter().take(5).map(|s| (score(&e, &s), s)).max_by(|a, b| a.0.partial_cmp(&b.0).unwrap()).filter(|x| x.0 >= 2.0).map(|x| x.1)
            });
            p(d.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1, total);
            (i, e, best)
        }
    }).buffer_unordered(4).collect().await;
    let mut results = results; results.sort_by_key(|r| r.0);
    let mut found = Vec::new(); let mut missing = Vec::new();
    for (_, e, s) in results { match s { Some(s) => found.push(s), None => missing.push(e) } }
    (found, missing)
}

#[cfg(test)]
mod tests {
    #[test]
    fn telegram_export() {
        let dir = std::env::temp_dir().join("pm-tg-test");
        let _ = std::fs::create_dir_all(dir.join("files"));
        std::fs::write(dir.join("files/desire.mp3"), b"x").unwrap();
        let json = r#"{"name":"BuddyMusic | Музыка","type":"bot_chat","messages":[
            {"id":1,"type":"message","media_type":"audio_file","file":"files/desire.mp3","file_name":"desire.mp3","performer":"Morris","title":"Desire","duration_seconds":180},
            {"id":2,"type":"message","media_type":"audio_file","file":"(File not included. Change data exporting settings to download.)","file_name":"Morris - desire (slowed&reverb).mp3","duration_seconds":200},
            {"id":3,"type":"message","media_type":"audio_file","file":"files/desire.mp3","performer":"Morris","title":"Desire"},
            {"id":4,"type":"message","media_type":"voice_message","file":"voice.ogg"},
            {"id":5,"type":"message","text":"Натисни, щоб знайти пісню"}]}"#;
        std::fs::write(dir.join("result.json"), json).unwrap();
        let (name, e) = super::telegram(&dir.join("result.json")).unwrap();
        assert_eq!(name, "BuddyMusic | Музыка");
        assert_eq!(e.len(), 2); // duplicate forward and non-audio messages are skipped
        assert_eq!((e[0].artist.as_str(), e[0].title.as_str(), e[0].duration), ("Morris", "Desire", Some(180)));
        assert!(e[0].file.is_some());
        assert_eq!((e[1].artist.as_str(), e[1].title.as_str()), ("Morris", "desire (slowed&reverb)")); // from the file name
        assert!(e[1].file.is_none());
    }
}

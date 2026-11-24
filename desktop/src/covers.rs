//! Artwork for imported files (Telegram exports have none): each song is looked up on YouTube Music by
//! "artist title" — the same request a search sends — and the cover of a confident match is used.
//! Only the cover URL is taken; the song keeps playing from the local file.
use crate::model::Song;
use once_cell::sync::Lazy;
use parking_lot::Mutex;

/// (done, total) of the running lookup, None = idle.
pub static PROGRESS: Lazy<Mutex<Option<(usize, usize)>>> = Lazy::new(Default::default);

fn words(s: &str) -> Vec<String> {
    // Drop "(Official Video)", "[slowed]", "feat. X" decorations; compare plain lowercase words.
    let mut t = String::new();
    let mut depth = 0i32;
    for c in s.to_lowercase().chars() {
        match c { '(' | '[' => depth += 1, ')' | ']' => depth -= 1, _ if depth <= 0 => t.push(if c.is_alphanumeric() { c } else { ' ' }), _ => {} }
    }
    t.split_whitespace().filter(|w| !matches!(*w, "feat" | "ft" | "the" | "official" | "audio" | "video" | "lyrics")).map(String::from).collect()
}
fn overlap(a: &[String], b: &[String]) -> f32 {
    if a.is_empty() || b.is_empty() { return 0.0 }
    a.iter().filter(|w| b.contains(w)).count() as f32 / a.len().min(b.len()) as f32
}

/// A good enough match: the title words agree, and so does the artist when the file has one.
pub fn matches(local: &Song, found: &Song) -> bool {
    let (lt, ft) = (words(&local.title), words(&found.title));
    let la = words(&local.artist_text());
    let fa = words(&found.artist_text());
    let title_ok = overlap(&lt, &ft) >= 0.75;
    let artist_ok = la.is_empty() || overlap(&la, &fa) >= 0.5 || overlap(&la, &ft) >= 0.5;
    title_ok && artist_ok
}

/// Finds covers for these songs (only local files without one), one search at a time.
pub async fn find(songs: Vec<Song>) -> usize {
    let todo: Vec<Song> = songs.into_iter().filter(|s| s.id.starts_with("file:") && s.thumbnail.is_none()).collect();
    if todo.is_empty() || PROGRESS.lock().is_some() || crate::settings::get().offline { return 0 }
    *PROGRESS.lock() = Some((0, todo.len()));
    let mut found = 0;
    for (i, s) in todo.iter().enumerate() {
        let q = format!("{} {}", s.artist_text(), s.title);
        if let Ok(res) = crate::yt::search_songs(q.trim()).await {
            if let Some(m) = res.iter().take(5).find(|r| matches(s, r)) {
                let mut s = s.clone();
                s.thumbnail = m.thumbnail.clone();
                if s.album.is_none() { s.album = m.album.clone().map(|a| crate::model::Ref { name: a.name, id: None }) }
                crate::db::put_song(&s);
                found += 1;
                // Redraw now and then, not after every song (a big import would redraw the list hundreds of times).
                if found % 8 == 1 { crate::db::bump() }
            }
        }
        *PROGRESS.lock() = Some((i + 1, todo.len()));
        tokio::time::sleep(std::time::Duration::from_millis(250)).await;
    }
    *PROGRESS.lock() = None;
    crate::db::bump();
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    fn s(t: &str, a: &str) -> Song { Song { id: "file:x".into(), title: t.into(), artists: if a.is_empty() { vec![] } else { vec![crate::model::Ref { name: a.into(), id: None }] }, album: None, duration: None, thumbnail: None } }
    #[test]
    fn matching() {
        assert!(matches(&s("Shadows", "Pastel Ghost"), &s("Shadows", "Pastel Ghost")));
        assert!(matches(&s("Bad Romance (Official Video)", "Lady Gaga"), &s("Bad Romance", "Lady Gaga")));
        assert!(!matches(&s("Shadows", "Pastel Ghost"), &s("Shadows", "Lindsey Stirling")));
        assert!(matches(&s("Idea 22", ""), &s("Idea 22", "Gibran Alcocer")));
    }
}

//! The local library (SQLite in the user's data directory). Nothing here ever leaves the computer.
use crate::model::{Item, Song};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use rusqlite::{params, Connection, OptionalExtension};
use std::time::{SystemTime, UNIX_EPOCH};

static DB: Lazy<Mutex<Connection>> = Lazy::new(|| {
    let c = Connection::open(crate::settings::data_dir().join("library.db")).expect("open library.db");
    c.execute_batch(
        "PRAGMA journal_mode=WAL;
         CREATE TABLE IF NOT EXISTS songs(id TEXT PRIMARY KEY, json TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS history(song_id TEXT NOT NULL, at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS likes(song_id TEXT PRIMARY KEY, at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS dislikes(song_id TEXT PRIMARY KEY);
         CREATE TABLE IF NOT EXISTS playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS playlist_songs(playlist_id INTEGER NOT NULL, pos INTEGER NOT NULL, song_id TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS saved(kind TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(kind,id));
         CREATE TABLE IF NOT EXISTS searches(q TEXT PRIMARY KEY, at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS plays(id INTEGER PRIMARY KEY AUTOINCREMENT, song_id TEXT NOT NULL, started INTEGER NOT NULL, ms INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS pins(key TEXT PRIMARY KEY, json TEXT NOT NULL, at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS downloads(song_id TEXT PRIMARY KEY, path TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS tombstones(kind TEXT NOT NULL, key TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(kind,key));",
    ).expect("schema");
    // Library sync with the phone: a stable id and a change time per playlist (ignored if the columns exist).
    let _ = c.execute("ALTER TABLE playlists ADD COLUMN uid TEXT", []);
    let _ = c.execute("ALTER TABLE playlists ADD COLUMN modified INTEGER NOT NULL DEFAULT 0", []);
    let _ = c.execute("UPDATE playlists SET uid=lower(hex(randomblob(16))) WHERE uid IS NULL", []);
    let _ = c.execute("UPDATE playlists SET modified=created WHERE modified=0", []);
    // A picture the user chose for the playlist (data_dir/covers/<id>); the number changes with every new picture.
    let _ = c.execute("ALTER TABLE playlists ADD COLUMN cover INTEGER NOT NULL DEFAULT 0", []);
    Mutex::new(c)
});

/// Bumped on every write; the UI re-reads what it shows when it changes.
pub static VERSION: Lazy<tokio::sync::watch::Sender<u64>> = Lazy::new(|| tokio::sync::watch::channel(0).0);
fn changed() { VERSION.send_modify(|v| *v += 1) }
pub fn bump() { changed() }
/// Direct access for bulk operations (e.g. restoring a phone backup in one transaction).
pub fn with<T>(f: impl FnOnce(&Connection) -> T) -> T { f(&DB.lock()) }

pub fn now_ms() -> i64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) }

pub fn put_song(s: &Song) {
    let json = serde_json::to_string(s).unwrap_or_default();
    let _ = DB.lock().execute("INSERT OR REPLACE INTO songs(id,json) VALUES(?,?)", params![s.id, json]);
}

fn songs_q(sql: &str, p: impl rusqlite::Params) -> Vec<Song> {
    let db = DB.lock();
    let Ok(mut st) = db.prepare(sql) else { return vec![] };
    let rows = st.query_map(p, |r| r.get::<_, String>(0));
    rows.map(|it| it.filter_map(|r| r.ok()).filter_map(|j| serde_json::from_str(&j).ok()).collect()).unwrap_or_default()
}

pub fn song(id: &str) -> Option<Song> { songs_q("SELECT json FROM songs WHERE id=?", params![id]).into_iter().next() }

// ---------- history ----------
pub fn add_history(s: &Song) {
    if !crate::settings::get().history { return }
    put_song(s);
    let _ = DB.lock().execute("INSERT INTO history(song_id,at) VALUES(?,?)", params![s.id, now_ms()]);
    changed();
}
pub fn history(limit: i64) -> Vec<Song> {
    songs_q("SELECT s.json FROM (SELECT song_id, MAX(at) m FROM history GROUP BY song_id) h JOIN songs s ON s.id=h.song_id ORDER BY h.m DESC LIMIT ?", params![limit])
}
pub fn remove_history(id: &str) { let _ = DB.lock().execute("DELETE FROM history WHERE song_id=?", params![id]); changed() }
pub fn clear_history() { let _ = DB.lock().execute("DELETE FROM history", []); changed() }
/// Most played songs (by number of history entries) — used for the personal home page.
pub fn top_songs(limit: i64) -> Vec<Song> {
    songs_q("SELECT s.json FROM (SELECT song_id, COUNT(*) c FROM history GROUP BY song_id) h JOIN songs s ON s.id=h.song_id ORDER BY h.c DESC LIMIT ?", params![limit])
}

// ---------- likes ----------
pub fn set_liked(s: &Song, liked: bool) {
    put_song(s);
    let db = DB.lock();
    if liked { let _ = db.execute("INSERT OR REPLACE INTO likes(song_id,at) VALUES(?,?)", params![s.id, now_ms()]); let _ = db.execute("DELETE FROM dislikes WHERE song_id=?", params![s.id]); untomb(&db, "like", &s.id) }
    else { let _ = db.execute("DELETE FROM likes WHERE song_id=?", params![s.id]); tomb(&db, "like", &s.id, now_ms()) }
    drop(db); changed();
}
pub fn is_liked(id: &str) -> bool { DB.lock().query_row("SELECT 1 FROM likes WHERE song_id=?", params![id], |_| Ok(())).optional().ok().flatten().is_some() }
pub fn liked() -> Vec<Song> { songs_q("SELECT s.json FROM likes l JOIN songs s ON s.id=l.song_id ORDER BY l.at DESC", []) }
pub fn set_disliked(s: &Song, on: bool) {
    let db = DB.lock();
    if on { let _ = db.execute("INSERT OR REPLACE INTO dislikes(song_id) VALUES(?)", params![s.id]); if db.execute("DELETE FROM likes WHERE song_id=?", params![s.id]).unwrap_or(0) > 0 { tomb(&db, "like", &s.id, now_ms()) } }
    else { let _ = db.execute("DELETE FROM dislikes WHERE song_id=?", params![s.id]); }
    drop(db); changed();
}
pub fn is_disliked(id: &str) -> bool { DB.lock().query_row("SELECT 1 FROM dislikes WHERE song_id=?", params![id], |_| Ok(())).optional().ok().flatten().is_some() }

// ---------- local playlists ----------
#[derive(Clone, Debug, PartialEq)]
pub struct Playlist { pub id: i64, pub name: String, pub description: String, pub count: i64, pub thumbnail: Option<String>,
    /// Up to four different song covers, for a 2×2 mosaic when there is no picture of its own.
    pub covers: Vec<String>, pub custom_cover: bool }

pub fn playlists() -> Vec<Playlist> {
    let db = DB.lock();
    let Ok(mut st) = db.prepare("SELECT p.id,p.name,p.description,(SELECT COUNT(*) FROM playlist_songs WHERE playlist_id=p.id), p.cover FROM playlists p ORDER BY p.created DESC") else { return vec![] };
    let mut out: Vec<Playlist> = st.query_map([], |r| {
        Ok(Playlist { id: r.get(0)?, name: r.get(1)?, description: r.get(2)?, count: r.get(3)?, thumbnail: None, covers: vec![], custom_cover: r.get::<_, i64>(4)? > 0 }
            .with_cover(r.get(4)?))
    }).map(|it| it.filter_map(|r| r.ok()).collect()).unwrap_or_default();
    drop(st);
    // The first covers in the playlist's order (songs without artwork are skipped).
    if let Ok(mut c) = db.prepare("SELECT s.json FROM playlist_songs ps JOIN songs s ON s.id=ps.song_id WHERE ps.playlist_id=? AND json_extract(s.json,'$.thumbnail') IS NOT NULL ORDER BY ps.pos LIMIT 24") {
        for p in out.iter_mut() {
            let rows: Vec<String> = c.query_map(params![p.id], |r| r.get(0)).map(|it| it.filter_map(|r| r.ok()).collect()).unwrap_or_default();
            for j in rows {
                if let Some(t) = serde_json::from_str::<Song>(&j).ok().and_then(|s| s.thumbnail) { if !p.covers.contains(&t) { p.covers.push(t) } }
                if p.covers.len() >= 4 { break }
            }
            if p.thumbnail.is_none() { p.thumbnail = p.covers.first().cloned() }
        }
    }
    out
}
impl Playlist {
    fn with_cover(mut self, v: i64) -> Self { if v > 0 { self.thumbnail = Some(format!("local:cover-{}-{v}", self.id)) } self }
}
pub fn cover_file(id: i64) -> std::path::PathBuf { crate::settings::data_dir().join("covers").join(id.to_string()) }
/// Uses the picture at `path` as the playlist's cover (None = back to the songs' covers).
pub fn set_playlist_cover(id: i64, path: Option<&std::path::Path>) -> bool {
    let file = cover_file(id);
    match path {
        Some(p) => { let _ = std::fs::create_dir_all(file.parent().unwrap()); if std::fs::copy(p, &file).is_err() { return false } }
        None => { let _ = std::fs::remove_file(&file); }
    }
    let v = if path.is_some() { now_ms() } else { 0 };
    let _ = DB.lock().execute("UPDATE playlists SET cover=? WHERE id=?", params![v, id]);
    changed();
    true
}
/// Saves a song's new details (e.g. a cover found for an imported file) everywhere it is shown.
pub fn update_song(s: &Song) { put_song(s); changed() }
pub fn playlist(id: i64) -> Option<Playlist> { playlists().into_iter().find(|p| p.id == id) }
pub fn create_playlist(name: &str) -> i64 {
    let db = DB.lock();
    let _ = db.execute("INSERT INTO playlists(name,created,uid,modified) VALUES(?,?,lower(hex(randomblob(16))),?)", params![name, now_ms(), now_ms()]);
    let id = db.last_insert_rowid(); drop(db); changed(); id
}
pub fn rename_playlist(id: i64, name: &str, description: &str) { let _ = DB.lock().execute("UPDATE playlists SET name=?, description=?, modified=? WHERE id=?", params![name, description, now_ms(), id]); changed() }
fn touch(db: &Connection, id: i64) { let _ = db.execute("UPDATE playlists SET modified=? WHERE id=?", params![now_ms(), id]); }
pub fn delete_playlist(id: i64) {
    let db = DB.lock();
    if let Ok(uid) = db.query_row("SELECT uid FROM playlists WHERE id=?", params![id], |r| r.get::<_, String>(0)) { tomb(&db, "playlist", &uid, now_ms()) }
    let _ = db.execute("DELETE FROM playlist_songs WHERE playlist_id=?", params![id]);
    let _ = db.execute("DELETE FROM playlists WHERE id=?", params![id]);
    drop(db); changed();
}
pub fn playlist_songs(id: i64) -> Vec<Song> { songs_q("SELECT s.json FROM playlist_songs ps JOIN songs s ON s.id=ps.song_id WHERE ps.playlist_id=? ORDER BY ps.pos", params![id]) }
pub fn add_to_playlist(id: i64, songs: &[Song]) {
    for s in songs { put_song(s) }
    let db = DB.lock();
    let start: i64 = db.query_row("SELECT COALESCE(MAX(pos),-1)+1 FROM playlist_songs WHERE playlist_id=?", params![id], |r| r.get(0)).unwrap_or(0);
    for (i, s) in songs.iter().enumerate() { let _ = db.execute("INSERT INTO playlist_songs(playlist_id,pos,song_id) VALUES(?,?,?)", params![id, start + i as i64, s.id]); }
    touch(&db, id);
    drop(db); changed();
}
/// Replaces the whole order (used by the editor: reorder / remove).
pub fn set_playlist_songs(id: i64, songs: &[Song]) {
    let db = DB.lock();
    let _ = db.execute("DELETE FROM playlist_songs WHERE playlist_id=?", params![id]);
    for (i, s) in songs.iter().enumerate() { let _ = db.execute("INSERT INTO playlist_songs(playlist_id,pos,song_id) VALUES(?,?,?)", params![id, i as i64, s.id]); }
    touch(&db, id);
    drop(db); changed();
}

// ---------- saved albums / playlists / artists ----------
pub fn save(item: &Item, on: bool) {
    let (kind, id) = match item { Item::Album { id, .. } => ("album", id.clone()), Item::Playlist { id, .. } => ("playlist", id.clone()), Item::Artist { id, .. } => ("artist", id.clone()), _ => return };
    let db = DB.lock();
    let key = format!("{kind}:{id}");
    if on { let _ = db.execute("INSERT OR REPLACE INTO saved(kind,id,json,at) VALUES(?,?,?,?)", params![kind, id, serde_json::to_string(item).unwrap_or_default(), now_ms()]); untomb(&db, "saved", &key) }
    else { let _ = db.execute("DELETE FROM saved WHERE kind=? AND id=?", params![kind, id]); tomb(&db, "saved", &key, now_ms()) }
    drop(db); changed();
}
pub fn is_saved(kind: &str, id: &str) -> bool { DB.lock().query_row("SELECT 1 FROM saved WHERE kind=? AND id=?", params![kind, id], |_| Ok(())).optional().ok().flatten().is_some() }
pub fn saved(kind: &str) -> Vec<Item> {
    let db = DB.lock();
    let Ok(mut st) = db.prepare("SELECT json FROM saved WHERE kind=? ORDER BY at DESC") else { return vec![] };
    st.query_map(params![kind], |r| r.get::<_, String>(0)).map(|it| it.filter_map(|r| r.ok()).filter_map(|j| serde_json::from_str(&j).ok()).collect()).unwrap_or_default()
}

// ---------- search history ----------
pub fn add_search(q: &str) {
    if !crate::settings::get().search_history || q.trim().is_empty() { return }
    let _ = DB.lock().execute("INSERT OR REPLACE INTO searches(q,at) VALUES(?,?)", params![q.trim(), now_ms()]); changed();
}
pub fn searches(prefix: &str) -> Vec<String> {
    let db = DB.lock();
    let Ok(mut st) = db.prepare("SELECT q FROM searches WHERE q LIKE ? ORDER BY at DESC LIMIT 8") else { return vec![] };
    st.query_map(params![format!("{}%", prefix.trim())], |r| r.get(0)).map(|it| it.filter_map(|r| r.ok()).collect()).unwrap_or_default()
}
pub fn remove_search(q: &str) { let _ = DB.lock().execute("DELETE FROM searches WHERE q=?", params![q]); changed() }
pub fn clear_searches() { let _ = DB.lock().execute("DELETE FROM searches", []); changed() }

// ---------- plays (statistics, local only) ----------
pub fn start_play(s: &Song) -> Option<i64> {
    if !crate::settings::get().stats { return None }
    put_song(s);
    let db = DB.lock();
    db.execute("INSERT INTO plays(song_id,started,ms) VALUES(?,?,0)", params![s.id, now_ms()]).ok()?;
    Some(db.last_insert_rowid())
}
pub fn update_play(id: i64, ms: i64) { let _ = DB.lock().execute("UPDATE plays SET ms=? WHERE id=?", params![ms, id]); }
/// (song, started_ms, listened_ms) for every play.
pub fn plays() -> Vec<(Song, i64, i64)> {
    let db = DB.lock();
    let Ok(mut st) = db.prepare("SELECT s.json,p.started,p.ms FROM plays p JOIN songs s ON s.id=p.song_id ORDER BY p.started") else { return vec![] };
    st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?)))
        .map(|it| it.filter_map(|r| r.ok()).filter_map(|(j, a, b)| serde_json::from_str(&j).ok().map(|s| (s, a, b))).collect()).unwrap_or_default()
}
pub fn clear_plays() { let _ = DB.lock().execute("DELETE FROM plays", []); changed() }

// ---------- pins ----------
pub fn pin(item: &Item, on: bool) {
    let db = DB.lock();
    if on { let _ = db.execute("INSERT OR REPLACE INTO pins(key,json,at) VALUES(?,?,?)", params![item.key(), serde_json::to_string(item).unwrap_or_default(), now_ms()]); }
    else { let _ = db.execute("DELETE FROM pins WHERE key=?", params![item.key()]); }
    drop(db); changed();
}
pub fn is_pinned(key: &str) -> bool { DB.lock().query_row("SELECT 1 FROM pins WHERE key=?", params![key], |_| Ok(())).optional().ok().flatten().is_some() }
pub fn pins() -> Vec<Item> {
    let db = DB.lock();
    let Ok(mut st) = db.prepare("SELECT json FROM pins ORDER BY at DESC") else { return vec![] };
    st.query_map([], |r| r.get::<_, String>(0)).map(|it| it.filter_map(|r| r.ok()).filter_map(|j| serde_json::from_str(&j).ok()).collect()).unwrap_or_default()
}

// ---------- downloads ----------
pub fn download_path(id: &str) -> Option<String> {
    DB.lock().query_row("SELECT path FROM downloads WHERE song_id=?", params![id], |r| r.get(0)).optional().ok().flatten()
        .filter(|p: &String| std::path::Path::new(p).exists())
}
pub fn set_download(s: &Song, path: &str) { put_song(s); let _ = DB.lock().execute("INSERT OR REPLACE INTO downloads(song_id,path) VALUES(?,?)", params![s.id, path]); changed() }
pub fn remove_download(id: &str) {
    if let Some(p) = download_path(id) { let _ = std::fs::remove_file(p); }
    let _ = DB.lock().execute("DELETE FROM downloads WHERE song_id=?", params![id]); changed();
}
pub fn downloads() -> Vec<Song> { songs_q("SELECT s.json FROM downloads d JOIN songs s ON s.id=d.song_id", []) }

pub fn wipe_all() {
    let db = DB.lock();
    let _ = db.execute_batch("DELETE FROM history; DELETE FROM likes; DELETE FROM dislikes; DELETE FROM playlist_songs; DELETE FROM playlists;
        DELETE FROM saved; DELETE FROM searches; DELETE FROM plays; DELETE FROM pins; DELETE FROM downloads; DELETE FROM songs; DELETE FROM tombstones;");
    drop(db); changed();
}

// ---------- library sync with the phone (see link.rs) ----------
/// A removal remembered so the other device removes it too instead of bringing it back.
fn tomb(db: &Connection, kind: &str, key: &str, at: i64) { let _ = db.execute("INSERT INTO tombstones(kind,key,at) VALUES(?,?,?) ON CONFLICT(kind,key) DO UPDATE SET at=max(at,excluded.at)", params![kind, key, at]); }
fn untomb(db: &Connection, kind: &str, key: &str) { let _ = db.execute("DELETE FROM tombstones WHERE kind=? AND key=?", params![kind, key]); }
fn tomb_at(db: &Connection, kind: &str, key: &str) -> Option<i64> { db.query_row("SELECT at FROM tombstones WHERE kind=? AND key=?", params![kind, key], |r| r.get(0)).optional().ok().flatten() }

#[derive(Clone, Debug, Default)]
pub struct SyncPlaylist { pub uid: String, pub name: String, pub description: String, pub created: i64, pub modified: i64, pub songs: Vec<Song> }
#[derive(Default)]
pub struct SyncData { pub likes: Vec<(Song, i64)>, pub saved: Vec<(Item, i64)>, pub playlists: Vec<SyncPlaylist>, pub tombs: Vec<(String, String, i64)> }

/// Changes whenever anything that is synced changes (cheap; checked after every library change).
pub fn sync_fingerprint() -> String {
    DB.lock().query_row("SELECT (SELECT COUNT(*) FROM likes) || ':' || IFNULL((SELECT MAX(at) FROM likes),0) || ':' || (SELECT COUNT(*) FROM saved) || ':' || IFNULL((SELECT MAX(at) FROM saved),0) || ':' || (SELECT COUNT(*) FROM playlists) || ':' || IFNULL((SELECT MAX(modified) FROM playlists),0) || ':' || (SELECT COUNT(*) FROM tombstones) || ':' || IFNULL((SELECT MAX(at) FROM tombstones),0)", [], |r| r.get(0)).unwrap_or_default()
}

pub fn sync_dump() -> SyncData {
    let likes = {
        let db = DB.lock();
        let mut st = db.prepare("SELECT s.json, l.at FROM likes l JOIN songs s ON s.id=l.song_id").expect("likes");
        st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))).map(|it| it.filter_map(|r| r.ok())
            .filter_map(|(j, at)| serde_json::from_str::<Song>(&j).ok().map(|s| (s, at))).collect::<Vec<_>>()).unwrap_or_default()
    };
    let saved = {
        let db = DB.lock();
        let mut st = db.prepare("SELECT json, at FROM saved").expect("saved");
        st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))).map(|it| it.filter_map(|r| r.ok())
            .filter_map(|(j, at)| serde_json::from_str::<Item>(&j).ok().map(|i| (i, at))).collect::<Vec<_>>()).unwrap_or_default()
    };
    let heads: Vec<(i64, SyncPlaylist)> = {
        let db = DB.lock();
        let mut st = db.prepare("SELECT id, uid, name, description, created, modified FROM playlists").expect("playlists");
        st.query_map([], |r| Ok((r.get(0)?, SyncPlaylist { uid: r.get(1)?, name: r.get(2)?, description: r.get(3)?, created: r.get(4)?, modified: r.get(5)?, songs: vec![] })))
            .map(|it| it.filter_map(|r| r.ok()).collect()).unwrap_or_default()
    };
    let playlists = heads.into_iter().map(|(id, mut p)| { p.songs = playlist_songs(id); p }).collect();
    let tombs = {
        let db = DB.lock();
        let mut st = db.prepare("SELECT kind, key, at FROM tombstones").expect("tombs");
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).map(|it| it.filter_map(|r| r.ok()).collect()).unwrap_or_default()
    };
    SyncData { likes, saved, playlists, tombs }
}

/// Merges the other device's library: the newest change wins for each like, saved item and playlist.
/// Returns whether anything changed here.
pub fn sync_merge(remote: SyncData, unlikes: Vec<(String, i64)>, unsaved: Vec<(String, i64)>, pldel: Vec<(String, i64)>) -> bool {
    let db = DB.lock();
    let Ok(tx) = db.unchecked_transaction() else { return false };
    let mut changed = false;
    let liked_at = |id: &str| tx.query_row("SELECT at FROM likes WHERE song_id=?", params![id], |r| r.get::<_, i64>(0)).optional().ok().flatten();
    for (song, at) in &remote.likes {
        if liked_at(&song.id).is_some() || tomb_at(&tx, "like", &song.id).map_or(false, |t| t >= *at) { continue }
        let _ = tx.execute("INSERT OR IGNORE INTO songs(id,json) VALUES(?,?)", params![song.id, serde_json::to_string(song).unwrap_or_default()]);
        let _ = tx.execute("INSERT OR REPLACE INTO likes(song_id,at) VALUES(?,?)", params![song.id, at]);
        let _ = tx.execute("DELETE FROM dislikes WHERE song_id=?", params![song.id]);
        untomb(&tx, "like", &song.id);
        changed = true;
    }
    for (id, at) in &unlikes {
        match liked_at(id) {
            Some(l) if l <= *at => { let _ = tx.execute("DELETE FROM likes WHERE song_id=?", params![id]); tomb(&tx, "like", id, *at); changed = true }
            Some(_) => {}
            None => tomb(&tx, "like", id, *at),
        }
    }
    let saved_at = |key: &str| { let (k, i) = key.split_once(':').unwrap_or(("", "")); tx.query_row("SELECT at FROM saved WHERE kind=? AND id=?", params![k, i], |r| r.get::<_, i64>(0)).optional().ok().flatten() };
    for (item, at) in &remote.saved {
        let (kind, id) = match item { Item::Album { id, .. } => ("album", id), Item::Playlist { id, .. } => ("playlist", id), Item::Artist { id, .. } => ("artist", id), _ => continue };
        let key = format!("{kind}:{id}");
        if saved_at(&key).is_some() || tomb_at(&tx, "saved", &key).map_or(false, |t| t >= *at) { continue }
        let _ = tx.execute("INSERT OR REPLACE INTO saved(kind,id,json,at) VALUES(?,?,?,?)", params![kind, id, serde_json::to_string(item).unwrap_or_default(), at]);
        untomb(&tx, "saved", &key);
        changed = true;
    }
    for (key, at) in &unsaved {
        match saved_at(key) {
            Some(s) if s <= *at => { let (k, i) = key.split_once(':').unwrap_or(("", "")); let _ = tx.execute("DELETE FROM saved WHERE kind=? AND id=?", params![k, i]); tomb(&tx, "saved", key, *at); changed = true }
            Some(_) => {}
            None => tomb(&tx, "saved", key, *at),
        }
    }
    for p in &remote.playlists {
        let by_uid = |uid: &str| tx.query_row("SELECT id, modified FROM playlists WHERE uid=?", params![uid], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?))).optional().ok().flatten();
        let mut local = by_uid(&p.uid);
        if local.is_none() {
            if tomb_at(&tx, "playlist", &p.uid).map_or(false, |t| t >= p.modified) { continue }
            // The same playlist created on both sides before syncing (e.g. restored from a phone backup): adopt its id.
            let twin: Option<i64> = tx.query_row("SELECT id FROM playlists WHERE name=? AND created=?", params![p.name, p.created], |r| r.get(0)).optional().ok().flatten();
            if let Some(id) = twin { let _ = tx.execute("UPDATE playlists SET uid=? WHERE id=?", params![p.uid, id]); local = by_uid(&p.uid) }
        }
        let id = match local {
            Some((_, modified)) if modified >= p.modified => continue,
            Some((id, _)) => { let _ = tx.execute("UPDATE playlists SET name=?, description=?, modified=? WHERE id=?", params![p.name, p.description, p.modified, id]); id }
            None => { let _ = tx.execute("INSERT INTO playlists(name,description,created,uid,modified) VALUES(?,?,?,?,?)", params![p.name, p.description, p.created, p.uid, p.modified]); tx.last_insert_rowid() }
        };
        let _ = tx.execute("DELETE FROM playlist_songs WHERE playlist_id=?", params![id]);
        for (i, s) in p.songs.iter().enumerate() {
            let _ = tx.execute("INSERT OR IGNORE INTO songs(id,json) VALUES(?,?)", params![s.id, serde_json::to_string(s).unwrap_or_default()]);
            let _ = tx.execute("INSERT INTO playlist_songs(playlist_id,pos,song_id) VALUES(?,?,?)", params![id, i as i64, s.id]);
        }
        untomb(&tx, "playlist", &p.uid);
        changed = true;
    }
    for (uid, at) in &pldel {
        let local: Option<(i64, i64)> = tx.query_row("SELECT id, modified FROM playlists WHERE uid=?", params![uid], |r| Ok((r.get(0)?, r.get(1)?))).optional().ok().flatten();
        match local {
            Some((id, modified)) if modified <= *at => {
                let _ = tx.execute("DELETE FROM playlist_songs WHERE playlist_id=?", params![id]);
                let _ = tx.execute("DELETE FROM playlists WHERE id=?", params![id]);
                let _ = tx.execute("DELETE FROM pins WHERE key=?", params![format!("l:{id}")]);
                tomb(&tx, "playlist", uid, *at); changed = true
            }
            Some(_) => {}
            None => tomb(&tx, "playlist", uid, *at),
        }
    }
    let _ = tx.commit();
    changed
}

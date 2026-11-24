//! Moving data from the phone: opens the phone's encrypted backup (.pmbackup — AES-256-GCM, "PMBACKUP1" header,
//! 52-symbol recovery key) and loads its library into this computer's library. Everything happens locally.
use crate::model::{Item, Ref, Song};
use aes_gcm::{aead::{Aead, KeyInit, Payload}, Aes256Gcm, Nonce};
use anyhow::{anyhow, Result};
use rusqlite::params;
use serde_json::Value;
use std::io::Read;

const MAGIC: &[u8] = b"PMBACKUP1";
const ALPHABET: &str = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

fn parse_key(text: &str) -> Result<[u8; 32]> {
    let clean: Vec<usize> = text.to_uppercase().chars().filter_map(|c| ALPHABET.find(c)).collect();
    if clean.len() != 52 { return Err(anyhow!(crate::tr!("Ключ має містити 52 символи (зараз {0})", clean.len()))) }
    let (mut out, mut buf, mut bits) = (Vec::with_capacity(33), 0u32, 0u32);
    for v in clean { buf = (buf << 5) | v as u32; bits += 5; if bits >= 8 { out.push(((buf >> (bits - 8)) & 0xff) as u8); bits -= 8; buf &= (1 << bits) - 1 } }
    let mut key = [0u8; 32];
    key.copy_from_slice(&out[..32]);
    Ok(key)
}

/// Decrypts a phone backup into its JSON payload.
pub fn open(bytes: &[u8], key_text: &str) -> Result<Value> {
    if bytes.len() < MAGIC.len() + 12 + 16 || &bytes[..MAGIC.len()] != MAGIC { return Err(anyhow!(crate::tr!("Це не файл резервної копії Privacy Music"))) }
    let key = parse_key(key_text)?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| anyhow!("{e}"))?;
    let nonce = Nonce::from_slice(&bytes[MAGIC.len()..MAGIC.len() + 12]);
    let plain = cipher.decrypt(nonce, Payload { msg: &bytes[MAGIC.len() + 12..], aad: MAGIC })
        .map_err(|_| anyhow!(crate::tr!("Неправильний ключ відновлення або файл пошкоджено")))?;
    let mut json = String::new();
    flate2::read::GzDecoder::new(&plain[..]).read_to_string(&mut json)?;
    Ok(serde_json::from_str(&json)?)
}

/// The phone's song JSON: {id,title,artists:[{n,i}],album:{n,i},duration,thumb}.
fn phone_song(j: &str) -> Option<Song> {
    let o: Value = serde_json::from_str(j).ok()?;
    let id = o["id"].as_str()?.to_string();
    if id.starts_with("content:") || id.starts_with("file:") { return None } // phone-only files
    let r = |x: &Value| Ref { name: x["n"].as_str().unwrap_or("").to_string(), id: x["i"].as_str().filter(|s| !s.is_empty() && *s != "null").map(String::from) };
    Some(Song {
        id, title: o["title"].as_str().unwrap_or("").to_string(),
        artists: o["artists"].as_array().map(|a| a.iter().map(r).collect()).unwrap_or_default(),
        album: o.get("album").filter(|a| a.is_object()).map(r),
        duration: o["duration"].as_i64().filter(|d| *d > 0).map(|d| d as u32),
        thumbnail: o["thumb"].as_str().filter(|s| !s.is_empty() && *s != "null").map(String::from),
    })
}

/// The phone's pin JSON (ItemCodec).
fn phone_item(o: &Value) -> Option<Item> {
    let s = |k: &str| o[k].as_str().filter(|v| !v.is_empty() && *v != "null").map(String::from);
    let refs = |v: &Value| v.as_array().map(|a| a.iter().map(|x| Ref { name: x["n"].as_str().unwrap_or("").into(), id: x["i"].as_str().filter(|i| !i.is_empty() && *i != "null").map(String::from) }).collect()).unwrap_or_default();
    Some(match o["kind"].as_str()? {
        "song" => Item::Song(phone_song(&o.to_string())?),
        "album" => Item::Album { id: s("id")?, title: s("title").unwrap_or_default(), artists: refs(&o["artists"]), year: o["year"].as_u64().map(|y| y as u16), thumbnail: s("thumb") },
        "playlist" => Item::Playlist { id: s("id")?, title: s("title").unwrap_or_default(), subtitle: s("sub").unwrap_or_default(), thumbnail: s("thumb") },
        "artist" => Item::Artist { id: s("id")?, name: s("title").unwrap_or_default(), subtitle: s("sub").unwrap_or_default(), thumbnail: s("thumb") },
        _ => return None,
    })
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Summary { pub songs: usize, pub liked: usize, pub playlists: usize, pub saved: usize, pub history: usize, pub plays: usize }

/// Replaces this computer's library with the phone's. Returns what was moved.
pub fn restore(payload: &Value) -> Result<Summary> {
    let lib = &payload["library"];
    let rows = |t: &str| lib[t].as_array().cloned().unwrap_or_default();
    let mut sum = Summary::default();
    crate::db::wipe_all();
    crate::db::with(|db| -> rusqlite::Result<()> {
        let tx = db.unchecked_transaction()?;
        for r in rows("songs") {
            let Some(song) = r["json"].as_str().and_then(phone_song) else { continue };
            tx.execute("INSERT OR REPLACE INTO songs(id,json) VALUES(?,?)", params![song.id, serde_json::to_string(&song).unwrap_or_default()])?;
            sum.songs += 1;
            if let Some(at) = r["liked_at"].as_i64() { tx.execute("INSERT OR REPLACE INTO likes(song_id,at) VALUES(?,?)", params![song.id, at])?; sum.liked += 1 }
            if r["disliked"].as_i64() == Some(1) { tx.execute("INSERT OR REPLACE INTO dislikes(song_id) VALUES(?)", params![song.id])?; }
        }
        let known = |tx: &rusqlite::Transaction, id: &str| tx.query_row("SELECT 1 FROM songs WHERE id=?", params![id], |_| Ok(())).is_ok();
        for r in rows("history") {
            let id = r["song_id"].as_str().unwrap_or("");
            if known(&tx, id) { tx.execute("INSERT INTO history(song_id,at) VALUES(?,?)", params![id, r["played_at"].as_i64().unwrap_or(0)])?; sum.history += 1 }
        }
        let mut map = std::collections::HashMap::new();
        for r in rows("playlists") {
            // Keeps the phone's sync id, so this playlist and the phone's copy stay one playlist when synced.
            let created = r["created_at"].as_i64().unwrap_or(0);
            let uid = r["uid"].as_str().filter(|u| !u.is_empty()).map(String::from).unwrap_or_else(|| hex::encode(rand::random::<[u8; 16]>()));
            tx.execute("INSERT INTO playlists(name,description,created,uid,modified) VALUES(?,?,?,?,?)",
                params![r["name"].as_str().unwrap_or("Playlist"), r["description"].as_str().unwrap_or(""), created, uid, r["modified"].as_i64().filter(|m| *m > 0).unwrap_or(created)])?;
            map.insert(r["id"].as_i64().unwrap_or(-1), tx.last_insert_rowid());
            sum.playlists += 1;
        }
        for r in rows("playlist_songs") {
            let id = r["song_id"].as_str().unwrap_or("");
            if let (Some(pid), true) = (map.get(&r["playlist_id"].as_i64().unwrap_or(-2)), known(&tx, id)) {
                tx.execute("INSERT INTO playlist_songs(playlist_id,pos,song_id) VALUES(?,?,?)", params![pid, r["position"].as_i64().unwrap_or(0), id])?;
            }
        }
        for r in rows("saved") {
            let s = |k: &str| r[k].as_str().unwrap_or("").to_string();
            let thumb = r["thumbnail"].as_str().filter(|t| !t.is_empty()).map(String::from);
            let item = match s("kind").as_str() {
                "album" => Item::Album { id: s("id"), title: s("title"), artists: vec![Ref { name: s("subtitle"), id: None }], year: None, thumbnail: thumb },
                "playlist" => Item::Playlist { id: s("id"), title: s("title"), subtitle: s("subtitle"), thumbnail: thumb },
                "artist" => Item::Artist { id: s("id"), name: s("title"), subtitle: s("subtitle"), thumbnail: thumb },
                _ => continue,
            };
            tx.execute("INSERT OR REPLACE INTO saved(kind,id,json,at) VALUES(?,?,?,?)", params![s("kind"), s("id"), serde_json::to_string(&item).unwrap_or_default(), r["saved_at"].as_i64().unwrap_or(0)])?;
            sum.saved += 1;
        }
        for r in rows("searches") { tx.execute("INSERT OR REPLACE INTO searches(q,at) VALUES(?,?)", params![r["query"].as_str().unwrap_or(""), r["at"].as_i64().unwrap_or(0)])?; }
        for r in rows("plays") {
            let id = r["song_id"].as_str().unwrap_or("");
            if known(&tx, id) { tx.execute("INSERT INTO plays(song_id,started,ms) VALUES(?,?,?)", params![id, r["started_at"].as_i64().unwrap_or(0), r["listened_ms"].as_i64().unwrap_or(0)])?; sum.plays += 1 }
        }
        for r in rows("pins") {
            let Some(item) = r["json"].as_str().and_then(|j| serde_json::from_str::<Value>(j).ok()).as_ref().and_then(phone_item) else { continue };
            tx.execute("INSERT OR REPLACE INTO pins(key,json,at) VALUES(?,?,?)", params![item.key(), serde_json::to_string(&item).unwrap_or_default(), r["at"].as_i64().unwrap_or(0)])?;
        }
        tx.commit()
    })?;
    // Preferences that mean the same on both: privacy choices, lyrics, content language/region, colours.
    let st = &payload["settings"];
    crate::settings::update(|s| {
        let b = |k: &str, d: bool| st[k].as_bool().unwrap_or(d);
        s.history = b("history_enabled", s.history); s.search_history = b("search_history_enabled", s.search_history);
        s.lrclib = b("lrclib", s.lrclib); s.autoplay = b("autoplay", s.autoplay); s.stats = b("stats_enabled", s.stats);
        s.lyrics_flow = b("lyrics_flow", s.lyrics_flow); s.search_suggestions = b("search_suggestions", s.search_suggestions);
        if let Some(v) = st["hl"].as_str() { s.hl = v.into() }
        if let Some(v) = st["gl"].as_str() { s.gl = v.into() }
        if let Some(v) = st["ui_lang"].as_str() { s.ui_language = v.into() }
        if let Some(v) = st["theme_bg"].as_i64() { s.theme_bg = (v as u32) & 0xffffff }
        if let Some(v) = st["theme_accent"].as_i64() { let a = (v as u32) & 0xffffff; if a != 0xffffff { s.theme_accent = a } }
        if let Some(v) = st["layout_home"].as_str() { s.layout_home = v.into() }
    });
    crate::db::bump();
    Ok(sum)
}

#[cfg(test)]
mod tests {
    #[test]
    fn key_roundtrip() {
        // Same Base32 alphabet and bit order as the phone (Backup.kt format()/parse()).
        let raw: [u8; 32] = std::array::from_fn(|i| (i * 37 + 11) as u8);
        let (mut s, mut buf, mut bits) = (String::new(), 0u32, 0u32);
        for b in raw { buf = (buf << 8) | b as u32; bits += 8; while bits >= 5 { s.push(super::ALPHABET.as_bytes()[((buf >> (bits - 5)) & 31) as usize] as char); bits -= 5 } buf &= (1 << bits) - 1 }
        if bits > 0 { s.push(super::ALPHABET.as_bytes()[((buf << (5 - bits)) & 31) as usize] as char) }
        let grouped: Vec<String> = s.as_bytes().chunks(4).map(|c| String::from_utf8(c.to_vec()).unwrap()).collect();
        assert_eq!(super::parse_key(&grouped.join("-").to_lowercase()).unwrap(), raw);
    }
}

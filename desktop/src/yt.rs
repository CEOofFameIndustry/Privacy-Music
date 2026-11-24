//! YouTube Music catalogue + streams through RustyPipe, with all of its traffic sent through the firewall proxy.
//! No cookies, no account, no on-disk cache of YouTube session data (visitor data lives in memory only).
use crate::model::{Item, Ref, Shelf, Song};
use crate::tr;
use anyhow::{anyhow, Result};
use once_cell::sync::OnceCell;
use rustypipe::client::RustyPipe;
use rustypipe::model::richtext::ToPlaintext;
use rustypipe::model::{AlbumItem, ArtistId, ArtistItem, MusicItem, MusicPlaylistItem, Thumbnail, TrackItem};

static RP: OnceCell<RustyPipe> = OnceCell::new();

pub fn init() -> Result<()> {
    let rp = RustyPipe::builder()
        .no_storage()     // nothing about the YouTube session is written to disk
        .no_reporter()    // no error reports
        .no_botguard()
        .unauthenticated()
        .build_with_client(crate::firewall::client_builder())?;
    let _ = RP.set(rp);
    Ok(())
}

fn rp() -> rustypipe::client::RustyPipeQuery {
    let q = RP.get().expect("yt not initialised").query();
    let s = crate::settings::get();
    let q = match s.hl.parse() { Ok(l) => q.lang(l), Err(_) => q };
    match s.gl.parse() { Ok(c) => q.country(c), Err(_) => q }
}

fn offline() -> Result<()> {
    if crate::settings::get().offline { Err(anyhow!(tr!("Офлайн-режим: мережу вимкнено в налаштуваннях приватності"))) } else { Ok(()) }
}

/// Largest thumbnail, resized on Google's image servers to `size` px where the URL allows it.
pub fn thumb(t: &[Thumbnail], size: u32) -> Option<String> {
    let best = t.iter().max_by_key(|t| t.width)?;
    Some(resize(&best.url, size))
}

/// Artwork in full quality for big views (YouTube Music covers come resizable from Google's image server).
pub fn hq(url: Option<&str>) -> Option<String> {
    let u = url?;
    Some(if u.contains("i.ytimg.com/vi/") {
        // Video thumbnails: the 720p frame instead of the small default one.
        let id = u.split("/vi/").nth(1).and_then(|r| r.split('/').next()).unwrap_or("");
        format!("https://i.ytimg.com/vi/{id}/hq720.jpg")
    } else { resize(u, 1200) })
}

pub fn resize(url: &str, size: u32) -> String {
    if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) && url.contains("=w") {
        let base = url.split("=w").next().unwrap_or(url);
        format!("{base}=w{size}-h{size}-l90-rj")
    } else { url.to_string() }
}

fn refs(a: &[ArtistId]) -> Vec<Ref> { a.iter().map(|a| Ref { name: a.name.clone(), id: a.id.clone() }).collect() }

pub fn song(t: &TrackItem) -> Song {
    Song {
        id: t.id.clone(), title: t.name.clone(), artists: refs(&t.artists),
        album: t.album.as_ref().map(|a| Ref { name: a.name.clone(), id: Some(a.id.clone()) }),
        duration: t.duration, thumbnail: thumb(&t.cover, 544),
    }
}

fn album(a: &AlbumItem) -> Item {
    Item::Album { id: a.id.clone(), title: a.name.clone(), artists: refs(&a.artists), year: a.year, thumbnail: thumb(&a.cover, 400) }
}

fn artist(a: &ArtistItem) -> Item {
    let sub = a.subscriber_count.map(|n| tr!("{0} підписників", compact(n))).unwrap_or_else(|| tr!("Виконавець"));
    Item::Artist { id: a.id.clone(), name: a.name.clone(), subtitle: sub, thumbnail: thumb(&a.avatar, 400) }
}

fn playlist(p: &MusicPlaylistItem) -> Item {
    let mut sub = Vec::new();
    if let Some(c) = &p.channel { sub.push(c.name.clone()) }
    if let Some(n) = p.track_count { sub.push(tr!("{0} треків", n)) }
    Item::Playlist { id: p.id.clone(), title: p.name.clone(), subtitle: sub.join(" • "), thumbnail: thumb(&p.thumbnail, 400) }
}

fn music_item(m: &MusicItem) -> Option<Item> {
    Some(match m {
        MusicItem::Track(t) => Item::Song(song(t)),
        MusicItem::Album(a) => album(a),
        MusicItem::Artist(a) => artist(a),
        MusicItem::Playlist(p) => playlist(p),
        _ => return None,
    })
}

pub fn compact(n: u64) -> String {
    match n { n if n >= 1_000_000 => format!("{:.1}M", n as f64 / 1e6), n if n >= 1_000 => format!("{:.0}K", n as f64 / 1e3), n => n.to_string() }
}

pub async fn search(q: &str) -> Result<Vec<Item>> {
    offline()?;
    let r = rp().music_search_main(q).await?;
    Ok(r.items.items.iter().filter_map(music_item).collect())
}

/// Search like YouTube Music's results page: songs, artists, albums and playlists sections (fetched in parallel).
pub async fn search_all(q: &str) -> Result<Vec<Shelf>> {
    offline()?;
    let r = rp();
    let (t, ar, al, pl) = tokio::join!(r.music_search_tracks(q), r.music_search_artists(q), r.music_search_albums(q), r.music_search_playlists(q, false));
    let mut out = Vec::new();
    if let Ok(t) = t { if !t.items.items.is_empty() { out.push(Shelf { id: Some("songs".into()), title: tr!("Пісні"), items: t.items.items.iter().map(|x| Item::Song(song(x))).collect(), list: true }) } }
    if let Ok(a) = ar { if !a.items.items.is_empty() { out.push(Shelf { id: Some("artists".into()), title: tr!("Виконавці"), items: a.items.items.iter().map(artist).collect(), list: false }) } }
    if let Ok(a) = al { if !a.items.items.is_empty() { out.push(Shelf { id: Some("albums".into()), title: tr!("Альбоми"), items: a.items.items.iter().map(album).collect(), list: false }) } }
    if let Ok(p) = pl { if !p.items.items.is_empty() { out.push(Shelf { id: Some("playlists".into()), title: tr!("Плейлисти"), items: p.items.items.iter().map(playlist).collect(), list: false }) } }
    if out.is_empty() { return Err(anyhow!(tr!("Нічого не знайдено"))) }
    Ok(out)
}

pub async fn search_songs(q: &str) -> Result<Vec<Song>> {
    offline()?;
    let r = rp().music_search_tracks(q).await?;
    Ok(r.items.items.iter().map(song).collect())
}

pub async fn suggestions(q: &str) -> Result<(Vec<String>, Vec<Item>)> {
    offline()?;
    let s = rp().music_search_suggestion(q).await?;
    Ok((s.terms, s.items.iter().filter_map(music_item).collect()))
}

/// "Explore": new releases, charts, moods & genres.
pub async fn explore() -> Result<Vec<Shelf>> {
    offline()?;
    let q = rp();
    let mut shelves = Vec::new();
    if let Ok(albums) = q.music_new_albums().await {
        shelves.push(Shelf { id: Some("new".into()), title: tr!("Нові альбоми й сингли"), items: albums.iter().map(album).collect(), list: false });
    }
    if let Ok(c) = q.music_charts(None).await {
        if !c.top_tracks.is_empty() { shelves.push(Shelf { id: Some("top_tracks".into()), title: tr!("Топ треків"), items: c.top_tracks.iter().take(20).map(|t| Item::Song(song(t))).collect(), list: true }) }
        if !c.artists.is_empty() { shelves.push(Shelf { id: Some("top_artists".into()), title: tr!("Топ виконавців"), items: c.artists.iter().map(artist).collect(), list: false }) }
        if !c.playlists.is_empty() { shelves.push(Shelf { id: Some("charts".into()), title: tr!("Чарти"), items: c.playlists.iter().map(playlist).collect(), list: false }) }
    }
    Ok(shelves)
}

pub struct AlbumPage { pub title: String, pub artists: Vec<Ref>, pub subtitle: String, pub thumbnail: Option<String>, pub description: Option<String>, pub songs: Vec<Song> }

pub async fn album_page(id: &str) -> Result<AlbumPage> {
    offline()?;
    let a = rp().music_album(id).await?;
    let cover = thumb(&a.cover, 720);
    let songs = a.tracks.iter().map(|t| { let mut s = song(t); if s.thumbnail.is_none() { s.thumbnail = cover.clone() } s }).collect();
    Ok(AlbumPage {
        title: a.name.clone(), artists: refs(&a.artists), thumbnail: cover,
        subtitle: [Some(format!("{:?}", a.album_type)), a.year.map(|y| y.to_string())].into_iter().flatten().collect::<Vec<_>>().join(" • "),
        description: a.description.as_ref().map(|d| d.to_plaintext()), songs,
    })
}

pub async fn playlist_page(id: &str) -> Result<AlbumPage> {
    offline()?;
    let q = rp();
    let mut p = q.music_playlist(id).await?;
    let _ = p.tracks.extend_limit(&q, 300).await;
    Ok(AlbumPage {
        title: p.name.clone(), artists: p.channel.as_ref().map(|c| vec![Ref { name: c.name.clone(), id: Some(c.id.clone()) }]).unwrap_or_default(),
        subtitle: p.track_count.map(|n| tr!("{0} треків", n)).unwrap_or_default(), thumbnail: thumb(&p.thumbnail, 720),
        description: p.description.as_ref().map(|d| d.to_plaintext()), songs: p.tracks.items.iter().map(song).collect(),
    })
}

pub struct ArtistPage { pub name: String, pub image: Option<String>, pub description: Option<String>, pub subscribers: Option<String>, pub radio: Option<String>, pub shelves: Vec<Shelf> }

pub async fn artist_page(id: &str) -> Result<ArtistPage> {
    offline()?;
    let a = rp().music_artist(id, false).await?;
    let mut shelves = Vec::new();
    if !a.tracks.is_empty() { shelves.push(Shelf { id: None, title: tr!("Популярні пісні"), items: a.tracks.iter().map(|t| Item::Song(song(t))).collect(), list: true }) }
    if !a.albums.is_empty() { shelves.push(Shelf { id: None, title: tr!("Альбоми й сингли"), items: a.albums.iter().map(album).collect(), list: false }) }
    if !a.playlists.is_empty() { shelves.push(Shelf { id: None, title: tr!("Плейлисти"), items: a.playlists.iter().map(playlist).collect(), list: false }) }
    if !a.similar_artists.is_empty() { shelves.push(Shelf { id: None, title: tr!("Схожі виконавці"), items: a.similar_artists.iter().map(artist).collect(), list: false }) }
    Ok(ArtistPage {
        name: a.name.clone(), image: thumb(&a.header_image, 1080), description: a.description.clone(),
        subscribers: a.subscriber_count.map(|n| tr!("{0} підписників", compact(n))), radio: a.radio_id.clone(), shelves,
    })
}

/// Endless "radio" for a song (what YouTube Music plays after it).
pub async fn radio(video_id: &str) -> Result<Vec<Song>> {
    offline()?;
    Ok(rp().music_radio_track(video_id).await?.items.iter().map(song).collect())
}

pub async fn radio_playlist(id: &str) -> Result<Vec<Song>> {
    offline()?;
    Ok(rp().music_radio(id).await?.items.iter().map(song).collect())
}

pub async fn related(video_id: &str) -> Result<Vec<Shelf>> {
    offline()?;
    let q = rp();
    let d = q.music_details(video_id).await?;
    // Some tracks have no "related" page (YouTube answers "No extra content available"): use their radio instead.
    let r = match d.related_id { Some(rid) => q.music_related(&rid).await.ok(), None => None };
    let Some(r) = r else {
        let songs: Vec<Item> = radio(video_id).await.unwrap_or_default().into_iter().filter(|x| x.id != video_id).take(25).map(Item::Song).collect();
        return Ok(if songs.is_empty() { vec![] } else { vec![Shelf { id: None, title: tr!("Вам також може сподобатися"), items: songs, list: true }] });
    };
    let mut s = Vec::new();
    if !r.tracks.is_empty() { s.push(Shelf { id: None, title: tr!("Вам також може сподобатися"), items: r.tracks.iter().map(|t| Item::Song(song(t))).collect(), list: true }) }
    if !r.artists.is_empty() { s.push(Shelf { id: None, title: tr!("Схожі виконавці"), items: r.artists.iter().map(artist).collect(), list: false }) }
    if !r.albums.is_empty() { s.push(Shelf { id: None, title: tr!("Альбоми"), items: r.albums.iter().map(album).collect(), list: false }) }
    if !r.playlists.is_empty() { s.push(Shelf { id: None, title: tr!("Плейлисти"), items: r.playlists.iter().map(playlist).collect(), list: false }) }
    Ok(s)
}

/// Plain lyrics from YouTube Music (with the "Source: …" footer).
pub async fn lyrics(video_id: &str) -> Result<Option<(String, String)>> {
    offline()?;
    let q = rp();
    let d = q.music_details(video_id).await?;
    let Some(lid) = d.lyrics_id else { return Ok(None) };
    let l = q.music_lyrics(&lid).await?;
    Ok(Some((l.body, l.footer)))
}

/// Full song info (for opening a youtube link).
pub async fn track(video_id: &str) -> Result<Song> {
    offline()?;
    Ok(song(&rp().music_details(video_id).await?.track))
}

/// Parses a music.youtube.com / youtu.be / youtube.com link.
pub fn parse_link(text: &str) -> Option<(char, String)> {
    let re_v = regex::Regex::new(r"(?:v=|youtu\.be/)([A-Za-z0-9_-]{11})").ok()?;
    let re_l = regex::Regex::new(r"list=([A-Za-z0-9_-]+)").ok()?;
    let re_b = regex::Regex::new(r"browse/((?:MPREb|UC)[A-Za-z0-9_-]+)").ok()?;
    let re_c = regex::Regex::new(r"channel/(UC[A-Za-z0-9_-]+)").ok()?;
    if let Some(c) = re_v.captures(text) { return Some(('v', c[1].to_string())) }
    if let Some(c) = re_l.captures(text) { return Some(('p', c[1].to_string())) }
    if let Some(c) = re_b.captures(text) { let id = c[1].to_string(); return Some((if id.starts_with("UC") { 'r' } else { 'a' }, id)) }
    re_c.captures(text).map(|c| ('r', c[1].to_string()))
}


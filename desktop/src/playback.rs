//! Queue and playback control on top of the audio engine (the desktop twin of playback/Playback.kt).
use crate::audio::{self, Cmd, Engine, Event, Source};
use crate::model::Song;
use once_cell::sync::{Lazy, OnceCell};
use parking_lot::Mutex;
use rand::seq::SliceRandom;
use std::sync::atomic::{AtomicU64, Ordering::*};
use tokio::sync::watch;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct State {
    pub queue: Vec<Song>,
    pub index: usize,
    pub title: Option<String>,
    pub playing: bool,
    pub shuffle: bool,
    /// 0 = off, 1 = whole queue, 2 = this track
    pub repeat: u8,
    pub error: Option<String>,
}
impl State { pub fn current(&self) -> Option<&Song> { self.queue.get(self.index) } }

static ENGINE: OnceCell<Engine> = OnceCell::new();
static RT: OnceCell<tokio::runtime::Handle> = OnceCell::new();
pub static STATE: Lazy<watch::Sender<State>> = Lazy::new(|| watch::channel(State::default()).0);
static ORIGINAL: Lazy<Mutex<Option<Vec<Song>>>> = Lazy::new(|| Mutex::new(None));
static GEN: AtomicU64 = AtomicU64::new(0);
/// Listened time of the current play, and the position it was last counted at.
static LISTENED: AtomicU64 = AtomicU64::new(0);
static LAST_POS: AtomicU64 = AtomicU64::new(0);
static PLAY_ROW: Lazy<Mutex<Option<(i64, String)>>> = Lazy::new(|| Mutex::new(None));
static CONTROLS: OnceCell<Mutex<souvlaki::MediaControls>> = OnceCell::new();

fn engine() -> &'static Engine { ENGINE.get().expect("audio not started") }
pub fn shared() -> &'static audio::Shared { &engine().shared }
pub fn state() -> State { STATE.borrow().clone() }
fn spawn<F: std::future::Future<Output = ()> + Send + 'static>(f: F) { if let Some(rt) = RT.get() { rt.spawn(f); } }

pub fn init(rt: tokio::runtime::Handle) {
    let _ = RT.set(rt);
    let e = audio::start();
    let events = e.events.clone();
    let _ = ENGINE.set(e);
    std::thread::spawn(move || {
        while let Ok(ev) = events.recv() {
            match ev {
                Event::Ended => spawn(async { on_ended().await }),
                Event::Advanced => on_advanced(),
                Event::Error(msg) => {
                    // YouTube sometimes refuses a single request: try the same song once more before skipping it.
                    let cur = state().current().map(|s| s.id.clone());
                    if cur.is_some() && *RETRIED.lock() != cur {
                        *RETRIED.lock() = cur;
                        let pos = shared().position_ms();
                        spawn(async move { tokio::time::sleep(std::time::Duration::from_millis(400)).await; load(pos).await });
                        continue;
                    }
                    STATE.send_modify(|s| s.error = Some(msg));
                    spawn(async { tokio::time::sleep(std::time::Duration::from_millis(800)).await; next_inner(true).await })
                }
            }
        }
    });
    // Statistics: time actually listened per play (seeks and pauses don't count), saved every few seconds (local only).
    spawn(async {
        let mut n = 0u32;
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            let p = shared().position_ms();
            let last = LAST_POS.swap(p, SeqCst);
            if state().playing && p > last && p - last <= 2500 { LISTENED.fetch_add(p - last, SeqCst); }
            n += 1;
            if n % 5 == 0 { if let Some((row, _)) = PLAY_ROW.lock().clone() { crate::db::update_play(row, LISTENED.load(SeqCst) as i64) } }
        }
    });
    #[cfg(not(windows))]
    init_media_keys(None);
    restore_queue();
    spawn(preload_manager());
}

// ---------- the next song, ready before it is needed (instant switch, crossfade) ----------
static RETRIED: Lazy<Mutex<Option<String>>> = Lazy::new(Default::default);
/// (song id, fade ms) handed to the engine as the next song.
static PRELOADED: Lazy<Mutex<Option<(String, u64)>>> = Lazy::new(Default::default);
static PREFETCHING: Lazy<Mutex<Option<String>>> = Lazy::new(Default::default);
static EXTENDED_FOR: Lazy<Mutex<Option<String>>> = Lazy::new(Default::default);

fn next_song(s: &State) -> Option<Song> {
    if s.repeat == 2 { return None }
    s.queue.get(s.index + 1).cloned().or_else(|| if s.repeat == 1 { s.queue.first().cloned() } else { None })
}
fn ready_file(id: &str) -> Option<std::path::PathBuf> {
    crate::db::download_path(id).map(Into::into).or_else(|| Some(cached_file(id)).filter(|p| p.exists()))
}

/// Once a second: keeps the engine's "next song" in step with the queue, downloads it in the background while
/// the current one plays, and (with autoplay) fetches more songs before the queue runs out.
async fn preload_manager() {
    loop {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        let s = state();
        let settings = crate::settings::get();
        let started = shared().loaded.load(Relaxed) && shared().position_ms() > 3000;
        let Some(cur) = s.current().cloned() else { continue };
        // Autoplay: extend the queue early so the next song can be prepared too.
        if started && s.index + 1 >= s.queue.len() && s.repeat == 0 && settings.autoplay && !settings.offline
            && EXTENDED_FOR.lock().as_deref() != Some(cur.id.as_str()) {
            *EXTENDED_FOR.lock() = Some(cur.id.clone());
            if let Ok(radio) = crate::yt::radio(&cur.id).await {
                let known: std::collections::HashSet<String> = state().queue.iter().map(|x| x.id.clone()).collect();
                let more: Vec<Song> = radio.into_iter().filter(|x| !known.contains(&x.id) && !crate::db::is_disliked(&x.id)).collect();
                if !more.is_empty() && state().current().map(|c| c.id == cur.id).unwrap_or(false) { STATE.send_modify(|st| st.queue.extend(more)) }
            }
            continue;
        }
        let want = if s.playing { next_song(&s) } else { None };
        let mut fade = settings.crossfade_secs.min(12) as u64 * 1000;
        if let Some(n) = &want {
            // Songs of one album are meant to run into each other: join them without a gap, never blend.
            let same_album = matches!((&cur.album, &n.album), (Some(a), Some(b)) if a.id.is_some() && a.id == b.id);
            if same_album { fade = 0 }
            // A blend is never longer than half of either song.
            for d in [cur.duration, n.duration].into_iter().flatten() { fade = fade.min(d as u64 * 500) }
        }
        let have = PRELOADED.lock().clone();
        match (&want, &have) {
            (Some(n), Some((id, f))) if *id == n.id && *f == fade => continue,
            (None, None) => continue,
            (_, Some(_)) => { let _ = engine().tx.send(Cmd::ClearPreload); *PRELOADED.lock() = None; }
            _ => {}
        }
        let Some(next) = want else { continue };
        if let Some(path) = ready_file(&next.id) {
            let end_ms = cur.duration.map(|d| d as u64 * 1000).unwrap_or(0);
            let _ = engine().tx.send(Cmd::Preload { src: Source::File(path), end_ms, fade_ms: fade });
            *PRELOADED.lock() = Some((next.id.clone(), fade));
        } else if shared().loaded.load(Relaxed) && shared().position_ms() > 800 && !settings.offline && PREFETCHING.lock().is_none() && !next.id.starts_with("file:") {
            *PREFETCHING.lock() = Some(next.id.clone());
            let economy = settings.economy;
            spawn(async move {
                let path = cached_file(&next.id);
                let part = path.with_extension("part");
                let _ = std::fs::create_dir_all(path.parent().unwrap());
                let mut args = crate::audio::ytdlp_args(&next.id, economy);
                args.splice(0..0, ["-o".to_string(), part.to_string_lossy().to_string()]);
                let ok = crate::sys::tokio_command("yt-dlp").args(&args).stdin(std::process::Stdio::null())
                    .stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).status().await.map(|s| s.success()).unwrap_or(false);
                if ok && part.exists() { let _ = std::fs::rename(&part, &path); } else { let _ = std::fs::remove_file(&part); }
                *PREFETCHING.lock() = None;
            });
        }
    }
}

/// The engine moved on to the preloaded song by itself (crossfade / gapless): follow it in the queue.
fn on_advanced() {
    let Some((id, _)) = PRELOADED.lock().take() else { return };
    let s = state();
    let i = s.queue.iter().enumerate().skip(s.index + 1).find(|(_, x)| x.id == id).map(|(i, _)| i).or_else(|| s.queue.iter().position(|x| x.id == id));
    let Some(i) = i else { return };
    GEN.fetch_add(1, SeqCst);
    STATE.send_modify(|st| { st.index = i; st.playing = true; st.error = None });
    if let Some(song) = state().current().cloned() { begin(&song) }
}

/// Equalizer for what plays now: the song's own setting, else the global one (if on).
pub fn apply_eq() {
    let st = crate::settings::get();
    let id = state().current().map(|s| s.id.clone()).unwrap_or_default();
    let gains = st.eq_songs.get(&id).copied().or(if st.eq_on { Some(st.eq_gains) } else { None });
    shared().set_eq(gains);
}

/// Bookkeeping when a song starts being heard: history, statistics, media widget, saved queue, equalizer.
fn begin(song: &Song) {
    crate::db::add_history(song);
    if let Some((row, _)) = PLAY_ROW.lock().take() { crate::db::update_play(row, LISTENED.swap(0, SeqCst) as i64) }
    LISTENED.store(0, SeqCst);
    LAST_POS.store(shared().position_ms(), SeqCst);
    *PLAY_ROW.lock() = crate::db::start_play(song).map(|r| (r, song.id.clone()));
    if RETRIED.lock().as_deref() != Some(song.id.as_str()) { *RETRIED.lock() = None }
    apply_eq();
    update_controls();
    persist_queue();
    // Songs from search suggestions and some shelves come without a length: look it up, or the
    // progress bar, crossfade and media widget have nothing to work with.
    if song.duration.is_none() {
        let id = song.id.clone();
        spawn(async move {
            let Ok(full) = crate::yt::track(&id).await else { return };
            let Some(d) = full.duration else { return };
            STATE.send_modify(|s| for x in s.queue.iter_mut().filter(|x| x.id == id) {
                x.duration = Some(d);
                if x.thumbnail.is_none() { x.thumbnail = full.thumbnail.clone() }
                if x.album.is_none() { x.album = full.album.clone() }
            });
            *META_SENT.lock() = Default::default();
            update_controls();
            persist_queue();
        });
    }
}

// ---------- system media controls (MPRIS on Linux) ----------
pub fn init_media_keys(hwnd: Option<*mut std::ffi::c_void>) {
    use souvlaki::{MediaControlEvent, MediaControls, PlatformConfig};
    let cfg = PlatformConfig { dbus_name: "privacy_music", display_name: "Privacy Music", hwnd };
    let Ok(mut c) = MediaControls::new(cfg) else { return };
    let _ = c.attach(move |e| match e {
        MediaControlEvent::Play => set_playing(true),
        MediaControlEvent::Pause => set_playing(false),
        MediaControlEvent::Toggle => toggle(),
        MediaControlEvent::Next => next(),
        MediaControlEvent::Previous => previous(),
        MediaControlEvent::Stop => set_playing(false),
        MediaControlEvent::SetPosition(p) => seek(p.0.as_millis() as u64),
        _ => {}
    });
    let _ = CONTROLS.set(Mutex::new(c));
}

/// What the system media widget last got (song id, cover file): metadata is sent only when it changes.
static META_SENT: Lazy<Mutex<(String, Option<String>)>> = Lazy::new(Default::default);

/// The cover as a local file for the system media widget (Windows flyout, MPRIS). It is fetched through our own
/// firewall + image cache, so Windows / the desktop never contact Google for it.
fn cover_file(song: &Song) -> Option<std::path::PathBuf> {
    use sha2::Digest;
    let url = crate::yt::resize(song.thumbnail.as_deref()?, 544);
    Some(crate::settings::cache_dir().join("art").join(format!("{:x}.jpg", sha2::Sha256::digest(url.as_bytes()))))
}
fn fetch_cover(song: Song) {
    spawn(async move {
        let (Some(path), Some(thumb)) = (cover_file(&song), song.thumbnail.clone()) else { return };
        let url = crate::yt::resize(&thumb, 544);
        if let Some((bytes, _)) = crate::image(&urlencoding::encode(&url)).await {
            let _ = std::fs::create_dir_all(path.parent().unwrap());
            if std::fs::write(&path, bytes).is_ok() { update_controls() }
        }
    });
}

fn update_controls() {
    let Some(c) = CONTROLS.get() else { return };
    let s = state();
    let mut c = c.lock();
    if let Some(song) = s.current() {
        let file = cover_file(song).filter(|p| p.exists());
        let cover = file.map(|p| format!("file://{}", p.display()));
        let key = (song.id.clone(), cover.clone());
        if *META_SENT.lock() != key {
            let artist = song.artist_text();
            let _ = c.set_metadata(souvlaki::MediaMetadata {
                title: Some(&song.title), artist: Some(&artist), album: song.album.as_ref().map(|a| a.name.as_str()),
                cover_url: cover.as_deref(), duration: song.duration.map(|d| std::time::Duration::from_secs(d as u64)),
            });
            if cover.is_none() && META_SENT.lock().0 != song.id { fetch_cover(song.clone()) }
            *META_SENT.lock() = key;
        }
    }
    let progress = Some(souvlaki::MediaPosition(std::time::Duration::from_millis(shared().position_ms())));
    let _ = c.set_playback(if s.current().is_none() { souvlaki::MediaPlayback::Stopped } else if s.playing { souvlaki::MediaPlayback::Playing { progress } } else { souvlaki::MediaPlayback::Paused { progress } });
}

// ---------- loading ----------
fn cached_file(id: &str) -> std::path::PathBuf { crate::settings::cache_dir().join("audio").join(format!("{id}.audio")) }

async fn load(start_ms: u64) {
    let gen = GEN.fetch_add(1, SeqCst) + 1;
    let Some(song) = state().current().cloned() else { return };
    STATE.send_modify(|s| { s.playing = true; s.error = None });
    shared().paused.store(false, Relaxed);
    shared().buffering.store(true, Relaxed);
    // A fresh load replaces whatever was preloaded in the engine.
    *PRELOADED.lock() = None;
    let retry = RETRIED.lock().as_deref() == Some(song.id.as_str()) && PLAY_ROW.lock().as_ref().map(|r| r.1 == song.id).unwrap_or(false);
    if retry { apply_eq() } else { begin(&song) }
    let source = if let Some(p) = crate::db::download_path(&song.id) { Source::File(p.into()) }
        else if cached_file(&song.id).exists() { Source::File(cached_file(&song.id)) }
        else {
            let _ = std::fs::create_dir_all(cached_file("x").parent().unwrap());
            if crate::settings::get().offline {
                shared().buffering.store(false, Relaxed);
                STATE.send_modify(|s| { s.error = Some(crate::tr!("Офлайн-режим: мережу вимкнено в налаштуваннях приватності")); s.playing = false });
                return;
            }
            Source::YtDlp { video_id: song.id.clone(), economy: crate::settings::get().economy, cache_to: Some(cached_file(&song.id)) }
        };
    if GEN.load(SeqCst) != gen { return } // the user already moved on
    let _ = engine().tx.send(Cmd::Load(source, start_ms));
}

async fn on_ended() {
    let s = state();
    if s.repeat == 2 { spawn(load(0)); return }
    next_inner(false).await;
}

async fn next_inner(_after_error: bool) {
    let s = state();
    if s.index + 1 < s.queue.len() {
        STATE.send_modify(|s| s.index += 1);
        load(0).await;
    } else if s.repeat == 1 && !s.queue.is_empty() {
        STATE.send_modify(|s| s.index = 0);
        load(0).await;
    } else if crate::settings::get().autoplay {
        // Autoplay: continue with YouTube Music's radio for the last song (what the original app does).
        if let Some(last) = s.current().cloned() {
            if let Ok(radio) = crate::yt::radio(&last.id).await {
                let known: std::collections::HashSet<String> = state().queue.iter().map(|x| x.id.clone()).collect();
                let more: Vec<Song> = radio.into_iter().filter(|x| !known.contains(&x.id) && !crate::db::is_disliked(&x.id)).collect();
                if !more.is_empty() {
                    STATE.send_modify(|s| { s.queue.extend(more); s.index += 1 });
                    load(0).await;
                    return;
                }
            }
        }
        STATE.send_modify(|s| s.playing = false);
    } else { STATE.send_modify(|s| s.playing = false) }
}

// ---------- public controls (callable from the UI thread) ----------
pub fn play_queue(songs: Vec<Song>, start: usize, title: Option<String>, shuffled: bool) {
    if songs.is_empty() { return }
    let mut songs = songs;
    let mut start = start.min(songs.len() - 1);
    *ORIGINAL.lock() = None;
    if shuffled {
        *ORIGINAL.lock() = Some(songs.clone());
        songs.shuffle(&mut rand::rng());
        start = 0;
    }
    STATE.send_modify(|s| { s.queue = songs; s.index = start; s.title = title; s.shuffle = shuffled });
    spawn(load(0));
}

/// Continues a queue that was playing on another device (hand-off): same song, same position.
pub fn play_queue_at(songs: Vec<Song>, index: usize, pos_ms: u64, title: Option<String>, playing: bool) {
    if songs.is_empty() { return }
    let index = index.min(songs.len() - 1);
    *ORIGINAL.lock() = None;
    STATE.send_modify(|s| { s.queue = songs; s.index = index; s.title = title; s.shuffle = false; s.playing = false });
    if playing { spawn(load(pos_ms)) } else { *RESUME.lock() = Some(pos_ms); persist_queue() }
}

/// One song + its radio after it (what tapping a song does in YouTube Music).
pub fn play_song(song: Song) {
    let title = crate::tr!("Радіо: {0}", song.title);
    play_queue(vec![song.clone()], 0, Some(title), false);
    spawn(async move {
        if let Ok(radio) = crate::yt::radio(&song.id).await {
            STATE.send_modify(|s| {
                if s.queue.first().map(|x| x.id == song.id).unwrap_or(false) && s.queue.len() == 1 {
                    s.queue.extend(radio.into_iter().filter(|x| x.id != song.id));
                }
            });
        }
    });
}

pub fn play_radio_playlist(id: String, title: String) {
    spawn(async move { if let Ok(songs) = crate::yt::radio_playlist(&id).await { play_queue(songs, 0, Some(title), false) } });
}

pub fn play_next(songs: Vec<Song>) {
    if state().queue.is_empty() { return play_queue(songs, 0, None, false) }
    STATE.send_modify(|s| { let at = s.index + 1; for (k, x) in songs.into_iter().enumerate() { s.queue.insert(at + k, x) } });
    persist_queue();
}
pub fn add_to_queue(songs: Vec<Song>) {
    if state().queue.is_empty() { return play_queue(songs, 0, None, false) }
    STATE.send_modify(|s| s.queue.extend(songs));
    persist_queue();
}
pub fn remove_at(i: usize) {
    let s = state();
    if i == s.index || i >= s.queue.len() { return }
    STATE.send_modify(|s| { s.queue.remove(i); if i < s.index { s.index -= 1 } });
    persist_queue();
}
pub fn clear_upcoming() { STATE.send_modify(|s| { let keep = s.index + 1; s.queue.truncate(keep) }); persist_queue() }
pub fn skip_to(i: usize) { if i < state().queue.len() { STATE.send_modify(|s| s.index = i); spawn(load(0)) } }
pub fn move_item(from: usize, to: usize) {
    STATE.send_modify(|s| {
        if from >= s.queue.len() || to >= s.queue.len() { return }
        let x = s.queue.remove(from); s.queue.insert(to, x);
        if s.index == from { s.index = to } else if from < s.index && to >= s.index { s.index -= 1 } else if from > s.index && to <= s.index { s.index += 1 }
    });
}

pub fn toggle() { set_playing(!state().playing) }
pub fn set_playing(on: bool) {
    let s = state();
    if s.current().is_none() { return }
    if on && !shared().loaded.load(Relaxed) && !shared().buffering.load(Relaxed) {
        // Nothing loaded (restored queue, or the track ended): start it.
        let pos = RESUME.lock().take().unwrap_or(0);
        STATE.send_modify(|s| s.playing = true);
        spawn(load(pos));
        return;
    }
    shared().paused.store(!on, Relaxed);
    let _ = engine().tx.send(Cmd::Pause(!on));
    STATE.send_modify(|s| s.playing = on);
    update_controls();
}
pub fn next() { spawn(async { let s = state(); if s.index + 1 < s.queue.len() || s.repeat == 1 || crate::settings::get().autoplay { next_inner(false).await } }) }
pub fn previous() {
    if shared().position_ms() > 3000 || state().index == 0 { seek(0); return }
    STATE.send_modify(|s| s.index -= 1);
    spawn(load(0));
}
pub fn seek(ms: u64) { let _ = engine().tx.send(Cmd::Seek(ms)); update_controls() }
/// The user picked another output device in Settings ("" = follow the system).
pub fn set_output(name: String) { crate::settings::update(|s| s.audio_device = name); let _ = engine().tx.send(Cmd::Device); }
pub fn set_volume(v: f32) { shared().set_volume(v); crate::settings::update(|s| s.volume = v) }

pub fn toggle_shuffle() {
    let s = state();
    if !s.shuffle {
        // Physically shuffle what comes after the current song, so "Up next" shows the real order.
        *ORIGINAL.lock() = Some(s.queue.clone());
        STATE.send_modify(|st| { let mut rest = st.queue.split_off(st.index + 1); rest.shuffle(&mut rand::rng()); st.queue.extend(rest); st.shuffle = true });
    } else {
        let orig = ORIGINAL.lock().take();
        STATE.send_modify(|st| {
            st.shuffle = false;
            if let (Some(orig), Some(cur)) = (orig, st.current().cloned()) {
                let extra: Vec<Song> = st.queue.iter().filter(|x| !orig.iter().any(|o| o.id == x.id)).cloned().collect();
                let mut q = orig; q.extend(extra);
                st.index = q.iter().position(|x| x.id == cur.id).unwrap_or(0);
                st.queue = q;
            }
        });
    }
    persist_queue();
}
pub fn cycle_repeat() { STATE.send_modify(|s| s.repeat = (s.repeat + 1) % 3) }

pub fn start_radio(song: Song) { play_song(song) }

// ---------- queue persistence ----------
static RESUME: Lazy<Mutex<Option<u64>>> = Lazy::new(|| Mutex::new(None));
fn queue_file() -> std::path::PathBuf { crate::settings::data_dir().join("queue.json") }
pub fn persist_queue() {
    if !crate::settings::get().persist_queue { let _ = std::fs::remove_file(queue_file()); return }
    let s = state();
    let v = serde_json::json!({ "queue": s.queue, "index": s.index, "title": s.title, "pos": shared().position_ms() });
    let _ = std::fs::write(queue_file(), v.to_string());
}
fn restore_queue() {
    if !crate::settings::get().persist_queue { return }
    let Ok(t) = std::fs::read_to_string(queue_file()) else { return };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&t) else { return };
    let queue: Vec<Song> = serde_json::from_value(v["queue"].clone()).unwrap_or_default();
    if queue.is_empty() { return }
    *RESUME.lock() = v["pos"].as_u64();
    STATE.send_modify(|s| { s.index = (v["index"].as_u64().unwrap_or(0) as usize).min(queue.len() - 1); s.queue = queue; s.title = v["title"].as_str().map(String::from); s.playing = false });
}
pub fn forget_queue_file() { let _ = std::fs::remove_file(queue_file()); }

/// Called every second by the UI so MPRIS shows the right position, and the queue file stays fresh.
pub fn tick() { update_controls(); }
/// Stores the listened time of what plays now (before quitting).
pub fn save_play() { if let Some((row, _)) = PLAY_ROW.lock().clone() { crate::db::update_play(row, LISTENED.load(SeqCst) as i64) } }

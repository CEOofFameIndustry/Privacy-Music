//! Download queue: one song at a time through yt-dlp, with live progress for the Downloads page.
use super::*;
use crate::db;
use crate::model::Song;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::SeqCst};

#[derive(Clone, Debug, PartialEq)]
pub enum DlState { Queued, /** percent 0–100 */ Running(f32), Failed }

static QUEUE: Lazy<Mutex<Vec<(Song, DlState)>>> = Lazy::new(Default::default);
static VER: AtomicU64 = AtomicU64::new(0);
static WORKING: AtomicBool = AtomicBool::new(false);
/// Song id whose running download should stop.
static CANCEL: Lazy<Mutex<Option<String>>> = Lazy::new(Default::default);

/// What the UI shows (mirrored from the queue by `sync`).
pub static DOWNLOADS: GlobalSignal<Vec<(Song, DlState)>> = Signal::global(Vec::new);

fn changed() { VER.fetch_add(1, SeqCst); }

/// Called from the UI's clock: copies the queue into `DOWNLOADS` when it changed.
pub fn sync() {
    static SEEN: AtomicU64 = AtomicU64::new(0);
    let v = VER.load(SeqCst);
    if SEEN.swap(v, SeqCst) != v { *DOWNLOADS.write() = QUEUE.lock().clone() }
}

pub fn download(songs: Vec<Song>) {
    let mut added = 0;
    {
        let mut q = QUEUE.lock();
        for s in songs {
            if s.id.starts_with("file:") || db::download_path(&s.id).is_some() { continue }
            if let Some(e) = q.iter_mut().find(|e| e.0.id == s.id) { if e.1 == DlState::Failed { e.1 = DlState::Queued; added += 1 } continue }
            q.push((s, DlState::Queued));
            added += 1;
        }
    }
    changed();
    if added == 0 { toast(tr!("Ці пісні вже завантажено")); return }
    toast(tr!("Завантаження: {0} треків", added));
    if !WORKING.swap(true, SeqCst) { spawn_bg(worker()) }
}

/// Removes a song from the queue (stops it if it is downloading now).
pub fn cancel(id: &str) {
    let mut q = QUEUE.lock();
    if q.iter().any(|e| e.0.id == id && matches!(e.1, DlState::Running(_))) { *CANCEL.lock() = Some(id.to_string()) }
    q.retain(|e| e.0.id != id);
    drop(q);
    changed();
}
pub fn retry(id: &str) {
    let s = QUEUE.lock().iter().find(|e| e.0.id == id).map(|e| e.0.clone());
    if let Some(s) = s { download(vec![s]) }
}
pub fn clear_failed() { QUEUE.lock().retain(|e| e.1 != DlState::Failed); changed() }
pub fn cancel_all() {
    let mut q = QUEUE.lock();
    if let Some(e) = q.iter().find(|e| matches!(e.1, DlState::Running(_))) { *CANCEL.lock() = Some(e.0.id.clone()) }
    q.clear();
    drop(q);
    changed();
}

fn set_state(id: &str, st: DlState) {
    if let Some(e) = QUEUE.lock().iter_mut().find(|e| e.0.id == id) { e.1 = st }
    changed();
}

async fn worker() {
    let mut done = 0;
    loop { loop {
        let next = QUEUE.lock().iter().find(|e| e.1 == DlState::Queued).map(|e| e.0.clone());
        let Some(s) = next else { break };
        set_state(&s.id, DlState::Running(0.0));
        if fetch(&s).await {
            QUEUE.lock().retain(|e| e.0.id != s.id);
            changed();
            done += 1;
        } else if QUEUE.lock().iter().any(|e| e.0.id == s.id) {
            set_state(&s.id, DlState::Failed);
            toast_from_bg(tr!("Не вдалося завантажити «{0}»", s.title));
        }
    }
    WORKING.store(false, SeqCst);
    // A download added right as the loop ended.
    if !(QUEUE.lock().iter().any(|e| e.1 == DlState::Queued) && !WORKING.swap(true, SeqCst)) { break }
    }
    if done > 0 { toast_from_bg(tr!("Завантажено: {0}", done)) }
}

async fn fetch(s: &Song) -> bool {
    use tokio::io::AsyncBufReadExt;
    let dir = crate::settings::download_dir();
    if std::fs::create_dir_all(&dir).is_err() { return false }
    let path = dir.join(format!("{}.webm", s.id));
    let part = dir.join(format!("{}.webm.part", s.id));
    let economy = { let st = crate::settings::get(); st.dl_economy };
    let mut args: Vec<String> = crate::audio::ytdlp_args(&s.id, economy).into_iter().filter(|a| a != "--no-progress").collect();
    args.splice(0..0, ["-o".to_string(), path.to_string_lossy().to_string(), "--progress".into(), "--newline".into(),
        "--progress-template".into(), "download:PMPROG %(progress._percent_str)s".into()]);
    let Ok(mut child) = crate::sys::tokio_command("yt-dlp").args(&args).stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null()).spawn() else { return false };
    let mut lines = tokio::io::BufReader::new(child.stdout.take().unwrap()).lines();
    let mut last = -1f32;
    loop {
        let line = tokio::select! {
            l = lines.next_line() => l,
            _ = tokio::time::sleep(std::time::Duration::from_millis(300)) => {
                if CANCEL.lock().as_deref() == Some(s.id.as_str()) { break } else { continue }
            }
        };
        match line {
            Ok(Some(l)) => if let Some(p) = l.split("PMPROG").nth(1).and_then(|r| r.trim().trim_end_matches('%').trim().parse::<f32>().ok()) {
                // Only whole-percent steps reach the UI.
                if p.floor() != last { last = p.floor(); set_state(&s.id, DlState::Running(p.min(100.0))) }
            },
            _ => break,
        }
    }
    if CANCEL.lock().as_deref() == Some(s.id.as_str()) {
        *CANCEL.lock() = None;
        let _ = child.kill().await;
        let _ = std::fs::remove_file(&part);
        return false;
    }
    let ok = child.wait().await.map(|st| st.success()).unwrap_or(false);
    if ok && path.exists() { db::set_download(s, &path.to_string_lossy()); true } else { false }
}

/// Size of all downloaded files, in bytes.
pub fn used_bytes() -> u64 {
    db::downloads().iter().filter_map(|s| db::download_path(&s.id)).filter_map(|p| std::fs::metadata(p).ok()).map(|m| m.len()).sum()
}

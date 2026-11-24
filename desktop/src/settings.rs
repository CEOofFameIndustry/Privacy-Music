//! All settings in one JSON file in the user's data directory. Nothing is synced anywhere.
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Settings {
    pub ui_language: String,
    /// "ytm" (YouTube Music-like) or "minimal" (clean, Apple Music-like).
    pub ui_style: String,
    pub theme_bg: u32,
    pub theme_accent: u32,
    pub history: bool,
    pub search_history: bool,
    pub search_suggestions: bool,
    pub lrclib: bool,
    pub lrclib_hint_hidden: bool,
    pub lyrics_flow: bool,
    pub offline: bool,
    pub stats: bool,
    pub autoplay: bool,
    pub persist_queue: bool,
    pub volume: f32,
    /// A picture behind the interface (stored as data_dir/background) and how much it is darkened (0–90 %).
    pub theme_image: bool,
    #[serde(default = "dim_default")]
    pub theme_dim: u32,
    /// Bumped when the picture changes, so the view reloads it.
    pub theme_image_v: u64,
    /// Close button / shortcut hides the window to the tray instead of quitting.
    #[serde(default = "yes")]
    pub close_to_tray: bool,
    /// GPU rendering of the interface: "auto" (off on NVIDIA, where WebKitGTK is slow), "on", "off". Needs a restart.
    #[serde(default = "auto")]
    pub gpu: String,
    /// Interface scale in percent.
    #[serde(default = "hundred")]
    pub ui_scale: u32,
    /// Lower bitrate to save data.
    pub economy: bool,
    pub hl: String,
    pub gl: String,
    pub layout_home: String,
    pub layout_library: String,
    pub layout_search: String,
    pub layout_tabs: String,
    /// Devices nearby: encrypted link to the user's own phone over the local network (off = nothing is sent or listened for).
    #[serde(default)]
    pub link: bool,
    /// Sync likes, playlists and saved albums / artists with paired devices.
    #[serde(default = "yes")]
    pub link_sync: bool,
    /// Paired devices may control playback here (play / pause / skip / volume / hand-off).
    #[serde(default = "yes")]
    pub link_control: bool,
    /// Starting music here pauses the paired device that is playing (one device plays at a time).
    #[serde(default = "yes")]
    pub link_single: bool,
    /// Lyrics timing nudge per audio output (ms, + = lyrics later), e.g. Bluetooth headphones add their own delay.
    #[serde(default)]
    pub lyrics_offsets: std::collections::HashMap<String, i32>,
    /// Blend songs into each other for this many seconds (0 = gapless, no blend).
    #[serde(default = "four")]
    pub crossfade_secs: u32,
    /// Equalizer for all songs (dB per band, see audio::EQ_FREQS), and its own setting for single songs.
    #[serde(default)]
    pub eq_on: bool,
    #[serde(default)]
    pub eq_gains: [f32; 10],
    #[serde(default)]
    pub eq_songs: std::collections::HashMap<String, [f32; 10]>,
    /// Downloads: smaller files (lower bitrate), and the folder they go to (empty = the app's data folder).
    #[serde(default)]
    pub dl_economy: bool,
    #[serde(default)]
    pub dl_dir: String,
    /// Blur of the background picture in px (0 = sharp).
    #[serde(default = "blur_default")]
    pub theme_blur: u32,
    /// Audio output device name ("" = follow the system default, also when headphones are plugged in / out).
    #[serde(default)]
    pub audio_device: String,
    /// Ask GitHub (api.github.com) once in a while whether a newer version was released, and say so.
    #[serde(default = "yes")]
    pub update_check: bool,
    /// The version the user chose to skip in the update notice.
    #[serde(default)]
    pub update_skipped: String,
    /// Sorting per song list ("local:ID", "liked", "downloads", "pl:ID"…): "custom", "recent", "title", "artist", "album", "duration" (+ ":desc").
    #[serde(default)]
    pub list_sort: std::collections::HashMap<String, String>,
    /// Compact song rows (no cover, one line).
    #[serde(default)]
    pub list_compact: bool,
    /// Imported files without artwork: look the song up on YouTube Music and use its cover.
    #[serde(default = "yes")]
    pub auto_covers: bool,
    /// Settings format version (for one-time migrations).
    #[serde(default)] // missing in old files → 0 → migrated below
    pub version: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            ui_language: "system".into(), ui_style: "ytm".into(), theme_bg: 0x000000, theme_accent: 0xfa2d48,
            history: true, search_history: true, search_suggestions: true, lrclib: false, lrclib_hint_hidden: false,
            lyrics_flow: true, offline: false, stats: true, autoplay: true, persist_queue: true, volume: 0.8, ui_scale: 100, theme_image: false, theme_dim: 55, theme_image_v: 0, close_to_tray: true, gpu: "auto".into(), economy: false, dl_economy: false, dl_dir: String::new(),
            hl: crate::i18n::system_lang(), gl: "US".into(),
            layout_home: String::new(), layout_library: String::new(), layout_search: String::new(), layout_tabs: String::new(), link: false, link_sync: true, link_control: true, link_single: true, lyrics_offsets: Default::default(), crossfade_secs: 4, eq_on: false, eq_gains: [0.0; 10], eq_songs: Default::default(), version: 2,
            theme_blur: 16, audio_device: String::new(), update_check: true, update_skipped: String::new(), list_sort: Default::default(), list_compact: false, auto_covers: true,
        }
    }
}

fn hundred() -> u32 { 100 }
/// Where new downloads are saved.
pub fn download_dir() -> std::path::PathBuf {
    let d = get().dl_dir;
    if d.is_empty() { data_dir().join("downloads") } else { std::path::PathBuf::from(d) }
}
fn dim_default() -> u32 { 55 }
fn blur_default() -> u32 { 16 }
fn yes() -> bool { true }
fn four() -> u32 { 4 }
fn auto() -> String { "auto".into() }

pub fn background_file() -> PathBuf { data_dir().join("background") }

pub fn data_dir() -> PathBuf {
    let dir = directories::ProjectDirs::from("", "", "privacy-music").map(|d| d.data_dir().to_path_buf())
        .unwrap_or_else(|| PathBuf::from(".privacy-music"));
    let _ = std::fs::create_dir_all(&dir);
    dir
}

pub fn cache_dir() -> PathBuf {
    let dir = directories::ProjectDirs::from("", "", "privacy-music").map(|d| d.cache_dir().to_path_buf())
        .unwrap_or_else(|| PathBuf::from(".privacy-music-cache"));
    let _ = std::fs::create_dir_all(&dir);
    dir
}

static SETTINGS: Lazy<RwLock<Settings>> = Lazy::new(|| {
    let mut s: Settings = std::fs::read_to_string(data_dir().join("settings.json")).ok()
        .and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
    // v2: one design on the desktop; the old default white accent becomes the new default.
    if s.version < 2 { if s.theme_accent == 0xffffff { s.theme_accent = 0xfa2d48 } s.ui_style = "minimal".into(); s.version = 2; }
    RwLock::new(s)
});

pub fn get() -> Settings { SETTINGS.read().clone() }

pub fn update(f: impl FnOnce(&mut Settings)) {
    let mut s = SETTINGS.write();
    f(&mut s);
    if let Ok(t) = serde_json::to_string_pretty(&*s) { let _ = std::fs::write(data_dir().join("settings.json"), t); }
}

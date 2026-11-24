//! Desktop UI (Dioxus): one clean design in the spirit of Apple Music for Mac, adapted to a big screen.
pub mod components;
pub mod dl;
pub mod icon_map;
pub mod pages;
pub mod player;
pub mod settings_ui;

use crate::model::{Item, Song};
use crate::playback;
use crate::tr;
use dioxus::prelude::*;
use once_cell::sync::OnceCell;

// ---------- background runtime bridge ----------
static RT: OnceCell<tokio::runtime::Handle> = OnceCell::new();
pub fn set_runtime(h: tokio::runtime::Handle) { let _ = RT.set(h); }
/// Runs a future on the app's background runtime (network, files) and awaits it from the UI.
pub async fn bg<T: Send + 'static>(f: impl std::future::Future<Output = T> + Send + 'static) -> T {
    RT.get().expect("runtime").spawn(f).await.expect("background task")
}
/// Starts work on the background runtime that outlives the component that started it (e.g. a closing menu).
pub fn spawn_bg(f: impl std::future::Future<Output = ()> + Send + 'static) { if let Some(rt) = RT.get() { rt.spawn(f); } }
pub async fn sleep_ms(ms: u64) { bg(tokio::time::sleep(std::time::Duration::from_millis(ms))).await }

/// Artwork goes through our own `pmimg:` protocol, i.e. through the firewall and the local image cache.
#[cfg(windows)]
const IMAGE_ORIGIN: &str = "http://pmimg.localhost";
#[cfg(not(windows))]
const IMAGE_ORIGIN: &str = "pmimg://localhost";
pub fn img(url: Option<&str>) -> String {
    match url { Some(u) if !u.is_empty() => format!("{IMAGE_ORIGIN}/{}", urlencoding::encode(u)), _ => String::new() }
}

// ---------- navigation ----------
#[derive(Clone, Debug, PartialEq)]
pub enum Route {
    Home, Search, Results(String), Library, Album(String), Playlist(String), Artist(String), Local(i64),
    Liked, Downloads, History, Settings, Privacy, Stats, Import, Appearance, Support,
    /// A library section: "playlists", "albums", "artists".
    LibSection(String),
    /// Move data from the phone (its encrypted backup).
    Transfer,
}

pub static STACK: GlobalSignal<Vec<Route>> = Signal::global(|| vec![start_route()]);

/// PM_START=<route> opens a page at launch (used for testing / screenshots).
fn start_route() -> Route {
    let v = std::env::var("PM_START").unwrap_or_default();
    let (k, arg) = v.split_once(':').unwrap_or((v.as_str(), ""));
    match k {
        "search" => Route::Search, "results" => Route::Results(arg.into()), "library" => Route::Library, "settings" => Route::Settings,
        "album" => Route::Album(arg.into()), "playlist" => Route::Playlist(arg.into()), "local" => Route::Local(arg.parse().unwrap_or(1)), "liked" => Route::Liked, "artist" => Route::Artist(arg.into()),
        "privacy" => Route::Privacy, "support" => Route::Support, "import" => Route::Import, "transfer" => Route::Transfer, "albums" => Route::LibSection("albums".into()), "appearance" => Route::Appearance, "stats" => Route::Stats,
        _ => Route::Home,
    }
}
pub fn route() -> Route { STACK.read().last().cloned().unwrap_or(Route::Home) }
pub fn go(r: Route) { if route() != r { STACK.write().push(r) } }
pub fn back() { let mut s = STACK.write(); if s.len() > 1 { s.pop(); } }
/// Top-level tab: resets the stack.
pub fn tab(r: Route) { *STACK.write() = vec![r.clone()]; if r == Route::Search { *FOCUS_SEARCH.write() = true } }
pub fn current_tab() -> Route {
    match STACK.read().first().cloned().unwrap_or(Route::Home) { Route::Search | Route::Results(_) => Route::Search, Route::Library => Route::Library, _ => Route::Home }
}

// ---------- global UI state ----------
pub static PSTATE: GlobalSignal<playback::State> = Signal::global(playback::State::default);
pub static POS: GlobalSignal<u64> = Signal::global(|| 0);
pub static BUFFERING: GlobalSignal<bool> = Signal::global(|| false);
pub static LIBV: GlobalSignal<u64> = Signal::global(|| 0);
pub static SETTINGS: GlobalSignal<crate::settings::Settings> = Signal::global(crate::settings::get);
pub static MENU: GlobalSignal<Option<MenuReq>> = Signal::global(|| None);
pub static TOAST: GlobalSignal<Option<String>> = Signal::global(|| None);
pub static PANEL: GlobalSignal<Option<player::Tab>> = Signal::global(|| None);
/// Full-screen player.
pub static FULL: GlobalSignal<bool> = Signal::global(|| false);
pub static ADD_TO: GlobalSignal<Option<Vec<Song>>> = Signal::global(|| None);
pub static FOCUS_SEARCH: GlobalSignal<bool> = Signal::global(|| false);
pub static QUERY: GlobalSignal<String> = Signal::global(String::new);
/// Devices nearby (link.rs) as the UI sees them.
pub static LINK: GlobalSignal<crate::link::View> = Signal::global(crate::link::View::default);
/// The devices pop-up above the player bar.
pub static DEVICES: GlobalSignal<bool> = Signal::global(|| false);
/// The equalizer pop-up above the player bar.
pub static EQ_OPEN: GlobalSignal<bool> = Signal::global(|| false);
/// Settings section to open (e.g. "devices" from the devices pop-up).
pub static SETTINGS_SECTION: GlobalSignal<Option<&'static str>> = Signal::global(|| None);
/// A newer release on GitHub (update notice in the sidebar).
pub static UPDATE: GlobalSignal<Option<crate::update::Release>> = Signal::global(|| None);
/// Cover lookup for imported files: (done, total).
pub static COVERS: GlobalSignal<Option<(usize, usize)>> = Signal::global(|| None);

/// Messages from background tasks (e.g. "continued from the phone"), shown as toasts by the UI.
static BG_TOASTS: once_cell::sync::Lazy<tokio::sync::watch::Sender<(u64, String)>> = once_cell::sync::Lazy::new(|| tokio::sync::watch::channel((0, String::new())).0);
pub fn toast_from_bg(msg: String) { BG_TOASTS.send_modify(|t| { t.0 += 1; t.1 = msg }) }

#[derive(Clone, Debug, PartialEq)]
pub struct MenuReq { pub item: Item, pub queue_index: Option<usize>, pub local_playlist: Option<(i64, usize)>, pub x: f64, pub y: f64 }

pub fn set(f: impl FnOnce(&mut crate::settings::Settings)) {
    crate::settings::update(f);
    *SETTINGS.write() = crate::settings::get();
}

pub fn toast(msg: impl Into<String>) {
    let m = msg.into();
    *TOAST.write() = Some(m.clone());
    // Not tied to the component that showed it (a closed dialog would cancel the timer and the toast would stay).
    dioxus::dioxus_core::spawn_forever(async move { sleep_ms(2600).await; if TOAST.read().as_deref() == Some(m.as_str()) { *TOAST.write() = None } });
}

/// What tapping an item does.
pub fn open(item: &Item, context: Option<(Vec<Song>, Option<String>)>) {
    match item {
        Item::Song(s) => match context {
            Some((songs, title)) => { let i = songs.iter().position(|x| x.id == s.id).unwrap_or(0); playback::play_queue(songs, i, title, false) }
            None => playback::play_song(s.clone()),
        },
        Item::Album { id, .. } => go(Route::Album(id.clone())),
        Item::Playlist { id, .. } => go(Route::Playlist(id.clone())),
        Item::Artist { id, .. } => go(Route::Artist(id.clone())),
        Item::Local { id, .. } => go(Route::Local(*id)),
    }
    if !matches!(item, Item::Song(_)) { *FULL.write() = false }
}

const CSS: &str = include_str!("../../assets/style.css");

fn hex(c: u32) -> String { format!("#{:06x}", c & 0xffffff) }
fn luminance(c: u32) -> f32 { let (r, g, b) = ((c >> 16 & 255) as f32, (c >> 8 & 255) as f32, (c & 255) as f32); (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0 }

#[component]
pub fn App() -> Element {
    #[cfg(windows)]
    use_hook(|| {
        use dioxus::desktop::tao::platform::windows::WindowExtWindows;
        playback::init_media_keys(Some(dioxus::desktop::window().hwnd() as *mut _));
    });
    #[cfg(target_os = "linux")]
    use_hook(|| {
        use dioxus::desktop::wry::WebViewExtUnix;
        use webkit2gtk::{HardwareAccelerationPolicy, SettingsExt, WebViewExt};
        let policy = match crate::settings::get().gpu.as_str() {
            "on" => Some(HardwareAccelerationPolicy::Always),
            "off" => Some(HardwareAccelerationPolicy::Never),
            _ => None,
        };
        let view = dioxus::desktop::window().webview.webview();
        if let Some(settings) = view.settings() {
            if let Some(p) = policy { settings.set_hardware_acceleration_policy(p); }
            // A mouse-wheel notch glides instead of jumping a whole step.
            settings.set_enable_smooth_scrolling(true);
        }
    });
    // Tray: the close button / shortcut hides the window (music keeps playing); the tray menu brings it back.
    use_hook(|| {
        use dioxus::desktop::trayicon::menu::{Menu, MenuItem, PredefinedMenuItem};
        let menu = Menu::new();
        let _ = menu.append_items(&[
            &MenuItem::with_id("show", tr!("Показати Privacy Music"), true, None),
            &MenuItem::with_id("toggle", tr!("Відтворити/пауза"), true, None),
            &MenuItem::with_id("next", tr!("Наступна"), true, None),
            &PredefinedMenuItem::separator(),
            &MenuItem::with_id("quit", tr!("Закрити Privacy Music"), true, None),
        ]);
        let icon = dioxus::desktop::icon_from_memory(include_bytes!("../../assets/icon-64.png")).ok();
        dioxus::desktop::trayicon::init_tray_icon(menu, icon);
    });
    // Dioxus registers the shared muda menu dispatcher before the tray dispatcher.
    dioxus::desktop::use_muda_event_handler(|e| match e.id.0.as_str() {
        "show" => { let w = dioxus::desktop::window(); w.set_visible(true); w.set_focus(); }
        "toggle" => playback::toggle(),
        "next" => playback::next(),
        "quit" => quit(),
        _ => {}
    });
    let close_to_tray = SETTINGS.read().close_to_tray;
    use_effect(use_reactive!(|close_to_tray| {
        dioxus::desktop::window().set_close_behavior(if close_to_tray { dioxus::desktop::WindowCloseBehaviour::WindowHides } else { dioxus::desktop::WindowCloseBehaviour::WindowCloses });
    }));
    use_hook(|| {
        // PM_PANEL=queue|lyrics opens the now-playing view at launch (testing / screenshots).
        if std::env::var("PM_AUTOPLAY").is_ok() { playback::set_playing(true) }
        // PM_TEST_DOWNLOAD=<video id>: downloads that song at launch (testing).
        if let Ok(id) = std::env::var("PM_TEST_DOWNLOAD") { player::download(vec![Song { id, title: "test".into(), artists: vec![], album: None, duration: None, thumbnail: None }]) }
        // PM_SETTINGS_SECTION=devices opens Settings at that section, the same way the devices pop-up does (testing).
        if let Ok(s) = std::env::var("PM_SETTINGS_SECTION") { *SETTINGS_SECTION.write() = Some(Box::leak(s.into_boxed_str())); go(Route::Settings) }
        // PM_TEST_JS="<js>;;<js>…": runs each script in the page 8 s after start, 2.5 s apart (testing only: clicks etc.).
        if let Ok(js) = std::env::var("PM_TEST_JS") {
            spawn(async move { sleep_ms(8000).await; for part in js.split(";;") { document::eval(part); sleep_ms(2500).await; } });
        }
        // PM_BENCH_SHEET=1: opens / closes the player page a few times and prints frame times (testing only).
        if std::env::var("PM_BENCH_SHEET").is_ok() {
            spawn(async {
                sleep_ms(9000).await;
                for round in 0..4 {
                    for open in [true, false] {
                        let mut rec = document::eval("let d=[],l=performance.now(),t0=l;function f(n){d.push(n-l);l=n;if(n-t0<900)requestAnimationFrame(f);else dioxus.send(JSON.stringify({n:d.length,max:Math.max(...d).toFixed(1),avg:(d.reduce((a,b)=>a+b,0)/d.length).toFixed(1),over20:d.filter(x=>x>20).length,long:d.map((x,i)=>x>30?i+'@'+Math.round(d.slice(0,i+1).reduce((a,b)=>a+b,0)):null).filter(Boolean).join(' ')}))}requestAnimationFrame(f);");
                        sleep_ms(40).await;
                        *FULL.write() = open;
                        if let Ok(v) = rec.recv::<String>().await { println!("bench round {round} {}: {v}", if open { "open " } else { "close" }); }
                        sleep_ms(1200).await;
                    }
                }
            });
        }
        match std::env::var("PM_PANEL").as_deref() { Ok("lyrics") => *PANEL.write() = Some(player::Tab::Lyrics), Ok("queue") => *PANEL.write() = Some(player::Tab::Queue), Ok("full") => *FULL.write() = true, _ => {} }
        spawn(async {
            let mut rx = playback::STATE.subscribe();
            loop { *PSTATE.write() = rx.borrow_and_update().clone(); match bg(async move { rx.changed().await.map(|_| rx) }).await { Ok(r) => rx = r, Err(_) => break } }
        });
        spawn_bg(crate::update::run());
        // Which audio output is in use (for the per-output lyrics offset).
        spawn(async { loop { bg(async { tokio::task::spawn_blocking(player::refresh_output).await.ok(); }).await; sleep_ms(15_000).await } });
        spawn(async {
            let mut rx = crate::link::VIEW.subscribe();
            loop { *LINK.write() = rx.borrow_and_update().clone(); match bg(async move { rx.changed().await.map(|_| rx) }).await { Ok(r) => rx = r, Err(_) => break } }
        });
        spawn(async {
            let mut rx = BG_TOASTS.subscribe();
            rx.borrow_and_update();
            loop { match bg(async move { rx.changed().await.map(|_| rx) }).await { Ok(mut r) => { let m = r.borrow_and_update().1.clone(); toast(m); rx = r } Err(_) => break } }
        });
        spawn(async {
            let mut rx = crate::db::VERSION.subscribe();
            loop { *LIBV.write() = *rx.borrow_and_update(); match bg(async move { rx.changed().await.map(|_| rx) }).await { Ok(r) => rx = r, Err(_) => break } }
        });
        spawn(async {
            let mut n = 0u32;
            loop {
                sleep_ms(250).await;
                let sh = playback::shared();
                let p = sh.position_ms();
                if *POS.peek() != p { *POS.write() = p }
                let b = sh.buffering.load(std::sync::atomic::Ordering::Relaxed);
                if *BUFFERING.peek() != b { *BUFFERING.write() = b }
                dl::sync();
                // The app was started again: show this window instead of a second one.
                if crate::instance::take_show() { let w = dioxus::desktop::window(); w.set_visible(true); w.set_minimized(false); w.set_focus(); }
                let c = *crate::covers::PROGRESS.lock();
                if *COVERS.peek() != c { *COVERS.write() = c }
                if n % 20 == 0 {
                    let u = crate::update::AVAILABLE.lock().clone().filter(|r| SETTINGS.peek().update_check && SETTINGS.peek().update_skipped != r.version);
                    if *UPDATE.peek() != u { *UPDATE.write() = u }
                }
                n += 1;
                if n % 4 == 0 {
                    bg(async move {
                        playback::tick();
                        if n % 40 == 0 { playback::persist_queue() }
                    }).await;
                }
            }
        });
    });
    let s = SETTINGS.read().clone();
    let accent = hex(s.theme_accent);
    let on_accent = if luminance(s.theme_accent) > 0.55 { "#000" } else { "#fff" };
    // A light background (white or any pale colour) gets dark text: the light theme.
    let light = luminance(s.theme_bg) > 0.5;
    let vars = format!("--bg:{};--fg:{};color-scheme:{};--accent:{};--on-accent:{};", hex(s.theme_bg), if light { "#141414" } else { "#ffffff" }, if light { "light" } else { "dark" }, accent, on_accent);
    let r = route();
    let panel = *PANEL.read();
    // Interface scale (Settings → General): zooms the whole web view, so layouts reflow instead of blurring.
    let scale = s.ui_scale;
    use_effect(use_reactive!(|scale| { let _ = dioxus::desktop::window().webview.zoom(scale.clamp(50, 300) as f64 / 100.0); }));
    // Blur is drawn once (the picture never moves), scaled up a little so the soft edges stay off-screen.
    let blur = s.theme_blur.min(60);
    // A blurred copy is made once by the app (main.rs image()), so nothing is re-blurred while drawing.
    let bg_style = format!("background-image:url('{IMAGE_ORIGIN}/__bg{}b{blur}')", s.theme_image_v);
    let dim = s.theme_dim.min(90) as f32 / 100.0;
    // The picture sits outside `.app`, where the theme variables don't reach: give the dimming layer its colour directly.
    let bg_hex = hex(s.theme_bg);
    rsx! {
        style { {CSS} }
        if s.theme_image { div { class: "app-bg", style: "{bg_style}", div { class: "app-bg-dim", style: "opacity:{dim};background:{bg_hex}" } } }
        div { class: match (s.theme_image, light) { (true, true) => "app has-bg light", (true, false) => "app has-bg", (false, true) => "app light", _ => "app" }, style: "{vars}", tabindex: "0",
            onkeydown: move |e: KeyboardEvent| {
                // Space = play / pause, unless typing in a field.
                if e.key() == Key::Character(" ".into()) && !e.modifiers().ctrl() { player::space_pressed() }
                if e.modifiers().ctrl() && e.key() == Key::Character("q".into()) { quit() }
                if e.key() == Key::Escape { if *FULL.peek() { *FULL.write() = false } else if MENU.peek().is_some() { *MENU.write() = None } }
            },
            components::Sidebar {}
            player::PlayerBar {}
            main { class: "content",
                match r {
                    Route::Home => rsx! { pages::Home {} },
                    Route::Search => rsx! { pages::Search {} },
                    Route::Results(q) => rsx! { pages::Results { query: q } },
                    Route::Library => rsx! { pages::Library {} },
                    Route::LibSection(k) => rsx! { pages::LibSection { kind: k } },
                    Route::Album(id) => rsx! { pages::Collection { id, album: true } },
                    Route::Playlist(id) => rsx! { pages::Collection { id, album: false } },
                    Route::Artist(id) => rsx! { pages::Artist { id } },
                    Route::Local(id) => rsx! { pages::LocalPlaylist { id } },
                    Route::Liked => rsx! { pages::SongList { kind: "liked" } },
                    Route::Downloads => rsx! { pages::SongList { kind: "downloads" } },
                    Route::History => rsx! { pages::SongList { kind: "history" } },
                    Route::Settings => rsx! { settings_ui::Settings {} },
                    Route::Privacy => rsx! { settings_ui::Privacy {} },
                    Route::Stats => rsx! { settings_ui::Stats {} },
                    Route::Import => rsx! { settings_ui::Import {} },
                    Route::Transfer => rsx! { settings_ui::Transfer {} },
                    Route::Appearance => rsx! { settings_ui::Appearance {} },
                    Route::Support => rsx! { settings_ui::Support {} },
                }
            }
            if panel.is_some() { player::SidePanel {} }
            player::PlayerOverlay {}
            if MENU.read().is_some() { components::Menu {} }
            if ADD_TO.read().is_some() { components::AddToPlaylist {} }
            if let Some(t) = TOAST.read().clone() { div { class: "toast", "{t}" } }
        }
    }
}

/// Really exit (the window close button only hides to the tray).
pub fn quit() {
    playback::persist_queue();
    playback::save_play();
    std::process::exit(0);
}

pub fn greeting() -> String {
    let h = (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) as i64
        + local_offset_secs()) / 3600 % 24;
    match h { 5..=11 => tr!("Доброго ранку"), 12..=17 => tr!("Доброго дня"), 18..=22 => tr!("Доброго вечора"), _ => tr!("Доброї ночі") }
}

fn local_offset_secs() -> i64 {
    // The system's current UTC offset (works the same on Linux and Windows).
    chrono::Local::now().offset().local_minus_utc() as i64
}

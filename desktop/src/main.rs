#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod audio;
mod covers;
mod instance;
mod update;
mod backup;
mod importer;
mod ui;
mod db;
mod firewall;
mod i18n;
mod link;
mod links;
mod lyrics;
mod model;
mod playback;
mod settings;
mod sys;
mod yt;

/// Render node and kernel driver of the GPU that drives a connected display (e.g. "/dev/dri/renderD129", "i915").
#[cfg(target_os = "linux")]
fn display_gpu() -> Option<(String, String)> {
    let mut found = None;
    for e in std::fs::read_dir("/sys/class/drm").ok()?.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let Some((card, _)) = name.split_once('-') else { continue };
        if std::fs::read_to_string(e.path().join("status")).map_or(true, |s| s.trim() != "connected") { continue }
        let dev = std::path::Path::new("/sys/class/drm").join(card).join("device");
        let driver = std::fs::read_link(dev.join("driver")).ok()?.file_name()?.to_string_lossy().into_owned();
        let node = std::fs::read_dir(dev.join("drm")).ok()?.flatten()
            .map(|d| d.file_name().to_string_lossy().into_owned()).find(|n| n.starts_with("renderD"))?;
        // Prefer the built-in screen (eDP / LVDS / DSI): that is where the window usually is.
        let internal = ["eDP", "LVDS", "DSI"].iter().any(|k| name.contains(k));
        let item = (format!("/dev/dri/{node}"), driver);
        if internal { return Some(item) }
        found.get_or_insert(item);
    }
    found
}

fn main() -> anyhow::Result<()> {
    // Hybrid laptops (Intel + NVIDIA): WebKit otherwise may render on the first GPU it finds, often the NVIDIA
    // one, and copy every frame across to the GPU that drives the screen: janky animations and scrolling.
    // Pin it to the GPU of the connected display. Must happen before any thread starts.
    #[cfg(target_os = "linux")]
    {
        let screen_gpu = display_gpu();
        if let Some((node, _)) = &screen_gpu {
            if std::env::var_os("WEBKIT_WEB_RENDER_DEVICE_FILE").is_none() { std::env::set_var("WEBKIT_WEB_RENDER_DEVICE_FILE", node) }
        }
        // The DMABUF renderer is slow / glitchy only when NVIDIA itself drives the screen.
        if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
            let off = match settings::get().gpu.as_str() {
                "on" => false,
                "off" => true,
                _ => match &screen_gpu { Some((_, driver)) => driver == "nvidia", None => std::path::Path::new("/proc/driver/nvidia").exists() },
            };
            if off { std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1"); }
        }
        // "Off" also keeps the old, most compatible path: the window goes through XWayland.
        // Otherwise the window is a native Wayland one, drawn by the GPU with no extra copy per frame.
        if settings::get().gpu == "off" && std::env::var_os("GDK_BACKEND").is_none() { std::env::set_var("GDK_BACKEND", "x11") }
    }
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build()?;
    i18n::set_language(&settings::get().ui_language);
    rt.block_on(firewall::start())?;
    yt::init()?;
    let args: Vec<String> = std::env::args().collect();
    // Testing: restore a phone backup without the UI (`--restore <file> <key>`).
    if args.get(1).map(String::as_str) == Some("--restore") {
        let bytes = std::fs::read(args.get(2).ok_or_else(|| anyhow::anyhow!("file"))?)?;
        let payload = backup::open(&bytes, args.get(3).map(String::as_str).unwrap_or(""))?;
        println!("{:?}", backup::restore(&payload)?);
        return Ok(());
    }
    if args.get(1).map(String::as_str) == Some("--selftest") {
        let q = args.get(2).cloned().unwrap_or_else(|| "Queen Bohemian Rhapsody".into());
        return rt.block_on(async move {
            let songs = yt::search_songs(&q).await?;
            let s = songs.first().ok_or_else(|| anyhow::anyhow!("no results"))?;
            println!("song: {} — {} ({:?}s)", s.title, s.artist_text(), s.duration);
            let src = audio::Source::YtDlp { video_id: s.id.clone(), economy: false, cache_to: None };
            let t0 = std::time::Instant::now();
            let secs = tokio::task::spawn_blocking(move || audio::decode_test(src, 10_000.0)).await??;
            println!("decoded {secs:.1}s in {:.1}s OK", t0.elapsed().as_secs_f64());
            let l = lyrics::get(s).await?;
            println!("lyrics: {}", l.map(|l| format!("{} ({} chars)", l.source, l.text.len())).unwrap_or("none".into()));
            println!("firewall: allowed tunnels {}, blocked {:?}", firewall::ALLOWED.load(std::sync::atomic::Ordering::Relaxed), *firewall::BLOCKED.lock());
            Ok(())
        });
    }
    // ---- the window ----
    // Already running (e.g. started again from the app menu)? Bring that window forward instead of a second copy.
    if instance::forward_to_running() { return Ok(()) }
    instance::listen();
    playback::init(rt.handle().clone());
    link::init(rt.handle().clone());
    ui::set_runtime(rt.handle().clone());
    let handle = rt.handle().clone();
    // Keep the background runtime alive on its own thread while the UI runs.
    std::thread::spawn(move || rt.block_on(std::future::pending::<()>()));
    use dioxus::desktop::{Config, WindowBuilder, LogicalSize};
    // The web view may load nothing from the network: images come only through our own protocol, and the
    // only connection allowed is to the local UI engine.
    let head = format!(r#"<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' dioxus:; style-src 'self' 'unsafe-inline' dioxus:; img-src http://pmimg.localhost pmimg: data: dioxus:; font-src data: dioxus:; connect-src 'self' dioxus: ipc: http://ipc.localhost ws://127.0.0.1:*; media-src 'none'; frame-src 'none'"><script>{}</script><script>{}</script>"#, ui::player::LYRICS_JS, include_str!("../assets/ui.js"));
    let cfg = Config::new()
        .with_window(WindowBuilder::new().with_title("Privacy Music").with_decorations(!tiling_wm()).with_inner_size(LogicalSize::new(1280.0, 820.0)).with_min_inner_size(LogicalSize::new(900.0, 600.0)))
        .with_background_color((0, 0, 0, 255))
        .with_icon(dioxus::desktop::icon_from_memory(include_bytes!("../assets/icon-256.png")).expect("icon"))
        .with_close_behaviour(if settings::get().close_to_tray { dioxus::desktop::WindowCloseBehaviour::WindowHides } else { dioxus::desktop::WindowCloseBehaviour::WindowCloses })
        // Dioxus would otherwise force XWayland + the CPU (shared-memory) renderer on every Wayland session, which
        // copies the whole window each frame: the cause of choppy animations, worst at full screen. main() decides instead.
        .with_disable_dma_buf_on_wayland(false)
        // wry's own file-drop handler swallows drag events inside the window, which breaks drag-to-reorder
        // (home blocks, queue, playlists). Nothing here accepts dropped files, so let the page handle drags.
        .with_disable_drag_drop_handler(true)
        .with_disable_context_menu(true)
        .with_menu(None)
        .with_custom_head(head)
        .with_navigation_handler(|url: &str| url.starts_with("dioxus://") || url.starts_with("http://dioxus.") || url.starts_with("about:"))
        .with_asynchronous_custom_protocol("pmimg", move |_id, req, responder| {
            let path = req.uri().path().trim_start_matches('/').to_string();
            handle.spawn(async move {
                let resp = match image(&path).await {
                    Some((bytes, ct)) => dioxus::desktop::wry::http::Response::builder().header("Content-Type", ct).header("Cache-Control", "max-age=31536000").body(bytes),
                    None => dioxus::desktop::wry::http::Response::builder().status(404).body(Vec::new()),
                };
                if let Ok(r) = resp { responder.respond(r) }
            });
        });
    dioxus::LaunchBuilder::desktop().with_cfg(cfg).launch(ui::App);
    Ok(())
}

/// Tiling compositors (Hyprland, sway, niri…) draw their own borders; a GTK title bar on top is just noise there.
fn tiling_wm() -> bool {
    if std::env::var("PM_DECORATIONS").as_deref() == Ok("1") { return false }
    let d = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default().to_lowercase();
    std::env::var_os("HYPRLAND_INSTANCE_SIGNATURE").is_some() || std::env::var_os("SWAYSOCK").is_some() || std::env::var_os("NIRI_SOCKET").is_some()
        || ["hyprland", "sway", "niri", "river", "i3", "bspwm", "dwl", "qtile", "awesome"].iter().any(|w| d.contains(w))
}

/// Artwork through the firewall, cached on disk (so a cover is fetched from Google only once).
pub(crate) async fn image(encoded: &str) -> Option<(Vec<u8>, &'static str)> {
    use sha2::Digest;
    // The user's own background picture (local file, never fetched).
    if encoded.starts_with("__bg") {
        // "__bg<version>b<blur px>": the blurred copy is made once here and cached (a CSS blur filter would be
        // redrawn by WebKit and darkens the picture's edges).
        let blur: u32 = encoded.rsplit_once('b').and_then(|(_, b)| b.parse().ok()).unwrap_or(0);
        if blur > 0 {
            let out = settings::cache_dir().join(format!("{encoded}.jpg"));
            if let Ok(b) = tokio::fs::read(&out).await { return Some((b, "image/jpeg")) }
            let src = settings::background_file();
            let made = tokio::task::spawn_blocking(move || -> Option<Vec<u8>> {
                // The file has no extension: detect the format from its bytes.
                let img = image::ImageReader::open(&src).ok()?.with_guessed_format().ok()?.decode().ok()?;
                // Small copy (the view scales it up smoothly), then a Gaussian blur in proportion.
                let small = img.resize(720, 720, image::imageops::FilterType::Triangle).to_rgb8();
                let sigma = blur as f32 * small.width() as f32 / 1600.0;
                let blurred = image::imageops::blur(&small, sigma.max(0.5));
                let mut bytes = Vec::new();
                image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, 90).encode_image(&blurred).ok()?;
                Some(bytes)
            }).await.ok().flatten()?;
            let _ = tokio::fs::write(&out, &made).await;
            return Some((made, "image/jpeg"));
        }
        let b = tokio::fs::read(settings::background_file()).await.ok()?;
        let ct = if b.starts_with(&[0x89, b'P']) { "image/png" } else if b.starts_with(b"RIFF") { "image/webp" } else { "image/jpeg" };
        return Some((b, ct));
    }
    let url = urlencoding::decode(encoded).ok()?.into_owned();
    // A playlist cover the user picked (local file, never fetched): "local:cover-<id>-<version>".
    if let Some(rest) = url.strip_prefix("local:cover-") {
        let id: i64 = rest.split('-').next()?.parse().ok()?;
        let b = tokio::fs::read(db::cover_file(id)).await.ok()?;
        let ct = if b.starts_with(&[0x89, b'P']) { "image/png" } else if b.starts_with(b"RIFF") { "image/webp" } else { "image/jpeg" };
        return Some((b, ct));
    }
    if !url.starts_with("https://") { return None }
    let dir = settings::cache_dir().join("img");
    let file = dir.join(format!("{:x}", sha2::Sha256::digest(url.as_bytes())));
    let ct = |b: &[u8]| if b.starts_with(&[0x89, b'P']) { "image/png" } else if b.starts_with(b"RIFF") { "image/webp" } else { "image/jpeg" };
    if let Ok(b) = tokio::fs::read(&file).await { let c = ct(&b); return Some((b, c)) }
    let b = firewall::HTTP.get(&url).send().await.ok()?.error_for_status().ok()?.bytes().await.ok()?.to_vec();
    let _ = tokio::fs::create_dir_all(&dir).await;
    let _ = tokio::fs::write(&file, &b).await;
    let c = ct(&b);
    Some((b, c))
}

//! Small platform differences in one place (Linux / Windows).

/// A helper program: on Windows a copy next to privacy-music.exe wins (the zip ships yt-dlp.exe there),
/// otherwise the one on PATH. On Windows it never opens a console window.
pub fn command(program: &str) -> std::process::Command {
    let mut c = std::process::Command::new(resolve(program));
    #[cfg(windows)] { use std::os::windows::process::CommandExt; c.creation_flags(CREATE_NO_WINDOW); }
    c
}
pub fn tokio_command(program: &str) -> tokio::process::Command {
    let mut c = tokio::process::Command::new(resolve(program));
    #[cfg(windows)] { c.creation_flags(CREATE_NO_WINDOW); }
    c
}
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

fn resolve(program: &str) -> std::path::PathBuf {
    if let Some(p) = bundled(program) { return p }
    program.into()
}

/// A helper shipped next to the app (Windows zip), if present: "<name>\\<name>.exe" (an unpacked build, which starts
/// faster because it doesn't unpack itself into %TEMP% on every run) or "<name>.exe".
/// On Linux the one-command installer puts yt-dlp and deno next to the app (~/.local/lib/privacy-music); a system
/// package (Arch) uses the ones on PATH instead.
pub fn bundled(program: &str) -> Option<std::path::PathBuf> {
    let dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    if cfg!(windows) {
        [dir.join(program).join(format!("{program}.exe")), dir.join(format!("{program}.exe"))].into_iter().find(|p| p.exists())
    } else {
        // Never pick up stray files from /usr/bin: only a private folder of ours counts.
        if !dir.ends_with("privacy-music") { return None }
        Some(dir.join(program)).filter(|p| p.is_file())
    }
}

/// Opens a link (mailto:, https:) in the system's default app.
pub fn open_url(url: &str) {
    #[cfg(windows)] { let _ = command("rundll32").args(["url.dll,FileProtocolHandler", url]).spawn(); }
    #[cfg(not(windows))] { let _ = std::process::Command::new("xdg-open").arg(url).spawn(); }
}

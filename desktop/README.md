# Privacy Music — desktop (Rust)

The desktop twin of the Android app: same privacy rules, same translations (`../app/src/main/assets/i18n`).

* **Network firewall** — every request (ours, RustyPipe's and yt-dlp's) goes through a local proxy that only
  tunnels to an allowlist (YouTube Music, Google video/image servers, LRCLIB when enabled, Spotify embeds for
  import). The web view that draws the UI has no network access (strict CSP; artwork comes via `pmimg:`).
* **Catalogue**: RustyPipe (no storage, no reports, no account). **Audio**: `yt-dlp -o -` piped into our own
  decoder (libopus / Symphonia → cpal). Songs are cached; downloads play offline.
* **Library**: SQLite in `~/.local/share/privacy-music`. Nothing is synced anywhere.
* **Import**: Telegram Desktop export (`result.json`, audio files kept as local songs), CSV/TSV, YouTube Music and
  public Spotify playlist links.

## Build

    cargo build --release          # needs webkit2gtk-4.1, gtk3, opus, alsa, dbus
    ./target/release/privacy-music # needs yt-dlp (+ yt-dlp-ejs, deno) at runtime

Arch package: `cd packaging && makepkg -si`.

`vendor/muda` and `vendor/tray-icon` are unmodified upstream crates except that `libxdo` is removed from their
default features (it is only needed for X11 copy/paste menu shortcuts, which this app does not use).

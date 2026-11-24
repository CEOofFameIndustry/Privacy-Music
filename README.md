# Privacy Music

A private music player for **Android**, **Windows** and **Linux**, with the YouTube Music catalogue.
No account, no ads, no tracking: your library, likes, playlists and history live only on your device.

---

## Install

### 📱 Android

**[⬇ Download PrivacyMusic.apk](https://github.com/CEOofFameIndustry/Privacy-Music/releases/latest/download/PrivacyMusic.apk)** — open the link on your phone, tap the downloaded file and allow the installation.

Android 8.0 or newer. Installing it over an older Privacy Music keeps your library.
Later updates: **Settings → About → Check for updates** in the app.

### 🪟 Windows 10 / 11

Open **PowerShell** (Start → type “PowerShell”) and paste:

```powershell
irm https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.ps1 | iex
```

No administrator rights needed. If Privacy Music is already installed (or running from a folder you unpacked earlier),
that copy is updated in place; otherwise it goes to `%LOCALAPPDATA%\Programs\Privacy Music` with a Start menu shortcut.
Run the same command to update. Manual download: [PrivacyMusic-windows-x64.zip](https://github.com/CEOofFameIndustry/Privacy-Music/releases/latest/download/PrivacyMusic-windows-x64.zip) — unpack it and start `privacy-music.exe`.

### 🐧 Linux (x86_64)

```bash
curl -fsSL https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.sh | bash
```

* **Arch, Manjaro, EndeavourOS** — installs (or upgrades) the pacman package; yt-dlp and deno come from the repositories.
* **Ubuntu 22.04+, Debian 12+, Mint 21+, Fedora 36+, openSUSE** — the libraries come from your package manager; the app,
  yt-dlp and deno (official builds, checksums verified) go to `~/.local/lib/privacy-music`.

Run the same command to update. Uninstall: `rm -rf ~/.local/lib/privacy-music ~/.local/bin/privacy-music ~/.local/share/applications/privacy-music.desktop`
(on Arch: `sudo pacman -R privacy-music`).

Your data stays where it is on every install and update: `~/.local/share/privacy-music` (Linux), `%APPDATA%\privacy-music` (Windows).

---

## Features

* Search, albums, artists, playlists, radio and charts from YouTube Music.
* A library on your device: likes, playlists (sorting, search inside a playlist, custom covers), offline downloads, listening stats.
* Lyrics, including time-synced lyrics (LRCLIB, opt-in).
* Crossfade, gapless albums, a 10-band equalizer, choice of audio output.
* Import from Telegram (covers are found automatically), Spotify links, CSV/Excel and YouTube Music.
* Devices nearby: phone ↔ computer over your local network with end-to-end encryption — remote control, hand-off, library sync.
* Interface in 11 languages.

## Privacy

* No Google SDKs, no Play Services, no analytics, no cookies, no advertising IDs.
* All traffic goes through a built-in firewall with an allowlist of hosts; YouTube's playback reports are blocked.
* YouTube only sees your IP address and what you search for and play. Use a VPN or Tor to hide your IP.
* The update check (GitHub) can be turned off in Settings.

## Building from source

* Android: `./gradlew :app:assembleRelease` (JDK 21, Kotlin 2.4).
* Desktop (Rust + Dioxus): `cd desktop && cargo build --release` — needs webkit2gtk-4.1, gtk3, opus, alsa, libayatana-appindicator;
  yt-dlp and deno at runtime. Packaging scripts: `desktop/packaging/` (`PKGBUILD`, `build-linux.sh`, `build-windows.sh`).

## Support the author

Crypto only (the app shows these with QR codes under Settings → Support):

| Coin | Network | Address |
|---|---|---|
| **Monero (XMR)** — recommended | Monero | `89DRpjfQeyjSxpsKYsd2dwJwjCjqLRjsrEf3kWcjKYyM5BRykkY2qiqEoJmPZrCpLR3J7jqLFUrgdEbYzF7ELLk28oYcQvS` |
| Bitcoin (BTC) | Bitcoin | `bc1qknsaqeupv2z9v24m7mvh0zxutewq32lqr7l2cv` |
| Tether (USDT) | **Arbitrum One only** | `0x2E6568ec773f220e7ED672Bd54AaF8Bd20f24c81` |

Bugs and ideas: [Issues](https://github.com/CEOofFameIndustry/Privacy-Music/issues).

License: GPL-3.0-or-later (see LICENSE). Privacy Music is not affiliated with YouTube or Google.

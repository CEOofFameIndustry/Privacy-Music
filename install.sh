#!/usr/bin/env bash
# Privacy Music for Linux — install or update with one command:
#   curl -fsSL https://raw.githubusercontent.com/CEOofFameIndustry/Privacy-Music/main/install.sh | bash
# Arch / Manjaro / EndeavourOS: the pacman package. Other distributions (Ubuntu, Debian, Mint, Fedora, openSUSE…):
# the libraries from your package manager + the app, yt-dlp and deno in ~/.local/lib/privacy-music (no root needed for those).
# Your library and settings (~/.local/share/privacy-music) are never touched.
set -euo pipefail
REPO="CEOofFameIndustry/Privacy-Music"
BASE="https://github.com/$REPO/releases/latest/download"
say() { printf '\033[1;35m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }
[ "$(uname -m)" = "x86_64" ] || die "only x86_64 builds are published for now (this machine: $(uname -m))."
command -v curl >/dev/null || die "curl is needed."
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

if command -v pacman >/dev/null; then
  say "Arch-based system: installing the pacman package"
  curl -fL --progress-bar -o "$tmp/privacy-music-x86_64.pkg.tar.zst" "$BASE/privacy-music-x86_64.pkg.tar.zst"
  $SUDO pacman -U --needed --noconfirm "$tmp/privacy-music-x86_64.pkg.tar.zst"
  say "Done. Start it from the app menu or run: privacy-music"
  exit 0
fi

say "Installing the libraries Privacy Music needs (asks for your password)"
if command -v apt-get >/dev/null; then
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq libwebkit2gtk-4.1-0 libgtk-3-0 libopus0 libayatana-appindicator3-1 unzip ca-certificates
  $SUDO apt-get install -y -qq libasound2t64 2>/dev/null || $SUDO apt-get install -y -qq libasound2
elif command -v dnf >/dev/null; then
  $SUDO dnf install -y -q webkit2gtk4.1 gtk3 opus alsa-lib libayatana-appindicator-gtk3 unzip
elif command -v zypper >/dev/null; then
  $SUDO zypper -n -q install libwebkit2gtk-4_1-0 libopus0 alsa libayatana-appindicator3-1 unzip
else
  say "Unknown package manager: make sure webkit2gtk 4.1, gtk3, opus, alsa and ayatana-appindicator are installed."
fi

lib="$HOME/.local/lib/privacy-music"
mkdir -p "$lib" "$HOME/.local/bin" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/512x512/apps"

say "Downloading Privacy Music"
curl -fL --progress-bar -o "$tmp/app.tar.gz" "$BASE/privacy-music-linux-x86_64.tar.gz"
tar -xzf "$tmp/app.tar.gz" -C "$tmp"
install -m755 "$tmp/privacy-music/privacy-music" "$lib/privacy-music"

say "Downloading yt-dlp (official build; fetches the audio)"
curl -fsSL -o "$tmp/yt-dlp_linux" "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
curl -fsSL -o "$tmp/SUMS" "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"
(cd "$tmp" && grep " yt-dlp_linux$" SUMS | sha256sum -c --quiet -) || die "yt-dlp checksum mismatch"
install -m755 "$tmp/yt-dlp_linux" "$lib/yt-dlp"

say "Downloading deno (official build; yt-dlp needs it for YouTube's checks)"
curl -fsSL -o "$tmp/deno.zip" "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip"
want=$(curl -fsSL "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip.sha256sum" | grep -oiE '[0-9a-f]{64}' | head -1 | tr A-F a-f)
[ "$(sha256sum "$tmp/deno.zip" | cut -d' ' -f1)" = "$want" ] || die "deno checksum mismatch"
unzip -oq "$tmp/deno.zip" deno -d "$lib"
chmod 755 "$lib/deno"

ln -sf "$lib/privacy-music" "$HOME/.local/bin/privacy-music"
install -m644 "$tmp/privacy-music/privacy-music.png" "$HOME/.local/share/icons/hicolor/512x512/apps/privacy-music.png"
sed "s|^Exec=.*|Exec=$lib/privacy-music|" "$tmp/privacy-music/privacy-music.desktop" > "$HOME/.local/share/applications/privacy-music.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true

say "Done. Start it from the app menu or run: $lib/privacy-music"
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) say "Tip: add ~/.local/bin to PATH to start it with just: privacy-music";; esac

#!/bin/bash
# Builds the Windows version on Linux (MinGW cross-compile) and packs a ready-to-run zip:
#   privacy-music.exe + WebView2Loader.dll + official yt-dlp.exe and deno.exe (checksums verified).
# Needs: rustup target x86_64-pc-windows-gnu, mingw-w64-gcc, cmake, curl, python3.
set -euo pipefail
cd "$(dirname "$0")/.."
ver=$(grep -m1 '^version' Cargo.toml | cut -d'"' -f2)
# Old bundled libopus needs these with CMake 4; build it statically into the exe.
CMAKE_POLICY_VERSION_MINIMUM=3.5 OPUS_STATIC=1 OPUS_NO_PKG=1 cargo build --release --target x86_64-pc-windows-gnu
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
dl=$work/dl; out="$work/Privacy Music"; mkdir -p "$dl" "$out"
# The unpacked ("onedir") build: starts ~1-2 s faster per song than the single yt-dlp.exe, which unpacks itself into %TEMP% every run.
curl -sSLf -o "$dl/yt-dlp_win.zip" https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_win.zip
curl -sSLf -o "$dl/SUMS" https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS
(cd "$dl" && grep " yt-dlp_win.zip$" SUMS | sha256sum -c -)
mkdir -p "$out/yt-dlp"
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$dl/yt-dlp_win.zip" "$out/yt-dlp"
[ -f "$out/yt-dlp/yt-dlp.exe" ] || { echo "yt-dlp_win.zip layout changed"; ls "$out/yt-dlp"; exit 1; }
curl -sSLf -o "$dl/deno.zip" https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip
want=$(curl -sSLf https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip.sha256sum | grep -oiE '[0-9a-f]{64}' | head -1 | tr A-F a-f)
[ "$(sha256sum "$dl/deno.zip" | cut -d' ' -f1)" = "$want" ] || { echo "deno checksum mismatch"; exit 1; }
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extract('deno.exe', sys.argv[2])" "$dl/deno.zip" "$out"
cp target/x86_64-pc-windows-gnu/release/privacy-music.exe target/x86_64-pc-windows-gnu/release/WebView2Loader.dll "$out/"
cp packaging/windows-README.txt "$out/README.txt"
rm -f "packaging/privacy-music-$ver-windows-x64.zip"
(cd "$work" && python3 -c "
import zipfile, os, sys
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, _, files in os.walk('Privacy Music'):
        for f in files: z.write(os.path.join(root, f))" "$OLDPWD/packaging/privacy-music-$ver-windows-x64.zip")
ls -la "packaging/privacy-music-$ver-windows-x64.zip"

#!/bin/bash
# Builds a Linux binary that runs on most distributions (glibc 2.35+: Ubuntu 22.04+, Debian 12+, Fedora 36+, Mint 21+…)
# inside an Ubuntu 22.04 container, and packs privacy-music-<ver>-linux-x86_64.tar.gz for install.sh.
# Usage: packaging/build-linux.sh [work dir for the cargo cache and target]
set -euo pipefail
cd "$(dirname "$0")/.."
ver=$(grep -m1 '^version' Cargo.toml | cut -d'"' -f2)
work=${1:-$PWD/target/linux-generic}
mkdir -p "$work/cargo" "$work/target"
# The whole repository goes in: the desktop app embeds the translations kept next to the phone app.
docker run --rm -v "$(cd .. && pwd)":/repo:ro -v "$work/cargo":/cargo -v "$work/target":/target -e CARGO_HOME=/cargo -e RUSTUP_HOME=/cargo/rustup -e CARGO_TARGET_DIR=/target \
  -e DEBIAN_FRONTEND=noninteractive ubuntu:22.04 bash -euc '
    apt-get update -qq
    apt-get install -y -qq curl build-essential pkg-config cmake libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev \
      libopus-dev libasound2-dev libssl-dev >/dev/null
    [ -d /cargo/rustup/toolchains ] || curl -sSf https://sh.rustup.rs | sh -s -- -y -q --profile minimal --no-modify-path
    cd /repo/desktop && /cargo/bin/cargo build --release --locked
    chown -R '"$(id -u):$(id -g)"' /target /cargo'
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
d="$out/privacy-music"; mkdir -p "$d"
cp "$work/target/release/privacy-music" "$d/"
strip "$d/privacy-music" || true
cp packaging/privacy-music.desktop packaging/privacy-music.png "$d/"
tar -C "$out" -czf "packaging/privacy-music-$ver-linux-x86_64.tar.gz" privacy-music
ls -la "packaging/privacy-music-$ver-linux-x86_64.tar.gz"

//! The network firewall. Every request the app makes — our own and the YouTube client library's — goes through a
//! tiny local HTTPS proxy that only opens tunnels to an allowlist of hosts. Anything else is refused locally and
//! shown in the network log. The web view that draws the UI has no network access at all (see main.rs).
use once_cell::sync::{Lazy, OnceCell};
use parking_lot::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[derive(Clone, Debug, PartialEq)]
pub struct Blocked { pub time: u64, pub host: String }

static PORT: OnceCell<u16> = OnceCell::new();
pub static ALLOWED: AtomicU64 = AtomicU64::new(0);
pub static BLOCKED: Lazy<Mutex<Vec<Blocked>>> = Lazy::new(|| Mutex::new(Vec::new()));

/// Hosts the app may talk to. Telemetry and ad hosts are simply not on the list.
pub fn permitted(host: &str) -> bool {
    let s = crate::settings::get();
    if s.offline { return false; }
    let img = |h: &str| {
        let re = regex::Regex::new(r"^(i\d?\.ytimg\.com|lh\d\.googleusercontent\.com|yt\d\.googleusercontent\.com|yt\d\.ggpht\.com|lh\d\.ggpht\.com)$").unwrap();
        re.is_match(h)
    };
    match host {
        "music.youtube.com" | "www.youtube.com" | "youtubei.googleapis.com" => true,
        h if h.ends_with(".googlevideo.com") => true,
        h if img(h) => true,
        "lrclib.net" => s.lrclib,
        "open.spotify.com" => true,
        // Update notice (Settings → General), and only for the release information.
        "api.github.com" => s.update_check,
        _ => false,
    }
}

pub fn port() -> u16 { *PORT.get().expect("firewall not started") }

fn now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

fn block(host: &str) {
    let mut b = BLOCKED.lock();
    b.insert(0, Blocked { time: now(), host: host.to_string() });
    b.truncate(200);
}

/// Starts the proxy on a random local port (127.0.0.1 only). Call once, inside the tokio runtime.
pub async fn start() -> anyhow::Result<u16> {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    let _ = PORT.set(port);
    tokio::spawn(async move {
        loop {
            let Ok((sock, _)) = listener.accept().await else { continue };
            tokio::spawn(handle(sock));
        }
    });
    Ok(port)
}

async fn handle(mut client: TcpStream) {
    let mut head = Vec::with_capacity(512);
    let mut buf = [0u8; 512];
    while !head.windows(4).any(|w| w == b"\r\n\r\n") {
        match client.read(&mut buf).await { Ok(0) | Err(_) => return, Ok(n) => head.extend_from_slice(&buf[..n]) }
        if head.len() > 16 * 1024 { return; }
    }
    let text = String::from_utf8_lossy(&head);
    let first = text.lines().next().unwrap_or_default();
    let mut parts = first.split_whitespace();
    let (method, target) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
    // Only TLS tunnels to port 443: plain HTTP never leaves the machine.
    let (host, port) = target.rsplit_once(':').unwrap_or((target, ""));
    if method != "CONNECT" || port != "443" || !permitted(host) {
        block(host);
        let _ = client.write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n").await;
        return;
    }
    let Ok(mut upstream) = TcpStream::connect((host, 443)).await else {
        let _ = client.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n").await;
        return;
    };
    ALLOWED.fetch_add(1, Ordering::Relaxed);
    if client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.is_err() { return; }
    let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
}

/// Generic browser identity: no device ids, no cookies (reqwest keeps none unless asked).
pub const UA: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

pub fn proxy_url() -> String { format!("http://127.0.0.1:{}", port()) }

pub fn client_builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder().proxy(reqwest::Proxy::all(proxy_url()).expect("proxy"))
}

pub static HTTP: Lazy<reqwest::Client> = Lazy::new(|| {
    client_builder().user_agent(UA).timeout(std::time::Duration::from_secs(30)).build().expect("http client")
});

pub fn blocking() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder().proxy(reqwest::Proxy::all(proxy_url()).expect("proxy"))
        .user_agent(UA).timeout(std::time::Duration::from_secs(30)).build().expect("http client")
}

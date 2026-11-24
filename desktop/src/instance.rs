//! One copy of the app at a time. The first one listens on a fixed local port (127.0.0.1 only); starting the app
//! again just asks it to show its window and exits, so there is never a second window or a second tray icon.
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const PORT: u16 = 47718;
const HELLO: &[u8] = b"privacy-music:show\n";
const REPLY: &[u8] = b"privacy-music:ok\n";
/// Set when another start asked for the window; the UI shows it and clears the flag.
pub static SHOW: AtomicBool = AtomicBool::new(false);

fn addr() -> SocketAddr { SocketAddr::from(([127, 0, 0, 1], PORT)) }

/// True if a running copy took over (this one should exit).
pub fn forward_to_running() -> bool {
    if std::env::var_os("PM_MULTI").is_some() { return false } // tests run several copies side by side
    let Ok(mut s) = TcpStream::connect_timeout(&addr(), Duration::from_millis(300)) else { return false };
    let _ = s.set_read_timeout(Some(Duration::from_millis(1500)));
    if s.write_all(HELLO).is_err() { return false }
    let mut buf = [0u8; 32];
    let n = s.read(&mut buf).unwrap_or(0);
    &buf[..n] == REPLY
}

pub fn listen() {
    if std::env::var_os("PM_MULTI").is_some() { return }
    let Ok(l) = TcpListener::bind(addr()) else { return };
    std::thread::spawn(move || {
        for mut c in l.incoming().flatten() {
            let _ = c.set_read_timeout(Some(Duration::from_millis(1000)));
            let mut buf = [0u8; 32];
            let n = c.read(&mut buf).unwrap_or(0);
            if &buf[..n] == HELLO { let _ = c.write_all(REPLY); SHOW.store(true, Ordering::Relaxed); }
        }
    });
}

pub fn take_show() -> bool { SHOW.swap(false, Ordering::Relaxed) }

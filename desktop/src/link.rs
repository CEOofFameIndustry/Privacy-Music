//! Devices nearby: an end-to-end encrypted link between this computer and the user's own phone, only over the
//! local network (never through any server). Off by default.
//!
//! * Discovery: this computer broadcasts a small UDP beacon. It names no one: for each paired phone it carries an
//!   8-byte tag (HMAC of a fresh nonce under a key only that phone shares), so strangers on the network learn nothing
//!   linkable. Only while "add a phone" is open does it also show this computer's name.
//! * Pairing: P-256 keys on both sides; the phone commits to its hello before seeing ours, then both screens show a
//!   6-digit code derived from the whole exchange. The user checks they match (stops a man in the middle).
//! * Every session: fresh ephemeral ECDH + the paired static ECDH → HKDF-SHA256 → two AES-256-GCM keys (forward
//!   secrecy; only the paired phone can derive them). Same primitives as the phone (Link.kt), no extra libraries there.
//!
//! What travels: what is playing (to show and control it), the queue on hand-off, and — if turned on — likes,
//! playlists and saved albums/artists for library sync. Nothing else.
use crate::model::{Item, Ref, Song};
use aes_gcm::{aead::{Aead, KeyInit}, Aes256Gcm, Nonce};
use anyhow::{anyhow, bail, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use hmac::{Hmac, Mac};
use once_cell::sync::Lazy;
use p256::{ecdh::EphemeralSecret, PublicKey, SecretKey};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering::Relaxed};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot, watch};

pub const TCP_PORT: u16 = 47719;
pub const UDP_PORT: u16 = 47720;
const MAX_FRAME: usize = 32 << 20;
const PROTO: &[u8] = b"PMLINK1";

// ---------- identity and paired devices (data_dir/link.json, private to this user) ----------
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Peer { pub id: String, pub name: String, #[serde(default)] pub kind: String, spk: String, #[serde(default)] pub paired_at: i64 }

#[derive(Serialize, Deserialize, Clone)]
struct Identity { id: String, sk: String, #[serde(default)] peers: Vec<Peer> }

fn ident_file() -> std::path::PathBuf { crate::settings::data_dir().join("link.json") }

static IDENT: Lazy<Mutex<Identity>> = Lazy::new(|| {
    if let Some(i) = std::fs::read_to_string(ident_file()).ok().and_then(|t| serde_json::from_str::<Identity>(&t).ok()) { return Mutex::new(i) }
    let sk = SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
    let i = Identity { id: hex::encode(rand::random::<[u8; 12]>()), sk: B64.encode(sk.to_bytes()), peers: vec![] };
    save_ident(&i);
    Mutex::new(i)
});

fn save_ident(i: &Identity) {
    let path = ident_file();
    let _ = std::fs::write(&path, serde_json::to_string_pretty(i).unwrap_or_default());
    #[cfg(unix)] { use std::os::unix::fs::PermissionsExt; let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)); }
}
fn secret() -> SecretKey { SecretKey::from_slice(&B64.decode(&IDENT.lock().sk).unwrap_or_default()).expect("link key") }
fn my_id() -> String { IDENT.lock().id.clone() }
fn sec1(pk: &PublicKey) -> Vec<u8> { use p256::elliptic_curve::sec1::ToEncodedPoint; pk.to_encoded_point(false).as_bytes().to_vec() }
fn parse_pk(bytes: &[u8]) -> Result<PublicKey> { PublicKey::from_sec1_bytes(bytes).map_err(|_| anyhow!("bad key")) }
pub fn peers() -> Vec<Peer> { IDENT.lock().peers.clone() }

pub fn device_name() -> String {
    std::fs::read_to_string("/etc/hostname").ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .unwrap_or_else(|| crate::tr!("Комп'ютер"))
}

/// This computer's address on the local network (shown for typing it on the phone if broadcasts are blocked).
pub fn local_address() -> Option<String> {
    // Connecting a UDP socket sends nothing; it only picks the interface a packet would leave from.
    let s = std::net::UdpSocket::bind(("0.0.0.0", 0)).ok()?;
    s.connect(("10.255.255.255", 1)).ok()?;
    Some(s.local_addr().ok()?.ip().to_string())
}

pub fn forget(id: &str) {
    { let mut i = IDENT.lock(); i.peers.retain(|p| p.id != id); save_ident(&i); }
    if let Some(tx) = SESSIONS.lock().remove(id) { let _ = tx.send(json!({"t": "bye"})); }
    publish();
}

// ---------- crypto helpers (mirrored exactly in Link.kt) ----------
fn hkdf(salt: &[u8], ikm: &[u8], info: &str, len: usize) -> Vec<u8> {
    let mut out = vec![0u8; len];
    hkdf::Hkdf::<Sha256>::new(Some(salt), ikm).expand(info.as_bytes(), &mut out).expect("hkdf length");
    out
}
fn dh(sk: &SecretKey, pk: &PublicKey) -> Vec<u8> { p256::ecdh::diffie_hellman(sk.to_nonzero_scalar(), pk.as_affine()).raw_secret_bytes().to_vec() }
fn beacon_key(peer_spk: &[u8]) -> Option<Vec<u8>> { Some(hkdf(&[0u8; 32], &dh(&secret(), &parse_pk(peer_spk).ok()?), "pm-link-v1 beacon", 32)) }
fn lp(out: &mut Vec<u8>, b: &[u8]) { out.extend_from_slice(&(b.len() as u16).to_be_bytes()); out.extend_from_slice(b); }

struct Hello { id: String, name: String, kind: String, spk: Vec<u8>, epk: Vec<u8>, n: Vec<u8>, pair: bool }
impl Hello {
    fn to_json(&self) -> Value { json!({"v": 1, "id": self.id, "name": self.name, "kind": self.kind, "spk": B64.encode(&self.spk), "epk": B64.encode(&self.epk), "n": B64.encode(&self.n), "pair": self.pair}) }
    fn from_json(v: &Value) -> Result<Hello> {
        let b = |k: &str| B64.decode(v[k].as_str().unwrap_or("")).map_err(|_| anyhow!("bad {k}"));
        let h = Hello { id: v["id"].as_str().unwrap_or("").into(), name: v["name"].as_str().unwrap_or("").chars().take(64).collect(), kind: v["kind"].as_str().unwrap_or("").into(),
            spk: b("spk")?, epk: b("epk")?, n: b("n")?, pair: v["pair"].as_bool().unwrap_or(false) };
        if h.id.is_empty() || h.id.len() > 64 || h.n.len() != 32 { bail!("bad hello") }
        Ok(h)
    }
    fn transcript(&self, out: &mut Vec<u8>) { lp(out, self.id.as_bytes()); lp(out, &self.spk); lp(out, &self.epk); lp(out, &self.n); }
}

/// An encrypted, framed connection. Nonce = 4 zero bytes + 64-bit counter; one key per direction.
struct Channel { tx: Aes256Gcm, rx: Aes256Gcm, ntx: u64, nrx: u64 }
impl Channel {
    fn seal(&mut self, v: &Value) -> Vec<u8> {
        let mut nonce = [0u8; 12]; nonce[4..].copy_from_slice(&self.ntx.to_be_bytes()); self.ntx += 1;
        self.tx.encrypt(&Nonce::from(nonce), v.to_string().as_bytes()).expect("encrypt")
    }
    fn open(&mut self, data: &[u8]) -> Result<Value> {
        let mut nonce = [0u8; 12]; nonce[4..].copy_from_slice(&self.nrx.to_be_bytes()); self.nrx += 1;
        let plain = self.rx.decrypt(&Nonce::from(nonce), data).map_err(|_| anyhow!("decrypt"))?;
        Ok(serde_json::from_slice(&plain)?)
    }
}

async fn read_frame<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<Vec<u8>> {
    let len = r.read_u32().await? as usize;
    if len > MAX_FRAME { bail!("frame too big") }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(buf)
}
async fn write_frame<W: AsyncWriteExt + Unpin>(w: &mut W, data: &[u8]) -> Result<()> {
    w.write_u32(data.len() as u32).await?; w.write_all(data).await?; w.flush().await?; Ok(())
}

// ---------- state shown by the UI ----------
#[derive(Clone, Debug, PartialEq, Default)]
pub struct Remote { pub song: Option<Song>, pub playing: bool, pub pos: u64, pub dur: u64, pub vol: f32, pub title: Option<String>, pub received: i64 }
impl Remote {
    /// Position extrapolated from when the state arrived.
    pub fn position(&self) -> u64 {
        let p = if self.playing { self.pos + (crate::db::now_ms() - self.received).max(0) as u64 } else { self.pos };
        if self.dur > 0 { p.min(self.dur) } else { p }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum Pairing { Idle, Waiting, Confirm { code: String, name: String }, Done(String), Failed(String) }

#[derive(Clone, Debug, PartialEq)]
pub struct DeviceView { pub id: String, pub name: String, pub kind: String, pub online: bool, pub remote: Option<Remote> }

#[derive(Clone, Debug, PartialEq)]
pub struct View { pub running: bool, pub error: Option<String>, pub devices: Vec<DeviceView>, pub pairing: Pairing, pub last_sync: Option<i64> }
impl Default for View { fn default() -> Self { View { running: false, error: None, devices: vec![], pairing: Pairing::Idle, last_sync: None } } }

pub static VIEW: Lazy<watch::Sender<View>> = Lazy::new(|| watch::channel(View::default()).0);
static REMOTES: Lazy<Mutex<HashMap<String, Remote>>> = Lazy::new(Default::default);
static SESSIONS: Lazy<Mutex<HashMap<String, mpsc::UnboundedSender<Value>>>> = Lazy::new(Default::default);
static TASKS: Lazy<Mutex<Vec<tokio::task::JoinHandle<()>>>> = Lazy::new(Default::default);
static PAIR_UNTIL: Lazy<Mutex<i64>> = Lazy::new(|| Mutex::new(0));
static PAIR_DECISION: Lazy<Mutex<Option<oneshot::Sender<bool>>>> = Lazy::new(Default::default);
static PAIR_BUSY: AtomicBool = AtomicBool::new(false);
static RT: once_cell::sync::OnceCell<tokio::runtime::Handle> = once_cell::sync::OnceCell::new();

fn publish() {
    let sessions: Vec<String> = SESSIONS.lock().keys().cloned().collect();
    let remotes = REMOTES.lock().clone();
    let devices = peers().into_iter().map(|p| DeviceView { online: sessions.contains(&p.id), remote: remotes.get(&p.id).cloned(), id: p.id, name: p.name, kind: p.kind }).collect();
    VIEW.send_modify(|v| v.devices = devices);
}

// ---------- start / stop ----------
pub fn init(rt: tokio::runtime::Handle) {
    let _ = RT.set(rt);
    apply();
    if std::env::var_os("PM_LINK_TEST").is_some() { start_pairing() }
    // Tell paired devices what plays here whenever it changes (and every few seconds while connected).
    spawn(async {
        let mut rx = crate::playback::STATE.subscribe();
        let mut was_playing = false;
        loop {
            let changed = tokio::time::timeout(Duration::from_secs(4), rx.changed()).await;
            if matches!(changed, Ok(Err(_))) { break }
            let playing = rx.borrow_and_update().playing;
            if playing && !was_playing && crate::settings::get().link_single { pause_others(None) }
            was_playing = playing;
            if !SESSIONS.lock().is_empty() { broadcast(state_msg()) }
        }
    });
    // Library changes (a like, a playlist edit…) reach the phone within a few seconds.
    spawn(async {
        let mut rx = crate::db::VERSION.subscribe();
        loop {
            if rx.changed().await.is_err() { break }
            tokio::time::sleep(Duration::from_secs(2)).await;
            rx.borrow_and_update();
            let fp = crate::db::sync_fingerprint();
            let changed = { let mut last = LAST_FP.lock(); let c = *last != fp; *last = fp; c };
            if changed && crate::settings::get().link_sync && !SESSIONS.lock().is_empty() { broadcast(sync_snapshot()) }
        }
    });
}
/// What the other device last saw of our library (no echo after merging its changes).
static LAST_FP: Lazy<Mutex<String>> = Lazy::new(|| Mutex::new(String::new()));
fn spawn<F: std::future::Future<Output = ()> + Send + 'static>(f: F) -> Option<tokio::task::JoinHandle<()>> { RT.get().map(|rt| rt.spawn(f)) }

/// Starts or stops the link to match the setting.
pub fn apply() {
    let on = crate::settings::get().link;
    let mut tasks = TASKS.lock();
    if on == !tasks.is_empty() { return }
    if !on {
        for t in tasks.drain(..) { t.abort() }
        for (_, tx) in SESSIONS.lock().drain() { let _ = tx.send(json!({"t": "bye"})); }
        REMOTES.lock().clear();
        VIEW.send_modify(|v| { v.running = false; v.pairing = Pairing::Idle });
        publish();
        return;
    }
    VIEW.send_modify(|v| { v.running = true; v.error = None });
    if let Some(t) = spawn(async { if let Err(e) = serve().await { VIEW.send_modify(|v| { v.running = false; v.error = Some(e.to_string()) }) } }) { tasks.push(t) }
    if let Some(t) = spawn(beacon()) { tasks.push(t) }
    drop(tasks);
    publish();
}

async fn serve() -> Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", TCP_PORT)).await
        .map_err(|e| anyhow!(crate::tr!("Не вдалося відкрити порт {0}: {1}", TCP_PORT, e)))?;
    loop {
        let (stream, addr) = listener.accept().await?;
        // Only the local network (private / link-local addresses) may connect.
        let local = match addr.ip() { std::net::IpAddr::V4(ip) => ip.is_private() || ip.is_link_local() || ip.is_loopback(), std::net::IpAddr::V6(ip) => ip.is_loopback() || (ip.segments()[0] & 0xffc0) == 0xfe80 || (ip.segments()[0] & 0xfe00) == 0xfc00 };
        if !local { continue }
        let _ = stream.set_nodelay(true);
        tokio::spawn(async move { let _ = session(stream).await; });
    }
}

async fn beacon() {
    let Ok(sock) = tokio::net::UdpSocket::bind(("0.0.0.0", 0)).await else { return };
    let _ = sock.set_broadcast(true);
    loop {
        let n: [u8; 16] = rand::random();
        let tags: Vec<String> = peers().iter().filter_map(|p| {
            let key = beacon_key(&B64.decode(&p.spk).ok()?)?;
            let mut m = <Hmac<Sha256> as Mac>::new_from_slice(&key).ok()?; m.update(&n);
            Some(hex::encode(&m.finalize().into_bytes()[..8]))
        }).collect();
        let mut msg = json!({"pm": 1, "p": TCP_PORT, "n": hex::encode(n), "t": tags});
        if *PAIR_UNTIL.lock() > crate::db::now_ms() { msg["pair"] = json!({"name": device_name(), "id": my_id()}) }
        let bytes = msg.to_string();
        let _ = sock.send_to(bytes.as_bytes(), ("255.255.255.255", UDP_PORT)).await;
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

// ---------- pairing (the UI drives these) ----------
pub fn start_pairing() {
    *PAIR_UNTIL.lock() = crate::db::now_ms() + 180_000;
    VIEW.send_modify(|v| v.pairing = Pairing::Waiting);
}
pub fn cancel_pairing() {
    *PAIR_UNTIL.lock() = 0;
    if let Some(tx) = PAIR_DECISION.lock().take() { let _ = tx.send(false); }
    VIEW.send_modify(|v| v.pairing = Pairing::Idle);
}
pub fn confirm_pairing(ok: bool) {
    if let Some(tx) = PAIR_DECISION.lock().take() { let _ = tx.send(ok); }
    if !ok { cancel_pairing() }
}

// ---------- one connection from a phone ----------
async fn session(mut stream: TcpStream) -> Result<()> {
    // 1. The phone commits to its hello first (so neither side can grind its keys to fake a matching code).
    let commit = tokio::time::timeout(Duration::from_secs(10), read_frame(&mut stream)).await??;
    let commit: Value = serde_json::from_slice(&commit)?;
    let commit = B64.decode(commit["c"].as_str().unwrap_or(""))?;
    // 2. Our hello.
    let esk = EphemeralSecret::random(&mut p256::elliptic_curve::rand_core::OsRng);
    let sk = secret();
    // No name in the clear: the phone learns it from the pairing beacon, later inside the encrypted channel.
    let me = Hello { id: my_id(), name: String::new(), kind: "desktop".into(), spk: sec1(&sk.public_key()), epk: sec1(&esk.public_key()), n: rand::random::<[u8; 32]>().to_vec(), pair: false };
    write_frame(&mut stream, me.to_json().to_string().as_bytes()).await?;
    // 3. Their hello must match the commitment.
    let raw = tokio::time::timeout(Duration::from_secs(10), read_frame(&mut stream)).await??;
    if Sha256::digest(&raw).as_slice() != commit.as_slice() { bail!("commitment mismatch") }
    let them = Hello::from_json(&serde_json::from_slice(&raw)?)?;
    let known = peers().into_iter().find(|p| p.id == them.id);
    let pairing = match &known {
        Some(p) if B64.decode(&p.spk).ok().as_deref() == Some(&them.spk[..]) => false,
        _ if them.pair && *PAIR_UNTIL.lock() > crate::db::now_ms() && !PAIR_BUSY.swap(true, Relaxed) => true,
        _ => bail!("unknown device"),
    };
    let result = run(stream, me, them, esk, sk, pairing).await;
    if pairing { PAIR_BUSY.store(false, Relaxed) }
    result
}

async fn run(stream: TcpStream, me: Hello, them: Hello, esk: EphemeralSecret, sk: SecretKey, pairing: bool) -> Result<()> {
    let (epk_them, spk_them) = (parse_pk(&them.epk)?, parse_pk(&them.spk)?);
    let mut t = PROTO.to_vec();
    them.transcript(&mut t); me.transcript(&mut t); // client first, then server
    let th = Sha256::digest(&t);
    let mut ikm = esk.diffie_hellman(&epk_them).raw_secret_bytes().to_vec();
    ikm.extend(dh(&sk, &spk_them));
    let keys = hkdf(&th, &ikm, "pm-link-v1 keys", 64);
    let mut ch = Channel { rx: Aes256Gcm::new_from_slice(&keys[..32])?, tx: Aes256Gcm::new_from_slice(&keys[32..])?, ntx: 0, nrx: 0 };
    let (mut rd, mut wr) = stream.into_split();

    if pairing {
        let sas = hkdf(&th, &ikm, "pm-link-v1 sas", 4);
        let code = format!("{:06}", u32::from_be_bytes([sas[0], sas[1], sas[2], sas[3]]) % 1_000_000);
        let (tx, rx) = oneshot::channel();
        *PAIR_DECISION.lock() = Some(tx);
        if std::env::var_os("PM_LINK_TEST").is_some() { println!("link pairing code {code}"); }
        VIEW.send_modify(|v| v.pairing = Pairing::Confirm { code, name: them.name.clone() });
        if std::env::var_os("PM_LINK_TEST").is_some() { confirm_pairing(true) }
        // Wait for both: our user's answer and the phone's.
        let mut mine = Box::pin(tokio::time::timeout(Duration::from_secs(120), rx));
        let mut theirs: Option<bool> = None;
        let mut ours: Option<bool> = None;
        while ours.is_none() || theirs.is_none() {
            tokio::select! {
                r = &mut mine, if ours.is_none() => {
                    let ok = matches!(r, Ok(Ok(true)));
                    ours = Some(ok);
                    write_frame(&mut wr, &ch.seal(&json!({"t": "pair", "ok": ok}))).await?;
                    if !ok { break }
                }
                f = read_frame(&mut rd), if theirs.is_none() => {
                    let v = ch.open(&f?)?;
                    let ok = v["t"] == "pair" && v["ok"].as_bool() == Some(true);
                    theirs = Some(ok);
                    if !ok { break }
                }
            }
        }
        *PAIR_DECISION.lock() = None;
        if ours != Some(true) || theirs != Some(true) {
            VIEW.send_modify(|v| v.pairing = Pairing::Failed(crate::tr!("Пристрій не під'єднано")));
            return Ok(());
        }
        *PAIR_UNTIL.lock() = 0;
        {
            let mut i = IDENT.lock();
            i.peers.retain(|p| p.id != them.id);
            i.peers.push(Peer { id: them.id.clone(), name: them.name.clone(), kind: them.kind.clone(), spk: B64.encode(&them.spk), paired_at: crate::db::now_ms() });
            save_ident(&i);
        }
        VIEW.send_modify(|v| v.pairing = Pairing::Done(them.name.clone()));
    }

    let id = them.id.clone();
    if std::env::var_os("PM_LINK_TEST").is_some() { println!("link session {id}"); }
    let (tx, mut out) = mpsc::unbounded_channel::<Value>();
    if let Some(old) = SESSIONS.lock().insert(id.clone(), tx.clone()) { let _ = old.send(json!({"t": "bye"})); }
    publish();
    let _ = tx.send(json!({"t": "info", "name": device_name()}));
    let _ = tx.send(state_msg());
    if crate::settings::get().link_sync { let _ = tx.send(sync_snapshot()); }

    let tx_ch = std::sync::Arc::new(Mutex::new(ch));
    let rx_ch = tx_ch.clone();
    let writer = tokio::spawn(async move {
        loop {
            let msg = match tokio::time::timeout(Duration::from_secs(15), out.recv()).await { Ok(Some(m)) => m, Ok(None) => break, Err(_) => json!({"t": "ping"}) };
            let bye = msg["t"] == "bye";
            let data = tx_ch.lock().seal(&msg);
            if write_frame(&mut wr, &data).await.is_err() || bye { break }
        }
    });
    let reader = async {
        loop {
            let f = tokio::time::timeout(Duration::from_secs(45), read_frame(&mut rd)).await??;
            let v = rx_ch.lock().open(&f)?;
            if v["t"] == "bye" { return Ok::<(), anyhow::Error>(()) }
            handle(&id, v);
        }
    };
    let _ = reader.await;
    writer.abort();
    let mut s = SESSIONS.lock();
    if s.get(&id).map_or(false, |t| t.same_channel(&tx)) { s.remove(&id); REMOTES.lock().remove(&id); }
    drop(s);
    publish();
    Ok(())
}

// ---------- messages ----------
pub fn send(id: &str, v: Value) { if let Some(tx) = SESSIONS.lock().get(id) { let _ = tx.send(v); } }
fn broadcast(v: Value) { for tx in SESSIONS.lock().values() { let _ = tx.send(v.clone()); } }

fn state_msg() -> Value {
    let s = crate::playback::state();
    let sh = crate::playback::shared();
    let song = s.current().cloned();
    json!({"t": "state", "song": song.as_ref().map(song_to_wire), "playing": s.playing, "pos": sh.position_ms(),
        "dur": song.as_ref().and_then(|x| x.duration).map(|d| d as u64 * 1000).unwrap_or(0),
        "vol": crate::settings::get().volume, "title": s.title, "len": s.queue.len(), "idx": s.index})
}

/// Pauses every other device that is playing (one device plays at a time, like Spotify).
fn pause_others(except: Option<&str>) {
    let remotes = REMOTES.lock().clone();
    for (id, r) in remotes { if r.playing && Some(id.as_str()) != except { send(&id, json!({"t": "cmd", "c": "pause"})) } }
}

fn handle(from: &str, v: Value) {
    if std::env::var_os("PM_LINK_TEST").is_some() { println!("link recv {}", v); }
    let settings = crate::settings::get();
    match v["t"].as_str().unwrap_or("") {
        "ping" | "pong" => {}
        // The phone's (possibly new) name, sent only inside the encrypted channel.
        "info" => {
            let name: String = v["name"].as_str().unwrap_or("").chars().take(64).collect();
            let mut i = IDENT.lock();
            if let Some(p) = i.peers.iter_mut().find(|p| p.id == from) { if !name.is_empty() && p.name != name { p.name = name; save_ident(&i); drop(i); publish() } }
        }
        "state" => {
            let r = Remote { song: v.get("song").and_then(wire_to_song), playing: v["playing"].as_bool().unwrap_or(false), pos: v["pos"].as_u64().unwrap_or(0),
                dur: v["dur"].as_u64().unwrap_or(0), vol: v["vol"].as_f64().unwrap_or(1.0) as f32, title: v["title"].as_str().map(String::from), received: crate::db::now_ms() };
            REMOTES.lock().insert(from.to_string(), r);
            publish();
        }
        "cmd" if settings.link_control => match v["c"].as_str().unwrap_or("") {
            "toggle" => crate::playback::toggle(),
            "play" => crate::playback::set_playing(true),
            "pause" => crate::playback::set_playing(false),
            "next" => crate::playback::next(),
            "prev" => crate::playback::previous(),
            "seek" => crate::playback::seek(v["v"].as_u64().unwrap_or(0)),
            "vol" => { let x = v["v"].as_f64().unwrap_or(0.8).clamp(0.0, 1.0) as f32; crate::playback::set_volume(x); crate::settings::update(|s| s.volume = x) }
            "skip" => crate::playback::skip_to(v["v"].as_u64().unwrap_or(0) as usize),
            // The phone asks to continue here → there.
            "handoff" => hand_off(from),
            _ => {}
        },
        "take" => {
            let mut queue: Vec<Song> = Vec::new();
            let mut index = v["index"].as_u64().unwrap_or(0) as usize;
            for (i, s) in v["queue"].as_array().cloned().unwrap_or_default().iter().enumerate() {
                match wire_to_song(s) { Some(song) => queue.push(song), None => if i < index { index -= 1 } }
            }
            if queue.is_empty() { return }
            let pos = v["pos"].as_u64().unwrap_or(0);
            crate::playback::play_queue_at(queue, index, pos, v["title"].as_str().map(String::from), v["playing"].as_bool().unwrap_or(true));
            crate::ui::toast_from_bg(crate::tr!("Продовжено з пристрою «{0}»", peer_name(from)));
        }
        "enqueue" => {
            let songs: Vec<Song> = v["songs"].as_array().cloned().unwrap_or_default().iter().filter_map(wire_to_song).collect();
            if songs.is_empty() { return }
            let n = songs.len();
            if v["next"].as_bool().unwrap_or(false) { crate::playback::play_next(songs) } else { crate::playback::add_to_queue(songs) }
            crate::ui::toast_from_bg(crate::tr!("«{0}» надіслав пісень: {1}", peer_name(from), n));
        }
        "sync" if settings.link_sync => {
            let changed = sync_merge(&v);
            *LAST_FP.lock() = crate::db::sync_fingerprint();
            VIEW.send_modify(|x| x.last_sync = Some(crate::db::now_ms()));
            if changed { crate::db::bump() }
        }
        _ => {}
    }
}

fn peer_name(id: &str) -> String { peers().into_iter().find(|p| p.id == id).map(|p| p.name).unwrap_or_default() }

/// Sends what plays here to `to` and pauses here (the music continues there).
pub fn hand_off(to: &str) {
    let s = crate::playback::state();
    if s.queue.is_empty() { return }
    let pos = crate::playback::shared().position_ms();
    send(to, json!({"t": "take", "queue": s.queue.iter().map(song_to_wire).collect::<Vec<_>>(), "index": s.index, "pos": pos, "title": s.title, "playing": true}));
    crate::playback::set_playing(false);
}
/// Asks `from` to continue its music here.
pub fn pull(from: &str) { send(from, json!({"t": "cmd", "c": "handoff"})); }
pub fn remote(id: &str, c: &str, v: Option<Value>) {
    let mut m = json!({"t": "cmd", "c": c});
    if let Some(v) = v { m["v"] = v }
    // Make the UI react at once instead of waiting for the device's next state.
    if c == "toggle" || c == "pause" || c == "play" {
        if let Some(r) = REMOTES.lock().get_mut(id) { r.pos = r.position(); r.received = crate::db::now_ms(); r.playing = match c { "play" => true, "pause" => false, _ => !r.playing } }
        publish();
    }
    send(id, m);
}
pub fn sync_now() { if crate::settings::get().link_sync { broadcast(sync_snapshot()) } }

// ---------- wire formats (the phone's JSON shapes) ----------
pub fn song_to_wire(s: &Song) -> Value {
    json!({"id": s.id, "title": s.title, "artists": s.artists.iter().map(|a| json!({"n": a.name, "i": a.id})).collect::<Vec<_>>(),
        "album": s.album.as_ref().map(|a| json!({"n": a.name, "i": a.id})), "duration": s.duration.map(|d| d as i64).unwrap_or(-1), "thumb": s.thumbnail})
}
pub fn wire_to_song(o: &Value) -> Option<Song> {
    let id = o["id"].as_str()?.to_string();
    if id.is_empty() || id.starts_with("content:") || id.starts_with("file:") { return None } // files that exist only on the phone
    let opt = |x: &Value| x.as_str().filter(|s| !s.is_empty() && *s != "null").map(String::from);
    let r = |x: &Value| Ref { name: x["n"].as_str().unwrap_or("").to_string(), id: opt(&x["i"]) };
    Some(Song { id, title: o["title"].as_str().unwrap_or("").to_string(),
        artists: o["artists"].as_array().map(|a| a.iter().map(r).collect()).unwrap_or_default(),
        album: o.get("album").filter(|a| a.is_object()).map(r),
        duration: o["duration"].as_i64().filter(|d| *d > 0).map(|d| d as u32), thumbnail: opt(&o["thumb"]) })
}
fn item_to_wire(item: &Item) -> Option<Value> {
    Some(match item {
        Item::Album { id, title, artists, thumbnail, .. } => json!({"kind": "album", "id": id, "title": title, "subtitle": artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", "), "thumb": thumbnail}),
        Item::Playlist { id, title, subtitle, thumbnail } => json!({"kind": "playlist", "id": id, "title": title, "subtitle": subtitle, "thumb": thumbnail}),
        Item::Artist { id, name, subtitle, thumbnail } => json!({"kind": "artist", "id": id, "title": name, "subtitle": subtitle, "thumb": thumbnail}),
        _ => return None,
    })
}
fn wire_to_item(o: &Value) -> Option<Item> {
    let s = |k: &str| o[k].as_str().unwrap_or("").to_string();
    let thumb = o["thumb"].as_str().filter(|t| !t.is_empty() && *t != "null").map(String::from);
    if s("id").is_empty() { return None }
    Some(match o["kind"].as_str()? {
        "album" => Item::Album { id: s("id"), title: s("title"), artists: vec![Ref { name: s("subtitle"), id: None }], year: None, thumbnail: thumb },
        "playlist" => Item::Playlist { id: s("id"), title: s("title"), subtitle: s("subtitle"), thumbnail: thumb },
        "artist" => Item::Artist { id: s("id"), name: s("title"), subtitle: s("subtitle"), thumbnail: thumb },
        _ => return None,
    })
}

// ---------- library sync (likes, playlists, saved albums / playlists / artists) ----------
fn sync_snapshot() -> Value {
    let l = crate::db::sync_dump();
    json!({"t": "sync",
        "likes": l.likes.iter().map(|(s, at)| json!([song_to_wire(s), at])).collect::<Vec<_>>(),
        "unlikes": l.tombs.iter().filter(|(k, _, _)| k == "like").map(|(_, key, at)| json!([key, at])).collect::<Vec<_>>(),
        "saved": l.saved.iter().filter_map(|(item, at)| Some(json!([item_to_wire(item)?, at]))).collect::<Vec<_>>(),
        "unsaved": l.tombs.iter().filter(|(k, _, _)| k == "saved").map(|(_, key, at)| json!([key, at])).collect::<Vec<_>>(),
        "playlists": l.playlists.iter().map(|p| json!({"uid": p.uid, "name": p.name, "desc": p.description, "created": p.created, "modified": p.modified,
            "songs": p.songs.iter().map(song_to_wire).collect::<Vec<_>>()})).collect::<Vec<_>>(),
        "pldel": l.tombs.iter().filter(|(k, _, _)| k == "playlist").map(|(_, key, at)| json!([key, at])).collect::<Vec<_>>(),
    })
}

fn sync_merge(v: &Value) -> bool {
    let arr = |k: &str| v[k].as_array().cloned().unwrap_or_default();
    let pair = |x: &Value| (x[0].clone(), x[1].as_i64().unwrap_or(0));
    let likes = arr("likes").iter().map(pair).filter_map(|(s, at)| Some((wire_to_song(&s)?, at))).collect();
    let unlikes = arr("unlikes").iter().map(pair).filter_map(|(k, at)| Some((k.as_str()?.to_string(), at))).collect();
    let saved = arr("saved").iter().map(pair).filter_map(|(i, at)| Some((wire_to_item(&i)?, at))).collect();
    let unsaved = arr("unsaved").iter().map(pair).filter_map(|(k, at)| Some((k.as_str()?.to_string(), at))).collect();
    let playlists = arr("playlists").iter().filter_map(|p| Some(crate::db::SyncPlaylist {
        uid: p["uid"].as_str()?.to_string(), name: p["name"].as_str().unwrap_or("").to_string(), description: p["desc"].as_str().unwrap_or("").to_string(),
        created: p["created"].as_i64().unwrap_or(0), modified: p["modified"].as_i64().unwrap_or(0),
        songs: p["songs"].as_array().map(|a| a.iter().filter_map(wire_to_song).collect()).unwrap_or_default() })).collect();
    let pldel = arr("pldel").iter().map(pair).filter_map(|(k, at)| Some((k.as_str()?.to_string(), at))).collect();
    crate::db::sync_merge(crate::db::SyncData { likes, saved, playlists, tombs: vec![] }, unlikes, unsaved, pldel)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn channel_roundtrip_and_tamper() {
        let k = [7u8; 32];
        let mk = || Channel { tx: Aes256Gcm::new_from_slice(&k).unwrap(), rx: Aes256Gcm::new_from_slice(&k).unwrap(), ntx: 0, nrx: 0 };
        let (mut a, mut b) = (mk(), mk());
        let m = a.seal(&json!({"t": "ping"}));
        assert_eq!(b.open(&m).unwrap()["t"], "ping");
        let mut bad = a.seal(&json!({"t": "x"})); bad[0] ^= 1;
        assert!(b.open(&bad).is_err());
    }
    #[test]
    fn wire_song_roundtrip() {
        let s = Song { id: "abc".into(), title: "T".into(), artists: vec![Ref { name: "A".into(), id: Some("UC1".into()) }], album: None, duration: Some(200), thumbnail: Some("https://x".into()) };
        assert_eq!(wire_to_song(&song_to_wire(&s)).unwrap(), s);
        assert!(wire_to_song(&json!({"id": "content://media/1", "title": "x"})).is_none());
    }
}

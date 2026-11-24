//! The audio engine: one thread that downloads (through the firewall), decodes and plays a track.
//! Opus (YouTube's best quality) is decoded by libopus, AAC by Symphonia; output goes through cpal
//! (PipeWire / PulseAudio / ALSA on Linux). Position is counted from the samples actually played.
use crossbeam_channel::{Receiver, Sender};
use parking_lot::{Condvar, Mutex};
use std::collections::VecDeque;
use std::io::{Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering::*};
use std::sync::Arc;
use std::time::Duration;
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_OPUS};
use symphonia::core::formats::{FormatOptions, SeekMode, SeekTo};
use symphonia::core::io::{MediaSource, MediaSourceStream};
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::Time;

pub enum Source {
    /// A YouTube stream URL (downloaded progressively; the full file is kept for the disk cache).
    Remote { url: String, size: u64, opus: bool, cache_to: Option<std::path::PathBuf> },
    /// A song fetched by yt-dlp (through the firewall) and piped straight into the decoder.
    YtDlp { video_id: String, economy: bool, cache_to: Option<std::path::PathBuf> },
    File(std::path::PathBuf),
}

/// yt-dlp arguments shared by streaming and downloading: no config files, no cache, no cookies, our proxy only.
pub fn ytdlp_args(video_id: &str, economy: bool) -> Vec<String> {
    let fmt = if economy { "bestaudio[acodec=opus][abr<=80]/worstaudio[acodec=opus]/worstaudio" } else { "bestaudio[acodec=opus]/bestaudio" };
    // The cache only holds YouTube's player script and solved challenges (no cookies, no history): with it a song
    // starts seconds sooner, because the JS challenge isn't solved from scratch for every song.
    let cache = crate::settings::cache_dir().join("yt-dlp").to_string_lossy().to_string();
    let mut args: Vec<String> = vec!["--ignore-config".into(), "--cache-dir".into(), cache, "--no-playlist".into(), "--quiet".into(), "--no-warnings".into(),
        "--no-progress".into(), "--proxy".into(), crate::firewall::proxy_url(), "-f".into(), fmt.into()];
    // Windows zip: deno.exe ships next to the app, not on PATH; tell yt-dlp where its JS runtime is.
    if let Some(deno) = crate::sys::bundled("deno") { args.push("--js-runtimes".into()); args.push(format!("deno:{}", deno.display())) }
    args.push(format!("https://music.youtube.com/watch?v={video_id}"));
    args
}

pub enum Cmd {
    Load(Source, u64), Pause(bool), Seek(u64), Stop,
    /// The next song, already on disk: blend into it `fade_ms` before the current one ends at `end_ms`
    /// (0 = gapless switch at the end). `end_ms` 0 = unknown length: switch at the end.
    Preload { src: Source, end_ms: u64, fade_ms: u64 },
    ClearPreload,
    /// Re-check the output device now (the user picked another one in Settings).
    Device,
}
#[derive(Debug)]
pub enum Event { Ended, Error(String), /** the preloaded song is now what is heard */ Advanced }

// ---------- equalizer (10 bands, applied in the output: changes are heard at once) ----------
pub const EQ_FREQS: [f32; 10] = [32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
#[derive(Clone, Copy, Default)]
struct Biquad { b0: f32, b1: f32, b2: f32, a1: f32, a2: f32, z: [[f32; 2]; 2] }
impl Biquad {
    /// RBJ peaking filter.
    fn set(&mut self, f: f32, gain_db: f32, rate: f32) {
        let a = 10f32.powf(gain_db / 40.0);
        let w0 = 2.0 * std::f32::consts::PI * (f / rate).min(0.49);
        let alpha = w0.sin() / (2.0 * 1.1);
        let (c, a0) = (w0.cos(), 1.0 + alpha / a);
        self.b0 = (1.0 + alpha * a) / a0; self.b1 = -2.0 * c / a0; self.b2 = (1.0 - alpha * a) / a0;
        self.a1 = -2.0 * c / a0; self.a2 = (1.0 - alpha / a) / a0;
    }
    #[inline] fn run(&mut self, x: f32, ch: usize) -> f32 {
        let y = self.b0 * x + self.z[ch][0];
        self.z[ch][0] = self.b1 * x - self.a1 * y + self.z[ch][1];
        self.z[ch][1] = self.b2 * x - self.a2 * y;
        y
    }
}
#[derive(Default)]
pub struct Eq { on: bool, bands: [Biquad; 10], pre: f32 }
impl Eq {
    fn set(&mut self, gains: Option<[f32; 10]>, rate: f32) {
        match gains {
            Some(g) if g.iter().any(|x| x.abs() > 0.05) => {
                for (b, (f, db)) in self.bands.iter_mut().zip(EQ_FREQS.iter().zip(g)) { b.set(*f, db.clamp(-12.0, 12.0), rate) }
                // Headroom so boosts never clip.
                let boost = g.iter().cloned().fold(0.0f32, f32::max).min(12.0);
                self.pre = 10f32.powf(-boost / 20.0);
                self.on = true;
            }
            _ => self.on = false,
        }
    }
    #[inline] fn process(&mut self, l: f32, r: f32) -> (f32, f32) {
        if !self.on { return (l, r) }
        let (mut l, mut r) = (l * self.pre, r * self.pre);
        for b in self.bands.iter_mut() { l = b.run(l, 0); r = b.run(r, 1); }
        (l, r)
    }
}

#[derive(Default)]
pub struct Shared {
    pub paused: AtomicBool,
    pub buffering: AtomicBool,
    pub loaded: AtomicBool,
    base_ms: AtomicU64,
    frames: AtomicU64,
    rate: AtomicU32,
    volume: AtomicU32,
    pub duration_ms: AtomicU64,
    eq: Mutex<Eq>,
    /// How far behind the last written frame the speakers are (device buffer + this callback), in µs.
    out_lag_us: AtomicU64,
    /// Name of the output device in use.
    pub device: Mutex<String>,
    /// The output stream reported an error (device unplugged / gone): reopen it.
    dead: AtomicBool,
}

impl Shared {
    pub fn position_ms(&self) -> u64 {
        let rate = self.rate.load(Relaxed).max(1) as u64;
        // Frames are counted when handed to the sound card; what is heard lags by the output buffer.
        let written = self.base_ms.load(Relaxed) + self.frames.load(Relaxed) * 1000 / rate;
        let lag = if self.frames.load(Relaxed) > 0 { self.out_lag_us.load(Relaxed) / 1000 } else { 0 };
        written.saturating_sub(lag).max(self.base_ms.load(Relaxed))
    }
    pub fn set_volume(&self, v: f32) { self.volume.store(v.clamp(0.0, 1.0).to_bits(), Relaxed) }
    pub fn volume(&self) -> f32 { f32::from_bits(self.volume.load(Relaxed)) }
    /// Equalizer gains in dB for EQ_FREQS, or None = off.
    pub fn set_eq(&self, gains: Option<[f32; 10]>) { let rate = self.rate.load(Relaxed).max(8000) as f32; self.eq.lock().set(gains, rate) }
}

pub struct Engine { pub tx: Sender<Cmd>, pub shared: Arc<Shared>, pub events: Receiver<Event> }

// ---------- progressive HTTP source ----------
struct Buf { data: Vec<u8>, done: bool, failed: Option<String> }
struct RemoteSource { buf: Arc<(Mutex<Buf>, Condvar)>, size: u64, pos: u64, cancel: Arc<AtomicBool>, known: Arc<AtomicU64> }

impl RemoteSource {
    fn open(url: String, size: u64, cache_to: Option<std::path::PathBuf>, cancel: Arc<AtomicBool>) -> Self {
        let buf = Arc::new((Mutex::new(Buf { data: Vec::with_capacity(size as usize), done: false, failed: None }), Condvar::new()));
        let b = buf.clone();
        let c = cancel.clone();
        std::thread::spawn(move || {
            let http = crate::firewall::blocking();
            // googlevideo serves the file in ranged chunks of at most 512 KiB to clients like ours.
            const CHUNK: u64 = 512 * 1024; // larger ranges are refused (403) without a PO token
            let mut off = 0u64;
            let total = if size > 0 { size } else { u64::MAX };
            while off < total && !c.load(Relaxed) {
                let end = (off + CHUNK - 1).min(total.saturating_sub(1));
                let asked = end - off + 1;
                let r = http.get(&url).header("Range", format!("bytes={off}-{end}")).send().and_then(|r| r.error_for_status()).and_then(|r| r.bytes());
                match r {
                    Ok(bytes) if !bytes.is_empty() => {
                        off += bytes.len() as u64;
                        let (m, cv) = &*b;
                        m.lock().data.extend_from_slice(&bytes);
                        cv.notify_all();
                        if (bytes.len() as u64) < asked { break } // server had less than asked: end of file
                    }
                    Ok(_) => break,
                    Err(e) => { let (m, cv) = &*b; m.lock().failed = Some(e.to_string()); cv.notify_all(); return }
                }
            }
            let (m, cv) = &*b;
            let mut g = m.lock();
            g.done = true;
            if let (Some(path), false) = (cache_to, c.load(Relaxed)) { let _ = std::fs::write(path, &g.data); }
            cv.notify_all();
        });
        RemoteSource { buf, size, pos: 0, cancel, known: Arc::new(AtomicU64::new(0)) }
    }
}

impl RemoteSource {
    /// Runs `yt-dlp -o -` and feeds its output into the buffer as it arrives (size unknown until it finishes).
    fn ytdlp(video_id: String, economy: bool, cache_to: Option<std::path::PathBuf>, cancel: Arc<AtomicBool>) -> Self {
        let buf = Arc::new((Mutex::new(Buf { data: Vec::with_capacity(6 << 20), done: false, failed: None }), Condvar::new()));
        let b = buf.clone();
        let c = cancel.clone();
        let known = Arc::new(AtomicU64::new(0));
        let k = known.clone();
        std::thread::spawn(move || {
            // The exact file size is written to a side file before the download starts (the WebM reader needs it).
            let size_file = crate::settings::cache_dir().join(format!("{video_id}.size"));
            let _ = std::fs::remove_file(&size_file);
            let mut args = ytdlp_args(&video_id, economy);
            args.splice(0..0, ["-o".to_string(), "-".to_string(), "--print-to-file".to_string(),
                "before_dl:%(filesize,filesize_approx)s".to_string(), size_file.to_string_lossy().to_string()]);
            let child = crate::sys::command("yt-dlp").args(&args)
                .stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped()).stdin(std::process::Stdio::null()).spawn();
            let mut child = match child {
                Ok(ch) => ch,
                Err(e) => { let (m, cv) = &*b; m.lock().failed = Some(format!("yt-dlp: {e}")); cv.notify_all(); return }
            };
            let mut out = child.stdout.take().unwrap();
            let mut chunk = vec![0u8; 64 * 1024];
            loop {
                if c.load(Relaxed) { let _ = child.kill(); let _ = child.wait(); return }
                match out.read(&mut chunk) {
                    Ok(0) => break,
                    Ok(n) => {
                        if k.load(Relaxed) == 0 {
                            let sz = std::fs::read_to_string(&size_file).ok().and_then(|t| t.trim().parse::<f64>().ok()).unwrap_or(0.0) as u64;
                            k.store(sz.max(1), Relaxed);
                            let _ = std::fs::remove_file(&size_file);
                        }
                        let (m, cv) = &*b; m.lock().data.extend_from_slice(&chunk[..n]); cv.notify_all();
                    }
                    Err(e) => { let (m, cv) = &*b; m.lock().failed = Some(e.to_string()); cv.notify_all(); let _ = child.kill(); return }
                }
            }
            let status = child.wait();
            let mut err = String::new();
            if let Some(mut e) = child.stderr.take() { let _ = e.read_to_string(&mut err); }
            let (m, cv) = &*b;
            let mut g = m.lock();
            if g.data.is_empty() || !status.map(|s| s.success()).unwrap_or(false) {
                g.failed = Some(err.lines().last().unwrap_or("yt-dlp failed").to_string());
            } else if let Some(path) = cache_to { let _ = std::fs::write(path, &g.data); }
            g.done = true;
            cv.notify_all();
        });
        // Wait (briefly) for the size so the reader can be opened with it.
        let t0 = std::time::Instant::now();
        while known.load(Relaxed) == 0 && t0.elapsed() < Duration::from_secs(60) {
            let g = buf.0.lock(); if g.failed.is_some() || g.done { break } drop(g);
            std::thread::sleep(Duration::from_millis(20));
        }
        let size = match known.load(Relaxed) { 0 | 1 => 0, n => n };
        RemoteSource { buf, size, pos: 0, cancel, known }
    }
}

impl Read for RemoteSource {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        let (m, cv) = &*self.buf;
        let mut g = m.lock();
        loop {
            if let Some(e) = &g.failed { return Err(std::io::Error::other(e.clone())) }
            let have = g.data.len() as u64;
            if self.pos < have {
                let n = ((have - self.pos) as usize).min(out.len());
                out[..n].copy_from_slice(&g.data[self.pos as usize..self.pos as usize + n]);
                self.pos += n as u64;
                return Ok(n);
            }
            if g.done || self.cancel.load(Relaxed) { return Ok(0) }
            cv.wait_for(&mut g, Duration::from_millis(200));
        }
    }
}
impl Seek for RemoteSource {
    fn seek(&mut self, p: SeekFrom) -> std::io::Result<u64> {
        let len = if self.size > 0 { self.size as i64 } else { self.buf.0.lock().data.len() as i64 };
        let np = match p { SeekFrom::Start(o) => o as i64, SeekFrom::End(o) => len + o, SeekFrom::Current(o) => self.pos as i64 + o };
        self.pos = np.max(0) as u64;
        Ok(self.pos)
    }
}
impl MediaSource for RemoteSource {
    fn is_seekable(&self) -> bool { true }
    fn byte_len(&self) -> Option<u64> {
        if self.size > 0 { return Some(self.size) }
        let g = self.buf.0.lock();
        g.done.then_some(g.data.len() as u64)
    }
}

// ---------- decoding ----------
enum Dec { Opus(opus::Decoder), Sym(Box<dyn symphonia::core::codecs::Decoder>) }

struct Track {
    format: Box<dyn symphonia::core::formats::FormatReader>,
    dec: Dec,
    track_id: u32,
    src_rate: u32,
    tb: Option<symphonia::core::units::TimeBase>,
    cancel: Arc<AtomicBool>,
    resample_pos: f64,
    last: [f32; 2],
    /// Encoder delay still to drop at the start (frames at the source rate), for gapless joins.
    skip: u64,
}

fn open(src: Source) -> anyhow::Result<Track> {
    let cancel = Arc::new(AtomicBool::new(false));
    let mut hint = Hint::new();
    let ms: Box<dyn MediaSource> = match src {
        Source::Remote { url, size, opus, cache_to } => { hint.with_extension(if opus { "webm" } else { "m4a" }); Box::new(RemoteSource::open(url, size, cache_to, cancel.clone())) }
        Source::YtDlp { video_id, economy, cache_to } => { hint.with_extension("webm"); Box::new(RemoteSource::ytdlp(video_id, economy, cache_to, cancel.clone())) }
        Source::File(p) => {
            if let Some(e) = p.extension().and_then(|e| e.to_str()) { hint.with_extension(e); }
            Box::new(std::fs::File::open(p)?)
        }
    };
    let mss = MediaSourceStream::new(ms, Default::default());
    let probed = symphonia::default::get_probe().format(&hint, mss, &FormatOptions { enable_gapless: true, ..Default::default() }, &MetadataOptions::default())?;
    let format = probed.format;
    let track = format.default_track().ok_or_else(|| anyhow::anyhow!("no audio track"))?;
    let params = track.codec_params.clone();
    let dec = if params.codec == CODEC_TYPE_OPUS { Dec::Opus(opus::Decoder::new(48_000, opus::Channels::Stereo)?) }
        else { Dec::Sym(symphonia::default::get_codecs().make(&params, &DecoderOptions::default())?) };
    let src_rate = if params.codec == CODEC_TYPE_OPUS { 48_000 } else { params.sample_rate.unwrap_or(44_100) };
    let skip = if params.codec == CODEC_TYPE_OPUS { params.delay.unwrap_or(0) as u64 } else { 0 };
    Ok(Track { track_id: track.id, tb: params.time_base, format, dec, src_rate, cancel, resample_pos: 0.0, last: [0.0; 2], skip })
}

impl Track {
    /// Decodes the next packet into interleaved stereo f32 at the source rate. None = end of stream.
    fn next(&mut self, scratch: &mut Vec<f32>) -> anyhow::Result<Option<Vec<f32>>> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(symphonia::core::errors::Error::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
                Err(e) => return Err(e.into()),
            };
            if packet.track_id() != self.track_id { continue }
            match &mut self.dec {
                Dec::Opus(d) => {
                    scratch.resize(5760 * 2, 0.0);
                    let n = d.decode_float(&packet.data, scratch, false)?;
                    let drop = (self.skip as usize).min(n);
                    self.skip -= drop as u64;
                    return Ok(Some(scratch[drop * 2..n * 2].to_vec()));
                }
                Dec::Sym(d) => {
                    let buf = match d.decode(&packet) { Ok(b) => b, Err(symphonia::core::errors::Error::DecodeError(_)) => continue, Err(e) => return Err(e.into()) };
                    let spec = *buf.spec();
                    let mut sb = symphonia::core::audio::SampleBuffer::<f32>::new(buf.capacity() as u64, spec);
                    sb.copy_interleaved_ref(buf);
                    let ch = spec.channels.count().max(1);
                    let s = sb.samples();
                    let mut out = Vec::with_capacity(s.len() / ch * 2);
                    for f in s.chunks(ch) { let l = f[0]; let r = if ch > 1 { f[1] } else { l }; out.push(l); out.push(r); }
                    return Ok(Some(out));
                }
            }
        }
    }

    fn seek(&mut self, ms: u64) -> u64 {
        let to = SeekTo::Time { time: Time::from(ms as f64 / 1000.0), track_id: Some(self.track_id) };
        match self.format.seek(SeekMode::Accurate, to) {
            Ok(s) => {
                if let Dec::Sym(d) = &mut self.dec { d.reset() }
                if let Dec::Opus(d) = &mut self.dec { let _ = d.reset_state(); }
                self.skip = 0;
                self.tb.map(|tb| { let t = tb.calc_time(s.actual_ts); t.seconds * 1000 + (t.frac * 1000.0) as u64 }).unwrap_or(ms)
            }
            Err(_) => ms,
        }
    }

    /// Linear resampling from the source rate to the device rate (only when they differ, e.g. 44.1k AAC → 48k).
    /// Position -1 refers to the last frame of the previous buffer, so there are no clicks at buffer edges.
    fn resample(&mut self, input: Vec<f32>, out_rate: u32) -> Vec<f32> {
        if self.src_rate == out_rate || input.len() < 2 { return input }
        let step = self.src_rate as f64 / out_rate as f64;
        let frames = input.len() / 2;
        let last = self.last;
        let get = |i: isize, c: usize| if i < 0 { last[c] } else { input[i as usize * 2 + c] };
        let mut out = Vec::with_capacity((frames as f64 / step) as usize * 2 + 4);
        let mut p = self.resample_pos;
        while p < (frames - 1) as f64 {
            let i = p.floor() as isize;
            let f = (p - i as f64) as f32;
            for c in 0..2 { let (a, b) = (get(i, c), get(i + 1, c)); out.push(a + (b - a) * f); }
            p += step;
        }
        self.resample_pos = p - frames as f64;
        self.last = [input[(frames - 1) * 2], input[(frames - 1) * 2 + 1]];
        out
    }
}

impl Drop for Track { fn drop(&mut self) { self.cancel.store(true, Relaxed) } }

// ---------- output ----------
pub fn start() -> Engine {
    let (tx, rx) = crossbeam_channel::unbounded::<Cmd>();
    let (etx, erx) = crossbeam_channel::unbounded::<Event>();
    let shared = Arc::new(Shared::default());
    shared.set_volume(crate::settings::get().volume);
    let sh = shared.clone();
    std::thread::Builder::new().name("audio".into()).spawn(move || run(rx, etx, sh)).expect("audio thread");
    Engine { tx, shared, events: erx }
}

/// Output device names the user can pick (empty list = only the system default).
pub fn output_devices() -> Vec<String> {
    use cpal::traits::{DeviceTrait, HostTrait};
    let host = cpal::default_host();
    let mut out: Vec<String> = host.output_devices().map(|it| it.filter_map(|d| d.name().ok()).collect()).unwrap_or_default();
    // ALSA lists every plugin and card variant ("front:CARD=…", "surround51:…"); PipeWire / PulseAudio route by
    // themselves, so only the plain entries are useful there.
    #[cfg(target_os = "linux")]
    out.retain(|n| !n.contains(':') && n != "null" && n != "default");
    out.sort(); out.dedup();
    out
}

type Ring = Arc<Mutex<VecDeque<f32>>>;
struct Output { _stream: cpal::Stream, name: String, rate: u32 }

/// Opens the wanted device ("" = the system default; a missing device falls back to the default too).
fn open_output(want: &str, ring: &Ring, sh: &Arc<Shared>) -> Option<Output> {
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    let host = cpal::default_host();
    let device = (!want.is_empty()).then(|| host.output_devices().ok().and_then(|mut it| it.find(|d| d.name().ok().as_deref() == Some(want)))).flatten()
        .or_else(|| host.default_output_device())?;
    let name = device.name().unwrap_or_default();
    let cfg = device.supported_output_configs().ok()
        .and_then(|mut c| c.find(|c| c.channels() == 2 && c.sample_format() == cpal::SampleFormat::F32 && c.min_sample_rate().0 <= 48_000 && c.max_sample_rate().0 >= 48_000))
        .map(|c| c.with_sample_rate(cpal::SampleRate(48_000)))
        .or_else(|| device.default_output_config().ok())?;
    let out_rate = cfg.sample_rate().0;
    let channels = cfg.channels() as usize;
    let rate = out_rate as u64;
    // A small fixed buffer (~21 ms) instead of the device default, which on PipeWire's ALSA plugin can be
    // hundreds of ms that the reported delay doesn't fully show: the clock (and the lyrics) stay honest.
    let mut stream_cfg = cfg.config();
    if let cpal::SupportedBufferSize::Range { min, max } = cfg.buffer_size() { stream_cfg.buffer_size = cpal::BufferSize::Fixed(1024u32.clamp(*min, *max)) }
    // The output callback, built twice at most (fixed small buffer first, then the device default).
    let make = || { let (r, s) = (ring.clone(), sh.clone()); move |data: &mut [f32], info: &cpal::OutputCallbackInfo| {
        // The first frame written now is heard at `playback`; the last one a callback-length later.
        let ts = info.timestamp();
        let ahead = ts.playback.duration_since(&ts.callback).map(|d| d.as_micros() as u64).unwrap_or(0);
        let chunk = (data.len() / channels.max(1)) as u64 * 1_000_000 / rate.max(1);
        s.out_lag_us.store((ahead + chunk).min(1_000_000), Relaxed);
        let vol = s.volume();
        let paused = s.paused.load(Relaxed);
        let mut q = r.lock();
        let mut eq = s.eq.lock();
        let mut frames = 0u64;
        for frame in data.chunks_mut(channels) {
            let (l, rr) = if paused || q.len() < 2 { (0.0, 0.0) } else { frames += 1; let (l, r) = (q.pop_front().unwrap(), q.pop_front().unwrap()); eq.process(l, r) };
            for (c, x) in frame.iter_mut().enumerate() { *x = (if c % 2 == 0 { l } else { rr }) * vol * vol; }
        }
        s.frames.fetch_add(frames, Relaxed);
    } };
    // Unplugged headphones / a removed USB card end the stream with an error: reopen on whatever is default now.
    let err = { let s = sh.clone(); move |e: cpal::StreamError| { eprintln!("audio output error: {e}"); s.dead.store(true, Relaxed) } };
    let mut st = device.build_output_stream(&stream_cfg, make(), err.clone(), None);
    if st.is_err() { st = device.build_output_stream(&cfg.config(), make(), err, None) }
    let st = st.ok()?;
    st.play().ok()?;
    sh.dead.store(false, Relaxed);
    *sh.device.lock() = name.clone();
    Some(Output { _stream: st, name, rate: out_rate })
}

/// The device that should play now, if it differs from the open one (or the open one is gone).
fn output_changed(cur: &Option<Output>, sh: &Shared) -> bool {
    use cpal::traits::{DeviceTrait, HostTrait};
    if sh.dead.load(Relaxed) || cur.is_none() { return true }
    let want = crate::settings::get().audio_device;
    let name = &cur.as_ref().unwrap().name;
    if want.is_empty() {
        // Following the system: headphones plugged in make them the default on Windows; switch with it.
        cpal::default_host().default_output_device().and_then(|d| d.name().ok()).map_or(false, |d| d != *name)
    } else { want != *name && output_devices().contains(&want) }
}

fn run(rx: Receiver<Cmd>, events: Sender<Event>, sh: Arc<Shared>) {
    let ring: Ring = Arc::new(Mutex::new(VecDeque::with_capacity(48_000 * 4)));
    let mut output = open_output(&crate::settings::get().audio_device, &ring, &sh);
    let mut out_rate = output.as_ref().map_or(48_000, |o| o.rate);
    let mut checked = std::time::Instant::now();
    sh.rate.store(out_rate, Relaxed);

    let mut track: Option<Track> = None;
    let mut scratch = Vec::new();
    let mut finished = false;
    let mut high = out_rate as usize * 2 * 2; // ~2 s of stereo audio buffered ahead
    let mut fpms = out_rate as u64; // frames per 1000 ms
    // Frames pushed into the ring since the last clear (same origin as sh.frames = frames played).
    let mut pushed: u64 = 0;
    // Where the current track's decoding is (track ms at the start + frames decoded since).
    let (mut cur_base, mut dec_frames) = (0u64, 0u64);
    let mut preload: Option<(Source, u64, u64)> = None;
    let mut fade: Option<Fade> = None;
    // Ring frame index where the next song starts being heard (then the UI moves on).
    let mut marker: Option<u64> = None;
    loop {
        // Once a second (or at once after an error / a new choice): follow the output device.
        if sh.dead.load(Relaxed) || checked.elapsed() > Duration::from_millis(1500) {
            checked = std::time::Instant::now();
            if output_changed(&output, &sh) {
                drop(output.take());
                output = open_output(&crate::settings::get().audio_device, &ring, &sh);
                if let Some(o) = &output {
                    if o.rate != out_rate {
                        // A different sample rate: what is buffered no longer fits, so continue from the current position.
                        out_rate = o.rate; sh.rate.store(out_rate, Relaxed);
                        let pos = sh.position_ms();
                        if let Some(t) = track.as_mut() {
                            ring.lock().clear(); fade = None; marker = None; pushed = 0;
                            let at = t.seek(pos);
                            sh.base_ms.store(at, Relaxed); sh.frames.store(0, Relaxed); finished = false; cur_base = at; dec_frames = 0;
                        }
                        high = out_rate as usize * 2 * 2; fpms = out_rate as u64;
                        // The equalizer's filters are computed for the sample rate.
                        crate::playback::apply_eq();
                    }
                } else { sh.dead.store(false, Relaxed) } // no device at all: try again later
            }
        }
        if let Some(m) = marker {
            if sh.frames.load(Relaxed) >= m {
                sh.frames.fetch_sub(m, Relaxed); pushed -= m; marker = None;
                sh.base_ms.store(0, Relaxed);
                let _ = events.send(Event::Advanced);
            }
        }
        let busy = track.is_some() && !finished && ring.lock().len() < high;
        let cmd = if busy { rx.try_recv().ok() } else { rx.recv_timeout(Duration::from_millis(30)).ok() };
        if let Some(cmd) = cmd {
            match cmd {
                Cmd::Load(src, start_ms) => {
                    track = None; ring.lock().clear(); finished = false; fade = None; preload = None; marker = None; pushed = 0;
                    sh.frames.store(0, Relaxed); sh.base_ms.store(0, Relaxed); sh.loaded.store(false, Relaxed);
                    sh.buffering.store(true, Relaxed);
                    match open(src) {
                        Ok(mut t) => {
                            let at = if start_ms > 0 { t.seek(start_ms) } else { 0 };
                            sh.base_ms.store(at, Relaxed); cur_base = at; dec_frames = 0;
                            track = Some(t); sh.loaded.store(true, Relaxed);
                        }
                        Err(e) => { sh.buffering.store(false, Relaxed); let _ = events.send(Event::Error(e.to_string())); }
                    }
                }
                Cmd::Pause(p) => sh.paused.store(p, Relaxed),
                Cmd::Seek(ms) => if let Some(t) = track.as_mut() {
                    ring.lock().clear(); fade = None; marker = None; pushed = 0;
                    let at = t.seek(ms);
                    sh.base_ms.store(at, Relaxed); sh.frames.store(0, Relaxed); finished = false; cur_base = at; dec_frames = 0;
                },
                Cmd::Stop => { track = None; ring.lock().clear(); fade = None; preload = None; marker = None; pushed = 0; sh.loaded.store(false, Relaxed); sh.frames.store(0, Relaxed); sh.base_ms.store(0, Relaxed) }
                Cmd::Preload { src, end_ms, fade_ms } => if fade.is_none() { preload = Some((src, end_ms, fade_ms)) },
                Cmd::ClearPreload => if fade.is_none() { preload = None },
                Cmd::Device => checked = std::time::Instant::now() - Duration::from_secs(10),
            }
            continue;
        }
        if track.is_none() { continue }
        if finished {
            if ring.lock().is_empty() { track = None; sh.loaded.store(false, Relaxed); let _ = events.send(Event::Ended); }
            continue;
        }
        if ring.lock().len() >= high { sh.buffering.store(false, Relaxed); continue }
        // Time to start blending into the preloaded song?
        let cur_ms = cur_base + dec_frames * 1000 / fpms;
        if fade.is_none() {
            if let Some((_, end_ms, fade_ms)) = preload.as_ref() {
                if *fade_ms > 0 && *end_ms > *fade_ms && cur_ms + fade_ms >= *end_ms {
                    let (src, _, fade_ms) = preload.take().unwrap();
                    if let Ok(next) = open(src) {
                        fade = Some(Fade { next, len: fade_ms * fpms / 1000, done: 0, buf: VecDeque::new(), next_dec: 0, next_eof: false, quiet: 0, lead_in: true });
                        marker = Some(pushed);
                    }
                }
            }
        }
        let t = track.as_mut().unwrap();
        let step = t.next(&mut scratch);
        let ended = match step {
            Ok(Some(pcm)) => {
                let pcm = t.resample(pcm, out_rate);
                dec_frames += (pcm.len() / 2) as u64;
                let out = match fade.as_mut() {
                    Some(f) => {
                        f.fill(pcm.len(), out_rate, &mut scratch);
                        let mut out = Vec::with_capacity(pcm.len());
                        for fr in pcm.chunks(2) {
                            // Two streams mixed sample by sample. The old song leaves on an equal-power (cosine) curve over
                            // the whole blend; the new one arrives in the first half (sine), so a drum hit on its first
                            // beat is heard at (almost) full strength instead of being lost under a slow fade-in.
                            let k = (f.done as f32 / f.len.max(1) as f32).min(1.0);
                            let (ga, gb) = ((k * std::f32::consts::FRAC_PI_2).cos(), ((k * 2.0).min(1.0) * std::f32::consts::FRAC_PI_2).sin());
                            let (nl, nr) = (f.buf.pop_front().unwrap_or(0.0), f.buf.pop_front().unwrap_or(0.0));
                            out.push(fr[0] * ga + nl * gb); out.push(fr[1] * ga + nr * gb);
                            // The old song already went quiet (a silent tail): finish the blend sooner.
                            let quiet = fr[0].abs().max(fr[1].abs()) < SILENCE;
                            f.quiet = if quiet { f.quiet + 1 } else { 0 };
                            f.done += if f.quiet > out_rate as u64 / 4 { 4 } else { 1 };
                        }
                        out
                    }
                    None => pcm,
                };
                pushed += (out.len() / 2) as u64;
                let mut q = ring.lock();
                q.extend(out);
                if q.len() > out_rate as usize / 2 { sh.buffering.store(false, Relaxed) }
                drop(q);
                fade.as_ref().map_or(false, |f| f.done >= f.len)
            }
            Ok(None) => true,
            Err(e) => {
                if fade.is_none() && preload.is_none() {
                    finished = true;
                    if ring.lock().is_empty() { let _ = events.send(Event::Error(e.to_string())); track = None; }
                    continue;
                }
                true
            }
        };
        if !ended { continue }
        // The current song is over (or fully blended out): continue with the next one, if it is ready.
        if fade.is_none() {
            if let Some((src, _, _)) = preload.take() {
                if let Ok(next) = open(src) { fade = Some(Fade { next, len: 0, done: 0, buf: VecDeque::new(), next_dec: 0, next_eof: false, quiet: 0, lead_in: false }); marker = Some(pushed) }
            }
        }
        match fade.take() {
            Some(f) => {
                let rest: Vec<f32> = f.buf.into_iter().collect();
                pushed += (rest.len() / 2) as u64;
                ring.lock().extend(rest);
                track = Some(f.next); cur_base = 0; dec_frames = f.next_dec; finished = f.next_eof && f.next_dec == 0;
            }
            None => finished = true,
        }
    }
}

/// Below this a sample counts as silence (about −50 dBFS).
const SILENCE: f32 = 0.003;

/// The song being blended in during a crossfade (decoded ahead into `buf`).
struct Fade { next: Track, len: u64, done: u64, buf: VecDeque<f32>, next_dec: u64, next_eof: bool,
    /// Frames in a row the old song has been silent.
    quiet: u64,
    /// Still dropping the new song's leading silence (only for a blend: a gapless join keeps every sample).
    lead_in: bool }
impl Fade {
    fn fill(&mut self, need: usize, out_rate: u32, scratch: &mut Vec<f32>) {
        while self.buf.len() < need && !self.next_eof {
            match self.next.next(scratch) {
                Ok(Some(p)) => {
                    let mut p = self.next.resample(p, out_rate);
                    self.next_dec += (p.len() / 2) as u64;
                    if self.lead_in {
                        // Skip up to 4 s of silence at the start, so the blend meets the music, not an empty intro.
                        let first = p.chunks(2).position(|f| f[0].abs().max(f[1].abs()) >= SILENCE);
                        match first {
                            Some(i) => { p.drain(..i * 2); self.lead_in = false }
                            None => { p.clear(); if self.next_dec > out_rate as u64 * 4 { self.lead_in = false } }
                        }
                    }
                    self.buf.extend(p)
                }
                _ => self.next_eof = true,
            }
        }
    }
}

/// Self-test helper: decode up to `seconds` of audio without playing it. Returns seconds decoded.
pub fn decode_test(src: Source, seconds: f64) -> anyhow::Result<f64> {
    let mut t = open(src)?;
    let mut scratch = Vec::new();
    let mut frames = 0usize;
    while (frames as f64) < seconds * t.src_rate as f64 {
        match t.next(&mut scratch)? { Some(p) => frames += p.len() / 2, None => break }
    }
    Ok(frames as f64 / t.src_rate as f64)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn eq_flat_is_off_and_boost_is_stable() {
        let mut eq = Eq::default();
        eq.set(Some([0.0; 10]), 48_000.0);
        assert_eq!(eq.process(0.5, -0.25), (0.5, -0.25));
        eq.set(Some([12.0, 12.0, 6.0, 0.0, -6.0, 0.0, 3.0, 6.0, 12.0, 12.0]), 48_000.0);
        let mut peak = 0f32;
        for n in 0..48_000 { let x = (n as f32 * 0.05).sin() * 0.9; let (l, r) = eq.process(x, x); assert!(l.is_finite() && r.is_finite()); peak = peak.max(l.abs()); }
        assert!(peak < 4.0, "peak {peak}");
    }
}

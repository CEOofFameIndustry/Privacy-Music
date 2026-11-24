use super::components::*;
use super::*;
use crate::db;
use crate::lyrics::{karaoke, Lyrics};
use crate::model::{fmt_ms, Item, Song};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Tab { Queue, Lyrics, Related }

pub fn space_pressed() { playback::toggle() }

/// The audio output in use (PipeWire / PulseAudio sink name), refreshed in the background. Bluetooth headphones add
/// their own delay that apps can't measure, so the lyrics offset is remembered per output.
pub static OUTPUT: once_cell::sync::Lazy<parking_lot::Mutex<String>> = once_cell::sync::Lazy::new(|| parking_lot::Mutex::new(String::new()));
pub fn refresh_output() {
    // PipeWire / PulseAudio know the real sink; elsewhere (Windows) the device the engine opened.
    #[cfg(not(target_os = "linux"))] { let d = playback::shared().device.lock().clone(); *OUTPUT.lock() = if d.is_empty() { "default".into() } else { d }; return }
    #[allow(unreachable_code)]
    let name = std::process::Command::new("pactl").arg("get-default-sink").output().ok()
        .filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).filter(|s| !s.is_empty())
        .unwrap_or_else(|| "default".into());
    *OUTPUT.lock() = name;
}
fn is_bluetooth(out: &str) -> bool { let l = out.to_lowercase(); out.starts_with("bluez") || ["bluetooth", "airpods", "buds", "hands-free", "wh-1000", "wf-1000"].iter().any(|k| l.contains(k)) }
/// ms the lyrics are shifted for the current output (+ = later). Bluetooth starts at +200 ms (typical codec delay).
pub fn lyrics_offset() -> i32 {
    let out = OUTPUT.lock().clone();
    SETTINGS.peek().lyrics_offsets.get(&out).copied().unwrap_or(if is_bluetooth(&out) { 200 } else { 0 })
}

/// Pushes the playback clock to the lyrics script (it extrapolates smoothly between pushes).
fn sync_lyrics_clock() { push_lyrics_clock(false) }
fn push_lyrics_clock(snap: bool) {
    let sh = playback::shared();
    use std::sync::atomic::Ordering::Relaxed;
    let pos = (sh.position_ms() as i64 - lyrics_offset() as i64).max(0);
    let playing = PSTATE.peek().playing && sh.loaded.load(Relaxed) && !sh.paused.load(Relaxed) && !sh.buffering.load(Relaxed);
    document::eval(&format!("window.pmSync && window.pmSync({pos}, {playing}, {snap})"));
}

pub fn download(songs: Vec<Song>) { super::dl::download(songs) }

fn toggle_panel(t: Tab) { let cur = *PANEL.peek(); *PANEL.write() = if cur == Some(t) { None } else { Some(t) } }

/// Bottom bar: song (click = full-screen player) · transport controls with the progress bar · volume and panels.
#[component]
pub fn PlayerBar() -> Element {
    let st = PSTATE.read();
    let song = st.current().cloned();
    let vol = SETTINGS.read().volume;
    let buffering = *BUFFERING.read() && st.playing;
    let has = song.is_some();
    let sid = song.as_ref().map(|s| s.id.clone()).unwrap_or_default();
    // Re-checked only when the song or the library changes (not on every position tick).
    let liked = use_memo(use_reactive!(|sid| { let _ = LIBV.read(); !sid.is_empty() && db::is_liked(&sid) }));
    let liked = *liked.read();
    let dur = song.as_ref().and_then(|s| s.duration).map(|d| d as u64 * 1000).unwrap_or(0).max(1);
    let vol_icon = if vol <= 0.001 { "volume_off" } else if vol < 0.5 { "volume_down" } else { "volume_up" };
    // Another of the user's devices is playing (or was just controlled from here): show it with remote controls.
    let playing_here = st.playing;
    use_effect(use_reactive!(|playing_here| if playing_here && CONTROL.peek().is_some() { *CONTROL.write() = None }));
    let remote = if st.playing { None } else {
        let ctl = CONTROL.read().clone();
        LINK.read().devices.iter().find(|d| d.online && d.remote.as_ref().map_or(false, |r| r.song.is_some() && (r.playing || ctl.as_deref() == Some(d.id.as_str())))).cloned()
    };
    if let Some(dev) = remote { return rsx! { RemoteBar { dev } } }
    rsx! {
        div { class: "playerbar",
            onclick: move |_| if has { let f = *FULL.peek(); *FULL.write() = !f },
            div { class: "pb-song",
                if let Some(song) = song.clone() {
                    {
                        let s2 = song.clone();
                        rsx! {
                            div { class: "pb-open", title: tr!("Відкрити плеєр"),
                                Thumb { url: song.thumbnail.clone(), class: "t56".to_string(), round: false }
                                div { class: "row-text",
                                    div { class: "row-title ellipsis", "{song.title}" }
                                    div { class: "row-sub ellipsis",
                                        for a in song.artists.clone() {
                                            span { class: if a.id.is_some() { "link" } else { "" }, onclick: move |e| { e.stop_propagation(); if let Some(i) = a.id.clone() { go(Route::Artist(i)) } }, "{a.name} " }
                                        }
                                    }
                                }
                            }
                            button { class: if liked { "icon-btn on" } else { "icon-btn dimmed" }, title: tr!("Подобається"), onclick: move |e| { e.stop_propagation(); db::set_liked(&s2, !liked) }, Icon { name: "favorite", fill: liked, size: 24 } }
                        }
                    }
                }
            }
            div { class: "pb-center",
                div { class: "pb-controls", onclick: move |e| e.stop_propagation(),
                    button { class: if st.shuffle { "icon-btn on" } else { "icon-btn dimmed" }, title: tr!("Перемішати"), disabled: !has, onclick: move |_| playback::toggle_shuffle(), Icon { name: "shuffle", size: 22 } }
                    button { class: "icon-btn", title: tr!("Попередня"), disabled: !has, onclick: move |_| playback::previous(), Icon { name: "skip_previous", fill: true, size: 32 } }
                    button { class: if buffering { "icon-btn play buffering" } else { "icon-btn play" }, title: tr!("Відтворити/пауза"), disabled: !has, onclick: move |_| playback::toggle(),
                        Icon { name: if st.playing { "pause" } else { "play_arrow" }, fill: true, size: 32 } }
                    button { class: "icon-btn", title: tr!("Наступна"), disabled: !has, onclick: move |_| playback::next(), Icon { name: "skip_next", fill: true, size: 32 } }
                    button { class: if st.repeat > 0 { "icon-btn on" } else { "icon-btn dimmed" }, title: tr!("Повтор"), disabled: !has, onclick: move |_| playback::cycle_repeat(), Icon { name: if st.repeat == 2 { "repeat_one" } else { "repeat" }, size: 22 } }
                }
                ProgressBar { dur, has }
            }
            div { class: "pb-right", onclick: move |e| e.stop_propagation(),
                if let Some(song) = song.clone() {
                    { let it = Item::Song(song); rsx! { button { class: "icon-btn dimmed", title: tr!("Більше"), onclick: move |e| open_menu(&e, it.clone(), None, None), Icon { name: "more_horiz", size: 24 } } } }
                }
                button { class: if SETTINGS.read().eq_on || song.as_ref().map_or(false, |s| SETTINGS.read().eq_songs.contains_key(&s.id)) { "icon-btn on" } else { "icon-btn dimmed" }, title: tr!("Еквалайзер"),
                    onclick: move |e| { e.stop_propagation(); let v = *EQ_OPEN.peek(); *EQ_OPEN.write() = !v; *DEVICES.write() = false }, Icon { name: "tune", size: 22 } }
                DevicesButton {}
                button { class: "icon-btn", title: tr!("Гучність"), onclick: move |_| { let v = if SETTINGS.peek().volume > 0.0 { 0.0 } else { 0.8 }; playback::set_volume(v); SETTINGS.write().volume = v }, Icon { name: vol_icon, size: 24 } }
                input { class: "volume", r#type: "range", min: "0", max: "100", value: "{(vol * 100.0) as i32}", style: "--p:{vol * 100.0}%",
                    oninput: move |e| if let Ok(v) = e.value().parse::<f32>() { playback::set_volume(v / 100.0); SETTINGS.write().volume = v / 100.0 } }
            }
            if let Some(err) = st.error.clone() { div { class: "pb-error", title: "{err}", {tr!("Не вдалося відтворити")} } }
            DevicesPopover {}
            EqPopover {}
        }
    }
}

/// The paired device whose playback this bar controls, while nothing plays here.
pub static CONTROL: GlobalSignal<Option<String>> = Signal::global(|| None);

/// The player bar while music plays on another device (like Spotify Connect): its song, its controls, its volume.
#[component]
fn RemoteBar(dev: crate::link::DeviceView) -> Element {
    let mut tick = use_signal(|| 0u32);
    use_future(move || async move { loop { sleep_ms(500).await; *tick.write() += 1 } });
    let _ = tick.read();
    let id = dev.id.clone();
    use_effect(use_reactive!(|id| if CONTROL.peek().as_deref() != Some(id.as_str()) { *CONTROL.write() = Some(id.clone()) }));
    let r = dev.remote.clone().unwrap_or_default();
    let dur = r.dur.max(1);
    let pos = r.position().min(dur);
    let (i1, i2, i3, i4, i5, i6) = (dev.id.clone(), dev.id.clone(), dev.id.clone(), dev.id.clone(), dev.id.clone(), dev.id.clone());
    let vol = r.vol;
    rsx! {
        div { class: "playerbar remote",
            div { class: "pb-song",
                if let Some(song) = r.song.clone() {
                    div { class: "pb-open",
                        Thumb { url: song.thumbnail.clone(), class: "t56".to_string(), round: false }
                        div { class: "row-text",
                            div { class: "row-title ellipsis", "{song.title}" }
                            div { class: "row-sub ellipsis", "{song.artist_text()}" }
                            div { class: "remote-on ellipsis", Icon { name: "mobile", size: 14 } span { {tr!("Відтворюється на «{0}»", dev.name)} } }
                        }
                    }
                }
            }
            div { class: "pb-center",
                div { class: "pb-controls",
                    button { class: "icon-btn", title: tr!("Попередня"), onclick: move |_| crate::link::remote(&i1, "prev", None), Icon { name: "skip_previous", fill: true, size: 32 } }
                    button { class: "icon-btn play", title: tr!("Відтворити/пауза"), onclick: move |_| crate::link::remote(&i2, "toggle", None),
                        Icon { name: if r.playing { "pause" } else { "play_arrow" }, fill: true, size: 32 } }
                    button { class: "icon-btn", title: tr!("Наступна"), onclick: move |_| crate::link::remote(&i3, "next", None), Icon { name: "skip_next", fill: true, size: 32 } }
                }
                div { class: "pb-prog",
                    span { class: "t", "{fmt_ms(pos)}" }
                    input { class: "seek", r#type: "range", min: "0", max: "{dur}", value: "{pos}", style: "--p:{pos as f64 * 100.0 / dur as f64}%",
                        onchange: move |e| if let Ok(v) = e.value().parse::<u64>() { crate::link::remote(&i4, "seek", Some(serde_json::json!(v))) } }
                    span { class: "t", "{fmt_ms(r.dur)}" }
                }
            }
            div { class: "pb-right",
                button { class: "btn small", title: tr!("Продовжити музику на цьому комп'ютері"), onclick: move |_| { *CONTROL.write() = None; crate::link::pull(&i5) }, Icon { name: "devices", size: 18 } {tr!("Слухати тут")} }
                DevicesButton {}
                input { class: "volume", r#type: "range", min: "0", max: "100", value: "{(vol * 100.0) as i32}", style: "--p:{vol * 100.0}%", title: tr!("Гучність на «{0}»", dev.name),
                    onchange: move |e| if let Ok(v) = e.value().parse::<f32>() { crate::link::remote(&i6, "vol", Some(serde_json::json!(v / 100.0))) } }
            }
            DevicesPopover {}
        }
    }
}

#[component]
fn DevicesButton() -> Element {
    let active = LINK.read().devices.iter().any(|d| d.online);
    rsx! {
        button { class: if active { "icon-btn on" } else { "icon-btn dimmed" }, title: tr!("Пристрої поруч"),
            onclick: move |e| { e.stop_propagation(); let v = *DEVICES.peek(); *DEVICES.write() = !v }, Icon { name: "devices", size: 22 } }
    }
}

/// Where the music plays: this computer or one of the user's paired devices (switch with one click).
#[component]
fn DevicesPopover() -> Element {
    if !*DEVICES.read() { return rsx! {} }
    let link = LINK.read().clone();
    let enabled = SETTINGS.read().link;
    let st = PSTATE.read().clone();
    let has_queue = st.current().is_some();
    let ctl = CONTROL.read().clone();
    let here = st.playing || ctl.is_none();
    let open_settings = move |_| { *DEVICES.write() = false; *SETTINGS_SECTION.write() = Some("devices"); go(Route::Settings) };
    rsx! {
        div { class: "dev-scrim", onclick: move |e| { e.stop_propagation(); *DEVICES.write() = false } }
        div { class: "dev-pop", onclick: move |e| e.stop_propagation(),
            div { class: "dev-head", {tr!("Пристрої поруч")} }
            if !enabled {
                p { class: "dim small", {tr!("Перемикайте, де грає музика, керуйте нею з телефона й синхронізуйте медіатеку. Лише у вашій локальній мережі, із наскрізним шифруванням.")} }
                button { class: "btn accent", onclick: move |_| { set(|s| s.link = true); crate::link::apply() }, {tr!("Увімкнути")} }
            } else {
                div { class: if here { "dev-row on" } else { "dev-row" },
                    Icon { name: "devices", size: 22 }
                    div { class: "grow", div { {tr!("Цей комп'ютер")} } div { class: "dim small", if st.playing { {tr!("Грає зараз")} } else { {tr!("Тут")} } } }
                    if here { Icon { name: "graphic_eq", size: 20 } }
                }
                for d in link.devices.clone() {
                    {
                        let (a, b) = (d.id.clone(), d.id.clone());
                        let r = d.remote.clone();
                        let playing = r.as_ref().map_or(false, |r| r.playing);
                        let status = if !d.online { tr!("Не в мережі") } else if let Some(song) = r.as_ref().and_then(|r| r.song.clone()).filter(|_| playing) { tr!("Грає: {0}", song.title) } else { tr!("У мережі") };
                        let active = ctl.as_deref() == Some(d.id.as_str()) && !st.playing;
                        rsx! {
                            div { class: if active { "dev-row on" } else if d.online { "dev-row" } else { "dev-row off" },
                                Icon { name: "mobile", size: 22 }
                                div { class: "grow", div { class: "ellipsis", "{d.name}" } div { class: "dim small ellipsis", "{status}" } }
                                if d.online && has_queue && (st.playing || !playing) {
                                    button { class: "btn small", title: tr!("Музика продовжиться на «{0}» з того ж місця", d.name),
                                        onclick: move |_| { crate::link::hand_off(&a); *CONTROL.write() = Some(a.clone()); *DEVICES.write() = false }, {tr!("Грати там")} }
                                }
                                if d.online && playing {
                                    button { class: "btn small", title: tr!("Продовжити на цьому комп'ютері"), onclick: move |_| { *CONTROL.write() = None; crate::link::pull(&b); *DEVICES.write() = false }, {tr!("Слухати тут")} }
                                }
                            }
                        }
                    }
                }
                if link.devices.is_empty() { p { class: "dim small", {tr!("Ще немає під'єднаних пристроїв.")} } }
                button { class: "dev-link", onclick: open_settings, if link.devices.is_empty() { {tr!("Під'єднати телефон…")} } else { {tr!("Налаштування пристроїв")} } }
            }
        }
    }
}

#[component]
fn ProgressBar(dur: u64, has: bool) -> Element {
    let pos = *POS.read();
    rsx! {
        div { class: "pb-prog", onclick: move |e| e.stop_propagation(),
            span { class: "t", "{fmt_ms(pos.min(dur))}" }
            input { class: "seek", r#type: "range", min: "0", max: "{dur}", value: "{pos.min(dur)}", disabled: !has, style: "--p:{pos as f64 * 100.0 / dur as f64}%",
                onchange: move |e| if let Ok(v) = e.value().parse::<u64>() { playback::seek(v) } }
            span { class: "t", if has { "{fmt_ms(dur)}" } else { "0:00" } }
        }
    }
}

/// Right-hand panel: lyrics, up next, related.
#[component]
pub fn SidePanel() -> Element {
    let tab = PANEL.read().unwrap_or(Tab::Queue);
    let song = PSTATE.read().current().cloned();
    rsx! {
        aside { class: "sidepanel", inert: (*FULL.read()).then_some(""),
            div { class: "sp-tabs",
                for (t, label) in [(Tab::Lyrics, tr!("Текст")), (Tab::Queue, tr!("Далі")), (Tab::Related, tr!("Схожі"))] {
                    button { class: if tab == t { "on" } else { "" }, onclick: move |_| *PANEL.write() = Some(t), "{label}" }
                }
            }
            div { key: "{tab:?}", class: "sp-body panel-enter",
                match (tab, song) {
                    (_, None) => rsx! { p { class: "dim center", {tr!("Нічого не грає")} } },
                    (Tab::Queue, _) => rsx! { QueueView {} },
                    (Tab::Lyrics, Some(s)) => if *FULL.read() { rsx! {} } else { rsx! { LyricsView { key: "{s.id}", song: s } } },
                    (Tab::Related, Some(s)) => rsx! { RelatedView { key: "{s.id}", song: s } },
                }
            }
        }
    }
}

/// The player page (like YouTube Music / Spotify on desktop): big artwork with the song's actions on the left,
/// "Up next / Lyrics / Related" on the right. The bottom bar stays, so the controls are always in one place.
#[component]
pub fn PlayerOverlay() -> Element {
    let open = *FULL.read();
    // The page stays mounted (and fully drawn) while closed, so opening only slides an existing layer.
    let mut tab = use_signal(|| Tab::Queue);
    use_resource(move || {
        let open = *FULL.read();
        async move {
            // Reset the tab only after the close slide has finished, never mid-animation.
            sleep_ms(320).await;
            if !open && *tab.peek() != Tab::Queue { tab.set(Tab::Queue); }
        }
    });
    rsx! {
        div { class: if open { "player-overlay open" } else { "player-overlay" },
            FullPlayer { tab }
        }
    }
}

#[component]
fn FullPlayer(mut tab: Signal<Tab>) -> Element {
    let st = PSTATE.read();
    let Some(song) = st.current().cloned() else { return rsx! {} };
    let _ = LIBV.read();
    let liked = db::is_liked(&song.id);
    let downloaded = db::download_path(&song.id).is_some();
    let preview = img(song.thumbnail.as_deref());
    let detail = crate::yt::hq(song.thumbnail.as_deref())
        .filter(|url| Some(url) != song.thumbnail.as_ref()).map(|u| img(Some(&u)));
    // Which HQ cover has finished decoding (so a new song never flashes the previous one's sharp copy).
    let mut hq_ready = use_signal(String::new);
    // A 32-px copy of the cover scaled up is already soft: no costly blur filter.
    let bgimg = song.thumbnail.as_deref().map(|u| img(Some(&crate::yt::resize(u, 32))));
    let (s1, s2, s3, s4) = (song.clone(), song.clone(), song.clone(), song.clone());
    let it = Item::Song(song.clone());
    let link = share_link(&it);
    let cur = *tab.read();
    rsx! {
        div { class: "full",
            div { class: "full-bg", style: bgimg.map(|url| format!("background-image:url('{url}')")) }
            div { class: "full-shade" }
            div { class: "full-left",
                button { class: "icon-btn close", title: tr!("Згорнути"), onclick: move |_| *FULL.write() = false, Icon { name: "expand_more", size: 32 } }
                div { class: if st.playing { "thumb full-art" } else { "thumb full-art paused" },
                    if preview.is_empty() { Icon { name: "music_note" } }
                    else { img { src: "{preview}", decoding: "async", draggable: "false" } }
                    if let Some(url) = detail {
                        img { key: "{url}", class: if *hq_ready.read() == url { "full-art-hq ready" } else { "full-art-hq" },
                            src: "{url}", decoding: "async", draggable: "false",
                            onload: { let u = url.clone(); move |_| hq_ready.set(u.clone()) } }
                    }
                }
                div { class: "full-title", "{song.title}" }
                div { class: "full-artist",
                    for a in song.artists.clone() {
                        span { class: if a.id.is_some() { "link" } else { "" }, onclick: move |_| if let Some(i) = a.id.clone() { *FULL.write() = false; go(Route::Artist(i)) }, "{a.name} " }
                    }
                    if let Some(al) = song.album.clone() {
                        span { class: "dim", "· " } span { class: if al.id.is_some() { "link dim" } else { "dim" }, onclick: move |_| if let Some(i) = al.id.clone() { *FULL.write() = false; go(Route::Album(i)) }, "{al.name}" }
                    }
                }
                div { class: "full-actions",
                    button { class: if liked { "act on" } else { "act" }, onclick: move |_| db::set_liked(&s1, !liked), Icon { name: "favorite", fill: liked } span { if liked { {tr!("Подобається ✓")} } else { {tr!("Подобається")} } } }
                    button { class: "act", onclick: move |_| *ADD_TO.write() = Some(vec![s2.clone()]), Icon { name: "playlist_add" } span { {tr!("Зберегти")} } }
                    if !song.id.starts_with("file:") {
                        button { class: "act", onclick: move |_| if downloaded { db::remove_download(&s3.id) } else { download(vec![s3.clone()]) },
                            Icon { name: if downloaded { "download_done" } else { "download" } } span { if downloaded { {tr!("Завантажено")} } else { {tr!("Завантажити")} } } }
                        button { class: "act", onclick: move |_| playback::start_radio(s4.clone()), Icon { name: "sensors" } span { {tr!("Радіо")} } }
                    }
                    if let Some(l) = link { button { class: "act", onclick: move |_| { copy(&l); toast(tr!("Посилання скопійовано")) }, Icon { name: "link" } span { {tr!("Поділитися")} } } }
                    button { class: "act", onclick: move |e| open_menu(&e, it.clone(), None, None), Icon { name: "more_horiz" } }
                }
            }
            div { class: "full-right",
                div { class: "full-tabs",
                    for (t, label) in [(Tab::Queue, tr!("Далі")), (Tab::Lyrics, tr!("Текст")), (Tab::Related, tr!("Схожі"))] {
                        button { class: if cur == t { "on" } else { "" }, onclick: move |_| tab.set(t), "{label}" }
                    }
                }
                div { key: "{cur:?}", class: "full-body panel-enter",
                    match cur {
                        Tab::Queue => rsx! { QueueView {} },
                        Tab::Lyrics => rsx! { LyricsView { key: "{song.id}", song: song.clone() } },
                        Tab::Related => rsx! { RelatedView { key: "{song.id}", song: song.clone() } },
                    }
                }
            }
        }
    }
}

#[component]
fn QueueView() -> Element {
    let st = PSTATE.read();
    let autoplay = SETTINGS.read().autoplay;
    let mut drag = use_signal(|| None::<(usize, usize)>);
    let queue = &st.queue;
    const PAGE_SIZE: usize = 16;
    // Enough rows to include the playing song and what follows it; more load while scrolling.
    let mut limit = use_signal(|| PSTATE.peek().index + PAGE_SIZE);
    let shown = (*limit.read()).max(st.index + 8);
    // Opening the queue (or a new song) puts the playing song at the top, with what comes next below it.
    let cur_index = st.index;
    let opened = *FULL.read();
    use_effect(use_reactive!(|cur_index, opened| { let _ = (cur_index, opened); document::eval("window.pmShowCurrent && window.pmShowCurrent()"); }));
    let keys = stable_keys(queue);
    rsx! {
        div { class: "queue-head",
            div { class: "grow", div { class: "dim", {tr!("Відтворення з")} } b { {st.title.clone().unwrap_or_else(|| tr!("Черга"))} } }
            button { class: if st.shuffle { "sq on" } else { "sq" }, title: tr!("Перемішати"), onclick: move |_| playback::toggle_shuffle(), Icon { name: "shuffle" } }
            button { class: if st.repeat > 0 { "sq on" } else { "sq" }, title: tr!("Повтор"), onclick: move |_| playback::cycle_repeat(), Icon { name: if st.repeat == 2 { "repeat_one" } else { "repeat" } } }
            button { class: if autoplay { "sq on" } else { "sq" }, title: tr!("Автовідтворення"), onclick: move |_| set(|s| s.autoplay = !s.autoplay), Icon { name: "all_inclusive" } }
        }
        div { "data-flip": "",
            onmouseup: move |_| if let Some((f, t)) = reorder_end(drag) { playback::move_item(f, t) },
            onmouseleave: move |_| if let Some((f, t)) = reorder_end(drag) { playback::move_item(f, t) },
        for (i, s) in queue.iter().take(shown).enumerate() {
            div { key: "{keys[i]}", "data-key": "{keys[i]}", class: reorder_class(*drag.read(), i, if i == st.index { "drag-wrap current" } else { "drag-wrap" }),
                onmouseenter: move |_| { let d = *drag.peek(); if let Some((f, _)) = d { drag.set(Some((f, i))) } },
                span { class: "handle", onmousedown: move |e| { e.prevent_default(); drag.set(Some((i, i))) }, Icon { name: "drag_indicator", size: 18 } }
                ItemRow { item: Item::Song(s.clone()), queue_index: Some(i) }
            }
        }
        }
        if shown < queue.len() {
            div { key: "more-{shown}", style: "height:1px", onvisible: move |e| {
                if e.is_intersecting().unwrap_or(false) { limit.set(shown + PAGE_SIZE) }
            } }
        }
        div { class: "pills",
            button { class: "btn", onclick: move |_| playback::clear_upcoming(), {tr!("Очистити наступні")} }
            button { class: "btn", onclick: move |_| *ADD_TO.write() = Some(PSTATE.read().queue.clone()), {tr!("Зберегти чергу як плейлист")} }
        }
    }
}

/// Lyrics: timed lines (LRCLIB, opt-in) fill as they're sung — or light up whole lines; plain text otherwise.
#[component]
fn LyricsView(song: Song) -> Element {
    let s = SETTINGS.read().clone();
    let sid = song.clone();
    let lrclib = use_memo(|| SETTINGS.read().lrclib);
    let lyrics = use_resource(move || { let song = sid.clone(); let _ = lrclib.read(); async move { bg(async move { crate::lyrics::get(&song).await }).await } });
    let flow = s.lyrics_flow;
    rsx! {
        if !s.lrclib {
            details { class: "card-note lyrics-consent", open: !s.lrclib_hint_hidden,
                summary { {tr!("Текст у такт музиці")} }
                p { class: "dim", {tr!("Щоб рядки підсвічувалися в момент виконання, як у Spotify, потрібен запит до відкритої бази LRCLIB (lrclib.net): ")} {tr!("надсилаються лише назва, виконавець і тривалість — без акаунта й ідентифікаторів.")} }
                div { class: "pills",
                    button { class: "btn accent", onclick: move |_| { crate::lyrics::clear(); set(|s| s.lrclib = true) }, {tr!("Увімкнути синхронізацію")} }
                    if !s.lrclib_hint_hidden { button { class: "btn", onclick: move |_| set(|s| s.lrclib_hint_hidden = true), {tr!("Більше не показувати")} } }
                }
            }
        }
        match &*lyrics.read() {
            None => rsx! { div { class: "loading", span { class: "spinner" } } },
            Some(Err(_)) => rsx! { div { class: "error", {tr!("Не вдалося завантажити текст")} } },
            Some(Ok(None)) => rsx! { div { class: "dim center", {tr!("Для цієї пісні немає тексту.")} } },
            Some(Ok(Some(l))) => rsx! {
                if l.synced.is_some() {
                    div { class: "lyr-tools",
                        div { class: "seg",
                            button { class: if !flow { "on" } else { "" }, onclick: move |_| set(|s| s.lyrics_flow = false), {tr!("Рядками")} }
                            button { class: if flow { "on" } else { "" }, onclick: move |_| set(|s| s.lyrics_flow = true), {tr!("Поступово")} }
                        }
                        LyricsOffset {}
                    }
                }
                if s.lrclib && l.synced.is_none() {
                    div { class: "dim small", {tr!("Текст у такт музиці")} " · " {tr!("Нічого не знайдено")} }
                }
                SyncedOrPlain { lyrics: l.clone(), flow }
            },
        }
    }
}

#[component]
fn SyncedOrPlain(lyrics: Lyrics, flow: bool) -> Element {
    let Some(lines) = lyrics.synced else {
        return rsx! { div { class: "lyrics-plain", for (i, l) in lyrics.text.lines().enumerate() { p { key: "{i}", "{l}" } } div { class: "dim small", {tr!("Текст: {0}", lyrics.source)} } } };
    };
    rsx! {
        div { class: if flow { "lyrics flow" } else { "lyrics" },
            TimedLines { lines, source: lyrics.source }
        }
    }
}

#[component]
fn TimedLines(lines: Vec<crate::lyrics::SyncedLine>, source: String) -> Element {
    use_future(|| async {
        loop {
            sync_lyrics_clock();
            sleep_ms(200).await;
        }
    });
    use_drop(|| { document::eval("window.pmReleaseLyrics()"); });
    let pace = karaoke::pace(&lines);
    rsx! {
        for (i, l) in lines.iter().enumerate() {
            {
                let t = l.time;
                let points = serde_json::to_string(&karaoke::timeline(&lines, i, pace)).unwrap();
                // Words with their character ranges, so a wrapped line still fills word by word.
                let mut words = Vec::new(); let mut at = 0usize;
                for w in l.text.split(' ') { let n = w.chars().count(); words.push((w.to_string(), at, at + n)); at += n + 1; }
                rsx! {
                    div { key: "{i}", class: "lyr-line", "data-time": "{t}", "data-points": "{points}",
                        onclick: move |_| playback::seek(t.max(0) as u64),
                        if l.text.trim().is_empty() { "♪" }
                        for (w, a, b) in words { span { class: "w", "data-a": "{a}", "data-b": "{b}", "{w}" } " " }
                    }
                }
            }
        }
        div { class: "dim small", {tr!("Текст: {0}", source)} }
    }
}

#[component]
fn RelatedView(song: Song) -> Element {
    let id = song.id.clone();
    let r = use_resource(move || { let id = id.clone(); async move { bg(async move { crate::yt::related(&id).await }).await } });
    let out = match &*r.read() {
        None => rsx! { div { class: "loading", span { class: "spinner" } } },
        // Network / YouTube errors are not shown raw: a plain sentence instead.
        Some(Err(_)) => rsx! { div { class: "dim center", {tr!("Не вдалося завантажити схожі пісні. Спробуйте пізніше.")} } },
        Some(Ok(sh)) if sh.is_empty() => rsx! { div { class: "dim center", {tr!("Схожих пісень не знайдено.")} } },
        Some(Ok(sh)) => rsx! { for s in sh.clone() { ShelfView { key: "{s.title}", shelf: s } } },
    };
    out
}

/// Page script for the lyrics: smooth clock + per-word fill + gentle auto-scroll.
pub const LYRICS_JS: &str = include_str!("../../assets/lyrics.js");


/// "Text earlier / later" in 50 ms steps, remembered for the current audio output.
#[component]
fn LyricsOffset() -> Element {
    let _ = SETTINGS.read();
    let ms = lyrics_offset();
    let bt = is_bluetooth(&OUTPUT.lock());
    let nudge = move |d: i32| {
        let out = OUTPUT.lock().clone();
        let v = (lyrics_offset() + d).clamp(-2000, 2000);
        set(move |s| { s.lyrics_offsets.insert(out, v); });
        push_lyrics_clock(true);
    };
    rsx! {
        div { class: "lyr-offset", title: if bt { tr!("Bluetooth-навушники затримують звук. Якщо текст випереджає спів — «пізніше», якщо відстає — «раніше». Запам'ятовується для цього пристрою виводу.") } else { tr!("Якщо текст випереджає спів — «пізніше», якщо відстає — «раніше». Запам'ятовується для цього пристрою виводу.") },
            button { class: "sq", title: tr!("Текст раніше"), onclick: move |_| nudge(-50), "−" }
            span { class: "small", if bt { "BT " } {format!("{:+.2}{}", ms as f64 / 1000.0, tr!(" с"))} }
            button { class: "sq", title: tr!("Текст пізніше"), onclick: move |_| nudge(50), "+" }
        }
    }
}

const EQ_PRESETS: &[(&str, [f32; 10])] = &[
    ("Рівно", [0.0; 10]),
    ("Більше басів", [6.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]),
    ("Менше басів", [-6.0, -5.0, -4.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]),
    ("Вокал", [-2.0, -2.0, -1.0, 0.0, 2.0, 3.0, 3.0, 2.0, 0.0, -1.0]),
    ("Високі", [0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 4.0, 5.0, 6.0]),
    ("Рок", [4.0, 3.0, 2.0, 0.0, -1.0, -1.0, 1.0, 2.0, 3.0, 4.0]),
    ("Поп", [-1.0, 0.0, 2.0, 3.0, 3.0, 2.0, 0.0, -1.0, -1.0, -1.0]),
    ("Електронна", [5.0, 4.0, 2.0, 0.0, -1.0, 1.0, 0.0, 1.0, 3.0, 4.0]),
    ("Акустика", [3.0, 2.0, 1.0, 1.0, 2.0, 2.0, 3.0, 2.0, 2.0, 1.0]),
    ("Тихо вночі", [4.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0, 4.0]),
];

/// Smooth curve through the band gains (Catmull-Rom as cubic Béziers), for the equalizer's graph.
fn eq_curve(g: &[f32; 10], w: f32, h: f32) -> (String, String) {
    let y = |v: f32| h / 2.0 - v / 12.0 * (h / 2.0 - 8.0);
    let mut pts: Vec<(f32, f32)> = vec![(0.0, y(g[0]))];
    pts.extend(g.iter().enumerate().map(|(i, v)| ((i as f32 + 0.5) / 10.0 * w, y(*v))));
    pts.push((w, y(g[9])));
    let mut d = format!("M{:.1},{:.1}", pts[0].0, pts[0].1);
    for i in 0..pts.len() - 1 {
        let p0 = pts[i.saturating_sub(1)]; let (p1, p2) = (pts[i], pts[i + 1]); let p3 = pts[(i + 2).min(pts.len() - 1)];
        let c1 = (p1.0 + (p2.0 - p0.0) / 6.0, p1.1 + (p2.1 - p0.1) / 6.0);
        let c2 = (p2.0 - (p3.0 - p1.0) / 6.0, p2.1 - (p3.1 - p1.1) / 6.0);
        d += &format!(" C{:.1},{:.1} {:.1},{:.1} {:.1},{:.1}", c1.0, c1.1, c2.0, c2.1, p2.0, p2.1);
    }
    let area = format!("{d} L{w},{:.1} L0,{:.1} Z", h / 2.0, h / 2.0);
    (d, area)
}

/// Equalizer: for all songs (the main one) or just for the current song. Changes are heard at once.
/// A graph shows the resulting sound curve; each band is a fader on top of it (wheel = ±1 dB, double click = 0).
#[component]
fn EqPopover() -> Element {
    let mut per_song = use_signal(|| false);
    if !*EQ_OPEN.read() { return rsx! {} }
    let s = SETTINGS.read().clone();
    let song = PSTATE.read().current().cloned();
    let sid = song.as_ref().map(|x| x.id.clone()).unwrap_or_default();
    let song_mode = *per_song.read() && !sid.is_empty();
    let gains = if song_mode { s.eq_songs.get(&sid).copied().unwrap_or(s.eq_gains) } else { s.eq_gains };
    let on = if song_mode { s.eq_songs.contains_key(&sid) } else { s.eq_on };
    let store = move |g: [f32; 10], sid: String, song_mode: bool| {
        if song_mode { set(move |st| { st.eq_songs.insert(sid, g); }) } else { set(move |st| { st.eq_gains = g; st.eq_on = true }) }
        playback::apply_eq();
    };
    let (s1, s2, s3, s4) = (sid.clone(), sid.clone(), sid.clone(), sid.clone());
    let (w, h) = (520.0f32, 170.0f32);
    let (line, area) = eq_curve(&gains, w, h);
    let preset = EQ_PRESETS.iter().find(|(_, g)| *g == gains).map(|p| p.0);
    rsx! {
        div { class: "dev-scrim", onclick: move |e| { e.stop_propagation(); *EQ_OPEN.write() = false } }
        div { class: "dev-pop eq-pop", onclick: move |e| e.stop_propagation(),
            div { class: "eq-head",
                div { class: "eq-title", Icon { name: "equalizer", size: 22 } span { {tr!("Еквалайзер")} } }
                div { class: "seg",
                    button { class: if !song_mode { "on" } else { "" }, onclick: move |_| per_song.set(false), {tr!("Для всіх пісень")} }
                    button { class: if song_mode { "on" } else { "" }, disabled: sid.is_empty(), title: song.as_ref().map(|x| x.title.clone()).unwrap_or_default(), onclick: move |_| per_song.set(true), {tr!("Для цієї пісні")} }
                }
                if !song_mode {
                    input { r#type: "checkbox", class: "switch", checked: on, title: if on { tr!("Вимкнути еквалайзер") } else { tr!("Увімкнути еквалайзер") },
                        onchange: move |e| { let v = e.checked(); set(move |st| st.eq_on = v); playback::apply_eq() } }
                }
            }
            if song_mode {
                p { class: "dim small eq-note", if on { {tr!("Ця пісня звучить зі своїм налаштуванням.")} } else { {tr!("Змініть будь-яку смугу — і ця пісня завжди звучатиме саме так.")} } }
            }
            div { class: "eq-presets",
                for (name, g) in EQ_PRESETS.iter() {
                    {
                        let sid = s1.clone();
                        rsx! { button { class: if preset == Some(*name) && on { "chip on" } else { "chip" }, onclick: move |_| store(*g, sid.clone(), song_mode), {tr!(*name)} } }
                    }
                }
            }
            div { class: if on { "eq-graph" } else { "eq-graph off" },
                svg { class: "eq-svg", view_box: "0 0 {w} {h}", preserve_aspect_ratio: "none",
                    defs { linearGradient { id: "eqfill", x1: "0", y1: "0", x2: "0", y2: "1",
                        stop { offset: "0", stop_color: "var(--accent)", stop_opacity: ".38" }
                        stop { offset: ".5", stop_color: "var(--accent)", stop_opacity: ".06" }
                        stop { offset: "1", stop_color: "var(--accent)", stop_opacity: ".38" } } }
                    for db in [12.0f32, 6.0, 0.0, -6.0, -12.0] {
                        { let y = h / 2.0 - db / 12.0 * (h / 2.0 - 8.0); rsx! { line { class: if db == 0.0 { "eq-zero" } else { "eq-grid" }, x1: "0", x2: "{w}", y1: "{y}", y2: "{y}" } } }
                    }
                    path { class: "eq-area", d: "{area}", fill: "url(#eqfill)" }
                    path { class: "eq-line", d: "{line}" }
                }
                div { class: "eq-scale", span { "+12" } span { "0" } span { "−12" } }
                div { class: "eq-bands",
                    for (i, f) in crate::audio::EQ_FREQS.iter().enumerate() {
                        {
                            let (sid, sid2, sid3) = (s2.clone(), s3.clone(), s4.clone());
                            let label = if *f >= 1000.0 { format!("{}k", f / 1000.0) } else { format!("{f}") };
                            let v = gains[i];
                            rsx! {
                                div { class: "eq-band",
                                    onwheel: move |e| { e.prevent_default(); let d = if e.delta().strip_units().y < 0.0 { 1.0 } else { -1.0 }; let mut g = gains; g[i] = (g[i] + d).clamp(-12.0, 12.0); store(g, sid2.clone(), song_mode) },
                                    ondoubleclick: move |_| { let mut g = gains; g[i] = 0.0; store(g, sid3.clone(), song_mode) },
                                    span { class: if v.abs() > 0.05 { "eq-val on" } else { "eq-val" }, {format!("{:+.0}", v)} }
                                    div { class: "eq-track",
                                        input { r#type: "range", class: "eq-slider", min: "-12", max: "12", step: "1", value: "{v}", title: "{label} Hz",
                                            oninput: move |e| if let Ok(x) = e.value().parse::<f32>() { let mut g = gains; g[i] = x; store(g, sid.clone(), song_mode) } }
                                    }
                                    span { class: "eq-freq", "{label}" }
                                }
                            }
                        }
                    }
                }
            }
            div { class: "eq-foot",
                span { class: "dim small", {tr!("Коліщатко миші — крок 1 дБ, подвійний клік — 0.")} }
                if song_mode && on {
                    { let sid = sid.clone(); rsx! { button { class: "btn small", onclick: move |_| { let id = sid.clone(); set(move |st| { st.eq_songs.remove(&id); }); playback::apply_eq() }, {tr!("Прибрати налаштування цієї пісні")} } } }
                } else {
                    { let sid = sid.clone(); rsx! { button { class: "btn small", onclick: move |_| store([0.0; 10], sid.clone(), song_mode), Icon { name: "restart_alt", size: 18 } {tr!("Скинути")} } } }
                }
            }
        }
    }
}

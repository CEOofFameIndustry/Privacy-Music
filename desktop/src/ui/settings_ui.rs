use super::components::*;
use super::*;
use crate::db;
use crate::model::{Item, Song};

// Settings rows: a small accent-coloured icon, title + description, control on the right.
const BLUE: &str = ""; const GREEN: &str = ""; const ORANGE: &str = ""; const RED: &str = "";
const PURPLE: &str = ""; const GRAY: &str = ""; const PINK: &str = ""; const TEAL: &str = ""; const INDIGO: &str = "";

#[component]
fn Ic(name: &'static str, #[props(default = "")] color: &'static str) -> Element { let _ = color; rsx! { span { class: "set-ic", Icon { name, size: 20 } } } }

#[component]
fn Toggle(icon: &'static str, #[props(default = "")] color: &'static str, title: String, sub: Option<String>, on: bool, onchange: EventHandler<bool>) -> Element {
    rsx! {
        label { class: "set-row",
            Ic { name: icon, color }
            div { class: "grow", div { "{title}" } if let Some(s) = sub { div { class: "dim small", "{s}" } } }
            input { r#type: "checkbox", class: "switch", checked: on, onchange: move |e| onchange.call(e.checked()) }
        }
    }
}

#[component]
fn SyncedLyricsSetting(enabled: bool) -> Element {
    rsx! {
        Toggle { icon: "lyrics", title: tr!("Синхронізовані тексти (LRCLIB)"),
            sub: Some(tr!("Вимкнено: тексти лише з YouTube Music. Увімкнено: назва й виконавець надсилаються на lrclib.net")),
            on: enabled, onchange: move |v| { crate::lyrics::clear(); set(|s| s.lrclib = v) } }
    }
}

#[component]
fn Link(icon: &'static str, #[props(default = "")] color: &'static str, title: String, sub: Option<String>, onclick: EventHandler<()>) -> Element {
    rsx! { button { class: "set-row", onclick: move |_| onclick.call(()), Ic { name: icon, color } div { class: "grow", div { "{title}" } if let Some(s) = sub { div { class: "dim small", "{s}" } } } span { class: "chev", Icon { name: "chevron_right" } } } }
}

#[component]
fn Group(title: String, children: Element) -> Element { rsx! { div { class: "group-title", "{title}" } div { class: "group", {children} } } }

const HL: &[(&str, &str)] = &[("uk", "Українська"), ("en", "English"), ("pl", "Polski"), ("de", "Deutsch"), ("fr", "Français"), ("es", "Español"), ("it", "Italiano"), ("pt", "Português"), ("cs", "Čeština"), ("ja", "日本語")];
const GL: &[&str] = &["UA", "PL", "DE", "US", "GB", "FR", "CZ", "CA", "ES", "IT", "JP"];

#[component]
pub fn Settings() -> Element {
    let s = SETTINGS.read().clone();
    let mut confirm = use_signal(|| None::<&'static str>);
    let mut sec = use_signal(|| "general");
    let sections = [("general", "settings", tr!("Загальні")), ("home", "home", tr!("Головна сторінка")), ("playback", "play_arrow", tr!("Відтворення")), ("devices", "devices", tr!("Пристрої поруч")), ("content", "language", tr!("Вміст")),
        ("privacy", "shield", tr!("Приватність")), ("stats", "bar_chart", tr!("Статистика")), ("data", "storage", tr!("Дані")),
        ("support", "volunteer_activism", tr!("Підтримка")), ("about", "info", tr!("Про застосунок"))];
    // Opened from elsewhere at a given section (e.g. the devices pop-up).
    // Read first, then clear: the read guard must be gone before the write (else AlreadyBorrowed panic).
    use_effect(move || { let want = *SETTINGS_SECTION.read(); if let Some(x) = want { sec.set(x); *SETTINGS_SECTION.write() = None } });
    let cur = *sec.read();
    rsx! {
        div { class: "page settings wide",
            TopBar { title: tr!("Налаштування") }
            div { class: "set-layout",
                nav { class: "set-nav",
                    for (id, icon, label) in sections.clone() {
                        button { class: if cur == id { "on" } else { "" }, onclick: move |_| sec.set(id), Icon { name: icon, size: 20 } span { "{label}" } }
                    }
                }
                div { class: "set-body",
                    match cur {
                        "general" => rsx! {
                            h2 { {tr!("Загальні")} }
                            div { class: "group",
                                label { class: "set-row", Ic { name: "translate" } div { class: "grow", {tr!("Мова інтерфейсу")} }
                                    select { value: "{s.ui_language}", onchange: move |e| { let v = e.value(); crate::i18n::set_language(&v); set(|s| s.ui_language = v) },
                                        option { value: "system", selected: s.ui_language == "system", {tr!("Як у системі")} }
                                        for (c, n) in crate::i18n::LANGUAGES { option { value: "{c}", selected: s.ui_language == *c, "{n}" } }
                                    }
                                }
                                label { class: "set-row", Ic { name: "open_in_full" } div { class: "grow", div { {tr!("Масштаб інтерфейсу")} } div { class: "dim small", {tr!("Розмір тексту, іконок і всіх елементів")} } }
                                    select { value: "{s.ui_scale}", onchange: move |e| if let Ok(v) = e.value().parse::<u32>() { set(|s| s.ui_scale = v) },
                                        for v in [75u32, 80, 90, 100, 110, 125, 150, 175, 200] { option { value: "{v}", selected: v == s.ui_scale, "{v}%" } }
                                    }
                                }
                                Toggle { icon: "close_fullscreen", title: tr!("Згортати в трей при закритті"), sub: Some(tr!("Хрестик і комбінація закриття ховають вікно, музика грає далі. Повністю вийти — Ctrl+Q або «Закрити Privacy Music» у меню трею")), on: s.close_to_tray, onchange: move |v| set(|s| s.close_to_tray = v) }
                                label { class: "set-row", Ic { name: "tune" } div { class: "grow", div { {tr!("Апаратне прискорення")} } div { class: "dim small", {tr!("Якщо інтерфейс гальмує, спробуйте інший варіант. Застосовується після перезапуску")} } }
                                    select { onchange: move |e| { let v = e.value(); set(|s| s.gpu = v) },
                                        option { value: "auto", selected: s.gpu == "auto", {tr!("Авто")} }
                                        option { value: "on", selected: s.gpu == "on", {tr!("Увімкнено")} }
                                        option { value: "off", selected: s.gpu == "off", {tr!("Вимкнено")} }
                                    }
                                }
                                Link { icon: "palette", title: tr!("Кольори, фон і обкладинки"), sub: Some(tr!("Колір фону й акценту")), onclick: move |_| go(Route::Appearance) }
                            }
                            div { class: "group-title", {tr!("Оновлення")} }
                            UpdateSettings {}
                        },
                        "home" => rsx! {
                            h2 { {tr!("Головна сторінка")} }
                            p { class: "dim", {tr!("Виберіть, що показувати на головній, і перетягніть, щоб змінити порядок.")} }
                            HomeBlocks {}
                        },
                        "playback" => rsx! {
                            h2 { {tr!("Відтворення")} }
                            div { class: "group",
                                Toggle { icon: "all_inclusive", title: tr!("Автовідтворення"), sub: Some(tr!("Коли черга закінчується, продовжувати схожими піснями (як у YouTube Music)")), on: s.autoplay, onchange: move |v| set(|s| s.autoplay = v) }
                                label { class: "set-row", Ic { name: "graphic_eq" } div { class: "grow", div { {tr!("Плавний перехід між піснями")} } div { class: "dim small", {tr!("Кінець однієї пісні м'яко перетікає в наступну. Наступна пісня завантажується заздалегідь, тож перемикання миттєве")} } }
                                    select { onchange: move |e| if let Ok(v) = e.value().parse::<u32>() { set(|s| s.crossfade_secs = v) },
                                        for v in [0u32, 2, 4, 6, 8, 10, 12] { option { value: "{v}", selected: v == s.crossfade_secs, if v == 0 { {tr!("Вимкнено")} } else { {tr!("{0} с", v)} } } }
                                    }
                                }
                                OutputDevice {}
                                Toggle { icon: "tune", title: tr!("Еквалайзер"), sub: Some(tr!("Налаштування смуг — кнопкою еквалайзера біля гучності")), on: s.eq_on, onchange: move |v| { set(|s| s.eq_on = v); playback::apply_eq() } }
                                Toggle { icon: "data_saver_on", title: tr!("Економна якість"), sub: Some(tr!("Менший бітрейт, щоб заощадити трафік")), on: s.economy, onchange: move |v| set(|s| s.economy = v) }
                                Toggle { icon: "queue_music", title: tr!("Пам'ятати чергу"), sub: Some(tr!("Зберігати поточну чергу на пристрої між запусками")), on: s.persist_queue, onchange: move |v| { set(|s| s.persist_queue = v); if !v { playback::forget_queue_file() } } }
                                SyncedLyricsSetting { enabled: s.lrclib }
                                if s.lrclib {
                                    Toggle { icon: "format_quote", title: tr!("Поступове заповнення тексту"), sub: Some(tr!("Увімкнено: рядок заповнюється так, як його співають. Вимкнено: рядок підсвічується цілком")), on: s.lyrics_flow, onchange: move |v| set(|s| s.lyrics_flow = v) }
                                }
                            }
                        },
                        "devices" => rsx! {
                            h2 { {tr!("Пристрої поруч")} }
                            DevicesSettings {}
                        },
                        "content" => rsx! {
                            h2 { {tr!("Вміст")} }
                            div { class: "group",
                                label { class: "set-row", Ic { name: "language" } div { class: "grow", {tr!("Мова вмісту YouTube")} }
                                    select { value: "{s.hl}", onchange: move |e| { let v = e.value(); set(|s| s.hl = v) }, for (c, n) in HL { option { value: "{c}", selected: s.hl == *c, "{n}" } } } }
                                label { class: "set-row", Ic { name: "public" } div { class: "grow", {tr!("Регіон вмісту")} }
                                    select { value: "{s.gl}", onchange: move |e| { let v = e.value(); set(|s| s.gl = v) }, for c in GL { option { value: "{c}", selected: s.gl == *c, "{c}" } } } }
                            }
                        },
                        "privacy" => rsx! {
                            h2 { {tr!("Приватність")} }
                            div { class: "group",
                                Link { icon: "security", title: tr!("Що бачить YouTube"), sub: Some(tr!("Що він отримує, а що ні • журнал мережі")), onclick: move |_| go(Route::Privacy) }
                                Link { icon: "shield", title: tr!("Максимальна приватність"), sub: Some(tr!("Вимкнути все необов'язкове одним натиском")), onclick: move |_| confirm.set(Some("max")) }
                                Toggle { icon: "cloud_off", title: tr!("Офлайн-режим"), sub: Some(tr!("Застосунок не виходить у мережу взагалі: грають лише завантажені пісні й файли з пристрою")), on: s.offline, onchange: move |v| set(|s| s.offline = v) }
                                Toggle { icon: "manage_search", title: tr!("Підказки під час пошуку"), sub: Some(tr!("Вимкнено: YouTube отримує запит лише після натискання «Шукати», а не кожну набрану літеру")), on: s.search_suggestions, onchange: move |v| set(|s| s.search_suggestions = v) }
                                Toggle { icon: "history", title: tr!("Історія прослуховування"), sub: Some(tr!("Записується тільки на цьому пристрої")), on: s.history, onchange: move |v| set(|s| s.history = v) }
                                Toggle { icon: "search", title: tr!("Історія пошуку"), sub: Some(tr!("Записується тільки на цьому пристрої")), on: s.search_history, onchange: move |v| set(|s| s.search_history = v) }
                                Toggle { icon: "image_search", title: tr!("Обкладинки для імпортованих файлів"), sub: Some(tr!("Після імпорту з Telegram шукати кожну пісню на YouTube Music за назвою й виконавцем і брати її обкладинку")), on: s.auto_covers, onchange: move |v| set(|s| s.auto_covers = v) }
                                SyncedLyricsSetting { enabled: s.lrclib }
                                Link { icon: "delete_sweep", title: tr!("Очистити історію прослуховування"), sub: None, onclick: move |_| { db::clear_history(); toast(tr!("Історію очищено")) } }
                                Link { icon: "search_off", title: tr!("Очистити історію пошуку"), sub: None, onclick: move |_| { db::clear_searches(); toast(tr!("Історію пошуку очищено")) } }
                            }
                        },
                        "stats" => rsx! {
                            h2 { {tr!("Статистика")} }
                            div { class: "group",
                                Toggle { icon: "bar_chart", title: tr!("Збирати статистику прослуховування"), sub: Some(tr!("Хвилини, топ пісень і виконавців, години — лише на цьому пристрої")), on: s.stats, onchange: move |v| set(|s| s.stats = v) }
                                Link { icon: "insights", title: tr!("Відкрити статистику"), sub: None, onclick: move |_| go(Route::Stats) }
                            }
                        },
                        "data" => rsx! {
                            h2 { {tr!("Дані")} }
                            div { class: "group",
                                Link { icon: "upload_file", title: tr!("Імпорт"), sub: Some(tr!("Telegram, посилання, таблиця CSV, резервна копія")), onclick: move |_| go(Route::Import) }
                                Link { icon: "cleaning_services", title: tr!("Очистити кеш"), sub: Some(tr!("Аудіо й обкладинки, які зберігалися для швидкого відтворення")), onclick: move |_| {
                                    let _ = std::fs::remove_dir_all(crate::settings::cache_dir().join("audio")); let _ = std::fs::remove_dir_all(crate::settings::cache_dir().join("img")); toast(tr!("Кеш очищено")) } }
                                Link { icon: "delete_forever", title: tr!("Стерти всі дані"), sub: Some(tr!("Медіатека, історія, плейлисти, завантаження, кеш і акаунт")), onclick: move |_| confirm.set(Some("wipe")) }
                            }
                        },
                        "support" => rsx! {
                            h2 { {tr!("Підтримка")} }
                            div { class: "group",
                                Link { icon: "volunteer_activism", title: tr!("Підтримати автора"), sub: None, onclick: move |_| go(Route::Support) }
                                Link { icon: "bug_report", title: tr!("Повідомити про помилку"), sub: Some(tr!("Або запропонувати ідею")), onclick: move |_| mail(&tr!("Повідомлення про помилку")) }
                            }
                        },
                        _ => rsx! {
                            h2 { {tr!("Про застосунок")} }
                            div { class: "group",
                                div { class: "set-row", Ic { name: "info" } div { class: "grow", div { "Privacy Music {env!(\"CARGO_PKG_VERSION\")}" } div { class: "dim small", {tr!("Без Google SDK, без аналітики. Каталог — YouTube Music, потоки — yt-dlp.")} } } }
                                div { class: "set-row", Ic { name: "shield" } div { class: "grow", {tr!("Гостьовий режим")} div { class: "dim small", {tr!("Без акаунта, без cookie, без ідентифікаторів пристрою")} } } }
                            }
                        },
                    }
                }
            }
        }
        if let Some(c) = *confirm.read() {
            div { class: "scrim dim", onclick: move |_| confirm.set(None) }
            div { class: "dialog",
                if c == "max" {
                    h3 { {tr!("Максимальна приватність")} }
                    p { class: "pre", {tr!("Буде вимкнено:\n• тексти в такт (LRCLIB)\n• підказки під час пошуку\n• історію прослуховування й пошуку\n• статистику\n• збереження черги між запусками\n• персональну головну й надсилання дій в акаунт (якщо ви увійшли)\n\n")} {tr!("Також: перевірку оновлень і пошук обкладинок для імпортованих файлів.")} " " {tr!("Ще більше: офлайн-режим (нуль запитів, грають лише завантаження), не входити в акаунт і VPN, щоб YouTube не бачив вашу IP-адресу.")} }
                    div { class: "pills",
                        button { class: "btn", onclick: move |_| confirm.set(None), {tr!("Скасувати")} }
                        button { class: "btn accent", onclick: move |_| { set(|s| { s.lrclib = false; s.search_suggestions = false; s.history = false; s.search_history = false; s.stats = false; s.persist_queue = false; s.update_check = false; s.auto_covers = false }); playback::forget_queue_file(); confirm.set(None); toast(tr!("Максимальну приватність увімкнено")) }, {tr!("Увімкнути")} }
                    }
                } else {
                    h3 { {tr!("Стерти всі дані?")} }
                    p { {tr!("Буде видалено всю медіатеку, історію, пошуки, плейлисти, завантаження, кеш, чергу та вихід з акаунта. Це не можна скасувати.")} }
                    div { class: "pills",
                        button { class: "btn", onclick: move |_| confirm.set(None), {tr!("Скасувати")} }
                        button { class: "btn danger", onclick: move |_| {
                            for s in db::downloads() { db::remove_download(&s.id) }
                            db::wipe_all(); playback::forget_queue_file();
                            let _ = std::fs::remove_dir_all(crate::settings::cache_dir());
                            confirm.set(None); toast(tr!("Усі дані стерто"))
                        }, {tr!("Стерти")} }
                    }
                }
            }
        }
    }
}

/// Home page blocks: switch each on / off, drag to reorder. Saved locally.
#[component]
fn HomeBlocks() -> Element {
    let entries = super::pages::home_blocks();
    let ids: Vec<String> = entries.iter().map(|e| e.0.clone()).collect();
    let list = super::pages::arrange(&SETTINGS.read().layout_home, &ids);
    let mut drag = use_signal(|| None::<(usize, usize)>);
    let save = |l: Vec<(String, bool)>| { let v = super::pages::encode(&l); set(move |s| s.layout_home = v) };
    let (lu, ll) = (list.clone(), list.clone());
    rsx! {
        div { class: if drag.read().is_some() { "group reordering" } else { "group" },
            onmouseup: move |_| if let Some((f, t)) = reorder_end(drag) { let mut v = lu.clone(); let x = v.remove(f); v.insert(t, x); save(v) },
            onmouseleave: move |_| if let Some((f, t)) = reorder_end(drag) { let mut v = ll.clone(); let x = v.remove(f); v.insert(t, x); save(v) },
            for (i, (id, on)) in list.clone().into_iter().enumerate() {
                {
                    let label = entries.iter().find(|e| e.0 == id).map(|e| e.1.clone()).unwrap_or(id.clone());
                    let l2 = list.clone();
                    rsx! {
                        div { key: "{id}", class: reorder_class(*drag.read(), i, "set-row"),
                            onmouseenter: move |_| { let d = *drag.peek(); if let Some((f, _)) = d { drag.set(Some((f, i))) } },
                            span { class: "handle", title: tr!("Перетягнути"), onmousedown: move |e| { e.prevent_default(); drag.set(Some((i, i))) }, Icon { name: "drag_indicator", size: 20 } }
                            div { class: "grow", "{label}" }
                            input { r#type: "checkbox", class: "switch", checked: on, onchange: move |e| { let mut v = l2.clone(); v[i].1 = e.checked(); save(v) } }
                        }
                    }
                }
            }
        }
        div { class: "pills", button { class: "btn", onclick: move |_| set(|s| s.layout_home = String::new()), Icon { name: "settings_backup_restore" } {tr!("Скинути")} } }
    }
}

fn mail(subject: &str) {
    let url = format!("mailto:2secondary2surfaced@protonmail.com?subject={}", urlencoding::encode(&format!("Privacy Music desktop: {subject}")));
    crate::sys::open_url(&url);
}

#[component]
pub fn Privacy() -> Element {
    let s = SETTINGS.read().clone();
    let mut tick = use_signal(|| 0u32);
    use_future(move || async move { loop { sleep_ms(1000).await; tick += 1 } });
    let _ = tick.read();
    let blocked = crate::firewall::BLOCKED.lock().clone();
    let allowed = crate::firewall::ALLOWED.load(std::sync::atomic::Ordering::Relaxed);
    rsx! {
        div { class: "page settings",
            TopBar { title: tr!("Приватність") }
            div { class: "card-note",
                b { {tr!("Що бачить YouTube")} }
                ul {
                    li { {tr!("IP-адресу пристрою — як і будь-який сайт. Щоб приховати її, використовуйте VPN або Tor (Orbot) на рівні системи.")} }
                    li { {tr!("Те, що ви шукаєте й відкриваєте: пошуковий запит, сторінку альбому, виконавця чи плейлиста та ID пісні, яку вмикаєте. Без цього музику не отримати.")} }
                    li { {tr!("Тимчасовий гостьовий токен сесії. Він живе лише в пам'яті й зникає, щойно ви закриваєте застосунок.")} }
                    if s.search_suggestions { li { {tr!("Підказки пошуку: текст, який ви друкуєте, ще до натискання «Шукати». Це можна вимкнути в налаштуваннях.")} } }
                    if s.offline { li { {tr!("Зараз нічого: увімкнено офлайн-режим, і фаєрвол блокує всі запити.")} } }
                }
            }
            div { class: "card-note",
                b { {tr!("Чого YouTube не отримує")} }
                ul {
                    li { {tr!("Жодних cookie, входу в акаунт, ID пристрою чи рекламного ID.")} }
                    li { {tr!("Звітів про відтворення (playback, watchtime, qoe, ptracking). Тому прослуховування не потрапляють в історію Google.")} }
                    li { {tr!("Вашої медіатеки, лайків, плейлистів, історії й статистики: вони лише в базі на цьому пристрої.")} }
                }
            }
            div { class: "card-note",
                b { {tr!("Інші сервіси")} }
                ul {
                    li { if s.lrclib { {tr!("LRCLIB (тексти в такт): отримує назву, виконавця й тривалість пісні, текст якої ви відкриваєте.")} } else { {tr!("LRCLIB (тексти в такт) вимкнено, тож запитів туди немає.")} } }
                    li { {tr!("Spotify: лише під час імпорту за посиланням — відкривається публічна сторінка плейлиста, без входу.")} }
                    if s.update_check { li { {tr!("GitHub (api.github.com): двічі на день — лише запит про номер нової версії. Вимикається в налаштуваннях.")} } }
                    if s.auto_covers { li { {tr!("Обкладинки для файлів з Telegram: назва й виконавець шукаються на YouTube Music, як звичайний пошук.")} } }
                    li { {tr!("Сервери обкладинок Google (i.ytimg.com, googleusercontent.com): бачать IP-адресу, коли завантажується картинка.")} }
                }
            }
            div { class: "group-title", {tr!("Мережевий фаєрвол")} }
            div { class: "group",
                div { class: "set-row", Ic { name: "security", color: GREEN } div { class: "grow", {tr!("Дозволено запитів: {0} • заблоковано: {1}", allowed, blocked.len())} } }
                if blocked.is_empty() { div { class: "set-row", Ic { name: "check", color: GRAY } div { class: "grow dim", {tr!("За цю сесію нічого не довелося блокувати.")} } } }
                for b in blocked { div { class: "set-row", Ic { name: "close", color: RED } span { class: "grow", "{b.host}" } } }
            }
        }
    }
}

// ---------- statistics (Spotify rules: a stream = ≥30 s or ≥90 % of a shorter track) ----------
#[component]
pub fn Stats() -> Element {
    let mut range = use_signal(|| 28i64);
    // Refreshes while open, so the song playing now is counted as it plays.
    let mut tick = use_signal(|| 0u32);
    use_future(move || async move { loop { sleep_ms(5000).await; *tick.write() += 1 } });
    let _ = tick.read();
    let now = db::now_ms();
    let plays = db::plays();
    let from = if *range.read() > 0 { now - *range.read() * 86_400_000 } else { 0 };
    let rows: Vec<&(Song, i64, i64)> = plays.iter().filter(|p| p.1 >= from).collect();
    let is_stream = |r: &(Song, i64, i64)| { let d = r.0.duration.map(|d| d as i64 * 1000).unwrap_or(0); r.2 >= 30_000 || (d > 0 && d < 30_000 && r.2 >= d * 9 / 10) };
    let minutes: i64 = rows.iter().map(|r| r.2).sum::<i64>() / 60_000;
    let streams = rows.iter().filter(|r| is_stream(r)).count();
    let mut songs: std::collections::HashMap<String, (Song, usize, i64)> = Default::default();
    let mut artists: std::collections::HashMap<String, (i64, usize)> = Default::default();
    for r in &rows {
        let e = songs.entry(r.0.id.clone()).or_insert((r.0.clone(), 0, 0));
        if is_stream(r) { e.1 += 1 } e.2 += r.2;
        for a in &r.0.artists { let x = artists.entry(a.name.clone()).or_insert((0, 0)); x.0 += r.2; if is_stream(r) { x.1 += 1 } }
    }
    let mut top_songs: Vec<_> = songs.into_values().filter(|s| s.1 > 0).collect();
    top_songs.sort_by(|a, b| b.1.cmp(&a.1).then(b.2.cmp(&a.2)));
    let mut top_artists: Vec<_> = artists.into_iter().collect();
    top_artists.sort_by(|a, b| b.1.0.cmp(&a.1.0).then(b.1.1.cmp(&a.1.1)));
    rsx! {
        div { class: "page",
            TopBar { title: tr!("Статистика") }
            div { class: "chips",
                for (d, l) in [(28, tr!("4 тижні")), (183, tr!("6 місяців")), (0, tr!("Весь час"))] { button { class: if *range.read() == d { "chip on" } else { "chip" }, onclick: move |_| range.set(d), "{l}" } }
            }
            if !SETTINGS.read().stats { p { class: "dim", {tr!("Збір статистики вимкнено в налаштуваннях.")} } }
            div { class: "stat-cards",
                div { class: "stat", b { "{minutes}" } span { {tr!("хвилин прослухано")} } }
                div { class: "stat", b { "{streams}" } span { {tr!("прослуховувань")} } }
            }
            h2 { class: "section-head", {tr!("Топ пісень")} }
            for (i, (s, n, _)) in top_songs.iter().take(20).enumerate() {
                div { key: "{s.id}", class: "stat-row", span { class: "idx", "{i + 1}" } ItemRow { item: Item::Song(s.clone()) } span { class: "dim", {tr!("{0} разів", n)} } }
            }
            h2 { class: "section-head", {tr!("Топ виконавців")} }
            for (i, (a, (ms, _))) in top_artists.iter().take(20).enumerate() {
                div { key: "{a}", class: "stat-row plain", span { class: "idx", "{i + 1}" } span { class: "grow", "{a}" } span { class: "dim", {tr!("{0} хв", ms / 60_000)} } }
            }
        }
    }
}

// ---------- appearance ----------
const BG: &[(u32, &str)] = &[(0x000000, "AMOLED"), (0x121212, "Graphite"), (0x0b1020, "Midnight"), (0x0c1a12, "Forest"), (0x1a0c12, "Wine"), (0x140c24, "Violet"),
    (0xffffff, "White"), (0xf2f2f7, "Light grey"), (0xfbf6ee, "Warm")];
const ACCENT: &[u32] = &[0xfa2d48, 0xff375f, 0xff9f0a, 0xffd60a, 0x30d158, 0x40c8e0, 0x0a84ff, 0x5e5ce6, 0xbf5af2, 0xa2845e, 0xffffff];

#[component]
pub fn Appearance() -> Element {
    let s = SETTINGS.read().clone();
    // Same rule as the app: a pale background means the light theme (dark text).
    let light = { let c = s.theme_bg; (0.2126 * (c >> 16 & 255) as f32 + 0.7152 * (c >> 8 & 255) as f32 + 0.0722 * (c & 255) as f32) / 255.0 > 0.5 };
    rsx! {
        div { class: "page settings",
            TopBar { title: tr!("Оформлення") }
            Group { title: tr!("Тема"),
                div { class: "seg theme-seg",
                    button { class: if !light { "on" } else { "" }, onclick: move |_| set(|s| s.theme_bg = 0x000000), {tr!("Темна")} }
                    button { class: if light { "on" } else { "" }, onclick: move |_| set(|s| { s.theme_bg = 0xffffff; if s.theme_accent == 0xffffff { s.theme_accent = 0xfa2d48 } }), {tr!("Світла")} }
                }
            }
            Group { title: tr!("Колір фону"),
                div { class: "swatches",
                    for (c, n) in BG { button { class: if s.theme_bg == *c { "swatch on" } else { "swatch" }, title: "{n}", style: "background:#{c:06x}", onclick: move |_| set(|s| s.theme_bg = *c) } }
                    input { r#type: "color", title: tr!("Свій"), value: "#{s.theme_bg:06x}", onchange: move |e| if let Ok(v) = u32::from_str_radix(e.value().trim_start_matches('#'), 16) { set(|s| s.theme_bg = v) } }
                }
            }
            Group { title: tr!("Фонове зображення"),
                div { class: "set-row",
                    Ic { name: "palette" }
                    div { class: "grow", if s.theme_image { {tr!("Встановлено власне зображення")} } else { {tr!("Не вибрано")} } }
                    button { class: "btn", onclick: move |_| { spawn(async move {
                        let Some(f) = rfd::AsyncFileDialog::new().add_filter("Images", &["jpg", "jpeg", "png", "webp"]).pick_file().await else { return };
                        let path = f.path().to_path_buf();
                        let ok = bg(async move { tokio::fs::copy(&path, crate::settings::background_file()).await.is_ok() }).await;
                        if ok { set(|s| { s.theme_image = true; s.theme_image_v += 1 }) } else { toast(tr!("Не вдалося відкрити зображення")) }
                    }); }, Icon { name: "folder_open" } {tr!("Вибрати зображення")} }
                    if s.theme_image { button { class: "btn", onclick: move |_| { let _ = std::fs::remove_file(crate::settings::background_file()); set(|s| s.theme_image = false) }, Icon { name: "close" } {tr!("Прибрати")} } }
                }
                if s.theme_image {
                    label { class: "set-row", Ic { name: "visibility" } div { class: "grow", {tr!("Затемнення")} }
                        input { class: "volume", r#type: "range", min: "0", max: "90", value: "{s.theme_dim}", style: "--p:{s.theme_dim as f32 / 0.9}%",
                            oninput: move |e| if let Ok(v) = e.value().parse::<u32>() { set(|s| s.theme_dim = v) } }
                        span { class: "dim small", "{s.theme_dim}%" }
                    }
                    label { class: "set-row", Ic { name: "palette" } div { class: "grow", div { {tr!("Розмиття")} } div { class: "dim small", {tr!("Розмитий фон не заважає читати текст")} } }
                        input { class: "volume", r#type: "range", min: "0", max: "40", value: "{s.theme_blur}", style: "--p:{s.theme_blur as f32 / 0.4}%",
                            oninput: move |e| if let Ok(v) = e.value().parse::<u32>() { set(|s| s.theme_blur = v) } }
                        span { class: "dim small", "{s.theme_blur} px" }
                    }
                }
            }
            Group { title: tr!("Акцентний колір"),
                div { class: "swatches",
                    for c in ACCENT { button { class: if s.theme_accent == *c { "swatch on" } else { "swatch" }, style: "background:#{c:06x}", onclick: move |_| set(|s| s.theme_accent = *c) } }
                    input { r#type: "color", title: tr!("Свій"), value: "#{s.theme_accent:06x}", onchange: move |e| if let Ok(v) = u32::from_str_radix(e.value().trim_start_matches('#'), 16) { set(|s| s.theme_accent = v) } }
                }
            }
        }
    }
}

/// QR code as inline SVG (drawn locally; the address never leaves the screen).
fn qr_svg(text: &str) -> String {
    qrcode::QrCode::new(text.as_bytes()).map(|c| c.render::<qrcode::render::svg::Color>().min_dimensions(200, 200).dark_color(qrcode::render::svg::Color("#000")).light_color(qrcode::render::svg::Color("#fff")).build()).unwrap_or_default()
}

#[component]
pub fn Support() -> Element {
    let mut shown = use_signal(|| None::<usize>);
    rsx! {
        div { class: "page settings",
            TopBar { title: tr!("Підтримати автора") }
            div { class: "card-note",
                b { {tr!("Дякуємо, що слухаєте з Privacy Music")} }
                p { class: "dim", {tr!("Застосунок безкоштовний, без реклами й стеження. Ось як можна допомогти йому розвиватися:")} }
            }
            div { class: "group-title", {tr!("Криптовалюта")} }
            p { class: "dim", {tr!("Підтримка приймається лише в криптовалюті. Monero рекомендовано: перекази в ній приватні за замовчуванням. Для USDT використовуйте лише мережу Arbitrum One — кошти з інших мереж буде втрачено.")} }
            for (i, d) in crate::links::DONATIONS.iter().enumerate() {
                div { key: "{d.coin}{d.network}", class: "coin",
                    div { class: "coin-head",
                        div { class: "grow", b { "{d.coin}" } span { class: "dim", " · " {tr!("мережа {0}", d.network)} } if d.recommended { span { class: "badge", {tr!("Рекомендовано")} } } }
                        if !d.address.is_empty() {
                            button { class: "btn", onclick: move |_| shown.set(if *shown.read() == Some(i) { None } else { Some(i) }), Icon { name: "table_view" } {tr!("QR-код")} }
                            button { class: "btn accent", onclick: move |_| { copy(d.address); toast(tr!("Адресу скопійовано")) }, Icon { name: "content_copy" } {tr!("Скопіювати")} }
                        }
                    }
                    if d.address.is_empty() { div { class: "coin-addr", {tr!("Адресу буде додано незабаром")} } } else { div { class: "coin-addr", "{d.address}" } }
                    if *shown.read() == Some(i) { div { class: "qr", dangerous_inner_html: "{qr_svg(d.address)}" } }
                }
            }
        }
    }
}

// ---------- moving data from the phone ----------
#[component]
pub fn Transfer() -> Element { rsx! { div { class: "page settings", TopBar { title: tr!("Імпорт") } TransferPanel {} } } }

#[component]
fn TransferPanel() -> Element {
    let mut file = use_signal(|| None::<std::path::PathBuf>);
    let mut key = use_signal(String::new);
    let mut state = use_signal(|| None::<Result<crate::backup::Summary, String>>);
    let mut busy = use_signal(|| false);
    let mut confirm = use_signal(|| false);
    let mut run = move || {
        let (Some(path), k) = (file.read().clone(), key.read().clone()) else { return };
        busy.set(true);
        spawn(async move {
            let r = bg(async move {
                let bytes = tokio::fs::read(&path).await.map_err(|e| e.to_string())?;
                let payload = crate::backup::open(&bytes, &k).map_err(|e| e.to_string())?;
                crate::backup::restore(&payload).map_err(|e| e.to_string())
            }).await;
            *SETTINGS.write() = crate::settings::get();
            crate::i18n::set_language(&crate::settings::get().ui_language);
            busy.set(false); state.set(Some(r));
        });
    };
    let name = file.read().as_ref().and_then(|p| p.file_name()).map(|n| n.to_string_lossy().to_string());
    rsx! {
        div {
            div { class: "card-note",
                b { {tr!("Перенести дані з телефона")} }
                p { {tr!("1. На телефоні: Налаштування → Резервні копії → «Зашифрована резервна копія». Створіть копію й збережіть файл .pmbackup туди, звідки його можна взяти на комп'ютері (Proton Drive, Google Drive, кабель).")} }
                p { {tr!("2. Тут виберіть цей файл і введіть ключ відновлення з телефона (52 символи). Розшифрування відбувається лише на цьому комп'ютері.")} }
                p { class: "dim", {tr!("Перенесуться медіатека, плейлисти, лайки, збережені альбоми й виконавці, історія, статистика, закріплене й налаштування приватності. Поточна медіатека на комп'ютері буде замінена. Пісні з файлів телефона не переносяться.")} }
            }
            div { class: "group",
                button { class: "set-row", onclick: move |_| { spawn(async move {
                        if let Some(f) = rfd::AsyncFileDialog::new().add_filter("Privacy Music backup", &["pmbackup"]).pick_file().await { file.set(Some(f.path().to_path_buf())); state.set(None) }
                    }); },
                    Ic { name: "folder_open", color: BLUE } div { class: "grow", div { {tr!("Файл резервної копії")} } div { class: "dim small", {name.clone().unwrap_or_else(|| tr!("Вибрати файл"))} } } span { class: "chev", Icon { name: "chevron_right" } } }
                div { class: "set-row", Ic { name: "key", color: ORANGE }
                    input { class: "field key-field", placeholder: "XXXX-XXXX-XXXX-…", value: "{key}", oninput: move |e| key.set(e.value()) } }
            }
            div { class: "pills",
                button { class: "btn accent", disabled: file.read().is_none() || key.read().trim().is_empty() || *busy.read(), onclick: move |_| confirm.set(true),
                    if *busy.read() { span { class: "spinner" } } else { Icon { name: "settings_backup_restore" } } {tr!("Перенести")} }
            }
            match state.read().clone() {
                Some(Ok(s)) => rsx! { div { class: "card-note", b { {tr!("Готово")} } p { {tr!("Пісень: {0} • лайків: {1} • плейлистів: {2} • збережено: {3} • історія: {4} • прослуховувань: {5}", s.songs, s.liked, s.playlists, s.saved, s.history, s.plays)} } } },
                Some(Err(e)) => rsx! { ErrorNote { err: e.to_string() } },
                None => rsx! {},
            }
        }
        if *confirm.read() {
            div { class: "scrim dim", onclick: move |_| confirm.set(false) }
            div { class: "dialog",
                h3 { {tr!("Замінити медіатеку на комп'ютері?")} }
                p { {tr!("Медіатека, історія й статистика на цьому комп'ютері будуть замінені даними з телефона.")} }
                div { class: "pills",
                    button { class: "btn", onclick: move |_| confirm.set(false), {tr!("Скасувати")} }
                    button { class: "btn accent", onclick: move |_| { confirm.set(false); run() }, {tr!("Перенести")} }
                }
            }
        }
    }
}

// ---------- import ----------
#[derive(Clone, PartialEq)]
enum Step { Idle, Working(String), Review { name: String, entries: Vec<crate::importer::Entry>, songs: Vec<Song> }, Done(String) }

#[component]
pub fn Import() -> Element {
    let mut step = use_signal(|| Step::Idle);
    let mut link = use_signal(String::new);
    let mut progress = use_signal(|| (0usize, 0usize));
    let mut keep_files = use_signal(|| true);
    let mut src_tab = use_signal(|| "telegram");
    let pick = move |kind: &'static str| async move {
        let mut d = rfd::AsyncFileDialog::new();
        d = if kind == "tg" { d.set_title(tr!("Файл result.json з експорту Telegram")).add_filter("Telegram export", &["json"]) } else { d.add_filter("CSV / TSV", &["csv", "tsv", "txt"]) };
        let Some(f) = d.pick_file().await else { return };
        let path = f.path().to_path_buf();
        step.set(Step::Working(tr!("Читаю файл…")));
        let r = if kind == "tg" { crate::importer::telegram(&path) } else { crate::importer::table(&path).map(|e| (path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default(), e)) };
        match r {
            Ok((name, entries)) if !entries.is_empty() => step.set(Step::Review { name, entries, songs: vec![] }),
            Ok(_) => step.set(Step::Done(tr!("У файлі не знайдено пісень"))),
            Err(e) => step.set(Step::Done(e.to_string())),
        }
    };
    let finish = move |name: String, entries: Vec<crate::importer::Entry>, songs: Vec<Song>| async move {
        let mut found = songs;
        let mut missing = 0;
        let keep = *keep_files.read();
        // Telegram files that were exported play from disk; the rest are looked up on YouTube Music.
        let (local, remote): (Vec<_>, Vec<_>) = entries.into_iter().partition(|e| keep && e.file.is_some());
        let local_songs: Vec<Song> = local.iter().filter_map(crate::importer::local_song).collect();
        if !remote.is_empty() {
            step.set(Step::Working(tr!("Шукаю пісні на YouTube Music…")));
            let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
            let job = bg(crate::importer::match_all(remote, move |d, t| { let _ = tx.send((d, t)); }));
            let mut job = Box::pin(job);
            let (m, miss) = loop {
                tokio::select! {
                    r = &mut job => break r,
                    Some(p) = rx.recv() => progress.set(p),
                }
            };
            found.extend(m); missing = miss.len();
        }
        let mut all = local_songs; all.extend(found);
        if all.is_empty() { step.set(Step::Done(tr!("Нічого не вдалося знайти"))); return }
        let id = db::create_playlist(&name);
        db::add_to_playlist(id, &all);
        // Telegram files have no artwork: look it up on YouTube Music in the background (Settings → Privacy can turn it off).
        if SETTINGS.peek().auto_covers { let a = all.clone(); spawn_bg(async move { crate::covers::find(a).await; }) }
        let n = all.len();
        step.set(Step::Done(tr!("Імпортовано {0} пісень у «{1}». Не знайдено: {2}", n, name, missing)));
    };
    rsx! {
        div { class: "page settings",
            TopBar { title: tr!("Імпорт") }
            match step.read().clone() {
                Step::Idle => rsx! {
                    div { class: "chips",
                        for (id, label) in [("telegram", "Telegram".to_string()), ("link", tr!("Посилання")), ("table", tr!("Таблиця")), ("phone", tr!("З резервної копії"))] {
                            button { class: if *src_tab.read() == id { "chip on" } else { "chip" }, onclick: move |_| src_tab.set(id), "{label}" }
                        }
                    }
                    if *src_tab.read() == "telegram" { div { class: "card-note",
                        b { "Telegram" }
                        p { class: "dim", {tr!("У Telegram Desktop: Налаштування → Додатково → Експорт даних (або ⋮ у чаті → Експорт історії чату), формат JSON, з аудіофайлами. Потім виберіть файл result.json. Застосунок не входить у ваш Telegram — він лише читає експорт.")} }
                        Toggle { icon: "music_note", color: BLUE, title: tr!("Зберегти аудіофайли з експорту"), sub: Some(tr!("Пісні, файли яких є в експорті, гратимуть з диска без інтернету. Решту знайдемо на YouTube Music.")), on: *keep_files.read(), onchange: move |v| keep_files.set(v) }
                        button { class: "btn accent", onclick: move |_| { spawn(pick("tg")); }, Icon { name: "folder_open" } {tr!("Вибрати result.json")} }
                    } }
                    if *src_tab.read() == "link" { div { class: "card-note",
                        b { {tr!("Посилання")} }
                        p { class: "dim", {tr!("Плейлист або альбом YouTube Music, чи публічний плейлист Spotify.")} }
                        div { class: "row-form",
                            input { class: "field", placeholder: "https://…", value: "{link}", oninput: move |e| link.set(e.value()) }
                            button { class: "btn accent", disabled: link.read().trim().is_empty(), onclick: move |_| {
                                let url = link.read().trim().to_string();
                                spawn(async move {
                                    step.set(Step::Working(tr!("Завантажую список…")));
                                    match bg(async move { crate::importer::link(&url).await }).await {
                                        Ok((name, entries, songs)) => step.set(Step::Review { name, entries, songs }),
                                        Err(e) => step.set(Step::Done(e.to_string())),
                                    }
                                });
                            }, {tr!("Імпортувати")} }
                        }
                    } }
                    if *src_tab.read() == "table" { div { class: "card-note",
                        b { {tr!("Таблиця")} }
                        p { class: "dim", {tr!("CSV або TSV з колонками назви й виконавця (Exportify, TuneMyMusic, Google Takeout…).")} }
                        button { class: "btn", onclick: move |_| { spawn(pick("csv")); }, Icon { name: "table_view" } {tr!("Вибрати файл")} }
                    } }
                    if *src_tab.read() == "phone" { TransferPanel {} }
                },
                Step::Working(msg) => rsx! { div { class: "card-note center", p { "{msg}" } if progress.read().1 > 0 { progress { max: "{progress.read().1}", value: "{progress.read().0}" } p { class: "dim", "{progress.read().0} / {progress.read().1}" } } } },
                Step::Review { name, entries, songs } => {
                    let files = entries.iter().filter(|e| e.file.is_some()).count();
                    let (n2, e2, s2) = (name.clone(), entries.clone(), songs.clone());
                    rsx! {
                        div { class: "card-note",
                            b { "{name}" }
                            p { {tr!("Знайдено пісень: {0}", entries.len() + songs.len())} if files > 0 { " • " {tr!("з файлами: {0}", files)} } }
                            div { class: "pills",
                                button { class: "btn", onclick: move |_| step.set(Step::Idle), {tr!("Скасувати")} }
                                button { class: "btn accent", onclick: move |_| { spawn(finish(n2.clone(), e2.clone(), s2.clone())); }, {tr!("Створити плейлист")} }
                            }
                        }
                        for (i, e) in entries.iter().take(200).enumerate() { div { key: "{i}", class: "set-row", span { class: "grow", "{e.artist} — {e.title}" } if e.file.is_some() { span { class: "dim small", "♪ file" } } } }
                    }
                },
                Step::Done(msg) => rsx! { div { class: "card-note center", p { "{msg}" } button { class: "btn", onclick: move |_| step.set(Step::Idle), {tr!("Готово")} } } },
            }
        }
    }
}

/// Settings → Devices nearby: turn the link on, pair the phone, choose what is shared.
#[component]
fn DevicesSettings() -> Element {
    let s = SETTINGS.read().clone();
    let link = LINK.read().clone();
    let pairing = link.pairing.clone();
    rsx! {
        p { class: "dim", {tr!("Ваш телефон і цей комп'ютер знаходять одне одного лише у вашій локальній мережі (Wi‑Fi) і спілкуються напряму, з наскрізним шифруванням. Жодних серверів чи акаунтів: якщо вимкнено, застосунок нічого не надсилає й не слухає.")} }
        div { class: "group",
            Toggle { icon: "devices", title: tr!("Пристрої поруч"), sub: Some(tr!("Перемикати, де грає музика, керувати з телефона, синхронізувати медіатеку")), on: s.link,
                onchange: move |v| { set(|s| s.link = v); crate::link::apply() } }
            if let Some(err) = link.error.clone() { div { class: "set-row", Ic { name: "info" } div { class: "grow dim small", "{err}" } } }
        }
        if s.link {
            div { class: "group-title", {tr!("Під'єднані пристрої")} }
            div { class: "group",
                for d in link.devices.clone() {
                    {
                        let id = d.id.clone();
                        rsx! {
                            div { class: "set-row", Ic { name: "mobile" }
                                div { class: "grow", div { "{d.name}" } div { class: "dim small", if d.online { {tr!("У мережі")} } else { {tr!("Не в мережі")} } } }
                                button { class: "btn", onclick: move |_| { crate::link::forget(&id); toast(tr!("Пристрій забуто")) }, {tr!("Забути")} }
                            }
                        }
                    }
                }
                match pairing {
                    crate::link::Pairing::Waiting => rsx! {
                        div { class: "pair-box",
                            div { {tr!("На телефоні відкрийте Налаштування → Пристрої поруч → «Під'єднати комп'ютер» і виберіть «{0}».", crate::link::device_name())} }
                            div { class: "dim small", {tr!("Обидва пристрої мають бути в одній мережі Wi‑Fi. Очікування до 3 хвилин.")} }
                            if let Some(ip) = crate::link::local_address() { div { class: "dim small", {tr!("Якщо комп'ютера немає в списку, введіть на телефоні його адресу: {0}", ip)} } }
                            button { class: "btn", onclick: move |_| crate::link::cancel_pairing(), {tr!("Скасувати")} }
                        }
                    },
                    crate::link::Pairing::Confirm { code, name } => rsx! {
                        div { class: "pair-box",
                            div { {tr!("Перевірте, що на «{0}» показано той самий код:", name)} }
                            div { class: "pair-code", "{&code[..3]} {&code[3..]}" }
                            div { class: "dim small", {tr!("Якщо коди відрізняються, хтось у мережі намагається втрутитися — скасуйте.")} }
                            div { class: "row-btns",
                                button { class: "btn accent", onclick: move |_| crate::link::confirm_pairing(true), {tr!("Код збігається")} }
                                button { class: "btn", onclick: move |_| crate::link::confirm_pairing(false), {tr!("Скасувати")} }
                            }
                        }
                    },
                    other => rsx! {
                        if let crate::link::Pairing::Done(name) = &other { div { class: "set-row", Ic { name: "check" } div { class: "grow", {tr!("«{0}» під'єднано", name)} } } }
                        if let crate::link::Pairing::Failed(msg) = &other { div { class: "set-row", Ic { name: "info" } div { class: "grow dim", "{msg}" } } }
                        Link { icon: "add", title: tr!("Під'єднати телефон"), sub: Some(tr!("Потрібен Privacy Music на телефоні в тій самій мережі Wi‑Fi")), onclick: move |_| crate::link::start_pairing() }
                    },
                }
            }
            div { class: "group-title", {tr!("Що спільне")} }
            div { class: "group",
                Toggle { icon: "sync", title: tr!("Синхронізувати медіатеку"), sub: Some(tr!("Вподобані пісні, плейлисти, збережені альбоми й виконавці. Історія та статистика лишаються на кожному пристрої окремо")), on: s.link_sync,
                    onchange: move |v| { set(|s| s.link_sync = v); if v { crate::link::sync_now() } } }
                if s.link_sync {
                    Link { icon: "sync_alt", title: tr!("Синхронізувати зараз"), sub: link.last_sync.map(|_| tr!("Останній обмін — у цьому сеансі")), onclick: move |_| { crate::link::sync_now(); toast(tr!("Медіатеку надіслано")) } }
                }
                Toggle { icon: "tune", title: tr!("Керування з інших пристроїв"), sub: Some(tr!("Телефон може ставити на паузу, перемикати пісні, змінювати гучність і забирати музику на себе")), on: s.link_control, onchange: move |v| set(|s| s.link_control = v) }
                Toggle { icon: "devices", title: tr!("Грати лише на одному пристрої"), sub: Some(tr!("Коли музика вмикається тут, вона ставиться на паузу на іншому пристрої (як у Spotify)")), on: s.link_single, onchange: move |v| set(|s| s.link_single = v) }
            }
        }
    }
}

/// Where the sound goes: the system default (follows headphones being plugged in / out) or a fixed device.
#[component]
fn OutputDevice() -> Element {
    let cur = SETTINGS.read().audio_device.clone();
    let list = use_resource(|| async { bg(async { tokio::task::spawn_blocking(crate::audio::output_devices).await.unwrap_or_default() }).await });
    let in_use = playback::shared().device.lock().clone();
    let devices = list.read().clone().unwrap_or_default();
    rsx! {
        label { class: "set-row", Ic { name: "speaker" }
            div { class: "grow", div { {tr!("Пристрій виводу звуку")} }
                div { class: "dim small", if cur.is_empty() { {tr!("Як у системі: під'єднані навушники вмикаються одразу, а після від'єднання звук повертається на динаміки")} } else { {tr!("Зараз грає: {0}", in_use)} } } }
            select { onchange: move |e| playback::set_output(e.value()),
                option { value: "", selected: cur.is_empty(), {tr!("Як у системі")} }
                for d in devices.iter() { option { value: "{d}", selected: *d == cur, "{d}" } }
                if !cur.is_empty() && !devices.contains(&cur) { option { value: "{cur}", selected: true, {tr!("{0} (не під'єднано)", cur)} } }
            }
        }
    }
}

/// Update notice: on / off, "check now", and what to run to update.
#[component]
fn UpdateSettings() -> Element {
    let s = SETTINGS.read().clone();
    let mut state = use_signal(|| None::<Result<Option<crate::update::Release>, String>>);
    let mut busy = use_signal(|| false);
    rsx! {
        div { class: "group",
            Toggle { icon: "new_releases", title: tr!("Повідомляти про оновлення"), sub: Some(tr!("Двічі на день запитувати в GitHub номер нової версії. GitHub бачить лише IP-адресу")), on: s.update_check, onchange: move |v| set(|s| s.update_check = v) }
            div { class: "set-row", Ic { name: "info" }
                div { class: "grow", div { {tr!("Версія {0}", crate::update::VERSION)} }
                    div { class: "dim small",
                        match &*state.read() {
                            None => rsx! {},
                            Some(Ok(None)) => rsx! { {tr!("У вас найновіша версія")} },
                            Some(Ok(Some(r))) => rsx! { {tr!("Доступна версія {0}", r.version)} },
                            Some(Err(_)) => rsx! { {tr!("Не вдалося перевірити. Спробуйте пізніше.")} },
                        }
                    }
                }
                button { class: "btn", disabled: *busy.read() || s.offline, onclick: move |_| {
                    busy.set(true);
                    // A one-off check asked for by the user works even with the automatic notice turned off.
                    spawn(async move {
                        let was = SETTINGS.peek().update_check;
                        if !was { crate::settings::update(|s| s.update_check = true) }
                        let r = bg(crate::update::check()).await.map_err(|e| e.to_string());
                        if !was { crate::settings::update(|s| s.update_check = false) }
                        if let Ok(Some(rel)) = &r { *UPDATE.write() = Some(rel.clone()) }
                        state.set(Some(r)); busy.set(false);
                    });
                }, if *busy.read() { span { class: "spinner" } } else { Icon { name: "refresh", size: 18 } } {tr!("Перевірити зараз")} }
            }
        }
    }
}

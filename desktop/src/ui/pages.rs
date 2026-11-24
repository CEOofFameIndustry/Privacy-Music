use super::components::*;
use super::*;
use crate::db;
use crate::model::{Item, Shelf, Song};

// ---------- user layouts (same model as the phone: "id,-hidden,id") ----------
pub fn arrange(saved: &str, available: &[String]) -> Vec<(String, bool)> {
    let clean = |s: &str| s.replace(',', " ");
    let ids: Vec<String> = available.iter().map(|s| clean(s)).collect();
    let mut out: Vec<(String, bool)> = Vec::new();
    for part in saved.split(',').filter(|p| !p.trim().is_empty()) {
        let (id, on) = match part.strip_prefix('-') { Some(x) => (x.to_string(), false), None => (part.to_string(), true) };
        if ids.contains(&id) && !out.iter().any(|(i, _)| *i == id) { out.push((id, on)) }
    }
    for (i, id) in ids.iter().enumerate() { if !out.iter().any(|(x, _)| x == id) { out.insert(i.min(out.len()), (id.clone(), true)) } }
    out
}
pub fn visible(saved: &str, available: &[String]) -> Vec<String> { arrange(saved, available).into_iter().filter(|x| x.1).map(|x| x.0).collect() }
pub fn encode(l: &[(String, bool)]) -> String { l.iter().map(|(id, on)| format!("{}{}", if *on { "" } else { "-" }, id.replace(',', " "))).collect::<Vec<_>>().join(",") }
pub fn default_tabs() -> Vec<String> {
    if SETTINGS.read().ui_style == "minimal" { vec!["home".into(), "library".into(), "search".into()] } else { vec!["home".into(), "search".into(), "library".into()] }
}
pub fn ordered_tabs() -> Vec<String> { visible(&SETTINGS.read().layout_tabs, &default_tabs()) }

/// Drag ≡ to reorder, eye to hide — plus the sidebar tabs. Saved locally only.
#[component]
pub fn LayoutEditor(title: String, which: String, entries: Vec<(String, String)>, on_done: EventHandler<()>) -> Element {
    let saved = { let s = SETTINGS.read(); match which.as_str() { "home" => s.layout_home.clone(), "library" => s.layout_library.clone(), _ => s.layout_search.clone() } };
    let ids: Vec<String> = entries.iter().map(|e| e.0.clone()).collect();
    let mut blocks = use_signal(|| arrange(&saved, &ids));
    let mut tabs = use_signal(|| arrange(&SETTINGS.read().layout_tabs, &default_tabs()));
    let mut drag_b = use_signal(|| None::<(usize, usize)>);
    let mut drag_t = use_signal(|| None::<(usize, usize)>);
    let labels: std::collections::HashMap<String, String> = entries.iter().map(|(a, b)| (a.replace(',', " "), b.clone())).collect();
    let tab_labels = |id: &str| match id { "search" => tr!("Пошук"), "library" => tr!("Медіатека"), _ => tr!("Головна") };
    let w = which.clone();
    let save = move || {
        let b = encode(&blocks.read()); let t = encode(&tabs.read());
        let w = w.clone();
        set(move |s| { match w.as_str() { "home" => s.layout_home = b, "library" => s.layout_library = b, _ => s.layout_search = b }; s.layout_tabs = t });
    };
    let ids2 = ids.clone();
    rsx! {
        div { class: "page editor",
            div { class: "topbar",
                h1 { class: "page-title", "{title}" }
                div { class: "actions",
                    button { class: "btn", onclick: move |_| { blocks.set(arrange("", &ids2)); tabs.set(arrange("", &default_tabs())) }, {tr!("Скинути")} }
                    button { class: "btn accent", onclick: { let save = save.clone(); move |_| { save(); on_done.call(()) } }, {tr!("Готово")} }
                }
            }
            p { class: "dim", {tr!("Перетягніть ≡, щоб змінити порядок. Око — показати або сховати блок. Зберігається лише на цьому пристрої.")} }
            div { onmouseup: move |_| if let Some((f, t)) = reorder_end(drag_b) { let mut b = blocks.write(); let x = b.remove(f); b.insert(t, x); },
                onmouseleave: move |_| if let Some((f, t)) = reorder_end(drag_b) { let mut b = blocks.write(); let x = b.remove(f); b.insert(t, x); },
            for (i, (id, on)) in blocks.read().clone().into_iter().enumerate() {
                div { key: "{id}", class: reorder_class(*drag_b.read(), i, if on { "drag-row" } else { "drag-row off" }),
                    onmouseenter: move |_| { let d = *drag_b.peek(); if let Some((f, _)) = d { drag_b.set(Some((f, i))) } },
                    span { class: "handle", onmousedown: move |e| { e.prevent_default(); drag_b.set(Some((i, i))) }, Icon { name: "drag_indicator" } }
                    span { class: "grow", {labels.get(&id).cloned().unwrap_or(id.clone())} }
                    button { class: "icon-btn", title: if on { tr!("Сховати") } else { tr!("Показати") }, onclick: move |_| { blocks.write()[i].1 = !on }, Icon { name: if on { "visibility" } else { "visibility_off" } } }
                }
            }
            }
            h3 { {tr!("Вкладки внизу")} }
            div { onmouseup: move |_| if let Some((f, t)) = reorder_end(drag_t) { let mut v = tabs.write(); let x = v.remove(f); v.insert(t, x); },
                onmouseleave: move |_| if let Some((f, t)) = reorder_end(drag_t) { let mut v = tabs.write(); let x = v.remove(f); v.insert(t, x); },
            for (i, (id, _)) in tabs.read().clone().into_iter().enumerate() {
                div { key: "t{id}", class: reorder_class(*drag_t.read(), i, "drag-row"),
                    onmouseenter: move |_| { let d = *drag_t.peek(); if let Some((f, _)) = d { drag_t.set(Some((f, i))) } },
                    span { class: "handle", onmousedown: move |e| { e.prevent_default(); drag_t.set(Some((i, i))) }, Icon { name: "drag_indicator" } } span { class: "grow", {tab_labels(&id)} }
                }
            }
            }
        }
    }
}

// ---------- home ----------
pub fn home_blocks() -> Vec<(String, String)> {
    vec![("greeting".into(), tr!("Привітання")), ("pins".into(), tr!("Закріплене")), ("quick".into(), tr!("Швидкий вибір")),
        ("again".into(), tr!("Слухати знову")), ("recent".into(), tr!("Нещодавно додане")), ("my_artists".into(), tr!("Ваші виконавці")),
        ("forgotten".into(), tr!("Давно не слухали")), ("new".into(), tr!("Нові альбоми й сингли")), ("top_tracks".into(), tr!("Топ треків")),
        ("top_artists".into(), tr!("Топ виконавців")), ("charts".into(), tr!("Чарти"))]
}

#[component]
pub fn Home() -> Element {
    let _ = LIBV.read();
    // Personal shelves are computed on this computer from the local history — nothing is sent anywhere.
    // "Refresh" picks other seeds from what you play most and like, so the selection changes each time.
    let mut quick_round = use_signal(|| 0u32);
    let quick = use_resource(move || async move {
        let _ = quick_round.read();
        use rand::seq::SliceRandom;
        let mut seeds = db::top_songs(15);
        seeds.extend(db::liked().into_iter().take(30));
        seeds.retain(|s| !s.id.starts_with("file:"));
        seeds.dedup_by(|a, b| a.id == b.id);
        seeds.shuffle(&mut rand::rng());
        let mut out: Vec<Song> = Vec::new();
        for s in seeds.into_iter().take(3) {
            let id = s.id.clone();
            if let Ok(mut r) = bg(async move { crate::yt::radio(&id).await }).await {
                let mut rest = r.split_off(1.min(r.len()));
                rest.shuffle(&mut rand::rng());
                for x in rest.into_iter().take(6) { if !out.iter().any(|o| o.id == x.id) && !db::is_disliked(&x.id) { out.push(x) } }
            }
        }
        out.shuffle(&mut rand::rng());
        out
    });
    let explore = use_resource(|| async { bg(crate::yt::explore()).await });
    // Which blocks and in what order: Settings → Home page.
    let order = visible(&SETTINGS.read().layout_home, &home_blocks().into_iter().map(|b| b.0).collect::<Vec<_>>());
    let history = db::history(16);
    let recent_ids: std::collections::HashSet<String> = db::history(60).into_iter().map(|s| s.id).collect();
    let forgotten: Vec<Song> = db::liked().into_iter().filter(|s| !recent_ids.contains(&s.id)).take(12).collect();
    let pins = db::pins();
    let mut added: Vec<Item> = db::saved("album");
    added.extend(db::saved("playlist"));
    added.extend(db::playlists().into_iter().map(|p| Item::Local { id: p.id, title: p.name, count: p.count, thumbnail: p.thumbnail }));
    let my_artists = db::saved("artist");
    let yt = explore.read().as_ref().and_then(|r| r.as_ref().ok()).cloned().unwrap_or_default();
    let yt_loading = explore.read().is_none();
    rsx! {
        div { class: "page",
            for block in order {
                match block.as_str() {
                    "greeting" => rsx! {
                        h1 { class: "greeting", {greeting()} }
                        if history.is_empty() && pins.is_empty() {
                            div { class: "card-note",
                                b { {tr!("Ваші добірки з'являться тут")} }
                                p { class: "dim", {tr!("Слухайте й ставте лайки — і тут з'являться «Швидкий вибір», мікси й новинки ваших виконавців. ")} {tr!("Рекомендації рахуються на цьому пристрої, ваші смаки нікуди не надсилаються.")} }
                            }
                        }
                    },
                    "pins" => rsx! { if !pins.is_empty() { ShelfView { shelf: Shelf { id: None, title: tr!("Закріплене"), items: pins.clone(), list: false } } } },
                    "quick" => rsx! {
                        if let Some(q) = quick.read().as_ref().filter(|q| !q.is_empty()) {
                            ShelfView { shelf: Shelf { id: None, title: tr!("Швидкий вибір"), items: q.iter().cloned().map(Item::Song).collect(), list: true },
                                on_refresh: move |_| *quick_round.write() += 1 }
                        }
                    },
                    "again" => rsx! { if !history.is_empty() { ShelfView { shelf: Shelf { id: None, title: tr!("Слухати знову"), items: history.iter().cloned().map(Item::Song).collect(), list: false } } } },
                    "recent" => rsx! { if !added.is_empty() { ShelfView { shelf: Shelf { id: None, title: tr!("Нещодавно додане"), items: added.clone(), list: false } } } },
                    "my_artists" => rsx! { if !my_artists.is_empty() { ShelfView { shelf: Shelf { id: None, title: tr!("Ваші виконавці"), items: my_artists.clone(), list: false } } } },
                    "forgotten" => rsx! { if forgotten.len() >= 3 { ShelfView { shelf: Shelf { id: None, title: tr!("Давно не слухали"), items: forgotten.iter().cloned().map(Item::Song).collect(), list: true } } } },
                    id => rsx! {
                        if let Some(sh) = yt.iter().find(|s| s.id.as_deref() == Some(id)) { ShelfView { key: "{id}", shelf: sh.clone() } }
                        else if yt_loading && id == "new" { div { class: "loading", span { class: "spinner" } } }
                    },
                }
            }
        }
    }
}

// ---------- search ----------
#[component]
pub fn Search() -> Element {
    let _ = LIBV.read();
    let mut editing = use_signal(|| false);
    let q = QUERY.read().clone();
    let suggest = use_resource(move || async move {
        let q = QUERY.read().clone();
        let s = SETTINGS.read().clone();
        if q.trim().is_empty() || !s.search_suggestions || s.offline { return (vec![], vec![]) }
        sleep_ms(250).await;
        if *QUERY.peek() != q { return (vec![], vec![]) }
        bg(async move { crate::yt::suggestions(&q).await.unwrap_or_default() }).await
    });
    let explore = use_resource(|| async { bg(crate::yt::explore()).await });
    let explore_titles: Vec<(String, String)> = explore.read().as_ref().and_then(|r| r.as_ref().ok()).map(|v| v.iter().map(|s| (format!("x:{}", s.title), s.title.clone())).collect()).unwrap_or_default();
    if *editing.read() {
        let mut entries = vec![("history".to_string(), tr!("Історія пошуку"))];
        entries.extend(explore_titles.clone());
        return rsx! { LayoutEditor { title: tr!("Пошук"), which: "search", entries, on_done: move |_| editing.set(false) } };
    }
    let mut ids = vec!["history".to_string()];
    ids.extend(explore_titles.iter().map(|x| x.0.clone()));
    let order = visible(&SETTINGS.read().layout_search, &ids);
    let history = if order.contains(&"history".to_string()) { db::searches(&q) } else { vec![] };
    let submit = move |text: String| {
        let t = text.trim().to_string(); if t.is_empty() { return }
        if let Some((k, id)) = crate::yt::parse_link(&t) {
            match k { 'a' => go(Route::Album(id)), 'p' => go(Route::Playlist(id)), 'r' => go(Route::Artist(id)),
                _ => { spawn(async move { if let Ok(s) = bg(async move { crate::yt::track(&id).await }).await { playback::play_song(s) } }); } }
            return;
        }
        db::add_search(&t);
        go(Route::Results(t));
    };
    let sub2 = submit.clone();
    rsx! {
        div { class: "page",
            div { class: "topbar",
                h1 { class: "page-title", {tr!("Пошук")} }
            }
            div { class: "search-box",
                Icon { name: "search" }
                input { class: "search-input", placeholder: tr!("Шукати пісні, виконавців, альбоми"), value: "{q}",
                    // One click on the magnifier = ready to type.
                    onmounted: move |e| async move { let _ = e.set_focus(true).await; },
                    oninput: move |e| *QUERY.write() = e.value(),
                    onkeydown: move |e| if e.key() == Key::Enter { submit(QUERY.read().clone()) } }
                if !q.is_empty() { button { class: "icon-btn", onclick: move |_| QUERY.write().clear(), Icon { name: "cancel", fill: true, size: 18 } } }
            }
            if !SETTINGS.read().search_history && q.is_empty() { p { class: "dim", {tr!("Історію пошуку вимкнено в налаштуваннях приватності — запити не зберігаються.")} } }
            for h in history {
                div { key: "h{h}", class: "suggest",
                    Icon { name: "history" }
                    span { class: "grow", onclick: { let h = h.clone(); let s = sub2.clone(); move |_| s(h.clone()) }, "{h}" }
                    button { class: "icon-btn", title: tr!("Видалити з історії"), onclick: { let h = h.clone(); move |_| db::remove_search(&h) }, Icon { name: "close", size: 18 } }
                }
            }
            if let Some((terms, items)) = suggest.read().clone() {
                for t in terms { div { key: "s{t}", class: "suggest", onclick: { let t = t.clone(); let s = sub2.clone(); move |_| s(t.clone()) }, Icon { name: "search" } span { class: "grow", "{t}" } } }
                for it in items { ItemRow { key: "{it.key()}", item: it } }
            }
            if q.is_empty() {
                match &*explore.read() {
                    None => rsx! { div { class: "loading", span { class: "spinner" } } },
                    Some(Err(e)) => rsx! { ErrorNote { err: e.to_string() } },
                    Some(Ok(sh)) => rsx! {
                        for id in order.iter().filter(|i| i.starts_with("x:")) {
                            if let Some(s) = sh.iter().find(|s| format!("x:{}", s.title) == **id) { ShelfView { key: "{id}", shelf: s.clone() } }
                        }
                    },
                }
            }
        }
    }
}

#[component]
pub fn Results(query: String) -> Element {
    let q = query.clone();
    let res = use_resource(move || { let q = q.clone(); async move { bg(async move { crate::yt::search_all(&q).await }).await } });
    let mut filter = use_signal(|| "all".to_string());
    let filters = [("all", tr!("Усе")), ("songs", tr!("Пісні")), ("albums", tr!("Альбоми")), ("artists", tr!("Виконавці")), ("playlists", tr!("Плейлисти"))];
    let out = rsx! {
        div { class: "page",
            TopBar { title: query.clone() }
            div { class: "chips",
                for (id, f) in filters { button { class: if *filter.read() == id { "chip on" } else { "chip" }, onclick: move |_| filter.set(id.to_string()), "{f}" } }
            }
            match &*res.read() {
                None => rsx! { div { class: "loading", span { class: "spinner" } } },
                Some(Err(e)) => rsx! { ErrorNote { err: e.to_string() } },
                Some(Ok(shelves)) => {
                    let f = filter.read().clone();
                    rsx! {
                        for sh in shelves.iter().filter(|s| f == "all" || s.id.as_deref() == Some(f.as_str())) {
                            if f == "all" { ShelfView { key: "{sh.title}", shelf: Shelf { items: sh.items.iter().take(if sh.list { 8 } else { 20 }).cloned().collect(), ..sh.clone() } } }
                            else if sh.list {
                                {
                                    let songs: Vec<Song> = sh.items.iter().filter_map(|i| if let Item::Song(s) = i { Some(s.clone()) } else { None }).collect();
                                    let songs = RowQueue::new(songs);
                                    rsx! { for it in sh.items.clone() { ItemRow { key: "{it.key()}", item: it, context: Some(songs.clone()), ctx_title: Some(query.clone()) } } }
                                }
                            } else { div { class: "grid", for it in sh.items.clone() { ItemCard { key: "{it.key()}", item: it } } } }
                        }
                    }
                }
            }
        }
    };
    out
}

// ---------- library ----------
fn lib_entries() -> Vec<(&'static str, &'static str, String, Route)> {
    vec![("PLAYLISTS", "queue_music", tr!("Плейлисти"), Route::LibSection("playlists".into())), ("SONGS", "favorite", tr!("Сподобалися"), Route::Liked),
        ("ALBUMS", "album", tr!("Альбоми"), Route::LibSection("albums".into())), ("ARTISTS", "mic", tr!("Виконавці"), Route::LibSection("artists".into())),
        ("DOWNLOADS", "download_done", tr!("Завантажено"), Route::Downloads), ("HISTORY", "history", tr!("Історія"), Route::History)]
}

#[component]
pub fn Library() -> Element {
    let _ = LIBV.read();
    let mut editing = use_signal(|| false);
    let all = lib_entries();
    let mut ids: Vec<String> = all.iter().map(|a| a.0.to_string()).collect();
    ids.push("RECENT".into());
    if *editing.read() {
        let mut entries: Vec<(String, String)> = all.iter().map(|a| (a.0.to_string(), a.2.clone())).collect();
        entries.push(("RECENT".into(), tr!("Нещодавно додане")));
        return rsx! { LayoutEditor { title: tr!("Медіатека"), which: "library", entries, on_done: move |_| editing.set(false) } };
    }
    let order = visible(&SETTINGS.read().layout_library, &ids);
    let shown: Vec<_> = order.iter().filter_map(|id| all.iter().find(|a| a.0 == id)).cloned().collect();
    let mut recent: Vec<Item> = db::saved("album");
    recent.extend(db::saved("playlist"));
    recent.extend(db::playlists().into_iter().map(|p| Item::Local { id: p.id, title: p.name, count: p.count, thumbnail: p.thumbnail }));
    rsx! {
        div { class: "page",
            TopBar { title: tr!("Медіатека"),
            }
            div { class: "lib-list",
                for (_, icon, label, to) in shown { button { class: "lib-row", onclick: move |_| go(to.clone()), Icon { name: icon } span { class: "grow", "{label}" } span { class: "chev", Icon { name: "chevron_right" } } } }
            }
            if order.contains(&"RECENT".to_string()) {
                h2 { class: "section-head", {tr!("Нещодавно додане")} }
                if recent.is_empty() { p { class: "dim", {tr!("Тут з'являться альбоми й плейлисти, які ви додасте в медіатеку.")} } }
                div { class: "grid", for it in recent { ItemCard { key: "{it.key()}", item: it } } }
            }
        }
    }
}

#[component]
pub fn LibSection(kind: String) -> Element {
    let title = match kind.as_str() { "albums" => tr!("Альбоми"), "artists" => tr!("Виконавці"), _ => tr!("Плейлисти") };
    rsx! {
        div { class: "page",
            TopBar { title }
            match kind.as_str() {
                "albums" => rsx! { SavedGrid { kind: "album" } },
                "artists" => rsx! { SavedGrid { kind: "artist" } },
                _ => rsx! { PlaylistsList {} },
            }
        }
    }
}

#[component]
fn PlaylistsList() -> Element {
    let _ = LIBV.read();
    let mut name = use_signal(String::new);
    rsx! {
        div { class: "row-form",
            input { class: "field", placeholder: tr!("Новий плейлист"), value: "{name}", oninput: move |e| name.set(e.value()) }
            button { class: "btn accent", disabled: name.read().trim().is_empty(), onclick: move |_| { let id = db::create_playlist(name.read().trim()); name.set(String::new()); go(Route::Local(id)) }, {tr!("Створити")} }
            button { class: "btn", onclick: move |_| go(Route::Import), Icon { name: "upload_file" } {tr!("Імпортувати плейлист")} }
        }
        ItemRow { item: Item::Playlist { id: "__liked".into(), title: tr!("Сподобалися"), subtitle: tr!("Автоплейлист • {0}", db::liked().len()), thumbnail: None } }
        for p in db::playlists() { ItemRow { key: "{p.id}", item: Item::Local { id: p.id, title: p.name, count: p.count, thumbnail: p.thumbnail } } }
        for it in db::saved("playlist") { ItemRow { key: "{it.key()}", item: it } }
    }
}

#[component]
fn SavedGrid(kind: String) -> Element {
    let _ = LIBV.read();
    let items = db::saved(&kind);
    rsx! {
        if items.is_empty() { p { class: "dim", if kind == "album" { {tr!("Натисніть «Додати в медіатеку» на сторінці альбому.")} } else { {tr!("Натисніть «Підписатися» на сторінці виконавця. Підписки зберігаються тільки на цьому пристрої.")} } } }
        div { class: "grid", for it in items { ItemCard { key: "{it.key()}", item: it } } }
    }
}

#[component]
pub fn SongList(kind: String) -> Element {
    let title = match kind.as_str() { "liked" => tr!("Сподобалися"), "downloads" => tr!("Завантажено"), _ => tr!("Історія") };
    rsx! { div { class: "page", TopBar { title } SongListInner { kind } } }
}

#[component]
fn SongListInner(kind: String) -> Element {
    let _ = LIBV.read();
    let all = match kind.as_str() { "liked" => db::liked(), "downloads" => db::downloads(), _ => db::history(500) };
    let title = match kind.as_str() { "liked" => tr!("Сподобалися"), "downloads" => tr!("Завантажено"), _ => tr!("Історія") };
    let query = use_signal(String::new);
    let sort = list_sort(&kind, false);
    let order = arrange_songs(&all, &sort, &query.read(), kind != "downloads");
    // What is shown is also what plays (sorted / filtered).
    let songs = RowQueue::new(order.iter().map(|&i| all[i].clone()).collect());
    let compact = SETTINGS.read().list_compact;
    let (s1, s2, t1, t2) = (songs.clone(), songs.clone(), title.clone(), title.clone());
    rsx! {
        div { class: "pills",
            button { class: "btn accent", disabled: songs.is_empty(), onclick: move |_| playback::play_queue(s1.to_vec(), 0, Some(t1.clone()), false), Icon { name: "play_arrow", fill: true } {tr!("Відтворити")} }
            button { class: "btn", disabled: songs.is_empty(), onclick: move |_| playback::play_queue(s2.to_vec(), 0, Some(t2.clone()), true), Icon { name: "shuffle" } {tr!("Перемішати")} }
            if kind == "history" && !songs.is_empty() { button { class: "btn", onclick: move |_| db::clear_history(), {tr!("Очистити історію прослуховування")} } }
            div { class: "grow" }
            if !all.is_empty() { ListTools { list: kind.clone(), custom: false, query } }
        }
        if songs.is_empty() {
            p { class: "dim", match kind.as_str() {
                "liked" => tr!("Натисніть 👍 у плеєрі або в меню пісні. Лайки зберігаються лише на цьому пристрої."),
                "downloads" => tr!("Завантажені пісні грають без інтернету."),
                _ => tr!("Тут з'являться пісні, які ви слухали."),
            } }
        }
        if !all.is_empty() && songs.is_empty() { p { class: "dim", {tr!("Нічого не знайдено")} } }
        div { class: if compact { "tracklist compact" } else { "tracklist" }, "data-flip": "",
            for (k, s) in stable_keys(&songs).into_iter().zip(songs.iter()) { div { key: "{k}", "data-key": "{k}", ItemRow { item: Item::Song(s.clone()), context: Some(songs.clone()), ctx_title: Some(title.clone()) } } }
        }
    }
}

// ---------- album / playlist ----------
#[component]
pub fn Collection(id: String, album: bool) -> Element {
    let _ = LIBV.read();
    let i2 = id.clone();
    let page = use_resource(move || { let id = i2.clone(); async move {
        if id == "__liked" { return Ok(crate::yt::AlbumPage { title: tr!("Сподобалися"), artists: vec![], subtitle: String::new(), thumbnail: None, description: None, songs: db::liked() }) }
        bg(async move { if album { crate::yt::album_page(&id).await } else { crate::yt::playlist_page(&id).await } }).await
    } });
    let kind = if album { "album" } else { "playlist" };
    let saved = db::is_saved(kind, &id);
    let query = use_signal(String::new);
    let list_id = format!("{kind}:{id}");
    let sort = list_sort(&list_id, true);
    let compact = SETTINGS.read().list_compact;
    let out = match &*page.read() {
        None => rsx! { div { class: "page", TopBar { title: "".to_string() } div { class: "loading", span { class: "spinner" } } } },
        Some(Err(e)) => rsx! { div { class: "page", TopBar { title: "".to_string() } ErrorNote { err: e.to_string() } } },
        Some(Ok(p)) => {
            let item = if album { Item::Album { id: id.clone(), title: p.title.clone(), artists: p.artists.clone(), year: None, thumbnail: p.thumbnail.clone() } }
                else { Item::Playlist { id: id.clone(), title: p.title.clone(), subtitle: p.subtitle.clone(), thumbnail: p.thumbnail.clone() } };
            let order = arrange_songs(&p.songs, &sort, &query.read(), id == "__liked");
            let original = sort == "custom" && query.read().is_empty();
            let songs = RowQueue::new(order.iter().map(|&i| p.songs[i].clone()).collect());
            let (s1, s2, s3) = (songs.clone(), songs.clone(), songs.clone());
            let (t1, t2) = (p.title.clone(), p.title.clone());
            let it = item.clone();
            let link = share_link(&item).unwrap_or_default();
            rsx! {
                div { class: "page",
                    TopBar { title: "".to_string(),
                        if id != "__liked" {
                            button { class: "icon-btn", title: if saved { tr!("У медіатеці") } else { tr!("Додати в медіатеку") }, onclick: move |_| { db::save(&it, !saved); toast(if saved { tr!("Видалено з медіатеки") } else { tr!("Додано в медіатеку") }) }, Icon { name: if saved { "library_add_check" } else { "library_add" } } }
                            button { class: "icon-btn", title: tr!("Копіювати посилання"), onclick: move |_| { copy(&link); toast(tr!("Посилання скопійовано")) }, Icon { name: "link" } }
                        }
                        button { class: "icon-btn", title: tr!("Завантажити"), onclick: move |_| player::download(s3.to_vec()), Icon { name: "download" } }
                    }
                    div { class: "collection-head glass",
                        Thumb { url: p.thumbnail.clone(), class: "big-cover".to_string(), round: false }
                        div { class: "collection-info",
                            h1 { "{p.title}" }
                            div { class: "artists",
                                for a in p.artists.clone() {
                                    span { class: if a.id.is_some() { "link" } else { "" }, onclick: move |_| if let Some(i) = a.id.clone() { go(Route::Artist(i)) }, "{a.name} " }
                                }
                            }
                            div { class: "meta", "{p.subtitle} • " {tr!("{0} треків", p.songs.len())} }
                            if let Some(d) = &p.description { p { class: "desc", "{d}" } }
                            div { class: "pills",
                                button { class: "btn accent", onclick: move |_| playback::play_queue(s1.to_vec(), 0, Some(t1.clone()), false), Icon { name: "play_arrow", fill: true } {tr!("Відтворити")} }
                                button { class: "btn", onclick: move |_| playback::play_queue(s2.to_vec(), 0, Some(t2.clone()), true), Icon { name: "shuffle" } {tr!("Перемішати")} }
                            }
                        }
                    }
                    div { class: "list-bar", div { class: "grow" } ListTools { list: list_id.clone(), custom: true, query } }
                    if songs.is_empty() && !p.songs.is_empty() { p { class: "dim", {tr!("Нічого не знайдено")} } }
                    div { class: if compact { "tracklist glass compact" } else { "tracklist glass" }, "data-flip": "",
                        for ((k, s), &i) in stable_keys(&songs).into_iter().zip(songs.iter()).zip(order.iter()) {
                            div { key: "{k}", "data-key": "{k}",
                                ItemRow { item: Item::Song(s.clone()), context: Some(songs.clone()), ctx_title: Some(p.title.clone()), index: if album && original { Some(i + 1) } else { None } }
                            }
                        }
                    }
                }
            }
        }
    };
    out
}

// ---------- artist ----------
#[component]
pub fn Artist(id: String) -> Element {
    let _ = LIBV.read();
    let i2 = id.clone();
    let page = use_resource(move || { let id = i2.clone(); async move { bg(async move { crate::yt::artist_page(&id).await }).await } });
    let subscribed = db::is_saved("artist", &id);
    let out = match &*page.read() {
        None => rsx! { div { class: "page", TopBar { title: "".to_string() } div { class: "loading", span { class: "spinner" } } } },
        Some(Err(e)) => rsx! { div { class: "page", TopBar { title: "".to_string() } ErrorNote { err: e.to_string() } } },
        Some(Ok(a)) => {
            let item = Item::Artist { id: id.clone(), name: a.name.clone(), subtitle: a.subscribers.clone().unwrap_or_default(), thumbnail: a.image.clone() };
            let radio = a.radio.clone();
            let name = a.name.clone();
            let bgimg = img(a.image.as_deref());
            rsx! {
                div { class: "page",
                    div { class: "artist-hero", style: "background-image:url('{bgimg}')",
                        div { class: "hero-shade" }
                        div { class: "hero-text",
                            div { class: "grow", h1 { "{a.name}" } if let Some(s) = &a.subscribers { div { class: "dim", "{s}" } } }
                            if let Some(r) = radio.clone() {
                                { let (r, n) = (r.clone(), name.clone()); rsx! { button { class: "round-play", title: tr!("Радіо"), onclick: move |_| playback::play_radio_playlist(r.clone(), tr!("Радіо: {0}", n)), Icon { name: "play_arrow", fill: true } } } }
                            }
                        }
                    }
                    div { class: "pills",
                        if STACK.read().len() > 1 { button { class: "btn", onclick: move |_| back(), Icon { name: "chevron_left" } {tr!("Назад")} } }
                        button { class: "btn", onclick: move |_| { db::save(&item, !subscribed) }, Icon { name: "star", fill: subscribed } if subscribed { {tr!("Ви підписані")} } else { {tr!("Підписатися")} } }
                        if let Some(r) = radio.clone() { button { class: "btn", onclick: move |_| playback::play_radio_playlist(r.clone(), tr!("Радіо: {0}", name)), Icon { name: "sensors" } {tr!("Радіо")} } }
                    }
                    for s in a.shelves.clone() { ShelfView { key: "{s.title}", shelf: s } }
                    if let Some(d) = &a.description { section { class: "shelf", h2 { class: "shelf-title", {tr!("Про виконавця")} } p { class: "desc", "{d}" } } }
                }
            }
        }
    };
    out
}

// ---------- local playlist (rename, cover, sort, search, reorder by dragging, remove) ----------
#[component]
pub fn LocalPlaylist(id: i64) -> Element {
    let _ = LIBV.read();
    let mut renaming = use_signal(|| false);
    let mut name = use_signal(String::new);
    let mut drag = use_signal(|| None::<(usize, usize)>);
    let query = use_signal(String::new);
    let Some(p) = db::playlist(id) else { return rsx! { div { class: "page", TopBar { title: "".to_string() } p { class: "dim", {tr!("Плейлист не знайдено")} } } } };
    let all = db::playlist_songs(id);
    let list_id = format!("local:{id}");
    let sort = list_sort(&list_id, true);
    let order = arrange_songs(&all, &sort, &query.read(), false);
    // Dragging only makes sense in the playlist's own order, with nothing filtered out.
    let reorderable = sort == "custom" && query.read().is_empty();
    let songs = RowQueue::new(order.iter().map(|&i| all[i].clone()).collect());
    let compact = SETTINGS.read().list_compact;
    let (s1, s2, s3) = (songs.clone(), songs.clone(), songs.clone());
    let (t1, t2) = (p.name.clone(), p.name.clone());
    let missing_art = all.iter().filter(|s| s.id.starts_with("file:") && s.thumbnail.is_none()).count();
    let covers = *COVERS.read();
    let custom_cover = p.custom_cover;
    let pick_cover = move |_| { spawn(async move {
        let Some(f) = rfd::AsyncFileDialog::new().add_filter(tr!("Зображення"), &["jpg", "jpeg", "png", "webp"]).pick_file().await else { return };
        let path = f.path().to_path_buf();
        if !bg(async move { db::set_playlist_cover(id, Some(&path)) }).await { toast(tr!("Не вдалося відкрити зображення")) }
    }); };
    let all2 = all.clone();
    let commit = move |f: usize, t: usize| { let mut v = all2.clone(); let x = v.remove(f); v.insert(t, x); db::set_playlist_songs(id, &v) };
    let commit2 = commit.clone();
    rsx! {
        div { class: "page",
            TopBar { title: "".to_string(),
                button { class: "icon-btn", title: tr!("Перейменувати"), onclick: { let n = p.name.clone(); move |_| { name.set(n.clone()); renaming.set(true) } }, Icon { name: "edit" } }
                button { class: "icon-btn", title: tr!("Змінити обкладинку"), onclick: pick_cover, Icon { name: "add_photo_alternate" } }
                button { class: "icon-btn", title: tr!("Завантажити"), onclick: move |_| player::download(s3.to_vec()), Icon { name: "download" } }
                button { class: "icon-btn", title: tr!("Видалити плейлист"), onclick: move |_| { db::delete_playlist(id); back() }, Icon { name: "delete" } }
            }
            div { class: "collection-head glass",
                div { class: "cover-edit", title: tr!("Змінити обкладинку"), onclick: pick_cover,
                    PlaylistCover { pl: p.clone(), class: "big-cover".to_string() }
                    div { class: "cover-hint", Icon { name: "add_photo_alternate", size: 36 } span { {tr!("Змінити обкладинку")} } }
                    if custom_cover {
                        button { class: "icon-btn cover-reset", title: tr!("Прибрати власну обкладинку"), onclick: move |e| { e.stop_propagation(); db::set_playlist_cover(id, None); }, Icon { name: "close", size: 18 } }
                    }
                }
                div { class: "collection-info",
                    if *renaming.read() {
                        div { class: "row-form",
                            input { class: "field", value: "{name}", oninput: move |e| name.set(e.value()), onmounted: move |e| async move { let _ = e.set_focus(true).await; },
                                onkeydown: move |e| if e.key() == Key::Enter { db::rename_playlist(id, name.read().trim(), ""); renaming.set(false) } }
                            button { class: "btn accent", onclick: move |_| { db::rename_playlist(id, name.read().trim(), ""); renaming.set(false) }, {tr!("Зберегти")} }
                        }
                    } else { h1 { "{p.name}" } }
                    div { class: "dim", {tr!("Плейлист на пристрої • {0} треків • {1}", all.len(), crate::model::fmt_dur(all.iter().filter_map(|s| s.duration).sum()))} }
                    div { class: "pills",
                        button { class: "btn accent", disabled: songs.is_empty(), onclick: move |_| playback::play_queue(s1.to_vec(), 0, Some(t1.clone()), false), Icon { name: "play_arrow", fill: true } {tr!("Відтворити")} }
                        button { class: "btn", disabled: songs.is_empty(), onclick: move |_| playback::play_queue(s2.to_vec(), 0, Some(t2.clone()), true), Icon { name: "shuffle" } {tr!("Перемішати")} }
                        if let Some((d, t)) = covers {
                            span { class: "dim small", {tr!("Пошук обкладинок: {0} з {1}", d, t)} }
                        } else if missing_art > 0 {
                            {
                                let songs = all.clone();
                                rsx! { button { class: "btn", title: tr!("Для файлів без обкладинки шукає пісню на YouTube Music за назвою й виконавцем (як звичайний пошук) і бере її обкладинку. Музика й далі грає з ваших файлів."),
                                    onclick: move |_| { let songs = songs.clone(); spawn_bg(async move { crate::covers::find(songs).await; }) },
                                    Icon { name: "image_search" } {tr!("Знайти обкладинки ({0})", missing_art)} } }
                            }
                        }
                    }
                }
            }
            if all.is_empty() { p { class: "dim", {tr!("Додавайте пісні через меню ⋮ → «Зберегти в плейлист».")} } }
            else { div { class: "list-bar", div { class: "grow" } ListTools { list: list_id.clone(), custom: true, query } } }
            if songs.is_empty() && !all.is_empty() { p { class: "dim", {tr!("Нічого не знайдено")} } }
            div { class: if compact { "tracklist glass compact" } else { "tracklist glass" }, "data-flip": "",
                onmouseup: move |_| if let Some((f, t)) = reorder_end(drag) { commit(f, t) },
                onmouseleave: move |_| if let Some((f, t)) = reorder_end(drag) { commit2(f, t) },
                for ((k, s), &i) in stable_keys(&songs).into_iter().zip(songs.iter()).zip(order.iter()) {
                    div { key: "{k}", "data-key": "{k}", class: reorder_class(*drag.read(), i, "drag-wrap"),
                        onmouseenter: move |_| { let d = *drag.peek(); if let Some((f, _)) = d { drag.set(Some((f, i))) } },
                        if reorderable {
                            span { class: "handle", title: tr!("Перетягнути"), onmousedown: move |e| { e.prevent_default(); drag.set(Some((i, i))) }, Icon { name: "drag_indicator" } }
                        }
                        ItemRow { item: Item::Song(s.clone()), context: Some(songs.clone()), ctx_title: Some(p.name.clone()), local: Some((id, i)) }
                    }
                }
            }
        }
    }
}

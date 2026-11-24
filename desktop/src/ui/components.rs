use super::*;
use crate::db;
use crate::model::{fmt_dur, Item, Shelf, Song};

/// A Material Symbols Rounded icon, drawn as an inline SVG path (sharp at any size or interface scale).
#[component]
pub fn Icon(#[props(into)] name: String, #[props(default)] fill: bool, #[props(default)] size: Option<u32>) -> Element {
    let d = super::icon_map::path(&name, fill);
    let px = size.unwrap_or(22);
    rsx! { svg { class: "ms", width: "{px}", height: "{px}", view_box: super::icon_map::VIEWBOX, "aria-hidden": "true", path { d: "{d}" } } }
}

#[component]
pub fn Sidebar() -> Element {
    let _ = LIBV.read();
    let r = route();
    let first = STACK.read().first().cloned().unwrap_or(Route::Home);
    let playlists = db::playlists();
    let active = |x: &Route| if r == *x || (first == *x && STACK.read().len() > 1 && matches!(x, Route::Home | Route::Library | Route::Search)) { "nav-item active" } else { "nav-item" };
    let item = |icon: &'static str, label: String, to: Route, top: bool| {
        let cls = active(&to);
        rsx! { button { class: "{cls}", onclick: move |_| if top { tab(to.clone()) } else { go(to.clone()) }, Icon { name: icon } span { class: "ellipsis", "{label}" } } }
    };
    let tabs = super::pages::ordered_tabs();
    rsx! {
        nav { class: "sidebar",
            for t in tabs {
                match t.as_str() {
                    "home" => item("home", tr!("Головна"), Route::Home, true),
                    "library" => rsx! {},
                    _ => item("search", tr!("Пошук"), Route::Search, true),
                }
            }
            div { class: "side-section", {tr!("Медіатека")} }
            {item("queue_music", tr!("Плейлисти"), Route::LibSection("playlists".into()), false)}
            {item("favorite", tr!("Сподобалися"), Route::Liked, false)}
            {item("album", tr!("Альбоми"), Route::LibSection("albums".into()), false)}
            {item("mic", tr!("Виконавці"), Route::LibSection("artists".into()), false)}
            {item("download_done", tr!("Завантажено"), Route::Downloads, false)}
            {item("history", tr!("Історія"), Route::History, false)}
            div { class: "side-section", {tr!("Плейлисти")} }
            for p in playlists {
                {
                    // "⋯" (and right-click) opens the playlist's menu: play, shuffle, queue, pin, delete.
                    let it = Item::Local { id: p.id, title: p.name.clone(), count: p.count, thumbnail: p.thumbnail.clone() };
                    let it2 = it.clone();
                    rsx! {
                        div { key: "pl{p.id}", class: "side-pl", oncontextmenu: move |e| { e.prevent_default(); open_menu(&e, it.clone(), None, None) },
                            {item("queue_music", p.name.clone(), Route::Local(p.id), false)}
                            button { class: "icon-btn side-more", title: tr!("Більше"), onclick: move |e| { e.stop_propagation(); open_menu(&e, it2.clone(), None, None) }, Icon { name: "more_horiz", size: 20 } }
                        }
                    }
                }
            }
            div { class: "side-bottom",
                if let Some(u) = UPDATE.read().clone() { UpdateCard { rel: u } }
                {item("bar_chart", tr!("Статистика"), Route::Stats, false)}
                {item("settings", tr!("Налаштування"), Route::Settings, false)}
            }
        }
    }
}

/// Page header: back button, big title, accent-coloured actions on the right.
#[component]
pub fn TopBar(title: String, children: Element) -> Element {
    let can_back = STACK.read().len() > 1;
    rsx! {
        div { class: "topbar",
            if can_back { button { class: "icon-btn back", title: tr!("Назад"), onclick: move |_| back(), Icon { name: "chevron_left", size: 30 } } }
            h1 { class: "page-title ellipsis", "{title}" }
            div { class: "actions", {children} }
        }
    }
}

#[component]
pub fn Thumb(url: Option<String>, class: String, round: bool, #[props(default = "music_note".to_string())] fallback: String) -> Element {
    let src = img(url.as_deref());
    let r = if round { "round" } else { "" };
    rsx! {
        div { class: "thumb {class} {r}",
            if !src.is_empty() { img { src: "{src}", loading: "lazy", decoding: "async", draggable: "false" } } else { Icon { name: fallback } }
        }
    }
}

pub fn open_menu(e: &MouseEvent, item: Item, queue_index: Option<usize>, local: Option<(i64, usize)>) {
    let p = e.client_coordinates();
    *MENU.write() = Some(MenuReq { item, queue_index, local_playlist: local, x: p.x, y: p.y });
}

fn fallback_icon(item: &Item) -> &'static str {
    match item { Item::Artist { .. } => "person", Item::Album { .. } => "album", Item::Playlist { .. } | Item::Local { .. } => "queue_music", _ => "music_note" }
}

/// Rows share one snapshot of the playback queue. Only a playback or edit action copies it.
#[derive(Clone)]
pub struct RowQueue(std::rc::Rc<Vec<Song>>);

impl RowQueue {
    pub fn new(songs: Vec<Song>) -> Self { Self(std::rc::Rc::new(songs)) }
}

impl std::ops::Deref for RowQueue {
    type Target = [Song];
    fn deref(&self) -> &[Song] { &self.0 }
}

impl PartialEq for RowQueue {
    fn eq(&self, other: &Self) -> bool { std::rc::Rc::ptr_eq(&self.0, &other.0) }
}

#[component]
pub fn ItemRow(item: Item, context: Option<RowQueue>, ctx_title: Option<String>, index: Option<usize>, queue_index: Option<usize>, local: Option<(i64, usize)>) -> Element {
    let cur = PSTATE.read().current().map(|s| s.id.clone());
    let playing = matches!(&item, Item::Song(s) if Some(&s.id) == cur.as_ref());
    let (it, it2, it3) = (item.clone(), item.clone(), item.clone());
    let cls = if playing { "row playing" } else { "row" };
    let dur = if let Item::Song(s) = &item { s.duration.map(fmt_dur).unwrap_or_default() } else { String::new() };
    let sub = match &item { Item::Song(s) => s.artist_text() + &s.album.as_ref().map(|a| format!(" — {}", a.name)).unwrap_or_default(), _ => item.subtitle() };
    // Songs get a ♥ that shows on hover (and stays lit when liked), like Spotify / Apple Music.
    let like_song = if let Item::Song(s) = &item { (!s.id.starts_with("file:")).then(|| s.clone()) } else { None };
    let liked = { let _ = LIBV.read(); like_song.as_ref().map_or(false, |s| db::is_liked(&s.id)) };
    rsx! {
        div { class: "{cls}",
            onclick: move |_| { match queue_index { Some(q) => playback::skip_to(q), None => open(&it, context.as_ref().map(|c| (c.to_vec(), ctx_title.clone()))) } },
            oncontextmenu: move |e| { e.prevent_default(); open_menu(&e, it2.clone(), queue_index, local) },
            if let Some(i) = index { div { class: "idx", if playing { Icon { name: "graphic_eq" } } else { "{i}" } } }
            else { Thumb { url: item.thumbnail().map(String::from), class: "t40".to_string(), round: item.is_round(), fallback: fallback_icon(&item) } }
            div { class: "row-text",
                div { class: "row-title ellipsis", "{item.title()}" }
                div { class: "row-sub ellipsis", "{sub}" }
            }
            if let Some(s) = like_song {
                button { class: if liked { "icon-btn like on" } else { "icon-btn like" }, title: if liked { tr!("Прибрати з «Сподобалися»") } else { tr!("Подобається") },
                    onclick: move |e| { e.stop_propagation(); db::set_liked(&s, !liked); toast(if liked { tr!("Прибрано з «Сподобалися»") } else { tr!("Додано в «Сподобалися»") }) },
                    Icon { name: "favorite", fill: liked, size: 20 } }
            }
            if !dur.is_empty() { span { class: "dur", "{dur}" } }
            button { class: "icon-btn more", title: tr!("Більше"), onclick: move |e| { e.stop_propagation(); open_menu(&e, it3.clone(), queue_index, local) }, Icon { name: "more_horiz" } }
        }
    }
}

#[component]
pub fn ItemCard(item: Item) -> Element {
    let (it, it2) = (item.clone(), item.clone());
    rsx! {
        div { class: "card",
            onclick: move |_| open(&it, None),
            oncontextmenu: move |e| { e.prevent_default(); open_menu(&e, it2.clone(), None, None) },
            Thumb { url: item.thumbnail().map(String::from), class: "cover".to_string(), round: item.is_round(), fallback: fallback_icon(&item) }
            div { class: "card-title ellipsis", "{item.title()}" }
            div { class: "card-sub ellipsis", "{item.subtitle()}" }
        }
    }
}

#[component]
pub fn ShelfView(shelf: Shelf, #[props(default)] on_refresh: Option<EventHandler<()>>) -> Element {
    let songs: Vec<Song> = shelf.items.iter().filter_map(|i| if let Item::Song(s) = i { Some(s.clone()) } else { None }).collect();
    let ctx = if shelf.list && !songs.is_empty() { Some(RowQueue::new(songs)) } else { None };
    rsx! {
        section { class: "shelf",
            h2 { class: "shelf-title", "{shelf.title}"
                if let Some(r) = on_refresh {
                    button { class: "icon-btn shelf-refresh", title: tr!("Оновити добірку"), onclick: move |_| r.call(()), Icon { name: "refresh", size: 20 } }
                } else { Icon { name: "chevron_right" } }
            }
            if shelf.list {
                div { class: "list-grid",
                    for item in shelf.items.iter().take(16) { ItemRow { key: "{item.key()}", item: item.clone(), context: ctx.clone(), ctx_title: Some(shelf.title.clone()) } }
                }
            } else {
                div { class: "carousel",
                    for (i, item) in shelf.items.iter().enumerate() { ItemCard { key: "{i}:{item.key()}", item: item.clone() } }
                }
            }
        }
    }
}

pub fn share_link(item: &Item) -> Option<String> {
    Some(match item {
        Item::Song(s) if s.id.starts_with("file:") => return None,
        Item::Song(s) => format!("https://music.youtube.com/watch?v={}", s.id),
        Item::Album { id, .. } => format!("https://music.youtube.com/browse/{id}"),
        Item::Playlist { id, .. } if id == "__liked" => return None,
        Item::Playlist { id, .. } => format!("https://music.youtube.com/playlist?list={id}"),
        Item::Artist { id, .. } => format!("https://music.youtube.com/channel/{id}"),
        Item::Local { .. } => return None,
    })
}

pub fn copy(text: &str) {
    let js = format!("navigator.clipboard.writeText({})", serde_json::to_string(text).unwrap_or_default());
    document::eval(&js);
}

/// Songs of any item (for play / shuffle / queue / download from a menu).
pub async fn songs_of(item: &Item) -> Vec<Song> {
    match item.clone() {
        Item::Song(s) => vec![s],
        Item::Album { id, .. } => bg(async move { crate::yt::album_page(&id).await.map(|p| p.songs).unwrap_or_default() }).await,
        Item::Playlist { id, .. } if id == "__liked" => db::liked(),
        Item::Playlist { id, .. } => bg(async move { crate::yt::playlist_page(&id).await.map(|p| p.songs).unwrap_or_default() }).await,
        Item::Artist { id, .. } => bg(async move {
            crate::yt::artist_page(&id).await.map(|p| p.shelves.into_iter().next().map(|s| s.items.into_iter().filter_map(|i| if let Item::Song(s) = i { Some(s) } else { None }).collect()).unwrap_or_default()).unwrap_or_default()
        }).await,
        Item::Local { id, .. } => db::playlist_songs(id),
    }
}

#[component]
pub fn Menu() -> Element {
    let Some(req) = MENU.read().clone() else { return rsx!{} };
    let item = req.item.clone();
    let close = move || *MENU.write() = None;
    let (x, y) = (req.x, req.y);
    let song = if let Item::Song(s) = &item { Some(s.clone()) } else { None };
    let liked = song.as_ref().map(|s| db::is_liked(&s.id)).unwrap_or(false);
    let pinned = db::is_pinned(&item.key());
    let saved_kind = match &item { Item::Album { id, .. } => Some(("album", id.clone())), Item::Playlist { id, .. } if id != "__liked" => Some(("playlist", id.clone())), Item::Artist { id, .. } => Some(("artist", id.clone())), _ => None };
    let saved = saved_kind.as_ref().map(|(k, id)| db::is_saved(k, id)).unwrap_or(false);
    let downloaded = song.as_ref().map(|s| db::download_path(&s.id).is_some()).unwrap_or(false);
    macro_rules! act { ($label:expr, $icon:expr, $body:expr) => {{
        let it = item.clone();
        let label: String = ($label).to_string();
        let icon: String = ($icon).to_string();
        let body = $body;
        rsx! { button { class: "menu-item", onclick: move |_| { close(); body(it.clone()); }, span { class: "grow", "{label}" } Icon { name: icon } } }
    }}}
    rsx! {
        div { class: "scrim", onclick: move |_| close(), oncontextmenu: move |e| { e.prevent_default(); close() } }
        div { class: "menu", style: "left:min({x}px, calc(100vw - 290px)); top:min({y}px, calc(100vh - 460px));",
            div { class: "menu-head",
                Thumb { url: item.thumbnail().map(String::from), class: "t40".to_string(), round: item.is_round(), fallback: fallback_icon(&item) }
                div { class: "row-text", div { class: "row-title ellipsis", "{item.title()}" } div { class: "row-sub ellipsis", "{item.subtitle()}" } }
            }
            {act!(tr!("Відтворити"), "play_arrow", |it: Item| { dioxus::dioxus_core::spawn_forever(async move { let s = songs_of(&it).await; let t = it.title().to_string(); playback::play_queue(s, 0, Some(t), false) }); })}
            if !matches!(item, Item::Song(_)) {
                {act!(tr!("Перемішати"), "shuffle", |it: Item| { dioxus::dioxus_core::spawn_forever(async move { let s = songs_of(&it).await; let t = it.title().to_string(); playback::play_queue(s, 0, Some(t), true) }); })}
            }
            {act!(tr!("Відтворити наступним"), "queue_play_next", |it: Item| { dioxus::dioxus_core::spawn_forever(async move { playback::play_next(songs_of(&it).await); toast(tr!("Буде відтворено наступним")) }); })}
            {act!(tr!("Додати в чергу"), "playlist_play", |it: Item| { dioxus::dioxus_core::spawn_forever(async move { playback::add_to_queue(songs_of(&it).await); toast(tr!("Додано в чергу")) }); })}
            if let Some(s) = song.clone().filter(|s| !s.id.starts_with("file:")) {
                {act!(tr!("Почати радіо"), "sensors", move |_| playback::start_radio(s.clone()))}
            }
            {act!(tr!("Зберегти в плейлист"), "playlist_add", |it: Item| { dioxus::dioxus_core::spawn_forever(async move { *ADD_TO.write() = Some(songs_of(&it).await) }); })}
            if let Some(s) = song.clone() {
                {act!(if liked { tr!("Прибрати з «Сподобалися»") } else { tr!("Подобається") }, "favorite", move |_| db::set_liked(&s, !liked))}
            }
            if let Some(s) = song.clone().filter(|s| !s.id.starts_with("file:")) {
                if downloaded { {act!(tr!("Видалити завантаження"), "file_download_off", move |_| db::remove_download(&s.id))} }
                else { {act!(tr!("Завантажити"), "download", move |_| player::download(vec![s.clone()]))} }
            }
            if !matches!(item, Item::Song(_) | Item::Local { .. }) {
                {act!(tr!("Завантажити"), "download", |it: Item| { dioxus::dioxus_core::spawn_forever(async move { player::download(songs_of(&it).await) }); })}
            }
            if saved_kind.is_some() {
                {act!(if saved { tr!("Видалити з медіатеки") } else { tr!("Додати в медіатеку") }, if saved { "library_add_check" } else { "library_add" }, move |it: Item| db::save(&it, !saved))}
            }
            if let Some(s) = song.clone() {
                for a in s.artists.iter().filter(|a| a.id.is_some()).take(2).cloned() {
                    {act!(tr!("Перейти до виконавця: {0}", a.name), "person", move |_| { go(Route::Artist(a.id.clone().unwrap())); *FULL.write() = false })}
                }
                if let Some(al) = s.album.clone().filter(|a| a.id.is_some()) {
                    {act!(tr!("Перейти до альбому"), "album", move |_| { go(Route::Album(al.id.clone().unwrap())); *FULL.write() = false })}
                }
            }
            if let Some(q) = req.queue_index {
                {act!(tr!("Видалити з черги"), "close", move |_| playback::remove_at(q))}
            }
            if let Some((pid, pos)) = req.local_playlist {
                {act!(tr!("Видалити з плейлиста"), "close", move |_| { let mut v = db::playlist_songs(pid); if pos < v.len() { v.remove(pos); db::set_playlist_songs(pid, &v) } })}
            }
            {act!(if pinned { tr!("Відкріпити з головної") } else { tr!("Закріпити на головній") }, "push_pin", move |it: Item| db::pin(&it, !pinned))}
            if let Some(link) = share_link(&item) {
                {act!(tr!("Копіювати посилання"), "link", move |_| { copy(&link); toast(tr!("Посилання скопійовано")) })}
            }
            if let Item::Local { id, .. } = item.clone() {
                {act!(tr!("Видалити плейлист"), "delete", move |_| { db::delete_playlist(id); if route() == Route::Local(id) { back() } })}
            }
        }
    }
}

#[component]
pub fn AddToPlaylist() -> Element {
    let _ = LIBV.read();
    let songs = ADD_TO.read().clone().unwrap_or_default();
    let mut name = use_signal(String::new);
    let close = move || *ADD_TO.write() = None;
    let s2 = songs.clone();
    rsx! {
        div { class: "scrim dim", onclick: move |_| close() }
        div { class: "dialog",
            h3 { {tr!("Зберегти в плейлист")} }
            div { class: "dialog-list",
                for p in db::playlists() {
                    {
                        let s = songs.clone();
                        rsx! { button { key: "{p.id}", class: "menu-item", onclick: move |_| { db::add_to_playlist(p.id, &s); close(); toast(tr!("Збережено в «{0}»", p.name)) },
                            Icon { name: "queue_music" } span { class: "grow", "{p.name}" } span { class: "dim", "{p.count}" } } }
                    }
                }
            }
            div { class: "row-form",
                input { class: "field", placeholder: tr!("Новий плейлист"), value: "{name}", oninput: move |e| name.set(e.value()) }
                button { class: "btn accent", disabled: name.read().trim().is_empty(), onclick: move |_| {
                    let id = db::create_playlist(name.read().trim()); db::add_to_playlist(id, &s2); close(); toast(tr!("Плейлист створено"))
                }, {tr!("Створити")} }
            }
            div { class: "pills", button { class: "btn", onclick: move |_| close(), {tr!("Скасувати")} } }
        }
    }
}

// ---------- drag to reorder ----------
// HTML drag-and-drop is unreliable in WebKitGTK, so lists reorder with plain mouse events: press the ≡ handle,
// move over the target row, release. The state is (row being moved, row under the pointer).
pub fn reorder_class(state: Option<(usize, usize)>, i: usize, base: &str) -> String {
    match state {
        Some((from, _)) if i == from => format!("{base} dragging"),
        Some((from, over)) if i == over && over < from => format!("{base} drop-before"),
        Some((from, over)) if i == over && over > from => format!("{base} drop-after"),
        _ => base.to_string(),
    }
}
/// Ends a reorder: returns (from, to) when the row actually moved.
pub fn reorder_end(mut state: Signal<Option<(usize, usize)>>) -> Option<(usize, usize)> {
    let r = *state.peek();
    state.set(None);
    r.filter(|(f, o)| f != o)
}

/// A plain-language error (the technical text stays in the tooltip, for bug reports).
#[component]
pub fn ErrorNote(err: String) -> Element {
    let l = err.to_lowercase();
    let msg = if !err.is_ascii() { err.clone() } // already a sentence of ours (e.g. offline mode)
        else if ["connect", "dns", "timed out", "timeout", "network", "error sending request", "proxy"].iter().any(|k| l.contains(k)) { tr!("Немає з'єднання з YouTube Music. Перевірте інтернет і спробуйте ще раз.") }
        else { tr!("YouTube Music зараз не віддає цю сторінку. Спробуйте пізніше.") };
    rsx! { div { class: "error", title: "{err}", "{msg}" } }
}

// ---------- song lists: search, sorting, compact view ----------
/// Sort options (id, label). "custom" = the list's own order (a playlist's order, an album's track order).
fn sort_options(custom: bool) -> Vec<(&'static str, String)> {
    let mut v = vec![];
    if custom { v.push(("custom", tr!("Власний порядок"))) }
    v.extend([("recent", tr!("Нещодавно додано")), ("title", tr!("Назва")), ("artist", tr!("Виконавець")), ("album", tr!("Альбом")), ("duration", tr!("Тривалість"))]);
    v
}
pub fn list_sort(list: &str, custom: bool) -> String {
    SETTINGS.read().list_sort.get(list).cloned().unwrap_or_else(|| if custom { "custom".into() } else { "recent".into() })
}
/// Which songs to show, in which order: indexes into `songs`. `newest_first` = the list is already stored newest first
/// (liked songs, history); otherwise later songs were added later (playlists, downloads).
pub fn arrange_songs(songs: &[Song], sort: &str, query: &str, newest_first: bool) -> Vec<usize> {
    let q = query.trim().to_lowercase();
    let mut idx: Vec<usize> = (0..songs.len()).filter(|&i| {
        if q.is_empty() { return true }
        let s = &songs[i];
        s.title.to_lowercase().contains(&q) || s.artist_text().to_lowercase().contains(&q) || s.album.as_ref().map_or(false, |a| a.name.to_lowercase().contains(&q))
    }).collect();
    let (key, desc) = match sort.split_once(':') { Some((k, d)) => (k, d == "desc"), None => (sort, false) };
    let low = |t: &str| t.to_lowercase();
    match key {
        "recent" => if !newest_first { idx.reverse() },
        "title" => idx.sort_by_key(|&i| low(&songs[i].title)),
        "artist" => idx.sort_by_key(|&i| (low(&songs[i].artist_text()), low(&songs[i].title))),
        "album" => idx.sort_by_key(|&i| (songs[i].album.as_ref().map(|a| low(&a.name)).unwrap_or_else(|| "\u{10ffff}".into()), i)),
        "duration" => idx.sort_by_key(|&i| songs[i].duration.unwrap_or(0)),
        _ => {}
    }
    if desc { idx.reverse() }
    idx
}

/// Search in the list + "Sort / View" menu (like Spotify). Changes are remembered per list on this computer.
#[component]
pub fn ListTools(list: String, custom: bool, query: Signal<String>) -> Element {
    let mut open = use_signal(|| false);
    let mut searching = use_signal(|| false);
    let sort = list_sort(&list, custom);
    let compact = SETTINGS.read().list_compact;
    let (key, desc) = match sort.split_once(':') { Some((k, d)) => (k.to_string(), d == "desc"), None => (sort.clone(), false) };
    let opts = sort_options(custom);
    let label = opts.iter().find(|o| o.0 == key).map(|o| o.1.clone()).unwrap_or_default();
    let show_field = *searching.read() || !query.read().is_empty();
    rsx! {
        div { class: "list-tools",
            if show_field {
                div { class: "list-search",
                    Icon { name: "search", size: 18 }
                    input { class: "search-input", placeholder: tr!("Шукати в списку"), value: "{query}",
                        onmounted: move |e| async move { let _ = e.set_focus(true).await; },
                        oninput: move |e| query.set(e.value()),
                        onkeydown: move |e| if e.key() == Key::Escape { query.set(String::new()); searching.set(false) } }
                    button { class: "icon-btn", title: tr!("Очистити"), onclick: move |_| { query.set(String::new()); searching.set(false) }, Icon { name: "close", size: 18 } }
                }
            } else {
                button { class: "icon-btn", title: tr!("Шукати в списку"), onclick: move |_| searching.set(true), Icon { name: "search", size: 22 } }
            }
            div { class: "sort-wrap",
                button { class: "sort-btn", title: tr!("Сортування й подання"), onclick: move |_| { let v = *open.peek(); open.set(!v) },
                    span { "{label}" } Icon { name: if compact { "menu" } else { "format_list_bulleted" }, size: 20 } }
                if *open.read() {
                    div { class: "dev-scrim", onclick: move |_| open.set(false) }
                    div { class: "sort-menu",
                        div { class: "sort-head", {tr!("Сортування")} }
                        for (id, text) in opts.clone() {
                            {
                                let l = list.clone();
                                let on = id == key;
                                rsx! {
                                    button { class: if on { "sort-item on" } else { "sort-item" },
                                        onclick: move |_| {
                                            // The same option again flips the direction.
                                            let v = if on && !desc && id != "custom" { format!("{id}:desc") } else { id.to_string() };
                                            let l = l.clone();
                                            set(move |s| { s.list_sort.insert(l, v); });
                                        },
                                        span { class: "grow", "{text}" }
                                        if on && id != "custom" { Icon { name: if desc { "arrow_upward" } else { "arrow_downward" }, size: 18 } }
                                    }
                                }
                            }
                        }
                        div { class: "sort-head", {tr!("Подання")} }
                        button { class: if compact { "sort-item on" } else { "sort-item" }, onclick: move |_| set(|s| s.list_compact = true),
                            Icon { name: "menu", size: 18 } span { class: "grow", {tr!("Компактний")} } if compact { Icon { name: "check", size: 18 } } }
                        button { class: if !compact { "sort-item on" } else { "sort-item" }, onclick: move |_| set(|s| s.list_compact = false),
                            Icon { name: "format_list_bulleted", size: 18 } span { class: "grow", {tr!("Список")} } if !compact { Icon { name: "check", size: 18 } } }
                    }
                }
            }
        }
    }
}

/// A playlist's cover: the user's own picture, else a 2×2 mosaic of its songs' covers, else the first cover.
#[component]
pub fn PlaylistCover(pl: crate::db::Playlist, class: String) -> Element {
    if pl.custom_cover || pl.covers.len() < 4 {
        return rsx! { Thumb { url: pl.thumbnail.clone(), class: class, round: false, fallback: "queue_music" } };
    }
    rsx! {
        div { class: "thumb mosaic {class}",
            for u in pl.covers.iter().take(4) { img { key: "{u}", src: "{img(Some(&crate::yt::resize(u, 226)))}", loading: "lazy", decoding: "async", draggable: "false" } }
        }
    }
}

/// Keys that stay with a song when the list is sorted or reordered (a song twice in a list gets "id#1").
pub fn stable_keys(songs: &[Song]) -> Vec<String> {
    let mut seen: std::collections::HashMap<&str, usize> = Default::default();
    songs.iter().map(|s| { let n = seen.entry(s.id.as_str()).or_default(); *n += 1; if *n == 1 { s.id.clone() } else { format!("{}#{}", s.id, *n - 1) } }).collect()
}

/// "A new version is out" in the sidebar: what to run to update (one command), the release page, or skip it.
#[component]
fn UpdateCard(rel: crate::update::Release) -> Element {
    let mut open = use_signal(|| false);
    let cmd = crate::update::install_command();
    let (url, ver) = (rel.url.clone(), rel.version.clone());
    rsx! {
        div { class: "update-card",
            div { class: "update-title", Icon { name: "new_releases", size: 18 } span { {tr!("Доступна версія {0}", rel.version)} } }
            div { class: "update-btns",
                button { class: "btn small accent", onclick: move |_| open.set(true), {tr!("Оновити")} }
                button { class: "btn small", title: tr!("Не нагадувати про цю версію"), onclick: move |_| { let v = ver.clone(); set(move |s| s.update_skipped = v); *UPDATE.write() = None }, {tr!("Пропустити")} }
            }
        }
        if *open.read() {
            div { class: "scrim dim", onclick: move |_| open.set(false) }
            div { class: "dialog",
                h3 { {tr!("Оновлення до версії {0}", rel.version)} }
                if !rel.notes.trim().is_empty() { p { class: "dim pre update-notes", "{rel.notes}" } }
                p { if cfg!(windows) { {tr!("Відкрийте PowerShell і виконайте команду — вона завантажить нову версію й замінить стару. Бібліотека й налаштування збережуться.")} } else { {tr!("Виконайте в терміналі — команда завантажить нову версію й замінить стару. Бібліотека й налаштування збережуться.")} } }
                div { class: "cmd-box", code { "{cmd}" } button { class: "icon-btn", title: tr!("Копіювати"), onclick: move |_| { copy(cmd); toast(tr!("Команду скопійовано")) }, Icon { name: "content_copy", size: 18 } } }
                div { class: "pills",
                    button { class: "btn", onclick: move |_| crate::sys::open_url(&url), Icon { name: "open_in_new", size: 18 } {tr!("Сторінка випуску")} }
                    button { class: "btn accent", onclick: move |_| open.set(false), {tr!("Готово")} }
                }
            }
        }
    }
}

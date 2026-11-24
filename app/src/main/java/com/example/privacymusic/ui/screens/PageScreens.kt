package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Pal

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.*
import com.example.privacymusic.net.Parser
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.PlayContext
import com.example.privacymusic.ui.components.*
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads a YouTube Music page and its "load more" continuations. Survives navigation while on the back stack. */
class PageModel(
    initialParams: String?,
    private val load: suspend (String?) -> Page,
    private val more: suspend (String) -> Parser.Continued = { YouTubeMusic.continuation(it) },
) : ViewModel() {
    var params by mutableStateOf(initialParams); private set
    var page by mutableStateOf<Page?>(null); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var loadingMore by mutableStateOf(false); private set

    init { refresh() }

    fun refresh(newParams: String? = params) {
        params = newParams
        viewModelScope.launch {
            loading = true; error = null
            try { page = load(params) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Diag.log("Сторінка: ${e.javaClass.simpleName} ${e.message?.take(80)}"); error = if (Settings.offline.value) T("Офлайн-режим: мережу вимкнено в налаштуваннях приватності") else T("Не вдалося завантажити. Перевірте з'єднання з мережею.") }
            finally { loading = false }
        }
    }

    val canLoadMore get() = page?.let { it.shelves.lastOrNull()?.continuation != null || it.continuation != null } == true

    fun loadMore() {
        val p = page ?: return
        if (loadingMore) return
        val shelfToken = p.shelves.lastOrNull()?.continuation
        val token = shelfToken ?: p.continuation ?: return
        viewModelScope.launch {
            loadingMore = true
            try {
                val c = more(token)
                page = if (shelfToken != null) {
                    val last = p.shelves.last()
                    p.copy(shelves = p.shelves.dropLast(1) + last.copy(items = (last.items + c.items).distinctBy { it.key }, continuation = c.next) + c.shelves)
                } else p.copy(shelves = p.shelves + c.shelves, continuation = c.next)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                page = if (shelfToken != null) p.copy(shelves = p.shelves.dropLast(1) + p.shelves.last().copy(continuation = null)) else p.copy(continuation = null)
            } finally { loadingMore = false }
        }
    }
}

/** Renders shelves lazily; long song lists become individual lazy rows. */
fun LazyListScope.shelves(model: PageModel, actions: ItemActions, numbered: Boolean = false, arrange: ((List<Shelf>) -> List<Shelf>)? = null) {
    val page = model.page ?: return
    (arrange?.invoke(page.shelves) ?: page.shelves).forEachIndexed { si, shelf ->
        if (shelf.style == ShelfStyle.LIST && shelf.items.size > 6) {
            item(key = "h${si}") { ShelfHeader(shelf.title, shelf.strapline, shelf.more?.let { e -> { actions.endpoint(e, shelf.title) } }) }
            itemsIndexed(shelf.items, key = { i, it -> "${si}:${i}:${it.key}" }) { i, item ->
                ItemRow(item, { actions.open(item, shelf.items) }, { actions.menu(item) }, index = if (numbered) i + 1 else null)
            }
        } else item(key = "s${si}") { ShelfView(shelf, actions, numbered = numbered) }
    }
    if (model.canLoadMore) item(key = "more") {
        LaunchedEffect(page) { model.loadMore() }
        Loading()
    }
}

@Composable
fun TopBar(title: String?, showLogo: Boolean = false, onBack: (() -> Unit)? = null, globalActions: Boolean = onBack == null, actions: @Composable RowScope.() -> Unit = {}) {
    val app = LocalApp.current
    val signedIn by AccountStore.signedIn.collectAsState()
    if (Pal.minimal) {
        // Minimal: a slim row of icons, then a large title (the home greeting acts as its title).
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, T("Назад"), tint = Pal.accent) }
                Spacer(Modifier.weight(1f))
                actions()
                if (globalActions) {
                    IconButton(onClick = { app.tab("search") }) { Icon(Icons.Rounded.Search, T("Пошук")) }
                    IconButton(onClick = { app.nav.navigate("settings") { launchSingleTop = true } }) {
                        Icon(if (signedIn) Icons.Rounded.AccountCircle else Icons.Rounded.Settings, T("Налаштування"))
                    }
                }
            }
            if (!showLogo && !title.isNullOrBlank()) Text(title, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp), fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
        }
        return
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, T("Назад")) }
        if (showLogo) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).background(Pal.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = Pal.onAccent)
            }
            Text("Music", Modifier.padding(start = 6.dp), fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.5).sp)
            Spacer(Modifier.weight(1f))
        } else Text(title.orEmpty(), Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp), fontSize = 20.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
        // Search / settings only on the main tabs; inner screens (incl. Settings itself) just have Back.
        if (globalActions) {
            IconButton(onClick = { app.tab("search") }) { Icon(Icons.Rounded.Search, T("Пошук")) }
            IconButton(onClick = { app.nav.navigate("settings") { launchSingleTop = true } }) {
                Icon(if (signedIn) Icons.Rounded.AccountCircle else Icons.Rounded.Settings, T("Налаштування"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val accountHome by Settings.accountHome.state.collectAsState()
    val model = viewModel(key = "home${accountHome}") { PageModel(null, { p -> YouTubeMusic.browse("FEmusic_home", p) }) }
    // Rebuilt when the app language changes, so the shelf titles follow it.
    val personal = viewModel(key = "personal-" + com.example.privacymusic.i18n.I18n.current()) { PersonalHomeModel() }
    val actions = remember { app.actions() }
    val pins by remember { Library.observe { Library.pins() } }.collectAsState(emptyList())
    val layout by Settings.layoutHome.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        LayoutEditor(T("Головна"), Settings.layoutHome, homeBlocks(), onDone = { editing = false })
        return
    }
    val order = Layouts.visible(layout, homeBlocks().map { it.first })
    PullToRefreshBox(isRefreshing = personal.refreshing && personal.shelves.isNotEmpty(), onRefresh = { personal.refresh(); model.refresh() }) {
        PageScaffold(model, { TopBar(null, showLogo = true) {
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, T("Налаштувати сторінку")) }
            IconButton(onClick = { app.nav.navigate("stats") { launchSingleTop = true } }) { Icon(Icons.Rounded.BarChart, T("Статистика")) }
        } }) {
            // Blocks in the user's order (pencil icon → drag to reorder, eye to hide).
            order.forEach { block ->
                when (block) {
                    "greeting" -> {
                        item("greeting") {
                            Text(greeting(), Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), fontSize = if (Pal.minimal) 32.sp else 28.sp,
                                fontWeight = if (Pal.minimal) FontWeight.ExtraBold else FontWeight.Bold, letterSpacing = if (Pal.minimal) (-0.6).sp else 0.sp)
                        }
                        if (personal.empty) item("welcome") {
                            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(T("Ваші добірки з'являться тут"), fontWeight = FontWeight.Bold)
                                    Text(T("Слухайте й ставте лайки — і тут з'являться «Швидкий вибір», мікси й новинки ваших виконавців. ") +
                                        T("Рекомендації рахуються на цьому пристрої, ваші смаки нікуди не надсилаються."), color = Dim, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    "pins" -> if (pins.isNotEmpty()) item("pins") { ShelfView(Shelf(T("Закріплене"), T("ШВИДКИЙ ДОСТУП"), pins, style = ShelfStyle.CAROUSEL), actions) }
                    "yt" -> {
                        val page = model.page
                        item("yt") { ShelfHeader(T("Від YouTube Music"), T("ДЛЯ ВСІХ СЛУХАЧІВ У ВАШОМУ РЕГІОНІ")) }
                        if (page != null && page.chips.isNotEmpty()) item("chips") {
                            ChipsRow(page.chips.map { it.title to it.selected }, { i ->
                                val chip = page.chips[i]
                                model.refresh(if (chip.selected) null else (chip.endpoint as? BrowseEndpoint)?.params)
                            }, Modifier.padding(vertical = 6.dp))
                        }
                        shelves(model, actions)
                    }
                    else -> personal.shelves.forEachIndexed { i, shelf -> if (shelf.id == block) item("p${i}:${shelf.title}") { ShelfView(shelf, actions) } }
                }
            }
        }
    }
}

/** Home page blocks a user can reorder / hide. */
private fun homeBlocks(): List<Pair<String, String>> = listOf(
    "greeting" to T("Привітання"), "pins" to T("Закріплене"), "quick" to T("Швидкий вибір"), "again" to T("Слухати знову"),
    "mixes" to T("Мікси для вас"), "fresh" to T("Нове від ваших виконавців"), "similar" to T("Схожі виконавці"),
    "forgotten" to T("Давно не слухали"), "yt" to T("Від YouTube Music"),
)

@Composable
fun PageScaffold(model: PageModel, top: @Composable () -> Unit, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        top()
        val phase = when { model.loading && model.page == null -> 0; model.error != null && model.page == null -> 1; else -> 2 }
        androidx.compose.animation.Crossfade(phase, animationSpec = androidx.compose.animation.core.tween(260), label = "page") { ph ->
            when (ph) {
                0 -> Loading(Modifier.fillMaxSize())
                1 -> ErrorBox(model.error ?: "", { model.refresh() })
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    content()
                    if (model.loading) item { Loading() }
                }
            }
        }
    }
}

// ---------------- generic browse page: album / playlist / artist / mood / "more" ----------------

@Composable
fun BrowseScreen(browseId: String, params: String?) {
    val app = LocalApp.current
    val context = LocalContext.current
    val model = viewModel(key = "browse:${browseId}:${params}") { PageModel(params, { p -> YouTubeMusic.browse(browseId, p) }) }
    val page = model.page
    val header = page?.header
    val playlistId = header?.playlistId ?: browseId.removePrefix("VL").takeIf { browseId.startsWith("VL") }
    val playContext = remember(playlistId, header?.title) { playlistId?.let { PlayContext.Remote(it, header?.title) } }
    val actions = remember(playContext) { app.actions(playContext) }
    val songs = page?.shelves?.filter { it.style == ShelfStyle.LIST }?.flatMap { it.items }?.filterIsInstance<Song>().orEmpty()

    Column(Modifier.fillMaxSize()) {
        TopBar(if (header?.type == PageType.ARTIST || header?.type == PageType.ALBUM || header?.type == PageType.PLAYLIST) null else header?.title, onBack = { app.nav.popBackStack() })
        when {
            model.loading && page == null -> Loading(Modifier.fillMaxSize())
            model.error != null && page == null -> ErrorBox(model.error!!, { model.refresh() })
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (header != null) item("header") {
                    when (header.type) {
                        PageType.ARTIST -> ArtistHeader(header, browseId)
                        PageType.ALBUM, PageType.PLAYLIST -> CollectionHeader(header, browseId, songs, onShare = {
                            val url = if (header.type == PageType.ALBUM && playlistId != null) "https://music.youtube.com/playlist?list=${playlistId}"
                                else "https://music.youtube.com/browse/${browseId}"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                        })
                        else -> {}
                    }
                }
                if (page != null && page.chips.isNotEmpty()) item("chips") {
                    ChipsRow(page.chips.map { it.title to it.selected }, { i ->
                        val chip = page.chips[i]
                        model.refresh(if (chip.selected) null else (chip.endpoint as? BrowseEndpoint)?.params)
                    }, Modifier.padding(vertical = 6.dp))
                }
                shelves(model, actions, numbered = header?.type == PageType.ALBUM)
                if (header?.type == PageType.ARTIST && header.description != null) item("about") {
                    ShelfHeader(T("Про виконавця"))
                    ExpandableText(header.description, Modifier.padding(horizontal = 16.dp), 5)
                }
                if (header?.type == PageType.ALBUM || header?.type == PageType.PLAYLIST) item("footer") {
                    if (header.secondSubtitle.isNotBlank()) Text(header.secondSubtitle, Modifier.padding(16.dp), color = Dim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CollectionHeader(h: Header, browseId: String, songs: List<Song>, onShare: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val kind = if (h.type == PageType.ALBUM) "album" else "playlist"
    val saved by remember(browseId) { Library.observe { Library.isSaved(kind, browseId) } }.collectAsState(false)
    val item: YTItem = if (h.type == PageType.ALBUM) AlbumItem(browseId, h.playlistId, h.title, h.artists, null, h.subtitle, h.thumbnail)
        else PlaylistItem(browseId, h.title, listOf(h.subtitle, h.artists.joinToString(", ") { it.name }).filter { it.isNotBlank() }.joinToString(" • "), h.thumbnail, h.playlistId)
    fun toggleSaved() {
        scope.launch(Dispatchers.IO) { Library.save(item, !saved) }
        if (Settings.accountSync.value && AccountStore.signedIn.value && h.playlistId != null)
            scope.launch { runCatching { YouTubeMusic.savePlaylist(h.playlistId, !saved) } }
        app.toast(if (saved) T("Видалено з медіатеки") else T("Додано в медіатеку"))
    }
    if (Pal.minimal) {
        // Minimal: plain background, big shadowed artwork, title + artist in the accent colour, Play / Shuffle pills.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = ::toggleSaved) { Icon(if (saved) Icons.Rounded.Check else Icons.Rounded.Add, if (saved) T("У медіатеці") else T("Додати в медіатеку"), tint = Pal.accent) }
                IconButton(onClick = { Downloads.download(songs); app.toast(T("Завантаження: {0} треків", songs.size)) }) { Icon(Icons.Rounded.Download, T("Завантажити"), tint = Pal.accent) }
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, T("Поділитися"), tint = Pal.accent) }
            }
            Thumb(h.thumbnail, Modifier.fillMaxWidth(0.72f).aspectRatio(1f).shadow(18.dp, RoundedCornerShape(10.dp)), 720, RoundedCornerShape(10.dp))
            Spacer(Modifier.height(18.dp))
            Text(h.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 26.sp)
            if (h.artists.isNotEmpty()) Row(Modifier.padding(top = 4.dp)) {
                h.artists.forEachIndexed { i, a ->
                    if (i > 0) Text(", ", color = Pal.accent, fontSize = 20.sp)
                    Text(a.name, Modifier.clickable(enabled = a.id != null) { a.id?.let { app.browse(it) } }, color = Pal.accent, fontSize = 20.sp)
                }
            }
            if (h.subtitle.isNotBlank()) Text(h.subtitle, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(Icons.Rounded.PlayArrow, T("Відтворити"), Modifier.weight(1f)) {
                    h.play?.let { Playback.playEndpoint(it, h.title) } ?: if (songs.isNotEmpty()) Playback.playQueue(songs, title = h.title) else Unit
                }
                PillButton(Icons.Rounded.Shuffle, T("Перемішати"), Modifier.weight(1f)) {
                    h.play?.let { Playback.playEndpoint(it, h.title, shuffled = true) } ?: Playback.playQueue(songs, title = h.title, shuffled = true)
                }
            }
            if (h.description != null) ExpandableText(h.description, Modifier.fillMaxWidth().padding(top = 14.dp), 2)
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = Pal.line)
        }
        return
    }
    Box {
        AsyncImage(Parser.resize(h.thumbnail, 120), null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.35f)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Pal.bg.copy(alpha = .3f), Pal.bg))))
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Thumb(h.thumbnail, Modifier.fillMaxWidth(0.66f).aspectRatio(1f), 720, RoundedCornerShape(8.dp))
            Spacer(Modifier.height(16.dp))
            Text(h.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (h.artists.isNotEmpty()) Row(Modifier.padding(top = 6.dp)) {
                h.artists.forEachIndexed { i, a ->
                    if (i > 0) Text(", ", fontWeight = FontWeight.Medium)
                    Text(a.name, Modifier.clickable(enabled = a.id != null) { a.id?.let { app.browse(it) } }, fontWeight = FontWeight.Medium)
                }
            }
            if (h.subtitle.isNotBlank()) Text(h.subtitle, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (h.description != null) ExpandableText(h.description, Modifier.padding(top = 10.dp), 2)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircleAction(Icons.Rounded.Download, T("Завантажити")) { Downloads.download(songs); app.toast(T("Завантаження: {0} треків", songs.size)) }
                CircleAction(if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, if (saved) T("У медіатеці") else T("Додати в медіатеку")) {
                    scope.launch(Dispatchers.IO) { Library.save(item, !saved) }
                    if (Settings.accountSync.value && AccountStore.signedIn.value && h.playlistId != null)
                        scope.launch { runCatching { YouTubeMusic.savePlaylist(h.playlistId, !saved) } }
                    app.toast(if (saved) T("Видалено з медіатеки") else T("Додано в медіатеку"))
                }
                FilledIconButton(onClick = { h.play?.let { Playback.playEndpoint(it, h.title) } ?: if (songs.isNotEmpty()) Playback.playQueue(songs, title = h.title) else Unit },
                    Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                    Icon(Icons.Rounded.PlayArrow, T("Відтворити"), Modifier.size(36.dp))
                }
                CircleAction(Icons.Rounded.Shuffle, T("Перемішати")) {
                    h.play?.let { Playback.playEndpoint(it, h.title, shuffled = true) } ?: Playback.playQueue(songs, title = h.title, shuffled = true)
                }
                CircleAction(Icons.Rounded.Share, T("Поділитися"), onShare)
            }
        }
    }
}

@Composable
private fun CircleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, Modifier.size(44.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Pal.chip)) {
        Icon(icon, label)
    }
}

@Composable
private fun ArtistHeader(h: Header, browseId: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val subscribed by remember(browseId) { Library.observe { Library.isSaved("artist", browseId) } }.collectAsState(false)
    fun toggleSub() {
        scope.launch(Dispatchers.IO) { Library.save(ArtistItem(browseId, h.title, h.subtitle, h.thumbnail), !subscribed) }
        if (Settings.accountSync.value && AccountStore.signedIn.value) scope.launch { runCatching { YouTubeMusic.subscribe(browseId, !subscribed) } }
    }
    if (Pal.minimal) {
        // Minimal: edge-to-edge photo, the name over it with a round accent play button, a star to follow.
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.05f)) {
                AsyncImage(Parser.resize(h.thumbnail, 1080), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Pal.bg)))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.title, Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp)
                    h.play?.let { p ->
                        FilledIconButton(onClick = { Playback.playEndpoint(p, h.title, shuffled = true) }, Modifier.size(52.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Icon(Icons.Rounded.PlayArrow, T("Відтворити"), Modifier.size(30.dp)) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (subscribed) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (subscribed) T("Ви підписані") else T("Підписатися"), Modifier.weight(1f), onClick = ::toggleSub)
                h.radio?.let { r -> PillButton(Icons.Rounded.Sensors, T("Радіо"), Modifier.weight(1f)) { Playback.playEndpoint(r, T("Радіо: {0}", h.title)) } }
            }
            if (h.secondSubtitle.isNotBlank()) Text(h.secondSubtitle, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Dim, fontSize = 13.sp)
        }
        return
    }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(1.2f)) {
            AsyncImage(Parser.resize(h.thumbnail, 1080), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(HeaderGradient))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(h.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                if (h.secondSubtitle.isNotBlank()) Text(h.secondSubtitle, color = Dim, fontSize = 13.sp)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) { Library.save(ArtistItem(browseId, h.title, h.subtitle, h.thumbnail), !subscribed) }
                if (Settings.accountSync.value && AccountStore.signedIn.value) scope.launch { runCatching { YouTubeMusic.subscribe(browseId, !subscribed) } }
            }) { Text(if (subscribed) T("Ви підписані") else T("Підписатися")) }
            Spacer(Modifier.weight(1f))
            h.radio?.let { r -> CircleAction(Icons.Rounded.Sensors, T("Радіо")) { Playback.playEndpoint(r, T("Радіо: {0}", h.title)) } }
            h.play?.let { p ->
                FilledIconButton(onClick = { Playback.playEndpoint(p, h.title, shuffled = true) }, Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Icon(Icons.Rounded.Shuffle, T("Перемішати")) }
            }
        }
        if (h.subtitle.isNotBlank()) Text(h.subtitle, Modifier.padding(horizontal = 16.dp), color = Dim, fontSize = 13.sp)
    }
}

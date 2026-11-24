package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Pal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacymusic.LocalAudioFiles
import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.*
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.AppController
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.PlayContext
import com.example.privacymusic.ui.components.*
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibTab(private val raw: String) { PLAYLISTS("Плейлисти"), SONGS("Сподобалися"), ALBUMS("Альбоми"), ARTISTS("Виконавці"), DOWNLOADS("Завантаження"), HISTORY("Історія"), DEVICE("На пристрої"), ACCOUNT("YouTube Music") ; val label: String get() = T(raw) }

@Composable
fun LibraryScreen() {
    val app = LocalApp.current
    val signedIn by AccountStore.signedIn.collectAsState()
    val accountLibrary by Settings.accountLibrary.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibTab.PLAYLISTS) }
    var creating by remember { mutableStateOf(false) }
    val all = LibTab.entries.filter { it != LibTab.ACCOUNT || (signedIn && accountLibrary) }
    val layout by Settings.layoutLibrary.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        LayoutEditor(T("Медіатека"), Settings.layoutLibrary, all.map { it.name to it.label } + ("RECENT" to T("Нещодавно додане")), onDone = { editing = false })
        return
    }
    // The user's order; hidden sections disappear (Playlists stays reachable if everything is hidden).
    val order = Layouts.visible(layout, all.map { it.name } + "RECENT")
    val available = order.mapNotNull { id -> all.firstOrNull { it.name == id } }.ifEmpty { listOf(LibTab.PLAYLISTS) }
    val current = if (tab in available) tab else available.first()
    if (Pal.minimal) {
        MinimalLibrary(available, "RECENT" in order, onEdit = { editing = true }, onCreate = { creating = true })
        if (creating) NewPlaylistDialog(onDismiss = { creating = false }) { name ->
            app.scope.launch { val id = withContext(Dispatchers.IO) { Library.createPlaylist(name) }; app.nav.navigate("local/${id}") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Медіатека")) {
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, T("Налаштувати сторінку")) }
            IconButton(onClick = { app.nav.navigate("stats") { launchSingleTop = true } }) { Icon(Icons.Rounded.BarChart, T("Статистика")) }
        }
        ChipsRow(available.map { it.label to (it == current) }, { tab = available[it] }, Modifier.padding(bottom = 6.dp))
        when (current) {
            LibTab.PLAYLISTS -> PlaylistsTab({ creating = true }, { app.nav.navigate("downloads") { launchSingleTop = true } })
            LibTab.SONGS -> SongsTab()
            LibTab.ALBUMS -> SavedTab("album", Icons.Rounded.Album, T("Альбомів поки немає"), T("Натисніть «Додати в медіатеку» на сторінці альбому."))
            LibTab.ARTISTS -> SavedTab("artist", Icons.Rounded.Person, T("Підписок поки немає"), T("Натисніть «Підписатися» на сторінці виконавця. Підписки зберігаються тільки на цьому пристрої."))
            LibTab.DOWNLOADS -> DownloadsTab()
            LibTab.HISTORY -> HistoryTab()
            LibTab.DEVICE -> DeviceTab()
            LibTab.ACCOUNT -> AccountTab()
        }
    }
    if (creating) NewPlaylistDialog(onDismiss = { creating = false }) { name ->
        app.scope.launch { val id = withContext(Dispatchers.IO) { Library.createPlaylist(name) }; app.nav.navigate("local/${id}") }
    }
}

private val LibTab.icon: ImageVector get() = when (this) {
    LibTab.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic; LibTab.SONGS -> Icons.Rounded.ThumbUp; LibTab.ALBUMS -> Icons.Rounded.Album
    LibTab.ARTISTS -> Icons.Rounded.Mic; LibTab.DOWNLOADS -> Icons.Rounded.DownloadForOffline; LibTab.HISTORY -> Icons.Rounded.History
    LibTab.DEVICE -> Icons.Rounded.PhoneAndroid; LibTab.ACCOUNT -> Icons.Rounded.AccountCircle
}

/** Minimal library: a list of categories (tap one to open it) followed by a grid of recently added collections. */
@Composable
private fun MinimalLibrary(available: List<LibTab>, showRecent: Boolean, onEdit: () -> Unit, onCreate: () -> Unit) {
    val app = LocalApp.current
    var open by rememberSaveable { mutableStateOf<LibTab?>(null) }
    androidx.activity.compose.BackHandler(open != null) { open = null }
    val saved by remember { Library.observe { (Library.saved("album") + Library.saved("playlist")).sortedByDescending { it.savedAt } } }.collectAsState(emptyList())
    val playlists by remember { Library.observe { Library.playlists() } }.collectAsState(emptyList())
    androidx.compose.animation.AnimatedContent(open, transitionSpec = {
        // Screens are transparent over one background, so the two pages must move edge to edge (full width, same
        // timing) — a parallax offset would slide one page over the other and both would show through.
        val ease = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(340, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
        if (targetState != null) androidx.compose.animation.slideInHorizontally(ease) { it } togetherWith androidx.compose.animation.slideOutHorizontally(ease) { -it }
        else androidx.compose.animation.slideInHorizontally(ease) { -it } togetherWith androidx.compose.animation.slideOutHorizontally(ease) { it }
    }, label = "lib") { tab ->
        Column(Modifier.fillMaxSize()) {
            if (tab == null) {
                TopBar(T("Медіатека")) {
                    IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, T("Налаштувати сторінку")) }
                    IconButton(onClick = { app.nav.navigate("stats") { launchSingleTop = true } }) { Icon(Icons.Rounded.BarChart, T("Статистика")) }
                }
                val recent: List<YTItem> = remember(saved, playlists) {
                    saved.map { s -> if (s.kind == "album") AlbumItem(s.id, s.playlistId, s.title, emptyList(), null, s.subtitle, s.thumbnail)
                        else PlaylistItem(s.id, s.title, s.subtitle, s.thumbnail, s.playlistId) } +
                        playlists.map { LocalPlaylistItem(it.id, it.name, it.count, it.thumbnail) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(available, key = { it.name }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { open = t }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(t.icon, null, Modifier.size(26.dp), tint = Pal.accent)
                            Text(t.label, Modifier.padding(start = 16.dp).weight(1f), fontSize = 20.sp)
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Dim)
                        }
                        HorizontalDivider(Modifier.padding(start = 58.dp), thickness = 0.5.dp, color = Pal.line)
                    }
                    if (showRecent) item {
                        Text(T("Нещодавно додане"), Modifier.padding(start = 16.dp, top = 26.dp, bottom = 10.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (recent.isEmpty()) Text(T("Тут з'являться альбоми й плейлисти, які ви додасте в медіатеку."), Modifier.padding(horizontal = 16.dp), color = Dim, fontSize = 14.sp)
                    }
                    if (showRecent) items(recent.chunked(2)) { row ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { item -> Box(Modifier.weight(1f)) { ItemCard(item, { app.open(item) }, { app.menu = AppController.MenuRequest(item) }, width = 400.dp) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            } else {
                TopBar(tab.label, onBack = { open = null })
                when (tab) {
                    LibTab.PLAYLISTS -> PlaylistsTab(onCreate, { app.nav.navigate("downloads") { launchSingleTop = true } })
                    LibTab.SONGS -> SongsTab()
                    LibTab.ALBUMS -> SavedTab("album", Icons.Rounded.Album, T("Альбомів поки немає"), T("Натисніть «Додати в медіатеку» на сторінці альбому."))
                    LibTab.ARTISTS -> SavedTab("artist", Icons.Rounded.Person, T("Підписок поки немає"), T("Натисніть «Підписатися» на сторінці виконавця. Підписки зберігаються тільки на цьому пристрої."))
                    LibTab.DOWNLOADS -> DownloadsTab()
                    LibTab.HISTORY -> HistoryTab()
                    LibTab.DEVICE -> DeviceTab()
                    LibTab.ACCOUNT -> AccountTab()
                }
            }
        }
    }
}

@Composable
fun NewPlaylistDialog(initial: String = "", title: String = T("Новий плейлист"), onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(T("Назва")) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()); onDismiss() }) { Text(T("Зберегти")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T("Скасувати")) } })
}

@Composable
private fun CollectionRow(title: String, subtitle: String, icon: ImageVector, thumbnail: String? = null, gradient: List<Color>? = null, onMenu: (() -> Unit)? = null, onClick: () -> Unit) {
    val minimal = Pal.minimal
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu).padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(if (minimal) 8.dp else 4.dp)
        if (gradient != null) Box(Modifier.size(56.dp).clip(shape).background(if (minimal) Brush.linearGradient(listOf(Pal.chip, Pal.chip)) else Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (minimal) Pal.accent else Color.White)
        }
        else Thumb(thumbnail, Modifier.size(56.dp), 160, shape, fallback = icon)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, fontWeight = if (minimal) FontWeight.Normal else FontWeight.Medium, fontSize = if (minimal) 17.sp else 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (onMenu != null) IconButton(onClick = onMenu) { Icon(if (minimal) Icons.Rounded.MoreHoriz else Icons.Rounded.MoreVert, T("Більше"), tint = if (minimal) Color.White else Dim) }
        else if (minimal) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.padding(end = 12.dp), tint = Dim)
    }
    if (minimal) HorizontalDivider(Modifier.padding(start = 86.dp), thickness = 0.5.dp, color = Pal.line)
}

@Composable
private fun PlaylistsTab(onCreate: () -> Unit, onDownloads: () -> Unit) {
    val app = LocalApp.current
    val playlists by remember { Library.observe { Library.playlists() } }.collectAsState(emptyList())
    val liked by remember { Library.observe { Library.likedSongs().size } }.collectAsState(0)
    val downloads by remember { Library.observe { Library.downloads().count { it.done } } }.collectAsState(0)
    val saved by remember { Library.observe { Library.saved("playlist") } }.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item { CollectionRow(T("Створити плейлист"), T("Зберігається лише на пристрої"), Icons.Rounded.Add, gradient = listOf(Pal.line, Pal.line), onClick = onCreate) }
        item { CollectionRow(T("Імпортувати плейлист"), T("YouTube Music, Spotify або таблиця CSV / XLSX"), Icons.Rounded.Input, gradient = listOf(Color(0xff1db954), Pal.accent)) { app.nav.navigate("import") } }
        item { CollectionRow(T("Сподобалися"), T("Автоплейлист • {0}", liked), Icons.Rounded.ThumbUp, gradient = listOf(Color(0xff6f4ef2), Color(0xffd33fa0))) { app.nav.navigate("liked") } }
        item { CollectionRow(T("Завантажено"), T("Автоплейлист • {0}", downloads), Icons.Rounded.DownloadDone, gradient = listOf(Color(0xff0f9d58), Color(0xff1e5f8a)), onClick = onDownloads) }
        items(playlists, key = { "l${it.id}" }) { p ->
            CollectionRow(p.name, T("Плейлист • {0}", p.count), Icons.AutoMirrored.Rounded.QueueMusic, p.thumbnail,
                onMenu = { app.menu = AppController.MenuRequest(LocalPlaylistItem(p.id, p.name, p.count, p.thumbnail)) }) { app.nav.navigate("local/${p.id}") }
        }
        items(saved, key = { "s${it.id}" }) { p ->
            CollectionRow(p.title, p.subtitle.ifBlank { T("Плейлист YouTube Music") }, Icons.AutoMirrored.Rounded.QueueMusic, p.thumbnail,
                onMenu = { app.menu = AppController.MenuRequest(PlaylistItem(p.id, p.title, p.subtitle, p.thumbnail, p.playlistId)) }) { app.browse(p.id) }
        }
    }
}

@Composable
private fun PlayAllRow(songs: List<Song>, title: String) {
    if (Pal.minimal) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(Icons.Rounded.PlayArrow, T("Відтворити"), Modifier.weight(1f), songs.isNotEmpty()) { Playback.playQueue(songs, title = title) }
            PillButton(Icons.Rounded.Shuffle, T("Перемішати"), Modifier.weight(1f), songs.isNotEmpty()) { Playback.playQueue(songs, title = title, shuffled = true) }
        }
        return
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { Playback.playQueue(songs, title = title) }, enabled = songs.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) { Icon(Icons.Rounded.PlayArrow, null); Text(T("Відтворити")) }
        OutlinedButton(onClick = { Playback.playQueue(songs, title = title, shuffled = true) }, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T("Перемішати")) }
    }
}

@Composable
private fun SongsTab() {
    val app = LocalApp.current
    val songs by remember { Library.observe { Library.likedSongs() } }.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        if (songs.isEmpty()) item { EmptyState(Icons.Rounded.ThumbUp, T("Тут будуть пісні, що сподобалися"), T("Натисніть 👍 у плеєрі або в меню пісні. Лайки зберігаються лише на цьому пристрої.")) }
        else item { PlayAllRow(songs, T("Сподобалися")) }
        items(songs, key = { it.id }) { s -> ItemRow(s, { app.open(s, PlayContext.Local(songs, T("Сподобалися"))) }, { app.menu = AppController.MenuRequest(s) }) }
    }
}

@Composable
fun LikedSongsScreen() {
    val app = LocalApp.current
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Сподобалися"), onBack = { app.nav.popBackStack() })
        SongsTab()
    }
}

@Composable
fun DownloadedScreen() {
    val app = LocalApp.current
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Завантажено"), onBack = { app.nav.popBackStack() })
        DownloadsTab()
    }
}

@Composable
private fun SavedTab(kind: String, icon: ImageVector, emptyTitle: String, emptyText: String) {
    val app = LocalApp.current
    val items by remember(kind) { Library.observe { Library.saved(kind) } }.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        if (items.isEmpty()) item { EmptyState(icon, emptyTitle, emptyText) }
        items(items, key = { it.id }) { s ->
            val item: YTItem = when (kind) {
                "album" -> AlbumItem(s.id, s.playlistId, s.title, subtitle = s.subtitle, thumbnail = s.thumbnail)
                "artist" -> ArtistItem(s.id, s.title, s.subtitle, s.thumbnail)
                else -> PlaylistItem(s.id, s.title, s.subtitle, s.thumbnail, s.playlistId)
            }
            ItemRow(item, { app.open(item) }, { app.menu = AppController.MenuRequest(item) })
        }
    }
}

@Composable
private fun DownloadsTab() {
    val app = LocalApp.current
    val downloads by remember { Library.observe { Library.downloads() } }.collectAsState(emptyList())
    val progress by Downloads.progress.collectAsState()
    val songs = downloads.filter { it.done }.map { it.song }
    LazyColumn(Modifier.fillMaxSize()) {
        if (downloads.isEmpty() && progress.isEmpty()) item {
            EmptyState(Icons.Rounded.DownloadForOffline, T("Немає завантажень"), T("Завантажені треки зберігаються в приватній пам'яті застосунку й грають без мережі."))
        } else item {
            PlayAllRow(songs, T("Завантажено"))
            Text(T("{0} МБ на пристрої", downloads.sumOf { it.size } / (1024 * 1024)), Modifier.padding(horizontal = 16.dp), color = Dim, fontSize = 13.sp)
        }
        items(downloads, key = { it.song.id }) { d ->
            ItemRow(d.song, { app.open(d.song, PlayContext.Local(songs, T("Завантажено"))) }, { app.menu = AppController.MenuRequest(d.song) },
                trailing = { progress[d.song.id]?.let { CircularProgressIndicator({ it }, Modifier.size(22.dp), strokeWidth = 2.dp) } })
        }
    }
}

@Composable
private fun HistoryTab() {
    val app = LocalApp.current
    val enabled by Settings.history.state.collectAsState()
    val history by remember { Library.observe { Library.history() } }.collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        if (!enabled) item {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                Column(Modifier.padding(16.dp)) {
                    Text(T("Історію прослуховування вимкнено"), fontWeight = FontWeight.Bold)
                    Text(T("Нові прослуховування не записуються. Увімкнути можна в налаштуваннях приватності."), color = Dim, fontSize = 13.sp)
                }
            }
        }
        if (history.isEmpty()) item { EmptyState(Icons.Rounded.History, T("Історія порожня"), T("Тут з'являтимуться пісні, які ви слухали. Історія зберігається тільки на цьому пристрої і нікуди не надсилається.")) }
        val groups = history.groupBy { dayLabel(it.playedAt) }
        groups.forEach { (label, entries) ->
            item(key = "g${label}") { ShelfHeader(label) }
            items(entries, key = { "${it.song.id}@${it.playedAt}" }) { e ->
                ItemRow(e.song, { app.open(e.song) }, { app.menu = AppController.MenuRequest(e.song, historyAt = e.playedAt) })
            }
        }
    }
}

private fun dayLabel(t: Long): String = when {
    DateUtils.isToday(t) -> T("Сьогодні")
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> T("Учора")
    System.currentTimeMillis() - t < 7 * DateUtils.DAY_IN_MILLIS -> T("Цього тижня")
    else -> java.text.SimpleDateFormat("LLLL yyyy", com.example.privacymusic.i18n.I18n.locale()).format(java.util.Date(t)).replaceFirstChar { it.uppercase() }
}

@Composable
private fun DeviceTab() {
    val app = LocalApp.current
    val context = LocalContext.current
    val songs by remember { Library.observe { Library.deviceSongs() } }.collectAsState(emptyList())
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) }
    val scan: () -> Unit = { app.scope.launch(Dispatchers.IO) { Library.replaceDeviceSongs(LocalAudioFiles.scan(context)) } }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) scan() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        app.scope.launch(Dispatchers.IO) {
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            uris.mapNotNull { LocalAudioFiles.read(context, it) }.forEach(Library::upsert)
            Library.replaceDeviceSongs(Library.deviceSongs())
        }
    }
    LaunchedEffect(granted) { if (granted) scan() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) Button(onClick = { request.launch(permission) }) { Text(T("Дозволити доступ до музики")) }
                else OutlinedButton(onClick = scan) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T("Оновити")) }
                OutlinedButton(onClick = { pick.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T("Вибрати файли")) }
            }
            if (songs.isNotEmpty()) PlayAllRow(songs, T("На пристрої"))
        }
        if (songs.isEmpty()) item { EmptyState(Icons.Rounded.PhoneAndroid, T("Немає аудіофайлів"), T("Музика, що вже є на телефоні, відтворюється повністю офлайн.")) }
        items(songs, key = { it.id }) { s -> ItemRow(s, { app.open(s, PlayContext.Local(songs, T("На пристрої"))) }, { app.menu = AppController.MenuRequest(s) }) }
    }
}

@Composable
private fun AccountTab() {
    val app = LocalApp.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(T("Ці розділи завантажуються з вашого акаунта YouTube Music лише тоді, коли ви їх відкриваєте."),
                Modifier.padding(16.dp), color = Dim, fontSize = 13.sp)
        }
        val entries = listOf(
            Triple(T("Плейлисти акаунта"), "FEmusic_liked_playlists", Icons.AutoMirrored.Rounded.QueueMusic),
            Triple(T("Пісні, що сподобалися (акаунт)"), "VLLM", Icons.Rounded.ThumbUp),
            Triple(T("Альбоми акаунта"), "FEmusic_liked_albums", Icons.Rounded.Album),
            Triple(T("Пісні в медіатеці акаунта"), "FEmusic_liked_videos", Icons.Rounded.MusicNote),
            Triple(T("Виконавці акаунта"), "FEmusic_library_corpus_track_artists", Icons.Rounded.Person),
            Triple(T("Підписки акаунта"), "FEmusic_library_corpus_artists", Icons.Rounded.Subscriptions),
        )
        items(entries) { (title, id, icon) -> CollectionRow(title, "YouTube Music", icon, gradient = listOf(Color(0xff3a1f1f), Color(0xff5a1a24))) { app.browse(id) } }
    }
}

// ---------------- local playlist ----------------

@Composable
fun LocalPlaylistScreen(id: Long, startEditing: Boolean) {
    val app = LocalApp.current
    val playlist by remember(id) { Library.observe { Library.playlist(id) } }.collectAsState(null)
    val songs by remember(id) { Library.observe { Library.playlistSongs(id) } }.collectAsState(emptyList())
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    LaunchedEffect(app.editPlaylist) { if (app.editPlaylist == id) { editing = true; app.editPlaylist = null } }
    val p = playlist
    val item = p?.let { LocalPlaylistItem(it.id, it.name, it.count, it.thumbnail) }
    val context = LocalContext.current
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) app.scope.launch { if (!Covers.set(context, id, uri)) app.toast(T("Не вдалося відкрити зображення")) }
    }
    val choose = { pickCover.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (editing && p != null) { EditPlaylist(p, songs, choose) { editing = false }; return }
    Column(Modifier.fillMaxSize()) {
        TopBar(null, onBack = { app.nav.popBackStack() }) {
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, T("Редагувати")) }
            IconButton(onClick = { item?.let { app.menu = AppController.MenuRequest(it) } }) { Icon(Icons.Rounded.MoreVert, T("Більше")) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(0.6f).aspectRatio(1f).clip(RoundedCornerShape(Pal.corner + 4.dp)).clickable { choose() }) {
                        Thumb(p?.thumbnail, Modifier.fillMaxSize(), 720, RoundedCornerShape(Pal.corner + 4.dp), Icons.AutoMirrored.Rounded.QueueMusic)
                        if (p?.customCover != true) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(CircleShape).background(Pal.bg.copy(alpha = .7f)).padding(8.dp)) {
                            Icon(Icons.Rounded.AddPhotoAlternate, T("Змінити обкладинку"), Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(p?.name.orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(T("Плейлист на пристрої • {0} треків • {1}", songs.size, formatDuration(songs.sumOf { it.duration ?: 0 })), color = Dim, fontSize = 13.sp)
                    if (!p?.description.isNullOrBlank()) ExpandableText(p!!.description, Modifier.padding(top = 8.dp), 3)
                    if (p?.source != null) Text(when {
                        p.source.contains("spotify") -> T("Імпортовано зі Spotify")
                        p.source.contains("youtube") -> T("Імпортовано з YouTube Music")
                        else -> T("Імпортовано з таблиці")
                    }, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    if (Pal.minimal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(Icons.Rounded.PlayArrow, T("Відтворити"), Modifier.weight(1f), songs.isNotEmpty()) { Playback.playQueue(songs, title = p?.name) }
                        PillButton(Icons.Rounded.Shuffle, T("Перемішати"), Modifier.weight(1f), songs.isNotEmpty()) { Playback.playQueue(songs, title = p?.name, shuffled = true) }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RoundAction(Icons.Rounded.Download, T("Завантажити"), songs.isNotEmpty()) { Downloads.download(songs); app.toast(T("Завантаження: {0} треків", songs.size)) }
                        RoundAction(Icons.Rounded.Edit, T("Редагувати"), true) { editing = true }
                        FilledIconButton(onClick = { Playback.playQueue(songs, title = p?.name) }, Modifier.size(64.dp), enabled = songs.isNotEmpty(),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                            Icon(Icons.Rounded.PlayArrow, T("Відтворити"), Modifier.size(36.dp))
                        }
                        RoundAction(Icons.Rounded.Shuffle, T("Перемішати"), songs.isNotEmpty()) { Playback.playQueue(songs, title = p?.name, shuffled = true) }
                        RoundAction(Icons.Rounded.MoreVert, T("Більше"), true) { item?.let { app.menu = AppController.MenuRequest(it) } }
                    }
                }
            }
            if (songs.isEmpty()) item { EmptyState(Icons.Rounded.PlaylistAdd, T("Плейлист порожній"), T("Додавайте пісні через меню ⋮ → «Зберегти в плейлист».")) }
            itemsIndexed(songs, key = { i, s -> "${i}:${s.id}" }) { i, s ->
                ItemRow(s, { app.open(s, PlayContext.Local(songs, p?.name)) }, { app.menu = AppController.MenuRequest(s, localPlaylist = id, index = i) })
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, Modifier.size(44.dp), enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Pal.chip)) { Icon(icon, label) }
}

/** YouTube Music-style edit mode: title, description, drag handles to reorder, ✕ to remove. Saved with ✓. */
@Composable
private fun EditPlaylist(p: LocalPlaylist, original: List<Song>, chooseCover: () -> Unit, onDone: () -> Unit) {
    val app = LocalApp.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var name by remember { mutableStateOf(p.name) }
    var description by remember { mutableStateOf(p.description) }
    val list = remember { mutableStateListOf<Pair<Long, Song>>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(original) { if (!loaded && original.isNotEmpty()) { list.addAll(original.mapIndexed { i, s -> i.toLong() to s }); loaded = true } }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(density) { 64.dp.toPx() }
    fun save() {
        if (!loaded && original.isNotEmpty()) { onDone(); return }
        val songs = list.map { it.second }; val n = name.trim().ifBlank { p.name }; val d = description
        app.scope.launch(Dispatchers.IO) {
            Library.renamePlaylist(p.id, n); Library.setPlaylistDescription(p.id, d); Library.replacePlaylistSongs(p.id, songs)
        }
        onDone()
    }
    androidx.activity.compose.BackHandler { save() }
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Редагувати плейлист"), onBack = onDone) { IconButton(onClick = ::save) { Icon(Icons.Rounded.Check, T("Зберегти")) } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    val ctx = LocalContext.current
                    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(p.thumbnail, Modifier.size(96.dp).clickable { chooseCover() }, 300, RoundedCornerShape(Pal.corner + 2.dp), Icons.AutoMirrored.Rounded.QueueMusic)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(T("Обкладинка"), fontWeight = FontWeight.Medium)
                            Text(if (p.customCover) T("Власне зображення") else T("З першої пісні"), color = Dim, fontSize = 13.sp)
                            Row {
                                TextButton(onClick = chooseCover) { Text(T("Змінити")) }
                                if (p.customCover) TextButton(onClick = { app.scope.launch(Dispatchers.IO) { Covers.remove(ctx, p.id) } }) { Text(T("Прибрати")) }
                            }
                        }
                    }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(T("Назва")) }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text(T("Опис")) }, minLines = 2)
                    Text(T("Перетягуйте ≡ щоб змінити порядок"), color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            items(list, key = { it.first }) { entry ->
                val (uid, s) = entry
                val isDragged = dragging == uid
                Row(Modifier.fillMaxWidth().height(64.dp).zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) offset else 0f }
                    .background(if (isDragged) Pal.chip else Color.Transparent)
                    .padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DragHandle, T("Перетягнути"), Modifier.padding(12.dp).pointerInput(uid) {
                        detectDragGestures(
                            onDragStart = { dragging = uid; offset = 0f },
                            onDragEnd = { dragging = null; offset = 0f },
                            onDragCancel = { dragging = null; offset = 0f },
                        ) { change, drag ->
                            change.consume()
                            offset += drag.y
                            val i = list.indexOfFirst { it.first == uid }
                            if (offset > rowPx / 2 && i < list.lastIndex) { list.add(i + 1, list.removeAt(i)); offset -= rowPx }
                            else if (offset < -rowPx / 2 && i > 0) { list.add(i - 1, list.removeAt(i)); offset += rowPx }
                        }
                    }, tint = Dim)
                    Thumb(s.thumbnail, Modifier.size(44.dp), 120)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 15.sp)
                        Text(s.artistText, maxLines = 1, color = Dim, fontSize = 13.sp)
                    }
                    IconButton(onClick = { list.remove(entry) }) { Icon(Icons.Rounded.Close, T("Видалити з плейлиста"), tint = Dim) }
                }
            }
        }
    }
}

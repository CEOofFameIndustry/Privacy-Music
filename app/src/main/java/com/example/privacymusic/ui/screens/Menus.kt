package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Danger

import com.example.privacymusic.ui.theme.Pal

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.*
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.AppController
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.components.Thumb
import com.example.privacymusic.ui.components.subtitleText
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun MenuEntry(icon: ImageVector, text: String, tint: Color = Color.White, onClick: () -> Unit) {
    if (Pal.minimal) {
        // Minimal: label first, icon on the right, hairline separators (context-menu style).
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 16.sp, color = tint)
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), thickness = 0.5.dp, color = Pal.line)
        return
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Text(text, Modifier.padding(start = 20.dp), fontSize = 15.sp, color = tint)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier, shape = RoundedCornerShape(12.dp), color = Pal.chip) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null); Spacer(Modifier.height(6.dp)); Text(text, fontSize = 12.sp, maxLines = 1)
        }
    }
}

fun shareUrl(item: YTItem): String? = when (item) {
    is Song -> if (item.isLocal) null else "https://music.youtube.com/watch?v=${item.id}"
    is AlbumItem -> item.playlistId?.let { "https://music.youtube.com/playlist?list=${it}" } ?: "https://music.youtube.com/browse/${item.browseId}"
    is PlaylistItem -> "https://music.youtube.com/playlist?list=${item.playlistId ?: item.browseId.removePrefix("VL")}"
    is ArtistItem -> "https://music.youtube.com/channel/${item.browseId}"
    is NavItem, is LocalPlaylistItem -> null
}

fun shareText(context: Context, text: String) =
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

/** A local playlist as text: one "Artist — Title  link" line per track (links only for YouTube tracks). */
fun playlistAsText(name: String, songs: List<Song>) = buildString {
    appendLine(name); appendLine()
    songs.forEachIndexed { i, s -> append("${i + 1}. ${s.artistText} — ${s.title}"); shareUrl(s)?.let { append("  ${it}") }; appendLine() }
}

/** Exports a local playlist as CSV (same columns the importer understands) and opens the share sheet. */
fun exportCsv(context: Context, name: String, songs: List<Song>) {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    val dir = File(context.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val file = File(dir, name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "playlist" } + ".csv")
    file.writeText("Track Name,Artist Name(s),Album Name,Duration (ms),Video ID\n" + songs.joinToString("\n") { s ->
        listOf(q(s.title), q(s.artists.joinToString(", ") { it.name }), q(s.album?.name.orEmpty()), ((s.duration ?: 0) * 1000L).toString(), if (s.isLocal) "" else s.id).joinToString(",")
    })
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}

/** Loads the tracks of an album / playlist so they can be queued or downloaded. */
private suspend fun tracksOf(item: YTItem): List<Song> = when (item) {
    is AlbumItem -> YouTubeMusic.browse(item.browseId).shelves.filter { it.style == ShelfStyle.LIST }.flatMap { it.items }.filterIsInstance<Song>()
    is PlaylistItem -> (item.playlistId ?: item.browseId.removePrefix("VL")).let { YouTubeMusic.next(WatchEndpoint(playlistId = it)).songs }
    is LocalPlaylistItem -> withContext(Dispatchers.IO) { Library.playlistSongs(item.id) }
    else -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemMenu(request: AppController.MenuRequest, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val item = request.item
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dialog by remember { mutableStateOf<String?>(null) }
    val pinned by remember(item.key) { Library.observe { Library.isPinned(item.key) } }.collectAsState(false)
    fun act(block: () -> Unit) { block(); onDismiss() }
    fun bulk(message: String?, block: (List<Song>) -> Unit) = act {
        app.scope.launch {
            try { val songs = tracksOf(item); if (songs.isEmpty()) app.toast(T("Немає треків")) else { block(songs); message?.let(app::toast) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { app.toast(T("Немає з'єднання з YouTube Music")) }
        }
    }
    val pinEntry: @Composable () -> Unit = {
        if (item !is NavItem) MenuEntry(Icons.Outlined.PushPin, if (pinned) T("Відкріпити з головної") else T("Закріпити на головній")) {
            act { app.scope.launch(Dispatchers.IO) { Library.pin(item, !pinned) }; app.toast(if (pinned) T("Відкріплено") else T("Закріплено на головній")) }
        }
    }

    when (dialog) {
        "rename" -> (item as? LocalPlaylistItem)?.let { p ->
            NewPlaylistDialog(p.title, T("Перейменувати плейлист"), onDismiss = onDismiss) { name -> app.scope.launch(Dispatchers.IO) { Library.renamePlaylist(p.id, name) } }
        }
        "delete" -> (item as? LocalPlaylistItem)?.let { p ->
            AlertDialog(onDismissRequest = onDismiss, title = { Text(T("Видалити плейлист?")) },
                text = { Text(T("Плейлист «{0}» буде видалено з пристрою. Пісні залишаться в інших списках і в «Сподобалися».", p.title)) },
                confirmButton = { TextButton(onClick = {
                    app.scope.launch(Dispatchers.IO) { Library.deletePlaylist(p.id) }
                    if (app.nav.currentBackStackEntry?.destination?.route?.startsWith("local/") == true) app.nav.popBackStack()
                    onDismiss()
                }) { Text(T("Видалити"), color = Danger) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(T("Скасувати")) } })
        }
    }
    if (dialog != null) return

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Pal.card,
        modifier = if (Pal.minimal) Modifier.padding(horizontal = 8.dp) else Modifier,
        shape = if (Pal.minimal) RoundedCornerShape(24.dp) else BottomSheetDefaults.ExpandedShape,
        dragHandle = if (Pal.minimal) ({ Spacer(Modifier.height(14.dp)) }) else ({ BottomSheetDefaults.DragHandle() })) {
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(item.thumbnail, Modifier.size(48.dp), 160, if (item is ArtistItem) CircleShape else RoundedCornerShape(4.dp),
                        if (item is LocalPlaylistItem) Icons.AutoMirrored.Rounded.QueueMusic else Icons.Rounded.MusicNote)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.subtitleText(), color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item is Song) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(Icons.AutoMirrored.Rounded.PlaylistPlay, T("Наступною"), Modifier.weight(1f)) { act { Playback.playNext(listOf(item)); app.toast(T("Буде відтворено наступною")) } }
                    QuickAction(Icons.Rounded.PlaylistAdd, T("У плейлист"), Modifier.weight(1f)) { act { app.addToPlaylist = listOf(item) } }
                    QuickAction(Icons.Rounded.Share, T("Поділитися"), Modifier.weight(1f)) {
                        act { shareText(context, shareUrl(item) ?: "${item.artistText} — ${item.title}") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Pal.line)
            }
            when (item) {
                is Song -> item { SongEntries(item, request, ::act); pinEntry() }
                is LocalPlaylistItem -> item {
                    MenuEntry(Icons.Rounded.PlayArrow, T("Відтворити")) { bulk(null) { Playback.playQueue(it, title = item.title) } }
                    MenuEntry(Icons.Rounded.Shuffle, T("Перемішати")) { bulk(null) { Playback.playQueue(it, title = item.title, shuffled = true) } }
                    MenuEntry(Icons.AutoMirrored.Rounded.PlaylistPlay, T("Відтворити наступним")) { bulk(T("Буде відтворено наступним")) { Playback.playNext(it) } }
                    MenuEntry(Icons.AutoMirrored.Rounded.QueueMusic, T("Додати в чергу")) { bulk(T("Додано в чергу")) { Playback.addToQueue(it) } }
                    MenuEntry(Icons.Rounded.Edit, T("Редагувати плейлист")) {
                        act {
                            app.editPlaylist = item.id
                            if (app.nav.currentBackStackEntry?.arguments?.getLong("id") != item.id || app.nav.currentBackStackEntry?.destination?.route?.startsWith("local/") != true)
                                app.nav.navigate("local/${item.id}")
                        }
                    }
                    MenuEntry(Icons.Rounded.DriveFileRenameOutline, T("Перейменувати")) { dialog = "rename" }
                    MenuEntry(Icons.Rounded.AddPhotoAlternate, T("Змінити обкладинку")) {
                        act {
                            app.editPlaylist = item.id
                            if (app.nav.currentBackStackEntry?.arguments?.getLong("id") != item.id || app.nav.currentBackStackEntry?.destination?.route?.startsWith("local/") != true)
                                app.nav.navigate("local/${item.id}")
                        }
                    }
                    MenuEntry(Icons.Rounded.Download, T("Завантажити")) { bulk(T("Завантаження розпочато")) { Downloads.download(it) } }
                    MenuEntry(Icons.Rounded.Share, T("Поділитися списком")) { bulk(null) { shareText(context, playlistAsText(item.title, it)) } }
                    MenuEntry(Icons.Rounded.TableView, T("Експортувати таблицю (CSV)")) { bulk(null) { exportCsv(context, item.title, it) } }
                    pinEntry()
                    MenuEntry(Icons.Rounded.DeleteOutline, T("Видалити плейлист"), Danger) { dialog = "delete" }
                }
                is AlbumItem, is PlaylistItem -> item {
                    val kind = if (item is AlbumItem) "album" else "playlist"
                    val id = if (item is AlbumItem) item.browseId else (item as PlaylistItem).browseId
                    val saved by remember { Library.observe { Library.isSaved(kind, id) } }.collectAsState(false)
                    val pid = (item as? AlbumItem)?.playlistId ?: (item as? PlaylistItem)?.playlistId
                    if (pid != null) {
                        MenuEntry(Icons.Rounded.PlayArrow, T("Відтворити")) { act { Playback.playEndpoint(WatchEndpoint(playlistId = pid), item.title) } }
                        MenuEntry(Icons.Rounded.Shuffle, T("Перемішати")) { act { Playback.playEndpoint(WatchEndpoint(playlistId = pid), item.title, shuffled = true) } }
                    }
                    MenuEntry(Icons.AutoMirrored.Rounded.PlaylistPlay, T("Відтворити наступним")) { bulk(T("Буде відтворено наступним")) { Playback.playNext(it) } }
                    MenuEntry(Icons.AutoMirrored.Rounded.QueueMusic, T("Додати в чергу")) { bulk(T("Додано в чергу")) { Playback.addToQueue(it) } }
                    MenuEntry(Icons.Rounded.PlaylistAdd, T("Додати в плейлист")) { bulk(null) { app.addToPlaylist = it } }
                    MenuEntry(Icons.Rounded.CopyAll, T("Зберегти копію на пристрої")) {
                        bulk(T("Створено локальну копію")) { songs -> app.scope.launch(Dispatchers.IO) { Library.createPlaylist(item.title, songs, shareUrl(item)) } }
                    }
                    MenuEntry(if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, if (saved) T("Видалити з медіатеки") else T("Додати в медіатеку")) {
                        act { app.scope.launch(Dispatchers.IO) { Library.save(item, !saved) } }
                    }
                    MenuEntry(Icons.Rounded.Download, T("Завантажити")) { bulk(T("Завантаження розпочато")) { Downloads.download(it) } }
                    (item as? AlbumItem)?.artists?.firstOrNull { it.id != null }?.let { a -> MenuEntry(Icons.Rounded.Person, T("До виконавця")) { act { app.playerOpen = false; app.browse(a.id!!) } } }
                    pinEntry()
                    MenuEntry(Icons.Rounded.Share, T("Поділитися")) { act { shareUrl(item)?.let { shareText(context, it) } } }
                }
                is ArtistItem -> item {
                    val saved by remember { Library.observe { Library.isSaved("artist", item.browseId) } }.collectAsState(false)
                    MenuEntry(Icons.Rounded.Person, T("Відкрити")) { act { app.browse(item.browseId) } }
                    MenuEntry(if (saved) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd, if (saved) T("Відписатися") else T("Підписатися")) {
                        act {
                            app.scope.launch(Dispatchers.IO) { Library.save(item, !saved) }
                            if (Settings.accountSync.value && AccountStore.signedIn.value) app.scope.launch { runCatching { YouTubeMusic.subscribe(item.browseId, !saved) } }
                        }
                    }
                    pinEntry()
                    MenuEntry(Icons.Rounded.Share, T("Поділитися")) { act { shareUrl(item)?.let { shareText(context, it) } } }
                }
                is NavItem -> item { MenuEntry(Icons.Rounded.OpenInNew, T("Відкрити")) { act { app.open(item) } } }
            }
        }
    }
}

@Composable
private fun SongEntries(song: Song, request: AppController.MenuRequest, act: (() -> Unit) -> Unit) {
    val app = LocalApp.current
    val liked by remember(song.id) { Library.observe { Library.isLiked(song.id) } }.collectAsState(false)
    val disliked by remember(song.id) { Library.observe { Library.isDisliked(song.id) } }.collectAsState(false)
    val downloaded by remember(song.id) { Library.observe { Library.downloadPath(song.id) != null } }.collectAsState(false)
    val progress by Downloads.progress.collectAsState()
    val currentIndex by Playback.index.collectAsState()
    if (!song.isLocal) MenuEntry(Icons.Rounded.Sensors, T("Почати радіо")) { act { Playback.startRadio(song) } }
    MenuEntry(Icons.AutoMirrored.Rounded.QueueMusic, T("Додати в чергу")) { act { Playback.addToQueue(listOf(song)); app.toast(T("Додано в чергу")) } }
    request.queueIndex?.let { qi ->
        if (qi != currentIndex) {
            MenuEntry(Icons.Rounded.VerticalAlignTop, T("Відтворити наступною в черзі")) { act { Playback.move(qi, if (qi > currentIndex) currentIndex + 1 else currentIndex) } }
            MenuEntry(Icons.Rounded.RemoveCircleOutline, T("Видалити з черги")) { act { Playback.removeAt(qi) } }
        }
    }
    MenuEntry(if (liked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, if (liked) T("Видалити з «Сподобалися»") else T("Подобається")) {
        act {
            app.scope.launch(Dispatchers.IO) { Library.setLiked(song, !liked) }
            if (Settings.accountSync.value && AccountStore.signedIn.value && !song.isLocal) app.scope.launch { runCatching { YouTubeMusic.like(song.id, if (liked) null else true) } }
        }
    }
    if (!song.isLocal) MenuEntry(if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt, if (disliked) T("Прибрати «Не цікавить»") else T("Не цікавить")) {
        act { app.scope.launch(Dispatchers.IO) { Library.setDisliked(song, !disliked) }; if (!disliked) app.toast(T("Пісня не з'являтиметься в автовідтворенні")) }
    }
    if (!song.isLocal) when {
        progress.containsKey(song.id) -> MenuEntry(Icons.Rounded.Cancel, T("Скасувати завантаження")) { act { Downloads.cancel(song.id) } }
        downloaded -> MenuEntry(Icons.Rounded.DeleteOutline, T("Видалити завантаження")) { act { app.scope.launch(Dispatchers.IO) { Downloads.delete(song.id) } } }
        else -> MenuEntry(Icons.Rounded.Download, T("Завантажити")) { act { Downloads.download(song); app.toast(T("Завантаження розпочато")) } }
    }
    song.album?.id?.let { id -> MenuEntry(Icons.Rounded.Album, T("До альбому")) { act { app.playerOpen = false; app.browse(id) } } }
    song.artists.filter { it.id != null }.forEach { a -> MenuEntry(Icons.Rounded.Person, T("До виконавця: {0}", a.name)) { act { app.playerOpen = false; app.browse(a.id!!) } } }
    request.localPlaylist?.let { pl ->
        val index = request.index ?: return@let
        MenuEntry(Icons.Rounded.ArrowUpward, T("Перемістити вгору")) { act { app.scope.launch(Dispatchers.IO) { Library.movePlaylistSong(pl, index, index - 1) } } }
        MenuEntry(Icons.Rounded.ArrowDownward, T("Перемістити вниз")) { act { app.scope.launch(Dispatchers.IO) { Library.movePlaylistSong(pl, index, index + 1) } } }
        MenuEntry(Icons.Rounded.RemoveCircleOutline, T("Видалити з плейлиста")) { act { app.scope.launch(Dispatchers.IO) { Library.removeFromPlaylist(pl, index) } } }
    }
    request.historyAt?.let { at -> MenuEntry(Icons.Rounded.HistoryToggleOff, T("Видалити з історії")) { act { app.scope.launch(Dispatchers.IO) { Library.removeHistory(song, at) } } } }
}

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val playlists by remember { Library.observe { Library.playlists() } }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(T("Зберегти в плейлист")) }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null); Text(T("Новий плейлист"), Modifier.padding(start = 14.dp), fontWeight = FontWeight.Medium)
                }
            }
            items(playlists, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable {
                    app.scope.launch(Dispatchers.IO) { songs.forEach { s -> if (songs.size > 1 || !Library.playlistContains(p.id, s.id)) Library.addToPlaylist(p.id, s) } }
                    app.toast(T("Збережено в «{0}»", p.name)); onDismiss()
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(p.thumbnail, Modifier.size(40.dp), 120, fallback = Icons.AutoMirrored.Rounded.QueueMusic)
                    Column(Modifier.padding(start = 12.dp)) { Text(p.name); Text(T("{0} треків", p.count), color = Dim, fontSize = 12.sp) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(T("Закрити")) } })
    if (creating) NewPlaylistDialog(onDismiss = { creating = false }) { name ->
        app.scope.launch { withContext(Dispatchers.IO) { Library.createPlaylist(name, songs) }; app.toast(T("Створено «{0}»", name)); onDismiss() }
    }
}

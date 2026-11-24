package com.example.privacymusic.ui.components

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Pal

import androidx.compose.foundation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.privacymusic.data.*
import com.example.privacymusic.net.Parser
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.theme.Dim

@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier, size: Int = 226, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Pal.corner), fallback: ImageVector = Icons.Rounded.MusicNote) {
    Box(modifier.clip(shape).background(Pal.chip), contentAlignment = Alignment.Center) {
        if (url == null) Icon(fallback, null, tint = Dim, modifier = Modifier.fillMaxSize(0.45f))
        else AsyncImage(model = Parser.resize(url, size), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

fun formatDuration(seconds: Int?): String = seconds?.let { s -> if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }.orEmpty()

fun YTItem.subtitleText(): String = when (this) {
    is Song -> listOf(artistText, album?.name.orEmpty().takeIf { artists.isNotEmpty() }.orEmpty(), formatDuration(duration))
        .filter { it.isNotBlank() }.joinToString(" • ").ifBlank { extra.orEmpty() }
    is AlbumItem -> subtitle.ifBlank { artists.joinToString(", ") { it.name } }
    is PlaylistItem -> subtitle
    is ArtistItem -> subtitle
    is NavItem -> ""
    is LocalPlaylistItem -> T("Плейлист • {0}", count)
}

@Composable
fun NowPlayingBars(modifier: Modifier = Modifier) {
    Icon(Icons.Rounded.GraphicEq, T("Зараз грає"), modifier, tint = Color.White)
}

/** Standard list row used everywhere (songs, albums, artists, playlists). */
@Composable
fun ItemRow(item: YTItem, onClick: () -> Unit, onMenu: (() -> Unit)?, modifier: Modifier = Modifier, index: Int? = null, trailing: @Composable (() -> Unit)? = null) {
    val current by Playback.current.collectAsState()
    val downloading by Downloads.progress.collectAsState()
    val playingThis = item is Song && current?.id == item.id
    val minimal = Pal.minimal
    Column(modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu).padding(start = 16.dp, end = 4.dp, top = if (minimal) 7.dp else 6.dp, bottom = if (minimal) 7.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (playingThis) NowPlayingBars(Modifier.size(20.dp)) else Text("${index}", color = Dim, fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
        } else {
            val shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(if (minimal) 6.dp else Pal.corner)
            Box(Modifier.size(if (minimal) 48.dp else 52.dp)) {
                Thumb(item.thumbnail, Modifier.fillMaxSize(), 160, shape, if (item is ArtistItem) Icons.Rounded.Person else Icons.Rounded.MusicNote)
                if (playingThis) Box(Modifier.fillMaxSize().clip(shape).background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) { NowPlayingBars(Modifier.size(22.dp)) }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (minimal) FontWeight.Normal else FontWeight.Medium, fontSize = if (minimal) 16.sp else 15.sp,
                color = if (minimal && playingThis) Pal.accent else Color.Unspecified)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if ((item as? Song)?.explicit == true || (item as? AlbumItem)?.explicit == true) ExplicitBadge()
                if (item is Song && downloading.containsKey(item.id)) Icon(Icons.Rounded.Downloading, null, Modifier.size(14.dp).padding(end = 3.dp), tint = Dim)
                Text(item.subtitleText(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 13.sp)
            }
        }
        trailing?.invoke()
        if (onMenu != null) IconButton(onClick = onMenu) { Icon(if (minimal) Icons.Rounded.MoreHoriz else Icons.Rounded.MoreVert, T("Більше"), tint = if (minimal) Color.White else Dim) }
    }
    // Minimal: hairline separators inset to the text, like a grouped list.
    if (minimal) HorizontalDivider(Modifier.padding(start = if (index != null) 56.dp else 78.dp), thickness = 0.5.dp, color = Pal.line)
    }
}

/** Minimal style's big rounded action button (grey, accent icon + label), e.g. Play / Shuffle under a header. */
@Composable
fun PillButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), color = Pal.chip) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = Pal.accent); Spacer(Modifier.width(6.dp))
            Text(label, color = Pal.accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
fun ExplicitBadge() {
    Box(Modifier.padding(end = 4.dp).size(14.dp).clip(RoundedCornerShape(2.dp)).background(Dim), contentAlignment = Alignment.Center) {
        Text("E", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Square / round card used in horizontal carousels. */
@Composable
fun ItemCard(item: YTItem, onClick: () -> Unit, onMenu: () -> Unit, width: Dp = 150.dp) {
    val round = item is ArtistItem
    val wide = item is Song && item.isVideo
    Column(Modifier.width(if (wide) width * 1.55f else width).combinedClickable(onClick = onClick, onLongClick = onMenu)) {
        Thumb(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f), 400,
            if (round) CircleShape else RoundedCornerShape(Pal.corner + 2.dp), if (round) Icons.Rounded.Person else Icons.Rounded.Album)
        Spacer(Modifier.height(8.dp))
        Text(item.title, maxLines = if (Pal.minimal) 1 else 2, overflow = TextOverflow.Ellipsis, fontWeight = if (Pal.minimal) FontWeight.Normal else FontWeight.Medium, fontSize = if (Pal.minimal) 13.sp else 14.sp,
            modifier = if (round) Modifier.fillMaxWidth() else Modifier, textAlign = if (round) androidx.compose.ui.text.style.TextAlign.Center else null)
        val sub = item.subtitleText()
        if (sub.isNotBlank()) Text(sub, maxLines = if (Pal.minimal) 1 else 2, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = if (Pal.minimal) 12.sp else 13.sp,
            modifier = if (round) Modifier.fillMaxWidth() else Modifier, textAlign = if (round) androidx.compose.ui.text.style.TextAlign.Center else null)
    }
}

@Composable
fun NavButton(item: NavItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val stripe = item.color?.let { Color(it.toInt()) } ?: Color(0xff555555)
    if (Pal.minimal) {
        // Minimal: a colourful category tile with the name in the bottom corner.
        Box(modifier.height(92.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(stripe, androidx.compose.ui.graphics.lerp(stripe, Color.Black, 0.45f)))).clickable(onClick = onClick)) {
            Text(item.title, Modifier.align(Alignment.BottomStart).padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp)
        }
        return
    }
    Row(modifier.height(48.dp).clip(RoundedCornerShape(6.dp)).background(Pal.chip).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
        Text(item.title, Modifier.padding(horizontal = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun ShelfHeader(title: String?, strapline: String? = null, onMore: (() -> Unit)? = null, onPlay: (() -> Unit)? = null, thumbnail: String? = null) {
    if (title == null && strapline == null) { Spacer(Modifier.height(8.dp)); return }
    if (Pal.minimal) {
        // Minimal: plain title with a chevron (tap the header for "more"), no outlined buttons.
        Row(Modifier.fillMaxWidth().then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (thumbnail != null) { Thumb(thumbnail, Modifier.size(36.dp), 120, CircleShape); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f, fill = false)) {
                if (strapline != null) Text(strapline.lowercase().replaceFirstChar { it.titlecase() }, color = Dim, fontSize = 13.sp)
                if (title != null) Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onMore != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, T("Більше"), tint = Dim)
            Spacer(Modifier.weight(0.001f))
            if (onPlay != null) IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, T("Відтворити все"), tint = Pal.accent) }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (thumbnail != null) { Thumb(thumbnail, Modifier.size(40.dp), 120, CircleShape); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            if (strapline != null) Text(strapline.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 0.6.sp)
            if (title != null) Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onPlay != null) OutlinedButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) { Text(T("Відтворити все"), fontSize = 12.sp) }
        if (onMore != null) OutlinedButton(onClick = onMore, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.padding(start = 6.dp).height(32.dp)) { Text(T("Більше"), fontSize = 12.sp) }
    }
}

/** Callbacks a shelf needs; provided by the screen. */
class ItemActions(
    val open: (YTItem, List<YTItem>) -> Unit,
    val menu: (YTItem) -> Unit,
    val endpoint: (Endpoint, String?) -> Unit,
)

@Composable
fun ShelfView(shelf: Shelf, actions: ItemActions, startIndex: Int = 0, numbered: Boolean = false) {
    val onMore = shelf.more?.let { e -> { actions.endpoint(e, shelf.title) } }
    val onPlay = shelf.play?.let { e -> { actions.endpoint(e, shelf.title) } }
    when (shelf.style) {
        ShelfStyle.QUICK_PICKS -> {
            ShelfHeader(shelf.title, shelf.strapline, onMore, onPlay)
            val pages = shelf.items.chunked(4)
            val pager = rememberPagerState { pages.size }
            HorizontalPager(pager, pageSize = PageSize.Fixed(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 48.dp),
                verticalAlignment = Alignment.Top) { p ->
                Column { pages[p].forEach { item -> ItemRow(item, { actions.open(item, shelf.items) }, { actions.menu(item) }) } }
            }
        }
        ShelfStyle.CAROUSEL -> {
            ShelfHeader(shelf.title, shelf.strapline, onMore, onPlay)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(shelf.items, key = { i, it -> "${i}:${it.key}" }) { _, item -> ItemCard(item, { actions.open(item, shelf.items) }, { actions.menu(item) }) }
            }
        }
        ShelfStyle.NAV_GRID -> {
            ShelfHeader(shelf.title, shelf.strapline, onMore)
            if (Pal.minimal) {
                // Minimal: "browse categories" — a two-column grid of big colourful tiles.
                shelf.items.filterIsInstance<NavItem>().chunked(2).forEach { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { NavButton(it, { actions.open(it, shelf.items) }, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                return
            }
            val rows = if (shelf.items.size > 8) 4 else 2
            LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.height((rows * 56).dp), contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(shelf.items, key = { i, it -> "${i}:${it.key}" }) { _, item -> (item as? NavItem)?.let { NavButton(it, { actions.open(it, shelf.items) }, Modifier.width(170.dp)) } }
            }
        }
        ShelfStyle.GRID -> {
            ShelfHeader(shelf.title, shelf.strapline, onMore)
            shelf.items.chunked(2).forEach { row ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { ItemCard(item, { actions.open(item, shelf.items) }, { actions.menu(item) }, width = 400.dp) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        ShelfStyle.TOP_RESULT -> {
            ShelfHeader(shelf.title ?: T("Найкращий результат"))
            val main = shelf.items.firstOrNull() ?: return
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                Row(Modifier.fillMaxWidth().clickable { actions.open(main, shelf.items) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(main.thumbnail, Modifier.size(72.dp), 200, if (main is ArtistItem) CircleShape else RoundedCornerShape(6.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(main.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(main.subtitleText(), color = Dim, fontSize = 13.sp, maxLines = 2)
                    }
                    IconButton(onClick = { actions.menu(main) }) { Icon(Icons.Rounded.MoreVert, T("Більше"), tint = Dim) }
                }
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        when (main) {
                            is Song -> Playback.playSong(main)
                            is ArtistItem -> actions.open(main, emptyList())
                            is AlbumItem -> main.playlistId?.let { Playback.playEndpoint(WatchEndpoint(playlistId = it), main.title) } ?: actions.open(main, emptyList())
                            is PlaylistItem -> main.playlistId?.let { Playback.playEndpoint(WatchEndpoint(playlistId = it), main.title) } ?: actions.open(main, emptyList())
                            else -> actions.open(main, emptyList())
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                        Icon(Icons.Rounded.PlayArrow, null); Text(if (main is ArtistItem) T("Відкрити") else T("Відтворити"))
                    }
                    if (main is Song) OutlinedButton(onClick = { Playback.startRadio(main) }) { Icon(Icons.Rounded.Sensors, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T("Радіо")) }
                }
            }
            shelf.items.drop(1).forEach { item -> ItemRow(item, { actions.open(item, shelf.items) }, { actions.menu(item) }) }
        }
        ShelfStyle.DESCRIPTION -> {
            ShelfHeader(shelf.title)
            ExpandableText(shelf.text.orEmpty(), Modifier.padding(horizontal = 16.dp))
        }
        ShelfStyle.LIST -> {
            ShelfHeader(shelf.title, shelf.strapline, onMore, onPlay)
            shelf.items.forEachIndexed { i, item ->
                ItemRow(item, { actions.open(item, shelf.items) }, { actions.menu(item) }, index = if (numbered) startIndex + i + 1 else null)
            }
        }
    }
}

@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 3) {
    var expanded by remember { mutableStateOf(false) }
    Text(text, modifier.clickable { expanded = !expanded }.animateContentSize(), color = Dim, fontSize = 14.sp,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun ChipsRow(chips: List<Pair<String, Boolean>>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips.size) { i ->
            val (title, selected) = chips[i]
            FilterChip(selected = selected, onClick = { onClick(i) }, label = { Text(title) }, shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = Pal.chip, labelColor = Color.White,
                    selectedContainerColor = Pal.accent, selectedLabelColor = Pal.onAccent),
                border = null)
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pal.accent, strokeWidth = 2.dp) }
}

@Composable
fun ErrorBox(message: String, retry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(48.dp), tint = Dim)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (retry != null) TextButton(onClick = retry) { Text(T("Повторити")) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = Dim)
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 14.sp)
    }
}

val HeaderGradient get() = Brush.verticalGradient(listOf(Color.Transparent, Pal.bg))

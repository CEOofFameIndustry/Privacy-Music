package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Pal

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.*
import com.example.privacymusic.net.LyricsRepo
import com.example.privacymusic.net.Parser
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.AppController
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.components.*
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberProgress(): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            pos = Playback.player.currentPosition
            dur = Playback.player.duration.takeIf { it > 0 } ?: ((Playback.current.value?.duration ?: 0) * 1000L)
            delay(400)
        }
    }
    return pos to dur
}

private fun time(ms: Long) = formatDuration((ms / 1000).toInt()).ifEmpty { "0:00" }

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val song by Playback.current.collectAsState()
    val playing by Playback.isPlaying.collectAsState()
    val buffering by Playback.buffering.collectAsState()
    val (pos, dur) = rememberProgress()
    val s = song ?: return
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    Column(Modifier.fillMaxWidth().background(if (Pal.minimal) Pal.card else Pal.chip).clickable(onClick = onOpen)
        // Up/down: drags the full player sheet with the finger. Sideways: previous / next track.
        .draggable(rememberDraggableState { app.dragSheet(it) }, Orientation.Vertical, onDragStarted = { app.startSheetDrag() }, onDragStopped = { app.releaseSheet(it) })
        .draggable(rememberDraggableState { d -> scope.launch { swipe.snapTo(swipe.value + d) } }, Orientation.Horizontal, onDragStopped = { v ->
            if (swipe.value < -120 || v < -1500) Playback.next() else if (swipe.value > 120 || v > 1500) Playback.previous()
            swipe.animateTo(0f, tween(220))
        })
        .graphicsLayer { translationX = swipe.value / 3 }) {
        if (!Pal.minimal) LinearProgressIndicator({ if (dur > 0) pos.toFloat() / dur else 0f }, Modifier.fillMaxWidth().height(2.dp), color = Pal.accent, trackColor = Pal.line, drawStopIndicator = {})
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(s.thumbnail, Modifier.size(44.dp), 160)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(s.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp)
            }
            if (!Pal.minimal) IconButton(onClick = Playback::previous) { Icon(Icons.Rounded.SkipPrevious, T("Попередня"), Modifier.size(28.dp)) }
            if (buffering && playing) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Pal.accent, strokeWidth = 2.dp) }
            else IconButton(onClick = Playback::toggle) {
                Crossfade(playing, animationSpec = tween(160), label = "pp") { Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(30.dp)) }
            }
            IconButton(onClick = Playback::next) { Icon(if (Pal.minimal) Icons.Rounded.FastForward else Icons.Rounded.SkipNext, T("Наступна"), Modifier.size(28.dp)) }
        }
    }
}

private enum class PlayerTab(private val raw: String) { QUEUE("Далі"), LYRICS("Текст"), RELATED("Схожі") ; val label: String get() = T(raw) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val song by Playback.current.collectAsState()
    val playing by Playback.isPlaying.collectAsState()
    val buffering by Playback.buffering.collectAsState()
    val shuffle by Playback.shuffle.collectAsState()
    val repeat by Playback.repeat.collectAsState()
    val queueTitle by Playback.queueTitle.collectAsState()
    val s = song ?: return
    val liked by remember(s.id) { Library.observe { Library.isLiked(s.id) } }.collectAsState(false)
    val disliked by remember(s.id) { Library.observe { Library.isDisliked(s.id) } }.collectAsState(false)
    var tab by remember { mutableStateOf<PlayerTab?>(null) }
    var extras by remember { mutableStateOf(false) }
    val (pos, dur) = rememberProgress()
    var seeking by remember { mutableStateOf<Float?>(null) }

    fun like(value: Boolean?) {
        app.scope.launch(Dispatchers.IO) {
            when (value) { true -> Library.setLiked(s, !liked); false -> Library.setDisliked(s, !disliked); null -> {} }
        }
        if (value == false && !disliked) Playback.next()
        if (Settings.accountSync.value && AccountStore.signedIn.value && !s.isLocal) app.scope.launch {
            runCatching { YouTubeMusic.like(s.id, if (value == true) (if (liked) null else true) else (if (disliked) null else false)) }
        }
    }

    val dragScope = rememberCoroutineScope()
    fun repeatLabel(mode: Int) = when (mode) { Player.REPEAT_MODE_ALL -> T("Повторювати чергу / плейлист"); Player.REPEAT_MODE_ONE -> T("Повторювати цей трек"); else -> T("Повтор вимкнено") }
    if (Pal.minimal) MinimalPlayer(s, playing, buffering, liked, pos, dur, onClose, like = { like(true) }, openTab = { tab = it }, openExtras = { extras = true })
    else Box(Modifier.fillMaxSize().background(Pal.bg).clickable(enabled = false) {}
        .draggable(rememberDraggableState { app.dragSheet(it) }, Orientation.Vertical, onDragStarted = { app.startSheetDrag() }, onDragStopped = { app.releaseSheet(it) })) {
        AsyncImage(Parser.resize(s.thumbnail, 60), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Pal.bg.copy(alpha = .55f), Pal.bg.copy(alpha = .85f), Pal.bg))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, T("Згорнути"), Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (queueTitle != null) {
                        Text(T("ВІДТВОРЕННЯ З"), fontSize = 10.sp, color = Dim, letterSpacing = 1.sp)
                        Text(queueTitle!!, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                DevicesButton()
                IconButton(onClick = { app.menu = AppController.MenuRequest(s) }) { Icon(Icons.Rounded.MoreVert, T("Більше")) }
            }
            Spacer(Modifier.weight(0.4f))
            val swipe = remember { Animatable(0f) }
            Thumb(s.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { translationX = swipe.value / 2; alpha = 1f - (kotlin.math.abs(swipe.value) / 1500f).coerceAtMost(0.5f) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = {
                        dragScope.launch { if (swipe.value < -150) Playback.next() else if (swipe.value > 150) Playback.previous(); swipe.animateTo(0f, tween(260)) }
                    }, onDragCancel = { dragScope.launch { swipe.animateTo(0f, tween(200)) } }) { change, d -> change.consume(); dragScope.launch { swipe.snapTo(swipe.value + d) } }
                }, 1080, RoundedCornerShape(8.dp))
            Spacer(Modifier.weight(0.4f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Row {
                        if (s.artists.isEmpty()) Text(s.artistText, color = Dim, fontSize = 16.sp, maxLines = 1)
                        s.artists.forEachIndexed { i, a ->
                            if (i > 0) Text(", ", color = Dim, fontSize = 16.sp)
                            Text(a.name, Modifier.clickable(enabled = a.id != null) { a.id?.let { onClose(); app.browse(it) } }, color = Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = { like(true) }) { Icon(if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, T("Подобається"), tint = if (liked) Pal.accent else Color.White) }
                if (!s.isLocal) {
                    IconButton(onClick = { like(false) }) { Icon(if (disliked) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, T("Не подобається")) }
                }
            }
            LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ActionChip(Icons.Rounded.PlaylistAdd, T("Зберегти")) { app.addToPlaylist = listOf(s) } }
                if (!s.isLocal) item { ActionChip(Icons.Rounded.Share, T("Поділитися")) { shareText(context, "https://music.youtube.com/watch?v=${s.id}") } }
                if (!s.isLocal) item { ActionChip(Icons.Rounded.Download, T("Завантажити")) { Downloads.download(s); app.toast(T("Завантаження розпочато")) } }
                if (!s.isLocal) item { ActionChip(Icons.Rounded.Sensors, T("Радіо")) { Playback.startRadio(s) } }
                item { ActionChip(Icons.Rounded.Bedtime, T("Таймер сну")) { extras = true } }
            }
            Spacer(Modifier.height(6.dp))
            Slider(value = seeking ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { Playback.seek((it * dur).toLong()) }; seeking = null },
                colors = SliderDefaults.colors(thumbColor = Pal.accent, activeTrackColor = Pal.accent, inactiveTrackColor = Color.White.copy(alpha = .25f)))
            Row(Modifier.fillMaxWidth()) {
                Text(time(seeking?.let { (it * dur).toLong() } ?: pos), color = Dim, fontSize = 12.sp)
                Spacer(Modifier.weight(1f)); Text(time(dur), color = Dim, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Playback.toggleShuffle(); app.toast(if (!shuffle) T("Перемішування увімкнено") else T("Перемішування вимкнено")) }) {
                    Icon(Icons.Rounded.Shuffle, T("Перемішати"), tint = if (shuffle) Pal.accent else Dim)
                }
                IconButton(onClick = Playback::previous, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, T("Попередня"), Modifier.size(40.dp)) }
                FilledIconButton(onClick = Playback::toggle, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                    if (buffering && playing) CircularProgressIndicator(Modifier.size(32.dp), color = Pal.onAccent, strokeWidth = 3.dp)
                    else Crossfade(playing, animationSpec = tween(160), label = "pp2") { Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(44.dp)) }
                }
                IconButton(onClick = Playback::next, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, T("Наступна"), Modifier.size(40.dp)) }
                IconButton(onClick = { Playback.cycleRepeat(); app.toast(repeatLabel(Playback.repeat.value)) }) {
                    Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, T("Повтор"), tint = if (repeat != Player.REPEAT_MODE_OFF) Pal.accent else Dim)
                }
            }
            // Swipe up here (or tap) for Up next / Lyrics / Related, like in YouTube Music.
            Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(onDragEnd = { if (acc < -40) tab = PlayerTab.QUEUE; acc = 0f }) { change, d -> change.consume(); acc += d }
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 4.dp).size(36.dp, 4.dp).background(Color.White.copy(alpha = .3f), RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PlayerTab.entries.forEach { t -> TextButton(onClick = { tab = t }) { Text(t.label.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) } }
                    IconButton(onClick = { extras = true }) { Icon(Icons.Rounded.Tune, T("Додатково")) }
                }
            }
        }
    }
    AnimatedVisibility(tab != null, enter = slideInVertically(tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it / 3 } + fadeIn(tween(240)),
        exit = slideOutVertically(tween(240)) { it / 3 } + fadeOut(tween(200))) {
        var shownTab by remember { mutableStateOf(tab ?: PlayerTab.QUEUE) }
        LaunchedEffect(tab) { tab?.let { shownTab = it } }
        NowPlayingPanel(s, shownTab, { shownTab = it; tab = it }, onClose = { tab = null }, closePlayer = { tab = null; onClose() })
    }
    if (extras) ExtrasSheet(s, onDismiss = { extras = false }, openEq = {
        runCatching {
            context.startActivity(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, Playback.player.audioSessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC))
        }.onFailure { app.toast(T("Системний еквалайзер недоступний")) }
    })
}

/** Minimal player: blurred artwork backdrop, artwork that settles back when paused, slim seek bar, plain controls.
 *  Shuffle / repeat live in the Up next panel; lyrics, timer and queue are the three icons at the bottom. */
@Composable
private fun MinimalPlayer(s: Song, playing: Boolean, buffering: Boolean, liked: Boolean, pos: Long, dur: Long, onClose: () -> Unit,
                          like: () -> Unit, openTab: (PlayerTab) -> Unit, openExtras: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val art by animateFloatAsState(if (playing || buffering) 1f else 0.84f, spring(dampingRatio = 0.7f, stiffness = 220f), label = "art")
    Box(Modifier.fillMaxSize().background(Pal.bg).clickable(enabled = false) {}
        .draggable(rememberDraggableState { app.dragSheet(it) }, Orientation.Vertical, onDragStarted = { app.startSheetDrag() }, onDragStopped = { app.releaseSheet(it) })) {
        AsyncImage(Parser.resize(s.thumbnail, 120), null, Modifier.fillMaxSize().blur(70.dp), contentScale = ContentScale.Crop, alpha = 0.9f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .6f)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp)) {
            Box(Modifier.fillMaxWidth().height(34.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 5.dp).background(Color.White.copy(alpha = .45f), RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.weight(0.5f))
            Thumb(s.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f)
                .graphicsLayer { scaleX = art; scaleY = art; translationX = swipe.value / 2 }
                .shadow(if (playing) 28.dp else 10.dp, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = {
                        scope.launch { if (swipe.value < -150) Playback.next() else if (swipe.value > 150) Playback.previous(); swipe.animateTo(0f, tween(260)) }
                    }, onDragCancel = { scope.launch { swipe.animateTo(0f, tween(200)) } }) { change, d -> change.consume(); scope.launch { swipe.snapTo(swipe.value + d) } }
                }, 1080, RoundedCornerShape(12.dp))
            Spacer(Modifier.weight(0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(s.artistText, Modifier.clickable(enabled = s.artists.firstOrNull()?.id != null) { s.artists.firstOrNull()?.id?.let { onClose(); app.browse(it) } },
                        color = Color.White.copy(alpha = .65f), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = like) { Icon(if (liked) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, T("Подобається"), tint = if (liked) Pal.accent else Color.White) }
                IconButton(onClick = { app.menu = AppController.MenuRequest(s) }, Modifier.size(34.dp).background(Color.White.copy(alpha = .15f), CircleShape)) {
                    Icon(Icons.Rounded.MoreHoriz, T("Більше"), Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            SlimSeekBar(pos, dur)
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = Playback::previous, Modifier.size(64.dp)) { Icon(Icons.Rounded.FastRewind, T("Попередня"), Modifier.size(44.dp)) }
                IconButton(onClick = Playback::toggle, Modifier.size(80.dp)) {
                    if (buffering && playing) CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                    else Crossfade(playing, animationSpec = tween(160), label = "mpp") { Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(60.dp)) }
                }
                IconButton(onClick = Playback::next, Modifier.size(64.dp)) { Icon(Icons.Rounded.FastForward, T("Наступна"), Modifier.size(44.dp)) }
            }
            Spacer(Modifier.weight(0.3f))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onClick = { openTab(PlayerTab.LYRICS) }) { Icon(Icons.Rounded.Lyrics, PlayerTab.LYRICS.label, tint = Color.White.copy(alpha = .75f)) }
                IconButton(onClick = openExtras) { Icon(Icons.Rounded.Bedtime, T("Таймер сну"), tint = Color.White.copy(alpha = .75f)) }
                DevicesButton(tint = Color.White.copy(alpha = .75f))
                IconButton(onClick = { openTab(PlayerTab.QUEUE) }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, PlayerTab.QUEUE.label, tint = Color.White.copy(alpha = .75f)) }
            }
        }
    }
}

/** Thin rounded seek bar that thickens while you drag it; tap anywhere to jump. */
@Composable
private fun SlimSeekBar(pos: Long, dur: Long) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val h by androidx.compose.animation.core.animateDpAsState(if (drag != null) 11.dp else 6.dp, label = "seek")
    Box(Modifier.fillMaxWidth().height(26.dp)
        .pointerInput(dur) {
            detectHorizontalDragGestures(onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { drag?.let { Playback.seek((it * dur).toLong()) }; drag = null }, onDragCancel = { drag = null }) { change, d ->
                change.consume(); drag = ((drag ?: 0f) + d / size.width).coerceIn(0f, 1f)
            }
        }
        .pointerInput(dur) { detectTapGestures { Playback.seek(((it.x / size.width).coerceIn(0f, 1f) * dur).toLong()) } },
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .22f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White.copy(alpha = if (drag != null) 1f else .8f)))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(time(drag?.let { (it * dur).toLong() } ?: pos), color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text("-" + time((dur - (drag?.let { (it * dur).toLong() } ?: pos)).coerceAtLeast(0)), color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
    }
}

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = .12f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun QueueView(onClosePlayer: () -> Unit) {
    val app = LocalApp.current
    val queue by Playback.queue.collectAsState()
    val index by Playback.index.collectAsState()
    val autoplay by Settings.autoplay.state.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (index > 0) list.scrollToItem((index - 1).coerceAtLeast(0)) }
    LazyColumn(state = list) {
        if (Pal.minimal) item {
            // Minimal: "Up next" title with three square toggles (shuffle, repeat, autoplay).
            val shuffle by Playback.shuffle.collectAsState()
            val repeat by Playback.repeat.collectAsState()
            val title by Playback.queueTitle.collectAsState()
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(T("Далі"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (title != null) Text(T("Відтворення з") + " " + title, color = Color.White.copy(alpha = .6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                @Composable fun square(on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
                    Box(Modifier.padding(start = 8.dp).size(38.dp, 32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Color.White.copy(alpha = .85f) else Color.White.copy(alpha = .14f))
                        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(20.dp), tint = if (on) Color.Black else Color.White) }
                square(shuffle, Icons.Rounded.Shuffle, T("Перемішати"), Playback::toggleShuffle)
                square(repeat != Player.REPEAT_MODE_OFF, if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, T("Повтор"), Playback::cycleRepeat)
                square(autoplay, Icons.Rounded.AllInclusive, T("Автовідтворення")) { Settings.autoplay.set(!autoplay) }
            }
        } else item {
            val shuffle by Playback.shuffle.collectAsState()
            val repeat by Playback.repeat.collectAsState()
            val title by Playback.queueTitle.collectAsState()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(T("Відтворення з"), color = Dim, fontSize = 12.sp)
                    Text(title ?: T("Черга"), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = Playback::toggleShuffle) { Icon(Icons.Rounded.Shuffle, T("Перемішати"), tint = if (shuffle) Pal.accent else Dim) }
                IconButton(onClick = Playback::cycleRepeat) {
                    Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, T("Повтор"), tint = if (repeat != Player.REPEAT_MODE_OFF) Pal.accent else Dim)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(T("Автовідтворення"), fontSize = 14.sp)
                    Text(T("Додавати схожі треки, коли черга закінчується"), color = Dim, fontSize = 12.sp)
                }
                Switch(autoplay, { Settings.autoplay.set(it) })
            }
        }
        itemsIndexed(queue, key = { i, s -> "${i}:${s.id}" }) { i, s ->
            Box(Modifier.background(if (i == index) Pal.chip else Color.Transparent)) {
                ItemRow(s, { Playback.skipTo(i) }, { app.menu = AppController.MenuRequest(s, queueIndex = i) })
            }
        }
        item {
            TextButton(onClick = { Playback.clearUpcoming() }, Modifier.padding(start = 16.dp)) { Icon(Icons.Rounded.ClearAll, null); Spacer(Modifier.width(8.dp)); Text(T("Очистити наступні")) }
            TextButton(onClick = { app.addToPlaylist = queue }, Modifier.padding(16.dp)) { Icon(Icons.Rounded.PlaylistAdd, null); Spacer(Modifier.width(8.dp)); Text(T("Зберегти чергу як плейлист")) }
        }
    }
}

@Composable
private fun RelatedView(song: Song, onNavigate: () -> Unit) {
    val app = LocalApp.current
    var page by remember(song.id) { mutableStateOf<Page?>(null) }
    var state by remember(song.id) { mutableStateOf("loading") }
    LaunchedEffect(song.id) {
        if (song.isLocal) { state = "none"; return@LaunchedEffect }
        try {
            val id = YouTubeMusic.next(WatchEndpoint(song.id)).relatedBrowseId
            page = id?.let { YouTubeMusic.browse(it) }
            state = if (page == null) "none" else "ok"
        } catch (e: CancellationException) { throw e } catch (_: Exception) { state = "error" }
    }
    val actions = remember {
        ItemActions({ item, _ -> if (item !is Song) onNavigate(); app.open(item) }, { app.menu = AppController.MenuRequest(it) }, { e, t -> onNavigate(); app.endpoint(e, t) })
    }
    when (state) {
        "loading" -> Loading()
        "none" -> EmptyState(Icons.Rounded.Explore, T("Немає схожого"), "")
        "error" -> ErrorBox(T("Не вдалося завантажити"), null)
        else -> LazyColumn { page!!.shelves.forEach { shelf -> item { ShelfView(shelf, actions) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtrasSheet(song: Song, onDismiss: () -> Unit, openEq: () -> Unit) {
    val sleepAt by Playback.sleepAt.collectAsState()
    val endOfSong by Playback.sleepEndOfSong.collectAsState()
    val skipSilence by Settings.skipSilence.state.collectAsState()
    var speed by remember { mutableFloatStateOf(Playback.player.playbackParameters.speed) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pal.card) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(T("Таймер сну"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(when { endOfSong -> T("Зупинка після цієї пісні"); sleepAt != null -> T("Зупинка о ") + java.text.SimpleDateFormat("HH:mm").format(java.util.Date(sleepAt!!)); else -> T("Вимкнено") }, color = Dim, fontSize = 13.sp)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60).forEach { m -> AssistChip({ Playback.sleep(m) }, { Text(T("{0} хв", m)) }) }
                AssistChip({ Playback.sleep(-1) }, { Text(T("Кінець пісні")) })
            }
            if (sleepAt != null || endOfSong) TextButton(onClick = { Playback.sleep(null) }) { Text(T("Вимкнути таймер")) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Pal.chip)
            Text(T("Швидкість: {0}×", "%.2f".format(speed)), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Slider(speed, { speed = (it * 20).toInt() / 20f; Playback.setSpeed(speed) }, valueRange = 0.5f..2f, steps = 29)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T("Пропускати тишу"), Modifier.weight(1f)); Switch(skipSilence, { Playback.setSkipSilence(it) })
            }
            TextButton(onClick = openEq) { Icon(Icons.Rounded.Equalizer, null); Spacer(Modifier.width(8.dp)); Text(T("Еквалайзер")) }
            if (!song.isLocal) TextButton(onClick = { Playback.startRadio(song); onDismiss() }) { Icon(Icons.Rounded.Sensors, null); Spacer(Modifier.width(8.dp)); Text(T("Почати радіо з цієї пісні")) }
            if (!song.isLocal) TextButton(onClick = { Downloads.download(song); onDismiss() }) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text(T("Завантажити")) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Full-screen "Далі / Текст / Схожі" panel
// ---------------------------------------------------------------------------------------------

@Composable
private fun NowPlayingPanel(song: Song, tab: PlayerTab, onTab: (PlayerTab) -> Unit, onClose: () -> Unit, closePlayer: () -> Unit) {
    BackHandler(onBack = onClose)
    if (Pal.minimal) {
        // Minimal: same blurred backdrop as the player, a small header, and icon toggles at the bottom instead of tabs.
        val playing by Playback.isPlaying.collectAsState()
        val (pos, dur) = rememberProgress()
        Box(Modifier.fillMaxSize().background(Pal.bg).clickable(enabled = false) {}) {
            AsyncImage(Parser.resize(song.thumbnail, 120), null, Modifier.fillMaxSize().blur(70.dp), contentScale = ContentScale.Crop, alpha = 0.9f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .4f), Color.Black.copy(alpha = .65f)))))
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(30.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp, 5.dp).background(Color.White.copy(alpha = .45f), RoundedCornerShape(3.dp)))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(song.thumbnail, Modifier.size(54.dp), 160, RoundedCornerShape(6.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(song.artistText, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Crossfade(tab, animationSpec = tween(220), label = "panel") { t ->
                        when (t) {
                            PlayerTab.QUEUE -> QueueView(closePlayer)
                            PlayerTab.LYRICS -> LyricsPane(song)
                            PlayerTab.RELATED -> RelatedView(song) { closePlayer() }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 26.dp)) {
                    SlimSeekBar(pos, dur)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = Playback::previous, Modifier.size(56.dp)) { Icon(Icons.Rounded.FastRewind, T("Попередня"), Modifier.size(38.dp)) }
                        IconButton(onClick = Playback::toggle, Modifier.size(64.dp)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(48.dp)) }
                        IconButton(onClick = Playback::next, Modifier.size(56.dp)) { Icon(Icons.Rounded.FastForward, T("Наступна"), Modifier.size(38.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf(PlayerTab.LYRICS to Icons.Rounded.Lyrics, PlayerTab.RELATED to Icons.Rounded.Explore, PlayerTab.QUEUE to Icons.AutoMirrored.Rounded.QueueMusic).forEach { (t, icon) ->
                        val on = tab == t
                        IconButton(onClick = { if (on) onClose() else onTab(t) }, Modifier.background(if (on) Color.White.copy(alpha = .9f) else Color.Transparent, CircleShape)) {
                            Icon(icon, t.label, tint = if (on) Color.Black else Color.White.copy(alpha = .75f))
                        }
                    }
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Pal.bg).clickable(enabled = false) {}) {
        AsyncImage(Parser.resize(song.thumbnail, 60), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.5f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Pal.bg.copy(alpha = .5f), Pal.bg.copy(alpha = .88f), Pal.bg))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, T("Згорнути"), Modifier.size(30.dp)) }
                Thumb(song.thumbnail, Modifier.size(40.dp), 120)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    Text(song.artistText, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
            }
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, contentColor = Color.White, indicator = {
                TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(tab.ordinal), color = Pal.accent)
            }) { PlayerTab.entries.forEach { e -> Tab(tab == e, { onTab(e) }, text = { Text(e.label) }) } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(tab, animationSpec = tween(220), label = "panel") { t ->
                    when (t) {
                        PlayerTab.QUEUE -> QueueView(closePlayer)
                        PlayerTab.LYRICS -> LyricsPane(song)
                        PlayerTab.RELATED -> RelatedView(song) { closePlayer() }
                    }
                }
            }
            if (tab == PlayerTab.LYRICS) LyricsControls()
        }
    }
}

@Composable
private fun rememberPosition(): State<Long> = produceState(Playback.player.currentPosition) {
    // A smooth playback clock for lyrics. The player's own position jitters by tens of ms from frame to frame
    // (and is extrapolated between audio timestamps), which made the fill stutter. This clock advances by the real
    // frame time and is gently steered toward the player (never jumps, never runs backwards); only a real seek or
    // a big drift snaps it.
    var clock = Playback.player.currentPosition.toDouble()
    var last = 0L
    while (isActive) withFrameNanos { now ->
        val p = Playback.player
        val reported = p.currentPosition.toDouble()
        if (!p.isPlaying || last == 0L) { clock = reported; last = now; value = reported.toLong(); return@withFrameNanos }
        val dt = (now - last) / 1_000_000.0 * p.playbackParameters.speed
        last = now
        clock += dt
        val err = reported - clock
        clock = if (kotlin.math.abs(err) > 600) reported else clock + (err * 0.06).coerceAtLeast(-dt * 0.5)
        value = clock.toLong()
    }
}

@Composable
private fun LyricsControls() {
    val playing by Playback.isPlaying.collectAsState()
    val (pos, dur) = rememberProgress()
    var seeking by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Slider(value = seeking ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { Playback.seek((it * dur).toLong()) }; seeking = null },
            colors = SliderDefaults.colors(thumbColor = Pal.accent, activeTrackColor = Pal.accent, inactiveTrackColor = Color.White.copy(alpha = .25f)))
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(time(seeking?.let { (it * dur).toLong() } ?: pos), color = Dim, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            IconButton(onClick = Playback::previous) { Icon(Icons.Rounded.SkipPrevious, T("Попередня"), Modifier.size(34.dp)) }
            FilledIconButton(onClick = Playback::toggle, Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Pal.accent, contentColor = Pal.onAccent)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, T("Відтворити/пауза"), Modifier.size(34.dp))
            }
            IconButton(onClick = Playback::next) { Icon(Icons.Rounded.SkipNext, T("Наступна"), Modifier.size(34.dp)) }
            Text(time(dur), color = Dim, fontSize = 12.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        }
    }
}

/** Spotify-style lyrics: the line being sung is bright and centred, other lines are dimmed; tap a line to jump to it. */
@Composable
private fun LyricsPane(song: Song) {
    val lrclib by Settings.lrclib.state.collectAsState()
    val hintHidden by Settings.lrclibHintHidden.state.collectAsState()
    var lyrics by remember(song.id, lrclib) { mutableStateOf<Lyrics?>(null) }
    var state by remember(song.id, lrclib) { mutableStateOf("loading") }
    LaunchedEffect(song.id, lrclib) {
        try { lyrics = LyricsRepo.get(song); state = if (lyrics == null) "none" else "ok" }
        catch (e: CancellationException) { throw e } catch (_: Exception) { state = "error" }
    }
    val l = lyrics
    val synced = l?.synced
    Column(Modifier.fillMaxSize()) {
        // One tap to get live lyrics: it's opt-in because it needs one request to lrclib.net.
        if (!lrclib && !hintHidden && !song.isLocal && state != "loading") Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Pal.card.copy(alpha = .9f))) {
            Column(Modifier.padding(14.dp)) {
                Text(T("Текст у такт музиці"), fontWeight = FontWeight.Bold)
                Text(T("Щоб рядки підсвічувалися в момент виконання, як у Spotify, потрібен запит до відкритої бази LRCLIB (lrclib.net): ") +
                    T("надсилаються лише назва, виконавець і тривалість — без акаунта й ідентифікаторів."), color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Row {
                    TextButton(onClick = { LyricsRepo.clear(); Settings.lrclib.set(true) }) { Text(T("Увімкнути синхронізацію")) }
                    // Closed once = never shown again (it can still be turned on in Settings → Privacy).
                    TextButton(onClick = { Settings.lrclibHintHidden.set(true) }) { Text(T("Більше не показувати"), color = Dim) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state == "loading" -> Loading(Modifier.align(Alignment.Center))
                state == "none" -> EmptyState(Icons.Rounded.Lyrics, T("Текст недоступний"), if (song.isLocal) T("Для локальних файлів текст не завантажується.") else T("Для цієї пісні немає тексту."))
                state == "error" -> ErrorBox(T("Не вдалося завантажити текст"), null)
                synced != null -> SyncedLyrics(synced, l.source)
                else -> PlainLyrics(l!!)
            }
        }
    }
}

private const val LYRIC_LEAD = 80L
private const val LYRIC_GLIDE = 450L

@Composable
private fun SyncedLyrics(lines: List<SyncedLine>, source: String) {
    val clock = rememberPosition()
    val offset by Settings.lyricsOffset.state.collectAsState()
    val position = remember { derivedStateOf { clock.value - Settings.lyricsOffset.value } }
    val flow by Settings.lyricsFlow.state.collectAsState()
    val pace = remember(lines) { Karaoke.pace(lines) }
    // A line lights LEAD ms early (the eye needs a moment); the scroll aims GLIDE ms ahead so it starts early and
    // lands exactly as the line begins, instead of starting when the line is already being sung.
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.time <= position.value + LYRIC_LEAD } } }
    val aim by remember(lines) { derivedStateOf { lines.indexOfLast { it.time <= position.value + LYRIC_GLIDE } } }
    val list = rememberLazyListState()
    val lastUser = remember { longArrayOf(0L) }
    val programmatic = remember { booleanArrayOf(false) }
    Column(Modifier.fillMaxSize()) {
    // Quick switch (same as Settings → Content), in its own row so it never covers the words.
    Row(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .35f)).padding(3.dp)) {
        listOf(false to T("Рядками"), true to T("Поступово")).forEach { (v, label) ->
            val on = flow == v
            Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White.copy(alpha = .9f) else Color.Transparent)
                .clickable { Settings.lyricsFlow.set(v) }.padding(horizontal = 14.dp, vertical = 6.dp),
                color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        // Text earlier / later (50 ms steps): for headphones or speakers that delay the sound.
        Spacer(Modifier.width(6.dp))
        Text("−", Modifier.clip(RoundedCornerShape(50)).clickable { Settings.lyricsOffset.set((offset - 50).coerceAtLeast(-2000)) }.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("%+.2f".format(offset / 1000.0) + T(" с"), Modifier.padding(vertical = 6.dp), color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
        Text("+", Modifier.clip(RoundedCornerShape(50)).clickable { Settings.lyricsOffset.set((offset + 50).coerceAtMost(2000)) }.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(Unit) { snapshotFlow { list.isScrollInProgress }.collect { if (it && !programmatic[0]) lastUser[0] = System.currentTimeMillis() } }
        LaunchedEffect(aim) {
            val active = aim
            if (active < 0 || System.currentTimeMillis() - lastUser[0] < 3500) return@LaunchedEffect
            // Arrive when the line starts (or quickly, if it already has).
            val ms = (lines[active].time - position.value).coerceIn(200L, LYRIC_GLIDE).toInt()
            programmatic[0] = true
            // The current line sits about a third of the way down (the list's top padding), like Spotify.
            try {
                // Glide (not jump) the new line into place when it's already on screen.
                val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active + 1 }
                // Same resting place as animateScrollToItem(active + 1, -5% of height): offsets count from the content padding.
                val want = -heightPx * 0.05f
                if (target != null) list.animateScrollBy(target.offset - want, tween(ms, easing = FastOutSlowInEasing))
                else list.animateScrollToItem(active + 1, -(heightPx * 0.05f).toInt())
            } finally { programmatic[0] = false }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.55f)) {
            item(key = "pad") { Spacer(Modifier.height(1.dp)) }
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                LyricLine(lines, i, i == active, i < active, position, flow, pace) { lastUser[0] = 0; Playback.seek(line.time) }
            }
            item(key = "src") { Text(T("Текст: {0}", source), Modifier.padding(24.dp), color = Dim, fontSize = 12.sp) }
        }
    }
    }
}

/** One lyric line: the current one grows slightly. In "progressive" mode it fills as it's sung (exactly, when the
 *  lyrics carry word timings); in "by lines" mode the whole line lights up at once. The fill is a soft-edged wipe
 *  drawn in the draw phase: no re-composition or text re-layout per frame, so it stays perfectly smooth. */
@Composable
private fun LyricLine(lines: List<SyncedLine>, i: Int, isActive: Boolean, isPast: Boolean, position: State<Long>, flow: Boolean, pace: Double, onClick: () -> Unit) {
    val line = lines[i]
    val scale by animateFloatAsState(if (isActive) 1.07f else 1f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "scale")
    val filling = isActive && flow && line.text.isNotBlank()
    val base by animateColorAsState(when { isActive && !filling -> Color.White; isPast -> Color.White.copy(alpha = .5f); else -> Color.White.copy(alpha = .36f) },
        tween(180), label = "line")
    val mod = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 9.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
    val style = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
    if (!filling) { Text(line.text.ifBlank { "♪" }, mod, color = base, style = style); return }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // Cumulative glyph widths (len + 1 entries), computed once per layout.
    val cum = remember(layout) {
        layout?.let { l -> DoubleArray(line.text.length + 1).also { a ->
            for (c in line.text.indices) a[c + 1] = a[c] + l.getBoundingBox(c).width.coerceAtLeast(0f)
        } }
    }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    Box(mod) {
        Text(line.text, color = Color.White.copy(alpha = .42f), style = style)
        Text(line.text, Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                val l = layout ?: return@drawWithContent
                // Read here (draw phase only): each frame just redraws this one line.
                val c = cum ?: return@drawWithContent
                val len = line.text.length
                // Even pixel speed: progress is measured in glyph-width units, then turned back into an x position.
                val w = Karaoke.flow(lines, i, position.value, pace, c)
                drawContent()
                if (w >= c[len]) return@drawWithContent
                var k = java.util.Arrays.binarySearch(c, w).let { if (it >= 0) it else -it - 2 }.coerceIn(0, len - 1)
                while (k < len - 1 && c[k + 1] <= w) k++
                val box = l.getBoundingBox(k)
                val x = (box.left + (w - c[k]).toFloat()).coerceAtMost(box.right)
                val cur = l.getLineForOffset(k)
                for (n in 0 until l.lineCount) {
                    val top = l.getLineTop(n); val bottom = l.getLineBottom(n)
                    when {
                        n < cur -> {}
                        n > cur -> drawRect(Color.Transparent, androidx.compose.ui.geometry.Offset(0f, top),
                            androidx.compose.ui.geometry.Size(size.width, bottom - top), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                        else -> {
                            // Soft edge: fully lit up to x, fading out over `edge` pixels after it.
                            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = x, endX = x + edge),
                                androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Size(edge, bottom - top),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                            drawRect(Color.Transparent, androidx.compose.ui.geometry.Offset(x + edge, top),
                                androidx.compose.ui.geometry.Size((size.width - x - edge).coerceAtLeast(0f), bottom - top), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                        }
                    }
                }
            }, color = Color.White, style = style, onTextLayout = { layout = it })
    }
}

@Composable
private fun PlainLyrics(l: Lyrics) {
    val lines = remember(l) { l.text.lines() }
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        itemsIndexed(lines, key = { i, _ -> i }) { _, line ->
            if (line.isBlank()) Spacer(Modifier.height(18.dp))
            else Text(line, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 3.dp))
        }
        item { Text(T("Текст: {0}", l.source), Modifier.padding(top = 20.dp), color = Dim, fontSize = 12.sp) }
    }
}

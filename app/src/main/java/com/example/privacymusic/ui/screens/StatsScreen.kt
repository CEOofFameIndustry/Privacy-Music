package com.example.privacymusic.ui.screens

import com.example.privacymusic.i18n.T

import com.example.privacymusic.ui.theme.Danger

import com.example.privacymusic.ui.theme.Pal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacymusic.data.*
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.ui.LocalApp
import com.example.privacymusic.ui.PlayContext
import com.example.privacymusic.ui.components.*
import com.example.privacymusic.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

private enum class Range(private val raw: String, val days: Int?) { WEEK("7 днів", 7), MONTH("4 тижні", 28), HALF("6 місяців", 182), YEAR("Рік", 365), ALL("Весь час", null) ; val label: String get() = T(raw) }

private typealias Ranked = StatsCalc.Ranked

private fun compute(range: Range): StatsCalc.Stats {
    val since = range.days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it.toLong()) } ?: 0L
    val st = StatsCalc.compute(Library.plays(since), range.days, System.currentTimeMillis(), if (since > 0) Library.firstPlayOfArtists() else emptyMap())
    val photos = Library.artistImages()
    return st.copy(topArtists = st.topArtists.map { a -> a.id?.let(photos::get)?.let { a.copy(thumbnail = it) } ?: a })
}

private fun minutes(ms: Long) = ms / 60_000
private fun prettyMinutes(ms: Long): String { val m = minutes(ms); return if (ms in 1 until 60_000) T("< 1 хв") else if (m >= 120) T("{0} год {1} хв", m / 60, m % 60) else T("{0} хв", m) }

@Composable
fun StatsScreen() {
    val app = LocalApp.current
    val enabled by Settings.stats.state.collectAsState()
    var range by remember { mutableStateOf(Range.MONTH) }
    val stats by remember(range) { Library.observe { compute(range) } }.collectAsState(null)
    var confirm by remember { mutableStateOf(false) }
    // Real artist photos instead of album covers: fetched once per artist, then cached in the local database.
    val missing = stats?.topArtists?.mapNotNull { it.id }?.filter { it.startsWith("UC") }.orEmpty()
    LaunchedEffect(missing) {
        val known = kotlinx.coroutines.withContext(Dispatchers.IO) { Library.artistImages() }
        for (id in missing.filter { it !in known }) {
            val url = runCatching { YouTubeMusic.browse(id, auth = false).header?.thumbnail }.getOrNull() ?: continue
            kotlinx.coroutines.withContext(Dispatchers.IO) { Library.putArtistImage(id, url) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(T("Статистика"), onBack = { app.nav.popBackStack() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                if (!enabled) Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Pal.card)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(T("Збір статистики вимкнено"), fontWeight = FontWeight.Bold)
                            Text(T("Нові прослуховування не записуються."), color = Dim, fontSize = 13.sp)
                        }
                        Switch(false, { Settings.stats.set(true) })
                    }
                }
                Text(T("Рахується лише на цьому пристрої й нікуди не надсилається. Правила як у Spotify: прослуховування зараховується після 30 секунд, топ пісень — за кількістю прослуховувань, топ виконавців — за хвилинами."), Modifier.padding(horizontal = 16.dp), color = Dim, fontSize = 12.sp)
                ChipsRow(Range.entries.map { it.label to (it == range) }, { range = Range.entries[it] }, Modifier.padding(vertical = 10.dp))
            }
            val s = stats ?: run { item { Loading() }; return@LazyColumn }
            if (s.streams + s.skips == 0) { item { EmptyState(Icons.Rounded.BarChart, T("Поки немає даних"), T("Послухайте музику — тут з'являться хвилини, топ пісень і виконавців, години прослуховування.")) }; return@LazyColumn }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(Pal.accent, Color.Black, .55f), androidx.compose.ui.graphics.lerp(Pal.accent, Color(0xff7b2ff7), .5f))))) {
                    Column(Modifier.padding(20.dp)) {
                        Text(T("Ви слухали"), fontSize = 14.sp)
                        Text(prettyMinutes(s.totalMs), fontSize = 44.sp, fontWeight = FontWeight.Black)
                        Text(T("{0} прослуховувань • {1} пісень • {2} виконавців", s.streams, s.tracks, s.artists), fontSize = 14.sp)
                        Text(T("у середньому {0} на день", prettyMinutes(s.avgDayMs)), fontSize = 13.sp, color = Color.White.copy(alpha = .8f))
                    }
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${s.streak}", if (s.streak == 1) T("день поспіль") else T("днів поспіль"), Modifier.weight(1f))
                    StatTile("${s.newArtists}", T("нових виконавців"), Modifier.weight(1f))
                    StatTile("${if (s.streams + s.skips > 0) s.skips * 100 / (s.streams + s.skips) else 0}%", T("пропущено до 30 с"), Modifier.weight(1f))
                }
            }
            if (s.topArtists.isNotEmpty()) {
                item { ShelfHeader(T("Топ виконавців")) }
                s.topArtists.forEachIndexed { i, a -> item("a${a.key}") { RankRow(i + 1, a, round = true, minutesFirst = true) { a.id?.let(app::browse) } } }
            }
            if (s.topSongs.isNotEmpty()) {
                item { ShelfHeader(T("Топ пісень"), onPlay = { app.open(s.topSongs.first().first, PlayContext.Local(s.topSongs.map { it.first }, T("Ваш топ"))) }) }
                s.topSongs.forEachIndexed { i, (song, r) ->
                    item("s${r.key}") { RankRow(i + 1, r) { app.open(song, PlayContext.Local(s.topSongs.map { it.first }, T("Ваш топ"))) } }
                }
            }
            if (s.topAlbums.isNotEmpty()) {
                item { ShelfHeader(T("Топ альбомів")) }
                s.topAlbums.forEachIndexed { i, a -> item("al${a.key}") { RankRow(i + 1, a, minutesFirst = true) { a.id?.let(app::browse) } } }
            }
            item {
                ShelfHeader(T("Коли ви слухаєте"))
                val peak = s.hours.indices.maxBy { s.hours[it] }
                Text(T("Найактивніше о {0}:00", peak), Modifier.padding(horizontal = 16.dp), color = Dim, fontSize = 13.sp)
                Bars(s.hours.toList(), (0..23).map { if (it % 6 == 0) "${it}" else "" })
            }
            item {
                ShelfHeader(T("Дні тижня"))
                Bars(s.weekdays.toList(), listOf(T("Пн"), T("Вт"), T("Ср"), T("Чт"), T("Пт"), T("Сб"), T("Нд")))
            }
            item {
                ShelfHeader(T("Останні {0} днів", s.days.size))
                s.bestDay?.let { (d, ms) ->
                    Text(T("Рекорд: {0} — ", prettyMinutes(ms)) + java.text.SimpleDateFormat("d MMMM yyyy", com.example.privacymusic.i18n.I18n.locale()).format(java.util.Date(d)),
                        Modifier.padding(horizontal = 16.dp), color = Dim, fontSize = 13.sp)
                }
                Bars(s.days.map { it.second }, s.days.mapIndexed { i, _ -> if (i == 0 || i == s.days.lastIndex) "" else "" })
            }
            s.firstSong?.let { f -> item {
                ShelfHeader(T("Перша пісня періоду"))
                ItemRow(f, { app.open(f) }, null)
            } }
            item {
                TextButton(onClick = { confirm = true }, Modifier.padding(16.dp)) { Icon(Icons.Rounded.DeleteOutline, null, tint = Danger); Spacer(Modifier.width(8.dp)); Text(T("Видалити всю статистику"), color = Danger) }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(T("Видалити статистику?")) },
        text = { Text(T("Усі записи про час прослуховування буде видалено з пристрою. Історія, лайки й плейлисти не зміняться.")) },
        confirmButton = { TextButton(onClick = { confirm = false; app.scope.launch(Dispatchers.IO) { Library.clearStats() } }) { Text(T("Видалити"), color = Danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(T("Скасувати")) } })
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Pal.card).padding(12.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Dim, fontSize = 12.sp, maxLines = 2)
    }
}

@Composable
private fun RankRow(rank: Int, r: Ranked, round: Boolean = false, minutesFirst: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${rank}", Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = if (rank <= 3) Pal.accent else Dim)
        Thumb(r.thumbnail, Modifier.size(48.dp), 160, if (round) CircleShape else RoundedCornerShape(4.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(listOf(r.subtitle, T("{0} разів", r.plays)).filter { it.isNotBlank() }.joinToString(" • "), color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(prettyMinutes(r.ms), color = Dim, fontSize = 13.sp)
    }
}

@Composable
private fun Bars(values: List<Long>, labels: List<String>) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width / values.size
            values.forEachIndexed { i, v ->
                val h = (v.toFloat() / max) * size.height
                drawRoundRect(if (v == max) Pal.accent else Color(0xff6f6f6f), Offset(i * w + w * 0.15f, size.height - h.coerceAtLeast(2f)),
                    Size(w * 0.7f, h.coerceAtLeast(2f)), CornerRadius(3f, 3f))
            }
        }
        Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, Modifier.weight(1f), color = Dim, fontSize = 10.sp, maxLines = 1) } }
    }
}

package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Listening statistics computed with Spotify's rules:
 *  - a play counts as a "stream" only after 30 seconds of listening (shorter tracks: when ≥ 90% was heard);
 *  - minutes listened = all time actually heard (pauses excluded), like Spotify's "minutes listened";
 *  - top songs are ranked by number of streams (ties → minutes), top artists and albums by minutes (ties → streams),
 *    the same way Spotify Wrapped presents them;
 *  - each credited artist of a track gets the play (featured artists included).
 * Pure function so it can be unit-tested.
 */
object StatsCalc {
    const val STREAM_MS = 30_000L

    data class Ranked(val key: String, val title: String, val subtitle: String, val thumbnail: String?, val ms: Long, val plays: Int, val id: String?)

    data class Stats(
        val totalMs: Long, val streams: Int, val tracks: Int, val artists: Int, val skips: Int,
        val topSongs: List<Pair<Song, Ranked>>, val topArtists: List<Ranked>, val topAlbums: List<Ranked>,
        val hours: LongArray, val weekdays: LongArray, val days: List<Pair<Long, Long>>,
        val streak: Int, val bestDay: Pair<Long, Long>?, val newArtists: Int, val firstSong: Song?, val avgDayMs: Long,
    )

    fun isStream(row: PlayRow): Boolean {
        val duration = (row.song.duration ?: 0) * 1000L
        return row.listenedMs >= STREAM_MS || (duration in 1 until STREAM_MS && row.listenedMs >= duration * 9 / 10)
    }

    fun compute(rows: List<PlayRow>, rangeDays: Int?, now: Long, firstHeard: Map<String, Long> = emptyMap(), zone: TimeZone = TimeZone.getDefault()): Stats {
        val since = rangeDays?.let { now - TimeUnit.DAYS.toMillis(it.toLong()) } ?: 0L
        val inRange = rows.filter { it.startedAt >= since && it.listenedMs > 0 }
        val streams = inRange.filter(::isStream)
        val cal = Calendar.getInstance(zone)
        fun dayStart(t: Long): Long { cal.timeInMillis = t; cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); return cal.timeInMillis }
        val hours = LongArray(24); val weekdays = LongArray(7); val perDay = HashMap<Long, Long>()
        for (r in inRange) {
            cal.timeInMillis = r.startedAt
            hours[cal.get(Calendar.HOUR_OF_DAY)] += r.listenedMs
            weekdays[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += r.listenedMs // Monday first
            perDay.merge(dayStart(r.startedAt), r.listenedMs, Long::plus)
        }
        val songs = inRange.groupBy { it.song.id }.map { (_, list) ->
            val s = list.last().song
            s to Ranked(s.id, s.title, s.artistText, s.thumbnail, list.sumOf { it.listenedMs }, list.count(::isStream), s.id)
        }.filter { it.second.plays > 0 }
            .sortedWith(compareByDescending<Pair<Song, Ranked>> { it.second.plays }.thenByDescending { it.second.ms })
        val artistAgg = LinkedHashMap<String, Ranked>()
        for (r in inRange) for (a in r.song.artists.ifEmpty { listOf(Ref(r.song.artistText.ifBlank { T("Невідомий виконавець") })) }) {
            val k = a.id ?: a.name
            val prev = artistAgg[k]
            artistAgg[k] = Ranked(k, a.name, "", prev?.thumbnail ?: r.song.thumbnail, (prev?.ms ?: 0) + r.listenedMs,
                (prev?.plays ?: 0) + if (isStream(r)) 1 else 0, a.id)
        }
        val albums = inRange.filter { it.song.album != null }.groupBy { it.song.album!!.id ?: it.song.album!!.name }.map { (k, list) ->
            val s = list.first().song
            Ranked(k, s.album!!.name, s.artistText, s.thumbnail, list.sumOf { it.listenedMs }, list.count(::isStream), s.album.id)
        }
        val byMinutes = compareByDescending<Ranked> { it.ms }.thenByDescending { it.plays }
        val today = dayStart(now)
        val shownDays = minOf(rangeDays ?: 30, 30)
        val daily = (shownDays - 1 downTo 0).map { d -> val t = dayStart(today - TimeUnit.DAYS.toMillis(d.toLong()) + 3_600_000); t to (perDay[t] ?: 0L) }
        var streak = 0
        // A streak still counts if today has no listening yet (Spotify-style "days in a row" up to yesterday).
        var cursor = if ((perDay[today] ?: 0L) > 0) today else dayStart(today - TimeUnit.HOURS.toMillis(12))
        while ((perDay[cursor] ?: 0L) > 0) { streak++; cursor = dayStart(cursor - TimeUnit.HOURS.toMillis(12)) }
        val totalMs = inRange.sumOf { it.listenedMs }
        val activeDays = perDay.count { it.value > 0 }.coerceAtLeast(1)
        return Stats(
            totalMs, streams.size, songs.size, artistAgg.size, inRange.count { !isStream(it) },
            songs.take(10), artistAgg.values.sortedWith(byMinutes).take(10), albums.sortedWith(byMinutes).take(6),
            hours, weekdays, daily, streak, perDay.maxByOrNull { it.value }?.toPair(),
            if (since > 0) firstHeard.count { it.value >= since } else artistAgg.size,
            (streams.firstOrNull() ?: inRange.firstOrNull())?.song, totalMs / activeDays,
        )
    }
}

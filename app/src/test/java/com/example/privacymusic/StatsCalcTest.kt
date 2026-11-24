package com.example.privacymusic

import com.example.privacymusic.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class StatsCalcTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val now = 1_795_000_000_000L - (1_795_000_000_000L % day) + 20 * 3_600_000L // 20:00 UTC

    private val a = Ref("Artist A", "UCa"); private val b = Ref("Artist B", "UCb")
    private fun song(id: String, vararg artists: Ref, dur: Int = 200, album: Ref? = null) = Song(id, "Song $id", artists.toList(), album, dur)
    private fun play(s: Song, ago: Long, ms: Long) = PlayRow(s, now - ago, ms, ms < 30_000)

    @Test fun thirtySecondRuleDecidesWhatIsAStream() {
        val s1 = song("s1", a)
        val stats = StatsCalc.compute(listOf(play(s1, 1000, 29_999), play(s1, 2000, 30_000), play(s1, 3000, 180_000)), 28, now, zone = utc)
        assertEquals(2, stats.streams)
        assertEquals(1, stats.skips)
        assertEquals(29_999L + 30_000 + 180_000, stats.totalMs) // minutes include everything actually heard
        assertEquals(2, stats.topSongs.single().second.plays)
    }

    @Test fun shortTracksCountWhenAlmostFullyHeard() {
        val jingle = song("j", a, dur = 20)
        val s = StatsCalc.compute(listOf(play(jingle, 1000, 18_500), play(jingle, 2000, 10_000)), 28, now, zone = utc)
        assertEquals(1, s.streams)
    }

    @Test fun songsRankByStreamsArtistsByMinutes() {
        val short = song("short", a, dur = 60); val long = song("long", b, dur = 600)
        val rows = List(5) { play(short, 1000L * (it + 1), 60_000) } + List(2) { play(long, 100_000L * (it + 1), 600_000) }
        val s = StatsCalc.compute(rows, 28, now, zone = utc)
        assertEquals("short", s.topSongs.first().first.id)       // 5 streams beat 2 streams
        assertEquals("UCb", s.topArtists.first().id)              // 20 minutes beat 5 minutes
    }

    @Test fun featuredArtistsAreCredited() {
        val s = StatsCalc.compute(listOf(play(song("f", a, b), 1000, 90_000)), 28, now, zone = utc)
        assertEquals(2, s.artists)
        assertTrue(s.topArtists.all { it.plays == 1 })
    }

    @Test fun rangeFiltersOldPlays() {
        val s1 = song("s1", a)
        val s = StatsCalc.compute(listOf(play(s1, 2 * day, 60_000), play(s1, 40 * day, 60_000)), 28, now, zone = utc)
        assertEquals(1, s.streams)
        assertEquals(2, StatsCalc.compute(listOf(play(s1, 2 * day, 60_000), play(s1, 40 * day, 60_000)), null, now, zone = utc).streams)
    }

    @Test fun streakCountsConsecutiveDays() {
        val s1 = song("s1", a)
        val rows = listOf(play(s1, 1000, 60_000), play(s1, day, 60_000), play(s1, 2 * day, 60_000), play(s1, 4 * day, 60_000))
        assertEquals(3, StatsCalc.compute(rows, 28, now, zone = utc).streak)
    }

    @Test fun hoursAndWeekdaysAddUp() {
        val s1 = song("s1", a)
        val st = StatsCalc.compute(listOf(play(s1, 1000, 60_000), play(s1, 3 * 3_600_000L, 120_000)), 28, now, zone = utc)
        assertEquals(180_000L, st.hours.sum())
        assertEquals(180_000L, st.weekdays.sum())
        assertEquals(60_000L, st.hours[19])
        assertEquals(120_000L, st.hours[17])
    }
}

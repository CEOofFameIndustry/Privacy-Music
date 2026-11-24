package com.example.privacymusic

import com.example.privacymusic.data.*
import com.example.privacymusic.net.LyricsRepo
import org.junit.Assert.*
import org.junit.Test

class KaraokeTest {
    @Test fun parsesPlainLrc() {
        val lines = LyricsRepo.parseLrc("[00:10.00]Hello bright world\n[00:20.00]Next line")
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].time)
        assertEquals("Hello bright world", lines[0].text)
        // No word timings → no guessing: the line is lit as a whole.
        assertNull(Karaoke.progress(lines, 0, 12_000))
    }

    @Test fun followsRealWordTimings() {
        val l = LyricsRepo.parseLrc("[00:01.00]<00:01.00>Hello <00:01.50>bright <00:03.50>world<00:04.00>\n[00:06.00]x")
        assertEquals("Hello bright world", l[0].text)
        assertEquals(0.0, Karaoke.progress(l, 0, 1_000)!!, 0.01)
        assertEquals(6.0, Karaoke.progress(l, 0, 1_500)!!, 0.01)       // "bright" starts exactly at its mark
        assertEquals(9.5, Karaoke.progress(l, 0, 2_500)!!, 0.01)       // "bright" is held for 2 s → fills slowly
        assertEquals(15.5, Karaoke.progress(l, 0, 3_750)!!, 0.01)      // "world" sung quickly in 0.5 s
        assertEquals(18.0, Karaoke.progress(l, 0, 4_000)!!, 0.01)      // explicit end mark → full
    }

    @Test fun nothingBeforeTheFirstWord() {
        val l = LyricsRepo.parseLrc("[00:01.00]<00:02.00>Hi <00:03.00>there")
        assertEquals(0.0, Karaoke.progress(l, 0, 1_500)!!, 0.01)
    }

    @Test fun estimatesBySyllables() {
        // "la la la la" = 4 syllables + 4 quick consonants = 5 units. Lines sung back to back every second → 200 ms/unit.
        val l = LyricsRepo.parseLrc("[00:00.00]la la la la\n[00:01.00]la la la la\n[00:02.00]la la la la\n[00:10.00]la la la la\n[00:11.00]x")
        val pace = Karaoke.pace(l)
        assertEquals(200.0, pace, 0.01)
        assertEquals(6.0, Karaoke.flow(l, 2, 2_500, pace), 0.01)   // halfway: two "la"s sung; the long break after doesn't slow it
        assertEquals(11.0, Karaoke.flow(l, 2, 3_000, pace), 0.01)  // done after its own sung length, not at the next line
        assertEquals(6.0, Karaoke.flow(l, 0, 460, pace), 0.01)     // a line that fills its gap is stretched across it
        assertEquals(6.0, Karaoke.flow(LyricsRepo.parseLrc("[00:01.00]<00:01.00>Hello <00:01.50>bright"), 0, 1_500, pace), 0.01) // real timings win
    }

    @Test fun pixelUnits() {
        // "Wi": W is wide (30 px) but quick to sing (consonant), i is narrow (10 px) and carries the note.
        val l = LyricsRepo.parseLrc("[00:00.00]Wi\n[00:01.00]x")
        val cum = doubleArrayOf(0.0, 30.0, 40.0)
        assertEquals(35.0, Karaoke.flow(l, 0, 195, 260.0, cum), 0.1)   // halfway through the "i" → 30 + 5 px
        val timed = LyricsRepo.parseLrc("[00:00.00]<00:00.00>Wi<00:01.00>")
        assertEquals(20.0, Karaoke.progress(timed, 0, 500, cum)!!, 0.01)
    }
}

class LayoutsTest {
    @Test fun arrangesAndKeepsNewBlocks() {
        val ui = com.example.privacymusic.ui.components.Layouts
        val saved = ui.encode(listOf("c" to true, "a" to false, "gone" to true))
        assertEquals(listOf("c" to true, "b" to true, "a" to false, "d" to true), ui.arrange(saved, listOf("a", "b", "c", "d")))
        assertEquals(listOf("c", "b", "d"), ui.visible(saved, listOf("a", "b", "c", "d")))
        assertEquals(listOf("x y"), ui.visible("", listOf("x,y")))
    }
}

class TelegramImportTest {
    @Test fun parsesExport() {
        val json = """{"name":"BuddyMusic | Музыка","type":"bot_chat","messages":[
            {"id":1,"type":"message","media_type":"audio_file","file":"files/desire.mp3","file_name":"desire.mp3","performer":"Morris","title":"Desire","duration_seconds":180},
            {"id":2,"type":"message","media_type":"audio_file","file":"(File not included. Change data exporting settings to download.)","file_name":"Morris - desire (slowed&reverb).mp3","duration_seconds":200},
            {"id":3,"type":"message","media_type":"audio_file","file":"files/desire.mp3","performer":"Morris","title":"Desire"},
            {"id":4,"type":"message","media_type":"voice_message","file":"voice.ogg"},
            {"id":5,"type":"message","text":"Натисни, щоб знайти пісню"}]}"""
        val (name, e) = com.example.privacymusic.net.Importer.parseTelegram(json)
        assertEquals("BuddyMusic | Музыка", name)
        assertEquals(2, e.size)
        assertEquals("Morris", e[0].first.artist); assertEquals("Desire", e[0].first.title); assertEquals(180, e[0].first.durationSec)
        assertEquals("files/desire.mp3", e[0].second)
        assertEquals("desire (slowed&reverb)", e[1].first.title); assertNull(e[1].second)
    }
}

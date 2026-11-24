package com.example.privacymusic

import com.example.privacymusic.data.*
import com.example.privacymusic.net.Parser
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs against real responses saved in $YTM_SAMPLES (skipped when absent). */
class ParserTest {
    private val dir = System.getenv("YTM_SAMPLES")?.let(::File)
    private fun load(name: String): JSONObject {
        assumeTrue(dir != null && File(dir, "$name.json").exists())
        return JSONObject(File(dir, "$name.json").readText())
    }
    private fun dump(p: Page) {
        println("HEADER ${p.header}")
        println("CHIPS ${p.chips.map { it.title }} cont=${p.continuation != null}")
        p.shelves.forEach { s ->
            println("SHELF '${s.title}' ${s.style} n=${s.items.size} more=${s.more} cont=${s.continuation != null} play=${s.play != null}")
            s.items.take(3).forEach { println("   $it") }
        }
    }

    @Test fun home() { val p = Parser.page(load("home"), "FEmusic_home"); dump(p); assertTrue(p.shelves.isNotEmpty()); assertNotNull(p.continuation) }
    @Test fun search() { val p = Parser.search(load("search_all")); dump(p); assertTrue(p.chips.size >= 5); assertTrue(p.shelves.first().style == ShelfStyle.TOP_RESULT) }
    @Test fun searchSongs() { val p = Parser.search(load("search_songs")); dump(p); val s = p.shelves.flatMap { it.items }.filterIsInstance<Song>(); assertTrue(s.size > 5); assertTrue(s.all { it.artists.isNotEmpty() && it.duration != null }) }
    @Test fun album() { val p = Parser.page(load("album"), "MPREb_XKnaEOEUlrU"); dump(p); assertEquals(PageType.ALBUM, p.header?.type); assertNotNull(p.header?.play) }
    @Test fun playlist() { val p = Parser.page(load("playlist"), "VLRDCLAK5uy_l4KEyuxwu2yBeugeQ0DRUY9xDYHLmJGm8"); dump(p); assertTrue(p.shelves.first().items.size >= 40); assertNotNull(p.shelves.first().continuation ?: p.continuation) }
    @Test fun artist() { val p = Parser.page(load("artist"), "UCxgN32UVVztKAQd2HkXzBtw"); dump(p); assertEquals(PageType.ARTIST, p.header?.type); assertNotNull(p.header?.description) }
    @Test fun moods() { val p = Parser.page(load("moods"), "FEmusic_moods_and_genres"); dump(p); assertTrue(p.shelves.all { it.style == ShelfStyle.NAV_GRID }) }
    @Test fun charts() { dump(Parser.page(load("charts"), "FEmusic_charts")) }
    @Test fun explore() { dump(Parser.page(load("explore"), "FEmusic_explore")) }
    @Test fun next() { val q = Parser.watch(load("next")); println(q.copy(songs = q.songs.take(3))); assertTrue(q.songs.size > 10); assertNotNull(q.lyricsBrowseId); assertNotNull(q.continuation) }
    @Test fun lyrics() { val l = Parser.lyrics(load("lyrics")); println(l?.source); assertNotNull(l) }
    @Test fun suggestions() { val (q, i) = Parser.suggestions(load("sugg")); println(q); println(i); assertTrue(q.isNotEmpty()) }
    @Test fun homeContinuation() { val c = Parser.continuation(load("homec2")); println(c.shelves.map { it.title to it.items.size }); assertTrue(c.shelves.isNotEmpty()) }
}

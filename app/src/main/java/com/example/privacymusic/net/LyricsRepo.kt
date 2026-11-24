package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import com.example.privacymusic.data.Lyrics
import com.example.privacymusic.data.Settings
import com.example.privacymusic.data.Song
import com.example.privacymusic.data.SyncedLine
import com.example.privacymusic.data.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyrics sources:
 *  - YouTube Music itself (plain text, no timing) — always available;
 *  - LRCLIB, an open lyrics database with per-line timestamps (Spotify-style live lyrics) — opt-in, because it needs
 *    the track title, artist and duration to be sent to lrclib.net (no account, no identifiers).
 */
object LyricsRepo {
    private val cache = ConcurrentHashMap<String, Lyrics>()

    /** Forget cached results, e.g. after the user turns LRCLIB on. */
    fun clear() = cache.clear()

    suspend fun get(song: Song): Lyrics? {
        cache[song.id]?.let { return it }
        if (song.isLocal) return null
        if (Settings.lrclib.value) lrclib(song)?.let { cache[song.id] = it; return it }
        val queue = YouTubeMusic.next(WatchEndpoint(song.id))
        val id = queue.lyricsBrowseId ?: return null
        return YouTubeMusic.lyrics(id)?.also { cache[song.id] = it }
    }

    private val noise = Regex("\\(.*?\\)|\\[.*?]|\\s-\\s.*(remaster|version|live|edit|mix).*", RegexOption.IGNORE_CASE)
    private fun clean(s: String) = s.replace(noise, " ").replace(Regex("\\s+"), " ").trim()

    private fun JSONObject.synced() = optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.plain() = optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }

    private fun toLyrics(o: JSONObject): Lyrics? {
        val synced = o.synced()?.let(::parseLrc)?.takeIf { it.size >= 2 }
        val plain = o.plain() ?: synced?.joinToString("\n") { it.text } ?: return null
        return Lyrics(plain, "LRCLIB", synced)
    }

    private fun call(url: okhttp3.HttpUrl): String? = Firewall.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) null else r.body?.string()
    }

    private suspend fun lrclib(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        runCatching {
            val artist = song.artists.firstOrNull()?.name ?: return@runCatching null
            val title = clean(song.title)
            // 1) exact lookup
            val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist)
                .apply { song.album?.name?.let { addQueryParameter("album_name", it) }; song.duration?.let { addQueryParameter("duration", it.toString()) } }.build()
            call(exact)?.let { toLyrics(JSONObject(it)) }?.takeIf { it.synced != null }?.let { return@runCatching it }
            // 2) fuzzy search; prefer a synced result whose length matches the track
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("q", "${artist} ${title}").build()
            val hits = call(search)?.let(::JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            val dur = song.duration
            val best = hits.filter { it.synced() != null && (dur == null || kotlin.math.abs(it.optDouble("duration") - dur) <= 6) }
                .minByOrNull { if (dur == null) 0.0 else kotlin.math.abs(it.optDouble("duration") - dur) }
                ?: hits.firstOrNull { it.plain() != null && (dur == null || kotlin.math.abs(it.optDouble("duration") - dur) <= 6) }
            best?.let(::toLyrics)
        }.getOrNull()
    }

    private val lrcLine = Regex("^((?:\\[\\d+:\\d+(?:[.:]\\d+)?])+)\\s*(.*)$")
    private val stamp = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
    private val wordStamp = Regex("<(\\d+):(\\d+)(?:[.:](\\d+))?>")

    private fun ms(min: String, sec: String, frac: String) =
        min.toLong() * 60_000 + sec.toLong() * 1000 + if (frac.isEmpty()) 0 else (frac + "00").take(3).toInt()

    /**
     * Parses [mm:ss.xx] lines (a line may carry several stamps) into a time-ordered list. Enhanced LRC word stamps
     * (<mm:ss.xx>word) are stripped from the text and kept as word marks for karaoke highlighting.
     */
    fun parseLrc(text: String): List<SyncedLine> = text.lines().flatMap { line ->
        val m = lrcLine.find(line.trim()) ?: return@flatMap emptyList()
        val raw = m.groupValues[2].trim()
        val marks = mutableListOf<Pair<Long, Int>>(); val clean = StringBuilder(); var last = 0
        for (w in wordStamp.findAll(raw)) {
            clean.append(raw, last, w.range.first); last = w.range.last + 1
            val (a, b, c) = w.destructured
            marks += ms(a, b, c) to clean.trimStart().length
        }
        clean.append(raw.substring(last))
        val words = clean.toString().replace(Regex("\\s+"), " ").trim()
        stamp.findAll(m.groupValues[1]).map { s ->
            val (min, sec, frac) = s.destructured
            SyncedLine(ms(min, sec, frac), words, marks.filter { it.second <= words.length })
        }.toList()
    }.sortedBy { it.time }
}

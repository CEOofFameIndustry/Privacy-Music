package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import org.json.JSONArray
import org.json.JSONObject

/** A link to another YouTube Music page (artist, album, playlist). */
data class Ref(val name: String, val id: String? = null)

sealed interface Endpoint
data class BrowseEndpoint(val browseId: String, val params: String? = null, val pageType: String? = null) : Endpoint
data class WatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
    val index: Int? = null,
) : Endpoint
data class SearchEndpoint(val query: String, val params: String? = null) : Endpoint

sealed interface YTItem {
    val key: String
    val title: String
    val thumbnail: String?
}

data class Song(
    val id: String,
    override val title: String,
    val artists: List<Ref> = emptyList(),
    val album: Ref? = null,
    val duration: Int? = null,
    override val thumbnail: String? = null,
    val explicit: Boolean = false,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
    /** Free-form second line when artists are unknown (e.g. local files, view counts). */
    val extra: String? = null,
) : YTItem {
    override val key get() = "song:${id}"
    val isLocal get() = id.startsWith("content:") || id.startsWith("file:")
    val artistText get() = artists.joinToString(", ") { it.name }.ifBlank { extra.orEmpty() }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title)
        .put("artists", JSONArray().apply { artists.forEach { put(JSONObject().put("n", it.name).put("i", it.id)) } })
        .put("album", album?.let { JSONObject().put("n", it.name).put("i", it.id) })
        .put("duration", duration ?: -1).put("thumb", thumbnail).put("explicit", explicit)
        .put("video", isVideo).put("extra", extra)

    companion object {
        fun fromJson(o: JSONObject): Song {
            val artists = o.optJSONArray("artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Ref(it.optString("n"), it.optString("i").ifEmpty { null }.takeUnless { v -> v == "null" }) }
            }.orEmpty()
            val album = o.optJSONObject("album")?.let { Ref(it.optString("n"), it.optString("i").takeUnless { v -> v.isEmpty() || v == "null" }) }
            return Song(
                id = o.getString("id"), title = o.optString("title"), artists = artists, album = album,
                duration = o.optInt("duration", -1).takeIf { it > 0 },
                thumbnail = o.optString("thumb").takeUnless { it.isEmpty() || it == "null" },
                explicit = o.optBoolean("explicit"), isVideo = o.optBoolean("video"),
                extra = o.optString("extra").takeUnless { it.isEmpty() || it == "null" },
            )
        }
    }
}

data class AlbumItem(
    val browseId: String,
    val playlistId: String? = null,
    override val title: String,
    val artists: List<Ref> = emptyList(),
    val year: String? = null,
    val subtitle: String = "",
    override val thumbnail: String? = null,
    val explicit: Boolean = false,
) : YTItem {
    override val key get() = "album:${browseId}"
}

data class PlaylistItem(
    val browseId: String,
    override val title: String,
    val subtitle: String = "",
    override val thumbnail: String? = null,
    val playlistId: String? = browseId.removePrefix("VL"),
    /** For mixes / radios that only have a watch endpoint. */
    val watch: WatchEndpoint? = null,
) : YTItem {
    override val key get() = "playlist:${browseId}"
}

data class ArtistItem(
    val browseId: String,
    override val title: String,
    val subtitle: String = "",
    override val thumbnail: String? = null,
) : YTItem {
    override val key get() = "artist:${browseId}"
}

/** A playlist that lives only on this device. */
data class LocalPlaylistItem(
    val id: Long,
    override val title: String,
    val count: Int = 0,
    override val thumbnail: String? = null,
) : YTItem {
    override val key get() = "local:${id}"
}

/** Mood / genre buttons and similar navigation tiles. */
data class NavItem(
    override val title: String,
    val endpoint: Endpoint,
    val color: Long? = null,
    override val thumbnail: String? = null,
) : YTItem {
    override val key get() = "nav:${title}:${endpoint}"
}

enum class ShelfStyle { LIST, CAROUSEL, QUICK_PICKS, GRID, NAV_GRID, TOP_RESULT, DESCRIPTION }

data class Shelf(
    val title: String? = null,
    val strapline: String? = null,
    val items: List<YTItem> = emptyList(),
    val more: Endpoint? = null,
    val style: ShelfStyle = ShelfStyle.LIST,
    val continuation: String? = null,
    val text: String? = null,
    val play: WatchEndpoint? = null,
    /** Stable kind for user layouts (e.g. "quick", "mixes"); null for shelves that come from YouTube Music. */
    val id: String? = null,
)

data class Chip(val title: String, val endpoint: Endpoint?, val selected: Boolean)

enum class PageType { HOME, ALBUM, PLAYLIST, ARTIST, OTHER }

data class Header(
    val title: String,
    val subtitle: String = "",
    val secondSubtitle: String = "",
    val description: String? = null,
    val thumbnail: String? = null,
    val artists: List<Ref> = emptyList(),
    val play: WatchEndpoint? = null,
    val shuffle: WatchEndpoint? = null,
    val radio: WatchEndpoint? = null,
    val playlistId: String? = null,
    val type: PageType = PageType.OTHER,
)

data class Page(
    val header: Header? = null,
    val chips: List<Chip> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    /** Continuation for more shelves (home feed etc.). */
    val continuation: String? = null,
)

data class WatchQueue(
    val songs: List<Song>,
    val continuation: String?,
    val playlistId: String?,
    val lyricsBrowseId: String?,
    val relatedBrowseId: String?,
    val automix: WatchEndpoint?,
    val currentIndex: Int,
)

/** One timed lyric line. [marks] = optional per-word start times (time ms → character offset) from enhanced LRC. */
data class SyncedLine(val time: Long, val text: String, val marks: List<Pair<Long, Int>> = emptyList())

data class Lyrics(val text: String, val source: String, val synced: List<SyncedLine>? = null)

/**
 * Karaoke maths (pure, unit-tested). Letters are filled only from REAL word timings (enhanced LRC): each word fills
 * over its own sung duration, so long-held words fill slowly and quick ones quickly. Lines without word timings are
 * lit as a whole at their exact timestamp — nothing is guessed, so nothing lags.
 */
object Karaoke {
    /** End of the last word: an explicit end mark if present, else a short tail capped by the next line. */
    private fun wordsEnd(lines: List<SyncedLine>, i: Int): Long {
        val line = lines[i]; val last = line.marks.last()
        if (last.second >= line.text.length) return last.first
        val next = lines.getOrNull(i + 1)?.time ?: Long.MAX_VALUE
        return minOf(next, last.first + (line.text.length - last.second) * 90L + 300)
    }

    /** Fractional number of characters sung at [pos], or null when the line has no word timings.
     *  With [cum] (cumulative glyph widths, size len+1) the result is in width units instead, so the fill moves at an
     *  even speed in pixels — wide and narrow letters no longer make it speed up and slow down. */
    fun progress(lines: List<SyncedLine>, i: Int, pos: Long, cum: DoubleArray? = null): Double? {
        val line = lines[i]; val len = line.text.length
        if (line.marks.isEmpty() || len == 0) return null
        fun u(o: Int) = cum?.get(o.coerceIn(0, len)) ?: o.toDouble()
        val marks = (line.marks + (wordsEnd(lines, i) to len)).distinctBy { it.second }.sortedBy { it.first }
        if (pos < marks.first().first) return u(marks.first().second).coerceAtMost(0.0)
        for (k in 0 until marks.size - 1) {
            val (t0, o0) = marks[k]; val (t1, o1) = marks[k + 1]
            if (pos < t1) return u(o0) + (u(o1) - u(o0)) * ((pos - t0).toDouble() / (t1 - t0).coerceAtLeast(1))
        }
        return u(len)
    }

    private const val VOWELS = "aeiouyáéíóúàèìòùäëïöüâêîôûåæøœąęаеєиіїоуюяыэё"

    /** How long each character takes to sing, in "syllable" units: vowels carry the sung notes (1), consonants are
     *  quick (0.25), a comma or dash is a short breath (0.8), spaces take no time. CJK/kana characters are one
     *  syllable each. Singing follows syllables, not letters — that's what keeps the estimate in step. */
    fun weights(text: String): DoubleArray = DoubleArray(text.length) { k ->
        val c = text[k].lowercaseChar()
        when {
            c in VOWELS -> 1.0
            c.code >= 0x2E80 && c.isLetter() -> 1.0
            c.isLetterOrDigit() -> 0.25
            c == ',' || c == ';' || c == ':' || c == '—' || c == '–' -> 0.8
            else -> 0.0
        }
    }

    /** This song's singing pace in ms per syllable unit, measured on lines sung back to back (a lower-middle
     *  percentile of gap / units, so instrumental breaks between lines don't slow it down). */
    fun pace(lines: List<SyncedLine>): Double {
        val r = lines.indices.mapNotNull { i ->
            val units = weights(lines[i].text).sum()
            val next = lines.getOrNull(i + 1)?.time ?: return@mapNotNull null
            if (units < 2) null else (next - lines[i].time).toDouble() / units
        }.sorted()
        return (r.getOrNull((r.size * 0.35).toInt()) ?: 260.0).coerceIn(110.0, 700.0)
    }

    /** Progressive fill: exact with word timings; otherwise an estimate that walks through the line syllable by
     *  syllable. The line's sung length is its syllables at this song's pace; if the gap to the next line is only a
     *  little longer, the singer is most likely stretching the line across it, so the fill stretches too. */
    fun flow(lines: List<SyncedLine>, i: Int, pos: Long, pace: Double, cum: DoubleArray? = null): Double {
        progress(lines, i, pos, cum)?.let { return it }
        val line = lines[i]; val len = line.text.length
        if (len == 0) return 0.0
        val total = cum?.get(len) ?: len.toDouble()
        val w = weights(line.text)
        val units = w.sum()
        val gap = ((lines.getOrNull(i + 1)?.time ?: (line.time + 6000)) - line.time).toDouble()
        if (units <= 0.0) return (total * (pos - line.time) / (gap * 0.9).coerceAtLeast(300.0)).coerceIn(0.0, total)
        val est = units * pace
        val dur = (if (gap <= est * 1.4) gap * 0.92 else est).coerceIn(300.0, maxOf(300.0, gap * 0.95))
        var target = units * ((pos - line.time) / dur).coerceIn(0.0, 1.0)
        if (target >= units) return total
        // Walk to the character being sung and the fraction of it done, then express that in output units.
        var k = 0
        while (k < len && target >= w[k]) { target -= w[k]; k++ }
        if (k >= len) return total
        val frac = if (w[k] > 0) target / w[k] else 0.0
        return if (cum != null) cum[k] + (cum[k + 1] - cum[k]) * frac else k + frac
    }
}

/** Serialises any item so it can be pinned / backed up. */
object ItemCodec {
    private fun refs(a: List<Ref>) = JSONArray().apply { a.forEach { put(JSONObject().put("n", it.name).put("i", it.id)) } }
    private fun refs(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        .map { Ref(it.optString("n"), it.optString("i").takeUnless { v -> v.isEmpty() || v == "null" }) }
    private fun JSONObject.s(k: String) = optString(k).takeUnless { it.isEmpty() || it == "null" }

    fun encode(item: YTItem): JSONObject? = when (item) {
        is Song -> item.toJson().put("kind", "song")
        is AlbumItem -> JSONObject().put("kind", "album").put("id", item.browseId).put("pl", item.playlistId).put("title", item.title)
            .put("artists", refs(item.artists)).put("year", item.year).put("sub", item.subtitle).put("thumb", item.thumbnail)
        is PlaylistItem -> JSONObject().put("kind", "playlist").put("id", item.browseId).put("pl", item.playlistId).put("title", item.title)
            .put("sub", item.subtitle).put("thumb", item.thumbnail)
        is ArtistItem -> JSONObject().put("kind", "artist").put("id", item.browseId).put("title", item.title).put("sub", item.subtitle).put("thumb", item.thumbnail)
        is LocalPlaylistItem -> JSONObject().put("kind", "local").put("id", item.id).put("title", item.title).put("thumb", item.thumbnail)
        is NavItem -> null
    }

    fun decode(o: JSONObject): YTItem? = when (o.optString("kind")) {
        "song" -> Song.fromJson(o)
        "album" -> AlbumItem(o.getString("id"), o.s("pl"), o.optString("title"), refs(o.optJSONArray("artists")), o.s("year"), o.optString("sub"), o.s("thumb"))
        "playlist" -> PlaylistItem(o.getString("id"), o.optString("title"), o.optString("sub"), o.s("thumb"), o.s("pl"))
        "artist" -> ArtistItem(o.getString("id"), o.optString("title"), o.optString("sub"), o.s("thumb"))
        "local" -> LocalPlaylistItem(o.getLong("id"), o.optString("title"), 0, o.s("thumb"))
        else -> null
    }
}

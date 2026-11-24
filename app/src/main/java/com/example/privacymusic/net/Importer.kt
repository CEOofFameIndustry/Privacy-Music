package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.example.privacymusic.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Imports playlists from YouTube Music / Spotify links and from spreadsheet files into a local playlist.
 * Spotify is read from its public embed page (no account, no API key, no OAuth); the tracks are then
 * matched against YouTube Music search.
 */
object Importer {
    data class Entry(val title: String, val artist: String = "", val album: String = "", val durationSec: Int? = null, val videoId: String? = null)
    data class Result(val name: String, val songs: List<Song>, val missed: List<String>, val note: String? = null)
    data class Progress(val done: Int, val total: Int, val stage: String)

    // ---------------- links ----------------
    suspend fun fromLink(url: String, progress: (Progress) -> Unit): Result {
        val u = url.trim()
        Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(playlist|album|track)/([A-Za-z0-9]+)").find(u)?.let { m ->
            val (kind, id) = m.destructured
            return fromSpotify(kind, id, progress)
        }
        val ep = YouTubeMusic.parseLink(u) ?: throw IOException(T("Посилання не розпізнано"))
        return fromYouTube(ep, progress)
    }

    private suspend fun fromYouTube(ep: Endpoint, progress: (Progress) -> Unit): Result {
        val browseId = when (ep) {
            is BrowseEndpoint -> ep.browseId
            is WatchEndpoint -> ep.playlistId?.let { "VL${it}" } ?: throw IOException(T("Це посилання на одну пісню, а не на плейлист"))
            else -> throw IOException(T("Посилання не розпізнано"))
        }
        progress(Progress(0, 0, T("Завантаження плейлиста YouTube Music…")))
        var page = YouTubeMusic.browse(browseId)
        val songs = page.shelves.filter { it.style == ShelfStyle.LIST }.flatMap { it.items }.filterIsInstance<Song>().toMutableList()
        var token = page.shelves.lastOrNull { it.style == ShelfStyle.LIST }?.continuation
        while (token != null && songs.size < 10_000) {
            currentCoroutineContext().ensureActive()
            val c = YouTubeMusic.continuation(token)
            songs += c.items.filterIsInstance<Song>()
            token = c.next
            progress(Progress(songs.size, 0, T("Завантажено {0} треків…", songs.size)))
        }
        val h = page.header
        val fixed = songs.map { if (it.thumbnail == null && h?.thumbnail != null) it.copy(thumbnail = h.thumbnail) else it }.distinctBy { it.id }
        return Result(h?.title ?: T("Імпортований плейлист"), fixed, emptyList())
    }

    private suspend fun fromSpotify(kind: String, id: String, progress: (Progress) -> Unit): Result {
        progress(Progress(0, 0, T("Читання плейлиста Spotify…")))
        val html = withContext(Dispatchers.IO) {
            Firewall.client.newCall(Request.Builder().url("https://open.spotify.com/embed/${kind}/${id}").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Spotify: HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw IOException(T("Spotify змінив формат сторінки"))
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: throw IOException(T("Плейлист приватний або недоступний"))
        val name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify" }
        val list = entity.optJSONArray("trackList")
        val entries = if (list != null) (0 until list.length()).map { list.getJSONObject(it) }.map {
            Entry(it.optString("title"), it.optString("subtitle").replace(' ', ' '), durationSec = it.optLong("duration").takeIf { d -> d > 0 }?.div(1000)?.toInt())
        } else listOf(Entry(name, entity.optString("subtitle")))
        val note = if (entries.size >= 100) T("Без входу Spotify показує лише перші 100 треків. Для довших плейлистів експортуйте CSV (наприклад, через Exportify) і імпортуйте таблицею.") else null
        val (songs, missed) = match(entries, progress)
        return Result(name, songs, missed, note)
    }

    // ---------------- tables ----------------
    suspend fun fromTable(context: Context, uri: Uri, progress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        progress(Progress(0, 0, T("Читання таблиці…")))
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException(T("Файл недоступний"))
        // A Telegram export's result.json picked on its own: no audio files available, so everything is matched.
        if (String(bytes, 0, minOf(bytes.size, 4096)).trimStart().startsWith("{")) {
            val (chat, tg) = parseTelegram(String(bytes))
            if (tg.isEmpty()) throw IOException(T("У експорті Telegram немає аудіофайлів"))
            val (songs, missed) = match(tg.map { it.first }, progress)
            return@withContext Result(chat, songs, missed)
        }
        val rows = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) readXlsx(bytes) else readDelimited(String(bytes).removePrefix("﻿"))
        val entries = entriesFrom(rows)
        if (entries.isEmpty()) throw IOException(T("У таблиці не знайдено треків. Потрібні стовпці «назва» і «виконавець» (або Video ID)."))
        val (songs, missed) = match(entries, progress)
        Result(name.substringBeforeLast('.').ifBlank { T("Імпорт з таблиці") }, songs, missed)
    }

    private fun readDelimited(text: String): List<List<String>> {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf('\t', ';', ',').maxBy { c -> first.count { it == c } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear(); if (row.any { it.isNotBlank() }) rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString(); if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    /** Minimal .xlsx reader: first worksheet, shared + inline strings, numbers. */
    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = z.readBytes() }
        }
        val shared = mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { data ->
            val p = Xml.newPullParser(); p.setInput(data.inputStream(), "UTF-8")
            var current: StringBuilder? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "si") current = StringBuilder()
                if (p.eventType == XmlPullParser.START_TAG && p.name == "t") current?.append(p.nextText())
                if (p.eventType == XmlPullParser.END_TAG && p.name == "si") { shared += current.toString(); current = null }
            }
        }
        val sheet = files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()?.let { files[it] } ?: return emptyList()
        val p = Xml.newPullParser(); p.setInput(sheet.inputStream(), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var row = sortedMapOf<Int, String>(); var col = 0; var type = ""; var value = StringBuilder()
        fun colIndex(ref: String) = ref.takeWhile { it.isLetter() }.fold(0) { a, ch -> a * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = sortedMapOf()
                    "c" -> { col = colIndex(p.getAttributeValue(null, "r") ?: ""); type = p.getAttributeValue(null, "t") ?: ""; value = StringBuilder() }
                    "v", "t" -> value.append(p.nextText())
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "c" -> row[col] = if (type == "s") shared.getOrNull(value.toString().toIntOrNull() ?: -1).orEmpty() else value.toString()
                    "row" -> if (row.isNotEmpty()) rows += (0..row.lastKey()).map { row[it].orEmpty() }
                }
            }
        }
        return rows
    }

    private fun entriesFrom(rows: List<List<String>>): List<Entry> {
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun find(vararg names: String) = header.indexOfFirst { h -> names.any { h == it } }.takeIf { it >= 0 }
            ?: header.indexOfFirst { h -> names.any { h.contains(it) } }.takeIf { it >= 0 }
        val vid = find("video id", "videoid", "youtube id", "video_id")
        val title = find("track name", "song title", "title", "song", "track", "name", "назва", "пісня", "трек", "название")
        val artist = find("artist name(s)", "artist names", "artist name 1", "artist name", "artists", "artist", "виконавець", "исполнитель")
        val album = find("album name", "album", "альбом")
        val dur = find("duration (ms)", "track duration (ms)", "duration_ms", "duration", "тривалість")
        val hasHeader = vid != null || title != null || artist != null
        val body = if (hasHeader) rows.drop(1) else rows
        return body.mapNotNull { r ->
            fun at(i: Int?) = i?.let { r.getOrNull(it) }?.trim().orEmpty()
            val v = at(vid).takeIf { videoIdPattern.matches(it) }
            val t = if (hasHeader) at(title) else r.getOrNull(0)?.trim().orEmpty()
            val a = if (hasHeader) at(artist) else r.getOrNull(1)?.trim().orEmpty()
            val d = at(dur).toDoubleOrNull()?.let { if (header.getOrNull(dur ?: -1)?.contains("ms") == true || it > 10_000) (it / 1000).toInt() else it.toInt() }
                ?: Parser.parseDuration(at(dur))
            if (v == null && t.isBlank()) null else Entry(t, a.replace(";", ", "), at(album), d, v)
        }
    }

    // ---------------- matching ----------------
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("\\(.*?\\)|\\[.*?]|feat\\.?.*|ft\\..*|- remaster.*|remastered.*"), " ").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun similarity(a: String, b: String): Double {
        val x = norm(a).split(' ').filter { it.isNotEmpty() }.toSet(); val y = norm(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / maxOf(x.size, y.size)
    }

    private fun score(e: Entry, s: Song): Double {
        var score = similarity(e.title, s.title) * 3
        if (e.artist.isNotBlank()) score += similarity(e.artist, s.artistText).coerceAtLeast(
            e.artist.split(',', '&').maxOf { similarity(it, s.artistText) }) * 2
        if (e.durationSec != null && s.duration != null) score += if (kotlin.math.abs(e.durationSec - s.duration) <= 4) 1.0 else if (kotlin.math.abs(e.durationSec - s.duration) > 25) -1.0 else 0.0
        if (s.isVideo) score -= 0.3
        return score
    }

    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

    suspend fun match(entries: List<Entry>, progress: (Progress) -> Unit): Pair<List<Song>, List<String>> = coroutineScope {
        val gate = Semaphore(4)
        var done = 0
        val results = entries.map { e ->
            async {
                gate.withPermit {
                    val song = runCatching { resolve(e) }.getOrNull()
                    synchronized(this@Importer) { done++; progress(Progress(done, entries.size, T("Пошук відповідників у YouTube Music: {0} з {1}", done, entries.size))) }
                    e to song
                }
            }
        }.awaitAll()
        results.mapNotNull { it.second }.distinctBy { it.id } to results.filter { it.second == null }.map { (e, _) -> listOf(e.artist, e.title).filter { it.isNotBlank() }.joinToString(" — ") }
    }

    private suspend fun resolve(e: Entry): Song? {
        if (e.videoId != null) {
            if (e.title.isNotBlank()) return Song(e.videoId, e.title, if (e.artist.isBlank()) emptyList() else listOf(Ref(e.artist)), e.album.takeIf { it.isNotBlank() }?.let { Ref(it) }, e.durationSec,
                "https://i.ytimg.com/vi/${e.videoId}/hqdefault.jpg")
            return YouTubeMusic.next(WatchEndpoint(e.videoId)).songs.firstOrNull { it.id == e.videoId }
        }
        val query = listOf(e.title, e.artist.substringBefore(',')).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YouTubeMusic.search(query, SONGS_FILTER).shelves.flatMap { it.items }.filterIsInstance<Song>().take(6)
        val best = candidates.maxByOrNull { score(e, it) } ?: return null
        return best.takeIf { score(e, it) >= 1.5 }
    }

    // ---------------- Telegram ----------------
    /**
     * Telegram Desktop export (Settings → Advanced → Export Telegram data, or "Export chat history" on a chat; JSON).
     * Every audio-file message becomes an entry (forwards of the same song once). Returns the chat name and
     * (entry, path of the exported file relative to result.json or null when the file wasn't exported).
     */
    fun parseTelegram(json: String): Pair<String, List<Pair<Entry, String?>>> {
        val root = JSONObject(json)
        val out = mutableListOf<Pair<Entry, String?>>()
        val seen = HashSet<String>()
        fun scan(chat: JSONObject) {
            val msgs = chat.optJSONArray("messages") ?: return
            for (k in 0 until msgs.length()) {
                val m = msgs.optJSONObject(k) ?: continue
                if (m.optString("media_type") != "audio_file") continue
                val file = m.optString("file").takeIf { it.isNotBlank() && !it.startsWith("(File not included") }
                val fileName = m.optString("file_name").ifBlank { m.optString("file") }.substringAfterLast('/')
                var title = m.optString("title").trim(); var artist = m.optString("performer").trim()
                if (title.isEmpty()) {
                    // No tags: "Artist - Title.mp3"
                    val stem = fileName.substringBeforeLast('.')
                    if (stem.contains(" - ")) { artist = stem.substringBefore(" - ").trim(); title = stem.substringAfter(" - ").trim() } else title = stem
                }
                if (title.isEmpty() || !seen.add(artist.lowercase() + "|" + title.lowercase())) continue
                out += Entry(title, artist, durationSec = m.optInt("duration_seconds").takeIf { it > 0 }) to file
            }
        }
        val list = root.optJSONObject("chats")?.optJSONArray("list")
        if (list != null) for (k in 0 until list.length()) list.optJSONObject(k)?.let(::scan) else scan(root)
        return (if (list != null) "Telegram" else root.optString("name").ifBlank { "Telegram" }) to out
    }

    /**
     * The whole export folder (picked with the system folder picker): songs whose audio file was exported become
     * local songs (played straight from that folder, offline); the rest are found on YouTube Music.
     * The app never signs in to Telegram — it only reads these files.
     */
    suspend fun fromTelegramFolder(context: Context, tree: Uri, progress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        progress(Progress(0, 0, T("Читання експорту Telegram…")))
        val cr = context.contentResolver
        runCatching { cr.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        data class Doc(val id: String, val name: String, val dir: Boolean)
        fun children(docId: String): List<Doc> {
            val uri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
            return cr.query(uri, cols, null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR)) }
            }.orEmpty()
        }
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        // result.json in the picked folder, or one level down (e.g. "ChatExport_2026-11-21").
        var dir = rootId
        var json = children(rootId).firstOrNull { it.name == "result.json" }
        if (json == null) for (d in children(rootId).filter { it.dir }) {
            children(d.id).firstOrNull { it.name == "result.json" }?.let { json = it; dir = d.id }
            if (json != null) break
        }
        val found = json ?: throw IOException(T("У папці немає result.json — виберіть папку експорту Telegram"))
        val text = cr.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, found.id))?.use { String(it.readBytes()) }
            ?: throw IOException(T("Файл недоступний"))
        val (chat, entries) = parseTelegram(text)
        if (entries.isEmpty()) throw IOException(T("У експорті Telegram немає аудіофайлів"))
        val listing = HashMap<String, List<Doc>>()
        fun resolve(rel: String): Uri? {
            var cur = dir
            for (part in rel.split('/').filter { it.isNotEmpty() }) {
                cur = listing.getOrPut(cur) { children(cur) }.firstOrNull { it.name == part }?.id ?: return null
            }
            return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cur)
        }
        val local = mutableListOf<Song>(); val remote = mutableListOf<Entry>()
        entries.forEachIndexed { i, (e, path) ->
            progress(Progress(i, entries.size, T("Читання експорту Telegram…")))
            val uri = path?.let(::resolve)
            if (uri != null) local += Song(uri.toString(), e.title, if (e.artist.isBlank()) emptyList() else listOf(Ref(e.artist, null)),
                duration = e.durationSec, extra = e.artist.ifBlank { T("Файл з Telegram") })
            else remote += e
        }
        val (matched, missed) = if (remote.isEmpty()) emptyList<Song>() to emptyList() else match(remote, progress)
        Result(chat, local + matched, missed, if (local.isNotEmpty()) T("З файлів експорту: {0} (грають без інтернету)", local.size) else null)
    }
}

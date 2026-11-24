package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class LocalPlaylist(val id: Long, val name: String, val count: Int, val thumbnail: String?, val created: Long, val description: String = "", val source: String? = null, val customCover: Boolean = false)
data class PlayRow(val song: Song, val startedAt: Long, val listenedMs: Long, val skipped: Boolean)
data class SavedItem(val kind: String, val id: String, val title: String, val subtitle: String, val thumbnail: String?, val playlistId: String?, val savedAt: Long)
data class HistoryEntry(val song: Song, val playedAt: Long)
data class Download(val song: Song, val path: String, val size: Long, val done: Boolean)

/**
 * The whole library lives in one private SQLite file on the device. Nothing here is ever uploaded;
 * account sync (if the user enables it) is a separate, explicit action.
 */
object Library {
    private lateinit var db: SQLiteDatabase
    private val version = MutableStateFlow(0L)

    private class Helper(context: Context) : SQLiteOpenHelper(context, "library.db", null, 5) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE songs(id TEXT PRIMARY KEY, json TEXT NOT NULL, liked_at INTEGER, disliked INTEGER NOT NULL DEFAULT 0, play_count INTEGER NOT NULL DEFAULT 0, last_played INTEGER, local INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, song_id TEXT NOT NULL, played_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX history_time ON history(played_at)")
            db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE playlist_songs(playlist_id INTEGER NOT NULL, position INTEGER NOT NULL, song_id TEXT NOT NULL)")
            db.execSQL("CREATE INDEX playlist_songs_idx ON playlist_songs(playlist_id, position)")
            db.execSQL("CREATE TABLE saved(kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL, thumbnail TEXT, playlist_id TEXT, saved_at INTEGER NOT NULL, PRIMARY KEY(kind, id))")
            db.execSQL("CREATE TABLE searches(query TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE downloads(song_id TEXT PRIMARY KEY, path TEXT NOT NULL, size INTEGER NOT NULL, done INTEGER NOT NULL, at INTEGER NOT NULL)")
            v2(db); v3(db); v4(db); v5(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { if (old < 2) v2(db); if (old < 3) v3(db); if (old < 4) v4(db); if (old < 5) v5(db) }
        /** Library sync with the computer: a stable id + change time per playlist, and remembered removals. */
        private fun v5(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN uid TEXT")
            db.execSQL("ALTER TABLE playlists ADD COLUMN modified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE tombstones(kind TEXT NOT NULL, key TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(kind, key))")
        }
        /** Artist photos fetched once for the statistics screen. */
        private fun v4(db: SQLiteDatabase) = db.execSQL("CREATE TABLE artist_images(id TEXT PRIMARY KEY, url TEXT NOT NULL)")
        /** Custom playlist cover (path of an image in the app's private storage). */
        private fun v3(db: SQLiteDatabase) = db.execSQL("ALTER TABLE playlists ADD COLUMN cover TEXT")
        private fun v2(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE playlists ADD COLUMN source TEXT")
            // One row per listening session of a track; used only for the on-device statistics screen.
            db.execSQL("CREATE TABLE plays(id INTEGER PRIMARY KEY AUTOINCREMENT, song_id TEXT NOT NULL, started_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL, skipped INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX plays_time ON plays(started_at)")
            db.execSQL("CREATE TABLE pins(key TEXT PRIMARY KEY, json TEXT NOT NULL, at INTEGER NOT NULL)")
        }
    }

    fun init(context: Context) {
        db = Helper(context.applicationContext).writableDatabase
        fillSyncIds()
        // The pre-2.0 build kept a Room database with seeded/demo data; it is not migrated on purpose.
        context.deleteDatabase("music.db")
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun changed() { version.value++ }
    /** Playlists without a sync id (older versions, restored backups) get one. */
    private fun fillSyncIds() {
        db.execSQL("UPDATE playlists SET uid=lower(hex(randomblob(16))) WHERE uid IS NULL")
        db.execSQL("UPDATE playlists SET modified=created_at WHERE modified=0")
    }
    private fun now() = System.currentTimeMillis()
    private fun tomb(kind: String, key: String, at: Long = now()) =
        db.execSQL("INSERT INTO tombstones(kind,key,at) VALUES(?,?,?) ON CONFLICT(kind,key) DO UPDATE SET at=max(at,excluded.at)", arrayOf<Any>(kind, key, at))
    private fun untomb(kind: String, key: String) = db.execSQL("DELETE FROM tombstones WHERE kind=? AND key=?", arrayOf(kind, key))
    private fun tombAt(kind: String, key: String): Long? = q("SELECT at FROM tombstones WHERE kind=? AND key=?", kind, key).use { if (it.moveToFirst()) it.getLong(0) else null }
    private fun touch(playlistId: Long) = db.execSQL("UPDATE playlists SET modified=? WHERE id=?", arrayOf<Any>(now(), playlistId))
    fun <T> observe(block: () -> T): Flow<T> = version.map { block() }.flowOn(Dispatchers.IO)

    private inline fun <T> Cursor.list(row: Cursor.() -> T): List<T> = use { buildList { while (moveToNext()) add(row()) } }
    private fun q(sql: String, vararg args: Any?): Cursor = db.rawQuery(sql, args.map { it?.toString() }.toTypedArray())
    private fun Cursor.song(i: Int = 0) = Song.fromJson(JSONObject(getString(i)))

    // ---------- songs ----------
    fun upsert(song: Song) {
        val v = ContentValues().apply { put("id", song.id); put("json", song.toJson().toString()); put("local", if (song.isLocal) 1 else 0) }
        if (db.update("songs", v, "id=?", arrayOf(song.id)) == 0) db.insert("songs", null, v)
    }

    fun song(id: String): Song? = q("SELECT json FROM songs WHERE id=?", id).list { song() }.firstOrNull()
    fun isLiked(id: String): Boolean = q("SELECT 1 FROM songs WHERE id=? AND liked_at IS NOT NULL", id).use { it.moveToFirst() }
    fun isDisliked(id: String): Boolean = q("SELECT 1 FROM songs WHERE id=? AND disliked=1", id).use { it.moveToFirst() }

    fun setLiked(song: Song, liked: Boolean) {
        upsert(song)
        val was = isLiked(song.id)
        db.execSQL("UPDATE songs SET liked_at=?, disliked=0 WHERE id=?", arrayOf<Any?>(if (liked) System.currentTimeMillis() else null, song.id))
        if (liked) untomb("like", song.id) else if (was) tomb("like", song.id)
        changed()
    }

    fun setDisliked(song: Song, disliked: Boolean) {
        upsert(song)
        if (disliked && isLiked(song.id)) tomb("like", song.id)
        db.execSQL("UPDATE songs SET disliked=?, liked_at=CASE WHEN ?=1 THEN NULL ELSE liked_at END WHERE id=?", arrayOf<Any>(if (disliked) 1 else 0, if (disliked) 1 else 0, song.id))
        changed()
    }

    fun likedSongs(): List<Song> = q("SELECT json FROM songs WHERE liked_at IS NOT NULL ORDER BY liked_at DESC").list { song() }

    // ---------- history (only written when the user keeps history on) ----------
    fun recordPlay(song: Song) {
        if (!Settings.history.value || song.id.isEmpty()) return
        upsert(song)
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE songs SET play_count=play_count+1, last_played=? WHERE id=?", arrayOf<Any>(now, song.id))
        db.execSQL("INSERT INTO history(song_id, played_at) VALUES(?,?)", arrayOf<Any>(song.id, now))
        db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY played_at DESC LIMIT 2000)")
        changed()
    }

    fun history(): List<HistoryEntry> = q("SELECT s.json, h.played_at FROM history h JOIN songs s ON s.id=h.song_id ORDER BY h.played_at DESC LIMIT 500")
        .list { HistoryEntry(song(0), getLong(1)) }

    fun removeHistory(song: Song, playedAt: Long) { db.execSQL("DELETE FROM history WHERE song_id=? AND played_at=?", arrayOf<Any>(song.id, playedAt)); changed() }

    fun mostPlayed(limit: Int = 50): List<Song> = q("SELECT json FROM songs WHERE play_count>0 ORDER BY play_count DESC, last_played DESC LIMIT ?", limit).list { song() }

    fun clearHistory() {
        db.execSQL("DELETE FROM history")
        db.execSQL("UPDATE songs SET play_count=0, last_played=NULL")
        changed()
    }

    // ---------- searches ----------
    fun addSearch(query: String) {
        if (!Settings.searchHistory.value || query.isBlank()) return
        db.execSQL("INSERT OR REPLACE INTO searches(query, at) VALUES(?,?)", arrayOf<Any>(query.trim(), System.currentTimeMillis()))
        db.execSQL("DELETE FROM searches WHERE query NOT IN (SELECT query FROM searches ORDER BY at DESC LIMIT 50)")
        changed()
    }
    fun searches(prefix: String = ""): List<String> =
        q("SELECT query FROM searches WHERE query LIKE ? ORDER BY at DESC LIMIT 20", "${prefix.replace("%", "")}%").list { getString(0) }
    fun removeSearch(query: String) { db.execSQL("DELETE FROM searches WHERE query=?", arrayOf(query)); changed() }
    fun clearSearches() { db.execSQL("DELETE FROM searches"); changed() }

    // ---------- playlists ----------
    fun playlists(): List<LocalPlaylist> = q("""
        SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlist_id=p.id),
          (SELECT s.json FROM playlist_songs ps JOIN songs s ON s.id=ps.song_id WHERE ps.playlist_id=p.id ORDER BY ps.position LIMIT 1), p.created_at,
          p.description, p.source, p.cover
        FROM playlists p ORDER BY p.created_at DESC""").list {
        val cover = getString(7)?.takeIf { java.io.File(it).exists() }?.let { "file://${it}" }
        LocalPlaylist(getLong(0), getString(1), getInt(2), cover ?: getString(3)?.let { Song.fromJson(JSONObject(it)).thumbnail }, getLong(4), getString(5).orEmpty(), getString(6), cover != null)
    }
    fun setPlaylistCover(id: Long, path: String?) { db.execSQL("UPDATE playlists SET cover=? WHERE id=?", arrayOf<Any?>(path, id)); changed() }
    fun coverPath(id: Long): String? = q("SELECT cover FROM playlists WHERE id=?", id).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setPlaylistDescription(id: Long, text: String) { db.execSQL("UPDATE playlists SET description=?, modified=? WHERE id=?", arrayOf<Any>(text.trim(), now(), id)); changed() }
    fun playlist(id: Long): LocalPlaylist? = playlists().firstOrNull { it.id == id }
    fun createPlaylist(name: String, songs: List<Song> = emptyList(), source: String? = null): Long {
        val id = db.insert("playlists", null, ContentValues().apply { put("name", name.trim()); put("created_at", now()); put("source", source)
            put("uid", java.util.UUID.randomUUID().toString().replace("-", "")); put("modified", now()) })
        songs.forEach { addToPlaylist(id, it, notify = false) }
        changed(); return id
    }
    fun renamePlaylist(id: Long, name: String) { db.execSQL("UPDATE playlists SET name=?, modified=? WHERE id=?", arrayOf<Any>(name.trim(), now(), id)); changed() }
    fun deletePlaylist(id: Long) {
        q("SELECT uid FROM playlists WHERE id=?", id).use { if (it.moveToFirst() && !it.isNull(0)) tomb("playlist", it.getString(0)) }
        db.execSQL("DELETE FROM pins WHERE key=?", arrayOf("local:${id}"))
        db.execSQL("DELETE FROM playlist_songs WHERE playlist_id=?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM playlists WHERE id=?", arrayOf<Any>(id)); changed()
    }
    fun playlistSongs(id: Long): List<Song> = q("SELECT s.json FROM playlist_songs ps JOIN songs s ON s.id=ps.song_id WHERE ps.playlist_id=? ORDER BY ps.position", id).list { song() }
    fun addToPlaylist(id: Long, song: Song, notify: Boolean = true) {
        upsert(song)
        val pos = q("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_songs WHERE playlist_id=?", id).use { it.moveToFirst(); it.getInt(0) }
        db.execSQL("INSERT INTO playlist_songs(playlist_id, position, song_id) VALUES(?,?,?)", arrayOf<Any>(id, pos, song.id))
        touch(id)
        if (notify) changed()
    }
    fun playlistContains(id: Long, songId: String) = q("SELECT 1 FROM playlist_songs WHERE playlist_id=? AND song_id=?", id, songId).use { it.moveToFirst() }
    fun replacePlaylistSongs(id: Long, songs: List<Song>) { songs.forEach(::upsert); rewrite(id, songs) }
    fun removeFromPlaylist(id: Long, index: Int) {
        val songs = playlistSongs(id).toMutableList(); if (index !in songs.indices) return
        songs.removeAt(index); rewrite(id, songs)
    }
    fun movePlaylistSong(id: Long, from: Int, to: Int) {
        val songs = playlistSongs(id).toMutableList(); if (from !in songs.indices || to !in songs.indices) return
        songs.add(to, songs.removeAt(from)); rewrite(id, songs)
    }
    private fun rewrite(id: Long, songs: List<Song>) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM playlist_songs WHERE playlist_id=?", arrayOf<Any>(id))
            songs.forEachIndexed { i, s -> db.execSQL("INSERT INTO playlist_songs(playlist_id, position, song_id) VALUES(?,?,?)", arrayOf<Any>(id, i, s.id)) }
            touch(id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    // ---------- saved albums / playlists / artists (library bookmarks + local "subscriptions") ----------
    fun save(item: YTItem, save: Boolean) {
        val (kind, id, sub, pl) = when (item) {
            is AlbumItem -> listOf("album", item.browseId, item.subtitle.ifBlank { item.artists.joinToString(", ") { it.name } }, item.playlistId)
            is PlaylistItem -> listOf("playlist", item.browseId, item.subtitle, item.playlistId)
            is ArtistItem -> listOf("artist", item.browseId, item.subtitle, null)
            else -> return
        }
        if (save) db.execSQL("INSERT OR REPLACE INTO saved(kind,id,title,subtitle,thumbnail,playlist_id,saved_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(kind, id, item.title, sub.orEmpty(), item.thumbnail, pl, System.currentTimeMillis()))
        else db.execSQL("DELETE FROM saved WHERE kind=? AND id=?", arrayOf(kind, id))
        if (save) untomb("saved", "${kind}:${id}") else tomb("saved", "${kind}:${id}")
        changed()
    }
    fun isSaved(kind: String, id: String) = q("SELECT 1 FROM saved WHERE kind=? AND id=?", kind, id).use { it.moveToFirst() }
    fun saved(kind: String): List<SavedItem> = q("SELECT kind,id,title,subtitle,thumbnail,playlist_id,saved_at FROM saved WHERE kind=? ORDER BY saved_at DESC", kind)
        .list { SavedItem(getString(0), getString(1), getString(2), getString(3), getString(4), getString(5), getLong(6)) }

    // ---------- device files ----------
    fun replaceDeviceSongs(songs: List<Song>) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM songs WHERE local=1 AND liked_at IS NULL AND id NOT IN (SELECT song_id FROM playlist_songs) AND id NOT IN (SELECT song_id FROM history)")
            songs.forEach(::upsert)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    fun deviceSongs(): List<Song> = q("SELECT json FROM songs WHERE local=1").list { song() }.sortedBy { it.title.lowercase() }

    // ---------- downloads ----------
    fun putDownload(song: Song, path: String, size: Long, done: Boolean) {
        upsert(song)
        db.execSQL("INSERT OR REPLACE INTO downloads(song_id,path,size,done,at) VALUES(?,?,?,?,?)", arrayOf<Any>(song.id, path, size, if (done) 1 else 0, System.currentTimeMillis()))
        changed()
    }
    fun removeDownload(id: String) { db.execSQL("DELETE FROM downloads WHERE song_id=?", arrayOf(id)); changed() }
    fun downloads(): List<Download> = q("SELECT s.json, d.path, d.size, d.done FROM downloads d JOIN songs s ON s.id=d.song_id ORDER BY d.at DESC")
        .list { Download(song(0), getString(1), getLong(2), getInt(3) == 1) }
    fun downloadPath(id: String): String? = q("SELECT path FROM downloads WHERE song_id=? AND done=1", id).use { if (it.moveToFirst()) it.getString(0) else null }

    // ---------- statistics (only written when the user keeps statistics on) ----------
    fun startPlay(song: Song, startedAt: Long): Long {
        upsert(song)
        return db.insert("plays", null, ContentValues().apply { put("song_id", song.id); put("started_at", startedAt); put("listened_ms", 0) })
    }
    fun updatePlay(rowId: Long, listenedMs: Long, skipped: Boolean) {
        db.execSQL("UPDATE plays SET listened_ms=?, skipped=? WHERE id=?", arrayOf<Any>(listenedMs, if (skipped) 1 else 0, rowId))
        changed()
    }
    fun plays(since: Long): List<PlayRow> = q("SELECT s.json, p.started_at, p.listened_ms, p.skipped FROM plays p JOIN songs s ON s.id=p.song_id WHERE p.started_at>=? AND p.listened_ms>0 ORDER BY p.started_at", since)
        .list { PlayRow(song(0), getLong(1), getLong(2), getInt(3) == 1) }
    fun firstPlayOfArtists(): Map<String, Long> {
        val out = HashMap<String, Long>()
        q("SELECT s.json, MIN(p.started_at) FROM plays p JOIN songs s ON s.id=p.song_id GROUP BY p.song_id").list { song(0) to getLong(1) }
            .forEach { (song, t) -> song.artists.forEach { a -> val k = a.id ?: a.name; if ((out[k] ?: Long.MAX_VALUE) > t) out[k] = t } }
        return out
    }
    fun artistImages(): Map<String, String> = q("SELECT id, url FROM artist_images").list { getString(0) to getString(1) }.toMap()
    fun putArtistImage(id: String, url: String) {
        db.execSQL("INSERT OR REPLACE INTO artist_images(id, url) VALUES(?,?)", arrayOf(id, url)); changed()
    }
    // ---------- inputs for the on-device personal home page ----------
    /** Distinct songs heard recently (history, or listening sessions if history is off). */
    fun recentSongs(limit: Int): List<Song> = q("""
        SELECT s.json FROM songs s JOIN (
          SELECT song_id, MAX(t) AS last FROM (SELECT song_id, played_at AS t FROM history UNION ALL SELECT song_id, started_at AS t FROM plays) GROUP BY song_id
        ) r ON r.song_id = s.id ORDER BY r.last DESC LIMIT ?""", limit).list { song() }

    /** Most-played songs since [since] (≥ 30 s plays), falling back to play counts from history. */
    fun topSongs(since: Long, limit: Int): List<Song> {
        val fromPlays = q("SELECT s.json FROM plays p JOIN songs s ON s.id=p.song_id WHERE p.started_at>=? AND p.listened_ms>=30000 GROUP BY p.song_id ORDER BY COUNT(*) DESC, SUM(p.listened_ms) DESC LIMIT ?", since, limit).list { song() }
        return fromPlays.ifEmpty { q("SELECT json FROM songs WHERE play_count>0 ORDER BY play_count DESC, last_played DESC LIMIT ?", limit).list { song() } }
    }

    /** Liked songs that haven't been played for a month (or never). */
    fun forgottenLikes(limit: Int): List<Song> = q("""
        SELECT s.json FROM songs s WHERE s.liked_at IS NOT NULL AND s.local=0 AND
          COALESCE((SELECT MAX(started_at) FROM plays WHERE song_id=s.id), s.last_played, 0) < ?
        ORDER BY RANDOM() LIMIT ?""", System.currentTimeMillis() - 30L * 86_400_000, limit).list { song() }

    fun recentlyPlayedIds(since: Long): Set<String> =
        q("SELECT song_id FROM history WHERE played_at>=? UNION SELECT song_id FROM plays WHERE started_at>=?", since, since).list { getString(0) }.toSet()

    fun clearStats() { db.execSQL("DELETE FROM plays"); changed() }

    // ---------- pins ("Закріплене" on the home screen, like YouTube Music's speed dial) ----------
    fun pin(item: YTItem, pin: Boolean) {
        if (pin) ItemCodec.encode(item)?.let { db.execSQL("INSERT OR REPLACE INTO pins(key,json,at) VALUES(?,?,?)", arrayOf<Any>(item.key, it.toString(), System.currentTimeMillis())) }
        else db.execSQL("DELETE FROM pins WHERE key=?", arrayOf(item.key))
        changed()
    }
    fun isPinned(key: String) = q("SELECT 1 FROM pins WHERE key=?", key).use { it.moveToFirst() }
    fun pins(): List<YTItem> = q("SELECT json FROM pins ORDER BY at DESC").list { ItemCodec.decode(JSONObject(getString(0))) }.filterNotNull().map { item ->
        if (item is LocalPlaylistItem) playlist(item.id)?.let { LocalPlaylistItem(it.id, it.name, it.count, it.thumbnail) } ?: item else item
    }

    // ---------- backup ----------
    private val backupTables = listOf("songs", "history", "playlists", "playlist_songs", "saved", "searches", "plays", "pins", "artist_images")

    /** Everything except downloaded audio (too large, and re-downloadable). */
    fun dump(): JSONObject {
        val out = JSONObject()
        for (t in backupTables) {
            val rows = org.json.JSONArray()
            q("SELECT * FROM ${t}").use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                    for (i in 0 until c.columnCount) when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                        Cursor.FIELD_TYPE_STRING -> o.put(c.getColumnName(i), c.getString(i))
                        Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                        else -> {}
                    }
                    rows.put(o)
                }
            }
            out.put(t, rows)
        }
        return out
    }

    fun restore(data: JSONObject) {
        db.beginTransaction()
        try {
            for (t in backupTables) db.execSQL("DELETE FROM ${t}")
            for (t in backupTables) {
                val rows = data.optJSONArray(t) ?: continue
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i)
                    val v = ContentValues()
                    o.keys().forEach { k -> when (val x = o.get(k)) { is Int -> v.put(k, x.toLong()); is Long -> v.put(k, x); is Double -> v.put(k, x); else -> v.put(k, x.toString()) } }
                    db.insertWithOnConflict(t, null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        fillSyncIds()
        changed()
    }

    /** Wipes every trace of listening activity and the library. */
    fun wipeAll() {
        for (t in listOf("history", "playlist_songs", "playlists", "saved", "searches", "downloads", "songs", "plays", "pins", "tombstones")) db.execSQL("DELETE FROM ${t}")
        changed()
    }

    // ---------- library sync with the computer (net/Link.kt; same rules as desktop/src/db.rs) ----------
    /** Changes whenever anything that is synced changes (cheap; checked after library changes). */
    fun syncFingerprint(): String = q("SELECT (SELECT COUNT(*) FROM songs WHERE liked_at IS NOT NULL AND local=0) || ':' || IFNULL((SELECT MAX(liked_at) FROM songs),0) || ':' || (SELECT COUNT(*) FROM saved) || ':' || IFNULL((SELECT MAX(saved_at) FROM saved),0) || ':' || (SELECT COUNT(*) FROM playlists) || ':' || IFNULL((SELECT MAX(modified) FROM playlists),0) || ':' || (SELECT COUNT(*) FROM tombstones) || ':' || IFNULL((SELECT MAX(at) FROM tombstones),0)").use { it.moveToFirst(); it.getString(0) }

    /** Likes, saved albums / playlists / artists, local playlists and remembered removals, in the shared wire format. */
    fun syncDump(): JSONObject {
        val arr = { org.json.JSONArray() }
        val likes = arr(); q("SELECT json, liked_at FROM songs WHERE liked_at IS NOT NULL AND local=0").list { likes.put(org.json.JSONArray().put(JSONObject(getString(0))).put(getLong(1))) }
        val saved = arr(); q("SELECT kind,id,title,subtitle,thumbnail,playlist_id,saved_at FROM saved").list {
            saved.put(org.json.JSONArray().put(JSONObject().put("kind", getString(0)).put("id", getString(1)).put("title", getString(2)).put("subtitle", getString(3))
                .put("thumb", getString(4)).put("pl", getString(5))).put(getLong(6)))
        }
        val playlists = arr(); q("SELECT id, uid, name, description, created_at, modified FROM playlists WHERE uid IS NOT NULL").list {
            Triple(getLong(0), JSONObject().put("uid", getString(1)).put("name", getString(2)).put("desc", getString(3).orEmpty()).put("created", getLong(4)).put("modified", getLong(5)), Unit)
        }.forEach { (id, o, _) ->
            o.put("songs", org.json.JSONArray().apply { playlistSongs(id).filter { !it.isLocal }.forEach { put(it.toJson()) } }); playlists.put(o)
        }
        val unlikes = arr(); val unsaved = arr(); val pldel = arr()
        q("SELECT kind, key, at FROM tombstones").list {
            val e = org.json.JSONArray().put(getString(1)).put(getLong(2))
            when (getString(0)) { "like" -> unlikes.put(e); "saved" -> unsaved.put(e); "playlist" -> pldel.put(e) }
        }
        return JSONObject().put("t", "sync").put("likes", likes).put("unlikes", unlikes).put("saved", saved).put("unsaved", unsaved).put("playlists", playlists).put("pldel", pldel)
    }

    /** Merges the computer's library: the newest change wins per like, saved item and playlist. True if anything changed. */
    fun syncMerge(o: JSONObject): Boolean {
        fun pairs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) } }.orEmpty()
        var changed = false
        db.beginTransaction()
        try {
            for (p in pairs("likes")) {
                val song = runCatching { Song.fromJson(p.getJSONObject(0)) }.getOrNull() ?: continue
                val at = p.optLong(1)
                if (song.id.isEmpty() || song.isLocal || isLiked(song.id) || (tombAt("like", song.id) ?: -1) >= at) continue
                if (song(song.id) == null) upsert(song)
                db.execSQL("UPDATE songs SET liked_at=?, disliked=0 WHERE id=?", arrayOf<Any>(at, song.id))
                untomb("like", song.id); changed = true
            }
            for (p in pairs("unlikes")) {
                val id = p.optString(0); val at = p.optLong(1)
                val liked = q("SELECT liked_at FROM songs WHERE id=? AND liked_at IS NOT NULL", id).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (liked == null) tomb("like", id, at)
                else if (liked <= at) { db.execSQL("UPDATE songs SET liked_at=NULL WHERE id=?", arrayOf(id)); tomb("like", id, at); changed = true }
            }
            for (p in pairs("saved")) {
                val s = p.optJSONObject(0) ?: continue; val at = p.optLong(1)
                val kind = s.optString("kind"); val id = s.optString("id")
                if (kind !in setOf("album", "playlist", "artist") || id.isEmpty() || isSaved(kind, id) || (tombAt("saved", "${kind}:${id}") ?: -1) >= at) continue
                fun str(k: String) = s.optString(k).takeUnless { it.isEmpty() || it == "null" }
                db.execSQL("INSERT OR REPLACE INTO saved(kind,id,title,subtitle,thumbnail,playlist_id,saved_at) VALUES(?,?,?,?,?,?,?)",
                    arrayOf<Any?>(kind, id, s.optString("title"), s.optString("subtitle"), str("thumb"), str("pl"), at))
                untomb("saved", "${kind}:${id}"); changed = true
            }
            for (p in pairs("unsaved")) {
                val key = p.optString(0); val at = p.optLong(1); val (kind, id) = key.split(':', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                val savedAt = q("SELECT saved_at FROM saved WHERE kind=? AND id=?", kind, id).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (savedAt == null) tomb("saved", key, at)
                else if (savedAt <= at) { db.execSQL("DELETE FROM saved WHERE kind=? AND id=?", arrayOf(kind, id)); tomb("saved", key, at); changed = true }
            }
            val lists = o.optJSONArray("playlists")
            for (i in 0 until (lists?.length() ?: 0)) {
                val p = lists!!.getJSONObject(i)
                val uid = p.optString("uid").ifEmpty { continue }; val modified = p.optLong("modified")
                fun byUid() = q("SELECT id, modified FROM playlists WHERE uid=?", uid).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
                var local = byUid()
                if (local == null) {
                    if ((tombAt("playlist", uid) ?: -1) >= modified) continue
                    // Same playlist on both sides before the first sync (e.g. copied with a backup): adopt the id.
                    q("SELECT id FROM playlists WHERE name=? AND created_at=?", p.optString("name"), p.optLong("created")).use { if (it.moveToFirst()) it.getLong(0) else null }
                        ?.let { db.execSQL("UPDATE playlists SET uid=? WHERE id=?", arrayOf<Any>(uid, it)); local = byUid() }
                }
                val songs = p.optJSONArray("songs")?.let { a -> (0 until a.length()).mapNotNull { runCatching { Song.fromJson(a.getJSONObject(it)) }.getOrNull() } }.orEmpty()
                val id = when {
                    local != null && local!!.second >= modified -> continue
                    local != null -> { db.execSQL("UPDATE playlists SET name=?, description=?, modified=? WHERE id=?", arrayOf<Any>(p.optString("name"), p.optString("desc"), modified, local!!.first)); local!!.first }
                    else -> db.insert("playlists", null, ContentValues().apply {
                        put("name", p.optString("name")); put("description", p.optString("desc")); put("created_at", p.optLong("created")); put("uid", uid); put("modified", modified) })
                }
                // Files that exist only on this phone never travel; keep them in the playlist.
                val deviceOnly = playlistSongs(id).filter { it.isLocal }
                db.execSQL("DELETE FROM playlist_songs WHERE playlist_id=?", arrayOf<Any>(id))
                (songs + deviceOnly).forEachIndexed { pos, s ->
                    if (song(s.id) == null) upsert(s)
                    db.execSQL("INSERT INTO playlist_songs(playlist_id, position, song_id) VALUES(?,?,?)", arrayOf<Any>(id, pos, s.id))
                }
                untomb("playlist", uid); changed = true
            }
            for (p in pairs("pldel")) {
                val uid = p.optString(0); val at = p.optLong(1)
                val local = q("SELECT id, modified FROM playlists WHERE uid=?", uid).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
                if (local == null) tomb("playlist", uid, at)
                else if (local.second <= at) {
                    db.execSQL("DELETE FROM pins WHERE key=?", arrayOf("local:${local.first}"))
                    db.execSQL("DELETE FROM playlist_songs WHERE playlist_id=?", arrayOf<Any>(local.first))
                    db.execSQL("DELETE FROM playlists WHERE id=?", arrayOf<Any>(local.first))
                    tomb("playlist", uid, at); changed = true
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) changed()
        return changed
    }
}

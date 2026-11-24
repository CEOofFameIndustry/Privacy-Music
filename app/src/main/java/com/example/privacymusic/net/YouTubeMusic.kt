package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal YouTube Music (WEB_REMIX) client.
 *
 * Guest requests carry no cookies and no account data. YouTube needs a visitorData token so that
 * "load more" continuations work; we keep the one YouTube hands out **in memory only**, so it dies with the
 * process and is never written to disk, and it is never sent together with account cookies (so a guest session
 * can't be linked to the account).
 */
object YouTubeMusic {
    private const val ORIGIN = "https://music.youtube.com"
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_ID = "67"
    private const val CLIENT_VERSION = "1.20251110.01.00"
    private val json = "application/json; charset=utf-8".toMediaType()

    @Volatile private var visitorData: String? = null

    /** Forget the guest session token (also done automatically when the app process ends). */
    fun resetSession() { visitorData = null }

    private fun context(auth: Boolean) = JSONObject().put("client", JSONObject()
        .put("clientName", CLIENT_NAME).put("clientVersion", CLIENT_VERSION)
        .put("hl", Settings.language.value).put("gl", Settings.region.value)
        .apply { if (!auth) visitorData?.let { put("visitorData", it) } })
        .put("user", JSONObject().put("lockedSafetyMode", false))

    suspend fun post(endpoint: String, body: JSONObject = JSONObject(), auth: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if (Settings.offline.value) throw IOException(T("Офлайн-режим: мережу вимкнено в налаштуваннях приватності"))
        val useAuth = auth && AccountStore.signedIn.value
        body.put("context", context(useAuth))
        val request = Request.Builder().url("${ORIGIN}/youtubei/v1/${endpoint}?prettyPrint=false")
            .post(body.toString().toRequestBody(json))
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN).header("Referer", "${ORIGIN}/")
            .header("X-YouTube-Client-Name", CLIENT_ID).header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Accept-Language", Settings.language.value)
            .apply {
                if (useAuth) tag(AccountAuth::class.java, AccountAuth)
                else visitorData?.let { header("X-Goog-Visitor-Id", it) }
            }
            .build()
        Firewall.client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("YouTube Music: HTTP ${r.code}")
            JSONObject(text).also { result ->
                if (!useAuth && visitorData == null)
                    result.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotEmpty() }?.let { visitorData = it }
            }
        }
    }

    private fun needsAuth(browseId: String) = AccountStore.signedIn.value && (
        browseId.startsWith("FEmusic_library") || browseId.startsWith("FEmusic_liked") || browseId == "FEmusic_history" ||
            browseId == "VLLM" || browseId == "VLSE" || (browseId == "FEmusic_home" && Settings.accountHome.value) ||
            (Settings.accountHome.value && (browseId == "FEmusic_explore" || browseId == "FEmusic_moods_and_genres")))

    suspend fun browse(browseId: String, params: String? = null, auth: Boolean = needsAuth(browseId)): Page {
        val body = JSONObject().put("browseId", browseId).apply { params?.let { put("params", it) } }
        val page = Parser.page(post("browse", body, auth), browseId)
        // Album playlists (OLAK5uy_…) come back without a header; show the real album page instead.
        if (page.header == null && browseId.startsWith("VLOLAK5uy_")) {
            val albumId = page.shelves.flatMap { it.items }.filterIsInstance<Song>().firstNotNullOfOrNull { it.album?.id }
            if (albumId != null) return browse(albumId)
        }
        return page
    }

    suspend fun continuation(token: String, auth: Boolean = false): Parser.Continued =
        Parser.continuation(post("browse", JSONObject().put("continuation", token), auth))

    suspend fun search(query: String, params: String? = null): Page =
        Parser.search(post("search", JSONObject().put("query", query).apply { params?.let { put("params", it) } }))

    suspend fun searchContinuation(token: String): Parser.Continued =
        Parser.continuation(post("search", JSONObject().put("continuation", token)))

    suspend fun suggestions(input: String): Pair<List<String>, List<YTItem>> =
        Parser.suggestions(post("music/get_search_suggestions", JSONObject().put("input", input)))

    suspend fun next(endpoint: WatchEndpoint, continuation: String? = null): WatchQueue {
        val body = JSONObject().put("enablePersistentPlaylistPanel", true).put("isAudioOnly", true).put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        endpoint.videoId?.let { body.put("videoId", it) }
        endpoint.playlistId?.let { body.put("playlistId", it) }
        endpoint.params?.let { body.put("params", it) }
        endpoint.index?.let { body.put("index", it) }
        continuation?.let { body.put("continuation", it) }
        val response = post("next", body)
        if (continuation != null) {
            val c = Parser.continuation(response)
            return WatchQueue(c.items.filterIsInstance<Song>(), c.next, endpoint.playlistId, null, null, null, -1)
        }
        return Parser.watch(response)
    }

    suspend fun lyrics(browseId: String): Lyrics? = Parser.lyrics(post("browse", JSONObject().put("browseId", browseId)))

    // ---------- account actions (only used when the user explicitly enabled account sync) ----------

    suspend fun like(videoId: String, liked: Boolean?) {
        val ep = when (liked) { true -> "like/like"; false -> "like/dislike"; null -> "like/removelike" }
        post(ep, JSONObject().put("target", JSONObject().put("videoId", videoId)), auth = true)
    }

    suspend fun savePlaylist(playlistId: String, save: Boolean) {
        post(if (save) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("playlistId", playlistId)), auth = true)
    }

    suspend fun subscribe(channelId: String, subscribe: Boolean) {
        post(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)), auth = true)
    }

    /** Resolve a pasted/shared URL into something we can open. */
    fun parseLink(url: String): Endpoint? {
        val u = url.trim()
        Regex("[?&]list=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)?.let { list ->
            val v = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1)
            return if (v != null) WatchEndpoint(v, list) else if (list.startsWith("OLAK") || list.startsWith("PL") || list.startsWith("RD"))
                BrowseEndpoint("VL${list}") else BrowseEndpoint("VL${list}")
        }
        Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1)?.let { return WatchEndpoint(it) }
        Regex("/browse/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)?.let { return BrowseEndpoint(it) }
        Regex("/channel/(UC[A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)?.let { return BrowseEndpoint(it) }
        return null
    }
}

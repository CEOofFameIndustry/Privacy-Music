package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Marks a request that may carry the signed-in account's cookies. Nothing else ever does. */
object AccountAuth

/**
 * Every byte the app sends goes through this firewall:
 *  - only an explicit allowlist of hosts + paths is reachable, everything else gets a local 403;
 *  - telemetry / playback-reporting endpoints are blocked even on allowed hosts;
 *  - headers are rebuilt from scratch (no device ids, no cookies, generic User-Agent);
 *  - account cookies are attached only to requests tagged [AccountAuth] going to music.youtube.com.
 */
object Firewall {
    data class Blocked(val time: Long, val method: String, val host: String, val path: String)

    private val _blocked = MutableStateFlow<List<Blocked>>(emptyList())
    val blocked = _blocked.asStateFlow()
    private val _allowed = MutableStateFlow(0L)
    val allowedCount = _allowed.asStateFlow()

    const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val telemetry = listOf(
        "/api/stats/", "/ptracking", "/youtubei/v1/log_event", "/youtubei/v1/feedback", "/youtubei/v1/att/",
        "/youtubei/v1/notification", "/generate_204", "/pagead", "/youtubei/v1/account", "/error_204",
        "/csi_204", "/youtubei/v1/player/heartbeat", "/youtubei/v1/guide", "/youtubei/v1/stats",
    )
    private val musicApi = setOf(
        "browse", "search", "next", "music/get_search_suggestions", "player", "music/get_queue",
        "like/like", "like/dislike", "like/removelike", "playlist/create", "playlist/delete",
        "browse/edit_playlist", "subscription/subscribe", "subscription/unsubscribe",
    )
    private val youtubeApi = setOf("player", "next", "visitor_id", "browse", "reel/reel_item_watch")
    private val imageHosts = Regex("^(i\\d?\\.ytimg\\.com|lh\\d\\.googleusercontent\\.com|yt\\d\\.googleusercontent\\.com|yt\\d\\.ggpht\\.com|lh\\d\\.ggpht\\.com)$")

    fun permitted(url: HttpUrl, method: String): Boolean {
        if (Settings.offline.value) return false
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val path = url.encodedPath
        if (telemetry.any { path.startsWith(it) }) return false
        val host = url.host
        return when {
            host == "music.youtube.com" -> method == "POST" && path.startsWith("/youtubei/v1/") &&
                path.removePrefix("/youtubei/v1/") in musicApi
            host == "www.youtube.com" || host == "youtubei.googleapis.com" ->
                (method == "POST" && path.startsWith("/youtubei/v1/") && path.removePrefix("/youtubei/v1/") in youtubeApi) ||
                    (method == "GET" && host == "www.youtube.com" &&
                        (path.startsWith("/s/player/") || path == "/iframe_api" || path == "/sw.js_data" ||
                            path.startsWith("/embed/") || path == "/watch"))
            host.endsWith(".googlevideo.com") -> (method == "GET" || method == "POST") && path == "/videoplayback"
            imageHosts.matches(host) -> method == "GET"
            host == "lrclib.net" -> method == "GET" && Settings.lrclib.value && path.startsWith("/api/")
            // Only used when the user imports a Spotify link: the public embed page lists the tracks, no account needed.
            host == "open.spotify.com" -> method == "GET" && path.startsWith("/embed/")
            // "Check for updates" (Settings): only this project's release info and files, only while checking.
            Updater.active && Updater.allows(host, path, method) -> true
            else -> false
        }
    }

    private val passHeaders = setOf(
        "content-type", "range", "accept", "accept-language", "origin", "referer", "x-goog-visitor-id",
        "x-youtube-client-name", "x-youtube-client-version", "x-goog-api-format-version", "x-goog-api-key",
    )

    /** NewPipe sends a consent cookie (SOCS/CONSENT) so YouTube doesn't redirect to a consent page; that's all we allow. */
    private fun consentOnly(cookie: String?): String? = cookie?.split(';')?.map { it.trim() }
        ?.filter { it.startsWith("SOCS=") || it.startsWith("CONSENT=") }?.joinToString("; ")?.ifEmpty { null }

    private fun blockedResponse(input: Request): Response {
        val entry = Blocked(System.currentTimeMillis(), input.method, input.url.host, input.url.encodedPath)
        _blocked.value = (listOf(entry) + _blocked.value).take(200)
        return Response.Builder().request(input).protocol(Protocol.HTTP_1_1).code(403)
            .message("Blocked by PrivacyMusic").body(ByteArray(0).toResponseBody()).build()
    }

    /** OkHttp redirects would bypass an application interceptor, so redirects are followed here, re-checked each hop. */
    private val interceptor = Interceptor { chain ->
        var request = chain.request()
        repeat(6) {
            if (!permitted(request.url, request.method)) return@Interceptor blockedResponse(request)
            val response = chain.proceed(sanitize(request))
            val location = response.header("Location")
            if (response.code !in setOf(301, 302, 303, 307, 308) || location == null) return@Interceptor response
            val target = response.request.url.resolve(location) ?: return@Interceptor response
            response.close()
            request = if (response.code == 303) request.newBuilder().url(target).get().build()
                else request.newBuilder().url(target).build()
        }
        blockedResponse(request)
    }

    private fun sanitize(input: Request): Request {
        _allowed.value++
        val clean = Request.Builder().url(input.url).method(input.method, input.body)
        for ((name, value) in input.headers) {
            if (name.lowercase() in passHeaders) clean.addHeader(name, value)
        }
        val ua = input.header("User-Agent")
        // Keep NewPipe's mobile-client UAs (needed for stream access); otherwise a generic browser UA.
        clean.header("User-Agent", if (ua != null && (ua.startsWith("com.google.") || ua.contains("Cobalt"))) ua else WEB_UA)
        consentOnly(input.header("Cookie"))?.let { clean.header("Cookie", it) }
        if (input.tag(AccountAuth::class.java) != null && input.url.host == "music.youtube.com") {
            AccountStore.cookieHeader()?.let { cookie ->
                clean.header("Cookie", cookie)
                AccountStore.sapisid()?.let { clean.header("Authorization", sapisidHash(it)) }
                clean.header("X-Goog-AuthUser", "0")
                clean.header("X-Origin", "https://music.youtube.com")
            }
        }
        return clean.build()
    }

    private fun sapisidHash(sapisid: String): String {
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("${ts} ${sapisid} https://music.youtube.com".toByteArray())
        return "SAPISIDHASH ${ts}_" + digest.joinToString("") { "%02x".format(it) }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            // Never store anything a server tries to set.
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = emptyList<Cookie>()
        })
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(interceptor)
        .build()

    fun clearLog() { _blocked.value = emptyList() }
}

package com.example.privacymusic.net

import com.example.privacymusic.i18n.T

import com.example.privacymusic.data.AudioQuality
import com.example.privacymusic.data.Settings
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Audio stream resolution via the open-source NewPipe extractor (handles signature / throttling deobfuscation
 * without any Google SDK). All of its traffic goes through [Firewall].
 */
object Streams {
    data class Resolved(val url: String, val mimeType: String, val bitrate: Int, val itag: Int, val expiresAt: Long, val length: Long?)

    private val resolved = ConcurrentHashMap<String, Resolved>()
    @Volatile private var initialized = false

    private object PrivateDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = OkRequest.Builder().url(request.url()).method(request.httpMethod(), body)
            request.headers().forEach { (name, values) -> builder.removeHeader(name); values.forEach { builder.addHeader(name, it) } }
            Firewall.client.newCall(builder.build()).execute().use { r ->
                if (r.code == 429) throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("Rate limited", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    @Synchronized fun init() {
        if (initialized) return
        NewPipe.init(PrivateDownloader, Localization(Settings.language.value, Settings.region.value), ContentCountry(Settings.region.value))
        initialized = true
    }

    fun invalidate(videoId: String) { resolved.remove(videoId) }

    fun cached(videoId: String): Resolved? = resolved[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }

    /** Blocking; call from a background thread. */
    fun resolve(videoId: String, metered: Boolean): Resolved {
        cached(videoId)?.let { return it }
        init()
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${videoId}")
        extractor.fetchPage()
        val audio = extractor.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        if (audio.isEmpty()) throw IOException("No audio stream for ${videoId}")
        // Prefer the original audio track (not an auto-dubbed one).
        val original = audio.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { audio }
        val low = when (Settings.audioQuality) { AudioQuality.LOW -> true; AudioQuality.HIGH -> false; AudioQuality.AUTO -> metered }
        val pick: AudioStream = if (low) original.minBy { it.averageBitrate.takeIf { b -> b > 0 } ?: Int.MAX_VALUE }
            else original.maxBy { it.averageBitrate }
        val url = pick.content
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
            ?: (System.currentTimeMillis() + 3 * 3600_000)
        val length = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return Resolved(url, pick.format?.mimeType ?: "audio/*", pick.averageBitrate, pick.itag, expire, length).also { resolved[videoId] = it }
    }
}

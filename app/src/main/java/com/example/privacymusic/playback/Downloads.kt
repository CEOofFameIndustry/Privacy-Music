package com.example.privacymusic.playback

import com.example.privacymusic.i18n.T

import android.content.Context
import com.example.privacymusic.data.Library
import com.example.privacymusic.data.Song
import com.example.privacymusic.net.Firewall
import com.example.privacymusic.net.Streams
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Offline downloads into the app's private storage (not visible to other apps, not backed up). */
object Downloads {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val slots = Semaphore(2)
    /** videoId -> progress 0..1 while downloading. */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val failed = MutableStateFlow<Set<String>>(emptySet())

    fun init(context: Context) { dir = File(context.filesDir, "music").apply { mkdirs() } }

    fun download(songs: List<Song>) = songs.filterNot { it.isLocal }.forEach(::download)

    @Synchronized fun download(song: Song) {
        if (jobs[song.id]?.isActive == true || Library.downloadPath(song.id) != null) return
        failed.value -= song.id
        progress.value += song.id to 0f
        jobs[song.id] = scope.launch {
            slots.withPermit {
                val part = File(dir, "${song.id}.part")
                try {
                    val stream = Streams.resolve(song.id, metered = false)
                    val ext = if (stream.mimeType.contains("webm")) "webm" else "m4a"
                    val target = File(dir, "${song.id}.${ext}")
                    Firewall.client.newCall(Request.Builder().url(stream.url).header("Range", "bytes=0-").build()).execute().use { r ->
                        if (r.code != 200 && r.code != 206) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("empty")
                        val total = body.contentLength().takeIf { it > 0 } ?: stream.length ?: -1L
                        part.outputStream().use { out ->
                            val src = body.byteStream(); val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                            while (true) {
                                ensureActive()
                                val n = src.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total > 0 && done - last > 256 * 1024) { last = done; progress.value += song.id to (done.toFloat() / total) }
                            }
                        }
                    }
                    if (!part.renameTo(target)) throw IOException("rename failed")
                    Library.putDownload(song, target.absolutePath, target.length(), true)
                } catch (e: CancellationException) {
                    part.delete(); throw e
                } catch (e: Exception) {
                    android.util.Log.w("Downloads", "Download of ${song.id} failed", e)
                    part.delete(); failed.value += song.id
                } finally {
                    progress.value -= song.id
                }
            }
        }
    }

    @Synchronized fun cancel(id: String) { jobs.remove(id)?.cancel(); progress.value -= id }

    fun delete(id: String) {
        cancel(id)
        Library.downloadPath(id)?.let { File(it).delete() }
        dir.listFiles { f -> f.name.startsWith("${id}.") }?.forEach { it.delete() }
        Library.removeDownload(id)
    }

    fun deleteAll() { Library.downloads().forEach { delete(it.song.id) } }
}

package com.example.privacymusic.playback

import com.example.privacymusic.i18n.T

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.privacymusic.data.*
import com.example.privacymusic.net.Firewall
import com.example.privacymusic.net.Parser
import com.example.privacymusic.net.Streams
import com.example.privacymusic.net.YouTubeMusic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the current queue came from, so it can keep growing like YouTube Music's endless radio. */
data class QueueSource(val endpoint: WatchEndpoint, val continuation: String?, val title: String? = null)

@OptIn(UnstableApi::class)
object Playback {
    private lateinit var app: Context
    lateinit var player: ExoPlayer; private set
    lateinit var cache: SimpleCache; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val songs = HashMap<String, Song>()
    private var source: QueueSource? = null
    private var extending: Job? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    val current = MutableStateFlow<Song?>(null)
    val isPlaying = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val queue = MutableStateFlow<List<Song>>(emptyList())
    val index = MutableStateFlow(-1)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val error = MutableStateFlow<String?>(null)
    val queueTitle = MutableStateFlow<String?>(null)
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt = _sleepAt.asStateFlow()
    val sleepEndOfSong = MutableStateFlow(false)
    private var sleepJob: Job? = null
    private var recordedFor: String? = null
    private var retried: String? = null

    fun init(context: Context) {
        app = context.applicationContext
        cache = SimpleCache(File(app.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(Settings.cacheMb.value * 1024L * 1024L), StandaloneDatabaseProvider(app))
        player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build()
        player.skipSilenceEnabled = Settings.skipSilence.value
        player.addListener(listener)
        // Save listening progress periodically so statistics survive the process being killed mid-song.
        scope.launch { while (isActive) { delay(30_000); if (player.isPlaying) { tick(); persist(session, final = false) } } }
        restoreQueue()
    }

    /** Binding a controller starts [PlaybackService] (notification, lock screen, headset buttons). */
    fun connect(context: Context) {
        if (controllerFuture != null) return
        controllerFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }
    fun disconnect() { controllerFuture?.let { MediaController.releaseFuture(it) }; controllerFuture = null }

    // ---------- data source: downloaded file > disk cache > freshly resolved stream ----------
    private fun metered(): Boolean = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private fun dataSourceFactory(): DataSource.Factory {
        val remote = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Firewall.client))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val local = DefaultDataSource.Factory(app)
        val switching = DataSource.Factory { SwitchingDataSource(local.createDataSource(), remote.createDataSource()) }
        return ResolvingDataSource.Factory(switching) { spec ->
            if (spec.uri.scheme != "ytm") return@Factory spec
            val id = spec.uri.host ?: return@Factory spec
            Library.downloadPath(id)?.let { path -> if (File(path).exists()) return@Factory spec.withUri(Uri.fromFile(File(path))) }
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(id))
            if (length > 0 && cache.isCached(id, spec.position, if (spec.length == C.LENGTH_UNSET.toLong()) length - spec.position else spec.length))
                return@Factory spec.buildUpon().setKey(id).build()
            val stream = Streams.resolve(id, metered())
            spec.buildUpon().setUri(stream.url.toUri()).setKey(id).build()
        }
    }

    private class SwitchingDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
        private var active: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) { local.addTransferListener(transferListener); remote.addTransferListener(transferListener) }
        override fun open(dataSpec: DataSpec): Long {
            val target = if (dataSpec.uri.scheme in setOf("file", "content", "android.resource")) local else remote
            active = target
            return target.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
        override fun getUri() = active?.uri
        override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()
        override fun close() { try { active?.close() } finally { active = null } }
    }

    private fun mediaItem(song: Song): MediaItem {
        songs[song.id] = song
        val meta = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artistText).setAlbumTitle(song.album?.name)
            .setArtworkUri(Parser.resize(song.thumbnail, 544)?.toUri())
            .setExtras(Bundle().apply { putString("song", song.toJson().toString()) })
            .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
        val uri = if (song.isLocal) song.id.toUri() else "ytm://${song.id}".toUri()
        return MediaItem.Builder().setMediaId(song.id).setUri(uri).setCustomCacheKey(song.id).setMediaMetadata(meta).build()
    }

    private fun songOf(item: MediaItem?): Song? = item?.let { songs[it.mediaId] ?: it.mediaMetadata.extras?.getString("song")?.let { j -> Song.fromJson(JSONObject(j)) } }

    // ---------- queue control ----------
    fun playQueue(list: List<Song>, start: Int = 0, from: QueueSource? = null, title: String? = null, shuffled: Boolean = false) {
        if (list.isEmpty()) return
        source = from
        queueTitle.value = title ?: from?.title
        error.value = null
        player.shuffleModeEnabled = false
        // Shuffle physically reorders the queue (like YouTube Music / Spotify), so "Up next" shows the real order.
        originalOrder = if (shuffled) list.map { it.id } else null
        shuffle.value = shuffled
        val ordered = if (shuffled) list.shuffled() else list
        player.setMediaItems(ordered.map(::mediaItem), if (shuffled) 0 else start.coerceIn(list.indices), 0)
        player.prepare(); player.play()
        sync()
    }

    /** Continues a queue that was playing on another device (hand-off): same song, same position. */
    fun playQueueAt(list: List<Song>, index: Int, positionMs: Long, title: String?, play: Boolean) {
        if (list.isEmpty()) return
        source = null
        queueTitle.value = title
        error.value = null
        player.shuffleModeEnabled = false
        originalOrder = null
        shuffle.value = false
        player.setMediaItems(list.map(::mediaItem), index.coerceIn(list.indices), positionMs.coerceAtLeast(0))
        player.prepare()
        if (play) player.play()
        sync()
    }

    /** Tapping a song in YouTube Music starts it plus its radio ("Далі"). */
    fun playSong(song: Song, title: String? = null) {
        if (song.isLocal) { playQueue(listOf(song)); return }
        playQueue(listOf(song), 0, QueueSource(WatchEndpoint(song.id, "RDAMVM${song.id}"), null), title ?: T("Радіо: {0}", song.title))
        loadRadio(WatchEndpoint(song.id, "RDAMVM${song.id}"), keepFirst = true)
    }

    fun playEndpoint(endpoint: WatchEndpoint, title: String? = null, shuffled: Boolean = false) {
        scope.launch {
            try {
                val q = YouTubeMusic.next(endpoint)
                if (q.songs.isEmpty()) { error.value = T("Чергу не вдалося завантажити"); return@launch }
                val start = q.currentIndex.coerceAtLeast(0)
                playQueue(q.songs, start, QueueSource(endpoint.copy(playlistId = q.playlistId ?: endpoint.playlistId), q.continuation, title), title, shuffled)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = T("Немає з'єднання з YouTube Music") }
        }
    }

    fun startRadio(song: Song) = playEndpoint(WatchEndpoint(song.id, "RDAMVM${song.id}"), T("Радіо: {0}", song.title))

    private fun loadRadio(endpoint: WatchEndpoint, keepFirst: Boolean) {
        extending?.cancel()
        extending = scope.launch {
            try {
                val q = YouTubeMusic.next(endpoint)
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val add = q.songs.filter { it.id !in existing && !Library.isDisliked(it.id) }
                if (keepFirst && player.mediaItemCount > 0) player.addMediaItems(add.map(::mediaItem))
                source = QueueSource(endpoint.copy(playlistId = q.playlistId ?: endpoint.playlistId), q.continuation, source?.title)
                sync()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun maybeExtend() {
        if (extending?.isActive == true || player.mediaItemCount == 0) return
        if (player.currentMediaItemIndex < player.mediaItemCount - 3 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val src = source
        extending = scope.launch {
            try {
                val more = when {
                    src?.continuation != null -> YouTubeMusic.next(src.endpoint, src.continuation).also { source = src.copy(continuation = it.continuation) }.songs
                    Settings.autoplay.value -> {
                        val last = songOf(player.getMediaItemAt(player.mediaItemCount - 1)) ?: return@launch
                        if (last.isLocal) return@launch
                        val ep = WatchEndpoint(last.id, "RDAMVM${last.id}")
                        YouTubeMusic.next(ep).also { source = QueueSource(ep, it.continuation, src?.title) }.songs
                    }
                    else -> emptyList()
                }
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val add = more.filter { it.id !in existing && !Library.isDisliked(it.id) }
                if (add.isNotEmpty()) player.addMediaItems(add.map(::mediaItem))
                sync()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    fun playNext(list: List<Song>) {
        if (player.mediaItemCount == 0) { playQueue(list); return }
        player.addMediaItems(player.currentMediaItemIndex + 1, list.map(::mediaItem)); sync()
    }
    fun addToQueue(list: List<Song>) {
        if (player.mediaItemCount == 0) { playQueue(list); return }
        player.addMediaItems(list.map(::mediaItem)); sync()
    }
    fun removeAt(i: Int) { if (i in 0 until player.mediaItemCount) { player.removeMediaItem(i); sync() } }
    fun move(from: Int, to: Int) { if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) { player.moveMediaItem(from, to); sync() } }
    fun skipTo(i: Int) { if (i in 0 until player.mediaItemCount) { player.seekToDefaultPosition(i); player.play() } }
    /** Removes everything after the current track (YouTube Music's "Clear queue"). */
    fun clearUpcoming() {
        val i = player.currentMediaItemIndex
        if (i >= 0 && i < player.mediaItemCount - 1) player.removeMediaItems(i + 1, player.mediaItemCount)
        source = null; sync()
    }
    fun clearQueue() { player.clearMediaItems(); source = null; sync() }

    fun toggle() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
        player.playWhenReady = !player.playWhenReady
    }
    fun next() = player.seekToNext()
    fun previous() = if (player.currentPosition > 3000) player.seekTo(0) else player.seekToPrevious()
    fun seek(ms: Long) = player.seekTo(ms)
    /** Song ids in their original order while shuffle is on (to restore it when shuffle is turned off). */
    private var originalOrder: List<String>? = null

    fun toggleShuffle() = setShuffle(!shuffle.value)

    fun setShuffle(on: Boolean) {
        val count = player.mediaItemCount
        val ids = (0 until count).map { player.getMediaItemAt(it).mediaId }
        if (on) {
            val cur = player.currentMediaItemIndex.coerceAtLeast(0)
            originalOrder = ids
            // Already played + current stay put; everything after the current song is shuffled.
            if (count > 0) reorder(ids.take(cur + 1) + ids.drop(cur + 1).shuffled())
        } else {
            val orig = originalOrder
            if (orig != null) {
                val pool = ids.toMutableList()
                val target = orig.mapNotNull { id -> if (pool.remove(id)) id else null } + pool // songs added later go to the end
                reorder(target)
            }
            originalOrder = null
        }
        shuffle.value = on
        sync()
    }

    /** Moves items into [target] order (ids may repeat) without interrupting the song that's playing. */
    private fun reorder(target: List<String>) {
        for (t in target.indices) {
            val from = (t until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == target[t] } ?: continue
            if (from != t) player.moveMediaItem(from, t)
        }
    }
    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        sync()
    }
    fun setSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed, player.playbackParameters.pitch) }
    fun setSkipSilence(on: Boolean) { Settings.skipSilence.set(on); player.skipSilenceEnabled = on }

    fun sleep(minutes: Int?) {
        sleepJob?.cancel(); _sleepAt.value = null; sleepEndOfSong.value = false
        if (minutes == null) return
        if (minutes == -1) { sleepEndOfSong.value = true; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = scope.launch { delay(minutes * 60_000L); player.pause(); _sleepAt.value = null }
    }

    // ---------- state ----------
    private fun sync() {
        val count = player.mediaItemCount
        queue.value = (0 until count).mapNotNull { songOf(player.getMediaItemAt(it)) }
        index.value = player.currentMediaItemIndex
        current.value = songOf(player.currentMediaItem)
        repeat.value = player.repeatMode
        isPlaying.value = player.isPlaying
        saveQueueSoon()
    }

    // ---------- on-device listening statistics ----------
    private class Session(val song: Song, val startedAt: Long) { var row = -1L; var listened = 0L; var since = 0L }
    private var session: Session? = null
    private val statsIo = Dispatchers.IO.limitedParallelism(1)

    private fun tick() {
        val s = session ?: return
        if (s.since > 0) { val now = android.os.SystemClock.elapsedRealtime(); s.listened += now - s.since; s.since = if (player.isPlaying) now else 0 }
    }

    private fun persist(s: Session?, final: Boolean) {
        if (s == null || !Settings.stats.value || s.listened < 5_000) return
        val listened = s.listened
        val duration = (s.song.duration ?: 0) * 1000L
        val skipped = final && listened < 30_000 && (duration == 0L || listened < duration - 10_000)
        scope.launch(statsIo) {
            if (s.row < 0) s.row = Library.startPlay(s.song, s.startedAt)
            Library.updatePlay(s.row, listened, skipped)
        }
    }

    private fun newSession() {
        val song = current.value ?: run { session = null; return }
        session = Session(song, System.currentTimeMillis()).also { if (player.isPlaying) it.since = android.os.SystemClock.elapsedRealtime() }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (sleepEndOfSong.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { player.pause(); sleepEndOfSong.value = false }
            error.value = null
            tick(); persist(session, final = true)
            sync(); maybeExtend()
            newSession()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = sync()
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
            tick()
            if (playing) {
                if (session?.song?.id != current.value?.id) newSession()
                session?.since = android.os.SystemClock.elapsedRealtime()
            } else persist(session, final = false)
            val song = current.value
            if (playing && song != null && recordedFor != "${song.id}@${player.currentMediaItemIndex}") {
                recordedFor = "${song.id}@${player.currentMediaItemIndex}"
                scope.launch(Dispatchers.IO) { Library.recordPlay(song) }
            }
            if (!playing) saveQueueSoon()
        }
        override fun onPlaybackStateChanged(state: Int) { buffering.value = state == Player.STATE_BUFFERING }
        // The notification / headset / car can ask for shuffle: do it the same "real reorder" way.
        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            if (enabled) { player.shuffleModeEnabled = false; if (!shuffle.value) setShuffle(true) }
        }
        override fun onRepeatModeChanged(mode: Int) = sync()
        override fun onPlayerError(e: PlaybackException) {
            Diag.log("Відтворення: ${e.errorCodeName} ${e.cause?.javaClass?.simpleName.orEmpty()}")
            val id = player.currentMediaItem?.mediaId
            if (id != null && retried != id && !songs[id].let { it?.isLocal == true }) {
                // Stream URLs expire / get rate-limited: resolve again once.
                retried = id
                Streams.invalidate(id)
                player.prepare(); player.play()
                return
            }
            error.value = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> T("Немає з'єднання з мережею")
                else -> T("Цей трек зараз недоступний для відтворення")
            }
            if (player.hasNextMediaItem()) scope.launch { delay(1500); player.seekToNext(); player.prepare(); player.play() }
        }
    }

    // ---------- queue persistence (local file only) ----------
    private var saveJob: Job? = null
    private fun saveQueueSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            val file = File(app.filesDir, "queue.json")
            if (!Settings.persistQueue.value) { file.delete(); return@launch }
            val json = JSONObject().put("index", player.currentMediaItemIndex).put("position", player.currentPosition)
                .put("title", queueTitle.value)
                .put("songs", JSONArray().apply { queue.value.take(300).forEach { put(it.toJson()) } })
            withContext(Dispatchers.IO) { runCatching { file.writeText(json.toString()) } }
        }
    }

    private fun restoreQueue() {
        if (!Settings.persistQueue.value) return
        val file = File(app.filesDir, "queue.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val list = json.optJSONArray("songs")?.let { a -> (0 until a.length()).map { Song.fromJson(a.getJSONObject(it)) } }.orEmpty()
        if (list.isEmpty()) return
        queueTitle.value = json.optString("title").takeUnless { it.isEmpty() || it == "null" }
        // Not prepared on purpose: nothing is requested from YouTube until the user presses play.
        player.setMediaItems(list.map(::mediaItem), json.optInt("index").coerceIn(list.indices), json.optLong("position"))
        sync()
    }

    fun forgetQueueFile() { File(app.filesDir, "queue.json").delete() }
}

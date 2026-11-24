package com.example.privacymusic.net

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.privacymusic.data.Library
import com.example.privacymusic.data.Settings
import com.example.privacymusic.data.Song
import com.example.privacymusic.i18n.T
import com.example.privacymusic.playback.Playback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Devices nearby (like Spotify Connect, but only between the user's own devices on the local network):
 * pairs with the Privacy Music desktop app, shows and controls what plays there, hands playback over in either
 * direction and syncs the library. End-to-end encrypted (see [LinkProtocol]); off by default; no servers.
 */
object Link {
    data class Remote(val song: Song?, val playing: Boolean, val pos: Long, val dur: Long, val vol: Float, val title: String?, val received: Long) {
        fun position(): Long { val p = if (playing) pos + (System.currentTimeMillis() - received) else pos; return if (dur > 0) p.coerceAtMost(dur) else p }
    }
    data class Device(val id: String, val name: String, val online: Boolean, val remote: Remote?)
    data class Pairable(val id: String, val name: String, val address: InetAddress, val port: Int, val seen: Long)
    sealed class Pairing {
        data object Idle : Pairing()
        data object Searching : Pairing()
        data class Connecting(val name: String) : Pairing()
        data class Confirm(val name: String, val code: String) : Pairing()
        data class Done(val name: String) : Pairing()
        data class Failed(val message: String) : Pairing()
    }

    val devices = MutableStateFlow<List<Device>>(emptyList())
    val pairable = MutableStateFlow<List<Pairable>>(emptyList())
    val pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    /** The computer whose playback the mini player controls while nothing plays here. */
    val controlling = MutableStateFlow<String?>(null)
    val lastSync = MutableStateFlow<Long?>(null)
    val running = MutableStateFlow(false)
    /** Short messages for the user ("continued from …"). */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private class Peer(val id: String, val name: String, val spk: ByteArray, val address: String?, val port: Int) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("spk", Base64.encodeToString(spk, Base64.NO_WRAP)).put("addr", address).put("port", port)
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private val sessions = ConcurrentHashMap<String, LinkProtocol.Session>()
    private val remotes = ConcurrentHashMap<String, Remote>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private var udp: DatagramSocket? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var pendingPair: LinkProtocol.Session? = null
    @Volatile private var lastFingerprint = ""
    private var myDecision: CompletableDeferred<Boolean>? = null

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    /** Android 17+ asks the user before an app may talk to devices on the local network. */
    fun needsPermission() = Build.VERSION.SDK_INT >= 37
    fun hasPermission() = !needsPermission() || app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun init(context: Context) {
        app = context.applicationContext
        apply()
        // Tell the computer what plays here whenever it changes, and every few seconds while connected.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            var wasPlaying = false
            launch { combine(Playback.current, Playback.isPlaying, Playback.index) { _, _, _ -> }.collect { sendState() } }
            launch { while (isActive) { delay(4000); if (sessions.isNotEmpty()) sendState() } }
            // Library changes (a like, a playlist edit…) reach the computer within a few seconds.
            launch(Dispatchers.IO) {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                Library.observe { Unit }.debounce(2000).collect {
                    val fp = Library.syncFingerprint()
                    if (fp != lastFingerprint) { lastFingerprint = fp; if (sessions.isNotEmpty()) syncNow() }
                }
            }
            Playback.isPlaying.collect { playing ->
                if (playing && !wasPlaying) { controlling.value = null; if (Settings.linkSingle.value) pauseOthers() }
                wasPlaying = playing
            }
        }
    }

    // ---------- identity (private key kept wrapped by a hardware-backed Keystore key) ----------
    private fun prefs() = app.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val identity: Pair<PrivateKey, ByteArray> by lazy {
        val p = prefs()
        val stored = p.getString("key", null)?.let { runCatching { unwrap(it) }.getOrNull() }
        if (stored != null) {
            val priv = LinkProtocol.privateKey(stored)
            priv to Base64.decode(p.getString("spk", ""), Base64.NO_WRAP)
        } else {
            val kp = LinkProtocol.newKeyPair()
            val spk = LinkProtocol.sec1(kp.public)
            p.edit().putString("key", wrap(kp.private.encoded)).putString("spk", Base64.encodeToString(spk, Base64.NO_WRAP)).commit()
            kp.private to spk
        }
    }
    /** Random, stable id of this phone for the paired computer (created once, never changes). */
    private val myId: String by lazy { prefs().getString("id", null) ?: LinkProtocol.hex(LinkProtocol.randomBytes(12)).also { prefs().edit().putString("id", it).commit() } }
    fun deviceName(): String = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun peers(): List<Peer> = runCatching {
        val a = JSONArray(prefs().getString("peers", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Peer(it.getString("id"), it.optString("name"), Base64.decode(it.getString("spk"), Base64.NO_WRAP), it.optString("addr").takeIf { s -> s.isNotEmpty() && s != "null" }, it.optInt("port", LinkProtocol.TCP_PORT)) }
    }.getOrDefault(emptyList())
    @Synchronized private fun savePeers(list: List<Peer>) { prefs().edit().putString("peers", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply(); publish() }
    @Synchronized private fun updatePeer(id: String, f: (Peer) -> Peer) = savePeers(peers().map { if (it.id == id) f(it) else it })

    private const val WRAP_ALIAS = "privacymusic_link_wrap"
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun wrap(raw: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP)
    }
    private fun unwrap(blob: String): ByteArray {
        val (iv, data) = blob.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(data, Base64.NO_WRAP))
    }

    private fun publish() {
        devices.value = peers().map { Device(it.id, it.name, sessions.containsKey(it.id), remotes[it.id]) }
    }

    // ---------- start / stop ----------
    @Synchronized fun apply() {
        val on = Settings.link.value && hasPermission()
        if (on && jobs.isEmpty()) {
            running.value = true
            jobs += scope.launch { listen() }
            jobs += scope.launch { reconnectLoop() }
        } else if (!on && jobs.isNotEmpty()) {
            jobs.forEach { it.cancel() }; jobs.clear()
            runCatching { udp?.close() }; udp = null
            runCatching { multicast?.release() }; multicast = null
            sessions.values.forEach { s -> runCatching { s.send(JSONObject().put("t", "bye")) }; s.close() }
            sessions.clear(); remotes.clear(); pairable.value = emptyList(); pairing.value = Pairing.Idle; controlling.value = null
            running.value = false
        }
        publish()
    }

    /** Beacons from computers: paired ones are recognised by their tag, others only show up while pairing. */
    private suspend fun listen() {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("privacymusic-link").apply { setReferenceCounted(false); acquire() }
        val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 3000; bind(InetSocketAddress(LinkProtocol.UDP_PORT)) } }.getOrNull() ?: return
        udp = sock
        val keys = HashMap<String, ByteArray>()
        val buf = ByteArray(4096)
        while (scope.isActive && !sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try { sock.receive(packet) } catch (_: java.net.SocketTimeoutException) { prunePairable(); continue } catch (_: Exception) { break }
            val o = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            if (o.optInt("pm") != 1) continue
            val port = o.optInt("p", LinkProtocol.TCP_PORT)
            val nonce = runCatching { LinkProtocol.unhex(o.getString("n")) }.getOrNull() ?: continue
            val tags = o.optJSONArray("t")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            for (peer in peers()) {
                val key = keys[peer.id] ?: runCatching { LinkProtocol.beaconKey(identity.first, peer.spk) }.getOrNull()?.also { keys[peer.id] = it } ?: continue
                if (LinkProtocol.beaconTag(key, nonce) in tags && !sessions.containsKey(peer.id)) connect(peer, packet.address, port)
            }
            o.optJSONObject("pair")?.let { pair ->
                if (pairing.value is Pairing.Searching) {
                    val item = Pairable(pair.optString("id"), pair.optString("name").take(64), packet.address, port, System.currentTimeMillis())
                    pairable.value = (pairable.value.filter { it.id != item.id } + item).sortedBy { it.name }
                }
            }
        }
    }
    private fun prunePairable() { val cut = System.currentTimeMillis() - 10_000; if (pairable.value.any { it.seen < cut }) pairable.value = pairable.value.filter { it.seen >= cut } }

    /** If beacons don't get through (some routers drop broadcasts), try the last known address now and then. */
    private suspend fun reconnectLoop() {
        while (scope.isActive) {
            for (p in peers()) if (!sessions.containsKey(p.id) && p.address != null) runCatching { connect(p, InetAddress.getByName(p.address), p.port) }
            delay(20_000)
        }
    }

    private fun connect(peer: Peer, address: InetAddress, port: Int) {
        if (!connecting.add(peer.id)) return
        scope.launch {
            try {
                val s = LinkProtocol.connect(address, port, myId, deviceName(), identity.first, identity.second, peer.spk)
                if (peer.address != address.hostAddress || peer.port != port) updatePeer(peer.id) { Peer(it.id, it.name, it.spk, address.hostAddress, port) }
                connecting.remove(peer.id)
                run(peer.id, s)
            } catch (_: Exception) {
            } finally { connecting.remove(peer.id) }
        }
    }

    // ---------- pairing (the settings screen drives these) ----------
    fun startPairing() { pairable.value = emptyList(); pairing.value = Pairing.Searching }
    fun cancelPairing() {
        myDecision?.complete(false); myDecision = null
        pendingPair?.let { runCatching { it.send(JSONObject().put("t", "pair").put("ok", false)) }; it.close() }; pendingPair = null
        pairing.value = Pairing.Idle
    }
    fun confirmPairing(ok: Boolean) { myDecision?.complete(ok); if (!ok) cancelPairing() }

    fun pairWith(target: Pairable) {
        pairing.value = Pairing.Connecting(target.name)
        scope.launch {
            val s = try { LinkProtocol.connect(target.address, target.port, myId, deviceName(), identity.first, identity.second, null) }
                catch (e: Exception) { pairing.value = Pairing.Failed(T("Не вдалося з'єднатися: {0}", e.message ?: "")); return@launch }
            pendingPair = s
            val mine = CompletableDeferred<Boolean>().also { myDecision = it }
            pairing.value = Pairing.Confirm(target.name, s.code ?: "")
            // The computer's answer arrives whenever its user decides; read it meanwhile.
            val theirs = CompletableDeferred<Boolean>()
            val reader = scope.launch {
                val ok = runCatching { s.receive() }.getOrNull()?.let { it.optString("t") == "pair" && it.optBoolean("ok") } ?: false
                theirs.complete(ok)
            }
            val ours = withTimeoutOrNull(130_000) { mine.await() } ?: false
            runCatching { s.send(JSONObject().put("t", "pair").put("ok", ours)) }
            val accepted = ours && (withTimeoutOrNull(130_000) { theirs.await() } ?: false)
            reader.cancel()
            myDecision = null; pendingPair = null
            if (!accepted) { s.close(); if (pairing.value !is Pairing.Idle) pairing.value = Pairing.Failed(T("Пристрій не під'єднано")); return@launch }
            savePeers(peers().filter { it.id != s.peer.id } + Peer(s.peer.id, target.name, s.peer.spk, target.address.hostAddress, target.port))
            pairing.value = Pairing.Done(target.name)
            run(s.peer.id, s)
        }
    }

    /** Pairing by typed address (when the router blocks broadcasts). Only private / local addresses are accepted. */
    fun pairWithAddress(text: String) {
        scope.launch {
            val (host, port) = text.substringBefore(':') to (text.substringAfter(':', "").toIntOrNull() ?: LinkProtocol.TCP_PORT)
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
            // A literal IP only (no DNS lookups), and only the local network.
            if (addr == null || !host.all { it.isDigit() || it == '.' || it == ':' } || !(addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)) {
                pairing.value = Pairing.Failed(T("Це не адреса в локальній мережі")); return@launch
            }
            pairWith(Pairable("", host, addr, port, System.currentTimeMillis()))
        }
    }

    fun forget(id: String) {
        sessions.remove(id)?.let { runCatching { it.send(JSONObject().put("t", "bye")) }; it.close() }
        remotes.remove(id)
        savePeers(peers().filter { it.id != id })
        if (controlling.value == id) controlling.value = null
    }

    // ---------- one connection ----------
    private suspend fun run(id: String, s: LinkProtocol.Session) {
        sessions.put(id, s)?.close()
        publish()
        s.send(JSONObject().put("t", "info").put("name", deviceName()))
        runCatching { s.send(withContext(Dispatchers.Main) { stateMsg() }) }
        if (Settings.linkSync.value) withContext(Dispatchers.IO) { s.send(Library.syncDump()) }
        val ping = scope.launch { while (isActive) { delay(15_000); runCatching { s.send(JSONObject().put("t", "ping")) } } }
        try {
            while (true) {
                val msg = s.receive()
                if (msg.optString("t") == "bye") break
                handle(id, msg)
            }
        } catch (_: Exception) {
        } finally {
            ping.cancel(); s.close()
            if (sessions[id] === s) { sessions.remove(id); remotes.remove(id); if (controlling.value == id) controlling.value = null }
            publish()
        }
    }

    private fun send(id: String, msg: JSONObject) { val s = sessions[id] ?: return; scope.launch { runCatching { s.send(msg) } } }
    private fun broadcast(msg: JSONObject) { for (id in sessions.keys) send(id, msg) }

    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun volume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /** Must run on the main thread (the player lives there). */
    private fun stateMsg(): JSONObject {
        val p = Playback.player
        val song = Playback.current.value
        return JSONObject().put("t", "state").put("song", song?.takeUnless { it.isLocal }?.toJson() ?: JSONObject.NULL)
            .put("playing", Playback.isPlaying.value).put("pos", p.currentPosition.coerceAtLeast(0))
            .put("dur", p.duration.takeIf { it > 0 } ?: ((song?.duration ?: 0) * 1000L)).put("vol", volume().toDouble())
            .put("title", Playback.queueTitle.value).put("len", Playback.queue.value.size).put("idx", Playback.index.value)
    }
    private fun sendState() {
        if (sessions.isEmpty()) return
        val msg = runCatching { stateMsg() }.getOrNull() ?: return
        broadcast(msg)
    }
    private fun pauseOthers() { for ((id, r) in remotes) if (r.playing) send(id, JSONObject().put("t", "cmd").put("c", "pause")) }

    private fun songs(a: JSONArray?): List<Song> = a?.let { (0 until it.length()).mapNotNull { i -> runCatching { Song.fromJson(it.getJSONObject(i)) }.getOrNull() } }.orEmpty()

    private suspend fun handle(from: String, o: JSONObject) {
        when (o.optString("t")) {
            "info" -> o.optString("name").take(64).takeIf { it.isNotBlank() }?.let { n -> if (peers().any { it.id == from && it.name != n }) updatePeer(from) { Peer(it.id, n, it.spk, it.address, it.port) } }
            "state" -> {
                val song = o.optJSONObject("song")?.let { runCatching { Song.fromJson(it) }.getOrNull() }
                remotes[from] = Remote(song, o.optBoolean("playing"), o.optLong("pos"), o.optLong("dur"), o.optDouble("vol", 1.0).toFloat(),
                    o.optString("title").takeUnless { it.isEmpty() || it == "null" }, System.currentTimeMillis())
                publish()
            }
            "cmd" -> if (Settings.linkControl.value) withContext(Dispatchers.Main) {
                when (o.optString("c")) {
                    "toggle" -> Playback.toggle()
                    "play" -> if (!Playback.isPlaying.value) Playback.toggle()
                    "pause" -> if (Playback.player.playWhenReady) Playback.player.pause()
                    "next" -> Playback.next()
                    "prev" -> Playback.previous()
                    "seek" -> Playback.seek(o.optLong("v"))
                    "skip" -> Playback.skipTo(o.optInt("v"))
                    "vol" -> { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); audio.setStreamVolume(AudioManager.STREAM_MUSIC, (o.optDouble("v", .5) * max).toInt().coerceIn(0, max), 0) }
                    "handoff" -> handOff(from)
                }
            }
            "take" -> withContext(Dispatchers.Main) {
                val list = songs(o.optJSONArray("queue"))
                if (list.isEmpty()) return@withContext
                Playback.playQueueAt(list, o.optInt("index"), o.optLong("pos"), o.optString("title").takeUnless { it.isEmpty() || it == "null" }, o.optBoolean("playing", true))
                controlling.value = null
                events.tryEmit(T("Продовжено з пристрою «{0}»", peers().firstOrNull { it.id == from }?.name ?: ""))
            }
            "enqueue" -> withContext(Dispatchers.Main) {
                val list = songs(o.optJSONArray("songs")); if (list.isEmpty()) return@withContext
                if (Playback.queue.value.isEmpty()) Playback.playQueue(list) else if (o.optBoolean("next")) Playback.playNext(list) else Playback.addToQueue(list)
                events.tryEmit(T("«{0}» надіслав пісень: {1}", peers().firstOrNull { it.id == from }?.name ?: "", list.size))
            }
            "sync" -> if (Settings.linkSync.value) { Library.syncMerge(o); lastFingerprint = Library.syncFingerprint(); lastSync.value = System.currentTimeMillis() }
        }
    }

    // ---------- actions from the UI ----------
    /** Sends what plays here to [to] and pauses here: the music continues there from the same moment. */
    fun handOff(to: String) {
        val q = Playback.queue.value.filter { !it.isLocal }
        val cur = Playback.current.value ?: return
        if (q.isEmpty()) return
        val idx = q.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        send(to, JSONObject().put("t", "take").put("queue", JSONArray().apply { q.forEach { put(it.toJson()) } }).put("index", idx)
            .put("pos", Playback.player.currentPosition).put("title", Playback.queueTitle.value).put("playing", true))
        Playback.player.pause()
        controlling.value = to
    }
    /** Asks [from] to continue its music on this phone. */
    fun pull(from: String) { controlling.value = null; send(from, JSONObject().put("t", "cmd").put("c", "handoff")) }
    fun remote(id: String, command: String, value: Any? = null) {
        if (command in setOf("toggle", "play", "pause")) remotes[id]?.let { r ->
            remotes[id] = r.copy(pos = r.position(), received = System.currentTimeMillis(), playing = when (command) { "play" -> true; "pause" -> false; else -> !r.playing })
            publish()
        }
        send(id, JSONObject().put("t", "cmd").put("c", command).apply { if (value != null) put("v", value) })
    }
    fun syncNow() { if (Settings.linkSync.value) scope.launch { val d = Library.syncDump(); broadcast(d) } }
}

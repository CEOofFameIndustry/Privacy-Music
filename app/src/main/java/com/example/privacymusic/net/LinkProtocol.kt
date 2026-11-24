package com.example.privacymusic.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The "devices nearby" wire protocol, shared byte for byte with the desktop app (desktop/src/link.rs).
 * Plain JVM crypto only (P-256 ECDH, HKDF-SHA256, AES-256-GCM): no extra libraries, nothing leaves the local network.
 *
 * Handshake (phone = client): commit(hash of our hello) → their hello → our hello. Keys come from a fresh ephemeral
 * ECDH plus the paired static ECDH, so only the two paired devices can read a session, and old sessions stay closed
 * even if a key leaks later. While pairing, both screens show a 6-digit code derived from the whole exchange.
 */
object LinkProtocol {
    const val TCP_PORT = 47719
    const val UDP_PORT = 47720
    private const val MAX_FRAME = 32 shl 20
    private val PROTO = "PMLINK1".toByteArray()
    private val rng = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    // ---------- primitives ----------
    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()
    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    /** Uncompressed SEC1 point (65 bytes), the form the desktop uses. */
    fun sec1(key: PublicKey): ByteArray { val w = (key as ECPublicKey).w; return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY) }
    fun publicKey(sec1: ByteArray): PublicKey {
        if (sec1.size != 65 || sec1[0] != 4.toByte()) throw IOException("bad key")
        val point = ECPoint(BigInteger(1, sec1.copyOfRange(1, 33)), BigInteger(1, sec1.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
    }
    private fun fixed(v: BigInteger): ByteArray { val r = v.toByteArray(); return when { r.size == 32 -> r; r.size > 32 -> r.copyOfRange(r.size - 32, r.size); else -> ByteArray(32 - r.size) + r } }
    fun dh(priv: PrivateKey, pub: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, len: Int) = hkdf(salt, ikm, info.toByteArray(), len)
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream(); var t = ByteArray(0); var i = 1
        while (out.size() < len) { t = hmac(prk, t + info + byteArrayOf(i.toByte())); out.write(t); i++ }
        return out.toByteArray().copyOf(len)
    }
    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Key that marks beacons of one paired computer (strangers on the network cannot link the beacons). */
    fun beaconKey(myPriv: PrivateKey, peerSpk: ByteArray) = hkdf(ByteArray(32), dh(myPriv, publicKey(peerSpk)), "pm-link-v1 beacon", 32)
    fun beaconTag(key: ByteArray, nonce: ByteArray) = hex(hmac(key, nonce).copyOf(8))

    // ---------- framing ----------
    fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) throw IOException("frame too big")
        return ByteArray(len).also { input.readFully(it) }
    }
    fun writeFrame(out: DataOutputStream, data: ByteArray) { synchronized(out) { out.writeInt(data.size); out.write(data); out.flush() } }

    /** AES-256-GCM, nonce = 4 zero bytes + 64-bit counter, one key per direction. */
    class Channel(txKey: ByteArray, rxKey: ByteArray) {
        private val tx = SecretKeySpec(txKey, "AES"); private val rx = SecretKeySpec(rxKey, "AES")
        private var ntx = 0L; private var nrx = 0L
        private fun nonce(n: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (n ushr (56 - 8 * i)).toByte() }
        @Synchronized fun seal(msg: JSONObject): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, tx, GCMParameterSpec(128, nonce(ntx++))); doFinal(msg.toString().toByteArray())
        }
        @Synchronized fun open(data: ByteArray): JSONObject = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, rx, GCMParameterSpec(128, nonce(nrx++))); JSONObject(String(doFinal(data)))
        }
    }

    class Hello(val id: String, val name: String, val kind: String, val spk: ByteArray, val epk: ByteArray, val n: ByteArray, val pair: Boolean) {
        fun json(): JSONObject = JSONObject().put("v", 1).put("id", id).put("name", name).put("kind", kind)
            .put("spk", b64.encodeToString(spk)).put("epk", b64.encodeToString(epk)).put("n", b64.encodeToString(n)).put("pair", pair)
        fun transcript(out: java.io.ByteArrayOutputStream) { for (b in listOf(id.toByteArray(), spk, epk, n)) { out.write(b.size shr 8); out.write(b.size and 0xff); out.write(b) } }
        companion object {
            fun parse(o: JSONObject) = Hello(o.optString("id"), o.optString("name").take(64), o.optString("kind"),
                unb64.decode(o.optString("spk")), unb64.decode(o.optString("epk")), unb64.decode(o.optString("n")), o.optBoolean("pair"))
        }
    }

    class Session(val socket: Socket, val input: DataInputStream, val output: DataOutputStream, val channel: Channel, val peer: Hello, val code: String?) {
        /** Sealing and writing together, so frames always go out in nonce order. */
        fun send(msg: JSONObject) = synchronized(this) { writeFrame(output, channel.seal(msg)) }
        fun receive(): JSONObject = channel.open(readFrame(input))
        fun close() = runCatching { socket.close() }
    }

    /**
     * Connects to a computer. [expectSpk] = its stored key (a normal session), or null to pair (then [Session.code]
     * holds the code to compare on both screens).
     */
    fun connect(host: java.net.InetAddress, port: Int, myId: String, myName: String, myPriv: PrivateKey, mySpk: ByteArray, expectSpk: ByteArray?): Session {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val eph = newKeyPair()
            val pairing = expectSpk == null
            // Our name only travels while pairing; afterwards it is sent inside the encrypted channel.
            val me = Hello(myId, if (pairing) myName else "", "phone", mySpk, sec1(eph.public), randomBytes(32), pairing)
            val mine = me.json().toString().toByteArray()
            writeFrame(output, JSONObject().put("v", 1).put("c", b64.encodeToString(sha256(mine))).toString().toByteArray())
            val them = Hello.parse(JSONObject(String(readFrame(input))))
            if (them.n.size != 32) throw IOException("bad hello")
            if (expectSpk != null && !expectSpk.contentEquals(them.spk)) throw IOException("unexpected device key")
            writeFrame(output, mine)
            val t = java.io.ByteArrayOutputStream().apply { write(PROTO); me.transcript(this); them.transcript(this) }
            val th = sha256(t.toByteArray())
            val ikm = dh(eph.private, publicKey(them.epk)) + dh(myPriv, publicKey(them.spk))
            val keys = hkdf(th, ikm, "pm-link-v1 keys", 64)
            val code = if (pairing) hkdf(th, ikm, "pm-link-v1 sas", 4).let { s ->
                val v = ((s[0].toLong() and 0xff) shl 24) or ((s[1].toLong() and 0xff) shl 16) or ((s[2].toLong() and 0xff) shl 8) or (s[3].toLong() and 0xff)
                "%06d".format(v % 1_000_000)
            } else null
            // The computer pings every 15 s; silence for longer means the connection is gone.
            socket.soTimeout = if (pairing) 150_000 else 45_000
            return Session(socket, input, output, Channel(keys.copyOfRange(0, 32), keys.copyOfRange(32, 64)), them, code)
        } catch (e: Exception) { runCatching { socket.close() }; throw e }
    }
}

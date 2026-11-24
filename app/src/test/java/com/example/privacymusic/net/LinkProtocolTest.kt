package com.example.privacymusic.net

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LinkProtocolTest {
    @Test fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = LinkProtocol.unhex("000102030405060708090a0b0c")
        val info = LinkProtocol.unhex("f0f1f2f3f4f5f6f7f8f9")
        val okm = LinkProtocol.hkdf(salt, ikm, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", LinkProtocol.hex(okm))
    }

    @Test fun keysRoundTrip() {
        val a = LinkProtocol.newKeyPair(); val b = LinkProtocol.newKeyPair()
        val pubA = LinkProtocol.publicKey(LinkProtocol.sec1(a.public))
        assertArrayEquals(LinkProtocol.dh(b.private, a.public), LinkProtocol.dh(b.private, pubA))
        assertArrayEquals(LinkProtocol.dh(a.private, b.public), LinkProtocol.dh(b.private, a.public))
        val k = ByteArray(32) { 3 }
        val (x, y) = LinkProtocol.Channel(k, k) to LinkProtocol.Channel(k, k)
        assertEquals("ping", y.open(x.seal(JSONObject().put("t", "ping"))).getString("t"))
    }

    /**
     * Talks to a real desktop app (run it with PM_LINK_TEST=1 and devices nearby enabled; see the desktop notes).
     * Skipped unless PM_LINK_DESKTOP=1.
     */
    @Test fun interopWithDesktop() {
        assumeTrue(System.getenv("PM_LINK_DESKTOP") == "1")
        val me = LinkProtocol.newKeyPair()
        val spk = LinkProtocol.sec1(me.public)
        val beacons = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 6000; bind(java.net.InetSocketAddress(LinkProtocol.UDP_PORT)) }
        // 1. The pairing beacon names the computer.
        var pairName: String? = null
        repeat(5) { if (pairName == null) {
            val p = DatagramPacket(ByteArray(4096), 4096); beacons.receive(p)
            pairName = JSONObject(String(p.data, 0, p.length)).optJSONObject("pair")?.optString("name")
        } }
        assertNotNull(pairName)
        // 2. Pair.
        val host = InetAddress.getLoopbackAddress()
        val s = LinkProtocol.connect(host, LinkProtocol.TCP_PORT, "testphone01", "Test Phone", me.private, spk, null)
        println("phone code ${s.code}")
        s.send(JSONObject().put("t", "pair").put("ok", true))
        val ack = s.receive()
        assertEquals("pair", ack.getString("t")); assertTrue(ack.getBoolean("ok"))
        val desktopSpk = s.peer.spk
        val info = s.receive(); assertEquals("info", info.getString("t")); println("desktop name ${info.getString("name")}")
        val state = s.receive(); assertEquals("state", state.getString("t"))
        s.send(JSONObject().put("t", "info").put("name", "Test Phone 2"))
        s.send(JSONObject().put("t", "state").put("playing", true).put("pos", 1234).put("dur", 200000).put("vol", 0.5)
            .put("song", JSONObject().put("id", "XFkzRNyygfk").put("title", "Creep").put("artists", org.json.JSONArray().put(JSONObject().put("n", "Radiohead"))).put("duration", 239)))
        s.close()
        // 3. A normal (paired) session with the stored key.
        val s2 = LinkProtocol.connect(host, LinkProtocol.TCP_PORT, "testphone01", "", me.private, spk, desktopSpk)
        assertEquals("info", s2.receive().getString("t"))
        s2.send(JSONObject().put("t", "sync").put("likes", org.json.JSONArray().put(org.json.JSONArray()
            .put(JSONObject().put("id", "7wtfhZwyrcc").put("title", "Believer").put("artists", org.json.JSONArray().put(JSONObject().put("n", "Imagine Dragons")))).put(1700000000000L))))
        Thread.sleep(500)
        s2.close()
        // 4. Its beacon now carries a tag only we can recognise.
        val key = LinkProtocol.beaconKey(me.private, desktopSpk)
        var tagged = false
        repeat(4) { if (!tagged) {
            val p = DatagramPacket(ByteArray(4096), 4096); beacons.receive(p)
            val o = JSONObject(String(p.data, 0, p.length))
            val tag = LinkProtocol.beaconTag(key, LinkProtocol.unhex(o.getString("n")))
            val tags = o.getJSONArray("t"); tagged = (0 until tags.length()).any { tags.getString(it) == tag }
        } }
        assertTrue(tagged)
        // 5. A stranger with another key is refused.
        val stranger = LinkProtocol.newKeyPair()
        val refused = runCatching {
            val s3 = LinkProtocol.connect(host, LinkProtocol.TCP_PORT, "stranger", "", stranger.private, LinkProtocol.sec1(stranger.public), desktopSpk)
            s3.receive()
        }.isFailure
        assertTrue(refused)
        beacons.close()
    }
}

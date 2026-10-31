package com.theveloper.pixelplay.shared.p2p

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerWireCodecTest {

    private val helloJson =
        """{"v":1,"type":"hello","id":"7f6c-uuid","from":"a1b2-uuid","ts":1710000000000,
           "name":"Mac mini","platform":"macos","appVersion":"0.1.0"}"""

    @Test
    fun `hello round trip preserves all envelope and identity fields`() {
        val decoded = PeerWireCodec.decode(helloJson).first
        assertTrue(decoded is P2pHello)
        assertEquals(1, decoded.v)
        assertEquals("7f6c-uuid", decoded.id)
        assertEquals("a1b2-uuid", decoded.from)
        assertEquals(1710000000000L, decoded.ts)
        assertEquals("Mac mini", decoded.name)
        assertEquals("macos", decoded.platform)
        assertEquals("0.1.0", decoded.appVersion)

        val encoded = PeerWireCodec.encode(decoded)
        val recoded = PeerWireCodec.decode(encoded).first
        assertEquals(decoded, recoded)
    }

    @Test
    fun `encoded hello carries required envelope keys`() {
        val encoded = PeerWireCodec.encode(
            P2pHello(id = "id-1", from = "from-1", ts = 42L, name = "Pixel", platform = "android", appVersion = "1.0"),
        )
        val root = PeerWireCodec.parseJsonObject(encoded)!!
        assertEquals(1, (root["v"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals("hello", (root["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Pixel", (root["name"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `hello_ack round trip keeps replyTo`() {
        val ack = P2pHelloAck(id = "ack-1", from = "callee", ts = 7L, replyTo = "hello-1", name = "Phone")
        val decoded = PeerWireCodec.decode(PeerWireCodec.encode(ack)).first
        assertEquals(ack, decoded)
        assertEquals("hello-1", decoded?.replyTo)
    }

    @Test
    fun `null replyTo is omitted from the wire`() {
        val encoded = PeerWireCodec.encode(P2pPing(id = "p1", from = "f", ts = 1L))
        assertTrue(!encoded.contains("replyTo"))
        val decoded = PeerWireCodec.decode(encoded).first
        assertTrue(decoded is P2pPing)
        assertNull(decoded.replyTo)
    }

    @Test
    fun `state_update round trip with artist extension`() {
        val state = P2pStateUpdate(
            id = "s1", from = "f", ts = 9L, track = "Song", status = "playing",
            positionSec = 42.1, seq = 17, artist = "Artist",
        )
        val decoded = PeerWireCodec.decode(PeerWireCodec.encode(state)).first
        assertEquals(state, decoded)
    }

    @Test
    fun `state_update decodes reference mac frame without artist field`() {
        val macFrame =
            """{"v":1,"type":"state_update","id":"x","from":"y","ts":5,
               "track":"Fake Track 01","status":"paused","positionSec":123.4,"seq":17}"""
        val decoded = PeerWireCodec.decode(macFrame).first
        assertTrue(decoded is P2pStateUpdate)
        assertEquals("Fake Track 01", decoded.track)
        assertEquals("paused", decoded.status)
        assertEquals(123.4, decoded.positionSec, 1e-9)
        assertEquals(17L, decoded.seq)
        assertNull(decoded.artist)
    }

    @Test
    fun `pong and test and goodbye round trip`() {
        val pong = P2pPong(id = "pg", from = "f", ts = 2L, replyTo = "ping-9")
        val test = P2pTest(id = "t", from = "f", ts = 3L, text = "hi from mac")
        val goodbye = P2pGoodbye(id = "g", from = "f", ts = 4L, reason = "app_shutdown")
        assertEquals(pong, PeerWireCodec.decode(PeerWireCodec.encode(pong)).first)
        assertEquals(test, PeerWireCodec.decode(PeerWireCodec.encode(test)).first)
        assertEquals(goodbye, PeerWireCodec.decode(PeerWireCodec.encode(goodbye)).first)
    }

    @Test
    fun `command round trip with seek position`() {
        val command = P2pCommand(
            id = "c1", from = "f", ts = 10L, action = "seek", positionSec = 42.5,
        )
        val decoded = PeerWireCodec.decode(PeerWireCodec.encode(command)).first
        assertEquals(command, decoded)
        assertTrue(decoded is P2pCommand)
        assertEquals("seek", decoded.action)
        assertEquals(42.5, decoded.positionSec!!, 1e-9)
    }

    @Test
    fun `command without positionSec round trips and omits the field`() {
        val encoded = PeerWireCodec.encode(P2pCommand(id = "c2", from = "f", ts = 11L, action = "pause"))
        assertTrue(!encoded.contains("positionSec"), encoded)
        val decoded = PeerWireCodec.decode(encoded).first
        assertTrue(decoded is P2pCommand)
        assertNull(decoded.positionSec)
        assertEquals("pause", decoded.action)
    }

    @Test
    fun `command decodes reference mac frame shape`() {
        val macFrame =
            """{"v":1,"type":"command","id":"x","from":"y","ts":1793446489411.0278,
               "action":"pause","positionSec":42.5}"""
        val (message, failure) = PeerWireCodec.decode(macFrame)
        assertNull(failure)
        assertTrue(message is P2pCommand)
        assertEquals("pause", message.action)
        assertEquals(42.5, message.positionSec!!, 1e-9)
    }

    @Test
    fun `command action wire mapping covers exactly the contract values`() {
        for (wire in listOf("play", "pause", "toggle", "stop", "seek", "next", "previous")) {
            assertEquals(wire, P2pCommandAction.fromWire(wire)?.wireValue)
        }
        assertNull(P2pCommandAction.fromWire("rewind"))
        assertTrue(P2pCommandAction.fromWire("seek")!!.needsPosition)
        assertTrue(P2pCommandAction.entries.none { it != P2pCommandAction.SEEK && it.needsPosition })
    }

    @Test
    fun `state_update round trips with session fields trackHash and playbackId`() {
        val state = P2pStateUpdate(
            id = "s2", from = "f", ts = 12L, track = "Hallelujah", status = "playing",
            positionSec = 42.5, seq = 3, artist = "Artist",
            trackHash = "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8",
            playbackId = "0f0f0f0f-1111-4222-8333-444455556666",
        )
        val decoded = PeerWireCodec.decode(PeerWireCodec.encode(state)).first
        assertEquals(state, decoded)
    }

    @Test
    fun `state_update without session fields omits them from the wire`() {
        val encoded = PeerWireCodec.encode(
            P2pStateUpdate(id = "s3", from = "f", ts = 13L, track = "t", status = "paused", positionSec = 1.0, seq = 4),
        )
        assertTrue(!encoded.contains("trackHash") && !encoded.contains("playbackId"), encoded)
        val decoded = PeerWireCodec.decode(encoded).first
        assertTrue(decoded is P2pStateUpdate)
        assertNull(decoded.trackHash)
        assertNull(decoded.playbackId)
    }

    @Test
    fun `unknown discriminator decodes to UNKNOWN_TYPE not MALFORMED`() {
        val frame = """{"v":1,"type":"future_thing","id":"i","from":"f","ts":1}"""
        val (message, failure) = PeerWireCodec.decode(frame)
        assertNull(message)
        assertEquals(PeerWireCodec.DecodeFailure.UNKNOWN_TYPE, failure)
    }

    @Test
    fun `missing required fields decode to MALFORMED`() {
        val (message, failure) = PeerWireCodec.decode("""{"v":1,"type":"ping","ts":1}""")
        assertNull(message)
        assertEquals(PeerWireCodec.DecodeFailure.MALFORMED, failure)
    }

    @Test
    fun `non json garbage decodes to MALFORMED`() {
        val (message, failure) = PeerWireCodec.decode("not json at all")
        assertNull(message)
        assertEquals(PeerWireCodec.DecodeFailure.MALFORMED, failure)
    }

    @Test
    fun `unknown extra keys are ignored on decode`() {
        val frame =
            """{"v":1,"type":"ping","id":"i","from":"f","ts":1,"futureField":{"nested":true}}"""
        val (message, failure) = PeerWireCodec.decode(frame)
        assertNull(failure)
        assertTrue(message is P2pPing)
    }

    @Test
    fun `envelope violation reports missing id from or ts`() {
        assertEquals("id", PeerWireCodec.envelopeViolation(P2pPing(id = "", from = "f", ts = 1L)))
        assertEquals("from", PeerWireCodec.envelopeViolation(P2pPing(id = "i", from = "", ts = 1L)))
        assertEquals("ts", PeerWireCodec.envelopeViolation(P2pPing(id = "i", from = "f", ts = 0L)))
        assertNull(PeerWireCodec.envelopeViolation(P2pPing(id = "i", from = "f", ts = 1L)))
    }

    @Test
    fun `state_update from mac with fractional ts and seq decodes`() {
        // Measured interop case: the reference mac peer emits ts/seq as JSON doubles.
        val macFrame =
            """{"from":"fffffffb","id":"c46a87f2","positionSec":0,"seq":1,"status":"stopped",
               "track":"Fake Track 01","ts":1793446489411.0278,"type":"state_update","v":1}"""
        val (message, failure) = PeerWireCodec.decode(macFrame)
        assertNull(failure)
        assertTrue(message is P2pStateUpdate)
        assertEquals(1793446489411L, message.ts)
        assertEquals(1L, message.seq)
    }

    @Test
    fun `fractional ts still encodes as integral long`() {
        val encoded = PeerWireCodec.encode(P2pPing(id = "i", from = "f", ts = 1793446489411L))
        assertTrue(encoded.contains("\"ts\":1793446489411"))
        assertTrue(!encoded.contains(".0278") && !encoded.contains("1793446489411.0"))
    }

    @Test
    fun `playback status wire mapping covers exactly the contract values`() {
        assertEquals(P2pPlaybackStatus.PLAYING, P2pPlaybackStatus.fromWire("playing"))
        assertEquals(P2pPlaybackStatus.PAUSED, P2pPlaybackStatus.fromWire("paused"))
        assertEquals(P2pPlaybackStatus.STOPPED, P2pPlaybackStatus.fromWire("stopped"))
        assertNull(P2pPlaybackStatus.fromWire("buffering"))
    }
}

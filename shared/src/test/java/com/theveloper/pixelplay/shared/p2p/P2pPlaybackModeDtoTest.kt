package com.theveloper.pixelplay.shared.p2p

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Playback-mode extension (added 2026-11-02): `shuffle`/`repeat` on `state_update` are
 * OPTIONAL and must never fail a frame — absent/invalid values decode to null.
 */
class P2pPlaybackModeDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    private fun decodeStateUpdate(extra: String = ""): P2pStateUpdate =
        json.decodeFromString(
            P2pStateUpdate.serializer(),
            """{"v":1,"type":"state_update","id":"m1","from":"p1","ts":1000,
                "track":"T","status":"playing","positionSec":1.0,"seq":1$extra}""",
        )

    private fun decodePayload(jsonText: String): P2pPeerPlaybackState =
        json.decodeFromString(P2pPeerPlaybackState.serializer(), jsonText)

    @Test
    fun `absent mode fields decode to null`() {
        val state = decodeStateUpdate()
        assertNull(state.shuffle)
        assertNull(state.repeat)
        assertNull(state.trackHash)
    }

    @Test
    fun `valid modes decode to their values`() {
        val state = decodeStateUpdate(extra = ""","shuffle":true,"repeat":"all"""")
        assertEquals(true, state.shuffle)
        assertEquals("all", state.repeat)
        assertEquals(P2pRepeatMode.ALL, P2pRepeatMode.fromWire(state.repeat))
    }

    @Test
    fun `invalid repeat string decodes but parses to null enum`() {
        val state = decodeStateUpdate(extra = ""","repeat":"loop-the-queue"""")
        assertEquals("loop-the-queue", state.repeat)
        assertNull(P2pRepeatMode.fromWire(state.repeat))
    }

    @Test
    fun `type-mismatched mode fields never fail the frame`() {
        // A numeric repeat and a string shuffle are tolerated as null, not a parse error.
        val state = decodeStateUpdate(extra = ""","shuffle":"yes","repeat":2,"trackHash":"abab"""")
        assertNull(state.shuffle)
        assertNull(P2pRepeatMode.fromWire(state.repeat))
        assertEquals("abab", state.trackHash)
    }

    @Test
    fun `standalone payload round-trips the modes`() {
        val payload = P2pPeerPlaybackState(
            status = P2pPlaybackStatus.PLAYING.wireValue,
            shuffle = false,
            repeat = "one",
        )
        val encoded = json.encodeToString(P2pPeerPlaybackState.serializer(), payload)
        val decoded = decodePayload(encoded)
        assertEquals(false, decoded.shuffle)
        assertEquals("one", decoded.repeat)
    }

    @Test
    fun `fromWire is tolerant of case and whitespace and null`() {
        assertEquals(P2pRepeatMode.OFF, P2pRepeatMode.fromWire(" OFF "))
        assertEquals(P2pRepeatMode.ONE, P2pRepeatMode.fromWire("One"))
        assertNull(P2pRepeatMode.fromWire(null))
        assertNull(P2pRepeatMode.fromWire("infinite"))
    }
}

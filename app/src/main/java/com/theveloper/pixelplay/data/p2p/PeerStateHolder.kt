package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection lifecycle of one peer entry (mirrors the reference MVP's ConnState). */
enum class P2pConnState {
    IDLE,
    CONNECTING,
    CONNECTED,
    CLOSING,
    RECONNECTING,
    GONE,
}

/** Last playback state received from a peer via `state_update`. */
data class PeerPlaybackSnapshot(
    val track: String,
    val artist: String?,
    val status: P2pPlaybackStatus,
    val positionSec: Double,
    val seq: Long,
    val receivedAtMs: Long,
    /** Shared-session fields (contract section 8); null = peer not session-capable. */
    val trackHash: String? = null,
    val playbackId: String? = null,
    /** Playback-mode fields (contract section 6, added 2026-11-02); raw wire values. */
    val shuffle: Boolean? = null,
    val repeat: String? = null,
)

/** UI-facing snapshot of one peer (discovery + connection + live playback). */
data class PeerUiModel(
    val key: String,
    val id: String?,
    val name: String,
    val platform: String,
    val connectionState: P2pConnState,
    val discovered: Boolean,
    val isCaller: Boolean,
    val host: String,
    val port: Int,
    val playback: PeerPlaybackSnapshot?,
    val rttMs: Long?,
    val reconnectInMs: Long?,
    val lastSeenAtMs: Long,
    val address: String,
)

/**
 * Singleton store of inbound peer states (deviceId -> snapshot) backing the Peers screen.
 * Written only by [PeerProtocolEngine].
 */
@Singleton
class PeerStateHolder @Inject constructor() {

    private val _peers = MutableStateFlow<Map<String, PeerUiModel>>(emptyMap())
    val peers: StateFlow<Map<String, PeerUiModel>> = _peers.asStateFlow()

    fun upsert(model: PeerUiModel) {
        _peers.value = _peers.value + (model.key to model)
    }

    fun remove(key: String) {
        _peers.value = _peers.value - key
    }

    fun clear() {
        _peers.value = emptyMap()
    }
}

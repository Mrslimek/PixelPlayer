package com.theveloper.pixelplay.presentation.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.p2p.PeerProtocolEngine.LocalIdentity
import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import com.theveloper.pixelplay.data.p2p.P2pConnState
import com.theveloper.pixelplay.data.p2p.PeerProtocolEngine
import com.theveloper.pixelplay.data.p2p.PeerStateHolder
import com.theveloper.pixelplay.data.p2p.PeerUiModel
import com.theveloper.pixelplay.data.p2p.P2pSessionState
import com.theveloper.pixelplay.data.p2p.SessionStateHolder
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PeersViewModel @Inject constructor(
    private val peerProtocolEngine: PeerProtocolEngine,
    peerStateHolder: PeerStateHolder,
    sessionStateHolder: SessionStateHolder,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val peers: StateFlow<List<PeerUiModel>> = peerStateHolder.peers
        .map { map ->
            map.values.sortedWith(
                compareByDescending<PeerUiModel> { it.connectionState == P2pConnState.CONNECTED }
                    .thenBy { it.name },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val identity: StateFlow<LocalIdentity> = peerProtocolEngine.identity

    val presenceEnabled: StateFlow<Boolean> = userPreferencesRepository.p2pPresenceEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val remoteControlAllowed: StateFlow<Boolean> = userPreferencesRepository.p2pRemoteControlAllowedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Shared-session state (contract section 8): active peer, conflicts, library_unsynced. */
    val sessionState: StateFlow<P2pSessionState> = sessionStateHolder.state

    fun setPresenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setP2pPresenceEnabled(enabled)
            peerProtocolEngine.setUserPresenceEnabled(enabled)
        }
    }

    fun setRemoteControlAllowed(allowed: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setP2pRemoteControlAllowed(allowed)
        }
    }

    /** Fire-and-forget remote playback command to one peer (contract section 6 `command`). */
    fun sendCommand(peerKey: String, action: P2pCommandAction) {
        peerProtocolEngine.sendCommand(peerKey, action)
    }

    /** Shared-session takeover: resume the peer's stint here from the local file (section 8). */
    fun playHere(peerKey: String) {
        viewModelScope.launch { peerProtocolEngine.playHere(peerKey) }
    }
}

package com.theveloper.pixelplay.presentation.peers

import androidx.lifecycle.ViewModel
import com.theveloper.pixelplay.data.p2p.ProtocolEventLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class P2pDebugLogViewModel @Inject constructor(
    private val eventLog: ProtocolEventLog,
) : ViewModel() {

    val events: StateFlow<List<ProtocolEventLog.P2pEvent>> = eventLog.events

    fun clearLog() = eventLog.clear()
}

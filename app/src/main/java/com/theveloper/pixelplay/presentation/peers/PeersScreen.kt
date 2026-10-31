package com.theveloper.pixelplay.presentation.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.p2p.P2pConnState
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import com.theveloper.pixelplay.data.p2p.PeerUiModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

/**
 * Peers screen: discovered/connected devices of the Music Share peer protocol with their
 * live playback state, connection quality (rtt), and the presence enable/disable switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    onBackClick: () -> Unit,
    viewModel: PeersViewModel = hiltViewModel(),
) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val presenceEnabled by viewModel.presenceEnabled.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Peers",
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            PresenceCard(
                identity = identity,
                enabled = presenceEnabled,
                onToggle = viewModel::setPresenceEnabled,
            )
            Spacer(Modifier.height(16.dp))
            if (sessionState.conflictActive) {
                sessionState.conflicts.firstOrNull()?.let { conflict ->
                    SessionConflictBanner(conflict)
                    Spacer(Modifier.height(8.dp))
                }
            }
            var unsyncedShown by remember(sessionState.lastUnsynced?.atMs) { mutableStateOf(true) }
            val unsynced = sessionState.lastUnsynced
            if (unsynced != null && unsyncedShown) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.p2p_library_unsynced_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { unsyncedShown = false },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = if (peers.isEmpty()) "No peers yet" else "Peers (${peers.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (peers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (presenceEnabled) {
                            "Searching the local network for _musicshare._tcp peers…"
                        } else {
                            "Presence is off. Enable it to discover nearby devices."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(peers, key = { it.key }) { peer ->
                        PeerCard(
                            peer = peer,
                            onCommand = viewModel::sendCommand,
                            onPlayHere = viewModel::playHere,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresenceCard(
    identity: com.theveloper.pixelplay.data.p2p.PeerProtocolEngine.LocalIdentity,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = if (identity.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Presence", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        identity.running -> "On — advertising as \"${identity.name}\" (ws :${identity.port})"
                        enabled -> "Starting…"
                        else -> "Off"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "device id: ${identity.id.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerUiModel,
    onCommand: (String, P2pCommandAction) -> Unit,
    onPlayHere: (String) -> Unit,
) {
    val stateColor = when (peer.connectionState) {
        P2pConnState.CONNECTED -> MaterialTheme.colorScheme.primary
        P2pConnState.CONNECTING, P2pConnState.RECONNECTING -> MaterialTheme.colorScheme.tertiary
        P2pConnState.CLOSING -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (peer.platform) {
                        "macos" -> Icons.Rounded.Computer
                        "android" -> Icons.Rounded.Phonelink
                        else -> Icons.Rounded.Devices
                    },
                    contentDescription = null,
                    tint = stateColor,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = peer.address.ifEmpty { "address unknown" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StateBadge(peer)
            }
            peer.playback?.let { playback ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            append(playback.track.ifEmpty { "(no track)" })
                            playback.artist?.let { if (it.isNotEmpty()) append(" — $it") }
                            append("  ·  ${playback.status}, ${String.format(Locale.US, "%.1f", playback.positionSec)}s")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val quality = peer.rttMs?.let { "rtt ${it}ms" } ?: "rtt —"
                val meta = buildList {
                    add(peer.connectionState.name.lowercase())
                    add(quality)
                    if (peer.discovered) add("discovered")
                    add("id ${peer.id?.take(8) ?: "?"}…")
                    peer.reconnectInMs?.let { add("retry in ${it / 1000.0}s") }
                }
                Text(
                    text = meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor,
                    modifier = Modifier.weight(1f),
                )
            }
            // Remote playback control (contract section 6 command): fire-and-forget buttons
            // for connected peers only.
            if (peer.connectionState == P2pConnState.CONNECTED) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommandButton("Play", Icons.Rounded.PlayArrow) {
                        onCommand(peer.key, P2pCommandAction.PLAY)
                    }
                    CommandButton("Pause", Icons.Rounded.Pause) {
                        onCommand(peer.key, P2pCommandAction.PAUSE)
                    }
                    CommandButton("Stop", Icons.Rounded.Stop) {
                        onCommand(peer.key, P2pCommandAction.STOP)
                    }
                }
            }
            // Shared-session takeover (contract section 8): the peer holds the active stint.
            val active = peer.playback != null &&
                peer.playback.trackHash != null &&
                peer.playback.status == P2pPlaybackStatus.PLAYING
            if (active && peer.connectionState == P2pConnState.CONNECTED) {
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = { onPlayHere(peer.key) },
                    label = { Text(stringResource(R.string.p2p_play_here)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CommandButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StateBadge(peer: PeerUiModel) {
    when (peer.connectionState) {
        P2pConnState.CONNECTED -> Text(
            text = "connected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        P2pConnState.CONNECTING, P2pConnState.RECONNECTING -> {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        else -> Text(
            text = peer.connectionState.name.lowercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

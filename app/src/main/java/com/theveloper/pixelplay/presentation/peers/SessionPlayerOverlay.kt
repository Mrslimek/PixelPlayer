package com.theveloper.pixelplay.presentation.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayCircle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.p2p.P2pSessionState
import com.theveloper.pixelplay.data.p2p.SessionPeerReachability
import com.theveloper.pixelplay.data.p2p.SessionSource
import java.util.Locale

/**
 * The shared-session representation on the main player surface (contract section 8).
 * The session axis is independent of the connection axis: an UNREACHABLE/GONE peer keeps
 * the stint visible as last-seen (authoritative holder data; any position extrapolation
 * here is a presentation estimate only) with a connection-lost indicator, and take-over
 * stays available. LOCAL sources render nothing here — that is today's local player.
 */
@Composable
fun SessionPlayerOverlay(
    state: P2pSessionState,
    positionEstimateMs: Long?,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val source = state.source) {
        is SessionSource.Remote -> RemoteSessionCard(
            source = source,
            positionEstimateMs = positionEstimateMs,
            onPlayHere = onPlayHere,
            modifier = modifier,
        )
        is SessionSource.TakeoverInFlight -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Taking over from ${source.peerName}…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is SessionSource.Local, null -> Unit // the local player is the surface; nothing to show
    }
    if (state.conflictActive) {
        state.conflicts.firstOrNull()?.let { conflict ->
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = modifier.fillMaxWidth(),
            ) {
                SessionConflictBanner(
                    conflict = conflict,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoteSessionCard(
    source: SessionSource.Remote,
    positionEstimateMs: Long?,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (source.reachability) {
                    SessionPeerReachability.CONNECTED -> Text(
                        text = "on ${source.peerName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SessionPeerReachability.UNREACHABLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "on ${source.peerName} — connection lost",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SessionPeerReachability.GONE -> Text(
                        text = "on ${source.peerName} — peer gone",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = source.stint.trackTitle.ifEmpty { "(unknown track)" },
                style = MaterialTheme.typography.titleSmall,
            )
            val positionText = positionEstimateMs?.let { formatMs(it) } ?: formatSec(source.stint.positionSec)
            Text(
                text = "${source.stint.status.wireValue.replaceFirstChar { it.uppercase() }} · $positionText" +
                    if (source.reachability != SessionPeerReachability.CONNECTED) {
                        " (last seen ${formatMs(source.stint.updatedAtMs)})"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            // Take-over stays available even when the peer is unreachable: starting a local
            // stint then is legal (section 8 conflict machinery covers the risk).
            AssistChip(
                onClick = onPlayHere,
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

/** PRESENTATION ESTIMATE: holder data + elapsed wall clock while the stint plays. */
private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(Locale.US, totalSec / 60, totalSec % 60)
}

private fun formatSec(sec: Double): String = String.format(Locale.US, "%.1f", sec) + "s"

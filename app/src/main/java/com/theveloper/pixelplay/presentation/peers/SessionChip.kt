package com.theveloper.pixelplay.presentation.peers

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.p2p.SessionPeerReachability
import com.theveloper.pixelplay.data.p2p.SessionSource

/**
 * Compact shared-session chip on the player surface (contract section 8), mirroring the
 * Cast chip pattern. The session axis is independent of the connection axis: an
 * UNREACHABLE/GONE peer keeps the stint rendered as last-known with warning styling.
 * LOCAL sources render nothing — that is today's local player.
 */
@Composable
fun SessionChip(
    source: SessionSource,
    isPlaying: Boolean,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectionLost = when (source) {
        is SessionSource.Remote ->
            source.reachability != SessionPeerReachability.CONNECTED
        is SessionSource.TakeoverInFlight -> false
        is SessionSource.Local -> return
    }
    val containerColor =
        if (connectionLost) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val contentColor =
        if (connectionLost) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .animateContentSize(),
        ) {
            when (source) {
                is SessionSource.Remote -> {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (connectionLost) {
                            stringResource(R.string.p2p_session_connection_lost)
                        } else {
                            source.peerName
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.p2p_play_here),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (connectionLost) contentColor else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onPlayHere),
                    )
                }
                is SessionSource.TakeoverInFlight -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.p2p_taking_over_from, source.peerName),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
                is SessionSource.Local -> Unit
            }
        }
    }
}

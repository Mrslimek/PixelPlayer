package com.theveloper.pixelplay.presentation.peers

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.p2p.SessionConflictEvent

/**
 * Shared-session violation banner (contract section 8): names the involved devices.
 * The protocol never auto-resolves conflicts — resolution belongs to the player/user.
 * Used on both the Peers screen and the main player surface.
 */
@Composable
fun SessionConflictBanner(
    conflict: SessionConflictEvent,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = "⚠ " + stringResource(R.string.p2p_session_conflict_banner, conflict.deviceNames.joinToString(" + ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

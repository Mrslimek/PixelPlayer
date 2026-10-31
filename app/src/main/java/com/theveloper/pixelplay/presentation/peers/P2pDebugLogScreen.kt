package com.theveloper.pixelplay.presentation.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.p2p.ProtocolEventLog
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug comm log: live human-readable protocol event feed with timestamps and direction
 * arrows, e.g. `→ state_update to Bocan: track=Song, status=playing, pos=42.1s`.
 * Auto-scrolls to the newest entry; scrolling up pauses auto-scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2pDebugLogScreen(
    onBackClick: () -> Unit,
    viewModel: P2pDebugLogViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(events.size) {
        if (atBottom && events.isNotEmpty()) {
            listState.animateScrollToItem(events.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("P2P communication log", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearLog) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Clear log")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (events.isEmpty()) {
                item {
                    Text(
                        text = "No protocol events yet. Enable presence on the Peers screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(events, key = { it.seqNo }) { event ->
                EventRow(event)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EventRow(event: ProtocolEventLog.P2pEvent) {
    val arrowColor = when (event.direction) {
        ProtocolEventLog.Direction.OUT -> MaterialTheme.colorScheme.primary
        ProtocolEventLog.Direction.IN -> MaterialTheme.colorScheme.tertiary
        ProtocolEventLog.Direction.PAIR -> MaterialTheme.colorScheme.secondary
        ProtocolEventLog.Direction.LOCAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ProtocolEventLog.Direction.WARN -> MaterialTheme.colorScheme.error
        ProtocolEventLog.Direction.CLOSE, ProtocolEventLog.Direction.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (event.direction == ProtocolEventLog.Direction.ERROR ||
            event.direction == ProtocolEventLog.Direction.WARN
        ) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            Color.Transparent
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = formatTimestamp(event.tsMs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.direction.arrow,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = arrowColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = event.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (event.detail.isNotEmpty()) {
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(tsMs: Long): String {
    val time = java.time.Instant.ofEpochMilli(tsMs)
        .atZone(java.time.ZoneId.systemDefault())
    return String.format(
        Locale.US,
        "%02d:%02d:%02d.%03d",
        time.hour, time.minute, time.second, time.nano / 1_000_000,
    )
}

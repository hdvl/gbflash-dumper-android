package com.gbflash.dumper.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gbflash.dumper.device.CartMode
import com.gbflash.dumper.dump.CartridgeInfo
import com.gbflash.dumper.ui.theme.AppAccent
import com.gbflash.dumper.ui.theme.AppBarTitleStyle
import com.gbflash.dumper.ui.theme.GbFlashTheme
import com.gbflash.dumper.ui.theme.LcdScreen

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DumperScreen(
    state: DumperUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectMode: (CartMode) -> Unit,
    onRequestDump: (DumpTarget) -> Unit,
    onCancelDump: () -> Unit,
    onDismissError: () -> Unit,
    onDismissLastDump: () -> Unit,
) {
    GbFlashTheme(accent = accentFor(state)) {
    Surface {
        Scaffold(
            topBar = { TopAppBar(title = { Text("GBFlash Dumper", style = AppBarTitleStyle) }) },
        ) { padding ->
            if (state.connectionStatus == ConnectionStatus.DISCONNECTED) {
                IdleState(onConnect, modifier = Modifier.padding(padding))
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionStatusRow(state, onDisconnect)

                if (state.connectionStatus == ConnectionStatus.CONNECTED) {
                    ModeSelector(onSelectMode)
                }

                state.cartInfo?.let { info ->
                    CartridgeCard(info, onRequestDump)
                }

                if (state.isBusy) {
                    BusyCard(state, onCancelDump)
                }

                state.lastDump?.let { dump ->
                    DumpCompleteCard(dump, onDismissLastDump)
                }

                Spacer(modifier = Modifier.weight(1f))

                TechnicalLogSection(state.log)
            }
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
        )
    }
    }
}

/** Picks the accent that best matches what's currently detected — see [AppAccent]. */
private fun accentFor(state: DumperUiState): AppAccent = when (val info = state.cartInfo) {
    is CartridgeInfo.Agb -> AppAccent.AGB
    is CartridgeInfo.Dmg -> if (info.header.isColorCompatible) AppAccent.CGB else AppAccent.DMG
    null -> AppAccent.NEUTRAL
}

/** Big centered "nothing plugged in yet" state, shown instead of the whole working screen. */
@Composable
private fun IdleState(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PixelCartridge(modifier = Modifier.size(72.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text("No GBFlash connected", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Plug in your GBFlash with a USB-OTG cable, then connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onConnect) { Text("Connect") }
    }
}

/** Compact single-line status once we're past the idle state (connecting, connected, or errored). */
@Composable
private fun ConnectionStatusRow(state: DumperUiState, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val statusText = when (state.connectionStatus) {
            ConnectionStatus.DISCONNECTED -> "" // unreachable here, IdleState handles it
            ConnectionStatus.NO_PERMISSION -> "Waiting for USB permission..."
            ConnectionStatus.CONNECTING -> "Connecting..."
            ConnectionStatus.CONNECTED -> state.firmware?.deviceName ?: "Connected"
            ConnectionStatus.ERROR -> "Connection error"
        }
        Text(statusText, style = MaterialTheme.typography.titleMedium)
        if (state.connectionStatus == ConnectionStatus.CONNECTED || state.connectionStatus == ConnectionStatus.ERROR) {
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun ModeSelector(onSelectMode: (CartMode) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's plugged in?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSelectMode(CartMode.DMG) }) { Text("Game Boy / Color") }
                Button(onClick = { onSelectMode(CartMode.AGB) }) { Text("Game Boy Advance") }
            }
        }
    }
}

@Composable
private fun CartridgeCard(info: CartridgeInfo, onRequestDump: (DumpTarget) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (info) {
                is CartridgeInfo.Dmg -> {
                    val h = info.header
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(h.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleLarge)
                        PlatformBadge(if (h.isColorCompatible) "GBC" else "GB")
                    }
                    Text("Mapper: ${h.mapperName}")
                    Text("ROM size: ${h.romSizeBytes / 1024} KiB")
                    Text("Save: ${if (h.save.sizeBytes > 0) "${h.save.sizeBytes} bytes" else "none"}")
                    if (!h.logoCorrect) Text("Warning: Nintendo logo checksum mismatch — check cartridge contacts.")
                    if (!h.isMapperSupported) Text("This mapper isn't supported yet — ROM dump only, no save.")
                }
                is CartridgeInfo.Agb -> {
                    val h = info.header
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(h.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleLarge)
                        PlatformBadge("GBA")
                    }
                    Text("Game code: ${h.gameCode}")
                    Text("ROM size: ${info.romSizeBytes / 1024} KiB")
                    Text("Save: ${info.saveType.label}")
                    if (!h.logoCorrect || !h.fixedByteOk) Text("Warning: header checksum mismatch — check cartridge contacts.")
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRequestDump(DumpTarget.ROM) }) { Text("Dump ROM") }
                val hasSave = when (info) {
                    is CartridgeInfo.Dmg -> info.header.save.sizeBytes > 0
                    is CartridgeInfo.Agb -> info.saveType.sizeBytes > 0
                }
                if (hasSave) {
                    OutlinedButton(onClick = { onRequestDump(DumpTarget.SAVE) }) { Text("Dump save") }
                }
            }
        }
    }
}

@Composable
private fun PlatformBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BusyCard(state: DumperUiState, onCancelDump: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text(state.busyLabel)
            }
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${formatBytes(state.transferredBytes)} / ${formatBytes(state.totalBytes)}")
                Text("${(state.progress * 100).toInt()}%")
            }
            TextButton(onClick = onCancelDump) { Text("Cancel") }
        }
    }
}

@Composable
private fun DumpCompleteCard(dump: LastDumpInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (dump.target == DumpTarget.ROM) "ROM dump complete" else "Save dump complete",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${dump.fileName} — ${formatBytes(dump.sizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = "Dismiss")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareFile(context, dump) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.height(0.dp).padding(start = 4.dp))
                    Text("Share")
                }
            }
        }
    }
}

private fun shareFile(context: Context, dump: LastDumpInfo) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, dump.uri)
        putExtra(Intent.EXTRA_TITLE, dump.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${dump.fileName}"))
}

@Composable
private fun TechnicalLogSection(log: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Technical details (${log.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            // A little dot-matrix "LCD screen" look for the raw protocol log — it's already
            // monospace, so leaning into the retro-display feel costs nothing in readability.
            Surface(color = LcdScreen.background, shape = MaterialTheme.shapes.small) {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp).padding(8.dp)) {
                    items(log.asReversed()) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = LcdScreen.ink)
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.2f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

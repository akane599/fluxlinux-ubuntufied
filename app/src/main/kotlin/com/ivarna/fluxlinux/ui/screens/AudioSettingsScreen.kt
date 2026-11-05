package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.ContentCopy
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Visibility
import androidx.compose.material.icons.sharp.VisibilityOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.core.terminal.PulseHost
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host PulseAudio status / start / restart / logs.
 * Guests stay clients of its unix socket (`PULSE_SERVER=unix:/tmp/pulse-native`
 * in PRoot, `unix:/mnt/host-tmp/pulse-native` in chroot). No TCP listener.
 */
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<PulseHost.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    fun loadStatus(startIfStopped: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val (s, log) = withContext(Dispatchers.IO) {
                var q = PulseHost.query(context)
                // prepareHost no longer starts Pulse; this screen does it on open.
                if (startIfStopped && !q.running) {
                    PulseHost.ensureStarted(context)
                    q = PulseHost.query(context)
                }
                q to PulseHost.readLog(context)
            }
            status = s
            logText = log
            busy = false
        }
    }

    fun runAction(
        label: String,
        toastOf: ((String) -> String)? = null,
        block: (Context) -> String
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val (s, log, toast) = withContext(Dispatchers.IO) {
                val out = block(context)
                Triple(
                    PulseHost.query(context),
                    PulseHost.readLog(context),
                    toastOf?.invoke(out) ?: label
                )
            }
            status = s
            logText = log
            showLogs = true
            busy = false
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { loadStatus(startIfStopped = true) }

    val running = status?.running == true
    val healthy = status?.healthy == true
    val statusMark = when {
        status == null -> MarkState.WORKING
        healthy -> MarkState.RUNNING
        running -> MarkState.HELD
        else -> MarkState.FAILED
    }

    SettingsPageScaffold(title = "Audio", onBack = onBack) {
        SettingsPageNote(
            "Host PulseAudio in the AkaTerminal prefix. Guests connect through its private unix socket (unix:/tmp/pulse-native; chroot: /mnt/host-tmp/pulse-native), never TCP. Stopping the desktop does not stop this service."
        )

        SectionLabel("Host server")
        SettingsPageStatus(
            text = if (busy && status == null) "Checking…" else (status?.label ?: "Checking…"),
            state = statusMark,
            modifier = Modifier
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.xs)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        SettingsPageNote(status?.detail ?: "Querying pulseaudio --check and pactl info…")
        if (busy) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.xs)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(FluxSpacing.md)
        ) {
            SettingsPageSecondaryButton(
                text = "Refresh",
                icon = Icons.Sharp.Refresh,
                onClick = { loadStatus() },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
            PlatedButton(
                text = if (running) "Restart" else "Start",
                onClick = {
                    if (running) {
                        runAction("Pulse restarted") { PulseHost.restart(it) }
                    } else {
                        runAction("Pulse started") { PulseHost.start(it) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
        }

        SectionLabel("Logs")
        SettingsPageNote("Supervisor and status output from this app (start_pulse_host.sh).")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(FluxSpacing.md)
        ) {
            SettingsPageSecondaryButton(
                text = if (showLogs) "Hide logs" else "View logs",
                icon = if (showLogs) Icons.Sharp.VisibilityOff else Icons.Sharp.Visibility,
                onClick = { showLogs = !showLogs },
                modifier = Modifier.weight(1f)
            )
            SettingsPageSecondaryButton(
                text = "Copy",
                icon = Icons.Sharp.ContentCopy,
                onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clip.setPrimaryClip(
                        ClipData.newPlainText("Pulse log", logText.ifBlank { "(empty)" })
                    )
                    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                },
                enabled = logText.isNotBlank(),
                modifier = Modifier.weight(1f)
            )
        }
        if (showLogs) {
            SettingsPageBlock(contentPadding = PaddingValues(FluxSpacing.md)) {
                SelectionContainer {
                    Text(
                        text = logText.ifBlank { "(no log yet — tap Refresh or Start)" },
                        style = CodeStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        SectionLabel("Guest clients")
        SettingsPageNote(
            "Points already-installed guests at the unix socket (moving old tcp:127.0.0.1 settings) and installs pactl. Chroots need root. Does not start a guest Pulse daemon."
        )
        SettingsPageSecondaryButton(
            text = "Repair guests",
            onClick = {
                runAction(
                    "Guest audio repaired",
                    PulseHost::repairToast
                ) { PulseHost.repairGuests(it) }
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
        )
    }
}

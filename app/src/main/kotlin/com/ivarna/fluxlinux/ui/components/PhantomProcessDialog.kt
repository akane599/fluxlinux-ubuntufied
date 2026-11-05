package com.ivarna.fluxlinux.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer.State
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status + Root / Shizuku / PC (adb) fixes. [warning] adds the intro text and "Don't show again". */
@Composable
fun PhantomProcessDialog(warning: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PhantomProcessFixer.state(context)) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun done(err: String?) {
        state = PhantomProcessFixer.state(context)
        msg = err?.let { context.getString(R.string.ubuntu_aka_phantom_failed, it) }
            ?: context.getString(R.string.ubuntu_aka_phantom_applied)
    }

    val cs = MaterialTheme.colorScheme
    val (mark, statusText) = when {
        !PhantomProcessFixer.applicable() -> MarkState.IDLE to R.string.ubuntu_aka_phantom_status_not_needed
        state == State.DISABLED -> MarkState.RUNNING to R.string.ubuntu_aka_phantom_status_disabled
        state == State.ENABLED -> MarkState.FAILED to R.string.ubuntu_aka_phantom_status_enabled
        else -> MarkState.IDLE to R.string.ubuntu_aka_phantom_status_unknown
    }
    val secondaryText = ButtonDefaults.textButtonColors(contentColor = cs.secondary)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ubuntu_aka_phantom_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
            ) {
                Text(
                    stringResource(R.string.ubuntu_aka_phantom_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(state = mark)
                    Spacer(Modifier.width(FluxSpacing.sm))
                    Text(
                        stringResource(R.string.ubuntu_aka_phantom_status, stringResource(statusText)),
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurface,
                    )
                }
                msg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FluxSpacing.touch),
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        msg = context.getString(R.string.ubuntu_aka_phantom_requesting_root)
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { PhantomProcessFixer.applyRoot() }
                            done(err)
                        }
                    }
                ) { Text(stringResource(R.string.ubuntu_aka_phantom_fix_root), style = MaterialTheme.typography.labelLarge) }
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FluxSpacing.touch),
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        fun run() = scope.launch {
                            val err = withContext(Dispatchers.IO) { PhantomProcessFixer.applyShizuku() }
                            done(err)
                        }
                        when {
                            !PhantomProcessFixer.shizukuRunning() ->
                                msg = context.getString(R.string.ubuntu_aka_phantom_shizuku_not_running)
                            PhantomProcessFixer.shizukuGranted() -> run()
                            else -> PhantomProcessFixer.requestShizuku { ok ->
                                if (ok) run() else msg = context.getString(R.string.ubuntu_aka_phantom_shizuku_denied)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.ubuntu_aka_phantom_fix_shizuku), style = MaterialTheme.typography.labelLarge) }
                Text(
                    stringResource(R.string.ubuntu_aka_phantom_adb_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = FluxSpacing.xs),
                )
                // Commands to type on a PC: the code face, on the terminal well.
                Text(
                    PhantomProcessFixer.adbCommands,
                    style = CodeStyle,
                    color = cs.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(cs.surfaceContainerLowest)
                        .padding(FluxSpacing.md),
                )
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FluxSpacing.touch),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ADB Commands", PhantomProcessFixer.adbCommands))
                        Toast.makeText(
                            context,
                            context.getString(R.string.ubuntu_aka_phantom_adb_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                ) { Text(stringResource(R.string.ubuntu_aka_phantom_copy_adb), style = MaterialTheme.typography.labelLarge) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = secondaryText) {
                Text(stringResource(R.string.ubuntu_aka_phantom_close))
            }
        },
        dismissButton = if (warning) {
            {
                TextButton(colors = secondaryText, onClick = {
                    PhantomProcessFixer.hideWarningForever(context)
                    onDismiss()
                }) { Text(stringResource(R.string.ubuntu_aka_phantom_hide)) }
            }
        } else null
    )
}

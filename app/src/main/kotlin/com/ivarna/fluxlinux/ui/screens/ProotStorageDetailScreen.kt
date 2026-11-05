package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.proot.ProotInfoStore
import com.ivarna.fluxlinux.core.proot.ProotSettingsModel
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parameterized detail screen for PRoot container storage (individual or universal).
 */
@Composable
fun ProotStorageDetailScreen(
    distroId: String,
    onBack: () -> Unit,
    onNavigateToInstall: (() -> Unit)? = null
) {
    val isUniversal = distroId == GuestStorageCatalog.ALL_PROOT_ID
    val distro = if (!isUniversal) {
        DistroRepository.supportedDistros.firstOrNull { it.id == distroId }
    } else null

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val containerPath = if (!isUniversal) {
        GuestStorageCatalog.prootContainerPath(context, distroId)
    } else {
        "${context.filesDir.absolutePath}/usr/var/lib/proot-distro/containers"
    }

    if (!isUniversal && (distro == null || containerPath == null)) {
        LaunchedEffect(distroId) {
            onBack()
        }
        return
    }

    val installableIds = remember { GuestStorageCatalog.installableProots().map { it.id } }

    val initialSizeUi = remember(distroId) {
        if (isUniversal) {
            val cachedBytes = ProotInfoStore.cachedBytes(context, GuestStorageCatalog.ALL_PROOT_ID)
            ProotSettingsModel.SizeUi(
                installed = true,
                dirExists = true,
                bytes = cachedBytes,
                dimmedCache = cachedBytes != null,
                hint = "Tap refresh to measure"
            )
        } else {
            ProotSettingsModel.loadCached(context, distroId)
        }
    }

    var sizeUi by remember { mutableStateOf(initialSizeUi) }
    var busy by remember { mutableStateOf(false) }

    fun refreshSize() {
        if (busy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Measuring container storage…")
        scope.launch {
            val s = withContext(Dispatchers.IO) {
                if (isUniversal) {
                    ProotSettingsModel.refreshUniversal(context, installableIds)
                } else {
                    ProotSettingsModel.refreshSize(context, distroId)
                }
            }
            sizeUi = s
            busy = false
        }
    }

    LaunchedEffect(distroId) {
        refreshSize()
    }

    val pageTitle = if (isUniversal) "All containers" else distro!!.name

    SettingsPageScaffold(title = pageTitle, onBack = onBack) {
        SettingsPageNote("PRoot lives in app storage (container + rootfs) and is removed if you uninstall the app.")

        SectionLabel("Container storage")
        SettingsPageMetaRow("Status") {
            val (statusText, mark) = when {
                isUniversal -> if (sizeUi.installed) "Active" to MarkState.IDLE else "Ready" to MarkState.IDLE
                sizeUi.installed -> "Installed" to MarkState.IDLE
                sizeUi.dirExists -> "Present" to MarkState.HELD
                else -> "Not installed" to MarkState.FAILED
            }
            SettingsPageStatus(text = statusText, state = mark)
        }

        val (v, u) = ProotInfoStore.formatStorageBytes(sizeUi.bytes)
        SettingsStorageReadout(
            label = "PRoot storage",
            value = v,
            unit = u,
            hint = sizeUi.hint,
            working = sizeUi.measuring || busy && sizeUi.bytes == null,
            dimmed = sizeUi.dimmedCache,
            refreshEnabled = !busy,
            onRefresh = { refreshSize() }
        )

        SettingsStoragePaths(label = "Container path", paths = listOf(containerPath ?: ""))

        if (!isUniversal && !sizeUi.installed && onNavigateToInstall != null) {
            PlatedButton(
                text = "Install PRoot distro",
                onClick = { onNavigateToInstall() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
            )
        }

        SettingsStorageRefresh(
            text = "Refresh size",
            working = busy,
            onClick = { refreshSize() }
        )
    }
}

/**
 * The measured size of a rootfs, as a tonal readout: what it measures and a refresh action,
 * the figure in mono, then how it was measured.
 */
@Composable
internal fun SettingsStorageReadout(
    label: String,
    value: String,
    unit: String,
    hint: String,
    working: Boolean,
    dimmed: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
) {
    SettingsPageBlock {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                Icon(Icons.Sharp.Refresh, contentDescription = "Refresh size")
            }
        }
        if (working) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(FluxSpacing.sm))
        }
        SettingsPageFigure(value = value, unit = unit, dimmed = dimmed)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Host path(s) of a rootfs, labelled, in mono. */
@Composable
internal fun SettingsStoragePaths(label: String, paths: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        paths.forEach { Text(it, style = CodeStyle, color = MaterialTheme.colorScheme.onSurface) }
    }
}

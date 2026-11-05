package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.core.chroot.ChrootInfoStore
import com.ivarna.fluxlinux.core.chroot.ChrootKillCoordinator
import com.ivarna.fluxlinux.core.chroot.ChrootSettingsModel
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.AkaButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parameterized detail screen for a single chroot distribution or "__all_chroot__" aggregate.
 */
@Composable
fun ChrootStorageDetailScreen(
    distroId: String,
    onBack: () -> Unit,
    onNavigateToInstall: (() -> Unit)? = null
) {
    val isUniversal = distroId == GuestStorageCatalog.ALL_CHROOT_ID
    val distro = if (!isUniversal) {
        DistroRepository.supportedDistros.firstOrNull { it.id == distroId }
    } else null

    val resolvedPath = if (!isUniversal) {
        GuestStorageCatalog.chrootPathOrNull(distroId)
    } else null

    // If target distro disappeared or invalid id, pop to list
    if (!isUniversal && (distro == null || resolvedPath == null)) {
        LaunchedEffect(distroId) {
            onBack()
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val path = resolvedPath ?: ChrootPaths.CHROOT_PATH
    val allPaths = remember { GuestStorageCatalog.KNOWN_CHROOT_PATHS.toList().sorted() }
    val installableIds = remember { GuestStorageCatalog.installableChroots().map { it.id } }

    val initialPage = remember(distroId) {
        if (isUniversal) {
            val cachedBytes = ChrootInfoStore.cachedBytes(context, GuestStorageCatalog.ALL_CHROOT_ID)
            val cachedProc = ChrootInfoStore.cachedProcCount(context, GuestStorageCatalog.ALL_CHROOT_ID)
            ChrootSettingsModel.PageSnapshot(
                size = ChrootSettingsModel.SizeUi(
                    rootOk = true,
                    installed = true,
                    markerOk = true,
                    dirExists = true,
                    bytes = cachedBytes,
                    dimmedCache = cachedBytes != null,
                    hint = "Tap refresh to measure"
                ),
                proc = ChrootSettingsModel.ProcUi(
                    rootOk = true,
                    count = cachedProc,
                    processes = emptyList(),
                    hint = if (cachedProc >= 0) "Cached proc count" else "Tap scan for all chroot processes"
                )
            )
        } else {
            ChrootSettingsModel.loadCached(context, distroId = distroId, path = path)
        }
    }

    var sizeUi by remember { mutableStateOf(initialPage.size) }
    var procUi by remember { mutableStateOf(initialPage.proc) }
    var busy by remember { mutableStateOf(false) }
    var showKillConfirm by remember { mutableStateOf(false) }
    val killCoordinator = remember { ChrootKillCoordinator() }
    var killProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun refreshAll(force: Boolean = true) {
        if (busy || killCoordinator.isBusy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Checking root · measuring rootfs…")
        procUi = procUi.copy(scanning = true, hint = "Scanning /proc across roots…")
        scope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        val p = ChrootSettingsModel.refreshUniversal(context, installableIds)
                        val proc = ChrootSettingsModel.scanAllProcesses(context, allPaths)
                        ChrootSettingsModel.PageSnapshot(p.size, proc)
                    } else {
                        ChrootSettingsModel.refreshPage(context, distroId = distroId, path = path, force = force)
                    }
                }
                sizeUi = snap.size
                procUi = snap.proc
            } finally {
                busy = false
            }
        }
    }

    fun refreshSizeOnly() {
        if (busy || killCoordinator.isBusy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Checking root · measuring rootfs…")
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.refreshUniversal(context, installableIds).size
                    } else {
                        ChrootSettingsModel.refreshSize(context, distroId = distroId, path = path, forceClearSu = true)
                    }
                }
                sizeUi = s
            } finally {
                busy = false
            }
        }
    }

    fun scanProcs() {
        if (busy || killCoordinator.isBusy) return
        busy = true
        procUi = procUi.copy(scanning = true, hint = "Scanning /proc for chroot root…")
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.scanAllProcesses(context, allPaths)
                    } else {
                        ChrootSettingsModel.refreshProcesses(context, distroId = distroId, path = path, forceClearSu = true)
                    }
                }
                procUi = p
            } finally {
                busy = false
            }
        }
    }

    fun killAll() {
        val session = killCoordinator.startSession() ?: return
        busy = true
        killProgress = null
        procUi = procUi.copy(killing = true, scanning = true, hint = "Sending SIGKILL…")
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.killAllProcesses(
                            context,
                            paths = allPaths,
                            isCancelled = { session.isCancelled },
                            onProgress = { cur, tot ->
                                killProgress = cur to tot
                            }
                        )
                    } else {
                        ChrootSettingsModel.killAllProcesses(context, path = path)
                    }
                }
                procUi = p
                Toast.makeText(
                    context,
                    p.statusLine ?: p.hint,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                killCoordinator.endSession(session)
                busy = false
                killProgress = null
            }
        }
    }

    fun cancelKill() {
        if (killCoordinator.requestCancel()) {
            procUi = procUi.copy(hint = "Cancelling kill… waiting for current path")
        }
    }

    LaunchedEffect(distroId) {
        refreshAll(force = false)
    }

    val pageTitle = if (isUniversal) "All chroots" else distro!!.name

    if (showKillConfirm) {
        AlertDialog(
            onDismissRequest = { showKillConfirm = false },
            title = { Text(if (isUniversal) "Kill all chroot processes?" else "Kill ${distro?.name ?: "chroot"} processes?") },
            text = {
                if (isUniversal) {
                    Text(
                        ChrootSettingsModel.confirmKillCopy(allPaths) +
                            if (procUi.count > 0) "\n\nDetected: ${procUi.count} process(es)." else ""
                    )
                } else {
                    Text(
                        "Sends SIGKILL to every process whose root is $path.\n\n" +
                            "Open chroot shells and guest daemons will die. Host Android processes " +
                            "are not targeted.\n\nRootfs and mounts stay." +
                            if (procUi.count > 0) "\n\nDetected: ${procUi.count} process(es)." else ""
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKillConfirm = false
                        killAll()
                    }
                ) { Text("Kill all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showKillConfirm = false }) { Text("Cancel") }
            }
        )
    }

    SettingsPageScaffold(title = pageTitle, onBack = onBack) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val body = MaterialTheme.colorScheme.onSurface

        SettingsPageAttention("Rootfs is not removed when you uninstall the app. Free space here.")

        // ── Storage & auto-detect ─────────────────────────────────────
        SectionLabel("Storage & manage")
        SettingsPageMetaRow("Status") {
            val installedCount = remember(sizeUi) {
                GuestStorageCatalog.installableChroots().count {
                    ChrootInfoStore.cachedInstalled(context, it.id)
                }
            }
            if (isUniversal) {
                SettingsPageStatus(
                    text = "$installedCount installed",
                    state = if (installedCount > 0) MarkState.IDLE else MarkState.FAILED
                )
            } else {
                val raw = ChrootSettingsModel.resolveStatus(
                    installed = sizeUi.installed,
                    dirExists = sizeUi.dirExists
                )
                SettingsPageStatus(
                    text = raw.lowercase().replaceFirstChar { it.uppercase() },
                    state = when {
                        sizeUi.installed -> MarkState.IDLE
                        sizeUi.dirExists -> MarkState.HELD
                        else -> MarkState.FAILED
                    }
                )
            }
        }
        if (sizeUi.rootOk || sizeUi.measuring) {
            SettingsPageMetaRow("Root access") {
                SettingsPageStatus(
                    text = when {
                        sizeUi.measuring -> "Checking"
                        sizeUi.rootOk -> "Granted"
                        else -> "Denied"
                    },
                    state = when {
                        sizeUi.measuring -> MarkState.WORKING
                        sizeUi.rootOk -> MarkState.RUNNING
                        else -> MarkState.FAILED
                    }
                )
            }
        }

        val (v, u) = ChrootInfoStore.formatStorageBytes(sizeUi.bytes)
        SettingsStorageReadout(
            label = "Linux storage",
            value = v,
            unit = u,
            hint = sizeUi.hint,
            working = sizeUi.measuring || busy && sizeUi.bytes == null,
            dimmed = sizeUi.dimmedCache,
            refreshEnabled = !busy,
            onRefresh = { refreshSizeOnly() }
        )

        SettingsStoragePaths(
            label = if (isUniversal) "Host paths (12 known)" else "Host path",
            paths = if (isUniversal) allPaths else listOf(path)
        )

        if (!isUniversal && !sizeUi.installed && onNavigateToInstall != null) {
            AkaButton(
                text = "Install chroot",
                onClick = { onNavigateToInstall() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
            )
        }

        // ── Processes / kill services ─────────────────────────────────
        SectionLabel("Processes")
        SettingsPageNote(
            if (isUniversal) {
                "Orphans survive app close across all roots. Kill before uninstall if stuck."
            } else {
                "Orphans survive app close (unlike proot). Kill before uninstall if stuck."
            }
        )
        SettingsPageBlock {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Chroot processes",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { scanProcs() }, enabled = !busy, shape = MaterialTheme.shapes.small) {
                    Text("Scan")
                }
            }
            if (procUi.scanning || procUi.killing) {
                LinearProgressIndicator(
                    color = if (procUi.killing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(FluxSpacing.sm))
            }
            SettingsPageFigure(
                value = if (procUi.count < 0) "—" else procUi.count.toString(),
                unit = "running",
                color = if (procUi.count > 0) MaterialTheme.colorScheme.error else body
            )
            Text(
                if (isUniversal) "roots=12 known · scan all catalog paths" else "root=$path",
                style = CodeStyle,
                color = muted
            )
            Text(
                procUi.hint,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = FluxSpacing.xs)
            )

            if (procUi.processes.isNotEmpty()) {
                Spacer(Modifier.height(FluxSpacing.sm))
                Text("Sample", style = MaterialTheme.typography.labelMedium, color = muted)
                procUi.processes.take(5).forEach { p ->
                    Text("${p.pid}  ${p.comm}", style = CodeStyle, color = body)
                }
                val more = procUi.processes.size - 5
                if (more > 0) {
                    Text("+$more more", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }

        val isOperationBusy = busy || killCoordinator.isBusy
        val killEnabled = ChrootSettingsModel.isKillEnabled(
            rootOk = procUi.rootOk,
            busy = isOperationBusy
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
        ) {
            val progress = killProgress
            val killText = if (progress != null) {
                "Killing (${progress.first}/${progress.second})…"
            } else if (isUniversal) {
                "Kill all across roots"
            } else {
                "Kill all chroot processes"
            }
            SettingsPageDangerButton(
                text = killText,
                onClick = { showKillConfirm = true },
                enabled = killEnabled,
                modifier = Modifier.fillMaxWidth(),
                leading = if (procUi.killing) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                    }
                } else {
                    null
                }
            )

            if (procUi.killing && isUniversal) {
                val isCancelling = killCoordinator.state == ChrootKillCoordinator.State.CANCEL_REQUESTED
                SettingsPageSecondaryButton(
                    text = if (isCancelling) "Cancelling…" else "Cancel",
                    onClick = { cancelKill() },
                    enabled = !isCancelling,
                    danger = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            procUi.statusLine?.let {
                Text(it, style = CodeStyle, color = muted)
            }
        }

        SettingsStorageRefresh(
            text = "Refresh all",
            working = busy,
            onClick = { refreshAll(force = true) }
        )
    }
}

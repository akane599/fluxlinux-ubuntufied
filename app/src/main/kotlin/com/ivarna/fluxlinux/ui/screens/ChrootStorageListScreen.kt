package com.ivarna.fluxlinux.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.chroot.ChrootInfoStore
import com.ivarna.fluxlinux.core.chroot.ChrootSettingsModel
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chroot storage list screen: renders all chroots aggregate row and installed chroots.
 * Two-phase loading: initial paint from cache/TTL, followed by sequential IO Job.
 */
@Composable
fun ChrootStorageListScreen(
    onBack: () -> Unit,
    onSelectDistro: (distroId: String) -> Unit,
    onNavigateToHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val stateRefresh by StateManager.refreshTrigger.collectAsState()
    var lifecycleRefreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val catalogDistros = remember { GuestStorageCatalog.installableChroots() }
    val sortedCatalog = remember(catalogDistros) {
        DistroRepository.sortForDistroPage(catalogDistros)
    }

    // Dynamic installed state and live sizes map
    val liveSizes = remember { mutableStateMapOf<String, Long?>() }
    val installedSet = remember { mutableStateMapOf<String, Boolean>() }
    var liveMeasuring by remember { mutableStateOf(false) }

    // Phase 1 cache read
    val initialRows = remember(lifecycleRefreshKey, stateRefresh) {
        sortedCatalog.filter { d ->
            val path = GuestStorageCatalog.chrootPathOrNull(d.id) ?: return@filter false
            ChrootDetection.isInstalled(path) || ChrootInfoStore.cachedInstalled(context, d.id)
        }
    }

    // Initialize map from cache if not already populated
    LaunchedEffect(initialRows) {
        initialRows.forEach { d ->
            installedSet[d.id] = true
            if (!liveSizes.containsKey(d.id)) {
                liveSizes[d.id] = ChrootInfoStore.cachedBytes(context, d.id)
            }
        }
    }

    // Phase 2 sequential IO Job
    fun runSequentialProbe() {
        if (liveMeasuring) return
        liveMeasuring = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val ids = sortedCatalog.map { it.id }
                ChrootSettingsModel.refreshInstalledSizes(context, ids, forceClearSuOnce = true)
            }
            installedSet.clear()
            results.forEach { (id, sizeUi) ->
                if (sizeUi.installed) {
                    installedSet[id] = true
                    liveSizes[id] = sizeUi.bytes
                } else {
                    installedSet[id] = false
                    liveSizes.remove(id)
                }
            }
            liveMeasuring = false
        }
    }

    LaunchedEffect(lifecycleRefreshKey, stateRefresh) {
        runSequentialProbe()
    }

    val installedDistros = sortedCatalog.filter { d ->
        installedSet[d.id] == true || (installedSet[d.id] == null && initialRows.any { it.id == d.id })
    }

    val totalBytes: Long? = run {
        val measured = installedDistros.mapNotNull { d -> liveSizes[d.id] ?: ChrootInfoStore.cachedBytes(context, d.id) }
        if (measured.isEmpty()) null else measured.sum()
    }

    val totalProcCount: Int = run {
        val cached = ChrootInfoStore.cachedProcCount(context, GuestStorageCatalog.ALL_CHROOT_ID)
        if (cached >= 0) {
            cached
        } else {
            val perIdCounts = installedDistros.map { d -> ChrootInfoStore.cachedProcCount(context, d.id) }
            if (perIdCounts.any { it >= 0 }) {
                perIdCounts.filter { it >= 0 }.sum()
            } else {
                -1
            }
        }
    }

    SettingsPageScaffold(title = "Chroot", onBack = onBack) {
        SettingsPageAttention("Rootfs is not removed when you uninstall the app. Free space here.")

        // Universal row (always present)
        val (sizeVal, sizeUnit) = ChrootInfoStore.formatStorageBytes(totalBytes)
        val sizeStr = if (sizeVal == "—") "—" else "$sizeVal $sizeUnit"
        val procStr = if (totalProcCount >= 0) "$totalProcCount running" else "— running"
        SettingsStorageRow(
            title = "All chroots",
            figure = "${installedDistros.size} installed · $sizeStr · $procStr",
            icon = Icons.Sharp.Layers,
            chevronDescription = "View all",
            onClick = { onSelectDistro(GuestStorageCatalog.ALL_CHROOT_ID) }
        )

        // Installed rows or empty CTA
        if (installedDistros.isNotEmpty()) {
            SectionLabel("Installed roots")
            installedDistros.forEach { distro ->
                val path = GuestStorageCatalog.chrootPathOrNull(distro.id) ?: ""
                val bytes = liveSizes[distro.id] ?: ChrootInfoStore.cachedBytes(context, distro.id)
                val (v, u) = ChrootInfoStore.formatStorageBytes(bytes)
                val rowSize = if (v == "—") "—" else "$v $u"
                SettingsStorageRow(
                    title = distro.name,
                    figure = rowSize,
                    path = path,
                    status = { SettingsPageStatus("Installed", MarkState.IDLE) },
                    chevronDescription = "View detail",
                    onClick = { onSelectDistro(distro.id) }
                )
            }
        } else {
            SettingsStorageEmpty(
                title = "No chroot installed",
                hint = stringResource(R.string.storage_chroot_empty_hint),
                action = stringResource(R.string.storage_go_home),
                onAction = onNavigateToHome
            )
        }

        SettingsStorageRefresh(
            text = "Refresh sizes",
            working = liveMeasuring,
            onClick = { runSequentialProbe() }
        )
    }
}

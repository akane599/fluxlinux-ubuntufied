package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.KeyboardArrowRight
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.proot.ProotInfoStore
import com.ivarna.fluxlinux.core.proot.ProotSettingsModel
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.AkaButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PRoot storage list screen: displays all PRoot containers and total container size.
 */
@Composable
fun ProotStorageListScreen(
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

    val catalogDistros = remember { GuestStorageCatalog.installableProots() }
    val sortedCatalog = remember(catalogDistros) {
        DistroRepository.sortForDistroPage(catalogDistros)
    }

    val liveSizes = remember { mutableStateMapOf<String, Long?>() }
    val installedSet = remember { mutableStateMapOf<String, Boolean>() }
    var liveMeasuring by remember { mutableStateOf(false) }

    // Phase 1 cache read
    val initialRows = remember(lifecycleRefreshKey, stateRefresh) {
        sortedCatalog.filter { d ->
            TerminalLauncher.isDistroInstalledOnFs(context, d.id) ||
                ProotInfoStore.cachedInstalled(context, d.id)
        }
    }

    LaunchedEffect(initialRows) {
        initialRows.forEach { d ->
            installedSet[d.id] = true
            if (!liveSizes.containsKey(d.id)) {
                liveSizes[d.id] = ProotInfoStore.cachedBytes(context, d.id)
            }
        }
    }

    fun runSequentialProbe() {
        if (liveMeasuring) return
        liveMeasuring = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val ids = sortedCatalog.map { it.id }
                ProotSettingsModel.refreshInstalledSizes(context, ids)
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
        val measured = installedDistros.mapNotNull { d -> liveSizes[d.id] ?: ProotInfoStore.cachedBytes(context, d.id) }
        if (measured.isEmpty()) null else measured.sum()
    }

    SettingsPageScaffold(title = "PRoot", onBack = onBack) {
        SettingsPageNote("PRoot lives in app storage (container + rootfs) and is removed if you uninstall the app.")

        // Universal row (always present)
        val (sizeVal, sizeUnit) = ProotInfoStore.formatStorageBytes(totalBytes)
        val totalStr = if (sizeVal == "—") "—" else "$sizeVal $sizeUnit"
        SettingsStorageRow(
            title = "All containers",
            figure = "${installedDistros.size} installed · $totalStr",
            icon = Icons.Sharp.Folder,
            chevronDescription = "View all",
            onClick = { onSelectDistro(GuestStorageCatalog.ALL_PROOT_ID) }
        )

        // Installed rows or empty state
        if (installedDistros.isNotEmpty()) {
            SectionLabel("Installed containers")
            installedDistros.forEach { distro ->
                val path = GuestStorageCatalog.prootContainerPath(context, distro.id) ?: ""
                val bytes = liveSizes[distro.id] ?: ProotInfoStore.cachedBytes(context, distro.id)
                val (v, u) = ProotInfoStore.formatStorageBytes(bytes)
                val sizeStr = if (v == "—") "—" else "$v $u"
                SettingsStorageRow(
                    title = distro.name,
                    figure = sizeStr,
                    path = path,
                    status = { SettingsPageStatus("Installed", MarkState.IDLE) },
                    chevronDescription = "View detail",
                    onClick = { onSelectDistro(distro.id) }
                )
            }
        } else {
            SettingsStorageEmpty(
                title = "No PRoot container installed",
                hint = stringResource(R.string.storage_proot_empty_hint),
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

/**
 * A storage row on the stock: title (and an optional status at its end), the measured
 * figure and the host path in mono, a chevron to the detail page.
 */
@Composable
internal fun SettingsStorageRow(
    title: String,
    figure: String,
    chevronDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    path: String? = null,
    icon: ImageVector? = null,
    status: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(FluxSpacing.lg))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (status != null) {
                    Spacer(Modifier.width(FluxSpacing.md))
                    status()
                }
            }
            Text(figure, style = CodeStyle, color = MaterialTheme.colorScheme.onSurface)
            if (!path.isNullOrEmpty()) {
                Text(path, style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(FluxSpacing.md))
        Icon(
            Icons.AutoMirrored.Sharp.KeyboardArrowRight,
            contentDescription = chevronDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Nothing installed: what that means, and the one way forward (the akane primary). */
@Composable
internal fun SettingsStorageEmpty(
    title: String,
    hint: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(FluxSpacing.xs))
        AkaButton(text = action, onClick = onAction)
    }
}

/** The page's re-measure action, with a working bar while it runs. */
@Composable
internal fun SettingsStorageRefresh(
    text: String,
    working: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
    ) {
        if (working) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsPageSecondaryButton(
            text = text,
            icon = Icons.Sharp.Refresh,
            onClick = onClick,
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

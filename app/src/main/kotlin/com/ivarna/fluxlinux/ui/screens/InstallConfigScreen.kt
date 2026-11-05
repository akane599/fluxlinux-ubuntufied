package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.desktop.UbuntuDesktop
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.ui.components.GlassScaffold
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.install.InstallThemePickRow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin install wizard (no feature modules). On Ubuntu it re-runs setup with the desktop
 * only when the desktop is installed ([InstallKind.forRerun]); other distros always get
 * XFCE. Uses the process-scoped [InstallController] for both proot and chroot.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun InstallConfigScreen(
    distro: Distro,
    onBack: () -> Unit,
    onInstallComplete: () -> Unit,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    // H2: process-scoped install; leaving this screen never cancels it (only Back/Cancel does).
    val installer = remember { InstallController.get(appCtx) }
    // A fresh open (not a rotation restore) forgets a finished run the user left with
    // system Back, so it neither shows DONE again nor re-toasts. Runs before collecting.
    rememberSaveable { installer.clearFinished(); true }
    val install by installer.state.collectAsState()
    var selectedTheme by rememberSaveable { mutableStateOf("dark") }
    var showLog by rememberSaveable { mutableStateOf(true) }
    // Re-running setup keeps an installed desktop and never adds one (that is Home's add-on).
    // Starts from the stored flag; the rootfs check runs off main, and a chroot rootfs is
    // root-only, so without the flag it asks root (which also heals the flag).
    var desktopInstalled by remember(distro.id) {
        mutableStateOf(StateManager.isComponentInstalled(appCtx, distro.id, UbuntuDesktop.COMPONENT_ID))
    }
    LaunchedEffect(distro.id) {
        if (desktopInstalled) return@LaunchedEffect
        desktopInstalled = withContext(Dispatchers.IO) {
            runCatching {
                UbuntuDesktop.isInstalled(appCtx, distro.id) ||
                    (UbuntuRuntime.isChrootId(distro.id) && UbuntuDesktop.probe(appCtx, distro.id))
            }.getOrDefault(false)
        }
    }
    val kind = InstallKind.effective(distro.id, InstallKind.forRerun(desktopInstalled))
    val withDesktop = kind.installsDesktop
    val mine = install.isActiveFor(distro.id)
    val failed = mine && install.status == InstallController.Status.Failed
    val phase = when {
        !mine -> InstallPhase.OPTIONS
        install.status == InstallController.Status.Succeeded -> InstallPhase.DONE
        else -> InstallPhase.RUNNING
    }

    // One success toast per run, also across recreation.
    var toastedRun by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(install.runId, install.status) {
        if (phase == InstallPhase.DONE && toastedRun != install.runId) {
            toastedRun = install.runId
            val done = if (install.kind.installsDesktop) {
                R.string.install_done_terminal_desktop
            } else {
                R.string.install_done_terminal
            }
            Toast.makeText(context, context.getString(done), Toast.LENGTH_SHORT).show()
        }
    }

    /** [runKind]: Retry repeats the failed run as it was (e.g. Home's desktop add-on). */
    fun startInstall(runKind: InstallKind = kind) {
        // One process-wide install at a time; a new start supersedes the previous run.
        installer.start(distro.id, selectedTheme, runKind)
    }

    GlassScaffold(
        hazeState = hazeState,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (phase) {
                            InstallPhase.OPTIONS -> "Install ${distro.name}"
                            InstallPhase.RUNNING -> if (failed) "Install failed" else "Installing…"
                            InstallPhase.DONE -> "Install complete"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (phase == InstallPhase.RUNNING && !failed) {
                                // Explicit user cancel.
                                installer.cancel()
                            } else if (mine) {
                                installer.clearFinished()
                            }
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.hazeChild(
                    state = hazeState,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    style = HazeMaterials.thin()
                )
            )
        },
        bottomBar = {
            if (phase == InstallPhase.OPTIONS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = { startInstall() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            // Cream secondary = readable CTA (dark primary blends into background)
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        val root = distro.id.contains("chroot")
                        val cta = when {
                            withDesktop && root -> R.string.install_cfg_cta_terminal_desktop_root
                            withDesktop -> R.string.install_cfg_cta_terminal_desktop
                            root -> R.string.install_cfg_cta_terminal_root
                            else -> R.string.install_cfg_cta_terminal
                        }
                        Text(stringResource(cta), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    ) {
        when (phase) {
            InstallPhase.OPTIONS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(
                        if (withDesktop) {
                            R.string.install_cfg_heading_terminal_desktop
                        } else {
                            R.string.install_cfg_heading_terminal
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                val profile = DistroInstallProfile.forId(distro.id)
                val displayName = profile?.displayName ?: distro.name
                val rootfsLine = when {
                    UbuntuRuntime.isUbuntuId(distro.id) -> stringResource(
                        if (withDesktop) {
                            R.string.install_cfg_body_terminal_desktop
                        } else {
                            R.string.install_cfg_body_terminal
                        },
                        displayName,
                    ) + " "
                    distro.id.startsWith("alpine") ->
                        "Installs Alpine 3.24 minirootfs, XFCE4 (apk), and Flux customization. "
                    distro.id.startsWith("fedora") ->
                        "Installs Fedora 44 rootfs, XFCE4 (dnf), Mesa, and Flux customization. "
                    distro.id.startsWith("void") ->
                        "Installs Void Linux rootfs, XFCE4 (xbps), Mesa, and Flux customization. "
                    distro.id.startsWith("opensuse") ->
                        "Installs openSUSE Tumbleweed rootfs, XFCE4 (zypper), Mesa, and Flux customization. "
                    else ->
                        "Installs $displayName rootfs, XFCE4, and Flux customization. "
                }
                Text(
                    rootfsLine +
                        "App modules can be added later from Distro Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The theme only styles the desktop; a terminal-only run has nothing to theme.
                if (withDesktop) {
                    Text(
                        "Appearance theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                    InstallThemePickRow(
                        id = "dark",
                        title = "Dark",
                        desc = "Sleek default",
                        selected = selectedTheme == "dark",
                        onSelect = { selectedTheme = "dark" }
                    )
                    InstallThemePickRow(
                        id = "light",
                        title = "Light",
                        desc = "Bright desktop",
                        selected = selectedTheme == "light",
                        onSelect = { selectedTheme = "light" }
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                        .border(1.dp, com.ivarna.fluxlinux.ui.theme.FluxHairline, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            "Includes",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        val bulletColor = com.ivarna.fluxlinux.ui.theme.fluxMutedText()
                        Text(
                            when {
                                distro.id.startsWith("alpine") ->
                                    "• Alpine 3.24 minirootfs (~4 MB + apk packages)"
                                distro.id.startsWith("fedora") ->
                                    "• Fedora 44 container rootfs"
                                distro.id.startsWith("void") ->
                                    "• Void Linux (glibc aarch64) rootfs"
                                distro.id.startsWith("opensuse") ->
                                    "• openSUSE Tumbleweed rootfs"
                                else ->
                                    "• $displayName rootfs"
                            },
                            fontSize = 13.sp,
                            color = bulletColor
                        )
                        Text(stringResource(R.string.install_cfg_bullet_shell), fontSize = 13.sp, color = bulletColor)
                        if (withDesktop) {
                            Text(stringResource(R.string.install_cfg_bullet_xfce), fontSize = 13.sp, color = bulletColor)
                        }
                        if (distro.id.startsWith("fedora") || distro.id.startsWith("void") ||
                            distro.id.startsWith("opensuse")
                        ) {
                            Text(
                                "• Mesa / VirGL hardware acceleration",
                                fontSize = 13.sp,
                                color = bulletColor
                            )
                        }
                        if (withDesktop) {
                            Text(stringResource(R.string.install_cfg_bullet_theme), fontSize = 13.sp, color = bulletColor)
                        }
                        if (distro.id.contains("chroot")) {
                            Text("• Requires root (KernelSU / Magisk)", fontSize = 13.sp, color = bulletColor)
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
            InstallPhase.RUNNING, InstallPhase.DONE -> InstallProgressPanel(
                percent = install.percent,
                phaseLabel = install.phaseLabel.ifBlank { stringResource(R.string.install_starting) },
                detail = install.detail,
                logText = install.logText,
                failed = failed,
                errorMessage = install.errorMessage,
                showLog = showLog,
                onToggleLog = { showLog = !showLog },
                onRetry = { startInstall(install.kind) },
                onDone = {
                    installer.clearFinished()
                    onInstallComplete()
                },
                isDone = phase == InstallPhase.DONE
            )
        }
    }
}

private enum class InstallPhase { OPTIONS, RUNNING, DONE }

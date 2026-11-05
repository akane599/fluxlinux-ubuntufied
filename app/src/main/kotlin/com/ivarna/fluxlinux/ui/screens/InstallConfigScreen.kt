package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.desktop.UbuntuDesktop
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.install.InstallThemePickRow
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin install wizard (no feature modules). On Ubuntu it re-runs setup with the desktop
 * only when the desktop is installed ([InstallKind.forRerun]); other distros always get
 * XFCE. Uses the process-scoped [InstallController] for both proot and chroot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallConfigScreen(
    distro: Distro,
    onBack: () -> Unit,
    onInstallComplete: () -> Unit,
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar takes the status bar; the bottom takes the navigation bar.
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (phase) {
                            InstallPhase.OPTIONS -> stringResource(R.string.aka_install_cfg_title, distro.name)
                            InstallPhase.RUNNING -> stringResource(
                                if (failed) R.string.aka_install_cfg_title_failed else R.string.aka_install_cfg_title_running
                            )
                            InstallPhase.DONE -> stringResource(R.string.aka_install_title_done)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                            Icons.AutoMirrored.Sharp.ArrowBack,
                            contentDescription = stringResource(R.string.aka_onb_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            if (phase == InstallPhase.OPTIONS) {
                val root = distro.id.contains("chroot")
                val cta = when {
                    withDesktop && root -> R.string.install_cfg_cta_terminal_desktop_root
                    withDesktop -> R.string.install_cfg_cta_terminal_desktop
                    root -> R.string.install_cfg_cta_terminal_root
                    else -> R.string.install_cfg_cta_terminal
                }
                PlatedButton(
                    text = stringResource(cta),
                    onClick = { startInstall() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md)
                )
            }
        }
    ) { padding ->
        when (phase) {
            InstallPhase.OPTIONS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
            ) {
                Text(
                    stringResource(
                        if (withDesktop) {
                            R.string.install_cfg_heading_terminal_desktop
                        } else {
                            R.string.install_cfg_heading_terminal
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(FluxSpacing.sm))
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
                    // Other distros are not in the main UI; their copy stays as it was.
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
                    rootfsLine + stringResource(R.string.aka_install_cfg_modules_later),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The theme only styles the desktop; a terminal-only run has nothing to theme.
                if (withDesktop) {
                    Spacer(Modifier.height(FluxSpacing.xl))
                    GroupTitle(stringResource(R.string.onboarding_theme_title))
                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
                    ) {
                        InstallThemePickRow(
                            id = "dark",
                            title = stringResource(R.string.onboarding_theme_dark),
                            desc = stringResource(R.string.onboarding_theme_dark_desc),
                            selected = selectedTheme == "dark",
                            onSelect = { selectedTheme = "dark" }
                        )
                        InstallThemePickRow(
                            id = "light",
                            title = stringResource(R.string.onboarding_theme_light),
                            desc = stringResource(R.string.onboarding_theme_light_desc),
                            selected = selectedTheme == "light",
                            onSelect = { selectedTheme = "light" }
                        )
                    }
                }

                Spacer(Modifier.height(FluxSpacing.xl))
                GroupTitle(stringResource(R.string.onboarding_manifest_title))
                // The shared strings carry a "• " prefix; the list draws its own marks.
                val bullets = buildList {
                    add(
                        when {
                            distro.id.startsWith("alpine") -> "Alpine 3.24 minirootfs (~4 MB + apk packages)"
                            distro.id.startsWith("fedora") -> "Fedora 44 container rootfs"
                            distro.id.startsWith("void") -> "Void Linux (glibc aarch64) rootfs"
                            distro.id.startsWith("opensuse") -> "openSUSE Tumbleweed rootfs"
                            else -> "$displayName rootfs"
                        }
                    )
                    add(stringResource(R.string.install_cfg_bullet_shell))
                    if (withDesktop) {
                        add(stringResource(R.string.install_cfg_bullet_xfce))
                    }
                    if (distro.id.startsWith("fedora") || distro.id.startsWith("void") ||
                        distro.id.startsWith("opensuse")
                    ) {
                        add("Mesa / VirGL hardware acceleration")
                    }
                    if (withDesktop) {
                        add(stringResource(R.string.install_cfg_bullet_theme))
                    }
                    if (distro.id.contains("chroot")) {
                        add(stringResource(R.string.aka_install_cfg_needs_root))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(FluxSpacing.xs)) {
                    bullets.forEach { BulletLine(it) }
                }
                Spacer(Modifier.height(FluxSpacing.lg))
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
                isDone = phase == InstallPhase.DONE,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(bottom = FluxSpacing.sm)
    )
}

@Composable
private fun BulletLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = FluxSpacing.sm)
                .size(FluxSpacing.xs + FluxSpacing.xxs)
                .background(MaterialTheme.colorScheme.secondary)
        )
        Spacer(Modifier.width(FluxSpacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class InstallPhase { OPTIONS, RUNNING, DONE }

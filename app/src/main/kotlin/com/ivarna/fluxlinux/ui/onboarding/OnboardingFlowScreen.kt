package com.ivarna.fluxlinux.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.system.GuestStorageLinks
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.ui.components.MethodChip
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.theme.BrandCream
import com.ivarna.fluxlinux.ui.theme.FluxAccentCyan
import com.ivarna.fluxlinux.ui.theme.FluxAccentMagenta
import com.ivarna.fluxlinux.ui.theme.FluxDarkGrey
import com.ivarna.fluxlinux.ui.theme.FluxDarkSurface
import com.ivarna.fluxlinux.ui.theme.FluxBodyMuted
import com.ivarna.fluxlinux.ui.theme.FluxHairline
import com.ivarna.fluxlinux.ui.theme.FluxSwitchUncheckedTrack
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlin.concurrent.thread

private enum class OnboardStep { Welcome, Consent, HostSetup, Options, Storage, Running, Done }

/** First-run setup always installs this proot guest (Ubuntu 26.04, see DistroInstallProfile). */
private const val UBUNTU_DISTRO_ID = "ubuntu"

/** Numbered setup steps shown in [StepHeader]: Consent, Host setup, Options, Storage. */
private const val ONBOARD_STEP_COUNT = 4

private val OnboardSuccess = Color(0xFF69F0AE)

/**
 * First-run onboarding:
 * Welcome → Disclosure/Consent → Host Setup → Options → Storage access → Progress → Complete.
 * Installs the Ubuntu rootfs + XFCE + customization. An existing Ubuntu rootfs is reused, never wiped.
 */
@Composable
fun OnboardingFlowScreen(
    onFinished: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onStartDesktop: (distroId: String) -> Unit = {}
) {
    val context = LocalContext.current
    // H2: the install is process-scoped. Leaving composition (rotation, uiMode change,
    // split-screen, Back) never cancels it; only the explicit Cancel action does.
    val installer = remember { InstallController.get(context.applicationContext) }
    // A fresh open (not a rotation restore) forgets a finished run left via system
    // Back; a running install is kept and resumed below. Runs before collecting.
    rememberSaveable { installer.clearFinished(); true }
    val install by installer.state.collectAsState()
    var step by rememberSaveable {
        // Re-opened while an Ubuntu install runs (or just finished): show its progress.
        mutableStateOf(
            if (installer.state.value.isActiveFor(UBUNTU_DISTRO_ID)) OnboardStep.Running else OnboardStep.Welcome
        )
    }
    var theme by rememberSaveable { mutableStateOf("dark") }
    var showLog by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(step, install.runId, install.status) {
        if (step != OnboardStep.Running) return@LaunchedEffect
        when {
            // Restored after process death: the install died with the process.
            !install.isActiveFor(UBUNTU_DISTRO_ID) -> step = OnboardStep.Options
            install.status == InstallController.Status.Succeeded -> {
                // Idempotent: only creates missing ~/storage links, never replaces anything.
                val appContext = context.applicationContext
                thread(name = "onboarding-storage-links") {
                    runCatching { GuestStorageLinks.ensure(appContext, UBUNTU_DISTRO_ID) }
                }
                step = OnboardStep.Done
            }
        }
    }

    fun startInstall() {
        step = OnboardStep.Running
        installer.start(UBUNTU_DISTRO_ID, theme)
    }

    /** Leaving the Done page: the finished run no longer needs to be shown. */
    fun leaveDone(then: () -> Unit) {
        installer.clearFinished()
        then()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluxDarkSurface)
            .systemBarsPadding()
    ) {
        when (step) {
            OnboardStep.Welcome -> WelcomePage(onNext = { step = OnboardStep.Consent })
            OnboardStep.Consent -> ConsentPage(
                onBack = { step = OnboardStep.Welcome },
                onNext = { step = OnboardStep.HostSetup }
            )
            OnboardStep.HostSetup -> HostSetupPage(
                onBack = { step = OnboardStep.Consent },
                onNext = { step = OnboardStep.Options }
            )
            OnboardStep.Options -> OptionsPage(
                theme = theme,
                onTheme = { theme = it },
                onBack = { step = OnboardStep.HostSetup },
                onInstall = {
                    if (SharedStorageAccess.isSupported(context) && !SharedStorageAccess.granted(context)) {
                        step = OnboardStep.Storage
                    } else {
                        startInstall()
                    }
                }
            )
            OnboardStep.Storage -> StoragePage(
                onBack = { step = OnboardStep.Options },
                onNext = { startInstall() }
            )
            OnboardStep.Running -> InstallProgressPanel(
                percent = install.percent,
                phaseLabel = install.phaseLabel.ifBlank { stringResource(R.string.install_starting) },
                detail = install.detail,
                logText = install.logText,
                failed = install.status == InstallController.Status.Failed,
                errorMessage = install.errorMessage,
                showLog = showLog,
                onToggleLog = { showLog = !showLog },
                onRetry = { startInstall() },
                onBack = {
                    // Explicit user cancel — the only path that stops the install.
                    installer.cancel()
                    step = OnboardStep.Options
                }
            )
            OnboardStep.Done -> DonePage(
                onHome = { leaveDone(onFinished) },
                onTerminal = { leaveDone(onOpenTerminal) },
                onDesktop = { leaveDone { onStartDesktop(UBUNTU_DISTRO_ID) } }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. WELCOME PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Hero Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glowing Logo Container
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                FluxAccentMagenta.copy(alpha = 0.25f),
                                FluxAccentCyan.copy(alpha = 0.25f)
                            )
                        )
                    )
                    .border(1.dp, FluxHairline, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_logo),
                    contentDescription = "FluxLinux Logo",
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "FluxLinux",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.onBackground,
                letterSpacing = 0.5.sp
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = colors.onBackground.copy(alpha = 0.72f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Scrollable Features & Illustration Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Feature 1: what setup installs
            ModernFeatureCard(
                icon = Icons.Filled.Storage,
                iconTint = FluxAccentMagenta,
                title = stringResource(R.string.onboarding_feature_ubuntu_title),
                description = stringResource(R.string.onboarding_feature_ubuntu_desc)
            )

            // Feature 2: Desktop & Themes
            ModernFeatureCard(
                icon = Icons.Filled.DesktopWindows,
                iconTint = FluxAccentCyan,
                title = "XFCE4 Desktop",
                description = "Pre-configured desktop experience with Flux glass themes, icons, wallpapers, and fonts."
            )

            // Feature 3: Integrated Terminal & X11
            ModernFeatureCard(
                icon = Icons.Filled.Terminal,
                iconTint = BrandCream,
                title = "Embedded Terminal & X11",
                description = "Built-in hardware accelerated X11 display and multi-tab terminal with Oh My Zsh integration."
            )

            Spacer(Modifier.height(4.dp))

            // Responsive Illustration Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.45f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.onboarding_bg_1),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    FluxDarkSurface.copy(alpha = 0.65f)
                                )
                            )
                        )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Get Started CTA
        FluxPrimaryButton(
            text = "Get Started",
            icon = Icons.Filled.ChevronRight,
            onClick = onNext
        )
    }
}

@Composable
private fun ModernFeatureCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.72f))
            .border(1.dp, FluxHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.15f))
                .border(1.dp, iconTint.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                color = colors.onSurface.copy(alpha = 0.68f),
                fontSize = 12.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. CONSENT PAGE (GITHUB DOWNLOAD NOTICE)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConsentPage(onBack: () -> Unit, onNext: () -> Unit) {
    var downloadConsent by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Step Navigation Bar
        StepHeader(
            currentStep = 1,
            totalSteps = ONBOARD_STEP_COUNT,
            title = "External Downloads",
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // Notice Header Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(FluxAccentMagenta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = null,
                                tint = FluxAccentMagenta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "F-Droid & Package Notice",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.onboarding_notice_body),
                        color = colors.onSurface.copy(alpha = 0.75f),
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    // Breakdown chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DownloadItemBadge(
                            title = stringResource(R.string.onboarding_notice_chip_host),
                            size = stringResource(R.string.onboarding_notice_chip_host_size),
                            modifier = Modifier.weight(1f)
                        )
                        DownloadItemBadge(
                            title = stringResource(R.string.onboarding_notice_chip_rootfs),
                            size = stringResource(R.string.onboarding_notice_chip_rootfs_size),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Interactive Consent Card
            FluxConsentCheckboxCard(
                consented = downloadConsent,
                onConsentChange = { downloadConsent = it }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = "Continue",
                enabled = downloadConsent,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onNext,
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun DownloadItemBadge(
    title: String,
    size: String,
    modifier: Modifier = Modifier
) {
    // Title above size: side by side, "~80-150 MiB" got clipped at phone width.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(FluxDarkSurface.copy(alpha = 0.8f))
            .border(1.dp, FluxHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = size,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = FluxAccentCyan
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. HOST SETUP PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HostSetupPage(onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    var bootstrapDone by remember { mutableStateOf(TerminalLauncher.isHostSetupDone(context)) }
    var initializing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    fun startInit() {
        initializing = true
        statusText = "Extracting host bootstrap…"
        TerminalLauncher.prepareHost(
            context,
            progress = { done, total, phase ->
                statusText = phase
                if (total > 0) progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            },
            onDone = { ok ->
                initializing = false
                bootstrapDone = TerminalLauncher.isHostSetupDone(context)
                statusText = if (bootstrapDone) "Host environment ready."
                else "Setup failed — check device storage."
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(
            currentStep = 2,
            totalSteps = ONBOARD_STEP_COUNT,
            title = "Host Environment",
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.onboarding_host_setup_intro),
                color = colors.onBackground.copy(alpha = 0.72f),
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(16.dp))

            // Dedicated Host Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.75f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (bootstrapDone) Color(0xFF69F0AE).copy(alpha = 0.15f)
                                        else FluxAccentMagenta.copy(alpha = 0.15f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (bootstrapDone) Icons.Filled.CheckCircle else Icons.Filled.Memory,
                                    contentDescription = null,
                                    tint = if (bootstrapDone) Color(0xFF69F0AE) else FluxAccentMagenta,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Embedded Host Prefix",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = if (bootstrapDone) "Initialized & Ready" else "Requires Setup",
                                    fontSize = 12.sp,
                                    color = if (bootstrapDone) Color(0xFF69F0AE) else colors.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        MethodChip(
                            label = if (bootstrapDone) "READY" else if (initializing) "EXTRACTING" else "PENDING",
                            color = if (bootstrapDone) Color(0xFF69F0AE) else if (initializing) FluxAccentMagenta else Color(0xFFFFA000)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    if (initializing) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = statusText.ifBlank { "Extracting bootstrap…" },
                                    fontSize = 12.sp,
                                    color = colors.onSurface.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FluxAccentMagenta
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = FluxAccentMagenta,
                                trackColor = colors.surfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    } else if (!bootstrapDone) {
                        Text(
                            text = "Tap below to extract the core userland files and start the embedded runtime.",
                            fontSize = 12.5.sp,
                            color = colors.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { startInit() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FluxAccentMagenta.copy(alpha = 0.20f),
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, FluxAccentMagenta.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Initialize Host", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    } else {
                        Text(
                            text = "Host environment is extracted and operational. PulseAudio and internal session services are active.",
                            fontSize = 12.5.sp,
                            color = colors.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = if (bootstrapDone) "Continue" else "Initialize",
                enabled = !initializing,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = {
                    if (bootstrapDone) onNext() else startInit()
                },
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. OPTIONS PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionsPage(
    theme: String,
    onTheme: (String) -> Unit,
    onBack: () -> Unit,
    onInstall: () -> Unit
) {
    var downloadConsent by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(
            currentStep = 3,
            totalSteps = ONBOARD_STEP_COUNT,
            title = "Desktop Options",
            onBack = onBack
        )

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Selected Distro Overview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.distro_ubuntu),
                        contentDescription = null,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.onboarding_options_distro_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = colors.onSurface
                        )
                        Text(
                            text = stringResource(R.string.onboarding_options_distro_desc),
                            fontSize = 12.sp,
                            color = colors.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            // Appearance Theme Selection
            Text(
                text = "Appearance Theme",
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                fontSize = 14.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeOptionCard(
                    title = "Dark Theme",
                    desc = "Sleek default",
                    isDark = true,
                    selected = theme == "dark",
                    onSelect = { onTheme("dark") },
                    modifier = Modifier.weight(1f)
                )
                ThemeOptionCard(
                    title = "Light Theme",
                    desc = "Bright workspace",
                    isDark = false,
                    selected = theme == "light",
                    onSelect = { onTheme("light") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Package Manifest Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.75f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Layers,
                            contentDescription = null,
                            tint = FluxAccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Package Manifest Included",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val items = listOf(
                        stringResource(R.string.onboarding_manifest_rootfs),
                        "XFCE4 lightweight desktop environment",
                        "Flux themes, wallpapers, and Nerd fonts",
                        "Embedded X11 display server + Zsh terminal"
                    )

                    items.forEach { item ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(FluxAccentMagenta)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = item,
                                fontSize = 12.5.sp,
                                color = colors.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Download Consent Checkbox Card
            FluxConsentCheckboxCard(
                consented = downloadConsent,
                onConsentChange = { downloadConsent = it }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = "Install Now",
                enabled = downloadConsent,
                icon = Icons.Filled.Download,
                onClick = onInstall,
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    desc: String,
    isDark: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) FluxAccentMagenta else FluxHairline

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) FluxAccentMagenta.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.72f))
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Swatch preview circle
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF1E1E24) else Color(0xFFE8EAF6))
                        .border(1.dp, FluxHairline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (isDark) FluxAccentMagenta else Color(0xFF1E1E24),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = FluxAccentMagenta,
                        unselectedColor = colors.onSurface.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = colors.onSurface
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. DONE / COMPLETION PAGE
// ─────────────────────────────────────────────────────────────────────────────

/** Stateless completion page (also rendered by the onboarding screenshot previews). */
@Composable
internal fun DonePage(
    onHome: () -> Unit,
    onTerminal: () -> Unit,
    onDesktop: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Ubuntu badge with a success status mark
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    colors.surface.copy(alpha = 0.85f),
                                    colors.surfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                        )
                        .border(1.5.dp, FluxHairline, RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.distro_ubuntu),
                        contentDescription = stringResource(R.string.onboarding_done_icon_desc),
                        modifier = Modifier.size(68.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(FluxDarkSurface)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(OnboardSuccess),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = FluxDarkSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.onboarding_done_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                color = colors.onBackground
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_done_body),
                textAlign = TextAlign.Center,
                color = colors.onBackground.copy(alpha = 0.72f),
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.height(14.dp))

            MethodChip(
                label = stringResource(R.string.onboarding_done_chip),
                color = FluxAccentCyan
            )

            Spacer(Modifier.height(32.dp))

            // Primary action: start the Ubuntu desktop
            Button(
                onClick = onDesktop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandCream,
                    contentColor = FluxDarkGrey
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.DesktopWindows, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.onboarding_done_start_desktop),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // Secondary action: open a terminal
            Button(
                onClick = onTerminal,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FluxAccentMagenta.copy(alpha = 0.16f),
                    contentColor = colors.onBackground
                ),
                border = BorderStroke(1.dp, FluxAccentMagenta.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.onboarding_done_open_terminal),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // Tertiary action: leave setup for the dashboard
            FluxGhostButton(
                text = stringResource(R.string.onboarding_done_dashboard),
                onClick = onHome,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STORAGE ACCESS PAGE (shown only while shared storage access is supported but not granted)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StoragePage(onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    var resumeKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeKey++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // API < 30: runtime READ/WRITE prompt. API >= 30: all-files access in system Settings.
    val legacyPermissions = remember { SharedStorageAccess.legacyPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resumeKey++ }
    val granted = remember(resumeKey) { SharedStorageAccess.granted(context) }

    StorageAccessContent(
        granted = granted,
        opensSettings = legacyPermissions.isEmpty(),
        onBack = onBack,
        onGrant = {
            if (legacyPermissions.isNotEmpty()) {
                permissionLauncher.launch(legacyPermissions)
            } else {
                SharedStorageAccess.openSettings(context)
            }
        },
        onContinue = onNext
    )
}

/** `~/storage/<name>` entries Ubuntu gets (mirrors GuestStorageLinks.LINKS), with plain descriptions. */
private val StorageLinkRows = listOf(
    "shared" to R.string.onboarding_storage_link_shared,
    "downloads" to R.string.onboarding_storage_link_downloads,
    "documents" to R.string.onboarding_storage_link_documents,
    "dcim" to R.string.onboarding_storage_link_dcim,
    "pictures" to R.string.onboarding_storage_link_pictures,
    "music" to R.string.onboarding_storage_link_music,
    "movies" to R.string.onboarding_storage_link_movies
)

/** Stateless storage step (also rendered by the onboarding screenshot previews). */
@Composable
internal fun StorageAccessContent(
    granted: Boolean,
    opensSettings: Boolean,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onContinue: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (granted) OnboardSuccess else FluxAccentMagenta

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(
            currentStep = 4,
            totalSteps = ONBOARD_STEP_COUNT,
            title = stringResource(R.string.onboarding_storage_title),
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderShared,
                    contentDescription = stringResource(R.string.onboarding_storage_icon_desc),
                    tint = accent,
                    modifier = Modifier.size(38.dp)
                )
            }

            Text(
                text = stringResource(R.string.onboarding_storage_intro),
                color = colors.onSurface.copy(alpha = 0.8f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth()
            )

            // The ~/storage links Ubuntu gets
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StorageLinkRows.forEach { (name, descRes) ->
                    StorageLinkRow(path = "~/storage/$name", description = stringResource(descRes))
                }
            }

            // The other direction: the phone's file manager sees Ubuntu's home
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(FluxAccentCyan.copy(alpha = 0.08f))
                    .border(1.dp, FluxAccentCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = FluxAccentCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.onboarding_storage_file_manager),
                    color = colors.onSurface.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            if (granted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OnboardSuccess.copy(alpha = 0.12f))
                        .border(1.dp, OnboardSuccess.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = OnboardSuccess, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.onboarding_storage_granted),
                        color = OnboardSuccess,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            } else {
                if (opensSettings) {
                    Text(
                        text = stringResource(R.string.onboarding_storage_settings_hint),
                        color = colors.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = stringResource(R.string.onboarding_storage_later),
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (granted) {
            FluxPrimaryButton(
                text = stringResource(R.string.onboarding_continue),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onContinue
            )
        } else {
            FluxPrimaryButton(
                text = stringResource(R.string.onboarding_storage_grant),
                icon = Icons.Filled.LockOpen,
                onClick = onGrant
            )
            FluxGhostButton(
                text = stringResource(R.string.onboarding_storage_skip),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StorageLinkRow(path: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = FluxAccentCyan,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REUSABLE ONBOARDING UI COMPONENTS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepHeader(
    currentStep: Int,
    totalSteps: Int,
    title: String,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val progress = currentStep.toFloat() / totalSteps.toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = BrandCream,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = "Step $currentStep of $totalSteps",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground.copy(alpha = 0.55f)
            )
        }

        Spacer(Modifier.height(6.dp))

        // Step Progress Bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = FluxAccentMagenta,
            trackColor = colors.surfaceVariant.copy(alpha = 0.4f)
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )
    }
}

@Composable
private fun FluxConsentCheckboxCard(
    consented: Boolean,
    onConsentChange: (Boolean) -> Unit
) {
    val body = stringResource(R.string.onboarding_consent_body)
    val colors = MaterialTheme.colorScheme
    val borderColor = if (consented) FluxAccentMagenta else FluxHairline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (consented) FluxAccentMagenta.copy(alpha = 0.08f) else colors.surface.copy(alpha = 0.72f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onConsentChange(!consented) }
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = consented,
            onCheckedChange = onConsentChange,
            colors = CheckboxDefaults.colors(
                checkedColor = FluxAccentMagenta,
                uncheckedColor = colors.onSurface.copy(alpha = 0.45f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = body,
            color = colors.onSurface.copy(alpha = 0.85f),
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun FluxPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandCream,
            contentColor = FluxDarkGrey,
            disabledContainerColor = FluxSwitchUncheckedTrack,
            disabledContentColor = FluxBodyMuted
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FluxGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = BrandCream)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}


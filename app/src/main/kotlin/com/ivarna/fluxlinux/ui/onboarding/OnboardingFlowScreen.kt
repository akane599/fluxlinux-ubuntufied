package com.ivarna.fluxlinux.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.sharp.CloudDownload
import androidx.compose.material.icons.sharp.DesktopWindows
import androidx.compose.material.icons.sharp.Download
import androidx.compose.material.icons.sharp.FolderOpen
import androidx.compose.material.icons.sharp.FolderShared
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material.icons.sharp.LockOpen
import androidx.compose.material.icons.sharp.Speed
import androidx.compose.material.icons.sharp.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.system.GuestStorageLinks
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeSwitch
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.aka.AkaWordmark
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.AkaButton
import com.ivarna.fluxlinux.ui.components.aka.AkaButtonKind
import com.ivarna.fluxlinux.ui.theme.TerminalBackground
import com.ivarna.fluxlinux.ui.theme.horizon
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.markColor
import com.ivarna.fluxlinux.ui.components.aka.selectionInks
import com.ivarna.fluxlinux.ui.components.aka.akaSelection
import com.ivarna.fluxlinux.ui.components.aka.akaTile
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.DesktopThemeSwatchDark
import com.ivarna.fluxlinux.ui.theme.DesktopThemeSwatchLight
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlin.concurrent.thread

private enum class OnboardStep { Welcome, Consent, HostSetup, Options, Storage, Running, Done }

/** True while an install of either Ubuntu (PRoot or chroot) runs or just finished. */
private fun InstallController.State.isUbuntuActive(): Boolean =
    isActiveFor(UbuntuRuntime.PROOT_ID) || isActiveFor(UbuntuRuntime.CHROOT_ID)

/**
 * The "Also install the XFCE desktop" switch when onboarding opens: off, unless an Ubuntu run
 * is still active (reopened from the launcher mid-install), whose choice Retry and Done keep.
 */
internal fun onboardingInstallsDesktop(state: InstallController.State): Boolean =
    state.isUbuntuActive() && state.kind.installsDesktop

/** The desktop theme when onboarding opens: an active Ubuntu run's, else dark. */
internal fun onboardingTheme(state: InstallController.State): String =
    if (state.isUbuntuActive()) state.theme else "dark"

/**
 * The runtime an onboarding install uses. A retry repeats the failed install's id. Chroot falls
 * back to PRoot only on an actual "no root" answer: an unknown root result (still probing, or
 * restored after recreation) keeps the user's choice, since chroot setup checks root itself.
 */
internal fun onboardingInstallRuntime(
    selected: UbuntuRuntimeMode,
    rootAvailable: Boolean?,
    retryDistroId: String? = null,
): UbuntuRuntimeMode = when {
    retryDistroId == UbuntuRuntime.CHROOT_ID -> UbuntuRuntimeMode.CHROOT
    retryDistroId == UbuntuRuntime.PROOT_ID -> UbuntuRuntimeMode.PROOT
    selected == UbuntuRuntimeMode.CHROOT && rootAvailable == false -> UbuntuRuntimeMode.PROOT
    else -> selected
}

/** Numbered setup steps shown in [StepHeader]: Consent, Host setup, Options, Storage. */
private const val ONBOARD_STEP_COUNT = 4

/**
 * First-run onboarding:
 * Welcome → Disclosure/Consent → Host Setup → Options → Storage access → Progress → Complete.
 * Installs the Ubuntu rootfs with the terminal setup (zsh, Oh My Zsh); the XFCE desktop and its
 * theming only when "Also install the XFCE desktop" is on (off by default, addable later from the
 * Ubuntu page). An existing Ubuntu rootfs is reused, never wiped.
 */
@Composable
fun OnboardingFlowScreen(
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
            if (installer.state.value.isUbuntuActive()) OnboardStep.Running else OnboardStep.Welcome
        )
    }
    var theme by rememberSaveable { mutableStateOf(onboardingTheme(installer.state.value)) }
    // The desktop is optional: off unless the user asks for it on the Options page. Reopened
    // during a run, it follows that run, so Retry and the Done page keep the user's choice.
    var installDesktop by rememberSaveable { mutableStateOf(onboardingInstallsDesktop(installer.state.value)) }
    var showLog by rememberSaveable { mutableStateOf(true) }
    // PRoot unless the user picks chroot on a rooted phone. A running install keeps its id.
    var runtime by rememberSaveable {
        mutableStateOf(
            when {
                installer.state.value.isActiveFor(UbuntuRuntime.CHROOT_ID) -> UbuntuRuntimeMode.CHROOT
                installer.state.value.isActiveFor(UbuntuRuntime.PROOT_ID) -> UbuntuRuntimeMode.PROOT
                else -> UbuntuRuntime.mode(context)
            }
        )
    }
    // The runtime choice is offered only once an off-main su probe succeeded; null = not asked
    // yet. Saved, so recreation neither forgets the answer nor raises a second su prompt.
    var rootAvailable by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(step == OnboardStep.Options) {
        if (step != OnboardStep.Options || rootAvailable != null) return@LaunchedEffect
        RootShell.probeRootAvailable { ok ->
            rootAvailable = ok
            if (!ok && runtime == UbuntuRuntimeMode.CHROOT) runtime = UbuntuRuntimeMode.PROOT
        }
    }

    LaunchedEffect(step, install.runId, install.status) {
        if (step != OnboardStep.Running) return@LaunchedEffect
        when {
            // Restored after process death: the install died with the process.
            !install.isUbuntuActive() -> step = OnboardStep.Options
            install.status == InstallController.Status.Succeeded -> {
                // Idempotent: only creates missing ~/storage links, never replaces anything.
                val appContext = context.applicationContext
                val installedId = install.distroId ?: runtime.distroId
                thread(name = "onboarding-storage-links") {
                    runCatching { GuestStorageLinks.ensure(appContext, installedId) }
                }
                step = OnboardStep.Done
            }
        }
    }

    fun startInstall(retry: Boolean = false) {
        runtime = onboardingInstallRuntime(
            selected = runtime,
            rootAvailable = rootAvailable,
            retryDistroId = if (retry) install.distroId else null,
        )
        // The chosen install becomes the one Home, the terminal and Files drive.
        UbuntuRuntimeSwitch.apply(context.applicationContext, runtime)
        step = OnboardStep.Running
        installer.start(
            runtime.distroId,
            theme,
            if (installDesktop) InstallKind.TERMINAL_AND_DESKTOP else InstallKind.TERMINAL,
        )
    }

    /** Leaving the Done page: the finished run no longer needs to be shown. */
    fun leaveDone(then: () -> Unit) {
        installer.clearFinished()
        then()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                installDesktop = installDesktop,
                onInstallDesktop = { installDesktop = it },
                runtime = runtime,
                rootAvailable = rootAvailable == true,
                onRuntime = { runtime = it },
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
                onRetry = { startInstall(retry = true) },
                onBack = {
                    // Explicit user cancel — the only path that stops the install.
                    installer.cancel()
                    step = OnboardStep.Options
                }
            )
            OnboardStep.Done -> DonePage(
                onTerminal = { leaveDone(onOpenTerminal) },
                onDesktop = if (installDesktop) {
                    { leaveDone { onStartDesktop(runtime.distroId) } }
                } else {
                    null
                },
                runtime = runtime
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Welcome: the app's one focal moment (the wordmark printing in two passes)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun WelcomePage(onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FluxSpacing.xl, vertical = FluxSpacing.lg)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(FluxSpacing.xxl))
            // Previews and screenshot tests capture one frame: print them registered.
            FittedWordmark(animate = !LocalInspectionMode.current)
            Spacer(Modifier.height(FluxSpacing.md))
            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onBackground
            )

            Spacer(Modifier.height(FluxSpacing.xl))
            WelcomeTerminalPlate()
            Spacer(Modifier.height(FluxSpacing.xl))

            // What setup gives you: the terminal first, the desktop as an option, files both ways.
            Column(verticalArrangement = Arrangement.spacedBy(FluxSpacing.lg)) {
                WelcomeFeatureRow(
                    icon = Icons.Sharp.Terminal,
                    title = stringResource(R.string.onboarding_feature_ubuntu_title),
                    description = stringResource(R.string.onboarding_feature_ubuntu_desc)
                )
                WelcomeFeatureRow(
                    icon = Icons.Sharp.DesktopWindows,
                    title = stringResource(R.string.onboarding_feature_desktop_title),
                    description = stringResource(R.string.onboarding_feature_desktop_desc)
                )
                WelcomeFeatureRow(
                    icon = Icons.Sharp.FolderShared,
                    title = stringResource(R.string.onboarding_feature_files_title),
                    description = stringResource(R.string.onboarding_feature_files_desc)
                )
            }
            Spacer(Modifier.height(FluxSpacing.lg))
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        AkaButton(
            text = stringResource(R.string.onboarding_get_started),
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The wordmark at display scale, shrunk only as far as needed to fit the width (narrow phones,
 * large font scales), so it never wraps or clips. A little room is kept for the horizon's glow.
 */
@Composable
private fun FittedWordmark(animate: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val base = MaterialTheme.typography.displayLarge
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val style = remember(maxPx, base, measurer) {
            val width = measurer.measure("AkaTerminal", base, maxLines = 1, softWrap = false).size.width
            // 0.94: a little room at the end so the last letter never clips.
            val fit = maxPx * 0.94f
            if (width <= 0 || width <= fit) {
                base
            } else {
                val k = fit / width
                base.copy(fontSize = base.fontSize * k, lineHeight = base.lineHeight * k)
            }
        }
        AkaWordmark(style = style, animate = animate)
    }
}

/** A plausible terminal excerpt in a terminal window: the product, shown. */
private sealed interface ExcerptLine {
    data class Command(val text: String) : ExcerptLine
    data class Output(val text: String) : ExcerptLine
}

private const val EXCERPT_PROMPT = "akane@ubuntu:~$ "

// `ls` lays the ~/storage links (GuestStorageLinks) out in columns for a narrow terminal.
private val WelcomeExcerpt = listOf(
    ExcerptLine.Command("uname -sm"),
    ExcerptLine.Output("Linux aarch64"),
    ExcerptLine.Command("ls ~/storage"),
    ExcerptLine.Output("dcim       movies    shared"),
    ExcerptLine.Output("documents  music"),
    ExcerptLine.Output("downloads  pictures"),
)

@Composable
private fun WelcomeTerminalPlate() {
    val inks = AkaTheme.inks
    val promptStyle = SpanStyle(color = inks.onChromeMuted)
    TerminalWindow(
        // An illustration: TalkBack skips it, the feature rows below say the same in words.
        modifier = Modifier.clearAndSetSemantics {}
    ) {
        WelcomeExcerpt.forEach { line ->
            val text = when (line) {
                is ExcerptLine.Command -> buildAnnotatedString {
                    withStyle(promptStyle) { append(EXCERPT_PROMPT) }
                    append(line.text)
                }
                is ExcerptLine.Output -> buildAnnotatedString { append(line.text) }
            }
            Text(text = text, style = CodeStyle, color = inks.onChrome, maxLines = 1, softWrap = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = EXCERPT_PROMPT, style = CodeStyle, color = inks.onChromeMuted, maxLines = 1, softWrap = false)
            ExcerptCursor()
        }
    }
}

/** The waiting block cursor, in akane. */
@Composable
private fun ExcerptCursor() {
    Box(
        Modifier
            .size(width = FluxSpacing.sm, height = FluxSpacing.lg)
            .background(AkaTheme.inks.primaryFill)
    )
}

/** One welcome point: a Sharp icon and two lines of text on the stock, no card around it. */
@Composable
private fun WelcomeFeatureRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(FluxSpacing.xl)
        )
        Spacer(Modifier.width(FluxSpacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.onBackground, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(FluxSpacing.xxs))
            Text(text = description, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Consent (GitHub download notice)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConsentPage(onBack: () -> Unit, onNext: () -> Unit) {
    var downloadConsent by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    StepPage(
        currentStep = 1,
        title = stringResource(R.string.aka_onb_consent_title),
        onBack = onBack,
        actions = {
            StepActionsRow(onBack) {
                AkaButton(
                    text = stringResource(R.string.onboarding_continue),
                    enabled = downloadConsent,
                    onClick = onNext,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Sharp.CloudDownload,
                contentDescription = null,
                tint = colors.secondary,
                modifier = Modifier.size(FluxSpacing.xl)
            )
            Spacer(Modifier.width(FluxSpacing.md))
            Text(
                text = stringResource(R.string.aka_onb_notice_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground
            )
        }
        Spacer(Modifier.height(FluxSpacing.md))
        Text(
            text = stringResource(R.string.onboarding_notice_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(FluxSpacing.lg))

        // What comes from where: one real group, so one tonal block.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(colors.surfaceContainerLow)
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
        ) {
            DownloadItemRow(
                title = stringResource(R.string.onboarding_notice_chip_host),
                size = stringResource(R.string.onboarding_notice_chip_host_size)
            )
            DownloadItemRow(
                title = stringResource(R.string.onboarding_notice_chip_rootfs),
                size = stringResource(R.string.onboarding_notice_chip_rootfs_size)
            )
        }

        Spacer(Modifier.height(FluxSpacing.xl))

        FluxConsentCheckboxCard(
            consented = downloadConsent,
            onConsentChange = { downloadConsent = it }
        )
    }
}

/** One download source: its name, and where it comes from or how large it is. */
@Composable
private fun DownloadItemRow(title: String, size: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(FluxSpacing.md))
        Text(
            text = size,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Host setup
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HostSetupPage(onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    var bootstrapDone by remember { mutableStateOf(TerminalLauncher.isHostSetupDone(context)) }
    var initializing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    // The last run ended without a working host: the failure holds its ink until a new run.
    var hostFailed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    fun startInit() {
        initializing = true
        hostFailed = false
        statusText = context.getString(R.string.aka_onb_host_status_extracting)
        TerminalLauncher.prepareHost(
            context,
            progress = { done, total, phase ->
                statusText = phase
                if (total > 0) progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            },
            onDone = { ok ->
                initializing = false
                bootstrapDone = TerminalLauncher.isHostSetupDone(context)
                hostFailed = !bootstrapDone
                statusText = if (bootstrapDone) context.getString(R.string.aka_onb_host_status_ready)
                else context.getString(R.string.aka_onb_host_status_failed)
            }
        )
    }

    val mark = when {
        bootstrapDone -> MarkState.RUNNING
        initializing -> MarkState.WORKING
        hostFailed -> MarkState.FAILED
        else -> MarkState.IDLE
    }
    val stateLabel = stringResource(
        when (mark) {
            MarkState.RUNNING -> R.string.aka_onb_host_state_ready
            MarkState.WORKING -> R.string.aka_onb_host_state_working
            MarkState.FAILED -> R.string.aka_onb_host_state_failed
            else -> R.string.aka_onb_host_state_pending
        }
    )

    StepPage(
        currentStep = 2,
        title = stringResource(R.string.aka_onb_host_title),
        onBack = onBack,
        actions = {
            StepActionsRow(onBack) {
                AkaButton(
                    text = stringResource(if (bootstrapDone) R.string.onboarding_continue else R.string.aka_onb_host_initialize),
                    enabled = !initializing,
                    onClick = {
                        if (bootstrapDone) onNext() else startInit()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        Text(
            text = stringResource(R.string.onboarding_host_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )

        Spacer(Modifier.height(FluxSpacing.xl))

        // The host's state: told by the mark's form, then its ink, then in words.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(colors.surfaceContainerLow)
                .padding(FluxSpacing.lg)
        ) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusMark(state = mark, size = FluxSpacing.md)
                Spacer(Modifier.width(FluxSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.aka_onb_host_name),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface
                    )
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = markColor(mark)
                    )
                }
            }

            Spacer(Modifier.height(FluxSpacing.md))

            when {
                initializing -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusText.ifBlank { stringResource(R.string.aka_onb_host_status_extracting) },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(FluxSpacing.sm))
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.secondary
                        )
                    }
                    Spacer(Modifier.height(FluxSpacing.sm))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall),
                        color = markColor(MarkState.WORKING),
                        trackColor = colors.surfaceContainerHighest
                    )
                }
                bootstrapDone -> Text(
                    text = stringResource(R.string.aka_onb_host_ready_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                else -> {
                    if (hostFailed) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error
                        )
                        Spacer(Modifier.height(FluxSpacing.xs))
                    }
                    Text(
                        text = stringResource(R.string.aka_onb_host_pending_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Options
// ─────────────────────────────────────────────────────────────────────────────

/** Stateless apart from the consent tick (also rendered by the onboarding screenshot previews). */
@Composable
internal fun OptionsPage(
    theme: String,
    onTheme: (String) -> Unit,
    onBack: () -> Unit,
    installDesktop: Boolean = false,
    onInstallDesktop: (Boolean) -> Unit = {},
    onInstall: () -> Unit,
    runtime: UbuntuRuntimeMode = UbuntuRuntimeMode.PROOT,
    rootAvailable: Boolean = false,
    onRuntime: (UbuntuRuntimeMode) -> Unit = {},
) {
    var downloadConsent by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    StepPage(
        currentStep = 3,
        title = stringResource(R.string.onboarding_options_title),
        onBack = onBack,
        actions = {
            StepActionsRow(onBack) {
                AkaButton(
                    text = stringResource(R.string.onboarding_install),
                    enabled = downloadConsent,
                    icon = Icons.Sharp.Download,
                    onClick = onInstall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        // What is being installed, as a heading row on the stock.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.distro_ubuntu),
                contentDescription = null,
                modifier = Modifier.size(FluxSpacing.xxl)
            )
            Spacer(Modifier.width(FluxSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.onboarding_options_distro_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground
                )
                Text(
                    text = stringResource(
                        if (runtime == UbuntuRuntimeMode.CHROOT) R.string.onboarding_options_distro_desc_chroot
                        else R.string.onboarding_options_distro_desc
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        // Runtime: only on rooted phones (off-main su probe), PRoot stays the default.
        if (rootAvailable) {
            Spacer(Modifier.height(FluxSpacing.xl))
            RuntimeChoiceSection(runtime = runtime, onRuntime = onRuntime)
        }

        Spacer(Modifier.height(FluxSpacing.lg))
        DesktopOptionRow(checked = installDesktop, onCheckedChange = onInstallDesktop)

        // The desktop theme only matters when the desktop is installed.
        AnimatedVisibility(visible = installDesktop) {
            Column(Modifier.padding(top = FluxSpacing.md)) {
                GroupTitle(stringResource(R.string.onboarding_theme_title))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    ThemeOption(
                        title = stringResource(R.string.onboarding_theme_dark),
                        desc = stringResource(R.string.onboarding_theme_dark_desc),
                        isDark = true,
                        selected = theme == "dark",
                        onSelect = { onTheme("dark") },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    ThemeOption(
                        title = stringResource(R.string.onboarding_theme_light),
                        desc = stringResource(R.string.onboarding_theme_light_desc),
                        isDark = false,
                        selected = theme == "light",
                        onSelect = { onTheme("light") },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }

        Spacer(Modifier.height(FluxSpacing.xl))

        // Package manifest: a short list on the stock.
        GroupTitle(stringResource(R.string.onboarding_manifest_title))
        val items = buildList {
            add(stringResource(R.string.onboarding_manifest_rootfs))
            add(stringResource(R.string.onboarding_manifest_terminal))
            if (installDesktop) {
                add(stringResource(R.string.onboarding_manifest_desktop))
                add(stringResource(R.string.onboarding_manifest_theming))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(FluxSpacing.xs)) {
            items.forEach { item -> BulletLine(item) }
        }

        Spacer(Modifier.height(FluxSpacing.xl))

        FluxConsentCheckboxCard(
            consented = downloadConsent,
            onConsentChange = { downloadConsent = it }
        )
    }
}

/** "Also install the XFCE desktop": off by default; the desktop can be added later. */
@Composable
private fun DesktopOptionRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .akaTile(MaterialTheme.shapes.medium)
            .heightIn(min = FluxSpacing.touch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FluxSpacing.xxs)) {
            Text(
                text = stringResource(R.string.onboarding_desktop_option),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground
            )
            Text(
                text = stringResource(R.string.onboarding_desktop_option_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(FluxSpacing.lg))
        FluxSwitch(checked = checked, onCheckedChange = null)
    }
}

/** PRoot vs chroot, shown on the Options page when root is available. */
@Composable
internal fun RuntimeChoiceSection(
    runtime: UbuntuRuntimeMode,
    onRuntime: (UbuntuRuntimeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        GroupTitle(stringResource(R.string.onboarding_runtime_title))
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
        ) {
            RuntimeOption(
                icon = Icons.Sharp.Layers,
                title = stringResource(R.string.onboarding_runtime_proot),
                desc = stringResource(R.string.onboarding_runtime_proot_desc),
                selected = runtime == UbuntuRuntimeMode.PROOT,
                onSelect = { onRuntime(UbuntuRuntimeMode.PROOT) }
            )
            RuntimeOption(
                icon = Icons.Sharp.Speed,
                title = stringResource(R.string.onboarding_runtime_chroot),
                desc = stringResource(R.string.onboarding_runtime_chroot_desc),
                selected = runtime == UbuntuRuntimeMode.CHROOT,
                onSelect = { onRuntime(UbuntuRuntimeMode.CHROOT) }
            )
        }
        Spacer(Modifier.height(FluxSpacing.sm))
        Text(
            text = stringResource(R.string.onboarding_runtime_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One runtime, in its tile: the selected one konjō with its horizon risen. */
@Composable
private fun RuntimeOption(
    icon: ImageVector,
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    OptionTile(selected = selected, onSelect = onSelect, modifier = Modifier.fillMaxWidth()) { muted ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) LocalContentColor.current else muted,
                modifier = Modifier.size(FluxSpacing.xl)
            )
            Spacer(Modifier.width(FluxSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = desc, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Spacer(Modifier.width(FluxSpacing.sm))
            OptionRadio(selected = selected)
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    desc: String,
    isDark: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    OptionTile(selected = selected, onSelect = onSelect, modifier = modifier) { muted ->
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The desktop theme's own swatch: content, not app chrome.
                Box(
                    modifier = Modifier
                        .size(FluxSpacing.xl)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(if (isDark) DesktopThemeSwatchDark else DesktopThemeSwatchLight)
                        .border(FluxSpacing.xxs / 2, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                )
                OptionRadio(selected = selected)
            }
            Spacer(Modifier.height(FluxSpacing.sm))
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Done
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stateless completion page (also rendered by the onboarding screenshot previews). The terminal
 * is the primary action; [onDesktop] is null when setup installed no desktop.
 */
@Composable
internal fun DonePage(
    onTerminal: () -> Unit,
    onDesktop: (() -> Unit)?,
    runtime: UbuntuRuntimeMode = UbuntuRuntimeMode.PROOT
) {
    val colors = MaterialTheme.colorScheme
    val inks = AkaTheme.inks

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FluxSpacing.xl, vertical = FluxSpacing.lg)
    ) {
        // Centred in the space above the actions while it fits; scrolls at large font scales.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val minHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight),
                verticalArrangement = Arrangement.Center
            ) {
                // The shell field with a prompt waiting: what "ready" looks like.
                TerminalWindow {
                    Image(
                        painter = painterResource(id = R.drawable.distro_ubuntu),
                        contentDescription = stringResource(R.string.onboarding_done_icon_desc),
                        modifier = Modifier.size(FluxSpacing.xxl + FluxSpacing.sm)
                    )
                    Spacer(Modifier.height(FluxSpacing.xl))
                    Row(
                        modifier = Modifier.clearAndSetSemantics {},
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = EXCERPT_PROMPT,
                            style = CodeStyle,
                            color = inks.onChrome,
                            maxLines = 1,
                            softWrap = false
                        )
                        ExcerptCursor()
                    }
                }

                Spacer(Modifier.height(FluxSpacing.xl))

                // Success by form and ink: the filled running mark, then the words.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(state = MarkState.RUNNING, size = FluxSpacing.lg + FluxSpacing.xs)
                    Spacer(Modifier.width(FluxSpacing.md))
                    Text(
                        text = stringResource(R.string.onboarding_done_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.onBackground
                    )
                }
                Spacer(Modifier.height(FluxSpacing.sm))
                Text(
                    text = stringResource(
                        if (onDesktop != null) R.string.onboarding_done_body_desktop
                        else R.string.onboarding_done_body
                    ),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(FluxSpacing.md))
                Text(
                    text = stringResource(
                        if (runtime == UbuntuRuntimeMode.CHROOT) R.string.onboarding_done_chip_chroot
                        else R.string.onboarding_done_chip
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.secondary
                )
            }
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        // Primary action: the terminal, the app's home.
        AkaButton(
            text = stringResource(R.string.onboarding_done_open_terminal),
            icon = Icons.Sharp.Terminal,
            onClick = onTerminal,
            modifier = Modifier.fillMaxWidth()
        )

        if (onDesktop != null) {
            Spacer(Modifier.height(FluxSpacing.md))
            AkaButton(
                text = stringResource(R.string.onboarding_done_start_desktop),
                icon = Icons.Sharp.DesktopWindows,
                onClick = onDesktop,
                kind = AkaButtonKind.SECONDARY,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Storage access (shown only while shared storage access is supported but not granted)
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

    StepPage(
        currentStep = 4,
        title = stringResource(R.string.onboarding_storage_title),
        onBack = onBack,
        actions = {
            StorageActions(granted = granted, onGrant = onGrant, onContinue = onContinue)
        }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Sharp.FolderShared,
                contentDescription = stringResource(R.string.onboarding_storage_icon_desc),
                tint = colors.secondary,
                modifier = Modifier.size(FluxSpacing.xl)
            )
            Spacer(Modifier.width(FluxSpacing.md))
            Text(
                text = stringResource(R.string.onboarding_storage_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        // The ~/storage links Ubuntu gets: one group, one tonal block.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(colors.surfaceContainerLow)
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FluxSpacing.md)
        ) {
            StorageLinkRows.forEach { (name, descRes) ->
                StorageLinkRow(path = "~/storage/$name", description = stringResource(descRes))
            }
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        // The other direction: the phone's file manager sees Ubuntu's home.
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Sharp.FolderOpen,
                contentDescription = null,
                tint = colors.secondary,
                modifier = Modifier.size(FluxSpacing.xl)
            )
            Spacer(Modifier.width(FluxSpacing.md))
            Text(
                text = stringResource(R.string.onboarding_storage_file_manager),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        if (granted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusMark(state = MarkState.RUNNING, size = FluxSpacing.md)
                Spacer(Modifier.width(FluxSpacing.sm))
                Text(
                    stringResource(R.string.onboarding_storage_granted),
                    style = MaterialTheme.typography.titleSmall,
                    color = markColor(MarkState.RUNNING)
                )
            }
        } else {
            if (opensSettings) {
                Text(
                    text = stringResource(R.string.onboarding_storage_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(FluxSpacing.xs))
            }
            Text(
                text = stringResource(R.string.onboarding_storage_later),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/** Granted: Continue. Not yet: Grant access as the primary, Skip for now as a secondary button. */
@Composable
private fun StorageActions(granted: Boolean, onGrant: () -> Unit, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
    ) {
        if (granted) {
            AkaButton(
                text = stringResource(R.string.onboarding_continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            AkaButton(
                text = stringResource(R.string.onboarding_storage_grant),
                icon = Icons.Sharp.LockOpen,
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            )
            AkaButton(
                text = stringResource(R.string.onboarding_storage_skip),
                onClick = onContinue,
                kind = AkaButtonKind.SECONDARY,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StorageLinkRow(path: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = path, style = CodeStyle, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared onboarding parts
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A numbered setup step: header, scrolling body, bottom-anchored [actions] (Back as a text
 * button, the step's one primary action as an akane button; see [StepActionsRow]).
 */
@Composable
private fun StepPage(
    currentStep: Int,
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FluxSpacing.xl, vertical = FluxSpacing.lg)
    ) {
        StepHeader(currentStep = currentStep, totalSteps = ONBOARD_STEP_COUNT, title = title, onBack = onBack)
        Spacer(Modifier.height(FluxSpacing.xl))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            content()
            Spacer(Modifier.height(FluxSpacing.lg))
        }
        Spacer(Modifier.height(FluxSpacing.lg))
        actions()
    }
}

/** Back as a secondary button at the start, the step's primary button filling the rest. */
@Composable
private fun StepActionsRow(onBack: () -> Unit, primary: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AkaButton(
            text = stringResource(R.string.aka_onb_back),
            onClick = onBack,
            kind = AkaButtonKind.SECONDARY,
            contentPadding = PaddingValues(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
        )
        primary()
    }
}

@Composable
private fun StepHeader(
    currentStep: Int,
    totalSteps: Int,
    title: String,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The icon's glyph lines up with the page edge; the target stays 48dp.
            IconButton(onClick = onBack, modifier = Modifier.offset(x = -FluxSpacing.md)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Sharp.ArrowBack,
                    contentDescription = stringResource(R.string.aka_onb_back),
                    tint = colors.onBackground
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.aka_onb_step, currentStep, totalSteps),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(FluxSpacing.xs))

        // The step track: one segment per step, the words above say the same.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
        ) {
            repeat(totalSteps) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(FluxSpacing.xs)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(if (i < currentStep) colors.secondary else colors.surfaceContainerHighest)
                )
            }
        }

        Spacer(Modifier.height(FluxSpacing.lg))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground
        )
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

/** A terminal window: the terminal's own black in a kachi frame, with the horizon under it. */
@Composable
private fun TerminalWindow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inks = AkaTheme.inks
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(TerminalBackground)
            .border(1.dp, inks.tileEdge, MaterialTheme.shapes.large)
            .horizon(inks.horizon, inks.horizonGlow, inset = FluxSpacing.lg)
            .padding(FluxSpacing.lg),
        content = content
    )
}

/**
 * A selectable option, always in its tile. Selected: konjō with the ai edge and its horizon
 * risen; unselected: kon with the hanada edge.
 * [content] gets the matching muted ink for supporting text.
 */
@Composable
private fun OptionTile(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (muted: Color) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = selectionInks()
    val colors = MaterialTheme.colorScheme
    val contentColor = if (selected) ink.onFill else colors.onSurface
    val muted = if (selected) ink.onFill.copy(alpha = 0.82f) else colors.onSurfaceVariant
    Box(
        modifier = modifier
            .akaSelection(selected, pressed, shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .heightIn(min = FluxSpacing.touch)
            .padding(FluxSpacing.md)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content(muted) }
    }
}

/** The radio inside a [OptionTile]: decorative (the tile carries the semantics). */
@Composable
private fun OptionRadio(selected: Boolean) {
    RadioButton(
        selected = selected,
        onClick = null,
        colors = RadioButtonDefaults.colors(
            selectedColor = LocalContentColor.current,
            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun FluxConsentCheckboxCard(
    consented: Boolean,
    onConsentChange: (Boolean) -> Unit
) {
    val body = stringResource(R.string.onboarding_consent_body)
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .akaTile(MaterialTheme.shapes.medium, selected = consented)
            .toggleable(value = consented, role = Role.Checkbox, onValueChange = onConsentChange)
            .padding(start = FluxSpacing.xs, top = FluxSpacing.xs, end = FluxSpacing.lg, bottom = FluxSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = consented,
            onCheckedChange = null,
            modifier = Modifier.size(FluxSpacing.touch),
            colors = CheckboxDefaults.colors(
                checkedColor = colors.primary,
                checkmarkColor = colors.onPrimary,
                uncheckedColor = colors.onSurfaceVariant
            )
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(top = FluxSpacing.md)
        )
    }
}

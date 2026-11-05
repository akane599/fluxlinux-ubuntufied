package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.desktop.UbuntuDesktop
import com.ivarna.fluxlinux.core.files.UbuntuFiles
import com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller
import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.GuestUsers
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.service.BaseInstallService
import com.ivarna.fluxlinux.core.system.GuestStorageLinks
import com.ivarna.fluxlinux.core.terminal.GpuAccelDetector
import com.ivarna.fluxlinux.core.terminal.GuestUserMigration
import com.ivarna.fluxlinux.core.terminal.HostCommandBuilder
import com.ivarna.fluxlinux.core.terminal.ShellCommandRunner
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import com.ivarna.fluxlinux.core.utils.StateManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the simplified onboarding / base install with phase progress + live log.
 * Proot: prepareHost → download → flux_install (extract + family) → customization via
 * proot-distro → hw-accel.
 * Chroot: root check → prepareHost → download → extract (setup_*_chroot) → family →
 * customization in chroot → hw-accel.
 *
 * [InstallKind] picks what runs: [InstallSteps.of] turns the effective kind into the flags
 * this runner branches on, and [BaseDesktopInstallPlan.phasesFor] gives the phases.
 * - TERMINAL: family with `FLUX_INSTALL_DESKTOP=0`, customization with
 *   `FLUX_CUSTOMIZE_DESKTOP=0`. No XFCE assets, no startxfce4 check, no hw-accel, and no
 *   desktop component flags.
 * - TERMINAL_AND_DESKTOP: the full install (XFCE, theme assets, customization, hw-accel).
 * - DESKTOP_ADD_ON: needs the rootfs installed already and never downloads or extracts one;
 *   it re-runs family setup and customization with the desktop on (PRoot via flux_install.sh,
 *   which skips extraction of a completely extracted rootfs).
 * Oh My Zsh is staged on the host first on PRoot only (a guest clone under proot can hang).
 *
 * Distro identity from [DistroInstallProfile].
 *
 * Cancel: generation token + destroy active process; never mutates StateManager after cancel.
 * Overlapping [start] cancels the previous run first.
 * Owned process-wide by [InstallController] (H2) — never by a composable.
 */
class OnboardingInstallRunner(private val ctx: Context) : InstallEngine {

    data class Progress(
        val phaseId: String,
        val phaseLabel: String,
        val phaseIndex: Int,
        val phaseCount: Int,
        val overallPercent: Int,
        val detail: String,
        val logLine: String? = null,
        val failed: Boolean = false,
        val finished: Boolean = false,
        val errorMessage: String? = null,
        /**
         * Only on the finished, successful update: the install is usable but its customization
         * failed or was partial ([customizationWarningRes]); null when it all ran.
         */
        val warning: String? = null,
    )

    private val appCtx = ctx.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val activeProcess = AtomicReference<Process?>(null)
    private val busy = AtomicBoolean(false)
    /** Throttle FGS notification updates (percent/label change only). */
    @Volatile private var lastNotifPercent: Int = -1
    @Volatile private var lastNotifLabel: String = ""
    /** Last overall percent from real phase updates (log lines must not thrash). */
    @Volatile private var lastOverallPercent: Int = 0
    /** FGS text when a progress update has no detail: what this run installs. */
    @Volatile private var notifFallbackText: String = ""

    override fun cancel() {
        cancelled.set(true)
        // Kills the job's whole process group (ShellCommandRunner / GroupKillingProcess).
        activeProcess.getAndSet(null)?.destroyForcibly()
        // Keep the FGS until the job's finally has run (it stops it); only drop it
        // here when nothing is running (also safe if the FGS was never started).
        if (!busy.get()) BaseInstallService.stop(appCtx)
    }

    fun isBusy(): Boolean = busy.get()

    /**
     * Start (or restart) install. If a previous job is still running, it is cancelled
     * and the new job is queued on the single-thread executor.
     *
     * Starts [BaseInstallService] FGS immediately so the system does not kill the
     * process during long rootfs / package work.
     */
    override fun start(
        distroId: String,
        theme: String,
        kind: InstallKind,
        onProgress: (Progress) -> Unit
    ) {
        // Invalidate any in-flight job before resetting cancel flag
        val gen = generation.incrementAndGet()
        // Every callback of this run is dropped once a newer run starts, so a stale
        // job's "Install cancelled" can never overwrite (or Retry-cancel) the new run.
        val guarded = guardGeneration(generation, gen) { p: Progress ->
            onProgress(p)
            updateInstallNotification(p)
        }
        cancelled.set(true)
        activeProcess.getAndSet(null)?.destroyForcibly()
        cancelled.set(false)
        lastOverallPercent = 0
        lastNotifPercent = -1
        lastNotifLabel = ""

        val profile = DistroInstallProfile.forId(distroId)
        if (profile == null) {
            busy.set(false)
            main.post {
                guarded(
                    Progress(
                        phaseId = "ERR",
                        phaseLabel = "Failed",
                        phaseIndex = 0,
                        phaseCount = 1,
                        overallPercent = 0,
                        detail = "Unsupported distro: $distroId",
                        failed = true,
                        finished = true,
                        errorMessage = "Unsupported distro: $distroId"
                    )
                )
            }
            return
        }
        val runKind = InstallKind.effective(distroId, kind)
        val steps = InstallSteps.of(profile.method, runKind)
        val phases = BaseDesktopInstallPlan.phasesFor(profile.method, profile.displayName, runKind)
        notifFallbackText = notificationText(runKind, profile.displayName)
        busy.set(true)
        // FGS first — before any long work — so backgrounding mid-install is safe
        try {
            BaseInstallService.start(
                appCtx,
                title = appCtx.getString(R.string.install_notif_title),
                text = notifFallbackText,
                percent = 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "BaseInstallService.start failed", e)
        }
        executor.execute {
            try {
                if (isStale(gen)) {
                    postCancelled(guarded, phases)
                    return@execute
                }
                if (steps.chroot) {
                    runChroot(profile, theme, steps, phases, guarded, gen)
                } else {
                    runProot(profile, theme, steps, phases, guarded, gen)
                }
            } catch (e: Exception) {
                if (!isStale(gen)) {
                    Log.e(TAG, "install failed", e)
                    postFail(guarded, phases, e.message ?: "Unknown error")
                } else {
                    postCancelled(guarded, phases)
                }
            } finally {
                if (generation.get() == gen) {
                    busy.set(false)
                    BaseInstallService.stop(appCtx)
                }
            }
        }
    }

    private fun isStale(gen: Int): Boolean =
        cancelled.get() || generation.get() != gen

    /** Phase index by [Phase.id] — never a hardcoded numeric index (D10). */
    private fun phaseIdx(phases: List<BaseDesktopInstallPlan.Phase>, id: String): Int =
        phases.indexOfFirst { it.id == id }.also { i ->
            require(i >= 0) { "install plan missing phase '$id'" }
        }

    private fun abortIfCancelled(
        gen: Int,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit
    ): Boolean {
        if (!isStale(gen)) return false
        postCancelled(onProgress, phases)
        return true
    }

    private fun runProot(
        profile: DistroInstallProfile,
        theme: String,
        steps: InstallSteps,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        val distroId = profile.distroId
        val familyIdx = phaseIdx(phases, steps.familyPhaseId)
        val customIdx = phaseIdx(phases, "CUSTOM")

        if (!prepareHostPhase(phases, onProgress, gen)) return

        if (steps.needsInstalledRootfs) {
            // The add-on never downloads or extracts a rootfs: it needs an installed one.
            if (!TerminalLauncher.isProotInstalled(appCtx, profile.prootName)) {
                postFail(
                    onProgress, phases,
                    appCtx.getString(R.string.install_addon_needs_rootfs, profile.displayName),
                )
                return
            }
        }
        if (steps.downloadRootfs && !downloadRootfs(profile, phases, onProgress, gen)) return

        enter(
            phases, familyIdx, onProgress,
            if (steps.desktopOnly) "Installing XFCE via proot-distro…" else "Installing ${profile.displayName} via proot-distro…",
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        // Re-running setup over an Ubuntu PRoot created as flux: rename it to akane first, or
        // the family setup (FLUX_GUEST_USER=akane) would add a second user next to flux.
        GuestUserMigration.migrateIfNeeded(appCtx, distroId)
        val bash = TermuxHostPaths.libBash(appCtx).absolutePath
        val installScript = TermuxHostPaths.hostScript(appCtx, "flux_install.sh").absolutePath
        // flux_install.sh extracts only a missing or interrupted rootfs (a completely extracted
        // one is never re-extracted), then runs the family setup payload.
        val setupB64 = BaseDesktopInstallPlan.familySetupB64(appCtx, theme, distroId, installDesktop = steps.installDesktop)
        val env = HostCommandBuilder.envMap(appCtx, forceHostSetup = false, includeTerm = false).apply {
            put("PYTHONUNBUFFERED", "1")
            put("FLUX_ROOTFS_PATH", "${TermuxHostPaths.HOME}/${profile.rootfsFileName}")
            put("FLUX_ROOTFS_NAME", profile.rootfsFileName)
            put("FLUX_ROOTFS_SHA256", profile.rootfsSha256)
            put("FLUX_ROOTFS_URL", profile.rootfsUrl)
        }
        beforeFamilyStep(appCtx, distroId, steps)
        // Never exec $PREFIX/bin/* as argv0 — W^X (targetSdk 36) only allows
        // nativeLibraryDir (libbash.so / libproot.so). stdbuf lives under PREFIX
        // and fails with EACCES (error 13). Stream lines as the pipe delivers them.
        val (exitInstall, _) = ShellCommandRunner.runCaptureExit(
            appCtx,
            arrayOf(bash, installScript, profile.prootName, setupB64),
            env,
            processHolder = activeProcess,
            onLine = lineSink(phases, familyIdx, onProgress, gen),
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (exitInstall != 0) {
            postFail(
                onProgress, phases,
                if (steps.desktopOnly) "XFCE setup failed (exit $exitInstall)"
                else "${profile.displayName} install failed (exit $exitInstall)",
            )
            return
        }
        if (!TerminalLauncher.isProotInstalled(appCtx, profile.prootName)) {
            postFail(onProgress, phases, "${profile.displayName} rootfs missing after install")
            return
        }
        if (steps.requireStartxfce4 && !isProotXfceInstalled(appCtx, profile.prootName)) {
            postFail(
                onProgress, phases,
                "XFCE not found after install (startxfce4 missing). Retry setup."
            )
            return
        }
        completePhase(
            phases, familyIdx, onProgress,
            when {
                steps.desktopOnly -> "XFCE installed"
                steps.installDesktop -> "${profile.displayName} + XFCE installed"
                else -> "${profile.displayName} installed"
            },
        )

        enter(phases, customIdx, onProgress, customizationDetail(steps))
        if (abortIfCancelled(gen, phases, onProgress)) return
        val hostThemeOk = steps.stageXfceAssets && try {
            // Native host extract into proot rootfs (avoids proot thrashing on Papirus).
            // Skip guest re-extract when this succeeds or assets are already present.
            log(phases, customIdx, onProgress, "Staging XFCE theme/icons on host (native tar)…")
            ProotXfceAssetInstaller.install(
                appCtx, theme, profile.prootName, lineSink(phases, customIdx, onProgress, gen),
            )
        } catch (e: Exception) {
            log(phases, customIdx, onProgress, "Host theme stage error: ${e.message}")
            false
        }
        if (abortIfCancelled(gen, phases, onProgress)) return
        val hostOmzOk = steps.stageHostOhMyZsh && try {
            log(phases, customIdx, onProgress, "Staging Oh My Zsh on host (avoids proot hang)…")
            ProotZshBootstrap.install(appCtx, profile.prootName, lineSink(phases, customIdx, onProgress, gen))
        } catch (e: Exception) {
            log(phases, customIdx, onProgress, "Host Oh My Zsh stage error: ${e.message}")
            false
        }
        if (abortIfCancelled(gen, phases, onProgress)) return
        val customExit = runProotGuestScript(
            phases, customIdx, onProgress, gen,
            prootName = profile.prootName,
            scriptAssetPath = profile.customizationScript,
            envPrefix = prootCustomizationEnv(
                theme = theme,
                customizeDesktop = steps.customizeDesktop,
                skipThemeIcons = hostThemeOk,
                skipOmz = hostOmzOk,
                guestUserEnv = prootGuestUserEnv(profile),
            ),
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val customWarning = customizationOutcome(profile, steps, phases, customIdx, onProgress, customExit)

        if (abortIfCancelled(gen, phases, onProgress)) return
        val hwOk = steps.runHwAccel && runHwAccelIfPresent(
            profile, phases, customIdx, onProgress, gen, chrootPath = null
        )

        completePhase(phases, customIdx, onProgress, "Customization done")

        if (abortIfCancelled(gen, phases, onProgress)) return
        // Termux-style ~/storage links exist right after setup.
        runCatching { GuestStorageLinks.ensure(appCtx, distroId) }
        // The Ubuntu file-manager roots appear once the rootfs exists; make DocumentsUI re-query.
        runCatching { UbuntuFiles.notifyRootsChanged(appCtx) }
        StateManager.setDistroInstalled(appCtx, distroId, true)
        recordDesktopComponents(appCtx, distroId, steps, customOk = customExit == 0, hwOk = hwOk)
        // Persist here, not from Done-page buttons: Back, rotation or process death
        // after a successful install must not re-run onboarding (M4).
        StateManager.setOnboardingComplete(appCtx, true)
        StateManager.triggerRefresh()
        postSuccess(onProgress, phases, customWarning)
    }

    private fun runChroot(
        profile: DistroInstallProfile,
        theme: String,
        steps: InstallSteps,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        val distroId = profile.distroId
        val familyIdx = phaseIdx(phases, steps.familyPhaseId)
        val customIdx = phaseIdx(phases, "CUSTOM")

        if (steps.checkRoot) {
            val r0Idx = phaseIdx(phases, "R0")
            enter(phases, r0Idx, onProgress, "Probing KernelSU / Magisk…")
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (!RootShell.isRootAvailable()) {
                postFail(
                    onProgress, phases,
                    "Root not available. Grant superuser to AkaTerminal, then retry."
                )
                return
            }
            completePhase(phases, r0Idx, onProgress, "Root OK")
        }
        RootShell.ensureBusyBoxResolver(appCtx)
        val resolvedBb = RootShell.resolveBusyBox()

        if (!prepareHostPhase(phases, onProgress, gen)) return

        val chrootPath = profile.chrootPath ?: ChrootPaths.CHROOT_PATH

        if (steps.needsInstalledRootfs) {
            // The add-on never downloads or extracts a rootfs: it needs an installed one, which
            // only a fresh root probe can confirm (the app can't read /data/local/tmp).
            ChrootDetection.invalidate(chrootPath)
            val refusal = chrootAddOnRefusal(ChrootDetection.probe(forceRoot = true, path = chrootPath))
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (refusal != null) {
                postFail(onProgress, phases, appCtx.getString(refusal, profile.displayName))
                return
            }
        }
        if (steps.downloadRootfs && !downloadRootfs(profile, phases, onProgress, gen)) return
        if (steps.extractRootfs && !extractChrootRootfs(profile, chrootPath, resolvedBb, phases, onProgress, gen)) {
            return
        }

        enter(
            phases, familyIdx, onProgress,
            if (steps.installDesktop) "Installing XFCE packages…" else "Installing base packages…",
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val familyPayload =
            BaseDesktopInstallPlan.familySetupPayload(appCtx, theme, distroId, installDesktop = steps.installDesktop)
        beforeFamilyStep(appCtx, distroId, steps)
        val familyExit = runChrootGuestBlocking(familyPayload, chrootPath, phases, familyIdx, onProgress, gen)
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (familyExit != 0) {
            postFail(
                onProgress, phases,
                if (steps.installDesktop) "XFCE setup failed (exit $familyExit)"
                else "Base setup failed (exit $familyExit)",
            )
            return
        }
        if (steps.requireStartxfce4 && !TerminalLauncher.isChrootXfceInstalled(chrootPath)) {
            postFail(
                onProgress, phases,
                "XFCE not found after family setup (startxfce4 missing). Retry."
            )
            return
        }
        completePhase(
            phases, familyIdx, onProgress,
            if (steps.installDesktop) "XFCE installed" else "Base packages installed",
        )

        enter(phases, customIdx, onProgress, customizationDetail(steps))
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (steps.stageXfceAssets) {
            // Stage theme/icon/cursor/wallpaper into the chroot guest /tmp (root copy).
            // The customization script extracts from /tmp/flux_xfce_assets with guest
            // tar; icons have no download fallback, so this staging is mandatory.
            log(phases, customIdx, onProgress, "Staging XFCE theme/icons into chroot…")
            try {
                ProotXfceAssetInstaller.installToChroot(
                    appCtx, theme, chrootPath, lineSink(phases, customIdx, onProgress, gen),
                )
            } catch (e: Exception) {
                log(phases, customIdx, onProgress, "chroot theme stage error: ${e.message}")
            }
        }
        val customPayload = BaseDesktopInstallPlan.customizationPayload(
            appCtx, theme, distroId, customizeDesktop = steps.customizeDesktop,
        )
        val customExit = runChrootGuestBlocking(customPayload, chrootPath, phases, customIdx, onProgress, gen)
        if (abortIfCancelled(gen, phases, onProgress)) return
        val customWarning = customizationOutcome(profile, steps, phases, customIdx, onProgress, customExit)

        if (abortIfCancelled(gen, phases, onProgress)) return
        val hwOk = steps.runHwAccel && runHwAccelIfPresent(
            profile, phases, customIdx, onProgress, gen, chrootPath = chrootPath
        )

        completePhase(phases, customIdx, onProgress, "Customization done")

        if (abortIfCancelled(gen, phases, onProgress)) return
        // Termux-style ~/storage links for the guest user and root (root, in the chroot).
        runCatching { GuestStorageLinks.ensure(appCtx, distroId) }.getOrNull()?.let {
            log(phases, customIdx, onProgress, "~/storage links: ${it.created} created, ${it.existing} present")
        }
        // The Ubuntu file-manager roots appear once the rootfs exists; make DocumentsUI re-query.
        runCatching { UbuntuFiles.notifyRootsChanged(appCtx) }
        StateManager.setDistroInstalled(appCtx, distroId, true)
        recordDesktopComponents(appCtx, distroId, steps, customOk = customExit == 0, hwOk = hwOk)
        // Persist here, not from Done-page buttons: Back, rotation or process death
        // after a successful install must not re-run onboarding (M4).
        StateManager.setOnboardingComplete(appCtx, true)
        StateManager.triggerRefresh()
        postSuccess(onProgress, phases, customWarning)
    }

    /**
     * HOST phase (both runtimes): extract the bootstrap and deploy the host scripts.
     * False after posting the failure (or the cancel).
     */
    private fun prepareHostPhase(
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int,
    ): Boolean {
        val hostIdx = phaseIdx(phases, "HOST")
        enter(phases, hostIdx, onProgress, "Extracting bootstrap + deploying scripts…")
        if (abortIfCancelled(gen, phases, onProgress)) return false
        var lastHostPhase = ""
        val hostOk = TerminalLauncher.prepareHostBlocking(appCtx, forceHostSetup = false) { done, total, phase ->
            if (isStale(gen)) return@prepareHostBlocking
            val frac = if (total > 0) done.toFloat() / total else 0f
            updateFraction(phases, hostIdx, frac, onProgress, phase)
            if (phase.isNotBlank() && phase != lastHostPhase) {
                lastHostPhase = phase
                log(phases, hostIdx, onProgress, phase)
            }
        }
        if (abortIfCancelled(gen, phases, onProgress)) return false
        if (!hostOk) {
            postFail(onProgress, phases, "Host bootstrap failed")
            return false
        }
        completePhase(phases, hostIdx, onProgress, "Host ready")
        return true
    }

    /**
     * DL phase (both runtimes): the pinned rootfs archive into the app home, from a local copy
     * or the network. On chroot it runs after R0 and HOST, so a device without root fails
     * before any download (R6). False after posting the failure (or the cancel).
     */
    private fun downloadRootfs(
        profile: DistroInstallProfile,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int,
    ): Boolean {
        val dlIdx = phaseIdx(phases, "DL")
        enter(phases, dlIdx, onProgress, "Downloading ${profile.displayName} rootfs…")
        if (abortIfCancelled(gen, phases, onProgress)) return false
        val dlOk = RootfsDownloader.ensurePresent(
            TermuxHostPaths.homeDir(appCtx), profile, RootfsDownloader.defaultClient,
            isCancelled = { isStale(gen) }
        ) { p ->
            if (isStale(gen)) return@ensurePresent
            val frac = if (p.totalBytes > 0) p.downloadedBytes.toFloat() / p.totalBytes else 0f
            updateFraction(
                phases, dlIdx, frac, onProgress,
                "Downloaded ${p.downloadedBytes / 1_048_576} / " +
                    "${p.totalBytes.coerceAtLeast(0) / 1_048_576} MiB"
            )
        }
        if (abortIfCancelled(gen, phases, onProgress)) return false
        if (!dlOk) {
            postFail(
                onProgress, phases,
                "Rootfs download failed — place ${profile.rootfsFileName} in the app " +
                    "home directory (${TermuxHostPaths.HOME}) or retry online"
            )
            return false
        }
        completePhase(phases, dlIdx, onProgress, "Rootfs ready (${profile.rootfsFileName})")
        return true
    }

    /**
     * ROOTFS phase of a chroot install: extract the downloaded archive with the verified setup
     * script as root, then re-probe. False after posting the failure (or the cancel).
     */
    private fun extractChrootRootfs(
        profile: DistroInstallProfile,
        chrootPath: String,
        resolvedBb: String?,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int,
    ): Boolean {
        val rootfsIdx = phaseIdx(phases, "ROOTFS")
        val setupAsset = profile.chrootSetupAsset
            ?: "scripts/chroot/setup_debian13_chroot.sh"
        val setupName = File(setupAsset).name

        enter(phases, rootfsIdx, onProgress, "Extracting chroot rootfs (may take several minutes)…")
        if (abortIfCancelled(gen, phases, onProgress)) return false
        // M14: root runs the setup script only from the root-only verified stage, next to
        // its verified resolve_bb.sh sidecar (never app-writable filesDir/staged_scripts).
        val stagedSetup = ChrootSetupCommand.stagedPath(setupName)
        if (!RootShell.ensureBusyBoxResolver(appCtx) ||
            !RootShell.stageVerified(appCtx, setupAsset, stagedSetup)
        ) {
            postFail(onProgress, phases, "Could not stage $setupName as root (verify failed)")
            return false
        }
        // Do not wrap with $PREFIX/bin/stdbuf — host W^X denies exec from app data.
        val rootCmd = ChrootSetupCommand.build(
            profile = profile,
            scriptName = setupName,
            scriptSha256 = assetSha(setupAsset),
            resolverSha256 = assetSha(BusyBoxPaths.RESOLVER_ASSET),
            resolvedBb = resolvedBb,
            chrootPath = chrootPath,
            rootfsHome = TermuxHostPaths.HOME,
            packageName = TermuxHostPaths.PACKAGE,
        )
        val rootResult = RootShell.captureResult(
            rootCmd,
            timeoutMs = 0L,
            onLine = lineSink(phases, rootfsIdx, onProgress, gen),
            processHolder = activeProcess
        )
        if (abortIfCancelled(gen, phases, onProgress)) return false
        if (rootResult.exitCode != 0) {
            postFail(onProgress, phases, "Chroot install failed (exit ${rootResult.exitCode})")
            return false
        }
        // App SELinux cannot see /data/local/tmp — always re-probe as root after install.
        TerminalLauncher.invalidateChrootInstalledCache()
        val installed = TerminalLauncher.isChrootInstalled(chrootPath)
        if (!installed) {
            log(phases, rootfsIdx, onProgress, "Root probe: chroot missing at $chrootPath")
            postFail(
                onProgress, phases,
                "Chroot rootfs missing after install (check root grant + $chrootPath)"
            )
            return false
        }
        log(phases, rootfsIdx, onProgress, "Chroot rootfs verified (root probe)")
        completePhase(phases, rootfsIdx, onProgress, "Chroot rootfs ready")
        return true
    }

    /**
     * Run the guest hw-accel installer with host-detected FLUX_GPU ([chrootPath] null = PRoot).
     * Failure is logged and does not fail onboarding (family first-paint is VirGL).
     */
    private fun runHwAccelIfPresent(
        profile: DistroInstallProfile,
        phases: List<BaseDesktopInstallPlan.Phase>,
        phaseIndex: Int,
        onProgress: (Progress) -> Unit,
        gen: Int,
        chrootPath: String?
    ): Boolean {
        val script = profile.hwAccelScript ?: return false
        val gpu = GpuAccelDetector.detect()
        GpuAccelDetector.persist(appCtx, gpu)
        log(
            phases, phaseIndex, onProgress,
            "Hardware acceleration: mode=${gpu.mode} vendor=${gpu.vendorHint}"
        )
        // Adreno: app picks the stable Turnip release (empty map elsewhere).
        val driverEnv = GpuDriverInstaller.guestEnv(appCtx, profile.distroId)
        (driverEnv["FLUX_TURNIP_MSG"] ?: driverEnv["FLUX_PANVK_MSG"])?.let { log(phases, phaseIndex, onProgress, it) }
        val ok = try {
            if (chrootPath != null) {
                val payload = BaseDesktopInstallPlan.hwAccelPayload(
                    appCtx, profile.distroId, gpu.mode, gpu.vendorHint, driverEnv
                )
                runChrootGuestBlocking(payload, chrootPath, phases, phaseIndex, onProgress, gen) == 0
            } else {
                stageFluxGpuCommon()
                runProotGuestScript(
                    phases, phaseIndex, onProgress, gen,
                    prootName = profile.prootName,
                    scriptAssetPath = script,
                    envPrefix = prootGuestUserEnv(profile).joinToString("") { "$it " } +
                        "FLUX_GPU=${gpu.mode} FLUX_GPU_VENDOR=${gpu.vendorHint}" +
                        driverEnv.entries.joinToString("") {
                            " ${it.key}='${it.value.replace("'", "")}'"
                        }
                ) == 0
            }
        } catch (e: Exception) {
            log(phases, phaseIndex, onProgress, "Hardware acceleration error: ${e.message}")
            false
        }
        if (driverEnv.isNotEmpty()) {
            GpuDriverInstaller.refreshInstalled(
                appCtx, profile.distroId, profile.prootName, chrootPath != null
            )
        }
        if (GpuDriverInstaller.pendingDistro(appCtx) != null) {
            GpuDriverInstaller.onPending?.invoke()
        }
        if (ok) {
            log(phases, phaseIndex, onProgress, "Hardware acceleration installed (${gpu.mode})")
        } else {
            log(
                phases, phaseIndex, onProgress,
                "Hardware acceleration skipped or failed — VirGL first-paint remains"
            )
        }
        return ok
    }

    private fun assetSha(asset: String): String =
        RootShell.sha256Hex(appCtx.assets.open(asset).use { it.readBytes() })

    private fun stageFluxGpuCommon() {
        val tmp = File(TermuxHostPaths.TMPDIR)
        if (!tmp.exists()) tmp.mkdirs()
        val dest = File(tmp, "flux_gpu_common.sh")
        appCtx.assets.open("scripts/common/setup/flux_gpu_common.sh").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest.setExecutable(true)
    }

    /**
     * `FLUX_GUEST_USER='<user>'` words for the Ubuntu PRoot (the user it logs in as: akane, or
     * flux while its passwd still has only flux), else none (scripts default to flux).
     */
    private fun prootGuestUserEnv(profile: DistroInstallProfile): List<String> =
        envWords(
            GuestUsers.scriptEnv(
                profile.distroId, profile.method, BaseDesktopInstallPlan.prootScriptRootfs(appCtx, profile),
            ),
        )

    /** Runs asset [scriptAssetPath] in the PRoot guest: its exit code, -1 if it never ran or went stale. */
    private fun runProotGuestScript(
        phases: List<BaseDesktopInstallPlan.Phase>,
        phaseIndex: Int,
        onProgress: (Progress) -> Unit,
        gen: Int,
        prootName: String,
        scriptAssetPath: String,
        envPrefix: String
    ): Int {
        return try {
            val tmp = File(TermuxHostPaths.TMPDIR)
            if (!tmp.exists()) tmp.mkdirs()
            val name = File(scriptAssetPath).name
            val dest = File(tmp, name)
            appCtx.assets.open("scripts/$scriptAssetPath").use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest.setExecutable(true)
            val bash = TermuxHostPaths.libBash(appCtx).absolutePath
            // Prefer bash if present (Debian); Alpine family installs bash before customization.
            // libbash.so only — never prefix stdbuf (W^X EACCES on $PREFIX/bin)
            val guest =
                "env $envPrefix PYTHONUNBUFFERED=1 " +
                    "sh -c 'if [ -x /bin/bash ]; then exec /bin/bash /tmp/$name; " +
                    "else exec /bin/sh /tmp/$name; fi'"
            val cmd = arrayOf(
                bash, "-c",
                "exec python ${TermuxHostPaths.PROOT_DISTRO} login $prootName --shared-tmp -- $guest"
            )
            val env = HostCommandBuilder.envMap(appCtx, includeTerm = false).apply {
                put("PYTHONUNBUFFERED", "1")
            }
            val (exit, _) = ShellCommandRunner.runCaptureExit(
                appCtx, cmd, env,
                processHolder = activeProcess,
                onLine = lineSink(phases, phaseIndex, onProgress, gen),
            )
            if (isStale(gen)) return -1
            exit
        } catch (e: Exception) {
            if (!isStale(gen)) {
                log(phases, phaseIndex, onProgress, "Guest script error: ${e.message}")
            }
            -1
        }
    }

    /** Runs [scriptBody] as root in the chroot at [chrootPath]; its exit code. */
    private fun runChrootGuestBlocking(
        scriptBody: String,
        chrootPath: String,
        phases: List<BaseDesktopInstallPlan.Phase>,
        phaseIndex: Int,
        onProgress: (Progress) -> Unit,
        gen: Int,
    ): Int {
        // captureInChroot already base64-wraps once via fluxlinux_chroot b64.
        // Nested echo|base64 is redundant and bloated host su -c strings.
        // guest_b64 prefers /bin/bash when present, else /bin/sh (Alpine minirootfs).
        val body = "export PYTHONUNBUFFERED=1\n$scriptBody"
        val result = RootShell.captureInChroot(
            body,
            user = "root",
            context = appCtx,
            chrootPath = chrootPath,
            timeoutMs = 0L,
            // A cancelled/retried run's root job must not leak lines into the new run.
            onLine = lineSink(phases, phaseIndex, onProgress, gen),
            processHolder = activeProcess
        )
        return result.exitCode
    }

    // ── progress helpers ────────────────────────────────────────────────────

    /** Line sink for a job's output: logs non-blank lines to [index] while run [gen] is current. */
    private fun lineSink(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        gen: Int,
    ): (String) -> Unit = { line ->
        if (!isStale(gen) && line.isNotBlank()) log(phases, index, onProgress, line)
    }

    /**
     * The one Progress builder for a phase update. [fraction] null keeps the last overall
     * percent (pure log lines must not snap the bar mid-phase); [detail] null shows the label.
     * No-op for an index outside the plan.
     */
    private fun postPhase(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        fraction: Float?,
        detail: String?,
        logLine: ((BaseDesktopInstallPlan.Phase) -> String)? = null,
    ) {
        val p = phases.getOrNull(index) ?: return
        val pct = if (fraction == null) {
            lastOverallPercent.coerceIn(0, 100)
        } else {
            weightedPercent(phases, index, fraction.coerceIn(0f, 1f)).also { lastOverallPercent = it }
        }
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = pct,
                detail = detail ?: p.label,
                logLine = logLine?.invoke(p),
            )
        )
    }

    private fun enter(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        detail: String
    ) = postPhase(phases, index, onProgress, fraction = 0f, detail = detail) { "── ${it.label}: $detail" }

    private fun completePhase(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        detail: String
    ) = postPhase(phases, index, onProgress, fraction = 1f, detail = detail) { "✓ $detail" }

    private fun updateFraction(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        frac: Float,
        onProgress: (Progress) -> Unit,
        detail: String
    ) = postPhase(phases, index, onProgress, fraction = frac, detail = detail)

    private fun log(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        line: String
    ) = postPhase(phases, index, onProgress, fraction = null, detail = null) { line }

    /**
     * Logs a failed or partial customization (exit [exit] != 0) and returns the warning the
     * success result carries; null when it exited 0. The install itself still succeeds.
     */
    private fun customizationOutcome(
        profile: DistroInstallProfile,
        steps: InstallSteps,
        phases: List<BaseDesktopInstallPlan.Phase>,
        customIdx: Int,
        onProgress: (Progress) -> Unit,
        exit: Int,
    ): String? {
        val res = customizationWarningRes(steps, exit) ?: return null
        log(phases, customIdx, onProgress, customizationFailureLog(exit))
        return appCtx.getString(res, profile.displayName)
    }

    /** [warning]: [customizationOutcome]'s, carried by the finished update ([Progress.warning]). */
    private fun postSuccess(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>,
        warning: String?,
    ) {
        val last = phases.last()
        lastOverallPercent = 100
        post(
            onProgress,
            Progress(
                phaseId = last.id,
                phaseLabel = "Complete",
                phaseIndex = phases.size - 1,
                phaseCount = phases.size,
                overallPercent = 100,
                detail = "Environment ready",
                finished = true,
                warning = warning,
                logLine = warning?.let { "WARNING: $it" },
            )
        )
    }

    private fun postFail(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>,
        message: String
    ) {
        val p = phases.firstOrNull()
        post(
            onProgress,
            Progress(
                phaseId = p?.id ?: "ERR",
                phaseLabel = "Failed",
                phaseIndex = 0,
                phaseCount = phases.size.coerceAtLeast(1),
                overallPercent = 0,
                detail = message,
                failed = true,
                finished = true,
                errorMessage = message,
                logLine = "ERROR: $message"
            )
        )
    }

    private fun postCancelled(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>
    ) {
        postFail(onProgress, phases, "Install cancelled")
    }

    private fun weightedPercent(
        phases: List<BaseDesktopInstallPlan.Phase>,
        currentIndex: Int,
        fractionInPhase: Float
    ): Int {
        val total = phases.sumOf { it.weight }.coerceAtLeast(1)
        var done = 0
        for (i in phases.indices) {
            if (i < currentIndex) done += phases[i].weight
            else if (i == currentIndex) done += (phases[i].weight * fractionInPhase).toInt()
        }
        return ((done * 100) / total).coerceIn(0, 100)
    }

    /** [onProgress] is the generation-guarded sink built in [start]; it also updates the FGS. */
    private fun post(onProgress: (Progress) -> Unit, progress: Progress) {
        main.post { onProgress(progress) }
    }

    private fun updateInstallNotification(progress: Progress) {
        val (label, detail) = notificationContent(
            progress,
            fallbackText = notifFallbackText,
            warningLabel = appCtx.getString(R.string.install_notif_done_warning),
        )
        val percent = progress.overallPercent
        // Avoid spamming NotificationManager on every log line
        if (!progress.finished &&
            !progress.failed &&
            percent == lastNotifPercent &&
            label == lastNotifLabel
        ) {
            return
        }
        lastNotifPercent = percent
        lastNotifLabel = label
        try {
            BaseInstallService.update(
                appCtx,
                title = "AkaTerminal — $label",
                text = detail,
                percent = percent
            )
        } catch (e: Exception) {
            Log.w(TAG, "FGS update failed: ${e.message}")
        }
        // The keep-alive notification goes with the service right after this last update.
        finishedWarning(progress)?.let { warning ->
            BaseInstallService.notifyFinishedWithWarning(appCtx, title = "AkaTerminal — $label", text = warning)
        }
    }

    private fun isProotXfceInstalled(ctx: Context, prootName: String): Boolean =
        UbuntuDesktop.hasStartxfce4(ProotXfceAssetInstaller.prootRootfs(ctx, prootName))

    /** FGS text naming what a [kind] run installs. */
    private fun notificationText(kind: InstallKind, displayName: String): String =
        appCtx.getString(notificationTextRes(kind), displayName)

    companion object {
        private const val TAG = "OnboardingInstall"

        /**
         * FGS title label and text for [progress]. A run that finished with a warning
         * ([Progress.warning]) gets [warningLabel] instead of "Install complete", and the
         * warning as its text; otherwise the text is the detail, or [fallbackText].
         */
        internal fun notificationContent(
            progress: Progress,
            fallbackText: String,
            warningLabel: String,
        ): Pair<String, String> {
            val warning = finishedWarning(progress)
            val label = when {
                progress.failed -> "Install failed"
                warning != null -> warningLabel
                progress.finished -> "Install complete"
                progress.phaseLabel.isNotBlank() -> progress.phaseLabel
                else -> "Installing…"
            }
            return label to (warning ?: progress.detail.ifBlank { fallbackText })
        }

        /**
         * The warning of a run that finished without failing ([Progress.warning]), else null.
         * It also gets its own dismissible notification
         * ([BaseInstallService.notifyFinishedWithWarning]).
         */
        internal fun finishedWarning(progress: Progress): String? =
            progress.warning?.takeIf { progress.finished && !progress.failed }

        /** String resource (one `%1$s` = distro display name) of the FGS text for [kind]. */
        internal fun notificationTextRes(kind: InstallKind): Int = when (kind) {
            InstallKind.TERMINAL -> R.string.install_notif_terminal
            InstallKind.TERMINAL_AND_DESKTOP -> R.string.install_notif_terminal_desktop
            InstallKind.DESKTOP_ADD_ON -> R.string.install_notif_desktop_addon
        }

        /**
         * Right before the family step: a run that installs the desktop marks it incomplete
         * ([UbuntuDesktop.markIncomplete]). Only [recordDesktopComponents] clears that, so a
         * run that fails after dpkg unpacked startxfce4, is cancelled or dies stays incomplete.
         */
        internal fun beforeFamilyStep(ctx: Context, distroId: String, steps: InstallSteps) {
            if (steps.markDesktopIncomplete) UbuntuDesktop.markIncomplete(ctx, distroId)
        }

        /**
         * Desktop component flags after a successful run that installed the desktop
         * ([InstallSteps.markDesktopComponents]): `xfce4_desktop` (verified by the startxfce4
         * check; clears the incomplete marker), plus `customization` and `hw_accel` when they
         * succeeded. A terminal-only run leaves them as they were.
         */
        internal fun recordDesktopComponents(
            ctx: Context,
            distroId: String,
            steps: InstallSteps,
            customOk: Boolean,
            hwOk: Boolean,
        ) {
            if (!steps.markDesktopComponents) return
            UbuntuDesktop.markInstalled(ctx, distroId)
            if (hwOk) {
                StateManager.setComponentInstalled(ctx, distroId, "hw_accel", true)
            }
            if (customOk) {
                StateManager.setComponentInstalled(ctx, distroId, "customization", true)
            }
        }

        /**
         * Why a chroot desktop add-on can't run on [snap] (a `forceRoot` probe), as a string
         * resource with one `%1$s` = distro display name; null when the rootfs is installed.
         * Root that failed, timed out or went away ([ChrootDetection.Snapshot.viaRoot] false)
         * couldn't see the root-only rootfs, so that is never reported as a missing rootfs.
         */
        internal fun chrootAddOnRefusal(snap: ChrootDetection.Snapshot): Int? = when {
            snap.installed -> null
            !snap.viaRoot -> R.string.install_addon_root_unverified
            else -> R.string.install_addon_needs_rootfs
        }

        /** setup_customization_xfce.sh's exit when it finished without Oh My Zsh. */
        internal const val CUSTOMIZATION_PARTIAL_EXIT = 3

        /**
         * Warning (one `%1$s` = distro display name) of a successful run whose customization
         * exited [exit] != 0: the shell setup of a terminal-only run, or the desktop theme and
         * shell setup of a run that installed the desktop. Null when it exited 0.
         */
        internal fun customizationWarningRes(steps: InstallSteps, exit: Int): Int? = when {
            exit == 0 -> null
            steps.installDesktop -> R.string.install_warning_desktop_setup
            else -> R.string.install_warning_shell_setup
        }

        /** Install-log line for a customization exit != 0 ([CUSTOMIZATION_PARTIAL_EXIT]: no Oh My Zsh). */
        internal fun customizationFailureLog(exit: Int): String =
            if (exit == CUSTOMIZATION_PARTIAL_EXIT) {
                "Customization partial: Oh My Zsh is missing (exit $exit)"
            } else {
                "Customization failed (exit $exit)"
            }

        /** CUSTOM phase detail: desktop theming, or the shell setup of a terminal-only run. */
        private fun customizationDetail(steps: InstallSteps): String =
            if (steps.customizeDesktop) "Themes, wallpapers, fonts…" else "zsh, Oh My Zsh, plugins…"

        /** POSIX single-quoted [value]: safe as one word in the host `bash -c` command. */
        internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** `K='V'` words for `env`, in map order. */
        internal fun envWords(env: Map<String, String>): List<String> =
            env.map { (k, v) -> "$k=${shellQuote(v)}" }

        /**
         * `env` words of the PRoot customization run (read by setup_customization_*.sh): every
         * value single-quoted, the guest user words ([guestUserEnv]) before the asset dir.
         */
        internal fun prootCustomizationEnv(
            theme: String,
            customizeDesktop: Boolean,
            skipThemeIcons: Boolean,
            skipOmz: Boolean,
            guestUserEnv: List<String>,
        ): String {
            val flags = envWords(
                linkedMapOf(
                    "FLUX_THEME" to theme,
                    "FLUX_CUSTOMIZE_DESKTOP" to if (customizeDesktop) "1" else "0",
                    "FLUX_SKIP_THEME_ICONS" to if (skipThemeIcons) "1" else "0",
                    "FLUX_SKIP_OMZ" to if (skipOmz) "1" else "0",
                    "FLUX_SKIP_POKEMON" to "0",
                    "FLUX_PROOT" to "1",
                ),
            )
            return (flags + guestUserEnv + envWords(mapOf("FLUX_ASSET_DIR" to "/tmp/flux_xfce_assets")))
                .joinToString(" ")
        }

        /**
         * Wraps [sink] so it only fires while [gen] is still the current [generation].
         * Checked at delivery time (main thread), after any newer [start] bumped it.
         */
        internal fun <T> guardGeneration(
            generation: AtomicInteger,
            gen: Int,
            sink: (T) -> Unit,
        ): (T) -> Unit = { value -> if (generation.get() == gen) sink(value) }
    }
}

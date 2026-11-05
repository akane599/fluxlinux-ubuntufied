package com.ivarna.fluxlinux.core.install

import android.content.Context
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.terminal.UninstallSessionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** The long-running install job behind [InstallController] ([OnboardingInstallRunner] in production). */
interface InstallEngine {
    fun start(
        distroId: String,
        theme: String,
        kind: InstallKind,
        onProgress: (OnboardingInstallRunner.Progress) -> Unit,
    )
    fun cancel()
}

/**
 * Process-scoped owner of the base-desktop install (H2).
 *
 * The runner and its progress used to live in composition, so rotation, a uiMode
 * change, split-screen or Back disposed the screen and cancelled a multi-minute
 * install. Screens now only render [state]; leaving composition never cancels.
 * [cancel] is the explicit user action. One install runs at a time across the
 * onboarding flow, the install wizard and Home's desktop add-on.
 */
class InstallController internal constructor(
    private val engine: InstallEngine,
    /** H2 (reverse): true while a distro uninstall session is open; installs are refused. */
    private val uninstallInProgress: () -> Boolean = { false },
    /** User-visible reason shown when [start] is refused because of [uninstallInProgress]. */
    private val uninstallBusyMessage: () -> String = { "" },
    /** User-visible reason when a desktop add-on is refused because another install runs. */
    private val installBusyMessage: () -> String = { "" },
) {

    enum class Status { Idle, Running, Succeeded, Failed }

    /** What [start] did. A [Refused] start ran nothing; callers toast its [Refused.message]. */
    sealed interface StartResult {
        data object Started : StartResult

        sealed interface Refused : StartResult {
            /** User-visible reason (a string resource in production). */
            val message: String
        }

        /** An uninstall is deleting files the install would use (H2); the state shows it too. */
        data class UninstallRunning(override val message: String) : Refused

        /** A [InstallKind.DESKTOP_ADD_ON] never supersedes a running install; state is untouched. */
        data class InstallRunning(override val message: String) : Refused
    }

    data class State(
        /** Increments per [start]; lets screens key one-shot effects to a run. */
        val runId: Int = 0,
        val distroId: String? = null,
        val theme: String = "dark",
        /**
         * What the run sets up: the requested kind made effective for [distroId]
         * ([InstallKind.effective]: TERMINAL runs as TERMINAL_AND_DESKTOP off Ubuntu).
         */
        val kind: InstallKind = InstallKind.TERMINAL,
        val status: Status = Status.Idle,
        val percent: Int = 0,
        /** Blank until the first progress update; screens show a "Starting" label. */
        val phaseLabel: String = "",
        val detail: String = "",
        val logText: String = "",
        val errorMessage: String? = null,
    ) {
        val isRunning: Boolean get() = status == Status.Running

        /** True when this state belongs to [id] and is not idle (running or finished). */
        fun isActiveFor(id: String): Boolean = distroId == id && status != Status.Idle
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Token of the run whose callbacks are accepted; bumped by [start] and [cancel]. */
    private val acceptedRun = AtomicInteger(0)

    /**
     * Start (or restart) an install of [kind] (terminal only by default; the desktop is
     * opt-in or a later [InstallKind.DESKTOP_ADD_ON]), stored and run as
     * [InstallKind.effective] for [distroId]. Main thread.
     *
     * - A running install is superseded (the engine cancels it), except by a
     *   [InstallKind.DESKTOP_ADD_ON]: that is refused ([StartResult.InstallRunning]) and the
     *   running install and [state] are left alone.
     * - Refused ([StartResult.UninstallRunning], state [Status.Failed] with the reason unless
     *   an install is running) while an uninstall is deleting a container or the proot-distro
     *   tree the install would run in (H2).
     */
    fun start(distroId: String, theme: String, kind: InstallKind = InstallKind.TERMINAL): StartResult {
        val runKind = InstallKind.effective(distroId, kind)
        if (uninstallInProgress()) {
            val message = uninstallBusyMessage()
            if (!_state.value.isRunning) {
                _state.value = State(
                    runId = acceptedRun.incrementAndGet(),
                    distroId = distroId,
                    theme = theme,
                    kind = runKind,
                    status = Status.Failed,
                    errorMessage = message,
                )
            }
            return StartResult.UninstallRunning(message)
        }
        if (runKind == InstallKind.DESKTOP_ADD_ON && _state.value.isRunning) {
            return StartResult.InstallRunning(installBusyMessage())
        }
        val runId = acceptedRun.incrementAndGet()
        _state.value = State(
            runId = runId,
            distroId = distroId,
            theme = theme,
            kind = runKind,
            status = Status.Running,
        )
        engine.start(distroId, theme, runKind) { p -> onProgress(runId, p) }
        return StartResult.Started
    }

    /** Explicit user cancel: stops the job and returns to idle; late callbacks are dropped. */
    fun cancel() {
        val runId = acceptedRun.incrementAndGet()
        engine.cancel()
        _state.value = State(runId = runId)
    }

    /** Forget a finished (succeeded/failed) run once its screen is left. No-op while running. */
    fun clearFinished() {
        _state.update { s ->
            if (s.status == Status.Succeeded || s.status == Status.Failed) State(runId = s.runId) else s
        }
    }

    private fun onProgress(runId: Int, p: OnboardingInstallRunner.Progress) {
        if (acceptedRun.get() != runId) return
        _state.update { s ->
            if (s.runId != runId) return@update s
            s.copy(
                percent = p.overallPercent,
                phaseLabel = p.phaseLabel,
                detail = p.detail,
                logText = p.logLine?.let { (s.logText + it + "\n").takeLast(LOG_LIMIT) } ?: s.logText,
                status = when {
                    p.failed -> Status.Failed
                    p.finished -> Status.Succeeded
                    else -> Status.Running
                },
                errorMessage = if (p.failed) p.errorMessage ?: p.detail else s.errorMessage,
            )
        }
    }

    companion object {
        private const val LOG_LIMIT = 12_000

        /**
         * "Repair host" deletes and re-extracts `usr/` (the proot-distro tree the
         * install runs in). Never while an install runs — it outlives its screen (H2),
         * so Home can be reached mid-install — nor during another extraction (M1).
         */
        fun hostRepairAllowed(install: State, extracting: Boolean): Boolean =
            !install.isRunning && !extracting

        @Volatile private var instance: InstallController? = null

        /** The process-wide controller, backed by an [OnboardingInstallRunner]. */
        fun get(ctx: Context): InstallController =
            instance ?: synchronized(this) {
                instance ?: ctx.applicationContext.let { app ->
                    InstallController(
                        OnboardingInstallRunner(app),
                        uninstallInProgress = { UninstallSessionFactory.isAnyUninstallRunning() },
                        uninstallBusyMessage = { app.getString(R.string.install_blocked_uninstall) },
                        installBusyMessage = { app.getString(R.string.install_blocked_running) },
                    )
                }.also { instance = it }
            }
    }
}

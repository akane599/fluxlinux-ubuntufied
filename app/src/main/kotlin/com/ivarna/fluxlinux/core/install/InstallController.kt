package com.ivarna.fluxlinux.core.install

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** The long-running install job behind [InstallController] ([OnboardingInstallRunner] in production). */
interface InstallEngine {
    fun start(distroId: String, theme: String, onProgress: (OnboardingInstallRunner.Progress) -> Unit)
    fun cancel()
}

/**
 * Process-scoped owner of the base-desktop install (H2).
 *
 * The runner and its progress used to live in composition, so rotation, a uiMode
 * change, split-screen or Back disposed the screen and cancelled a multi-minute
 * install. Screens now only render [state]; leaving composition never cancels.
 * [cancel] is the explicit user action. One install runs at a time across the
 * onboarding flow and the install wizard.
 */
class InstallController internal constructor(
    private val engine: InstallEngine,
    /** H2 (reverse): true while a distro uninstall session is open; installs are refused. */
    private val uninstallInProgress: () -> Boolean = { false },
    /** User-visible reason shown when [start] is refused because of [uninstallInProgress]. */
    private val uninstallBusyMessage: () -> String = { "" },
) {

    enum class Status { Idle, Running, Succeeded, Failed }

    data class State(
        /** Increments per [start]; lets screens key one-shot effects to a run. */
        val runId: Int = 0,
        val distroId: String? = null,
        val theme: String = "dark",
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
     * Start (or restart) an install. A previous run is superseded (the engine cancels it).
     * Refused (false, state [Status.Failed] with the reason) while an uninstall is deleting
     * a container or the proot-distro tree the install would run in (H2).
     */
    fun start(distroId: String, theme: String): Boolean {
        if (uninstallInProgress()) {
            if (!_state.value.isRunning) {
                _state.value = State(
                    runId = acceptedRun.incrementAndGet(),
                    distroId = distroId,
                    theme = theme,
                    status = Status.Failed,
                    errorMessage = uninstallBusyMessage(),
                )
            }
            return false
        }
        val runId = acceptedRun.incrementAndGet()
        _state.value = State(
            runId = runId,
            distroId = distroId,
            theme = theme,
            status = Status.Running,
        )
        engine.start(distroId, theme) { p -> onProgress(runId, p) }
        return true
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
                        uninstallInProgress = {
                            com.ivarna.fluxlinux.core.terminal.UninstallSessionFactory.isAnyUninstallRunning()
                        },
                        uninstallBusyMessage = {
                            app.getString(com.ivarna.fluxlinux.R.string.install_blocked_uninstall)
                        },
                    )
                }.also { instance = it }
            }
    }
}

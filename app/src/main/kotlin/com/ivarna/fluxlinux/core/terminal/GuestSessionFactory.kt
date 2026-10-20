package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.data.terminalComponentFor
import com.ivarna.fluxlinux.core.system.GuestStorageLinks
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import com.termux.terminal.TerminalSession

/**
 * Builds interactive guest sessions (shell / shell-root / component payloads).
 * Method is ALWAYS passed explicitly by card paths (plan §2.6) — no ambient
 * `LinuxCommandBuilder.currentMethod` for product actions.
 */
object GuestSessionFactory {

    /**
     * Create + open an interactive guest session.
     * [shellCmd] default `"exec zsh"` (the interactive sentinel) or blank →
     * interactive login; the actual guest binary comes from
     * [TerminalPreferences.getGuestLoginShell] (zsh default, bash opt-in).
     * Anything else is a guest payload.
     */
    fun openSession(
        ctx: Context,
        type: String,
        title: String = type,
        shellCmd: String = "exec zsh",
        method: String,
        distroId: String? = null
    ): Boolean = prepareSession(ctx, type, title, shellCmd, method, distroId)?.invoke() ?: false

    /**
     * Background half of [openSession]: repairs + argv/env build (chroot may block on su).
     * Returns the opener, which MUST run on the main thread (TerminalSession needs its Looper);
     * null when no tab is free.
     */
    fun prepareSession(
        ctx: Context,
        type: String,
        title: String = type,
        shellCmd: String = "exec zsh",
        method: String,
        distroId: String? = null
    ): (() -> Boolean)? {
        if (!SessionRegistry.hasFreeTab()) return null
        // Patch guest .zshrc if it still hard-sources missing oh-my-zsh / pokemon,
        // or create a missing Flux profile (Alpine installs that never wrote one).
        GuestZshrcRepair.repairIfNeeded(ctx, method, distroId)
        // Alpine proot: ensure apk db/lock is app-writable so `sudo apk` works.
        GuestApkDbRepair.repairIfNeeded(ctx, method, distroId)
        // Proot: Termux-style ~/storage links (same distro fallback as LinuxCommandBuilder).
        if (method != "chroot") {
            runCatching { GuestStorageLinks.ensure(ctx, distroId ?: "debian") }
            // Ubuntu uutils broken by l2s: the guest's own `/usr/bin/env -i` would fail too.
            GuestCoreutilsRepair.repairIfNeeded(distroId)
        }
        val user = LinuxCommandBuilder.sessionUserForType(type)
        // Always ZSH or BASH from prefs — never null (chroot root follows the pref too).
        val loginShell = TerminalPreferences.getGuestLoginShell(ctx)
        Log.d("GuestSessionFactory", "loginShell=${loginShell.id} method=$method distroId=$distroId")
        val (args, envMap) = LinuxCommandBuilder.build(
            ctx, shellCmd, user = user, method = method, distroId = distroId,
            loginShell = loginShell
        )

        val isChroot = method == "chroot"
        val cwd = if (isChroot) "/" else TermuxHostPaths.homeDir(ctx).absolutePath
        // proot argv[0] is the executable: libbash (legacy) or libproot (direct).
        val sessionExec = if (isChroot) com.ivarna.fluxlinux.core.root.ChrootPaths.SESSION_EXEC else args[0]

        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        return {
            val session = TerminalSession(sessionExec, cwd, args, env, 10000, SessionRegistry.sessionClient())
            SessionRegistry.add(
                ctx,
                SessionRegistry.ManagedSession(
                    session,
                    type,
                    title,
                    method,
                    distroId = distroId,
                    iconRes = DistroRepository.iconResFor(distroId) ?: R.drawable.ic_terminal
                )
            )
        }
    }

    /**
     * True when the embedded host is present enough to open an interactive host
     * shell: libbash.so available and the bootstrap extracted (R4 — host shell
     * previously skipped `prepareHost` and could fail weakly on a missing tree).
     */
    fun hostShellReady(ctx: Context): Boolean =
        TermuxHostPaths.libBash(ctx).isFile && TerminalLauncher.isBootstrapExtracted(ctx)

    /**
     * Interactive host (embedded Termux prefix) shell under libbash — no guest login.
     * Used by the HOST card in the terminal tool selector (plan §5.1).
     * Host env carries PREFIX/HOME/TMPDIR/package identity via [HostCommandBuilder].
     */
    fun openHostShell(ctx: Context, title: String = "Host Shell"): Boolean {
        if (!SessionRegistry.hasFreeTab()) return false
        val shell = TermuxHostPaths.libBash(ctx).absolutePath
        val (_, envMap) = HostCommandBuilder.build(ctx, shell, forceHostSetup = false)
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val session = TerminalSession(
            shell,
            TermuxHostPaths.homeDir(ctx).absolutePath,
            arrayOf(shell, "-l"),
            env,
            10000,
            SessionRegistry.sessionClient()
        )
        return SessionRegistry.add(
            ctx,
            SessionRegistry.ManagedSession(
                session,
                "host",
                title,
                "host",
                iconRes = R.drawable.ic_terminal
            )
        )
    }

    /**
     * Component install/uninstall session for a distro. The component script is
     * base64-injected and runs INSIDE the guest as **root** (apt/dpkg require it;
     * setup scripts then switch to user `flux` where needed). Same terminal
     * component as the parent distro — never Termux intent.
     * [onFinished] fires when the session exits.
     */
    fun openComponentSession(
        ctx: Context,
        distro: Distro,
        scriptContent: String,
        title: String,
        extraEnv: Map<String, String> = emptyMap(),
        isUninstall: Boolean = false,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        val method = terminalComponentFor(distro.id).method
        val b64 = android.util.Base64.encodeToString(
            scriptContent.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val guestPayload = componentPayload(b64, isUninstall, extraEnv)
        if (!SessionRegistry.hasFreeTab()) return false
        // Root required: customization / feature setup scripts call apt & chown.
        // Interactive shells stay flux via openSession(sessionUserForType).
        val user = "root"
        val (args, envMap) = LinuxCommandBuilder.build(
            ctx, guestPayload, user = user, method = method, distroId = distro.id
        )
        val isChroot = method == "chroot"
        val sessionExec = if (isChroot) com.ivarna.fluxlinux.core.root.ChrootPaths.SESSION_EXEC else args[0]
        val cwd = if (isChroot) "/" else TermuxHostPaths.homeDir(ctx).absolutePath
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val session = TerminalSession(sessionExec, cwd, args, env, 10000, SessionRegistry.sessionClient())
        return SessionRegistry.add(
            ctx,
            SessionRegistry.ManagedSession(
                session,
                "component",
                title,
                method,
                onFinished,
                distroId = distro.id,
                iconRes = DistroRepository.iconResFor(distro.id) ?: R.drawable.ic_terminal
            )
        )
    }

    /**
     * Guest-side shell for [openComponentSession]. The script goes to a
     * per-session `mktemp` path (L1): concurrent component tabs share the
     * guest `/tmp`, so a fixed name let one tab overwrite the other's script
     * mid-run. The file is removed after the run and the exit code kept.
     */
    internal fun componentPayload(
        b64: String,
        isUninstall: Boolean,
        extraEnv: Map<String, String>,
        tmpDir: String = "/tmp",
    ): String {
        val arg = if (isUninstall) " uninstall" else ""
        val envPrefix = extraEnv.entries.joinToString("") { (k, v) ->
            "export ${k}='${v.replace("'", "'\\''")}'; "
        }
        return buildString {
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append(envPrefix)
            append("FLUX_FEATURE=\$(mktemp $tmpDir/flux_feature.XXXXXX) || exit 1; ")
            append("echo '$b64' | base64 -d > \"\$FLUX_FEATURE\"; ")
            append("chmod +x \"\$FLUX_FEATURE\"; ")
            append("if [ -x /bin/bash ]; then bash \"\$FLUX_FEATURE\"$arg; ")
            append("else sh \"\$FLUX_FEATURE\"$arg; fi; RC=\$?; ")
            append("rm -f \"\$FLUX_FEATURE\"; ")
            append("exit \$RC")
        }
    }
}

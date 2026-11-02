package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.terminalComponentFor
import com.ivarna.fluxlinux.core.install.ChrootSetupCommand
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.GuestUsers
import com.ivarna.fluxlinux.core.root.RootShell
import com.termux.terminal.TerminalSession

/**
 * Builds host + install sessions.
 *  - proot: `$HOME/flux_install.sh <prootName> [setup_b64]` under libbash
 *  - chroot: staged setup_*_chroot.sh run as root on the host
 */
object InstallSessionFactory {

    fun openInstallSession(
        ctx: Context,
        distro: Distro,
        setupB64: String? = null,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        val method = terminalComponentFor(distro.id).method
        return when (method) {
            "chroot" -> openChrootInstall(ctx, distro.id, onFinished)
            else -> openProotInstall(ctx, distro.id, setupB64, onFinished)
        }
    }

    fun openHostScriptSession(
        ctx: Context,
        scriptName: String,
        title: String = scriptName,
        args: Array<String> = emptyArray(),
        forceHostSetup: Boolean = false,
        onFinished: (() -> Unit)? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Boolean {
        val script = TermuxHostPaths.hostScript(ctx, scriptName)
        val (_, envMap) = HostCommandBuilder.build(
            ctx,
            script.absolutePath,
            forceHostSetup = forceHostSetup || HostCommandBuilder.shouldForceHostSetup(scriptName)
        )
        extraEnv.forEach { (k, v) -> envMap[k] = v }
        val shell = TermuxHostPaths.libBash(ctx).absolutePath
        val argv = arrayOf(shell, script.absolutePath) + args
        val cwd = TermuxHostPaths.homeDir(ctx).absolutePath
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val session = TerminalSession(shell, cwd, argv, env, 10000, SessionRegistry.sessionClient())
        return SessionRegistry.add(
            ctx,
            SessionRegistry.ManagedSession(session, "host", title, "host", onFinished)
        )
    }

    fun openHostCommandSession(
        ctx: Context,
        command: String,
        title: String = "Host Shell",
        onClosed: ((Int) -> Unit)? = null
    ): Boolean {
        val shell = TermuxHostPaths.libBash(ctx).absolutePath
        val (_, envMap) = HostCommandBuilder.build(ctx, shell, forceHostSetup = false)
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val session = TerminalSession(
            shell,
            TermuxHostPaths.homeDir(ctx).absolutePath,
            arrayOf(shell, "-c", command),
            env,
            10000,
            SessionRegistry.sessionClient()
        )
        return SessionRegistry.add(
            ctx,
            SessionRegistry.ManagedSession(session, "host", title, "host", onClosed = onClosed)
        )
    }

    /**
     * Root session that runs [innerCmd] under `su -c`.
     *
     * Argv MUST be `[/system/bin/sh, -c, winchCmd]` (same as [ChrootCommandBuilder]).
     * Passing only `[-c, winchCmd]` makes toybox/mksh treat the command string as
     * a script filename → `c: trap …: No such file or directory`.
     */
    fun openRootInnerSession(
        ctx: Context,
        innerCmd: String,
        title: String = "Root Shell",
        onFinished: (() -> Unit)? = null,
        rootfsFileName: String = DistroInstallProfile.DEBIAN_ROOTFS_NAME,
        extraEnv: Map<String, String> = emptyMap(),
        wrapWinch: Boolean = true,
        onClosed: ((Int) -> Unit)? = null
    ): Boolean {
        val rootInner = RootShell.shellRootCommand(innerCmd)
        // Uninstall is non-interactive; the WINCH trap has made toybox sh
        // SIGSEGV after the script already succeeded (signal 11).
        val sessionCmd = if (wrapWinch) rootSessionWinchCommand(rootInner) else rootInner
        val env = HostCommandBuilder.envMap(ctx, includeTerm = false)
        env["PATH"] = "/system/bin:/system/xbin:/sbin:" + (env["PATH"] ?: "")
        env["TERM"] = "xterm-256color"
        env["FLUX_ROOTFS_PATH"] = "${TermuxHostPaths.HOME}/$rootfsFileName"
        extraEnv.forEach { (k, v) -> env[k] = v }
        val session = TerminalSession(
            com.ivarna.fluxlinux.core.root.ChrootPaths.SESSION_EXEC,
            "/",
            rootSessionArgv(sessionCmd),
            env.map { "${it.key}=${it.value}" }.toTypedArray(),
            10000,
            SessionRegistry.sessionClient()
        )
        return SessionRegistry.add(
            ctx,
            SessionRegistry.ManagedSession(
                session, "install", title, "chroot", onFinished, onClosed
            )
        )
    }

    fun openRootScriptSession(
        ctx: Context,
        scriptPath: String,
        title: String = "Root Shell",
        onFinished: (() -> Unit)? = null,
        rootfsFileName: String = DistroInstallProfile.DEBIAN_ROOTFS_NAME,
        chrootPath: String? = null,
        rootfsProfile: DistroInstallProfile? = null
    ): Boolean {
        val extra = mutableMapOf<String, String>()
        if (chrootPath != null) extra["FLUX_CHROOT"] = chrootPath
        // `su -c` does NOT inherit the TerminalSession env map — export the
        // rootfs identity inside the command string so the chroot setup script
        // can resolve / download / SHA-check the archive (P4-T4).
        if (rootfsProfile != null) {
            extra["FLUX_ROOTFS_URL"] = rootfsProfile.rootfsUrl
            extra["FLUX_ROOTFS_SHA256"] = rootfsProfile.rootfsSha256
            extra["FLUX_ROOTFS_NAME"] = rootfsProfile.rootfsFileName
            if (rootfsProfile.method == "chroot") {
                // setup_guest_chroot.sh creates this login user (akane on the Ubuntu chroot).
                extra[GuestUsers.ENV_GUEST_USER] = GuestUsers.chrootUser(rootfsProfile.distroId)
            }
        }
        val exports = extra.entries.joinToString(" ") { (k, v) ->
            "$k='${v.replace("'", "'\\''")}'"
        }
        val inner = if (exports.isEmpty()) {
            "sh '$scriptPath'"
        } else {
            "export $exports; sh '$scriptPath'"
        }
        return openRootInnerSession(
            ctx, inner, title, onFinished, rootfsFileName, extra
        )
    }

    private fun openProotInstall(
        ctx: Context,
        distroId: String,
        setupB64: String?,
        onFinished: (() -> Unit)?
    ): Boolean {
        val profile = DistroInstallProfile.require(distroId)
        // Re-running setup repairs a broken guest: its login must work before it can switch.
        GuestUserMigration.migrateIfNeeded(ctx, distroId)
        GuestCoreutilsRepair.repairIfNeeded(distroId)
        val args = if (setupB64.isNullOrEmpty() || setupB64 == "null") {
            arrayOf(profile.prootName)
        } else {
            arrayOf(profile.prootName, setupB64)
        }
        return openHostScriptSession(
            ctx,
            "flux_install.sh",
            title = "${profile.displayName} Install (Flux Terminal)",
            args = args,
            onFinished = onFinished,
            extraEnv = mapOf(
                "FLUX_ROOTFS_PATH" to "${TermuxHostPaths.HOME}/${profile.rootfsFileName}",
                "FLUX_ROOTFS_NAME" to profile.rootfsFileName,
                "FLUX_ROOTFS_SHA256" to profile.rootfsSha256,
                "FLUX_ROOTFS_URL" to profile.rootfsUrl
            )
        )
    }

    /**
     * Chroot setup in a root terminal tab. M14: the script and its resolver are staged into
     * the root-only verified stage by the session's own root command
     * ([ChrootSetupCommand.buildStagingSession]), so nothing calls su on this (main) thread
     * and root never runs a copy from app-writable `filesDir/staged_scripts`.
     */
    private fun openChrootInstall(
        ctx: Context,
        distroId: String,
        onFinished: (() -> Unit)?
    ): Boolean {
        val profile = DistroInstallProfile.require(distroId)
        val asset = profile.chrootSetupAsset
            ?: return false
        val inner = try {
            val script = ctx.assets.open(asset).use { it.readBytes() }
            val resolver = ctx.assets.open(BusyBoxPaths.RESOLVER_ASSET).use { it.readBytes() }
            ChrootSetupCommand.buildStagingSession(
                profile = profile,
                scriptName = FileName(asset),
                scriptB64 = java.util.Base64.getEncoder().encodeToString(script),
                scriptSha256 = RootShell.sha256Hex(script),
                resolverB64 = java.util.Base64.getEncoder().encodeToString(resolver),
                resolverSha256 = RootShell.sha256Hex(resolver),
                resolvedBb = RootShell.cachedBusyBox(),
                chrootPath = profile.chrootPath ?: com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH,
                rootfsHome = TermuxHostPaths.HOME,
                packageName = TermuxHostPaths.PACKAGE,
            )
        } catch (e: Exception) {
            android.util.Log.w("InstallSessionFactory", "chroot setup payload for $distroId failed: ${e.message}")
            return false
        }
        return openRootInnerSession(
            ctx,
            inner,
            title = "${profile.displayName} Install",
            onFinished = onFinished,
            rootfsFileName = profile.rootfsFileName,
        )
    }

    private fun FileName(assetPath: String): String =
        assetPath.substringAfterLast('/')

    fun rootSessionWinchCommand(rootInner: String): String =
        "trap 'kill -WINCH -\$\$ 2>/dev/null; kill -WINCH 0 2>/dev/null' WINCH; $rootInner"

    /** Full argv for [com.ivarna.fluxlinux.core.root.ChrootPaths.SESSION_EXEC]. */
    fun rootSessionArgv(winchCmd: String): Array<String> =
        arrayOf(
            com.ivarna.fluxlinux.core.root.ChrootPaths.SESSION_EXEC,
            "-c",
            winchCmd
        )
}

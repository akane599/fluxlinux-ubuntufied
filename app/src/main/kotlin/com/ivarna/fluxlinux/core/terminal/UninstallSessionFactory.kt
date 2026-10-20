package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.terminalComponentFor
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.RootShell
import java.util.concurrent.ConcurrentHashMap

/**
 * Distro uninstall sessions.
 *  - proot: `proot-distro remove <name>` + marker cleanup + callback
 *  - chroot: root session running staged uninstall_*_chroot.sh
 */
object UninstallSessionFactory {

    /**
     * Chroot paths whose uninstall session is open (H4). The desktop and chroot
     * terminal must not start (and re-mount) a rootfs that is being deleted.
     */
    private val uninstallingChroots: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isChrootUninstalling(path: String): Boolean = path in uninstallingChroots

    /**
     * Opens the uninstall session. [onOpened] runs on the main thread with whether
     * the terminal tab was added. Chroot su discovery, BusyBox resolution and asset
     * staging block for seconds, so they run on [TerminalLauncher.executor] (M15)
     * and only the session add is posted back to main.
     *
     * @return false when nothing was opened or dispatched (callers should rely on
     *   [onOpened] for the outcome; for chroot `true` only means "dispatched").
     */
    fun openUninstallSession(
        ctx: Context,
        distro: Distro,
        onOpened: (Boolean) -> Unit = {},
    ): Boolean {
        val method = terminalComponentFor(distro.id).method
        val profile = DistroInstallProfile.forId(distro.id)
        val appCtx = ctx.applicationContext
        val onClosed: (Int) -> Unit = {
            TerminalLauncher.refreshInstalledAfterUninstall(appCtx, distro.id)
        }
        return when (method) {
            "chroot" -> {
                val chrootPath = profile?.chrootPath.orEmpty()
                if (chrootPath.isNotEmpty() && !uninstallingChroots.add(chrootPath)) {
                    // An uninstall of this rootfs is already running.
                    onOpened(false)
                    return false
                }
                val release: () -> Unit = {
                    if (chrootPath.isNotEmpty()) uninstallingChroots.remove(chrootPath)
                }
                TerminalLauncher.executor.execute {
                    val inner = try {
                        chrootUninstallCommand(appCtx, distro, profile, chrootPath)
                    } catch (e: Exception) {
                        android.util.Log.w("UninstallSession", "staging failed: ${e.message}")
                        null
                    }
                    TerminalLauncher.mainHandler.post {
                        val opened = inner != null && InstallSessionFactory.openRootInnerSession(
                            appCtx,
                            inner,
                            title = "Uninstall ${distro.name}",
                            rootfsFileName = profile?.rootfsFileName
                                ?: DistroInstallProfile.DEBIAN_ROOTFS_NAME,
                            extraEnv = mapOf(
                                "FLUX_CHROOT" to chrootPath,
                                "FLUX_DISTRO_ID" to distro.id,
                            ),
                            wrapWinch = false,
                            // uninstall_debian13_chroot.sh removes the shared helper.
                            onClosed = {
                                release()
                                RootShell.invalidateChrootHelper()
                                onClosed(it)
                            },
                        )
                        if (!opened) release()
                        onOpened(opened)
                    }
                }
                true
            }
            else -> {
                val removeName = profile?.prootName ?: distro.id
                val callback = "am start -a android.intent.action.VIEW -d " +
                    "\"fluxlinux://callback?result=success&name=distro_uninstall_${distro.id}\""
                val cmd = buildString {
                    append("NAME=$removeName; ")
                    append("DIR=\"\$PREFIX/var/lib/proot-distro/containers/\$NAME\"; ")
                    append("proot-distro remove \$NAME; RC=\$?; ")
                    append("rm -rf \"\$DIR\" 2>/dev/null || true; ")
                    // Customization can leave root-owned fonts/icons; app-uid rm fails.
                    append("if [ -e \"\$DIR\" ]; then ")
                    append("if [ -x /system/bin/su ]; then /system/bin/su -c \"rm -rf \$DIR\" </dev/null; ")
                    append("elif command -v su >/dev/null 2>&1; then su -c \"rm -rf \$DIR\" </dev/null; fi; ")
                    append("fi; ")
                    append("rm -f \"\$HOME/.fluxlinux_distro_${distro.id}_installed\" 2>/dev/null; ")
                    append("if [ ! -e \"\$DIR\" ]; then RC=0; fi; ")
                    append("if [ \$RC -eq 0 ]; then $callback; fi; exit \$RC")
                }
                val opened = InstallSessionFactory.openHostCommandSession(
                    ctx, cmd, title = "Uninstall ${distro.name}", onClosed = onClosed
                )
                onOpened(opened)
                opened
            }
        }
    }

    /** Blocking (su probe, BusyBox resolve, asset staging): background thread only. */
    private fun chrootUninstallCommand(
        ctx: Context,
        distro: Distro,
        profile: DistroInstallProfile?,
        chrootPath: String,
    ): String {
        val asset = profile?.chrootUninstallAsset
            ?: "scripts/chroot/uninstall_debian13_chroot.sh"
        val name = asset.substringAfterLast('/')
        RootShell.ensureBusyBoxResolver(ctx)
        val resolvedBb = RootShell.resolveBusyBox()
        val staged = RootShell.stageAsset(ctx, asset)
            ?: TermuxHostPaths.hostScript(ctx, name).absolutePath
        val resolverStaged = RootShell.stageAsset(ctx, BusyBoxPaths.RESOLVER_ASSET)
            ?: TermuxHostPaths.hostScript(ctx, "resolve_bb.sh").absolutePath
        val tmpPath = "/data/local/tmp/$name"
        val stagedQ = staged.replace("'", "'\\''")
        val resolverQ = resolverStaged.replace("'", "'\\''")
        val chrootQ = chrootPath.replace("'", "'\\''")
        val bbExport = if (!resolvedBb.isNullOrEmpty()) "FLUX_BB='$resolvedBb' " else ""
        // Copy out of app-private storage (su often cannot read it),
        // then run with FLUX_CHROOT on the su command (su drops env).
        return "cp -f '$stagedQ' '$tmpPath' && chmod 755 '$tmpPath' && " +
            "{ cp -f '$resolverQ' '${BusyBoxPaths.RESOLVER_ON_DEVICE}' && " +
            "chmod 755 '${BusyBoxPaths.RESOLVER_ON_DEVICE}' || true; } && " +
            "${bbExport}FLUX_RESOLVE_BB='${BusyBoxPaths.RESOLVER_ON_DEVICE}' " +
            "FLUX_CHROOT='$chrootQ' FLUX_DISTRO_ID='${distro.id}' " +
            "sh '$tmpPath'"
    }
}

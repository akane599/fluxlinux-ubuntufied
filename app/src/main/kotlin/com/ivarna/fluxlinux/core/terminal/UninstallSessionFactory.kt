package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.terminalComponentFor
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
     * Uninstall sessions (proot or chroot) being prepared or open. [InstallController]
     * refuses to start an install meanwhile (H2, reverse direction).
     */
    private val openUninstalls = AtomicInteger(0)

    fun isAnyUninstallRunning(): Boolean = openUninstalls.get() > 0

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
        openUninstalls.incrementAndGet()
        val onClosed: (Int) -> Unit = {
            openUninstalls.decrementAndGet()
            TerminalLauncher.refreshInstalledAfterUninstall(appCtx, distro.id)
        }
        return when (method) {
            "chroot" -> {
                val chrootPath = profile?.chrootPath.orEmpty()
                if (chrootPath.isNotEmpty() && !uninstallingChroots.add(chrootPath)) {
                    // An uninstall of this rootfs is already running.
                    openUninstalls.decrementAndGet()
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
                            // uninstall_debian13_chroot.sh removes legacy helper copies.
                            onClosed = {
                                release()
                                RootShell.invalidateChrootHelper()
                                onClosed(it)
                            },
                        )
                        if (!opened) {
                            release()
                            openUninstalls.decrementAndGet()
                        }
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
                if (!opened) openUninstalls.decrementAndGet()
                onOpened(opened)
                opened
            }
        }
    }

    /**
     * Blocking (su probe, BusyBox resolve, staging): background thread only.
     * Null when the script or its resolver sidecar could not be staged and verified in the
     * root-only stage (fail closed, M14).
     */
    private fun chrootUninstallCommand(
        ctx: Context,
        distro: Distro,
        profile: DistroInstallProfile?,
        chrootPath: String,
    ): String? {
        val asset = profile?.chrootUninstallAsset
            ?: "scripts/chroot/uninstall_debian13_chroot.sh"
        val scriptName = asset.substringAfterLast('/')
        // The script sources $(dirname "$0")/resolve_bb.sh: stage + verify it next to it.
        if (!RootShell.ensureBusyBoxResolver(ctx)) {
            android.util.Log.w("UninstallSession", "resolver stage/verify failed; not running $asset")
            return null
        }
        if (!RootShell.stageVerified(ctx, asset, "${ChrootPaths.ROOT_STAGE_DIR}/$scriptName")) {
            android.util.Log.w("UninstallSession", "stage/verify failed; not running $asset")
            return null
        }
        return buildChrootUninstallCommand(
            scriptName = scriptName,
            scriptSha256 = assetSha(ctx, asset),
            resolverSha256 = assetSha(ctx, BusyBoxPaths.RESOLVER_ASSET),
            resolvedBb = RootShell.resolveBusyBox(),
            chrootPath = chrootPath,
            distroId = distro.id,
        )
    }

    private fun assetSha(ctx: Context, asset: String): String =
        RootShell.sha256Hex(ctx.assets.open(asset).use { it.readBytes() })

    /**
     * Root command for the uninstall session (M14). The script and its resolver sidecar were
     * staged by [RootShell.stageVerified] into the root-only [ChrootPaths.ROOT_STAGE_DIR];
     * both are re-verified against the APK hashes right before `sh '<dest>'`, else exit 127.
     * Nothing is copied to or executed from shell-writable `/data/local/tmp`.
     */
    internal fun buildChrootUninstallCommand(
        scriptName: String,
        scriptSha256: String,
        resolverSha256: String,
        resolvedBb: String?,
        chrootPath: String,
        distroId: String,
    ): String {
        val dest = "${ChrootPaths.ROOT_STAGE_DIR}/$scriptName"
        val resolver = BusyBoxPaths.RESOLVER_ON_DEVICE
        val chrootQ = chrootPath.replace("'", "'\\''")
        val bbExport = if (!resolvedBb.isNullOrEmpty() && !resolvedBb.startsWith("/data/local/tmp/")) {
            "FLUX_BB='$resolvedBb' "
        } else {
            ""
        }
        return "${RootShell.SHA_FN}; " +
            "if ${RootShell.shaMatches(dest, scriptSha256)} && " +
            "${RootShell.shaMatches(resolver, resolverSha256)}; then " +
            "${bbExport}FLUX_RESOLVE_BB='$resolver' FLUX_CHROOT='$chrootQ' FLUX_DISTRO_ID='$distroId' " +
            "sh '$dest'; " +
            "else echo '[FluxLinux] $scriptName or its resolver in " +
            "${ChrootPaths.ROOT_STAGE_DIR} failed SHA-256 verification; uninstall not run' >&2; exit 127; fi"
    }
}

package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import java.io.File
import com.ivarna.fluxlinux.core.root.GuestUsers

/** Entry point for building terminal commands.
 *  Delegates to ProotCommandBuilder or ChrootCommandBuilder based on method.
 *  Ported from termux-lib `LinuxCommandBuilder`. */
object LinuxCommandBuilder {

    /**
     * UI-default isolation method. DEPRECATED for product/card actions —
     * card paths must pass `method` explicitly from
     * `terminalComponentFor(distroId).method` (plan §2.6). Kept only as a
     * convenience default for the shared Terminal page tool selector.
     */
    @Deprecated("Pass method explicitly from terminalComponentFor(distroId).method for card actions")
    var currentMethod = "proot"

    /**
     * Guest user for session type, distro unknown.
     * - shell-root / component → root (apt/dpkg & setup scripts)
     * - interactive shell / default → flux (pass the distro to get akane for Ubuntu)
     */
    fun sessionUserForType(type: String): String =
        sessionUserForType(type, method = "proot", distroId = null)

    /**
     * Guest user for session type on [method]/[distroId]: root for shell-root/component,
     * else the guest's default user ([GuestUsers.defaultUser]: `akane` for both Ubuntu
     * installs, `flux` for every other distro).
     */
    fun sessionUserForType(type: String, method: String?, distroId: String?): String =
        when (type) {
            "shell-root", "component" -> GuestUsers.ROOT
            else -> GuestUsers.defaultUser(method, distroId)
        }

    /** [user]: `root`, a guest user, or null for the default user of [method]/[distroId]. */
    fun build(
        ctx: Context,
        shellCmd: String,
        user: String? = null,
        useSharedTmp: Boolean = true,
        method: String = currentMethod,
        distroId: String? = null,
        loginShell: GuestLoginShell? = null
    ): Pair<Array<String>, HashMap<String, String>> {
        val profile = distroId?.let {
            com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(it)
        }
        return when (method) {
            "chroot" -> ChrootCommandBuilder.build(
                ctx,
                shellCmd,
                user, // null → default user of the chroot path (akane for Ubuntu)
                chrootPath = profile?.chrootPath
                    ?: com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH,
                loginShell = loginShell
            )
            else -> {
                val prootName = profile?.prootName?.ifBlank { null } ?: "debian"
                ProotCommandBuilder.build(
                    ctx,
                    shellCmd,
                    // null → default user of the distro (akane for Ubuntu); an Ubuntu rootfs
                    // that still has only flux (migration failed) keeps logging in as flux.
                    GuestUsers.prootLoginUser(
                        user ?: GuestUsers.userFor(distroId),
                        File("${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/$prootName/rootfs"),
                    ),
                    useSharedTmp,
                    distro = prootName,
                    loginShell = loginShell ?: GuestLoginShell.DEFAULT
                )
            }
        }
    }
}

package com.ivarna.fluxlinux.core.root

import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Which guest user a session, script or file path belongs to. The rule itself is
 * [UbuntuRuntime.guestUserFor] (`akane` for both Ubuntu installs, `flux` for every other
 * distro, whose existing installs keep working); this adds the chroot-path lookup, name
 * validation and script env. Callers that pass an explicit user keep it; `null` means
 * "the default user for this guest".
 *
 * The same rule lives in the guest scripts: `fluxlinux_chroot.sh` (`resolve_guest_user`),
 * `start_guest_gui.sh` and `repair_pulse_guests.sh` default to `akane` when the guest
 * passwd has it, else `flux`; the shared setup scripts read `FLUX_GUEST_USER` ([scriptEnv])
 * and default to `flux` without it.
 */
object GuestUsers {

    const val ROOT = "root"

    /** Every non-Ubuntu guest ([UbuntuRuntime.LEGACY_GUEST_USER]). */
    const val LEGACY_USER = UbuntuRuntime.LEGACY_GUEST_USER

    /** Env var the guest setup scripts read for the login user. */
    const val ENV_GUEST_USER = "FLUX_GUEST_USER"

    private val NAME = Regex("^[a-z_][a-z0-9_-]{0,31}$")

    /** A plain guest user name (safe to splice into a shell word). `root` is valid. */
    fun isValidName(name: String?): Boolean = name != null && NAME.matches(name)

    /** Default non-root user of [distroId] (either runtime). */
    fun userFor(distroId: String?): String = UbuntuRuntime.guestUserFor(distroId)

    /** Default non-root user of a chroot distro (same rule as [userFor]). */
    fun chrootUser(distroId: String?): String = userFor(distroId)

    /** Default non-root user of the chroot rootfs at [chrootPath]. */
    fun chrootUserForPath(chrootPath: String?): String =
        userFor(if (chrootPath == ChrootPaths.UBUNTU_CHROOT_PATH) UbuntuRuntime.CHROOT_ID else null)

    /** Default interactive user ([method] no longer matters: both Ubuntu runtimes use akane). */
    @Suppress("UNUSED_PARAMETER")
    fun defaultUser(method: String?, distroId: String?): String = userFor(distroId)

    /**
     * The chroot user to run as: [requested] when given (root or a valid name), else the
     * default for [chrootPath].
     * @throws IllegalArgumentException for a name that is not a plain user name.
     */
    fun resolveChrootUser(requested: String?, chrootPath: String?): String {
        val user = requested ?: return chrootUserForPath(chrootPath)
        require(isValidName(user)) { "invalid guest user name: $user" }
        return user
    }

    /**
     * PRoot login user for [requested] in [rootfs]: when the Ubuntu user is asked for but the
     * guest passwd still has only `flux` (GuestUserMigration failed or found a conflict it
     * would not touch), log in as `flux` so the terminal and desktop keep working. Without a
     * readable passwd the homes decide (a real `home/flux` and no `home/akane` → `flux`).
     * Any other request returns [requested] unchanged.
     */
    fun prootLoginUser(requested: String, rootfs: File?): String {
        if (requested != UbuntuRuntime.UBUNTU_GUEST_USER || rootfs == null) return requested
        val passwd = File(rootfs, "etc/passwd").toPath()
        val text = try {
            if (Files.isRegularFile(passwd, LinkOption.NOFOLLOW_LINKS)) {
                String(Files.readAllBytes(passwd), Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (text != null) return loginUserFromPasswd(requested, text)
        // No readable passwd: go by the homes (only a real legacy home and no new one → flux).
        val home = { u: String -> Files.isDirectory(File(rootfs, "home/$u").toPath(), LinkOption.NOFOLLOW_LINKS) }
        return if (!home(requested) && home(LEGACY_USER)) LEGACY_USER else requested
    }

    /**
     * Login user's home in a PRoot [rootfs] of [distroId] ([prootLoginUser] rule), e.g.
     * `rootfs/home/akane` on Ubuntu, `rootfs/home/flux` elsewhere or before migration.
     */
    fun prootHome(rootfs: File, distroId: String?): File =
        File(rootfs, homeOf(prootLoginUser(userFor(distroId), rootfs)).removePrefix("/"))

    /**
     * [prootHome] for a `containers/<prootName>/rootfs` dir: the container name is the
     * distro id for Ubuntu (`ubuntu`); other containers resolve to `flux` either way.
     */
    fun prootHome(rootfs: File): File = prootHome(rootfs, rootfs.absoluteFile.parentFile?.name)

    /** [prootLoginUser] on passwd text. */
    internal fun loginUserFromPasswd(requested: String, passwd: String): String {
        val users = passwd.lineSequence().map { it.substringBefore(':') }.toSet()
        return if (requested !in users && LEGACY_USER in users) LEGACY_USER else requested
    }

    /** Guest home of [user] (`/root` for root). */
    fun homeOf(user: String): String = if (user == ROOT) "/root" else "/home/$user"

    /**
     * Env for guest scripts (family, customization, hw-accel, components) of [distroId]:
     * chroot guests get [ENV_GUEST_USER] and `FLUX_PROOT=0`; the Ubuntu PRoot gets
     * [ENV_GUEST_USER] for the user it logs in as ([prootLoginUser] on [prootRootfs]: `akane`,
     * or `flux` while its passwd still has only flux, so a re-run never adds an empty second
     * account); other PRoot distros get nothing (their callers set `FLUX_PROOT=1`, and the
     * scripts default the user to `flux`).
     *
     * The app cannot read a chroot rootfs, so chroot guests always get `akane`; the scripts
     * themselves fall back to `flux` when the guest passwd has flux but not the requested user
     * (same rule as `fluxlinux_chroot.sh` `resolve_guest_user`).
     */
    fun scriptEnv(distroId: String?, method: String?, prootRootfs: File?): Map<String, String> = when {
        method == "chroot" -> linkedMapOf(ENV_GUEST_USER to userFor(distroId), "FLUX_PROOT" to "0")
        UbuntuRuntime.isUbuntuId(distroId) ->
            linkedMapOf(ENV_GUEST_USER to prootLoginUser(userFor(distroId), prootRootfs))
        else -> emptyMap()
    }
}

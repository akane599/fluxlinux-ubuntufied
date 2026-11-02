package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.GuestUsers
import com.ivarna.fluxlinux.core.root.RootShell

/**
 * Root command that runs a chroot setup script (`setup_*_chroot.sh`) for onboarding (M14).
 *
 * The script and its `resolve_bb.sh` sidecar are staged by [RootShell.stageVerified] into the
 * root-only [ChrootPaths.ROOT_STAGE_DIR] and re-verified against the APK hashes right before
 * `sh '<dest>'`, else exit 127 — never run from app-writable `filesDir/staged_scripts` or
 * shell-writable `/data/local/tmp`. Same shape as `UninstallSessionFactory`'s command.
 */
internal object ChrootSetupCommand {

    fun stagedPath(scriptName: String): String = "${ChrootPaths.ROOT_STAGE_DIR}/$scriptName"

    fun build(
        profile: DistroInstallProfile,
        scriptName: String,
        scriptSha256: String,
        resolverSha256: String,
        resolvedBb: String?,
        chrootPath: String,
        rootfsHome: String,
        packageName: String,
    ): String {
        require(scriptName.matches(Regex("[A-Za-z0-9._-]+"))) { "bad script name: $scriptName" }
        val dest = stagedPath(scriptName)
        val resolver = BusyBoxPaths.RESOLVER_ON_DEVICE
        val label = profile.distroId.removeSuffix("_chroot")
        val user = GuestUsers.chrootUser(profile.distroId)
        val bbExport = if (!resolvedBb.isNullOrEmpty() && !resolvedBb.startsWith("/data/local/tmp/")) {
            "export FLUX_BB=${q(resolvedBb)}; "
        } else {
            ""
        }
        val env = bbExport +
            "export FLUX_RESOLVE_BB=${q(resolver)}; " +
            "export FLUX_ROOTFS_PATH=${q("$rootfsHome/${profile.rootfsFileName}")}; " +
            "export FLUX_ROOTFS_NAME=${q(profile.rootfsFileName)}; " +
            "export FLUX_ROOTFS_SHA256=${q(profile.rootfsSha256)}; " +
            "export FLUX_ROOTFS_URL=${q(profile.rootfsUrl)}; " +
            "export FLUX_CHROOT=${q(chrootPath)}; " +
            "export FLUX_DISTRO_LABEL=${q(label)}; " +
            "export FLUX_GUEST_USER=${q(user)}; " +
            "export TERMUX_APP__PACKAGE_NAME=${q(packageName)}; " +
            "export TERMUX__HOME=${q(rootfsHome)}; " +
            "export PYTHONUNBUFFERED=1; "
        return "${RootShell.SHA_FN}; " +
            "if ${RootShell.shaMatches(dest, scriptSha256)} && " +
            "${RootShell.shaMatches(resolver, resolverSha256)}; then " +
            env + "sh '$dest'; " +
            "else echo '[FluxLinux] $scriptName or its resolver in ${ChrootPaths.ROOT_STAGE_DIR} " +
            "failed SHA-256 verification; setup not run' >&2; exit 127; fi"
    }

    /**
     * [build] for a root terminal session that opens without any su call beforehand (the
     * main thread must not block on su): the same root command first writes the setup
     * script and its resolver into [ChrootPaths.ROOT_STAGE_DIR] from base64 (temp file,
     * SHA-256 check, rename; [RootShell.installSnippet]), then [build] re-verifies both and
     * runs the script. Any staging failure exits 127 before anything runs.
     */
    fun buildStagingSession(
        profile: DistroInstallProfile,
        scriptName: String,
        scriptB64: String,
        scriptSha256: String,
        resolverB64: String,
        resolverSha256: String,
        resolvedBb: String?,
        chrootPath: String,
        rootfsHome: String,
        packageName: String,
    ): String {
        require(scriptName.matches(Regex("[A-Za-z0-9._-]+"))) { "bad script name: $scriptName" }
        require(B64.matches(scriptB64) && B64.matches(resolverB64)) { "payload is not base64" }
        require(HEX64.matches(scriptSha256) && HEX64.matches(resolverSha256)) { "bad sha256" }
        val stage = "${RootShell.SHA_FN}; { ${RootShell.stageDirSnippet()} && " +
            RootShell.installSnippet(BusyBoxPaths.RESOLVER_ON_DEVICE, resolverSha256, b64 = resolverB64) +
            " && " + RootShell.installSnippet(stagedPath(scriptName), scriptSha256, b64 = scriptB64) +
            "; } || { echo '[FluxLinux] could not stage $scriptName as root (verify failed)' >&2; exit 127; }; "
        return stage + build(
            profile, scriptName, scriptSha256, resolverSha256, resolvedBb, chrootPath, rootfsHome, packageName,
        )
    }

    private val B64 = Regex("[A-Za-z0-9+/=]+")
    private val HEX64 = Regex("[0-9a-f]{64}")

    private fun q(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}

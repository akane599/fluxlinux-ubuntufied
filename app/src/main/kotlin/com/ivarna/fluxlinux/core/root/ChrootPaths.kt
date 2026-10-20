package com.ivarna.fluxlinux.core.root

import com.ivarna.fluxlinux.BuildConfig

/**
 * On-device chroot SSOT paths + helper identity.
 * Shared by [com.ivarna.fluxlinux.core.terminal.ChrootCommandBuilder] and
 * [RootShell] without an import cycle (RootShell = su only, never imports
 * ChrootCommandBuilder — plan §2.5).
 */
object ChrootPaths {
    /** Debian 13 chroot rootfs (historical default). */
    const val DEBIAN_CHROOT_PATH = "/data/local/tmp/chrootDebian13"

    /** Alpine 3.24 chroot rootfs (coexists with Debian). */
    const val ALPINE_CHROOT_PATH = "/data/local/tmp/chrootAlpine"

    const val FEDORA_CHROOT_PATH = "/data/local/tmp/chrootFedora"
    const val VOID_CHROOT_PATH = "/data/local/tmp/chrootVoid"
    const val OPENSUSE_CHROOT_PATH = "/data/local/tmp/chrootOpenSUSE"

    const val DEEPIN_CHROOT_PATH = "/data/local/tmp/chrootDeepin"
    const val CHIMERA_CHROOT_PATH = "/data/local/tmp/chrootChimera"
    const val MANJARO_CHROOT_PATH = "/data/local/tmp/chrootManjaro"

    const val UBUNTU_CHROOT_PATH = "/data/local/tmp/chrootUbuntu"
    const val KALI_CHROOT_PATH = "/data/local/tmp/chrootKali"
    const val PARROT_CHROOT_PATH = "/data/local/tmp/chrootParrot"
    const val ARCH_CHROOT_PATH = "/data/local/tmp/chrootArch"

    /**
     * Default chroot path for unscoped APIs (settings / legacy).
     * Prefer [DEBIAN_CHROOT_PATH] / [ALPINE_CHROOT_PATH] or profile.chrootPath.
     */
    const val CHROOT_PATH = DEBIAN_CHROOT_PATH

    /**
     * Root-only staging root (M14). `/data/local/tmp` is `0771 shell:shell`, so the shell uid
     * (adb, Shizuku apps) could replace anything root later runs from there. `/data/adb` is
     * root-only on Magisk / KernelSU / APatch; RootShell creates these dirs root:root 0700.
     */
    const val ROOT_STAGE_PARENT = "/data/adb/fluxlinux"

    /** Per-package stage dir (both flavors may be installed side by side). */
    const val ROOT_STAGE_DIR = ROOT_STAGE_PARENT + "/" + BuildConfig.APPLICATION_ID

    /**
     * On-device SSOT helper (assets/scripts/chroot/fluxlinux_chroot.sh), staged by
     * [RootShell.ensureChrootHelper] and SHA-256-verified against the APK asset.
     * `resolve_bb.sh` sits next to it ([BusyBoxPaths.RESOLVER_ON_DEVICE]) so the helper's
     * `$(dirname "$0")/resolve_bb.sh` lookup finds the verified sidecar first.
     */
    const val CHROOT_HELPER = "$ROOT_STAGE_DIR/fluxlinux_chroot.sh"

    /** Pre-M14 location (shell-writable). Only ever removed, never executed. */
    const val LEGACY_CHROOT_HELPER = "/data/local/tmp/fluxlinux_chroot.sh"

    const val CHROOT_HELPER_ASSET = "scripts/chroot/fluxlinux_chroot.sh"
    /** Must match first `# fluxlinux-chroot vN` line in the asset. */
    const val CHROOT_HELPER_VERSION = "fluxlinux-chroot v2.9"

    /** Session executable — must be a system binary (SELinux blocks exec of app-data scripts). */
    const val SESSION_EXEC = "/system/bin/sh"

    fun pathForDistro(distroId: String): String = when (distroId) {
        "alpine_chroot" -> ALPINE_CHROOT_PATH
        "debian13_chroot", "debian_chroot" -> DEBIAN_CHROOT_PATH
        "fedora_chroot" -> FEDORA_CHROOT_PATH
        "void_chroot" -> VOID_CHROOT_PATH
        "opensuse_chroot" -> OPENSUSE_CHROOT_PATH
        "deepin_chroot" -> DEEPIN_CHROOT_PATH
        "chimera_chroot" -> CHIMERA_CHROOT_PATH
        "manjaro_chroot" -> MANJARO_CHROOT_PATH
        "ubuntu_chroot" -> UBUNTU_CHROOT_PATH
        "kali_chroot" -> KALI_CHROOT_PATH
        "parrot_chroot" -> PARROT_CHROOT_PATH
        "archlinux_chroot" -> ARCH_CHROOT_PATH
        else -> CHROOT_PATH
    }
}

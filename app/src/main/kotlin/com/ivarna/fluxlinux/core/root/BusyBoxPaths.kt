package com.ivarna.fluxlinux.core.root

/**
 * On-device BusyBox resolve paths. Shared by [RootShell] and unit tests.
 * Shell resolver walks the same list (plus FLUX_BB and `command -v`). BusyBox is used in
 * place from these root-owned locations; it is never copied to a shell-writable dir (M14).
 */
object BusyBoxPaths {
    const val RESOLVER_ASSET = "scripts/chroot/resolve_bb.sh"

    /** Root-only, SHA-256-verified resolver sidecar (next to the chroot helper). */
    const val RESOLVER_ON_DEVICE = "${ChrootPaths.ROOT_STAGE_DIR}/resolve_bb.sh"

    /** Pre-M14 copies under /data/local/tmp (shell-writable): removed, never executed. */
    const val LEGACY_PINNED = "/data/local/tmp/flux_busybox"
    const val LEGACY_RESOLVER = "/data/local/tmp/fluxlinux_resolve_bb.sh"
    val CANDIDATES: List<String> = listOf(
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox",
        "/data/adb/magisk/busybox",
        "/data/adb/modules/busybox-ndk/system/xbin/busybox",
        "/data/adb/modules/busybox-ndk/system/bin/busybox",
        "/debug_ramdisk/busybox",
        "/sbin/busybox",
        "/system/xbin/busybox",
        "/system/bin/busybox",
    )
}

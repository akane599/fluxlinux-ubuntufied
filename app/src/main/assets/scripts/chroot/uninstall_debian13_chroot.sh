#!/bin/sh
# uninstall_debian13_chroot.sh
# Uninstalls Debian 13 (Trixie) Chroot environment (Requires Root)

# Paths (Must match setup_debian13_chroot.sh)
DEBIANPATH="/data/local/tmp/chrootDebian13"
LAUNCH_SCRIPTS="/data/local/tmp/start_debian13.sh /data/local/tmp/start_debian13_gui.sh /data/local/tmp/enter_debian13.sh /data/local/tmp/enter_debian13_root.sh /data/local/tmp/run_debian13_root.sh /data/local/tmp/stop_debian13_gui.sh /data/local/tmp/uninstall_debian13_chroot.sh /data/local/tmp/fluxlinux_chroot.sh"

# Error Handler
error() {
    printf "\033[1;31m[!] %s\033[0m\n" "$1"
}

success() {
    printf "\033[1;32m[✓] %s\033[0m\n" "$1"
}

progress() {
    printf "\033[1;36m[+] %s\033[0m\n" "$1"
}

# --- H4: mount-safe rootfs removal --------------------------------------------
# Order: mark (.flux_uninstalling, drop .flux_configured) → kill → unmount
# (loop while mounted, fail closed) → rename to a tombstone → kill/unmount the
# tombstone again → delete with find -xdev -depth -delete, which never descends
# into another filesystem. A concurrent fluxlinux_chroot.sh ensure_mounts refuses
# the marked tree, and once renamed it can only bind into a fresh path, never
# into the tree being deleted.
PROC_MOUNTS=/proc/mounts
FIND=""

# Mount points at or below $1, deepest first (exact path match, no regex).
mounts_under() {
    awk -v p="$1" '$2 == p || index($2, p "/") == 1 { print $2 }' \
        "$PROC_MOUNTS" 2>/dev/null | sort -r
}

# Unmount everything at or below $1, looping while anything is still mounted
# (stacked binds need several passes). 0 only when nothing remains.
unmount_under() {
    _pass=0
    while [ "$_pass" -lt 5 ]; do
        _mnts=$(mounts_under "$1")
        [ -z "$_mnts" ] && return 0
        for _mnt in $_mnts; do
            progress "Unmounting: $_mnt"
            $BB umount "$_mnt" 2>/dev/null \
                || $BB umount -l "$_mnt" 2>/dev/null \
                || /system/bin/umount -l "$_mnt" 2>/dev/null || true
        done
        _pass=$((_pass + 1))
    done
    [ -z "$(mounts_under "$1")" ]
}

# Kill host processes whose root is $1 (prefer the shared SSOT helper).
kill_rooted() {
    _helper="$(dirname "$0")/chroot_processes.sh"
    if [ -f "$_helper" ]; then
        sh "$_helper" kill "$1" >/dev/null 2>&1 || true
        return 0
    fi
    for _pid_dir in /proc/[0-9]*; do
        [ -d "$_pid_dir" ] || continue
        _root=$(readlink "$_pid_dir/root" 2>/dev/null)
        if [ "$_root" = "$1" ]; then
            progress "Killing stuck process ${_pid_dir#/proc/}..."
            kill -9 "${_pid_dir#/proc/}" 2>/dev/null
        fi
    done
    return 0
}

# Pick a find that really supports -xdev -depth -delete (probe on a scratch dir).
pick_find() {
    _probe="$1"
    for _f in "${BB:+$BB find}" /system/bin/find find; do
        [ -n "$_f" ] || continue
        rm -rf "$_probe" 2>/dev/null
        mkdir -p "$_probe/d" 2>/dev/null || return 1
        : > "$_probe/d/f"
        # shellcheck disable=SC2086
        if $_f "$_probe" -xdev -depth -delete 2>/dev/null && [ ! -e "$_probe" ]; then
            FIND="$_f"
            return 0
        fi
    done
    rm -rf "$_probe" 2>/dev/null
    return 1
}

# Delete tombstone $1 without crossing mounts; success = the tree is gone.
delete_tree() {
    if [ -z "$FIND" ] && ! pick_find "$1.findprobe"; then
        error "No find with -xdev -depth -delete; refusing an unsafe delete of $1"
        return 1
    fi
    progress "Removing RootFS directory ($1)..."
    # shellcheck disable=SC2086
    $FIND "$1" -xdev -depth -delete 2>/dev/null
    if [ -e "$1" ] || [ -L "$1" ]; then
        error "Failed to remove $1"
        return 1
    fi
    return 0
}

# 0 only when $1 and every stale tombstone are gone.
remove_rootfs() {
    _gp="$1"
    if [ -L "$_gp" ]; then
        error "Refusing: $_gp is a symlink"
        return 1
    fi
    if [ -d "$_gp" ]; then
        # Mark first: ensure_mounts refuses a tree that is being uninstalled.
        : > "$_gp/.flux_uninstalling" 2>/dev/null || true
        rm -f "$_gp/.flux_configured" "$_gp/.flux_extract_ok" 2>/dev/null || true
    fi
    progress "Checking for stalled processes..."
    kill_rooted "$_gp"
    progress "Unmounting filesystems under $_gp..."
    if ! unmount_under "$_gp"; then
        error "CRITICAL: filesystems still mounted under $_gp — nothing deleted:"
        mounts_under "$_gp"
        error "Reboot the device, then uninstall again."
        return 1
    fi
    if [ -d "$_gp" ]; then
        # Nothing is mounted at $_gp, so this is a plain same-directory rename.
        mv "$_gp" "$_gp.deleting.$$" || {
            error "Could not rename $_gp; nothing deleted"
            return 1
        }
    else
        progress "RootFS directory not found (already removed?)"
    fi
    _rc=0
    for _tomb in "$_gp".deleting.*; do
        { [ -d "$_tomb" ] && [ ! -L "$_tomb" ]; } || continue
        kill_rooted "$_tomb"
        if ! unmount_under "$_tomb"; then
            error "CRITICAL: filesystems still mounted under $_tomb — not deleting it:"
            mounts_under "$_tomb"
            _rc=1
            continue
        fi
        delete_tree "$_tomb" || _rc=1
    done
    # A session that raced the rename may have re-created the path (with binds).
    if [ -e "$_gp" ] || [ -L "$_gp" ]; then
        unmount_under "$_gp" || true
        error "RootFS path still exists: $_gp"
        _rc=1
    fi
    return $_rc
}

# Tests source this script with FLUX_UNINSTALL_LIB=1 to exercise the functions.
if [ "${FLUX_UNINSTALL_LIB:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi
# -----------------------------------------------------------------------------

# Check Root
if [ "$(id -u)" != "0" ]; then
    error "This script must be run as root."
    exit 1
fi

progress "Starting Uninstallation of Debian 13 Chroot..."
progress "Target: $DEBIANPATH"

# Detect Busybox
# resolve root BusyBox (manager built-in; NDK module not required)
_rr=""
for _c in \
  "${FLUX_RESOLVE_BB:-}" \
  "$(dirname "$0")/resolve_bb.sh" \
  /data/local/tmp/fluxlinux_resolve_bb.sh
do
  [ -n "$_c" ] && [ -f "$_c" ] && _rr="$_c" && break
done
if [ -n "$_rr" ]; then
  # shellcheck disable=SC1090
  . "$_rr"
  resolve_bb || true
fi
if [ -z "${BB:-}" ]; then
  if [ -n "${FLUX_BB:-}" ] && [ -x "$FLUX_BB" ] &&
     "$FLUX_BB" --list >/dev/null 2>&1; then BB="$FLUX_BB"; fi
  if [ -z "${BB:-}" ] && [ -x /data/local/tmp/flux_busybox ] &&
     /data/local/tmp/flux_busybox --list >/dev/null 2>&1; then
    BB=/data/local/tmp/flux_busybox
  fi
  if [ -z "${BB:-}" ]; then
    for path in \
      /data/adb/ksu/bin/busybox \
      /data/adb/ap/bin/busybox \
      /data/adb/magisk/busybox \
      /data/adb/modules/busybox-ndk/system/xbin/busybox \
      /data/adb/modules/busybox-ndk/system/bin/busybox \
      /debug_ramdisk/busybox \
      /sbin/busybox \
      /system/xbin/busybox \
      /system/bin/busybox
    do
      if [ -x "$path" ]; then BB="$path"; break; fi
    done
  fi
fi

# 1-3. Kill, unmount, remove RootFS (mount-safe; see remove_rootfs)
if remove_rootfs "$DEBIANPATH"; then
    _result=success
    # 4. Remove Scripts
    progress "Removing launcher scripts..."
    for script in $LAUNCH_SCRIPTS; do
        if [ -f "$script" ]; then
            rm -f "$script"
            success "Removed: $script"
        fi
    done
    success "Uninstallation Complete!"
else
    _result=failure
    error "Uninstallation FAILED — the rootfs was not (fully) removed."
fi

# 5. Ensure Host TraceFS is Mounted (Prevents Android Zygote Trace Crash)
mount -t tracefs tracefs /sys/kernel/tracing 2>/dev/null || true

# 6. Notify NativeCode App
progress "Notifying NativeCode App..."
/system/bin/am start -a android.intent.action.VIEW -d "fluxlinux://callback?result=${_result}&name=distro_uninstall_debian13_chroot" >/dev/null 2>&1
[ "$_result" = success ]

#!/bin/sh
# uninstall_guest_chroot.sh — remove a FluxLinux guest chroot (Requires Root)
# Target from FLUX_CHROOT (must be set). Does not remove shared fluxlinux_chroot.sh.

GUESTPATH="${FLUX_CHROOT:-}"
LAUNCH_SCRIPTS="/data/local/tmp/start_guest_gui.sh /data/local/tmp/stop_guest_gui.sh"

error()    { printf "\033[1;31m[!] %s\033[0m\n" "$1"; }
success()  { printf "\033[1;32m[✓] %s\033[0m\n" "$1"; }
progress() { printf "\033[1;36m[+] %s\033[0m\n" "$1"; }

# --- H4: mount-safe rootfs removal --------------------------------------------
# Order: mark (.flux_uninstalling, drop .flux_configured) → wait out an in-flight
# ensure_mounts (shared mount lock) → kill → unmount (loop while mounted, fail
# closed) → rename to a fresh tombstone → kill/unmount the tombstone again →
# re-check /proc/mounts (grep, independent of awk) → find -depth -delete.
# The /proc/mounts checks are the guard: -xdev is defense in depth only (BusyBox
# find ignores it under -depth/-delete, and no find can see a same-filesystem
# bind). A concurrent fluxlinux_chroot.sh ensure_mounts refuses the marked tree,
# and once renamed it can only bind into a fresh path, never into the tombstone.
PROC_MOUNTS=/proc/mounts
FIND=""
AWK=""
# The find probe runs next to this script: the root-only stage dir it is launched
# from (never shell-writable /data/local/tmp). Tests override it after sourcing.
PROBE_BASE="$(dirname "$0")"

# awk from PATH, else the root BusyBox applet. Non-zero when neither works.
pick_awk() {
    [ -n "$AWK" ] && return 0
    for _a in awk "${BB:+$BB awk}"; do
        [ -n "$_a" ] || continue
        # shellcheck disable=SC2086
        if [ "$(echo ok | $_a '{ print $1 }' 2>/dev/null)" = ok ]; then
            AWK="$_a"
            return 0
        fi
    done
    return 1
}

# Mount points at or below $1, deepest first (exact path match, no regex).
# Fails closed: non-zero when /proc/mounts is unreadable or awk is unavailable.
mounts_under() {
    { [ -r "$PROC_MOUNTS" ] && pick_awk; } || return 1
    # shellcheck disable=SC2086
    $AWK -v p="$1" '
        $2 == p || index($2, p "/") == 1 { m[n++] = $2 }
        END {
            for (i = 0; i < n; i++)
                for (j = i + 1; j < n; j++)
                    if (length(m[j]) > length(m[i])) { t = m[i]; m[i] = m[j]; m[j] = t }
            for (i = 0; i < n; i++) print m[i]
        }' "$PROC_MOUNTS"
}

# Independent of awk: 0 only when $PROC_MOUNTS is readable and lists no mount
# point at or below $1 (fixed-string match; grep status 1 = no match).
no_mounts_at_or_under() {
    [ -r "$PROC_MOUNTS" ] || return 1
    _grep=grep
    command -v grep >/dev/null 2>&1 || _grep="${BB:+$BB grep}"
    [ -n "$_grep" ] || return 1
    # shellcheck disable=SC2086
    $_grep -F -e " $1 " -e " $1/" "$PROC_MOUNTS" >/dev/null 2>&1
    [ "$?" -eq 1 ]
}

# Unmount everything at or below $1, looping while anything is still mounted
# (stacked binds need several passes). 0 only when both checks see nothing.
unmount_under() {
    _pass=0
    while [ "$_pass" -lt 5 ]; do
        _mnts=$(mounts_under "$1") || return 1
        if [ -z "$_mnts" ]; then
            no_mounts_at_or_under "$1"
            return
        fi
        for _mnt in $_mnts; do
            progress "Unmounting: $_mnt"
            $BB umount "$_mnt" 2>/dev/null \
                || $BB umount -l "$_mnt" 2>/dev/null \
                || /system/bin/umount -l "$_mnt" 2>/dev/null || true
        done
        _pass=$((_pass + 1))
    done
    _mnts=$(mounts_under "$1") || return 1
    [ -z "$_mnts" ] && no_mounts_at_or_under "$1"
}

# --- mount lock (M16) --------------------------------------------------------
# One copy each in fluxlinux_chroot.sh (ensure_mounts), stop_*_gui.sh and
# uninstall_*_chroot.sh; keep them identical. A mkdir lock inside the
# (root-owned) rootfs; the holder records "pid boot_id epoch" in ./owner. A lock
# is stale when its holder is gone (or is no FluxLinux script: pid reuse), it
# is from an earlier boot (the dir lives on /data), it is older than 30 s
# (holders keep it for seconds), or it stayed owner-less for ~3 s (holder
# killed between mkdir and the owner write). Stale locks are broken under a
# second mkdir mutex and re-checked there, so only one waiter breaks a lock and
# nobody breaks a fresh one.
lock_owner_now() {
    printf '%s %s %s\n' "$$" \
        "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" "$(date +%s 2>/dev/null)"
}

# 0 when lock dir $1 is stale; $2 = consecutive polls it was seen owner-less.
lock_is_stale() {
    _rec=$(cat "$1/owner" 2>/dev/null)
    if [ -z "$_rec" ]; then
        [ "$2" -ge 15 ]
        return
    fi
    _lpid=${_rec%% *}
    _rest=${_rec#* }
    _lboot=${_rest%% *}
    _lts=${_rest#* }
    [ "$_lboot" = "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" ] || return 0
    case "$_lts" in ''|*[!0-9]*) return 0 ;; esac
    _now=$(date +%s 2>/dev/null)
    case "$_now" in
        ''|*[!0-9]*) ;;
        *) [ $((_now - _lts)) -gt 30 ] && return 0 ;;
    esac
    case "$_lpid" in ''|*[!0-9]*) return 0 ;; esac
    [ -d "/proc/$_lpid" ] || return 0
    grep -q -e fluxlinux_chroot -e _gui.sh -e _chroot.sh "/proc/$_lpid/cmdline" 2>/dev/null \
        || return 0
    return 1
}

mount_lock() {
    _lk="$1/.flux_mount.lock"
    _lw=0
    _empty=0
    _bw=0
    while ! mkdir "$_lk" 2>/dev/null; do
        [ "$_lw" -ge 60 ] && return 1
        _lw=$((_lw + 1))
        if [ -s "$_lk/owner" ]; then _empty=0; else _empty=$((_empty + 1)); fi
        if lock_is_stale "$_lk" "$_empty"; then
            if mkdir "$_lk.break" 2>/dev/null; then
                _bw=0
                # Re-check under the mutex: another waiter may have replaced the lock;
                # an owner-less one must stay owner-less (a fresh holder writes at once).
                [ -s "$_lk/owner" ] || sleep 0.3 2>/dev/null || sleep 1
                if lock_is_stale "$_lk" "$_empty"; then
                    rm -rf "$_lk" 2>/dev/null
                    if mkdir "$_lk" 2>/dev/null; then
                        lock_record "$_lk"
                        rmdir "$_lk.break" 2>/dev/null
                        return 0
                    fi
                fi
                rmdir "$_lk.break" 2>/dev/null
                _empty=0
                continue
            fi
            # A breaker holds the mutex for well under a second; a leftover one is stale.
            _bw=$((_bw + 1))
            [ "$_bw" -ge 10 ] && { rmdir "$_lk.break" 2>/dev/null; _bw=0; }
        fi
        sleep 0.2 2>/dev/null || sleep 1
    done
    lock_record "$_lk"
}

# The owner record appears atomically (temp + rename), never half-written.
lock_record() {
    lock_owner_now > "$1/owner.$$" && mv -f "$1/owner.$$" "$1/owner"
}

# Release only a lock this process still owns (a stale-broken one may be another's now).
mount_unlock() {
    _rec=$(cat "$1/.flux_mount.lock/owner" 2>/dev/null)
    [ "${_rec%% *}" = "$$" ] && rm -rf "$1/.flux_mount.lock" 2>/dev/null
    return 0
}
# --- end mount lock -----------------------------------------------------------

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

# Pick a find that supports -depth -delete: toybox first (BusyBox find ignores
# -xdev under -depth/-delete). Probes in $PROBE_BASE, never in /data/local/tmp.
pick_find() {
    case "$PROBE_BASE" in
        ""|.|/data/local/tmp|/data/local/tmp/*) return 1 ;;
    esac
    [ -d "$PROBE_BASE" ] && [ ! -L "$PROBE_BASE" ] || return 1
    _probe="$PROBE_BASE/.flux_findprobe.$$"
    for _f in /system/bin/find find "${BB:+$BB find}"; do
        [ -n "$_f" ] || continue
        rm -rf "$_probe" 2>/dev/null
        { mkdir "$_probe" && mkdir "$_probe/d"; } 2>/dev/null || return 1
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

# Delete tombstone $1; success = the tree is gone. Fails closed when anything is
# (or might be) mounted at or below it right before the delete.
delete_tree() {
    if [ -z "$FIND" ] && ! pick_find; then
        error "No usable find -depth -delete; refusing an unsafe delete of $1"
        return 1
    fi
    if ! no_mounts_at_or_under "$1"; then
        error "CRITICAL: $1 has a mount below it (or $PROC_MOUNTS is unreadable) — not deleting it"
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
        # mkdir never follows a planted symlink (it fails with EEXIST instead).
        [ -L "$_gp/.flux_uninstalling" ] && rm -f "$_gp/.flux_uninstalling"
        [ -e "$_gp/.flux_uninstalling" ] || mkdir "$_gp/.flux_uninstalling" 2>/dev/null || true
        rm -f "$_gp/.flux_configured" "$_gp/.flux_extract_ok" 2>/dev/null || true
        # Let an in-flight ensure_mounts finish; later ones see the marker.
        if mount_lock "$_gp"; then
            mount_unlock "$_gp"
        else
            progress "Mount lock busy; continuing (mount checks still apply)"
        fi
    fi
    progress "Checking for stalled processes..."
    kill_rooted "$_gp"
    progress "Unmounting filesystems under $_gp..."
    if ! unmount_under "$_gp"; then
        error "CRITICAL: cannot confirm nothing is mounted under $_gp — nothing deleted:"
        grep -F " $_gp" "$PROC_MOUNTS" 2>/dev/null
        error "Reboot the device, then uninstall again."
        return 1
    fi
    if [ -d "$_gp" ]; then
        _new="$_gp.deleting.$$"
        # A pre-existing name (dir or planted symlink) would make mv move the
        # rootfs into it: refuse instead.
        if [ -e "$_new" ] || [ -L "$_new" ]; then
            error "Refusing: $_new already exists; nothing deleted"
            return 1
        fi
        # Nothing is mounted at $_gp, so this is a plain same-directory rename.
        mv "$_gp" "$_new" || {
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
            error "CRITICAL: cannot confirm nothing is mounted under $_tomb — not deleting it:"
            grep -F " $_tomb" "$PROC_MOUNTS" 2>/dev/null
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

if [ "$(id -u)" != "0" ]; then
    error "This script must be run as root."
    exit 1
fi

if [ -z "$GUESTPATH" ]; then
    error "FLUX_CHROOT is not set."
    exit 1
fi

case "$GUESTPATH" in
    /data/local/tmp/chrootDebian13|/data/local/tmp/chrootAlpine|\
    /data/local/tmp/chrootFedora|/data/local/tmp/chrootVoid|\
    /data/local/tmp/chrootOpenSUSE|/data/local/tmp/chrootDeepin|\
    /data/local/tmp/chrootChimera|/data/local/tmp/chrootManjaro|\
    /data/local/tmp/chrootUbuntu|/data/local/tmp/chrootKali|\
    /data/local/tmp/chrootParrot|/data/local/tmp/chrootArch) ;;
    *)
        error "Refusing to remove unexpected path: $GUESTPATH"
        exit 1
        ;;
esac

progress "Starting Uninstallation of guest chroot..."
progress "Target: $GUESTPATH"

# resolve root BusyBox (manager built-in; NDK module not required)
_rr=""
for _c in \
  "${FLUX_RESOLVE_BB:-}" \
  "$(dirname "$0")/resolve_bb.sh"
do
  # M14: never source anything from shell-writable /data/local/tmp.
  case "$_c" in /data/local/tmp/*) continue ;; esac
  [ -n "$_c" ] && [ -f "$_c" ] && _rr="$_c" && break
done
if [ -n "$_rr" ]; then
  # shellcheck disable=SC1090
  . "$_rr"
  resolve_bb || true
fi
if [ -z "${BB:-}" ]; then
  if [ -n "${FLUX_BB:-}" ] && [ -x "$FLUX_BB" ] &&
     "$FLUX_BB" --list >/dev/null 2>&1; then
    case "$FLUX_BB" in /data/local/tmp/*) ;; *) BB="$FLUX_BB" ;; esac
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

if remove_rootfs "$GUESTPATH"; then
    _result=success
    success "Uninstallation Complete!"
else
    _result=failure
    error "Uninstallation FAILED — the rootfs was not (fully) removed."
fi

_cb="${FLUX_DISTRO_ID:-guest_chroot}"
am start -a android.intent.action.VIEW \
    -d "fluxlinux://callback?result=${_result}&name=distro_uninstall_${_cb}" \
    >/dev/null 2>&1 || true
[ "$_result" = success ]

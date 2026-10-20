#!/system/bin/sh
# stop_debian13_gui.sh — root: stop XFCE in Debian chroot + host X11 sockets
# Does NOT pkill proot (proot stop is stop_gui.sh only).

DEBIANPATH="${DEBIANPATH:-/data/local/tmp/chrootDebian13}"
if [ -z "${TARGET_PREFIX:-}" ]; then
  if [ -n "${FLUX_PREFIX:-}" ]; then
    TARGET_PREFIX="$FLUX_PREFIX"
  elif [ -n "${FLUX_PACKAGE:-}" ]; then
    TARGET_PREFIX="/data/data/${FLUX_PACKAGE}/files/usr"
  elif [ -d /data/data/com.zenithblue.fluxlinux/files/usr ]; then
    TARGET_PREFIX="/data/data/com.zenithblue.fluxlinux/files/usr"
  else
    TARGET_PREFIX="/data/data/com.ivarna.fluxlinux/files/usr"
  fi
fi

echo "========================================"
echo "FluxLinux: Stopping Chroot XFCE"
echo "========================================"

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
  # sidecar missing (desktop/uninstall/staged setup) — same B1 walk as resolve_bb
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
if [ -z "${BB:-}" ]; then
  echo "FluxLinux: ERROR — root-capable busybox not found" >&2
  exit 1
fi


echo "[1/4] Kill XFCE in chroot..."
if [ -n "$BB" ] && [ -d "$DEBIANPATH" ]; then
  $BB chroot "$DEBIANPATH" /bin/su - root -c \
    "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel plasmashell kwin_x11 startplasma-x11 plasma_session ksmserver dbus-launch dbus-daemon 2>/dev/null; true" \
    >/dev/null 2>&1
fi

echo "[2/4] Stop Termux X11 host processes..."
# Restore loader write bits for redeploy
chmod 0700 "$TARGET_PREFIX/libexec/termux-x11" 2>/dev/null || true
chmod 0600 "$TARGET_PREFIX/libexec/termux-x11/loader.apk" 2>/dev/null || true
pkill -9 -f "termux-x11" 2>/dev/null || true
pkill -9 -f "app_process.*termux-x11" 2>/dev/null || true
killall -9 Xwayland 2>/dev/null || true
rm -rf "$TARGET_PREFIX/tmp/.X11-unix" "$TARGET_PREFIX/tmp/.X0-lock" "$TARGET_PREFIX/tmp/.X1-lock" 2>/dev/null || true

# awk from PATH, else the root BusyBox applet. Non-zero when neither works.
AWK=""
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

# 0 while any host process is rooted at the chroot (a terminal tab, apt, a
# component run...) or a fluxlinux_chroot.sh session registered by ensure_mounts
# has not entered it yet. Also 0 when usage cannot be determined (fail closed:
# the caller then keeps the mounts).
chroot_in_use() {
  _rl=readlink
  command -v readlink >/dev/null 2>&1 || _rl="$BB readlink"
  if pick_awk; then
    # shellcheck disable=SC2086
    ls -l /proc/[0-9]*/root 2>/dev/null | $AWK -v p="$DEBIANPATH" '
      $(NF-1) == "->" && $NF == p { f = 1 }
      END { if (NR == 0) exit 2; exit f ? 0 : 1 }'
    case $? in 1) ;; *) return 0 ;; esac
  else
    # shellcheck disable=SC2086
    [ -n "$($_rl /proc/self/root 2>/dev/null)" ] || return 0
    for _r in /proc/[0-9]*/root; do
      # shellcheck disable=SC2086
      [ "$($_rl "$_r" 2>/dev/null)" = "$DEBIANPATH" ] && return 0
    done
  fi
  for _s in "$DEBIANPATH"/.flux_sessions/*; do
    [ -f "$_s" ] || continue
    _p=${_s##*/}
    if [ -d "/proc/$_p" ] && grep -q fluxlinux_chroot "/proc/$_p/cmdline" 2>/dev/null; then
      return 0
    fi
    # shellcheck disable=SC2086
    [ "$($_rl "/proc/$_p/root" 2>/dev/null)" = "$DEBIANPATH" ] && return 0
    rm -f "$_s" 2>/dev/null
  done
  return 1
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

echo "[3/4] Unmount chroot binds (best-effort)..."
if [ -n "$BB" ] && [ -d "$DEBIANPATH" ]; then
  # The X11 bind belongs to the desktop only.
  $BB umount "$DEBIANPATH/tmp/.X11-unix" 2>/dev/null \
    || $BB umount -l "$DEBIANPATH/tmp/.X11-unix" 2>/dev/null || true
  # Give the killed XFCE processes a moment to exit before deciding.
  _w=0
  while chroot_in_use && [ "$_w" -lt 10 ]; do
    sleep 0.2 2>/dev/null || sleep 1
    _w=$((_w + 1))
  done
  # M16: lazy unmount would pull /proc, /dev, /sys and /sdcard out from under
  # open chroot sessions (broken ptys, /dev/null turning into a regular file).
  # Decide and unmount under the mount lock so a session can't start in between.
  if ! mount_lock "$DEBIANPATH"; then
    echo "FluxLinux: chroot mount lock busy — keeping its mounts"
  elif chroot_in_use; then
    mount_unlock "$DEBIANPATH"
    echo "FluxLinux: chroot still in use (or usage unknown) — keeping its mounts"
  else
    for m in \
      "$DEBIANPATH/mnt/host-tmp" \
      "$DEBIANPATH/sdcard" \
      "$DEBIANPATH/dev/shm" \
      "$DEBIANPATH/dev/pts" \
      "$DEBIANPATH/proc" \
      "$DEBIANPATH/sys" \
      "$DEBIANPATH/dev"
    do
      $BB umount "$m" 2>/dev/null || $BB umount -l "$m" 2>/dev/null || true
    done
    mount_unlock "$DEBIANPATH"
  fi
fi

echo "[4/4] Done (PulseAudio stopped from app-uid wrapper if used)"
echo "========================================"
exit 0

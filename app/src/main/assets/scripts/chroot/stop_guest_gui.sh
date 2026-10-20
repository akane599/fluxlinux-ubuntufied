#!/system/bin/sh
# stop_guest_gui.sh — root: stop XFCE in guest chroot + host X11 sockets
# Does NOT pkill proot (proot stop is stop_gui.sh only).

CHROOT_ROOT="${CHROOT_ROOT:-${FLUX_CHROOT:-/data/local/tmp/chrootGuest}}"
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
if [ -n "$BB" ] && [ -d "$CHROOT_ROOT" ]; then
  $BB chroot "$CHROOT_ROOT" /bin/su - root -c \
    "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel dbus-launch dbus-daemon 2>/dev/null; true" \
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
    ls -l /proc/[0-9]*/root 2>/dev/null | $AWK -v p="$CHROOT_ROOT" '
      $(NF-1) == "->" && $NF == p { f = 1 }
      END { if (NR == 0) exit 2; exit f ? 0 : 1 }'
    case $? in 1) ;; *) return 0 ;; esac
  else
    # shellcheck disable=SC2086
    [ -n "$($_rl /proc/self/root 2>/dev/null)" ] || return 0
    for _r in /proc/[0-9]*/root; do
      # shellcheck disable=SC2086
      [ "$($_rl "$_r" 2>/dev/null)" = "$CHROOT_ROOT" ] && return 0
    done
  fi
  for _s in "$CHROOT_ROOT"/.flux_sessions/*; do
    [ -f "$_s" ] || continue
    _p=${_s##*/}
    if [ -d "/proc/$_p" ] && grep -q fluxlinux_chroot "/proc/$_p/cmdline" 2>/dev/null; then
      return 0
    fi
    # shellcheck disable=SC2086
    [ "$($_rl "/proc/$_p/root" 2>/dev/null)" = "$CHROOT_ROOT" ] && return 0
    rm -f "$_s" 2>/dev/null
  done
  return 1
}

# Shared with fluxlinux_chroot.sh ensure_mounts: a mkdir lock inside the
# (root-owned) rootfs, so no session can mount/register between the in-use check
# and the unmounts below. A dead holder's lock is broken.
mount_lock() {
  _lk="$1/.flux_mount.lock"
  _lw=0
  while ! mkdir "$_lk" 2>/dev/null; do
    [ "$_lw" -ge 60 ] && return 1
    _lo=$(cat "$_lk/pid" 2>/dev/null)
    if { [ -n "$_lo" ] || [ "$_lw" -ge 50 ]; } && ! kill -0 "$_lo" 2>/dev/null; then
      rm -rf "$_lk" 2>/dev/null
    else
      sleep 0.2 2>/dev/null || sleep 1
    fi
    _lw=$((_lw + 1))
  done
  echo "$$" > "$_lk/pid"
}
mount_unlock() { rm -rf "$1/.flux_mount.lock" 2>/dev/null; }

echo "[3/4] Unmount chroot binds (best-effort)..."
if [ -n "$BB" ] && [ -d "$CHROOT_ROOT" ]; then
  # The X11 bind belongs to the desktop only.
  $BB umount "$CHROOT_ROOT/tmp/.X11-unix" 2>/dev/null \
    || $BB umount -l "$CHROOT_ROOT/tmp/.X11-unix" 2>/dev/null || true
  # Give the killed XFCE processes a moment to exit before deciding.
  _w=0
  while chroot_in_use && [ "$_w" -lt 10 ]; do
    sleep 0.2 2>/dev/null || sleep 1
    _w=$((_w + 1))
  done
  # M16: lazy unmount would pull /proc, /dev, /sys and /sdcard out from under
  # open chroot sessions (broken ptys, /dev/null turning into a regular file).
  # Decide and unmount under the mount lock so a session can't start in between.
  if ! mount_lock "$CHROOT_ROOT"; then
    echo "FluxLinux: chroot mount lock busy — keeping its mounts"
  elif chroot_in_use; then
    mount_unlock "$CHROOT_ROOT"
    echo "FluxLinux: chroot still in use (or usage unknown) — keeping its mounts"
  else
    for m in \
      "$CHROOT_ROOT/mnt/host-tmp" \
      "$CHROOT_ROOT/sdcard" \
      "$CHROOT_ROOT/dev/shm" \
      "$CHROOT_ROOT/dev/pts" \
      "$CHROOT_ROOT/proc" \
      "$CHROOT_ROOT/sys" \
      "$CHROOT_ROOT/dev"
    do
      $BB umount "$m" 2>/dev/null || $BB umount -l "$m" 2>/dev/null || true
    done
    mount_unlock "$CHROOT_ROOT"
  fi
fi

echo "[4/4] Done (PulseAudio stopped from app-uid wrapper if used)"
echo "========================================"
exit 0

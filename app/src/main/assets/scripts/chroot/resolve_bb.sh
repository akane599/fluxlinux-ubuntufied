#!/system/bin/sh
# Shared BusyBox resolver for FluxLinux chroot.
# Magisk / KernelSU / APatch built-in busybox is enough — NDK module not required.
# Safe to source under set -u. No set -e.
# M14: BusyBox is used in place from root-owned locations. Never pick or create a copy in a
# shell-writable dir (/data/local/tmp is 0771 shell:shell — adb/Shizuku could swap it).

bb_has() {
  "$1" --list 2>/dev/null | tr ' \t' '\n' | grep -qx "$2"
}

bb_ok() {
  [ -n "${1:-}" ] && [ -x "$1" ] || return 1
  case "$1" in *com.termux*|*fluxlinux*|*nativecode*|/data/local/tmp/*) return 1 ;; esac
  bb_has "$1" chroot && bb_has "$1" mount
}

resolve_bb() {
  BB=""
  if [ -n "${FLUX_BB:-}" ] && bb_ok "$FLUX_BB"; then
    BB="$FLUX_BB"
  else
    for path in \
      /data/adb/ksu/bin/busybox \
      /data/adb/ap/bin/busybox \
      /data/adb/magisk/busybox \
      /data/adb/modules/busybox-ndk/system/xbin/busybox \
      /data/adb/modules/busybox-ndk/system/bin/busybox \
      /debug_ramdisk/busybox \
      /sbin/busybox
    do
      if bb_ok "$path"; then
        BB="$path"
        break
      fi
    done
    if [ -z "$BB" ]; then
      _det=$(command -v busybox 2>/dev/null || true)
      if [ -n "${_det:-}" ] && bb_ok "$_det"; then
        BB="$_det"
      fi
    fi
    if [ -z "$BB" ]; then
      for path in \
        /system/xbin/busybox \
        /system/bin/busybox
      do
        if bb_ok "$path"; then
          BB="$path"
          break
        fi
      done
    fi
  fi
  [ -n "$BB" ] || return 1
  return 0
}

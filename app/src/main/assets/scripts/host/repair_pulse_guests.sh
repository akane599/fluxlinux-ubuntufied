#!/bin/bash
# repair_pulse_guests.sh — run setup_pulse_guest.sh inside installed guests.
# Host Pulse stays in the app PREFIX. This only writes guest client config
# and installs named Pulse *client* packages when pactl is missing.
# Host Pulse serves only a unix socket in its tmp ($PREFIX/tmp/pulse-native):
# proot guests reach it at /tmp/pulse-native (--shared-tmp), chroot guests at
# /mnt/host-tmp/pulse-native (fluxlinux_chroot.sh bind). Never TCP 4713.

PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
_HOST_ENV="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}/etc/fluxlinux-host.env"
[ -r "$_HOST_ENV" ] && . "$_HOST_ENV"

PKG="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
export TERMUX_APP__PACKAGE_NAME="$PKG"
export TERMUX__PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
export TERMUX__HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
export PREFIX="${PREFIX:-$TERMUX__PREFIX}"
export HOME="${TERMUX__HOME}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin${PATH:+:$PATH}"

COMMON="${HOME}/flux_guest_common.sh"
REPAIR="${HOME}/setup_pulse_guest.sh"
MODE="${1:-all}"

if [ ! -f "$COMMON" ] || [ ! -f "$REPAIR" ]; then
  echo "FluxLinux: [AUDIO] FAIL guest repair scripts not deployed"
  exit 0
fi

mkdir -p "$TMPDIR" 2>/dev/null || true
cp -f "$COMMON" "$TMPDIR/flux_guest_common.sh"
cp -f "$REPAIR" "$TMPDIR/setup_pulse_guest.sh"
chmod 755 "$TMPDIR/setup_pulse_guest.sh" 2>/dev/null || true

repair_proot() {
  _pd="$PREFIX/var/lib/proot-distro"
  command -v proot-distro >/dev/null 2>&1 || {
    echo "FluxLinux: [AUDIO] skip proot repair (proot-distro missing)"
    return 0
  }
  for _layout in containers installed-rootfs; do
    [ -d "$_pd/$_layout" ] || continue
    for _dir in "$_pd/$_layout"/*; do
      [ -d "$_dir" ] || continue
      _name=$(basename "$_dir")
      case "$_name" in
        .*|"") continue ;;
      esac
      if [ ! -d "$_dir/rootfs/etc" ] && [ ! -d "$_dir/etc" ]; then
        continue
      fi
      echo "FluxLinux: [AUDIO] repair proot $_name"
      # Same env -i as ProotCommandBuilder.guestLoginEnv — no host PATH/PREFIX.
      _gp="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
      proot-distro login "$_name" --shared-tmp --user root -- \
        env -i HOME=/root USER=root LOGNAME=root \
          TERM="${TERM:-xterm-256color}" LANG=C \
          TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp PATH="$_gp" \
          PULSE_SERVER=unix:/tmp/pulse-native \
          /bin/sh /tmp/setup_pulse_guest.sh \
        || echo "FluxLinux: [AUDIO] WARN repair failed proot $_name"
    done
  done
}

_CHROOT_PATHS="
/data/local/tmp/chrootDebian13
/data/local/tmp/chrootAlpine
/data/local/tmp/chrootFedora
/data/local/tmp/chrootVoid
/data/local/tmp/chrootOpenSUSE
/data/local/tmp/chrootDeepin
/data/local/tmp/chrootChimera
/data/local/tmp/chrootManjaro
/data/local/tmp/chrootUbuntu
/data/local/tmp/chrootKali
/data/local/tmp/chrootParrot
/data/local/tmp/chrootArch
"

# --- chroot client-config migration (runs as root on the host) ---
# Older chroot guests were configured for tcp:127.0.0.1, which host Pulse no
# longer serves (KDE xstartup: bare 127.0.0.1). Rewrite only those exact
# FluxLinux-written lines to the server list the guest writer uses
# (flux_guest_common.sh FLUX_PULSE_SERVERS); a chroot reaches the host socket
# through its second entry, /mnt/host-tmp/pulse-native. Root on the host must
# never follow a guest-planted link, so every path component under the rootfs
# has to be a real directory / regular file. Idempotent.
CHROOT_PULSE_SERVERS="unix:/tmp/pulse-native unix:/mnt/host-tmp/pulse-native"
# 0 when no component of <rootfs>/<rel> (after <rootfs>) is a symlink.
chroot_path_no_links() {
  _cr=$1
  _rest=$2
  _cur=$_cr
  while [ -n "$_rest" ]; do
    case "$_rest" in
      */*) _seg=${_rest%%/*}; _rest=${_rest#*/} ;;
      *) _seg=$_rest; _rest="" ;;
    esac
    [ -n "$_seg" ] || continue
    _cur="$_cur/$_seg"
    [ -L "$_cur" ] && return 1
  done
  return 0
}
migrate_chroot_file() {
  _cr=$1
  _f="$_cr/$2"
  chroot_path_no_links "$_cr" "$2" || return 0
  [ -f "$_f" ] || return 0
  grep -q '127\.0\.0\.1' "$_f" 2>/dev/null || return 0
  rm -f "$_f.flux-tmp"
  sed -e "s#^export PULSE_SERVER=tcp:127\.0\.0\.1\$#export PULSE_SERVER=\"$CHROOT_PULSE_SERVERS\"#" \
      -e "s#^export PULSE_SERVER=127\.0\.0\.1\$#export PULSE_SERVER=\"$CHROOT_PULSE_SERVERS\"#" \
      -e "s#^PULSE_SERVER=tcp:127\.0\.0\.1\$#PULSE_SERVER=\"$CHROOT_PULSE_SERVERS\"#" \
      -e "s#^default-server = tcp:127\.0\.0\.1\$#default-server = $CHROOT_PULSE_SERVERS#" \
      "$_f" > "$_f.flux-tmp" 2>/dev/null \
    && cat "$_f.flux-tmp" > "$_f" \
    && echo "FluxLinux: [AUDIO] migrated chroot $2 to unix socket"
  rm -f "$_f.flux-tmp"
}
migrate_chroot_guest() {
  for _rel in etc/profile.d/flux-pulse.sh etc/environment \
              etc/pulse/client.conf etc/pulse/client.conf.d/99-fluxlinux.conf \
              home/flux/.vnc/xstartup; do
    migrate_chroot_file "$1" "$_rel"
  done
}
# --- end chroot client-config migration ---

repair_chroot_as_root() {
  # M14: only the verified helper in the root-only stage (RootShell.ensureChrootHelper),
  # never the shell-writable /data/local/tmp copy.
  _helper="/data/adb/fluxlinux/$PKG/fluxlinux_chroot.sh"
  for _path in $_CHROOT_PATHS; do
    [ -d "$_path/etc" ] || continue
    [ -L "$_path" ] && continue
    # Host-side first: works even when the helper or the guest shell fails.
    migrate_chroot_guest "$_path"
    if [ ! -f "$_helper" ]; then
      echo "FluxLinux: [AUDIO] skip chroot client install (helper missing) $_path"
      continue
    fi
    # Staging dir must be the guest's own /tmp, never a link out of the rootfs.
    chroot_path_no_links "$_path" tmp || {
      echo "FluxLinux: [AUDIO] WARN skip chroot $_path (/tmp is a link)"
      continue
    }
    mkdir -p "$_path/tmp" 2>/dev/null || true
    rm -f "$_path/tmp/flux_guest_common.sh" "$_path/tmp/setup_pulse_guest.sh"
    cp -f "$COMMON" "$_path/tmp/flux_guest_common.sh"
    cp -f "$REPAIR" "$_path/tmp/setup_pulse_guest.sh"
    chmod 755 "$_path/tmp/setup_pulse_guest.sh" 2>/dev/null || true
    echo "FluxLinux: [AUDIO] repair chroot $_path"
    FLUX_CHROOT="$_path" FLUX_PACKAGE="$PKG" \
      /system/bin/sh "$_helper" sh --user root -- \
        'PULSE_SERVER=unix:/mnt/host-tmp/pulse-native /bin/sh /tmp/setup_pulse_guest.sh' \
      || echo "FluxLinux: [AUDIO] WARN repair failed chroot $_path"
  done
}

if [ "$MODE" != "chroot-only" ]; then
  repair_proot
fi

if [ "$MODE" = "chroot-only" ] || [ "$(id -u)" = "0" ]; then
  if [ "$(id -u)" = "0" ]; then
    repair_chroot_as_root
  fi
  exit 0
fi

_su=""
for _c in /system/bin/su /sbin/su /system/xbin/su; do
  if [ -x "$_c" ]; then
    _su="$_c"
    break
  fi
done
if [ -z "$_su" ] && command -v su >/dev/null 2>&1; then
  _su=$(command -v su)
fi

if [ -n "$_su" ]; then
  echo "FluxLinux: [AUDIO] chroot repair via su"
  "$_su" -c "HOME='$HOME' PREFIX='$PREFIX' TERMUX__PREFIX='$PREFIX' TERMUX__HOME='$HOME' TERMUX_APP__PACKAGE_NAME='$PKG' /system/bin/sh '$0' chroot-only" \
    || echo "FluxLinux: [AUDIO] WARN chroot repair su failed"
else
  echo "FluxLinux: [AUDIO] skip chroot repair (no su)"
fi

exit 0

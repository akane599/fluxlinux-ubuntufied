#!/data/data/com.ivarna.fluxlinux/files/usr/bin/bash
# stop_gui_chroot.sh — app-uid: stop X11/VirGL + root stop_*_gui.sh (Pulse stays up)
# Arg1 / FLUX_CHROOT_DISTRO: debian13_chroot|alpine_chroot|fedora_chroot|void_chroot|opensuse_chroot|deepin_chroot|chimera_chroot|manjaro_chroot|ubuntu_chroot|kali_chroot|parrot_chroot|archlinux_chroot.
# Does NOT pkill proot.

DISTRO_HINT="${1:-${FLUX_CHROOT_DISTRO:-debian13_chroot}}"

PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
_HOST_ENV="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}/etc/fluxlinux-host.env"
[ -r "$_HOST_ENV" ] && . "$_HOST_ENV"

PKG="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
TERMUX_PREFIX="${TERMUX__PREFIX:-/data/data/$PKG/files/usr}"
TERMUX_HOME="${TERMUX__HOME:-/data/data/$PKG/files/home}"
export HOME="$TERMUX_HOME"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"

case "$DISTRO_HINT" in
  alpine|alpine_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootAlpine}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_alpine_gui.sh}"
    ;;
  fedora|fedora_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootFedora}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  void|void_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootVoid}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  opensuse|opensuse_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootOpenSUSE}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  deepin|deepin_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootDeepin}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  chimera|chimera_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootChimera}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  manjaro|manjaro_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootManjaro}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  ubuntu|ubuntu_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootUbuntu}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  kali|kali_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootKali}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  parrot|parrot_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootParrot}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  archlinux|archlinux_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootArch}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_guest_gui.sh}"
    ;;
  *)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootDebian13}"
    ROOT_STOP_NAME="${ROOT_STOP_NAME:-stop_debian13_gui.sh}"
    ;;
esac
ROOT_STOP_SCRIPT="$TERMUX_HOME/$ROOT_STOP_NAME"
RESOLVER_SRC="$TERMUX_HOME/resolve_bb.sh"
# M14: root runs only copies in the root-only stage (root:root 0700; ChrootPaths.ROOT_STAGE_DIR).
ROOT_STAGE_PARENT="/data/adb/fluxlinux"
ROOT_STAGE="$ROOT_STAGE_PARENT/$PKG"
ROOT_STOP_STAGED="$ROOT_STAGE/$ROOT_STOP_NAME"
RESOLVER_STAGED="$ROOT_STAGE/resolve_bb.sh"

echo "========================================"
echo "FluxLinux: STOP XFCE (chroot mode)"
echo "  distro=$DISTRO_HINT path=$CHROOT_PATH"
echo "========================================"

if [ ! -f "$ROOT_STOP_SCRIPT" ]; then
  echo "FluxLinux: [WARN] missing $ROOT_STOP_SCRIPT — best-effort kill only"
else
  SU_BIN=su
  for s in /system/bin/su /system/xbin/su /sbin/su; do
    if [ -x "$s" ]; then SU_BIN="$s"; break; fi
  done
  "$SU_BIN" -c "( umask 077; mkdir -p '$ROOT_STAGE' ) && [ ! -L '$ROOT_STAGE_PARENT' ] && [ ! -L '$ROOT_STAGE' ] && \
      chown 0:0 '$ROOT_STAGE_PARENT' '$ROOT_STAGE' && chmod 0700 '$ROOT_STAGE_PARENT' '$ROOT_STAGE' || \
      { echo 'FluxLinux: [ERROR] cannot create root-only stage $ROOT_STAGE' >&2; exit 1; }; \
    _stage() { [ -f \"\$1\" ] || return 1; cp -f \"\$1\" \"\$2.stage.\$\$\" && chmod 0600 \"\$2.stage.\$\$\" && \
      mv -f \"\$2.stage.\$\$\" \"\$2\" || { rm -f \"\$2.stage.\$\$\"; return 1; }; }; \
    _stage '$ROOT_STOP_SCRIPT' '$ROOT_STOP_STAGED' || { echo 'FluxLinux: [ERROR] cannot stage $ROOT_STOP_NAME' >&2; exit 1; }; \
    _stage '$RESOLVER_SRC' '$RESOLVER_STAGED'; \
    rm -f '/data/local/tmp/$ROOT_STOP_NAME' /data/local/tmp/fluxlinux_resolve_bb.sh 2>/dev/null; \
    DEBIANPATH='$CHROOT_PATH' CHROOT_ROOT='$CHROOT_PATH' FLUX_CHROOT='$CHROOT_PATH' \
    TARGET_PREFIX='$TERMUX_PREFIX' \
    FLUX_BB='${FLUX_BB:-}' FLUX_RESOLVE_BB='$RESOLVER_STAGED' sh '$ROOT_STOP_STAGED'"
fi

echo "Stopping VirGL (app uid; Pulse stays running)..."
pkill -f "virgl_test_server" 2>/dev/null || true

pkill -9 -f "termux-x11" 2>/dev/null || true
pkill -9 -f "app_process.*termux-x11" 2>/dev/null || true

echo "Chroot GUI stop complete."
echo "========================================"
exit 0

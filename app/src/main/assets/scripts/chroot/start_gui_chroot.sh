#!/data/data/com.ivarna.fluxlinux/files/usr/bin/bash
# start_gui_chroot.sh — Launch XFCE4 in chroot (app-uid host + root guest)
# Host stack mirrors start_gui.sh (Pulse/VirGL/embedded X11).
# Arg1 / FLUX_CHROOT_DISTRO: debian13_chroot|alpine_chroot|fedora_chroot|void_chroot|opensuse_chroot|deepin_chroot|chimera_chroot|manjaro_chroot|ubuntu_chroot|kali_chroot|parrot_chroot|archlinux_chroot.
# NEVER am force-stop own package.

DISTRO_HINT="${1:-${FLUX_CHROOT_DISTRO:-debian13_chroot}}"

PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
_HOST_ENV="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}/etc/fluxlinux-host.env"
[ -r "$_HOST_ENV" ] && . "$_HOST_ENV"

PKG="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
TERMUX_PREFIX="${TERMUX__PREFIX:-/data/data/$PKG/files/usr}"
TERMUX_HOME="${TERMUX__HOME:-/data/data/$PKG/files/home}"
export HOME="$TERMUX_HOME"
export TMPDIR="${TMPDIR:-$TERMUX_PREFIX/tmp}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib:$TERMUX_PREFIX/opt/virglrenderer-android/lib"
export TERMUX_APP__PACKAGE_NAME="$PKG"
export TERMUX_X11_OVERRIDE_PACKAGE="$PKG"
export TERMUX__PREFIX="$TERMUX_PREFIX"
export TERMUX__HOME="$TERMUX_HOME"
export XKB_CONFIG_ROOT="$TERMUX_PREFIX/share/X11/xkb"

# Resolve chroot path + guest launcher from distro hint (env overrides win)
case "$DISTRO_HINT" in
  alpine|alpine_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootAlpine}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_alpine_gui.sh}"
    ;;
  fedora|fedora_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootFedora}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  void|void_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootVoid}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  opensuse|opensuse_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootOpenSUSE}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  deepin|deepin_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootDeepin}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  chimera|chimera_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootChimera}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  manjaro|manjaro_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootManjaro}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  ubuntu|ubuntu_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootUbuntu}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  kali|kali_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootKali}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  parrot|parrot_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootParrot}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  archlinux|archlinux_chroot)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootArch}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_guest_gui.sh}"
    ;;
  *)
    CHROOT_PATH="${CHROOT_PATH:-/data/local/tmp/chrootDebian13}"
    ROOT_GUI_NAME="${ROOT_GUI_NAME:-start_debian13_gui.sh}"
    ;;
esac
ROOT_GUI_SCRIPT="$TERMUX_HOME/$ROOT_GUI_NAME"
HELPER_SRC="$TERMUX_HOME/fluxlinux_chroot.sh"
RESOLVER_SRC="$TERMUX_HOME/resolve_bb.sh"
# M14: root runs only copies in a root-only dir (root:root 0700; same path as
# ChrootPaths.ROOT_STAGE_DIR). /data/local/tmp is shell-writable, so nothing is run from there.
ROOT_STAGE_PARENT="/data/adb/fluxlinux"
ROOT_STAGE="$ROOT_STAGE_PARENT/$PKG"
ROOT_GUI_STAGED="$ROOT_STAGE/$ROOT_GUI_NAME"
HELPER_STAGED="$ROOT_STAGE/fluxlinux_chroot.sh"
RESOLVER_STAGED="$ROOT_STAGE/resolve_bb.sh"

echo "========================================"
echo "FluxLinux: START XFCE (chroot mode)"
echo "  distro=$DISTRO_HINT path=$CHROOT_PATH"
echo "========================================"

# Preflight chroot
if [ ! -d "$CHROOT_PATH" ]; then
  echo "FluxLinux: ERROR — chroot not found at $CHROOT_PATH"
  echo "Install rooted distro from onboarding or Distros."
  exit 1
fi
if [ ! -f "$CHROOT_PATH/.flux_configured" ] && \
   [ ! -e "$CHROOT_PATH/usr/bin/startxfce4" ] && \
   [ ! -e "$CHROOT_PATH/usr/sbin/startxfce4" ]; then
  echo "FluxLinux: ERROR — chroot incomplete (no .flux_configured / startxfce4)."
  echo "Re-run base desktop install for the rooted distro."
  exit 1
fi
if [ ! -f "$ROOT_GUI_SCRIPT" ]; then
  echo "FluxLinux: ERROR — missing $ROOT_GUI_SCRIPT (HostScriptDeployer failed?)"
  exit 1
fi
export PULSE_RUNTIME_PATH="${HOME}/.pulse"
mkdir -p "$PULSE_RUNTIME_PATH" "$TMPDIR" 2>/dev/null

# Stale host graphics only — do NOT force-stop $PKG and do not kill Pulse.
pkill -f "virgl_test_server" 2>/dev/null || true
pkill -f termux-x11 2>/dev/null || true
pkill -f "app_process.*termux-x11" 2>/dev/null || true
sleep 1

# Host Pulse (app uid) before su. Supervisor prints FluxLinux: [AUDIO] …
if [ -x "$TERMUX_HOME/start_pulse_host.sh" ]; then
  bash "$TERMUX_HOME/start_pulse_host.sh" || true
elif command -v start_pulse_host.sh >/dev/null 2>&1; then
  start_pulse_host.sh || true
fi

# VirGL optional
if command -v virgl_test_server_android >/dev/null; then
  echo "FluxLinux: Starting VirGL server..."
  virgl_test_server_android --socket-path "$TERMUX_PREFIX/tmp/.virgl_test" >/dev/null 2>&1 &
  sleep 2
  test -S "${TMPDIR}/.virgl_test" && echo "FluxLinux: VirGL socket ready" \
    || echo "FluxLinux: [WARN] VirGL socket not found"
else
  echo "FluxLinux: VirGL unavailable; software GL in guest"
fi

# Embedded termux-x11 (same as proot start_gui.sh)
echo "FluxLinux: Starting termux-x11 server..."
export XDG_RUNTIME_DIR="$TMPDIR"
export DISPLAY=:0

chmod 0400 "$TERMUX_PREFIX/libexec/termux-x11/loader.apk" 2>/dev/null || true
chmod 0500 "$TERMUX_PREFIX/libexec/termux-x11" 2>/dev/null || true

if [ -L "$TERMUX_PREFIX/share/X11/xkb" ] && [ ! -e "$TERMUX_PREFIX/share/X11/xkb" ]; then
  rm -f "$TERMUX_PREFIX/share/X11/xkb"
  ln -s "$TERMUX_PREFIX/share/xkeyboard-config-2" "$TERMUX_PREFIX/share/X11/xkb"
fi

if [ -z "$TERMUX_X11_APK_PATH" ]; then
  # /system/bin/pm — termux-exec rewrites bare "pm" to $PREFIX/bin/pm (missing).
  TERMUX_X11_APK_PATH=$(/system/bin/pm path "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://')
fi
if [ -z "$TERMUX_X11_APK_PATH" ] || [ ! -f "$TERMUX_X11_APK_PATH" ]; then
  TERMUX_X11_APK_PATH=$(/system/bin/find /data/app -name "base.apk" -path "*$PKG*" 2>/dev/null | head -1)
fi
export TERMUX_X11_APK_PATH
echo "FluxLinux: APK path = $TERMUX_X11_APK_PATH"

# Prefer the package manager's read-only libXlorie.so (nativeLibraryDir, extractNativeLibs):
# Android 17 refuses System.load of a writable file, so the /data/data copy fails there.
NATIVE_LIB="$(dirname "$TERMUX_X11_APK_PATH")/lib/arm64/libXlorie.so"
[ -f "$NATIVE_LIB" ] || NATIVE_LIB="$(dirname "$TERMUX_X11_APK_PATH")/lib/arm/libXlorie.so"
if [ -n "$TERMUX_X11_APK_PATH" ] && [ -f "$NATIVE_LIB" ]; then
  export TERMUX_X11_LIB_PATH="$NATIVE_LIB"
  echo "FluxLinux: libXlorie.so = $NATIVE_LIB (read-only)"
else
  TERMUX_X11_LIB_PATH=""
fi

APP_LIB_DIR="/data/data/$PKG/lib"
mkdir -p "$APP_LIB_DIR" 2>/dev/null
# Fallback only: re-extract when the APK is newer, else an app update keeps running the old X server.
if [ -z "$TERMUX_X11_LIB_PATH" ] && { [ ! -f "$APP_LIB_DIR/libXlorie.so" ] || [ "$TERMUX_X11_APK_PATH" -nt "$APP_LIB_DIR/libXlorie.so" ]; } && [ -n "$TERMUX_X11_APK_PATH" ]; then
  echo "FluxLinux: Extracting libXlorie.so..."
  ( cd "$APP_LIB_DIR" && \
    unzip -o "$TERMUX_X11_APK_PATH" 'lib/arm64-v8a/libXlorie.so' 2>/dev/null && \
    mv -f lib/arm64-v8a/libXlorie.so . && rm -rf lib ) || \
  ( cd "$APP_LIB_DIR" && \
    unzip -o "$TERMUX_X11_APK_PATH" 'lib/armeabi-v7a/libXlorie.so' 2>/dev/null && \
    mv -f lib/armeabi-v7a/libXlorie.so . && rm -rf lib )
  touch "$APP_LIB_DIR/libXlorie.so" 2>/dev/null # unzip keeps the 1981 entry mtime
  chmod 0555 "$APP_LIB_DIR/libXlorie.so" 2>/dev/null || true # best effort: not writable
fi

rm -f "$TMPDIR/.X0-lock" "$TMPDIR/.X1-lock" "$TMPDIR/.tX0-lock" \
  "$TMPDIR/.X11-unix/X0" "$TMPDIR/.X11-unix/X1" 2>/dev/null || true
if [ -e "$TMPDIR/.X11-unix" ] && [ ! -d "$TMPDIR/.X11-unix" ]; then
  rm -f "$TMPDIR/.X11-unix" 2>/dev/null || true
fi
mkdir -p "$TMPDIR/.X11-unix" 2>/dev/null || {
  rm -rf "$TMPDIR/.X11-unix" 2>/dev/null || true
  mkdir -p "$TMPDIR/.X11-unix"
}
chmod 1777 "$TMPDIR" "$TMPDIR/.X11-unix" 2>/dev/null || true
_ctx=$(ls -Zd "$TERMUX_PREFIX" 2>/dev/null | awk '{print $1}')
if [ -n "$_ctx" ] && command -v chcon >/dev/null 2>&1; then
  chcon -R "$_ctx" "$TMPDIR" 2>/dev/null || chcon "$_ctx" "$TMPDIR" 2>/dev/null || true
fi

LD_LIBRARY_PATH="" LD_PRELOAD="" \
CLASSPATH="$TERMUX_PREFIX/libexec/termux-x11/loader.apk" \
TERMUX_X11_APK_PATH="$TERMUX_X11_APK_PATH" \
TERMUX_X11_LIB_PATH="$TERMUX_X11_LIB_PATH" \
TERMUX_X11_OVERRIDE_PACKAGE="$PKG" \
LANG=en_US.UTF-8 \
/system/bin/app_process / \
  --nice-name="termux-x11" com.termux.x11.Loader :0 -legacy-drawing &
XSERVER_PID=$!
# Wait up to 15s for the X socket; ready marker only once it exists and the server lives.
_i=0
while [ ! -S "$TMPDIR/.X11-unix/X0" ] && [ "$_i" -lt 30 ] && kill -0 "$XSERVER_PID" 2>/dev/null; do
  sleep 0.5
  _i=$((_i + 1))
done
if [ ! -S "$TMPDIR/.X11-unix/X0" ] || ! kill -0 "$XSERVER_PID" 2>/dev/null; then
  echo "FluxLinux: [ERROR] X server did not create $TMPDIR/.X11-unix/X0 within 15s (or exited)."
  exit 1
fi
echo "FluxLinux: host X0 socket ready (X server PID=$XSERVER_PID)"

echo "FluxLinux: Opening X11 activity..."
am start -n "$PKG/com.termux.x11.MainActivity" \
  --activity-single-top \
  --activity-clear-top 2>/dev/null || \
am start -n "$PKG/com.termux.x11.MainActivity" 2>/dev/null
sleep 1

# Root stage: as root, copy the guest launcher + helper + resolver from app-private HOME into
# the root-only stage (temp + rename, so a running helper never sees a half-written file),
# drop the old /data/local/tmp copies, then run from the stage.
echo "FluxLinux: Entering chroot as root..."
SU_BIN=""
for s in /system/bin/su /system/xbin/su /sbin/su; do
  if [ -x "$s" ]; then SU_BIN="$s"; break; fi
done
if [ -z "$SU_BIN" ]; then
  # still try su from PATH (KernelSU may hide path)
  SU_BIN=su
fi

# Pass HELPER + path env so guest GUI scripts use SSOT mounts
# DEBIANPATH = legacy name used by start_debian13_gui.sh
# CHROOT_ROOT  = name used by start_alpine_gui.sh
"$SU_BIN" -c "( umask 077; mkdir -p '$ROOT_STAGE' ) && [ ! -L '$ROOT_STAGE_PARENT' ] && [ ! -L '$ROOT_STAGE' ] && \
    chown 0:0 '$ROOT_STAGE_PARENT' '$ROOT_STAGE' && chmod 0700 '$ROOT_STAGE_PARENT' '$ROOT_STAGE' || \
    { echo 'FluxLinux: [ERROR] cannot create root-only stage $ROOT_STAGE' >&2; exit 1; }; \
  _stage() { [ -f \"\$1\" ] || return 1; cp -f \"\$1\" \"\$2.stage.\$\$\" && chmod 0600 \"\$2.stage.\$\$\" && \
    mv -f \"\$2.stage.\$\$\" \"\$2\" || { rm -f \"\$2.stage.\$\$\"; return 1; }; }; \
  _stage '$ROOT_GUI_SCRIPT' '$ROOT_GUI_STAGED' || { echo 'FluxLinux: [ERROR] cannot stage $ROOT_GUI_NAME' >&2; exit 1; }; \
  _stage '$HELPER_SRC' '$HELPER_STAGED'; \
  _stage '$RESOLVER_SRC' '$RESOLVER_STAGED'; \
  rm -f '/data/local/tmp/$ROOT_GUI_NAME' /data/local/tmp/fluxlinux_chroot.sh \
    /data/local/tmp/fluxlinux_resolve_bb.sh /data/local/tmp/flux_busybox 2>/dev/null; \
  DEBIANPATH='$CHROOT_PATH' CHROOT_ROOT='$CHROOT_PATH' FLUX_CHROOT='$CHROOT_PATH' \
  TARGET_PREFIX='$TERMUX_PREFIX' HELPER='$HELPER_STAGED' \
  FLUX_DESKTOP='${FLUX_DESKTOP:-xfce4}' FLUX_GPU_RUNTIME='${FLUX_GPU_RUNTIME:-}' \
  FLUX_BB='${FLUX_BB:-}' FLUX_RESOLVE_BB='$RESOLVER_STAGED' sh '$ROOT_GUI_STAGED'"
rc=$?
echo "FluxLinux: chroot GUI exit=$rc"
exit $rc

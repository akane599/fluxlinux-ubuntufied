#!/bin/bash
# start_pulse_host.sh — SSOT host PulseAudio supervisor (app uid only).
# Guests are clients of a unix socket in the app-private shared tmp
# ($PREFIX/tmp/pulse-native = guest /tmp/pulse-native under proot --shared-tmp,
# /mnt/host-tmp/pulse-native in chroot). No TCP listener: 127.0.0.1:4713 is
# reachable (and squattable) by every app. Never start as root or --system.
# Exit 0 even if audio fails so desktop start is not blocked.
# Invoked via libbash; shebang is a fallback for a raw exec.

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
export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# termux-exec makes `test -x` on /data/app jniLibs fail, so we would
# fall back to $PREFIX/bin and hit W^X EACCES. Drop it for this script.
unset LD_PRELOAD
# nativeLibraryDir: same folder as libproot.so / libbash.so (/proc/self/exe).
_nld=""
if [ -n "${PD_PROOT_BIN:-}" ]; then
  _nld="${PD_PROOT_BIN%/*}"
elif [ -n "${PD_PULSEAUDIO_BIN:-}" ]; then
  _nld="${PD_PULSEAUDIO_BIN%/*}"
else
  _exe=$(readlink -f /proc/self/exe 2>/dev/null || true)
  case "$_exe" in
    */libbash.so|*/libproot.so) _nld="${_exe%/*}" ;;
  esac
fi
export PATH="${_nld:+$_nld:}$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin"
# Never exec $PREFIX/bin/pulseaudio — targetSdk 36 W^X (app uid EACCES).
# Existence via toybox: bash `test -f` is hooked by an already-loaded termux-exec.
_isfile() { /system/bin/toybox test -f "$1"; }
PA=""
PACTL=""
[ -n "${PD_PULSEAUDIO_BIN:-}" ] && _isfile "$PD_PULSEAUDIO_BIN" && PA="$PD_PULSEAUDIO_BIN"
[ -z "$PA" ] && [ -n "$_nld" ] && _isfile "$_nld/libpulseaudio.so" && PA="$_nld/libpulseaudio.so"
[ -n "${PD_PACTL_BIN:-}" ] && _isfile "$PD_PACTL_BIN" && PACTL="$PD_PACTL_BIN"
[ -z "$PACTL" ] && [ -n "$_nld" ] && _isfile "$_nld/libpactl.so" && PACTL="$_nld/libpactl.so"
# Android nativeLibraryDir is /data/app/~~hash==/pkg-hash==/lib/arm64/*.so
# toybox `env` treats any argv containing `=` as NAME=VALUE, then execs the
# next word (`--version`). Always launch the binary via /system/bin/sh.
pa_env() {
  /system/bin/env -i \
    PATH="$PREFIX/bin:/system/bin" \
    LD_LIBRARY_PATH="$PREFIX/lib" \
    HOME="$HOME" \
    PREFIX="$PREFIX" \
    TMPDIR="$TMPDIR" \
    PULSE_RUNTIME_PATH="$PULSE_RUNTIME_PATH" \
    XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" \
    TERMUX__PREFIX="$PREFIX" \
    TERMUX__HOME="$HOME" \
    "$@"
}
run_bin() {
  _bin=$1
  shift
  # $1/shift, not exec "$0": toybox sh and libbash -c disagree about $0.
  pa_env /system/bin/sh -c 'bin="$1"; shift; exec "$bin" "$@"' x "$_bin" "$@"
}
run_pa() { run_bin "$PA" "$@"; }
run_pactl() { run_bin "$PACTL" "$@"; }
export PULSE_RUNTIME_PATH="${HOME}/.pulse"
export XDG_RUNTIME_DIR="${HOME}/.pulse-runtime"
# A set PULSE_SERVER makes pulseaudio refuse to spawn a daemon (client-only).
unset PULSE_SERVER
mkdir -p "$PULSE_RUNTIME_PATH" "$XDG_RUNTIME_DIR" "$TMPDIR" "$PREFIX/tmp" "$HOME/.config/pulse" 2>/dev/null || true
chmod 700 "$PULSE_RUNTIME_PATH" "$XDG_RUNTIME_DIR" 2>/dev/null || true
# pactl autospawn creates a 20s-idle daemon. Host clients must never do that.
if [ -f "$HOME/.config/pulse/client.conf" ]; then
  if grep -qE '^;?[[:space:]]*autospawn' "$HOME/.config/pulse/client.conf" 2>/dev/null; then
    sed -i 's/^;*[[:space:]]*autospawn.*/autospawn = no/' "$HOME/.config/pulse/client.conf"
  else
    printf 'autospawn = no\n' >> "$HOME/.config/pulse/client.conf"
  fi
else
  printf 'autospawn = no\n' > "$HOME/.config/pulse/client.conf"
fi

audio_fail() {
  echo "FluxLinux: [AUDIO] FAIL $*"
  exit 0
}

if [ "$(id -u)" = "0" ]; then
  audio_fail "refusing to start Pulse as root"
fi

if [ -z "$PA" ] || ! _isfile "$PA"; then
  audio_fail "libpulseaudio.so missing under nativeLibraryDir (W^X; nld=${_nld:-?})"
fi
if [ -z "$PACTL" ] || ! _isfile "$PACTL"; then
  audio_fail "libpactl.so missing under nativeLibraryDir (W^X; nld=${_nld:-?})"
fi
case "$PA" in
  "$PREFIX/bin/"*|*/usr/bin/pulseaudio)
    audio_fail "refusing PREFIX/bin pulseaudio (W^X); nld=${_nld:-?}"
    ;;
esac

# Overlay copies often land mode 600. untrusted_app cannot mmap those
# PROT_EXEC; root still can, so this looks like “missing libs”.
for _so in libsoxr.so libsoxr-lsr.so libandroid-execinfo.so libFLAC.so libmp3lame.so; do
  _p="$PREFIX/lib/$_so"
  [ -f "$_p" ] || continue
  chmod 755 "$_p" 2>/dev/null || chmod u+rx "$_p" 2>/dev/null || true
done

_ver=$(run_pa --version 2>&1) || \
  audio_fail "pulseaudio cannot exec (${_ver:-missing runtime libs})"

# Unix socket guests use. $PREFIX/tmp is the shared tmp both proot
# (--shared-tmp → /tmp) and chroot (/mnt/host-tmp bind) guests already see;
# it lives under the app-private data dir, so other apps cannot connect.
PULSE_SOCK="$PREFIX/tmp/pulse-native"

# Must pass PULSE_SERVER inside env -i. A prefix assignment is discarded.
# Command is /system/bin/sh (no `=`). $PACTL is $0 of that sh, never env COMMAND.
sock_ok() {
  pa_env PULSE_SERVER="unix:$PULSE_SOCK" /system/bin/sh -c 'bin="$1"; shift; exec "$bin" "$@"' x "$PACTL" info >/dev/null 2>&1
}

sink_name() {
  run_pactl info 2>/dev/null | awk -F': ' '/^Default Sink:/{print $2; exit}'
}

is_real_sink() {
  _s=$(sink_name)
  [ -n "$_s" ] && [ "$_s" != "auto_null" ] && [ "$_s" != "null" ]
}

# Our guest socket module (the daemon's own runtime-dir unix module is separate).
# Suffix match: 99-fluxlinux.pa and this script may spell the prefix differently
# (/data/data vs /data/user/0); sock_ok proves our path reaches it.
our_sock_module() {
  run_pactl list short modules 2>/dev/null \
    | grep 'module-native-protocol-unix' | grep -q 'socket=[^[:space:]]*/tmp/pulse-native'
}

# Any module-native-protocol-tcp (older FluxLinux loaded an anonymous one on
# 127.0.0.1:4713). Never keep it: any app could record or load modules.
tcp_module_ids() {
  run_pactl list short modules 2>/dev/null | awk '$2 == "module-native-protocol-tcp" {print $1}'
}

unload_tcp_modules() {
  for _idx in $(tcp_module_ids); do
    run_pactl unload-module "$_idx" >/dev/null 2>&1 || true
    echo "FluxLinux: [AUDIO] unloaded TCP module #$_idx"
  done
}

# Existing proot guests were configured for tcp:127.0.0.1. Rewrite only those
# exact FluxLinux-written lines to the socket list (proot path first, chroot
# path second; libpulse tries each in turn). Regular files only, never links.
GUEST_PULSE_SERVER="unix:/tmp/pulse-native unix:/mnt/host-tmp/pulse-native"
migrate_guest_file() {
  _f=$1
  [ -f "$_f" ] && [ ! -L "$_f" ] || return 0
  grep -q 'tcp:127\.0\.0\.1' "$_f" 2>/dev/null || return 0
  sed -e "s#^export PULSE_SERVER=tcp:127\.0\.0\.1\$#export PULSE_SERVER=\"$GUEST_PULSE_SERVER\"#" \
      -e "s#^PULSE_SERVER=tcp:127\.0\.0\.1\$#PULSE_SERVER=\"$GUEST_PULSE_SERVER\"#" \
      -e "s#^default-server = tcp:127\.0\.0\.1\$#default-server = $GUEST_PULSE_SERVER#" \
      "$_f" > "$_f.flux-tmp" 2>/dev/null \
    && cat "$_f.flux-tmp" > "$_f" \
    && echo "FluxLinux: [AUDIO] migrated ${_f#"$PREFIX"/} to unix socket"
  rm -f "$_f.flux-tmp"
}
migrate_proot_guests() {
  for _rootfs in "$PREFIX"/var/lib/proot-distro/containers/*/rootfs \
                 "$PREFIX"/var/lib/proot-distro/installed-rootfs/*; do
    [ -d "$_rootfs/etc" ] || continue
    for _rel in etc/profile.d/flux-pulse.sh etc/environment \
                etc/pulse/client.conf etc/pulse/client.conf.d/99-fluxlinux.conf \
                home/flux/.vnc/xstartup; do
      migrate_guest_file "$_rootfs/$_rel"
    done
  done
}

# First pulseaudio PID only. W^X argv0 is libpulseaudio.so.
first_pulse_pid() {
  _p=$(pidof pulseaudio 2>/dev/null || true)
  [ -n "$_p" ] || _p=$(pidof libpulseaudio.so 2>/dev/null || true)
  [ -n "$_p" ] || return 1
  echo "$_p" | awk '{print $1; exit}'
}

# pactl info is the truth. nld pulseaudio --check/--kill/--start all require
# /proc/self/exe == $PREFIX/bin/pulseaudio and refuse ("playing games").
daemon_alive() {
  run_pactl info >/dev/null 2>&1
}

kill_our_pulse() {
  _p=$(first_pulse_pid || true)
  if [ -z "$_p" ] && [ -r "$PULSE_RUNTIME_PATH/pid" ]; then
    _p=$(tr -d ' \n' <"$PULSE_RUNTIME_PATH/pid")
  fi
  [ -n "$_p" ] && kill "$_p" 2>/dev/null || true
  _p=$(pidof libpulseaudio.so 2>/dev/null || true)
  [ -n "$_p" ] && kill $_p 2>/dev/null || true
}

wait_until_dead() {
  _i=0
  while daemon_alive; do
    _i=$((_i + 1))
    [ "$_i" -ge 20 ] && break
    sleep 0.1
  done
}

# Autospawned daemons use the default 20s idle timeout. Replace them.
if daemon_alive; then
  _pa_pid=$(first_pulse_pid || true)
  if [ -n "$_pa_pid" ] && [ -r "/proc/${_pa_pid}/cmdline" ]; then
    _cmd=$(tr '\0' ' ' <"/proc/${_pa_pid}/cmdline" 2>/dev/null || true)
    case "$_cmd" in
      *exit-idle-time=-1*) ;;
      *)
        kill_our_pulse
        wait_until_dead
        ;;
    esac
  fi
fi

migrate_proot_guests

already_good() {
  daemon_alive || return 1
  is_real_sink || return 1
  [ -z "$(tcp_module_ids)" ] || return 1
  our_sock_module || return 1
  sock_ok || return 1
  return 0
}

if already_good; then
  echo "FluxLinux: [AUDIO] already running sink=$(sink_name) unix=$PULSE_SOCK"
  exit 0
fi

if ! daemon_alive; then
  # --start re-execs /proc/self/exe and requires exe == PREFIX/bin/pulseaudio.
  # From nativeLibraryDir that check fails; --daemonize=yes does not re-exec.
  run_pa --daemonize=yes --exit-idle-time=-1 \
    --dl-search-path="$PREFIX/lib/pulseaudio/modules" || \
    audio_fail "pulseaudio --daemonize failed"
  sleep 0.5
  if ! daemon_alive; then
    audio_fail "daemon started but pactl info failed"
  fi
fi

load_mod() {
  run_pactl load-module "$@" >/dev/null 2>&1 || true
}

if ! is_real_sink; then
  load_mod module-aaudio-sink
fi
if ! is_real_sink; then
  load_mod module-sles-sink
fi

unload_tcp_modules

SOCK_LOAD_ERR=""
if ! our_sock_module; then
  # Only same-uid / root processes can reach $PREFIX/tmp, so anonymous auth
  # is scoped to FluxLinux and its guests (chroot guests run as other uids).
  SOCK_LOAD_ERR=$(run_pactl load-module module-native-protocol-unix \
    socket="$PULSE_SOCK" auth-anonymous=1 2>&1) || true
  sleep 0.3
fi

if ! is_real_sink; then
  audio_fail "no real sink (aaudio/sles failed, sink=$(sink_name))"
fi

if [ -n "$(tcp_module_ids)" ]; then
  audio_fail "sink=$(sink_name) but a TCP module is still loaded (refusing anonymous 127.0.0.1:4713)"
fi

if ! our_sock_module || ! sock_ok; then
  audio_fail "sink=$(sink_name) but unix:$PULSE_SOCK not reachable${SOCK_LOAD_ERR:+ load=$SOCK_LOAD_ERR}"
fi

echo "FluxLinux: [AUDIO] sink=$(sink_name) unix=$PULSE_SOCK"
exit 0

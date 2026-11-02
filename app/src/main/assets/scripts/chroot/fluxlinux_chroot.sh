#!/system/bin/sh
# fluxlinux-chroot v3.0
# SSOT chroot runner for NativeCode Debian 13 (requires root).
# Do not nest this under run_debian13_root.sh — it already owns mounts + one chroot.
# Guest entry always uses env -i + Debian PATH (never Android /system PATH).
# v2.2: TTY-safe b64 (no stdin pipe), login --workdir, devpts ptmx heal.
# v3.0: --user takes any guest user (validated); default akane (Ubuntu chroot) else
#       flux; ~/storage links (links / links-status); zsh profile seeds ZshPluginSet.
#
# Usage:
#   fluxlinux_chroot.sh version
#   fluxlinux_chroot.sh mount [--x11]
#   fluxlinux_chroot.sh login [--user USER|root] [--shell zsh|bash] [--workdir PATH]
#   fluxlinux_chroot.sh sh    [--user USER|root] -- 'shell string'
#   fluxlinux_chroot.sh exec  [--user USER|root] -- CMD [ARGS...]
#   fluxlinux_chroot.sh b64   [--user USER|root] -- BASE64_PAYLOAD
#   fluxlinux_chroot.sh links [--user USER]          (create ~/storage links)
#   fluxlinux_chroot.sh links-status [--user USER]   (print FLUX_LINKS … present=0|1)
#
# USER: ^[a-z_][a-z0-9_-]{0,31}$ and present in the guest /etc/passwd. Default: akane
# when the guest has it (Ubuntu chroot), else flux (older Debian/Alpine/… chroots).
#
# Env:
#   FLUX_CHROOT  FLUX_PACKAGE  FLUX_HOST_TMP  FLUX_PREFIX  FLUX_BB  FLUX_SHELL
set -u

VERSION_STR="fluxlinux-chroot v3.0"
# Prefer caller-pinned env (RootShell / start_gui). Fallbacks cover both store flavors.
if [ -z "${FLUX_PACKAGE:-}" ]; then
  if [ -d /data/data/com.zenithblue.fluxlinux/files/usr ]; then
    FLUX_PACKAGE=com.zenithblue.fluxlinux
  else
    FLUX_PACKAGE=com.ivarna.fluxlinux
  fi
fi
FLUX_CHROOT="${FLUX_CHROOT:-/data/local/tmp/chrootDebian13}"
FLUX_HOST_TMP="${FLUX_HOST_TMP:-/data/data/${FLUX_PACKAGE}/files/usr/tmp}"
FLUX_PREFIX="${FLUX_PREFIX:-/data/data/${FLUX_PACKAGE}/files/usr}"
LOGIN_SHELL="${FLUX_SHELL:-zsh}"
USER_NAME=""
USER_HOME=""
NO_MIGRATE=0
LOGIN_WORKDIR=""
WANT_X11=0
MODE=""
BB=""

die() {
  echo "fluxlinux_chroot: $*" >&2
  exit 2
}

usage() {
  cat <<'EOF' >&2
usage:
  fluxlinux_chroot.sh version
  fluxlinux_chroot.sh mount [--x11]
  fluxlinux_chroot.sh login [--user USER|root] [--shell zsh|bash] [--workdir PATH]
  fluxlinux_chroot.sh sh    [--user USER|root] -- SHELL_STRING
  fluxlinux_chroot.sh exec  [--user USER|root] -- CMD [ARGS...]
  fluxlinux_chroot.sh b64   [--user USER|root] -- BASE64_PAYLOAD
  fluxlinux_chroot.sh links [--user USER]
  fluxlinux_chroot.sh links-status [--user USER]
EOF
  exit 2
}

require_root() {
  [ "$(id -u)" = "0" ] || die "must run as root (id=$(id -u))"
}

# Shared resolver (sidecar). Helper stays single-file if only this script was copied.
_rr=""
for _c in \
  "$(dirname "$0")/resolve_bb.sh"
do
  # M14: never source anything from shell-writable /data/local/tmp.
  case "$_c" in /data/local/tmp/*) continue ;; esac
  [ -n "$_c" ] && [ -f "$_c" ] && _rr="$_c" && break
done
_RESOLVE_BB_SOURCED=0
if [ -n "$_rr" ]; then
  # shellcheck disable=SC1090
  . "$_rr" && _RESOLVE_BB_SOURCED=1 || true
fi
if [ "$_RESOLVE_BB_SOURCED" != "1" ]; then
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
    # M14: BusyBox is used in place; never copied to a shell-writable dir.
    [ -n "$BB" ] || return 1
    return 0
  }
fi

# True if target path is already a mount point (exact match in /proc/mounts).
is_mounted() {
  _tgt="$1"
  grep -q " ${_tgt} " /proc/mounts 2>/dev/null
}

bind_if_missing() {
  _src="$1"
  _dst="$2"
  mkdir -p "$_dst" 2>/dev/null || true
  if is_mounted "$_dst"; then
    return 0
  fi
  $BB mount --bind "$_src" "$_dst" 2>/dev/null \
    || /system/bin/mount --bind "$_src" "$_dst" 2>/dev/null || true
}

mount_type_if_missing() {
  _type="$1"
  _src="$2"
  _dst="$3"
  _opts="${4:-}"
  mkdir -p "$_dst" 2>/dev/null || true
  if is_mounted "$_dst"; then
    return 0
  fi
  if [ -n "$_opts" ]; then
    $BB mount -t "$_type" -o "$_opts" "$_src" "$_dst" 2>/dev/null \
      || /system/bin/mount -t "$_type" -o "$_opts" "$_src" "$_dst" 2>/dev/null || true
  else
    $BB mount -t "$_type" "$_src" "$_dst" 2>/dev/null \
      || /system/bin/mount -t "$_type" "$_src" "$_dst" 2>/dev/null || true
  fi
}

ensure_sticky_tmp() {
  mkdir -p "$FLUX_CHROOT/tmp" "$FLUX_CHROOT/var/tmp" 2>/dev/null || true
  # Never keep a host bind/tmpfs on guest /tmp (breaks apt _apt mkstemp).
  if is_mounted "$FLUX_CHROOT/tmp"; then
    $BB umount "$FLUX_CHROOT/tmp" 2>/dev/null || $BB umount -l "$FLUX_CHROOT/tmp" 2>/dev/null || true
  fi
  chmod 1777 "$FLUX_CHROOT/tmp" 2>/dev/null || true
  chmod 1777 "$FLUX_CHROOT/var/tmp" 2>/dev/null || true
}

# devpts with usable ptmx (stock mount often leaves ptmxmode=000 → c--------- pts/ptmx).
# Root still "passes" test -w on mode 000 — check numeric mode, not -w.
ensure_devpts() {
  _pts="$FLUX_CHROOT/dev/pts"
  mkdir -p "$_pts" 2>/dev/null || true
  if is_mounted "$_pts"; then
    _perm=""
    if [ -c "$_pts/ptmx" ]; then
      _perm=$($BB stat -c '%a' "$_pts/ptmx" 2>/dev/null || stat -c '%a' "$_pts/ptmx" 2>/dev/null || echo "")
    fi
    # 0 / 000 / empty missing → heal once (no mount storm: single umount+remount)
    case "$_perm" in
      ""|0|000)
        $BB umount "$_pts" 2>/dev/null || $BB umount -l "$_pts" 2>/dev/null || true
        ;;
    esac
  fi
  if ! is_mounted "$_pts"; then
    $BB mount -t devpts devpts "$_pts" -o newinstance,ptmxmode=0666,mode=0620 2>/dev/null \
      || $BB mount -t devpts devpts "$_pts" -o ptmxmode=0666,mode=0620 2>/dev/null \
      || $BB mount -t devpts -o ptmxmode=0666,mode=0620 devpts "$_pts" 2>/dev/null \
      || $BB mount -t devpts devpts "$_pts" 2>/dev/null \
      || true
  fi
  if [ ! -c "$FLUX_CHROOT/dev/ptmx" ] && [ -c "$_pts/ptmx" ]; then
    $BB ln -sf pts/ptmx "$FLUX_CHROOT/dev/ptmx" 2>/dev/null || true
  fi
  unset _pts _perm
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

ensure_mounts() {
  [ -d "$FLUX_CHROOT" ] || die "chroot missing: $FLUX_CHROOT"
  mount_lock "$FLUX_CHROOT" || die "chroot mount lock busy: $FLUX_CHROOT"
  # Never (re)bind host /dev, /sys or /sdcard into a rootfs that uninstall is
  # deleting as root (H4), or into a tree that was never set up. Legacy installs
  # without .flux_configured still pass when they have a guest shell.
  if [ -e "$FLUX_CHROOT/.flux_uninstalling" ]; then
    mount_unlock "$FLUX_CHROOT"
    die "chroot is being uninstalled: $FLUX_CHROOT"
  fi
  if [ ! -e "$FLUX_CHROOT/.flux_configured" ] \
    && [ ! -L "$FLUX_CHROOT/bin/sh" ] && [ ! -e "$FLUX_CHROOT/bin/sh" ] \
    && [ ! -e "$FLUX_CHROOT/usr/bin/bash" ] && [ ! -e "$FLUX_CHROOT/usr/bin/sh" ]; then
    mount_unlock "$FLUX_CHROOT"
    die "chroot not installed: $FLUX_CHROOT"
  fi
  mkdir -p \
    "$FLUX_CHROOT/dev" "$FLUX_CHROOT/dev/pts" "$FLUX_CHROOT/dev/shm" \
    "$FLUX_CHROOT/proc" "$FLUX_CHROOT/sys" \
    "$FLUX_CHROOT/tmp" "$FLUX_CHROOT/mnt/host-tmp" "$FLUX_CHROOT/sdcard" \
    "$FLUX_HOST_TMP" 2>/dev/null || true

  # Soft remount /data for dev,suid (KSU/Magisk; fail soft)
  /system/bin/mount -o remount,dev,suid /data >/dev/null 2>&1 \
    || $BB mount -o remount,dev,suid /data >/dev/null 2>&1 \
    || $BB mount -o remount,dev,suid / >/dev/null 2>&1 \
    || true

  bind_if_missing /dev "$FLUX_CHROOT/dev"
  bind_if_missing /sys "$FLUX_CHROOT/sys"
  # /dev bind usually exposes kgsl. If the node is still missing, mknod (fail-soft).
  if [ -e /dev/kgsl-3d0 ] && [ ! -e "$FLUX_CHROOT/dev/kgsl-3d0" ]; then
    $BB mknod "$FLUX_CHROOT/dev/kgsl-3d0" c $($BB stat -c "%t %T" /dev/kgsl-3d0 2>/dev/null) \
      >/dev/null 2>&1 || true
    chmod 666 "$FLUX_CHROOT/dev/kgsl-3d0" >/dev/null 2>&1 || true
  fi
  mount_type_if_missing proc proc "$FLUX_CHROOT/proc"
  ensure_devpts
  mount_type_if_missing tmpfs tmpfs "$FLUX_CHROOT/dev/shm" "size=512M,mode=1777"

  ensure_sticky_tmp

  bind_if_missing "$FLUX_HOST_TMP" "$FLUX_CHROOT/mnt/host-tmp"
  bind_if_missing /sdcard "$FLUX_CHROOT/sdcard"

  # launch_tool bridge: host-tmp → guest sticky /tmp
  if [ -f "$FLUX_HOST_TMP/launch_tool.sh" ]; then
    cp -f "$FLUX_HOST_TMP/launch_tool.sh" "$FLUX_CHROOT/tmp/launch_tool.sh" 2>/dev/null || true
    chmod 755 "$FLUX_CHROOT/tmp/launch_tool.sh" 2>/dev/null || true
  fi

  if [ "$WANT_X11" = "1" ]; then
    mkdir -p "$FLUX_PREFIX/tmp/.X11-unix" "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null || true
    chmod 1777 "$FLUX_PREFIX/tmp/.X11-unix" 2>/dev/null || true
    # Refresh X11 bind so new host sockets appear
    if is_mounted "$FLUX_CHROOT/tmp/.X11-unix"; then
      $BB umount "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null \
        || $BB umount -l "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null || true
    fi
    $BB mount --bind "$FLUX_PREFIX/tmp/.X11-unix" "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null \
      || mount --bind "$FLUX_PREFIX/tmp/.X11-unix" "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null || true
  fi

  # Register this session ($$ execs into the chroot) so a stop that runs before
  # the chroot is entered still sees it as in use (M16).
  if [ "$MODE" != mount ]; then
    mkdir -p "$FLUX_CHROOT/.flux_sessions" 2>/dev/null \
      && : > "$FLUX_CHROOT/.flux_sessions/$$" 2>/dev/null || true
  fi
  mount_unlock "$FLUX_CHROOT"
}

# --- user migration (v3.0) ----------------------------------------------------
# One copy each in fluxlinux_chroot.sh and setup_guest_chroot.sh; keep them identical
# (ChrootGuestUserScriptTest). Renames guest user OLD to NEW in place, the root-side twin
# of GuestUserMigration.kt (Ubuntu chroots created as flux before akane):
#  1. home/OLD (real dir) is renamed to home/NEW with one mv (rename(2): links inside move
#     as links, nothing is followed), unless home/NEW exists (then no merge);
#  2. compat link home/OLD -> NEW once home/OLD is gone;
#  3. per-user entries (AccountsService, crontabs, mail) are renamed;
#  4. sudoers.d lines/file and the doas.conf identity token are rewritten;
#  5. account files, field-wise, temp + rename: subuid, subgid, gshadow, group, shadow,
#     passwd LAST (the commit point).
# Keyed on passwd (O = has OLD, N = has NEW): O+N conflict (nothing touched); O only:
# steps 1-5, each skips done work, so a crash resumes next time; N only: home repair
# (home/NEW missing, home/OLD a real dir); neither: n/a. Runs as root INSIDE the chroot,
# so a guest symlink can never point it at a host path. Args: OLD NEW [ROOT prefix, tests].
# Prints one "FLUX_MIGRATE result=migrated|repaired|already|conflict|n/a|failed" line.
user_migration_body() {
  cat <<'MIGRATE'
_o=$1; _n=$2; _R=${3:-}
_res() { echo "FLUX_MIGRATE result=$1"; exit "${2:-0}"; }
_dir() { [ -d "$1" ] && [ ! -L "$1" ]; }
_file() { [ -f "$1" ] && [ ! -L "$1" ]; }
_there() { [ -e "$1" ] || [ -L "$1" ]; }
_E="$_R/etc"; _H="$_R/home"
{ _dir "$_E" && _file "$_E/passwd"; } || _res n/a
_pw() { awk -F: -v u="$1" -v f="$2" '!/^[ \t]*#/ && NF >= 7 && $1 == u { print $f; exit }' "$_E/passwd"; }
_has() { [ -n "$(_pw "$1" 1)" ]; }
# _rw FILE AWK-ARGS...: rewrite FILE through awk, temp + rename, owner and mode kept.
_rw() {
  _f=$1; shift
  _file "$_f" || return 0
  _t="$_f.flux-migrate"
  rm -f "$_t"
  awk "$@" "$_f" > "$_t" || { rm -f "$_t"; return 1; }
  chown --reference="$_f" "$_t" 2>/dev/null || chown 0:0 "$_t" 2>/dev/null
  chmod --reference="$_f" "$_t" 2>/dev/null || chmod 600 "$_t"
  mv -f "$_t" "$_f" || { rm -f "$_t"; return 1; }
}
# Steps 1-2. Returns 0 when home/OLD is no longer a real dir (moved or never there).
_home() {
  _dir "$_H" || return 0
  if _dir "$_H/$_o"; then
    if _there "$_H/$_n"; then
      echo "fluxlinux: home/$_n exists; leaving home/$_o in place (no merge)" >&2
    else
      mv "$_H/$_o" "$_H/$_n" || return 1
    fi
  fi
  if ! _there "$_H/$_o" && _dir "$_H/$_n"; then
    ln -s "$_n" "$_H/$_o" 2>/dev/null || true
  fi
  return 0
}
_O=0; _N=0
_has "$_o" && _O=1
_has "$_n" && _N=1
if [ "$_O" = 1 ] && [ "$_N" = 1 ]; then _res conflict; fi
if [ "$_O" = 0 ]; then
  [ "$_N" = 1 ] || _res n/a
  if [ "$(_pw "$_n" 6)" = "/home/$_n" ] && _dir "$_H" && ! _there "$_H/$_n" && _dir "$_H/$_o"; then
    _home && _res repaired
  fi
  _res already
fi
_gid=$(_pw "$_o" 4)
_home || _res failed 1
for _d in var/lib/AccountsService/users var/spool/cron/crontabs var/mail; do
  _dir "$_R/$_d" || continue
  if _there "$_R/$_d/$_o" && ! _there "$_R/$_d/$_n"; then mv "$_R/$_d/$_o" "$_R/$_d/$_n" 2>/dev/null || true; fi
done
if _dir "$_E/sudoers.d"; then
  for _s in "$_E"/sudoers.d/*; do
    case "$_s" in *.flux-migrate) continue ;; esac
    _file "$_s" || continue
    _rw "$_s" -v o="$_o" -v n="$_n" '
      !/^[ \t]*#/ { match($0, /^[ \t]*/); l = substr($0, 1, RLENGTH); r = substr($0, RLENGTH + 1)
        if (index(r, o) == 1 && (length(r) == length(o) || substr(r, length(o) + 1, 1) ~ /[ \t]/))
          $0 = l n substr(r, length(o) + 1) }
      { print }' || true
  done
  if _file "$_E/sudoers.d/$_o" && ! _there "$_E/sudoers.d/$_n"; then
    mv "$_E/sudoers.d/$_o" "$_E/sudoers.d/$_n" 2>/dev/null || true
  fi
fi
_rw "$_E/doas.conf" -v o="$_o" -v n="$_n" '
  ($1 == "permit" || $1 == "deny") { for (i = 2; i <= NF; i++) { if ($i == "as" || $i == "cmd" || $i ~ /^#/) break
    if ($i == o) { $i = n; break } } }
  { print }' || true
_rg=0
if _file "$_E/group" && [ -n "$_gid" ]; then
  awk -F: -v o="$_o" -v g="$_gid" '!/^[ \t]*#/ && NF >= 4 && $1 == o && $3 == g { f = 1 } END { exit !f }' "$_E/group" \
    && ! awk -F: -v n="$_n" '!/^[ \t]*#/ && NF >= 4 && $1 == n { f = 1 } END { exit !f }' "$_E/group" && _rg=1
fi
_first() {
  _rw "$1" -v o="$_o" -v n="$_n" -v m="$2" '
    BEGIN { FS = OFS = ":" }
    NR == FNR { if (!/^[ \t]*#/ && $1 == n) have = 1; next }
    !have && !/^[ \t]*#/ && NF >= m && $1 == o { $1 = n }
    { print }' "$1"
}
_grp() {
  _rw "$1" -v o="$_o" -v n="$_n" -v rg="$_rg" -v gs="$2" '
    function fix(s,  a, c, i, v, r, hit, seen) {
      c = split(s, a, ","); for (i = 1; i <= c; i++) if (a[i] == o) hit = 1
      if (!hit) return s
      r = ""; for (i = 1; i <= c; i++) { v = (a[i] == o) ? n : a[i]
        if (!(v in seen)) { seen[v] = 1; r = (r == "") ? v : r "," v } }
      return r }
    BEGIN { FS = OFS = ":" }
    !/^[ \t]*#/ && NF >= 4 { if (rg == 1 && $1 == o) $1 = n
      if (gs == 1 && $3 != "") $3 = fix($3)
      if ($4 != "") $4 = fix($4) }
    { print }'
}
_first "$_E/subuid" 3 || _res failed 1
_first "$_E/subgid" 3 || _res failed 1
_grp "$_E/gshadow" 1 || _res failed 1
_grp "$_E/group" 0 || _res failed 1
_first "$_E/shadow" 2 || _res failed 1
_rw "$_E/passwd" -v o="$_o" -v n="$_n" '
  BEGIN { FS = OFS = ":" }
  !/^[ \t]*#/ && NF >= 7 && $1 == o { $1 = n; if ($6 == "/home/" o) $6 = "/home/" n }
  { print }' || _res failed 1
_res migrated
MIGRATE
}

# 0 when something is mounted at or below ROOTFS/home/USER (never rename across a mount),
# and when /proc/mounts cannot be checked (fails closed). grep -F: toybox has no awk.
user_migration_mount_busy() {
  grep -qF -e " $1/home/$2 " -e " $1/home/$2/" /proc/mounts 2>/dev/null
  [ "$?" -ne 1 ]
}
# --- end user migration -------------------------------------------------------

# --- guest user (v3.0) -------------------------------------------------------
# Non-root login user. An explicit --user wins but must be a plain name that the
# guest /etc/passwd lists (or root). Default: akane when the guest has it (Ubuntu
# chroot, UbuntuRuntime.UBUNTU_GUEST_USER), else flux (older chroots keep working).
valid_user_name() {
  case "$1" in
    ''|[!a-z_]*|*[!a-z0-9_-]*) return 1 ;;
  esac
  [ "${#1}" -le 32 ]
}

# Only call with a name valid_user_name accepted (used in a grep pattern).
guest_has_user() {
  [ -f "$FLUX_CHROOT/etc/passwd" ] && grep -q "^$1:" "$FLUX_CHROOT/etc/passwd" 2>/dev/null
}

default_guest_user() {
  if guest_has_user akane; then printf '%s' akane; else printf '%s' flux; fi
}

# home/flux still a real dir while home/akane is missing (interrupted migration).
guest_home_needs_repair() {
  [ -d "$FLUX_CHROOT/home/flux" ] && [ ! -L "$FLUX_CHROOT/home/flux" ] \
    && [ ! -e "$FLUX_CHROOT/home/akane" ] && [ ! -L "$FLUX_CHROOT/home/akane" ]
}

# Ubuntu chroots created as flux (before akane): rename flux to akane in place
# ([user migration]), as root inside the chroot, under the mount lock, never while
# something is mounted below the old home. Prints the FLUX_MIGRATE line; 1 = not run.
migrate_guest_user() {
  { [ -e "$FLUX_CHROOT/bin/sh" ] || [ -L "$FLUX_CHROOT/bin/sh" ]; } || return 1
  [ ! -e "$FLUX_CHROOT/.flux_uninstalling" ] || return 1
  if user_migration_mount_busy "$FLUX_CHROOT" "$1"; then
    echo "FLUX_MIGRATE result=busy"
    return 1
  fi
  mount_lock "$FLUX_CHROOT" || return 1
  _mb=$(user_migration_body)
  if [ -n "${BB:-}" ] && bb_has "$BB" chroot; then
    $BB chroot "$FLUX_CHROOT" /usr/bin/env -i "PATH=$GUEST_PATH_ROOT" \
      /bin/sh -c "$_mb" flux-migrate "$1" "$2"
  else
    /system/bin/chroot "$FLUX_CHROOT" /usr/bin/env -i "PATH=$GUEST_PATH_ROOT" \
      /bin/sh -c "$_mb" flux-migrate "$1" "$2"
  fi
  _mrc=$?
  mount_unlock "$FLUX_CHROOT"
  return "$_mrc"
}

# Sets USER_NAME (default when empty) and USER_HOME; dies on an invalid/unknown user.
resolve_guest_user() {
  [ -n "$USER_NAME" ] || USER_NAME=$(default_guest_user)
  # The Ubuntu user on a chroot still set up as flux: migrate it in place; when that
  # cannot run (busy mount, conflict, failure) keep logging in as flux instead of dying.
  # NO_MIGRATE=1 (links-status): read-only, fall back without renaming anything.
  if [ "$USER_NAME" = akane ]; then
    if ! guest_has_user akane && guest_has_user flux; then
      [ "${NO_MIGRATE:-0}" = 1 ] || migrate_guest_user flux akane >&2 || true
      if ! guest_has_user akane; then
        echo "fluxlinux-chroot: akane is not set up in $FLUX_CHROOT yet, using flux" >&2
        USER_NAME=flux
      fi
    elif [ "${NO_MIGRATE:-0}" != 1 ] && guest_has_user akane && guest_home_needs_repair; then
      migrate_guest_user flux akane >&2 || true
    fi
  fi
  if [ "$USER_NAME" = root ]; then
    USER_HOME=/root
    return 0
  fi
  valid_user_name "$USER_NAME" || die "invalid user name: $USER_NAME"
  guest_has_user "$USER_NAME" || die "user $USER_NAME not in $FLUX_CHROOT/etc/passwd (re-run chroot setup)"
  # passwd home only when it is a plain absolute path (it lands in the env -i list).
  _uh=$(awk -F: -v u="$USER_NAME" '$1==u {print $6; exit}' "$FLUX_CHROOT/etc/passwd" 2>/dev/null)
  case "$_uh" in
    /*) case "$_uh" in *[!A-Za-z0-9._/-]*) _uh="" ;; esac ;;
    *) _uh="" ;;
  esac
  USER_HOME="${_uh:-/home/$USER_NAME}"
  unset _uh
}
# --- end guest user -----------------------------------------------------------

# --- storage links (v3.0) -----------------------------------------------------
# Termux-style ~/storage/{shared,downloads,...} for the user's home and /root, same
# contract as GuestStorageLinks.kt: idempotent, creates only what is missing, never
# deletes, replaces or writes through a symlink. Targets use /sdcard, the only shared
# storage bind (ensure_mounts). Runs as root INSIDE the chroot, so a guest symlink can
# never point it at a host path. Args: MODE(ensure|status) USER HOME [ROOT prefix, tests].
storage_links_body() {
  cat <<'LINKS'
_mode=$1; _user=$2; _uhome=$3; _R=${4:-}
_c=0; _e=0; _s=""; _seen=0; _ok=1
_ugrp=$(id -gn "$_user" 2>/dev/null) || _ugrp=""
_real_dir() {
  _p=$_R; _rest=${1#/}
  while [ -n "$_rest" ]; do
    _seg=${_rest%%/*}
    case "$_rest" in */*) _rest=${_rest#*/} ;; *) _rest="" ;; esac
    [ -n "$_seg" ] || continue
    _p="$_p/$_seg"
    { [ -d "$_p" ] && [ ! -L "$_p" ]; } || return 1
  done
  return 0
}
if [ "$_user" = root ]; then _homes="/root:root:root"; else _homes="$_uhome:$_user:${_ugrp:-$_user} /root:root:root"; fi
for _ent in $_homes; do
  _h=${_ent%%:*}; _own=${_ent#*:}
  _real_dir "$_h" || continue
  _seen=1
  _st="$_R$_h/storage"
  if [ -L "$_st" ] || { [ -e "$_st" ] && [ ! -d "$_st" ]; }; then
    _s="$_s,$_h/storage"; _ok=0; continue
  fi
  if [ ! -d "$_st" ]; then
    if [ "$_mode" != ensure ]; then _ok=0; continue; fi
    mkdir "$_st" 2>/dev/null || { _s="$_s,$_h/storage"; _ok=0; continue; }
    chown "$_own" "$_st" 2>/dev/null || true
  fi
  for _l in shared:/sdcard downloads:/sdcard/Download documents:/sdcard/Documents \
            dcim:/sdcard/DCIM pictures:/sdcard/Pictures music:/sdcard/Music \
            movies:/sdcard/Movies; do
    _n=${_l%%:*}; _t=${_l#*:}; _lp="$_st/$_n"
    if [ -L "$_lp" ] && [ "$(readlink "$_lp")" = "$_t" ]; then
      _e=$((_e + 1))
    elif [ -e "$_lp" ] || [ -L "$_lp" ]; then
      _s="$_s,$_h/storage/$_n"; _ok=0
    elif [ "$_mode" = ensure ] && ln -s "$_t" "$_lp" 2>/dev/null; then
      chown -h "$_own" "$_lp" 2>/dev/null || true
      _c=$((_c + 1))
    else
      _ok=0
      [ "$_mode" = ensure ] && _s="$_s,$_h/storage/$_n"
    fi
  done
done
_present=0
[ "$_seen" = 1 ] && [ "$_ok" = 1 ] && _present=1
echo "FLUX_LINKS mode=$_mode created=$_c existing=$_e present=$_present skipped=${_s#,}"
LINKS
}

# MODE = ensure|status. USER_NAME / USER_HOME must be resolved. No mounts needed.
run_storage_links() {
  [ -e "$FLUX_CHROOT/bin/sh" ] || [ -L "$FLUX_CHROOT/bin/sh" ] || return 1
  _lb=$(storage_links_body)
  if [ -n "${BB:-}" ] && bb_has "$BB" chroot; then
    $BB chroot "$FLUX_CHROOT" /usr/bin/env -i "PATH=$GUEST_PATH_ROOT" \
      /bin/sh -c "$_lb" flux-links "$1" "$USER_NAME" "$USER_HOME"
  else
    /system/bin/chroot "$FLUX_CHROOT" /usr/bin/env -i "PATH=$GUEST_PATH_ROOT" \
      /bin/sh -c "$_lb" flux-links "$1" "$USER_NAME" "$USER_HOME"
  fi
}
# --- end storage links --------------------------------------------------------

# Single-quote escape for embedding into su -c '…'
sq() {
  # shellcheck disable=SC2001
  printf "%s" "$1" | sed "s/'/'\\\\''/g"
}

# shell-join argv into a single-quoted string safe for su -c
quote_argv() {
  _out=""
  for _a in "$@"; do
    _q=$(sq "$_a")
    if [ -z "$_out" ]; then
      _out="'$_q'"
    else
      _out="$_out '$_q'"
    fi
  done
  printf "%s" "$_out"
}

# Canonical Debian PATH inside rootfs (no Android /system).
GUEST_PATH_ROOT="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

guest_path_for_user() {
  if [ "$USER_NAME" = "root" ]; then
    printf '%s' "$GUEST_PATH_ROOT"
  else
    printf '%s' "$USER_HOME/.local/bin:$USER_HOME/bin:$USER_HOME/.cargo/bin:/opt/nodejs/bin:$GUEST_PATH_ROOT"
  fi
}

# KEY=VAL list for guest /usr/bin/env -i (space-separated; values have no spaces).
build_guest_env_args() {
  _gp=$(guest_path_for_user)
  _term="${TERM:-xterm-256color}"
  # POSIX C until guest profile.d/zshrc picks a locale that exists.
  # Forcing en_US.UTF-8 here prints setlocale warnings on Manjaro ARM.
  _lang="${LANG:-C}"
  if [ "$USER_NAME" = "root" ]; then
    GUEST_ENV_ARGS="PATH=$_gp HOME=/root USER=root LOGNAME=root TERM=$_term LANG=$_lang TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp PULSE_SERVER=unix:/mnt/host-tmp/pulse-native DEBIAN_FRONTEND=noninteractive"
  else
    GUEST_ENV_ARGS="PATH=$_gp HOME=$USER_HOME USER=$USER_NAME LOGNAME=$USER_NAME NVM_DIR=$USER_HOME/.nvm TERM=$_term LANG=$_lang TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp PULSE_SERVER=unix:/mnt/host-tmp/pulse-native DEBIAN_FRONTEND=noninteractive"
  fi
}

# chroot + clean env -i + remaining guest argv (drops Android PATH/LD_*).
guest_chroot_env() {
  build_guest_env_args
  # shellcheck disable=SC2086
  if [ -n "${BB:-}" ] && bb_has "$BB" chroot; then
    exec $BB chroot "$FLUX_CHROOT" /usr/bin/env -i $GUEST_ENV_ARGS "$@"
  fi
  exec /system/bin/chroot "$FLUX_CHROOT" /usr/bin/env -i $GUEST_ENV_ARGS "$@"
}

guest_bin_exists() {
  [ -x "$FLUX_CHROOT/bin/$1" ] || [ -x "$FLUX_CHROOT/usr/bin/$1" ]
}

# Absolute guest path for an applet (/bin, /usr/bin, /sbin, /usr/sbin).
# Fedora 44+ puts su/runuser under util-linux, often only in /usr/sbin.
guest_bin_path() {
  for _d in /bin /usr/bin /sbin /usr/sbin; do
    if [ -x "$FLUX_CHROOT$_d/$1" ]; then
      printf '%s' "$_d/$1"
      return 0
    fi
  done
  return 1
}

# Switch to USER_NAME without guest su/runuser.
# Android busybox chroot is "NEWROOT [PROG]" only — GNU --userspec is treated
# as NEWROOT and fails with "can't change root directory to '--userspec=…'".
# Stage host busybox into the guest (argv0 must be "busybox") and drop uid
# with setuidgid NUMERIC (name lookup does not see the guest user).
guest_userspec() {
  _pw="$FLUX_CHROOT/etc/passwd"
  _uid=$(awk -F: -v u="$USER_NAME" '$1==u {print $3; exit}' "$_pw")
  [ -n "${_uid:-}" ] || \
    die "cannot switch to $USER_NAME: no su/runuser and no passwd entry"
  if ! "$BB" --list 2>/dev/null | tr ' \t' '\n' | grep -qx setuidgid; then
    die "busybox setuidgid missing; cannot drop to $USER_NAME (no guest su)"
  fi
  mkdir -p "$FLUX_CHROOT/tmp" || true
  _bb=/tmp/busybox
  cp -f "$BB" "$FLUX_CHROOT$_bb" || die "cannot stage busybox for setuidgid"
  chmod 755 "$FLUX_CHROOT$_bb"
  build_guest_env_args
  # shellcheck disable=SC2086
  exec $BB chroot "$FLUX_CHROOT" "$_bb" setuidgid "$_uid" \
    /usr/bin/env -i $GUEST_ENV_ARGS "$@"
}

# Drop to USER_NAME then exec remaining argv (non-interactive).
# Order matches start_guest_gui: runuser, then su, then numeric setuidgid.
guest_as_user() {
  [ "$#" -ge 1 ] || die "guest_as_user requires CMD"
  _runuser=$(guest_bin_path runuser || true)
  if [ -n "${_runuser:-}" ]; then
    guest_chroot_env "$_runuser" -u "$USER_NAME" -- "$@"
  fi
  _su=$(guest_bin_path su || true)
  if [ -n "${_su:-}" ]; then
    # su - USER -s SHELL [ -c CMD … ]
    guest_chroot_env "$_su" - "$USER_NAME" -s "$@"
  fi
  guest_userspec "$@"
}

# Interactive login as USER_NAME. LOGIN_SHELL is already resolve_login_shell'd.
# su - handles home/cd; runuser/userspec cd via $HOME from build_guest_env_args.
guest_login_user() {
  case "$LOGIN_SHELL" in
    zsh)
      _bin=/bin/zsh
      _boot="exec /bin/zsh -l"
      ;;
    sh|ash)
      _bin=/bin/sh
      _boot="exec /bin/sh -l"
      ;;
    bash|*)
      _bin=/bin/bash
      _boot="exec /bin/bash --login"
      ;;
  esac
  _inner="${_cd}${_boot}"

  _runuser=$(guest_bin_path runuser || true)
  if [ -n "${_runuser:-}" ]; then
    guest_chroot_env "$_runuser" -u "$USER_NAME" -- /bin/sh -c "cd 2>/dev/null || true; $_inner"
  fi
  _su=$(guest_bin_path su || true)
  if [ -n "${_su:-}" ]; then
    if [ -n "$_cd" ]; then
      guest_chroot_env "$_su" - "$USER_NAME" -s "$_bin" -c "$_inner"
    else
      guest_chroot_env "$_su" - "$USER_NAME" -s "$_bin"
    fi
  fi
  guest_userspec /bin/sh -c "cd 2>/dev/null || true; $_inner"
}

# App uid cannot stat /data/local/tmp (SELinux). Resolve as root from the rootfs.
resolve_login_shell() {
  _req="${1:-zsh}"
  case "$_req" in
    ash) _req=sh ;;
  esac
  if guest_bin_exists "$_req"; then
    printf '%s' "$_req"
    return
  fi
  for _s in zsh bash sh; do
    if guest_bin_exists "$_s"; then
      printf '%s' "$_s"
      return
    fi
  done
  printf '%s' "sh"
}

# Seed a Flux zsh profile when customization never wrote one (Alpine chroot) for the
# non-root user. Host-side write into the rootfs: skips any symlinked home or file.
ensure_flux_zsh_profile() {
  [ "$USER_NAME" != "root" ] || return 0
  _home="$FLUX_CHROOT$USER_HOME"
  [ -d "$_home" ] && [ ! -L "$_home" ] && [ ! -L "$FLUX_CHROOT/home" ] || return 0
  if [ ! -e "$_home/.zshrc" ] && [ ! -L "$_home/.zshrc" ]; then
    cat > "$_home/.zshrc" <<'ZSHRC'
# Guest PATH only — never inherit host PREFIX/bin (nested proot glue errors).
export PATH="$HOME/.local/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

_have=$(locale -a 2>/dev/null || true)
_pick=""
for _c in en_US.UTF-8 en_US.utf8 C.UTF-8 C.utf8; do
  echo "$_have" | grep -qxFi "$_c" && { _pick="$_c"; break; }
done
if [ -n "$_pick" ]; then
  export LANG="$_pick" LC_ALL="$_pick"
else
  unset LC_ALL
  export LANG=C
fi
unset _have _c _pick
unset PROOT_TMP_DIR
export TMPDIR="${TMPDIR:-/tmp}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

{
  if command -v fastfetch >/dev/null 2>&1; then
    _ff="$HOME/.local/share/fastfetch/presets/termux.jsonc"
    if [ -f "$_ff" ]; then
      fastfetch --config "$_ff" 2>/dev/null || true
    else
      fastfetch --config termux 2>/dev/null || true
    fi
    unset _ff
  fi
  if command -v pokemon-colorscripts >/dev/null 2>&1; then
    pokemon-colorscripts --no-title -r 1,2,3 2>/dev/null || true
  fi
} &!

export ZSH="${ZSH:-$HOME/.oh-my-zsh}"
if [ -f "$ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="agnosterzak"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  # flux-zsh-plugins v1: managed by FluxLinux (ZshPluginSet.kt). Edit freely; Flux never rewrites it.
  _flux_zc="${ZSH_CUSTOM:-$ZSH/custom}"
  plugins=()
  for _flux_p in git extract colored-man-pages copypath copyfile command-not-found fzf zoxide fzf-tab zsh-autosuggestions zsh-syntax-highlighting history-substring-search; do
    [[ -d "$_flux_zc/plugins/$_flux_p" || -d "$ZSH/plugins/$_flux_p" ]] || continue
    case "$_flux_p" in
      fzf|fzf-tab) (( $+commands[fzf] )) || continue ;;
      zoxide) (( $+commands[zoxide] )) || continue ;;
      command-not-found) [[ -x /usr/lib/command-not-found || -x /usr/share/command-not-found/command-not-found ]] || continue ;;
    esac
    plugins+=("$_flux_p")
  done
  unset _flux_zc _flux_p
  source "$ZSH/oh-my-zsh.sh"
  if (( $+functions[history-substring-search-up] )); then
    bindkey '^[[A' history-substring-search-up
    bindkey '^[[B' history-substring-search-down
    bindkey '^[OA' history-substring-search-up
    bindkey '^[OB' history-substring-search-down
    if [[ -n "${terminfo[kcuu1]}" ]]; then
      bindkey "${terminfo[kcuu1]}" history-substring-search-up
    fi
    if [[ -n "${terminfo[kcud1]}" ]]; then
      bindkey "${terminfo[kcud1]}" history-substring-search-down
    fi
  fi
fi

if command -v sudo >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get() { command sudo apt-get "$@"; }
  fi
  if command -v apt >/dev/null 2>&1; then
    apt() { command sudo apt "$@"; }
  fi
  if command -v apk >/dev/null 2>&1; then
    apk() { command sudo apk "$@"; }
  fi
fi
ZSHRC
  fi
  if [ ! -e "$_home/.zprofile" ] && [ ! -L "$_home/.zprofile" ]; then
    printf '%s\n' '[[ -o interactive ]] || { [ -f "$HOME/.zshrc" ] && . "$HOME/.zshrc"; }' \
      > "$_home/.zprofile"
  fi
  if [ -x "$FLUX_CHROOT/bin/zsh" ] && [ -f "$FLUX_CHROOT/etc/passwd" ] && \
     grep -q "^$USER_NAME:" "$FLUX_CHROOT/etc/passwd" && \
     ! grep -q "^$USER_NAME:.*zsh\$" "$FLUX_CHROOT/etc/passwd"; then
    sed -i "s|^\\($USER_NAME:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*\\):.*|\\1:/bin/zsh|" \
      "$FLUX_CHROOT/etc/passwd" 2>/dev/null || true
  fi
  for _zf in "$_home/.zshrc" "$_home/.zprofile"; do
    [ -L "$_zf" ] || chown --reference="$_home" "$_zf" 2>/dev/null || true
  done
}
guest_login() {
  ensure_flux_zsh_profile
  # ~/storage links (cheap, idempotent; never fatal for the login).
  run_storage_links ensure >/dev/null 2>&1 || true
  LOGIN_SHELL="$(resolve_login_shell "${LOGIN_SHELL:-zsh}")"
  # Optional project cwd (workspace shell). Path must not contain single quotes.
  _cd=""
  if [ -n "${LOGIN_WORKDIR:-}" ]; then
    case "$LOGIN_WORKDIR" in
      *"'"*) die "workdir must not contain single quotes" ;;
    esac
    _cd="cd '$LOGIN_WORKDIR' 2>/dev/null || true; "
  fi
  case "$USER_NAME" in
    root)
      case "$LOGIN_SHELL" in
        zsh)
          if [ -n "$_cd" ]; then
            guest_chroot_env /bin/zsh -c "${_cd}exec /bin/zsh -l"
          else
            guest_chroot_env /bin/zsh -l
          fi
          ;;
        sh|ash)
          # Alpine/Chimera minirootfs — resolver may return sh when bash/zsh missing
          if [ -n "$_cd" ]; then
            guest_chroot_env /bin/sh -c "${_cd}exec /bin/sh -l"
          else
            guest_chroot_env /bin/sh -l
          fi
          ;;
        bash|*)
          if [ -n "$_cd" ]; then
            guest_chroot_env /bin/bash --login -c "${_cd}exec /bin/bash --login"
          else
            guest_chroot_env /bin/bash --login
          fi
          ;;
      esac
      ;;
    *)
      guest_login_user
      ;;
  esac
}

# Non-interactive: one shell string as USER_NAME (single chroot + one su layer).
# Host-encode to base64 then guest_b64 — avoids double-quote/$/` breakage (G3).
guest_sh() {
  _cmd="$1"
  _b64=""
  if command -v base64 >/dev/null 2>&1; then
    _b64=$(printf '%s' "$_cmd" | base64 | tr -d '\n')
  elif [ -n "$BB" ]; then
    _b64=$(printf '%s' "$_cmd" | $BB base64 2>/dev/null | tr -d '\n')
  fi
  if [ -n "$_b64" ]; then
    guest_b64 "$_b64"
    return
  fi
  # Fallback only if host has no base64 (should not happen on Android root)
  # Prefer bash when present; Alpine minirootfs only has /bin/sh until bootstrap.
  _gshell=/bin/sh
  [ -x "${FLUX_CHROOT:-}/bin/bash" ] || [ -x /bin/bash ] && _gshell=/bin/bash
  if [ "$USER_NAME" = "root" ]; then
    guest_chroot_env "$_gshell" -c "$_cmd"
  else
    guest_as_user "$_gshell" -c "$_cmd"
  fi
}

# Argv-preserving exec (root: chroot + binary; user: su -c with quoted argv).
guest_exec() {
  if [ "$#" -lt 1 ]; then
    die "exec requires CMD"
  fi
  if [ "$USER_NAME" = "root" ]; then
    guest_chroot_env "$@"
  else
    _joined=$(quote_argv "$@")
    _eshell=/bin/bash
    guest_bin_exists bash || _eshell=/bin/sh
    guest_as_user "$_eshell" -c "exec $_joined"
  fi
}

# Base64 payload → guest shell as USER_NAME (Kotlin / RootShell path).
# Absolute /usr/bin/base64 — never depend on guest PATH for decode bootstrap.
# TTY-safe: decode to temp script then run FILE (do NOT pipe into bash — that steals stdin
# and breaks TUI tools needing /dev/tty: bubbletea, grok, claude, opencode).
# Prefers /bin/bash when present; falls back to /bin/sh for Alpine minirootfs.
guest_b64() {
  _b64="$1"
  [ -n "$_b64" ] || die "b64 requires payload"
  # alphabet-only payload — safe inside single quotes
  # \$ preserved for guest; host expands only ${_b64}
  _inner="_b='${_b64}'; _f=/tmp/.nc_b64_\$\$; { echo \$_b | /usr/bin/base64 -d 2>/dev/null || echo \$_b | /bin/base64 -d; } >\$_f || exit 2; if [ -x /bin/bash ]; then /bin/bash --noprofile --norc \$_f; else /bin/sh \$_f; fi; _e=\$?; rm -f \$_f; exit \$_e"
  if [ "$USER_NAME" = "root" ]; then
    # Outer bootstrap: sh always exists; inner script picks bash if available
    guest_chroot_env /bin/sh -c "$_inner"
  else
    guest_as_user /bin/sh -c "$_inner"
  fi
}

parse_common_flags() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --user)
        [ "$#" -ge 2 ] || die "--user needs value"
        USER_NAME="$2"
        shift 2
        ;;
      --shell)
        [ "$#" -ge 2 ] || die "--shell needs value"
        LOGIN_SHELL="$2"
        shift 2
        ;;
      --x11)
        WANT_X11=1
        shift
        ;;
      --)
        shift
        REMAINING_ARGS="$@"
        return 0
        ;;
      -*)
        die "unknown flag: $1"
        ;;
      *)
        REMAINING_ARGS="$@"
        return 0
        ;;
    esac
  done
  REMAINING_ARGS=""
}

# --- main ---
[ "$#" -ge 1 ] || usage
MODE="$1"
shift

case "$MODE" in
  version|-V|--version)
    echo "$VERSION_STR"
    exit 0
    ;;
  mount|login|sh|exec|b64|links|links-status) ;;
  -h|--help|help) usage ;;
  *) die "unknown mode: $MODE" ;;
esac

require_root
resolve_bb || die "root-capable busybox not found"

# Flag parse: collect until -- or end; modes sh/exec/b64 need --
REMAINING_ARGS=""
WANT_X11=0
USER_NAME=""
LOGIN_SHELL="${FLUX_SHELL:-zsh}"

case "$MODE" in
  mount)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --x11) WANT_X11=1; shift ;;
        *) die "mount: unknown arg $1" ;;
      esac
    done
    ensure_mounts
    exit 0
    ;;
  login)
    LOGIN_WORKDIR=""
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --shell)
          [ "$#" -ge 2 ] || die "--shell needs value"
          LOGIN_SHELL="$2"; shift 2
          ;;
        --workdir)
          [ "$#" -ge 2 ] || die "--workdir needs value"
          LOGIN_WORKDIR="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        *) die "login: unknown arg $1" ;;
      esac
    done
    resolve_guest_user
    ensure_mounts
    guest_login
    ;;
  sh)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        --) shift; break ;;
        *)
          # allow bare string without -- for convenience
          break
          ;;
      esac
    done
    resolve_guest_user
    [ "$#" -ge 1 ] || die "sh requires a shell string"
    # Join remaining as one command string (caller may pass one quoted arg)
    CMD_STR="$*"
    ensure_mounts
    guest_sh "$CMD_STR"
    ;;
  exec)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        --) shift; break ;;
        *) break ;;
      esac
    done
    resolve_guest_user
    [ "$#" -ge 1 ] || die "exec requires CMD"
    ensure_mounts
    guest_exec "$@"
    ;;
  b64)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        --) shift; break ;;
        *) break ;;
      esac
    done
    resolve_guest_user
    [ "$#" -ge 1 ] || die "b64 requires payload"
    B64_PAYLOAD="$1"
    ensure_mounts
    guest_b64 "$B64_PAYLOAD"
    ;;
  links|links-status)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        *) die "$MODE: unknown arg $1" ;;
      esac
    done
    [ -d "$FLUX_CHROOT" ] || die "chroot missing: $FLUX_CHROOT"
    [ ! -e "$FLUX_CHROOT/.flux_uninstalling" ] || die "chroot is being uninstalled: $FLUX_CHROOT"
    # Default user missing (bare rootfs): links for /root only.
    if [ -z "$USER_NAME" ]; then
      USER_NAME=$(default_guest_user)
      guest_has_user "$USER_NAME" || USER_NAME=root
    fi
    [ "$MODE" = links ] || NO_MIGRATE=1
    resolve_guest_user
    if [ "$MODE" = links ]; then
      run_storage_links ensure || die "no guest shell in $FLUX_CHROOT"
    else
      run_storage_links status || die "no guest shell in $FLUX_CHROOT"
    fi
    ;;
esac

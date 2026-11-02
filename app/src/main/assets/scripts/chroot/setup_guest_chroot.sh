#!/bin/sh
# setup_guest_chroot.sh
# Generic chroot extract + user bootstrap (Fedora / Void / openSUSE).
# XFCE is installed later by the family script (onboarding XFCE phase).
#
# Env (from DistroInstallProfile / OnboardingInstallRunner):
#   FLUX_CHROOT, FLUX_ROOTFS_PATH, FLUX_ROOTFS_SHA256, FLUX_ROOTFS_NAME,
#   FLUX_ROOTFS_URL (GitHub release download fallback)
#   FLUX_GUEST_USER (guest login user: akane for ubuntu_chroot, default flux)
#   TERMUX_APP__PACKAGE_NAME, TERMUX__HOME

GUESTPATH="${FLUX_CHROOT:-/data/local/tmp/chrootGuest}"
USERNAME="${FLUX_GUEST_USER:-flux}"
DISTRO_LABEL="${FLUX_DISTRO_LABEL:-guest}"

PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
APP_HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
APP_PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
ROOTFS_NAME="${FLUX_ROOTFS_NAME:-rootfs.tar.xz}"
ROOTFS_SHA256="${FLUX_ROOTFS_SHA256:-}"
ROOTFS_URL="${FLUX_ROOTFS_URL:-}"

progress() { printf "\033[1;36m[+] %s\033[0m\n" "$1"; }
success()  { printf "\033[1;32m[✓] %s\033[0m\n" "$1"; }
error()    { printf "\033[1;31m[!] %s\033[0m\n" "$1"; }

# Guest user names end up in guest shell strings: plain names only (no root).
valid_user_name() {
    case "$1" in
        ''|root|[!a-z_]*|*[!a-z0-9_-]*) return 1 ;;
    esac
    [ "${#1}" -le 32 ]
}

PROC_MOUNTS=/proc/mounts

# True if $1 is a mount point (exact match in /proc/mounts).
is_mounted() {
    grep -q " $1 " "$PROC_MOUNTS" 2>/dev/null
}

AWK=""

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

# L6: never stack a second layer on a target that is already mounted.
bind_if_missing() {
    mkdir -p "$2" 2>/dev/null || true
    is_mounted "$2" && return 0
    $BB mount --bind "$1" "$2" 2>/dev/null || /system/bin/mount --bind "$1" "$2"
}

mount_type_if_missing() {
    # $1=type $2=dst [$3=opts]
    mkdir -p "$2" 2>/dev/null || true
    is_mounted "$2" && return 0
    if [ -n "${3:-}" ]; then
        $BB mount -t "$1" -o "$3" "$1" "$2" 2>/dev/null \
            || /system/bin/mount -t "$1" -o "$3" "$1" "$2"
    else
        $BB mount -t "$1" "$1" "$2" 2>/dev/null || /system/bin/mount -t "$1" "$1" "$2"
    fi
}

# Unmount everything under the rootfs, looping while anything is still mounted
# (stacked layers need several passes). 0 only when nothing remains (L6).
cleanup_mounts() {
    progress "Safety Check: Unmounting filesystems..."
    _pass=0
    while [ "$_pass" -lt 5 ]; do
        # Fail closed (H4 review): unknown mount state counts as still mounted.
        _mnts=$(mounts_under "$GUESTPATH") || break
        if [ -z "$_mnts" ]; then
            no_mounts_at_or_under "$GUESTPATH" && return 0
            break
        fi
        for _mnt in $_mnts; do
            $BB umount "$_mnt" 2>/dev/null || $BB umount -l "$_mnt" 2>/dev/null || true
        done
        _pass=$((_pass + 1))
    done
    if _mnts=$(mounts_under "$GUESTPATH") && [ -z "$_mnts" ] \
        && no_mounts_at_or_under "$GUESTPATH"; then
        return 0
    fi
    error "Still mounted (or mounts unreadable) under $GUESTPATH:"
    grep -F " $GUESTPATH" "$PROC_MOUNTS" 2>/dev/null
    return 1
}

goodbye() {
    error "Something went wrong."
    cleanup_mounts
    error "Exiting..."
    exit 1
}

resolve_rootfs_archive() {
    ROOTFS_ARCHIVE=""
    if [ -n "${FLUX_ROOTFS_PATH:-}" ] && [ -f "$FLUX_ROOTFS_PATH" ] && [ -s "$FLUX_ROOTFS_PATH" ]; then
        ROOTFS_ARCHIVE="$FLUX_ROOTFS_PATH"
        progress "rootfs from FLUX_ROOTFS_PATH=$ROOTFS_ARCHIVE"
        return 0
    fi
    for candidate in \
        "$APP_HOME/$ROOTFS_NAME" \
        "$APP_HOME/rootfs/$ROOTFS_NAME" \
        "$APP_PREFIX/var/lib/proot-distro/cache/rootfs/$ROOTFS_NAME" \
        "/sdcard/Download/$ROOTFS_NAME" \
        "/storage/emulated/0/Download/$ROOTFS_NAME" \
        "$GUESTPATH/$ROOTFS_NAME"
    do
        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            ROOTFS_ARCHIVE="$candidate"
            progress "rootfs found: $ROOTFS_ARCHIVE"
            return 0
        fi
    done
    return 1
}

# Download Helper (fallback only — prefer app-local archive). Same pattern as
# setup_debian13_chroot.sh / setup_alpine_chroot.sh.
download_file() {
    # $1=dir $2=filename $3=url
    progress "Downloading file..."
    if [ -e "$1/$2" ] && [ -s "$1/$2" ]; then
        printf "\033[1;33m[!] File already exists: %s\033[0m\n" "$2"
        return 0
    fi
    mkdir -p "$1" 2>/dev/null || true
    if command -v wget >/dev/null 2>&1; then
        wget -O "$1/$2" "$3" && success "File downloaded: $2" && return 0
    fi
    progress "Trying Busybox wget..."
    $BB wget -O "$1/$2" "$3" && success "File downloaded (Fallback)" && return 0
    goodbye
}

# M2: .flux_extract_ok is written only after tar exits 0. A tree that has a
# shell but no marker was interrupted mid-extraction and is re-extracted over.
# .flux_configured (written after a past full extract) grandfathers old installs.
EXTRACT_MARKER=".flux_extract_ok"

mark_extracted() {
    printf '%s\n' "${ROOTFS_SHA256:-unknown}" > "$1/$EXTRACT_MARKER" || goodbye
}

extract_file() {
    _dest="$1"
    _archive="${2:-}"
    progress "Extracting file from $_archive ..."
    if [ -e "$_dest/$EXTRACT_MARKER" ] || [ -e "$_dest/.flux_configured" ]; then
        printf "\033[1;33m[!] Rootfs already extracted: %s\033[0m\n" "$_dest"
        return 0
    fi
    if [ -L "$_dest/bin/sh" ] || [ -e "$_dest/bin/sh" ] || \
       [ -e "$_dest/usr/bin/sh" ] || [ -e "$_dest/usr/bin/apk" ] || \
       [ -x "$_dest/usr/bin/bash" ] || [ -x "$_dest/bin/bash" ]; then
        printf "\033[1;33m[!] Rootfs is incomplete (no %s) — re-extracting: %s\033[0m\n" \
            "$EXTRACT_MARKER" "$_dest"
    fi
    if [ ! -f "$_archive" ] || [ ! -s "$_archive" ]; then
        error "Rootfs archive missing: $_archive"
        goodbye
    fi
    # Never let tar write through a leftover bind (/dev, /sdcard) into the host.
    if ! cleanup_mounts; then
        error "Refusing to extract while filesystems are mounted under $_dest"
        goodbye
    fi
    mkdir -p "$_dest"
    # Always use root busybox tar — toybox /system/bin/tar has no xz.
    _tar="$BB"
    [ -n "$_tar" ] || _tar=busybox
    case "$_archive" in
        *.tar.xz|*.txz)
            if $_tar tar xJf "$_archive" -C "$_dest"; then
                mark_extracted "$_dest"
                success "Rootfs extracted successfully (xz)."
                return 0
            fi
            ;;
        *.tar.gz|*.tgz)
            if $_tar tar xzf "$_archive" -C "$_dest"; then
                mark_extracted "$_dest"
                success "Rootfs extracted successfully (gzip)."
                return 0
            fi
            ;;
    esac
    if $_tar tar xf "$_archive" -C "$_dest"; then
        mark_extracted "$_dest"
        success "Rootfs extracted successfully."
        return 0
    fi
    error "Extraction Failed!"
    goodbye
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

# A chroot set up as flux before akane: rename flux to $USERNAME in place ([user
# migration]) instead of adding a second user next to it. 0 = nothing to do or renamed.
migrate_flux_user() {
    [ "$USERNAME" != flux ] || return 0
    grep -q "^$USERNAME:" "$GUESTPATH/etc/passwd" 2>/dev/null && return 0
    grep -q '^flux:' "$GUESTPATH/etc/passwd" 2>/dev/null || return 0
    if user_migration_mount_busy "$GUESTPATH" flux; then
        error "Something is mounted under $GUESTPATH/home/flux: not renaming flux to $USERNAME"
        return 1
    fi
    progress "Renaming user flux to $USERNAME (home, groups, sudoers)..."
    $BB chroot "$GUESTPATH" /usr/bin/env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        /bin/sh -c "$(user_migration_body)" flux-migrate flux "$USERNAME"
    grep -q "^$USERNAME:" "$GUESTPATH/etc/passwd" 2>/dev/null
}

ensure_chroot_tmp() {
    HOST_TMP="/data/data/${PKG}/files/usr/tmp"
    mkdir -p "$GUESTPATH/tmp" "$HOST_TMP" "$GUESTPATH/mnt/host-tmp" "$GUESTPATH/var/tmp"
    if grep -q " $GUESTPATH/tmp " /proc/mounts 2>/dev/null; then
        $BB umount "$GUESTPATH/tmp" 2>/dev/null || $BB umount -l "$GUESTPATH/tmp" 2>/dev/null || true
    fi
    chmod 1777 "$GUESTPATH/tmp" 2>/dev/null || true
    chmod 1777 "$GUESTPATH/var/tmp" 2>/dev/null || true
    chmod 1777 "$HOST_TMP" 2>/dev/null || true
    bind_if_missing "$HOST_TMP" "$GUESTPATH/mnt/host-tmp" 2>/dev/null || true
}

guest_sh() {
    $BB chroot "$GUESTPATH" /bin/sh -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $1"
}

configure_guest_chroot() {
    progress "Configuring $DISTRO_LABEL chroot environment..."

    if [ ! -d "$GUESTPATH" ]; then
        mkdir -p "$GUESTPATH" || goodbye
    fi

    progress "Mounting filesystems..."
    /system/bin/mount -o remount,dev,suid /data 2>/dev/null \
        || $BB mount -o remount,dev,suid /data 2>/dev/null \
        || $BB mount -o remount,dev,suid / 2>/dev/null || true

    bind_if_missing /dev "$GUESTPATH/dev" || goodbye
    bind_if_missing /sys "$GUESTPATH/sys" || goodbye
    mount_type_if_missing proc "$GUESTPATH/proc" || goodbye
    mount_type_if_missing devpts "$GUESTPATH/dev/pts" || goodbye
    mount_type_if_missing tmpfs "$GUESTPATH/dev/shm" "size=512M,mode=1777" || goodbye

    ensure_chroot_tmp

    bind_if_missing /sdcard "$GUESTPATH/sdcard" || goodbye

    progress "Configuring Network and Android groups..."
    guest_sh '
        rm -f /etc/resolv.conf
        echo "nameserver 8.8.8.8" > /etc/resolv.conf
        echo "nameserver 1.1.1.1" >> /etc/resolv.conf
        echo "127.0.0.1 localhost" > /etc/hosts

        if command -v groupadd >/dev/null 2>&1; then
            groupadd -g 3003 aid_inet 2>/dev/null || true
            groupadd -g 3004 aid_net_raw 2>/dev/null || true
            groupadd -g 1003 aid_graphics 2>/dev/null || true
            usermod -aG aid_inet root 2>/dev/null || true
        elif command -v addgroup >/dev/null 2>&1; then
            addgroup -g 3003 aid_inet 2>/dev/null || true
            addgroup -g 3004 aid_net_raw 2>/dev/null || true
            addgroup -g 1003 aid_graphics 2>/dev/null || true
            addgroup root aid_inet 2>/dev/null || true
        fi
    ' || goodbye

    # Never a second user next to an existing flux: rename it, else keep using flux.
    if ! migrate_flux_user; then
        error "Could not rename flux to $USERNAME; keeping user flux"
        USERNAME=flux
    fi
    progress "Creating User ($USERNAME)..."
    # Android only lets members of aid_inet (3003) open sockets and aid_net_raw (3004)
    # use raw ones (ping): without them a non-root guest user has no network.
    guest_sh "
        if ! id $USERNAME >/dev/null 2>&1; then
            if command -v useradd >/dev/null 2>&1; then
                useradd -m -s /bin/bash $USERNAME 2>/dev/null || useradd -m -s /bin/sh $USERNAME
            elif command -v adduser >/dev/null 2>&1; then
                adduser -D -s /bin/sh $USERNAME 2>/dev/null || true
            fi
            # New users only: default password is the user name (akane/akane, flux/flux).
            echo '$USERNAME:$USERNAME' | chpasswd 2>/dev/null || true
        fi
        id $USERNAME >/dev/null 2>&1 || { echo 'user $USERNAME could not be created' >&2; exit 1; }
        for g in wheel sudo audio video users aid_inet aid_net_raw; do
            grep -q \"^\$g:\" /etc/group 2>/dev/null || continue
            if command -v usermod >/dev/null 2>&1; then
                usermod -aG \$g $USERNAME 2>/dev/null || true
            else
                addgroup $USERNAME \$g 2>/dev/null || true
            fi
        done
        mkdir -p /etc/sudoers.d
        echo '$USERNAME ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/$USERNAME
        chmod 0440 /etc/sudoers.d/$USERNAME
    " || goodbye

    touch "$GUESTPATH/.flux_configured"
    # A failed earlier uninstall may have left this; the tree is valid again.
    rm -rf "$GUESTPATH/.flux_uninstalling" 2>/dev/null || true
    success "$DISTRO_LABEL environment configured (rootfs + user; XFCE via family phase)!"

    # M14: the app stages fluxlinux_chroot.sh, resolve_bb.sh and the GUI scripts into
    # the root-only, SHA-256-verified stage (/data/adb/fluxlinux/<pkg>). Nothing that
    # root later executes is copied to shell-writable /data/local/tmp any more.

    cleanup_mounts
    success "FluxLinux: $DISTRO_LABEL Chroot Setup Complete!"

    am start -a android.intent.action.VIEW \
        -d "fluxlinux://callback?result=success&name=distro_install_${DISTRO_LABEL}_chroot" \
        >/dev/null 2>&1 || true
    return 0
}

main() {
    export LD_LIBRARY_PATH="${APP_PREFIX}/lib"

    if [ "$(id -u)" != "0" ]; then
        error "This script must be run as root. Exiting."
        exit 1
    fi

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

    progress "Using Root Busybox: $BB"

    GUESTPATH="${FLUX_CHROOT:-/data/local/tmp/chrootGuest}"

    if ! valid_user_name "$USERNAME"; then
        error "Invalid FLUX_GUEST_USER: $USERNAME"
        exit 1
    fi

    # Configured as flux before akane: rename in place. When that cannot run, the
    # existing flux install stays (the app logs in as flux until it can).
    if [ -f "$GUESTPATH/.flux_configured" ] && ! migrate_flux_user; then
        error "Could not rename flux to $USERNAME yet; the existing flux user stays"
        USERNAME=flux
    fi
    if [ -f "$GUESTPATH/.flux_configured" ] && \
       { [ -e "$GUESTPATH/bin/bash" ] || [ -e "$GUESTPATH/usr/bin/bash" ]; } && \
       grep -q "^$USERNAME:" "$GUESTPATH/etc/passwd" 2>/dev/null; then
        success "$DISTRO_LABEL Chroot already installed."
        progress "VERIFY: chroot present at $GUESTPATH"
        am start -a android.intent.action.VIEW \
            -d "fluxlinux://callback?result=success&name=distro_install_${DISTRO_LABEL}_chroot" \
            >/dev/null 2>&1 || true
        exit 0
    fi

    mkdir -p "$GUESTPATH" || goodbye

    if ! resolve_rootfs_archive; then
        if [ -n "$ROOTFS_URL" ]; then
            progress "No local rootfs — downloading $ROOTFS_URL"
            download_file "$APP_PREFIX/var/lib/proot-distro/cache/rootfs" "$ROOTFS_NAME" "$ROOTFS_URL"
            ROOTFS_ARCHIVE="$APP_PREFIX/var/lib/proot-distro/cache/rootfs/$ROOTFS_NAME"
        else
            error "No local rootfs (expected $APP_HOME/$ROOTFS_NAME)"
            goodbye
        fi
    fi

    if [ -n "$ROOTFS_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
        _got="$(sha256sum "$ROOTFS_ARCHIVE" | awk '{print $1}')"
        if [ "$_got" != "$ROOTFS_SHA256" ]; then
            error "SHA256 mismatch for $ROOTFS_ARCHIVE"
            error "  expected $ROOTFS_SHA256"
            error "  got      $_got"
            goodbye
        fi
        success "SHA256 OK"
    fi

    extract_file "$GUESTPATH" "$ROOTFS_ARCHIVE"
    # Pacman CheckSpace cannot see Android bind-mount free space
    # ("could not determine cachedir mount point /var/cache/pacman/pkg").
    if [ -f "$GUESTPATH/etc/pacman.conf" ]; then
        if grep -q '^CheckSpace' "$GUESTPATH/etc/pacman.conf" 2>/dev/null; then
            sed -i 's/^CheckSpace/#CheckSpace/' "$GUESTPATH/etc/pacman.conf" || true
            progress "Disabled pacman CheckSpace (Android bind mounts)"
        fi
        mkdir -p "$GUESTPATH/var/cache/pacman/pkg"
    fi
    configure_guest_chroot
    exit 0
}

# Tests source this file with FLUX_SETUP_LIB=1 to exercise single functions.
[ "${FLUX_SETUP_LIB:-0}" = "1" ] || main "$@"

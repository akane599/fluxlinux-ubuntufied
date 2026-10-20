#!/bin/sh
# setup_guest_chroot.sh
# Generic chroot extract + user bootstrap (Fedora / Void / openSUSE).
# XFCE is installed later by the family script (onboarding XFCE phase).
#
# Env (from DistroInstallProfile / OnboardingInstallRunner):
#   FLUX_CHROOT, FLUX_ROOTFS_PATH, FLUX_ROOTFS_SHA256, FLUX_ROOTFS_NAME,
#   FLUX_ROOTFS_URL (GitHub release download fallback)
#   TERMUX_APP__PACKAGE_NAME, TERMUX__HOME

GUESTPATH="${FLUX_CHROOT:-/data/local/tmp/chrootGuest}"
USERNAME="flux"
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

PROC_MOUNTS=/proc/mounts

# True if $1 is a mount point (exact match in /proc/mounts).
is_mounted() {
    grep -q " $1 " "$PROC_MOUNTS" 2>/dev/null
}

# Mount points at or below $1, deepest first (exact path match, no regex).
mounts_under() {
    awk -v p="$1" '$2 == p || index($2, p "/") == 1 { print $2 }' \
        "$PROC_MOUNTS" 2>/dev/null | sort -r
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
        _mnts=$(mounts_under "$GUESTPATH")
        [ -z "$_mnts" ] && return 0
        for _mnt in $_mnts; do
            $BB umount "$_mnt" 2>/dev/null || $BB umount -l "$_mnt" 2>/dev/null || true
        done
        _pass=$((_pass + 1))
    done
    if [ -n "$(mounts_under "$GUESTPATH")" ]; then
        error "Still mounted under $GUESTPATH:"
        mounts_under "$GUESTPATH"
        return 1
    fi
    return 0
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

    progress "Creating User ($USERNAME)..."
    guest_sh "
        if ! id $USERNAME >/dev/null 2>&1; then
            if command -v useradd >/dev/null 2>&1; then
                useradd -m -s /bin/bash $USERNAME 2>/dev/null || useradd -m -s /bin/sh $USERNAME
            fi
            echo '$USERNAME:flux' | chpasswd 2>/dev/null || true
        fi
        if command -v usermod >/dev/null 2>&1; then
            for g in wheel audio video users aid_inet; do
                usermod -aG \$g $USERNAME 2>/dev/null || true
            done
        fi
        mkdir -p /etc/sudoers.d
        echo '$USERNAME ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/$USERNAME
        chmod 0440 /etc/sudoers.d/$USERNAME
    " || goodbye

    touch "$GUESTPATH/.flux_configured"
    # A failed earlier uninstall may have left this; the tree is valid again.
    rm -f "$GUESTPATH/.flux_uninstalling" 2>/dev/null || true
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

    if [ -f "$GUESTPATH/.flux_configured" ] && \
       { [ -e "$GUESTPATH/bin/bash" ] || [ -e "$GUESTPATH/usr/bin/bash" ]; } && \
       grep -q '^flux:' "$GUESTPATH/etc/passwd" 2>/dev/null; then
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

#!/bin/sh

# setup_debian13_chroot.sh
# Installs a Debian 13 (Trixie) Chroot environment (Requires Root)
# Based on LinuxDroidMaster/Termux-Desktops Guide

# Global Variables
DEBIANPATH="/data/local/tmp/chrootDebian13"
USERNAME="flux"

# Same pinned rootfs as proot path (app assets → deploy to $HOME)
PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
APP_HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
APP_PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
ROOTFS_NAME="debian_13_rootfs.tar.xz"
ROOTFS_URL="${FLUX_ROOTFS_URL:-https://github.com/abhay-byte/fluxlinux/releases/download/rootfs/debian_13_rootfs.tar.xz}"
ROOTFS_SHA256="${FLUX_ROOTFS_SHA256:-13e29f6099c3b805e84694507ede460c03886ffb364c03317272691cf84e6803}"

# Function to show progress message
progress() {
    printf "\033[1;36m[+] %s\033[0m\n" "$1"
}

# Function to show success message
success() {
    printf "\033[1;32m[✓] %s\033[0m\n" "$1"
}

# Function to show error message
error() {
    printf "\033[1;31m[!] %s\033[0m\n" "$1"
}

PROC_MOUNTS=/proc/mounts

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

# Unmount everything under the rootfs, looping while anything is still mounted
# (stacked layers need several passes). 0 only when nothing remains (L6).
cleanup_mounts() {
    progress "Safety Check: Unmounting filesystems..."
    _pass=0
    while [ "$_pass" -lt 5 ]; do
        # Fail closed (H4 review): unknown mount state counts as still mounted.
        _mnts=$(mounts_under "$DEBIANPATH") || break
        if [ -z "$_mnts" ]; then
            no_mounts_at_or_under "$DEBIANPATH" && return 0
            break
        fi
        for _mnt in $_mnts; do
            $BB umount "$_mnt" 2>/dev/null || $BB umount -l "$_mnt" 2>/dev/null || true
        done
        _pass=$((_pass + 1))
    done
    if _mnts=$(mounts_under "$DEBIANPATH") && [ -z "$_mnts" ] \
        && no_mounts_at_or_under "$DEBIANPATH"; then
        return 0
    fi
    error "Still mounted (or mounts unreadable) under $DEBIANPATH:"
    grep -F " $DEBIANPATH" "$PROC_MOUNTS" 2>/dev/null
    return 1
}


# Exit handler
goodbye() {
    error "Something went wrong."
    cleanup_mounts
    error "Exiting..."
    exit 1
}

# Resolve app-packaged / manual / download rootfs (same file as flux_install.sh)
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
        "/sdcard/Download/rootfs.tar.xz" \
        "/storage/emulated/0/Download/$ROOTFS_NAME" \
        "/storage/emulated/0/Download/rootfs.tar.xz" \
        "$DEBIANPATH/$ROOTFS_NAME" \
        "$DEBIANPATH/rootfs.tar.xz"
    do
        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            ROOTFS_ARCHIVE="$candidate"
            progress "rootfs found: $ROOTFS_ARCHIVE"
            return 0
        fi
    done

    return 1
}

# Download Helper (fallback only — prefer app-local archive)
download_file() {
    # $1=dir $2=filename $3=url
    progress "Downloading file..."
    if [ -e "$1/$2" ] && [ -s "$1/$2" ]; then
        printf "\033[1;33m[!] File already exists: %s\033[0m\n" "$2"
        printf "\033[1;33m[!] Skipping download...\033[0m\n"
        return 0
    fi
    mkdir -p "$1" 2>/dev/null || true
    if command -v wget >/dev/null 2>&1; then
        wget -O "$1/$2" "$3"
        if [ $? -eq 0 ]; then
            success "File downloaded successfully: $2"
            return 0
        fi
        error "Standard wget failed: $2."
    fi
    progress "Trying Busybox wget..."
    $BB wget -O "$1/$2" "$3"
    if [ $? -eq 0 ]; then
        success "File downloaded successfully (Fallback)"
        return 0
    fi
    goodbye
}

# Extraction Helper — archive path is $2 (or $1/rootfs.tar.xz)
extract_archive() {
    # $1=dest root  $2=optional archive path
    _dest="$1"
    _archive="${2:-$_dest/rootfs.tar.xz}"
    progress "Extracting file from $_archive ..."
    if [ ! -f "$_archive" ] || [ ! -s "$_archive" ]; then
        error "Rootfs archive missing: $_archive"
        goodbye
    fi

    if tar xpvf "$_archive" -C "$_dest" --numeric-owner >/dev/null 2>&1; then
        success "Rootfs extracted successfully."
        return 0
    fi

    progress "Standard extract failed. Trying unxz pipe..."
    UNXZ_CMD="unxz"
    if ! command -v unxz >/dev/null 2>&1; then
        if "$BB" unxz --help >/dev/null 2>&1; then
            UNXZ_CMD="$BB unxz"
        else
            error "No 'unxz' tool found. Cannot extract .tar.xz file."
            goodbye
        fi
    fi

    if $UNXZ_CMD -c "$_archive" | tar xpv -C "$_dest" --numeric-owner >/dev/null 2>&1; then
         success "Rootfs extracted successfully (via unxz pipe)."
         return 0
    fi

    if tar xJvf "$_archive" -C "$_dest" --numeric-owner >/dev/null 2>&1; then
         success "Rootfs extracted successfully (Fallback flags)."
         return 0
    fi

    error "Extraction Failed! Your Busybox/Tar does not support XZ compression."
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
    if [ -e "$_dest/$EXTRACT_MARKER" ] || [ -e "$_dest/.flux_configured" ]; then
        printf "\033[1;33m[!] Rootfs already extracted: %s\033[0m\n" "$_dest"
        return 0
    fi
    if [ -L "$_dest/bin/sh" ] || [ -e "$_dest/bin/sh" ] || [ -e "$_dest/bin/bash" ]; then
        printf "\033[1;33m[!] Rootfs is incomplete (no %s) — re-extracting: %s\033[0m\n" \
            "$EXTRACT_MARKER" "$_dest"
    fi
    # Never let tar write through a leftover bind (/dev, /sdcard) into the host.
    if ! cleanup_mounts; then
        error "Refusing to extract while filesystems are mounted under $_dest"
        goodbye
    fi
    extract_archive "$@"
    mark_extracted "$_dest"
}


# Sticky disk-backed /tmp for apt (_apt). Host stages scripts at
# $DEBIANPATH/tmp (same path as chroot /tmp) — no tmpfs overlay.
# NEVER bind app files/usr/tmp onto /tmp (app_data perms/SELinux break mkstemp).
# Host app tmp is bridged at /mnt/host-tmp only.
ensure_chroot_tmp() {
    HOST_TMP="${APP_PREFIX}/tmp"
    mkdir -p "$DEBIANPATH/tmp" "$HOST_TMP" "$DEBIANPATH/mnt/host-tmp" "$DEBIANPATH/var/tmp"

    # Drop previous bad bind/tmpfs on /tmp if present
    if grep -q " $DEBIANPATH/tmp " /proc/mounts 2>/dev/null; then
        $BB umount "$DEBIANPATH/tmp" 2>/dev/null || $BB umount -l "$DEBIANPATH/tmp" 2>/dev/null || true
    fi

    chmod 1777 "$DEBIANPATH/tmp" 2>/dev/null || true
    chmod 1777 "$DEBIANPATH/var/tmp" 2>/dev/null || true
    progress "Sticky /tmp ready at $DEBIANPATH/tmp (mode 1777, no host bind)"

    chmod 1777 "$HOST_TMP" 2>/dev/null || true
    $BB mount --bind "$HOST_TMP" "$DEBIANPATH/mnt/host-tmp" 2>/dev/null || true

    if [ -f "$HOST_TMP/launch_tool.sh" ]; then
        cp -f "$HOST_TMP/launch_tool.sh" "$DEBIANPATH/tmp/launch_tool.sh" 2>/dev/null || true
        chmod 755 "$DEBIANPATH/tmp/launch_tool.sh" 2>/dev/null || true
    fi
}

# Main Configuration Logic
configure_debian_chroot() {
    progress "Configuring Debian chroot environment..."

    if [ ! -d "$DEBIANPATH" ]; then
        mkdir -p "$DEBIANPATH"
        [ $? -ne 0 ] && goodbye
    fi

    progress "Mounting filesystems..."
    # Soft remount: busybox often cannot resolve /data on Magisk/KSU (not fatal)
    # Prefer system mount — busybox often "can't find /data" on KSU (sudo stays nosuid)
    /system/bin/mount -o remount,dev,suid /data 2>/dev/null || $BB mount -o remount,dev,suid /data 2>/dev/null || $BB mount -o remount,dev,suid / 2>/dev/null || true

    $BB mount --bind /dev "$DEBIANPATH/dev" || /system/bin/mount --bind /dev "$DEBIANPATH/dev" || goodbye
    $BB mount --bind /sys "$DEBIANPATH/sys" || /system/bin/mount --bind /sys "$DEBIANPATH/sys" || goodbye
    $BB mount -t proc proc "$DEBIANPATH/proc" || /system/bin/mount -t proc proc "$DEBIANPATH/proc" || goodbye
    $BB mount -t devpts devpts "$DEBIANPATH/dev/pts" || /system/bin/mount -t devpts devpts "$DEBIANPATH/dev/pts" || goodbye

    mkdir -p "$DEBIANPATH/dev/shm"
    $BB mount -t tmpfs -o size=512M,mode=1777 tmpfs "$DEBIANPATH/dev/shm" || /system/bin/mount -t tmpfs -o size=512M,mode=1777 tmpfs "$DEBIANPATH/dev/shm" || goodbye

    # Debian /tmp must be sticky world-writable for apt (_apt).
    # Do NOT bind Termux files/usr/tmp here — app_data perms/SELinux break mkstemp.
    ensure_chroot_tmp

    mkdir -p "$DEBIANPATH/sdcard"
    $BB mount --bind /sdcard "$DEBIANPATH/sdcard" || /system/bin/mount --bind /sdcard "$DEBIANPATH/sdcard" || goodbye

    progress "Configuring Network and Groups..."
    $BB chroot "$DEBIANPATH" /bin/bash -c '
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        rm -f /etc/resolv.conf
        echo "nameserver 8.8.8.8" > /etc/resolv.conf
        echo "127.0.0.1 localhost" > /etc/hosts

        groupadd -g 3003 aid_inet 2>/dev/null
        groupadd -g 3004 aid_net_raw 2>/dev/null
        groupadd -g 1003 aid_graphics 2>/dev/null

        usermod -g 3003 -G 3003,3004 -a _apt 2>/dev/null || true
        usermod -G 3003 -a root 2>/dev/null || true

        echo "Testing Network..."
        if ping -c 1 8.8.8.8 >/dev/null 2>&1; then
            echo " [OK] Network is working."
        else
            echo " [!] Network check failed. Apt might fail."
        fi
    ' || goodbye

    progress "Updating packages (apt update/upgrade)..."
    $BB chroot "$DEBIANPATH" /bin/bash -c '
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        export DEBIAN_FRONTEND=noninteractive
        # apt (_apt) requires sticky world-writable /tmp
        chmod 1777 /tmp /var/tmp 2>/dev/null || true
        # Drop stale indexes from a previous failed signature pass
        rm -rf /var/lib/apt/lists/*
        mkdir -p /var/lib/apt/lists/partial
        chmod 755 /var/lib/apt/lists /var/lib/apt/lists/partial
        # Quick write probe (fails fast with a clear message)
        if ! su -s /bin/sh _apt -c "echo ok > /tmp/.apt_write_probe" 2>/dev/null; then
            # _apt may not exist yet on minimal rootfs — probe as nobody if present
            if ! touch /tmp/.apt_write_probe 2>/dev/null; then
                echo "[!] /tmp is not writable — apt will fail"
                exit 1
            fi
        fi
        rm -f /tmp/.apt_write_probe
        apt-get update -y
        apt-get upgrade -y
        apt-get install -y nano vim net-tools sudo git dbus-x11 wget unzip
    ' || goodbye

    progress "Creating User ($USERNAME)..."
    $BB chroot "$DEBIANPATH" /bin/bash -c "
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        groupadd storage 2>/dev/null
        groupadd wheel 2>/dev/null
        id -u $USERNAME >/dev/null 2>&1 || useradd -m -g users -G wheel,audio,video,storage,aid_inet -s /bin/bash $USERNAME
        echo '$USERNAME:flux' | chpasswd
    " || goodbye

    progress "Configuring Sudoers..."
    $BB chroot "$DEBIANPATH" /bin/bash -c "
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        echo '$USERNAME ALL=(ALL:ALL) NOPASSWD:ALL' > /etc/sudoers.d/$USERNAME
        chmod 0440 /etc/sudoers.d/$USERNAME
    " || goodbye

    progress "Installing XFCE4..."
    $BB chroot "$DEBIANPATH" /bin/bash -c '
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        export DEBIAN_FRONTEND=noninteractive
        chmod 1777 /tmp /var/tmp 2>/dev/null || true
        apt-get install -y xfce4 xfce4-terminal
    ' || goodbye

    touch "$DEBIANPATH/.flux_configured"
    # A failed earlier uninstall may have left this; the tree is valid again.
    rm -rf "$DEBIANPATH/.flux_uninstalling" 2>/dev/null || true
    success "Debian Environment Configured!"

    # --- thin compat wrappers (M14) ---
    # The app stages fluxlinux_chroot.sh, resolve_bb.sh and the GUI start/stop scripts into
    # the root-only, SHA-256-verified stage. Root-run wrappers live there too, never in
    # shell-writable /data/local/tmp (the shell uid could swap them before su runs them).
    STAGE_PARENT="/data/adb/fluxlinux"
    STAGE_DIR="$STAGE_PARENT/$PKG"
    rm -f /data/local/tmp/start_debian13.sh /data/local/tmp/stop_debian13_gui.sh \
        /data/local/tmp/run_debian13_root.sh /data/local/tmp/enter_debian13.sh \
        /data/local/tmp/enter_debian13_root.sh 2>/dev/null || true
    if ! { ( umask 077; mkdir -p "$STAGE_DIR" ) && [ ! -L "$STAGE_PARENT" ] && [ ! -L "$STAGE_DIR" ] && \
        chown 0:0 "$STAGE_PARENT" "$STAGE_DIR" && chmod 0700 "$STAGE_PARENT" "$STAGE_DIR"; }; then
        printf "\033[1;33m[!] Root-only stage %s unavailable; compat launchers skipped\033[0m\n" "$STAGE_DIR"
    else
    LAUNCH_SCRIPT="$STAGE_DIR/start_debian13.sh"
    progress "Creating thin GUI launch script at $LAUNCH_SCRIPT..."

    # Expand STAGE_DIR / APP_PREFIX / PKG at write time (unquoted EOF); keep runtime vars escaped.
    cat <<EOF > "$LAUNCH_SCRIPT"
#!/system/bin/sh
# Compat one-shot GUI → prefer start_debian13_gui.sh (app SSOT) else helper mount --x11 + xfce
GUI=$STAGE_DIR/start_debian13_gui.sh
HELPER=$STAGE_DIR/fluxlinux_chroot.sh
DEBIANPATH="\${DEBIANPATH:-/data/local/tmp/chrootDebian13}"
TARGET_PREFIX="\${TARGET_PREFIX:-$APP_PREFIX}"
USERNAME="\${USERNAME:-flux}"
export FLUX_PACKAGE="$PKG"

if [ -f "\$GUI" ]; then
  exec sh "\$GUI"
fi

if [ ! -f "\$HELPER" ]; then
  echo "fluxlinux_chroot.sh missing — open app chroot session or re-run setup" >&2
  exit 127
fi

export FLUX_CHROOT="\$DEBIANPATH"
export FLUX_PREFIX="\$TARGET_PREFIX"
export FLUX_HOST_TMP="\${TARGET_PREFIX}/tmp"
sh "\$HELPER" mount --x11 || true
sh "\$HELPER" sh --user root -- \\
  "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel dbus-launch dbus-daemon 2>/dev/null; true" \\
  >/dev/null 2>&1 || true
echo "Starting Debian 13 Chroot GUI (\$USERNAME) via SSOT helper..."
echo "NOTE: Prefer app Settings START (start_gui_chroot.sh) for Pulse/X11 host stack."
exec sh "\$HELPER" sh --user "\$USERNAME" -- \\
  'export DISPLAY=:0 PULSE_SERVER=unix:/mnt/host-tmp/pulse-native XDG_RUNTIME_DIR=/tmp VTEST_SOCKET_NAME=/mnt/host-tmp/.virgl_test; xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null; exec dbus-launch --exit-with-session startxfce4'
EOF
    chmod 700 "$LAUNCH_SCRIPT"
    success "GUI Launch script created (SSOT thin wrapper)."
    # No separate stop wrapper: the app stages the verified stop_debian13_gui.sh into
    # $STAGE_DIR on every desktop Stop (stop_gui_chroot.sh).

    ROOT_RUNNER="$STAGE_DIR/run_debian13_root.sh"
    progress "Creating thin root runner → SSOT at $ROOT_RUNNER..."
    cat <<'EOF' > "$ROOT_RUNNER"
#!/system/bin/sh
# Compat wrapper → fluxlinux_chroot.sh exec --user root
HELPER=@FLUX_STAGE_DIR@/fluxlinux_chroot.sh
if [ ! -f "$HELPER" ]; then
  echo "fluxlinux_chroot.sh missing — open a chroot session once or re-run setup" >&2
  exit 127
fi
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <command> [args...]" >&2
  exit 1
fi
exec sh "$HELPER" exec --user root -- "$@"
EOF
    sed -i "s|@FLUX_STAGE_DIR@|$STAGE_DIR|" "$ROOT_RUNNER"
    chmod 700 "$ROOT_RUNNER"
    success "Root runner created (SSOT wrapper)."

    CLI_SCRIPT="$STAGE_DIR/enter_debian13.sh"
    progress "Creating thin CLI launcher at $CLI_SCRIPT..."
    cat <<'EOF' > "$CLI_SCRIPT"
#!/system/bin/sh
# Compat → fluxlinux_chroot.sh login --user flux
HELPER=@FLUX_STAGE_DIR@/fluxlinux_chroot.sh
[ -f "$HELPER" ] || { echo "fluxlinux_chroot.sh missing" >&2; exit 127; }
echo "Entering Debian 13 Chroot (CLI)..."
exec sh "$HELPER" login --user flux --shell zsh
EOF
    sed -i "s|@FLUX_STAGE_DIR@|$STAGE_DIR|" "$CLI_SCRIPT"
    chmod 700 "$CLI_SCRIPT"
    success "CLI Launcher created: su -c sh $CLI_SCRIPT"

    ROOT_CLI_SCRIPT="$STAGE_DIR/enter_debian13_root.sh"
    progress "Creating thin root CLI launcher at $ROOT_CLI_SCRIPT..."
    cat <<'EOF' > "$ROOT_CLI_SCRIPT"
#!/system/bin/sh
# Compat → fluxlinux_chroot.sh login --user root
HELPER=@FLUX_STAGE_DIR@/fluxlinux_chroot.sh
[ -f "$HELPER" ] || { echo "fluxlinux_chroot.sh missing" >&2; exit 127; }
echo "Entering Debian 13 Chroot as ROOT..."
exec sh "$HELPER" login --user root --shell bash
EOF
    sed -i "s|@FLUX_STAGE_DIR@|$STAGE_DIR|" "$ROOT_CLI_SCRIPT"
    chmod 700 "$ROOT_CLI_SCRIPT"
    success "Root CLI Launcher created: su -c sh $ROOT_CLI_SCRIPT"
    fi

    cleanup_mounts
    success "NativeCode: Chroot Setup Complete!"

    # Notify NativeCode app (non-fatal: often fails under pure root / no app context)
    am start -a android.intent.action.VIEW -d "fluxlinux://callback?result=success&name=distro_install_debian13_chroot" >/dev/null 2>&1 || true
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

    DEBIANPATH="/data/local/tmp/chrootDebian13"

    # Already installed only with the configured marker (M2): a shell alone may be a
    # tree whose extraction was interrupted; extract_file re-extracts over it.
    if [ -f "$DEBIANPATH/.flux_configured" ]; then
        success "Debian 13 Chroot already installed."
        progress "Skipping installation (rootfs at $DEBIANPATH)..."
        rm -rf "$DEBIANPATH/.flux_uninstalling" 2>/dev/null || true
        # Print probe lines the app can match in logs
        progress "VERIFY: chroot present (marker)"
        am start -a android.intent.action.VIEW -d "fluxlinux://callback?result=success&name=distro_install_debian13_chroot" >/dev/null 2>&1 || true
        exit 0
    fi

    if [ ! -d "$DEBIANPATH" ]; then
        mkdir -p "$DEBIANPATH"
        success "Created directory: $DEBIANPATH"
    fi

    # Prefer app-deployed rootfs (same as flux_install / assets/rootfs/)
    if resolve_rootfs_archive; then
        :
    else
        progress "No local rootfs — downloading $ROOTFS_URL"
        download_file "$DEBIANPATH" "$ROOTFS_NAME" "$ROOTFS_URL"
        ROOTFS_ARCHIVE="$DEBIANPATH/$ROOTFS_NAME"
    fi

    # Optional SHA check (when sha256sum available)
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

    extract_file "$DEBIANPATH" "$ROOTFS_ARCHIVE"
    configure_debian_chroot
    exit 0
}

[ "${FLUX_SETUP_LIB:-0}" = "1" ] || main "$@"

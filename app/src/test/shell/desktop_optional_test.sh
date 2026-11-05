#!/bin/sh
# Optional desktop: FLUX_INSTALL_DESKTOP=0 (family + pulse helper) and
# FLUX_CUSTOMIZE_DESKTOP=0 (customization) skip only the XFCE parts; unset keeps
# the desktop install. Runs the scripts (or their extracted pieces) with stub
# helpers and package managers; absolute paths are rewritten into the scratch dir.
# Usage: desktop_optional_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
FAMILY="$ASSETS/scripts/ubuntu/common/setup/setup_ubuntu_family.sh"
COMMON="$ASSETS/scripts/common/setup/flux_guest_common.sh"
CUSTOM="$ASSETS/scripts/common/setup/setup_customization_xfce.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
has() { grep -qF -- "$2" "$1" 2>/dev/null; }

for f in "$FAMILY" "$COMMON" "$CUSTOM"; do
    sh -n "$f" || fail "sh -n $f"
done

mkdir -p "$T/bin"
for tool in grep sed cat printf mkdir rm mv cp ls find awk tr cut head dirname basename chmod touch; do
    _p=$(command -v "$tool") && ln -sf "$_p" "$T/bin/$tool"
done

# ── setup_ubuntu_family.sh ─────────────────────────────────────────────────
# Stub helpers record their calls; apt-get records its args (FAIL_TARGETED=1 fails
# the targeted XFCE list so the metapackage fallback runs).
cat > "$T/bin/apt-get" <<EOF
#!/bin/sh
echo "\$*" >> "\$CALLS.apt"
case "\$*" in *xfce4-session*) [ "\${FAIL_TARGETED:-0}" = 1 ] && exit 100 ;; esac
exit 0
EOF
printf '#!/bin/sh\necho 0\n' > "$T/bin/id"
chmod +x "$T/bin/apt-get" "$T/bin/id"
{
    for fn in _flux_log _flux_ensure_tmp _flux_ensure_dns _flux_disable_guest_selinux \
        _flux_apt_prep _flux_ensure_dbus _flux_ensure_en_us_locale _flux_require_startxfce4 \
        _flux_ensure_groups _flux_ensure_user _flux_ensure_sudo _flux_ensure_home \
        _flux_setup_pulse _flux_write_gpu_mode _flux_fix_pm_writable; do
        echo "$fn() { echo \"$fn \$*\" >> \"\$CALLS.fn\"; }"
    done
    # Mirror rewrite reads/writes /etc/apt: keep it inside the scratch dir.
    sed "s|/etc/apt/|$T/etc/apt/|g" "$FAMILY"
} > "$T/family.sh"
mkdir -p "$T/etc/apt/sources.list.d"
printf 'deb http://archive.ubuntu.com/ubuntu resolute main\n' > "$T/etc/apt/sources.list"

run_family() { # $1 = case name, rest = env assignments
    _case=$1; shift
    CALLS="$T/fam.$_case"
    : > "$CALLS.apt"; : > "$CALLS.fn"
    env -i PATH="$T/bin" CALLS="$CALLS" "$@" /bin/sh "$T/family.sh" ubuntu > "$CALLS.out" 2>&1
    echo $? > "$CALLS.rc"
}

run_family off FLUX_INSTALL_DESKTOP=0
C="$T/fam.off"
[ "$(cat "$C.rc")" = 0 ] || fail "family off: exit $(cat "$C.rc"): $(tail -5 "$C.out")"
has "$C.apt" "xfce4" && fail "family off: XFCE apt install ran: $(grep xfce4 "$C.apt")"
has "$C.apt" "coreutils-from-gnu" || fail "family off: GNU coreutils switch missing"
has "$C.apt" "git zsh python3" || fail "family off: base packages missing"
has "$C.fn" "_flux_require_startxfce4" && fail "family off: startxfce4 required"
has "$C.fn" "_flux_write_gpu_mode" && fail "family off: gpu mode written"
for fn in _flux_ensure_en_us_locale _flux_ensure_user _flux_ensure_sudo _flux_ensure_home \
    _flux_setup_pulse _flux_fix_pm_writable; do
    has "$C.fn" "$fn" || fail "family off: $fn not called"
done
has "$C.out" "terminal-only: skipping XFCE" || fail "family off: no terminal-only log line"

for c in unset on; do
    if [ "$c" = on ]; then run_family on FLUX_INSTALL_DESKTOP=1; else run_family unset; fi
    C="$T/fam.$c"
    [ "$(cat "$C.rc")" = 0 ] || fail "family $c: exit $(cat "$C.rc"): $(tail -5 "$C.out")"
    has "$C.apt" "xfce4-session xfce4-panel" || fail "family $c: targeted XFCE list missing"
    has "$C.fn" "_flux_require_startxfce4" || fail "family $c: startxfce4 not required"
    has "$C.fn" "_flux_write_gpu_mode virgl" || fail "family $c: gpu mode not written"
    has "$C.out" "terminal-only" && fail "family $c: says terminal-only"
done

run_family fallback FAIL_TARGETED=1
grep -qx -- "install -y --no-install-recommends xfce4" "$T/fam.fallback.apt" \
    || fail "family desktop: xfce4 metapackage fallback missing"
run_family fallback_off FAIL_TARGETED=1 FLUX_INSTALL_DESKTOP=0
has "$T/fam.fallback_off.apt" "xfce4" && fail "family off: metapackage fallback ran"

# ── flux_guest_common.sh _flux_install_pulse_client ────────────────────────
for fn in _flux_apt_pulse_plugin _flux_install_pulse_client; do
    awk -v n="$fn" 'index($0, n "() {") == 1 { p = 1 } p { print } p && /^}$/ { exit }' \
        "$COMMON" >> "$T/pulse_fns.sh"
    grep -q "^$fn() {" "$T/pulse_fns.sh" || fail "$fn not found in $COMMON"
done
cat > "$T/bin/dpkg-query" <<'EOF'
#!/bin/sh
printf '%s' "${DPKG_STATUS:-}"
EOF
chmod +x "$T/bin/dpkg-query"
cat > "$T/pulse.sh" <<EOF
_flux_log() { :; }
_flux_is_chimera() { return 1; }
_flux_mask_pipewire_pulse() { :; }
_flux_guest_pactl() { [ -n "\${PACTL:-}" ] && echo "\$PACTL"; }
_flux_try_install() { echo "\$*" >> "\$CALLS"; return 0; }
. "$T/pulse_fns.sh"
_flux_install_pulse_client
EOF
run_pulse() { # $1 = case name, rest = env assignments
    CALLS="$T/pulse.$1"; shift
    : > "$CALLS"
    env -i PATH="$T/bin" CALLS="$CALLS" "$@" /bin/sh "$T/pulse.sh" >/dev/null 2>&1 \
        || fail "pulse: $CALLS exited non-zero"
}
run_pulse off FLUX_INSTALL_DESKTOP=0
grep -qx "libpulse0 pulseaudio-utils" "$T/pulse.off" \
    || fail "pulse off: expected 'libpulse0 pulseaudio-utils', got: $(cat "$T/pulse.off")"
has "$T/pulse.off" "xfce4-pulseaudio-plugin" && fail "pulse off: panel plugin requested"
run_pulse on
grep -qx "libpulse0 pulseaudio-utils xfce4-pulseaudio-plugin" "$T/pulse.on" \
    || fail "pulse desktop: plugin not requested, got: $(cat "$T/pulse.on")"
run_pulse addon PACTL=/usr/bin/pactl DPKG_STATUS="unknown ok not-installed"
grep -qx "xfce4-pulseaudio-plugin" "$T/pulse.addon" \
    || fail "pulse add-on: plugin not added next to an existing pactl: $(cat "$T/pulse.addon")"
run_pulse addon_have PACTL=/usr/bin/pactl DPKG_STATUS="install ok installed"
[ -s "$T/pulse.addon_have" ] && fail "pulse add-on: reinstalled a present plugin"
run_pulse present_off PACTL=/usr/bin/pactl FLUX_INSTALL_DESKTOP=0
[ -s "$T/pulse.present_off" ] && fail "pulse off: installed with pactl present: $(cat "$T/pulse.present_off")"

# ── setup_customization_xfce.sh ────────────────────────────────────────────
GUARD='if [ "${FLUX_CUSTOMIZE_DESKTOP:-1}" != 0 ]; then'
start=$(awk '/^ASSET_REPO=/ { print NR; exit }' "$CUSTOM")
chown_cfg=$(awk 'index($0, "_flux_chown_r \"$CUSTOM_USER:$CUSTOM_GROUP\" \"$USER_HOME/.config\"") == 1 { print NR; exit }' "$CUSTOM")
[ -n "$start" ] && [ -n "$chown_cfg" ] || fail "customization: block anchors not found"
start=${start:-1}; chown_cfg=${chown_cfg:-1}
[ "$(sed -n "$((start - 1))p" "$CUSTOM")" = "$GUARD" ] \
    || fail "customization: no FLUX_CUSTOMIZE_DESKTOP guard right before ASSET_REPO"
# The guard's `fi` is the last code line before the shared .config chown.
end=$(awk -v lim="$chown_cfg" 'NR < lim && $0 !~ /^[[:space:]]*(#|$)/ { last = NR } END { print last }' "$CUSTOM")
[ "$(sed -n "${end}p" "$CUSTOM")" = "fi" ] || fail "customization: guard not closed before the .config chown"
sed -n "$((start - 1)),${end}p" "$CUSTOM" > "$T/block.sh"
sh -n "$T/block.sh" || fail "customization: guarded block is not one balanced if/fi"

line_of() { awk -v s="$1" 'index($0, s) == 1 { print NR; exit }' "$CUSTOM"; }
for m in 'theme_is_installed() {' 'download_if_needed() {' '_flux_archive_ok() {' \
    '_flux_papirus_pkg_fallback() {' 'WALLPAPER_DIR=' 'FONT_DIR=' 'cat > "$XFCONF_DIR/xsettings.xml"' \
    'cat > "$USER_HOME/.config/gtk-3.0/settings.ini"' 'cat > "$XFCONF_DIR/xfce4-desktop.xml"'; do
    n=$(line_of "$m")
    [ -n "$n" ] && [ "$n" -gt "$start" ] && [ "$n" -lt "$end" ] \
        || fail "customization: desktop piece '$m' (line ${n:-none}) outside the guard ($start..$end)"
done
for m in '_flux_git_clone() {' 'echo "FluxLinux: Installing Oh My Zsh..."' 'POKEMON_SHA=' \
    'ZSHRC="$USER_HOME/.zshrc"' 'ZPROFILE=' '_flux_write_fastfetch_preset "$USER_HOME"' \
    'if command -v zsh >/dev/null 2>&1; then'; do
    n=$(line_of "$m")
    [ -n "$n" ] && [ "$n" -gt "$end" ] \
        || fail "customization: terminal piece '$m' (line ${n:-none}) not after the guard (end $end)"
done

# Package pass: desktop-only packages follow the flag; zypper never gets curl.
pkg_from=$(line_of 'echo "FluxLinux: Installing customization packages..."')
pkg_to=$(line_of '# Fail closed: guest OMZ')
if [ -z "$pkg_from" ] || [ -z "$pkg_to" ]; then
    fail "customization: package pass anchors not found"
else
    sed -n "${pkg_from},$((pkg_to - 1))p" "$CUSTOM" > "$T/pkgpass_body.sh"
    cat > "$T/pkgpass.sh" <<EOF
_flux_pkg_add() { echo "\$*" >> "\$CALLS"; return 0; }
handle_error() { echo "handle_error \$*" >> "\$CALLS"; exit 1; }
. "$T/pkgpass_body.sh"
EOF
    mkdir -p "$T/zbin"
    printf '#!/bin/sh\nexit 0\n' > "$T/zbin/zypper"
    chmod +x "$T/zbin/zypper"
    run_pkg() { # $1 = case, $2 = PATH, rest = env
        CALLS="$T/pkg.$1"; _path=$2; shift 2
        : > "$CALLS"
        env -i PATH="$_path" CALLS="$CALLS" "$@" /bin/sh "$T/pkgpass.sh" >/dev/null 2>&1 \
            || fail "package pass $CALLS exited non-zero"
    }
    run_pkg off "$T/bin" FLUX_CUSTOMIZE_DESKTOP=0
    grep -qx "curl wget git zsh bash" "$T/pkg.off" || fail "pkg off: $(cat "$T/pkg.off")"
    for p in unzip fontconfig xz xfce4-screenshooter; do
        has "$T/pkg.off" "$p" && fail "pkg off: desktop package $p requested"
    done
    has "$T/pkg.off" "fastfetch" || fail "pkg off: fastfetch missing"
    run_pkg on "$T/bin"
    grep -qx "curl wget unzip fontconfig git zsh bash" "$T/pkg.on" || fail "pkg on: $(cat "$T/pkg.on")"
    grep -qx "xz-utils" "$T/pkg.on" || fail "pkg on: xz-utils missing"
    grep -qx "xfce4-screenshooter" "$T/pkg.on" || fail "pkg on: xfce4-screenshooter missing"
    run_pkg zyp_on "$T/zbin:$T/bin"
    grep -qx "wget unzip fontconfig git zsh bash" "$T/pkg.zyp_on" || fail "pkg zypper: $(cat "$T/pkg.zyp_on")"
    run_pkg zyp_off "$T/zbin:$T/bin" FLUX_CUSTOMIZE_DESKTOP=0
    grep -qx "wget git zsh bash" "$T/pkg.zyp_off" || fail "pkg zypper off: $(cat "$T/pkg.zyp_off")"
    has "$T/pkg.zyp_off" "curl" && fail "pkg zypper off: curl requested"
fi

# Guarded block, run for real against a scratch tree: on writes the xfconf
# settings, off leaves the home untouched; both fall through to what follows.
# Stubs only (never chmod the symlinked host tools).
for tool in wget curl unzip; do
    printf '#!/bin/sh\nexit 1\n' > "$T/bin/$tool"
    chmod +x "$T/bin/$tool"
done
for tool in fc-list fc-cache gtk-update-icon-cache tar; do
    printf '#!/bin/sh\nexit 0\n' > "$T/bin/$tool"
    chmod +x "$T/bin/$tool"
done
ln -sf "$(command -v mktemp)" "$T/bin/mktemp"
sed -e "s#\([^A-Za-z0-9_]\)/var/tmp/#\1$T/var/tmp/#g" \
    -e "s#\([^A-Za-z0-9_]\)/tmp/#\1$T/tmp/#g" \
    -e "s#\([^A-Za-z0-9_]\)/usr/share/#\1$T/usr/share/#g" \
    "$T/block.sh" > "$T/block_rooted.sh"
cat > "$T/runblock.sh" <<EOF
_flux_chown() { :; }
_flux_chown_r() { :; }
_flux_fetch() { return 1; }
_flux_sha256_ok() { return 1; }
_flux_pkg_add() { return 1; }
CUSTOM_USER=akane CUSTOM_GROUP=akane
USER_HOME="\$H" THEME_DIR="$T/usr/share/themes" ICON_DIR="$T/usr/share/icons"
FLUX_ASSET_DIR="$T/assets" FLUX_THEME=dark FLUX_PROOT=1
. "$T/block_rooted.sh"
echo FLUX_AFTER_BLOCK
EOF
mkdir -p "$T/tmp" "$T/var/tmp"
for c in on off; do
    H="$T/home.$c"; mkdir -p "$H"
    if [ "$c" = off ]; then
        env -i PATH="$T/bin" H="$H" FLUX_CUSTOMIZE_DESKTOP=0 /bin/sh "$T/runblock.sh" > "$T/block.$c.out" 2>&1
    else
        env -i PATH="$T/bin" H="$H" /bin/sh "$T/runblock.sh" > "$T/block.$c.out" 2>&1
    fi
    has "$T/block.$c.out" FLUX_AFTER_BLOCK || fail "block $c: did not fall through: $(tail -5 "$T/block.$c.out")"
done
[ -f "$T/home.on/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml" ] \
    || fail "block on: xsettings.xml not written: $(tail -5 "$T/block.on.out")"
[ -f "$T/home.on/.config/gtk-3.0/settings.ini" ] || fail "block on: gtk-3.0 settings not written"
[ -z "$(ls -A "$T/home.off")" ] || fail "block off: wrote into the home: $(ls -A "$T/home.off")"
[ -s "$T/block.off.out" ] && [ "$(cat "$T/block.off.out")" != FLUX_AFTER_BLOCK ] \
    && fail "block off: ran desktop steps: $(head -3 "$T/block.off.out")"

if [ "$FAILS" -ne 0 ]; then
    echo "desktop_optional_test: $FAILS failure(s)"
    exit 1
fi
echo "desktop_optional_test: OK"

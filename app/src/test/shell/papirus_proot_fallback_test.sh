#!/bin/sh
# Regression test: under PRoot the category-gate fallback must never pull the
# full papirus-icon-theme package (100k+ files, minutes of dpkg unpack); in a
# chroot it still runs, with apt Recommends off.
# Usage: papirus_proot_fallback_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
SCRIPT="$ASSETS/scripts/common/setup/setup_customization_xfce.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }

# The script needs root and installs packages, so source only the functions.
for fn in _flux_pkg_add _flux_is_proot _flux_papirus_pkg_fallback; do
    awk -v n="$fn" 'index($0, n "() {") == 1 { p = 1 } p { print } p && /^}$/ { exit }' \
        "$SCRIPT" >> "$T/fns.sh"
    grep -q "^$fn() {" "$T/fns.sh" || fail "$fn not found in $SCRIPT"
done
# shellcheck source=/dev/null
. "$T/fns.sh"

# --- proot (explicit flag): the package manager is never invoked -----------
_real_pkg_add_def=$(cat "$T/fns.sh")
_flux_pkg_add() { echo "$*" >> "$T/pkg_calls"; return 1; }
FLUX_PROOT=1
if _flux_papirus_pkg_fallback > "$T/out" 2>&1; then
    fail "proot: fallback reported it ran"
fi
[ -s "$T/pkg_calls" ] && fail "proot: _flux_pkg_add called: $(cat "$T/pkg_calls")"
grep -q "PRoot" "$T/out" || fail "proot: no skip message"

# --- chroot (FLUX_PROOT=0): apt runs with --no-install-recommends ------------
eval "$_real_pkg_add_def"
mkdir -p "$T/bin"
cat > "$T/bin/apt-get" <<EOF
#!/bin/sh
echo "\$*" >> "$T/apt_calls"
EOF
chmod +x "$T/bin/apt-get"
# Only the stub package manager is visible (no host dnf/pacman/...).
for tool in grep sed; do
    ln -s "$(command -v "$tool")" "$T/bin/$tool"
done
OLD_PATH=$PATH
PATH="$T/bin"
FLUX_PROOT=0
_flux_papirus_pkg_fallback > "$T/out" 2>&1 || fail "chroot: fallback skipped"
PATH=$OLD_PATH
grep -q -- "--no-install-recommends papirus-icon-theme" "$T/apt_calls" 2>/dev/null \
    || fail "chroot: expected apt-get --no-install-recommends, got: $(cat "$T/apt_calls" 2>/dev/null)"
[ -z "${FLUX_APT_NO_RECOMMENDS:-}" ] || fail "FLUX_APT_NO_RECOMMENDS leaked to the caller"

if [ "$FAILS" -ne 0 ]; then
    echo "papirus_proot_fallback_test: $FAILS failure(s)"
    exit 1
fi
echo "papirus_proot_fallback_test: OK"

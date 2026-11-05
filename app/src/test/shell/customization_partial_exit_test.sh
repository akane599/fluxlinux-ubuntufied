#!/bin/sh
# setup_customization_xfce.sh ends in _flux_customization_finish: exit 0 only with
# Oh My Zsh in place, exit 3 ("partial") without it, and a final line that names the
# terminal-only (FLUX_CUSTOMIZE_DESKTOP=0) or desktop run. The app turns exit 3 into a
# finished install with a warning, so nothing may run after it.
# Usage: customization_partial_exit_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
CUSTOM="$ASSETS/scripts/common/setup/setup_customization_xfce.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }

sh -n "$CUSTOM" || fail "sh -n $CUSTOM"
awk 'index($0, "_flux_customization_finish() {") == 1 { p = 1 } p { print } p && /^}$/ { exit }' \
    "$CUSTOM" > "$T/finish.sh"
grep -q '^_flux_customization_finish() {' "$T/finish.sh" || fail "_flux_customization_finish not found"

# The call is the script's last code line: no later step can turn exit 3 back into 0.
last=$(awk '$0 !~ /^[[:space:]]*(#|$)/ { l = $0 } END { print l }' "$CUSTOM")
[ "$last" = "_flux_customization_finish" ] || fail "last code line is '$last', not the finish call"
# Oh My Zsh's outcome is the only thing it reads: OMZ_OK is set once, before the plugins.
grep -q '^OMZ_OK=0$' "$CUSTOM" || fail "OMZ_OK=0 initialisation missing"

run_finish() { # $1 = case, rest = env assignments
    _c=$1; shift
    env -i PATH=/usr/bin:/bin "$@" /bin/sh -c ". '$T/finish.sh'; _flux_customization_finish" \
        > "$T/$_c.out" 2>&1
    echo $? > "$T/$_c.rc"
}
expect() { # $1 = case, $2 = exit code, $3 = exact output line
    [ "$(cat "$T/$1.rc")" = "$2" ] || fail "$1: exit $(cat "$T/$1.rc"), want $2"
    grep -qxF -- "$3" "$T/$1.out" || fail "$1: output '$(cat "$T/$1.out")', want '$3'"
}

run_finish desktop_ok OMZ_OK=1
expect desktop_ok 0 "FluxLinux: XFCE4 Customization complete!"
run_finish desktop_on_ok OMZ_OK=1 FLUX_CUSTOMIZE_DESKTOP=1
expect desktop_on_ok 0 "FluxLinux: XFCE4 Customization complete!"
run_finish desktop_partial OMZ_OK=0
expect desktop_partial 3 "FluxLinux: WARNING: XFCE4 Customization finished without Oh My Zsh (partial, exit 3)"
run_finish terminal_ok OMZ_OK=1 FLUX_CUSTOMIZE_DESKTOP=0
expect terminal_ok 0 "FluxLinux: shell customization complete!"
run_finish terminal_partial OMZ_OK=0 FLUX_CUSTOMIZE_DESKTOP=0
expect terminal_partial 3 "FluxLinux: WARNING: shell customization finished without Oh My Zsh (partial, exit 3)"
grep -q "XFCE" "$T/terminal_partial.out" "$T/terminal_ok.out" && fail "terminal-only output mentions XFCE"
run_finish unset FLUX_CUSTOMIZE_DESKTOP=0
[ "$(cat "$T/unset.rc")" = 3 ] || fail "OMZ_OK unset: exit $(cat "$T/unset.rc"), want 3"

if [ "$FAILS" -ne 0 ]; then
    echo "customization_partial_exit_test: $FAILS failure(s)"
    exit 1
fi
echo "customization_partial_exit_test: OK"

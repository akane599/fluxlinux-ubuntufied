#!/bin/sh
# Regression test for L21: _flux_write_gpu_mode is a first-paint default and
# must not overwrite a mode setup_hw_accel_guest.sh already chose.
# Usage: gpu_mode_default_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
# Re-root /etc/fluxlinux into the scratch dir, then source the helpers.
sed "s|/etc/fluxlinux|$T/etc/fluxlinux|g" \
    "$ASSETS/scripts/common/setup/flux_guest_common.sh" > "$T/common.sh"
# shellcheck source=/dev/null
. "$T/common.sh"
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
mode() { tr -d '[:space:]' < "$T/etc/fluxlinux/gpu_mode"; }

_flux_write_gpu_mode virgl >/dev/null
[ "$(mode)" = virgl ] || fail "absent: expected virgl, got $(mode)"

printf '%s\n' turnip > "$T/etc/fluxlinux/gpu_mode"
_flux_write_gpu_mode virgl >/dev/null
[ "$(mode)" = turnip ] || fail "turnip downgraded to $(mode)"

printf '%s\n' panvk > "$T/etc/fluxlinux/gpu_mode"
_flux_write_gpu_mode >/dev/null
[ "$(mode)" = panvk ] || fail "panvk downgraded to $(mode)"

: > "$T/etc/fluxlinux/gpu_mode"
_flux_write_gpu_mode virgl >/dev/null
[ "$(mode)" = virgl ] || fail "empty: expected virgl, got $(mode)"

if [ "$FAILS" -ne 0 ]; then
    echo "gpu_mode_default_test: $FAILS failure(s)"
    exit 1
fi
echo "gpu_mode_default_test: OK"

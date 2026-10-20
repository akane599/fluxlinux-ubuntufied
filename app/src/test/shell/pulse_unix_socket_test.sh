#!/bin/sh
# Regression test for M17: guests reach host Pulse through the shared-tmp unix
# socket, never anonymous TCP 127.0.0.1:4713.
#  1. start_pulse_host.sh migrate_proot_guests rewrites only the FluxLinux-written
#     tcp:127.0.0.1 lines of existing proot guests (regular files, no links).
#  2. flux_guest_common.sh _flux_write_pulse_client writes the socket list.
# Usage: pulse_unix_socket_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
LIST="unix:/tmp/pulse-native unix:/mnt/host-tmp/pulse-native"

# --- 1. host-side migration of existing proot guests -------------------------
awk '/^GUEST_PULSE_SERVER=/ { on = 1 }
     on { print }
     on && /^migrate_proot_guests\(\) \{/ { last = 1 }
     last && /^}/ { exit }' \
    "$ASSETS/scripts/host/start_pulse_host.sh" > "$T/migrate.sh"
grep -q '^migrate_proot_guests() {' "$T/migrate.sh" || fail "migrate functions not found"
PREFIX="$T/usr"
R="$PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs"
mkdir -p "$R/etc/profile.d" "$R/etc/pulse/client.conf.d" "$R/home/flux/.vnc"
printf 'export PULSE_SERVER=tcp:127.0.0.1\n' > "$R/etc/profile.d/flux-pulse.sh"
printf 'LANG=C\nPULSE_SERVER=tcp:127.0.0.1\n' > "$R/etc/environment"
printf '; c\ndefault-server = tcp:127.0.0.1\nautospawn = no\n' > "$R/etc/pulse/client.conf"
printf 'default-server = tcp:127.0.0.1\nautospawn = no\n' > "$R/etc/pulse/client.conf.d/99-fluxlinux.conf"
printf '#!/bin/sh\nexport PULSE_SERVER=tcp:127.0.0.1\nexec startxfce4\n' > "$R/home/flux/.vnc/xstartup"
chmod 755 "$R/home/flux/.vnc/xstartup"
# A link must never be written through; user-chosen servers stay.
printf 'export PULSE_SERVER=tcp:127.0.0.1\n' > "$T/victim"
R2="$PREFIX/var/lib/proot-distro/containers/other/rootfs"
mkdir -p "$R2/etc/profile.d"
ln -s "$T/victim" "$R2/etc/profile.d/flux-pulse.sh"
printf 'PULSE_SERVER=tcp:192.168.1.5\n' > "$R2/etc/environment"

# shellcheck source=/dev/null
. "$T/migrate.sh"
migrate_proot_guests > "$T/migrate.log"
[ "$GUEST_PULSE_SERVER" = "$LIST" ] || fail "host list differs from guest list: $GUEST_PULSE_SERVER"

grep -rq 'tcp:127.0.0.1' "$R" && fail "tcp:127.0.0.1 left in migrated rootfs: $(grep -rl 'tcp:127.0.0.1' "$R")"
v=$(sh -c ". '$R/etc/profile.d/flux-pulse.sh'; printf %s \"\$PULSE_SERVER\"")
[ "$v" = "$LIST" ] || fail "profile.d PULSE_SERVER=$v"
grep -qx "PULSE_SERVER=\"$LIST\"" "$R/etc/environment" || fail "environment not migrated"
grep -qx 'LANG=C' "$R/etc/environment" || fail "environment other line lost"
grep -qx "default-server = $LIST" "$R/etc/pulse/client.conf" || fail "client.conf not migrated"
grep -qx '; c' "$R/etc/pulse/client.conf" || fail "client.conf comment lost"
grep -qx "default-server = $LIST" "$R/etc/pulse/client.conf.d/99-fluxlinux.conf" || fail "client.conf.d not migrated"
grep -qx "export PULSE_SERVER=\"$LIST\"" "$R/home/flux/.vnc/xstartup" || fail "xstartup not migrated"
[ -x "$R/home/flux/.vnc/xstartup" ] || fail "xstartup lost exec bit"
grep -qx 'export PULSE_SERVER=tcp:127.0.0.1' "$T/victim" || fail "wrote through a symlink"
[ -L "$R2/etc/profile.d/flux-pulse.sh" ] || fail "symlink replaced"
grep -qx 'PULSE_SERVER=tcp:192.168.1.5' "$R2/etc/environment" || fail "user server rewritten"
find "$PREFIX" -name '*.flux-tmp' | grep -q . && fail "temp files left"
# Idempotent: second run changes nothing and logs nothing.
migrate_proot_guests > "$T/migrate2.log"
[ -s "$T/migrate2.log" ] && fail "second migration not a no-op: $(cat "$T/migrate2.log")"

# --- 2. guest-side writer -----------------------------------------------------
G="$T/guest"
mkdir -p "$G/etc/pulse" "$G/home/flux/.vnc"
printf 'LANG=C\nPULSE_SERVER=tcp:127.0.0.1\n' > "$G/etc/environment"
printf '; default-server =\n; autospawn = yes\n' > "$G/etc/pulse/client.conf"
printf '#!/bin/sh\nexport PULSE_SERVER=tcp:127.0.0.1\nexec startxfce4\n' > "$G/home/flux/.vnc/xstartup"
sed -e "s|/etc/|$G/etc/|g" -e "s|/home/flux/|$G/home/flux/|g" \
    "$ASSETS/scripts/common/setup/flux_guest_common.sh" > "$T/common.sh"
# shellcheck source=/dev/null
. "$T/common.sh"
_flux_write_pulse_client
grep -rq 'tcp:127.0.0.1' "$G" && fail "guest writer left tcp: $(grep -rl 'tcp:127.0.0.1' "$G")"
v=$(sh -c ". '$G/etc/profile.d/flux-pulse.sh'; printf %s \"\$PULSE_SERVER\"")
[ "$v" = "$LIST" ] || fail "guest profile.d PULSE_SERVER=$v"
grep -qx "PULSE_SERVER=\"$LIST\"" "$G/etc/environment" || fail "guest environment"
grep -qx 'LANG=C' "$G/etc/environment" || fail "guest environment truncated"
grep -qx "default-server = $LIST" "$G/etc/pulse/client.conf" || fail "guest client.conf"
grep -qx 'autospawn = no' "$G/etc/pulse/client.conf" || fail "guest autospawn"
grep -qx "default-server = $LIST" "$G/etc/pulse/client.conf.d/99-fluxlinux.conf" || fail "guest client.conf.d"
grep -qx "export PULSE_SERVER=\"$LIST\"" "$G/home/flux/.vnc/xstartup" || fail "guest xstartup"

if [ "$FAILS" -ne 0 ]; then
    echo "pulse_unix_socket_test: $FAILS failure(s)"
    exit 1
fi
echo "pulse_unix_socket_test: OK"

#!/bin/sh
# Regression test for M17 (chroot guests): "Repair guests" runs as root on the
# host and must move existing chroot guests off tcp:127.0.0.1 (host Pulse now
# serves only a unix socket) without ever following a guest-planted link.
# Exercises repair_pulse_guests.sh migrate_chroot_guest on a fake rootfs.
# Usage: pulse_repair_chroot_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
LIST="unix:/tmp/pulse-native unix:/mnt/host-tmp/pulse-native"
SRC="$ASSETS/scripts/host/repair_pulse_guests.sh"

awk '/^# --- chroot client-config migration/ { on = 1 }
     on { print }
     /^# --- end chroot client-config migration/ { exit }' "$SRC" > "$T/migrate.sh"
grep -q '^migrate_chroot_guest() {' "$T/migrate.sh" || fail "migrate_chroot_guest not found"
grep -q 'tcp:127.0.0.1 \\$' "$SRC" && fail "proot repair still passes PULSE_SERVER=tcp:127.0.0.1"
grep -q 'PULSE_SERVER=unix:/tmp/pulse-native' "$SRC" || fail "proot repair env not unix:/tmp/pulse-native"
grep -q "'PULSE_SERVER=unix:/mnt/host-tmp/pulse-native /bin/sh /tmp/setup_pulse_guest.sh'" "$SRC" \
    || fail "chroot repair env not unix:/mnt/host-tmp/pulse-native"

# shellcheck source=/dev/null
. "$T/migrate.sh"
[ "$CHROOT_PULSE_SERVERS" = "$LIST" ] || fail "list differs from guest writer: $CHROOT_PULSE_SERVERS"
GUEST_LIST=$(sed -n 's/^FLUX_PULSE_SERVERS="\(.*\)"$/\1/p' "$ASSETS/scripts/common/setup/flux_guest_common.sh")
[ "$GUEST_LIST" = "$LIST" ] || fail "flux_guest_common.sh list is '$GUEST_LIST'"

# --- 1. regular files are migrated ------------------------------------------
R="$T/chrootUbuntu"
mkdir -p "$R/etc/profile.d" "$R/etc/pulse/client.conf.d" "$R/home/flux/.vnc"
printf 'export PULSE_SERVER=tcp:127.0.0.1\n' > "$R/etc/profile.d/flux-pulse.sh"
printf 'LANG=C\nPULSE_SERVER=tcp:127.0.0.1\n' > "$R/etc/environment"
printf '; c\ndefault-server = tcp:127.0.0.1\nautospawn = no\n' > "$R/etc/pulse/client.conf"
printf 'default-server = tcp:127.0.0.1\nautospawn = no\n' > "$R/etc/pulse/client.conf.d/99-fluxlinux.conf"
# KDE xstartup was written with a bare host (no tcp: prefix).
printf '#!/bin/bash\nexport PULSE_SERVER=127.0.0.1\nexec startplasma-x11\n' > "$R/home/flux/.vnc/xstartup"
chmod 755 "$R/home/flux/.vnc/xstartup"

migrate_chroot_guest "$R" > "$T/m1.log"
grep -rq '127\.0\.0\.1' "$R" && fail "loopback left in migrated chroot: $(grep -rl '127.0.0.1' "$R")"
v=$(sh -c ". '$R/etc/profile.d/flux-pulse.sh'; printf %s \"\$PULSE_SERVER\"")
[ "$v" = "$LIST" ] || fail "profile.d PULSE_SERVER=$v"
grep -qx "PULSE_SERVER=\"$LIST\"" "$R/etc/environment" || fail "environment not migrated"
grep -qx 'LANG=C' "$R/etc/environment" || fail "environment other line lost"
grep -qx "default-server = $LIST" "$R/etc/pulse/client.conf" || fail "client.conf not migrated"
grep -qx '; c' "$R/etc/pulse/client.conf" || fail "client.conf comment lost"
grep -qx "default-server = $LIST" "$R/etc/pulse/client.conf.d/99-fluxlinux.conf" || fail "client.conf.d not migrated"
grep -qx "export PULSE_SERVER=\"$LIST\"" "$R/home/flux/.vnc/xstartup" || fail "KDE xstartup not migrated"
[ -x "$R/home/flux/.vnc/xstartup" ] || fail "xstartup lost exec bit"
[ "$(grep -c 'migrated chroot' "$T/m1.log")" -eq 5 ] || fail "expected 5 migrations: $(cat "$T/m1.log")"
# Idempotent: second run changes nothing and logs nothing.
migrate_chroot_guest "$R" > "$T/m2.log"
[ -s "$T/m2.log" ] && fail "second migration not a no-op: $(cat "$T/m2.log")"

# --- 2. links are never followed (root on the host) --------------------------
V="$T/host"
mkdir -p "$V/pulse"
printf 'export PULSE_SERVER=tcp:127.0.0.1\n' > "$V/victim"
printf 'default-server = tcp:127.0.0.1\n' > "$V/pulse/client.conf"
printf 'PULSE_SERVER=tcp:127.0.0.1\n' > "$V/env-victim"
L="$T/chrootEvil"
mkdir -p "$L/etc/profile.d" "$L/home/flux/.vnc"
ln -s "$V/victim" "$L/etc/profile.d/flux-pulse.sh"   # file link
ln -s "$V/pulse" "$L/etc/pulse"                      # directory link
printf 'PULSE_SERVER=tcp:127.0.0.1\n' > "$L/etc/environment"
ln -s "$V/env-victim" "$L/etc/environment.flux-tmp"  # planted temp link
# User-chosen servers stay.
printf '#!/bin/sh\nexport PULSE_SERVER=tcp:192.168.1.5\n' > "$L/home/flux/.vnc/xstartup"

migrate_chroot_guest "$L" > "$T/m3.log"
grep -qx 'export PULSE_SERVER=tcp:127.0.0.1' "$V/victim" || fail "wrote through a file link"
grep -qx 'default-server = tcp:127.0.0.1' "$V/pulse/client.conf" || fail "wrote through a directory link"
grep -qx 'PULSE_SERVER=tcp:127.0.0.1' "$V/env-victim" || fail "wrote through a planted .flux-tmp link"
[ -L "$L/etc/profile.d/flux-pulse.sh" ] || fail "file link replaced"
[ -L "$L/etc/pulse" ] || fail "directory link replaced"
grep -qx "PULSE_SERVER=\"$LIST\"" "$L/etc/environment" || fail "regular environment beside links not migrated"
grep -qx 'export PULSE_SERVER=tcp:192.168.1.5' "$L/home/flux/.vnc/xstartup" || fail "user server rewritten"
find "$T" -name '*.flux-tmp' ! -type l | grep -q . && fail "temp files left"

chroot_path_no_links "$L" etc/environment || fail "regular path reported as link"
chroot_path_no_links "$L" etc/pulse/client.conf && fail "dir link not detected"
chroot_path_no_links "$L" etc/profile.d/flux-pulse.sh && fail "file link not detected"

if [ "$FAILS" -ne 0 ]; then
    echo "pulse_repair_chroot_test: $FAILS failure(s)"
    exit 1
fi
echo "pulse_repair_chroot_test: OK"

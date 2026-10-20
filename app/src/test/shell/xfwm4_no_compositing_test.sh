#!/bin/sh
# Regression test for M11: flux_gpu_xfwm4_no_compositing patches only
# /general/use_compositing in xfwm4.xml and never drops other properties.
# Usage: xfwm4_no_compositing_test.sh <app/src/main/assets dir>
set -u
ASSETS=${1:?usage: $0 <assets dir>}
# shellcheck source=/dev/null
. "$ASSETS/scripts/common/setup/flux_gpu_common.sh"

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
FAILS=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
count() { grep -c "$1" "$2" 2>/dev/null || true; }

check_off_once() {
    _f=$1
    [ "$(count 'name="use_compositing" type="bool" value="false"' "$_f")" = 1 ] \
        || fail "$2: expected exactly one use_compositing=false"
    [ "$(count 'name="use_compositing"' "$_f")" = 1 ] \
        || fail "$2: duplicate use_compositing"
    [ "$(count '</channel>' "$_f")" = 1 ] || fail "$2: channel not closed once"
}

# 1. present, value=true, with a theme → flipped to false, theme kept.
f="$T/present_true/xfwm4.xml"; mkdir -p "${f%/*}"
cat > "$f" <<'X'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Flux-Dark"/>
    <property name="use_compositing" type="bool" value="true"/>
    <property name="button_layout" type="string" value="O|HMC"/>
  </property>
</channel>
X
chmod 640 "$f"
flux_gpu_xfwm4_no_compositing "$f" || fail "present_true: rc=$?"
check_off_once "$f" present_true
grep -q 'name="theme" type="string" value="Flux-Dark"' "$f" || fail "present_true: theme lost"
grep -q 'name="button_layout" type="string" value="O|HMC"' "$f" || fail "present_true: button_layout lost"
grep -q 'value="true"' "$f" && fail "present_true: true remains"
[ "$(stat -c %a "$f" 2>/dev/null || stat -f %Lp "$f")" = 640 ] || fail "present_true: mode changed"
ls "${f%/*}" | grep -q flux-tmp && fail "present_true: temp file left"

# 2. present, already false → unchanged content.
f="$T/present_false/xfwm4.xml"; mkdir -p "${f%/*}"
cat > "$f" <<'X'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Greybird"/>
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
X
cp "$f" "$T/present_false.orig"
flux_gpu_xfwm4_no_compositing "$f" || fail "present_false: rc=$?"
cmp -s "$f" "$T/present_false.orig" || fail "present_false: file changed"

# 3a. general present, property missing → inserted inside general, theme kept.
f="$T/missing_prop/xfwm4.xml"; mkdir -p "${f%/*}"
cat > "$f" <<'X'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Flux-Dark"/>
  </property>
</channel>
X
flux_gpu_xfwm4_no_compositing "$f" || fail "missing_prop: rc=$?"
check_off_once "$f" missing_prop
grep -q 'name="theme" type="string" value="Flux-Dark"' "$f" || fail "missing_prop: theme lost"
[ "$(count '<property name="general"' "$f")" = 1 ] || fail "missing_prop: general duplicated"
awk '/name="general"/{g=1} g && /use_compositing/{ok=1} /^  <\/property>/{g=0} END{exit !ok}' "$f" \
    || fail "missing_prop: not inside general"

# 3b. no general block → general block added before </channel>, rest kept.
f="$T/missing_general/xfwm4.xml"; mkdir -p "${f%/*}"
cat > "$f" <<'X'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="other" type="string" value="keep-me"/>
</channel>
X
flux_gpu_xfwm4_no_compositing "$f" || fail "missing_general: rc=$?"
check_off_once "$f" missing_general
grep -q 'name="other" type="string" value="keep-me"' "$f" || fail "missing_general: other lost"
tail -n 1 "$f" | grep -q '^</channel>$' || fail "missing_general: </channel> not last"

# 4. missing file (and missing dirs) → minimal channel created.
f="$T/missing_file/a/b/xfwm4.xml"
flux_gpu_xfwm4_no_compositing "$f" || fail "missing_file: rc=$?"
[ -f "$f" ] || fail "missing_file: not created"
check_off_once "$f" missing_file
grep -q '<channel name="xfwm4"' "$f" || fail "missing_file: no channel"

# 5. empty file → treated as missing.
f="$T/empty/xfwm4.xml"; mkdir -p "${f%/*}"; : > "$f"
flux_gpu_xfwm4_no_compositing "$f" || fail "empty: rc=$?"
check_off_once "$f" empty

if [ "$FAILS" -ne 0 ]; then
    echo "xfwm4_no_compositing_test: $FAILS failure(s)"
    exit 1
fi
echo "xfwm4_no_compositing_test: OK"

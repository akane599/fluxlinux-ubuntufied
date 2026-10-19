#!/usr/bin/env bash
# Preserve Gradle's real exit status, retain the full log, print a bounded excerpt.
set -uo pipefail
[ -f ./gradlew ] || { echo 'gradlew missing; run from the project root' >&2; exit 2; }
mkdir -p .claude/kit/logs
log=$(mktemp .claude/kit/logs/gradle.XXXXXXXX.log) || exit 2
if [ -x ./gradlew ]; then runner=(./gradlew); else runner=(bash ./gradlew); fi
"${runner[@]}" "$@" --console=plain -q >"$log" 2>&1
rc=$?
if [ "$rc" -eq 0 ]; then
  printf 'PASS: Gradle (log: %s)\n' "$log"
else
  tail -n 80 "$log"
  printf '\nFAIL: Gradle exit %s (full log: %s)\n' "$rc" "$log" >&2
fi
exit "$rc"

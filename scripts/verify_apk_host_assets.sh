#!/usr/bin/env bash
# verify_apk_host_assets.sh — unzip checks that the built APK contains the host
# jniLibs and assets/bootstrap.tar (STORED), and — since rootfs moved to GitHub
# release download — ZERO assets/rootfs/* entries. Both flavors package the
# bootstrap (HostBootstrap.downloadsFromRelease is false for every appId).
#
# Usage:
#   ./scripts/verify_apk_host_assets.sh app/build/outputs/apk/ivarna/debug/app-ivarna-debug.apk
#   ./scripts/verify_apk_host_assets.sh app/build/outputs/apk/zenithblue/debug/app-zenithblue-debug.apk
set -euo pipefail

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "Usage: $0 <path-to-apk>" >&2
  exit 2
fi

fail=0

echo "=== verifying host assets in $APK ==="

for entry in \
  "lib/arm64-v8a/libbash.so" \
  "lib/arm64-v8a/libproot.so" \
  "lib/arm64-v8a/libloader.so" \
  "lib/arm64-v8a/libloader32.so" \
  "lib/arm64-v8a/libpulseaudio.so" \
  "lib/arm64-v8a/libpactl.so"; do
  # grep -c (not -q): -q closes the pipe early → SIGPIPE on unzip under pipefail
  if [ "$(unzip -l "$APK" | grep -c " $entry$")" -gt 0 ]; then
    echo "  [OK] $entry"
  else
    echo "  [MISSING] $entry"
    fail=1
  fi
done

bootstrap_count="$(unzip -l "$APK" | grep -c " assets/bootstrap.tar$" || true)"
if [ "$bootstrap_count" -gt 0 ]; then
  echo "  [OK] assets/bootstrap.tar"
  method=$(unzip -v "$APK" | grep " assets/bootstrap.tar$" | awk '{print $2}')
  if [ "$method" = "Stored" ]; then
    echo "  [OK] assets/bootstrap.tar stored uncompressed"
  else
    echo "  [FAIL] assets/bootstrap.tar compressed ($method) — check androidResources.noCompress"
    fail=1
  fi
else
  echo "  [MISSING] assets/bootstrap.tar"
  fail=1
fi

# Negative rootfs gate: rootfs archives ship via the GitHub release tag `rootfs`,
# never inside the APK.
rootfs_entries=$(unzip -l "$APK" | grep -c "assets/rootfs/" || true)
if [ "$rootfs_entries" -eq 0 ]; then
  echo "  [OK] zero assets/rootfs/* entries (rootfs downloads at install time)"
else
  echo "  [FAIL] APK contains $rootfs_entries assets/rootfs/* entries — must be zero"
  unzip -l "$APK" | grep "assets/rootfs/" || true
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "PASS"
else
  echo "FAIL"
  exit 1
fi

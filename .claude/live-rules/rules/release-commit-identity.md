---
description: FluxLinux release commit identity
prompt: ["release", "tag", "version bump", "fdroid", "f-droid", "changelog"]
globs: ["fastlane/metadata/**", "docs/releases/**", "com.ivarna.fluxlinux.yml"]
---
- APK commit == tag commit == GitHub release commit == HEAD. No drift.
- Before tagging: clean tree, then `./gradlew clean assembleRelease` so `META-INF/version-control-info.textproto` is fresh.
- Verify with `unzip -p <apk> META-INF/version-control-info.textproto | grep revision` against `git rev-parse HEAD` and the tag; re-check the downloaded release APK after upload.
- On any mismatch, delete the tag/release and redo from the correct commit. Full steps: `.agent/workflows/fdroid-update.md`.

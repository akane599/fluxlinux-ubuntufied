---
description: FluxLinux release commit identity
prompt: ["release", "tag", "version bump", "fdroid", "f-droid", "changelog"]
globs: ["fastlane/metadata/**", "docs/releases/**", "com.ivarna.fluxlinux.yml"]
---
- APK commit == tag commit == GitHub release commit == HEAD. No drift.
- Before tagging: clean tree, then `bash .claude/kit/gradle-check.sh clean :app:assembleIvarnaRelease` so `META-INF/version-control-info.textproto` is fresh.
- Verify with `unzip -p <apk> META-INF/version-control-info.textproto | grep revision` against `git rev-parse HEAD` and the tag; re-check the downloaded release APK after upload.
- On any mismatch, delete the tag/release and redo from the correct commit. Full steps: `.agent/workflows/fdroid-update.md`.
- Build the release from the main checkout, not a git worktree: AGP 8.7 can't find git in a worktree and silently omits `META-INF/version-control-info.textproto`.
- Fork releases go to `akane599/fluxlinux-ubuntufied`, titled "FluxLinux Ubuntu edition v<ver>". The APK comes from `app/build/outputs/apk/ivarna/release/app-ivarna-release.apk` and is uploaded as `FluxLinux-Ubuntu-v<ver>.apk`. The commit is `chore(release): v<ver> (<code>); …`, plus `docs/releases/v<ver>.md`, `changelogs/<code>.txt` and a PROGRESS.md line. `.agent/workflows/fdroid-update.md` still names `abhay-byte/fluxlinux` and `assembleRelease`; ignore those parts.

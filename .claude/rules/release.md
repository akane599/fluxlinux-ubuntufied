---
description: FluxLinux fork release procedure (commit identity, two APKs)
paths:
  - "app/build.gradle.kts"
  - "docs/releases/**"
  - "fastlane/metadata/**"
  - "changelogs/**"
  - "com.ivarna.fluxlinux.yml"
---
- APK commit == tag commit == GitHub release commit == HEAD. No drift.
- Build from the main checkout, never a git worktree: AGP 8.7 can't find git in a worktree and silently omits `META-INF/version-control-info.textproto`.
- Clean tree, then build both APKs in one clean run from the same HEAD: `bash .claude/scripts/gradle-check.sh clean :app:assembleIvarnaRelease :app:assembleIvarnaDebug`.
- Verify the release APK with `unzip -p <apk> META-INF/version-control-info.textproto | grep revision` against `git rev-parse HEAD` and the tag. The debug APK strips `META-INF/**`; check that `aapt2 dump badging` shows the same versionCode/versionName as the release APK.
- Releases go to `akane599/fluxlinux-ubuntufied`, titled "FluxLinux Ubuntu edition v<ver>", with two assets: `app/build/outputs/apk/ivarna/release/app-ivarna-release.apk` as `FluxLinux-Ubuntu-v<ver>.apk` and the debug APK as `FluxLinux-Ubuntu-v<ver>-debug.apk`.
- The release commit is `chore(release): v<ver> (<code>); …`, with `docs/releases/v<ver>.md`, `changelogs/<code>.txt` and a PROGRESS.md decision-log line.
- After upload, download both assets and `cmp` them against the local builds. On any mismatch, delete the tag/release and redo from the correct commit.
- Release notes (GitHub release body only, from a scratch file) list both APKs' SHA-256, say the debug APK is debug-signed for diagnosis (`run-as` works) and can't update a release install (uninstall first, which deletes app data). Never put checksums in the committed `docs/releases` file: committing them changes HEAD and so the APK.
- Verify a release candidate on the real phone with the debug APK before publishing.
- `.agent/workflows/fdroid-update.md` is the upstream procedure; it names `abhay-byte/fluxlinux`, `assembleRelease` and no debug APK, so ignore those parts.

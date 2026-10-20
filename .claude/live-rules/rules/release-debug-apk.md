---
description: FluxLinux releases ship a debug APK too
prompt: ["release", "tag", "version bump", "fdroid", "f-droid", "changelog", "publish", "push apk"]
globs: ["docs/releases/**"]
---
- Every GitHub release ships two assets: `FluxLinux-Ubuntu-v<ver>.apk` (release) and `FluxLinux-Ubuntu-v<ver>-debug.apk`.
- Build both in one clean run from the same HEAD in the main checkout: `bash .claude/kit/gradle-check.sh clean :app:assembleIvarnaRelease :app:assembleIvarnaDebug`.
- The debug APK strips `META-INF/**`, so it has no version-control-info. Verify that `aapt2 dump badging` shows the same versionCode/versionName as the release APK.
- After upload, download both assets and `cmp` them against the local builds.
- Release notes list both APKs' SHA-256. They also say the debug APK is signed with the debug key and is for diagnosis (`run-as` works), and that it can't update a release install (uninstall first, which deletes app data).

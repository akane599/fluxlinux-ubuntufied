# Build and release
*Last Updated: 2026-10-19*

## Toolchain and versions (`gradle/libs.versions.toml`, `app/build.gradle.kts`)
- Gradle 8.14, AGP 8.7.3, Kotlin 2.0.20, JDK target 17. compileSdk/targetSdk 36, minSdk 26. NDK 29.0.14206865, arm64-v8a only.
- Compose BOM 2024.02.00 (material3, icons-extended), activity-compose 1.8.2, lifecycle 2.7.0, core-ktx 1.12.0.
- haze 1.1.0 (glass blur), accompanist-permissions 0.34.0, okhttp 4.12.0 (+ mockwebserver), Shizuku 13.1.5, termux terminal-view 0.118.0 (jitpack).
- Tests: junit 4.13.2, org.json. Espresso/ui-test are declared but there is no `androidTest` source.
- Screenshot testing uses plugin `com.android.compose.screenshot` and `screenshot-validation-api` 0.0.1-alpha10. It is pinned there because alpha11+ need Kotlin ≥2.2.
- `gradle.properties`: `-Xmx4096m`, nonTransitiveRClass, `android.suppressUnsupportedCompileSdk=36`, `android.experimental.enableScreenshotTest=true`.

## Flavors (dimension `store`)
| Flavor | applicationId | Channel |
|---|---|---|
| `ivarna` | `com.ivarna.fluxlinux` | F-Droid / GitHub (arm64 abiFilter) |
| `zenithblue` | `com.zenithblue.fluxlinux` | Google Play |

Flavor source sets hold only assets and jniLibs; there are no Kotlin differences. Task names are flavor-qualified (`:app:assembleIvarnaDebug`); there is no plain `assembleDebug`.

## Reproducible builds
- Archives use no timestamps and a fixed file order.
- `app/fix-baseline-profiles.gradle` disables ART/startup profiles.
- `dependenciesInfo` is off, and release excludes `META-INF/**` and `*.prof`.
- Release signs only if the root `keystore.properties` exists (gitignored; never read it). R8 minify and shrink are on.

## Release
- F-Droid recipe: `com.ivarna.fluxlinux.yml`. `UpdateCheckMode: Tags`, so a tag publishes. The 2.0.1 build uses `submodules: true`, `ndk: r29` and `NO_DOCKER=1` and runs `build_packages_for_appid.sh` → `build_loader_apk.sh` → `build_guest_helpers.sh` → `assemble_bootstrap.py`, with `scandelete`/`scanignore` lists.
- Fastlane metadata is in `fastlane/metadata/android/en-US/` (changelogs/<versionCode>.txt, images). There is no `.github/` CI.
- Release notes: `RELEASE.md` (current), `CHANGELOG.md`, `docs/releases/`. Procedure: `.agent/workflows/fdroid-update.md`. Commit identity (APK == tag == HEAD) is enforced by a live-rule.

## docs/
- `docs/README.md` is the index. `docs/plans/` holds current plans (index `docs/plans/README.md`) and device results in `docs/plans/results/`.
- `docs/plan/` and `docs/phases/` are older.
- Also `docs/distro/` (per-distro), `docs/releases/`, `docs/todo/`, `docs/install_ref/`, `docs/termux/` and `docs/reviews/`.

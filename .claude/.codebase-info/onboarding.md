# Onboarding
*Last Updated: 2026-10-19*

## Fresh checkout → green build
1. JDK 17+, Android SDK (`ANDROID_HOME`), NDK 29.0.14206865, CMake 3.22.1, plus python3, bison and patch for the X server build.
2. Fetch the termux-x11 submodules only. `native/termux-packages` is huge and needed only to rebuild debs:
   `git submodule update --init --depth 1 -- $(git config -f .gitmodules --get-regexp 'submodule\.termux-x11.*\.path' | awk '{print $2}')`
3. Assemble the host bootstrap from the tracked debs (no Docker needed):
   `python3 scripts/assemble_bootstrap.py --package-name com.ivarna.fluxlinux --mode full && bash scripts/verify_bootstrap.sh com.ivarna.fluxlinux`
4. Build and test: `bash .claude/kit/gradle-check.sh :app:assembleIvarnaDebug :app:testIvarnaDebugUnitTest`.
5. Install: `./gradlew :app:installIvarnaDebug -q && adb shell am start -n com.ivarna.fluxlinux/.MainActivity`. The ivarna flavor ships arm64 only; x86_64 emulators run it via native bridge.

Sidequest worktrees are set up automatically. `bootstrap.tar` is copied in through `worktreeDependencyPaths` (`link` mode only works for directories). The board's `worktreeSetup` clones the 16 termux-x11 submodules from the main checkout over `file://`, with no network, in about 1.5 s.

## Common tasks
| Task | Where |
|---|---|
| Add or change a distro | `core/install/DistroInstallProfile.kt` (pins), `core/data/DistroRepository.kt`, `app/src/main/assets/scripts/<distro>/`, release tag `rootfs`, `scripts/verify_rootfs_shas.sh` |
| Change how proot/chroot is invoked | `core/terminal/{Proot,Chroot,Linux}CommandBuilder.kt` (tests next to them) |
| Desktop start/stop bugs | `core/desktop/DesktopLauncher.kt`, `assets/scripts/debian/proot/start/start_gui.sh`, `assets/scripts/chroot/start_gui_chroot.sh` (deployed by `HostScriptDeployer`), `core/utils/EmbeddedX11.kt` |
| Audio | `core/terminal/PulseHost.kt`, `assets/scripts/host/start_pulse_host.sh` |
| New screen | Add a `Screen` enum value and `when` branch in `MainActivity.kt`, put the screen in `ui/screens/`, and handle back in the back chain |
| Host package set change | `native/package-lists/`, then rebuild debs with `scripts/build_packages_for_appid.sh` (needs `native/termux-packages`) |
| Release | `.agent/workflows/fdroid-update.md`, plus the commit-identity live-rule |

See [native-bootstrap.md](native-bootstrap.md) and [build-and-release.md](build-and-release.md).

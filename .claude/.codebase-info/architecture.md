# Architecture
*Last Updated: 2026-10-19*

Paths below abbreviate `app/src/main/kotlin/com/ivarna/fluxlinux/` as `KT/`.

## Layers
```
MainActivity (manual Screen enum + BottomTab)          ui/screens, ui/onboarding, ui/components, ui/theme
        │  collectAsState on singleton StateFlows
        ▼
core/* singleton `object`s (no ViewModels, no DI)
  install/   onboarding runner, rootfs download + SHA pins, per-distro profiles
  terminal/  host prefix extraction, command builders, session registry, shell runner, PulseHost
  desktop/   GUI session start/stop/readiness            service/  foreground services
  root/ chroot/ proot/   su, BusyBox, per-container state  gpu/  VirGL/Panvk drivers
  utils/     SharedPreferences wrappers, EmbeddedX11, logcat
        │  ProcessBuilder via ShellCommandRunner / RootShell
        ▼
Host prefix  filesDir/usr  (from bootstrap.tar + jniLibs libbash/libproot/libloader)
        │  proot / chroot
        ▼
Guest rootfs (12 distros, downloaded from GitHub release tag `rootfs`)
        ▲ X11 via in-process Termux-X11 server (:termux-x11)   ▲ audio via host PulseAudio
```

## Key flows
**First run (onboarding → host → rootfs)**
1. `KT/ui/onboarding/OnboardingFlowScreen.kt` calls `TerminalLauncher.prepareHost`.
2. `KT/core/terminal/BootstrapInstaller.kt` `ensureExtracted` untars the asset `bootstrap.tar` with a hand-rolled tar reader, preserving existing proot containers.
3. `HostScriptDeployer.deployScripts` runs, then `setup_termux.sh`.
4. `KT/core/install/OnboardingInstallRunner.kt` drives `runProot`/`runChroot` phases from `BaseDesktopInstallPlan`.
5. `RootfsDownloader.ensurePresent` downloads the rootfs and checks SHA-256 and free space against `DistroInstallProfile`/`PinnedReleaseArchive` pins.
6. Guest scripts run, then `StateManager.setOnboardingComplete`/`setDistroInstalled`.

**Desktop session**
1. `KT/core/desktop/DesktopLauncher.kt` `start` probes install state (`TerminalLauncher.isProotInstalled`/`isChrootInstalled`).
2. It runs `start_gui.sh` or `start_gui_chroot.sh` through `ShellCommandRunner.runStreamedCancelable` and starts `DesktopSessionService`.
3. PulseAudio comes from `app/src/main/assets/scripts/host/start_pulse_host.sh`, wrapped by `PulseHost`.
4. The X server is `KT/core/utils/EmbeddedX11.kt` `startServer`, which runs `com.termux.x11.CmdEntryPoint.main(":0", ...)` in-process. Readiness comes from log-line parsing plus `DesktopSessionQuery`.
5. `LinuxCommandBuilder.currentMethod` ("proot" or "chroot") selects `ProotCommandBuilder` or `ChrootCommandBuilder`.

**Embedded terminal**
1. `KT/ui/screens/TerminalScreen.kt` hosts `com.termux.view.TerminalView` (jitpack terminal-view).
2. `FluxTerminalSessionManager.openSessionAfterHost` calls `GuestSessionFactory.prepareSession`, which creates `com.termux.terminal.TerminalSession` (`:terminal-emulator`).
3. `SessionRegistry` keeps sessions alive through `AppTerminalService`.
4. Variants: host shell (`HostCommandBuilder`), install, uninstall, component and root-script sessions.

**Settings**: SharedPreferences only. See [patterns.md](patterns.md).

## Boundaries and invariants
- Every host path derives from `BuildConfig.APPLICATION_ID` (`TermuxHostPaths`). The bootstrap debs bake in `/data/data/<appId>/files/usr`, so a bootstrap is valid only for its own flavor. See [native-bootstrap.md](native-bootstrap.md).
- Host binaries are exec'd from `nativeLibraryDir` because of W^X, so `jniLibs.useLegacyPackaging = true` must stay.
- `FluxApp.onCreate` refreshes the host env because `nativeLibraryDir` changes on every app update.
- Legacy v1.x Termux-based distros are bridged by `KT/core/legacy/`, and `Screen.SETTINGS_LEGACY_TERMUX` exposes them.

Related: [entry-points.md](entry-points.md), [modules.md](modules.md).

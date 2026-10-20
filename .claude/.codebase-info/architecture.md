# Architecture
*Last Updated: 2026-10-20*

Paths below abbreviate `app/src/main/kotlin/com/ivarna/fluxlinux/` as `KT/`.

## Layers
```
MainActivity (manual Screen enum; Ubuntu auto-start)          ui/screens, ui/onboarding, ui/components, ui/theme
        │  collectAsState on singleton StateFlows
        ▼
core/* singleton `object`s (no ViewModels, no DI)
  install/   onboarding runner, `InstallController` (process-scoped install owner + busy guard), rootfs download + SHA pins, per-distro profiles
  terminal/  host prefix extraction, command builders, session registry, shell runner, PulseHost
  desktop/   GUI session start/stop/readiness            service/  foreground services, `LaunchTargets`
  root/ chroot/ proot/   su, BusyBox, per-container state  gpu/  VirGL/Panvk drivers
  utils/     SharedPreferences wrappers, EmbeddedX11 (X11 prefs launcher)
  files/     SAF DocumentsProvider for the Ubuntu rootfs    system/  SafeFiles, ~/storage links, storage perms
        │  ProcessBuilder via ShellCommandRunner / RootShell
        ▼
Host prefix  filesDir/usr  (from bootstrap.tar + jniLibs libbash/libproot/libloader)
        │  proot / chroot
        ▼
Guest rootfs (Ubuntu-first; 12 distros supported, downloaded from GitHub release tag `rootfs`)
        ▲ X11 via in-process Termux-X11 server (:termux-x11)   ▲ audio via host PulseAudio
```

## Key flows
**First run (onboarding → host → rootfs)**
1. `KT/ui/onboarding/OnboardingFlowScreen.kt` calls `TerminalLauncher.prepareHost`.
2. `KT/core/terminal/BootstrapInstaller.kt` `ensureExtracted` untars the asset `bootstrap.tar` with a hand-rolled tar reader. It preserves existing proot containers by moving `usr/var/lib/proot-distro` aside to `.preserved_proot_distro`. `recoverStrandedAside` restores an aside left by an interrupted run. An existing aside is never deleted; it is renamed with a timestamp instead.
3. `HostScriptDeployer.deployScripts` runs, then `setup_termux.sh`.
4. `KT/core/install/OnboardingInstallRunner.kt` drives `runProot`/`runChroot` phases from `BaseDesktopInstallPlan`. It is owned process-wide by `KT/core/install/InstallController.kt` (state as `StateFlow`, so rotation or leaving the screen never cancels an install; one install at a time across onboarding and the install wizard). The controller refuses to start while an uninstall session is open (`UninstallSessionFactory.isAnyUninstallRunning`), and `hostRepairAllowed` blocks host repair mid-install. Component re-runs from Distro Settings assemble their script with `KT/core/install/GuestComponentPayload.kt` (env exports, then `flux_guest_common.sh`/`flux_gpu_common.sh`, then the script).
5. `RootfsDownloader.ensurePresent` downloads the rootfs and checks SHA-256 and free space against `DistroInstallProfile`/`PinnedReleaseArchive` pins.
6. Guest scripts run, then `StateManager.setOnboardingComplete`/`setDistroInstalled`.

**Desktop session**
1. `KT/core/desktop/DesktopLauncher.kt` `start` probes install state (`TerminalLauncher.isProotInstalled`/`isChrootInstalled`).
2. It runs `start_gui.sh` or `start_gui_chroot.sh` through `ShellCommandRunner.runStreamedCancelable` (its own process group, so cancel kills proot/apt/git children too) and starts `DesktopSessionService`. Start/stop ordering is `KT/core/desktop/DesktopRunState.kt` (pure, unit-tested): every start/stop opens a generation, callbacks from older generations are ignored, and a start waits for a running stop script instead of cancelling it.
3. PulseAudio comes from `app/src/main/assets/scripts/host/start_pulse_host.sh`, wrapped by `PulseHost`. It listens on a unix socket `$PREFIX/tmp/pulse-native` only (no TCP). Guests reach it at `unix:/tmp/pulse-native` (proot, `--shared-tmp`) or `unix:/mnt/host-tmp/pulse-native` (chroot).
4. The X server is launched by the `start_gui*.sh` scripts: `app_process` with `loader.apk` runs `com.termux.x11.Loader :0` (CmdEntryPoint), then the script waits for `$TMPDIR/.X11-unix/X0`. `DesktopLauncher.openX11` opens `com.termux.x11.MainActivity`.
5. Readiness: the Running phase only flips on the guest marker line `FLUX_DESKTOP_READY` (`DesktopLauncher.isDesktopReadyLine`), which the proot `start_gui.sh` and chroot launchers print only if the desktop process is still alive about 2 s after launch. The host X-socket line is never a marker; `DesktopSessionQuery` serves state queries.
6. `LinuxCommandBuilder.currentMethod` ("proot" or "chroot") selects `ProotCommandBuilder` or `ChrootCommandBuilder`.

**Embedded terminal**
1. `KT/ui/screens/TerminalScreen.kt` hosts `com.termux.view.TerminalView` (jitpack terminal-view). There is no distro picker (v2.1.2): with no sessions it auto-opens an Ubuntu PRoot user shell once per entry (failure shows a retry state). There is no top bar or tab row (after v2.1.5): a Termux-style left drawer (`TerminalDrawerSheet`) lists sessions (tap to switch, 48dp ✕, long-press Close / Close other tabs) and holds "New Ubuntu session" (long-press or ▾ offers Ubuntu root, Ubuntu chroot when root is granted + installed, and the Termux host shell), the extra-keys switch and Back to home. `KT/ui/terminal/TerminalSessionDefaults.kt` picks those cards from `TerminalShellCatalog`. The drawer opens from the ☰ key at the left of the extra-keys bar or by swiping from `DrawerEdgeZone`, a 20dp×192dp left-edge strip over the `TerminalView` with `systemGestureExclusion` (the AndroidView would otherwise swallow the drawer drag).
2. `FluxTerminalSessionManager.openSessionAfterHost` calls `GuestSessionFactory.prepareSession`, which creates `com.termux.terminal.TerminalSession` (`:terminal-emulator`).
3. `SessionRegistry` keeps sessions alive through `AppTerminalService`.
4. Variants: host shell (`HostCommandBuilder`), install, uninstall, component and root-script sessions. `UninstallSessionFactory` tracks open uninstalls so installs and desktop starts refuse a rootfs being removed.
5. Extra-keys bar: `KT/ui/terminal/TerminalExtraKeys.kt`. A fixed ☰ column (opens the drawer) sits left of two rows of 12 keys that share one horizontal scroll: each starts with Termux's default 7 (`ESC / ― HOME ↑ END PGUP` / `↹ CTRL ALT ← ↓ → PGDN`) and continues past the right edge with `| ~ _ \ BKSP` / `SHFT Fn INS DEL ENT` (row data test-pinned by `TerminalExtraKeysLayoutTest`). All 12 columns show unscrolled when cells would be ≥56dp; Fn adds F1–F12 as a third row on the same grid. Held arrows, PGUP/PGDN, BKSP and DEL repeat on the `KT/ui/terminal/KeyRepeat.kt` schedule: Termux-constant by default (first repeat at 400 ms, then every 80 ms), or accelerating (100→30 ms) when `TerminalPreferences.isKeyRepeatAccelerated` (the "Accelerate held keys" switch in Terminal settings) is on.
6. Ubuntu zsh profile: `KT/core/terminal/ZshPluginSet.kt` is the single source of truth for the oh-my-zsh plugin order, the managed `.zshrc` block (plugins built at start-up from what is installed; Up/Down bound to history-substring-search), apt deps (fzf, zoxide, command-not-found) and the fzf-tab clone. `GuestZshrcRepair` upgrades existing Ubuntu profiles that still carry the Flux default plugin line (backup `.zshrc.flux-bak`), `core/install/ProotZshBootstrap` clones fzf-tab and installs the deps lazily (as its own process group with a timeout), and `scripts/common/setup/setup_customization_xfce.sh` carries a verbatim copy enforced by `ZshPluginSetTest`.

**Root / chroot**
1. `KT/core/root/RootShell.kt` resolves `su` once (single-flight walk over `SU_TRIALS`; a probe timeout stops the walk, nothing cached on failure). `captureResult`/`executeSync` take `timeoutMs` (exit -2 = timeout, -1 = no su; see `failureReason`). Root commands are wrapped with a pid marker so cancel or timeout tree-kills the root job (`killTreeThenDestroy`, `buildTreeKillScript`), off the caller thread.
2. Root-run files live in a root-only stage dir `ChrootPaths.ROOT_STAGE_DIR` = `/data/adb/fluxlinux/<applicationId>/` (M14), never the shell-writable `/data/local/tmp` (legacy copies are only removed). `fluxlinux_chroot.sh` and its `resolve_bb.sh` sidecar are staged there and SHA-256-verified against the APK assets (`RootShell.ensureChrootHelper`/`stageVerified`). `BusyBoxPaths.CANDIDATES` lists the BusyBox locations; BusyBox is used in place, never copied.
3. `app/src/main/assets/scripts/chroot/fluxlinux_chroot.sh` (v2.9) takes a shared mkdir mount lock (`<chroot>/.flux_mount.lock`, owner file `pid boot_id epoch`). A lock is broken when its holder is gone, pid-less, from a previous boot or not a FluxLinux script, under a second mkdir mutex. `stop_*_gui.sh` and `uninstall_*_chroot.sh` carry an identical copy (keep them in sync; `MountLockTest`). Each chroot session registers `<chroot>/.flux_sessions/<pid>`, so mounts stay while any session lives. Install state markers: `.flux_configured` (setup finished), `.flux_extract_ok` (rootfs extracted, holds the SHA; also used for proot containers), `.flux_uninstalling` (set by the uninstall scripts; `fluxlinux_chroot.sh` refuses to mount while it exists).

**File access (v2.1.0)**
1. `KT/core/system/GuestStorageLinks.kt` `ensure(ctx, "ubuntu")` creates Termux-style `~/storage/{shared,downloads,documents,dcim,pictures,music,movies}` links to `/storage/emulated/0[/...]` in `/home/flux` and `/root`. It is idempotent and never deletes or replaces anything. It is called from Home, `GuestSessionFactory`, `DesktopLauncher` and `OnboardingInstallRunner`.
2. `KT/core/system/SharedStorageAccess.kt` handles permission state and requests: All files access on ivarna API 30+, legacy READ/WRITE below 30.
3. `UbuntuDocumentsProvider` serves the rootfs to SAF. `KT/core/files/GuestDocumentPaths.kt` resolves guest-style symlinks confined to the rootfs, the container and shared storage. Docs reached through a symlinked directory (`traversesLink`) are browse/create only: no delete, move or rename, and no tree-grant child.

**Settings**: SharedPreferences only. See [patterns.md](patterns.md).

## Boundaries and invariants
- Every host path derives from `BuildConfig.APPLICATION_ID` (`TermuxHostPaths`). The bootstrap debs bake in `/data/data/<appId>/files/usr`, so a bootstrap is valid only for its own flavor. See [native-bootstrap.md](native-bootstrap.md).
- Host binaries are exec'd from `nativeLibraryDir` because of W^X, so `jniLibs.useLegacyPackaging = true` must stay.
- `FluxApp.onCreate` refreshes the host env because `nativeLibraryDir` changes on every app update.
- Legacy v1.x Termux-based distros are bridged by `KT/core/legacy/`, and `Screen.SETTINGS_LEGACY_TERMUX` exposes them.
- **Child processes run as their own process group** so cancel/timeout reaches descendants: `ProcessGroupLauncher.wrap` (a `set -m` bash wrapper from `libbash`, no `setsid` needed) in `ShellCommandRunner.kt`, with a SIGKILL escalation on destroy. Do not start long host jobs with a bare `ProcessBuilder`.
- `LaunchTargets` (`KT/core/service/LaunchTargets.kt`, pure) validates what a notification/termux-x11 intent may open (`EXTRA_TARGET_PAGE`: home, terminal, install) and which distro ids a `fluxlinux://callback?name=distro_(un)install_<id>` callback may name (callbacks are unauthenticated).
- The activity-to-X-server socket uses full-length framed I/O: `termux-x11/src/main/cpp/lorie/conn_io.h` (`lorie_read_full`/write loops, payload caps) included by `activity.cpp`, `cmdentrypoint.cpp`, `buffer.c`, `renderer.cpp`.
- XFCE theme/icon/cursor archives: `KT/core/install/ProotXfceAssetInstaller.kt` (`installInto`, injectable extract) always leaves guest copies in `$PREFIX/tmp/flux_xfce_assets` (guest `/tmp/flux_xfce_assets` = `FLUX_ASSET_DIR`). Stock Android tar can't run `xz` (rc 127), so host `.tar.xz` extracts are skipped, and the guest's GNU tar extracts the copies. Never delete those copies on a host failure (v2.1.3 regression). PRoot guest scripts get `FLUX_PROOT=1` (fallback: non-zero `TracerPid`). `setup_customization_xfce.sh` uses it to skip the full `papirus-icon-theme` apt package, a many-minute unpack under PRoot.
- **Never follow symlinks in recursive file ops.** Guest homes link `~/storage/*` to shared storage. Use `KT/core/system/SafeFiles.kt` (`deleteTree`, `copyTree`, `walkNoFollow`, `isSymlink`; java.nio NOFOLLOW_LINKS). `SymlinkSafetyContractTest` fails on `deleteRecursively`, `copyRecursively`, `walkTopDown` and `File.walk()` in production code.

Related: [entry-points.md](entry-points.md), [modules.md](modules.md).

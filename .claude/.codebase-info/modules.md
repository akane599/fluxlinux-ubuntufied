# Modules
*Last Updated: 2026-10-20*

## Gradle modules
| Module | Lang | Purpose | Used by |
|---|---|---|---|
| `:app` | Kotlin (all) | The app. Compose UI plus `core/*` logic | — |
| `:terminal-emulator` | Java + JNI (`src/main/jni/termux.c`, ndkBuild, arm64) | termux-app `terminal-emulator` v0.118.0, vendored unmodified (GPLv3) and built from source for 16 KB page alignment. Package `com.termux.terminal` (`TerminalSession`, `TerminalEmulator`, `TerminalBuffer`, ...) | `:app` `implementation`. Replaces the jitpack copy pulled by `terminal-view` |
| `:termux-x11` | Java + C/CMake (`src/main/cpp/CMakeLists.txt`, `lorie/`, `patches/`, `recipes/`, 16 xorg submodules) | Embedded Termux:X11 1.03.01 fork: X server `libXlorie.so` plus the client Activity. The activity-to-server event socket uses full-length framed I/O from `src/main/cpp/lorie/conn_io.h` (`lorie_read_full`/`write_full`/`skip_full`, `LORIE_MAX_PAYLOAD` 16 MiB). Packages `com.termux.x11` (`MainActivity`, `LorieView`, `CmdEntryPoint`, `Prefs`, `LoriePreferences`), `.input`, `.utils` | `:app` `implementation` |
| `:stub` | Java | Hidden-API compile stubs (`android.app.ActivityThread`, `IActivityManager`, `IPackageManager`, ...) | `:termux-x11` `compileOnly` only |

`:app` also uses `com.github.termux.termux-app:terminal-view` v0.118.0 from jitpack for `com.termux.view.TerminalView`.

## `:app` packages (`app/src/main/kotlin/com/ivarna/fluxlinux/`)
| Package | Purpose | Key files |
|---|---|---|
| `core/terminal` | Core: host prefix, command builders, sessions, shell exec, audio | `BootstrapInstaller`, `HostScriptDeployer`, `TerminalLauncher`, `FluxTerminalSessionManager`, `SessionRegistry`, `GuestSessionFactory`, `ZshPluginSet`, `Install/UninstallSessionFactory`, `Linux/Proot/Chroot/HostCommandBuilder`, `ShellCommandRunner` (+ `ProcessGroupLauncher`), `PulseHost` (unix-socket PulseAudio), `TermuxHostPaths`, guest repair (`GuestApkDbRepair`, `GuestZshrcRepair`, `GuestRepairCache`), `GpuAccelDetector` |
| `core/install` | Install pipeline and pins | `HostBootstrap`, `PinnedReleaseArchive`, `RootfsDownloader`, `DistroInstallProfile` (rootfs SHA SSOT), `BaseDesktopInstallPlan`, `OnboardingInstallRunner`, `InstallController` (process-scoped install owner, uninstall busy guard), `GuestComponentPayload` (component script + helper-lib assembly), `ProotXfceAssetInstaller`, `ProotZshBootstrap` |
| `core/desktop` | GUI session lifecycle | `DesktopLauncher`, `DesktopRunState` (start/stop generations, pure), `DesktopSession` (`DesktopSessionQuery`), `GuiDesktopLog` |
| `core/service` | Foreground services, launch-intent validation | `AppTerminalService`, `DesktopSessionService`, `BaseInstallService`, `LaunchTargets` (pure; see [entry-points.md](entry-points.md)) |
| `core/root`, `core/chroot`, `core/proot` | su/BusyBox, and chroot/proot container state, size, settings, kill/process mgmt | `RootShell` (su resolve, exec timeouts, root tree-kill, verified staging to `/data/adb/fluxlinux/<appId>`), `ChrootPaths`, `BusyBoxPaths`, `ChrootDetection`, `ChrootSettingsModel`, `ProotSettingsModel`, `*SizeManager`, `*InfoStore` |
| `core/gpu` | GPU drivers (VirGL, Panvk) | `GpuDriverInstaller`, `PanvkInstaller`, `DriverRelease` |
| `core/legacy` | Bridge to v1.x Termux-hosted distros | `LegacyTermuxBridge`, `LegacyTermuxStore` |
| `core/system` | Android workarounds, file safety | `PhantomProcessFixer`, `SharedStorageAccess`, `SafeFiles` (no-follow tree ops), `GuestStorageLinks` (`~/storage`) |
| `core/files` | SAF access to the Ubuntu rootfs | `UbuntuDocumentsProvider`, `GuestDocumentPaths` (pure, unit-tested path/link resolution), `UbuntuFiles` (authority, open, notifyRootsChanged) |
| `core/data`, `core/model` | Distro catalog and script layer | `DistroRepository`, `Distro`, `DistroSpec`, `ScriptManager`, `TerminalComponent` |
| `core/utils` | Prefs, misc | `StateManager`, `UbuntuLaunchPreferences`, `TerminalPreferences`, `ThemePreferences`, `TermuxX11Preferences`, `RootUtils`, `SystemInfoUtils`, `EmbeddedX11` (opens the X11 preferences activity only), `InstallationQueueManager` |
| `ui/*` | Compose UI | `screens/` (Prerequisites, RootAccess and ChrootSettings screens were deleted), `onboarding/`, `components/` (`Glass*` + `UbuntuDashboard`), `terminal/` (extra keys + `KeyRepeat`, `TerminalSessionDefaults`, IME), `install/`, `theme/` (Color/Theme/Type) |

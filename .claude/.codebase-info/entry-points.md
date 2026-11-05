# Entry points
*Last Updated: 2026-11-05*

`KT/` = `app/src/main/kotlin/com/ivarna/fluxlinux/`. The manifest is `app/src/main/AndroidManifest.xml`. The `ivarna` flavor adds `MANAGE_EXTERNAL_STORAGE` via `app/src/ivarna/AndroidManifest.xml`.

| Component | Class | Notes |
|---|---|---|
| Application | `KT/FluxApp.kt` | Refreshes the host env (`TermuxHostPaths.refreshHostEnvIfStale`) |
| Activity (launcher) | `KT/MainActivity.kt` | The only app Activity. LAUNCHER plus a VIEW/BROWSABLE filter. `onNewIntent` can switch screens; target pages and callback distro ids are validated by `KT/core/service/LaunchTargets.kt` |
| Activity (X11) | `com.termux.x11.MainActivity` (`:termux-x11`) | Opened by `DesktopLauncher.openX11`; X11 prefs (`LoriePreferences`) via `KT/core/utils/EmbeddedX11.kt` `launchPreferences` |
| FGS | `KT/core/service/AppTerminalService.kt` | Keeps terminal sessions alive. Started by `SessionRegistry`; `ACTION_TOGGLE_WAKELOCK` (notification action, drawer switch) holds/releases `TerminalWakeLock` |
| FGS | `KT/core/service/DesktopSessionService.kt` | Keeps the GUI session alive. Started by `DesktopLauncher` |
| FGS | `KT/core/service/BaseInstallService.kt` | dataSync. Install progress notification |
| DocumentsProvider | `KT/core/files/UbuntuDocumentsProvider.kt` | Authority `${applicationId}.documents`, exported with `MANAGE_DOCUMENTS`. Serves the active runtime (`UbuntuRuntime`): SAF roots `ubuntu_home` ("Ubuntu", /home/akane, or legacy /home/flux before migration) and `ubuntu_root` ("Ubuntu filesystem", /) in PRoot mode, `ubuntu_chroot_home`/`ubuntu_chroot_root` in chroot mode (via su). Opened via `KT/core/files/UbuntuFiles.open`, which maps the generic ids to the active mode |
| Provider | `rikka.shizuku.ShizukuProvider` | The FileProvider entry and `res/xml/file_paths.xml` were removed |

## Navigation (no NavHost)
- `MainActivity.kt` declares `enum class Screen` and switches on it with `when (currentScreen)`. `currentScreen` is `remember { mutableStateOf }`, mirrored into a `MutableStateFlow` for `onNewIntent`. Back handling is a hand-written if/else chain.
- `Screen` values: `ONBOARDING, PREREQUISITES, HOME, SETTINGS, SETTINGS_TERMINAL, SETTINGS_X11, SETTINGS_AUDIO, SETTINGS_CHROOT, SETTINGS_CHROOT_DETAIL, SETTINGS_PROOT, SETTINGS_PROOT_DETAIL, SETTINGS_LEGACY_TERMUX, TROUBLESHOOTING, INSTALL_WIZARD, DISTRO_SETTINGS, TERMINAL` (`ROOT_ACCESS` was removed).
- `TERMINAL` and `PREREQUISITES` are legacy enum values that redirect to `HOME` (their screens, `PrerequisitesScreen.kt`, `RootAccessScreen.kt` and `ChrootSettingsScreen.kt`, are deleted; `SETTINGS_CHROOT*` now use `ChrootStorageListScreen`/`ChrootStorageDetailScreen`).
- There is no bottom bar any more (v2.1.0). `GlassBottomNavigation.kt` only holds `BottomTab { HOME, TERMINAL }`, used for intent routing. `DistroScreen.kt` was deleted.
- `HOME` has two tabs (`currentTab`, default `TERMINAL`): the terminal (`TerminalScreen`, the app's home after onboarding) and the Ubuntu page, reached from the terminal drawer under an M3 `TopAppBar` (← terminal, Settings). The Ubuntu page is `KT/ui/screens/HomeScreen.kt` wrapping the stateless `KT/ui/components/UbuntuDashboard.kt`: header with runtime chip and CHECKING/NEEDS_ROOT states, a state-driven Desktop section (`DesktopAddOnUi`: install / progress / failed / start-open-stop + log), Files (home, filesystem, shared storage) and recovery. Back: Ubuntu page → terminal; Settings → where it was opened; the terminal root → system. `SETTINGS` carries the "Ubuntu runtime" card (`ui/components/UbuntuRuntimeCard.kt`).
- **No desktop auto-start** (removed on `feat/terminal-first`): the desktop starts only from the Ubuntu page or onboarding's Done page, and only when it is installed (`UbuntuDesktop.detect`; `DesktopLauncher` refuses with "Install the desktop first", or a retry hint for an unfinished install).
- Screens live in `KT/ui/screens/` (Home, Settings*, Terminal, Troubleshooting, DistroSettings, InstallConfig, ...). Onboarding is `KT/ui/onboarding/OnboardingFlowScreen.kt` and installs Ubuntu only, with no distro pick. It offers a PRoot-or-chroot runtime choice only when root is available, an off-by-default "Also install the XFCE desktop" switch (`InstallKind`), plus a storage-access step. Done opens the terminal; "Start desktop" appears only when the desktop was installed.

## Script entry points
- Guest and host shell scripts live in `app/src/main/assets/scripts/<alpine|arch|chimera|chroot|common|debian|deepin|fedora|host|kali|legacy-termux|manjaro|opensuse|parrot|termux|ubuntu|void>/`. `HostScriptDeployer` deploys them.
- Host scripts: `scripts/host/setup_termux.sh`, `start_pulse_host.sh` (PulseAudio on a unix socket), `repair_pulse_guests.sh`. Chroot scripts (`scripts/chroot/`): `fluxlinux_chroot.sh` (root helper v3.0, mount lock, guest user, storage links), `setup_guest_chroot.sh`, `resolve_bb.sh`, `start_*_gui.sh`/`stop_*_gui.sh`, `uninstall_*_chroot.sh`.
- Build-time scripts in the repo-root `scripts/` are covered in [native-bootstrap.md](native-bootstrap.md).

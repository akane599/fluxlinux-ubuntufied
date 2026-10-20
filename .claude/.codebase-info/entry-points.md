# Entry points
*Last Updated: 2026-10-20*

`KT/` = `app/src/main/kotlin/com/ivarna/fluxlinux/`. The manifest is `app/src/main/AndroidManifest.xml`. The `ivarna` flavor adds `MANAGE_EXTERNAL_STORAGE` via `app/src/ivarna/AndroidManifest.xml`.

| Component | Class | Notes |
|---|---|---|
| Application | `KT/FluxApp.kt` | Refreshes the host env (`TermuxHostPaths.refreshHostEnvIfStale`) |
| Activity (launcher) | `KT/MainActivity.kt` | The only app Activity. LAUNCHER plus a VIEW/BROWSABLE filter. `onNewIntent` can switch screens; target pages and callback distro ids are validated by `KT/core/service/LaunchTargets.kt` |
| Activity (X11) | `com.termux.x11.MainActivity` (`:termux-x11`) | Opened by `DesktopLauncher.openX11`; X11 prefs (`LoriePreferences`) via `KT/core/utils/EmbeddedX11.kt` `launchPreferences` |
| FGS | `KT/core/service/AppTerminalService.kt` | Keeps terminal sessions alive. Started by `SessionRegistry` |
| FGS | `KT/core/service/DesktopSessionService.kt` | Keeps the GUI session alive. Started by `DesktopLauncher` |
| FGS | `KT/core/service/BaseInstallService.kt` | dataSync. Install progress notification |
| DocumentsProvider | `KT/core/files/UbuntuDocumentsProvider.kt` | Authority `${applicationId}.documents`, exported with `MANAGE_DOCUMENTS`. SAF roots `ubuntu_home` ("Ubuntu", /home/flux) and `ubuntu_root` ("Ubuntu filesystem", /). Opened via `KT/core/files/UbuntuFiles.open` |
| Provider | `rikka.shizuku.ShizukuProvider` | The FileProvider entry and `res/xml/file_paths.xml` were removed |

## Navigation (no NavHost)
- `MainActivity.kt` declares `enum class Screen` and switches on it with `when (currentScreen)`. `currentScreen` is `remember { mutableStateOf }`, mirrored into a `MutableStateFlow` for `onNewIntent`. Back handling is a hand-written if/else chain.
- `Screen` values: `ONBOARDING, PREREQUISITES, HOME, SETTINGS, SETTINGS_TERMINAL, SETTINGS_X11, SETTINGS_AUDIO, SETTINGS_CHROOT, SETTINGS_CHROOT_DETAIL, SETTINGS_PROOT, SETTINGS_PROOT_DETAIL, SETTINGS_LEGACY_TERMUX, TROUBLESHOOTING, INSTALL_WIZARD, DISTRO_SETTINGS, TERMINAL` (`ROOT_ACCESS` was removed).
- `TERMINAL` and `PREREQUISITES` are legacy enum values that redirect to `HOME` (their screens, `PrerequisitesScreen.kt`, `RootAccessScreen.kt` and `ChrootSettingsScreen.kt`, are deleted; `SETTINGS_CHROOT*` now use `ChrootStorageListScreen`/`ChrootStorageDetailScreen`).
- There is no bottom bar any more (v2.1.0). `GlassBottomNavigation.kt` only holds `BottomTab { HOME, TERMINAL }`, used for intent routing. `DistroScreen.kt` was deleted.
- `HOME` is the Ubuntu dashboard: `KT/ui/screens/HomeScreen.kt` wraps the stateless `KT/ui/components/UbuntuDashboard.kt` (hero, quick actions, shared storage, recovery).
- **Auto-start:** on a plain cold launch after onboarding, `MainActivity` starts the Ubuntu XFCE desktop after a 1.5 s cancellable countdown. It is gated by `KT/core/utils/UbuntuLaunchPreferences.kt` (prefs `flux_ubuntu_launch`: `auto_start_desktop`, plus the crash-loop guard `auto_start_attempt_pending`). If a session is already running, the display is reopened instead.
- Screens live in `KT/ui/screens/` (Home, Settings*, Terminal, Troubleshooting, DistroSettings, InstallConfig, ...). Onboarding is `KT/ui/onboarding/OnboardingFlowScreen.kt` and installs Ubuntu only, with no distro or method pick, plus a storage-access step.

## Script entry points
- Guest and host shell scripts live in `app/src/main/assets/scripts/<alpine|arch|chimera|chroot|common|debian|deepin|fedora|host|kali|legacy-termux|manjaro|opensuse|parrot|termux|ubuntu|void>/`. `HostScriptDeployer` deploys them.
- Host scripts: `scripts/host/setup_termux.sh`, `start_pulse_host.sh` (PulseAudio on a unix socket), `repair_pulse_guests.sh`. Chroot scripts (`scripts/chroot/`): `fluxlinux_chroot.sh` (root helper, mount lock), `resolve_bb.sh`, `start_*_gui.sh`/`stop_*_gui.sh`, `uninstall_*_chroot.sh`.
- Build-time scripts in the repo-root `scripts/` are covered in [native-bootstrap.md](native-bootstrap.md).

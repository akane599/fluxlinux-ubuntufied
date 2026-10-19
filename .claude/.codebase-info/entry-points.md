# Entry points
*Last Updated: 2026-10-19*

`KT/` = `app/src/main/kotlin/com/ivarna/fluxlinux/`. The manifest is `app/src/main/AndroidManifest.xml`. The `ivarna` flavor adds `MANAGE_EXTERNAL_STORAGE` via `app/src/ivarna/AndroidManifest.xml`.

| Component | Class | Notes |
|---|---|---|
| Application | `KT/FluxApp.kt` | Refreshes the host env (`TermuxHostPaths.refreshHostEnvIfStale`) |
| Activity (launcher) | `KT/MainActivity.kt` | The only app Activity. LAUNCHER plus a VIEW/BROWSABLE filter. `onNewIntent` can switch screens |
| Activity (X11) | `com.termux.x11.MainActivity` (`:termux-x11`) | Opened by `KT/core/utils/EmbeddedX11.kt` `launchDisplay` |
| FGS | `KT/core/service/AppTerminalService.kt` | Keeps terminal sessions alive. Started by `SessionRegistry` |
| FGS | `KT/core/service/DesktopSessionService.kt` | Keeps the GUI session alive. Started by `DesktopLauncher` |
| FGS | `KT/core/service/BaseInstallService.kt` | dataSync. Install progress notification |
| Service | `KT/core/service/InstallServerService.kt` | Local install server (`core/utils/LocalInstallServer.kt`) |
| Providers | `androidx.core.content.FileProvider`, `rikka.shizuku.ShizukuProvider` | |

## Navigation (no NavHost)
- `MainActivity.kt` declares `enum class Screen` and switches on it with `when (currentScreen)`. `currentScreen` is `remember { mutableStateOf }`, mirrored into a `MutableStateFlow` for `onNewIntent`. Back handling is a hand-written if/else chain.
- `Screen` values: `ONBOARDING, PREREQUISITES, HOME, SETTINGS, SETTINGS_TERMINAL, SETTINGS_X11, SETTINGS_AUDIO, SETTINGS_CHROOT, SETTINGS_CHROOT_DETAIL, SETTINGS_PROOT, SETTINGS_PROOT_DETAIL, SETTINGS_LEGACY_TERMUX, TROUBLESHOOTING, ROOT_ACCESS, INSTALL_WIZARD, DISTRO_SETTINGS, TERMINAL`.
- `TERMINAL` and `PREREQUISITES` are legacy and redirect to `HOME`. `KT/ui/screens/PrerequisitesScreen.kt` (about 2.4k lines) is not routed any more.
- Bottom tabs: `BottomTab { HOME, DISTROS, TERMINAL }` in `KT/ui/components/GlassBottomNavigation.kt`.
- Screens live in `KT/ui/screens/` (Home, Settings*, Terminal, Troubleshooting, RootAccess, DistroSettings, ...). Onboarding is `KT/ui/onboarding/OnboardingFlowScreen.kt`.

## Script entry points
- Guest and host shell scripts live in `app/src/main/assets/scripts/<alpine|arch|chimera|chroot|common|debian|deepin|fedora|host|kali|legacy-termux|manjaro|opensuse|parrot|termux|ubuntu|void>/`. `HostScriptDeployer` deploys them.
- Host scripts: `scripts/host/setup_termux.sh`, `start_pulse_host.sh`, `repair_pulse_guests.sh`.
- Build-time scripts in the repo-root `scripts/` are covered in [native-bootstrap.md](native-bootstrap.md).

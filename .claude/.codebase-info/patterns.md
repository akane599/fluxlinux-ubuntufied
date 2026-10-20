# Patterns, testing and style
*Last Updated: 2026-10-20*

## Code patterns (`:app`)
- **State:** no ViewModels and no DI. Screens use Compose `remember`/`mutableStateOf`. Shared state lives in singleton `object`s exposing `StateFlow` (`FluxTerminalSessionManager`, `DesktopLauncher`, `SessionRegistry`, `InstallationQueueManager`, `StateManager`, ...), which UI reads with `collectAsState`.
- **Threading:** mostly raw `Thread {}` and single-thread executors that post back through a main `Handler`, plus some `Dispatchers.IO` coroutines. Follow the local style of the file you are in.
- **Shell execution:** `core/terminal/ShellCommandRunner` (`run`, `runCapture`, `runCaptureExit`, `runStreamed`, `runStreamedCancelable` → `ShellJob`) runs against the host prefix. `core/root/RootShell` handles su, scripts and BusyBox (`captureResult`/`executeSync` take `timeoutMs`; exit -2 = timeout, -1 = no su). Command builders return `(args, env)`, and heavy logic sits in the asset shell scripts. Cancellable or long host jobs go through `ProcessGroupLauncher.wrap` so cancel kills the whole process group, and root jobs are tree-killed by pid marker. The `execute*` convenience helpers and `runComponentChain` were removed; component scripts are assembled by `GuestComponentPayload`.
- **Pure state cores:** order-sensitive logic is split out of Android-bound objects so it is JVM-testable: `DesktopRunState` (start/stop generations), `LaunchTargets`, `GuestComponentPayload`, `InstallController` (takes an `InstallEngine`). Stale-callback guards use a generation counter, not flags.
- **Paths:** always go through `TermuxHostPaths` / `ChrootPaths` / `BusyBoxPaths`, which derive from `BuildConfig.APPLICATION_ID`. Never hard-code `com.ivarna.fluxlinux`.
- **Prefs:** SharedPreferences only.
  - `fluxlinux_state` (`StateManager`), `flux_terminal_prefs` (`TerminalPreferences`), `theme_prefs` (`ThemePreferences`).
  - `flux_ubuntu_launch` (`UbuntuLaunchPreferences`: auto-start switch and crash-loop guard).
  - `flux_legacy_termux_prefs` (`LegacyTermuxStore`), `flux_v1_notice`, `TermuxX11Preferences`, `GuestRepairCache`.
- **Errors/logging:** `runCatching` and Boolean/Pair results, with `Log.w/e/i` and a per-object `TAG`. File logs come from `GuiDesktopLog` and `PulseHost` (cacheDir).
- **File trees:** never use `File.deleteRecursively`, `copyRecursively`, `walkTopDown` or `walk()`. Use `core/system/SafeFiles`. Tests that create links must point them at temp dirs only, and clean up with `SafeFiles.deleteTree`.
- **UI:** the custom "Glass" design system in `ui/components/` (`GlassCard`, `GlassScaffold`, `GlassBottomNavigation`), with haze blur. There are many literal colors outside `ui/theme/`.

## Testing
- JVM JUnit4 only: `app/src/test/java/com/ivarna/fluxlinux/{core/*,ui/*,scripts}` plus a second root `app/src/test/kotlin/com/ivarna/fluxlinux/{core/install,ui/screens}`, with `unitTests.isReturnDefaultValues = true`. There is no Robolectric, mockk or instrumented tests.
- Screenshot tests use Compose Preview Screenshot Testing. `@PreviewTest` previews live in `app/src/screenshotTest/kotlin/com/ivarna/fluxlinux/ui/ScreenshotPreviews.kt` and are built from stateless leaf composables with fake data, because whole screens read device state. References are in `app/src/screenshotTestIvarnaDebug/reference/` (ivarna flavor only). Write them with `:app:updateIvarnaDebugScreenshotTest` and diff with `:app:validateIvarnaDebugScreenshotTest`.
- Fakes: `core/terminal/FakeContext.kt` and `core/utils/FakePrefsContext.kt` (`InMemoryPrefs`). Production test seams such as `RootShell.seedSuInvocationForTest` exist.
- **Contract tests** read production sources and scripts as text via a `repoFile(rel)` helper and assert patterns. Examples: `OnboardingConsentContractTest`, `KdeDesktopContractTest`, `LegacyTermux*ContractTest`, `AudioSettingsContractTest`, and since the audit remediation `ChrootLifecycleContractTest`, `ChrootStartGuiContractTest`/`ProotStartGuiContractTest`, `ServiceLifecycleContractTest`, `UninstallBusyGuardContractTest`, `OnboardingCompletionContractTest`, `BackupRulesContractTest`, `CustomizationXfceScriptContractTest`, `SymlinkSafetyContractTest`. Renaming or restructuring code can break them without any behavior change.
- **Shell-script fixture tests:** the real asset scripts run under the host shell. `scripts/GuestShellScriptTest` runs each `app/src/test/shell/*_test.sh` (`gpu_mode_default`, `pulse_repair_chroot`, `pulse_unix_socket`, `xfwm4_no_compositing`) with `/bin/sh <assets-dir>`; each sources the asset helpers and works in a `mktemp -d` dir (exit 0 = pass). `MountLockTest`, `StopChrootInUseTest`, `ChrootSetupScriptTest`, `ChrootUninstallScriptTest` and `ProcessGroupKillTest` also execute the scripts or the process-group wrapper; the script-driven ones `assumeTrue` a POSIX `/bin/sh`.
- Builder and parser tests: `ProotCommandBuilderTest`, `ChrootCommandBuilderTest`, `LinuxCommandBuilderTest`, `PulseHostTest`, `HostBootstrapTest`, `RootfsDownloaderTest`.
- Run: `bash .claude/kit/gradle-check.sh :app:testIvarnaDebugUnitTest`. The suite has about 722 `@Test` methods after the audit remediation (461 at v2.1.0, PROGRESS.md).

## Style
- One top-level `object` or file per concern, PascalCase. Tests are named `method_condition` or `*ContractTest`.
- Comments are fairly dense and explain why, often tagged with an issue or plan id (`#37`, `proot-opt-01`). KDoc sits on most objects.
- Fully qualified names inline (`com.ivarna.fluxlinux.core...`) are common in `MainActivity` and screens.
- Large files are common: `OnboardingFlowScreen.kt` 1.6k, `MainActivity.kt` 1.3k, `LegacyTermuxSettingsScreen.kt` 1.1k, `OnboardingInstallRunner.kt` 0.9k, `RootShell.kt` 0.9k, `UbuntuDashboard.kt` 0.8k lines.
- No ktlint, detekt or `.editorconfig`. Android lint uses `abortOnError = false`.

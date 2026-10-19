# Patterns, testing and style
*Last Updated: 2026-10-19*

## Code patterns (`:app`)
- **State:** no ViewModels and no DI. Screens use Compose `remember`/`mutableStateOf`. Shared state lives in singleton `object`s exposing `StateFlow` (`FluxTerminalSessionManager`, `DesktopLauncher`, `SessionRegistry`, `InstallationQueueManager`, `StateManager`, ...), which UI reads with `collectAsState`.
- **Threading:** mostly raw `Thread {}` and single-thread executors that post back through a main `Handler`, plus some `Dispatchers.IO` coroutines. Follow the local style of the file you are in.
- **Shell execution:** `core/terminal/ShellCommandRunner` (`run`, `runCapture`, `runCaptureExit`, `runStreamed`, `runStreamedCancelable` → `ShellJob`) runs against the host prefix. `core/root/RootShell` handles su, scripts and BusyBox. Command builders return `(args, env)`, and heavy logic sits in the asset shell scripts.
- **Paths:** always go through `TermuxHostPaths` / `ChrootPaths` / `BusyBoxPaths`, which derive from `BuildConfig.APPLICATION_ID`. Never hard-code `com.ivarna.fluxlinux`.
- **Prefs:** SharedPreferences only.
  - `fluxlinux_state` (`StateManager`), `flux_terminal_prefs` (`TerminalPreferences`), `theme_prefs` (`ThemePreferences`).
  - `flux_legacy_termux_prefs` (`LegacyTermuxStore`), `flux_v1_notice`, `TermuxX11Preferences`, `GuestRepairCache`.
- **Errors/logging:** `runCatching` and Boolean/Pair results, with `Log.w/e/i` and a per-object `TAG`. File logs come from `GuiDesktopLog` and `PulseHost` (cacheDir).
- **UI:** the custom "Glass" design system in `ui/components/` (`GlassCard`, `GlassScaffold`, `GlassBottomNavigation`), with haze blur. There are many literal colors outside `ui/theme/`.

## Testing
- JVM JUnit4 only: `app/src/test/java/com/ivarna/fluxlinux/{core/*,ui/*}`, with `unitTests.isReturnDefaultValues = true`. There is no Robolectric, mockk or instrumented tests.
- Screenshot tests use Compose Preview Screenshot Testing. `@PreviewTest` previews live in `app/src/screenshotTest/kotlin/com/ivarna/fluxlinux/ui/ScreenshotPreviews.kt` and are built from stateless leaf composables with fake data, because whole screens read device state. References are in `app/src/screenshotTestIvarnaDebug/reference/` (ivarna flavor only). Write them with `:app:updateIvarnaDebugScreenshotTest` and diff with `:app:validateIvarnaDebugScreenshotTest`.
- Fakes: `core/terminal/FakeContext.kt` and `core/utils/FakePrefsContext.kt` (`InMemoryPrefs`). Production test seams such as `RootShell.seedSuInvocationForTest` exist.
- **Contract tests** read production sources and scripts as text via a `repoFile(rel)` helper and assert patterns. Examples: `OnboardingConsentContractTest`, `KdeDesktopContractTest`, `LegacyTermux*ContractTest`, `AudioSettingsContractTest`. Renaming or restructuring code can break them without any behavior change.
- Builder and parser tests: `ProotCommandBuilderTest`, `ChrootCommandBuilderTest`, `LinuxCommandBuilderTest`, `PulseHostTest`, `HostBootstrapTest`, `RootfsDownloaderTest`.
- Run: `bash .claude/kit/gradle-check.sh :app:testIvarnaDebugUnitTest`. The baseline is 402 tests with 0 failures (PROGRESS.md).

## Style
- One top-level `object` or file per concern, PascalCase. Tests are named `method_condition` or `*ContractTest`.
- Comments are fairly dense and explain why, often tagged with an issue or plan id (`#37`, `proot-opt-01`). KDoc sits on most objects.
- Fully qualified names inline (`com.ivarna.fluxlinux.core...`) are common in `MainActivity` and screens.
- Large files are common: `PrerequisitesScreen.kt` 2.4k, `OnboardingFlowScreen.kt` 1.8k, `MainActivity.kt` 1.2k, `HomeScreen.kt` 1.2k lines.
- No ktlint, detekt or `.editorconfig`. Android lint uses `abortOnError = false`.

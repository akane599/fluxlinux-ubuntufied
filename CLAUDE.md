<!-- android-kit -->
# CLAUDE.md — FluxLinux (ubuntufied fork)

Runs full Linux desktops (12 distros, PRoot or chroot, XFCE4 over Termux-X11, PulseAudio) on Android, for users who want a desktop Linux on their phone/tablet without root.
Android, developed on Ubuntu from the CLI. **If the STACK block still has `<placeholders>`, run `/bootstrap` first.**

<!-- STACK:BEGIN (filled by /bootstrap) -->
- Origin: fork of abhay-byte/fluxlinux (origin = akane599/fluxlinux-ubuntufied) @ 7f6e61d · Integration branch: main
- Language: mixed — 162 Kotlin / 45 Java (:app is all Kotlin; Java only in :termux-x11, :terminal-emulator, :stub) + C/CMake (:termux-x11 X server) · UI: Compose (+7 XML layouts) · screenshot tests: compose-preview (ivarna flavor)
- JDK target 17 · Gradle 8.14 · AGP 8.7.3 · Kotlin 2.0.20 · compileSdk 36 · targetSdk 36 · minSdk 26
- Modules: :app :termux-x11 :terminal-emulator :stub · Architecture: single-activity Compose, no DI framework · DI/DB/Net: none / none / okhttp
- Flavors (dimension `store`): `ivarna` = com.ivarna.fluxlinux (F-Droid/GitHub, arm64-v8a only) · `zenithblue` = com.zenithblue.fluxlinux (Play) · Launcher: .MainActivity
- Baseline: build ok (ivarnaDebug) · 402 unit tests, 0 failures (PROGRESS.md)
<!-- STACK:END -->

## How work flows here
- **The main session orchestrates; it doesn't implement.** Work becomes Sidequest tickets, executors work in isolated worktrees on their returned native Claude route, and the main session integrates verified results. Bounded inline-safe maintenance follows Sidequest's current rules.
- **Routing** (Sidequest profile `android-kit-claude-max`): Opus/high orchestrates, diagnoses bugs, investigates spikes, handles hard coding and reviews plans. Opus/high implements code/UI, designs tests and reviews code; validate high-impact or disputed findings with an explicit Opus/high review override. Sonnet/medium handles bounded research, mapping and mechanical edits. Haiku/low runs exact checks. Visual judgement uses Opus/medium, high for complex flows. Escalation uses Opus/xhigh with a fresh hypothesis, then Fable/high only as a last resort. Use native Claude routes and inspect effective board/ticket overrides.
- **Big or multi-step changes:** plan mode → pin the reviewed plan as a Sidequest story → dispatch independent work within routing and ownership limits.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** use `/bug-hunt` for existing-code investigations while Sidequest is active or within a story. Required ticket checks and Sidequest review gates remain. For a separate full repository audit, enter review mode with Sidequest off, then use `/full-repo-audit` followed by `/audit-remediate` for requested fixes; they coordinate native workers independently.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/live-rules/rules/` and are injected into every session and executor, so they're not repeated here. **Project map:** `.claude/.codebase-info/` (codebase-mapper).
- **Board:** "show the Sidequest board"; side issues mentioned mid-task get filed, not worked.
- **Plugin health / updates:** `/kit-doctor`, `/kit-refresh --update-toolshed` for project-scoped Toolshed updates. Never run the broad updater or enable Model Gateway for this project.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.

## Commands
- There is no plain `Debug` variant: always name a flavor. Build: `bash .claude/kit/gradle-check.sh :app:assembleIvarnaDebug` · Unit: `:app:testIvarnaDebugUnitTest` · Lint: `:app:lintIvarnaDebug`
- Screenshots: `:app:updateIvarnaDebugScreenshotTest` (write refs to `app/src/screenshotTestIvarnaDebug/reference`) · `:app:validateIvarnaDebugScreenshotTest` (diff). Previews live in `app/src/screenshotTest/` (`@PreviewTest`, fake state only); plugin `com.android.compose.screenshot` 0.0.1-alpha10 (alpha11+ need Kotlin ≥2.2)
- Device: `./gradlew :app:installIvarnaDebug -q && adb shell am start -n com.ivarna.fluxlinux/.MainActivity` · logs: `adb logcat -d --pid=$(adb shell pidof -s com.ivarna.fluxlinux) | tail -80`
- Emulator: `emulator -avd pixel-api35 -no-window -no-audio &` then `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'` (ivarna ships arm64-v8a host binaries only; x86_64 emulators run them via native bridge)
- No-KVM host (current VM, temporary): instead of the emulator, use the `redroid` Docker container (Android 15 x86_64, adb `emulator-5554` / `127.0.0.1:5555`, drive it through mobile-mcp (`.mcp.json`)). Its image sets `ro.dalvik.vm.native.bridge=libnb.so` but ships no libnb or arm64 linker, so the arm64 host binaries can't run there: host setup fails (`libbash.so: syntax error`) and the PRoot/desktop runtime can only be verified on a real arm64 device. Kotlin UI, the DocumentsProvider and `~/storage` links can be checked with a staged fake rootfs. If it's stopped, run `docker start redroid` then `adb connect 127.0.0.1:5555`
- Fresh checkout prerequisites (the build fails without them):
  - termux-x11 C sources: `git submodule update --init --depth 1 -- $(git config -f .gitmodules --get-regexp 'submodule\.termux-x11.*\.path' | awk '{print $2}')`. Skip `native/termux-packages`, which is huge and only needed to rebuild debs.
  - Host bootstrap (gitignored, ~124 MB, built from the tracked `native/output/<appId>/*.deb`): `python3 scripts/assemble_bootstrap.py --package-name <appId> --mode full && bash scripts/verify_bootstrap.sh <appId>`. `:app:packageHostAssets<Flavor>` fails closed without it.
- Release: follow `.agent/workflows/fdroid-update.md` (commit-identity rule in live-rules). F-Droid metadata: `com.ivarna.fluxlinux.yml` (UpdateCheckMode: Tags).

## Parallel execution

Dispatch all ready, independent work concurrently as soon as its inputs are available. This applies to implementation, audits, reviews, research, tests, tool calls and remediation. Do not impose a kit-specific agent count, RAM-based subagent cap, fixed wave size or wait-for-all barrier. Refill available native execution capacity immediately when a worker finishes. Preserve actual runtime permissions/capacity; disclose unavailable parallelism rather than bypassing it. Serialize only real dependencies or shared-resource collisions (same files, Git index, board mutation, report writer, device, build outputs). Use distinct owned files/worktrees to remove avoidable collisions. Gradle verification alone queues for the RAM-derived machine-wide command slots; waiting verification never blocks independent work or reserves a slot for a ticket's lifetime. CPU determines workers per Gradle command, not agent dispatch capacity. Do not spawn redundant work just to fill slots. Cloud model inference does not justify a local RAM-based agent cap; local commands still consume resources.

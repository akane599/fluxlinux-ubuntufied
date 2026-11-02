# CLAUDE.md — FluxLinux (ubuntufied fork)

Runs an Ubuntu 26.04 XFCE desktop (PRoot, no root; chroot with root) over Termux-X11 with PulseAudio on Android. This fork is Ubuntu-first: setup installs Ubuntu and opening the app starts its desktop; other distros' code and existing installs remain but aren't in the main UI.
Android, developed on Ubuntu from the CLI.

- Origin: fork of abhay-byte/fluxlinux (origin = akane599/fluxlinux-ubuntufied) @ 7f6e61d · Integration branch: main
- Language: mixed — Kotlin (:app is all Kotlin; Java only in :termux-x11, :terminal-emulator, :stub) + C/CMake (:termux-x11 X server) · UI: Compose (+7 XML layouts) · screenshot tests: compose-preview (ivarna flavor)
- JDK target 17 · Gradle 8.14 · AGP 8.7.3 · Kotlin 2.0.20 · compileSdk 36 · targetSdk 36 · minSdk 26
- Modules: :app :termux-x11 :terminal-emulator :stub · Architecture: single-activity Compose, no DI framework · DI/DB/Net: none / none / okhttp
- Flavors (dimension `store`): `ivarna` = com.ivarna.fluxlinux (F-Droid/GitHub, arm64-v8a only) · `zenithblue` = com.zenithblue.fluxlinux (Play) · Launcher: .MainActivity
- Baseline: build ok (ivarnaDebug) · 727 unit tests, 0 failures (v2.1.4, 2026-10-20)

## How work flows here
- For ongoing multi-session work, read PROGRESS.md at the repo root before planning, and update it at milestones. Its decision log is append-only (one line each).
- Big or multi-step changes: plan first (plan mode for anything architectural), split the work by file ownership, run independent parts as parallel subagents, then integrate, review and verify in the main session.
- Reviews: an independent review (a fresh subagent given the diff, not your conclusions) for meaningful behaviour changes before merging; validate each finding by reading the code path before fixing. Checklist: `.claude/references/android-bug-checklist.md`.
- Bug fixes start from a test that reproduces the bug where feasible.
- Conventions live in `.claude/rules/` (some load only for matching paths). Project map: `.claude/.codebase-info/`; refresh it when flows or file locations change.
- Skills here: `compose-design` (UI work), `full-repo-audit` then `audit-remediate` (whole-repo audit and fixes).
- Personal preferences go to auto memory ("remember that …"); lasting repo facts go here or in `.claude/rules/`.

## Commands
- There is no plain `Debug` variant: always name a flavor. Build: `bash .claude/scripts/gradle-check.sh :app:assembleIvarnaDebug` · Unit: `:app:testIvarnaDebugUnitTest` · Lint: `:app:lintIvarnaDebug`
- Screenshots: `:app:updateIvarnaDebugScreenshotTest` (write refs to `app/src/screenshotTestIvarnaDebug/reference`) · `:app:validateIvarnaDebugScreenshotTest` (diff). Previews live in `app/src/screenshotTest/` (`@PreviewTest`, fake state only); plugin `com.android.compose.screenshot` 0.0.1-alpha10 (alpha11+ need Kotlin ≥2.2)
- Shell tests: `for f in app/src/test/shell/*.sh; do sh "$f" app/src/main/assets; done` (each needs the assets dir argument).
- Device: `./gradlew :app:installIvarnaDebug -q && adb shell am start -n com.ivarna.fluxlinux/.MainActivity` · logs: `adb logcat -d --pid=$(adb shell pidof -s com.ivarna.fluxlinux) | tail -80`
- Emulator: `emulator -avd pixel-api35 -no-window -no-audio &` then `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'` (ivarna ships arm64-v8a host binaries only; x86_64 emulators run them via native bridge)
- No-KVM host (current VM, temporary): instead of the emulator, use the `redroid` Docker container (Android 15 x86_64, adb `emulator-5554` / `127.0.0.1:5555`, drive it through mobile-mcp (`.mcp.json`)). Its image sets `ro.dalvik.vm.native.bridge=libnb.so` but ships no libnb or arm64 linker, so the arm64 host binaries can't run there: host setup fails (`libbash.so: syntax error`) and the PRoot/desktop runtime can only be verified on a real arm64 device. Kotlin UI, the DocumentsProvider and `~/storage` links can be checked with a staged fake rootfs. If it's stopped, run `docker start redroid` then `adb connect 127.0.0.1:5555`
- Real device: an arm64 phone (SM-S938B, Android 17, rooted with KernelSU) is sometimes attached, as adb serial `emulator-5554` or over the network (`100.70.96.59:5555`). Check `adb devices -l` for the model before assuming redroid. The user often runs Claude from Termux on that phone, so don't drive its UI while Termux has focus; ask them to tap through and monitor via adb. Release builds aren't debuggable, so diagnose with the debug APK and `run-as`.
- Inspect the guest rootfs read-only from the host: push a script to `/data/local/tmp` and run `adb shell 'cat /data/local/tmp/x.sh | run-as com.ivarna.fluxlinux sh'` with relative paths (Ubuntu rootfs: `files/usr/var/lib/proot-distro/containers/ubuntu/rootfs`). Inline `$PWD`/`$VAR` would be expanded on the wrong side.
  The Ubuntu chroot rootfs is `/data/local/tmp/chrootUbuntu` (root-owned, `run-as` can't read it). The Ubuntu guest user is `akane` in both runtimes.
- The Bash tool runs zsh: unquoted `$VAR` isn't word-split and bare `=====` words fail. Wrap multi-step pipelines in `bash -c '…'`, or use scratchpad bash scripts.
- Fresh checkout prerequisites (the build fails without them):
  - termux-x11 C sources: `git submodule update --init --depth 1 -- $(git config -f .gitmodules --get-regexp 'submodule\.termux-x11.*\.path' | awk '{print $2}')`. Skip `native/termux-packages`, which is huge and only needed to rebuild debs.
  - Host bootstrap (gitignored, ~124 MB, built from the tracked `native/output/<appId>/*.deb`): `python3 scripts/assemble_bootstrap.py --package-name <appId> --mode full && bash scripts/verify_bootstrap.sh <appId>`. `:app:packageHostAssets<Flavor>` fails closed without it.
  - Repo-local git identity, or commits from worktrees fail: `git config user.name akane && git config user.email hackistek@gmail.com`
- Worktrees don't get the gitignored `bootstrap.tar` (`app/src/ivarna/assets/`) or the termux-x11 sources: copy the first from the main checkout and init the second as below, or builds fail. Each worktree build costs ~1–2 GB of disk.
- Manual worktrees: initialise termux-x11 submodules with per-command `git -c submodule.<name>.url=<main>/.git/modules/<name>`, never `git config submodule.*.url`, which rewrites the shared `.git/config`. Remove worktrees with `git worktree remove --force --force` once their branch is merged (git refuses worktrees with submodules). Their dirty submodule sources are the build's own patches, same as in the main checkout.
- Release: follow `.claude/rules/release.md` (read it before starting). F-Droid metadata: `com.ivarna.fluxlinux.yml` (UpdateCheckMode: Tags).

## Parallel execution
- Run independent work (implementation, research, reviews, checks) concurrently; serialize only real dependencies and shared resources (same files, the git index, the device, build outputs).
- Give each parallel agent explicit file ownership. Agents sharing one checkout (no worktree) must not run the full unit suite at the same time, because they collide on `app/build/test-results`. The main session runs the final verification alone and commits explicit paths, never `git commit -a`.
- Gradle limits are in `.claude/rules/android-build.md` (2 commands, `--max-workers=4`).

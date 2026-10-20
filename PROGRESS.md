# PROGRESS.md — FluxLinux (ubuntufied fork)

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-19 @ 7f6e61d: `:app:assembleIvarnaDebug` ok, 402 unit tests (`:app:testIvarnaDebugUnitTest`), 2 failing (pre-existing, not ours):
  - `ui.OnboardingConsentContractTest.optionsPage_requiresDownloadConsentBeforeInstall`: AssertionError (cause not yet investigated)
  - `core.terminal.GuestRootfsShellTest.alpine_absolute_sh_symlink_counts_as_installed`: AssertionError (cause not yet investigated)
- 2026-10-20 @ bffeb9e: 722 unit tests, 0 failing; lint 0 errors; screenshots valid (audit remediation gate).
- 2026-10-19 @ b4cf9d6: 402 unit tests, 0 failing. Both baseline failures are fixed (SQ-1 bc97fe1, SQ-2 f1b5efd). Screenshot tests: 3 previews pass `:app:validateIvarnaDebugScreenshotTest` (SQ-3).

## Decision log (never delete; one line each)
- 2026-10-19: Adopted android-kit at 7f6e61d (fork mode, integration branch main, kit files committed).
- 2026-10-19: Prior `.agent/` docs reconciled. The release commit-identity rule and push approval are now live-rules. `.agent/rules/project.md` is stale (it describes FinalBenchmark2) and is ignored. The generic workflows are superseded by Sidequest.
- 2026-10-19: Compose Preview Screenshot Testing pinned at 0.0.1-alpha10 (alpha11+ need Kotlin ≥2.2; the project is on 2.0.20). Previews use fake data, and only the ivarna flavor has references.
- 2026-10-19: v2.1.0 makes the app Ubuntu PRoot-first (story US-1). Ubuntu-only onboarding; Home is an Ubuntu dashboard (Distros tab removed, DistroScreen deleted); cold launch auto-starts the XFCE desktop after a 1.5 s cancellable countdown, with a Settings switch and a crash-loop guard (UbuntuLaunchPreferences). Other installed distros stay on disk, untouched.
- 2026-10-19: Recursive file ops must never follow symlinks: use `core/system/SafeFiles` (NOFOLLOW). `SymlinkSafetyContractTest` guards against `deleteRecursively`/`copyRecursively`/`walkTopDown`. Guest homes hold `~/storage/*` links to shared storage, so a following walk could wipe the user's files.
- 2026-10-19: Guest `~/storage` links (shared, downloads, documents, dcim, pictures, music, movies) are created by `GuestStorageLinks` in `/home/flux` and `/root`; never deleted or replaced. ivarna uses All files access; API ≤29 uses legacy READ/WRITE_EXTERNAL_STORAGE; zenithblue gains no MANAGE_EXTERNAL_STORAGE.
- 2026-10-19: `UbuntuDocumentsProvider` (`${applicationId}.documents`) exposes "Ubuntu" (home) and "Ubuntu filesystem" (/) SAF roots; guest-style link resolution confined to the rootfs + shared storage. Docs reached through a symlinked directory are browse/create only (no delete/move/rename, no tree grant), so client-side recursive moves can't empty shared storage.
- 2026-10-19: Release APK shrunk 177→65 MB: `bootstrap.tar` deflated in the APK, native libs stripped (NDK 29), English-only resources. Releases are signed with the fork's own key in `~/.config/fluxlinux-ubuntufied/signing/` (repo-root `keystore.properties` copy, gitignored); never regenerate it.
- 2026-10-20: v2.1.1: termux-x11 loads `libXlorie.so` from the read-only `dirname(base.apk)/lib/arm64` (nativeLibraryDir, via `TERMUX_X11_LIB_PATH`) over the script-extracted `/data/data/<pkg>/lib` copy, because Android 17 refuses `System.load` of a writable file. Keep `useLegacyPackaging = true` (extractNativeLibs): without it that read-only copy doesn't exist.
- 2026-10-20: v2.1.2: terminal opens Ubuntu directly (TerminalToolSelector removed; extras via long-press "+") over a distro picker, because only Ubuntu is installable in this fork. Ubuntu zsh plugin list is built at start-up from what's installed (ZshPluginSet.kt is the source of truth, script copy test-enforced), so partial network installs never print errors.
- 2026-10-20: v2.1.3: audit remediation (AUDIT_REPORT.md §6; 57 fixed, M14/L8 partial, L29 blocked on a toolchain decision). Chroot root helpers live in root-only /data/adb/fluxlinux/<pkg> and are SHA-256-checked before exec, never /data/local/tmp. PulseAudio uses a private unix socket, not 127.0.0.1:4713. Chroot mounts are guarded by a shared mkdir lock that is identical across 7 scripts (test-enforced).
- 2026-10-20: v2.1.4: stock Android tar can't run xz, so XFCE `.tar.xz` assets are unpacked by the guest. ProotXfceAssetInstaller must never delete the guest copies in `$PREFIX/tmp/flux_xfce_assets`: doing so in v2.1.3 sent fresh installs into a many-minute `papirus-icon-theme` apt unpack. PRoot guests get `FLUX_PROOT=1` and skip that package. Releases also ship a debug APK (live-rule).
- 2026-10-19: redroid (no-KVM host) can't execute the arm64 host binaries (no native bridge libs), so on-device QA there covers UI, provider and links with a staged fake rootfs only.

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Sidequest bug hunt | — | — | `/bug-hunt` |
| Repository audit | 2026-10-20 | 4 high, 23 medium, 33 low (AUDIT_REPORT.md) | `/full-repo-audit` |
| Audit fixes | 2026-10-20 | 57 fixed, M14/L8 partial, L29 blocked; device-unverified | `/audit-remediate` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | 2026-10-20 | 0 errors | `./gradlew :app:lintIvarnaDebug -q` |
| Device QA | 2026-10-19 | redroid: upgrade preserved staged files; SAF roots, ~/storage links, Home, auto-start guard, Settings switch, onboarding OK; runtime not testable | Mobile MCP | Mobile MCP (when configured) or android-emulator-qa fallback skill |

## Environment notes
- A fresh checkout needs the termux-x11 submodules and an assembled host bootstrap before any build (see CLAUDE.md Commands). Both were done on this machine on 2026-10-19.
- AVDs available: pixel-api35, pixel, batstats-api36, batstats16k, screenoff-api37, amber-api23.

# PROGRESS.md — FluxLinux (ubuntufied fork)

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-19 @ 7f6e61d: `:app:assembleIvarnaDebug` ok, 402 unit tests (`:app:testIvarnaDebugUnitTest`), 2 failing (pre-existing, not ours):
  - `ui.OnboardingConsentContractTest.optionsPage_requiresDownloadConsentBeforeInstall`: AssertionError (cause not yet investigated)
  - `core.terminal.GuestRootfsShellTest.alpine_absolute_sh_symlink_counts_as_installed`: AssertionError (cause not yet investigated)

## Decision log (never delete; one line each)
- 2026-10-19: Adopted android-kit at 7f6e61d (fork mode, integration branch main, kit files committed).
- 2026-10-19: Prior `.agent/` docs reconciled. The release commit-identity rule and push approval are now live-rules. `.agent/rules/project.md` is stale (it describes FinalBenchmark2) and is ignored. The generic workflows are superseded by Sidequest.

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Sidequest bug hunt | — | — | `/bug-hunt` |
| Repository audit | — | — | `/full-repo-audit` |
| Audit fixes | — | — | `/audit-remediate` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintIvarnaDebug -q` |
| Device QA | — | — | Mobile MCP (when configured) or android-emulator-qa fallback skill |

## Environment notes
- A fresh checkout needs the termux-x11 submodules and an assembled host bootstrap before any build (see CLAUDE.md Commands). Both were done on this machine on 2026-10-19.
- AVDs available: pixel-api35, pixel, batstats-api36, batstats16k, screenoff-api37, amber-api23.

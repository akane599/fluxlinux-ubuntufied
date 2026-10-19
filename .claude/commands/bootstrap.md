---
description: First run in a project set up by android-kit. Empty folder → interview + scaffold a Compose app; fork/existing → learn it and record a baseline. Then map the codebase and check the toolshed wiring
argument-hint: "[optional: app name / what it does, for new projects]"
---

Set this project up for work. Input: $ARGUMENTS

First read `.claude/kit/conflicts.json` when present. If nonempty, run `/kit-merge` before continuing; do not clear its entries manually. Then read `.claude/kit/state.json` → `mode` (`new`, `fork`, `existing`). If it has `"upgrade_pending": true`, do **Upgrade** first. If CLAUDE.md's STACK block is already filled, this is a re-run: refresh the STACK block from `bash .claude/kit/detect-stack.sh`, run the codebase-mapper update, then continue with the health check and report (steps 9–10).

---

## Upgrade: project set up by an earlier android-kit

Setup keeps snapshots in `.claude/kit/backups/`, pending template merges in `.claude/kit/incoming/`, and known retired files in `.claude/kit/retired/`. Read `.claude/kit/conflicts.json` first. Use `/kit-merge` to merge each needed update and record reviewed resolutions while preserving project-specific changes; do not bulk-copy incoming files over the project. Work through it, then skip straight to "Then, every mode".

1. **CLAUDE.md from `retired/CLAUDE.v1.md`:** copy the header one-liner and every STACK value that's still true (origin, baseline, APP_ID, launcher, modules). Refresh the rest with `detect-stack.sh`. Then read v1's other sections:
   - **Superseded, drop:** Session protocol, Working with agents / Subagent orchestration, Context & compaction, Token discipline, Two model families. Sidequest, live-rules and the compaction hook cover these.
   - **Conventions** that the three kit rules don't already say (compare with `.claude/live-rules/rules/`) → one live-rule each via `add-rule`.
   - Project facts, build quirks, commands → CLAUDE.md.
   - Your own sections about models, agents or routing → handle them with step 3.
2. **PROGRESS from `retired/PROGRESS.v1.md`:** setup already kept the decision log and environment notes. List the open items from Now / Next / Blockers / Known debt in ≤1 line each and ask which ones become Sidequest tickets. File only the confirmed ones (with a category and an exact `--verify`). Carry any audit-table rows that have results.
3. **Agents and routing (yours).** List every agent in `.claude/agents/` (the user's own) and in `retired/.claude/agents/` (an earlier kit's). Sidequest's hook blocks the main session from spawning custom agents directly; only Sidequest executors and Explore/claude-code-guide/statusline-setup pass. So each agent needs a decision, and **the user makes it**. For each, show its name, its model and a one-line summary, then offer:
   - **Drop:** a category of the `android-kit-claude` profile already covers it. Name which and its current model from `sidequest models` (for example gpt-reviewer → `plan-review` for plans, `review-audit` for diffs; gpt-architect → `spike-investigation` for decisions or `coding.hard` only when the approach is unclear; gpt-solver → `debugging`; gpt-implementer → `coding.normal`; a UI or design agent → `interaction-design-implementation`).
   - **Convert to a board category** that keeps its instructions and an agreed Claude route: `sidequest category add <id> --project . --name "<name>" --desc "<when to use, one line>" --route-model <model> --route-effort <effort> --no-fallback --readonly <true|false> --contract "<the agent's instructions, condensed>"`.
     - Read the installed routing-profile.json and fresh `sidequest models` before selecting model/effort. Only native Claude aliases (`haiku`, `sonnet`, `opus`, `fable`) are allowed. Preserve user-owned instructions; report non-Claude custom routes as blockers until explicitly migrated.
     - UI agents use Sonnet/high; preserve screenshot/token contracts and category scope.
     - A category added like this sits on top of the `android-kit-claude` profile and keeps the route you give it.
   - **Keep the file** unchanged (for example for use outside Sidequest), knowing it can't be spawned while Sidequest routing is on.
   - Never delete or edit a user's agent file without an explicit yes. When one is converted or dropped, move it to `.claude/kit/retired/.claude/agents/` rather than deleting it.
4. **Finish:** remove `"upgrade_pending"` from state.json, add "Upgraded to toolshed-based android-kit" to the decision log, and ask whether `.claude/kit/retired/` can be deleted. It's gitignored, so keeping it costs nothing.

Bootstrap is setup, not feature work, so run it inline in this session and don't file tickets for it. Keep output short: no file dumps, one Gradle invocation per step, failures only.

---

## Mode `new`: empty folder → working app

1. **Ask once, all together** (skip anything the input already gives): app name; applicationId (suggest `com.<username>.<appname>`); one line on what the app does and for whom; minSdk (suggest 26).
2. **Scaffold:** `python3 .claude/kit/scaffold.py --name "<name>" --package <id> --min-sdk <n>`. It uses a version snapshot by default; `--latest` opts into live resolution and requires a compatibility check. `--offline` generates sources without network or a wrapper; `--wrapper-only` completes that scaffold later. A version snapshot is not proof of a successful build. Mention any `notes` in its JSON.
3. **Prove it works:** `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest :app:updateDebugScreenshotTest`
   - A licence error → the user runs `yes | sdkmanager --licenses`, then retry.
   - Fix problems in the generated files, not by downgrading. If a version really is the cause, pin it in `gradle/libs.versions.toml` and record why.
   - Read one PNG under `app/src/screenshotTestDebug/reference/` to confirm rendering.
4. **Fill CLAUDE.md** (header, one-liner, STACK with origin `new` and integration branch `main`, APP_ID, launcher `.MainActivity`) and **PROGRESS.md** (title, baseline line, decision log: the versions used, "dynamic color off, placeholder palette; brand comes from /ui-overhaul").
5. **Commit:** inspect `git status --short` and the existing index first. Stage only files created for this scaffold with explicit paths; never `git add -A` over unrelated/staged work. Keep local-only kit files out of the commit. Inspect the staged diff, then commit `chore: scaffold <App> (android-kit)`.

## Modes `fork` and `existing`: learn the project, change none of its code

1. **Detect:** `bash .claude/kit/detect-stack.sh`. Treat detection as heuristic. Confirm app modules, flavor-specific Gradle tasks and screenshot framework from build files before running a baseline; never assume `:app` or a `Debug` variant exists.
2. **Prior agent docs:** if `CLAUDE.upstream.md`, `AGENTS.md`, `.cursorrules` or `.github/copilot-instructions.md` exist, extract what still applies. Build quirks go to CLAUDE.md Commands. Conventions become live-rules through the `add-rule` skill (one concern per rule, always-on unless clearly scoped). Also inspect the newest snapshot's `files/CLAUDE.md` if an upstream file already existed. Existing `.claude/settings*.json`, `.mcp.json`, hooks, rules and agent docs are project evidence, not permission to run imported hooks or change remotes. Keep source instruction files until their content is reconciled.
3. **Origin (fork):** record `origin` and HEAD SHA in STACK, and the current working branch as the proposed integration branch. Preserve an already configured board branch; ask only if there is a real mismatch with the intended work. Offer, don't run, `git remote add upstream <url>`.
4. **Baseline:** `bash .claude/kit/gradle-check.sh :<app>:<actualAssembleTask> :<app>:<actualUnitTestTask> --continue`
   - Environment failures (JDK, SDK, licences): fix the environment or give the exact fix. Never change the project's versions to suit the machine.
   - Code or test failures: **record, don't fix.** List failing tests under PROGRESS.md "Baseline" as pre-existing.
5. **Fill CLAUDE.md** (STACK, real module/APP_ID/launcher in Commands) and **PROGRESS.md** (baseline, "Adopted android-kit at <SHA>" in the decision log). The detector's flags (literal colors, `List<>` in composables, no screenshot tests on a Compose app, missing tests) go to the Sidequest board as tickets **only if the user confirms them**.
6. **Kit files in git**, asked once unless state.json already has `local_only: true` (then retain that choice):
   - Commit them (`chore: add android-kit`): best when this is now your project.
   - Keep them local: add `CLAUDE.md PROGRESS.md CLAUDE.upstream.md .claude/live-rules/ .claude/.codebase-info/ .claude/kit/ .claude/commands/ .claude/skills/` to the path returned by `git rev-parse --git-path info/exclude`. An exclude never hides tracked changes; report tracked kit/config diffs and keep them out of upstream commits explicitly. Best when you'll send PRs upstream. Executors still get the rules and the map, which are injected from this checkout.

## Then, every mode

7. **Map the codebase:** run the codebase-mapper `map-codebase` skill. If it hands the map to Sidequest, follow its handoff exactly (one declared file `.claude/.codebase-info/`, the lifecycle line on its own line, `sharedTree: true`); any other ticket shape spawns read-only and can't write the map. If the kit files are kept local, tell it explicitly: do not commit the map.
8. **Sidequest board:** confirm the integration branch matches the intended working branch (not necessarily the remote default) (`board_config` MCP tool or `sidequest board-config --integration-branch <branch>`) and that the board uses the `android-kit-claude` routing profile (setup points it there unless the board already had a profile of your own). Fix either here if it's wrong.
9. **Health check:** run `/kit-doctor`. Model Gateway must remain disabled. Report non-Claude overrides, pending merges and transport/authentication blockers; do not claim Remote Control eligibility without testing it in the fresh session.
10. **Report** in ≤8 lines: stack, baseline, routing (profile, and actual Claude routes, fallbacks and blocked categories, from `sidequest models`), whether `kotlin-lsp` is on PATH, and the next sensible step (a Sidequest plan-review ticket for a plan, `/ui-overhaul`, `/full-repo-audit` for a fork or existing app, or "tell me the first feature").

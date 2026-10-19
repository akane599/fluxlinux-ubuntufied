---
description: Hunt for bugs in existing code. Read-only Sonnet/high reviews by area with the Android bug checklist, then one fix ticket per bug you pick, each starting from a test that reproduces it
argument-hint: "[scope: path, module or feature | blank = the riskiest areas from the codebase map]"
---

Find bugs in existing code while Sidequest is active, including within an ongoing story. Scope: $ARGUMENTS


1. **Size the requested coverage.** Use the scope, or when it's blank, `.claude/.codebase-info/` to select the riskiest parts first (persistence and migrations, concurrency and background work, state restoration and navigation, anything touching money, auth or personal data). Use one reviewer for a bounded area; split only when the areas need distinct context. Dispatch all independent ready areas concurrently within actual runtime capacity; no fixed wave cap. Do not fragment one small feature into four tickets or give several reviewers the same source tree. For an explicitly broad/full audit, cover the whole requested scope without artificial wave barriers; do not silently truncate it. State selected and unreviewed areas. Use the map and path inventory to scope; don't perform a duplicate source audit in the main session.

2. **File one read-only `review-audit` ticket per area** (Sidequest rules apply):
   - title `Bug hunt: <area>`, `--readonly true`, no declared files;
   - description: the area's paths; the absolute path of `$CLAUDE_PROJECT_DIR/.claude/kit/android-bug-checklist.md`; "Hunt for real bugs in this area with that checklist and report in its format as your closing body. Run no Gradle task and edit nothing."
   - They don't run Gradle, so they can all go in one wave.

3. **Dispatch and wait.** Inspect the fresh effective routes first: report each reviewer's actual Claude route and any availability fallback. Use the exact returned Sidequest spawn; preserve its model field when present, and omit it when absent/null. Give each reviewer only raw source scope and the checklist. Do not hunt yourself or share findings between reviewers in the meantime.

4. **Triage once.** Merge the reports and drop duplicates before reading candidate evidence. For each finding, read the cited lines and necessary callers yourself; keep it only if the scenario holds. Reuse shared evidence across findings; do not repeat the full area hunt or dispatch an extra validator for a claim you can directly resolve. Show the user a numbered list (severity, one line, `file:line`) and let them pick what gets fixed. The rest goes on the board only if they say so.

5. **One fix ticket per picked bug.**
   - Category by what the fix touches: UI → `interaction-design-implementation` (or `ui.tweak`); clear cause → `coding.normal`; unclear cause → `debugging`.
   - Declare its files. Description: the finding, then "First add a test that reproduces this and show it failing in your submit report; then fix it until the test passes."
   - Verify: the module's unit test task. A bug that only shows on a device says so in the description, and gets checked with the Mobile MCP (when configured) or android-emulator-qa fallback skill after integration.
   - Mark a crash or data-loss fix `--high-stakes`, so it gets a review before integration.

6. **Record:** a "Bug hunt" row in PROGRESS.md's audit table (date, areas, found / fixed). If a bug reveals a convention worth keeping, add it as a live-rule with `add-rule`.

For adopted projects, discover the actual module, build variant and test framework first. The `:app`/`Debug`/Compose screenshot commands above are examples for the generated scaffold, not universal commands. Keep existing Paparazzi, Roborazzi, XML or device-based checks; report unavailable visual validation explicitly.

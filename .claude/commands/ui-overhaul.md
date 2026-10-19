---
description: Visual overhaul of a Compose app as a Sidequest story. You write the brief and tokens (approval gate), then theme ticket → screen wave → visual + code review → integrate
argument-hint: "[scope: \"all\" | screen/package names] [optional design direction]"
---

Overhaul the Compose UI. Scope and direction: $ARGUMENTS
Load the `compose-design` skill. You orchestrate: you decide the design direction; executors write the code.

## 1. Inventory and baseline (inline: operational, not implementation)
- A bounded `codebase-exploration` ticket on its Claude-only route. Return, don't dump: the screen-level composables with paths; the theme files and whether tokens are real or defaults; counts of `Color(0x…)` and raw `.dp`/`.sp` outside `ui/theme`; existing `@PreviewTest`s; whether `com.android.compose.screenshot` is applied. A larger or unfamiliar codebase gets a `codebase-exploration` ticket instead.
- Baseline: `./gradlew :app:updateDebugScreenshotTest --console=plain -q`, then copy the reference PNGs to `design/before/`. Without screenshot testing, the first ticket of the story adds it (compose-design §5).
- Read 3–4 baseline images and write a 5-line honest critique.

## 2. Brief and tokens: STOP for approval
Following compose-design §4 steps 1–3: palette hexes with roles, type pairing and scale, shapes, spacing, motion, the one memorable element, and a one-line intent per screen in scope. **Wait for the user's approval.** Nothing is filed before it.

## 3. Pin the story and file the whole backlog
- `story add` "UI overhaul <date>". The story contract carries the approved brief and tokens verbatim.
- **Theme ticket**, category `interaction-design-implementation`: files `ui/theme/*`. Verify: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain -q`.
- **One ticket per screen** (or feature package), same category, `depends-on` the theme ticket, with its exact files plus its own reference PNG paths. Description: that screen's intent line, "load compose-design and compose-stability", previews (light, dark, fontScale 1.5, widthDp 840). Verify: `./gradlew :app:updateDebugScreenshotTest :app:testDebugUnitTest --console=plain -q`, committing only its own reference PNGs. If a ticket needs a token that doesn't exist, it asks through the board; it never edits theme files.
- **Review tickets**, `depends-on` all screen tickets, same wave:
  - `visual-evaluation`: the after-screenshots and the brief attached; a designer's critique against compose-design §3 and the calibration list.
  - `review-audit`: the story's diff; Compose stability, Modifier order, accessibility, token discipline.
- Before Gradle verification, run `python3 .claude/kit/resource-policy.py` and use its current `gradle_command_limit` and `gradle_workers_per_command` (pass `--max-workers=N`). This supersedes fixed kit concurrency defaults. Count active Gradle commands machine-wide across Claude/Codex projects and worktrees, not tickets or idle daemons. Queue only verification when capacity is occupied; continue independent implementation/review/research within existing worker and ownership limits. Low memory headroom temporarily defers new heavy builds, never all ticket dispatch; recheck before launching. This is advisory coordination, not an enforced semaphore. Do not automatically edit Gradle JVM heaps, swap, OS settings, model routing or agent counts. Saved resource-policy.json is diagnostic only: always re-probe on the current machine.

## 4. Run the waves
Dispatch per the Sidequest skill: theme → screens → reviews. Integrate each wave once. Issues raised by both reviews come first. Each fix becomes a follow-up ticket (`ui.tweak` when mechanical, else `interaction-design-implementation`); no inline patching. UI and review tickets follow their named Claude-only categories and explicit fallbacks; logic changes it uncovers go to separate `coding.*` tickets.

## 5. Prove and record
- `rg -n "Color\(0x|[0-9]+\.(dp|sp)\b" <ui dir> --glob '!**/theme/**'` → near-empty; justify survivors.
- `rg -n "List<|Map<|Set<" <ui dir> --glob '*.kt' | rg -v "Immutable|Persistent"` in composable params and state → empty.
- `./gradlew :app:assembleDebug :app:validateDebugScreenshotTest --console=plain -q` on the integrated tree. Copy the refs to `design/after/`.
- PROGRESS.md: decision-log lines (tokens chosen, ideas rejected) and the audit-table UI row. Summary in ≤10 lines: what changed visually, screenshot paths, open tickets.

For adopted projects, discover the actual module, build variant and test framework first. The `:app`/`Debug`/Compose screenshot commands above are examples for the generated scaffold, not universal commands. Keep existing Paparazzi, Roborazzi, XML or device-based checks; report unavailable visual validation explicitly.

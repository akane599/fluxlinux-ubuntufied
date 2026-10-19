---
name: compose-design
description: Visual design guidance for native Jetpack Compose apps — use when building new screens, restyling or overhauling existing UI, defining a theme, or reviewing the look of a Compose screen. Covers Material 3 theming as a token system, typography, color, shape, spacing, motion, dark/dynamic color, adaptive layout, and a screenshot-based self-critique loop. Not for XML Views.
---

# Compose Design

Approach this as the design lead for this specific app, not as a Material template generator. The default Compose app — `MaterialTheme` untouched, purple-ish dynamic color, `Card` everywhere, `TopAppBar` + `FAB` + `LazyColumn` of identical rows — is the AI/tutorial tell. Every project gets deliberate choices grounded in what the app *is* and who uses it.

## 1. Ground the design in the subject
Before touching a theme file, state in 3 lines: what the app does, who uses it and in what situation (one hand on a bus? a desk? outdoors in sun?), and the single job of the screen being designed. If the brief doesn't say, propose these and confirm. Those answers drive palette, type, density, and touch-target size — a field-service app and a meditation app must not share a look.

## 2. Tokens first, screens second
Material 3 *is* a token system; use it as one instead of hardcoding values in composables.
- **Color:** define a `lightColorScheme`/`darkColorScheme` from 4–6 named brand hexes via a `Color.kt` object; decide explicitly whether dynamic color (`dynamicLightColorScheme`) is *on* (personal/utility apps) or *off* (branded products). Use `surfaceContainer*` tiers for elevation hierarchy instead of shadows. Never `Color(0xFF...)` inside a screen file.
- **Type:** one family, two at most (`FontFamily` from `res/font`). Build the full `Typography()` scale — `displayLarge` through `labelSmall` — with intentional weights and letter-spacing; don't leave Roboto defaults for a branded app. Headlines carry personality; body stays legible (≥16sp on phones, line-height ≈1.4–1.5×).
- **Shape:** `Shapes(extraSmall..extraLarge)` chosen once. One radius everywhere is the SaaS-card tell; radius should encode hierarchy (containers > controls > chips).
- **Spacing:** a `Spacing` object (4/8/12/16/24/32/48) exposed via `CompositionLocal` or `MaterialTheme` extension. All padding references it.
- **Motion:** pick one `MotionScheme`/duration-easing set; document when to use `animateContentSize`, `AnimatedVisibility`, `AnimatedContent`, shared-element transitions.
Write these as `ui/theme/{Color,Type,Shape,Spacing,Theme}.kt`. Every screen uses `MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`, `MaterialTheme.shapes.*`.

## 3. Compose-specific design principles
- **Hierarchy through surfaces, not borders.** Prefer tonal `surfaceContainerLow/High` layering over `Card` outlines and shadows; reserve elevation for things that actually float (FAB, sheets, menus).
- **Don't wrap everything in `Card`.** A list row is a row. Use `ListItem`, dividers, or spacing; cards only for discrete, actionable, glanceable units.
- **Touch targets ≥48dp**, with content padding not margin, so the whole row is tappable. Thumb-zone: primary action bottom-anchored on phones (`BottomAppBar`/`FAB`/bottom sheet), not top-right.
- **Text tells:** no ALL-CAPS labels (M3 already dropped them), no single-word colored highlight in a headline, no eyebrow labels above every section, no "→" in button text. Sentence case. CTAs name the outcome ("Save changes", not "Submit").
- **Empty/error/loading states are designed screens**, not a centered `CircularProgressIndicator`. Empty state = an invitation to act; error = what happened + how to fix, in the app's voice.
- **Motion:** one orchestrated moment per screen at most (enter transition, a reveal). Motion that answers a tap (expand, confirm, shared element) is welcome; scattered fades on every item read as generated.
- **Adaptive:** use `WindowSizeClass`; phones get single-pane, medium/expanded get list-detail (`ListDetailPaneScaffold`). Don't hard-code widths.
- **Dark theme is a first-class design**, not an inversion — check contrast (`onSurface` on `surface` ≥4.5:1), reduce saturation of accents, avoid pure black unless OLED-intent.
- **Icons:** one set (Material Symbols with one weight/fill setting, or a single custom set). Mixed icon families are the fastest way to look assembled.
- **Accessibility floor:** `contentDescription` on meaningful images, `semantics` merges on rows, `Modifier.clearAndSetSemantics` where needed, respect `LocalReducedMotion`/system font scale (test at 1.3× and 2×).

## 4. Process: brief → tokens → critique plan → build → screenshot → critique
1. **Brief** (5 lines): subject, audience, situation, the one memorable element, what stays quiet.
2. **Token plan:** palette hexes with roles, type pairing and scale, shape radii, spacing, motion durations. Put it in the Sidequest story contract (see `/ui-overhaul`) so it survives compaction and reaches every executor.
3. **Review the plan against the tells** in §3 and the calibration list below. If any part is what you'd produce for *any* app of this kind, change it and say why.
4. **Build theme files first**, then screens: one ticket per screen with a strict file scope, `depends-on` the theme ticket. Screens consume tokens only.
5. **Screenshot** (see §5). Read the PNGs. Critique in the mirror: remove one accessory. Check dark, large-font, and a small-width device.
6. Record what was tried and rejected in `PROGRESS.md` decision log — future passes shouldn't rediscover it.

Calibration — current generated-Compose defaults to avoid unless the brief asks: dynamic-color purple on `surface` with default Roboto; `Card` grid with `elevation = 4.dp` and 12dp radius on everything; a gradient hero `Box` with a large number and small label; `Scaffold` + centered `TopAppBar` + `FAB` for every screen regardless of content; near-black `#121212` dark theme with one neon accent; hairline `Divider` after every item.

## 5. Seeing the result (Ubuntu, no Android Studio)
Pick the first available:
- **Compose Preview Screenshot Testing** (AGP ≥ 8.5, JVM, no emulator): plugin `com.android.compose.screenshot`; previews in `src/screenshotTest/` (or existing `@Preview`s). `./gradlew :app:updateIvarnaDebugScreenshotTest -q` writes PNGs under `app/src/screenshotTestIvarnaDebug/reference/`; `validateIvarnaDebugScreenshotTest` diffs against them — a free visual regression suite for the overhaul. Add previews per screen for: light, dark (`uiMode = UI_MODE_NIGHT_YES`), `fontScale = 1.5f`, and `widthDp = 360` / `840`.
- **Emulator:** boot headless, `:app:installIvarnaDebug`, then the Mobile MCP screenshot tool when configured, otherwise the `android-emulator-qa` fallback screenshot step (`adb exec-out screencap -p > shot.png`) per screen. Slower, but exercises real navigation/insets.
Read the PNGs with the Read tool and critique them concretely (spacing rhythm, hierarchy, contrast, alignment to the grid, what dominates the eye first). A screenshot is worth more than another paragraph of reasoning.

## 6. Deliverables of a design pass
- `ui/theme/*` token files; no literal colors/dp/sp in screen files (grep to prove it: `rg -n "Color\(0x|\b[0-9]+\.dp\b" ui/ --glob '!theme/*'` — only spacing-token exceptions allowed).
- Previews per screen (light/dark/large-font/tablet).
- Before/after screenshots referenced in the PR description.
- `PROGRESS.md`: decision-log lines for the tokens chosen and ideas rejected, plus the audit-table UI row. The brief and token plan live in the story.

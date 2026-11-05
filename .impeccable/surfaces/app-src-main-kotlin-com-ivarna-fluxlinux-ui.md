---
version: 1
slug: "app-src-main-kotlin-com-ivarna-fluxlinux-ui"
primary_target: "app/src/main/kotlin/com/ivarna/fluxlinux/ui"
related_targets: []
---

# AkaTerminal app shell (all Compose screens)

Scope: every Compose surface of the app: terminal (extra keys, session drawer), onboarding/setup, Ubuntu page, Settings family, dialogs, install progress. Visitor mode: Operate.

Audience/job: the maintainer and public users running Ubuntu on a phone, one-handed, often at night, for long shell/agent sessions. Task: get to a working shell instantly; manage sessions, wakelock, desktop and setup without leaving the terminal for long.

Constraints: Material 3 structure and native controls (drawer, switches, dialogs, snackbars), system Back, 48dp targets, font scale 1.3, light + dark schemes. Launcher icon unchanged. The terminal renderer (vendored TerminalView) is not restyled beyond its background and default colours.

Memorable moment: the AkaTerminal wordmark printing in two ink passes on first launch, and the selected session sitting as an overprint whose plates snap into register on touch.

## Direction contract

THESIS: AkaTerminal is printed, not rendered: a two-ink risograph zine for a terminal. Refuses the category default of a grey Material screen with one accent (and the near-black + neon "hacker" look).

OWN-WORLD: Riso spot inks on dark stock. Ground ink-black #0F1326 (blue-black stock); shell fields in Federal Blue ink (#1B2A5C field, #3255A4 ink); Bright Red #F15060 (aka) carries primary actions; overprint plum (red × blue multiply) marks selection; Yellow #FFE800 for held/attention; Green #00A95C for running; Fluorescent Pink for errors, always with a mark. Text in cool bone #E9ECF2. Federal set as text (labels, links, progress) takes Federal Tint #A9BCF2, because #3255A4 reads under 4.5:1 on stock; the one hairline is Federal Line #5D78C4. Primary plates are Red over a Federal second plate, the wordmark's pair. Light scheme: cool white stock #EFF1F5 with the same inks. Cut-paper corners (2–10dp). Riso grain only on large printed fields. One family, Archivo, ranked by width (expanded display, normal body) and weight; JetBrains Mono only for code, paths and keys. Sharp Material icons. Raised from the declined emission-line rail: state is carried by mark form (filled, ring, struck, half) as well as hue. Raised from the declined gate board: a changed state (install done/failed) holds its ink until the user has seen it. Raised from the declined design annual: one hairline weight, one type family, strict ramp. Raised from the declined star atlas: at night the chrome never outshines the terminal text.

STORY: The visitor opens the app and is in a shell; the blue shell bar under it and the drawer read as one printed sheet. They understand which session is live, whether the wakelock is held, whether Ubuntu/desktop is installed or running, and act with one tap.

FIRST VIEWPORT: Terminal full bleed on ink-black; Federal Blue extra-keys field at the bottom (☰ left, Paste right, two rows of 7–8 keys, 48dp targets at phone width, latched modifiers overprinted). Drawer open: Federal Blue sheet, grain-printed AkaTerminal wordmark (Archivo Expanded ExtraBold) at top, session rows with state marks, the active row as the plum overprint with red/blue fringes, New session in red ink, Hold wakelock switch, then Ubuntu page / Files / Settings rows.

FORM: Risograph two-ink zine (candidate 7 of 7 grounded list; assigned by the roll). Signature move: misregistration that registers: selected/primary elements rest with their second plate offset 2dp; press or selection settles the plates into register with one damped overshoot (spring); reduced motion cuts instantly. seed:9812609be7a0d39d

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

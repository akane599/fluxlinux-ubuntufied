---
version: 1
slug: "app-src-main-kotlin-com-ivarna-fluxlinux-ui"
primary_target: "app/src/main/kotlin/com/ivarna/fluxlinux/ui"
related_targets: []
---

# AkaTerminal app shell (all Compose screens)

Scope: every Compose surface of the app: terminal (extra keys, session drawer), onboarding/setup, Ubuntu page, Settings family, dialogs, install progress. Visitor mode: Operate.

Audience/job: the maintainer and public users running Ubuntu on a phone, one-handed, often at night, for long shell/agent sessions. Task: get to a working shell instantly; manage sessions, wakelock, desktop and setup without leaving the terminal for long.

Constraints: Material 3 structure and native controls (drawer, switches, dialogs, snackbars), system Back, 48dp targets, font scale 1.3, light + dark schemes (the app forces dark). The launcher icon is redrawn for this direction (user's request, 2026-11-05). The terminal renderer (vendored TerminalView) is not restyled beyond its background and default colours.

Memorable moment: the horizon rising under the AkaTerminal wordmark on first launch, and under the current session each time the drawer opens.

## Direction contract

THESIS: AkaTerminal at dusk. 茜 akane, the madder red in the name, is the last light on the horizon over 藍 ai indigo night; the terminal is the night sky, the chrome is the horizon. Refuses the category default of a grey Material screen with one accent, the near-black + neon "hacker" look, and flat text-on-black controls (the user's complaint about the riso black build: buttons read as text on a flat ground).

OWN-WORLD: Japanese traditional colours, each token named for its dye. Ground 墨 sumi #05060B (black with an indigo cast; the user wants the app black); the terminal itself and the extra-keys bar pure black #000000. Chrome 褐色 kachi #0B0F1E (drawer sheet). Every control sits in a container: 紺 kon #131B36 at rest, 紺青 konjō #1B2852 pressed or selected, edged by 縹 hanada #2E4377 (rows, cards) or 藍 ai #4A66AA (controls, selected; ≥ 3:1 on sumi). 茜 akane #D2383F is the one accent: primary buttons (white text), latched modifier keys, the switch's on track, "Aka" in the wordmark; 深緋 kokiake #9E2A33 when pressed. 朱 shu #EE6A2E only as the horizon's brightest point. Text 白練 shironeri #EEF0F5, secondary 銀鼠 ginnezumi #A9B0C4, accent text 白藍 shiro-ai #A3B8EC. States with a mark form: 若竹 wakatake #68BE8D running, 山吹 yamabuki #F8B500 held, 躑躅 tsutsuji #E95295 failed. Dawn (light) scheme: cool paper #F2F3F7 with akane deepened to #B7282E. Soft corners (6dp keys and chips, 10dp buttons, 12dp rows, 16dp cards, 24dp sheets). One family, Archivo: semi-expanded SemiBold for display and titles (calm, never shouted), normal width for body and labels; JetBrains Mono for code, paths and keys. Sharp Material icons. Kept from the riso build: state carried by mark form (filled, ring, dashed, half, struck) as well as hue; a changed state holds its ink until seen; at night the chrome never outshines the terminal text.

STORY: The visitor opens the app and is in a shell on pure black; under it a row of indigo key caps. They open the drawer and the current session's horizon rises; every session, switch and destination is its own tile, so nothing reads as loose text. The Ubuntu page and Settings are stacks of tiles and cards; the one akane button on each is the next step.

FIRST VIEWPORT: Terminal full bleed on #000000; the extra-keys bar on the same black, ☰ left, Paste right, two rows of 7–8 kon key caps with hanada edges, 44dp tall with 3dp gaps (Termux density: the user keeps the terminal's height; the full-width utility columns and the gaps keep neighbours apart), labels in the mono face, latched modifiers filled akane. Drawer open: kachi sheet, the AkaTerminal wordmark over its horizon line, session tiles with state marks, the current session konjō with the ai edge and the horizon rising along its bottom, New session as the akane button, Extra keys / Hold wakelock switch tiles, then Ubuntu page / Files / Settings tiles.

FORM: Akane-iro · twilight (impeccable's pick in the first direction round, chosen by the user over the riso build on 2026-11-05). Signature move: the horizon, a 2dp line, akane at its ends and shu at its brightest point, with sunset light fading up from it; it grows out from the centre and its light lifts (no overshoot) under the one current thing: the wordmark on first launch, the current session as the drawer opens, the selected option or segment, the Ubuntu card once Ubuntu is ready, the welcome terminal window. Reduced motion shows it risen. seed:9812609be7a0d39d

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

---
name: AkaTerminal
description: Ubuntu on Android, terminal first, at dusk: akane light on the horizon over indigo night.
colors:
  sumi: "#05060B"
  terminal-black: "#000000"
  kachi: "#0B0F1E"
  kachi-2: "#0F1528"
  kon: "#131B36"
  konjo: "#1B2852"
  hanada: "#2E4377"
  ai: "#4A66AA"
  shiro-ai: "#A3B8EC"
  akane: "#D2383F"
  kokiake: "#9E2A33"
  shu: "#EE6A2E"
  akane-well: "#3A1218"
  on-akane: "#FFFFFF"
  wakatake: "#68BE8D"
  yamabuki: "#F8B500"
  tsutsuji: "#E95295"
  shironeri: "#EEF0F5"
  ginnezumi: "#A9B0C4"
  ink-text: "#0B0F1E"
  dawn: "#F2F3F7"
  akane-deep: "#B7282E"
  tsutsuji-deep: "#B4206A"
typography:
  display-large:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "44px"
    fontWeight: 650
    lineHeight: "48px"
    letterSpacing: "-1px"
  display-small:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "30px"
    fontWeight: 650
    lineHeight: "36px"
    letterSpacing: "-0.5px"
  headline-medium:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "23px"
    fontWeight: 650
    lineHeight: "29px"
    letterSpacing: "-0.3px"
  headline-small:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "20px"
    fontWeight: 650
    lineHeight: "26px"
    letterSpacing: "-0.2px"
  title-large:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "20px"
    fontWeight: 650
    lineHeight: "26px"
    letterSpacing: "-0.1px"
  title-medium:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "16px"
    fontWeight: 650
    lineHeight: "22px"
    letterSpacing: "0px"
  title-small:
    fontFamily: "Archivo, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0.1px"
  body-large:
    fontFamily: "Archivo, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
    letterSpacing: "0.1px"
  body-medium:
    fontFamily: "Archivo, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
    letterSpacing: "0.15px"
  body-small:
    fontFamily: "Archivo, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "17px"
    letterSpacing: "0.2px"
  label-large:
    fontFamily: "Archivo, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    letterSpacing: "0.1px"
  label-medium:
    fontFamily: "Archivo, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: "16px"
    letterSpacing: "0.3px"
  label-small:
    fontFamily: "Archivo, sans-serif"
    fontSize: "11px"
    fontWeight: 500
    lineHeight: "16px"
    letterSpacing: "0.4px"
  code:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "19px"
rounded:
  extra-small: "6px"
  small: "10px"
  medium: "12px"
  large: "16px"
  extra-large: "24px"
spacing:
  xxs: "2px"
  xs: "4px"
  tile-gap: "3px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
  touch: "48px"
components:
  button-primary:
    backgroundColor: "{colors.akane}"
    textColor: "{colors.on-akane}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    padding: "12px 24px"
    height: "48px"
  button-primary-pressed:
    backgroundColor: "{colors.kokiake}"
    textColor: "{colors.on-akane}"
  button-secondary:
    backgroundColor: "{colors.kon}"
    textColor: "{colors.shironeri}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    padding: "12px 24px"
    height: "48px"
  button-secondary-pressed:
    backgroundColor: "{colors.konjo}"
    textColor: "{colors.shironeri}"
  button-danger:
    backgroundColor: "{colors.tsutsuji}"
    textColor: "{colors.ink-text}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    height: "48px"
  list-row:
    backgroundColor: "{colors.kon}"
    textColor: "{colors.shironeri}"
    typography: "{typography.title-small}"
    rounded: "{rounded.medium}"
    padding: "12px 16px"
    height: "56px"
  list-row-pressed:
    backgroundColor: "{colors.konjo}"
  selection:
    backgroundColor: "{colors.konjo}"
    textColor: "{colors.shironeri}"
    rounded: "{rounded.medium}"
  card:
    backgroundColor: "{colors.kon}"
    textColor: "{colors.shironeri}"
    rounded: "{rounded.large}"
    padding: "16px"
  section-label:
    textColor: "{colors.shiro-ai}"
    typography: "{typography.label-large}"
    padding: "24px 16px 8px 20px"
  key-cap:
    backgroundColor: "{colors.kon}"
    textColor: "{colors.shironeri}"
    typography: "{typography.label-medium}"
    rounded: "{rounded.extra-small}"
  key-cap-pressed:
    backgroundColor: "{colors.konjo}"
  key-cap-latched:
    backgroundColor: "{colors.akane}"
    textColor: "{colors.on-akane}"
    rounded: "{rounded.extra-small}"
  switch-on:
    backgroundColor: "{colors.akane}"
    textColor: "{colors.on-akane}"
    rounded: "{rounded.small}"
    width: "52px"
    height: "32px"
  switch-off:
    backgroundColor: "{colors.kon}"
    textColor: "{colors.ai}"
    rounded: "{rounded.small}"
    width: "52px"
    height: "32px"
  drawer-sheet:
    backgroundColor: "{colors.kachi}"
    textColor: "{colors.shironeri}"
    rounded: "{rounded.extra-large}"
    width: "300px"
---

# Design System: AkaTerminal

## Overview

**Creative North Star: "Akane at Dusk"**

AkaTerminal is the last light of the day over indigo night. 茜 akane, the madder red in the name, is the one accent and the horizon; the terminal is the night sky; the chrome around it steps up through the traditional indigo dyes. Every colour is a named Japanese traditional colour (`Iro`), and the app is black: the terminal and the extra-keys bar sit on pure black, every other screen on 墨 sumi, a black with an indigo cast.

The density is a tool used one-handed at night for long sessions. Nothing reads as loose text on the ground: every row, key, session, switch and secondary action sits in its own container (a tile in 紺 kon, edged in 縹 hanada or 藍 ai), and the one akane fill per region is the next step. The signature is the horizon: a 2dp line along the bottom of the one current thing, akane at its ends and 朱 shu at its brightest point, with sunset light fading up from it. It grows out from the centre and lifts with no overshoot. Reduced motion shows it risen.

Material 3 is underneath (drawer, dialogs, radio buttons, progress, snackbars, system Back) restyled with the dyes, a soft corner scale and one type family. The app forces the dark (night) scheme; the light (dawn) scheme, cool paper with akane deepened, is kept in `Theme.kt` so a later switch stays cheap. Dynamic (wallpaper) colour is off.

**Key Characteristics:**
- Traditional colours only: sumi ground, indigo dyes for surfaces, akane the one accent, three state colours that travel with a mark.
- Every control in a container; one akane primary per region.
- The horizon under the one current thing, and nowhere else.
- One family, Archivo: a semi-expanded cut at wght 650 for display and titles, normal width for reading; JetBrains Mono for code and keys.
- Soft corners (6 to 24), one 1dp edge weight, no shadows.
- Sharp Material icons.

## Colors

Night indigo stepped by dye, one madder accent, and state colours that never appear without a mark form.

### Primary
- **茜 Akane** (`akane`): primary fills (AkaButton PRIMARY, New session, latched modifier keys, switch-on track), "Aka" in the wordmark, and the horizon line. Text on it is white (`on-akane`, 4.5:1 or better).
- **深緋 Kokiake** (`kokiake`): akane while pressed; inverse primary.
- **朱 Shu** (`shu`): only the brightest point of the horizon and its glow, and the sun in the launcher icon. Never a fill or text.
- **Akane Well** (`akane-well`): the dark primary container.

### Secondary
- **紺 Kon** (`kon`): the resting container. Rows, cards, key caps, secondary buttons, the off switch track (`AkaInks.tile`).
- **紺青 Konjō** (`konjo`): pressed and selected containers (`tilePressed`); the secondary container.
- **縹 Hanada** (`hanada`): a container's quiet edge (`tileEdge`), and outline-variant dividers.
- **藍 Ai** (`ai`): the control line (`controlLine`, 3:1 or better on sumi): selected tiles, secondary buttons, the off switch's edge and thumb, pressed keys.
- **白藍 Shiro-ai** (`shiro-ai`): indigo that reads as text. Section labels, links, progress, the working mark (Material `secondary`).

### Tertiary
- **若竹 Wakatake** (`wakatake`): running. Live sessions, a running Ubuntu or desktop; always a filled disc.
- **山吹 Yamabuki** (`yamabuki`): held / look at this. The wakelock's half disc, attention icons (Material `tertiary`).
- **躑躅 Tsutsuji** (`tsutsuji`): failed. The struck ring, error text, the destructive button fill (Material `error`).

### Neutral
- **墨 Sumi** (`sumi`): the ground of every screen except the terminal.
- **Terminal Black** (`terminal-black`): the terminal and the extra-keys bar (`TerminalBackground`), and the lowest container.
- **褐色 Kachi** (`kachi`): the chrome: the drawer sheet (`AkaInks.chrome`), surface-container-low. **Kachi 2** (`kachi-2`) is one dye up: surface-container.
- **白練 Shironeri** (`shironeri`): text on the night.
- **銀鼠 Ginnezumi** (`ginnezumi`): secondary text and idle marks (7:1 or better on sumi and kon).
- **Ink Text** (`ink-text`): dark text on light fills (yamabuki, tsutsuji, dawn paper).
- **Dawn** (`dawn`), **Akane Deep** (`akane-deep`), **Tsutsuji Deep** (`tsutsuji-deep`): the light scheme's ground, primary and error.

### Named Rules
**The Named Dye Rule.** Every colour is an `Iro` traditional colour or a scheme role built from one. New colours enter `Iro` and both schemes, never as a raw value in a screen. Screens read `MaterialTheme.colorScheme` or `AkaTheme.inks`.

**The One Akane Rule.** Akane is the action ink: one primary fill per screen region (and the latched keys, the on switch). Everything else is a kon container.

**The Form-First Rule.** Wakatake, yamabuki and tsutsuji never appear without their mark form or their words. Hue is the second signal.

**The Night Rule.** The chrome never outshines the terminal text: surfaces stay in the indigo dyes, shu stays inside the horizon, and the only large bright fill is the one akane primary.

## Typography

**Display Font:** Archivo SemiExpanded at wght 650, wdth 112 (one static instance, declared `FontWeight.SemiBold` in `Type.kt` because it is the only weight in its family; with Archivo, sans-serif)
**Body Font:** Archivo Regular, Medium, SemiBold (normal width)
**Label/Mono Font:** JetBrains Mono Regular and SemiBold

**Character:** one grotesque, calm rather than shouted at night: the semi-expanded 650 cut carries the wordmark, heads and titles; the normal width does the reading and the labels. The mono face is reserved for what the shell owns.

Sizes are sp (written as px, 1px = 1sp). The ramp steps run about 1.1 to 1.25 (44, 36, 30, 27, 23, 20, 16, 14, 12, 11); display tracking tightens as size grows. Display medium (36/42, -0.7) and headline large (27/33, -0.4) exist in `Type.kt` between the steps listed.

### Hierarchy
- **Display** (SemiExpanded 650, 30 to 44, line 36 to 48): the wordmark on onboarding.
- **Headline** (SemiExpanded 650, 20 to 27): page heads and card heads ("Ubuntu 26.04"); headline-medium is the drawer wordmark.
- **Title** (SemiExpanded 650 at 16 to 20; normal width 600 at 14): top bars and dialog titles; title-small is every row title and the selected session.
- **Body** (400, 12 to 16): explanations and supporting text (body-small is the most used role).
- **Label** (600 at 12 to 14, 500 at 11; sentence case): buttons, section labels, drawer actions.
- **Code** (JetBrains Mono 400, 13, line 19): commands, paths, versions. Key caps use the mono face too (title-medium for single glyphs, label sizes for words).

### Named Rules
**The One Family Rule.** Archivo by width and weight, JetBrains Mono for code and keys. No third face.

**The Sentence Case Rule.** Labels and section heads are sentence case at their role's tracking. No uppercase tracked labels above headings.

## Layout

Single column, full width, phone first. Screens pad 16 (`lg`) from the edge; tiles stack with 3 (`tile-gap`) above and below each, so 6 between neighbours. Groups sit under section labels (24 above, 8 below, 20 start). Rows are 56 minimum, drawer rows 52, and every touch target is 48 or more. The spacing scale is `FluxSpacing` (2, 4, 8, 12, 16, 24, 32); 8, 12 and 16 carry most of the rhythm. Layouts hold at font scale 1.3; the drawer wordmark caps its own scale at 1.15.

The terminal is full bleed on black. The extra-keys bar sits under it on the same black: two rows of key caps with 3 gaps, plus a third F-key row when Fn is on, scrolling together between two fixed utility columns (drawer at the left, Paste at the right, each one key wide and the full bar tall). The drawer is a left kachi sheet up to 300 wide, 12 side padding. Key caps are 44 tall (Termux density, so the terminal keeps its height); the 3 gaps and the full-height utility columns keep neighbours apart. This is the one deliberate exception to the 48 target.

## Elevation & Depth

Flat. No drop shadows, and Material's tonal elevation tint is off (`surfaceTint` equals the ground). Depth is the dye ladder: terminal black, sumi, kachi, kachi 2, kon, konjō, each container closed by a 1dp edge. The only light in the system is the horizon's glow, which belongs to the current thing.

### Shadow Vocabulary
None. The horizon (see Components) is the one gradient and is not a shadow.

### Named Rules
**The Dye Ladder Rule.** Lift a surface by stepping it one dye up the indigo ladder and giving it an edge, never with a shadow or tonal tint.

**The One Horizon Rule.** The horizon marks the one current thing in its context: the wordmark, the current session, the selected option or segment, the Ubuntu card once ready, the welcome terminal window. Never on idle siblings or as decoration.

## Shapes

Soft, not pillowed: folded washi rather than cut card. Radius encodes hierarchy: key caps, chips and the switch thumb 6 (`extra-small`); buttons, segments and the switch track 10 (`small`); rows and tiles 12 (`medium`); cards and settings blocks 16 (`large`); sheets and large dialogs 24 (`extra-large`). The drawer is square where it meets the screen edge and 24 at its free edge. No stadium shapes, including the switch. One edge weight (1); the horizon is 2.

## Components

### Buttons (AkaButton)
- **Shape:** soft corners (10, `small`), 48 minimum height, 64 minimum width, 12 by 24 padding, optional 18 leading Sharp icon with an 8 gap, label-large.
- **Primary:** akane fill, white label, no edge; kokiake while pressed (120ms, standard easing). One per region.
- **Secondary:** kon fill with the 1 ai edge and shironeri label; konjō while pressed. Always reads as a button, never as text.
- **Disabled:** sinks below a resting button: the container drops to the kachi chrome fill with the quiet hanada edge, and the label dims to ginnezumi at 60%, so the action's shape stays visible without reading as available.
- **Destructive:** a Material Button in tsutsuji with Ink Text, 10 corners, 48 minimum, for explicit destructive actions in Settings.

### Tiles and selection (akaTile, akaSelection)
- **Tile:** kon fill, 1 hanada edge (ai when it is a `control` or selected), konjō while pressed or selected, fill animating over 120ms.
- **Selection:** a choice among siblings (segment, setup option, current session): the selected one is konjō with the ai edge and its horizon rising (`rise` spring); unselected siblings are resting tiles. The segmented PRoot/Chroot selector adds a check icon to the selected side.

### Lists (AkaRow, SectionLabel)
- **Row:** its own tile inset 16 from the edge, 56 minimum, 12 by 16 padding, optional 22 Sharp icon in ginnezumi, title-small title, body-small supporting text, trailing slot (chevron, switch, mark). The whole row is the target. Disabled at 38% alpha. Inside a block (`LocalInTile`) rows lie flat.
- **Section label:** label-large in shiro-ai, sentence case.

### Cards / Containers
- **Corner Style:** 16 (`large`).
- **Background:** kon tile with the hanada edge; settings blocks pad 16 and hold flat rows.
- **Shadow Strategy:** none (see Elevation & Depth).
- **Signature card:** the Ubuntu card: a large tile with a headline-medium head, state mark plus words, one akane primary inside, and its horizon rising once Ubuntu is ready.

### Switch (FluxSwitch)
Track 52 by 32 at 10 corners with a 1 edge; square-ish thumb at 6 corners, inset 5. On: akane track and edge, 22 white thumb at the end. Off: kon track, ai edge, 16 ai thumb. Track colour over 220ms; thumb size and position on the `settle` spring; reduced motion snaps. Disabled at 38% alpha. With no `onCheckedChange` the row owns the toggle and its semantics.

### Status mark (StatusMark)
A drawn 10 mark: filled disc (running, wakatake), open ring (idle, ginnezumi), dashed turning ring (working, shiro-ai, 1.4s per turn, still under reduced motion), half disc (held, yamabuki), struck ring (failed, tsutsuji). Stroke is a fifth of the size, 1.5 minimum.

### Extra keys
Key caps on the black bar: each a kon tile at 6 corners with the hanada edge and a shironeri label; konjō with the ai edge while pressed. Every label is in the mono face: word keys at label-medium (label-small in narrow cells under 44), both at zero tracking; single glyphs at title-medium. A latched modifier (CTRL, ALT, SHFT, Fn) is the akane fill with white text, kokiake while pressed; a locked one adds a 14 by 2 bar under its label. The utility keys carry Sharp Menu and ContentPaste icons.

### Navigation
The session drawer: a kachi sheet; the header is the headline-medium wordmark over its static horizon, a "Sessions" title-small in ginnezumi below it, closed by a hanada divider. Sessions are 52 tiles with a 10 mark in the 24 icon column; the current one is the selection (title-small, shironeri) whose horizon rises as the drawer opens, the rest body-medium in ginnezumi. Then New session as the sheet's one akane button (with a ▾ shell menu), the Extra keys and Hold wakelock switch tiles (the held wakelock swaps its icon for the half disc), then Ubuntu page, Files and Settings tiles. Top bars are flat on sumi with Sharp icons.

### Wordmark and mark
**AkaWordmark** sets "Aka" in akane and "Terminal" in shironeri over the horizon, 8 below the text. On first launch the horizon rises over 720ms (`RISE`, decelerate easing); elsewhere it is shown risen. The app mark and launcher icon are a shell prompt with the akane sun on the horizon where the cursor would be, over a sumi-to-konjō twilight sky with akane light. The icon's gradients are the sky (a vertical sumi, konjō, kachi blend), the akane light (a radial glow around the sun, on the launcher background only), the shu-to-akane sun and the horizon line; they stay inside the icon and the in-app mark (`ic_aka_mark`, the same sky, sun and horizon without the glow). Monochrome and status-bar icons are single white shapes. Geometry is shared with `scripts/render_launcher_icon.py`.

### Motion
Durations: 120 quick, 220 standard, 720 rise. Easing: standard `cubic-bezier(0.2, 0, 0, 1)`, decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)`. Springs: `rise` (no bounce, stiffness 180, about 400ms) for the horizon, `settle` (no bounce, stiffness 520) for thumbs and sheets. Nothing overshoots.

## Do's and Don'ts

### Do:
- **Do** take every colour from `MaterialTheme.colorScheme` or `AkaTheme.inks`; new colours go into `Iro` and both schemes first.
- **Do** put every control in a container: AkaRow, akaTile, AkaButton SECONDARY, never bare text.
- **Do** give each region one akane AkaButton PRIMARY and make the rest SECONDARY.
- **Do** mark the one current choice with akaSelection, so its horizon rises.
- **Do** pair every state colour with a StatusMark form or plain words.
- **Do** use the shape scale (6, 10, 12, 16, 24) and `FluxSpacing`, never raw dp for padding.
- **Do** honour reduced motion: the horizon shows risen, switches snap.
- **Do** set code, paths and commands in JetBrains Mono (`CodeStyle`).

### Don't:
- **Don't** use drop shadows, glows outside the horizon, glass or tonal elevation tints.
- **Don't** draw the horizon on more than the one current thing, or use shu anywhere but the horizon and the icon's sun.
- **Don't** set ai (#4A66AA) or hanada as text; use shiro-ai.
- **Don't** put dark text on akane; it takes white. Yamabuki and tsutsuji take Ink Text.
- **Don't** add uppercase tracked kickers or eyebrows above headings.
- **Don't** tint the terminal or the extra-keys bar; both stay pure black.
- **Don't** use Rounded or Outlined icon sets, emoji or text glyphs as icons outside key caps.
- **Don't** turn on dynamic (wallpaper) colour.

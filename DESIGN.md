---
name: AkaTerminal
description: Ubuntu on Android, terminal first, printed as a two-ink risograph sheet.
colors:
  stock: "#0F1326"
  stock-deep: "#0A0D1C"
  surface-container-low: "#131830"
  surface-container: "#171D38"
  surface-container-high: "#1D2443"
  surface-container-highest: "#242C50"
  surface-bright: "#2B3460"
  outline-variant: "#2E3760"
  federal: "#3255A4"
  federal-field: "#1B2A5C"
  federal-field-high: "#243670"
  federal-tint: "#A9BCF2"
  federal-line: "#5D78C4"
  red: "#F15060"
  red-deep: "#C62E3F"
  overprint: "#4A2150"
  on-overprint-container: "#FFD9DD"
  yellow: "#FFE800"
  tertiary-container: "#3D3A12"
  green-tint: "#3FD98A"
  pink: "#FF48B0"
  pink-deep: "#C2186B"
  error-container: "#55123F"
  on-error-container: "#FFD6EA"
  bone: "#E9ECF2"
  bone-muted: "#B4BCD3"
  bone-on-field: "#C3CDEB"
  ink-text: "#14193A"
  stock-light: "#EFF1F5"
  federal-line-deep: "#3F5AA8"
  federal-field-light: "#2A4A94"
typography:
  display-large:
    fontFamily: "Archivo Expanded, Archivo, sans-serif"
    fontSize: "44px"
    fontWeight: 800
    lineHeight: "48px"
    letterSpacing: "-1px"
  display-small:
    fontFamily: "Archivo Expanded, Archivo, sans-serif"
    fontSize: "30px"
    fontWeight: 800
    lineHeight: "36px"
    letterSpacing: "-0.5px"
  headline-large:
    fontFamily: "Archivo Expanded, Archivo, sans-serif"
    fontSize: "27px"
    fontWeight: 800
    lineHeight: "33px"
    letterSpacing: "-0.4px"
  headline-medium:
    fontFamily: "Archivo Expanded, Archivo, sans-serif"
    fontSize: "23px"
    fontWeight: 800
    lineHeight: "29px"
    letterSpacing: "-0.3px"
  headline-small:
    fontFamily: "Archivo Expanded, Archivo, sans-serif"
    fontSize: "20px"
    fontWeight: 800
    lineHeight: "26px"
    letterSpacing: "-0.2px"
  title-large:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: "26px"
    letterSpacing: "-0.1px"
  title-medium:
    fontFamily: "Archivo SemiExpanded, Archivo, sans-serif"
    fontSize: "16px"
    fontWeight: 600
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
  extra-small: "2px"
  small: "4px"
  medium: "6px"
  large: "10px"
  extra-large: "14px"
spacing:
  xxs: "2px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
  touch: "48px"
components:
  plated-button-primary:
    backgroundColor: "{colors.red}"
    textColor: "{colors.ink-text}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    padding: "12px 24px"
    height: "48px"
  plated-button-disabled:
    backgroundColor: "{colors.federal-field}"
    textColor: "{colors.bone-on-field}"
    rounded: "{rounded.small}"
    padding: "12px 24px"
    height: "48px"
  selection-plate:
    backgroundColor: "{colors.overprint}"
    textColor: "{colors.bone}"
    typography: "{typography.title-small}"
    rounded: "{rounded.small}"
    height: "56px"
  key-cap:
    backgroundColor: "{colors.federal-field}"
    textColor: "{colors.bone}"
    typography: "{typography.label-medium}"
    rounded: "{rounded.extra-small}"
    height: "48px"
  key-cap-pressed:
    backgroundColor: "{colors.federal-field-high}"
    textColor: "{colors.bone}"
  key-cap-latched:
    backgroundColor: "{colors.overprint}"
    textColor: "{colors.bone}"
    rounded: "{rounded.extra-small}"
  list-row:
    backgroundColor: "{colors.stock}"
    textColor: "{colors.bone}"
    typography: "{typography.title-small}"
    padding: "12px 16px"
    height: "56px"
  section-label:
    textColor: "{colors.federal-tint}"
    typography: "{typography.label-large}"
    padding: "24px 16px 8px 16px"
  printed-field:
    backgroundColor: "{colors.federal-field}"
    textColor: "{colors.bone}"
    rounded: "{rounded.large}"
    padding: "16px"
  switch-on:
    backgroundColor: "{colors.red}"
    textColor: "{colors.ink-text}"
    rounded: "{rounded.small}"
    width: "52px"
    height: "32px"
  switch-off:
    backgroundColor: "{colors.surface-container-highest}"
    textColor: "{colors.federal-line}"
    rounded: "{rounded.small}"
    width: "52px"
    height: "32px"
  button-danger:
    backgroundColor: "{colors.pink}"
    textColor: "{colors.ink-text}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    height: "48px"
  button-text:
    textColor: "{colors.red}"
    typography: "{typography.label-large}"
    height: "48px"
---

# Design System: AkaTerminal

## Overview

**Creative North Star: "The Two-Ink Zine"**

AkaTerminal is printed, not rendered. Every colour on screen is a risograph spot ink or the stock it is printed on: a blue-black dark stock, a Federal Blue field for the shell, Bright Red (aka) for the one primary action, and the plum overprint where red lands on blue to mark what is selected. Hierarchy comes from tone and ink, not from light and shadow. The terminal sits full bleed on pure black (the user's choice: on OLED the shell should read as a screen, not tinted paper); the chrome around it stays on the stock.

The density is that of a tool used one-handed at night for long sessions. Rows are full width on the stock and grouped by small labels, not boxed in cards. Large printed fields (the drawer header, the Ubuntu status sheet, onboarding plates) carry a faint fixed-seed grain; nothing else does. The one signature motion is misregistration that registers: primary and selected elements rest with their second plate 2dp out of register, and a press settles the plates together with one damped overshoot. Reduced motion cuts instantly.

The system is Material 3 underneath (drawer, dialogs, radio buttons, progress, snackbars, system Back) restyled with the inks, a cut-paper shape scale and one type family. It ships dark only; the light scheme (cool white stock, deepened inks) is kept in the tokens so a later switch stays cheap. Dynamic (wallpaper) colour is off: the inks are fixed.

**Key Characteristics:**
- Spot inks on dark stock; no greys, no gradients, no glass.
- One primary ink (Red) and one selection ink (overprint plum), both printed as misregistered plates.
- State told by mark form first, hue second.
- One family, Archivo, ranked by width and weight; JetBrains Mono only for code, paths and key caps.
- Cut-paper corners (2 to 14px), one hairline weight, no shadows.
- Sharp Material icons.

## Colors

A two-ink riso sheet: blue-black stock, Federal Blue fields, Bright Red for action, and three state inks that always travel with a drawn mark.

### Primary
- **Bright Red, aka** (`red`): the brand ink. Primary action fills (PlatedButton, New session, switch-on track), the "Aka" half of the wordmark, text buttons and links. Text on it is Ink Text, never white.
- **Deep Red** (`red-deep`): Red deepened for text and fills on light stock; inverse primary on dark.
- **Overprint Plum** (`overprint`): Red over Federal Blue. The selection fill: the active session row, latched modifier keys, the selected option in onboarding and install, and the body of the app mark. Also the dark primary container.

### Secondary
- **Federal Blue** (`federal`): the ink itself. Used as the second plate (bottom-end fringe) of every plated element, the wordmark's under-plate, and the mark's blue plate. Never as text on stock: it reads under 4.5:1.
- **Federal Field** (`federal-field`): Federal laid down as a solid field. The extra-keys bar, the session drawer, printed sheets, the disabled (unprinted) plate. Also the secondary container.
- **Federal Field High** (`federal-field-high`): one printed tint up from the field. Pressed key caps, the off switch track on the field.
- **Federal Tint** (`federal-tint`): Federal as text on dark stock. Section labels, secondary accent text, the working mark.
- **Federal Line** (`federal-line`): the one hairline. Outlines, unchecked controls, the off switch (3:1 or better on stock).

### Tertiary
- **Riso Yellow** (`yellow`): held / look at this. The wakelock's held mark and attention states. Not a fill for buttons.
- **Green Tint** (`green-tint`): running. Live sessions and a running Ubuntu or desktop, always as a filled-disc mark.
- **Fluorescent Pink** (`pink`): failed. Error text, the struck-ring mark and the destructive button fill. Never alone: a mark or words always carry the meaning too.

### Neutral
- **Dark Stock** (`stock`): the ground of every screen except the terminal itself, which is pure black (`TerminalBackground`).
- **Deep Stock** (`stock-deep`): one step under the stock: the terminal well, scrims, lowest container.
- **Printed blue-black tints** (`surface-container-low` through `surface-bright`): surfaces stepping up in tone for dialogs, menus and sheets.
- **Outline Variant** (`outline-variant`): quiet dividers on stock.
- **Cool Bone** (`bone`): body and title text on stock and on the Federal field.
- **Bone Muted** (`bone-muted`): supporting text on stock (7:1 or better).
- **Bone on Field** (`bone-on-field`): supporting text on the Federal field, idle marks there (4.5:1 or better).
- **Ink Text** (`ink-text`): text on Red, Yellow and Pink fills, and body text on light stock.
- **Light Stock** (`stock-light`), **Federal Line Deep** (`federal-line-deep`), **Light Federal Field** (`federal-field-light`): the light scheme's ground, hairline and shell field.

### Named Rules
**The Spot Ink Rule.** Every colour is one of the inks or the stock. New colours enter `Riso` and the schemes, never as a raw value in a screen. Screens read `MaterialTheme.colorScheme` or `AkaTheme.inks`.

**The One Red Rule.** Red is the action ink. One plated primary per screen region; secondary actions are outlined or text buttons.

**The Form-First Rule.** A state ink (green, yellow, pink) never appears without its mark form or its words. Hue is the second signal.

**The Night Rule.** The chrome never outshines the terminal text: fields stay dark blue, grain stays at 0.07 alpha or less, and no ink is used as a large bright fill except the one red primary.

## Typography

**Display Font:** Archivo Expanded ExtraBold (with Archivo, sans-serif)
**Title Font:** Archivo SemiExpanded SemiBold
**Body Font:** Archivo Regular, Medium, SemiBold (normal width)
**Label/Mono Font:** JetBrains Mono Regular and SemiBold

**Character:** one grotesque ranked like a zine's type case: the expanded heavy cut shouts the wordmark and page heads, the semi-expanded cut sets titles, the normal width does the reading. The mono face is reserved for what the shell owns.

Sizes are in sp (the frontmatter writes them as px, 1px = 1sp). The ramp steps at about 1.2, and display tracking tightens as size grows.

### Hierarchy
- **Display** (800, 30 to 44, line 36 to 48, tracking -0.5 to -1): the AkaTerminal wordmark and the onboarding welcome only.
- **Headline** (800, 20 to 27, line 26 to 33): page heads and printed-sheet heads ("Ubuntu 26.04", "Ubuntu terminal").
- **Title** (SemiExpanded 600 at 16 to 20; normal-width 600 at 14): dialog titles and top bars; title-small is every row title.
- **Body** (400, 12 to 16, line 17 to 24): explanations and row supporting text (body-small is the most used role).
- **Label** (600 at 12 to 14, 500 at 11; sentence case): buttons, section labels, chips and key caps.
- **Code** (JetBrains Mono 400, 13, line 19): commands, paths, version strings; single-glyph key caps use the mono face at title-medium.

### Named Rules
**The One Family Rule.** Archivo by width and weight, JetBrains Mono for code. No third face.

**The Sentence Case Rule.** Labels and section heads are sentence case at their role's tracking. No uppercase tracked labels above headings.

## Layout

Single column, full width, phone first. Screens pad from the edge at 16 (`lg`), with 24 (`xl`) on drawer and sheet headers. Content is grouped by section labels (24 above, 8 below) and full-width rows of 56 minimum height, not by cards. The spacing scale is `FluxSpacing` (2, 4, 8, 12, 16, 24, 32); 8, 16 and 12 carry most of the rhythm. Every touch target is 48 or more (`touch`), including key caps at phone width.

The terminal is full bleed on black; the extra-keys field sits under it at the bottom, two scrolling rows with fixed utility columns (drawer at the left, Paste at the right). The drawer is a left Federal sheet. Layouts must hold at font scale 1.3 without clipping.

## Elevation & Depth

Flat. There are no drop shadows and Material's tonal elevation tint is disabled (`surfaceTint` equals the ground). Depth comes from printing: the stock, a printed blue-black tint step for dialogs and menus, and the solid Federal field for the shell. The only offset form is the misregistered second plate, which is ink, not light.

### Named Rules
**The Printed Depth Rule.** Lift a surface by changing its ink or tint step, never with a shadow or a glow.

**The Two-Plate Rule.** Misregistration belongs to primary and selected elements only: plate A peeks top-start and plate B bottom-end, both 2px, and they register (offset 0) on press with the `register` spring. It is never a decorative offset shadow on cards, rows or text.

**The Grain Rule.** Grain (fixed seed, 0.07 alpha dark, 0.06 light) prints only on large fields, under the content, never behind small body text.

## Shapes

Cut paper: corners are trimmed, not pillowed, and radius encodes hierarchy. Key caps, chips and the switch thumb take 2 (`extra-small`); buttons, rows, plates and the switch track 4 (`small`); small containers 6 (`medium`); printed sheets and onboarding plates 10 (`large`); the drawer sheet and large dialogs 14 (`extra-large`). Material's stadium shapes are replaced, including the switch. One hairline weight (1).

## Components

### Buttons
- **Primary (PlatedButton):** a printed plate that registers when pressed. Red fill, Ink Text label (label-large), 4 corners, 12 by 24 padding, 48 minimum height, optional 18 leading Sharp icon. The Federal plate rests 2 out of register bottom-end; press settles it with one overshoot.
- **Disabled:** an unprinted plate: the Federal field with no fringes and Bone on Field text at 60%, not Material grey.
- **Secondary:** Material OutlinedButton (Federal Line hairline) and TextButton (Red label).
- **Destructive:** a filled button in Fluorescent Pink with Ink Text, 4 corners, 48 minimum height, used for explicit destructive actions in Settings.

### Selection plate
The selected or current item (active session row, latched modifier, chosen setup option): overprint plum fill, Bone text, red fringe top-start and Federal fringe bottom-end, registering on press. Unselected siblings sit flat on their ground.

### Lists (AkaRow, SectionLabel)
- **Row:** full width on the stock, 56 minimum, 12 by 16 padding, optional 22 Sharp icon in Bone Muted, title-small title, body-small supporting text, trailing slot (chevron, switch, mark). The whole row is the target. Disabled at 38% alpha.
- **Section label:** label-large in Federal Tint, sentence case, 24 above and 8 below.

### Printed field (sheets)
The Ubuntu status sheet and onboarding plates: Federal field, 10 corners, 16 padding, grain under the content, headline-medium head, state mark plus words, one plated primary inside.

### Switch (FluxSwitch)
Squared track 52 by 32 at 4 corners with a 1 hairline; squared thumb at 2 corners. On: Red track, Ink Text thumb 22 at the end. Off: surface-container-highest track, Federal Line border and a 16 thumb; on the Federal field the off track takes Federal Field High. Thumb slides with the `settle` spring; reduced motion snaps.

### Status mark (StatusMark)
A drawn 10 mark: filled disc (running, Green Tint), open ring (idle), dashed turning ring (working, Federal Tint, 1.4s per turn, still under reduced motion), half disc (held, Yellow), struck ring (failed, Pink). On the Federal field marks take the full-strength spot inks.

### Extra keys
Key caps on the Federal field: flat at rest (no fill), one tint up while pressed, 2 corners. Word keys in label-medium; single glyph keys (arrows, symbols) in the mono face at title-medium, as on a physical keyboard. Latched modifiers are the selection plate.

### Navigation
The session drawer is a left Federal sheet: grain-printed wordmark header closed by a translucent Federal Tint hairline, session rows with marks, New session as a red plate, the Hold wakelock switch, then Ubuntu page, Files and Settings rows. Top bars are flat on stock with a title in headline or title type and Sharp icons.

### Wordmark and mark
**AkaWordmark** prints in two passes: a Federal under-plate 1.5 down-end, "Aka" in Red and "Terminal" in Bone over it. On first launch the passes slide in from opposite sides and register with the `register` spring (640ms print budget); they rest slightly out of register. The app mark (`ic_aka_mark`) is the same three inks: red and Federal plates under an overprint plate with a bone prompt.

## Do's and Don'ts

### Do:
- **Do** take every colour from `MaterialTheme.colorScheme` or `AkaTheme.inks`; new inks go into `Riso` first.
- **Do** print the main action of a region as one PlatedButton and keep the rest outlined or text.
- **Do** pair every state ink with a StatusMark form or plain words.
- **Do** group settings and actions as full-width AkaRows under SectionLabels on the stock.
- **Do** use the shape scale (2, 4, 6, 10, 14) and `FluxSpacing`, never raw dp for padding.
- **Do** honour reduced motion: plates and wordmark cut to register, switches snap.
- **Do** set code, paths and commands in JetBrains Mono (`CodeStyle`).

### Don't:
- **Don't** use drop shadows, glows, gradients, glass or tonal elevation tints.
- **Don't** put the misregistered offset on anything that is not a primary or selected element.
- **Don't** set Federal (#3255A4) as text on stock; use Federal Tint.
- **Don't** use white text on Red, Yellow or Pink fills; use Ink Text.
- **Don't** add uppercase tracked kickers or eyebrows above headings.
- **Don't** print grain behind small body text or across the terminal.
- **Don't** use Rounded or Outlined icon sets, emoji or text glyphs as icons outside key caps.
- **Don't** turn on dynamic (wallpaper) colour.

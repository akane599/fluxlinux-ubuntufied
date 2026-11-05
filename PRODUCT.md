# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- Primary: the maintainer (akane599), who runs Ubuntu on a rooted Android phone (SM-S938B, KernelSU) as a daily Linux environment: shell work, agent CLIs (Claude Code, Codex) full screen, long apt/build jobs with the screen off, now and then an XFCE desktop.
- Secondary: people who install the public GitHub/F-Droid release to get a real Ubuntu on an unrooted phone. They meet setup and onboarding first, so first-run clarity matters, but the daily terminal is the main surface.

## Product Purpose
AkaTerminal runs Ubuntu 26.04 on Android without root (PRoot) or with root (chroot), terminal first: opening the app shows a working Ubuntu shell. An XFCE desktop over an embedded Termux-X11 server is optional, added during setup or later. Success: the terminal feels like a native, fast, Termux-grade terminal, and the surrounding app (setup, Ubuntu page, files, settings) stays out of the way.

## Positioning
A single app that is both the terminal and the Ubuntu install: no Termux, no proot-distro commands, no separate X11 app. The same Ubuntu user (`akane`) and home in PRoot and chroot, phone storage linked into `~/storage`, and Ubuntu's files exposed to Android's Files app.

## Operating Context
- Used one-handed on a phone, often for long sessions, frequently with the on-screen keyboard and the two-row extra-keys bar. Full-screen TUIs (Claude Code, Codex, tmux, vim) run inside it.
- A foreground-service notification keeps sessions alive; it carries Hold wakelock and Exit.
- Navigation: a left session drawer on the terminal (edge swipe or the ☰ key) leads to the Ubuntu page, Files and Settings.

## Capabilities and Constraints
- Single-activity Jetpack Compose app (Material 3) plus vendored Termux terminal-emulator / termux-x11 views; minSdk 26, targetSdk 36.
- Package ids stay `com.ivarna.fluxlinux` (ivarna flavor, F-Droid/GitHub) and `com.zenithblue.fluxlinux` (Play) so existing installs update.
- Rootfs and XFCE theme assets download from the upstream project's GitHub releases; those URLs are functional and stay.
- Other distros' code and installs remain but are not in the main UI.

## Brand Commitments
- Name: AkaTerminal (renamed from FluxLinux on 2026-11-05). Author shown as akane599.
- The launcher icon stays as it is.
- The original project (FluxLinux by abhay-byte) is credited once, in a small place (Settings → About); its owner's name, photo and social links are not shown elsewhere.

## Evidence on Hand
- No testimonials, user counts or press. Do not invent any.
- Real screenshots come from the app (compose-preview screenshot tests under `app/src/screenshotTestIvarnaDebug/reference`).

## Product Principles
1. The terminal is the product; everything else is one gesture away and never in front of it.
2. Termux parity where users have muscle memory (extra keys, notification actions, key repeat), improvements only where they are clearly better.
3. Never risk the user's files: destructive actions are explicit and explained.
4. Honest state: show what is installed, running or failing, in plain words.

## Accessibility & Inclusion
- Large font scale (1.3) must not clip; touch targets 48dp; the app ships dark only (MainActivity pins ThemeMode.DARK); the light scheme is kept in the tokens and previews so a later switch stays cheap.

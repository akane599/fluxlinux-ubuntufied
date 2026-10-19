# FluxLinux codebase map
*Last Updated: 2026-10-19*

FluxLinux runs full Linux desktops on Android without root via PRoot, or with root via chroot. It covers 12 distros, with XFCE/KDE over an embedded Termux-X11 server and PulseAudio. This repo is the `akane599/fluxlinux-ubuntufied` fork of `abhay-byte/fluxlinux`. It is a single-Activity Compose app (`:app`) with three vendored Termux-derived modules and a native host-bootstrap pipeline (`native/`, `scripts/`).

The app embeds its own Termux-style host prefix: `bootstrap.tar` is extracted to `filesDir/usr`, and `libbash`/`libproot`/`libloader` come from jniLibs. Guest rootfs tarballs are downloaded at install time from the GitHub release tag `rootfs`. Shell scripts in `app/src/main/assets/scripts/` do most of the guest-side work.

## Docs
| Doc | Read when |
|---|---|
| [architecture.md](architecture.md) | You need the big picture: layers, the host/guest split, and the key runtime flows |
| [modules.md](modules.md) | You are touching `:termux-x11`, `:terminal-emulator` or `:stub`, or a `core/*` package |
| [entry-points.md](entry-points.md) | You are looking for the manifest components, navigation (`Screen` enum) or where a screen lives |
| [native-bootstrap.md](native-bootstrap.md) | You are touching `native/`, `scripts/`, `bootstrap.tar`, jniLibs, rootfs pins or `packageHostAssets*` |
| [build-and-release.md](build-and-release.md) | You are dealing with flavors, versions/deps, reproducible builds, the F-Droid recipe, fastlane or `docs/` |
| [patterns.md](patterns.md) | You are writing code: state, threading, shell execution, prefs, tests, style |
| [onboarding.md](onboarding.md) | You are on a fresh checkout, running a first build, or doing a common task |

## How to use / maintain this map
- Read INDEX first, then open only the doc your task needs. Prefer the cited paths over re-deriving them.
- After changing structure (new package, module, script, flow or build step), update the affected doc and its `Last Updated` line, then run `node <codebase-mapper>/scripts/write-map-state.js --project .` (or `/codebase-mapper:update-codebase-map`).
- Never record secrets. `keystore.properties` and `*.jks` exist locally but must not be read.

# AkaTerminal

Ubuntu 26.04 in your pocket. AkaTerminal opens straight into a terminal with a full Ubuntu
userland behind it, with no root needed. On a rooted phone you can switch to a faster chroot
runtime, and an XFCE desktop is one switch away when a keyboard isn't enough.

By [akane599](https://github.com/akane599) · GPLv3 · Android 8.0+ (arm64-v8a)

---

## What it does

- **Terminal first.** Opening the app shows its terminal. You get tabs, extra keys (Esc, Ctrl,
  Alt, arrows and more) and tappable links. The ongoing notification keeps sessions alive and
  has two actions: **Hold wakelock** keeps the phone awake with the screen off, and **Exit**
  closes the terminals and the desktop.
- **Two runtimes.** PRoot works on any phone. Chroot (root via Magisk, KernelSU or APatch) runs
  a full system and is noticeably faster. You can switch between them in Settings.
- **Optional desktop.** XFCE over the bundled X11 display and PulseAudio audio. Install it
  during setup or add it later from the Ubuntu page. GPU acceleration uses Turnip + Zink on
  Adreno, PanVK + Zink on MediaTek phones with a recent Mali GPU (Valhall v10 or newer) and
  VirGL elsewhere.
- **Your files, both ways:**
  - Ubuntu's home and its whole filesystem show up as roots in the Android Files app and any
    file picker.
  - Inside Ubuntu, Termux-style links point at your phone's storage:

  | Link | Target |
  |------|--------|
  | `~/storage/shared` | `/storage/emulated/0` |
  | `~/storage/downloads` | `Download` |
  | `~/storage/documents` | `Documents` |
  | `~/storage/dcim` | `DCIM` |
  | `~/storage/pictures` | `Pictures` |
  | `~/storage/music` | `Music` |
  | `~/storage/movies` | `Movies` |

- **Self-contained.** The host shell, PRoot, X11 and PulseAudio ship inside the APK. You don't
  need a separate Termux or Termux:X11 install.
- **Safe with your data.** File operations never follow symlinks, so removing or measuring
  Ubuntu can't reach your shared storage through `~/storage`. Upgrades keep your installed
  system and home folder.

## Install

Download the APK from [GitHub Releases](https://github.com/akane599/AkaTerminal/releases).
Open it, finish the short setup (optional desktop, storage access, and PRoot or chroot when
the phone is rooted) and the terminal opens when Ubuntu is ready.

Releases are signed with this project's own key, and the package name is
`com.ivarna.fluxlinux`. If you have upstream FluxLinux from F-Droid or its GitHub releases
installed, it uses the same package name, so uninstall it first. **Uninstalling deletes its
data.** Upstream's Play build (`com.zenithblue.fluxlinux`) can stay installed alongside.

Coming from "FluxLinux Ubuntu edition" v2.1.x? From v2.2.0 on, releases are signed with a
new key, so Android won't update a v2.1.x install in place: uninstall it first (this deletes
its Ubuntu install and home folder, so copy out anything you need), then install AkaTerminal.
Later updates install over it as usual.

## Build

```sh
git submodule update --init --depth 1 -- $(git config -f .gitmodules --get-regexp 'submodule\.termux-x11.*\.path' | awk '{print $2}')
python3 scripts/assemble_bootstrap.py --package-name com.ivarna.fluxlinux --mode full
bash scripts/verify_bootstrap.sh com.ivarna.fluxlinux
./gradlew :app:assembleIvarnaDebug
```

You need JDK 17 and the Android SDK (compileSdk 36) in `ANDROID_HOME`.

## Documentation

| Document | About |
|----------|-------|
| [Hardware acceleration](docs/hardware_acceleration.md) | GPU drivers (Turnip, VirGL) |
| [Scripts reference](docs/scripts_reference.md) | Installation and setup scripts |
| [Script execution](docs/script_execution_workflow.md) | How scripts run inside the guest |
| [Assets reference](docs/assets_reference.md) | Themes, icons, wallpapers, rootfs |
| [Architecture](docs/architecture.md) | System design overview |
| [Privacy policy](docs/privacy_policy.md) | No accounts, no analytics, no tracking |

## Problems

Open an issue at [github.com/akane599/AkaTerminal/issues](https://github.com/akane599/AkaTerminal/issues).
**Troubleshooting** on the Ubuntu page covers the common ones.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

The terminal stack bundles [termux-app](https://github.com/termux/termux-app) v0.118.0 (GPLv3)
for `TerminalSession` and `TerminalView`. The display is a fork of
[termux-x11](https://github.com/termux/termux-x11). Host packages are rebuilt from the Termux
package repository (`native/`). The UI typefaces are Archivo and JetBrains Mono, both under the
SIL Open Font License (see [third_party/fonts](third_party/fonts/README.md)).

<sub>AkaTerminal is a fork of [FluxLinux](https://github.com/abhay-byte/fluxlinux).</sub>

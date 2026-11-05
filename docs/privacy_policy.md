# Privacy Policy for AkaTerminal

**Last updated:** November 5, 2026

AkaTerminal runs Ubuntu on your device. This policy explains what the app does with your data.

## 1. No data collection

AkaTerminal works entirely on your device. There is no backend server, no account, and no analytics, tracking or crash-reporting service.

## 2. Network use

After you agree in setup, the app downloads the Ubuntu rootfs (and, if you choose them, desktop theme assets or GPU driver archives) from GitHub Releases. Programs you run inside Ubuntu (apt, git, curl, …) use the network as you tell them to.

## 3. Permissions

- **All files access** (GitHub/F-Droid build, Android 11+) or storage read/write (Android 8–10): links your phone's folders into `~/storage` inside Ubuntu. Only when you allow it.
- **Notifications**: the ongoing notification that keeps terminal sessions alive, with Hold wakelock and Exit.
- **Wake lock**: only while you turn on Hold wakelock.
- **Root** (optional): only for the chroot runtime on rooted phones.

## 4. Your data

Everything the app creates (the Ubuntu filesystem, your home folder, settings) stays on your device. Uninstalling the app or clearing its data deletes it. Files you keep in shared storage through `~/storage` are not deleted.

## 5. Changes

Changes to this policy are published in this file in the repository.

## 6. Contact

Questions: open an issue at [github.com/akane599/fluxlinux-ubuntufied/issues](https://github.com/akane599/fluxlinux-ubuntufied/issues).

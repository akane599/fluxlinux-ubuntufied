# Native host bootstrap and rootfs pipeline
*Last Updated: 2026-10-19*

There is one host bootstrap per applicationId, because termux-packages debs bake in the `/data/data/<appId>/files/usr` prefix. Never ship a bootstrap built for another appId. Full plan: `docs/plans/termux-native-packages-dual-appid.md`. Overview: `native/README.md`.

## Layout
| Path | Tracked? | Contents |
|---|---|---|
| `native/termux-packages/` | submodule (shallow, usually not checked out) | termux-packages tree, needed only to rebuild debs |
| `native/package-lists/` | yes | `bootstrap-host.txt`, `termux-lib-ssot.txt` (what F-Droid builds), `terminal-proot.txt`, `ivarna-missing.txt` |
| `native/output/<appId>/*.deb` | **yes** | Built debs, so a bootstrap can be assembled anywhere without Docker |
| `native/bootstrap/<appId>/jniLibs/arm64-v8a/` | yes | `libbash`, `libproot`, `libloader`, `libloader32`, `libpulseaudio`, `libpactl` `.so` |
| `native/bootstrap/<appId>/bootstrap.tar` | no (gitignored, about 124 MB) | Regenerated with `scripts/assemble_bootstrap.py` |
| `native/loader/Loader.java` | yes | Source of `app/src/main/assets/loader.apk`. The class must stay `com.termux.x11.Loader` |
| `native/patches/proot/`, `native/guest/` | yes | 16 KB loader32 patch. openSUSE `libevp_md2` stub |

## Scripts (`scripts/`)
| Script | Purpose |
|---|---|
| `build_packages_for_appid.sh <appId>` | Build debs. Uses Docker (termux package-builder) by default; `NO_DOCKER=1` builds on the host (F-Droid) |
| `assemble_bootstrap.py --package-name <appId> --mode full` | Build `bootstrap.tar` and jniLibs from the debs |
| `verify_bootstrap.sh <appId>`, `verify_deb_prefix.sh` | Check prefix/RUNPATH correctness |
| `package_host_assets.sh <appId>` | Stage the bootstrap, jniLibs and pulse-runtime into `app/src/<flavor>/`. Called by Gradle |
| `verify_apk_host_assets.sh <apk>` | unzip checks on a built APK, for either flavor: the six jniLibs, `assets/bootstrap.tar` present and STORED, and zero `assets/rootfs/*` entries |
| `build_loader_apk.sh`, `build_guest_helpers.sh` | Rebuild `loader.apk`, `bwrap-proot-shim` and `libevp_md2.so`. Needed after F-Droid's scanner deletes binaries |
| `upload_rootfs_release.sh`, `upload_host_bootstrap_release.sh`, `verify_rootfs_shas.sh` | Upload to and verify the GitHub release tag `rootfs` |

## Gradle hook
In `app/build.gradle.kts`, `packageHostAssetsIvarna`/`packageHostAssetsZenithblue` (Exec → `package_host_assets.sh`) fail closed if `bootstrap.tar` or any of the six `.so` files is missing. Every `assemble*`/`bundle*`/`pre*` task for the flavor depends on them. Outputs are `app/src/<flavor>/assets/bootstrap.tar` and `app/src/<flavor>/jniLibs/` (both gitignored).

## In the APK
- `assets/bootstrap.tar`, STORED (`noCompress` `tar`/`xz`).
- jniLibs extracted to `nativeLibraryDir` (`useLegacyPackaging`).
- `assets/pulse-runtime/*.so`, `assets/loader.apk`, `assets/xfce4/`, `assets/scripts/`.

## Rootfs
- 12 distro tarballs are downloaded at install time from release tag `rootfs`, with SHA/size gates in `RootfsDownloader`. Pins live in `DistroInstallProfile`, the SSOT.
- They are not built in this repo; they are uploaded from a local gitignored `assets/rootfs/`.
- Never overwrite a release asset with different bytes, because old APKs pin its SHA. Run `verify_rootfs_shas.sh` after changing a pin.

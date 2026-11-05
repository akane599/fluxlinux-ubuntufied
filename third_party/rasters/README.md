# Raster provenance

Every raster the app ships carries its origin. PNGs carry it inside the file (a text chunk
written by `impeccable embed-prompt`; read it back with `impeccable embed-prompt <file> --read`).
WebP files can't hold it in a form the Android resource merger accepts, so their records live
here as JSON sidecars, one per file, named after the resource
(`app-drawable/<name>.webp.json` ↔ `app/src/main/res/drawable/<name>.webp`). Android rejects
any non-resource file inside `res/`, so they can't sit next to the image.

| Rasters | Origin |
|---|---|
| `drawable/distro_*.webp` | Inherited from upstream FluxLinux (GPLv3). Each is the logo of a Linux distribution and a trademark of that project, used only to identify the distribution. |
| `drawable/ic_logo.webp`, `drawable/ic_launcher_monochrome.png`, `mipmap-*/ic_launcher.png` | Inherited from upstream FluxLinux (GPLv3). This is the launcher art, kept unchanged at the user's request. |
| `termux-x11/src/main/res/mipmap-*/*.png` | From the termux-x11 project (GPLv3), vendored with the display module. |

None of these rasters were generated. The app's own mark (`drawable/ic_aka_mark.xml`) is a
vector drawn for AkaTerminal.

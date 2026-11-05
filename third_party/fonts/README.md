# Bundled fonts

The app's fonts in `app/src/main/res/font/` are static instances of two variable fonts, both under the SIL Open Font License 1.1 (texts in this folder).

| File | Source | Instance |
|---|---|---|
| `archivo_regular.ttf` | Archivo[wdth,wght].ttf, google/fonts `ofl/archivo` | wght 400, wdth 100 |
| `archivo_medium.ttf` | same | wght 500, wdth 100 |
| `archivo_semibold.ttf` | same | wght 600, wdth 100 |
| `archivo_semiexpanded_semibold.ttf` | same | wght 650, wdth 112 |
| `archivo_expanded_extrabold.ttf` | same | wght 800, wdth 125 |
| `jetbrains_mono_regular.ttf` | JetBrainsMono[wght].ttf, google/fonts `ofl/jetbrainsmono` | wght 400 |
| `jetbrains_mono_semibold.ttf` | same | wght 600 |

Made on 2026-11-05 with fontTools: `fonttools varLib.instancer --static <font> wght=… wdth=…`, then `pyftsubset --layout-features='*' --unicodes='U+0000-024F,U+0300-036F,U+2000-206F,U+20AC,U+2122,U+2190-21FF,U+2212,U+2318,U+2325,U+2387,U+238B,U+23CE,U+2500-257F,U+25A0-25FF,U+2713'` (Latin, Latin Extended-A/B incl. Turkish, punctuation, arrows, box drawing, geometric shapes).

Under OFL the instances are Modified Versions; they keep the upstream names and copyright notices in their `name` tables and are distributed only inside the app.

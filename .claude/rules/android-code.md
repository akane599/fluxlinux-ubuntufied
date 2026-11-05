---
description: Kotlin and Jetpack Compose conventions
paths:
  - "**/*.kt"
  - "**/*.kts"
  - "app/src/main/res/**"
---
- Kotlin official style, trailing commas, `val` by default, sealed interfaces for state/events, no `!!` outside tests.
- Keep this project's architecture: single-activity Compose, singleton `object`s exposing `StateFlow` (no ViewModels, no DI). Don't introduce a new framework or dependency to restructure code unless the task asks for it.
- Composables: stateless where practical; `modifier: Modifier = Modifier` as the first optional param; UI state is a `val`-only data class. `@Immutable`/`@Stable` only where the compiler can't infer it, and only if true.
- Design tokens: use `MaterialTheme.colorScheme/typography/shapes` and `AkaTheme.inks` (the risograph system in DESIGN.md: plates, marks, the Federal hairline); no new `Color(0x…)` in screen files; follow a file's existing local layout scale instead of scattering raw `.dp`. Designing or restyling UI: load the `compose-design` skill first.
- User-visible text in `strings*.xml`. New pure logic gets a JVM unit test with fakes, not mocks.
- XML Views exist only in legacy code: ViewBinding, lifecycle-aware observers; no `findViewById`.
- Never run `su`, blocking root probes or rootfs file I/O on the main thread (`RootShell` probes can block for seconds; `ChrootDetection` on main is cache-only).
- Symlinks: recursive delete/copy/walk/chmod must go through `core/system/SafeFiles` (NOFOLLOW); never `File.deleteRecursively`/`copyRecursively`/`walk*`/`listFiles` recursion (`SymlinkSafetyContractTest` enforces it). Guest homes hold `~/storage/*` links to shared storage. Tests build links only inside `Files.createTempDirectory` fixtures pointing at temp victims, never at `user.home`, `/storage`, `/sdcard` or real dirs, and clean up only via `SafeFiles.deleteTree`.

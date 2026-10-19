---
description: Kotlin and Jetpack Compose conventions
priority: 10
---
- Kotlin official style, trailing commas, `val` by default, sealed interfaces for state/events, no `!!` outside tests.
- Composables: stateless; `modifier: Modifier = Modifier` first optional param; state hoisted to a ViewModel exposed as `StateFlow`, collected with `collectAsStateWithLifecycle`.
- Stability: UI state is a `val`-only data class; collections in state and composable params are `ImmutableList`/`ImmutableMap`, never `List`/`Map`. `@Immutable`/`@Stable` only where the compiler can't infer it, and only if true.
- Design tokens only: `MaterialTheme.colorScheme/typography/shapes` and `MaterialTheme.spacing`; no `Color(0x…)`, raw `.dp`/`.sp` in screen files. Designing or restyling UI: load the `compose-design` skill first.
- In newly scaffolded apps every screen-level composable gets `@PreviewTest` previews: light, dark, fontScale 1.5, widthDp 840.
- User-visible text in `strings.xml`. New ViewModel / use case / repository → unit test with fakes, not mocks.
- XML Views exist only in legacy code: ViewBinding, lifecycle-aware observers; no `findViewById`.

- Adopted projects keep their existing architecture, collection types, DI, formatter and screenshot framework. Apply kit conventions to new work where compatible; adopting a new dependency or migrating an existing pattern needs a scoped ticket. Missing `@PreviewTest` support is not permission to install a new plugin.

# Android bug checklist (review-audit tickets: bound reviews and /bug-hunt)

Hunt for bugs a real user would hit, not style. Every finding needs a code path as evidence.

## A. Concurrency and coroutines
- Work launched in the wrong scope: `GlobalScope`, a hand-made `CoroutineScope` nobody cancels, `viewModelScope` work that must outlive the screen.
- Cancellation swallowed: `catch (e: Exception)` or `runCatching` around suspend calls eats `CancellationException`.
- Blocking IO or heavy work on the main thread; disk or network without `Dispatchers.IO`; `runBlocking` in app code.
- Mutable state shared between coroutines without a `Mutex`, atomics or confinement.
- Flows collected outside `repeatOnLifecycle` / `collectAsStateWithLifecycle`; `stateIn`/`shareIn` with the wrong `started` policy.

## B. Lifecycle and state
- State lost on rotation, process death or back navigation where the user would notice (no `SavedStateHandle` / `rememberSaveable`).
- Context, Activity or View leaks: held by ViewModels, singletons, companion objects or long-lived callbacks.
- Listeners, receivers and callbacks registered without a matching unregister.
- Compose: side effects outside `LaunchedEffect`/`DisposableEffect`; effect keys that go stale or restart too often; state reads that loop or miss updates.

## C. Errors and edge cases
- Silent failures: empty catch blocks, errors logged but never shown, `null` for failure that no caller handles.
- Kotlin/Java boundary: platform types assumed non-null, `!!` on values that can be null, `lateinit` read before it's set.
- Empty, huge, duplicate or out-of-order data; pagination edges; off-by-one.
- Time, locale, units: time zones and DST, `SimpleDateFormat` across threads, locale-dependent parsing, RTL.
- Network: no timeout, retries without backoff, offline paths, partial responses, HTTP errors treated as success.

## D. Data and persistence
- Room: missing migrations (or destructive fallback on user data), main-thread queries, multi-step writes outside a transaction.
- DataStore/SharedPreferences: two instances on one file, `commit()` on the main thread, reads assumed instant.
- Serialization: no defaults for new fields, enums that crash on unknown values, nullability that disagrees with the server.

## E. Platform and security
- Exported components, intent extras and deep links trusted without validation; `PendingIntent` mutability flags.
- Runtime permissions: missing checks, denial and "don't ask again" paths.
- Secrets or personal data in logs, crash reports or plaintext storage; WebView with JavaScript and file access; cleartext traffic.
- APIs above `minSdk` without a version check.

## Report format (your closing body)
Worst first. For each finding:
- **Severity** crash / data loss / wrong behaviour / leak / minor · **Confidence** high / medium
- `file:line` and the code path that leads there
- **Scenario:** the steps or input that trigger it, and what the user sees
- **Test:** the unit or Robolectric test that fails today (name, and what it asserts), or "device only" and why
- **Fix:** the smallest safe change

End with the areas you checked and found clean, and what you couldn't check (needs a device, a backend, real data).
Medium confidence is fine; a guess without a code path is not a finding.

# Android, Kotlin/Java & Shizuku/Binder Audit Checklist

When auditing an Android or privileged system repository, check every subsystem against these domain-specific failure modes:

## 1. Kotlin Coroutines & Structured Concurrency Traps
- **`runCatching` / Generic `catch (e: Exception)` Swallowing `CancellationException`:**
  - Search (`rg "runCatching|catch\s*\(\s*\w+\s*:\s*(Exception|Throwable)"`) inside `suspend` functions or coroutine builders.
  - If `CancellationException` is caught and not rethrown (`if (e is CancellationException) throw e`), parent coroutine cancellation is silently broken, causing zombie background loops and leaked resources.
- **Dispatcher Starvation & Blocking Calls:**
  - Verify that `Process.waitFor()`, synchronous `IBinder.transact()`, file I/O, and heavy `PackageManager` queries never run on `Dispatchers.Main` or `Dispatchers.Default`.
  - Check if long-running blocking shell/root streams use `Dispatchers.IO` (or a dedicated bounded dispatcher so they don't starve standard `Dispatchers.IO` threads).
- **GlobalScope & Unscoped Coroutines:**
  - Flag `CoroutineScope(Dispatchers.IO)` created inside repositories, services, or views without an attached `SupervisorJob()` and explicit `.cancel()` tied to component destruction.
- **Flow Lifecycle Leaks:**
  - In UI layers, verify `StateFlow`/`SharedFlow` collection uses `repeatOnLifecycle(Lifecycle.State.STARTED)` or `collectAsStateWithLifecycle()` rather than raw `lifecycleScope.launch`.

## 2. Shizuku, Binder IPC & Privileged Shell Execution
- **Binder Death & Stale Remote References:**
  - Every `IBinder`, `ShizukuBinderWrapper`, or `Shizuku.UserService` connection can die at any moment if the Shizuku daemon or system server restarts.
  - Check that `RemoteException` / `DeadObjectException` / `SecurityException` are caught at every IPC boundary, stale binder caches are invalidated via `linkToDeath`, and state degrades gracefully instead of crashing the app.
- **Shizuku Permission & Lifecycle Races:**
  - Verify `Shizuku.pingBinder()` is checked *before* checking `Shizuku.checkSelfPermission()`.
  - Ensure `OnRequestPermissionResultListener`, `OnBinderReceivedListener`, and `OnBinderDeadListener` are symmetrically removed in `onDestroy()` / `onCleared()` to prevent memory leaks.
- **1MB Binder Buffer Exhaustion (`TransactionTooLargeException`):**
  - Flag any IPC/AIDL call or `Intent` extras passing unbounded lists (e.g., all installed `PackageInfo`/`ApplicationInfo` objects with signatures/permissions, or raw logs/bitmaps). Verify pagination, `ParceledListSlice`, or file/FD-backed transfer is used.
- **Linux 64KB Pipe Buffer Deadlock in Shell/Process Execution:**
  - When spawning `ProcessBuilder` / `Runtime.exec` / `Shizuku.newProcess`, if the child process writes >64KB to `stderr` while the parent thread is blocking on reading `stdout` (or running `process.waitFor()` before draining streams), the child process **permanently deadlocks**.
  - Verify `redirectErrorStream(true)` is used OR `stdout` and `stderr` are drained concurrently on separate threads/coroutines, with mandatory process timeouts (`waitFor(timeout, unit)` + `destroyForcibly()`).
- **Shell Injection in Privileged Commands:**
  - Trace every string interpolated into `sh -c ...` or root/Shizuku commands (e.g., package names, file paths, UIDs, user labels). Even package names or file paths from untrusted directories must be validated against strict regex or escaped via `ShellQuote` before running with `adb`/`shell`/`root` privileges.

## 3. Android Component Lifecycles, Memory & OS Restrictions (API 33–36)
- **Context & View Leaks:**
  - Singletons, companion objects, or `UserService` instances holding Activity/View `Context` instead of `applicationContext`.
- **BroadcastReceiver & Service Registration Parity:**
  - Every `registerReceiver` / `bindService` must have a guaranteed `unregisterReceiver` / `unbindService` in the matching lifecycle teardown—even if an exception occurs during initialization.
  - Check Android 14+ (`UPSIDE_DOWN_CAKE`) compliance: `Context.registerReceiver` calls for non-system broadcasts must specify `Context.RECEIVER_EXPORTED` or `Context.RECEIVER_NOT_EXPORTED`.
- **Foreground Services, WakeLocks & Doze Hygiene:**
  - Every `PowerManager.WakeLock.acquire()` must specify a timeout (`acquire(timeoutMs)`) and be released in a `finally` block.
  - Check Android 14+ mandatory `foregroundServiceType` declarations in `AndroidManifest.xml` matching runtime `ServiceCompat.startForeground()` calls.
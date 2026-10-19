---
description: Android build, test and dependency rules
priority: 20
---
- Gradle: always preserve its exit code. From the checkout root use `bash .claude/kit/gradle-check.sh <tasks>` (full log retained, last 80 lines on failure), or raw `./gradlew <tasks> --console=plain -q` if the helper is absent. Never use a grep/head pipeline as the pass/fail oracle.
- Verify with the narrowest task. `:app` has no plain `Debug` variant, so name the flavor: `bash .claude/kit/gradle-check.sh :app:testIvarnaDebugUnitTest --tests '<Class>'`; UI changes add `:app:validateIvarnaDebugScreenshotTest` (only UI tickets update refs, via `:app:updateIvarnaDebugScreenshotTest`).
- Batch the necessary Gradle tasks per change-set rather than running per file. Rerun relevant checks after fixes or to resolve a concrete failure; this batching guideline is not a one-run lifetime cap.
- Before Gradle verification, run `python3 .claude/kit/resource-policy.py` and use its current `gradle_command_limit` and `gradle_workers_per_command` (pass `--max-workers=N`). This supersedes fixed kit concurrency defaults. Count active Gradle commands machine-wide across Claude/Codex projects and worktrees, not tickets or idle daemons. Queue only verification when capacity is occupied; continue independent implementation/review/research within existing worker and ownership limits. Low memory headroom temporarily defers new heavy builds, never all ticket dispatch; recheck before launching. This is advisory coordination, not an enforced semaphore. Do not automatically edit Gradle JVM heaps, swap, OS settings, model routing or agent counts. Saved resource-policy.json is diagnostic only: always re-probe on the current machine.
- Never bump compileSdk/targetSdk, AGP, Gradle, Kotlin or the JDK target, and never add a dependency, without it being in the ticket. For new apps use `gradle/libs.versions.toml`; adopted projects keep their existing catalog/build-logic/versioning layout.
- The SDK comes from `ANDROID_HOME`. Don't create or edit `local.properties`.
- Never read, print or commit keystores (`*.jks`, `*.keystore`), `local.properties`, `google-services.json`, `.env*`, `.claude-plugin-config.json`.
- Library APIs: check context7 instead of recalling from memory.

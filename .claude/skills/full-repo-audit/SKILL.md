---
name: full-repo-audit
description: Perform an exhaustive, staff-level architectural, security, concurrency, performance, and correctness audit across the entire repository (not a diff or PR). Uses shell execution, git forensics, static analyzers, ripgrep, and cross-file call-graph tracing to find real, systemic bugs.
---

## Standalone review mode

Use this workflow with Sidequest disabled. If Sidequest is active, stop before audit dispatch and ask the user to enter review mode from an ordinary terminal, then fully restart Claude. Do not disable hooks inside an active run or dispatch Sidequest tickets for this workflow. `/bug-hunt` remains the Sidequest-native investigation command.

Read `references/blind-audit.md` before project summaries, then `references/orchestration.md`. Use the current native session configuration; these skills do not pin models or require provider diversity. Keep any gateway needed by the chosen kit enabled. Treat domain checklists as investigation prompts, confirm each claim against actual code/API versions, and redact credentials.

# Whole-Repository Principal Audit Protocol

You are conducting a **complete, ground-up audit of the entire repository**—not a PR or commit review.
Use tools permitted by the user, project policy and current environment: shell execution, compilers, typecheckers, linters, `rg` (ripgrep), `fd`/`find`, `git` history analysis, AST search, and parallel sub-agents.

## Core Anti-Slop Rules (Read First)
1. **Map before auditing:** Map the full repository topology first, then audit every subsystem systematically; don't infer how the rest of the app works from a few files.
2. **Skip linter territory:** Don't report formatting, naming nitpicks or missing comments; run static analysis for style and concentrate on **logic bugs, broken invariants, race conditions, resource leaks, architectural coupling and security flaws**.
3. **Verify Before Reporting (Zero Hallucinations):** Never report a "potential" issue without tracing its callers and callees across the codebase using `rg` or LSP/AST tools. If a function looks unsafe, check whether upstream callers already sanitize input or hold a lock.
4. **Prove Every Finding:** Every reported issue MUST include:
   - Exact `path/to/file.ext:line_start-line_end`
   - The full cross-file execution/call trace that triggers the bug
   - A concrete failure or exploit scenario (what actually breaks in production)
   - A production-ready code fix or architectural remediation

---

## Phase 0: Isolation, integrity and report preservation

Follow `references/blind-audit.md` in full. Establish the contamination ledger before reading PROJECT/MAP/PROGRESS, history or prior findings. Required instructions still apply; prior exposure cannot be undone by promising to ignore it. Strict blindness requires a fresh eligible investigator context or an explicit limitation before proceeding.

Run the bundled audit guard's `begin` action, preserve its session path, and keep all application/skill/kit files unchanged. Never run kit maintenance or remediation during an audit. The original report stays in place until guarded publication.

## Phase 1: Current-source reconnaissance

Inventory manifests, entry points, current source/tests and build configuration with the bundled `scripts/repo-recon.sh`, then map source subsystems afresh. Restrict subsequent searches to explicit application source roots. Do not load generated project maps, board summaries, previous findings or commit history before freeze. The filtering helper may omit legitimate audit-named application files; record coverage gaps and inspect explicitly approved current-source paths without broadening to prior assessment artifacts.

## Phase 2: Deterministic Tooling Baseline

Do not guess what the compiler or analyzer already knows. Run the project's native verification tools in non-destructive/check mode (as applicable to the stack):
- **Typecheckers & Compilers:** `tsc --noEmit`, `./gradlew lintDebug`, `cargo check` / `cargo clippy`, `mypy` / `pyright`, `go vet`.
- **Dependency & Security Scanners:** Check for outdated/vulnerable packages, broad permissions, or exposed secrets.
- **Test Suite Execution:** Run the existing test suite once to see if tests pass, fail, or hang, and identify which modules have zero test coverage.

Capture any real errors or critical warnings from these tools to correlate with your manual deep dive.

---

## Phase 3: Deep-Dive Subsystem Audit (Vertical Slices)

- If `build.gradle*` or `AndroidManifest.xml` is present, instruct every subsystem sub-agent (especially IPC, app, and feature agents) to also enforce `references/android-kotlin-shizuku.md`.
- Coordinate one shared baseline in Phase 2 and pass its outcomes to subsystem workers. Workers must not duplicate it. Any additional verification needed to confirm a finding follows the Gradle verification policy in `references/orchestration.md`; queue only that verification step, never independent audit dispatch. Required device checks without a connected test device remain explicitly unverified.

Divide the codebase into logical subsystems (e.g., Entry/UI layer, Domain/State Engine, Persistence/IPC/Network, Background Workers/Services, Native/Shell bridges). If your environment supports **sub-agents**, dispatch parallel sub-agents per subsystem or per audit dimension below. Otherwise, iterate through every subsystem sequentially across these **6 Failure Domains**:

### 1. Architecture, State Lifecycle & Coupling
- **Source-of-Truth Violations:** Look for duplicated state that can desynchronize (e.g., in-memory caches out of sync with disk/DB/preferences, or UI state diverging from service state).
- **Lifecycle Mismatches:** Identify objects holding references to shorter-lived contexts, callbacks/listeners/observers registered without symmetric unregistration, or background jobs outliving their parent scope.
- **Boundary Leaks:** Domain logic leaking into UI/views, circular module dependencies, or God classes managing unrelated responsibilities.

### 2. Concurrency, Asynchrony & Resource Management
- **Race Conditions & Shared Mutable State:** Search for unsynchronized collections, global singletons, or fields accessed across threads, coroutines, or async tasks (`rg` for shared maps/lists, static state, locks, mutexes, dispatchers, thread pools).
- **Cancellation & Orphaned Work:** Async tasks, coroutines, threads, or child processes that ignore cancellation signals or continue mutating state after the caller has timed out or been destroyed.
- **Resource Leaks:** Unclosed file descriptors, streams, database cursors, sockets, wake locks, or shell processes—especially inside error/exception branches (`try` without `finally` / `use` / `with` / RAII).
- **Main/Event-Loop Blocking:** Synchronous disk I/O, network calls, heavy serialization, orIPC/shell calls executing on the main/UI thread or main event loop.

### 3. Security, Privilege & Trust Boundaries
- **Command & Code Injection:** Trace every sink that executes shell commands, root/privileged helpers, dynamic SQL, reflection, or deserialization (`Runtime.exec`, `ProcessBuilder`, `sh -c`, `subprocess`, `eval`, raw SQL queries). Verify whether user, file, or IPC inputs are strictly parameterized/escaped.
- **IPC / Deep-Links / Exported Components / API Endpoints:** Audit every entry point accessible from outside the process (exported services/receivers/activities, HTTP endpoints, IPC binders, sockets, file watchers) for missing caller authentication, permission checks, or path-traversal vulnerabilities.
- **Secrets & Cryptography:** Hardcoded API keys/tokens, insecure random number generators, plaintext storage of sensitive data, or overly permissive file/directory modes.

### 4. Error Handling, Edge Cases & Resilience
- **Silent Failures:** Find every swallowed exception (`catch` blocks that only log or ignore errors) where the caller continues assuming the operation succeeded.
- **Partial Failure & Corrupt State:** Multi-step mutations (e.g., write file + update DB + update memory) that lack rollback/atomicity if step 2 fails or the process crashes mid-flight.
- **Boundary & Null Assumptions:** Force-unwraps (`!!`, `.unwrap()`), unchecked array indexing, integer overflow/underflow, timezone/locale-sensitive parsing bugs, and empty/malformed payload handling.
- **Missing Timeouts:** Network requests, IPC calls, lock acquisitions, or child process executions with infinite wait times that can permanently wedge the app.

### 5. Performance, Memory & Power Efficiency
- **Algorithmic Bottlenecks:** $O(N^2)$ lookups inside loops (e.g., repeated linear scans or regex compilation inside loops, N+1 database/IPC queries).
- **Memory Churn & Unbounded Growth:** Unbounded caches/logs/queues that grow forever in long-running sessions, or heavy object allocations inside hot loops/render frames.
- **Wakeups & Polling:** Busy-wait loops, overly aggressive polling intervals, or redundant background wakeups that drain battery/CPU when idle.

### 6. Test Suite Integrity & Blind Spots
- **Tautological Tests:** Tests that only assert that a mock returned what it was told to return, without testing real logic.
- **Critical Blind Spots:** Cross-reference the current-source subsystem inventory from Phase 1 against test coverage—identify the highest-risk core modules that lack failure-path and concurrency tests.

---

## Phase 4: Cross-Reference & False-Positive Filtering

For every candidate issue discovered in Phase 3:
1. Run `rg` to find **all call sites** of the affected function/class across the entire repo.
2. Verify whether the bug is actually reachable in real execution flows.
3. Discard theoretical nitpicks, dead code (unless reporting dead code for removal), or issues mitigated by an upstream wrapper.
4. Group related symptoms under their single **root architectural cause** rather than listing 10 repetitive symptoms.

---

## Phase 5: Freeze independent findings, compare, then publish

Write the independent report to the guard session’s `draft.md` using the structure below. Freeze it with the guard before reading any prior report or deferred summary. Then create a separate delta and publish with the guard as described in `references/blind-audit.md`. Respect an existing lowercase `audit_report.md`; do not create a competing report. Summarize the published result to the user.

### 1. Executive Summary & System Health Scorecard
- **Architecture & Modularity:** [Score /10 + 1-sentence justification]
- **Correctness & Resilience:** [Score /10 + 1-sentence justification]
- **Concurrency & Resource Safety:** [Score /10 + 1-sentence justification]
- **Security & Privilege Hygiene:** [Score /10 + 1-sentence justification]
- **Performance & Efficiency:** [Score /10 + 1-sentence justification]
- **Top 3 Systemic Risks:** The three most dangerous architectural or correctness flaws in the repo right now.

### 2. Confirmed Findings (Ordered by Severity: CRITICAL -> HIGH -> MEDIUM -> LOW)
For **each** finding, use this strict template:
- **[ID] [SEVERITY] Title of the Issue**
  - **Category:** (Security | Concurrency | Resource Leak | Logic Bug | Architecture | Performance)
  - **Location:** `path/to/file.ext:line_start-line_end` (plus related caller files)
  - **Confidence:** Confirmed (Verified via call-trace / reproduction)
  - **Root Cause & Execution Trace:** Step-by-step explanation of how execution reaches this state across files, quoting the exact offending code snippet.
  - **Failure / Exploit Scenario:** Exactly what happens in production when this triggers (crash, deadlock, data loss, privilege escalation, battery drain).
  - **Concrete Fix:** Exact drop-in replacement code or diff showing how to fix it properly.

### 3. Dead Code, Redundancy & Technical Debt Cleanup
- List unused classes/functions/dependencies, duplicate utilities, and stale `TODO`/`FIXME` traps verified as safe to remove or consolidate.

### 4. Prioritized Remediation Roadmap
- **Phase 1: Immediate Hotfixes (Stop the Bleeding):** Critical crashes, leaks, security holes, and data-corruption bugs that can be patched in isolated commits.
- **Phase 2: Structural & Concurrency Hardening:** Lifecycle fixes, synchronization overhauls, and error-propagation improvements.
- **Phase 3: Architectural Refactoring & Test Coverage:** Decoupling modules and high-value integration tests to prevent regressions.

Include a coverage ledger listing subsystems examined, tools actually run, unavailable checks and remaining gaps. Scores are qualitative judgments, not measured guarantees. Never claim exhaustive coverage when access, time, tooling or device evidence is missing. Audit mode writes only its report/evidence; proposed application fixes belong in the report until remediation is requested.

### 5. Audit isolation and delta
Include the contamination ledger, source revision/state, input exclusions, fresh-context availability and freeze evidence. Append the separately labeled post-freeze Audit Delta. Keep post-freeze discoveries out of independent findings. Report protected-file integrity failures prominently; do not claim successful completion if audit skills or kit files changed.

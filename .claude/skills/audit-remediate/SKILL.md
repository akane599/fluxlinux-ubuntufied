---
name: audit-remediate
description: Systematically fix findings from AUDIT_REPORT.md using atomic git commits and mandatory build/test verification loops. Never batches unrelated fixes or leaves the build broken.
---

## Standalone review mode

Use this workflow with Sidequest disabled. If Sidequest is active, stop before audit dispatch and ask the user to enter review mode from an ordinary terminal, then fully restart Claude. Do not disable hooks inside an active run or dispatch Sidequest tickets for this workflow. `/bug-hunt` remains the Sidequest-native investigation command.

Read `references/orchestration.md` before starting. Use the current native session configuration; these skills do not pin models or require provider diversity. Keep any gateway needed by the chosen kit enabled. Treat domain checklists as investigation prompts, confirm each claim against actual code/API versions, and redact credentials.

# Atomic Audit Remediation Protocol

You are an execution-focused Principal Engineer tasked with resolving confirmed issues documented in `AUDIT_REPORT.md`.

## Core Safety Rules
1. **Never Batch Unrelated Fixes:** Fix one finding ID (or one tightly coupled root-cause group) at a time.
2. **Compile & Test Gate:** Run the agreed relevant compile/lint/regression checks per finding and the full build/test gate after the batch. A finding remains verification-pending until its required checks pass; batch completion requires the full gate.
3. **Bounded Recovery:** If the current fix still fails after 2 repair attempts, revert only changes introduced by this finding using its recorded patch or isolated clean worktree. Never use blanket checkout/reset/clean commands or discard pre-existing/user/other-agent edits. If precise recovery is uncertain, preserve the work and mark the finding BLOCKED with the failing command and reason. Verification is evidence, not a zero-regression guarantee.

## Step-by-Step Execution Loop

Do not remove or relocate audit/remediation skills, kit metadata or project instructions as cleanup. Audit infrastructure changes require a separate explicit request.

### 1. Preparation & Triage
1. Read `AUDIT_REPORT.md`.
2. Inspect `git status` and record the baseline. Preserve dirty work; do not stash/reset it implicitly. Create a unique remediation branch/worktree without overwriting any existing branch. If isolation is unavailable, stop before editing overlapping dirty files.
3. Unless the user specified particular Finding IDs (e.g., `"Fix CRIT-01 and HIGH-02"`), sort all confirmed findings by:
   - **Priority 1:** `CRITICAL` & `HIGH` fixes that are self-contained (crashes, leaks, security holes, concurrency bugs).
   - **Priority 2:** `MEDIUM` correctness and performance fixes.
   - **Priority 3:** Dead code removal and structural refactors.

### 2. Per-Finding Fix Cycle (Repeat for Each Target Finding)
Run this cycle concurrently for all independent ready findings in separately owned worktrees. Priority orders dispatch when native capacity is full; it is not a barrier between independent findings. Preserve the ordered steps within each finding and serialize shared report/integration writes.

For each finding `[ID]`:
1. **Re-Verify Context:** Read the target file(s) and run `rg` on every symbol you plan to modify to ensure no callers are broken by signature or behavior changes.
2. **Implement the Fix:** Apply the minimal, production-grade code change that resolves the root cause (not a superficial symptom band-aid).
3. **Add/Update Regression Test:** Where feasible, add a unit test that reproduces the failure condition and proves the fix works.
4. **Run the Verification Gate:**
   - Execute the narrowest agreed compile/lint/regression commands that exercise this finding. Reserve the full project suite for the batch gate unless the change requires it earlier.
   - If the build or tests fail, analyze the compiler/test output and fix the regression immediately.
5. **Atomic Commit:**
   - Once the required checks pass, if committing is authorized, stage only the relevant files and commit with a structured message:
     `git commit -m "fix(<scope>): resolve [ID] - <concise summary of root cause fixed>"`
6. **Update `AUDIT_REPORT.md`:**
   - Have the report owner record `[FIXED in <short-commit-sha>]` only after successful verification and an actual commit. If committing is not authorized, record `VERIFIED, UNCOMMITTED`; if required checks cannot run, record `VERIFICATION PENDING`. Keep report changes tracked in a separate report-update commit when authorized; parallel workers must not edit the shared report.

### 3. Final Summary
After completing the requested batch of fixes, run the full build/test suite one final time and output a summary table of:
- `[ID]` | Commit SHA | Files Modified | Verification Command Run | Status (`FIXED` / `SKIPPED` / `BLOCKED`)

Run the narrowest relevant regression checks per finding; run the agreed full build/test gate once after the requested batch. Preserve exit codes. Missing tooling/devices or pre-existing failures must be reported separately; do not call a fix fully verified while required checks are unavailable. Coordinate Gradle runs under references/orchestration.md without blocking unrelated review work.

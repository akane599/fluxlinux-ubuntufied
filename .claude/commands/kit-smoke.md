---
description: Create and run repeatable Android smoke flows with evidence tied to the source revision
argument-hint: "[setup|run] [device serial]"
---
Inspect the actual applicationId, variant, onboarding state and accessibility labels.
Use `android-kit-manager addons --project "$PWD" --app-id <actual-id> --ready-text <stable-label>`
to create two launch/relaunch starter flows. Existing customized flows must be preserved.
Install Maestro only when requested, following https://docs.maestro.dev/maestro-cli.
Expand the flows to verify this project's actual critical feature, permissions denied/granted,
back navigation and persistence. Use stable selectors; do not invent UI or claim starter flows
cover these cases. Do not clear app data, change permissions or uninstall without task authorization.
Build/install the intended APK using this project's existing Android QA procedure.
Reserve one explicit adb serial for one device owner. Pause Mobile MCP/emulator QA interaction
while running `android-kit-manager smoke --project "$PWD" --device <serial>`.
The runner serializes manager smoke runs for that serial across projects and writes local
results under .claude/kit/evidence. This lock does not intercept third-party adb/MCP callers.
Record the APK hash, build variant, device API, acceptance criteria and screenshot paths alongside
result.json. Its commit/diff hashes do not include untracked app code or prove APK identity.
For CI, run the same flows against a prepared emulator/installed APK; review artifacts before upload.

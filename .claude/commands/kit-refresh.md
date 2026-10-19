---
description: Verify the selected project kit, scopes, routes and agent registration after merges; optionally update only this project's Toolshed plugins
argument-hint: "[--update-toolshed]"
---
Run inline without paid inference, nested Claude sessions or hook bypasses. Read kit_home,
kit_variant and gateway_enabled from .claude/kit/state.json. Read kit.json in that installed kit.

1. Inspect conflicts.json and `claude plugin list --json`. Prefer this project's project-scope
   install; local copies are migration conflicts. Never select another project's installation.
2. Only with explicit --update-toolshed, refresh the eigenwise-toolshed marketplace, then run
   `claude plugin update <id> --scope project` for the selected project's enabled Toolshed IDs.
   Never enable or install a missing plugin during refresh. Include model-gateway only if
   gateway_enabled is true. Gateway runtime updates may affect a shared process; report that
   scope. Do not call a broad cross-project updater. Re-read installed roots after updates.
3. Run `python3 <KIT_ROOT>/lib/project-settings.py check "$PWD" <KIT_ROOT>`.
   Use merge-settings.py --audit-hooks across user/shared/local settings; report overlap metadata,
   not credentials or command values. Plugin/managed hooks require separate /hooks inspection.
4. Sync live-rules with its installed scripts/sync-atomic-rules.js --project "$PWD".
   If gateway_enabled is true, run its installed `doctor` and `catalog --refresh --json`;
   inspect exit codes/readiness, not just old printed catalog data. Do not log in, change wiring
   or restart the gateway implicitly. If false, do not call gateway commands.
5. For stock run sidequest-defaults.py <SIDEQUEST_CLI> check; for other sets run
   routing-profile.py <SIDEQUEST_CLI> <KIT_ROOT>/lib/routing-profile.json check.
   Inspect actual models --full --json, board/ticket overrides and unavailable routes.
   Never retarget active attempts or reset profiles during refresh.
6. Verify both project audit skills and their referenced files exist and match reviewed template updates.
   Confirm superseded audit/review plugins are disabled at project scope. Inspect actual registered Agent types separately.
   A file/catalog row is not proof of loaded registration. Mark unavailable checks pending and
   use /agents after a full restart. Never generate per-model agents or alter spawn model fields.
7. Report merge blockers, scopes, hook coverage, rule sync, transport, routes, on-disk definitions
   and loaded types separately. Fully restart after changed plugins/commands/rules; then /kit-doctor.
   Do not claim account authentication, quota, paid inference or Remote Control was tested.

8. If android-kit-manager is available, run `android-kit-manager status --project "$PWD"`.
   Keep version-drift and pending loaded-agent checks visible. After an explicitly accepted
   update and successful checks, record the new baseline with `android-kit-manager lock --project "$PWD"`.
   Do not overwrite the baseline just to silence drift.

9. Check CLAUDE.md and customized commands for references to retired plan-audit,
   pr-review-toolkit, code-review, claude-security and security-sweep workflows. Report stale
   guidance for a reviewed edit; never invoke retired commands as an automatic fallback.

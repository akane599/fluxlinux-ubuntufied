---
description: Read-only inspection of the selected kit, project scope, gateway choice and model routing
---
Run inline. No installation, enablement changes, imported hooks or inference.

1. Read .claude/kit/state.json, conflicts.json and installed kit.json. Report variant, project
   scope, gateway choice and pending template merges. Detect stack using the project kit helper.
2. Run project-settings.py check "$PWD" <KIT_ROOT>. Do not print credentials/environment values.
   Inspect user/project/local precedence and managed/shell overrides; do not silently edit them.
3. Inspect installed project-scope plugins and competing namespace masks. Local kit registrations
   are migration conflicts. Model Gateway may remain installed for other projects; its enablement
   must match this project's selected policy. Run the manager for installation/migration repairs.
4. Use sidequest-defaults.py check for stock, routing-profile.py check for other variants.
   Compare actual routes, fallbacks and effects of board/ticket overrides. Preserve active work.
   Stock follows upstream coding defaults without kit category edits; other sets use their exact
   routing-profile.json. Never infer provider participation from a model alias alone.
5. Audit hook overlap metadata with merge-settings.py --audit-hooks; inspect /hooks for plugin
   sources. Distinguish four separate hook events from duplicate execution.
6. Check full-repo-audit and audit-remediate project skill discovery and ensure superseded review plugins are disabled.
   Report loaded-state checks as pending when unavailable. Use /kit-refresh after kit merges.
7. Return PASS/WARN/BLOCKED with specific actions. Gateway-backed sets need gateway readiness;
   gateway-off sets must not start it. Remote Control still requires compatible native transport,
   account login and runtime eligibility. No account/quota/inference guarantee follows from files.

## Machine resources

Run `python3 .claude/kit/resource-policy.py` from the project root and report RAM/available memory, effective CPU capacity, swap, disk, KVM access, warnings and recommended Gradle limits. With `--fix-resources` in the user request, run the helper with `--apply` to refresh only its generated resource-policy.json. This flag authorizes that file write; all other doctor checks remain read-only. Re-run after host resizing or moving machines. Follow the current policy for subsequent Gradle commands; never treat it as a ticket/agent dispatch cap. Unsupported probes are unknown, not successful checks. Report conflicting customized rules for kit-merge instead of rewriting them. Do not run maintenance during a blind audit.

Check that escalation resolves to Opus/xhigh and code review to Sonnet/high. Report older model aliases, effort caps or environment overrides that lower effective effort; never claim requested effort proves actual runtime effort. Do not bypass organization limits.

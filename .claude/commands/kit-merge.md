---
description: Merge pending android-kit template updates into this project and record reviewed resolutions without losing customizations
argument-hint: "[optional: selected project-relative paths; default = all pending conflicts]"
---

Resolve the kit's pending template conflicts in this project. Scope: $ARGUMENTS
This is kit setup maintenance: run inline, not as Sidequest feature tickets. Do not
change plugin enablement, bypass hooks, scaffold the app, commit, push or run Gradle.

1. Read `.claude/kit/conflicts.json`, `.claude/kit/state.json` and relevant project
   instructions. List the selected pending paths. If none remain, run `/kit-refresh`
   without upgrade options and report that there is nothing to merge. Do not invent new conflicts.
2. Locate the installed kit from `ANDROID_KIT_HOME` when set, then the recorded `kit_home` in
   `.claude/kit/state.json`, otherwise `$HOME/.local/share/android-kit-claude-max`. It must contain `lib/project-files.py` with a
   `resolve` action and `template/`. Use absolute quoted paths. If unavailable,
   report that setup from Claude-only version 2026.10.16.1-Claude-only or newer is needed; do not edit
   manifests or clear conflict metadata as a substitute.
3. Before edits, use that helper's `snapshot <project-root> <kit-template>` action.
   For each selected path compare the current file with
   `.claude/kit/incoming/<project-relative-path>`. Verify the incoming copy matches
   the installed kit template. Earlier snapshots may explain project intent;
   treat them as historical evidence, not necessarily an original unmodified base.
4. Merge into the active project file, one file at a time. Preserve actual modules,
   flavor/task names, package IDs, SDK/build constraints, tokens, architecture and
   project conventions. Apply the incoming Claude-only routing and workflow changes.
   Remove superseded Claude-only UI rules and native Claude routes from converted
   workflows. Do not blindly copy all incoming files or drop a project customization
   just because the template is newer. When the differences are only stock kit
   changes, adopting the whole incoming file is appropriate after comparison.
   If two project requirements cannot be reconciled, leave that file pending and
   ask only about that specific decision while continuing other files.
5. Read the finished file and diff again. For Python, compile without executing
   (do not run scaffold.py against the existing app). For Markdown, verify YAML
   frontmatter and internal paths; use an available parser if present, otherwise
   state that YAML was inspected but not parser-validated. Check routing consistency
   across bootstrap, full-repo-audit, audit-remediate, ui-overhaul and android-orchestration.
   Preserve actual behavior and token/screenshot contracts in the other rules.
6. Only after each merge has been reviewed, record those exact files using the
   installed helper. For example, with the default kit location:

   ```bash
   python3 "$HOME/.local/share/android-kit-claude-max/lib/project-files.py" resolve \
     "$PWD" "$HOME/.local/share/android-kit-claude-max/template" \
     .claude/skills/full-repo-audit/SKILL.md
   ```

   Replace the example path with the files actually reviewed. The helper records
   template and merged hashes, removes only those pending entries and incoming
   copies, and never replaces the active files. Do not write manifest hashes by
   hand, set conflicts.json to [], or run resolve merely to suppress a warning.
   Unresolved files must remain pending. New edits or newer templates may require
   another review; an accepted merge is tied to the exact reviewed bytes.
7. Invoke `/kit-refresh` without upgrade options after resolving the selected files,
   including a partial merge. It syncs live-rules, checks native Claude routing, audits
   hook overlaps and checks Claude agent definitions/registration. If the command is not
   loaded yet, read its installed command file and follow it inline; do not bypass hooks.
   Report resolved files, preserved customizations, remaining conflicts and refresh limits.
   Keep existing transport/model blockers visible. Fully restart Claude Code after changed
   commands/rules/model rows, then run `/kit-doctor` in that fresh session. Do not claim
   already-loaded instructions or agents were replaced. Use `/kit-refresh --update-toolshed`
   only when the user explicitly requests a Toolshed upgrade; it can affect other projects.

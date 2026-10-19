#!/usr/bin/env python3
"""
android-kit PreToolUse hook (Agent): don't launch a map-writing Sidequest ticket read-only.

`codebase-exploration` is a read-only category, and Sidequest gives a ticket in it a writer only in its
shared-tree artifact mode: exactly one declared file under .claude/.codebase-info/, codebase-mapper's
lifecycle line on a line of its own in the description, and dispatch with sharedTree: true. Miss any of
the three and the spawn is a read-only executor (Edit and Write disallowed) that can't write the map.
This hook catches that spawn and says which condition is missing, so the orchestrator fixes the ticket
and dispatches again instead of burning a run.
"""
import json, re, sys

READONLY = re.compile(r"(^|:)sidequest-exec-(dispatch-readonly|readonly-)")
MAP_ROOT = ".claude/.codebase-info"
MARKER = ("Shared-tree artifact mode: leave the generated map as working-tree output; verify, comment, "
          "and close with done. Do not commit, submit, push, or edit source.")


def declared_files(prompt):
    m = re.search(r"^Declared files:[ \t]*\n((?:[ \t]*-[^\n]*(?:\n|$))+)", prompt, re.M)
    return [line.strip()[1:].strip() for line in m.group(1).splitlines() if line.strip()] if m else []


def main():
    try:
        data = json.load(sys.stdin)
    except ValueError:
        return
    if data.get("tool_name") != "Agent":
        return
    ti = data.get("tool_input") or {}
    if not READONLY.search(str(ti.get("subagent_type") or "")):
        return
    prompt = str(ti.get("prompt") or "")
    files = declared_files(prompt)
    writes_map = any((f.removeprefix("./").rstrip("/") == MAP_ROOT or f.removeprefix("./").startswith(MAP_ROOT + "/")) for f in files) or "Artifact write carve-out" in prompt
    if not writes_map:
        return
    problems = []
    if len(files) != 1:
        problems.append(f"it declares {len(files)} files; it needs exactly one, `{MAP_ROOT}/`")
    if not any(line.strip() == MARKER for line in prompt.splitlines()):
        problems.append("its description doesn't have the line below on a line of its own (not in a list item or code block)")
    if not problems:
        problems.append("it wasn't dispatched with `sharedTree: true`")
    reason = (
        "android-kit: this ticket writes the codebase map, but Sidequest prepared it read-only, so its executor "
        "can't write anything. A map ticket only gets a writer in Sidequest's shared-tree artifact mode: category "
        f"`codebase-exploration`, one declared file `{MAP_ROOT}/`, the codebase-mapper lifecycle line on its own "
        "line in the description, and dispatch with `sharedTree: true`. Here " + "; ".join(problems) + ".\n"
        f"Line: {MARKER}\n"
        "Fix the ticket with `update`, then dispatch it again with `sharedTree: true` and spawn exactly what that "
        "returns. (The codebase-mapper skills' Sidequest handoff has the full recipe; mapping inline also works.)"
    )
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))


if __name__ == "__main__":
    main()

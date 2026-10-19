---
description: End of a session or story. Turns what was corrected and decided into live-rules, CLAUDE.md facts and decision-log lines (only the ones you approve), files leftover side issues, refreshes the codebase map
---

Wrap up this session. Nothing is written without the user's yes.

1. **Collect** from this session only (the conversation, board activity, and `.claude/compact-checkpoint.md` if it was compacted):
   - corrections the user gave you or an executor, and anything they had to say twice;
   - build, SDK or tooling quirks you hit, and what fixed them;
   - decisions with a reason (why X over Y);
   - side issues that came up but weren't done.

2. **Sort** each into one place:
   - a convention or guardrail every session and executor should follow → live-rule via `add-rule` (one concern per rule; extend an existing rule rather than adding a near-duplicate);
   - a project fact (command, module, quirk, environment detail) → CLAUDE.md, in Commands or the STACK block; keep the file under 200 lines;
   - a decision → PROGRESS.md decision log: `- YYYY-MM-DD: X over Y, because …`;
   - a side issue → a Sidequest ticket, filed and not worked;
   - a personal preference that isn't about this project → auto memory handles it; if it wasn't saved, the user can say "remember that …";
   - a one-off → drop it.
   Skip anything the code, git history or an existing rule already says.

3. **Propose** a numbered list of at most 10 items: where it goes, then the exact line. Wait for the user to pick.

4. **Apply** only the picked items. If integrated code changed this session, run the codebase-mapper `update-codebase-map` skill (inline, or through its shared-tree Sidequest handoff exactly as written; a map ticket in any other shape spawns read-only).

5. **Report** what changed in up to 3 lines. If CLAUDE.md or the rules haven't been audited in a few weeks, suggest `/doctor prompt-audit` (Claude Code 2.1.283+), which finds stale or contradicting instructions.

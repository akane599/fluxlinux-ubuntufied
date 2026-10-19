---
description: Enter or restore temporary standalone review mode, preserving the selected gateway and project settings
argument-hint: "on|off"
---
Use `android-kit-manager review --project "$PWD" --mode on --idle` only after checking
that Sidequest has no active work and the user has requested standalone review. Stop
and report active work; do not kill or retarget attempts. The --idle flag is an explicit
operator assertion, not an automatic scheduler check. Run this from an ordinary terminal
if Sidequest blocks it. Do not bypass its hooks. Fully exit and restart Claude afterward.
Run the requested targeted review in the fresh session. Keep Model Gateway enabled for
GPT/hybrid. When finished, run `android-kit-manager review --project "$PWD" --mode off`,
restart Claude and run /kit-refresh. The restore merges only Sidequest enablement flags;
it preserves unrelated settings edited during review. Never claim a reload is a full restart.

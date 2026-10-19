---
description: Configure a pinned optional Mobile MCP and coordinate device ownership
argument-hint: "[exact version]"
---
Mobile MCP complements the existing emulator QA workflow. Read https://github.com/mobile-next/mobile-mcp
for current compatibility. Resolve an exact published npm version with `npm view @mobilenext/mobile-mcp version`
when the user requests setup, then run `android-kit-manager addons --project "$PWD" --mobile-version <version>`.
The command preserves other .mcp.json servers and refuses to overwrite a differing mobile-mcp entry.
Restart Claude, complete its normal MCP trust approval, and list available devices to verify connectivity.
Select one explicit local adb device and one owner for each session. Prefer accessibility elements;
use screenshots for visual inspection. Pause MCP interaction while Maestro/another QA agent owns
that device. Do not activate cloud-device accounts, purchases, or destructive device actions implicitly.

---
description: Set up optional local usage measurement and identify repeated or failed work
---
Observability is optional. If requested, use `android-kit-manager addons --project "$PWD" --observability`
and restart. Follow /observability:enable-project-telemetry to obtain service setup and repository
opt-in choices. Prefer local SQLite initially; dashboard/export requires an explicit choice.
Never enable Model Gateway as a telemetry prerequisite. Use observed token/model/tool/session
metadata, not invented remaining subscription quota. API-equivalent estimates are not subscription
spend. Correlate retries, failed commands, repeated reviews and reopened tasks with accepted outcomes;
request broader exports or new instrumentation only if those measurements are missing.

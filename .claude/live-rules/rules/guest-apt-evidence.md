---
description: Guest apt changes need proof apt accepts them
globs: ["app/src/main/assets/scripts/**/*.sh"]
prompt: ["apt", "dpkg", "coreutils"]
---
- Guest-side apt/dpkg changes (provider switches, removals, pins) need evidence that apt accepts the exact command: the user's terminal output or an `apt-get -s` run in the guest.
- A `|| WARNING` fallback hides a solver refusal, as SQ-24's first coreutils switch did (apt 3 won't drop a Protected package unless it is named with a trailing `-`).

#!/usr/bin/env bash
set -euo pipefail
exec python3 "$(dirname "${BASH_SOURCE[0]}")/detect-stack.py" "${1:-.}"

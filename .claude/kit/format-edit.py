#!/usr/bin/env python3
"""Opt-in formatter; no jq dependency, no shell interpolation, bounded runtime."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

try:
    data = json.load(sys.stdin)
    root = Path(os.environ.get('CLAUDE_PROJECT_DIR') or data.get('cwd') or '.').resolve()
    if (root / '.claude/kit/format-on-edit').is_file():
        f = Path((data.get('tool_input') or {}).get('file_path', ''))
        f = (Path(data.get('cwd') or root) / f).resolve()
        if f.is_relative_to(root) and f.suffix in ('.kt', '.kts') and f.is_file() and shutil.which('ktlint'):
            subprocess.run(['ktlint', '-F', str(f)], timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
except (ValueError, OSError, subprocess.TimeoutExpired):
    pass

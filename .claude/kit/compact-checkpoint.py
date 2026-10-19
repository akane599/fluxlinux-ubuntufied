#!/usr/bin/env python3
"""Save bounded, session-specific context before compaction; never mix agents/sessions."""
from collections import deque
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def checkpoint(root, session):
    key = hashlib.sha256(session.encode()).hexdigest()[:24]
    return root / '.claude/compact-checkpoints' / (key + '.md')


def main():
    hook = json.load(sys.stdin)
    # Executor compaction must not become main-session instructions.
    if hook.get('agent_id') or hook.get('agent_type'):
        return
    session = hook.get('session_id')
    if not isinstance(session, str) or not session:
        return
    root = Path(os.environ.get('CLAUDE_PROJECT_DIR') or hook.get('cwd') or '.').resolve()
    out = checkpoint(root, session)
    if out.is_symlink() or any(p.is_symlink() for p in out.parents if p != root and root in p.parents):
        return
    if len(sys.argv) > 1 and sys.argv[1] == '--restore':
        if out.is_file():
            print(out.read_text())
        return
    prompts, edited = deque(maxlen=6), deque(maxlen=25)
    transcript = hook.get('transcript_path')
    if transcript and Path(transcript).is_file():
        with open(transcript, errors='replace') as stream:
            for line in stream:
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                msg = e.get('message') or {}; content = msg.get('content')
                if e.get('type') == 'user' and not e.get('isMeta'):
                    blocks = [content] if isinstance(content, str) else [b.get('text','') for b in (content or []) if isinstance(b,dict) and b.get('type') == 'text']
                    text = ' '.join(t.strip() for t in blocks if t.strip() and not t.strip().startswith(('<','Caveat:','[Request interrupted')))
                    if text:
                        prompts.append(text[:600] + ('… [truncated]' if len(text)>600 else ''))
                if e.get('type') == 'assistant' and isinstance(content,list):
                    for block in content:
                        if isinstance(block,dict) and block.get('type') == 'tool_use' and block.get('name') in ('Edit','Write','MultiEdit','NotebookEdit'):
                            f = (block.get('input') or {}).get('file_path')
                            if f:
                                if f in edited: edited.remove(f)
                                edited.append(f)
    def git(*args):
        try:
            return subprocess.run(['git',*args],cwd=root,capture_output=True,text=True,timeout=3).stdout.strip()
        except (OSError,subprocess.TimeoutExpired):
            return ''
    lines=['# Session compact checkpoint', 'Historical excerpts; latest user instructions take precedence. Excerpts may be truncated.', '', '## Recent user messages']
    lines += ['> '+p.replace('\n','\n> ') for p in prompts]
    lines += ['', '## Repository', '- Branch: '+git('symbolic-ref','--short','HEAD'), '```', git('status','--short')[:4000], '```', '## Recently edited files']
    lines += ['- '+f for f in edited]
    lines += ['', 'Check the Sidequest board for work already in flight before continuing.']
    out.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    fd, temp = tempfile.mkstemp(prefix=out.name + '.', dir=out.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write('\n'.join(lines) + '\n')
        os.replace(temp, out)
    finally:
        if os.path.lexists(temp):
            os.unlink(temp)


if __name__ == '__main__':
    try:
        main()
    except (ValueError,OSError,TypeError):
        pass  # compaction/recovery must never fail due to this optional checkpoint

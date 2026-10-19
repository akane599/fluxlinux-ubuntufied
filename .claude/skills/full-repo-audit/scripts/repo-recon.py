#!/usr/bin/env python3
"""Current-source reconnaissance only; no project summaries or Git history."""
import os,re,sys
from pathlib import Path
ROOT=Path.cwd().resolve()
SKIP={'.git','.agents','.codex','.claude','.audit-evidence','audit-history','node_modules','build','dist','.gradle','__pycache__','.venv','venv','android-kit','android-kit-suite','coverage'}
EXT={'.kt','.kts','.gradle','.java','.py','.js','.jsx','.ts','.tsx','.rs','.go','.c','.h','.cpp','.swift','.xml','.sh','.toml'}
NAMES={'package.json','Makefile','Dockerfile','CMakeLists.txt','gradle.properties'}
PATTERN=re.compile(r'TODO|FIXME|HACK|XXX|@Suppress|noinspection|ProcessBuilder|Runtime\.getRuntime\(\)\.exec|subprocess\.(?:run|Popen|call)|eval\(|rawQuery|execSQL|!!|\.unwrap\(')
def eligible(p):
 rel=p.relative_to(ROOT)
 return not any(x.casefold() in SKIP or x.startswith('.') or 'audit' in x.casefold() or 'remediation' in x.casefold() for x in rel.parts) and (p.suffix.lower() in EXT or p.name in NAMES)
def main():
 roots=[Path(x) for x in sys.argv[1:]] or [ROOT];files=set()
 for raw in roots:
  p=raw.absolute()
  if not p.is_relative_to(ROOT) or any(a.is_symlink() for a in [p,*p.parents] if a==ROOT or ROOT in a.parents):raise ValueError('Use non-symlink source roots inside the project')
  p=p.resolve()
  if not p.is_relative_to(ROOT):raise ValueError('Source root escapes project')
  if p.is_file():
   if eligible(p):files.add(p)
   continue
  for directory,dirs,names in os.walk(p,followlinks=False):
   dirs[:]=[d for d in dirs if d.casefold() not in SKIP and not d.startswith('.') and 'audit' not in d.casefold() and 'remediation' not in d.casefold() and not (Path(directory)/d).is_symlink()]
   for name in names:
    f=Path(directory)/name
    if not f.is_symlink() and eligible(f):files.add(f)
 print('=== CURRENT SOURCE INVENTORY (filtered; no history or prior context) ===')
 for p in sorted(files):print(p.relative_to(ROOT))
 print('=== CURRENT SOURCE MARKERS (first 140 matches; not exhaustive) ===')
 hits=0
 for p in sorted(files):
  if p.stat().st_size>2_000_000:continue
  try:lines=p.read_text().splitlines()
  except (OSError,UnicodeError):continue
  for number,line in enumerate(lines,1):
   if PATTERN.search(line):
    print(f'{p.relative_to(ROOT)}:{number}:{line[:400]}');hits+=1
    if hits>=140:return
if __name__=='__main__':
 try:main()
 except (ValueError,OSError) as e:raise SystemExit(str(e))

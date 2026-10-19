#!/usr/bin/env python3
"""Archive, freeze and publish audit evidence without modifying skills or source.
This detects protected-file changes; it is not a sandbox or a blindness guarantee.
"""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path

PROTECTED=('.agents/skills','.codex/skills','.claude/skills','.claude/commands',
 '.codex/android-kit','.claude/kit','AGENTS.md','CLAUDE.md','PROJECT.md','MAP.md','PROGRESS.md')
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def plain(path):
 if path.is_symlink():raise ValueError('Refusing symlink: '+str(path))
def inventory(root):
 result={}
 for rel in PROTECTED:
  p=root/rel
  for ancestor in [p,*p.parents]:
   if ancestor==root:break
   plain(ancestor)
  if not p.exists():continue
  paths=p.rglob('*') if p.is_dir() else [p]
  for f in paths:
   plain(f)
   if f.is_file() and '__pycache__' not in f.parts:result[str(f.relative_to(root))]=digest(f)
 return result

def base(root):
 r=subprocess.run(['git','-C',str(root),'rev-parse','--absolute-git-dir'],capture_output=True,text=True)
 gitdir=Path(r.stdout.strip()) if r.returncode==0 else None
 p=gitdir/'audit-history' if gitdir else root/'.audit-evidence'
 for ancestor in [p,*p.parents]:plain(ancestor)
 return p

def write(path,data):
 with path.open('x',encoding='utf-8') as f:f.write(data)
 os.chmod(path,0o600)

def begin(root,report):
 if report and Path(report).name!=report:raise ValueError('Report must be a root filename')
 if report and report.casefold()!='audit_report.md':raise ValueError('Report filename must be AUDIT_REPORT.md (any case)')
 matches=[p.name for p in root.iterdir() if p.name.casefold()=='audit_report.md']
 if len(matches)>1:raise ValueError('Multiple case variants of audit report exist; reconcile them explicitly first')
 name=report or (matches[0] if matches else 'AUDIT_REPORT.md')
 if matches and name!=matches[0]:raise ValueError('Use the existing report spelling: '+matches[0])
 target=root/name;plain(target)
 protected=inventory(root);parent=base(root);parent.mkdir(parents=True,exist_ok=True)
 session=Path(tempfile.mkdtemp(prefix='run-',dir=parent));os.chmod(session,0o700)
 prior=digest(target) if target.exists() else None
 if prior:
  archived=session/'previous-report.md';archived.write_bytes(target.read_bytes());os.chmod(archived,0o600)
 write(session/'state.json',json.dumps({'root':str(root),'report':name,'prior_sha256':prior,'protected':protected},indent=2))
 print(session)

def load(root,session):
 p=Path(session).absolute();plain(p)
 if p.parent!=base(root):raise ValueError('Session is outside this worktree audit history')
 plain(p/'state.json');state=json.loads((p/'state.json').read_text())
 if state['root']!=str(root) or Path(state['report']).name!=state['report'] or state['report'].casefold()!='audit_report.md':raise ValueError('Invalid audit session')
 return p,state

def check(root,state):
 now=inventory(root);old=state['protected']
 changed=sorted(k for k in now.keys()|old.keys() if now.get(k)!=old.get(k))
 if changed:raise ValueError('Protected audit/kit files changed; stop and investigate, do not auto-restore: '+', '.join(changed))
 target=root/state['report'];plain(target)
 if (digest(target) if target.exists() else None)!=state['prior_sha256']:raise ValueError('Original report changed during audit; preserve it and investigate')

def freeze(root,p,state,draft):
 check(root,state);src=Path(draft);plain(src)
 if src.resolve()==(root/state['report']).resolve():raise ValueError('Use a separate draft; preserve original report')
 data=src.read_text()
 if not data.strip():raise ValueError('Empty independent report')
 write(p/'independent.md',data)
 write(p/'freeze.json',json.dumps({'sha256':digest(p/'independent.md')}))
 print('Independent report frozen. Only now begin the prior-report delta phase.')

def publish(root,p,state,delta):
 check(root,state)
 for name in ('freeze.json','independent.md'):plain(p/name)
 frozen=json.loads((p/'freeze.json').read_text())
 if digest(p/'independent.md')!=frozen['sha256']:raise ValueError('Frozen independent report was modified')
 data=(p/'independent.md').read_text()
 if state['prior_sha256'] and not delta:raise ValueError('Prior report exists; supply a separate post-freeze delta')
 if delta:
  src=Path(delta);plain(src);data+='\n\n## Audit Delta (post-freeze comparison)\n\n'+src.read_text()
 target=root/state['report'];fd,tmp=tempfile.mkstemp(prefix='.audit-report-',dir=root)
 try:
  with os.fdopen(fd,'w') as f:f.write(data)
  os.replace(tmp,target)
 finally:
  if os.path.exists(tmp):os.unlink(tmp)
 print('Published '+str(target)+'; archived report and frozen evidence retained. Protected files unchanged.')

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('action',choices=['begin','check','freeze','publish']);ap.add_argument('--root',default='.');ap.add_argument('--report');ap.add_argument('--session');ap.add_argument('--draft');ap.add_argument('--delta');a=ap.parse_args();root=Path(a.root).resolve()
 if a.action=='begin':begin(root,a.report);return
 if not a.session:ap.error('--session is required')
 p,state=load(root,a.session)
 if a.action=='check':check(root,state);print('Protected files and original report unchanged')
 elif a.action=='freeze':
  if not a.draft:ap.error('--draft is required')
  freeze(root,p,state,a.draft)
 else:publish(root,p,state,a.delta)
if __name__=='__main__':
 try:main()
 except (ValueError,OSError,KeyError,TypeError) as exc:raise SystemExit(str(exc))

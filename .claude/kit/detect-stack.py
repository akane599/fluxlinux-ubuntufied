#!/usr/bin/env python3
"""Read-only Android inventory. Heuristics report unknown instead of inventing defaults."""
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
if not root.is_dir():
    sys.exit(f'not a directory: {root}')
SKIP = {'.git', '.gradle', '.idea', '.claude', 'build', '.cxx', 'node_modules', 'vendor', 'android-kit', 'android-kit-claude-max'}
files = []
for base, dirs, names in os.walk(root, followlinks=False):
    dirs[:] = sorted(d for d in dirs if d not in SKIP and not (Path(base)/d).is_symlink())
    for name in sorted(names):
        p = Path(base)/name
        if p.is_symlink():
            continue
        if p.suffix in ('.kt', '.java', '.gradle', '.kts') or name in ('libs.versions.toml', 'AndroidManifest.xml'):
            files.append(p)

def read(p):
    try:
        return p.read_text(errors='replace')[:2_000_000]
    except OSError:
        return ''

builds = '\n'.join(read(p) for p in files if p.suffix in ('.gradle', '.kts') or p.name == 'libs.versions.toml')
kt = [p for p in files if p.suffix == '.kt']
jv = [p for p in files if p.suffix == '.java']
sources = '\n'.join(read(p) for p in kt)
settings = '\n'.join(read(p) for p in (root/'settings.gradle', root/'settings.gradle.kts'))
catalog = read(root/'gradle/libs.versions.toml')

def match(pattern, text, fallback='unknown'):
    m = re.search(pattern, text, re.M)
    return m.group(1) if m else fallback

def has(pattern):
    return bool(re.search(pattern, builds, re.I))

def choice(rows):
    return next((label for label, pattern in rows if has(pattern)), 'none')

def kv(k, value):
    print(f'{k}={value if str(value).strip() else "unknown"}')

def run(*argv):
    try:
        r = subprocess.run(argv, cwd=root, capture_output=True, text=True, timeout=8)
        return r.stdout.strip() if r.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''

kv('project_name', match(r'rootProject\.name\s*=\s*[\'"]([^\'"]+)', settings, root.name))
modules = re.findall(r'[\'"](:[\w:-]+)[\'"]', '\n'.join(line for line in settings.splitlines() if 'include' in line))
kv('modules', ' '.join(modules))
apps = []
for p in files:
    if p.name in ('build.gradle', 'build.gradle.kts') and re.search(r'com\.android\.application|libs\.plugins\.android\.application', read(p)):
        if p.parent != root:
            apps.append(':' + ':'.join(p.parent.relative_to(root).parts))
kv('app_module', ' '.join(apps))
for key, pattern in [('application_id', r'applicationId\s*(?:=|\()?\s*[\'"]([^\'"]+)'), ('namespace', r'namespace\s*(?:=|\()?\s*[\'"]([^\'"]+)')]:
    kv(key, match(pattern, builds))
android = '{http://schemas.android.com/apk/res/android}'
launchers = []
for p in files:
    if p.name != 'AndroidManifest.xml':
        continue
    try:
        tree = ET.fromstring(read(p))
        for node in list(tree.iter('activity')) + list(tree.iter('activity-alias')):
            for filt in node.findall('intent-filter'):
                if any(c.get(android+'name') == 'android.intent.category.LAUNCHER' for c in filt.findall('category')):
                    launchers.append(node.get(android+'name', 'unknown'))
    except ET.ParseError:
        pass
kv('launcher_activity', ' '.join(dict.fromkeys(launchers)))
kv('kotlin_files', len(kt)); kv('java_files', len(jv))
kv('primary_language', 'mixed' if kt and jv else 'kotlin' if kt else 'java' if jv else 'unknown')
kv('compose', 'yes' if has(r'androidx\.compose|compose-bom|compose\.bom') else 'no')
kv('screenshot_testing', choice([('compose-preview', r'com\.android\.compose\.screenshot|screenshot-validation'), ('paparazzi', r'paparazzi'), ('roborazzi', r'roborazzi')]))
kv('xml_layouts', sum(1 for base, dirs, names in os.walk(root) for name in names if name.endswith('.xml') and '/res/layout' in str(base) and not any(x in Path(base).relative_to(root).parts for x in SKIP)))
for key, pattern in [('dynamic_color', r'dynamic(Light|Dark)ColorScheme'), ('custom_typography', r'Typography\s*\(')]:
    kv(key, 'yes' if re.search(pattern, sources) else 'no')
kv('previews', sum('@Preview' in read(p) for p in kt))
kv('literal_colors_outside_theme', sum(len(re.findall(r'Color\(0x', read(p))) for p in kt if 'theme' not in p.parts))
kv('mutable_list_in_composables', len(re.findall(r'@Composable\b[^{}]*\b(?:List|Map|Set)<', sources)))
for key, pattern in [('immutable_collections', r'kotlinx[.-]collections[.-]immutable'), ('compose_metrics', r'reportsDestination|metricsDestination'), ('viewbinding', r'viewBinding\s*=?\s*true'), ('databinding', r'dataBinding\s*=?\s*true'), ('firebase', r'com\.google\.firebase'), ('espresso', r'espresso')]:
    kv(key, 'yes' if has(pattern) else 'no')
for key, pattern in [('agp_version', r'^(?:agp|androidGradlePlugin|android-gradle-plugin)\s*=\s*"([^"]+)"'), ('kotlin_version', r'^kotlin\s*=\s*"([^"]+)"')]:
    kv(key, match(pattern, catalog))
kv('gradle_version', match(r'gradle-([\d.]+)-(?:bin|all)', read(root/'gradle/wrapper/gradle-wrapper.properties')))
kv('java_target', match(r'(?:JavaVersion\.VERSION_|jvmToolchain\(|jvmTarget\s*=\s*[\'"])([\d_]+)', builds))
for key, pattern in [('compile_sdk',r'compileSdk(?:Version)?\s*[=(]?\s*(\d+)'), ('min_sdk',r'minSdk(?:Version)?\s*[=(]?\s*(\d+)')]:
    kv(key, match(pattern, builds))
kv('version_catalog', 'yes' if catalog else 'no')
for key, rows in {
 'di':[('hilt',r'dagger\.hilt|hilt-android'),('koin',r'io\.insert-koin|koin-android'),('dagger',r'com\.google\.dagger')],
 'db':[('room',r'androidx\.room'),('sqldelight',r'sqldelight'),('realm',r'io\.realm')],
 'network':[('retrofit',r'retrofit'),('ktor',r'io\.ktor'),('okhttp',r'okhttp'),('volley',r'volley')],
 'async':[('coroutines',r'kotlinx[.-]coroutines'),('rxjava',r'rxjava|rxandroid')],
 'navigation':[('compose-navigation',r'navigation-compose'),('fragment-navigation',r'navigation-fragment')]
}.items(): kv(key, choice(rows))
kv('lint_tools', ' '.join(x for x in ('detekt','ktlint','spotless','checkstyle') if has(x)) or 'none')
kv('unit_test_libs', ' '.join(x for x in ('junit','mockk','mockito','turbine') if has(x)) or 'none')
kv('androidtest_dirs', len({str(p).split('/androidTest/')[0] for p in files if '/androidTest/' in str(p)}))
for key, cmd in [('kotlin_lsp','kotlin-lsp'), ('jdtls','jdtls'), ('gh_cli','gh')]: kv(key,'installed' if shutil.which(cmd) else 'missing')
kv('android_home', os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or 'unknown')
kv('java_home', os.environ.get('JAVA_HOME','unknown'))
kv('kvm','yes' if os.access('/dev/kvm',os.W_OK) else 'no')
for key, args in [('git_repo',('rev-parse','--is-inside-work-tree')), ('git_head',('rev-parse','--short','HEAD')), ('git_branch',('symbolic-ref','--short','HEAD')), ('git_commits',('rev-list','--count','HEAD'))]: kv(key,run('git',*args))
# Never echo embedded access tokens from HTTPS remote URLs.
remotes = re.sub(r'(https?://)[^/\s@]+@', r'\1[redacted]@', run('git','remote','-v'))
kv('git_remotes', ' ; '.join(remotes.splitlines()))
kv('agent_docs',' '.join(n for n in ('CLAUDE.upstream.md','AGENTS.md','.cursorrules','.github/copilot-instructions.md') if (root/n).exists()))

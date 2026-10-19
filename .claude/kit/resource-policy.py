#!/usr/bin/env python3
"""Linux resource policy. Read-only unless --apply; no Gradle/system settings edited."""
import argparse
import json
import math
import os
from pathlib import Path
import platform
import shutil
import tempfile

GIB = 1024 ** 3


def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return ''


def number(path):
    try:
        n = int(read(path))
        return n if 0 <= n < 2**60 else None
    except ValueError:
        return None


def cgroups(proc=Path('/proc'), base=Path('/sys/fs/cgroup')):
    # Resolve membership against mount roots (including namespaced mounts).
    result = []
    membership = [line.split(':', 2) for line in read(proc/'self/cgroup').splitlines()]
    for line in read(proc/'self/mountinfo').splitlines():
        left, sep, right = line.partition(' - ')
        fields, tail = left.split(), right.split()
        if not sep or len(fields) < 5 or not tail or tail[0] != 'cgroup2':
            continue
        mount = Path(fields[4].replace('\\040', ' '))
        root = Path(fields[3].replace('\\040', ' '))
        for row in membership:
            if len(row) != 3 or row[0:2] != ['0', '']:
                continue
            try:
                relative = Path(row[2]).relative_to(root)
            except ValueError:
                continue
            if '..' in relative.parts:
                continue
            current = mount/relative
            while True:
                result.append(current)
                if current == mount:
                    break
                current = current.parent
    if not result and (base/'cgroup.controllers').exists():
        result.append(base)
    return list(dict.fromkeys(result))


def probe(project):
    mem = {}
    for line in read('/proc/meminfo').splitlines():
        key, _, value = line.partition(':')
        try:
            mem[key] = int(value.split()[0]) * 1024
        except (ValueError, IndexError):
            pass
    total = mem.get('MemTotal')
    available = mem.get('MemAvailable')
    cpu = float(os.cpu_count() or 1)
    if hasattr(os, 'sched_getaffinity'):
        cpu = min(cpu, len(os.sched_getaffinity(0)))
    groups = cgroups()
    for group in groups:
        limit = number(group/'memory.max')
        used = number(group/'memory.current')
        if limit is not None:
            total = min(total, limit) if total is not None else limit
            if used is not None:
                headroom = max(0, limit-used)
                available = min(available, headroom) if available is not None else headroom
        quota = read(group/'cpu.max').split()
        if len(quota) == 2 and quota[0] != 'max':
            try:
                cpu = min(cpu, int(quota[0])/int(quota[1]))
            except (ValueError, ZeroDivisionError):
                pass
    warnings = []
    if not total:
        warnings.append('RAM detection unavailable: retain conservative fallback and inspect manually.')
    if read('/proc/self/cgroup') and not groups:
        warnings.append('cgroup v2 not resolved; legacy/container limits may require manual inspection.')
    disk = shutil.disk_usage(project).free
    if disk < 10*GIB:
        warnings.append('Less than 10 GiB disk free; inspect caches/build output before large builds.')
    return dict(platform=platform.system(), ram_bytes=total, available_bytes=available,
                cpu_capacity=cpu, swap_total_bytes=mem.get('SwapTotal'),
                swap_free_bytes=mem.get('SwapFree'), disk_free_bytes=disk,
                kvm_access=os.access('/dev/kvm', os.R_OK | os.W_OK),
                memory_pressure=read('/proc/pressure/memory'),
                cgroup_v2_checked=bool(groups), warnings=warnings)


def policy(spec):
    ram = (spec['ram_bytes'] or 0)/GIB
    cpu = max(0.01, spec['cpu_capacity'])
    # Heuristic: reserve OS/editor/services capacity; never treat swap as RAM.
    slots = 2
    if ram >= 24 and not spec['warnings']:
        slots = max(2, int(ram//8))
    workers = max(1, min(4, math.floor(cpu/slots))) if ram >= 8 else 1
    warnings = list(spec['warnings'])
    if ram < 8 or cpu < 2:
        warnings.append('Small host: two-command ceiling retained; use one heavy build at a time if pressure/OOM occurs.')
    available = spec.get('available_bytes')
    pressure = available is not None and available < max(GIB, (spec['ram_bytes'] or 0)*0.10)
    if pressure:
        warnings.append('Low memory headroom now: defer another heavy build, continue independent non-build work; recheck before launching.')
    return dict(schema=1, specs=spec, gradle_command_limit=slots,
                gradle_workers_per_command=workers,
                defer_new_heavy_build=pressure,
                emulator='Check emulator acceleration explicitly before use.' if spec['kvm_access'] else 'No usable /dev/kvm detected; use JVM checks or a remote/physical device.',
                warnings=warnings,
                scope='Machine-wide active Gradle commands across projects/worktrees; NOT ticket dispatch or idle daemon count.',
                enforcement='Advisory coordination only, not a semaphore. Apply --max-workers to each Gradle invocation; do not rewrite JVM heap settings automatically.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Save generated policy beside this helper; never edit user rules/settings.')
    parser.add_argument('--project', type=Path, default=Path.cwd())
    args = parser.parse_args()
    report = policy(probe(args.project))
    target = Path(__file__).resolve().parent/'resource-policy.json'
    if args.apply:
        if target.is_symlink():
            parser.error('Refusing symlink output')
        if target.exists():
            old = json.loads(target.read_text())
            if old.get('schema') != 1 or 'enforcement' not in old:
                parser.error('Unrecognized policy file; preserve and reconcile manually')
        fd, name = tempfile.mkstemp(prefix='.resource-', dir=target.parent)
        try:
            with os.fdopen(fd, 'w') as stream:
                json.dump(report, stream, indent=2);stream.write('\n')
            os.replace(name, target)
        finally:
            if os.path.exists(name):
                os.unlink(name)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

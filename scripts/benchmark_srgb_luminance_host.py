#!/usr/bin/env python3
"""Bounded, offline HotSpot-only source-bound experiment; run from the owned RAM worktree.

Uses the cached compiler inventory also used by compile_focused.py. No Gradle,
Android execution, network, process discovery, dependency installation, or commits.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'd3aced695a49d193f32edfb9abb21236c5147bfc'
SOURCE = Path('src/main/kotlin/smallthingz/ui/controls/SelectionContrast.kt')
QA = ROOT / 'docs/qa/srgb-channel-table-experiment-20261125/benchmark'
HARNESS = ROOT / 'scripts/host/srgb-luminance-benchmark/SrgbLuminanceBenchmark.java'
RECORD = Path('/workspace/control/.tmp/control-product-provider-marker-v1/host-controls-01/result.json')
CONFIG = {'cold_fresh_jvms_per_mode_per_version': 5, 'hot_forks_per_version': 3,
          'warmup_batches_per_workload': 8, 'measured_batches_per_workload': 7,
          'calls_per_batch': {'srgbLuminance': 524288, 'selectionForegroundWithIndication': 16384,
                              'SelectionContrast.foreground': 65536},
          'corpus': '4096 ARGBs: 256 opaque grays then xorshift32 seed 0x5a17c9e3; four palettes',
          'indication_max_alpha': 40, 'target_bytecode': 17,
          'ordering': 'alternate baseline/candidate order by fork/sample; sequential processes'}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def describe(path):
    return {'path': str(path), 'bytes': Path(path).stat().st_size, 'sha256': digest(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', default='run-01', help='New directory name under the assigned QA benchmark directory')
    args = parser.parse_args()
    if not args.run or Path(args.run).name != args.run or args.run in ('.', '..'):
        parser.error('--run must be a plain new directory name')
    if ROOT != Path('/dev/shm/codex-srgb-channel-table-20261125/worktree'):
        parser.error('This experiment is restricted to its assigned RAM worktree')
    os.umask(0o077)
    baseline = subprocess.check_output(['git', 'show', f'{BASE}:{SOURCE}'], cwd=ROOT)
    candidate = (ROOT / SOURCE).read_bytes()
    if baseline == candidate or b'private val SRGB_CHANNEL_LUMINANCE = DoubleArray(256)' not in candidate:
        parser.error('Actual candidate table is not ready; refusing a baseline-vs-baseline run')
    prior = json.loads(RECORD.read_bytes())
    old_command = prior['operations'][0]['argv']
    java = Path(old_command[0])
    compiler = old_command[old_command.index('-cp') + 1]
    for p in compiler.split(':'):
        if digest(p) != prior['tools'][p]:
            raise RuntimeError(f'Compiler dependency hash mismatch: {p}')
    stdlib = next(p for p in compiler.split(':') if Path(p).name.startswith('kotlin-stdlib-'))
    annotations = next(p for p in compiler.split(':') if Path(p).name.startswith('annotations-'))
    out = QA / args.run
    out.mkdir(parents=True, exist_ok=False)
    scratch = out / 'scratch'
    scratch.mkdir()
    # Keep subprocess scratch owned; no injected JVM options or compiler daemons.
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
        env.pop(key, None)
    env.update(TMPDIR=str(scratch), TMP=str(scratch), TEMP=str(scratch), LC_ALL='C')
    config = dict(CONFIG)
    config.update(started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), baseline_commit=BASE,
                  scope='Host HotSpot only; not phone/ART, CPU shares, or performance acceptance',
                  source=describe(ROOT / SOURCE), script=describe(Path(__file__).resolve()), harness=describe(HARNESS),
                  compiler_record=describe(RECORD), compiler_dependencies={p: digest(p) for p in compiler.split(':')},
                  java=describe(java), javac=describe(java.with_name('javac')),
                  runtime_files=[describe(java.parent.parent / p) for p in ('release', 'lib/modules', 'lib/server/libjvm.so')],
                  environment={'tmpdir': str(scratch), 'LC_ALL': 'C', 'injected_java_options_removed': True})
    (out / 'config.json').write_text(json.dumps(config, indent=2) + '\n')
    operations = []

    def execute(label, argv):
        started = time.perf_counter_ns()
        with (out / f'{label}.stdout.log').open('xb') as stdout, (out / f'{label}.stderr.log').open('xb') as stderr:
            result = subprocess.run([str(a) for a in argv], cwd=ROOT, env=env, stdout=stdout, stderr=stderr)
        operations.append({'label': label, 'argv': [str(a) for a in argv], 'elapsed_ns': time.perf_counter_ns() - started,
                           'exit_code': result.returncode, 'stdout': describe(out / f'{label}.stdout.log'),
                           'stderr': describe(out / f'{label}.stderr.log')})
        (out / 'operations.json').write_text(json.dumps(operations, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(f'{label} failed: see {out / (label + ".stderr.log")}')
        return [json.loads(line) for line in (out / f'{label}.stdout.log').read_text().splitlines()] if label.startswith(('first-', 'init-', 'hot-')) else []

    jvm = [java, '-XX:-UsePerfData', '-Xms64m', '-Xmx256m', '-XX:+UseSerialGC', f'-Djava.io.tmpdir={scratch}']
    execute('java-version', [java, '-XX:-UsePerfData', '-version'])
    classes = {}
    for name, content in (('baseline', baseline), ('candidate', candidate)):
        folder = out / name
        folder.mkdir()
        snapshot = folder / SOURCE.name
        snapshot.write_bytes(content)
        classes[name] = folder / 'classes'
        classes[name].mkdir()
        execute(f'compile-{name}', [*jvm, '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                                  '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', f'{stdlib}:{annotations}',
                                  '-d', classes[name], snapshot])
    harness_classes = out / 'harness-classes'
    harness_classes.mkdir()
    execute('compile-harness', [java.with_name('javac'), '-J-XX:-UsePerfData', f'-J-Djava.io.tmpdir={scratch}',
                               '--release', '17', '-classpath', f'{classes["baseline"]}:{stdlib}',
                               '-d', harness_classes, HARNESS])
    agent = out / 'size-agent.jar'
    with zipfile.ZipFile(agent, 'w', compression=zipfile.ZIP_STORED) as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: SrgbLuminanceBenchmark$SizeAgent\n\n')
        p = harness_classes / 'SrgbLuminanceBenchmark$SizeAgent.class'
        archive.write(p, p.name)
    config['source_snapshots'] = {v: describe(out / v / SOURCE.name) for v in classes}
    config['classfiles'] = [describe(p) for p in sorted(out.rglob('*.class'))]
    config['agent'] = describe(agent)
    (out / 'config.json').write_text(json.dumps(config, indent=2) + '\n')
    observations = []
    for mode, forks in (('first', 5), ('init', 5), ('hot', 3)):
        for fork in range(forks):
            order = ('baseline', 'candidate') if fork % 2 == 0 else ('candidate', 'baseline')
            for name in order:
                label = f'{mode}-{fork:02}-{name}'
                rows = execute(label, [*jvm, f'-javaagent:{agent}', '-cp', f'{harness_classes}:{classes[name]}:{stdlib}',
                                       'SrgbLuminanceBenchmark', mode])
                observations.extend(dict(row, version=name, fork=fork, mode=mode) for row in rows)
                print(label + ' complete', flush=True)
    (out / 'observations.json').write_text(json.dumps(observations, indent=2) + '\n')
    # Observable checksums must agree across separate source versions and every measured batch.
    checks = {}
    for stage in (*CONFIG['calls_per_batch'], 'first_use_including_lazy_loading', 'first_direct_call_after_init_including_linkage'):
        rows = [r for r in observations if r['stage'] == stage]
        checks[stage] = len({r['checksum'] for r in rows}) == 1 and {r['version'] for r in rows} == {'baseline', 'candidate'}
    for version, expected in (('baseline', 0), ('candidate', 1)):
        checks[f'{version}_table_count'] = all(r['count'] == expected for r in observations
                                                   if r['stage'] == 'table_count' and r['version'] == version)
    checks['source_unchanged_during_run'] = digest(ROOT / SOURCE) == config['source']['sha256']
    summaries = {}
    stages = sorted({r['stage'] for r in observations if 'ns' in r})
    for stage in stages:
        summaries[stage] = {}
        for version in ('baseline', 'candidate'):
            rows = [r for r in observations if r['stage'] == stage and r['version'] == version]
            values = [r['ns'] / r['calls'] for r in rows]
            allocation = [r['bytes'] / r['calls'] for r in rows]
            summaries[stage][version] = {'samples': len(rows), 'median_ns_per_call': statistics.median(values),
                                         'min_ns_per_call': min(values), 'max_ns_per_call': max(values),
                                         'median_bytes_per_call': statistics.median(allocation),
                                         'max_bytes_per_call': max(allocation),
                                         'per_fork_median_ns_per_call': {str(f): statistics.median(r['ns'] / r['calls'] for r in rows if r['fork'] == f)
                                                                       for f in sorted({r['fork'] for r in rows})}}
    result = {'checks': checks, 'summaries': summaries,
              'table_layout': [r for r in observations if r['stage'] in ('table_layout', 'vm_layout')],
              'limits': ['HotSpot host measurements only; no phone/ART or performance acceptance claim.',
                         'Small sequential fork sample; scheduling, JIT, thermal/load and GC noise are uncontrolled.',
                         'Cold first-use includes VM lazy loading/linkage and first call; not process startup.',
                         'Isolated initialization preloads target metadata and warms JDK Math/initialization API.',
                         'Initialization includes residual ensureInitialized overhead; control is reported, not subtracted.',
                         'ThreadMXBean measures current-thread Java heap bytes, excluding native/metaspace/other-thread costs.',
                         'Instrumentation array size is HotSpot-specific; static reference/Class metadata excluded.',
                         'Hot samples include loop, checksum and fixed timing/counter overhead; no overhead subtraction.',
                         'Checksums support measurement integrity; focused bitwise/JUnit regression remains main-owned.']}
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'result': str(out / 'result.json'), 'checks': checks}), flush=True)
    return 0 if all(checks.values()) else 1


if __name__ == '__main__':
    sys.exit(main())

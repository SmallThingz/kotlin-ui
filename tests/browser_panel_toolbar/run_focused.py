#!/usr/bin/env python3
"""Focused browser-panel toolbar overlay; never invokes Gradle.

Uses the existing cached Android/Skia fixture dependencies, with fresh classes and receipts.
Exact parent panel helper is generated only in the output. Default mode writes a plan without launching a VM.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
BASE = '05ed4596e505cf945a5d265399c61d6f11365cad'
REL = Path('src/main/kotlin/smallthingz/ui/reverb')
REFERENCE = Path('/workspace/.tmp/control-idle-memory-20261127/browser-fixture/run-memory-native-01')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--variant', choices=['actual', 'sizing-parent'], default='actual')
    parser.add_argument('--compiled-from', type=Path, help='Reuse successful compilation of identical inputs/variant')
    parser.add_argument('--resource-apk', required=True, type=Path, help='Existing binary app resource archive matching the cached R classes')
    parser.add_argument('--run-native', action='store_true', help='Only after root admits serial native execution and verifies the existing asset cache')
    parser.add_argument('--test-class', choices=['BrowserPanelToolbarHostTest'], default='BrowserPanelToolbarHostTest')
    parser.add_argument('--production-from', type=Path, help='Reuse matching production bytecode while compiling only the selected new fixture')
    parser.add_argument('--execute', action='store_true', help='Execute the prepared stages only in the admitted serial slot')
    parser.add_argument('--method', help='Run only one named affected JUnit method')
    args = parser.parse_args()
    assert not (args.compiled_from and args.production_from)
    # Use the same root/04 fixture lock: busy means no compile/native launch or extraction.
    lock = Path('/workspace/.tmp/control-cloud-ui-compile-20261126/.final-984337.lock').open('r+b')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    with zipfile.ZipFile(args.resource_apk) as archive:
        assert 'resources.arsc' in archive.namelist(), 'Binary resources required; no conversion/build is performed'
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = args.compiled_from.resolve() / 'classes' if args.compiled_from else out / 'classes'
    if not args.compiled_from:
        classes.mkdir()
    original = json.loads((REFERENCE / 'test-command.json').read_text())
    cp = original[original.index('-cp') + 1].split(':')
    config_root = out / 'resource-config'
    config_root.mkdir()
    cp = [str(config_root), str(classes)] + cp[3:]
    if args.production_from:
        cp.insert(2, str(args.production_from.resolve() / 'classes'))
    assert all(Path(p).exists() for p in cp)
    classpath = ':'.join(cp)
    shared = next(p for p in cp if 'bundleLibRuntimeToJarRelease/classes.jar' in p)
    gradle = Path('/home/agent/.gradle/caches/modules-2/files-2.1')
    compiler = []
    for artifact, version in [('org.jetbrains.kotlin/kotlin-compiler-embeddable', '2.4.21'),
                              ('org.jetbrains.kotlin/kotlin-stdlib', '2.4.21'),
                              ('org.jetbrains.kotlin/kotlin-script-runtime', '2.4.21'),
                              ('org.jetbrains.kotlin/kotlin-reflect', '2.4.10'),
                              ('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', '1.9.0'),
                              ('org.jetbrains/annotations', '23.0.0')]:
        paths = list((gradle / artifact / version).glob('*/*.jar'))
        assert len(paths) == 1, artifact
        compiler.append(str(paths[0]))
    inputs = [ROOT / REL / 'ReverbBrowserPanel.kt',
              ROOT / REL / 'ReverbBrowserPanelSettings.kt', ROOT / REL / 'ReverbBrowserPanelTabs.kt',
              ROOT / REL / 'ReverbBrowserTabCountDrawable.kt',
              ROOT / REL / 'ReverbBottomActionDock.kt',
              ROOT / 'src/main/kotlin/smallthingz/ui/components/NativeBottomActionDock.kt',
              ROOT / 'src/main/kotlin/smallthingz/ui/components/NativeCenteredTopBarLayout.kt',
              ROOT / REL / 'ReverbTitledTopBarLayout.kt',
              Path(__file__).with_name(args.test_class + '.java')]
    source = [p.read_bytes() for p in inputs]
    selected = list(inputs[:-1])
    if args.variant == 'sizing-parent':
        # Exact prior executed candidate: unboxed helper but old46/single-slot parent policy.
        prior = ROOT.parent / '01-panel-toolbar-checks/actual-01/input-snapshots'
        for i in range(3):
            selected[i] = out / inputs[i].name
            selected[i].write_bytes((prior / inputs[i].name).read_bytes())
        selected[6] = out / 'NativeCenteredTopBarLayout.kt'
        selected[6].write_bytes(subprocess.check_output(['git', 'show', BASE + ':src/main/kotlin/smallthingz/ui/components/NativeCenteredTopBarLayout.kt'], cwd=ROOT))
    config = config_root / 'com/android/tools/test_config.properties'
    config.parent.mkdir(parents=True)
    properties = (REFERENCE / 'classes/com/android/tools/test_config.properties').read_text().splitlines()
    properties = [line for line in properties if not line.startswith('android_resource_apk=')]
    properties.append('android_resource_apk=' + str(args.resource_apk.resolve()))
    config.write_text('\n'.join(properties) + '\n')
    java = Path('/workspace/android-toolchain/jdk/bin')
    runtime_options = [arg for arg in original[1:original.index('-cp')] if not arg.startswith('-Djava.io.tmpdir=')]
    # Keep small transient native libraries on disk; reuse already-restored fonts/ICU read-only.
    # Never re-extract the ~189MiB asset set into the nearly-full shared-memory filesystem.
    tmp = out / 'tmp'
    if args.run_native:
        assets = Path('/dev/shm/control-icon-render-runtime-20261127/tmp/robolectric-nativeruntime-assets')
        assert assets.is_dir(), 'Root must verify the complete native asset cache before this focused run'
        tmp.mkdir()
        (tmp / 'robolectric-nativeruntime-assets').symlink_to(assets, target_is_directory=True)
        runtime_options.append('-Djava.io.tmpdir=' + str(tmp))
    commands = [
        ('kotlin', [str(java / 'java'), '-Xmx384m', '-cp', ':'.join(compiler),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-jvm-target', '17', '-classpath', classpath, '-Xfriend-paths=' + shared,
                    '-d', str(classes), *map(str, selected)]),
        ('java', [str(java / 'javac'), '--release', '17', '-cp', classpath, '-d', str(classes), str(inputs[-1])]),
        ('test', [str(java / 'java'), *runtime_options, '-Daddress.test.output=' + str(out),
                  '-cp', classpath, 'org.junit.runner.JUnitCore', args.test_class]),
    ]
    if args.method:
        commands[-1][1][-2:] = [args.test_class, args.method]
    receipt = {'method':args.method, 'variant': args.variant, 'base': BASE, 'gradle': False, 'device': False,
               'test_class': args.test_class,
               'native_execution_requested': args.run_native, 'execute_requested': args.execute,
               'resource_apk': str(args.resource_apk.resolve()), 'resource_sha256': hashlib.sha256(args.resource_apk.read_bytes()).hexdigest(),
               'limit': 'Exact panel helper, Settings, Tabs, counter, docks, centered layout and titled wrapper over cached shared/dependency classes. One panel-toolbar case; not Chrome five-case rerun, full app or installed acceptance.',
               'input_sha256': {str(p): hashlib.sha256(b).hexdigest() for p, b in zip(inputs, source)},
               'stages': []}
    if args.production_from:
        previous = json.loads((args.production_from / 'result.json').read_text())
        assert previous['variant'] == args.variant
        assert all(previous['input_sha256'][str(p)] == receipt['input_sha256'][str(p)] for p in inputs[:-1])
        assert any(s['stage'] == 'kotlin' and s.get('exit_code') == 0 for s in previous['stages'])
        receipt['production_compilation_reused_from'] = str(args.production_from.resolve())
        commands = [command for command in commands if command[0] != 'kotlin']
    receipt['selected_compiler_sources'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in selected}
    snapshots = out / 'input-snapshots'
    snapshots.mkdir()
    for p in [*selected, inputs[-1], Path(__file__)]:
        (snapshots / p.name).write_bytes(p.read_bytes())
    if args.compiled_from:
        previous = json.loads((args.compiled_from / 'result.json').read_text())
        assert previous['input_sha256'] == receipt['input_sha256'] and previous['variant'] == args.variant
        assert all(any(s['stage'] == name and s.get('exit_code') == 0 for s in previous['stages']) for name in ['kotlin', 'java'])
        receipt['compilation_reused_from'] = str(args.compiled_from.resolve())
        commands = [command for command in commands if command[0] == 'test']
    if not args.run_native:
        commands = [command for command in commands if command[0] != 'test']
    def save():
        (out / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
    receipt['prepared_commands'] = commands
    if not args.execute:
        receipt['status'] = 'PLAN_ONLY_NO_VM'
        save()
        print(json.dumps({'plan': str(out / 'result.json'), 'stages': [x[0] for x in commands]}))
        return
    council = ROOT.parent
    handback = json.loads((council / '03-tabs-implementation.json').read_text())
    assert handback['native_resource_handoff']['current'].startswith('No02 native process remains')
    lease = open(council / 'native-fixture-serial.lock', 'a')
    fcntl.flock(lease.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    active = []
    for proc in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            tokens = proc.read_bytes().split(b'\0')
        except OSError:
            continue
        if any(t in tokens for t in (b'org.junit.runner.JUnitCore', b'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler')):
            active.append(int(proc.parent.name))
    assert not active, f'Other fixture JVM active: {active}'
    assert os.statvfs(out).f_bavail * os.statvfs(out).f_frsize >= 24 * 1024 * 1024, 'Need24MiB transient disk reserve'
    if args.run_native:
        # Audit already-extracted fonts/ICU/hyphen bytes without writing or extracting any asset.
        asset_jar = Path('/dev/shm/control-icon-render-runtime-20261127/native-assets-preserved.zip')
        audited, total = 0, 0
        with zipfile.ZipFile(asset_jar) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                cached = assets / entry.filename
                assert cached.is_file(), 'Missing existing asset: ' + entry.filename
                assert cached.stat().st_size == entry.file_size
                crc = 0
                with cached.open('rb') as stream:
                    while chunk := stream.read(65536): crc = zlib.crc32(chunk, crc)
                assert crc == entry.CRC, 'Asset CRC mismatch: ' + entry.filename
                audited += 1; total += entry.file_size
        assert audited > 0
        receipt['asset_cache_audit'] = {'files': audited, 'bytes': total, 'new_asset_copies': 0, 'reference_archive':str(asset_jar)}
    receipt['status'] = 'SERIAL_SLOT_ACTIVE'
    handshake = council / '01-panel-toolbar-handshake.json'
    handshake.write_text(json.dumps({'owner':'01-browser','status':'SERIAL_SLOT_ACTIVE','pid':os.getpid(), 'report':str(out / 'result.json'), '02_handback':str(council / '03-tabs-implementation.json')}, indent=2)+'\n')
    save()
    env = dict(os.environ)
    env.pop('JAVA_TOOL_OPTIONS', None)
    for name, command in commands:
        row = {'stage': name, 'command': command, 'started': time.time()}
        receipt['stages'].append(row)
        save()
        with (out / (name + '.stdout')).open('wb') as stdout, (out / (name + '.stderr')).open('wb') as stderr:
            completed = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, timeout=180)
        row.update(exit_code=completed.returncode, finished=time.time())
        receipt['sources_unchanged'] = all(p.read_bytes() == b for p, b in zip(inputs, source))
        save()
        print(name, completed.returncode, flush=True)
        if completed.returncode:
            print((out / (name + '.stdout')).read_text()[-4500:])
            print((out / (name + '.stderr')).read_text()[-4500:])
            receipt['status'] = 'TERMINAL_FAILURE_RETAINED'
            save()
            handshake.write_text(json.dumps({'owner':'01-browser','status':'SERIAL_SLOT_RETURNED','exit_code':completed.returncode,'report':str(out / 'result.json')},indent=2)+'\n')
            raise SystemExit(completed.returncode)
    receipt['status'] = 'TERMINAL_SUCCESS'
    save()
    handshake.write_text(json.dumps({'owner':'01-browser','status':'SERIAL_SLOT_RETURNED','exit_code':0,'report':str(out / 'result.json')},indent=2)+'\n')
    if args.run_native:
        print((out / 'test.stdout').read_text()[-1800:])
    else:
        receipt['native_execution'] = 'not run; requires root serial admission'
        save()


if __name__ == '__main__':
    main()

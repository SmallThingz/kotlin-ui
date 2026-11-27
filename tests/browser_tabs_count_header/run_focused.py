#!/usr/bin/env python3
"""Offline two-file overlay compilation and four isolated Robolectric tests; never invokes Gradle.

Uses the existing cached Android/Skia fixture dependencies, with fresh classes and receipts.
Parent-header and fixed-count controls are generated in the output only, never in product source.
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

ROOT = Path(__file__).resolve().parents[2]
BASE = '05ed4596e505cf945a5d265399c61d6f11365cad'
REVERB_ADAPTERS = Path('src/main/adapters/kotlin/smallthingz/ui/reverb')
BASE_REVERB_SOURCE = Path('src/main/kotlin/smallthingz/ui/reverb')
REFERENCE = Path('/workspace/.tmp/control-idle-memory-20261127/browser-fixture/run-memory-native-01')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--variant', choices=['actual', 'parent-header', 'fixed-count'], default='actual')
    parser.add_argument('--compiled-from', type=Path, help='Reuse successful compilation of identical inputs/variant')
    parser.add_argument('--resource-apk', required=True, type=Path, help='Existing binary app resource archive matching the cached R classes')
    parser.add_argument('--run-native', action='store_true', help='Only after root admits serial native execution and verifies the existing asset cache')
    parser.add_argument('--test-class', choices=['BrowserTabCountHeaderTest', 'BrowserTabViewportBudgetTest', 'BrowserTabRootDispatchTest'], default='BrowserTabCountHeaderTest')
    parser.add_argument('--production-from', type=Path, help='Reuse matching production bytecode while compiling only the selected new fixture')
    parser.add_argument('--current-dock', action='store_true', help='Freeze and compile current Native/Reverb dock sources with the two tab sources; never reuse old dock bytecode')
    args = parser.parse_args()
    assert not (args.current_dock and args.compiled_from)
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
    inputs = [ROOT / REVERB_ADAPTERS / 'ReverbBrowserPanelTabs.kt', ROOT / REVERB_ADAPTERS / 'ReverbBrowserTabCountDrawable.kt',
              Path(__file__).with_name(args.test_class + '.java')]
    source = [p.read_bytes() for p in inputs]
    selected = list(inputs[:2])
    if args.current_dock:
        inputs += [ROOT / 'src/main/kotlin/smallthingz/ui/components/NativeBottomActionDock.kt',
                   ROOT / REVERB_ADAPTERS / 'ReverbBottomActionDock.kt']
        source = [p.read_bytes() for p in inputs]
        selected += inputs[3:]
    # Every compiler input is a frozen copy; concurrent owner edits cannot change this run.
    snapshots = out / 'input-snapshots'
    snapshots.mkdir()
    frozen = []
    for path, data in zip(inputs, source):
        target = snapshots / path.name
        target.write_bytes(data)
        frozen.append(target)
    selected = [frozen[inputs.index(p)] for p in selected]
    (snapshots / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    if args.variant == 'parent-header':
        selected[0] = out / 'ReverbBrowserPanelTabs.kt'
        selected[0].write_bytes(subprocess.check_output(['git', 'show', BASE + ':' + str(BASE_REVERB_SOURCE / inputs[0].name)], cwd=ROOT))
    if args.variant == 'fixed-count':
        selected[1] = out / 'ReverbBrowserTabCountDrawable.kt'
        text = source[1].decode()
        old = 'if (count > 99) "99+" else count.coerceAtLeast(0).toString()'
        assert text.count(old) == 1
        selected[1].write_text(text.replace(old, '"1"'))
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
        ('java', [str(java / 'javac'), '--release', '17', '-cp', classpath, '-d', str(classes), str(frozen[2])]),
        ('test', [str(java / 'java'), *runtime_options, '-Dtabs.test.output=' + str(out),
                  '-cp', classpath, 'org.junit.runner.JUnitCore', args.test_class]),
    ]
    receipt = {'variant': args.variant, 'base': BASE, 'gradle': False, 'device': False,
               'test_class': args.test_class,
               'native_execution_requested': args.run_native,
               'resource_apk': str(args.resource_apk.resolve()), 'resource_sha256': hashlib.sha256(args.resource_apk.read_bytes()).hexdigest(),
               'limit': 'Isolated tab overlay with current frozen Dock sources when current_dock=true; mounted panel only, no ControlScreenFrame/Home/cutout acceptance.',
               'current_dock': args.current_dock,
               'selected_compiler_input_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in selected},
               'cached_shared_jar': shared, 'cached_shared_jar_sha256': hashlib.sha256(Path(shared).read_bytes()).hexdigest(),
               'input_sha256': {str(p): hashlib.sha256(b).hexdigest() for p, b in zip(inputs, source)},
               'stages': []}
    if args.production_from:
        previous = json.loads((args.production_from / 'result.json').read_text())
        assert previous['variant'] == args.variant
        production_inputs = inputs[:2] + (inputs[3:] if args.current_dock else [])
        assert previous.get('current_dock', False) == args.current_dock
        assert all(previous['input_sha256'][str(p)] == receipt['input_sha256'][str(p)] for p in production_inputs)
        if args.current_dock:
            assert previous['cached_shared_jar_sha256'] == receipt['cached_shared_jar_sha256']
            for name in ['components/NativeBottomActionDock', 'reverb/ReverbBottomActionDock']:
                assert (args.production_from / ('classes/smallthingz/ui/' + name + '.class')).is_file()
        assert any(s['stage'] == 'kotlin' and s.get('exit_code') == 0 for s in previous['stages'])
        receipt['production_compilation_reused_from'] = str(args.production_from.resolve())
        commands = [command for command in commands if command[0] != 'kotlin']
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
            raise SystemExit(completed.returncode)
    if args.run_native:
        print((out / 'test.stdout').read_text()[-1800:])
    else:
        receipt['native_execution'] = 'not run; requires root serial admission'
        save()


if __name__ == '__main__':
    main()

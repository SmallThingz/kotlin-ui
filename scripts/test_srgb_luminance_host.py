#!/usr/bin/env python3
"""Run only luminance and existing contrast JVM tests using retained local dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'src/main/kotlin/smallthingz/ui/controls/SelectionContrast.kt',
    'src/main/kotlin/smallthingz/ui/controls/TextSurfaceContrast.kt',
    'src/main/kotlin/smallthingz/ui/animation/ColorTransition.kt',
    'src/test/kotlin/smallthingz/ui/controls/SelectionContrastTest.kt',
    'src/test/kotlin/smallthingz/ui/controls/TextSurfaceContrastTest.kt',
    'src/test/kotlin/smallthingz/ui/controls/SrgbLuminanceTableTest.kt',
]
TESTS = ['SelectionContrastTest', 'TextSurfaceContrastTest', 'SrgbLuminanceTableTest']


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--compiler-classpath', required=True)
    parser.add_argument('--test-classpath', required=True,
                        help='Retained graphics dependencies, stdlib, annotations, JUnit4, Hamcrest')
    parser.add_argument('--output', type=Path, required=True, help='New owned RAM directory')
    args = parser.parse_args()
    os.umask(0o077)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    scratch = output / 'scratch'
    scratch.mkdir()
    java = args.java_home / 'bin/java'
    files = [ROOT / name for name in SOURCES]
    dependencies = sorted(set((args.compiler_classpath + os.pathsep + args.test_classpath).split(os.pathsep)))
    inputs = [*files, Path(__file__).resolve(), java, *map(Path, dependencies)]
    before = {str(path): sha(path) for path in inputs}
    receipt = {'state': 'pending', 'inputs': before, 'operations': [],
               'scope': 'Host JVM only; no Android runtime, APK, device or production acceptance.',
               'test_classes': TESTS}

    def save():
        (output / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')

    def run(stage, argv):
        operation = {'stage': stage, 'argv': list(map(str, argv)), 'exit_code': None}
        receipt['operations'].append(operation)
        save()
        log = output / (stage + '.log')
        with log.open('xb') as stream:
            completed = subprocess.run(operation['argv'], stdout=stream, stderr=subprocess.STDOUT)
        operation.update(exit_code=completed.returncode, log_sha256=sha(log), log_bytes=log.stat().st_size)
        save()
        return completed.returncode

    java_args = [java, '-XX:-UsePerfData', '-Xmx384m', '-Djava.io.tmpdir=' + str(scratch)]
    code = run('java-version', [java, '-XX:-UsePerfData', '-version'])
    if code == 0:
        code = run('compile', [*java_args, '-cp', args.compiler_classpath,
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
            '-jvm-target', '17', '-classpath', args.test_classpath, '-d', classes, *files])
    if code == 0:
        code = run('tests', [*java_args, '-cp', str(classes) + os.pathsep + args.test_classpath,
            'org.junit.runner.JUnitCore', *('smallthingz.ui.controls.' + name for name in TESTS)])
    receipt.update(state='terminal', exit_code=code,
                   inputs_unchanged=before == {str(path): sha(path) for path in inputs},
                   classfiles={str(p.relative_to(output)): sha(p) for p in sorted(classes.rglob('*.class'))})
    save()
    print(json.dumps({'result': str(output / 'result.json'), 'exit_code': code,
                      'inputs_unchanged': receipt['inputs_unchanged']}))
    return code if receipt['inputs_unchanged'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

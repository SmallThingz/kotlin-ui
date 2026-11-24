#!/usr/bin/env python3
"""Test production font-scale snapshot publication with a host SparseArray fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'src/main/kotlin/smallthingz/ui/unit/InlineClassHelper.kt',
    'src/main/kotlin/smallthingz/ui/unit/fontscaling/FontScaleConverter.kt',
    'src/main/kotlin/smallthingz/ui/unit/fontscaling/FontScaleConverterFactory.kt',
    'src/main/kotlin/smallthingz/ui/unit/fontscaling/FontScaleConverterTable.kt',
    'src/main/kotlin/smallthingz/ui/unit/fontscaling/MathUtils.kt',
]
FIXTURE = 'src/testHost/java/android/util/SparseArray.java'
TEST = 'src/testHost/kotlin/smallthingz/ui/unit/fontscaling/FontScaleConverterSnapshotHostTest.kt'
TEST_CLASS = 'smallthingz.ui.unit.fontscaling.FontScaleConverterSnapshotHostTest'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True,
                        help='Kotlin compiler-embeddable and its dependencies, separated by the OS path separator')
    parser.add_argument('--test-classpath', required=True,
                        help='Kotlin stdlib, AndroidX/JetBrains annotations, JUnit4 and Hamcrest jars')
    parser.add_argument('--output', type=Path, required=True, help='New directory for classes, logs and exit receipts')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--source-root', type=Path, default=ROOT,
                        help='Production native-ui checkout; fixtures always come from this script checkout')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    java = str(args.java_home / 'bin/java') if args.java_home else 'java'
    javac = str(args.java_home / 'bin/javac') if args.java_home else 'javac'
    sources = [args.source_root.resolve() / name for name in SOURCES]
    fixture, test = ROOT / FIXTURE, ROOT / TEST
    files = sources + [fixture, test, Path(__file__).resolve()]
    before = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    commands = []

    def run(stage, command):
        with (output / (stage + '.log')).open('w') as log:
            try:
                code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
            except OSError as error:
                log.write(str(error) + '\n')
                code = 127
        commands.append({'stage': stage, 'argv': command, 'exit': code})
        (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        return code

    classpath = str(classes) + os.pathsep + args.test_classpath
    code = run('javac', [javac, '--release', '17', '-d', str(classes), str(fixture)])
    if code == 0:
        code = run('kotlinc', [java, '-cp', args.compiler_classpath,
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
            '-module-name', 'kotlin_ui_font_scaling_host', '-classpath', classpath,
            '-d', str(classes), *map(str, sources), str(test)])
    if code == 0:
        code = run('tests', [java, '-cp', classpath, 'org.junit.runner.JUnitCore', TEST_CLASS])
    after = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    if code == 0 and before != after:
        code = 1
    summary = {'exit': code, 'source_unchanged': before == after,
               'source_sha256': before, 'source_sha256_after': after, 'test_class': TEST_CLASS,
               'qualification': 'Actual production font scaling with host SparseArray publication interleavings; '
                                'not Android runtime, rendering or timing proof'}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    return code


if __name__ == '__main__':
    raise SystemExit(main())

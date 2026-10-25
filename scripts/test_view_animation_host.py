#!/usr/bin/env python3
"""Test actual ViewFrameLoop control flow with a narrow host View fixture, not Android rendering."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'src/main/kotlin/smallthingz/ui/animation/view/ViewFrameLoop.kt',
    'src/test/kotlin/smallthingz/ui/animation/CapturedFrameClock.kt',
    'src/testViewHost/kotlin/android/view/View.kt',
    'src/testViewHost/kotlin/smallthingz/ui/animation/ViewFrameLoopFixtureTest.kt',
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True, help='Kotlin compiler and dependencies')
    parser.add_argument('--test-classpath', required=True,
                        help='Built library classes, stdlib, coroutines, annotations, JUnit4 and Hamcrest')
    parser.add_argument('--output', type=Path, required=True, help='New directory for logs and receipts')
    parser.add_argument('--java-home', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    java = str(args.java_home / 'bin/java') if args.java_home else 'java'
    sources = [ROOT / name for name in SOURCES]
    before = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}
    commands = []

    def run(stage, command):
        with (output / (stage + '.log')).open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        commands.append({'stage': stage, 'argv': command, 'exit': result.returncode})
        (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        return result.returncode

    code = run('kotlinc', [java, '-cp', args.compiler_classpath,
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
        '-jvm-default=no-compatibility', '-module-name', 'view_animation_host',
        '-classpath', args.test_classpath, '-d', str(classes), *map(str, sources)])
    if code == 0:
        code = run('tests', [java, '-cp', str(classes) + os.pathsep + args.test_classpath,
            'org.junit.runner.JUnitCore', 'smallthingz.ui.animation.ViewFrameLoopFixtureTest'])
    after = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}
    summary = {'exit': code, 'source_unchanged': before == after, 'source_sha256': before,
               'qualification': 'Actual owner control flow with a host View linkage fixture; not Android runtime, rendering, input or timing proof'}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    return code if before == after else 1


if __name__ == '__main__':
    raise SystemExit(main())

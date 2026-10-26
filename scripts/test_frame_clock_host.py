#!/usr/bin/env python3
"""Compile production frame scheduling against host queue fixtures; no Android runtime or rendering."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'src/main/kotlin/smallthingz/ui/runtime/MonotonicFrameClock.kt',
    'src/main/kotlin/smallthingz/ui/platform/AndroidUiFrameClock.kt',
    'src/main/kotlin/smallthingz/ui/platform/AndroidUiDispatcher.kt',
]
# Other host suites have separate production dependencies and runners.
FIXTURES = [
    'src/testHost/java/android/os/Handler.java',
    'src/testHost/java/android/os/Looper.java',
    'src/testHost/java/android/view/Choreographer.java',
]
TESTS = [
    'src/testHost/kotlin/smallthingz/ui/platform/AndroidUiFrameClockHostTest.kt',
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True,
                        help='Kotlin compiler-embeddable and its dependencies, separated by the OS path separator')
    parser.add_argument('--test-classpath', required=True,
                        help='Kotlin stdlib, coroutines, annotations, JUnit4 and Hamcrest jars')
    parser.add_argument('--output', type=Path, required=True, help='New directory for classes, logs and exit receipts')
    parser.add_argument('--java-home', type=Path, default=None)
    parser.add_argument('--source-root', type=Path, default=ROOT,
                        help='Production checkout to test; fixtures always come from this script checkout')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    java = str(args.java_home / 'bin/java') if args.java_home else 'java'
    javac = str(args.java_home / 'bin/javac') if args.java_home else 'javac'
    sources = [args.source_root.resolve() / name for name in SOURCES]
    fixtures = [ROOT / name for name in FIXTURES]
    tests = [ROOT / name for name in TESTS]
    files = sources + fixtures + tests
    before = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    results = []

    def run(name, command):
        with (output / (name + '.log')).open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        results.append({'stage': name, 'argv': command, 'exit': result.returncode})
        (output / 'commands.json').write_text(json.dumps(results, indent=2) + '\n')
        return result.returncode

    code = run('javac', [javac, '--release', '17', '-d', str(classes), *map(str, fixtures)])
    if code == 0:
        code = run('kotlinc', [java, '-cp', args.compiler_classpath,
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
            '-module-name', 'kotlin_ui_frame_clock_host', '-classpath', str(classes) + os.pathsep + args.test_classpath,
            '-d', str(classes), *map(str, sources + tests)])
    if code == 0:
        code = run('tests', [java, '-cp', str(classes) + os.pathsep + args.test_classpath,
            'org.junit.runner.JUnitCore', 'smallthingz.ui.platform.AndroidUiFrameClockHostTest'])
    after = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    summary = {'exit': code, 'source_unchanged': before == after, 'source_sha256': before,
               'qualification': 'Host queue fixtures with actual production clock/dispatcher; not Android runtime, rendering or timing proof'}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    return code if before == after else 1


if __name__ == '__main__':
    raise SystemExit(main())

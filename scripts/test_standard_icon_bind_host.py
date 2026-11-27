#!/usr/bin/env python3
"""Execute the exact Kotlin bind method with counted host fixtures; no library/Android build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = 'e17bec64e38a2d4f1758f993dc1199a57b321a1a'
SOURCE = 'src/main/kotlin/smallthingz/ui/components/NativeStandardIconButtonView.kt'
FIXTURE = ROOT / 'tests/host-fixtures/icons/kotlin/StandardIconBindFixture.kt'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def method(source):
    begin = source.index('    fun bindAction(')
    end = source.index('    /** The host supplies', begin)
    return source[begin:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True)
    parser.add_argument('--test-classpath', required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    scratch = output / 'scratch'
    scratch.mkdir()
    baseline = subprocess.check_output(['git', 'show', BASE + ':' + SOURCE], cwd=ROOT).decode()
    source = (ROOT / SOURCE).read_text()
    before = method(baseline)
    expected = before.replace('        this.density = density; currentClick = onClick',
                              '        val densityChanged = this.density != density\n'
                              '        this.density = density; currentClick = onClick')
    expected = expected.replace('        requestLayout()', '        if (densityChanged) requestLayout()')
    assert source == baseline.replace(before, expected), 'Change must be the density-only guard'
    actual = method(source)
    assert actual == expected
    fixture = FIXTURE.read_text()
    assert fixture.count('// @@ACTUAL_BIND_ACTION@@') == 1
    variants = [
        ('actual', actual, True),
        ('original-unconditional-negative', before, False),
        ('missing-layout-negative', actual.replace('if (densityChanged) requestLayout()', 'Unit'), False),
        ('early-return-negative', actual.replace('val densityChanged = this.density != density',
            'val densityChanged = this.density != density\n        if (!densityChanged) return'), False),
        ('skipped-controller-negative', actual.replace('controller.bind(', 'if (densityChanged) controller.bind('), False),
    ]
    result = dict(schema='native-standard-icon-bind-host-v1', baseline_commit=BASE,
                  baseline_sha256=sha(baseline.encode()), source_sha256=sha(source.encode()),
                  fixture_sha256=sha(fixture.encode()), runner_sha256=sha(Path(__file__).read_bytes()),
                  outside_bind_method_unchanged=True, all_other_bind_statements_unchanged=True,
                  library_build=False, android_device=False, cases=[])
    result['tool_inputs'] = {p: sha(Path(p).read_bytes()) for cp in
        (args.compiler_classpath, args.test_classpath) for p in cp.split(os.pathsep)}
    java = str(args.java_home / 'bin/java')
    jvm = [java, '-XX:-UsePerfData', '-Xmx384m', '-Djava.io.tmpdir=' + str(scratch)]
    receipt = output / 'result.json'
    for name, body, positive in variants:
        folder = output / name
        folder.mkdir()
        classes = folder / 'classes'
        classes.mkdir()
        code = folder / 'StandardIconBindFixture.kt'
        code.write_text(fixture.replace('// @@ACTUAL_BIND_ACTION@@', body))
        row = dict(name=name, expected_success=positive, source_sha256=sha(code.read_bytes()),
                   compile_exit=None, run_exit=None, outcome='unknown')
        result['cases'].append(row)
        receipt.write_text(json.dumps(result, indent=2) + '\n')
        for stage, command in [
            ('compile', jvm + ['-cp', args.compiler_classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', args.test_classpath,
                '-d', str(classes), str(code)]),
            ('run', jvm + ['-cp', str(classes) + os.pathsep + args.test_classpath,
                           'StandardIconBindFixtureKt']),
        ]:
            row[stage + '_command'] = command
            receipt.write_text(json.dumps(result, indent=2) + '\n')
            with (folder / (stage + '.stdout')).open('xb') as stdout, \
                    (folder / (stage + '.stderr')).open('xb') as stderr:
                run = subprocess.run(command, stdout=stdout, stderr=stderr)
            row[stage + '_exit'] = run.returncode
            for suffix in ['stdout', 'stderr']:
                row[stage + '_' + suffix + '_sha256'] = sha((folder / (stage + '.' + suffix)).read_bytes())
            receipt.write_text(json.dumps(result, indent=2) + '\n')
            if stage == 'compile' and run.returncode:
                raise RuntimeError('Host compilation failed; original receipt retained, no retry')
        summary = re.findall(r'RESULT passed=(\d+) failed=(\d+)', (folder / 'run.stdout').read_text())
        assert len(summary) == 1
        row['passed'], row['failed'] = map(int, summary[0])
        row['control_passed'] = (row['run_exit'] == 0 and row['failed'] == 0) if positive else \
            (row['run_exit'] != 0 and row['failed'] > 0)
        row['outcome'] = 'terminal_observed'
        receipt.write_text(json.dumps(result, indent=2) + '\n')
        assert row['control_passed'], name
    assert (ROOT / SOURCE).read_text() == source and FIXTURE.read_text() == fixture
    result['all_controls_passed'] = True
    result['limits'] = ('Exact Kotlin bindAction control flow on host doubles only. Geometry/measure/layout '
                        'and Reverb glyph code unchanged by source comparison; Android traversal, ripple '
                        'rendering, input cancellation and pixel output were not executed or qualified.')
    receipt.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(receipt=str(receipt), sha256=sha(receipt.read_bytes()),
                         cases=[{k: r[k] for k in ['name', 'passed', 'failed', 'control_passed']}
                                for r in result['cases']]), indent=2))


if __name__ == '__main__':
    main()

"""Compile selected production/test sources using retained, hash-verified tools only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

os.umask(0o077)
W = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
OUT = args.out
OUT.mkdir(parents=True, exist_ok=False)
(OUT / 'scratch').mkdir()
(OUT / 'classes').mkdir()
prior_path = Path('/dev/shm/kotlin-ui-segmented-spacing-halley-20261125/.tmp/segmented-spacing-review/compile-02/result.json')
prior = json.loads(prior_path.read_bytes())
def desc(path):
    p = Path(path)
    b = p.read_bytes()
    return dict(path=str(p), bytes=len(b), sha256=hashlib.sha256(b).hexdigest())
for group in ('dependencies', 'compiler_inputs'):
    for p, sha in prior[group].items():
        assert desc(p)['sha256'] == sha, p
assert desc(prior['java']['path']) == prior['java']
java = prior['java']['path']
compiler = prior['argv'][prior['argv'].index('-cp') + 1]
native = '/workspace/control/.tmp/control-final-107/build-evidence/native-ui-release-runtime.jar'
sources = [W / p for p in (
    'src/main/kotlin/smallthingz/ui/components/NativeMaterialButtonView.kt',
    'src/androidTest/java/smallthingz/ui/components/NativeButtonOutlineLayoutIntegrationTest.kt',
    'src/androidTest/java/smallthingz/ui/components/NativeMenuAccessibilityIntegrationTest.kt',
)]
argv = [java, '-XX:-UsePerfData', '-Xmx512m', '-Djava.io.tmpdir=' + str(OUT / 'scratch'),
        '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-jvm-target', '17', '-Xfriend-paths=' + native, '-classpath', ':'.join(prior['dependencies']),
        '-d', str(OUT / 'classes'), *map(str, sources)]
r = dict(schema=1, state='unknown', base='93bea5f2c412aadb600b06f97063a70dc39367d6',
         sources=[desc(p) for p in sources], argv=argv, tools_origin=desc(prior_path),
         dependencies=prior['dependencies'], compiler_inputs=prior['compiler_inputs'], java=prior['java'],
         device_actions=False, apk_build=False, tests_executed=False, compute_timeout=None,
         limits='Selected files compiled against retained historical e17 APIs, not a whole-library build. Mounted Android tests remain NOT_RUN.')
def save(): (OUT / 'result.json').write_text(json.dumps(r, indent=2) + '\n')
save()
with (OUT / 'stdout.log').open('xb') as out, (OUT / 'stderr.log').open('xb') as err:
    completed = subprocess.run(argv, stdout=out, stderr=err)
r.update(state='terminal', exit_code=completed.returncode, stdout=desc(OUT / 'stdout.log'),
         stderr=desc(OUT / 'stderr.log'), classfiles=[desc(p) for p in sorted((OUT / 'classes').rglob('*.class'))],
         sources_unchanged=[desc(p) for p in sources] == r['sources'])
save()
print(json.dumps(dict(exit_code=completed.returncode, receipt=desc(OUT / 'result.json'))))
raise SystemExit(completed.returncode)

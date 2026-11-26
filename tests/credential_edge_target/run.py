#!/usr/bin/env python3
"""Narrow offline real-View edge dispatch. Compiles only CredentialFieldGroup + fixture."""
from pathlib import Path
import fcntl, hashlib, json, os, shutil, subprocess, sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path('/workspace/.tmp/control-council-browser-memory-20261127/C/04-credential-fixture')
Q = Path('/workspace/.tmp/control-icon-texture-update-20261127/render-qualification')
W = Path('/workspace/.tmp/control-cloud-ui-compile-20261126')
RT = Path('/dev/shm/control-icon-render-runtime-20261127')
JAVA = Path('/workspace/android-toolchain/jdk/bin')
SOURCE = 'src/main/kotlin/smallthingz/ui/common/CredentialFieldGroup.kt'
BASE = '05ed4596e505cf945a5d265399c61d6f11365cad'
label = sys.argv[1]
retirement = 'retirement' in label
assert 'reuse' in label or retirement
test_name = 'CredentialRouteRetirementTest' if retirement else 'ReusedCredentialRouteTest'
assert label.startswith(('parent-', 'candidate-')) and '/' not in label
lock = (W/'.final-984337.lock').open('r+b')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
assert shutil.disk_usage(OUT).free > 50 * 1024**2
run = OUT/label
run.mkdir()
classes = run/'classes'; classes.mkdir()
tmp = run/'tmp'; tmp.mkdir()
cache = RT/'tmp/robolectric-nativeruntime-assets'
assert all(len(list(cache.glob(group+'-*/.complete'))) == 1 for group in ['fonts','icu','hyphen-data']), 'Completed native data cache required, never restore/extract assets here'
(tmp/'robolectric-nativeruntime-assets').symlink_to(cache, target_is_directory=True)
parent = label.startswith('parent-')
source = ((OUT/'candidate-reuse-02/CredentialFieldGroup.kt').read_bytes() if retirement else
    subprocess.check_output(['git', 'show', BASE+':'+SOURCE], cwd=ROOT)) if parent else (ROOT/SOURCE).read_bytes()
production_sha = hashlib.sha256(source).hexdigest()
if retirement:
    call = b'target.visual.dispatchTouchEvent(forwarded)'
    assert source.count(call) == 1
    source = source.replace(call, b'CredentialRouteTestTransport.dispatch(target.visual, forwarded)')
copy = run/'CredentialFieldGroup.kt'; copy.write_bytes(source)
fixture = run/(test_name+'.kt'); fixture.write_bytes((Path(__file__).parent/fixture.name).read_bytes())
manifest = run/'AndroidManifest.xml'; shutil.copyfile(Q/'harness/AndroidManifest.xml', manifest)
apk = Path('/dev/shm/control-idle-a02-rotation-20261127/control-arm64-v8a.apk')
config = classes/'com/android/tools/test_config.properties'; config.parent.mkdir(parents=True)
config.write_text(f'android_merged_manifest={manifest}\nandroid_custom_package=dev.aicore.app\nandroid_resource_apk={apk}\n')
cp = [str(classes)] + [p for p in (Q/'classpath.txt').read_text().strip().split(':') if p != str(Q/'harness/classes')]
assert all(Path(p).exists() for p in cp)
shared = next(p for p in cp if 'runtime_library_classes_jar' in p)
old = json.loads(Path('/workspace/.tmp/control-idle-memory-20261127/noise-tests-01/compile-command.json').read_text())
compiler = old[old.index('-cp')+1]
classpath = ':'.join(cp)
def sha(p):
    with Path(p).open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()
bindings = {str(p):sha(p) for p in [copy, fixture, manifest, config, apk, *[Path(p) for p in cp if Path(p).is_file()]]}
receipt = {'base':BASE, 'variant':label, 'source_sha256':sha(copy), 'inputs':bindings,
    'production_source_sha256':production_sha,
    'fixture_transport_injection': 'Exactly one copied-source dispatch call replaced by fixture transport; only one ordinary MOVE may return false, all other events use real visual.dispatchTouchEvent. No production hook or event flag changes.' if retirement else None,
    'parent_source':str(OUT/'candidate-reuse-02/CredentialFieldGroup.kt') if retirement and parent else BASE if parent else str(ROOT/SOURCE),
    'scope':'Actual CredentialFieldGroup overlay + unchanged cached shared dependencies, native Android software graphics/View parent dispatch; no screenshots/device/app launch',
    'gradle_or_broad_build':False, 'native_assets_restored':False, 'stages':[]}
env = dict(os.environ); env.pop('JAVA_TOOL_OPTIONS', None); env['TMPDIR']=str(tmp)
commands = [
 ('compile', [str(JAVA/'java'), '-Xmx384m', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
     '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-Xfriend-paths='+shared, '-classpath', classpath,
     '-d', str(classes), str(copy), str(fixture)]),
 ('test', [str(JAVA/'java'), '-Xmx512m', '-ea', '-Djava.awt.headless=true', '-Djava.io.tmpdir='+str(tmp),
     '-Drobolectric.offline=true', '-Drobolectric.dependency.dir='+str(RT), '-Drobolectric.enabledSdks=36',
     '-cp', classpath, 'org.junit.runner.JUnitCore', 'smallthingz.ui.common.'+test_name])]
for stage, command in commands:
    (run/(stage+'-command.json')).write_text(json.dumps(command, indent=2)+'\n')
    with (run/(stage+'-stdout.txt')).open('wb') as stdout, (run/(stage+'-stderr.txt')).open('wb') as stderr:
        code = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, timeout=120).returncode
    receipt['stages'].append({'stage':stage, 'exit_code':code})
    receipt['inputs_unchanged'] = all(sha(p)==h for p,h in bindings.items())
    (run/'result.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(label, stage, code, flush=True)
    print((run/(stage+'-stdout.txt')).read_text()[-7500:], flush=True)
    print((run/(stage+'-stderr.txt')).read_text()[-2000:], flush=True)
    if code: sys.exit(code)

"""Exact-source outline callback/cache regressions with delivered-outline JVM doubles.

No Android, Gradle, downloads, timeout, or device calls. Output must be a new directory.
The double models synchronous callback -> RenderNode assignment, including a throwing
callback retaining the prior outline; it is not a rendering or performance test.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

os.umask(0o077)
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/main/kotlin/smallthingz/ui/components/NativeMaterialButtonView.kt'
BASE = '93bea5f2c412aadb600b06f97063a70dc39367d6'
INSERTION = '''            // Refreshes outside onLayout (including failed ones) invalidate its last-layout key.
            // Clear before reading density; only a successful layout invalidation commits a key.
            when {
                view === this@NativeMaterialButtonView -> buttonOutlineGeometry.valid = false
                view === content -> contentOutlineGeometry.valid = false
            }
'''

PREFIX = '''import kotlin.math.min
data class Outline(var width: Int = 0, var height: Int = 0, var radius: Float = 0f) {
    fun setRoundRect(l: Int, t: Int, r: Int, b: Int, value: Float) {
        width = r-l; height = b-t; radius = value
    }
}
abstract class ViewOutlineProvider { abstract fun getOutline(view: View, outline: Outline) }
open class View(var width: Int = 320, var height: Int = 96) {
    var invalidations = 0
    var layouts = 0
    var attached = true
    var throwBeforeCallback = false
    var throwAfterCallback = false
    var delivered: Outline? = null
    var outlineProvider: ViewOutlineProvider? = null
        set(value) { if (field !== value) { field = value; invalidateOutline() } }
    fun rebuildOutline() {
        if (!attached) return
        val provider = outlineProvider
        if (provider == null) { delivered = null; return }
        val next = Outline()
        provider.getOutline(this, next)
        check(!throwAfterCallback) { "after callback" }
        delivered = next.copy()
    }
    open fun invalidateOutline() {
        invalidations++
        check(!throwBeforeCallback) { "before callback" }
        rebuildOutline()
    }
    fun layout(l: Int, t: Int, r: Int, b: Int) { layouts++; width = r-l; height = b-t }
    open fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
}
class Input { var calls = 0; fun measured() { calls++ } }
class Role { val roleView = View() }
class Style(var radiusDp: Float? = 8f)
class NativeMaterialButtonView : View() {
    val content = View()
    val mergedRowAccessibility = Role()
    val input = Input()
    val style = Style()
    var density = 1f
    var failDensity = false
    private val layoutDensity: Float get() { check(!failDensity) { "density" }; return density }
'''

SUFFIX = '''
    init { outlineProvider = shape; content.outlineProvider = shape }
    fun owned() = shape
    fun step() = onLayout(false,0,0,width,height)
    fun counts() = invalidations to content.invalidations
    fun contentOnly(w: Int, h: Int) {
        content.width=w; content.height=h; invalidateLayoutOutline(content,contentOutlineGeometry)
    }
}
var assertions = 0
var failures = 0
var cases = 0
fun expect(name: String, actual: Any?, expected: Any?) {
    assertions++; check(actual == expected) { "$name: $actual != $expected" }
}
fun failure(message: String, action: () -> Unit) {
    try { action(); throw AssertionError("missing $message failure") }
    catch (e: IllegalStateException) { expect("failure", e.message, message) }
}
fun case(name: String, action: () -> Unit) {
    cases++
    try { action(); println("PASS $name") }
    catch (e: Throwable) { failures++; println("FAIL $name: ${e.message}") }
}
fun custom(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) =
        outline.setRoundRect(0,0,view.width,view.height,radius)
    override fun equals(other: Any?) = true // Must not turn logical equality into owned identity.
    override fun hashCode() = 0
}
fun main() {
    for (content in listOf(false, true)) {
        val label = if(content) "content" else "button"
        case("density ABA delivered outline $label") {
            val h=NativeMaterialButtonView(); h.step()
            val v=if(content) h.content else h
            expect("initial",v.delivered!!.radius,8f)
            h.density=2f; v.invalidateOutline()
            expect("intermediate delivered",v.delivered!!.radius,16f)
            h.density=1f; h.step()
            expect("returned delivered",v.delivered!!.radius,8f)
            val calls=h.counts(); h.step(); expect("stable again",h.counts(),calls)
        }
        case("failed custom to owned delivered outline $label") {
            val h=NativeMaterialButtonView(); h.step()
            val v=if(content) h.content else h
            v.outlineProvider=custom(3f)
            expect("custom delivered",v.delivered!!.radius,3f)
            h.failDensity=true
            failure("density") { v.outlineProvider=h.owned() }
            expect("owned assigned before failed rebuild",v.outlineProvider === h.owned(),true)
            expect("failed rebuild retains custom",v.delivered!!.radius,3f)
            h.failDensity=false; h.step()
            expect("recovered owned delivered",v.delivered!!.radius,8f)
        }
        case("external refresh dirties only target $label") {
            val h=NativeMaterialButtonView(); h.step()
            val v=if(content) h.content else h
            v.invalidateOutline(); val before=h.counts(); h.step()
            expect("one target refresh",h.counts(),if(content) before.first to before.second+1 else before.first+1 to before.second)
            val stable=h.counts(); h.step(); expect("restable",h.counts(),stable)
        }
        case("direct sample cannot relabel delivered intermediate $label") {
            val h=NativeMaterialButtonView(); h.step()
            val v=if(content) h.content else h
            h.density=2f; v.invalidateOutline(); h.density=1f
            val sampled=Outline(); h.owned().getOutline(v,sampled)
            expect("sample current geometry",sampled.radius,8f)
            expect("sample did not deliver",v.delivered!!.radius,16f)
            h.step(); expect("layout delivers current geometry",v.delivered!!.radius,8f)
        }
        case("custom null identity fallback $label") {
            val h=NativeMaterialButtonView(); h.step()
            val v=if(content) h.content else h
            v.outlineProvider=custom(3f); val before=v.invalidations
            h.step(); h.step(); expect("custom unconditional",v.invalidations,before+2)
            expect("custom retained",v.delivered!!.radius,3f)
            v.outlineProvider=null; val nullBefore=v.invalidations
            h.step(); h.step(); expect("null unconditional",v.invalidations,nullBefore+2)
            expect("null removes outline",v.delivered,null)
            v.outlineProvider=h.owned(); h.step(); expect("return radius",v.delivered!!.radius,8f)
            val stable=h.counts(); h.step(); expect("return stable",h.counts(),stable)
        }
    }
    case("stable geometry retains child role and input work") {
        val h=NativeMaterialButtonView(); h.step(); val before=h.counts()
        repeat(4) { h.step() }; expect("same outlines suppressed",h.counts(),before)
        expect("child layout",h.content.layouts,5); expect("role layout",h.mergedRowAccessibility.roleView.layouts,5)
        expect("input measured",h.input.calls,5)
        h.width=336; h.step(); expect("new width",h.content.delivered!!.width,336)
        h.height=112; h.step(); expect("new height",h.delivered!!.height,112)
        h.density=2f; h.step(); expect("new density",h.delivered!!.radius,16f)
        h.style.radiusDp=7f; h.step(); expect("new radius",h.delivered!!.radius,14f)
        h.height=8; h.step(); val clamped=h.counts(); h.density=3f; h.step()
        expect("density still matters when clamped",h.counts(),clamped.first+1 to clamped.second+1)
        expect("clamped",h.delivered!!.radius,4f)
        h.contentOnly(40,8); expect("independent content width",h.content.delivered!!.width,40)
        expect("button retained width",h.delivered!!.width,336)
    }
    case("nonfinite density and radius never cache") {
        for (value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val h=NativeMaterialButtonView(); h.density=value; h.step(); val before=h.counts(); h.step()
            expect("nonfinite density",h.counts(),before.first+1 to before.second+1)
            h.density=1f; h.step(); val stable=h.counts(); h.step(); expect("finite recovery",h.counts(),stable)
        }
        val h=NativeMaterialButtonView(); h.style.radiusDp=Float.NaN; h.step(); val before=h.counts(); h.step()
        expect("nonfinite radius",h.counts(),before.first+1 to before.second+1)
        h.style.radiusDp=8f; h.width=0; h.height=0; h.step(); val stable=h.counts(); h.step()
        expect("zero geometry caches",h.counts(),stable); expect("zero radius",h.delivered!!.radius,0f)
    }
    case("layout failure before callback cannot commit key") {
        val h=NativeMaterialButtonView(); h.step(); h.density=2f; h.throwBeforeCallback=true
        failure("before callback") { h.step() }; h.throwBeforeCallback=false; h.density=1f
        val before=h.invalidations; h.step(); expect("retry old key",h.invalidations,before+1)
        expect("old radius correct",h.delivered!!.radius,8f)
    }
    case("external failure after callback cannot retain key") {
        val h=NativeMaterialButtonView(); h.step(); h.outlineProvider=custom(3f); h.throwAfterCallback=true
        failure("after callback") { h.outlineProvider=h.owned() }
        expect("old delivered retained",h.delivered!!.radius,3f)
        h.throwAfterCallback=false; h.step(); expect("recovery",h.delivered!!.radius,8f)
    }
    case("attachment framework rebuild clears old key") {
        val h=NativeMaterialButtonView(); h.step(); h.attached=false; h.density=2f
        h.invalidateOutline(); expect("detached does not deliver",h.delivered!!.radius,8f)
        h.attached=true; h.rebuildOutline(); expect("attach intermediate",h.delivered!!.radius,16f)
        h.density=1f; h.step(); expect("layout restores",h.delivered!!.radius,8f)
    }
    println("RESULT cases=$cases assertions=$assertions failures=$failures")
    check(failures==0) { "$failures failed cases" }
}
'''


def desc(path):
    p = Path(path)
    with p.open('rb') as f:
        digest = hashlib.file_digest(f, 'sha256').hexdigest()
    return dict(path=str(p), bytes=p.stat().st_size, sha256=digest)


def extract(raw):
    start = raw.index('    private val shape = object : ViewOutlineProvider() {')
    end = raw.index('    init {', start)
    layout_start = raw.index('    override fun onLayout(', raw.index('    override fun containsPointerLayer'))
    layout_end = raw.index('    override fun onDraw', layout_start)
    radius = next(line for line in raw.splitlines() if line.startswith('    private fun radius('))
    return radius + '\n' + raw[start:end] + raw[layout_start:layout_end]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--tool-receipt', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    raw = SOURCE.read_text()
    base = subprocess.check_output(['git', 'show', BASE + ':' + str(SOURCE.relative_to(ROOT))], cwd=ROOT).decode()
    assert raw.count(INSERTION) == 1
    assert raw.replace(INSERTION, '', 1) == base, 'unexpected production delta'
    prior = json.loads(args.tool_receipt.read_bytes())
    for path, sha in prior['compiler_inputs'].items():
        assert desc(path)['sha256'] == sha
    assert desc(prior['java']['path']) == prior['java']
    java = prior['java']['path']
    compiler = prior['argv'][prior['argv'].index('-cp')+1]
    stdlib = next(path for path in prior['dependencies'] if '/kotlin-stdlib/' in path)
    assert desc(stdlib)['sha256'] == prior['dependencies'][stdlib]
    statement = '            outline.setRoundRect(0, 0, view.width, view.height, radius(view.width, view.height))\n'
    late = raw.replace(INSERTION, '', 1).replace(statement, statement + INSERTION, 1)
    variants = {'candidate': raw, 'parent': base, 'clear_after_callback_mutant': late}
    receipt = dict(state='running', base=BASE, source=desc(SOURCE), runner=desc(__file__),
                   production_delta_only_callback_invalidation=True, tools_origin=desc(args.tool_receipt),
                   java=prior['java'], compiler_inputs=prior['compiler_inputs'], stdlib=desc(stdlib), results=[],
                   device_actions=False, android_execution=False, apk_build=False, timeout=None)
    result_path = args.out / 'result.json'
    def save():
        result_path.write_text(json.dumps(receipt, indent=2) + '\n')
    save()
    for name, source in variants.items():
        out = args.out / name
        out.mkdir(); (out/'classes').mkdir(); (out/'scratch').mkdir()
        fixture = out/'OutlineCoherenceControls.kt'
        fixture.write_text(PREFIX + extract(source) + SUFFIX)
        argv = [java, '-XX:-UsePerfData', '-Xmx512m', '-Djava.io.tmpdir='+str(out/'scratch'), '-cp', compiler,
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
                '-classpath', stdlib, '-d', str(out/'classes'), str(fixture)]
        with (out/'compile.stdout').open('xb') as stdout, (out/'compile.stderr').open('xb') as stderr:
            compiled = subprocess.run(argv, stdout=stdout, stderr=stderr)
        entry = dict(name=name, fixture=desc(fixture), compile_exit=compiled.returncode, compile_argv=argv)
        receipt['results'].append(entry); save()
        if compiled.returncode != 0:
            raise RuntimeError(name+' compilation failed')
        invocation = [java, '-XX:-UsePerfData', '-Djava.io.tmpdir='+str(out/'scratch'), '-cp', str(out/'classes')+':'+stdlib,
                      'OutlineCoherenceControlsKt']
        with (out/'run.stdout').open('xb') as stdout, (out/'run.stderr').open('xb') as stderr:
            ran = subprocess.run(invocation, stdout=stdout, stderr=stderr)
        lines = (out/'run.stdout').read_text().splitlines()
        failed = [line for line in lines if line.startswith('FAIL ')]
        expected = not failed if name == 'candidate' else any('failed custom to owned' in line for line in failed)
        if name == 'parent':
            expected = expected and all(any('density ABA delivered outline '+target in line for line in failed)
                                        for target in ('button','content'))
        entry.update(run_exit=ran.returncode, run_argv=invocation, cases=lines,
                     matched=expected and ((ran.returncode == 0) == (name == 'candidate')),
                     logs=[desc(out/p) for p in ('compile.stdout','compile.stderr','run.stdout','run.stderr')])
        save()
    receipt.update(state='terminal', status='PASS' if all(r['matched'] for r in receipt['results']) else 'FAIL',
                   source_unchanged=desc(SOURCE)==receipt['source'])
    save()
    print(json.dumps(dict(status=receipt['status'], receipt=desc(result_path),
                          results=[dict(name=r['name'], compile_exit=r['compile_exit'], run_exit=r['run_exit'],
                                        summary=r['cases'][-1]) for r in receipt['results']])))
    raise SystemExit(0 if receipt['status']=='PASS' and receipt['source_unchanged'] else 1)


if __name__ == '__main__':
    main()

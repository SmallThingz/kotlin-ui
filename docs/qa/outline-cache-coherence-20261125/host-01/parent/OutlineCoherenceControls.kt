import kotlin.math.min
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
    private fun radius(w: Int, h: Int): Float = min(min(w, h) / 2f, style.radiusDp?.times(layoutDensity) ?: Float.MAX_VALUE)
    private val shape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius(view.width, view.height))
        }
    }
    private class OutlineGeometry {
        var valid = false
        var width = 0
        var height = 0
        var density = 0f
        var radius = 0f
    }
    private val buttonOutlineGeometry = OutlineGeometry()
    private val contentOutlineGeometry = OutlineGeometry()

    private fun invalidateLayoutOutline(view: View, previous: OutlineGeometry) {
        if (view.outlineProvider !== shape) {
            previous.valid = false
            view.invalidateOutline()
            return
        }
        val width = view.width
        val height = view.height
        val density = layoutDensity
        val radius = radius(width, height)
        val finite = density.isFinite() && radius.isFinite()
        if (finite && previous.valid && previous.width == width && previous.height == height &&
            previous.density == density && previous.radius == radius) return
        previous.valid = false
        view.invalidateOutline()
        previous.width = width
        previous.height = height
        previous.density = density
        previous.radius = radius
        previous.valid = finite
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width, height)
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
        invalidateLayoutOutline(this, buttonOutlineGeometry)
        invalidateLayoutOutline(content, contentOutlineGeometry)
        input.measured()
    }

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

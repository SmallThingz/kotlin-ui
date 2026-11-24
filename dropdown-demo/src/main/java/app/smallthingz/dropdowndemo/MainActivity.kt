package app.smallthingz.dropdowndemo

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import smallthingz.ui.common.ControlTheme
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.common.DropdownField
import smallthingz.ui.common.DropdownGroup
import smallthingz.ui.components.*
import smallthingz.ui.email.EmailComposerFields
import smallthingz.ui.email.EmailDropdownGroup
import smallthingz.ui.email.EmailRecipientRow
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import kotlin.math.roundToInt

/** Demo composition only. Every painted control comes from the actual library draft. */
class MainActivity : Activity() {
    private val appearance = NativeUiAppearance.Default
    private var dark = true
    private var rtl = false
    private var popup: NativeAnchoredDropdown? = null
    private var emailGroup: DropdownGroup? = null
    private var composerFields: EmailComposerFields? = null
    private var subject = "Team update"
    private val resourcesToClose = ArrayList<AutoCloseable>()
    private val values = linkedMapOf("Top edge" to "3", "Channels" to "mono", "Sample format" to "pcm16",
        "Sample rate" to "44100", "Audio source" to "recognition", "Input device" to "default", "Bottom edge" to "1",
        "Message sort" to "newest", "Archive folder" to "old", "From" to "personal")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) {
            dark = state.getBoolean("dark", true); rtl = state.getBoolean("rtl", false)
            subject = state.getString("subject") ?: subject
            values.keys.toList().forEach { key -> state.getString(key)?.let { values[key] = it } }
        }
        render()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("dark", dark); state.putBoolean("rtl", rtl)
        state.putString("subject", composerFields?.subjectEditor?.text?.toString() ?: subject)
        values.forEach { (key, value) -> state.putString(key, value) }
        super.onSaveInstanceState(state)
    }

    private fun disposeViews() {
        composerFields?.let { subject = it.subjectEditor.text.toString() }
        composerFields = null
        emailGroup?.close()
        emailGroup = null
        popup?.close()
        resourcesToClose.forEach { it.close() }
        resourcesToClose.clear()
    }
    override fun onStop() { emailGroup?.dismiss(); popup?.close(); super.onStop() }
    override fun onDestroy() { disposeViews(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    private fun render() {
        disposeViews()
        val palette = appearance.resolveColorScheme(this, dark)
        val density = Density(resources.displayMetrics.density, resources.configuration.fontScale)
        val resolver = createFontFamilyResolver(this)
        val root = FrameLayout(this).apply {
            isFocusableInTouchMode = true
            background = appearance.backdrops.pageBackground(palette)
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setOnApplyWindowInsetsListener { v, insets ->
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                insets
            }
        }
        val host = NativeAnchoredDropdown(this, root, appearance, palette, density, resolver).also { popup = it }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(page, FrameLayout.LayoutParams(-1, -2)) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        fun paragraph(label: String, role: NativeTypographyRole, muted: Boolean = false) = ParagraphView(
            this, appearance.typography[role], density, resolver, if (muted) palette.muted else palette.ink,
        ).apply { setText(label) }
        fun heading(label: String) { page.addView(paragraph(label, NativeTypographyRole.TITLE_MEDIUM).apply { setPadding(dp(2), dp(18), 0, dp(10)) }) }
        fun field(label: String, choices: List<Pair<String, String>>): NativeDropdown<String> {
            val labels = choices.toMap()
            val ids = choices.map { it.first }
            var content = NativeDropdownContent(label, checkNotNull(values[label]), ids) { id: String -> labels.getValue(id) }
            lateinit var view: NativeDropdown<String>
            view = host.createField(content) { selected ->
                values[label] = selected
                content = content.copy(selected = selected)
                view.bind(content, false, NativeDropdownColors(palette.field, palette.field, palette.muted, palette.ink, palette.border))
            }
            resourcesToClose += view
            return view
        }
        fun group() = appearance.groupSurfaces.settingsChoices.create(this).also { appearance.groupSurfaces.settingsChoices.bind(it, palette) }
        val top = group()
        top.addRow(field("Top edge", listOf("1" to "One", "2" to "Two", "3" to "Three")), NativeGroupLayout.LayoutParams(-1, -2))
        page.addView(top)
        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        heading("Audio")
        val audio = group()
        audio.addRow(field("Channels", listOf("mono" to "Mono", "stereo" to "Stereo")), NativeGroupLayout.LayoutParams(-1, -2))
        val format = field("Sample format", listOf("pcm16" to "16-bit PCM", "pcm24" to "24-bit PCM", "float" to "32-bit float"))
        val rates = listOf(8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000)
        val rate = field("Sample rate", rates.map { it.toString() to "$it Hz" })
        audio.addRow(NativeDropdownPair(this, format, rate), NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
        val source = field("Audio source", listOf("default" to "Default", "mic" to "Microphone", "recognition" to "Voice recognition", "unprocessed" to "Unprocessed", "camcorder" to "Camcorder"))
        val input = field("Input device", listOf("default" to "Automatic", "internal" to "Built-in", "usb" to "USB", "bluetooth" to "Bluetooth"))
        audio.addRow(NativeDropdownPair(this, source, input), NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
        page.addView(audio)
        page.addView(paragraph("Scroll to choose; release to snap into place. Tap the current slot to close, or tap another option to slide it into place.", NativeTypographyRole.BODY_SMALL, true).apply {
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
        fun button(label: String, action: () -> Unit) {
            val button = NativeMaterialButtonView(this, appearance.buttons.text, density, resolver, onClick = action)
            button.bind(label, true, palette.buttonPalette)
            resourcesToClose += button
            page.addView(button, LinearLayout.LayoutParams(-1, dp(48)))
        }
        button(if (dark) "Light theme" else "Dark theme") { dark = !dark; render() }
        button(if (rtl) "Left-to-right layout" else "Right-to-left layout") { rtl = !rtl; render() }
        heading("Email")
        val emailTheme = ControlTheme.resolve(this, dark)
        val mailChoices = EmailDropdownGroup(emailTheme, { root }) { root.hasWindowFocus() }
        emailGroup = mailChoices
        val mail = group()
        fun emailChoice(label: String, options: List<String>, labels: Map<String, String>): View {
            var value = DropdownContent(label, values.getValue(label), options, labels::getValue)
            lateinit var choice: DropdownField<String>
            val fixed = paragraph("", NativeTypographyRole.BODY_LARGE).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            fun showFixed() {
                val readOnly = options.size == 1 && value.selected == options.single()
                fixed.visibility = if (readOnly) View.VISIBLE else View.GONE
                if (readOnly) fixed.setText("$label: ${labels.getValue(value.selected)}")
            }
            choice = mailChoices.field(this, value) { selected ->
                values[label] = selected
                value = value.copy(selected = selected)
                choice.bind(value)
                showFixed()
            }
            resourcesToClose += choice
            showFixed()
            return FrameLayout(this).apply {
                addView(choice.view, FrameLayout.LayoutParams(-1, -2))
                addView(fixed, FrameLayout.LayoutParams(-1, -2))
            }
        }
        mail.addRow(emailChoice("Message sort", listOf("newest", "oldest", "subject"),
            mapOf("newest" to "Newest first", "oldest" to "Oldest first", "subject" to "Subject")),
            NativeGroupLayout.LayoutParams(-1, -2))
        mail.addRow(emailChoice("Archive folder", listOf("archive"), mapOf("old" to "Old archive", "archive" to "Archive")),
            NativeGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
        page.addView(mail)
        val composer = EmailComposerFields(this, emailTheme, "Subject")
        composerFields = composer
        val from = emailChoice("From", listOf("personal", "projects"), mapOf(
            "personal" to "Personal <one@example.invalid>", "projects" to "Projects <two@example.invalid>",
        ))
        composer.addRecipientRow(from, LinearLayout.LayoutParams(-1, -2))
        val recipient = EmailRecipientRow(this, emailTheme).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val address = TextView(context).apply { text = "team@example.test" }
            bindAddressText(address, secondary = false)
            addView(address)
        }
        composer.addRecipientRow(recipient, LinearLayout.LayoutParams(-1, -2))
        composer.subjectEditor.setText(subject)
        page.addView(composer.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val bottom = group()
        bottom.addRow(field("Bottom edge", listOf("1" to "One", "2" to "Two", "3" to "Three")), NativeGroupLayout.LayoutParams(-1, -2))
        page.addView(bottom)
        setContentView(root)
        root.requestApplyInsets()
    }
}

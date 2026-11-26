package smallthingz.ui.text.view

import android.app.Activity
import android.app.Application
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.text.ParagraphIntrinsics
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

/** Real EmojiCompat empty-metadata readiness; glyph/font rendering qualification is separate. */
@Suppress("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParagraphReadyFontObservationTest {
    @Test fun readyParagraphRecordsIdentityWithoutCreatingAnObservationLease() = withFixture(true) { f ->
        f.layout()
        val current = checkNotNull(f.view.currentParagraph).paragraphIntrinsics
        assertTrue(current.emojiProcessed)
        assertSame(current, field(f.view, "observedIntrinsics"))
        assertNull(field(f.view, "fontObservation"))
        f.view.setText("A different label")
        f.layout()
        val replacement = checkNotNull(f.view.currentParagraph).paragraphIntrinsics
        assertNotSame(current, replacement)
        assertTrue(replacement.emojiProcessed)
        assertSame(replacement, field(f.view, "observedIntrinsics"))
        assertNull(field(f.view, "fontObservation"))
    }

    @Test fun loadingSnapshotStillTakesCompletionBeforeRegistrationRacePath() = withFixture(false) { f ->
        f.layout()
        val original = checkNotNull(f.view.currentParagraph)
        val current = original.paragraphIntrinsics
        assertFalse(current.emojiProcessed)
        assertNotNull(field(f.view, "fontObservation"))
        // Reproduce completion after construction but before observation without a fake readiness
        // flag: retire the first lease, complete real EmojiCompat, then register that exact snapshot.
        invoke(f.view, "clearFontObservation")
        f.loader.complete()
        assertEquals(EmojiCompat.LOAD_STATE_SUCCEEDED, EmojiCompat.get().loadState)
        assertFalse(current.emojiProcessed)
        assertTrue(current.hasStaleResolvedFonts)
        ParagraphView::class.java.getDeclaredMethod("observeResolvedFonts", ParagraphIntrinsics::class.java)
            .apply { isAccessible = true }.invoke(f.view, current)
        assertNotNull("Loading snapshot must not take the ready early-return", field(f.view, "fontObservation"))
        shadowOf(Looper.getMainLooper()).idle()
        f.layout()
        val rebuilt = checkNotNull(f.view.currentParagraph)
        assertNotSame(original, rebuilt)
        assertTrue(rebuilt.paragraphIntrinsics.emojiProcessed)
        assertNull(field(f.view, "fontObservation"))
    }

    @Test fun queuedCompletionCannotKeepDetachedObservationAndReattachUsesReadySnapshot() = withFixture(false) { f ->
        f.layout()
        assertNotNull(field(f.view, "fontObservation"))
        f.loader.complete()
        f.root.removeView(f.view)
        assertNull(field(f.view, "observedIntrinsics"))
        assertNull(field(f.view, "fontObservation"))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(field(f.view, "observedIntrinsics"))
        assertNull(field(f.view, "fontObservation"))
        f.root.addView(f.view, FrameLayout.LayoutParams(320, FrameLayout.LayoutParams.WRAP_CONTENT))
        f.layout()
        assertTrue(checkNotNull(f.view.currentParagraph).paragraphIntrinsics.emojiProcessed)
        assertNull(field(f.view, "fontObservation"))
    }

    private fun withFixture(ready: Boolean, block: (Fixture) -> Unit) {
        val previous = if (EmojiCompat.isConfigured()) EmojiCompat.get() else null
        val loader = Loader()
        EmojiCompat.reset(object : EmojiCompat.Config(loader) {})
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            if (ready) loader.complete()
            block(Fixture(controller.get(), loader))
        } finally {
            controller.pause().stop().destroy()
            EmojiCompat.reset(previous)
        }
    }

    private class Loader : EmojiCompat.MetadataRepoLoader {
        private var callback: EmojiCompat.MetadataRepoLoaderCallback? = null
        override fun load(value: EmojiCompat.MetadataRepoLoaderCallback) { callback = value }
        fun complete() {
            val target = checkNotNull(callback)
            callback = null
            target.onLoaded(MetadataRepo.create(Typeface.DEFAULT))
        }
    }
    private class Fixture(activity: Activity, val loader: Loader) {
        val root = FrameLayout(activity)
        val view = ParagraphView(activity, TextStyle(fontSize = 20.sp), Density(1f),
            createFontFamilyResolver(activity), 0xff111111.toInt()).apply { setText("Audio ready") }
        init {
            activity.setContentView(root)
            root.addView(view, FrameLayout.LayoutParams(320, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun invoke(owner: Any, name: String) { owner.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(owner) }
}

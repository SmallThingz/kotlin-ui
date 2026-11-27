package smallthingz.ui.unit.fontscaling

import android.util.SparseArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real factory with a deterministic publication interleaving on host storage. */
class FontScaleConverterSnapshotHostTest {
    @Test
    fun insertionBeforeInterpolationIndicesDoesNotRetargetTheCurve() {
        assertStableAcrossPublication(request = 1.4f, inserted = 1.2f)
    }

    @Test
    fun insertionBelowTheFirstTableDoesNotRetargetTheIdentityInterpolation() {
        assertStableAcrossPublication(request = 1.08f, inserted = 1.05f)
    }

    @Test
    fun insertionDoesNotTurnAnOutOfBoundsScaleIntoAnInterpolation() {
        assertStableAcrossPublication(request = 2.5f, inserted = 1.2f)
    }

    @Test
    fun exactLookupUsesTheSameSnapshotAsItsIndex() {
        assertStableAcrossPublication(request = 1.5f, inserted = 1.2f)
    }

    @Test
    fun inactiveScalingDoesNotReadOrPublishLookupState() {
        val initial = initialTables()
        val watched = PublishingTable(initial, 100) { error("Unexpected lookup") }
        withTables(watched) {
            assertNull(FontScaleConverterFactory.forScale(1f))
            assertSame(watched, FontScaleConverterFactory.sLookupTables)
            assertEquals(0, watched.publications)
        }
    }

    private fun assertStableAcrossPublication(request: Float, inserted: Float) {
        val initial = initialTables()
        val expected = withTables(initial.clone()) {
            requireNotNull(FontScaleConverterFactory.forScale(request))
        }
        val insertedConverter = withTables(initial.clone()) {
            requireNotNull(FontScaleConverterFactory.forScale(inserted))
        }
        val replacement = initial.clone().apply {
            put((inserted * 100f).toInt(), insertedConverter)
        }
        val watched = PublishingTable(initial, (request * 100f).toInt()) {
            FontScaleConverterFactory.sLookupTables = replacement
        }
        withTables(watched) {
            val actual = requireNotNull(FontScaleConverterFactory.forScale(request))
            assertEquals(1, watched.publications)
            for (size in floatArrayOf(-48f, -12f, 0f, 1f, 8f, 10f, 14f, 20f, 30f, 100f, 180f)) {
                assertEquals(expected.convertSpToDp(size).toRawBits(), actual.convertSpToDp(size).toRawBits())
                assertEquals(expected.convertDpToSp(size).toRawBits(), actual.convertDpToSp(size).toRawBits())
            }
            val published = FontScaleConverterFactory.sLookupTables
            assertSame(insertedConverter, published[(inserted * 100f).toInt()])
            if (initial.indexOfKey((request * 100f).toInt()) < 0) {
                assertNotSame(replacement, published)
                assertSame(actual, published[(request * 100f).toInt()])
            }
            assertTrue(watched.indexOfKeyWithoutPublication((request * 100f).toInt()) ==
                initial.indexOfKey((request * 100f).toInt()))
        }
    }

    private fun initialTables(): SparseArray<FontScaleConverter> = SparseArray<FontScaleConverter>().apply {
        for (scale in floatArrayOf(1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
            put((scale * 100f).toInt(), FontScaleConverterTable(floatArrayOf(1f), floatArrayOf(scale)))
        }
    }

    private inline fun <T> withTables(tables: SparseArray<FontScaleConverter>, block: () -> T): T {
        val previous = FontScaleConverterFactory.sLookupTables
        FontScaleConverterFactory.sLookupTables = tables
        return try {
            block()
        } finally {
            FontScaleConverterFactory.sLookupTables = previous
        }
    }

    private class PublishingTable(
        source: SparseArray<FontScaleConverter>,
        private val requestedKey: Int,
        private val publish: () -> Unit,
    ) : SparseArray<FontScaleConverter>() {
        var publications = 0
            private set

        init {
            for (index in 0 until source.size()) put(source.keyAt(index), source.valueAt(index))
        }

        override fun indexOfKey(key: Int): Int {
            val index = super.indexOfKey(key)
            publishOnce(key)
            return index
        }

        override fun get(key: Int): FontScaleConverter? {
            val value = super.get(key)
            if (value != null) publishOnce(key)
            return value
        }

        private fun publishOnce(key: Int) {
            if (key == requestedKey && publications == 0) {
                publications++
                publish()
            }
        }

        fun indexOfKeyWithoutPublication(key: Int): Int = super.indexOfKey(key)
    }
}

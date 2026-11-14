package smallthingz.ui.interaction.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CachedAnimatorDurationScaleTest {
    @Test fun framesReadMemoryUntilSettingsObserverRefreshes() {
        var setting = 0.5f
        var reads = 0
        val cache = CachedAnimatorDurationScale { reads++; setting }
        setting = 2f
        repeat(120) { assertEquals(0.5f, cache.value, 0f) }
        assertEquals(1, reads)
        cache.refresh()
        repeat(120) { assertEquals(2f, cache.value, 0f) }
        assertEquals(2, reads)
    }

    @Test fun refreshPreservesDisabledFractionalAndSlowAnimationScales() {
        var setting = 1f
        val cache = CachedAnimatorDurationScale { setting }
        for (scale in listOf(0f, 0.5f, 2f, 10f, 1f)) {
            setting = scale
            cache.refresh()
            assertEquals(scale, cache.value, 0f)
        }
    }

    @Test fun failedRefreshDoesNotPublishAnInventedScale() {
        var failure = false
        val cache = CachedAnimatorDurationScale {
            if (failure) throw IllegalStateException("unavailable")
            0f
        }
        failure = true
        assertEquals(1000L, cache.refresh())
        assertEquals(0f, cache.value, 0f)
    }

    @Test fun unavailableProviderRetriesWithBoundedBackoffAndRecovers() {
        var failure = false
        var setting = 0.5f
        val cache = CachedAnimatorDurationScale {
            if (failure) throw IllegalStateException("unavailable")
            setting
        }
        failure = true
        for (delay in listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L)) {
            assertEquals(delay, cache.refresh())
            assertEquals(0.5f, cache.value, 0f)
        }
        failure = false
        setting = 0f
        assertNull(cache.refresh())
        assertEquals(0f, cache.value, 0f)
        failure = true
        assertEquals(1000L, cache.refresh())
    }

    @Test fun deniedInitialReadUsesDefaultWithoutPollingFramesAndRecovers() {
        var denied = true
        var reads = 0
        val cache = CachedAnimatorDurationScale {
            reads++
            if (denied) throw SecurityException("denied")
            2f
        }
        repeat(120) { assertEquals(1f, cache.value, 0f) }
        assertEquals(1, reads)
        assertEquals(1000L, cache.refresh())
        denied = false
        assertNull(cache.refresh())
        assertEquals(2f, cache.value, 0f)
        assertEquals(3, reads)
    }

    @Test fun observerSetupFailureRetriesOutsideFramesAndKeepsLastScale() {
        var unavailable = true
        var setups = 0
        var reads = 0
        val cache = CachedAnimatorDurationScale(beforeRefresh = {
            setups++
            if (unavailable) throw SecurityException("observer denied")
        }) { reads++; 0.5f }
        assertEquals(1000L, cache.refresh())
        repeat(120) { assertEquals(0.5f, cache.value, 0f) }
        assertEquals(1, setups)
        assertEquals(1, reads)
        assertEquals(2000L, cache.refresh())
        unavailable = false
        assertNull(cache.refresh())
        assertEquals(3, setups)
        assertEquals(2, reads)
    }

    @Test fun invalidSettingsUseDefaultWithoutLosingValidZeroOrFractionalScales() {
        var setting = Float.NaN
        val cache = CachedAnimatorDurationScale { setting }
        assertEquals(1f, cache.value, 0f)
        for (invalid in listOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f)) {
            setting = invalid
            assertNull(cache.refresh())
            assertEquals(1f, cache.value, 0f)
        }
        for (valid in listOf(0f, 0.5f, 10f)) {
            setting = valid
            assertNull(cache.refresh())
            assertEquals(valid, cache.value, 0f)
        }
    }

    @Test fun observerRefreshCannotOvertakeInitialReadAndRetryScheduling() {
        val reads = AtomicInteger()
        val secondRead = CountDownLatch(1)
        val cache = CachedAnimatorDurationScale {
            reads.incrementAndGet().also { if (it == 3) secondRead.countDown() }.toFloat()
        }
        val scheduling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val initial = workers.submit {
                cache.refresh {
                    scheduling.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(scheduling.await(5, TimeUnit.SECONDS))
            val observer = workers.submit { secondStarted.countDown(); cache.refresh() }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondRead.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            initial.get(5, TimeUnit.SECONDS)
            observer.get(5, TimeUnit.SECONDS)
            assertEquals(3f, cache.value, 0f)
            assertEquals(0L, secondRead.count)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}

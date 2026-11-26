package smallthingz.ui.graphics

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProceduralNoiseTest {
    @Test fun stripsReuseBoundedScratchAndPreserveEveryPixel() {
        val expected = generateNoisePixels()
        var scratch: IntArray? = null
        var nextY = 0
        var writes = 0
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(NOISE_TILE_SIZE * NOISE_STRIP_HEIGHT * 4)
        writeNoiseStrips { y, pixels ->
            assertEquals(nextY, y)
            assertEquals(1024 * 16, pixels.size)
            if (scratch == null) scratch = pixels else assertSame(scratch, pixels)
            for (index in pixels.indices) assertEquals(expected[y * NOISE_TILE_SIZE + index], pixels[index])
            bytes.asIntBuffer().put(pixels)
            digest.update(bytes.array())
            nextY += NOISE_STRIP_HEIGHT
            writes++
        }
        assertEquals(64, writes)
        assertEquals(NOISE_TILE_SIZE, nextY)
        assertEquals("04bafc239a3167b4169e12ac5b3d4b402024bc9bf76256226fd928c7e210420a",
            digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test fun interruptedStripWriteCannotPublishPartialTileOrRetryGeneration() {
        val failure = IllegalStateException("pixel write failed")
        var writes = 0
        var attempts = 0
        var completions = 0
        val cache = NoiseTileCache {
            attempts++
            writeNoiseStrips { y, _ ->
                writes++
                if (y == NOISE_STRIP_HEIGHT * 3) throw failure
            }
            Any()
        }
        repeat(2) { cache.start({ it() }, { completions++ }) }
        assertEquals(4, writes)
        assertEquals(1, attempts)
        assertEquals(1, completions)
        assertNull(cache.valueOrNull())
        assertSame(failure, cache.failureOrNull())
    }

    @Test fun grainHasStableFullTileAndReleasedPalette() {
        val pixels = generateNoisePixels()
        assertEquals(1024 * 1024, pixels.size)
        val bytes = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("04bafc239a3167b4169e12ac5b3d4b402024bc9bf76256226fd928c7e210420a", digest)
        val counts = IntArray(26)
        for (pixel in pixels) {
            val alpha = pixel ushr 24
            val rgb = pixel and 0x00ffffff
            assertTrue(alpha in 5..17)
            assertTrue(rgb == 0 || rgb == 0x00ffffff)
            counts[alpha - 5 + if (rgb == 0) 0 else 13]++
        }
        // Fixed seed, not a probabilistic test: keep every released grain level well populated.
        assertTrue(counts.all { it in 39_000..42_000 })
        pixels[0] = 0
        assertEquals(0x0f000000, generateNoisePixels()[0])
    }

    @Test fun simultaneousRequestsScheduleOneSharedValue() {
        val value = Any()
        val generated = AtomicInteger()
        val scheduled = AtomicInteger()
        val completed = AtomicInteger()
        val cache = NoiseTileCache { generated.incrementAndGet(); value }
        val begin = CountDownLatch(1)
        val done = CountDownLatch(8)
        val jobs = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        repeat(8) {
            Thread {
                begin.await()
                cache.start({ scheduled.incrementAndGet(); jobs.add(it) }, { completed.incrementAndGet() })
                done.countDown()
            }.start()
        }
        begin.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, scheduled.get())
        assertNull(cache.valueOrNull())
        jobs.remove().invoke()
        assertSame(value, cache.valueOrNull())
        repeat(10) { cache.start({ fail("Ready cache rescheduled") }, { fail("Ready cache republished") }) }
        assertEquals(1, generated.get())
        assertEquals(1, completed.get())
    }

    @Test fun generationFailureIsPublishedOnceAndNeverRetriedPerDraw() {
        val failure = IllegalStateException("generation failed")
        var attempts = 0
        var completions = 0
        val cache = NoiseTileCache<Any> { attempts++; throw failure }
        repeat(10) { cache.start({ it() }, { completions++ }) }
        assertSame(failure, cache.failureOrNull())
        assertNull(cache.valueOrNull())
        assertEquals(1, attempts)
        assertEquals(1, completions)
    }

    @Test fun schedulingFailureDoesNotLeavePendingGenerationOrRunLateWork() {
        val failure = IllegalStateException("worker unavailable")
        var lateWork: (() -> Unit)? = null
        var generated = 0
        var completions = 0
        val cache = NoiseTileCache { generated++; Any() }
        cache.start({ lateWork = it; throw failure }, { completions++ })
        lateWork!!.invoke()
        assertSame(failure, cache.failureOrNull())
        assertEquals(0, generated)
        assertEquals(1, completions)
    }

    @Test fun schedulerErrorAfterCompletionCannotReplacePublishedValue() {
        val value = Any()
        val failure = IllegalStateException("late scheduler error")
        var completions = 0
        val cache = NoiseTileCache { value }
        try {
            cache.start({ it(); throw failure }, { completions++ })
            fail("Late error was swallowed")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertSame(value, cache.valueOrNull())
        assertNull(cache.failureOrNull())
        assertEquals(1, completions)
    }
    @Test fun retirementBeforeQueuedWorkPreventsAllocationAndCompletion() {
        var generated = 0
        var completed = 0
        var queued: (() -> Unit)? = null
        val cache = NoiseTileCache { generated++; Any() }
        cache.start({ queued = it }, { completed++ })
        cache.retire()
        cache.retire()
        checkNotNull(queued).invoke()
        cache.start({ fail("Retired generation was rescheduled") }, { completed++ })
        assertEquals(0, generated)
        assertEquals(0, completed)
        assertNull(cache.valueOrNull())
        assertNull(cache.failureOrNull())
    }

    @Test fun retirementDuringGenerationRejectsLateValueWithoutBlockingReplacement() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val completions = AtomicInteger()
        val oldValue = Any()
        val old = NoiseTileCache {
            entered.countDown()
            check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
            oldValue
        }
        old.start({ work ->
            Thread {
                try { work() } catch (failure: Throwable) { workerFailure.set(failure) }
                finally { done.countDown() }
            }.start()
        }, { completions.incrementAndGet() })
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            old.retire()
            val replacementValue = Any()
            val replacement = NoiseTileCache { replacementValue }
            replacement.start({ it() }, { completions.incrementAndGet() })
            assertSame(replacementValue, replacement.valueOrNull())
            finish.countDown()
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(workerFailure.get())
            assertNull(old.valueOrNull())
            assertNull(old.failureOrNull())
            assertEquals(1, completions.get())
            assertSame(replacementValue, replacement.valueOrNull())
        } finally { finish.countDown() }
    }

    @Test fun retirementDropsPublishedValueAndReplacementGeneratesOnlyOnce() {
        val value = Any()
        val old = NoiseTileCache { value }
        old.start({ it() }, {})
        assertSame(value, old.valueOrNull())
        old.retire()
        assertNull(old.valueOrNull())
        old.start({ fail("Retired published cache restarted") }, {})
        var generated = 0
        val replacement = NoiseTileCache { generated++; value }
        repeat(3) { replacement.start({ it() }, {}) }
        assertEquals(1, generated)
        assertSame(value, replacement.valueOrNull())
    }

    @Test fun retirementDuringFailedGenerationDoesNotRetainFailureOrNotify() {
        lateinit var cache: NoiseTileCache<Any>
        cache = NoiseTileCache {
            cache.retire()
            throw IllegalStateException("Retired work failed")
        }
        cache.start({ it() }, { fail("Retired generation notified observers") })
        assertNull(cache.valueOrNull())
        assertNull(cache.failureOrNull())
        cache.start({ fail("Retired failed generation restarted") }, {})
    }

}

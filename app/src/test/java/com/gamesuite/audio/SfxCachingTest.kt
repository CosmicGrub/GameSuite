package com.gamesuite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SfxCachingTest {
    @Test
    fun cacheRendersEachKindOnlyOnce() {
        val calls = HashMap<SfxKind, Int>()
        val cache = SfxPcmCache { kind ->
            calls[kind] = (calls[kind] ?: 0) + 1
            ShortArray(4) { it.toShort() }
        }
        val first = cache.get(SfxKind.WHOOSH)
        repeat(10) { assertSame(first, cache.get(SfxKind.WHOOSH)) }
        cache.get(SfxKind.LIGHT_TICK)
        assertEquals(1, calls[SfxKind.WHOOSH])
        assertEquals(1, calls[SfxKind.LIGHT_TICK])
        assertEquals(2, cache.size())
    }

    @Test
    fun limiterNeverExceedsMaxAndRecovers() {
        val limiter = SfxSlotLimiter(2)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        limiter.release()
        assertEquals(1, limiter.active())
        assertTrue(limiter.tryAcquire())
        repeat(5) { limiter.release() }
        assertEquals(0, limiter.active())
    }

    @Test
    fun durationIgnoresPanning() {
        assertEquals(1000L, sfxDurationMillis(44100))
        assertEquals(0L, sfxDurationMillis(0))
    }
}

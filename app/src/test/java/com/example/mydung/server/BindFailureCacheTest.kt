package com.example.mydung.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BindFailureCacheTest {

    @Test
    fun freshCache_doesNotSkip() {
        val cache = BindFailureCache()
        assertFalse(cache.shouldSkipBind(1_000L))
    }

    @Test
    fun afterFailure_skipsWithinCooldown() {
        val cache = BindFailureCache()
        cache.recordFailure(1_000L)
        assertTrue(cache.shouldSkipBind(1_001L))
        assertTrue(cache.shouldSkipBind(1_000L + BindFailureCache.DEFAULT_COOLDOWN_MS - 1))
    }

    @Test
    fun afterCooldown_triesAgain() {
        val cache = BindFailureCache()
        cache.recordFailure(1_000L)
        assertFalse(cache.shouldSkipBind(1_000L + BindFailureCache.DEFAULT_COOLDOWN_MS))
        assertFalse(cache.shouldSkipBind(1_000L + BindFailureCache.DEFAULT_COOLDOWN_MS + 1))
    }

    @Test
    fun clear_allowsRetryImmediately() {
        val cache = BindFailureCache()
        cache.recordFailure(1_000L)
        assertTrue(cache.shouldSkipBind(2_000L))
        cache.clear()
        assertFalse(cache.shouldSkipBind(2_000L))
    }

    @Test
    fun secondFailure_refreshesCooldown() {
        val cache = BindFailureCache()
        cache.recordFailure(1_000L)
        cache.recordFailure(2_000L)
        assertTrue(cache.shouldSkipBind(2_000L + BindFailureCache.DEFAULT_COOLDOWN_MS - 1))
        assertFalse(cache.shouldSkipBind(2_000L + BindFailureCache.DEFAULT_COOLDOWN_MS))
    }
}

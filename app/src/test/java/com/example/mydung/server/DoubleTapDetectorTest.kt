package com.example.mydung.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapDetectorTest {

    @Test
    fun firstTap_neverDouble() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
    }

    @Test
    fun twoTapsSameIndexWithinThreshold_isDouble() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(3, 1000L))
        assertTrue(d.tap(3, 1200L)) // 200ms < 300ms
    }

    @Test
    fun twoTapsSameIndexAtThreshold_isDouble() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
        assertTrue(d.tap(0, 1300L)) // đúng 300ms vẫn tính
    }

    @Test
    fun twoTapsTooSlow_notDouble() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
        assertFalse(d.tap(0, 1301L)) // 301ms: quá ngưỡng
    }

    @Test
    fun twoTapsDifferentIndex_notDouble() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
        assertFalse(d.tap(1, 1100L)) // khác phần tử
    }

    @Test
    fun twoTapsSameMillisecond_notDouble() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
        // Cùng mili giây (delta = 0) không tính — tránh 1 sự kiện chạm bị
        // đếm 2 lần.
        assertFalse(d.tap(0, 1000L))
    }

    @Test
    fun thirdQuickTap_isDoubleAgain() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
        assertTrue(d.tap(0, 1200L))
        assertTrue(d.tap(0, 1400L)) // mốc đã cập nhật: tap 3 cũng là double mới
    }

    @Test
    fun reset_forgetsPreviousTap() {
        val d = DoubleTapDetector()
        assertFalse(d.tap(0, 1000L))
        d.reset()
        assertFalse(d.tap(0, 1100L))
    }

    @Test
    fun customThreshold_respected() {
        val d = DoubleTapDetector(thresholdMs = 100L)
        assertFalse(d.tap(0, 1000L))
        assertFalse(d.tap(0, 1150L))
        assertTrue(d.tap(0, 1200L)) // 50ms sau lần 2
    }
}

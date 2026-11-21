package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchProtocolTest {

    @Test
    fun display_valid() {
        assertEquals(
            TouchProtocol.DisplayInfo(2560, 1600, 1, 0),
            TouchProtocol.parseDisplay("DISPLAY 2560 1600 1 0"),
        )
    }

    @Test
    fun display_invalid() {
        assertNull(TouchProtocol.parseDisplay("DISPLAY 2560 1600 1")) // thiếu tham số
        assertNull(TouchProtocol.parseDisplay("DISPLAY 0 1600 1 0")) // rộng = 0
        assertNull(TouchProtocol.parseDisplay("DISPLAY 2560 -5 1 0")) // cao âm
        assertNull(TouchProtocol.parseDisplay("DISPLAY 2560 1600 4 0")) // xoay ngoài 0..3
        assertNull(TouchProtocol.parseDisplay("DISPLAY 2560 1600 1 -1")) // displayId âm
        assertNull(TouchProtocol.parseDisplay("DISPLAY a b c d"))
        assertNull(TouchProtocol.parseDisplay("DISPLA 2560 1600 1 0"))
    }

    @Test
    fun map_validAndInvalid() {
        assertEquals(true, TouchProtocol.parseMap("MAP 1"))
        assertEquals(false, TouchProtocol.parseMap("MAP 0"))
        assertNull(TouchProtocol.parseMap("MAP 2"))
        assertNull(TouchProtocol.parseMap("MAP"))
        assertNull(TouchProtocol.parseMap("MAP on"))
    }

    @Test
    fun touchCfg_validSubset() {
        val values = TouchProtocol.parseTouchCfg("TOUCHCFG sensX=1.2 joyR=0.08")
        assertEquals(mapOf("sensX" to 1.2f, "joyR" to 0.08f), values)
    }

    @Test
    fun touchCfg_invalid() {
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG")) // không có cặp nào
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG sensX=abc")) // không phải số
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG sensX")) // thiếu =
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG unknown=1")) // khóa lạ
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG sensX=NaN")) // không hữu hạn
    }

    @Test
    fun testTouch_tapValid() {
        assertEquals(
            TouchProtocol.TestTouch.Tap(0.5f, 0.5f),
            TouchProtocol.parseTestTouch("TESTTOUCH TAP 0.5 0.5"),
        )
    }

    @Test
    fun testTouch_swipeValid() {
        assertEquals(
            TouchProtocol.TestTouch.Swipe(0.3f, 0.5f, 0.7f, 0.5f, 500),
            TouchProtocol.parseTestTouch("TESTTOUCH SWIPE 0.3 0.5 0.7 0.5 500"),
        )
    }

    @Test
    fun testTouch_twoValid() {
        assertEquals(
            TouchProtocol.TestTouch.Two(0.4f, 0.4f, 0.6f, 0.6f),
            TouchProtocol.parseTestTouch("TESTTOUCH TWO 0.4 0.4 0.6 0.6"),
        )
    }

    @Test
    fun testTouch_invalid() {
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH TAP 1.5 0.5")) // tỉ lệ > 1
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH TAP -0.1 0.5")) // tỉ lệ < 0
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH TAP 0.5")) // thiếu tham số
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH SWIPE 0.3 0.5 0.7 0.5 10")) // ms quá nhỏ
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH SWIPE 0.3 0.5 0.7 0.5 99999")) // ms quá lớn
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH PINCH 0.5 0.5")) // loại lạ
        assertNull(TouchProtocol.parseTestTouch("TESTTOUCH"))
    }

    @Test
    fun formatMapStatus_exact() {
        val s = TouchProtocol.formatMapStatus(
            mapOn = true,
            display = TouchProtocol.DisplayInfo(2560, 1600, 1, 0),
            pointers = 2,
            latencyMs = 4,
            injectErr = 0,
            injectOk = true,
        )
        assertEquals(
            "MAP map=1 display=2560x1600 pointers=2 latencyMs=4 injectErr=0 injectOk=1 injector=reflect mode=camera cursor=0.50,0.50",
            s,
        )
        val s2 = TouchProtocol.formatMapStatus(false, null, 0, 0, 3, false)
        assertEquals(
            "MAP map=0 display=none pointers=0 latencyMs=0 injectErr=3 injectOk=0 injector=reflect mode=camera cursor=0.50,0.50",
            s2,
        )
        assertTrue(s2.contains("display=none"))
    }

    @Test
    fun formatMapStatus_cursorMode() {
        val s = TouchProtocol.formatMapStatus(
            mapOn = true,
            display = TouchProtocol.DisplayInfo(2560, 1600, 1, 0),
            pointers = 1,
            latencyMs = 2,
            injectErr = 0,
            injectOk = true,
            mode = "cursor",
            cursorX = 0.58f,
            cursorY = 0.25f,
        )
        assertTrue(s.contains("mode=cursor"))
        assertTrue(s.contains("cursor=0.58,0.25"))
    }

    @Test
    fun formatMapStatus_evdev() {
        val s = TouchProtocol.formatMapStatus(
            mapOn = true,
            display = TouchProtocol.DisplayInfo(2560, 1600, 1, 0),
            pointers = 1,
            latencyMs = 2,
            injectErr = 0,
            injectOk = true,
            injector = "evdev",
        )
        assertTrue(s.contains("injector=evdev"))
        assertTrue(s.endsWith("cursor=0.50,0.50"))
    }

    @Test
    fun parseInject_validAndInvalid() {
        assertEquals(InjectorMode.REFLECT, TouchProtocol.parseInject("INJECT reflect"))
        assertEquals(InjectorMode.EVDEV, TouchProtocol.parseInject("INJECT evdev"))
        assertNull(TouchProtocol.parseInject("INJECT"))
        assertNull(TouchProtocol.parseInject("INJECT foo"))
        assertNull(TouchProtocol.parseInject("INJECT reflect extra"))
        assertNull(TouchProtocol.parseInject("INJECT REFLECT")) // phân biệt hoa/thường
    }
}
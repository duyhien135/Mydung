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
    fun cursorMode_validAndInvalid() {
        assertEquals(true, TouchProtocol.parseCursorMode("CURSORMODE 1"))
        assertEquals(false, TouchProtocol.parseCursorMode("CURSORMODE 0"))
        assertNull(TouchProtocol.parseCursorMode("CURSORMODE 2"))
        assertNull(TouchProtocol.parseCursorMode("CURSORMODE"))
        assertNull(TouchProtocol.parseCursorMode("CURSORMODE on"))
        assertNull(TouchProtocol.parseCursorMode("MAP 1"))
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
            "MAP map=1 display=2560x1600 pointers=2 latencyMs=4 injectErr=0 injectOk=1 injector=reflect mode=camera cursor=0.50,0.50 overlay=0 mouse=1 grab=none",
            s,
        )
        val s2 = TouchProtocol.formatMapStatus(false, null, 0, 0, 3, false)
        assertEquals(
            "MAP map=0 display=none pointers=0 latencyMs=0 injectErr=3 injectOk=0 injector=reflect mode=camera cursor=0.50,0.50 overlay=0 mouse=1 grab=none",
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
        assertTrue(s.contains("cursor=0.50,0.50"))
        // Build-9: có overlay=0 (lớp phủ không bị ẩn); build-11: cuối dòng
        // thêm mouse=1 grab=none.
        assertTrue(s.contains("overlay=0"))
        assertTrue(s.endsWith("grab=none"))
    }

    @Test
    fun formatMapStatus_overlayHidden() {
        val s = TouchProtocol.formatMapStatus(
            mapOn = true,
            display = null,
            pointers = 0,
            latencyMs = 0,
            injectErr = 0,
            injectOk = true,
            overlayHidden = true,
        )
        assertTrue(s.contains("overlay=1"))
        assertTrue(s.endsWith("grab=none"))
    }

    @Test
    fun parsePollRate_validAndInvalid() {
        assertEquals(125, TouchProtocol.parsePollRate("POLLRATE 125"))
        assertEquals(250, TouchProtocol.parsePollRate("POLLRATE 250"))
        assertEquals(500, TouchProtocol.parsePollRate("POLLRATE 500"))
        assertEquals(1000, TouchProtocol.parsePollRate("POLLRATE 1000"))
        // Build-11: thêm 60 Hz và 0 = NO LIMIT.
        assertEquals(60, TouchProtocol.parsePollRate("POLLRATE 60"))
        assertEquals(0, TouchProtocol.parsePollRate("POLLRATE 0"))
        assertNull(TouchProtocol.parsePollRate("POLLRATE 2000"))
        assertNull(TouchProtocol.parsePollRate("POLLRATE abc"))
        assertNull(TouchProtocol.parsePollRate("POLLRATE"))
        assertNull(TouchProtocol.parsePollRate("POLLRATE 500 extra"))
    }

    @Test
    fun parseOverlay_validAndInvalid() {
        assertEquals(true, TouchProtocol.parseOverlay("OVERLAY 1"))
        assertEquals(false, TouchProtocol.parseOverlay("OVERLAY 0"))
        assertNull(TouchProtocol.parseOverlay("OVERLAY 2"))
        assertNull(TouchProtocol.parseOverlay("OVERLAY"))
        assertNull(TouchProtocol.parseOverlay("OVERLAY 1 extra"))
    }

    @Test
    fun parseTouchCfg_build9Keys() {
        val v = TouchProtocol.parseTouchCfg("TOUCHCFG dampX=3 dampY=5 cursorSpeed=1.5 overlayKey=68")
        assertEquals(3f, v?.get("dampX"))
        assertEquals(5f, v?.get("dampY"))
        assertEquals(1.5f, v?.get("cursorSpeed"))
        assertEquals(68f, v?.get("overlayKey"))
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG foo=1")) // khóa lạ: từ chối cả lệnh
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

    // ── Build-11: MOUSE, mouse=/grab=, khóa TOUCHCFG mới ──

    @Test
    fun parseMouse_validAndInvalid() {
        assertEquals(true, TouchProtocol.parseMouse("MOUSE 1"))
        assertEquals(false, TouchProtocol.parseMouse("MOUSE 0"))
        assertNull(TouchProtocol.parseMouse("MOUSE 2"))
        assertNull(TouchProtocol.parseMouse("MOUSE"))
        assertNull(TouchProtocol.parseMouse("MOUSE on"))
        assertNull(TouchProtocol.parseMouse("MAP 1"))
    }

    @Test
    fun formatMapStatus_mouseOffAndGrab() {
        val s = TouchProtocol.formatMapStatus(
            mapOn = true,
            display = null,
            pointers = 0,
            latencyMs = 0,
            injectErr = 0,
            injectOk = true,
            mouseEnabled = false,
            grabMethod = "lc-mut",
        )
        assertTrue(s.contains("mouse=0"))
        assertTrue(s.endsWith("grab=lc-mut"))
    }

    @Test
    fun parseTouchCfg_build11Keys() {
        val v = TouchProtocol.parseTouchCfg("TOUCHCFG cursorRegion=2 mouseKey=59")
        assertEquals(2f, v?.get("cursorRegion"))
        assertEquals(59f, v?.get("mouseKey"))
        assertNull(TouchProtocol.parseTouchCfg("TOUCHCFG foo=1")) // khóa lạ: từ chối cả lệnh
    }
}
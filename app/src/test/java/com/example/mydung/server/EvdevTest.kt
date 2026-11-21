package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvdevTest {

    private val xRange = Evdev.AbsRange(0, 2000)
    private val yRange = Evdev.AbsRange(0, 1000)

    private fun spec(action: Int, vararg pointers: TouchEngine.Pointer) =
        TouchEngine.MotionEventSpec(action, pointers.toList(), 1000L, 2000L)

    // ---------- Mã hóa struct input_event ----------

    @Test
    fun encodeFrame_exactBytes() {
        val bytes = Evdev.encodeFrame(
            listOf(Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 1000)),
            1_700_000_000_123L,
        )
        assertEquals(24, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1_700_000_000L, buf.long) // tv_sec
        assertEquals(123_000L, buf.long) // tv_usec
        assertEquals(3, buf.short.toInt()) // type EV_ABS
        assertEquals(53, buf.short.toInt()) // code ABS_MT_POSITION_X
        assertEquals(1000, buf.int) // value
    }

    @Test
    fun encodeFrame_multipleEvents() {
        val bytes = Evdev.encodeFrame(
            listOf(
                Evdev.EvdevEvent(0, 0, 0),
                Evdev.EvdevEvent(1, 0x14a, 1),
                Evdev.EvdevEvent(3, 0x39, -1), // value âm (TRACKING_ID=-1)
            ),
            0L,
        )
        assertEquals(72, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(48 + 20) // event thứ 3, trường value (8+8+2+2)
        assertEquals(-1, buf.int)
    }

    // ---------- Dịch đặc tả MotionEvent ----------

    @Test
    fun translate_down() {
        val events = Evdev.translateSpec(
            spec(TouchEngine.ACTION_DOWN, TouchEngine.Pointer(0, 500f, 250f)),
            1000, 500, xRange, yRange,
        )
        assertEquals(
            listOf(
                Evdev.EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOUCH, 1),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, 0),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 1000),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, 500),
                Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0),
            ),
            events,
        )
    }

    @Test
    fun translate_pointerDown_onlyNewPointer() {
        val action = TouchEngine.ACTION_POINTER_DOWN or
            (1 shl TouchEngine.ACTION_POINTER_INDEX_SHIFT)
        val events = Evdev.translateSpec(
            spec(
                action,
                TouchEngine.Pointer(0, 100f, 100f),
                TouchEngine.Pointer(2, 800f, 400f),
            ),
            1000, 500, xRange, yRange,
        )
        // Chỉ ngón mới (index 1, id 2) được gửi, không có BTN_TOUCH.
        assertEquals(
            listOf(
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 2),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, 2),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 1600),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, 800),
                Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0),
            ),
            events,
        )
    }

    @Test
    fun translate_move_allPointers() {
        val events = Evdev.translateSpec(
            spec(
                TouchEngine.ACTION_MOVE,
                TouchEngine.Pointer(0, 100f, 100f),
                TouchEngine.Pointer(1, 200f, 300f),
            ),
            1000, 500, xRange, yRange,
        )
        assertEquals(
            listOf(
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 200),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, 200),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 1),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 400),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, 600),
                Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0),
            ),
            events,
        )
    }

    @Test
    fun translate_up_lastPointer() {
        val events = Evdev.translateSpec(
            spec(TouchEngine.ACTION_UP, TouchEngine.Pointer(0, 500f, 250f)),
            1000, 500, xRange, yRange,
        )
        assertEquals(
            listOf(
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1),
                Evdev.EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOUCH, 0),
                Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0),
            ),
            events,
        )
    }

    @Test
    fun translate_pointerUp_onlyReleasedPointer() {
        val action = TouchEngine.ACTION_POINTER_UP or
            (0 shl TouchEngine.ACTION_POINTER_INDEX_SHIFT)
        val events = Evdev.translateSpec(
            spec(
                action,
                TouchEngine.Pointer(0, 100f, 100f),
                TouchEngine.Pointer(1, 200f, 300f),
            ),
            1000, 500, xRange, yRange,
        )
        // Chỉ ngón nhả (index 0, id 0), không có BTN_TOUCH (còn ngón khác).
        assertEquals(
            listOf(
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1),
                Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0),
            ),
            events,
        )
    }

    @Test
    fun translate_cancel_allPointers() {
        val events = Evdev.translateSpec(
            spec(
                TouchEngine.ACTION_CANCEL,
                TouchEngine.Pointer(0, 100f, 100f),
                TouchEngine.Pointer(1, 200f, 300f),
            ),
            1000, 500, xRange, yRange,
        )
        assertEquals(
            listOf(
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 1),
                Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1),
                Evdev.EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOUCH, 0),
                Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0),
            ),
            events,
        )
    }

    @Test
    fun translate_invalid_returnsEmpty() {
        assertTrue(Evdev.translateSpec(spec(99), 1000, 500, xRange, yRange).isEmpty())
        assertTrue(
            Evdev.translateSpec(
                spec(TouchEngine.ACTION_DOWN), 1000, 500, xRange, yRange,
            ).isEmpty(),
        ) // DOWN không có ngón
        val badIndex = TouchEngine.ACTION_POINTER_DOWN or
            (5 shl TouchEngine.ACTION_POINTER_INDEX_SHIFT)
        assertTrue(
            Evdev.translateSpec(
                spec(badIndex, TouchEngine.Pointer(0, 1f, 1f)),
                1000, 500, xRange, yRange,
            ).isEmpty(),
        ) // chỉ số vượt mảng
    }

    // ---------- Đổi tọa độ ----------

    @Test
    fun scale_edges() {
        assertEquals(0, Evdev.scale(0f, 1000, xRange))
        assertEquals(2000, Evdev.scale(1000f, 1000, xRange))
        assertEquals(2000, Evdev.scale(1500f, 1000, xRange)) // vượt: kẹp max
        assertEquals(0, Evdev.scale(-50f, 1000, xRange)) // âm: kẹp min
        assertEquals(1000, Evdev.scale(500f, 1000, xRange))
        assertEquals(0, Evdev.scale(500f, 0, xRange)) // chưa có kích thước
    }

    @Test
    fun scale_nonZeroMin() {
        val r = Evdev.AbsRange(100, 1100)
        assertEquals(100, Evdev.scale(0f, 1000, r))
        assertEquals(600, Evdev.scale(500f, 1000, r))
        assertEquals(1100, Evdev.scale(1000f, 1000, r))
    }

    // ---------- Tìm node cảm ứng ----------

    private fun absWord(vararg bits: Int): String {
        var w = 0L
        for (b in bits) w = w or (1L shl b)
        return w.toString(16)
    }

    // Output thật của `getevent -p /dev/input/event2` (định dạng getevent,
    // KHÔNG phải bảng sysfs: file capabilities/abs chỉ chứa bitmap hex).
    private val geteventText = """
        add device 1: /dev/input/event2
          name:     "fts_ts"
          events:
            EV_SYN (0x00)
            EV_KEY (0x01)
            EV_ABS (0x03)
          input props:
            INPUT_PROP_DIRECT (0x01)
          0000 : value 0, min 0, max 0, fuzz 0, flat 0, resolution 0
          0001 : value 0, min 0, max 0, fuzz 0, flat 0, resolution 0
          002f : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
          0035 : value 0, min 0, max 1599, fuzz 0, flat 0, resolution 0
          0036 : value 0, min 0, max 2559, fuzz 0, flat 0, resolution 0
          0039 : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
    """.trimIndent()

    private fun procTextWithTouch(): String {
        val touchAbs = absWord(0, 1, 47, 53, 54, 57)
        return """
            I: Bus=0003 Vendor=046d Product=c52b Version=0111
            N: Name="Logitech USB Keyboard"
            P: Phys=usb-0000:00:14.0-1/input0
            S: Sysfs=/devices/pci0000:00/0000:00:14.0/usb1/1-1/1-1:1.0/0003:046D:C52B.0001/input/input10
            U: Uniq=
            H: Handlers=sysrq kbd event5 leds 
            B: PROP=0
            B: EV=120013
            B: KEY=10000 7 ff9f207a c14057ff febeffdf ffefffff ffffffff fffffffe
            B: MSC=10
            B: LED=7

            I: Bus=0018 Vendor=0000 Product=0000 Version=0000
            N: Name="fts_ts"
            P: Phys=
            S: Sysfs=/devices/platform/soc/xxx/input/input2
            U: Uniq=
            H: Handlers=event2 
            B: PROP=2
            B: EV=b
            B: KEY=400 0 0 0 0 0
            B: ABS=$touchAbs
        """.trimIndent()
    }

    @Test
    fun findTouchNode_found() {
        val node = Evdev.findTouchNode(procTextWithTouch()) { geteventText }
        assertEquals("/dev/input/event2", node?.path)
        assertEquals(Evdev.AbsRange(0, 1599), node?.xRange)
        assertEquals(Evdev.AbsRange(0, 2559), node?.yRange)
    }

    @Test
    fun findTouchNode_none() {
        // Chỉ có bàn phím, không có node cảm ứng.
        val onlyKeyboard = procTextWithTouch().substringBefore("\n\nI: Bus=0018")
        assertNull(Evdev.findTouchNode(onlyKeyboard) { geteventText })
        assertNull(Evdev.findTouchNode("") { geteventText })
    }

    @Test
    fun findTouchNode_noCapsSkipped() {
        // Có node cảm ứng nhưng không chạy được getevent -p -> bỏ qua.
        assertNull(Evdev.findTouchNode(procTextWithTouch()) { null })
    }

    @Test
    fun findTouchNode_prefersLargestXSpan() {
        val touchAbs = absWord(0, 1, 47, 53, 54, 57)
        val text = """
            I: Bus=0018 Vendor=0000 Product=0000 Version=0000
            N: Name="small_touch"
            H: Handlers=event3 
            B: EV=b
            B: ABS=$touchAbs

            I: Bus=0018 Vendor=0000 Product=0000 Version=0000
            N: Name="big_touch"
            H: Handlers=event2 
            B: EV=b
            B: ABS=$touchAbs
        """.trimIndent()
        val node = Evdev.findTouchNode(text) { nodePath ->
            when (nodePath) {
                "/dev/input/event3" ->
                    "  0035 : value 0, min 0, max 799, fuzz 0, flat 0, resolution 0\n" +
                        "  0036 : value 0, min 0, max 599, fuzz 0, flat 0, resolution 0"
                "/dev/input/event2" -> geteventText
                else -> null
            }
        }
        assertEquals("/dev/input/event2", node?.path)
    }

    @Test
    fun parseGeteventRanges_validAndInvalid() {
        assertEquals(
            Evdev.AbsRange(0, 1599) to Evdev.AbsRange(0, 2559),
            Evdev.parseGeteventRanges(geteventText),
        )
        assertNull(
            Evdev.parseGeteventRanges(
                "  0035 : value 0, min 0, max 1599, fuzz 0, flat 0, resolution 0",
            ),
        ) // thiếu Y
        assertNull(Evdev.parseGeteventRanges(""))
        assertNull(
            Evdev.parseGeteventRanges(
                "  0035 : value 0, min x, max y, fuzz 0, flat 0, resolution 0\n" +
                    "  0036 : value 0, min 0, max 2559, fuzz 0, flat 0, resolution 0",
            ),
        ) // min/max sai
    }

    @Test
    fun parseGeteventRanges_rejectsSysfsBitmap() {
        // Hồi quy: file /sys/.../capabilities/abs chỉ chứa bitmap hex
        // (vd "260800000000003"), không phải bảng min/max — parse phải
        // trả null chứ không được hiểu nhầm thành khoảng ABS.
        assertNull(Evdev.parseGeteventRanges("260800000000003"))
        assertNull(Evdev.parseGeteventRanges("0"))
        assertNull(Evdev.parseGeteventRanges("53 0 1599 0 0 0\n54 0 2559 0 0 0"))
    }

    @Test
    fun releaseAllSlotsEvents_allSlots() {
        val events = Evdev.releaseAllSlotsEvents()
        // 10 slot x (SLOT + TRACKING_ID=-1) + BTN_TOUCH=0 + SYN_REPORT.
        assertEquals(22, events.size)
        assertEquals(Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0), events[0])
        assertEquals(Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1), events[1])
        assertEquals(Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 9), events[18])
        assertEquals(Evdev.EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1), events[19])
        assertEquals(Evdev.EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOUCH, 0), events[20])
        assertEquals(Evdev.EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0), events[21])
    }
}

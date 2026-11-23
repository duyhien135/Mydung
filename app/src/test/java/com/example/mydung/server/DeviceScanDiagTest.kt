package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor

/**
 * Test phân loại EVIOCGBIT (build-13) + token chẩn đoán quét.
 * probe() dùng backend fake (không có /dev/input trên máy test).
 */
class EvdevCapsTest {

    /** Fake backend: trả mask cố định theo cmd. */
    private class FakeIoctl(private val byCmd: Map<Int, Int>) : EvdevCapsIoctl {
        override val strategyName = "fake"
        override fun resolve() {}
        override fun ioctlIntValue(fd: FileDescriptor, cmd: Int): Int? = byCmd[cmd]
    }

    @Test
    fun mouseCaps_classifyMouse() {
        // EV: EV_SYN|EV_KEY|EV_REL; REL: REL_X|REL_Y|REL_WHEEL.
        val m = EvdevCapsMasks(ev = 0b111, keyWord0 = 0, rel = 0b100000011, abs = 0)
        assertEquals(DeviceKind.MOUSE, EvdevCaps.classify(m))
    }

    @Test
    fun mouseWithoutButtons_notMouse() {
        // Có REL_X/Y nhưng không có EV_KEY (không nút) -> không phải chuột.
        val m = EvdevCapsMasks(ev = 0b101, keyWord0 = 0, rel = 0b11, abs = 0)
        assertNull(EvdevCaps.classify(m))
    }

    @Test
    fun keyboardCaps_classifyKeyboard() {
        // EV: EV_SYN|EV_KEY; KEY word0 có phím chữ Q(16)-P(25).
        val letters = (16..25).fold(0) { acc, b -> acc or (1 shl b) }
        val m = EvdevCapsMasks(ev = 0b11, keyWord0 = letters, rel = 0, abs = 0)
        assertEquals(DeviceKind.KEYBOARD, EvdevCaps.classify(m))
    }

    @Test
    fun keyboardOnlyAS_classifyKeyboard() {
        // Chỉ có A(30) + S(31) trong word0 cũng đủ nhận bàn phím.
        val letters = (1 shl 30) or (1 shl 31)
        val m = EvdevCapsMasks(ev = 0b11, keyWord0 = letters, rel = 0, abs = 0)
        assertEquals(DeviceKind.KEYBOARD, EvdevCaps.classify(m))
    }

    @Test
    fun touchCaps_classifyNull() {
        // Cảm ứng: EV_SYN|EV_KEY|EV_ABS, KEY word0 = 0 (BTN_TOUCH=330 ở word cao).
        val m = EvdevCapsMasks(ev = 0b1011, keyWord0 = 0, rel = 0, abs = 0xFF)
        assertNull(EvdevCaps.classify(m))
    }

    @Test
    fun powerKeyCaps_classifyNull() {
        // Nút nguồn: EV_KEY nhưng word0 = 0 (KEY_POWER=116 ở word 1).
        val m = EvdevCapsMasks(ev = 0b11, keyWord0 = 0, rel = 0, abs = 0)
        assertNull(EvdevCaps.classify(m))
    }

    @Test
    fun probe_withFakeBackend_returnsMasks() {
        EvdevCaps.setIoctlForTest(
            FakeIoctl(
                mapOf(
                    Config.EVIOCGBIT_EV to 0b111,
                    Config.EVIOCGBIT_KEY to 0,
                    Config.EVIOCGBIT_REL to 0b11,
                    Config.EVIOCGBIT_ABS to 0,
                ),
            ),
        )
        try {
            val r = EvdevCaps.probe(FileDescriptor())
            assertTrue(r is EvdevCaps.ProbeResult.Ok)
            val masks = (r as EvdevCaps.ProbeResult.Ok).masks
            assertEquals(0b111, masks.ev)
            assertEquals(0b11, masks.rel)
            assertEquals(DeviceKind.MOUSE, EvdevCaps.classify(masks))
        } finally {
            EvdevCaps.resetIoctlForTest()
        }
    }

    @Test
    fun probe_zeroEv_returnsZeroMask() {
        EvdevCaps.setIoctlForTest(FakeIoctl(mapOf(Config.EVIOCGBIT_EV to 0)))
        try {
            assertEquals(EvdevCaps.ProbeResult.ZeroMask, EvdevCaps.probe(FileDescriptor()))
        } finally {
            EvdevCaps.resetIoctlForTest()
        }
    }

    @Test
    fun probe_ioctlFails_returnsIoctlFailed() {
        EvdevCaps.setIoctlForTest(FakeIoctl(emptyMap()))
        try {
            assertEquals(EvdevCaps.ProbeResult.IoctlFailed, EvdevCaps.probe(FileDescriptor()))
        } finally {
            EvdevCaps.resetIoctlForTest()
        }
    }

    @Test
    fun ioctlNumbers_matchLinux() {
        // EVIOCGBIT(ev, 4) = _IOR('E', 0x20 + ev, 4).
        assertEquals(0x80044520.toInt(), Config.EVIOCGBIT_EV)
        assertEquals(0x80044521.toInt(), Config.EVIOCGBIT_KEY)
        assertEquals(0x80044522.toInt(), Config.EVIOCGBIT_REL)
        assertEquals(0x80044523.toInt(), Config.EVIOCGBIT_ABS)
    }
}

class ScanDiagnosticsTest {
    @Test
    fun token_format() {
        val d = ScanDiagnostics(
            procReadOk = true, procBlocks = 12, parsedCount = 2, nodesOnDisk = 12,
            fallbackClassified = 1, openFailed = 0, openedCount = 3,
        )
        assertEquals("proc:12_parsed:2_nodes:12_open:3_err:0_fb:1", d.token())
    }

    @Test
    fun token_procError() {
        val d = ScanDiagnostics(procReadOk = false)
        assertTrue(d.token().startsWith("proc:ERR_"))
    }

    @Test
    fun token_noSpaces() {
        // Token đi trong MAPSTATUS (phân tách bằng khoảng trắng) nên không
        // được chứa khoảng trắng.
        val d = ScanDiagnostics(
            procReadOk = true, procBlocks = 5, parsedCount = 0, nodesOnDisk = 9,
            fallbackClassified = 0, openFailed = 2, lastOpenError = "event5: Permission denied",
            openedCount = 0,
        )
        assertTrue(!d.token().contains(' '))
    }
}

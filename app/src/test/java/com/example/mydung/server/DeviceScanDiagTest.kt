package com.example.mydung.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
        // 6 trường đầu giữ nguyên build-13, thêm _pf/_io/_sf/_st (build-14)
        // và _gs/_ge (build-15, mặc định none).
        assertEquals(
            "proc:12_parsed:2_nodes:12_open:3_err:0_fb:1_pf:0_io:0_sf:0_st:none_gs:none_ge:none",
            d.token(),
        )
    }

    @Test
    fun token_extendedFields() {
        // Trường hợp Redmi build-13: proc:ERR, fb:0 — build-14 thêm pf/io/sf/st
        // để biết kẹt ở mở node, ioctl hay phân loại.
        val d = ScanDiagnostics(
            procReadOk = false, parsedCount = 0, nodesOnDisk = 7,
            fallbackClassified = 0, openFailed = 0, openedCount = 0,
            probeOpenFailed = 3, ioctlAvailable = true, sysfsClassified = 0,
            strategyName = "lc-mut?",
            grabStrategyName = "lc-mut?", lastGrabError = "no-ioctl",
        )
        assertEquals(
            "proc:ERR_parsed:0_nodes:7_open:0_err:0_fb:0_pf:3_io:1_sf:0_st:lc-mut?_gs:lc-mut?_ge:no-ioctl",
            d.token(),
        )
    }

    @Test
    fun token_sysfsClassified() {
        val d = ScanDiagnostics(
            procReadOk = false, parsedCount = 0, nodesOnDisk = 7,
            fallbackClassified = 1, openFailed = 0, openedCount = 1,
            probeOpenFailed = 0, ioctlAvailable = false, sysfsClassified = 1,
            strategyName = "none",
            grabStrategyName = "lc-mut", lastGrabError = "none",
        )
        assertEquals(
            "proc:ERR_parsed:0_nodes:7_open:1_err:0_fb:1_pf:0_io:0_sf:1_st:none_gs:lc-mut_ge:none",
            d.token(),
        )
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
            openedCount = 0, probeOpenFailed = 1, ioctlAvailable = true,
            sysfsClassified = 0, strategyName = "lc-mut?",
        )
        assertTrue(!d.token().contains(' '))
    }
}

/**
 * Test phân loại bằng sysfs (build-14, đường chính thay EVIOCGBIT).
 * Dùng cây sysfs giả trong thư mục tạm (không có /sys trên máy test).
 */
class SysfsCapsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun restoreRoot() {
        SysfsCaps.inputRoot = "/sys/class/input"
    }

    /** Tạo 1 node giả: root/eventN/device/capabilities/{ev,key,rel,abs} + name. */
    private fun fakeNode(
        eventId: String,
        ev: String?, key: String?, rel: String?, abs: String?,
        name: String?,
    ) {
        val dir = File(tmp.root, "$eventId/device/capabilities").apply { mkdirs() }
        if (ev != null) File(dir, "ev").writeText(ev)
        if (key != null) File(dir, "key").writeText(key)
        if (rel != null) File(dir, "rel").writeText(rel)
        if (abs != null) File(dir, "abs").writeText(abs)
        if (name != null) File(tmp.root, "$eventId/device/name").writeText(name)
        SysfsCaps.inputRoot = tmp.root.absolutePath
    }

    @Test
    fun parseMask_singleWord() {
        assertEquals(0x7, SysfsCaps.parseMaskWord0("7"))
        assertEquals(0x120013, SysfsCaps.parseMaskWord0("120013"))
        assertEquals(0x7, SysfsCaps.parseMaskWord0("  7\n"))
    }

    @Test
    fun parseMask_multiWord_lastIsWord0() {
        // Từ cao nhất đứng trước — từ cuối là word 0 (vd key bàn phím thật).
        assertEquals(
            0xfffffffe.toInt(),
            SysfsCaps.parseMaskWord0("7 ff9f387acb60ff febeffdfffefffff fffffffffffffffe"),
        )
    }

    @Test
    fun parseMask_invalid_returnsNull() {
        assertNull(SysfsCaps.parseMaskWord0(""))
        assertNull(SysfsCaps.parseMaskWord0("   "))
        assertNull(SysfsCaps.parseMaskWord0("xyz"))
        // Từ dài hơn 64 bit (17 ký tự hex) thì không parse.
        assertNull(SysfsCaps.parseMaskWord0("1ffffffffffffffff"))
    }

    @Test
    fun parseMask_fullWord() {
        assertEquals(-1, SysfsCaps.parseMaskWord0("ffffffffffffffff"))
        assertEquals(-1, SysfsCaps.parseMaskWord0("FFFFFFFFFFFFFFFF"))
    }

    @Test
    fun probe_mouse() {
        // Chuột: EV_SYN|EV_KEY|EV_REL, REL_X|REL_Y|REL_WHEEL.
        fakeNode("event5", ev = "7", key = "0", rel = "103", abs = "0", name = "VXE R1 SE+")
        val r = SysfsCaps.probe("event5")
        assertTrue(r is SysfsCaps.SysfsProbe.Read)
        val read = r as SysfsCaps.SysfsProbe.Read
        assertEquals(DeviceKind.MOUSE, read.kind)
        assertEquals("VXE R1 SE+", read.name)
    }

    @Test
    fun probe_keyboard_realMasks() {
        // Bàn phím với mask như trên máy thật (ev + key nhiều từ).
        fakeNode(
            "event3", ev = "120013",
            key = "7 ff9f387acb60ff febeffdfffefffff fffffffffffffffe",
            rel = "0", abs = "0", name = "USB Keyboard",
        )
        val r = SysfsCaps.probe("event3")
        assertTrue(r is SysfsCaps.SysfsProbe.Read)
        assertEquals(DeviceKind.KEYBOARD, (r as SysfsCaps.SysfsProbe.Read).kind)
    }

    @Test
    fun probe_touch_returnsNullKind() {
        // Cảm ứng: EV_SYN|EV_KEY|EV_ABS, không REL, không phím chữ -> null
        // (tên trung tính để test đúng luật classify, không phải lọc tên).
        fakeNode("event1", ev = "b", key = "0", rel = "0", abs = "1000000", name = "ABS Device")
        val r = SysfsCaps.probe("event1")
        assertTrue(r is SysfsCaps.SysfsProbe.Read)
        assertNull((r as SysfsCaps.SysfsProbe.Read).kind)
    }

    @Test
    fun probe_powerKey_returnsNullKind() {
        // Nút nguồn: EV_KEY nhưng word0 = 0 (KEY_POWER ở word 1) -> null.
        fakeNode("event2", ev = "3", key = "0", rel = "0", abs = "0", name = "Button Device")
        val r = SysfsCaps.probe("event2")
        assertTrue(r is SysfsCaps.SysfsProbe.Read)
        assertNull((r as SysfsCaps.SysfsProbe.Read).kind)
    }

    @Test
    fun probe_internalName_returnsNullKind() {
        // Mask chuột nhưng tên nội bộ (cảm ứng) -> loại.
        fakeNode("event4", ev = "7", key = "0", rel = "103", abs = "0", name = "fts_touch")
        val r = SysfsCaps.probe("event4")
        assertTrue(r is SysfsCaps.SysfsProbe.Read)
        assertNull((r as SysfsCaps.SysfsProbe.Read).kind)
    }

    @Test
    fun probe_missingNode_returnsUnreadable() {
        SysfsCaps.inputRoot = tmp.root.absolutePath
        assertEquals(SysfsCaps.SysfsProbe.Unreadable, SysfsCaps.probe("event9"))
    }

    @Test
    fun probe_partialMasks_returnsUnreadable() {
        // Thiếu file key -> không đọc đủ mask -> Unreadable (để rớt xuống EVIOCGBIT).
        fakeNode("event6", ev = "7", key = null, rel = "103", abs = "0", name = "VXE R1 SE+")
        assertEquals(SysfsCaps.SysfsProbe.Unreadable, SysfsCaps.probe("event6"))
    }
}

/**
 * Test luật chọn strategy EVIOCGBIT (build-14): không strategy nào vượt
 * qua kiểm chứng fd=-1 thì giữ strategy đầu tiên có lỗi MƠ HỒ làm phương
 * án cuối "chưa kiểm chứng" (tên + "?") thay vì trả "none". Lỗi CẤU TRÚC
 * (sai chữ ký...) bị loại khỏi ứng viên (sửa theo Claude).
 */
class ReflectiveEvdevCapsIoctlTest {

    @Test
    fun verifiedStrategy_noQuestionMark() {
        // Invoker chạy ngon trên fd giả (không ném) -> kiểm chứng qua.
        val ioctl = ReflectiveEvdevCapsIoctl()
        ioctl.resolveWithAttempts(
            listOf("fake-a" to { { _: FileDescriptor, _: Int -> 42 } }),
        )
        assertEquals("fake-a", ioctl.strategyName)
        assertEquals(42, ioctl.ioctlIntValue(FileDescriptor(), Config.EVIOCGBIT_EV))
    }

    @Test
    fun unverifiedFallback_keepsFirstResolved() {
        // Strategy đầu resolve được nhưng kiểm chứng ném lỗi không có
        // errno (ROM lạ ném lỗi khác EBADF); strategy sau không resolve
        // được. -> Giữ strategy đầu, tên + "?"; ioctl thật vẫn gọi được.
        var calls = 0
        val invoker: (FileDescriptor, Int) -> Int = { _, _ ->
            calls++
            if (calls == 1) throw RuntimeException("not EBADF") else 42
        }
        val ioctl = ReflectiveEvdevCapsIoctl()
        ioctl.resolveWithAttempts(
            listOf(
                "fake-a" to { invoker },
                "fake-b" to { throw IllegalStateException("no class") },
            ),
        )
        assertEquals("fake-a?", ioctl.strategyName)
        assertEquals(42, ioctl.ioctlIntValue(FileDescriptor(), Config.EVIOCGBIT_EV))
    }

    @Test
    fun nothingResolved_returnsNone() {
        val ioctl = ReflectiveEvdevCapsIoctl()
        ioctl.resolveWithAttempts(
            listOf("fake-a" to { throw IllegalStateException("no class") }),
        )
        assertEquals("none", ioctl.strategyName)
        assertNull(ioctl.ioctlIntValue(FileDescriptor(), Config.EVIOCGBIT_EV))
    }

    @Test
    fun secondStrategyVerified_whenFirstFailsBuild() {
        // Strategy đầu không resolve được, strategy sau kiểm chứng qua
        // -> chọn strategy sau (không "?").
        val ioctl = ReflectiveEvdevCapsIoctl()
        ioctl.resolveWithAttempts(
            listOf(
                "fake-a" to { throw IllegalStateException("no class") },
                "fake-b" to { { _: FileDescriptor, _: Int -> 7 } },
            ),
        )
        assertEquals("fake-b", ioctl.strategyName)
        assertEquals(7, ioctl.ioctlIntValue(FileDescriptor(), Config.EVIOCGBIT_EV))
    }

    @Test
    fun structuralError_excludedFromFallback() {
        // Cả 2 strategy đều ném lỗi cấu trúc khi kiểm chứng (sai chữ
        // ký / thiếu method) -> không có ứng viên "chưa kiểm chứng"
        // -> "none", chứ không chọn bừa 1 cái đã chứng minh gọi sai.
        val ioctl = ReflectiveEvdevCapsIoctl()
        ioctl.resolveWithAttempts(
            listOf(
                "fake-a" to { { _: FileDescriptor, _: Int -> throw IllegalArgumentException("bad arg") } },
                "fake-b" to { { _: FileDescriptor, _: Int -> throw NoSuchMethodError("no method") } },
            ),
        )
        assertEquals("none", ioctl.strategyName)
        assertNull(ioctl.ioctlIntValue(FileDescriptor(), Config.EVIOCGBIT_EV))
    }

    @Test
    fun ambiguousAfterStructural_becomesCandidate() {
        // Strategy đầu lỗi cấu trúc (loại), strategy sau lỗi mơ hồ
        // (RuntimeException, không phải lỗi cấu trúc) -> giữ strategy
        // sau làm "chưa kiểm chứng".
        val ioctl = ReflectiveEvdevCapsIoctl()
        ioctl.resolveWithAttempts(
            listOf(
                "fake-a" to { { _: FileDescriptor, _: Int -> throw IllegalArgumentException("bad arg") } },
                "fake-b" to { { _: FileDescriptor, _: Int -> throw RuntimeException("ambiguous") } },
            ),
        )
        assertEquals("fake-b?", ioctl.strategyName)
    }
}

/**
 * Test resolver dùng chung (build-15) với invoker dạng Grabber
 * ((FileDescriptor) -> Unit): cùng luật chọn như EvdevCaps nhưng
 * kiểu invoker khác.
 */
class IoctlReflectionResolverTest {

    @Test
    fun verifiedUnitInvoker_noQuestionMark() {
        val r = IoctlReflectionResolver.resolve(
            listOf("fake-a" to { { _: FileDescriptor -> } }),
            { fn -> fn(FileDescriptor()) },
            "TEST",
        )
        assertEquals("fake-a", r.strategyName)
        assertTrue(r.invoker != null)
    }

    @Test
    fun ambiguousUnitInvoker_becomesUnverifiedCandidate() {
        // Lỗi mơ hồ khi kiểm chứng -> giữ làm "chưa kiểm chứng".
        val r = IoctlReflectionResolver.resolve(
            listOf("fake-a" to { { _: FileDescriptor -> throw RuntimeException("ambiguous") } }),
            { fn -> fn(FileDescriptor()) },
            "TEST",
        )
        assertEquals("fake-a?", r.strategyName)
        assertTrue(r.invoker != null)
    }

    @Test
    fun structuralUnitInvoker_excluded() {
        // Lỗi cấu trúc khi kiểm chứng -> loại hẳn, trả "none".
        val r = IoctlReflectionResolver.resolve(
            listOf("fake-a" to { { _: FileDescriptor -> throw IllegalArgumentException("bad arg") } }),
            { fn -> fn(FileDescriptor()) },
            "TEST",
        )
        assertEquals("none", r.strategyName)
        assertNull(r.invoker)
    }
}

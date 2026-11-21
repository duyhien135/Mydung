package com.example.mydung.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private fun collect(vararg events: RawEvent): List<InputEvent> {
        val asm = FrameAssembler()
        val out = ArrayList<InputEvent>()
        for (e in events) asm.feed(e) { out.add(it) }
        return out
    }

    private fun syn(t: Long = 0) = RawEvent(0, 0, 0, t)
    private fun rel(code: Int, value: Int, t: Long = 0) = RawEvent(2, code, value, t)
    private fun key(code: Int, value: Int, t: Long = 0) = RawEvent(1, code, value, t)

    @Test
    fun mouseMove_coalescedPerFrame() {
        val out = collect(rel(0, 5), rel(1, -3), syn(100))
        assertEquals(listOf(InputEvent.MouseMove(5, -3, 100)), out)
    }

    @Test
    fun mouseMove_notMergedAcrossFrames() {
        val out = collect(rel(0, 5), syn(100), rel(0, 2), syn(200))
        assertEquals(
            listOf(InputEvent.MouseMove(5, 0, 100), InputEvent.MouseMove(2, 0, 200)),
            out,
        )
    }

    @Test
    fun emptyFrame_emitsNothing() {
        assertTrue(collect(syn()).isEmpty())
    }

    @Test
    fun wheel_verticalAndHorizontal() {
        val out = collect(rel(8, 1), rel(6, -1), syn(50))
        assertEquals(listOf(InputEvent.Wheel(1, -1, 50)), out)
    }

    @Test
    fun keyRepeat_filtered() {
        val out = collect(key(17, 1), key(17, 2), key(17, 2), key(17, 0))
        assertEquals(
            listOf(InputEvent.Key(17, true, 0), InputEvent.Key(17, false, 0)),
            out,
        )
    }

    @Test
    fun mouseButtons_areButtonEvents() {
        val out = collect(key(0x110, 1), key(0x111, 1), key(0x112, 0))
        assertEquals(
            listOf(
                InputEvent.Button(0x110, true, 0),
                InputEvent.Button(0x111, true, 0),
                InputEvent.Button(0x112, false, 0),
            ),
            out,
        )
    }

    @Test
    fun releaseAll_emitsSyntheticReleases() {
        val asm = FrameAssembler()
        val out = ArrayList<InputEvent>()
        asm.feed(key(17, 1)) { out.add(it) } // W đè
        asm.feed(key(30, 1)) { out.add(it) } // A đè
        asm.feed(key(0x110, 1)) { out.add(it) } // Chuột trái đè
        out.clear()
        asm.releaseAll(999) { out.add(it) }
        assertEquals(
            setOf(
                InputEvent.Key(17, false, 999),
                InputEvent.Key(30, false, 999),
                InputEvent.Button(0x110, false, 999),
            ),
            out.toSet(),
        )
        // Sau releaseAll, bộ đệm đã sạch: releaseAll lần 2 không phát gì.
        out.clear()
        asm.releaseAll(1000) { out.add(it) }
        assertTrue(out.isEmpty())
    }
}

class InputProtocolTest {

    @Test
    fun format_allEventTypes() {
        assertEquals("EV K 17 1 100", InputProtocol.format(InputEvent.Key(17, true, 100)))
        assertEquals("EV K 17 0 100", InputProtocol.format(InputEvent.Key(17, false, 100)))
        assertEquals("EV M 5 -3 100", InputProtocol.format(InputEvent.MouseMove(5, -3, 100)))
        assertEquals("EV B 272 1 100", InputProtocol.format(InputEvent.Button(272, true, 100)))
        assertEquals("EV W 1 -1 100", InputProtocol.format(InputEvent.Wheel(1, -1, 100)))
        val dev = InputDeviceInfo("event6", "Logitech Mouse", "/dev/input/event6", DeviceKind.MOUSE)
        assertEquals("EV DEV add event6 Logitech Mouse", InputProtocol.format(InputEvent.Device(true, dev)))
        assertEquals("EV DEV remove event6 Logitech Mouse", InputProtocol.format(InputEvent.Device(false, dev)))
    }

    @Test
    fun deviceLine_roundTrip() {
        val info = InputDeviceInfo("event5", "Logitech USB Receiver", "/dev/input/event5", DeviceKind.KEYBOARD)
        val line = InputProtocol.formatDevice(info)
        assertEquals("DEV event5 keyboard /dev/input/event5 Logitech USB Receiver", line)
        assertEquals(info, InputProtocol.parseDeviceLine(line))
    }

    @Test
    fun parseDeviceLine_invalid() {
        assertEquals(null, InputProtocol.parseDeviceLine("DEV event5 keyboard"))
        assertEquals(null, InputProtocol.parseDeviceLine("XXX event5 keyboard /dev/input/event5 Name"))
        assertEquals(null, InputProtocol.parseDeviceLine("DEV event5 touch /dev/input/event5 Name"))
        assertEquals(null, InputProtocol.parseDeviceLine(""))
    }

    @Test
    fun parseGrab_valid() {
        assertEquals("mouse" to true, InputProtocol.parseGrab("GRAB mouse 1"))
        assertEquals("keyboard" to false, InputProtocol.parseGrab("GRAB keyboard 0"))
        assertEquals("all" to true, InputProtocol.parseGrab("GRAB all 1"))
        assertEquals("mouse" to true, InputProtocol.parseGrab("  GRAB   mouse   1  "))
    }

    @Test
    fun parseKeyPress_valid() {
        assertEquals(17, InputProtocol.parseKeyPress("EV K 17 1 100"))
        assertEquals(272, InputProtocol.parseKeyPress("EV B 272 1 100"))
    }

    @Test
    fun parseKeyPress_ignored() {
        assertEquals(null, InputProtocol.parseKeyPress("EV K 17 0 100")) // lúc nhả
        assertEquals(null, InputProtocol.parseKeyPress("EV M 5 -3 100"))
        assertEquals(null, InputProtocol.parseKeyPress("EV W 1 -1 100"))
        assertEquals(null, InputProtocol.parseKeyPress("EV DEV add event6 Chuột"))
        assertEquals(null, InputProtocol.parseKeyPress("EV K xx 1 100"))
        assertEquals(null, InputProtocol.parseKeyPress(""))
    }

    @Test
    fun parseGrab_invalid() {
        assertEquals(null, InputProtocol.parseGrab("GRAB mouse"))
        assertEquals(null, InputProtocol.parseGrab("GRAB mouse 2"))
        assertEquals(null, InputProtocol.parseGrab("GRAB touch 1"))
        assertEquals(null, InputProtocol.parseGrab("GRAB"))
        assertEquals(null, InputProtocol.parseGrab("PING"))
    }
}

/** Fake EvdevIoctl cho test: trả outcome cố định, ghi lại fd đã grab. */
private class FakeEvdevIoctl(
    private val outcome: GrabOutcome,
    override val strategyName: String = "fake",
) : EvdevIoctl {
    var grabbedFds = ArrayList<java.io.FileDescriptor>()
    override fun resolve() { /* không dò gì */ }
    override fun grab(fd: java.io.FileDescriptor): GrabOutcome {
        grabbedFds.add(fd)
        return outcome
    }
}

/** Giả android.system.ErrnoException: cùng tên đơn + field errno công khai. */
class ErrnoException(@JvmField val errno: Int) : Exception("fake errno $errno")

class GrabberTest {

    @After
    fun restoreBackend() {
        // Backend fake không được rò sang test khác (JUnit chạy chung 1 JVM).
        Grabber.setIoctlForTest(ReflectiveEvdevIoctl())
    }

    @Test
    fun eviocgrab_constant() {
        // _IOW('E', 0x90, int) = (1<<30) | (4<<16) | (0x45<<8) | 0x90
        assertEquals(0x40044590, Config.EVIOCGRAB)
    }

    @Test
    fun resolve_withoutAndroidClasses_fallsBackToNone() {
        // Trên JVM thường không có libcore/android.system.Os -> none, không ném lỗi.
        val ioctl = ReflectiveEvdevIoctl()
        ioctl.resolve()
        assertEquals("none", ioctl.strategyName)
        assertEquals(GrabOutcome.Failed("không có ioctl"), ioctl.grab(java.io.FileDescriptor()))
    }

    @Test
    fun grab_withoutIoctl_returnsFalse() {
        Grabber.resolve()
        assertEquals("none", Grabber.strategyName)
        assertEquals(false, Grabber.grab(java.io.FileDescriptor()))
    }

    @Test
    fun grab_withFakeSuccess_returnsTrue() {
        val fake = FakeEvdevIoctl(GrabOutcome.Grabbed)
        Grabber.setIoctlForTest(fake)
        val fd = java.io.FileDescriptor()
        assertEquals(true, Grabber.grab(fd))
        assertEquals(listOf(fd), fake.grabbedFds)
        assertEquals("fake", Grabber.strategyName)
    }

    @Test
    fun grab_withFakeBusy_returnsFalse() {
        Grabber.setIoctlForTest(FakeEvdevIoctl(GrabOutcome.Busy))
        assertEquals(false, Grabber.grab(java.io.FileDescriptor()))
    }

    @Test
    fun grab_withFakeFailure_returnsFalse() {
        Grabber.setIoctlForTest(FakeEvdevIoctl(GrabOutcome.Failed("lỗi giả")))
        assertEquals(false, Grabber.grab(java.io.FileDescriptor()))
    }
}

class ExtractErrnoTest {

    @Test
    fun extractErrno_unwrapsInvocationTarget() {
        val wrapped = java.lang.reflect.InvocationTargetException(ErrnoException(16))
        assertEquals(16, extractErrno(wrapped))
    }

    @Test
    fun extractErrno_direct() {
        assertEquals(9, extractErrno(ErrnoException(9)))
    }

    @Test
    fun extractErrno_nonErrno_returnsNull() {
        assertEquals(null, extractErrno(RuntimeException("x")))
        val wrapped = java.lang.reflect.InvocationTargetException(RuntimeException("x"))
        assertEquals(null, extractErrno(wrapped))
    }
}

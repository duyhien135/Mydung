package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InputEventParserTest {

    private fun eventBytes(sec: Long, usec: Long, type: Int, code: Int, value: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(sec)
        buf.putLong(usec)
        buf.putShort(type.toShort())
        buf.putShort(code.toShort())
        buf.putInt(value)
        return buf.array()
    }

    @Test
    fun parse_keyPressW() {
        val raw = InputEventParser.parse(eventBytes(1000, 500_000, 1, 17, 1))
        assertEquals(RawEvent(1, 17, 1, 1_000_500), raw)
    }

    @Test
    fun parse_negativeRelValue() {
        // REL_Y = -3 phải giữ dấu âm.
        val raw = InputEventParser.parse(eventBytes(0, 0, 2, 1, -3))
        assertEquals(RawEvent(2, 1, -3, 0), raw)
    }

    @Test
    fun parse_shortArray_returnsNull() {
        assertNull(InputEventParser.parse(ByteArray(23)))
        assertNull(InputEventParser.parse(ByteArray(0)))
    }

    @Test
    fun parse_withOffset() {
        val bytes = eventBytes(5, 0, 0, 0, 0) + eventBytes(6, 0, 1, 30, 1)
        val raw = InputEventParser.parse(bytes, 24)
        assertEquals(RawEvent(1, 30, 1, 6000), raw)
    }
}

class DevicesParserTest {

    // Dữ liệu mẫu theo đúng định dạng /proc/bus/input/devices.
    // Chú ý: dòng B: KEY= in các từ từ CAO xuống THẤP (kernel input_seq_print_bitmap).
    private val sample = """
        I: Bus=0003 Vendor=046d Product=c52b Version=0111
        N: Name="Logitech USB Receiver"
        P: Phys=usb-0000:00:14.0-1/input0
        S: Sysfs=/devices/pci0000:00/0000:00:14.0/usb1/1-1/1-1:1.0/input/input20
        U: Uniq=
        H: Handlers=sysrq kbd event5 leds
        B: PROP=0
        B: EV=120013
        B: KEY=4 2000001c0030002
        B: MSC=10
        B: LED=1f

        I: Bus=0003 Vendor=046d Product=c52b Version=0111
        N: Name="Logitech USB Receiver Mouse"
        P: Phys=usb-0000:00:14.0-1/input1
        S: Sysfs=/devices/pci0000:00/0000:00:14.0/usb1/1-1/1-1:1.1/input/input21
        U: Uniq=
        H: Handlers=mouse0 event6
        B: PROP=0
        B: EV=17
        B: KEY=70000 0 0 0 0
        B: REL=103
        B: MSC=10

        I: Bus=0018 Vendor=0000 Product=0000 Version=0000
        N: Name="fts_ts"
        P: Phys=
        S: Sysfs=/devices/platform/soc/1c00000.i2c/i2c-0/0-0038/input/input0
        U: Uniq=
        H: Handlers=event0
        B: PROP=2
        B: EV=b
        B: KEY=400 0 0 0 0 0
        B: ABS=10000000000 0

        I: Bus=0019 Vendor=0000 Product=0001 Version=0000
        N: Name="Power Button"
        P: Phys=PNP0C0C/button/input0
        S: Sysfs=/devices/LNXSYSTM:00/LNXSYBUS:00/PNP0C0C:00/input/input1
        U: Uniq=
        H: Handlers=kbd event1
        B: PROP=0
        B: EV=3
        B: KEY=10000000000000 0

    """.trimIndent()

    @Test
    fun parseDevices_findsKeyboardAndMouse_only() {
        val devices = InputEventParser.parseDevices(sample)
        assertEquals(2, devices.size)

        val kbd = devices[0]
        assertEquals("event5", kbd.id)
        assertEquals("Logitech USB Receiver", kbd.name)
        assertEquals("/dev/input/event5", kbd.node)
        assertEquals(DeviceKind.KEYBOARD, kbd.kind)

        val mouse = devices[1]
        assertEquals("event6", mouse.id)
        assertEquals("Logitech USB Receiver Mouse", mouse.name)
        assertEquals("/dev/input/event6", mouse.node)
        assertEquals(DeviceKind.MOUSE, mouse.kind)
    }

    @Test
    fun parseDevices_skipsInternalDevices() {
        // Cảm ứng (fts_ts) và nút nguồn không được liệt kê.
        val devices = InputEventParser.parseDevices(sample)
        assertTrue(devices.none { it.name == "fts_ts" || it.name == "Power Button" })
    }

    @Test
    fun parseDevices_emptyText() {
        assertTrue(InputEventParser.parseDevices("").isEmpty())
        assertTrue(InputEventParser.parseDevices("\n\n").isEmpty())
    }

    @Test
    fun parseDevices_keyboardWithoutLetters_ignored() {
        // Chỉ có phím media (không có phím chữ) -> không tính là bàn phím.
        val text = """
            I: Bus=0003 Vendor=046d Product=c52b Version=0111
            N: Name="Logitech Media Keys"
            H: Handlers=kbd event7
            B: EV=13
            B: KEY=0 0
        """.trimIndent()
        assertTrue(InputEventParser.parseDevices(text).isEmpty())
    }

    @Test
    fun parseDevices_skipsFingerprintAndGpioKeys() {
        // Vân tay + phím cứng nguồn/âm lượng (gpio-keys): tuyệt đối không
        // được liệt kê để GRAB (grab sẽ làm mất vân tay / nút nguồn hệ thống).
        // Cả 3 node dưới đều có handler kbd + phím chữ (đủ điều kiện làm
        // bàn phím theo khả năng) — chỉ bộ lọc tên loại chúng ra.
        val text = """
            I: Bus=0018 Vendor=0000 Product=0000 Version=0000
            N: Name="Fingerprint Reader"
            H: Handlers=kbd event2
            B: EV=120013
            B: KEY=4 2000001c0030002

            I: Bus=0019 Vendor=0001 Product=0001 Version=0100
            N: Name="gpio-keys"
            H: Handlers=kbd event3
            B: EV=120013
            B: KEY=4 2000001c0030002

            I: Bus=0019 Vendor=0001 Product=0001 Version=0100
            N: Name="qpnp_pon"
            H: Handlers=kbd event4
            B: EV=120013
            B: KEY=4 2000001c0030002
        """.trimIndent()
        val devices = InputEventParser.parseDevices(text)
        assertTrue(devices.isEmpty())
    }
}

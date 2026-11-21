package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceGroupingTest {

    @Test
    fun baseName_stripsInterfaceSuffix() {
        assertEquals(
            "Compx 2.4G Wireless Receiver",
            DeviceGrouping.baseName("Compx 2.4G Wireless Receiver Keyboard"),
        )
        assertEquals(
            "Compx 2.4G Wireless Receiver",
            DeviceGrouping.baseName("Compx 2.4G Wireless Receiver Consumer Control"),
        )
        assertEquals(
            "Compx VXE NordicMouse 1K Dongle",
            DeviceGrouping.baseName("Compx VXE NordicMouse 1K Dongle"),
        )
    }

    @Test
    fun group_mergesOneDongleIntoOneDevice() {
        // 7 node từ 2 dongle (đúng ảnh màn chính build-8 của người dùng).
        val nodes = listOf(
            DeviceGrouping.Node("Compx 2.4G Wireless Receiver", false, false),
            DeviceGrouping.Node("Compx 2.4G Wireless Receiver Consumer Control", false, true),
            DeviceGrouping.Node("Compx 2.4G Wireless Receiver Keyboard", false, true),
            DeviceGrouping.Node("Compx 2.4G Wireless Receiver Mouse", true, false),
            DeviceGrouping.Node("Compx 2.4G Wireless Receiver System Control", false, true),
            DeviceGrouping.Node("Compx VXE NordicMouse 1K Dongle", true, false),
            DeviceGrouping.Node("Compx VXE NordicMouse 1K Dongle", true, false),
        )
        val grouped = DeviceGrouping.group(nodes)
        assertEquals(2, grouped.size)
        // Chuột trước.
        assertEquals("Compx VXE NordicMouse 1K Dongle", grouped[0].name)
        assertEquals(true, grouped[0].isMouse)
        assertEquals("Compx 2.4G Wireless Receiver", grouped[1].name)
        assertEquals(true, grouped[1].isMouse)
        assertEquals(true, grouped[1].isKeyboard)
    }

    @Test
    fun format_oneLine() {
        val devices = DeviceGrouping.group(
            listOf(
                DeviceGrouping.Node("Compx VXE NordicMouse 1K Dongle", true, false),
                DeviceGrouping.Node("Compx 2.4G Wireless Receiver Keyboard", false, true),
            ),
        )
        assertEquals(
            "🖱 Compx VXE NordicMouse 1K Dongle  +  ⌨ Compx 2.4G Wireless Receiver",
            DeviceGrouping.format(devices),
        )
    }

    @Test
    fun format_empty() {
        assertEquals("", DeviceGrouping.format(emptyList()))
    }
}

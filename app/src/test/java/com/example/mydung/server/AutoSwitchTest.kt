package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSwitchTest {

    private val ff = ProfileCodec.defaultFreeFire().copy(id = "ff", packageName = "com.dts.freefireth")
    private val ffMax = ProfileCodec.defaultFreeFire().copy(
        id = "max",
        name = "Free Fire MAX",
        packageName = "com.dts.freefiremax",
    )
    private val unassigned = ProfileCodec.defaultFreeFire().copy(id = "none", packageName = null)

    private val profiles = listOf(ff, ffMax, unassigned)

    @Test
    fun pick_matchDifferentProfile() {
        assertEquals(ffMax, AutoSwitch.pickProfileForPackage(profiles, "ff", "com.dts.freefiremax"))
    }

    @Test
    fun pick_alreadyActive() {
        assertNull(AutoSwitch.pickProfileForPackage(profiles, "ff", "com.dts.freefireth"))
    }

    @Test
    fun pick_noMatchKeepsCurrent() {
        assertNull(AutoSwitch.pickProfileForPackage(profiles, "ff", "com.example.other"))
        assertNull(AutoSwitch.pickProfileForPackage(profiles, "ff", "com.example.mydung"))
    }

    @Test
    fun pick_blankPackage() {
        assertNull(AutoSwitch.pickProfileForPackage(profiles, "ff", ""))
        assertNull(AutoSwitch.pickProfileForPackage(profiles, "ff", "   "))
    }

    @Test
    fun pick_emptyList() {
        assertNull(AutoSwitch.pickProfileForPackage(emptyList(), null, "com.dts.freefireth"))
    }

    @Test
    fun pick_firstOfDuplicates() {
        val dup = ff.copy(id = "ff2")
        val list = listOf(dup, ff, ffMax)
        assertEquals(dup, AutoSwitch.pickProfileForPackage(list, "max", "com.dts.freefireth"))
    }
}

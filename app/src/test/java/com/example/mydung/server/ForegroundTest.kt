package com.example.mydung.server

import android.app.ActivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTest {

    // ---------- Parse dòng FG ----------

    @Test
    fun parseFgLine_valid() {
        assertEquals("com.dts.freefireth", ForegroundParser.parseFgLine("FG com.dts.freefireth"))
        assertEquals("com.dts.freefiremax", ForegroundParser.parseFgLine("FG com.dts.freefiremax"))
    }

    @Test
    fun parseFgLine_invalid() {
        assertNull(ForegroundParser.parseFgLine("FG"))
        assertNull(ForegroundParser.parseFgLine(""))
        assertNull(ForegroundParser.parseFgLine("EV K 17 1 123"))
        assertNull(ForegroundParser.parseFgLine("FG com.a com.b")) // thừa phần
        assertNull(ForegroundParser.parseFgLine("fg com.dts.freefireth")) // sai hoa/thường
    }

    // ---------- Chọn tiến trình foreground ----------

    private fun proc(pkg: String?, importance: Int): ActivityManager.RunningAppProcessInfo {
        return ActivityManager.RunningAppProcessInfo().apply {
            processName = pkg ?: ""
            pkgList = if (pkg == null) emptyArray() else arrayOf(pkg)
            this.importance = importance
        }
    }

    @Test
    fun pickForeground_found() {
        val list = listOf(
            proc("com.example.mydung", 400), // BACKGROUND
            proc("com.dts.freefireth", ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
            proc("com.android.systemui", 100), // cũng foreground (hiếm): lấy cái đầu
        )
        assertEquals("com.dts.freefireth", ForegroundParser.pickForeground(list))
    }

    @Test
    fun pickForeground_none() {
        assertNull(ForegroundParser.pickForeground(emptyList()))
        assertNull(
            ForegroundParser.pickForeground(
                listOf(proc("com.example.mydung", 400), proc("com.android.systemui", 300)),
            ),
        )
    }

    @Test
    fun pickForeground_emptyPkgList() {
        val p = ActivityManager.RunningAppProcessInfo().apply {
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            pkgList = emptyArray()
        }
        assertNull(ForegroundParser.pickForeground(listOf(p)))
    }

    // ---------- Parse dumpsys window ----------

    @Test
    fun parseDumpsysWindow_found() {
        val out = """
            Window Manager State:
              mCurrentFocus=Window{3a2f1c0 u0 com.dts.freefireth/com.dts.freefireth.GameActivity}
              mFocusedApp=ActivityRecord{...}
        """.trimIndent()
        assertEquals("com.dts.freefireth", ForegroundParser.parseDumpsysWindow(out))
    }

    @Test
    fun parseDumpsysWindow_max() {
        val out = "  mCurrentFocus=Window{a1b2c3d u0 com.dts.freefiremax/com.dts.freefiremax.GameActivity}"
        assertEquals("com.dts.freefiremax", ForegroundParser.parseDumpsysWindow(out))
    }

    @Test
    fun parseDumpsysWindow_none() {
        assertNull(ForegroundParser.parseDumpsysWindow("Window Manager State:\n  mFocusedApp=null"))
        assertNull(ForegroundParser.parseDumpsysWindow(""))
        // Focus là cửa sổ hệ thống, không có package/activity.
        assertNull(
            ForegroundParser.parseDumpsysWindow(
                "  mCurrentFocus=Window{9f8e7d6 u0 StatusBar}",
            ),
        )
    }
}

package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {

    private fun ok(text: String): Profile {
        val r = ProfileCodec.fromJson(text)
        assertTrue("parse lỗi: ${(r as? ProfileCodec.Result.Err)?.message}", r is ProfileCodec.Result.Ok)
        return (r as ProfileCodec.Result.Ok).profile
    }

    @Test
    fun roundTrip_defaultProfile() {
        val p = ProfileCodec.defaultFreeFire()
        val back = ok(ProfileCodec.toJson(p))
        assertEquals(p, back)
        // JSON 1 dòng (lệnh PROFILE gửi 1 dòng).
        assertTrue(!ProfileCodec.toJson(p).contains('\n'))
    }

    @Test
    fun defaultProfile_hasJoystickCameraButtons() {
        val p = ProfileCodec.defaultFreeFire()
        assertEquals(1, p.elements.count { it.type == ElementType.JOYSTICK })
        assertEquals(1, p.elements.count { it.type == ElementType.CAMERA })
        assertEquals(Config.DEFAULT_BUTTONS.size, p.elements.count { it.type == ElementType.BUTTON })
        assertEquals(Config.DEFAULT_BUTTONS.size, p.elements.mapNotNull { it.keyCode }.toSet().size)
    }

    @Test
    fun cursorKey_roundTripAndDefault() {
        val p = ProfileCodec.defaultFreeFire().copy(cursorToggleKey = LinuxKey.F11)
        val back = ok(ProfileCodec.toJson(p))
        assertEquals(LinuxKey.F11, back.cursorToggleKey)
        // JSON cũ thiếu trường cursorKey -> mặc định F9 (tương thích ngược).
        val old = ok("""{"elements":[]}""")
        assertEquals(Config.CURSOR_TOGGLE_KEY, old.cursorToggleKey)
        assertEquals(LinuxKey.F9, old.cursorToggleKey)
    }

    @Test
    fun outOfRangeValues_clamped() {
        val p = ok(
            """{"name":"X","sensX":99,"sensY":-1,"toggleKey":66,
               "elements":[{"type":"BUTTON","x":1.5,"y":-0.2,"size":9,"key":57,"name":"N"}]}""",
        )
        assertEquals(5f, p.sensX, 0.001f)
        assertEquals(0.1f, p.sensY, 0.001f)
        val e = p.elements.single()
        assertEquals(1f, e.x, 0.001f)
        assertEquals(0f, e.y, 0.001f)
        assertEquals(0.5f, e.size, 0.001f)
    }

    @Test
    fun missingFields_useDefaults() {
        val p = ok("""{"elements":[]}""")
        assertEquals("Profile", p.name)
        assertEquals(Config.SENS_X, p.sensX, 0.001f)
        assertEquals(Config.SENS_Y, p.sensY, 0.001f)
        assertEquals(Config.MAP_TOGGLE_KEY, p.mapToggleKey)
        assertEquals(ProfileCodec.CURRENT_VERSION, p.schemaVersion)
        assertTrue(p.id.isNotBlank())
        assertEquals(null, p.packageName)
    }

    @Test
    fun missingVersion_treatedAsV1() {
        val p = ok("""{"name":"Cu","elements":[]}""")
        assertEquals(ProfileCodec.CURRENT_VERSION, p.schemaVersion)
    }

    @Test
    fun newerVersion_rejected() {
        val r = ProfileCodec.fromJson("""{"version":99,"name":"X","elements":[]}""")
        assertTrue(r is ProfileCodec.Result.Err)
    }

    @Test
    fun invalidJson_rejected() {
        assertTrue(ProfileCodec.fromJson("không phải json") is ProfileCodec.Result.Err)
        assertTrue(ProfileCodec.fromJson("[1,2]") is ProfileCodec.Result.Err)
        assertTrue(ProfileCodec.fromJson("") is ProfileCodec.Result.Err)
    }

    @Test
    fun unknownFields_ignored() {
        val p = ok("""{"name":"X","truongLa":123,"elements":[{"type":"BUTTON","x":0.5,"y":0.5,"key":57,"name":"N","them":"zzz"}]}""")
        assertEquals("X", p.name)
        assertEquals(1, p.elements.size)
    }

    @Test
    fun badElements_droppedOthersKept() {
        val p = ok(
            """{"name":"X","elements":[
               {"type":"BUTTON","x":0.5,"y":0.5,"key":57,"name":"Tốt"},
               {"type":"LOAI_LA","x":0.5,"y":0.5,"name":"Xấu"},
               {"type":"BUTTON","name":"Thiếu tọa độ"},
               "không phải object",
               {"type":"BUTTON","x":0.6,"y":0.6,"key":19,"name":"Tốt 2"}
            ]}""",
        )
        assertEquals(listOf("Tốt", "Tốt 2"), p.elements.map { it.name })
    }

    @Test
    fun toLayout_convertsRatios() {
        val p = ProfileCodec.defaultFreeFire()
        val l = p.toLayout(2560, 1600)
        assertEquals(Config.JOY_CENTER_X, l.joyX, 0.001f)
        assertEquals(Config.JOY_RADIUS, l.joyR, 0.001f)
        assertEquals(Config.CAM_CENTER_X, l.camX, 0.001f)
        assertEquals(Config.CAM_HALF_W, l.camHW, 0.001f)
        // Vùng camera vuông theo pixel: nửa cao = nửa rộng * 2560/1600.
        assertEquals(Config.CAM_HALF_W * 2560f / 1600f, l.camHH, 0.001f)
        assertEquals(Config.DEFAULT_BUTTONS, l.buttons)
        assertEquals(Config.MAP_TOGGLE_KEY, l.mapToggleKey)
    }

    @Test
    fun toLayout_missingJoystickCamera_usesDefaults() {
        val p = Profile(
            id = "x", name = "Chỉ nút", packageName = null, schemaVersion = 1,
            sensX = 2f, sensY = 2f, mapToggleKey = LinuxKey.F9,
            elements = listOf(
                ProfileElement(ElementType.BUTTON, 0.5f, 0.5f, 0.03f, LinuxKey.SPACE, "Nhảy"),
            ),
        )
        val l = p.toLayout(1000, 500)
        assertEquals(Config.JOY_CENTER_X, l.joyX, 0.001f)
        assertEquals(Config.CAM_CENTER_X, l.camX, 0.001f)
        assertEquals(2f, l.sensX, 0.001f)
        assertEquals(LinuxKey.F9, l.mapToggleKey)
        assertEquals(mapOf(LinuxKey.SPACE to (0.5f to 0.5f)), l.buttons)
    }

    @Test
    fun toLayout_disabledElementsIgnored() {
        val p = ProfileCodec.defaultFreeFire().copy(
            elements = ProfileCodec.defaultFreeFire().elements.map {
                if (it.type == ElementType.JOYSTICK) it.copy(enabled = false) else it
            },
        )
        val l = p.toLayout(1000, 500)
        // Joystick bị tắt -> dùng mặc định (không mất joystick hẳn).
        assertEquals(Config.JOY_CENTER_X, l.joyX, 0.001f)
    }

    @Test
    fun twoDefaultProfiles_haveDifferentIds() {
        assertNotEquals(
            ProfileCodec.defaultFreeFire().id,
            ProfileCodec.defaultFreeFire().id,
        )
    }

    @Test
    fun defaultFreeFireMax_hasMaxPackage() {
        val p = ProfileCodec.defaultFreeFireMax()
        assertEquals("Free Fire MAX", p.name)
        assertEquals("com.dts.freefiremax", p.packageName)
        assertEquals(1, p.elements.count { it.type == ElementType.JOYSTICK })
        assertEquals(Config.DEFAULT_BUTTONS.size, p.elements.count { it.type == ElementType.BUTTON })
    }

    @Test
    fun swipe_roundTrip() {
        val el = ProfileElement(
            ElementType.SWIPE, 0.3f, 0.5f, 0.03f, LinuxKey.Q, "Vuốt ngang",
            enabled = true, x2 = 0.7f, y2 = 0.5f, durationMs = 500L,
        )
        val p = ProfileCodec.defaultFreeFire().copy(elements = listOf(el))
        val back = ok(ProfileCodec.toJson(p))
        assertEquals(p, back)
        val got = back.elements.single()
        assertEquals(ElementType.SWIPE, got.type)
        assertEquals(0.7f, got.x2)
        assertEquals(0.5f, got.y2)
        assertEquals(500L, got.durationMs)
    }

    @Test
    fun swipe_oldJsonWithoutEndPoint_usesDefaults() {
        // JSON cũ (không có x2/y2/durationMs) vẫn đọc được, toLayout bù mặc định.
        val p = ok(
            """{"name":"X","elements":[
               {"type":"SWIPE","x":0.3,"y":0.5,"key":16,"name":"Vuốt"}
            ]}""",
        )
        val el = p.elements.single()
        assertEquals(ElementType.SWIPE, el.type)
        assertEquals(null, el.x2)
        assertEquals(400L, el.durationMs)
        val l = p.toLayout(1000, 500)
        val spec = l.swipes[LinuxKey.Q]
        assertTrue(spec != null)
        assertEquals(0.3f, spec!!.x1, 0.001f)
        assertEquals(0.45f, spec.x2, 0.001f) // x + 0.15 mặc định
        assertEquals(0.5f, spec.y2, 0.001f)
        assertEquals(400L, spec.durationMs)
    }

    @Test
    fun toLayout_swipeGoesToSwipesMap() {
        val p = Profile(
            id = "x", name = "Vuốt", packageName = null, schemaVersion = 1,
            sensX = 1f, sensY = 1f, mapToggleKey = LinuxKey.F8,
            elements = listOf(
                ProfileElement(
                    ElementType.SWIPE, 0.2f, 0.5f, 0.03f, LinuxKey.E, "Vuốt",
                    enabled = true, x2 = 0.8f, y2 = 0.5f, durationMs = 250L,
                ),
                ProfileElement(
                    ElementType.SWIPE, 0.2f, 0.5f, 0.03f, LinuxKey.R, "Vuốt tắt",
                    enabled = false, x2 = 0.8f, y2 = 0.5f, durationMs = 250L,
                ),
            ),
        )
        val l = p.toLayout(1000, 500)
        assertEquals(1, l.swipes.size)
        val spec = l.swipes[LinuxKey.E]!!
        assertEquals(0.2f, spec.x1, 0.001f)
        assertEquals(0.8f, spec.x2, 0.001f)
        assertEquals(250L, spec.durationMs)
        assertTrue(l.buttons.isEmpty())
    }
}

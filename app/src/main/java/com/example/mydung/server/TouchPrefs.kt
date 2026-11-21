package com.example.mydung.server

import android.content.Context

/**
 * Lưu tạm cấu hình chạm GĐ4 (độ nhạy, joystick, camera) bằng SharedPreferences.
 * Spec GĐ4 ghi "tạm lưu bằng DataStore" nhưng thêm DataStore cần sửa Gradle
 * (ngoài phạm vi), nên dùng SharedPreferences — GĐ5 thay bằng profile.
 */
object TouchPrefs {
    private const val PREFS_NAME = "touch_prefs"

    data class Values(
        val sensX: Float,
        val sensY: Float,
        val joyX: Float,
        val joyY: Float,
        val joyR: Float,
        val camX: Float,
        val camY: Float,
        val camHW: Float,
        val camHH: Float,
    ) {
        companion object {
            fun default() = Values(
                sensX = Config.SENS_X, sensY = Config.SENS_Y,
                joyX = Config.JOY_CENTER_X, joyY = Config.JOY_CENTER_Y, joyR = Config.JOY_RADIUS,
                camX = Config.CAM_CENTER_X, camY = Config.CAM_CENTER_Y,
                camHW = Config.CAM_HALF_W, camHH = Config.CAM_HALF_H,
            )
        }

        /** -> map khóa/giá trị cho lệnh TOUCHCFG. */
        fun toMap(): Map<String, Float> = mapOf(
            "sensX" to sensX, "sensY" to sensY,
            "joyX" to joyX, "joyY" to joyY, "joyR" to joyR,
            "camX" to camX, "camY" to camY, "camHW" to camHW, "camHH" to camHH,
        )
    }

    fun load(context: Context): Values {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val d = Values.default()
        return Values(
            sensX = p.getFloat("sensX", d.sensX),
            sensY = p.getFloat("sensY", d.sensY),
            joyX = p.getFloat("joyX", d.joyX),
            joyY = p.getFloat("joyY", d.joyY),
            joyR = p.getFloat("joyR", d.joyR),
            camX = p.getFloat("camX", d.camX),
            camY = p.getFloat("camY", d.camY),
            camHW = p.getFloat("camHW", d.camHW),
            camHH = p.getFloat("camHH", d.camHH),
        )
    }

    fun save(context: Context, v: Values) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("sensX", v.sensX)
            .putFloat("sensY", v.sensY)
            .putFloat("joyX", v.joyX)
            .putFloat("joyY", v.joyY)
            .putFloat("joyR", v.joyR)
            .putFloat("camX", v.camX)
            .putFloat("camY", v.camY)
            .putFloat("camHW", v.camHW)
            .putFloat("camHH", v.camHH)
            .apply()
    }
}

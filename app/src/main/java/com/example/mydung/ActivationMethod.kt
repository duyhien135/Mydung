package com.example.mydung

import android.content.Context

/**
 * Phương thức kích hoạt Server đặc quyền. Người dùng chọn trong Cài đặt,
 * mặc định Shizuku (đơn giản nhất, không cần ghép nối ADB tay).
 *
 * - SHIZUKU: qua app Shizuku (uid 2000) — dùng cho Redmi (không root).
 * - ROOT: qua `su -c` trên máy (uid 0) — dùng cho Y700 đã root bằng KernelSU.
 * - ADB: qua gỡ lỗi không dây tự nối (libadb, uid 2000) — đường cũ GĐ1/GĐ2.
 */
enum class ActivationMethod {
    SHIZUKU,
    ROOT,
    ADB,
}

object ActivationPrefs {
    private const val PREFS_APP = "keytouch_prefs"
    private const val KEY_METHOD = "activation_method"

    fun get(context: Context): ActivationMethod {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .getString(KEY_METHOD, null)
        return try {
            if (raw != null) ActivationMethod.valueOf(raw) else ActivationMethod.SHIZUKU
        } catch (_: IllegalArgumentException) {
            ActivationMethod.SHIZUKU
        }
    }

    fun set(context: Context, method: ActivationMethod) {
        context.applicationContext
            .getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().putString(KEY_METHOD, method.name).apply()
    }
}

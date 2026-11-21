package com.example.mydung.server

import android.content.Context

/**
 * Cài đặt app trong keytouch_prefs mà cả UI lẫn ServerManager đều cần đọc
 * (tần số quét chuột, tốc độ con trỏ, trạng thái ẩn lớp phủ). Đặt ở package
 * server để ServerManager dùng được mà không import UI.
 *
 * KHÔNG biên dịch được trong check-local.sh (cần android.content.Context)
 * — CI là vòng kiểm tra cuối.
 */
object KeyTouchPrefs {
    private const val PREFS = "keytouch_prefs"
    private const val KEY_POLL_RATE_HZ = "poll_rate_hz"
    private const val KEY_CURSOR_SPEED = "cursor_speed"
    private const val KEY_OVERLAY_HIDDEN = "overlay_hidden"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tần số quét chuột (Hz) — tốc độ xử lý nội bộ của app, mặc định 500. */
    fun pollRateHz(context: Context): Int {
        val v = prefs(context).getInt(KEY_POLL_RATE_HZ, Config.DEFAULT_POLL_RATE_HZ)
        return if (v in Config.POLL_RATES) v else Config.DEFAULT_POLL_RATE_HZ
    }

    fun setPollRateHz(context: Context, hz: Int) {
        val v = if (hz in Config.POLL_RATES) hz else Config.DEFAULT_POLL_RATE_HZ
        prefs(context).edit().putInt(KEY_POLL_RATE_HZ, v).apply()
    }

    /** Tốc độ con trỏ ảo (0.5..3.0, mặc định 1.0). */
    fun cursorSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_CURSOR_SPEED, Config.CURSOR_SPEED_DEFAULT)
            .coerceIn(Config.CURSOR_SPEED_MIN, Config.CURSOR_SPEED_MAX)

    fun setCursorSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(
            KEY_CURSOR_SPEED,
            v.coerceIn(Config.CURSOR_SPEED_MIN, Config.CURSOR_SPEED_MAX),
        ).apply()
    }

    /**
     * Lớp phủ có đang bị ẩn thủ công không. LƯU LẠI để mở app lần sau vẫn
     * nhớ (người dùng chọn nhớ trạng thái; quên phím thì bấm "Hiện lại lớp
     * phủ" trong tab Cài đặt của app).
     */
    fun overlayHidden(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_HIDDEN, false)

    fun setOverlayHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_HIDDEN, hidden).apply()
    }
}

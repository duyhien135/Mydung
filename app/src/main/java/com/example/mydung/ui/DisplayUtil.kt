package com.example.mydung.ui

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** Kích thước thật của màn hình (tính cả xoay) + xoay + displayId. */
data class RealDisplay(val width: Int, val height: Int, val rotation: Int, val displayId: Int)

/**
 * Đọc kích thước THẬT của màn hình (gồm cả vùng tai thỏ và thanh điều
 * hướng) — overlay và Server cùng dùng hàm này nên tọa độ tỉ lệ 0-1 trong
 * trình sửa khớp đúng tọa độ tiêm.
 *
 * API 30+: WindowMetrics tối đa (kích thước cửa sổ lớn nhất có thể = toàn
 * màn hình); cũ hơn: getRealMetrics. Lỗi -> null (caller dùng kích thước
 * cửa sổ làm dự phòng).
 */
@Suppress("DEPRECATION")
fun readRealDisplay(context: Context): RealDisplay? {
    return try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return RealDisplay(bounds.width(), bounds.height(), display.rotation, display.displayId)
            }
            // bounds lạ thì rơi xuống getRealMetrics bên dưới.
        }
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        RealDisplay(metrics.widthPixels, metrics.heightPixels, display.rotation, display.displayId)
    } catch (_: Exception) {
        null
    }
}

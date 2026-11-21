package com.example.mydung.server

import java.util.Locale

/**
 * Giao thức GĐ4 (dùng chung Server và app). Tọa độ test/config viết theo
 * TỈ LỆ màn hình (0.0..1.0), Server nhân với kích thước thật.
 *
 * - DISPLAY <rộng> <cao> <xoay> <displayId>
 * - MAP <0|1>
 * - MAPSTATUS (không tham số) -> "MAP map=1 display=2560x1600 pointers=2
 *   latencyMs=4 injectErr=0 injectOk=1 injector=reflect mode=camera cursor=0.58,0.50"
 * - TOUCHCFG <khóa>=<giá trị> ... (khóa: sensX sensY joyX joyY joyR camX
 *   camY camHW camHH; thiếu khóa nào giữ nguyên giá trị đó)
 * - TESTTOUCH TAP <xr> <yr> | SWIPE <x1r> <y1r> <x2r> <y2r> <ms> |
 *   TWO <x1r> <y1r> <x2r> <y2r>
 * - INJECT <reflect|evdev> (GĐ6: chọn cách tiêm; mặc định reflect)
 */
object TouchProtocol {

    data class DisplayInfo(val width: Int, val height: Int, val rotation: Int, val displayId: Int)

    sealed class TestTouch {
        data class Tap(val xRatio: Float, val yRatio: Float) : TestTouch()
        data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Int) : TestTouch()
        data class Two(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : TestTouch()
    }

    /** Parse "DISPLAY <w> <h> <rot> <displayId>". Sai -> null. */
    fun parseDisplay(line: String): DisplayInfo? {
        val p = line.trim().split("\\s+".toRegex())
        if (p.size != 5 || p[0] != "DISPLAY") return null
        val w = p[1].toIntOrNull() ?: return null
        val h = p[2].toIntOrNull() ?: return null
        val rot = p[3].toIntOrNull() ?: return null
        val id = p[4].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || rot !in 0..3 || id < 0) return null
        return DisplayInfo(w, h, rot, id)
    }

    /** Parse "MAP <0|1>" -> true/false. Sai -> null. */
    fun parseMap(line: String): Boolean? {
        val p = line.trim().split("\\s+".toRegex())
        if (p.size != 2 || p[0] != "MAP") return null
        return when (p[1]) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    /** Các khóa TOUCHCFG được chấp nhận. */
    val TOUCHCFG_KEYS = setOf(
        "sensX", "sensY", "joyX", "joyY", "joyR", "camX", "camY", "camHW", "camHH",
    )

    /**
     * Parse "TOUCHCFG k=v ..." -> map khóa/giá trị (chỉ khóa đã biết).
     * Sai cú pháp hoặc giá trị không phải số -> null (từ chối cả lệnh).
     */
    fun parseTouchCfg(line: String): Map<String, Float>? {
        val p = line.trim().split("\\s+".toRegex())
        if (p.isEmpty() || p[0] != "TOUCHCFG" || p.size < 2) return null
        val out = HashMap<String, Float>()
        for (i in 1 until p.size) {
            val kv = p[i].split("=", limit = 2)
            if (kv.size != 2 || kv[0] !in TOUCHCFG_KEYS) return null
            val v = kv[1].toFloatOrNull() ?: return null
            if (!v.isFinite()) return null
            out[kv[0]] = v
        }
        return out
    }

    /** Parse lệnh TESTTOUCH. Sai cú pháp hoặc tỉ lệ ngoài 0..1 -> null. */
    fun parseTestTouch(line: String): TestTouch? {
        val p = line.trim().split("\\s+".toRegex())
        if (p.size < 2 || p[0] != "TESTTOUCH") return null
        fun ratio(s: String?): Float? {
            val v = s?.toFloatOrNull() ?: return null
            return if (v.isFinite() && v in 0f..1f) v else null
        }
        return when (p[1]) {
            "TAP" -> {
                if (p.size != 4) return null
                val x = ratio(p[2]) ?: return null
                val y = ratio(p[3]) ?: return null
                TestTouch.Tap(x, y)
            }
            "SWIPE" -> {
                if (p.size != 7) return null
                val x1 = ratio(p[2]) ?: return null
                val y1 = ratio(p[3]) ?: return null
                val x2 = ratio(p[4]) ?: return null
                val y2 = ratio(p[5]) ?: return null
                val ms = p[6].toIntOrNull() ?: return null
                if (ms !in 50..5000) return null
                TestTouch.Swipe(x1, y1, x2, y2, ms)
            }
            "TWO" -> {
                if (p.size != 6) return null
                val x1 = ratio(p[2]) ?: return null
                val y1 = ratio(p[3]) ?: return null
                val x2 = ratio(p[4]) ?: return null
                val y2 = ratio(p[5]) ?: return null
                TestTouch.Two(x1, y1, x2, y2)
            }
            else -> null
        }
    }

    /** Parse "INJECT <reflect|evdev>" (GĐ6). Sai -> null. */
    fun parseInject(line: String): InjectorMode? {
        val p = line.trim().split("\\s+".toRegex())
        if (p.size != 2 || p[0] != "INJECT") return null
        return InjectorMode.fromKey(p[1])
    }

    /** Dòng trạng thái MAP trả về cho lệnh MAPSTATUS.
     *  mode: "camera" (nhìn tự do) hoặc "cursor" (con trỏ ảo); cursorX/Y là
     *  vị trí con trỏ theo tỉ lệ màn hình (app vẽ chấm con trỏ khi mode=cursor). */
    fun formatMapStatus(
        mapOn: Boolean, display: DisplayInfo?, pointers: Int,
        latencyMs: Long, injectErr: Long, injectOk: Boolean,
        injector: String = InjectorMode.REFLECT.key,
        mode: String = "camera",
        cursorX: Float = 0.5f, cursorY: Float = 0.5f,
    ): String {
        val d = display?.let { "${it.width}x${it.height}" } ?: "none"
        // Locale.US: dấu thập phân luôn là "." (locale vi dùng "," sẽ làm hỏng parse).
        val cx = String.format(Locale.US, "%.2f", cursorX)
        val cy = String.format(Locale.US, "%.2f", cursorY)
        return "MAP map=${if (mapOn) 1 else 0} display=$d pointers=$pointers " +
            "latencyMs=$latencyMs injectErr=$injectErr injectOk=${if (injectOk) 1 else 0} " +
            "injector=$injector mode=$mode cursor=$cx,$cy"
    }
}

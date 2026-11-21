package com.example.mydung.server

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Codec evdev thuần Kotlin (GĐ6, học theo GG Mouse Pro): dịch đặc tả
 * MotionEvent của TouchEngine thành các struct input_event thô để ghi
 * thẳng vào node cảm ứng /dev/input/eventX, và tìm node cảm ứng thật
 * theo khả năng (KHÔNG hardcode eventN — quy tắc v1).
 *
 * Giao thức đa điểm (MT protocol B, slot-based):
 * - Xuống ngón: SLOT=id, TRACKING_ID=id, POSITION_X/Y, (ngón đầu: BTN_TOUCH=1).
 * - Di chuyển: SLOT=id, POSITION_X/Y cho mỗi ngón.
 * - Nhả ngón: SLOT=id, TRACKING_ID=-1, (ngón cuối: BTN_TOUCH=0).
 * - Hủy khẩn: TRACKING_ID=-1 cho mọi ngón + BTN_TOUCH=0.
 * - Cuối mỗi khung: SYN_REPORT. tracking id lấy đúng bằng id ngón (0..9):
 *   ổn định trong lúc ngón còn đè, không trùng giữa các ngón cùng lúc.
 *
 * LƯU Ý: ioctl EVIOCGBIT/EVIOCGABS cần truyền buffer nên không gọi được
 * qua libcore (chỉ có ioctlInt với tham số int). Cách tìm node ở đây:
 * đọc bitmap khả năng trong /proc/bus/input/devices, rồi lấy khoảng giá
 * trị ABS bằng `getevent -p` (lệnh này tự gọi đúng các ioctl đó) — tương
 * đương, không cần JNI. File /sys/.../capabilities/abs KHÔNG dùng được:
 * nó chỉ chứa bitmap hex, không có min/max.
 */
object Evdev {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_ABS = 3
    const val SYN_REPORT = 0
    const val ABS_MT_SLOT = 0x2f // 47
    const val ABS_MT_POSITION_X = 0x35 // 53
    const val ABS_MT_POSITION_Y = 0x36 // 54
    const val ABS_MT_TRACKING_ID = 0x39 // 57
    const val BTN_TOUCH = 0x14a // 330
    const val INPUT_EVENT_SIZE = 24

    /** 1 event evdev: (type, code, value). */
    data class EvdevEvent(val type: Int, val code: Int, val value: Int)

    /** Khoảng giá trị của 1 trục ABS (đọc từ `getevent -p`). */
    data class AbsRange(val min: Int, val max: Int) {
        val span: Int get() = max - min
    }

    /** Node cảm ứng đã tìm được + khoảng ABS của nó. */
    data class TouchNode(val path: String, val xRange: AbsRange, val yRange: AbsRange)

    /** Mã hóa 1 khung event thành mảng byte (mỗi event 24 byte LE) để ghi 1 lần. */
    fun encodeFrame(events: List<EvdevEvent>, timeMs: Long): ByteArray {
        val buf = ByteBuffer.allocate(events.size * INPUT_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val sec = timeMs / 1000
        val usec = (timeMs % 1000) * 1000
        for (e in events) {
            buf.putLong(sec)
            buf.putLong(usec)
            buf.putShort(e.type.toShort())
            buf.putShort(e.code.toShort())
            buf.putInt(e.value)
        }
        return buf.array()
    }

    /**
     * Dịch đặc tả MotionEvent thành danh sách event evdev (đã gồm SYN_REPORT
     * cuối khung). Tọa độ pixel đổi sang khoảng ABS của màn hình. Action lạ
     * hoặc thiếu ngón -> danh sách rỗng (không tiêm gì).
     */
    fun translateSpec(
        spec: TouchEngine.MotionEventSpec,
        displayW: Int, displayH: Int,
        xRange: AbsRange, yRange: AbsRange,
    ): List<EvdevEvent> {
        val out = ArrayList<EvdevEvent>()
        val base = spec.action and 0xFF
        val index = (spec.action shr TouchEngine.ACTION_POINTER_INDEX_SHIFT) and 0xFF
        fun ax(p: TouchEngine.Pointer) = scale(p.x, displayW, xRange)
        fun ay(p: TouchEngine.Pointer) = scale(p.y, displayH, yRange)
        fun down(p: TouchEngine.Pointer) {
            out.add(EvdevEvent(EV_ABS, ABS_MT_SLOT, p.id))
            out.add(EvdevEvent(EV_ABS, ABS_MT_TRACKING_ID, p.id))
            out.add(EvdevEvent(EV_ABS, ABS_MT_POSITION_X, ax(p)))
            out.add(EvdevEvent(EV_ABS, ABS_MT_POSITION_Y, ay(p)))
        }
        fun move(p: TouchEngine.Pointer) {
            out.add(EvdevEvent(EV_ABS, ABS_MT_SLOT, p.id))
            out.add(EvdevEvent(EV_ABS, ABS_MT_POSITION_X, ax(p)))
            out.add(EvdevEvent(EV_ABS, ABS_MT_POSITION_Y, ay(p)))
        }
        fun up(p: TouchEngine.Pointer) {
            out.add(EvdevEvent(EV_ABS, ABS_MT_SLOT, p.id))
            out.add(EvdevEvent(EV_ABS, ABS_MT_TRACKING_ID, -1))
        }
        when (base) {
            TouchEngine.ACTION_DOWN -> {
                if (spec.pointers.size != 1) return emptyList()
                out.add(EvdevEvent(EV_KEY, BTN_TOUCH, 1))
                down(spec.pointers[0])
            }
            TouchEngine.ACTION_POINTER_DOWN -> {
                val p = spec.pointers.getOrNull(index) ?: return emptyList()
                down(p)
            }
            TouchEngine.ACTION_MOVE -> {
                if (spec.pointers.isEmpty()) return emptyList()
                for (p in spec.pointers) move(p)
            }
            TouchEngine.ACTION_UP -> {
                if (spec.pointers.size != 1) return emptyList()
                up(spec.pointers[0])
                out.add(EvdevEvent(EV_KEY, BTN_TOUCH, 0))
            }
            TouchEngine.ACTION_POINTER_UP -> {
                val p = spec.pointers.getOrNull(index) ?: return emptyList()
                up(p)
            }
            TouchEngine.ACTION_CANCEL -> {
                if (spec.pointers.isEmpty()) return emptyList()
                for (p in spec.pointers) up(p)
                out.add(EvdevEvent(EV_KEY, BTN_TOUCH, 0))
            }
            else -> return emptyList()
        }
        out.add(EvdevEvent(EV_SYN, SYN_REPORT, 0))
        return out
    }

    /** Đổi tọa độ pixel sang giá trị ABS, kẹp trong khoảng. */
    fun scale(pixel: Float, displayPx: Int, range: AbsRange): Int {
        if (displayPx <= 0 || range.span <= 0) return range.min
        val v = range.min + (pixel / displayPx) * range.span
        return v.toInt().coerceIn(range.min, range.max)
    }

    /**
     * Event hủy khẩn cấp toàn bộ slot (0..9): TRACKING_ID=-1 cho mọi slot
     * + BTN_TOUCH=0 + SYN_REPORT. Dùng khi dừng Server / đổi injector để
     * không còn slot nào kẹt, kể cả slot mà engine đã mất dấu (ví dụ race
     * khi đổi injector). LƯU Ý: cũng nhả ngón tay thật đang chạm (chung
     * không gian slot trên cùng node) — chỉ gọi khi dừng/đổi, không gọi
     * trong lúc chơi.
     */
    fun releaseAllSlotsEvents(): List<EvdevEvent> {
        val out = ArrayList<EvdevEvent>()
        for (slot in 0 until TouchEngine.MAX_POINTERS) {
            out.add(EvdevEvent(EV_ABS, ABS_MT_SLOT, slot))
            out.add(EvdevEvent(EV_ABS, ABS_MT_TRACKING_ID, -1))
        }
        out.add(EvdevEvent(EV_KEY, BTN_TOUCH, 0))
        out.add(EvdevEvent(EV_SYN, SYN_REPORT, 0))
        return out
    }

    /**
     * Tìm node cảm ứng đa điểm trong /proc/bus/input/devices: node có
     * EV_ABS + các trục ABS_MT_POSITION_X/Y/TRACKING_ID. Lấy khoảng ABS
     * qua [readGetevent] (output của `getevent -p <node>`).
     * Nhiều node thì chọn node có khoảng X lớn nhất (màn hình chính).
     * Không tìm được -> null.
     */
    fun findTouchNode(
        procText: String,
        readGetevent: (nodePath: String) -> String?,
    ): TouchNode? {
        var best: TouchNode? = null
        for (block in procText.split("\n\n")) {
            if (block.isBlank()) continue
            var handlers = ""
            var evMask = 0L
            val absWords = ArrayList<Long>()
            for (rawLine in block.lines()) {
                val line = rawLine.trim()
                when {
                    line.startsWith("H: Handlers=") ->
                        handlers = line.substringAfter("H: Handlers=").trim()
                    line.startsWith("B: EV=") ->
                        evMask = line.substringAfter("B: EV=").trim()
                            .split("\\s+".toRegex()).firstOrNull()?.toLongOrNull(16) ?: 0L
                    line.startsWith("B: ABS=") -> {
                        absWords.clear()
                        // Kernel in các từ từ CAO xuống THẤP: đảo lại để
                        // index 0 = bit 0-63 (giống cách parse B: KEY= ở GĐ3).
                        line.substringAfter("B: ABS=").trim().split("\\s+".toRegex())
                            .reversed()
                            .forEach { absWords.add(it.toLongOrNull(16) ?: 0L) }
                    }
                }
            }
            val eventNode = handlers.split("\\s+".toRegex())
                .firstOrNull { it.startsWith("event") } ?: continue
            if (!hasBit(evMask, EV_ABS)) continue
            if (!hasAbsCode(absWords, ABS_MT_POSITION_X) ||
                !hasAbsCode(absWords, ABS_MT_POSITION_Y) ||
                !hasAbsCode(absWords, ABS_MT_TRACKING_ID)
            ) {
                continue
            }
            val nodePath = "/dev/input/$eventNode"
            val geteventOut = readGetevent(nodePath) ?: continue
            val ranges = parseGeteventRanges(geteventOut) ?: continue
            val node = TouchNode(nodePath, ranges.first, ranges.second)
            if (best == null || node.xRange.span > best.xRange.span) best = node
        }
        return best
    }

    /**
     * Parse output `getevent -p /dev/input/eventX` thành khoảng X (mã 0x35)
     * và Y (mã 0x36). Dòng ABS có dạng:
     * `  0035 : value 0, min 0, max 1599, fuzz 0, flat 0, resolution 0`
     * (mã hex, min/max thập phân). Thiếu 1 trong 2 -> null.
     */
    fun parseGeteventRanges(text: String): Pair<AbsRange, AbsRange>? {
        var x: AbsRange? = null
        var y: AbsRange? = null
        val re = Regex("""^\s*([0-9a-fA-F]{4})\s*:\s*value\s+(-?\d+),\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        for (rawLine in text.lines()) {
            val m = re.find(rawLine) ?: continue
            val code = m.groupValues[1].toIntOrNull(16) ?: continue
            val min = m.groupValues[3].toIntOrNull() ?: continue
            val max = m.groupValues[4].toIntOrNull() ?: continue
            when (code) {
                ABS_MT_POSITION_X -> x = AbsRange(min, max)
                ABS_MT_POSITION_Y -> y = AbsRange(min, max)
            }
        }
        return if (x != null && y != null) x to y else null
    }

    private fun hasBit(mask: Long, bit: Int) = ((mask shr bit) and 1L) == 1L

    private fun hasAbsCode(words: List<Long>, code: Int): Boolean {
        val w = words.getOrNull(code / 64) ?: return false
        return ((w shr (code % 64)) and 1L) == 1L
    }
}

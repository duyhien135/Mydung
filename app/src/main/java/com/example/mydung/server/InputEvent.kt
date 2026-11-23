package com.example.mydung.server

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Một struct input_event thô đọc từ /dev/input (arm64: 24 byte, little-endian). */
data class RawEvent(val type: Int, val code: Int, val value: Int, val timeMs: Long)

/** Sự kiện đã xử lý, đưa vào InputBus. */
sealed class InputEvent {
    data class Key(val code: Int, val pressed: Boolean, val timeMs: Long) : InputEvent()
    data class MouseMove(val dx: Int, val dy: Int, val timeMs: Long) : InputEvent()
    data class Button(val code: Int, val pressed: Boolean, val timeMs: Long) : InputEvent()
    data class Wheel(val vertical: Int, val horizontal: Int, val timeMs: Long) : InputEvent()
    data class Device(val added: Boolean, val info: InputDeviceInfo) : InputEvent()
}

enum class DeviceKind { KEYBOARD, MOUSE }

/** id = tên node (event<N>), ổn định trong lúc thiết bị còn cắm. */
data class InputDeviceInfo(val id: String, val name: String, val node: String, val kind: DeviceKind)

/** Parser thuần Kotlin, không phụ thuộc Android. */
object InputEventParser {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_REL = 2
    const val EV_ABS = 3
    const val REL_X = 0
    const val REL_Y = 1
    const val REL_HWHEEL = 6
    const val REL_WHEEL = 8
    const val INPUT_EVENT_SIZE = 24

    /**
     * Parse 24 byte thành RawEvent. Layout arm64 little-endian:
     * 8 byte tv_sec, 8 byte tv_usec, 2 byte type, 2 byte code, 4 byte value (có dấu).
     * Trả null nếu không đủ 24 byte.
     */
    fun parse(bytes: ByteArray, offset: Int = 0): RawEvent? {
        if (bytes.size - offset < INPUT_EVENT_SIZE) return null
        val buf = ByteBuffer.wrap(bytes, offset, INPUT_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val sec = buf.long
        val usec = buf.long
        val type = buf.short.toInt() and 0xFFFF
        val code = buf.short.toInt() and 0xFFFF
        val value = buf.int
        return RawEvent(type, code, value, sec * 1000 + usec / 1000)
    }

    /** Parse nội dung /proc/bus/input/devices thành danh sách thiết bị USB (bàn phím/chuột). */
    fun parseDevices(text: String): List<InputDeviceInfo> {
        val out = ArrayList<InputDeviceInfo>()
        for (block in text.split("\n\n")) {
            if (block.isBlank()) continue
            var name = ""
            var handlers = ""
            var evMask = 0L
            var relMask = 0L
            val keyWords = ArrayList<Long>()
            for (rawLine in block.lines()) {
                val line = rawLine.trim()
                when {
                    line.startsWith("N: Name=") -> name = line.substringAfter("N: Name=").trim().trim('"')
                    line.startsWith("H: Handlers=") -> handlers = line.substringAfter("H: Handlers=").trim()
                    line.startsWith("B: EV=") -> evMask = firstHex(line.substringAfter("B: EV="))
                    line.startsWith("B: REL=") -> relMask = firstHex(line.substringAfter("B: REL="))
                    line.startsWith("B: KEY=") -> {
                        keyWords.clear()
                        // Kernel in các từ theo thứ tự từ CAO xuống THẤP
                        // (input_seq_print_bitmap: for i = BITS_TO_LONGS-1 down to 0),
                        // và bỏ các từ 0 ở đầu. Đảo lại để index 0 = bit 0-63.
                        line.substringAfter("B: KEY=").trim().split("\\s+".toRegex())
                            .reversed()
                            .forEach { keyWords.add(it.toLongOrNull(16) ?: 0L) }
                    }
                }
            }
            val eventNode = handlers.split("\\s+".toRegex()).firstOrNull { it.startsWith("event") } ?: continue
            classify(name, eventNode, handlers, evMask, relMask, keyWords)?.let { out.add(it) }
        }
        return out
    }

    private fun firstHex(s: String): Long =
        s.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull(16) ?: 0L

    private fun hasBit(mask: Long, bit: Int) = ((mask shr bit) and 1L) == 1L

    private fun hasKeyCode(words: List<Long>, code: Int): Boolean {
        val w = words.getOrNull(code / 64) ?: return false
        return ((w shr (code % 64)) and 1L) == 1L
    }

    // Tên thiết bị nội của tablet (cảm ứng, nút nguồn, cảm biến...) -> bỏ qua.
    // "hall" chỉ khớp "hall sensor" để khỏi loại nhầm bàn phím hall-effect USB.
    // "fingerprint" khớp riêng: cảm biến vân tay có EV_KEY nhưng không phải
    // bàn phím, tuyệt đối không được GRAB (grab sẽ làm mất vân tay hệ thống).
    private val INTERNAL_NAME =
        Regex("(?i)touch|digitizer|pen|stylus|fts|goodix|synaptics|elan|wacom|power|pwrkey|sensor|accel|gyro|magnet|proxim|hall sensor|gpio|pogo|qpnp|pmic|fingerprint")

    /** Tên thiết bị nội (cảm ứng, nút nguồn, cảm biến...) hay không.
     *  Public để DeviceScanner dùng chung cho cả đường /proc lẫn fallback
     *  EVIOCGBIT (tên fallback đọc từ sysfs). */
    fun isInternalName(name: String): Boolean = INTERNAL_NAME.containsMatchIn(name)

    /**
     * Phân loại theo khả năng của từng node, không theo tên (1 thiết bị USB có thể
     * xuất nhiều node: bàn phím + phím media, chuột + bàn phím phụ).
     * Bàn phím = có EV_KEY + handler kbd + có phím chữ. Chuột = có EV_REL (X, Y) + BTN_LEFT.
     */
    private fun classify(
        name: String, eventNode: String, handlers: String,
        evMask: Long, relMask: Long, keyWords: List<Long>,
    ): InputDeviceInfo? {
        if (isInternalName(name)) return null
        val hasKey = hasBit(evMask, EV_KEY)
        val hasRel = hasBit(evMask, EV_REL)
        val hasAbs = hasBit(evMask, EV_ABS)
        val hasKbd = handlers.split("\\s+".toRegex()).contains("kbd")
        if (hasAbs && !hasRel && !hasKbd) return null // màn cảm ứng / bút
        val hasLetters = (16..25).any { hasKeyCode(keyWords, it) } ||
            (30..38).any { hasKeyCode(keyWords, it) } ||
            (44..50).any { hasKeyCode(keyWords, it) }
        return when {
            hasKey && hasKbd && hasLetters ->
                InputDeviceInfo(eventNode, name, "/dev/input/$eventNode", DeviceKind.KEYBOARD)
            hasRel && hasBit(relMask, REL_X) && hasBit(relMask, REL_Y) &&
                hasKeyCode(keyWords, LinuxKey.BTN_LEFT) ->
                InputDeviceInfo(eventNode, name, "/dev/input/$eventNode", DeviceKind.MOUSE)
            else -> null
        }
    }
}

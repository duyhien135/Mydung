package com.example.mydung.server

import java.io.File

/**
 * Đọc khả năng thiết bị evdev từ sysfs — ĐƯỜNG PHÂN LOẠI CHÍNH từ
 * build-14 (thay EVIOCGBIT làm đường chính).
 *
 * Bối cảnh: trên Redmi Turbo 4 (HyperOS, Android 15), Server không đọc
 * được /proc/bus/input/devices (proc:ERR — SELinux siết) và fallback
 * EVIOCGBIT cũng phân loại được 0 node (fb:0 — ioctl reflection có
 * thể bị chặn hoặc fd giả kiểm chứng loại oan strategy). sysfs thì
 * chỉ cần đọc file text, không cần ioctl, không cần mở /dev/input:
 * /sys/class/input/eventN/device/capabilities/{ev,key,rel,abs}.
 *
 * Định dạng mỗi file capabilities: chuỗi hex, mỗi từ 64-bit = tối đa
 * 16 ký tự hex, các từ cách nhau khoảng trắng, từ cao nhất đứng
 * TRƯỚC (big-endian theo từ) — từ cuối cùng là word 0 (bit 0-63),
 * bit thấp nhất của mỗi từ ứng với mã sự kiện nhỏ nhất. Cùng ngữ
 * nghĩa bit như EVIOCGBIT nên chỉ cần 32 bit thấp của word 0 để
 * build [EvdevCapsMasks] rồi phân loại bằng đúng [EvdevCaps.classify].
 *
 * Đọc file lỗi (không tồn tại, không có quyền, parse lỗi) thì trả
 * null lặng lẽ, không crash — DeviceScanner sẽ rớt xuống EVIOCGBIT.
 */
object SysfsCaps {

    /** Gốc sysfs — test trỏ sang thư mục giả qua setter này (nhớ khôi phục). */
    @Volatile
    var inputRoot: String = "/sys/class/input"

    /** Kết quả probe sysfs cho 1 node. */
    sealed class SysfsProbe {
        /**
         * Đọc được mask sysfs: kind = chuột/phím phân loại được, null
         * nếu không phải (cảm ứng, nút nguồn, tên nội bộ...). Đọc được
         * là đủ kết luận — không cần thử EVIOCGBIT nữa.
         */
        data class Read(val kind: DeviceKind?, val name: String?) : SysfsProbe()

        /** Không đọc được mask (file lỗi/parse lỗi) — nên thử đường khác. */
        object Unreadable : SysfsProbe()
    }

    /**
     * Parse chuỗi hex capabilities -> 32 bit thấp của word 0 (từ cuối
     * cùng). null nếu chuỗi rỗng hoặc từ cuối không phải hex hợp lệ.
     */
    fun parseMaskWord0(text: String): Int? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val last = words.last()
        if (last.length > 16) return null
        val value = last.toULongOrNull(16) ?: return null
        return value.toInt() // 32 bit thấp của word 0
    }

    /** Đọc mask word 0 của 1 loại (ev/key/rel/abs). null nếu đọc/parse lỗi. */
    fun readMaskWord0(eventId: String, kind: String): Int? {
        val text = try {
            File("$inputRoot/$eventId/device/capabilities/$kind").readText()
        } catch (_: Exception) {
            return null
        }
        return parseMaskWord0(text)
    }

    /** Đọc đủ 4 mask (EV/KEY/REL/ABS) của 1 node. null nếu lỗi file nào. */
    fun readMasks(eventId: String): EvdevCapsMasks? {
        val ev = readMaskWord0(eventId, "ev") ?: return null
        val key = readMaskWord0(eventId, "key") ?: return null
        val rel = readMaskWord0(eventId, "rel") ?: return null
        val abs = readMaskWord0(eventId, "abs") ?: return null
        return EvdevCapsMasks(ev, key, rel, abs)
    }

    /** Tên thiết bị từ sysfs. null nếu không đọc được. */
    fun readName(eventId: String): String? = try {
        File("$inputRoot/$eventId/device/name").readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Phân loại 1 node bằng sysfs: đọc mask rồi phân loại theo đúng luật
     * [EvdevCaps.classify], loại tên nội bộ (cảm ứng, nút nguồn...).
     * Không mở /dev/input, không gọi ioctl.
     */
    /** Node đã log mask 1 lần khi classify null (đỡ spam log mỗi lần quét). */
    private val nullClassifyLogged = mutableSetOf<String>()

    fun probe(eventId: String): SysfsProbe {
        val masks = readMasks(eventId) ?: return SysfsProbe.Unreadable
        val kind = EvdevCaps.classify(masks)
        if (kind == null) {
            synchronized(nullClassifyLogged) {
                if (nullClassifyLogged.add(eventId)) {
                    Server.log(
                        "Sysfs: $eventId classify null " +
                            "(ev=0x${masks.ev.toString(16)} key=0x${masks.keyWord0.toString(16)} " +
                            "rel=0x${masks.rel.toString(16)} abs=0x${masks.abs.toString(16)}).",
                    )
                }
            }
            return SysfsProbe.Read(null, readName(eventId))
        }
        val name = readName(eventId)
        if (name != null && InputEventParser.isInternalName(name)) {
            return SysfsProbe.Read(null, name)
        }
        return SysfsProbe.Read(kind, name)
    }
}

package com.example.mydung.server

/**
 * Gộp thiết bị theo thiết bị vật lý (build-9): 1 dongle USB hiện nhiều node
 * input (Receiver / Consumer Control / Keyboard / Mouse / System Control)
 * thì gộp thành 1 dòng gọn trên màn chính. Thuần logic nên unit test được.
 */
object DeviceGrouping {

    /** 1 node input: tên + loại (chuột/phím, đọc từ sources của InputDevice). */
    data class Node(val name: String, val isMouse: Boolean, val isKeyboard: Boolean)

    /** 1 thiết bị vật lý sau khi gộp (có thể vừa chuột vừa phím). */
    data class PhysicalDevice(val name: String, val isMouse: Boolean, val isKeyboard: Boolean)

    /** Hậu tố interface của cùng 1 dongle — bỏ để lấy tên gốc. */
    private val INTERFACE_SUFFIXES = listOf(
        " Consumer Control", " System Control", " Keyboard", " Mouse",
    )

    /** Tên gốc sau khi bỏ hậu tố interface (vd "Compx 2.4G Receiver Keyboard" -> "Compx 2.4G Receiver"). */
    fun baseName(name: String): String {
        var n = name.trim()
        for (s in INTERFACE_SUFFIXES) {
            if (n.endsWith(s)) {
                n = n.removeSuffix(s).trim()
                break
            }
        }
        return n
    }

    /** Gộp danh sách node thành thiết bị vật lý (chuột thuần trước, rồi chuột+phím, rồi phím, rồi theo tên). */
    fun group(nodes: List<Node>): List<PhysicalDevice> {
        val merged = LinkedHashMap<String, PhysicalDevice>()
        for (n in nodes) {
            val base = baseName(n.name)
            val prev = merged[base]
            merged[base] = PhysicalDevice(
                base,
                (prev?.isMouse ?: false) || n.isMouse,
                (prev?.isKeyboard ?: false) || n.isKeyboard,
            )
        }
        fun rank(d: PhysicalDevice): Int = when {
            d.isMouse && !d.isKeyboard -> 0 // chuột thuần
            d.isMouse && d.isKeyboard -> 1 // vừa chuột vừa phím (receiver combo)
            d.isKeyboard -> 2 // phím thuần
            else -> 3 // không rõ loại
        }
        return merged.values.sortedWith(compareBy<PhysicalDevice> { rank(it) }.thenBy { it.name })
    }

    /** 1 dòng gọn: "🖱 tên + ⌨ tên". Thiết bị vừa chuột vừa phím thì hiện cả 2 icon. */
    fun format(devices: List<PhysicalDevice>): String {
        return devices.joinToString("  +  ") { d ->
            val icons = buildString {
                if (d.isMouse) append("🖱 ")
                if (d.isKeyboard) append("⌨ ")
            }.trimEnd()
            if (icons.isEmpty()) d.name else "$icons ${d.name}"
        }
    }
}

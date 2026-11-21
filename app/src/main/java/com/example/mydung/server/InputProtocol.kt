package com.example.mydung.server

/** Định dạng dòng văn bản của giao thức GĐ3 (dùng chung Server và app). */
object InputProtocol {

    /** Sự kiện -> dòng "EV ..." gửi về app. */
    fun format(e: InputEvent): String = when (e) {
        is InputEvent.Key -> "EV K ${e.code} ${if (e.pressed) 1 else 0} ${e.timeMs}"
        is InputEvent.MouseMove -> "EV M ${e.dx} ${e.dy} ${e.timeMs}"
        is InputEvent.Button -> "EV B ${e.code} ${if (e.pressed) 1 else 0} ${e.timeMs}"
        is InputEvent.Wheel -> "EV W ${e.vertical} ${e.horizontal} ${e.timeMs}"
        is InputEvent.Device -> "EV DEV ${if (e.added) "add" else "remove"} ${e.info.id} ${e.info.name}"
    }

    /**
     * Parse dòng "EV K/B <code> <1|0> <time>" thành mã phím/nút khi NHẤN
     * (pressed=1). Dùng cho gán phím trong editor GĐ5. Dòng khác -> null.
     */
    fun parseKeyPress(line: String): Int? {
        val parts = line.split(' ')
        if (parts.size < 4 || parts[0] != "EV" || (parts[1] != "K" && parts[1] != "B")) return null
        if (parts[3] != "1") return null // chỉ lấy lúc nhấn, bỏ lúc nhả
        return parts[2].toIntOrNull()
    }

    /** 1 thiết bị trong phản hồi DEVICES. Tên để cuối vì có thể chứa khoảng trắng. */
    fun formatDevice(info: InputDeviceInfo): String =
        "DEV ${info.id} ${info.kind.name.lowercase()} ${info.node} ${info.name}"

    /** Parse dòng "DEV <id> <kind> <node> <tên...>". Sai định dạng -> null. */
    fun parseDeviceLine(line: String): InputDeviceInfo? {
        val parts = line.split(" ", limit = 5)
        if (parts.size < 5 || parts[0] != "DEV") return null
        val kind = when (parts[2]) {
            "keyboard" -> DeviceKind.KEYBOARD
            "mouse" -> DeviceKind.MOUSE
            else -> return null
        }
        return InputDeviceInfo(parts[1], parts[4], parts[3], kind)
    }

    /** Parse "GRAB <mouse|keyboard|all> <0|1>" -> Pair(target, on). Sai cú pháp -> null. */
    fun parseGrab(line: String): Pair<String, Boolean>? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size != 3 || parts[0] != "GRAB") return null
        if (parts[1] != "mouse" && parts[1] != "keyboard" && parts[1] != "all") return null
        val on = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return parts[1] to on
    }
}

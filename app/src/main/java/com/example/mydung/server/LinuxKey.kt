package com.example.mydung.server

/**
 * Mã phím/nút Linux (linux/input-event-codes.h). Dùng chung cho GĐ3/GĐ4/GĐ5.
 * Chỉ liệt kê các mã hay dùng; mã khác vẫn xử lý bình thường, chỉ không có tên đẹp.
 */
object LinuxKey {
    const val ESC = 1
    const val KEY_1 = 2
    const val KEY_2 = 3
    const val KEY_3 = 4
    const val KEY_4 = 5
    const val KEY_5 = 6
    const val KEY_6 = 7
    const val KEY_7 = 8
    const val KEY_8 = 9
    const val KEY_9 = 10
    const val KEY_0 = 11
    const val BACKSPACE = 14
    const val TAB = 15
    const val Q = 16
    const val W = 17
    const val E = 18
    const val R = 19
    const val T = 20
    const val Y = 21
    const val U = 22
    const val I = 23
    const val O = 24
    const val P = 25
    const val ENTER = 28
    const val LEFTCTRL = 29
    const val A = 30
    const val S = 31
    const val D = 32
    const val F = 33
    const val G = 34
    const val H = 35
    const val J = 36
    const val K = 37
    const val L = 38
    const val LEFTSHIFT = 42
    const val Z = 44
    const val X = 45
    const val C = 46
    const val V = 47
    const val B = 48
    const val N = 49
    const val M = 50
    const val RIGHTSHIFT = 54
    const val LEFTALT = 56
    const val SPACE = 57
    const val CAPSLOCK = 58
    const val F1 = 59
    const val F2 = 60
    const val F3 = 61
    const val F4 = 62
    const val F5 = 63
    const val F6 = 64
    const val F7 = 65
    const val F8 = 66
    const val F9 = 67
    const val F10 = 68
    const val F11 = 87
    const val F12 = 88
    const val RIGHTCTRL = 97
    const val RIGHTALT = 100
    const val UP = 103
    const val LEFT = 105
    const val RIGHT = 106
    const val DOWN = 108
    const val DELETE = 111

    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    /** Tên hiển thị ngắn cho mã phím/nút. Mã lạ trả về "K<code>". */
    fun name(code: Int): String = when (code) {
        ESC -> "Esc"
        BACKSPACE -> "Backspace"
        TAB -> "Tab"
        ENTER -> "Enter"
        LEFTCTRL, RIGHTCTRL -> "Ctrl"
        LEFTSHIFT, RIGHTSHIFT -> "Shift"
        LEFTALT, RIGHTALT -> "Alt"
        SPACE -> "Space"
        CAPSLOCK -> "CapsLock"
        UP -> "↑"
        DOWN -> "↓"
        LEFT -> "←"
        RIGHT -> "→"
        DELETE -> "Del"
        BTN_LEFT -> "Chuột trái"
        BTN_RIGHT -> "Chuột phải"
        BTN_MIDDLE -> "Chuột giữa"
        BTN_SIDE -> "Nút hông"
        BTN_EXTRA -> "Nút phụ"
        in 2..11 -> "${(code - 1) % 10}"
        in 59..68 -> "F${code - 58}"
        F11 -> "F11"
        F12 -> "F12"
        Q -> "Q"; W -> "W"; E -> "E"; R -> "R"; T -> "T"; Y -> "Y"; U -> "U"; I -> "I"; O -> "O"; P -> "P"
        A -> "A"; S -> "S"; D -> "D"; F -> "F"; G -> "G"; H -> "H"; J -> "J"; K -> "K"; L -> "L"
        Z -> "Z"; X -> "X"; C -> "C"; V -> "V"; B -> "B"; N -> "N"; M -> "M"
        else -> "K$code"
    }
}

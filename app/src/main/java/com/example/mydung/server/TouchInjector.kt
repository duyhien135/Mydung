package com.example.mydung.server

/** Cách tiêm cảm ứng. Mặc định là REFLECT (giữ nguyên hành vi GĐ4). */
enum class InjectorMode(val key: String) {
    REFLECT("reflect"),
    EVDEV("evdev");

    companion object {
        fun fromKey(key: String): InjectorMode? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Giao diện chung cho các cách tiêm cảm ứng (GĐ6).
 * - Injector (GĐ4): qua InputManager.injectInputEvent bằng reflection.
 * - RawEvdevInjector (GĐ6): ghi event thô thẳng vào node cảm ứng
 *   /dev/input/eventX (học theo GG Mouse Pro).
 *
 * TouchEngine (logic ngón) không đổi; chỉ lớp Injector thay được.
 */
interface TouchInjector {
    /** Tên chế độ: "reflect" hoặc "evdev" (hiện trong MAPSTATUS). */
    val name: String

    /** Tiêm có khả dụng không (dò lúc probe, mất khi ghi lỗi). */
    val available: Boolean

    /** Số lần tiêm lỗi (xem trong MAPSTATUS). */
    val errorCount: Long

    /** Dò khả năng tiêm (an toàn khi gọi nhiều lần). Trả về available. */
    fun probe(): Boolean

    /** Tiêm 1 đặc tả MotionEvent. Trả false nếu không tiêm được. */
    fun inject(spec: TouchEngine.MotionEventSpec, displayId: Int): Boolean

    /** Giải phóng tài nguyên khi không dùng nữa (mặc định: không làm gì). */
    fun close() {}
}

package com.example.mydung.server

/**
 * Nhận diện double-tap (build-11): 2 lần chạm cùng 1 phần tử trong
 * [Config.DOUBLE_TAP_TIMEOUT_MS] thì tính là double-tap. Tự đếm thời
 * gian (dùng uptimeMillis do caller truyền) thay vì dựa vào API
 * gesture của Compose — API đó không chắc chắn trong BOM này, và logic
 * thuần JVM này test được trực tiếp.
 *
 * Dùng trong trình sửa: chạm 1 lần chỉ chọn/kéo phần tử; double-tap mới
 * mở bảng chỉnh (nút) hoặc bật/tắt chuột (camera).
 */
class DoubleTapDetector(
    private val thresholdMs: Long = Config.DOUBLE_TAP_TIMEOUT_MS,
) {
    private var lastIndex: Int? = null
    private var lastTimeMs: Long = 0L

    /**
     * Ghi nhận 1 lần chạm vào phần tử [index] lúc [nowMs].
     * Trả true nếu đây là lần chạm thứ 2 vào CÙNG phần tử trong ngưỡng
     * (double-tap); ngược lại trả false. Lần chạm này luôn trở thành mốc
     * cho lần sau (tap thứ 3 nhanh sau đó cũng tính là double-tap mới).
     */
    fun tap(index: Int, nowMs: Long): Boolean {
        val delta = nowMs - lastTimeMs
        val isDouble = lastIndex == index && delta in 1..thresholdMs
        lastIndex = index
        lastTimeMs = nowMs
        return isDouble
    }

    /** Quên mốc lần chạm trước (vd khi rời trình sửa). */
    fun reset() {
        lastIndex = null
        lastTimeMs = 0L
    }
}

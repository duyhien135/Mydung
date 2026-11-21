package com.example.mydung.server

import kotlin.math.roundToInt

/**
 * Mọi hằng số chỉnh được của dự án gom ở đây (GĐ3; GĐ4/GĐ5 thêm vào file này).
 * Không rải số cứng trong code.
 */
object Config {
    // GĐ2 (giữ nguyên giá trị cũ)
    const val LOG_FILE_PATH = "/data/local/tmp/mydung_server.log"
    const val MAX_LOG_SIZE_BYTES = 200 * 1024L
    const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 phút không có lệnh -> Server tự thoát
    const val APK_CHECK_INTERVAL_MS = 15 * 1000L

    // GĐ2 sửa ở GĐ3: heartbeat PING của app. Phải nhanh hơn ngưỡng nhả GRAB
    // 10s (mất 2 nhịp liên tiếp mới nhả).
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    // GĐ3: quét thiết bị /dev/input
    const val DEVICE_SCAN_INTERVAL_MS = 2_000L

    // GĐ3: nhả GRAB khi không có heartbeat quá ngưỡng.
    // Heartbeat của app là 5s nên ngưỡng 10s = mất 2 nhịp liên tiếp.
    const val GRAB_HEARTBEAT_TIMEOUT_MS = 10_000L

    // GĐ3: phím thoát khẩn cấp (giữ Ctrl trái + Alt trái + Esc)
    const val EMERGENCY_HOLD_MS = 1_000L
    const val EMERGENCY_CHECK_INTERVAL_MS = 200L

    // GĐ3: hàng đợi đẩy sự kiện về app (mỗi kết nối SUB_INPUT)
    const val EVENT_QUEUE_CAPACITY = 256
    const val KEY_EVENT_OFFER_TIMEOUT_MS = 200L

    // GĐ3: UI màn "Kiểm tra thiết bị nhập"
    const val UI_FLUSH_INTERVAL_MS = 33L // ~30 lần vẽ/giây
    const val UI_MAX_LOG_LINES = 200

    // ioctl EVIOCGRAB = _IOW('E', 0x90, int)
    const val EVIOCGRAB = 0x40044590

    // GĐ4: tiêm cảm ứng đa điểm
    const val MOVE_MIN_INTERVAL_MS = 8L // gộp MOVE: tối đa ~120Hz
    const val CAMERA_IDLE_RELEASE_MS = 150L // chuột đứng yên quá ngưỡng thì nhả ngón camera
    const val LATENCY_LOG_INTERVAL_MS = 5_000L
    const val TOUCH_QUEUE_CAPACITY = 512
    const val TOUCH_WORKER_POLL_MS = 20L
    const val INJECT_DEVICE_ID = 0 // CHƯA CHẮC: thử 0 trước (xem báo cáo GĐ4)
    const val TEST_TAP_HOLD_MS = 80L
    const val TEST_TWO_HOLD_MS = 600L
    const val TEST_SWIPE_STEPS = 10

    // GĐ4: bố cục mặc định cho game bắn súng kiểu Free Fire.
    // Tọa độ theo TỈ LỆ màn hình ngang (0.0..1.0); bán kính joystick theo chiều rộng.
    const val JOY_CENTER_X = 0.12f
    const val JOY_CENTER_Y = 0.68f
    const val JOY_RADIUS = 0.055f
    const val CAM_CENTER_X = 0.58f
    const val CAM_CENTER_Y = 0.50f
    // Camera (FPS Look) là VÙNG TRÒN (build-9, trước là chữ nhật): CAM_HALF_W
    // là BÁN KÍNH theo tỉ lệ chiều rộng (~70dp trên máy 2560px). CAM_HALF_H
    // giữ lại cho tương thích TOUCHCFG/profile cũ — logic tròn chỉ dùng bán kính.
    const val CAM_HALF_W = 0.055f
    const val CAM_HALF_H = 0.32f
    const val CAM_RADIUS_MIN = 0.02f
    const val CAM_RADIUS_MAX = 0.12f
    // Độ nhạy engine (hệ số nhân delta chuột, dùng trong TouchLayout /
    // TouchMapper): 1.0 = mặc định. UI hiển thị thang 1..100 (xem
    // SENS_UI_* bên dưới) rồi đổi sang hệ số bằng sensFactor().
    const val SENS_X = 1.0f
    const val SENS_Y = 1.0f
    const val MAP_TOGGLE_KEY = LinuxKey.F8
    // Phím chuyển nhanh chế độ con trỏ <-> nhìn tự do (mặc định F9).
    // Chế độ con trỏ: chuột di chuyển 1 con trỏ ảo, chuột trái bấm tại
    // vị trí con trỏ (để bấm giao diện game); nhìn tự do: chuột kéo camera.
    const val CURSOR_TOGGLE_KEY = LinuxKey.F9
    // Build-11: phím ẩn/hiện lớp phủ mặc định là F1 (gần tay trái khu
    // WASD hơn F10 cũ — người dùng chê F10 xa quá). Gán lại được trong
    // bảng Cài đặt của nút K. Profile cũ đã lưu overlayKey thì giữ nguyên
    // giá trị đã lưu (kể cả F10); chỉ profile chưa từng gán mới dùng F1.
    const val OVERLAY_TOGGLE_KEY = LinuxKey.F1
    // Build-11: phím bật/tắt chuột (mặc định CHƯA GÁN = 0; mã phím Linux
    // thật luôn > 0 nên 0 là sentinel an toàn). Gán trong bảng chỉnh camera.
    const val MOUSE_TOGGLE_KEY_UNBOUND = 0

    // Camera: khi chuột ra khỏi vùng thì tự nhấc rồi chạm lại ở rìa ĐỐI
    // DIỆN (không phải tâm) để mỗi lần vuốt đi được hết chiều rộng vùng.
    // Chạm lại cách rìa một khoảng = tỉ lệ này của nửa chiều vùng.
    const val CAMERA_EDGE_INSET_RATIO = 0.05f

    // Camera: UP và DOWN lại ở rìa cách nhau ít nhất 1 vòng worker
    // (không cùng khung) — DOWN lại ngay trong cùng vòng với UP có thể
    // làm game nhận nhầm thành 1 lần chạm. TouchMapper hẹn DOWN lại sau
    // khoảng này, tick() sẽ thực hiện.
    const val CAMERA_REPRESS_DELAY_MS = 20L

    // Khởi động Server (chẩn đoán theo giai đoạn): thăm dò mỗi 250ms,
    // kiểm tra tiến trình mỗi 2s, tối đa 30s.
    const val SERVER_POLL_MS = 250L
    const val SERVER_PING_TIMEOUT_MS = 500
    const val SERVER_START_TIMEOUT_MS = 30_000L
    const val SERVER_PROCESS_CHECK_MS = 2_000L
    const val SERVER_LOG_TAIL_LINES = 25

    /** Bảng nút mặc định: mã Linux -> điểm chạm (tỉ lệ). */
    val DEFAULT_BUTTONS: Map<Int, Pair<Float, Float>> = mapOf(
        LinuxKey.BTN_LEFT to (0.88f to 0.68f), // bắn
        LinuxKey.BTN_RIGHT to (0.79f to 0.42f), // ngắm (thêm ngoài spec GĐ4)
        LinuxKey.SPACE to (0.79f to 0.52f), // nhảy
        LinuxKey.C to (0.72f to 0.78f), // ngồi
        LinuxKey.R to (0.79f to 0.30f), // nạp đạn
        LinuxKey.LEFTSHIFT to (0.68f to 0.90f), // chạy
        LinuxKey.KEY_1 to (0.86f to 0.16f), // vũ khí 1
        LinuxKey.KEY_2 to (0.91f to 0.16f), // vũ khí 2
        LinuxKey.KEY_3 to (0.96f to 0.16f), // vũ khí 3
    )

    // GĐ5: profile + lớp phủ
    const val PROFILE_FILE_NAME = "profiles.json"
    const val DEFAULT_BUTTON_SIZE = 0.03f // bán kính hiển thị mặc định của nút (tỉ lệ chiều rộng)
    const val OVERLAY_NOTIFICATION_ID = 2001

    // Foreground service giữ heartbeat sống khi app ở nền (HyperOS giết app
    // nền mạnh; mất heartbeat 10s thì MAP tự tắt giữa trận).
    const val KEEPALIVE_NOTIFICATION_ID = 2002

    /**
     * Che token (32 ký tự hex) trong mọi chuỗi ghi log. Log Server nằm ở
     * /data/local/tmp — app khác đọc được sẽ lấy token tiêm chạm qua
     * loopback. Gọi ở cả 2 sink log (Server.log + PairingState.log) và
     * trong thông điệp ngoại lệ chứa lệnh shell.
     */
    private val TOKEN_REGEX = Regex("[0-9a-f]{32}")

    fun redactSecrets(text: String): String = TOKEN_REGEX.replace(text, "***")

    // GĐ6: theo dõi app foreground (tự đổi profile theo game)
    const val FG_POLL_MS = 1_000L // quét app foreground mỗi 1 giây
    const val FG_QUEUE_CAPACITY = 16 // hàng đợi đẩy FG về app (sự kiện hiếm)

    // Build-11: tần số quét chuột (polling rate) — TỐC ĐỘ XỬ LÝ NỘI BỘ của
    // app (chu kỳ thăm dò vị trí con trỏ + gộp MOVE tiêm), KHÔNG phải
    // polling rate phần cứng của chuột USB (app không đổi được cái đó).
    // 60Hz→16ms, 125Hz→8ms, 250Hz→4ms, 500Hz→2ms, 1000Hz→1ms,
    // NO LIMIT (0)→0ms: không gộp MOVE, tiêm ngay mỗi sự kiện.
    // Mặc định 500Hz. Chỉnh trong bảng chỉnh camera (trình sửa).
    val POLL_RATES = listOf(60, 125, 250, 500, 1000)
    const val POLL_RATE_NO_LIMIT = 0
    const val DEFAULT_POLL_RATE_HZ = 500

    /** Chu kỳ xử lý (ms) theo tần số quét: NO LIMIT -> 0 (không gộp); còn
     *  lại kẹp 1..20ms (60Hz -> 16ms). */
    fun pollIntervalMs(hz: Int): Long =
        if (hz <= 0) 0L else (1000L / hz.coerceIn(POLL_RATES.first(), POLL_RATES.last())).coerceIn(1L, 20L)

    /** Độ trễ thăm dò MAPSTATUS ở app (ms): như pollIntervalMs nhưng tối
     *  thiểu 1ms để vòng poll không quay chặt khi NO LIMIT. */
    fun pollDelayMs(hz: Int): Long = pollIntervalMs(hz).coerceAtLeast(1L)

    // Build-11: thang độ nhạy / đầm tâm 1..100 (trước: nhạy 0.1..5.0,
    // đầm 1..10 — người dùng chê thang cũ nhỏ quá).
    // Độ nhạy: hệ số engine = giá trị UI / 20 (tuyến tính):
    //   1 -> 0.05 (rất chậm), 20 -> 1.0 (mặc định cũ), 100 -> 5.0 (nhanh nhất).
    // Đầm tâm (Kháng nhạy): mức đầm engine = 1 + (giá trị UI - 1) / 11
    //   (1 -> 1.0, 100 -> 10.0); EMA alpha = 1 / mức đầm:
    //   1 -> alpha 1.0 (không làm mượt = "Tắt"), 100 -> alpha 0.1 (đầm nhất).
    const val SENS_UI_MIN = 1
    const val SENS_UI_MAX = 100
    const val SENS_UI_DEFAULT = 20
    const val DAMP_MIN = 1
    const val DAMP_MAX = 100
    const val DAMP_DEFAULT = 1

    /** Đổi giá trị độ nhạy UI (1..100) sang hệ số engine (0.05..5.0). */
    fun sensFactor(uiValue: Int): Float =
        uiValue.coerceIn(SENS_UI_MIN, SENS_UI_MAX) / 20f

    /** Đổi hệ số engine cũ (0.1..5.0) sang thang UI 1..100 (migrate profile v1). */
    fun sensUiValue(factor: Float): Int =
        (factor * 20f).roundToInt().coerceIn(SENS_UI_MIN, SENS_UI_MAX)

    /** Đổi giá trị đầm tâm UI (1..100) sang mức đầm engine (1.0..10.0). */
    fun dampLevel(uiValue: Int): Float =
        1f + (uiValue.coerceIn(DAMP_MIN, DAMP_MAX) - 1) / 11f

    /** Hệ số EMA alpha theo giá trị đầm tâm UI (1..100): 1 -> 1.0, 100 -> 0.1. */
    fun dampAlpha(uiValue: Int): Float = 1f / dampLevel(uiValue)

    /** Đổi mức đầm cũ (1..10) sang thang UI 1..100 (migrate profile v1):
     *  1 -> 1, 10 -> 100, tuyến tính ở giữa (mỗi mức cũ = 11 mức mới). */
    fun dampUiValue(oldLevel: Int): Int =
        (1 + (oldLevel.coerceIn(1, 10) - 1) * 11).coerceIn(DAMP_MIN, DAMP_MAX)

    // Build-11: giới hạn vùng di chuyển của con trỏ ảo (bảng chỉnh camera,
    // như K7XD). TẮT = không giới hạn (toàn màn hình); TOÀN MÀN = cả màn
    // hình (hiệu ứng giống TẮT, giữ lại cho đủ lựa chọn như K7XD); MÀN
    // PHẢI = chỉ nửa phải màn hình (x >= 50% chiều rộng).
    const val CURSOR_REGION_OFF = 0
    const val CURSOR_REGION_FULL = 1
    const val CURSOR_REGION_RIGHT = 2
    const val CURSOR_REGION_DEFAULT = CURSOR_REGION_OFF

    // Build-11: double-tap mở bảng chỉnh nút — 2 lần chạm cùng 1 phần tử
    // trong ngưỡng này thì tính là double-tap (xem DoubleTapDetector).
    const val DOUBLE_TAP_TIMEOUT_MS = 300L

    // Build-9: Tự động Tap / Thả Tap / Macro.
    const val AUTO_TAP_HOLD_MS = 40L // mỗi lần tap giữ ngón 40ms rồi nhả
    const val AUTO_TAP_INTERVAL_DEFAULT_MS = 100L
    const val AUTO_TAP_INTERVAL_MIN_MS = 50L
    const val AUTO_TAP_INTERVAL_MAX_MS = 2000L
    const val MACRO_TAP_HOLD_MS = 40L
    const val MACRO_WAIT_MAX_MS = 10_000L

    // Build-9: tốc độ con trỏ ảo (chế độ con trỏ) — hệ số nhân delta chuột
    // REL trước khi cập nhật vị trí con trỏ. KHÁC độ nhạy X/Y (tốc độ xoay
    // camera ở chế độ nhìn tự do). Lưu SharedPreferences, không nằm trong profile.
    const val CURSOR_SPEED_MIN = 0.5f
    const val CURSOR_SPEED_MAX = 3.0f
    const val CURSOR_SPEED_DEFAULT = 1.0f
}

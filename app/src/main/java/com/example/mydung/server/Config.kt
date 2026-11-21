package com.example.mydung.server

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
    const val CAM_HALF_W = 0.26f
    const val CAM_HALF_H = 0.32f
    const val SENS_X = 1.0f
    const val SENS_Y = 1.0f
    const val MAP_TOGGLE_KEY = LinuxKey.F8
    // Phím chuyển nhanh chế độ con trỏ <-> nhìn tự do (mặc định F9).
    // Chế độ con trỏ: chuột di chuyển 1 con trỏ ảo, chuột trái bấm tại
    // vị trí con trỏ (để bấm giao diện game); nhìn tự do: chuột kéo camera.
    const val CURSOR_TOGGLE_KEY = LinuxKey.F9

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
}

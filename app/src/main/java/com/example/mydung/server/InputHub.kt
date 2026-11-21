package com.example.mydung.server

/**
 * Trung tâm điều phối GĐ3 trong Server: quét thiết bị, giữ trạng thái GRAB,
 * phím thoát khẩn cấp, nhả GRAB khi mất heartbeat/kết nối app.
 */
object InputHub {
    private val EMERGENCY_KEYS = setOf(LinuxKey.LEFTCTRL, LinuxKey.LEFTALT, LinuxKey.ESC)

    private val scanner = DeviceScanner(
        onEvent = { InputBus.publish(it) },
        onDeviceAdded = { info -> maybeGrabNewDevice(info) },
    )

    private val grabbedIds = mutableSetOf<String>()

    @Volatile
    var grabMouse = false
        private set

    @Volatile
    var grabKeyboard = false
        private set

    @Volatile
    private var lastAppActivityMs = System.currentTimeMillis()

    @Volatile
    private var started = false
    private var watchdogThread: Thread? = null

    // Phím đang đè (toàn cục, để nhận phím thoát khẩn cấp).
    private val pressedKeys = mutableSetOf<Int>()
    private var comboStartMs = 0L

    private val comboListener = InputBus.Listener { e ->
        if (e is InputEvent.Key) {
            synchronized(pressedKeys) {
                if (e.pressed) pressedKeys.add(e.code) else pressedKeys.remove(e.code)
                if (EMERGENCY_KEYS.all { it in pressedKeys }) {
                    if (comboStartMs == 0L) comboStartMs = System.currentTimeMillis()
                } else {
                    comboStartMs = 0L
                }
            }
        }
        if (e is InputEvent.Device && !e.added) {
            synchronized(grabbedIds) { grabbedIds.remove(e.info.id) }
        }
    }

    // Ghi log sự kiện phím/nút/cắm-rút vào log Server để test được cả khi app
    // ở nền (điều kiện xong số 4). Không ghi chuột di chuyển (quá nhiều, đầy log).
    private val logListener = InputBus.Listener { e ->
        when (e) {
            is InputEvent.Key -> Server.log("EV phím ${LinuxKey.name(e.code)} ${if (e.pressed) "nhấn" else "nhả"}")
            is InputEvent.Button -> Server.log("EV nút ${LinuxKey.name(e.code)} ${if (e.pressed) "nhấn" else "nhả"}")
            is InputEvent.Wheel -> Server.log("EV lăn chuột dọc=${e.vertical} ngang=${e.horizontal}")
            is InputEvent.Device -> Server.log("EV thiết bị ${if (e.added) "cắm vào" else "rút ra"}: ${e.info.name}")
            is InputEvent.MouseMove -> { /* quá nhiều, không ghi */ }
        }
    }

    fun start() {
        if (started) return
        started = true
        Grabber.resolve()
        InputBus.subscribe(comboListener)
        InputBus.subscribe(logListener)
        scanner.start()
        watchdogThread = Thread({ watchdogLoop() }, "InputHubWatchdog").apply { isDaemon = true; start() }
        Server.log("InputHub đã khởi động (quét thiết bị mỗi ${Config.DEVICE_SCAN_INTERVAL_MS}ms).")
    }

    fun stop() {
        if (!started) return
        started = false
        watchdogThread?.interrupt()
        releaseAllGrabs("InputHub dừng")
        scanner.stop()
        InputBus.unsubscribe(comboListener)
        InputBus.unsubscribe(logListener)
    }

    fun devices(): List<InputDeviceInfo> = scanner.devices()

    /** Server gọi mỗi khi nhận lệnh đã xác thực từ app (kể cả PING heartbeat). */
    fun noteAppActivity() {
        lastAppActivityMs = System.currentTimeMillis()
    }

    /**
     * Socket SUB_INPUT đóng: nhả GRAB ngay, không chờ heartbeat — TRỪ khi
     * MAP đang bật (keymap chạy độc lập trong Server; đóng màn thử phím
     * hay trình sửa không được làm mất GRAB lúc đang chơi).
     */
    fun onAppDisconnected() {
        if (TouchHub.mapOn) {
            Server.log("SUB_INPUT đóng nhưng MAP đang bật: giữ nguyên GRAB.")
            return
        }
        releaseAllGrabs("mất kết nối app")
    }

    /**
     * Bật/tắt GRAB. Trả về dòng trạng thái gửi lại cho app.
     * Thiết bị cắm thêm sau đó sẽ tự được chiếm nếu đúng loại đang bật.
     */
    fun setGrab(target: String, on: Boolean): String {
        when (target) {
            "mouse" -> grabMouse = on
            "keyboard" -> grabKeyboard = on
            "all" -> {
                grabMouse = on
                grabKeyboard = on
            }
        }
        var grabbed = 0
        var failed = 0
        for (info in scanner.devices()) {
            val want = when (info.kind) {
                DeviceKind.MOUSE -> grabMouse
                DeviceKind.KEYBOARD -> grabKeyboard
            }
            if (want) {
                if (grabDevice(info)) grabbed++ else failed++
            } else {
                ungrabDevice(info)
            }
        }
        val method = Grabber.strategyName
        Server.log("GRAB $target=$on: chiếm được $grabbed, lỗi $failed (cách $method).")
        return "OK GRAB mouse=${if (grabMouse) 1 else 0} keyboard=${if (grabKeyboard) 1 else 0} " +
            "method=$method grabbed=$grabbed failed=$failed"
    }

    private fun maybeGrabNewDevice(info: InputDeviceInfo) {
        val want = when (info.kind) {
            DeviceKind.MOUSE -> grabMouse
            DeviceKind.KEYBOARD -> grabKeyboard
        }
        if (want) grabDevice(info)
    }

    private fun grabDevice(info: InputDeviceInfo): Boolean {
        val fd = scanner.readerFor(info.id)?.fd ?: return false
        if (Grabber.grab(fd)) {
            synchronized(grabbedIds) { grabbedIds.add(info.id) }
            return true
        }
        return false
    }

    private fun ungrabDevice(info: InputDeviceInfo) {
        // Chỉ mở lại reader khi thiết bị thực sự đang bị grab — nếu không
        // chỉ tốn 1 lần đóng/mở luồng đọc vô ích.
        val wasGrabbed = synchronized(grabbedIds) { grabbedIds.remove(info.id) }
        if (!wasGrabbed) return
        // Nhả GRAB = đóng fd (kernel tự nhả khi fd đóng) rồi mở lại reader
        // để vẫn đọc được event — không nhả được bằng ioctl (EVIOCGRAB
        // chỉ nhận con trỏ, NULL = nhả mà ioctlInt không truyền được NULL).
        // Xem Grabber.
        scanner.reopenReader(info.id)
    }

    /** Nhả mọi GRAB và tắt chế độ map (map ở GĐ4; GĐ3 chỉ nhả GRAB). */
    fun releaseAllGrabs(reason: String) {
        if (!grabMouse && !grabKeyboard && grabbedIds.isEmpty()) return
        for (info in scanner.devices()) ungrabDevice(info)
        synchronized(grabbedIds) { grabbedIds.clear() }
        grabMouse = false
        grabKeyboard = false
        Server.log("Đã nhả mọi GRAB ($reason).")
    }

    private fun watchdogLoop() {
        while (started) {
            try {
                Thread.sleep(Config.EMERGENCY_CHECK_INTERVAL_MS)
                // Phím thoát khẩn cấp: giữ đủ lâu thì nhả GRAB, xử lý ngay trong Server.
                val comboHeld = synchronized(pressedKeys) {
                    comboStartMs != 0L &&
                        System.currentTimeMillis() - comboStartMs >= Config.EMERGENCY_HOLD_MS
                }
                if (comboHeld) {
                    synchronized(pressedKeys) { comboStartMs = 0L }
                    releaseAllGrabs("phím thoát khẩn cấp Ctrl+Alt+Esc")
                    // GĐ4: phím thoát khẩn cấp hủy mọi cảm ứng VÀ tắt chế độ MAP.
                    TouchHub.forceMapOff("phím thoát khẩn cấp Ctrl+Alt+Esc")
                }
                // Mất heartbeat quá ngưỡng thì nhả GRAB.
                if ((grabMouse || grabKeyboard) &&
                    System.currentTimeMillis() - lastAppActivityMs > Config.GRAB_HEARTBEAT_TIMEOUT_MS
                ) {
                    releaseAllGrabs("không có heartbeat quá ${Config.GRAB_HEARTBEAT_TIMEOUT_MS / 1000}s")
                }
                // GĐ4: mất heartbeat quá ngưỡng thì hủy cảm ứng và tắt MAP
                // (dù GRAB có bật hay không).
                if (TouchHub.mapOn &&
                    System.currentTimeMillis() - lastAppActivityMs > Config.GRAB_HEARTBEAT_TIMEOUT_MS
                ) {
                    TouchHub.forceMapOff("không có heartbeat quá ${Config.GRAB_HEARTBEAT_TIMEOUT_MS / 1000}s")
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Server.log("InputHub watchdog lỗi: ${e.message}")
            }
        }
    }
}

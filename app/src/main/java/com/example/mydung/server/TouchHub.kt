package com.example.mydung.server

import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Trung tâm điều phối GĐ4 trong Server: nhận sự kiện từ InputBus, ánh xạ
 * thành cảm ứng đa điểm và tiêm ngay trong Server (không vòng qua app).
 *
 * Luồng: listener InputBus chỉ đẩy vào hàng đợi; 1 luồng worker xử lý ánh
 * xạ + tiêm. Mọi truy cập engine/mapper đi qua 1 khóa duy nhất, và TIÊM
 * LUÔN NẰM TRONG KHÓA (cùng lần khóa với lúc tạo đặc tả): không có lần
 * hủy (CANCEL) nào chen vào giữa lúc tạo DOWN và lúc tiêm, nên không thể
 * có DOWN tiêm sau CANCEL rồi kẹt ngón.
 *
 * Bật MAP thì tự chiếm (GRAB) chuột + bàn phím; tắt MAP thì nhả GRAB.
 *
 * An toàn: tắt MAP / phím thoát khẩn cấp (InputHub) / mất heartbeat quá
 * 10s / Server dừng đều hủy toàn bộ ngón bằng ACTION_CANCEL, không để kẹt.
 * LƯU Ý: đóng kết nối SUB_INPUT (xem log GĐ3) KHÔNG hủy cảm ứng — MAP chạy
 * độc lập trong Server, chỉ mất heartbeat mới tính là "mất kết nối app".
 */
object TouchHub {
    private sealed class Work {
        class Event(val e: InputEvent, val arrivalMs: Long) : Work()
        class Test(val t: TouchProtocol.TestTouch) : Work()
    }

    /** Ngón dùng cho TESTTOUCH (nằm trong dải id nút 2..9, kiểm tra bận trước). */
    private const val TEST_ID_1 = 8
    private const val TEST_ID_2 = 9

    private val lock = Any()
    private val engine = TouchPointerSet()
    private val mapper = TouchMapper(engine, TouchLayout.default(), 0, 0)
    private val queue = LinkedBlockingQueue<Work>(Config.TOUCH_QUEUE_CAPACITY)

    @Volatile
    var mapOn = false
        private set

    /**
     * Chế độ con trỏ (true) hay nhìn tự do bằng camera (false). MẶC ĐỊNH
     * LÀ CON TRỎ (true) — giống GG Mouse Pro: vào game là có con trỏ bấm
     * được ngay cả ở sảnh; F9 hoặc menu nút K đổi sang nhìn tự do. Cờ này
     * là nguồn thật cho MAPSTATUS (mode=cursor/camera) và cho phím F9
     * (setCursorMode(!cursorMode)); phải giữ đồng bộ với
     * TouchMapper.cursorMode (đổi mặc định thì đổi cả 2, không thì lần bấm
     * F9 đầu tiên không có tác dụng).
     */
    @Volatile
    var cursorMode = true
        private set

    @Volatile
    var display: TouchProtocol.DisplayInfo? = null
        private set

    /**
     * Lớp phủ có đang bị ẩn thủ công không (build-9): phím ẩn/hiện lớp phủ
     * (mặc định F1 từ build-11) đổi cờ này, app đọc qua MAPSTATUS
     * (overlay=1|0) rồi tự ẩn/hiện các cửa sổ. Keymap vẫn chạy ngầm
     * bình thường khi ẩn.
     */
    @Volatile
    var overlayHidden = false
        private set

    /** Chu kỳ gộp MOVE tiêm (ms) = 1000 / tần số quét chuột (build-9, mặc định 500Hz -> 2ms). */
    @Volatile
    private var moveIntervalMs = Config.pollIntervalMs(Config.DEFAULT_POLL_RATE_HZ)

    /** Cách tiêm đang dùng (GĐ6). Mặc định REFLECT, giữ nguyên hành vi GĐ4. */
    @Volatile
    var injectorMode: InjectorMode = InjectorMode.REFLECT
        private set

    /** Giá trị block_untrusted_touches trước khi MAP tắt nó (null = chưa tắt). */
    private var untrustedTouchesPrev: String? = null

    @Volatile
    private var started = false
    private var worker: Thread? = null

    private var lastMoveInjectMs = 0L
    private var latencySumMs = 0L
    private var latencyCount = 0L
    private var lastLatencyLogMs = 0L
    private var dropCount = 0L

    private val busListener = InputBus.Listener { e ->
        // Phím nóng bật/tắt MAP (mặc định F8), xử lý ngay, không đưa vào hàng đợi.
        if (e is InputEvent.Key && e.pressed && e.code == mapper.layout.mapToggleKey) {
            val res = setMap(!mapOn)
            if (res.startsWith("ERR")) Server.log("MAP: bấm ${LinuxKey.name(e.code)} nhưng $res")
            return@Listener
        }
        // Phím nóng chuyển chế độ con trỏ <-> nhìn tự do (mặc định F9).
        // Kiểm tra sau phím MAP: nếu người dùng gán 2 phím trùng nhau thì
        // chỉ phím MAP có tác dụng (UI cảnh báo trường hợp này).
        if (e is InputEvent.Key && e.pressed && e.code == mapper.layout.cursorToggleKey) {
            val res = setCursorMode(!cursorMode)
            if (res.startsWith("ERR")) Server.log("CURSOR: bấm ${LinuxKey.name(e.code)} nhưng $res")
            return@Listener
        }
        // Build-9: phím nóng ẩn/hiện lớp phủ (mặc định F1 từ build-11).
        // Đổi cờ, app đọc qua MAPSTATUS (overlay=1|0) rồi tự ẩn/hiện các
        // cửa sổ. Kiểm tra sau F8/F9: trùng phím thì phím ưu tiên cao hơn thắng.
        if (e is InputEvent.Key && e.pressed && e.code == mapper.layout.overlayToggleKey) {
            setOverlayHidden(!overlayHidden)
            return@Listener
        }
        // Build-11: phím bật/tắt chuột (mặc định chưa gán = 0). Tắt thì
        // chuột không di chuyển con trỏ / không xoay camera nữa (phím/nút
        // vẫn tiêm bình thường); bật lại thì dùng như cũ. Kiểm tra sau
        // phím lớp phủ: trùng phím thì phím lớp phủ thắng.
        val mouseKey = mapper.layout.mouseToggleKey
        if (e is InputEvent.Key && e.pressed && mouseKey != 0 && e.code == mouseKey) {
            setMouseEnabled(!mapper.mouseEnabled)
            return@Listener
        }
        // Build-9: phím Xoay tự do (FREE_LOOK): bấm để đổi chế độ con trỏ
        // <-> nhìn tự do, y hệt F9. Chỉ nhận phím bàn phím (không nhận nút
        // chuột — nút chuột đã dùng cho bấm tại con trỏ / nút bắn).
        // Build-12: KHÔNG return sau khi đổi chế độ — event đi tiếp xuống
        // hàng đợi để các phần tử khác gán cùng phím (nút/vuốt/macro...)
        // cùng kích hoạt (1 phím gán nhiều phần tử). Phím chỉ gán FREE_LOOK
        // thì TouchMapper không tìm thấy gì nên không làm gì thêm; MAP tắt
        // thì cổng `if (!mapOn)` bên dưới chặn lại.
        if (e is InputEvent.Key && e.pressed && e.code in mapper.layout.freeLookKeys) {
            val res = setCursorMode(!cursorMode)
            if (res.startsWith("ERR")) Server.log("FREELOOK: bấm ${LinuxKey.name(e.code)} nhưng $res")
        }
        if (!mapOn) return@Listener
        val item = Work.Event(e, SystemClock.uptimeMillis())
        if (!queue.offer(item)) {
            // Hàng đợi đầy: rớt sự kiện chuột cũ nhất rồi thử lại.
            val it = queue.iterator()
            while (it.hasNext()) {
                val old = it.next()
                if (old is Work.Event && old.e is InputEvent.MouseMove) {
                    it.remove()
                    break
                }
            }
            if (!queue.offer(item)) {
                dropCount++
                if (dropCount % 100L == 1L) Server.log("TouchHub: hàng đợi đầy, đã rớt $dropCount sự kiện.")
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        Injector.probe()
        InputBus.subscribe(busListener)
        worker = Thread({ workerLoop() }, "TouchHubWorker").apply { isDaemon = true; start() }
        Server.log("TouchHub đã khởi động (MAP mặc định tắt, tiêm khả dụng=${Injector.available}).")
    }

    fun stop() {
        if (!started) return
        started = false
        mapOn = false
        worker?.interrupt()
        worker = null
        cancelAll("TouchHub dừng")
        restoreUntrustedTouchesBlock()
        RawEvdevInjector.releaseAllSlots()
        RawEvdevInjector.close()
        injectorMode = InjectorMode.REFLECT
        InputBus.unsubscribe(busListener)
    }

    /** Injector đang dùng. */
    private fun activeInjector(): TouchInjector =
        if (injectorMode == InjectorMode.EVDEV) RawEvdevInjector else Injector

    /**
     * Đổi cách tiêm (GĐ6: lệnh INJECT). Hủy ngón trong engine TRƯỚC, đổi
     * mode NGAY, rồi tiêm CANCEL qua injector CŨ: mọi event trong lúc
     * probe() (đọc file, hàng chục ms) đều đi qua injector mới một cách
     * nhất quán, không kẹt ngón. Đổi sang evdev mà dò không được node cảm
     * ứng thì giữ nguyên reflect và trả ERR (ngón vẫn được nhả ở injector
     * cũ). Đổi về reflect thì nhả thêm toàn bộ slot evdev trước khi đóng
     * stream để không còn slot nào kẹt.
     */
    fun setInjector(mode: InjectorMode): String {
        if (mode == injectorMode) return "OK INJECT ${mode.key}"
        val oldInjector = activeInjector()
        // Tiêm CANCEL trong khóa (cùng lần khóa với cancelAll): không có
        // sự kiện nào của worker chen vào giữa lúc hủy và lúc tiêm, nên
        // không thể có DOWN mới tiêm sau CANCEL rồi kẹt ngón.
        synchronized(lock) {
            mapper.reset()
            engine.cancelAll(SystemClock.uptimeMillis())?.also {
                oldInjector.inject(it, display?.displayId ?: 0)
                Server.log("Cảm ứng: hủy toàn bộ ngón (đổi injector sang ${mode.key}).")
            }
        }
        if (mode == InjectorMode.EVDEV) {
            val d = display
            if (d != null) RawEvdevInjector.setDisplay(d.width, d.height)
            if (!RawEvdevInjector.probe()) {
                return "ERR evdev_unavailable"
            }
            injectorMode = InjectorMode.EVDEV
            Server.log("INJECT: đã chuyển sang evdev (${RawEvdevInjector.currentNode()}).")
            return "OK INJECT evdev"
        }
        injectorMode = InjectorMode.REFLECT
        RawEvdevInjector.releaseAllSlots()
        RawEvdevInjector.close()
        Server.log("INJECT: đã chuyển về reflect.")
        return "OK INJECT reflect"
    }

    /** App gửi DISPLAY mỗi khi kết nối và khi xoay màn hình. Đổi DISPLAY hủy ngón cũ. */
    fun setDisplay(info: TouchProtocol.DisplayInfo): String {
        synchronized(lock) {
            mapper.reset()
            mapper.displayW = info.width
            mapper.displayH = info.height
            display = info
            // Tiêm CANCEL trong khóa (xem setInjector): tránh DOWN chen vào sau CANCEL.
            engine.cancelAll(SystemClock.uptimeMillis())?.let { inject(it) }
        }
        RawEvdevInjector.setDisplay(info.width, info.height)
        Server.log("DISPLAY: ${info.width}x${info.height} xoay=${info.rotation} displayId=${info.displayId}.")
        return "OK DISPLAY ${info.width}x${info.height}"
    }

    /** Bật/tắt MAP. Chưa có DISPLAY thì chưa cho bật. Tắt thì hủy mọi ngón ngay.
     *  Bật MAP thì tự chiếm (GRAB) chuột + bàn phím để sự kiện không lọt
     *  xuống Android (không thì chuột hệ thống cũng di chuyển/bấm cùng lúc
     *  với chạm tiêm); tắt MAP thì nhả GRAB để dùng chuột/phím bình thường.
     */
    fun setMap(on: Boolean): String {
        if (on) {
            val d = display ?: return "ERR no_display"
            mapOn = true
            lastLatencyLogMs = SystemClock.uptimeMillis()
            disableUntrustedTouchesBlock()
            val grabRes = InputHub.setGrab("all", true)
            Server.log("MAP bật (display ${d.width}x${d.height}, injector=${activeInjector().name}, tiêm khả dụng=${activeInjector().available}). $grabRes")
            return "OK MAP 1"
        }
        mapOn = false
        cancelAll("tắt MAP")
        InputHub.setGrab("all", false)
        restoreUntrustedTouchesBlock()
        return "OK MAP 0"
    }

    /** Tắt MAP từ bên ngoài (mất heartbeat): hủy ngón với lý do rồi tắt. */
    fun forceMapOff(reason: String) {
        mapOn = false
        cancelAll(reason)
        InputHub.setGrab("all", false)
        restoreUntrustedTouchesBlock()
    }

    /**
     * Chuyển chế độ con trỏ <-> nhìn tự do. Đổi chế độ thì nhả ngón camera /
     * ngón bấm tại con trỏ đang đè (mapper lo). Chế độ chỉ có tác dụng khi
     * MAP đang bật, nhưng vẫn cho đổi khi MAP tắt để lần bật sau dùng ngay.
     */
    fun setCursorMode(on: Boolean): String {
        synchronized(lock) {
            val s = mapper.setCursorMode(on, SystemClock.uptimeMillis())
            for (spec in s) inject(spec)
        }
        cursorMode = on
        Server.log(if (on) "Chế độ con trỏ: chuột di chuyển con trỏ ảo, chuột trái bấm tại con trỏ." else "Chế độ nhìn tự do: chuột kéo camera.")
        return "OK CURSOR ${if (on) 1 else 0}"
    }

    /**
     * Đặt trạng thái ẩn lớp phủ (build-9): lệnh OVERLAY từ app hoặc phím
     * nóng ẩn/hiện lớp phủ. Chỉ đổi cờ + ghi log — app tự ẩn/hiện các cửa
     * sổ khi đọc MAPSTATUS. Keymap vẫn chạy ngầm, không ảnh hưởng MAP.
     */
    fun setOverlayHidden(hidden: Boolean): String {
        overlayHidden = hidden
        Server.log(if (hidden) "Lớp phủ: đã ẩn (keymap vẫn chạy ngầm)." else "Lớp phủ: đã hiện lại.")
        return "OK OVERLAY ${if (hidden) 1 else 0}"
    }

    /**
     * Bật/tắt chuột (build-11): lệnh MOUSE từ app, phím bật/tắt chuột,
     * hoặc double-tap vòng camera trong trình sửa. Tắt thì di chuyển chuột
     * bị bỏ (con trỏ đứng yên, camera không xoay); phím/nút vẫn tiêm bình
     * thường. App đọc mouse= trong MAPSTATUS để ẩn/hiện tam giác con trỏ.
     */
    fun setMouseEnabled(on: Boolean): String {
        mapper.mouseEnabled = on
        Server.log(if (on) "Chuột: đã bật." else "Chuột: đã tắt (di chuyển bị bỏ, phím/nút vẫn chạy).")
        return "OK MOUSE ${if (on) 1 else 0}"
    }

    /**
     * Đặt tần số quét chuột (build-9; build-11 thêm 60Hz + NO LIMIT):
     * chu kỳ gộp MOVE tiêm = 1000/hz ms (60Hz->16ms, 125Hz->8ms,
     * 250Hz->4ms, 500Hz->2ms, 1000Hz->1ms, NO LIMIT->0ms = tiêm ngay
     * mỗi sự kiện). Đây là tốc độ xử lý nội bộ của app, KHÔNG phải
     * polling rate phần cứng của chuột USB.
     */
    fun setPollRate(hz: Int): String {
        if (hz == Config.POLL_RATE_NO_LIMIT) {
            moveIntervalMs = 0L
            Server.log("Tần số quét chuột: NO LIMIT (không gộp MOVE, tiêm ngay mỗi sự kiện).")
            return "OK POLLRATE 0"
        }
        val clamped = hz.coerceIn(Config.POLL_RATES.first(), Config.POLL_RATES.last())
        moveIntervalMs = Config.pollIntervalMs(clamped)
        Server.log("Tần số quét chuột: $clamped Hz (chu kỳ xử lý ${moveIntervalMs}ms).")
        return "OK POLLRATE $clamped"
    }

    /**
     * Tắt chặn "untrusted touch" của Android 12+ khi bật MAP (GĐ6, học theo
     * GG Mouse Pro): khi lớp phủ chơi đang hiện, hệ thống mặc định chặn cảm
     * ứng tiêm đi xuyên qua; tắt thì cảm ứng tiêm mới tới được game. Lưu
     * giá trị cũ để khôi phục khi tắt MAP.
     */
    private fun disableUntrustedTouchesBlock() {
        if (untrustedTouchesPrev != null) return // đã tắt rồi
        val prev = Shell.runOutput(Shell.getBlockUntrustedTouchesCommand()) ?: "1"
        val rc = Shell.run(Shell.blockUntrustedTouchesCommand(off = true))
        if (rc != 0) {
            // put thất bại: không lưu prev để lần bật MAP sau thử lại;
            // MAP vẫn bật (evdev không cần setting này, reflect có thể
            // vẫn tiêm được khi không có lớp phủ).
            Server.log("MAP bật: TẮT block_untrusted_touches THẤT BẠI (exit=$rc) — cảm ứng tiêm có thể bị chặn khi lớp phủ hiện.")
            return
        }
        untrustedTouchesPrev = prev
        Server.log("MAP bật: đã tắt block_untrusted_touches (giá trị cũ: $prev).")
    }

    /** Khôi phục block_untrusted_touches về giá trị trước khi bật MAP. */
    private fun restoreUntrustedTouchesBlock() {
        val prev = untrustedTouchesPrev ?: return
        untrustedTouchesPrev = null
        val rc = Shell.run(Shell.restoreBlockUntrustedTouchesCommand(prev))
        Server.log("MAP tắt: đã khôi phục block_untrusted_touches=$prev (exit=$rc).")
    }

    /** Thay toàn bộ bố cục (GĐ5: lệnh PROFILE). Hủy ngón cũ vì vị trí không còn đúng. */
    fun setLayout(layout: TouchLayout) {
        synchronized(lock) {
            mapper.reset()
            mapper.layout = layout
            engine.cancelAll(SystemClock.uptimeMillis())?.let { inject(it) }
        }
    }

    /** Áp dụng profile từ app (GĐ5: lệnh PROFILE). Cần DISPLAY trước để đổi tỉ lệ sang pixel. */
    fun applyProfile(profile: Profile): String {
        val d = display ?: return "ERR no_display"
        setLayout(profile.toLayout(d.width, d.height))
        Server.log("PROFILE: đã áp dụng \"${profile.name}\" (${profile.elements.size} phần tử).")
        return "OK PROFILE ${profile.name}"
    }

    /** Chỉnh 1 phần bố cục (GĐ4: lệnh TOUCHCFG). Giá trị kẹp vào khoảng hợp lệ. */
    fun applyTouchCfg(values: Map<String, Float>): String {
        synchronized(lock) {
            var l = mapper.layout
            for ((k, v) in values) {
                l = when (k) {
                    // Build-11: app gửi HỆ SỐ engine (đã đổi từ thang UI
                    // 1..100 bằng Config.sensFactor): 0.05..5.0.
                    "sensX" -> l.copy(sensX = v.coerceIn(0.05f, 5f))
                    "sensY" -> l.copy(sensY = v.coerceIn(0.05f, 5f))
                    "joyX" -> l.copy(joyX = v.coerceIn(0f, 1f))
                    "joyY" -> l.copy(joyY = v.coerceIn(0f, 1f))
                    "joyR" -> l.copy(joyR = v.coerceIn(0.01f, 0.5f))
                    "camX" -> l.copy(camX = v.coerceIn(0f, 1f))
                    "camY" -> l.copy(camY = v.coerceIn(0f, 1f))
                    "camHW" -> l.copy(camHW = v.coerceIn(0.05f, 0.5f))
                    "camHH" -> l.copy(camHH = v.coerceIn(0.05f, 0.5f))
                    // Build-9: đầm tâm X/Y (build-11: mức 1..100), tốc độ
                    // con trỏ (0.5..3.0), phím ẩn/hiện lớp phủ (mã Linux).
                    // Build-11: giới hạn vùng con trỏ (0..2), phím bật/tắt
                    // chuột (mã Linux, 0 = chưa gán).
                    "dampX" -> l.copy(dampX = v.toInt().coerceIn(Config.DAMP_MIN, Config.DAMP_MAX))
                    "dampY" -> l.copy(dampY = v.toInt().coerceIn(Config.DAMP_MIN, Config.DAMP_MAX))
                    "cursorSpeed" -> l.copy(
                        cursorSpeed = v.coerceIn(Config.CURSOR_SPEED_MIN, Config.CURSOR_SPEED_MAX),
                    )
                    "overlayKey" -> l.copy(overlayToggleKey = v.toInt())
                    "cursorRegion" -> l.copy(
                        cursorRegion = v.toInt().coerceIn(
                            Config.CURSOR_REGION_OFF, Config.CURSOR_REGION_RIGHT,
                        ),
                    )
                    "mouseKey" -> l.copy(mouseToggleKey = v.toInt())
                    else -> l
                }
            }
            mapper.layout = l
            mapper.reset()
            engine.cancelAll(SystemClock.uptimeMillis())?.let { inject(it) }
        }
        Server.log("TOUCHCFG: đã áp dụng ${values.keys.joinToString(",")}.")
        return "OK TOUCHCFG"
    }

    /** Bố cục hiện tại (để GĐ5 / MAPSTATUS dùng). */
    fun currentLayout(): TouchLayout = synchronized(lock) { mapper.layout }

    fun mapStatus(): String {
        val (pointers, latency, cursor) = synchronized(lock) {
            Triple(
                engine.count,
                if (latencyCount > 0) latencySumMs / latencyCount else 0L,
                mapper.cursorRatio(),
            )
        }
        val inj = activeInjector()
        return TouchProtocol.formatMapStatus(
            mapOn, display, pointers, latency, inj.errorCount, inj.available, inj.name,
            mode = if (cursorMode) "cursor" else "camera",
            cursorX = cursor.first, cursorY = cursor.second,
            overlayHidden = overlayHidden,
            // Build-11: mouse= để app ẩn/hiện tam giác con trỏ khi chuột
            // bị tắt; grab= để app hiện cách GRAB đang dùng (chẩn đoán
            // lỗi 2 con trỏ trên máy thật: "none" = chưa grab được).
            mouseEnabled = mapper.mouseEnabled,
            grabMethod = Grabber.strategyName,
            // Build-12: số liệu grab thật + số thiết bị Server thấy (chẩn
            // đoán lỗi tam giác đứng im / gán phím không ăn trên máy thật).
            grabbed = InputHub.grabbedCount(),
            grabFailed = InputHub.lastGrabFailed,
            devices = InputHub.deviceCount(),
            // Build-13: chẩn đoán quét (proc/parse/mở/lỗi/fallback) — app
            // hiện khi devices=0 để biết Server kẹt ở bước nào.
            diag = InputHub.scanDiagnostics().token(),
        )
    }

    /**
     * Chạy test tiêm (chạm/vuốt/2 ngón). Làm được cả khi MAP tắt — đó là mục
     * đích của nút TEST. Cần DISPLAY trước. Ngón test bận thì báo lỗi.
     */
    fun testTouch(t: TouchProtocol.TestTouch): String {
        if (display == null) return "ERR no_display"
        if (!activeInjector().available) return "ERR inject_unavailable"
        val busy = synchronized(lock) {
            engine.isDown(TEST_ID_1) || engine.isDown(TEST_ID_2)
        }
        if (busy) return "ERR test_busy"
        if (!queue.offer(Work.Test(t))) return "ERR queue_full"
        return "OK TESTTOUCH"
    }

    /** Hủy khẩn toàn bộ ngón (tắt MAP / thoát khẩn cấp / mất heartbeat / dừng). */
    fun cancelAll(reason: String) {
        var cancelled = false
        synchronized(lock) {
            mapper.reset()
            engine.cancelAll(SystemClock.uptimeMillis())?.let {
                inject(it) // tiêm trong khóa: không DOWN nào chen vào sau CANCEL
                cancelled = true
            }
        }
        if (cancelled) {
            Server.log("Cảm ứng: hủy toàn bộ ngón ($reason).")
        }
    }

    private fun workerLoop() {
        while (started) {
            try {
                val work = queue.poll(Config.TOUCH_WORKER_POLL_MS, TimeUnit.MILLISECONDS)
                val now = SystemClock.uptimeMillis()
                // processEvent trả true khi vòng này vừa tiêm DOWN/POINTER_DOWN:
                // bỏ qua flushMove vòng này để DOWN và MOVE đầu không gửi
                // cùng khung (game có thể bỏ qua MOVE dính liền sau DOWN).
                var pressedDown = false
                when (work) {
                    is Work.Event -> pressedDown = processEvent(work, now)
                    is Work.Test -> runTest(work.t)
                    null -> { /* hết timeout: vẫn chạy kiểm tra định kỳ bên dưới */ }
                }
                // Tiến triển các vuốt (SWIPE) đang chạy TRƯỚC khi gộp MOVE để
                // vị trí nội suy được tiêm ngay trong vòng này.
                synchronized(lock) {
                    for (spec in mapper.tick(now)) inject(spec)
                }
                // Gộp MOVE: tối đa 1 sự kiện mỗi moveIntervalMs (tần số quét
                // chuột, build-9; mặc định 2ms = 500Hz).
                if (!pressedDown && now - lastMoveInjectMs >= moveIntervalMs) {
                    synchronized(lock) {
                        engine.flushMove(now)?.let {
                            inject(it)
                            lastMoveInjectMs = now
                        }
                    }
                }
                // Nhả ngón camera khi chuột đứng yên quá ngưỡng.
                synchronized(lock) {
                    mapper.checkIdle(now)?.let { inject(it) }
                }
                // Log độ trễ trung bình mỗi 5s khi MAP bật.
                if (mapOn && now - lastLatencyLogMs >= Config.LATENCY_LOG_INTERVAL_MS) {
                    lastLatencyLogMs = now
                    val (sum, count) = synchronized(lock) {
                        val s = latencySumMs
                        val c = latencyCount
                        latencySumMs = 0L
                        latencyCount = 0L
                        s to c
                    }
                    if (count > 0) {
                        Server.log("Độ trễ tiêm trung bình: ${sum / count}ms ($count sự kiện), lỗi tiêm: ${activeInjector().errorCount}.")
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Server.log("TouchHub worker lỗi: ${e.message}")
            }
        }
    }

    /**
     * Xử lý 1 sự kiện đã xếp hàng. Trả true nếu vòng này vừa tiêm DOWN hoặc
     * POINTER_DOWN (để workerLoop bỏ qua flushMove vòng này).
     *
     * Kiểm tra mapOn TRONG khóa: sự kiện xếp hàng từ lúc MAP còn bật nhưng
     * xử lý sau khi MAP đã tắt (setMap(false) đã hủy ngón) thì bỏ — không
     * thì DOWN mới tiêm sau CANCEL sẽ kẹt ngón. Tiêm cũng trong khóa nên
     * không có lần hủy nào chen vào giữa lúc tạo đặc tả và lúc tiêm.
     */
    private fun processEvent(work: Work.Event, now: Long): Boolean {
        var pressedDown = false
        synchronized(lock) {
            if (!mapOn) return false // MAP đã tắt sau khi sự kiện xếp hàng: bỏ
            val specs = mapper.process(work.e, now)
            for (spec in specs) {
                inject(spec)
                if (spec.action == TouchEngine.ACTION_DOWN ||
                    (spec.action and 0xFF) == TouchEngine.ACTION_POINTER_DOWN
                ) {
                    pressedDown = true
                }
            }
            // Độ trễ: từ lúc sự kiện vào hàng đợi đến lúc tiêm xong.
            val latency = SystemClock.uptimeMillis() - work.arrivalMs
            latencySumMs += latency
            latencyCount++
        }
        return pressedDown
    }

    /** Chạy test tiêm trên luồng worker; sleep nằm NGOÀI khóa để không chặn sự kiện.
     *  Mỗi bước nhấn/nhả + tiêm nằm trong CÙNG 1 lần khóa (như processEvent)
     *  để CANCEL của setDisplay/setMap không chen vào giữa. */
    private fun runTest(t: TouchProtocol.TestTouch) {
        val d = display ?: return
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        fun now() = SystemClock.uptimeMillis()
        when (t) {
            is TouchProtocol.TestTouch.Tap -> {
                val ok = synchronized(lock) {
                    if (engine.isDown(TEST_ID_1)) false
                    else {
                        engine.press(TEST_ID_1, t.xRatio * w, t.yRatio * h, now())
                            ?.let { inject(it) }
                        true
                    }
                }
                if (!ok) return
                Thread.sleep(Config.TEST_TAP_HOLD_MS)
                synchronized(lock) { engine.release(TEST_ID_1, now())?.let { inject(it) } }
                Server.log("TESTTOUCH TAP (${t.xRatio}, ${t.yRatio}) xong.")
            }
            is TouchProtocol.TestTouch.Swipe -> {
                val ok = synchronized(lock) {
                    if (engine.isDown(TEST_ID_1)) false
                    else {
                        engine.press(TEST_ID_1, t.x1 * w, t.y1 * h, now())
                            ?.let { inject(it) }
                        true
                    }
                }
                if (!ok) return
                val steps = Config.TEST_SWIPE_STEPS
                val stepMs = (t.ms / steps).coerceAtLeast(1)
                for (i in 1..steps) {
                    val f = i.toFloat() / steps
                    val x = t.x1 * w + (t.x2 - t.x1) * w * f
                    val y = t.y1 * h + (t.y2 - t.y1) * h * f
                    synchronized(lock) {
                        engine.setPosition(TEST_ID_1, x, y)
                        engine.flushMove(now())?.let { inject(it) }
                    }
                    Thread.sleep(stepMs.toLong())
                }
                synchronized(lock) { engine.release(TEST_ID_1, now())?.let { inject(it) } }
                Server.log("TESTTOUCH SWIPE xong (${t.ms}ms).")
            }
            is TouchProtocol.TestTouch.Two -> {
                val ok = synchronized(lock) {
                    if (engine.isDown(TEST_ID_1) || engine.isDown(TEST_ID_2)) false
                    else {
                        engine.press(TEST_ID_1, t.x1 * w, t.y1 * h, now())
                            ?.let { inject(it) }
                        engine.press(TEST_ID_2, t.x2 * w, t.y2 * h, now())
                            ?.let { inject(it) }
                        true
                    }
                }
                if (!ok) return
                Thread.sleep(Config.TEST_TWO_HOLD_MS)
                synchronized(lock) {
                    engine.release(TEST_ID_2, now())?.let { inject(it) }
                    engine.release(TEST_ID_1, now())?.let { inject(it) }
                }
                Server.log("TESTTOUCH TWO xong.")
            }
        }
    }

    private fun inject(spec: TouchEngine.MotionEventSpec) {
        activeInjector().inject(spec, display?.displayId ?: 0)
    }
}

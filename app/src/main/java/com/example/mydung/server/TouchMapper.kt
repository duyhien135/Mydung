package com.example.mydung.server

/**
 * 1 lần vuốt gán cho 1 phím: bấm phím thì ngón chạm ở (x1, y1), trượt tới
 * (x2, y2) trong [durationMs] rồi nhả. Tọa độ theo TỈ LỆ màn hình (0.0..1.0).
 */
data class SwipeSpec(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val durationMs: Long,
)

/** 1 điểm tự động tap (build-9): giữ phím thì tap lặp tại (x, y) mỗi [intervalMs]. */
data class AutoTapSpec(val x: Float, val y: Float, val intervalMs: Long)

/**
 * Bố cục ánh xạ: mọi tọa độ viết theo TỈ LỆ màn hình ngang (0.0..1.0),
 * nhân với kích thước thật lúc dùng. GĐ4 dùng bộ mặc định; GĐ5 build
 * từ profile (Profile.toLayout) theo đúng struct này.
 */
data class TouchLayout(
    val joyX: Float, val joyY: Float, val joyR: Float, // tâm + bán kính joystick (bán kính theo chiều rộng)
    val camX: Float, val camY: Float, val camHW: Float, val camHH: Float, // tâm + BÁN KÍNH vùng camera tròn (camHH giữ lại cho tương thích, logic tròn chỉ dùng camHW)
    val sensX: Float, val sensY: Float, // độ nhạy chuột (pixel trên màn hình / 1 đơn vị chuột)
    val buttons: Map<Int, Pair<Float, Float>>, // mã Linux -> điểm chạm (tỉ lệ)
    val swipes: Map<Int, SwipeSpec> = emptyMap(), // mã Linux -> vuốt (tỉ lệ)
    val mapToggleKey: Int = LinuxKey.F8,
    val cursorToggleKey: Int = LinuxKey.F9, // phím chuyển con trỏ <-> nhìn tự do
    // Build-9:
    val autoTaps: Map<Int, AutoTapSpec> = emptyMap(), // mã Linux -> tự động tap lặp
    val releaseTaps: Map<Int, Pair<Float, Float>> = emptyMap(), // mã Linux -> tap khi NHẢ phím
    val freeLookKeys: Set<Int> = emptySet(), // phím đổi chế độ con trỏ <-> nhìn tự do (như F9)
    val macros: Map<Int, List<MacroStep>> = emptyMap(), // mã Linux -> chuỗi bước macro
    val dampX: Int = Config.DAMP_DEFAULT, // đầm tâm X (mức 1..100, EMA alpha = 1/mức đầm; xem Config.dampAlpha)
    val dampY: Int = Config.DAMP_DEFAULT, // đầm tâm Y
    val cursorSpeed: Float = Config.CURSOR_SPEED_DEFAULT, // tốc độ con trỏ ảo (chế độ con trỏ)
    val overlayToggleKey: Int = Config.OVERLAY_TOGGLE_KEY, // phím ẩn/hiện lớp phủ
    // Build-11: giới hạn vùng con trỏ (0 = TẮT, 1 = TOÀN MÀN, 2 = MÀN PHẢI)
    // + phím bật/tắt chuột (0 = chưa gán).
    val cursorRegion: Int = Config.CURSOR_REGION_DEFAULT,
    val mouseToggleKey: Int = Config.MOUSE_TOGGLE_KEY_UNBOUND,
) {
    companion object {
        /** Bộ mặc định cho game bắn súng kiểu Free Fire (tọa độ trong Config). */
        fun default(): TouchLayout = TouchLayout(
            joyX = Config.JOY_CENTER_X, joyY = Config.JOY_CENTER_Y, joyR = Config.JOY_RADIUS,
            camX = Config.CAM_CENTER_X, camY = Config.CAM_CENTER_Y,
            camHW = Config.CAM_HALF_W, camHH = Config.CAM_HALF_H,
            sensX = Config.SENS_X, sensY = Config.SENS_Y,
            buttons = Config.DEFAULT_BUTTONS,
            mapToggleKey = Config.MAP_TOGGLE_KEY,
            cursorToggleKey = Config.CURSOR_TOGGLE_KEY,
        )
    }
}

/**
 * Biến sự kiện InputBus thành thao tác cảm ứng trên TouchPointerSet.
 * Logic thuần (thời gian do caller truyền) nên unit test được.
 *
 * - WASD -> giữ 1 ngón joystick (id 0), hướng chuẩn hóa độ dài 1.
 * - Chuột -> ngón camera (id 1), vuốt vô hạn bằng UP + DOWN lại ở điểm
 *   ĐỐI XỨNG QUA TÂM khi ra khỏi vùng TRÒN; DOWN lại hẹn sang tick sau
 *   (cách UP ít nhất Config.CAMERA_REPRESS_DELAY_MS, không cùng khung);
 *   đứng yên quá Config.CAMERA_IDLE_RELEASE_MS thì nhả ngón.
 * - Chế độ con trỏ (cursorMode): chuột di chuyển 1 con trỏ ảo (không đè
 *   ngón), chuột trái/phải bấm tại vị trí con trỏ (id 1) để bấm giao diện
 *   game; kéo chuột trong lúc giữ nút thì ngón đi theo con trỏ. Tốc độ
 *   con trỏ dùng layout.cursorSpeed (KHÁC độ nhạy X/Y — độ nhạy chỉ dùng
 *   cho camera ở chế độ nhìn tự do).
 * - Phím/nút trong bảng buttons -> chạm 1 điểm cố định (id 2..9).
 * - Phím/nút trong bảng swipes -> vuốt từ điểm đầu tới điểm cuối trong
 *   durationMs (id 2..9, dùng chung dải id với nút; nhả phím sớm thì hủy).
 * - Build-9: autoTaps (giữ phím thì tap lặp), releaseTaps (tap khi nhả
 *   phím), macros (1 phím chạy chuỗi tap/chờ/vuốt qua MacroPlayer).
 *   Đầm tâm: delta chuột REL được làm mượt bằng EMA theo từng trục
 *   (alpha = 1/mức đầm; mức 1 = không làm mượt) trước khi dùng.
 *
 * Sự kiện lặp phím (EV_KEY value=2) đã bị lọc ở FrameAssembler nên không
 * tới được đây — nhấn giữ phím không tạo vuốt/nút mới.
 */
class TouchMapper(
    private val engine: TouchPointerSet,
    @Volatile var layout: TouchLayout, // busListener đọc ngoài khóa để biết phím bật/tắt MAP
    var displayW: Int,
    var displayH: Int,
) {
    private val moveKeys = mutableSetOf<Int>()
    private var camX = 0f
    private var camY = 0f
    private var lastMouseMs = 0L
    private val buttonIds = mutableMapOf<Int, Int>() // mã phím/nút -> id ngón

    /**
     * Chế độ con trỏ (true) hay nhìn tự do bằng camera (false). MẶC ĐỊNH
     * LÀ CON TRỎ (true) — giống GG Mouse Pro: vào game là có con trỏ bấm
     * được ngay cả ở sảnh; F9 hoặc menu nút K đổi sang nhìn tự do. Đổi
     * mặc định ở đây thì phải đổi cùng lúc TouchHub.cursorMode (MAPSTATUS
     * đọc cờ bên đó; 2 cờ lệch nhau thì lần bấm F9 đầu tiên không có tác
     * dụng vì TouchHub tưởng đang ở chế độ còn lại).
     */
    var cursorMode = true
        private set

    /**
     * Chuột có đang bật không (build-11): tắt thì onMouseMove bỏ mọi di
     * chuyển (con trỏ đứng yên + camera không xoay); phím/nút vẫn tiêm
     * bình thường. Đổi bằng phím bật/tắt chuột (gán trong bảng chỉnh
     * camera), double-tap vòng camera, hoặc lệnh MOUSE. Mặc định bật.
     * reset() KHÔNG đụng tới cờ này (bật/tắt MAP giữ nguyên trạng thái).
     */
    @Volatile
    var mouseEnabled = true

    // Vị trí con trỏ ảo (pixel). Khởi tạo lười ở tâm vùng camera.
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorInit = false

    /** Mã nút chuột đang giữ để bấm tại con trỏ (null = không bấm). */
    private var cursorTapCode: Int? = null

    /** Vuốt đang chạy: mã phím -> (id ngón, lúc bắt đầu, thông số vuốt). */
    private data class ActiveSwipe(val pointerId: Int, val startMs: Long, val spec: SwipeSpec)
    private val activeSwipes = mutableMapOf<Int, ActiveSwipe>()

    /** Vuốt đã tới điểm cuối ở tick trước: tick này mới UP (điểm cuối đã được tiêm bằng MOVE). */
    private val pendingRelease = mutableSetOf<Int>()

    /** Lần chạm lại camera đang chờ sau khi UP ở rìa: (x, y, thời điểm sớm
     *  nhất được DOWN lại). UP và DOWN cách nhau ít nhất 1 tick (không cùng
     *  khung) — tick() sẽ DOWN khi tới giờ. */
    private var pendingCamRepress: Triple<Float, Float, Long>? = null

    /** Lần nội suy vuốt gần nhất (bước nội suy tối thiểu 8ms). */
    private var lastInterpMs = 0L

    /** Trạng thái EMA đầm tâm: delta đã làm mượt của lần di chuột trước (theo từng trục). */
    private var smoothDx = 0f
    private var smoothDy = 0f

    /** Tự động tap đang giữ: mã phím -> (thông số, lúc tap gần nhất). */
    private data class ActiveAutoTap(val spec: AutoTapSpec, var lastTapMs: Long)
    private val activeAutoTaps = mutableMapOf<Int, ActiveAutoTap>()

    /** Tap đã DOWN, đang chờ UP: (id ngón, thời điểm nhả). Dùng chung cho auto-tap + thả tap. */
    private data class PendingTapUp(val pointerId: Int, val upMs: Long)
    private val pendingTapUps = mutableListOf<PendingTapUp>()

    /** Phím thả-tap đang giữ (đã bấm, chờ nhả để tap). */
    private val releaseTapArmed = mutableSetOf<Int>()

    /** Macro đang chạy (1 phím = 1 chuỗi bước). */
    private val macroPlayer = MacroPlayer(engine, ::allocPointerId) { displayW to displayH }

    /**
     * Cấp 1 id ngón trong dải dùng chung 2..9 (nút + vuốt). Mọi lần gọi
     * đều nằm trong khóa duy nhất của TouchHub nên không có 2 lần cấp trùng
     * nhau; id chỉ được cấp lại sau khi ngón đã UP (engine không còn giữ
     * id đó) — không tái dùng sớm.
     */
    private fun allocPointerId(): Int? =
        (TouchEngine.BUTTON_ID_FIRST..TouchEngine.BUTTON_ID_LAST)
            .firstOrNull { !engine.isDown(it) }

    /** Xử lý 1 sự kiện, trả về các đặc tả cần tiêm ngay (DOWN/UP; MOVE gộp sau). */
    fun process(e: InputEvent, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        return when (e) {
            is InputEvent.Key -> when (e.code) {
                LinuxKey.W, LinuxKey.A, LinuxKey.S, LinuxKey.D -> {
                    if (e.pressed) moveKeys.add(e.code) else moveKeys.remove(e.code)
                    updateJoystick(nowMs)
                }
                else -> onButton(e.code, e.pressed, nowMs)
            }
            is InputEvent.Button -> onButton(e.code, e.pressed, nowMs)
            is InputEvent.MouseMove -> onMouseMove(e.dx, e.dy, nowMs)
            else -> emptyList() // Wheel/Device: GĐ4 chưa dùng
        }
    }

    /** Chuột đứng yên quá ngưỡng mà ngón camera còn đè -> nhả để tránh kẹt. */
    fun checkIdle(nowMs: Long): TouchEngine.MotionEventSpec? {
        if (!engine.isDown(TouchEngine.POINTER_CAMERA)) return null
        if (nowMs - lastMouseMs < Config.CAMERA_IDLE_RELEASE_MS) return null
        return engine.release(TouchEngine.POINTER_CAMERA, nowMs)
    }

    /**
     * Tiến triển các vuốt đang chạy (TouchHub gọi mỗi vòng worker): nội suy
     * vị trí theo thời gian (MOVE gộp sau qua flushMove). Vuốt tới điểm
     * cuối thì đặt đúng điểm cuối và chờ 1 tick rồi mới UP — MOVE tới điểm
     * cuối được tiêm ở tick này, UP ở tick sau (không thì UP mang vị trí
     * nội suy cũ, vuốt kết thúc ngắn hơn điểm cuối). Bước nội suy tối
     * thiểu 8ms (bằng MOVE_MIN_INTERVAL_MS).
     * Trả về các đặc tả UP của vuốt vừa xong.
     */
    fun tick(nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        // Chạm lại camera sau UP ở rìa: chỉ DOWN khi đã qua thời điểm hẹn
        // (UP và DOWN cách nhau ít nhất 1 tick, không cùng khung).
        val pending = pendingCamRepress
        if (pending != null && nowMs >= pending.third) {
            pendingCamRepress = null
            if (!engine.isDown(TouchEngine.POINTER_CAMERA)) {
                engine.press(TouchEngine.POINTER_CAMERA, pending.first, pending.second, nowMs)
                    ?.let { out.add(it) }
                camX = pending.first
                camY = pending.second
            }
        }
        // Build-9: nhả các tap đã hết thời gian giữ (auto-tap / thả tap).
        val tapIt = pendingTapUps.iterator()
        while (tapIt.hasNext()) {
            val t = tapIt.next()
            if (nowMs >= t.upMs) {
                tapIt.remove()
                engine.release(t.pointerId, nowMs)?.let { out.add(it) }
            }
        }
        // Build-9: auto-tap lặp — giữ phím thì tap lại mỗi intervalMs.
        for ((_, active) in activeAutoTaps) {
            if (nowMs - active.lastTapMs >= active.spec.intervalMs) {
                active.lastTapMs = nowMs
                val id = allocPointerId() ?: continue // hết ngón: bỏ lần tap này
                engine.press(
                    id, active.spec.x * displayW, active.spec.y * displayH, nowMs,
                )?.let { out.add(it) }
                pendingTapUps.add(PendingTapUp(id, nowMs + Config.AUTO_TAP_HOLD_MS))
            }
        }
        // Build-9: tiến triển macro đang chạy.
        out.addAll(macroPlayer.tick(nowMs))
        if (activeSwipes.isEmpty() && pendingRelease.isEmpty()) return out
        // Vuốt đã tới điểm cuối ở tick trước: nhả ngay ở tick này.
        val it = pendingRelease.iterator()
        while (it.hasNext()) {
            val code = it.next()
            it.remove()
            val active = activeSwipes.remove(code) ?: continue
            engine.release(active.pointerId, nowMs)?.let { out.add(it) }
        }
        val canInterp = nowMs - lastInterpMs >= Config.MOVE_MIN_INTERVAL_MS
        if (canInterp) lastInterpMs = nowMs
        for ((code, active) in activeSwipes) {
            val dur = active.spec.durationMs.coerceAtLeast(1L)
            val elapsed = nowMs - active.startMs
            if (elapsed >= dur) {
                // Tới điểm cuối: đặt đúng điểm cuối, chờ 1 tick rồi mới UP.
                engine.setPosition(active.pointerId, active.spec.x2 * displayW, active.spec.y2 * displayH)
                pendingRelease.add(code)
            } else if (canInterp) {
                val f = (elapsed.toFloat() / dur).coerceIn(0f, 1f)
                val x = (active.spec.x1 + (active.spec.x2 - active.spec.x1) * f) * displayW
                val y = (active.spec.y1 + (active.spec.y2 - active.spec.y1) * f) * displayH
                engine.setPosition(active.pointerId, x, y)
            }
        }
        return out
    }

    /** Xóa trạng thái ánh xạ (khi tắt MAP / hủy khẩn; engine xóa riêng). */
    fun reset() {
        moveKeys.clear()
        buttonIds.clear()
        activeSwipes.clear()
        pendingRelease.clear()
        pendingCamRepress = null
        lastInterpMs = 0L
        lastMouseMs = 0L
        cursorTapCode = null
        cursorInit = false // con trỏ về tâm vùng camera lần dùng sau
        // Build-9: xóa trạng thái auto-tap / thả tap / macro / EMA.
        // Macro chỉ xóa sổ (clear), KHÔNG nhả ngón riêng — TouchHub sẽ
        // CANCEL toàn bộ engine ngay sau reset() (xem MacroPlayer.clear).
        activeAutoTaps.clear()
        pendingTapUps.clear()
        releaseTapArmed.clear()
        macroPlayer.clear()
        smoothDx = 0f
        smoothDy = 0f
    }

    /**
     * Đổi chế độ con trỏ <-> nhìn tự do. Đổi chế độ thì nhả ngón camera /
     * ngón bấm tại con trỏ đang đè (nếu có) VÀ nhả các nút đang giữ để
     * không kẹt ngón: đang giữ chuột trái để bắn mà bấm F9 thì lúc nhả nút
     * ở chế độ con trỏ, sự kiện nhả đi vào onCursorTap (không khớp
     * cursorTapCode) nên ngón bắn không bao giờ được nhả. Joystick (id 0)
     * không nhả — WASD dùng được ở cả 2 chế độ, moveKeys vẫn theo dõi
     * đúng để nhả khi thôi bấm phím.
     */
    fun setCursorMode(on: Boolean, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        if (on == cursorMode) return emptyList()
        cursorMode = on
        cursorTapCode = null
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        engine.release(TouchEngine.POINTER_CAMERA, nowMs)?.let { out.add(it) }
        for ((_, id) in buttonIds) {
            engine.release(id, nowMs)?.let { out.add(it) }
        }
        buttonIds.clear()
        for ((_, active) in activeSwipes) {
            engine.release(active.pointerId, nowMs)?.let { out.add(it) }
        }
        activeSwipes.clear()
        pendingRelease.clear()
        // Build-9: đổi chế độ cũng hủy auto-tap / thả tap / macro đang chạy
        // (nhả ngón qua đặc tả để không kẹt).
        for (t in pendingTapUps) {
            engine.release(t.pointerId, nowMs)?.let { out.add(it) }
        }
        pendingTapUps.clear()
        activeAutoTaps.clear()
        releaseTapArmed.clear()
        out.addAll(macroPlayer.cancelAll(nowMs))
        smoothDx = 0f
        smoothDy = 0f
        // Hủy cả lần chạm lại camera đang chờ (kẻo ngón camera DOWN ở chế độ con trỏ).
        pendingCamRepress = null
        return out
    }

    /** Vị trí con trỏ ảo theo tỉ lệ màn hình (0.0..1.0) — app vẽ chấm con trỏ. */
    fun cursorRatio(): Pair<Float, Float> {
        ensureCursorInit()
        if (displayW <= 0 || displayH <= 0) return 0.5f to 0.5f
        return (cursorX / displayW) to (cursorY / displayH)
    }

    private fun ensureCursorInit() {
        if (cursorInit) return
        cursorInit = true
        cursorX = layout.camX * displayW
        cursorY = layout.camY * displayH
    }

    private fun updateJoystick(nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        var vx = 0f
        var vy = 0f
        if (LinuxKey.D in moveKeys) vx += 1f
        if (LinuxKey.A in moveKeys) vx -= 1f
        if (LinuxKey.S in moveKeys) vy += 1f
        if (LinuxKey.W in moveKeys) vy -= 1f
        if (vx == 0f && vy == 0f) {
            engine.release(TouchEngine.POINTER_JOYSTICK, nowMs)?.let { out.add(it) }
            return out
        }
        // Chuẩn hóa: W+D đi chéo với độ dài 1, không phải 1.41.
        val len = kotlin.math.sqrt(vx * vx + vy * vy)
        vx /= len
        vy /= len
        val tx = (layout.joyX * displayW) + vx * (layout.joyR * displayW)
        val ty = (layout.joyY * displayH) + vy * (layout.joyR * displayW)
        if (!engine.isDown(TouchEngine.POINTER_JOYSTICK)) {
            engine.press(TouchEngine.POINTER_JOYSTICK, tx, ty, nowMs)?.let { out.add(it) }
        } else {
            // Đảo hướng dùng MOVE, không nhả ngón.
            engine.setPosition(TouchEngine.POINTER_JOYSTICK, tx, ty)
        }
        return out
    }

    /**
     * Làm mượt delta chuột REL bằng EMA theo từng trục (đầm tâm): alpha =
     * 1/mức đầm, mức đầm = 1 + (giá trị UI 1..100 - 1)/11 (xem
     * Config.dampAlpha). Mức 1 -> alpha = 1 (delta thô, không làm mượt);
     * mức cao -> delta biến thiên chậm, tâm đầm/ổn định hơn nhưng phản hồi
     * chậm hơn.
     */
    private fun smoothDelta(dx: Int, dy: Int): Pair<Float, Float> {
        val ax = Config.dampAlpha(layout.dampX)
        val ay = Config.dampAlpha(layout.dampY)
        smoothDx += (dx - smoothDx) * ax
        smoothDy += (dy - smoothDy) * ay
        return smoothDx to smoothDy
    }

    /** Kẹp điểm (x, y) vào trong hình tròn tâm (cx, cy) bán kính r. */
    private fun clampToCircle(cx: Float, cy: Float, r: Float, x: Float, y: Float): Pair<Float, Float> {
        val dx = x - cx
        val dy = y - cy
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d <= r || d == 0f) return x to y
        val s = r / d
        return (cx + dx * s) to (cy + dy * s)
    }

    private fun onMouseMove(dx: Int, dy: Int, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        // Build-11: chuột đang tắt thì bỏ mọi di chuyển (con trỏ đứng yên,
        // camera không xoay). Phím/nút vẫn tiêm bình thường.
        if (!mouseEnabled) return emptyList()
        val (sdx, sdy) = smoothDelta(dx, dy)
        if (cursorMode) {
            // Chế độ con trỏ: chuột chỉ di chuyển con trỏ ảo, không đè ngón.
            // Đang giữ nút chuột thì ngón bấm đi theo con trỏ (kéo/thả trong menu game).
            // Tốc độ con trỏ dùng layout.cursorSpeed — KHÁC độ nhạy X/Y
            // (độ nhạy chỉ dùng cho camera ở chế độ nhìn tự do).
            // Build-11: giới hạn vùng con trỏ — MÀN PHẢI thì x không sang
            // được nửa trái (x >= 50% chiều rộng); TẮT/TOÀN MÀN thì cả màn hình.
            ensureCursorInit()
            val minX = if (layout.cursorRegion == Config.CURSOR_REGION_RIGHT) {
                displayW / 2f
            } else 0f
            cursorX = (cursorX + sdx * layout.cursorSpeed).coerceIn(minX, displayW.toFloat())
            cursorY = (cursorY + sdy * layout.cursorSpeed).coerceIn(0f, displayH.toFloat())
            if (cursorTapCode != null) {
                engine.setPosition(TouchEngine.POINTER_CAMERA, cursorX, cursorY)
            }
            return emptyList()
        }
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        lastMouseMs = nowMs
        val cx = layout.camX * displayW
        val cy = layout.camY * displayH
        // Vùng camera là HÌNH TRÒN (build-9): bán kính = camHW * chiều rộng.
        val radius = layout.camHW * displayW
        val pending = pendingCamRepress
        if (pending != null) {
            // Đang chờ chạm lại sau lần UP: cộng delta vào vị trí hẹn (kẹp
            // trong hình tròn), không DOWN ở tâm — ngón sẽ DOWN ở vị trí
            // hẹn khi tới giờ ở tick().
            val (px, py) = clampToCircle(
                cx, cy, radius,
                pending.first + sdx * layout.sensX,
                pending.second + sdy * layout.sensY,
            )
            camX = px
            camY = py
            pendingCamRepress = Triple(px, py, pending.third)
            return out
        }
        if (!engine.isDown(TouchEngine.POINTER_CAMERA)) {
            // Chưa có ngón: DOWN ở tâm vùng rồi đặt vị trí = tâm + delta (kẹp trong tròn).
            engine.press(TouchEngine.POINTER_CAMERA, cx, cy, nowMs)?.let { out.add(it) }
            val (nx, ny) = clampToCircle(
                cx, cy, radius,
                cx + sdx * layout.sensX,
                cy + sdy * layout.sensY,
            )
            camX = nx
            camY = ny
            engine.setPosition(TouchEngine.POINTER_CAMERA, camX, camY)
            return out
        }
        camX += sdx * layout.sensX
        camY += sdy * layout.sensY
        val dxc = camX - cx
        val dyc = camY - cy
        val dist = kotlin.math.sqrt(dxc * dxc + dyc * dyc)
        if (dist > radius) {
            // Ra khỏi vùng tròn: UP rồi DOWN lại ở điểm ĐỐI XỨNG QUA TÂM
            // (cách rìa một chút) để lần vuốt tiếp theo đi được gần hết
            // đường kính, không hết vùng ngay. DOWN lại KHÔNG làm ngay ở
            // đây: hẹn sang tick sau để UP và DOWN cách nhau ít nhất 1
            // tick (không cùng khung).
            val ux = dxc / dist
            val uy = dyc / dist
            val inset = radius * Config.CAMERA_EDGE_INSET_RATIO
            val nx = cx - ux * (radius - inset)
            val ny = cy - uy * (radius - inset)
            engine.release(TouchEngine.POINTER_CAMERA, nowMs)?.let { out.add(it) }
            camX = nx
            camY = ny
            pendingCamRepress = Triple(nx, ny, nowMs + Config.CAMERA_REPRESS_DELAY_MS)
        } else {
            engine.setPosition(TouchEngine.POINTER_CAMERA, camX, camY)
        }
        return out
    }

    private fun onButton(code: Int, pressed: Boolean, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        // Chế độ con trỏ: chuột trái/phải bấm TẠI vị trí con trỏ (để bấm
        // giao diện game), không dùng điểm chạm đã gán trong bảng.
        if (cursorMode && (code == LinuxKey.BTN_LEFT || code == LinuxKey.BTN_RIGHT)) {
            return onCursorTap(code, pressed, nowMs)
        }
        // Phím gán vuốt thì chạy vuốt, không chạm điểm cố định.
        val swipe = layout.swipes[code]
        if (swipe != null) return onSwipe(code, swipe, pressed, nowMs)
        // Build-9: tự động tap / thả tap / macro (kiểm tra trước nút thường).
        val autoTap = layout.autoTaps[code]
        if (autoTap != null) return onAutoTap(code, autoTap, pressed, nowMs)
        val releaseTap = layout.releaseTaps[code]
        if (releaseTap != null) return onReleaseTap(code, releaseTap, pressed, nowMs)
        val macro = layout.macros[code]
        if (macro != null) return onMacro(code, macro, pressed, nowMs)
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        val point = layout.buttons[code] ?: return out
        if (pressed) {
            if (buttonIds.containsKey(code)) return out
            val id = allocPointerId() ?: return out // hết ngón
            buttonIds[code] = id
            engine.press(id, point.first * displayW, point.second * displayH, nowMs)
                ?.let { out.add(it) }
        } else {
            val id = buttonIds.remove(code) ?: return out
            engine.release(id, nowMs)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Tự động Tap: bấm phím thì tap ngay 1 cái, giữ phím thì tick() tap lặp
     * mỗi intervalMs; nhả phím thì dừng (tap đang giữ vẫn nhả đúng hẹn).
     */
    private fun onAutoTap(
        code: Int,
        spec: AutoTapSpec,
        pressed: Boolean,
        nowMs: Long,
    ): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        if (pressed) {
            if (activeAutoTaps.containsKey(code)) return out
            activeAutoTaps[code] = ActiveAutoTap(spec, nowMs)
            val id = allocPointerId() ?: return out // hết ngón: giữ phím vẫn ghi nhận, lần tap sau thử lại
            engine.press(id, spec.x * displayW, spec.y * displayH, nowMs)
                ?.let { out.add(it) }
            pendingTapUps.add(PendingTapUp(id, nowMs + Config.AUTO_TAP_HOLD_MS))
        } else {
            activeAutoTaps.remove(code)
        }
        return out
    }

    /**
     * Thả Tap: bấm phím chỉ "nạp" (không làm gì), nhả phím thì tap 1 cái
     * tại vị trí đó. Nhả mà trước đó không bấm thì không tap.
     */
    private fun onReleaseTap(
        code: Int,
        point: Pair<Float, Float>,
        pressed: Boolean,
        nowMs: Long,
    ): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        if (pressed) {
            releaseTapArmed.add(code)
            return out
        }
        if (!releaseTapArmed.remove(code)) return out
        val id = allocPointerId() ?: return out // hết ngón: bỏ lần tap này
        engine.press(id, point.first * displayW, point.second * displayH, nowMs)
            ?.let { out.add(it) }
        pendingTapUps.add(PendingTapUp(id, nowMs + Config.AUTO_TAP_HOLD_MS))
        return out
    }

    /** Macro: bấm phím thì chạy chuỗi bước (bấm lại thì chạy lại từ đầu); nhả phím thì hủy. */
    private fun onMacro(
        code: Int,
        steps: List<MacroStep>,
        pressed: Boolean,
        nowMs: Long,
    ): List<TouchEngine.MotionEventSpec> {
        return if (pressed) macroPlayer.start(code, steps, nowMs)
        else macroPlayer.cancel(code, nowMs)
    }

    /**
     * Bấm tại con trỏ (chế độ con trỏ): DOWN ở vị trí con trỏ lúc nhấn,
     * UP lúc nhả. Dùng id ngón camera (id 1) vì ở chế độ này không có ngón
     * camera. Nhấn trùng (giữ 2 nút chuột cùng lúc) thì chỉ nhận nút đầu.
     */
    private fun onCursorTap(code: Int, pressed: Boolean, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        ensureCursorInit()
        if (pressed) {
            if (cursorTapCode != null) return out
            cursorTapCode = code
            engine.press(TouchEngine.POINTER_CAMERA, cursorX, cursorY, nowMs)?.let { out.add(it) }
        } else {
            if (cursorTapCode != code) return out
            cursorTapCode = null
            engine.release(TouchEngine.POINTER_CAMERA, nowMs)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Bấm phím vuốt: DOWN ở điểm đầu rồi tick() trượt dần tới điểm cuối.
     * Nhả phím sớm thì hủy vuốt ngay (UP ở vị trí hiện tại).
     */
    private fun onSwipe(
        code: Int,
        spec: SwipeSpec,
        pressed: Boolean,
        nowMs: Long,
    ): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        if (pressed) {
            if (activeSwipes.containsKey(code)) return out
            val id = allocPointerId() ?: return out // hết ngón
            activeSwipes[code] = ActiveSwipe(id, nowMs, spec)
            engine.press(id, spec.x1 * displayW, spec.y1 * displayH, nowMs)
                ?.let { out.add(it) }
        } else {
            val active = activeSwipes.remove(code) ?: return out
            pendingRelease.remove(code)
            engine.release(active.pointerId, nowMs)?.let { out.add(it) }
        }
        return out
    }
}

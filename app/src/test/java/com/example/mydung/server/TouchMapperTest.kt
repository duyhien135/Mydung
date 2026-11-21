package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMapperTest {

    // Màn hình 1000x500 để tính nhẩm dễ.
    private val layout = TouchLayout(
        joyX = 0.1f, joyY = 0.5f, joyR = 0.1f, // tâm (100, 250), bán kính 100px
        camX = 0.5f, camY = 0.5f, camHW = 0.2f, camHH = 0.4f, // tâm (500, 250), vùng TRÒN bán kính 200px (camHH không dùng trong logic tròn)
        sensX = 1f, sensY = 1f,
        buttons = mapOf(
            LinuxKey.SPACE to (0.9f to 0.5f), // (900, 250)
            LinuxKey.BTN_LEFT to (0.8f to 0.8f), // (800, 400)
        ),
    )

    private fun mapper(): Pair<TouchPointerSet, TouchMapper> {
        val engine = TouchPointerSet()
        return engine to TouchMapper(engine, layout, 1000, 500)
    }

    private fun key(code: Int, pressed: Boolean, t: Long = 0) = InputEvent.Key(code, pressed, t)
    private fun btn(code: Int, pressed: Boolean, t: Long = 0) = InputEvent.Button(code, pressed, t)
    private fun move(dx: Int, dy: Int, t: Long = 0) = InputEvent.MouseMove(dx, dy, t)

    private fun assertPoint(spec: TouchEngine.MotionEventSpec?, id: Int, x: Float, y: Float) {
        val p = spec?.pointers?.single { it.id == id }
        assertEquals(x, p?.x ?: Float.NaN, 0.01f)
        assertEquals(y, p?.y ?: Float.NaN, 0.01f)
    }

    @Test
    fun joystick_wMovesUp() {
        val (_, m) = mapper()
        val specs = m.process(key(LinuxKey.W, true, 100), 100)
        assertEquals(1, specs.size)
        assertEquals(TouchEngine.ACTION_DOWN, specs[0].action)
        assertPoint(specs[0], TouchEngine.POINTER_JOYSTICK, 100f, 150f) // tâm + (0,-100)
    }

    @Test
    fun joystick_diagonalPosition() {
        val (engine, m) = mapper()
        m.process(key(LinuxKey.W, true, 100), 100)
        m.process(key(LinuxKey.D, true, 110), 110)
        val move = engine.flushMove(120)
        assertEquals(TouchEngine.ACTION_MOVE, move?.action)
        // Hướng chéo chuẩn hóa: (100 + 70.71, 250 - 70.71).
        assertPoint(move, TouchEngine.POINTER_JOYSTICK, 170.71f, 179.29f)
    }

    @Test
    fun joystick_directionChangeUsesMoveNotReDown() {
        val (engine, m) = mapper()
        val down = m.process(key(LinuxKey.W, true, 100), 100)
        assertEquals(TouchEngine.ACTION_DOWN, down.single().action)
        // Đổi sang D: không DOWN mới, chỉ MOVE.
        assertTrue(m.process(key(LinuxKey.D, true, 110), 110).isEmpty())
        assertTrue(m.process(key(LinuxKey.W, false, 120), 120).isEmpty())
        val move = engine.flushMove(130)
        assertEquals(TouchEngine.ACTION_MOVE, move?.action)
        assertPoint(move, TouchEngine.POINTER_JOYSTICK, 200f, 250f) // chỉ D: (100+100, 250)
        assertEquals(1, engine.count)
    }

    @Test
    fun joystick_releaseWhenNoKeyDown() {
        val (_, m) = mapper()
        m.process(key(LinuxKey.W, true, 100), 100)
        m.process(key(LinuxKey.A, true, 110), 110)
        assertTrue(m.process(key(LinuxKey.W, false, 120), 120).isEmpty()) // còn A
        val up = m.process(key(LinuxKey.A, false, 130), 130)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
    }

    @Test
    fun camera_firstMoveDownAtCenterThenMove() {
        val (engine, m) = mapper()
        m.setCursorMode(false, 0) // mặc định là con trỏ — test camera phải chuyển về nhìn tự do
        val specs = m.process(move(10, 5, 100), 100)
        assertEquals(1, specs.size)
        assertEquals(TouchEngine.ACTION_DOWN, specs[0].action)
        assertPoint(specs[0], TouchEngine.POINTER_CAMERA, 500f, 250f) // DOWN ở tâm vùng
        val mv = engine.flushMove(110)
        assertPoint(mv, TouchEngine.POINTER_CAMERA, 510f, 255f) // tâm + delta
    }

    @Test
    fun camera_wrapUpAndDownAtOppositeEdge() {
        val (_, m) = mapper()
        m.setCursorMode(false, 0) // mặc định là con trỏ — test camera phải chuyển về nhìn tự do
        m.process(move(10, 0, 100), 100) // ngón ở (510, 250)
        // Di tiếp 300px phải: 810 > biên 700 -> UP, DOWN lại hẹn sang tick
        // sau (không cùng khung với UP).
        val specs = m.process(move(300, 0, 110), 110)
        assertEquals(1, specs.size)
        assertEquals(TouchEngine.ACTION_UP, specs[0].action)
        // Chưa tới giờ hẹn thì tick không DOWN.
        assertTrue(m.tick(110).isEmpty())
        // Tới giờ: DOWN ở RÌA ĐỐI DIỆN (rìa trái x=300, cách rìa 5% của nửa
        // chiều vùng = 10px -> x=310), y giữ nguyên.
        val down = m.tick(110 + Config.CAMERA_REPRESS_DELAY_MS)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], TouchEngine.POINTER_CAMERA, 310f, 250f)
        // Di tiếp từ rìa trái: MOVE bình thường.
        val specs2 = m.process(move(10, 0, 140), 140)
        assertTrue(specs2.isEmpty())
    }

    @Test
    fun camera_moveWhileWaitingToRepress_accumulatesOnPendingPosition() {
        val (engine, m) = mapper()
        m.setCursorMode(false, 0) // mặc định là con trỏ — test camera phải chuyển về nhìn tự do
        m.process(move(10, 0, 100), 100) // ngón ở (510, 250)
        m.process(move(300, 0, 110), 110) // ra khỏi vùng phải -> UP, hẹn DOWN ở (310, 250)
        assertEquals(0, engine.count)
        // Chuột di tiếp trong lúc chờ: delta cộng vào vị trí hẹn, không DOWN ở tâm.
        assertTrue(m.process(move(20, 0, 120), 120).isEmpty())
        assertEquals(0, engine.count)
        val down = m.tick(110 + Config.CAMERA_REPRESS_DELAY_MS)
        assertEquals(1, down.size)
        assertPoint(down[0], TouchEngine.POINTER_CAMERA, 330f, 250f)
    }

    @Test
    fun camera_idleRelease() {
        val (_, m) = mapper()
        m.setCursorMode(false, 0) // mặc định là con trỏ — test camera phải chuyển về nhìn tự do
        m.process(move(10, 0, 100), 100)
        assertEquals(null, m.checkIdle(100 + Config.CAMERA_IDLE_RELEASE_MS - 1))
        val up = m.checkIdle(100 + Config.CAMERA_IDLE_RELEASE_MS + 1)
        assertEquals(TouchEngine.ACTION_UP, up?.action)
        assertEquals(TouchEngine.POINTER_CAMERA, up?.pointers?.single()?.id)
        assertEquals(null, m.checkIdle(1000)) // đã nhả rồi
    }

    @Test
    fun button_pressReleaseFixedPoint() {
        val (_, m) = mapper()
        val down = m.process(key(LinuxKey.SPACE, true, 100), 100)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], 2, 900f, 250f)
        val up = m.process(key(LinuxKey.SPACE, false, 200), 200)
        assertEquals(TouchEngine.ACTION_UP, up.single().action)
    }

    @Test
    fun buttons_twoAtOnceAndReleaseOrder() {
        val (_, m) = mapper()
        m.setCursorMode(false, 0) // mặc định là con trỏ — ở đó BTN_LEFT bấm tại con trỏ, không phải điểm gán
        val d1 = m.process(key(LinuxKey.SPACE, true, 100), 100)
        assertEquals(TouchEngine.ACTION_DOWN, d1.single().action)
        val d2 = m.process(btn(LinuxKey.BTN_LEFT, true, 110), 110)
        assertEquals(TouchEngine.ACTION_POINTER_DOWN or (1 shl TouchEngine.ACTION_POINTER_INDEX_SHIFT), d2.single().action)
        // Nhả Space (id 2, chỉ số 0) khi còn chuột trái.
        val u1 = m.process(key(LinuxKey.SPACE, false, 120), 120)
        assertEquals(TouchEngine.ACTION_POINTER_UP or (0 shl TouchEngine.ACTION_POINTER_INDEX_SHIFT), u1.single().action)
        val u2 = m.process(btn(LinuxKey.BTN_LEFT, false, 130), 130)
        assertEquals(TouchEngine.ACTION_UP, u2.single().action)
    }

    @Test
    fun button_repeatPressIgnored() {
        val (_, m) = mapper()
        m.process(key(LinuxKey.SPACE, true, 100), 100)
        assertTrue(m.process(key(LinuxKey.SPACE, true, 110), 110).isEmpty())
    }

    @Test
    fun unknownKeyAndWheel_ignored() {
        val (_, m) = mapper()
        assertTrue(m.process(key(LinuxKey.Q, true, 100), 100).isEmpty())
        assertTrue(m.process(InputEvent.Wheel(1, 0, 100), 100).isEmpty())
    }

    @Test
    fun joystickAndCameraAndButton_simultaneous() {
        val (engine, m) = mapper()
        m.setCursorMode(false, 0) // mặc định là con trỏ — test này cần camera + BTN_LEFT ở điểm gán
        m.process(key(LinuxKey.W, true, 100), 100) // joystick
        m.process(move(5, 0, 110), 110) // camera
        m.process(btn(LinuxKey.BTN_LEFT, true, 120), 120) // bắn
        assertEquals(3, engine.count)
        // Nhả nút bắn không ảnh hưởng joystick/camera.
        val up = m.process(btn(LinuxKey.BTN_LEFT, false, 130), 130)
        assertEquals(TouchEngine.ACTION_POINTER_UP, up.single().action and 0xFF)
        assertEquals(2, engine.count)
        assertTrue(engine.isDown(TouchEngine.POINTER_JOYSTICK))
        assertTrue(engine.isDown(TouchEngine.POINTER_CAMERA))
    }

    // ── Vuốt (SWIPE) ──

    private fun swipeMapper(): Pair<TouchPointerSet, TouchMapper> {
        val engine = TouchPointerSet()
        val swipeLayout = layout.copy(
            swipes = mapOf(
                // Q: vuốt ngang (300, 250) -> (700, 250) trong 400ms.
                LinuxKey.Q to SwipeSpec(0.3f, 0.5f, 0.7f, 0.5f, 400L),
            ),
        )
        return engine to TouchMapper(engine, swipeLayout, 1000, 500)
    }

    @Test
    fun swipe_pressStartsAtStartPoint() {
        val (_, m) = swipeMapper()
        val down = m.process(key(LinuxKey.Q, true, 100), 100)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], 2, 300f, 250f)
    }

    @Test
    fun swipe_tickInterpolatesThenReleases() {
        val (engine, m) = swipeMapper()
        m.process(key(LinuxKey.Q, true, 100), 100)
        // Nửa thời gian (200ms): chưa nhả, vị trí giữa đường.
        assertTrue(m.tick(300).isEmpty())
        val mid = engine.flushMove(300)
        assertEquals(TouchEngine.ACTION_MOVE, mid?.action)
        assertPoint(mid, 2, 500f, 250f)
        // Hết 400ms: đặt đúng điểm cuối, GIỮ 1 tick rồi mới UP (không nhả ngay).
        assertTrue(m.tick(500).isEmpty())
        val end = engine.flushMove(500)
        assertEquals(TouchEngine.ACTION_MOVE, end?.action)
        assertPoint(end, 2, 700f, 250f)
        // Tick sau mới nhả ngón.
        val up = m.tick(600)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
        assertEquals(0, engine.count)
        assertTrue(m.tick(700).isEmpty())
    }

    @Test
    fun swipe_tickMinStep8ms() {
        val (engine, m) = swipeMapper()
        m.process(key(LinuxKey.Q, true, 100), 100)
        // Nội suy ở 105ms (f = 5/400 -> x = 305).
        m.tick(105)
        val mv1 = engine.flushMove(105)
        assertEquals(TouchEngine.ACTION_MOVE, mv1?.action)
        assertPoint(mv1, 2, 305f, 250f)
        // Tick sau chỉ 3ms: bị bỏ qua (chưa đủ bước tối thiểu 8ms), không có MOVE mới.
        m.tick(108)
        assertEquals(null, engine.flushMove(108))
        // Đủ 8ms từ lần nội suy trước (120ms): nội suy tiếp (f = 20/400 -> x = 320).
        m.tick(120)
        val mv2 = engine.flushMove(120)
        assertEquals(TouchEngine.ACTION_MOVE, mv2?.action)
        assertPoint(mv2, 2, 320f, 250f)
    }

    @Test
    fun swipe_releaseEarlyCancels() {
        val (engine, m) = swipeMapper()
        m.process(key(LinuxKey.Q, true, 100), 100)
        m.tick(200) // mới đi được 1/4 đường
        val up = m.process(key(LinuxKey.Q, false, 200), 200)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
        assertEquals(0, engine.count)
        assertTrue(m.tick(600).isEmpty()) // không còn vuốt để chạy tiếp
    }

    @Test
    fun swipe_repeatPressIgnored() {
        val (_, m) = swipeMapper()
        m.process(key(LinuxKey.Q, true, 100), 100)
        assertTrue(m.process(key(LinuxKey.Q, true, 110), 110).isEmpty())
    }

    @Test
    fun swipe_andButton_sharePointerPool() {
        val (engine, m) = swipeMapper()
        m.process(key(LinuxKey.Q, true, 100), 100) // vuốt chiếm id 2
        val down = m.process(key(LinuxKey.SPACE, true, 110), 110) // nút lấy id 3
        assertEquals(TouchEngine.ACTION_POINTER_DOWN or (1 shl TouchEngine.ACTION_POINTER_INDEX_SHIFT), down.single().action)
        assertPoint(down.single(), 3, 900f, 250f)
        assertEquals(2, engine.count)
    }

    // ── Chế độ con trỏ (bấm giao diện game bằng chuột) ──

    @Test
    fun defaultMode_isCursor() {
        val (engine, m) = mapper()
        assertTrue(m.cursorMode) // mặc định là con trỏ (giống GG Mouse Pro)
        // Di chuột không đè ngón nào, chỉ di chuyển con trỏ.
        assertTrue(m.process(move(10, 5, 100), 100).isEmpty())
        assertEquals(0, engine.count)
        val (rx, ry) = m.cursorRatio()
        // Con trỏ khởi tạo ở tâm vùng camera (500, 250), cộng (10, 5).
        assertEquals(0.51f, rx, 0.001f)
        assertEquals(0.51f, ry, 0.001f)
    }

    @Test
    fun cursorMode_mouseMoveMovesCursorNoFinger() {
        val (engine, m) = mapper()
        assertTrue(m.setCursorMode(true, 100).isEmpty())
        assertTrue(m.process(move(10, 5, 110), 110).isEmpty())
        assertEquals(0, engine.count) // không đè ngón nào
        val (rx, ry) = m.cursorRatio()
        // Con trỏ khởi tạo ở tâm vùng camera (500, 250), cộng (10, 5).
        assertEquals(0.51f, rx, 0.001f)
        assertEquals(0.51f, ry, 0.001f)
    }

    @Test
    fun cursorMode_leftClickTapsAtCursorAndDragFollows() {
        val (engine, m) = mapper()
        m.setCursorMode(true, 100)
        m.process(move(10, 0, 110), 110) // con trỏ ở (510, 250)
        val down = m.process(btn(LinuxKey.BTN_LEFT, true, 120), 120)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], TouchEngine.POINTER_CAMERA, 510f, 250f)
        // Kéo chuột trong lúc giữ nút: ngón đi theo con trỏ.
        m.process(move(20, 0, 130), 130)
        val mv = engine.flushMove(130)
        assertEquals(TouchEngine.ACTION_MOVE, mv?.action)
        assertPoint(mv, TouchEngine.POINTER_CAMERA, 530f, 250f)
        val up = m.process(btn(LinuxKey.BTN_LEFT, false, 140), 140)
        assertEquals(TouchEngine.ACTION_UP, up.single().action)
        assertEquals(0, engine.count)
    }

    @Test
    fun cursorMode_toggleReleasesCameraFinger() {
        val (engine, m) = mapper()
        m.setCursorMode(false, 0) // về nhìn tự do trước để có ngón camera mà nhả
        m.process(move(10, 0, 100), 100) // ngón camera đang đè
        assertTrue(engine.isDown(TouchEngine.POINTER_CAMERA))
        val specs = m.setCursorMode(true, 110)
        assertEquals(1, specs.size)
        assertEquals(TouchEngine.ACTION_UP, specs[0].action)
        assertEquals(0, engine.count)
        // Tắt chế độ con trỏ: chuột lại kéo camera như cũ.
        m.setCursorMode(false, 120)
        val down = m.process(move(10, 0, 130), 130)
        assertEquals(TouchEngine.ACTION_DOWN, down.single().action)
    }

    @Test
    fun cursorMode_toggleReleasesHeldMouseButton() {
        val (engine, m) = mapper()
        m.setCursorMode(false, 0) // về nhìn tự do trước để có ngón camera + ngón bắn mà nhả
        // Giữ chuột trái để bắn (ngón nút id 2) + ngón camera đang đè.
        m.process(move(10, 0, 100), 100)
        m.process(btn(LinuxKey.BTN_LEFT, true, 105), 105)
        assertEquals(2, engine.count)
        // Bấm F9: phải nhả cả ngón camera lẫn ngón bắn.
        val specs = m.setCursorMode(true, 110)
        assertEquals(2, specs.size)
        assertEquals(0, engine.count)
        // Nhả chuột trái sau khi đã đổi chế độ: không sinh sự kiện, không kẹt ngón.
        assertTrue(m.process(btn(LinuxKey.BTN_LEFT, false, 120), 120).isEmpty())
        assertEquals(0, engine.count)
    }

    @Test
    fun cursorMode_toggleKeepsJoystick() {
        val (engine, m) = mapper()
        m.process(key(LinuxKey.W, true, 100), 100) // joystick id 0
        assertTrue(engine.isDown(TouchEngine.POINTER_JOYSTICK))
        // Đổi chế độ: joystick KHÔNG bị nhả (WASD dùng được ở cả 2 chế độ).
        assertTrue(m.setCursorMode(true, 110).isEmpty())
        assertTrue(engine.isDown(TouchEngine.POINTER_JOYSTICK))
        // Nhả W ở chế độ con trỏ: joystick nhả bình thường.
        val up = m.process(key(LinuxKey.W, false, 120), 120)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
    }

    @Test
    fun cursorMode_buttonKeysStillWork() {
        val (_, m) = mapper()
        m.setCursorMode(true, 100)
        // Phím SPACE vẫn chạm điểm đã gán (900, 250), không bị con trỏ ảnh hưởng.
        val down = m.process(key(LinuxKey.SPACE, true, 110), 110)
        assertPoint(down.single(), 2, 900f, 250f)
    }

    @Test
    fun cursorMode_cursorClampedToScreen() {
        val (_, m) = mapper()
        m.setCursorMode(true, 100)
        m.process(move(-100000, 100000, 110), 110)
        val (rx, ry) = m.cursorRatio()
        assertEquals(0f, rx, 0.001f)
        assertEquals(1f, ry, 0.001f)
    }

    // ── Build-9: camera tròn ──

    @Test
    fun camera_circle_diagonalOutsideCircleWraps() {
        val (engine, m) = mapper()
        m.setCursorMode(false, 0)
        // Di chéo tới (690, 440): trong hình chữ nhật cũ (x<700, y<450) nhưng
        // NGOÀI hình tròn (cách tâm 268px > bán kính 200px) -> lần di đầu
        // bị kẹp vào biên tròn (641.42, 391.42).
        m.process(move(190, 190, 100), 100)
        val mv = engine.flushMove(100)
        assertPoint(mv, TouchEngine.POINTER_CAMERA, 641.42f, 391.42f)
        // Di tiếp ra ngoài: UP + hẹn DOWN ở điểm đối xứng qua tâm
        // (hướng (0.707, 0.707) -> đối xứng (500-134.35, 250-134.35)).
        val specs = m.process(move(10, 10, 110), 110)
        assertEquals(1, specs.size)
        assertEquals(TouchEngine.ACTION_UP, specs[0].action)
        val down = m.tick(110 + Config.CAMERA_REPRESS_DELAY_MS)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], TouchEngine.POINTER_CAMERA, 365.65f, 115.65f)
    }

    // ── Build-9: đầm tâm (EMA) + tốc độ con trỏ ──

    @Test
    fun damping_level1_noSmoothing() {
        val (_, m) = mapper() // damp mặc định 1
        m.process(move(10, 10, 100), 100)
        val (rx, ry) = m.cursorRatio()
        assertEquals(0.51f, rx, 0.001f)
        assertEquals(0.52f, ry, 0.001f)
    }

    @Test
    fun damping_level2_halvesFirstDeltaThenAccumulates() {
        val engine = TouchPointerSet()
        val m = TouchMapper(engine, layout.copy(dampX = 2, dampY = 2), 1000, 500)
        m.process(move(10, 0, 100), 100)
        // EMA alpha=0.5: delta mượt = 5 -> con trỏ (505, 250).
        assertEquals(0.505f, m.cursorRatio().first, 0.001f)
        m.process(move(10, 0, 110), 110)
        // Lần 2: mượt = 5 + (10-5)*0.5 = 7.5 -> con trỏ 512.5.
        assertEquals(0.5125f, m.cursorRatio().first, 0.001f)
    }

    @Test
    fun damping_separateAxes() {
        val engine = TouchPointerSet()
        val m = TouchMapper(engine, layout.copy(dampX = 1, dampY = 4), 1000, 500)
        m.process(move(10, 8, 100), 100)
        // X không làm mượt (10), Y alpha=0.25 -> 2.
        val (rx, ry) = m.cursorRatio()
        assertEquals(0.51f, rx, 0.001f)
        assertEquals(0.504f, ry, 0.001f)
    }

    @Test
    fun cursorSpeed_scalesCursorMovement() {
        val engine = TouchPointerSet()
        val m = TouchMapper(engine, layout.copy(cursorSpeed = 2f), 1000, 500)
        m.process(move(10, 5, 100), 100)
        val (rx, ry) = m.cursorRatio()
        assertEquals(0.52f, rx, 0.001f)
        assertEquals(0.52f, ry, 0.001f)
    }

    @Test
    fun cursorMode_ignoresSensitivity() {
        // Chế độ con trỏ dùng cursorSpeed, KHÔNG dùng sensX/sensY (độ nhạy
        // chỉ dùng cho camera ở chế độ nhìn tự do).
        val engine = TouchPointerSet()
        val m = TouchMapper(engine, layout.copy(sensX = 3f, sensY = 3f, cursorSpeed = 1f), 1000, 500)
        m.process(move(10, 5, 100), 100)
        val (rx, ry) = m.cursorRatio()
        assertEquals(0.51f, rx, 0.001f)
        assertEquals(0.51f, ry, 0.001f)
    }

    // ── Build-9: tự động tap / thả tap / macro ──

    private fun autoTapMapper(): Pair<TouchPointerSet, TouchMapper> {
        val engine = TouchPointerSet()
        val l = layout.copy(
            autoTaps = mapOf(
                LinuxKey.Q to AutoTapSpec(0.5f, 0.5f, 100L), // (500, 250) mỗi 100ms
            ),
        )
        return engine to TouchMapper(engine, l, 1000, 500)
    }

    @Test
    fun autoTap_pressTapsImmediatelyAndRepeatsWhileHeld() {
        val (engine, m) = autoTapMapper()
        val down = m.process(key(LinuxKey.Q, true, 100), 100)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], 2, 500f, 250f)
        // Hết 40ms giữ thì nhả.
        val up = m.tick(140)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
        // Đủ 100ms từ lần tap trước thì tap lại.
        assertTrue(m.tick(199).isEmpty())
        val down2 = m.tick(200)
        assertEquals(1, down2.size)
        assertEquals(TouchEngine.ACTION_DOWN, down2[0].action)
        assertEquals(1, engine.count)
        // Nhả phím thì dừng lặp (tap đang giữ vẫn nhả đúng hẹn).
        assertTrue(m.process(key(LinuxKey.Q, false, 210), 210).isEmpty())
        assertEquals(1, m.tick(240).size) // nhả lần tap thứ 2
        assertTrue(m.tick(300).isEmpty())
        assertTrue(m.tick(400).isEmpty())
        assertEquals(0, engine.count)
    }

    private fun releaseTapMapper(): Pair<TouchPointerSet, TouchMapper> {
        val engine = TouchPointerSet()
        val l = layout.copy(releaseTaps = mapOf(LinuxKey.Q to (0.5f to 0.5f)))
        return engine to TouchMapper(engine, l, 1000, 500)
    }

    @Test
    fun releaseTap_tapsOnlyOnRelease() {
        val (engine, m) = releaseTapMapper()
        assertTrue(m.process(key(LinuxKey.Q, true, 100), 100).isEmpty()) // bấm: không làm gì
        assertEquals(0, engine.count)
        val down = m.process(key(LinuxKey.Q, false, 200), 200)
        assertEquals(1, down.size)
        assertEquals(TouchEngine.ACTION_DOWN, down[0].action)
        assertPoint(down[0], 2, 500f, 250f)
        val up = m.tick(240)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
    }

    @Test
    fun releaseTap_strayReleaseIgnored() {
        val (_, m) = releaseTapMapper()
        assertTrue(m.process(key(LinuxKey.Q, false, 100), 100).isEmpty()) // nhả mà không bấm trước
    }

    private fun macroMapper(): Pair<TouchPointerSet, TouchMapper> {
        val engine = TouchPointerSet()
        val l = layout.copy(
            macros = mapOf(
                LinuxKey.Q to listOf(
                    MacroStep(MacroStepKind.TAP, 0.5f, 0.5f),
                    MacroStep(MacroStepKind.WAIT, ms = 100L),
                    MacroStep(MacroStepKind.TAP, 0.8f, 0.8f),
                ),
            ),
        )
        return engine to TouchMapper(engine, l, 1000, 500)
    }

    @Test
    fun macro_tapWaitTap_playsInOrder() {
        val (engine, m) = macroMapper()
        val down = m.process(key(LinuxKey.Q, true, 100), 100)
        assertEquals(1, down.size)
        assertPoint(down[0], 2, 500f, 250f) // bước 1: tap (500, 250)
        // Hết 40ms giữ thì nhả, rồi chờ 100ms (bắt đầu tính từ lúc nhả = 140).
        val up = m.tick(140)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
        assertTrue(m.tick(239).isEmpty()) // đang chờ
        val down2 = m.tick(240)
        assertEquals(1, down2.size)
        assertPoint(down2[0], 2, 800f, 400f) // bước 3: tap (800, 400)
        val up2 = m.tick(280)
        assertEquals(1, up2.size)
        assertEquals(TouchEngine.ACTION_UP, up2[0].action)
        assertTrue(m.tick(300).isEmpty()) // macro xong
        assertEquals(0, engine.count)
    }

    @Test
    fun macro_releaseCancelsMidWay() {
        val (engine, m) = macroMapper()
        m.process(key(LinuxKey.Q, true, 100), 100)
        val up = m.process(key(LinuxKey.Q, false, 110), 110)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
        assertTrue(m.tick(200).isEmpty()) // không chạy tiếp bước sau
        assertEquals(0, engine.count)
    }

    @Test
    fun macro_pressAgainRestartsFromBeginning() {
        val (_, m) = macroMapper()
        m.process(key(LinuxKey.Q, true, 100), 100)
        m.tick(140) // nhả tap đầu, đang chờ
        // Bấm lại trong lúc đang chạy: chạy lại từ đầu (tap đầu ngay).
        val down = m.process(key(LinuxKey.Q, true, 150), 150)
        assertEquals(1, down.size)
        assertPoint(down[0], 2, 500f, 250f)
    }
}

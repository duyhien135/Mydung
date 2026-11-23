package com.example.mydung.server

import java.util.UUID

/** Loại phần tử trên lớp phủ chỉnh nút. Thêm loại mới KHÔNG được đổi tên loại cũ (JSON đã lưu). */
enum class ElementType {
    JOYSTICK, CAMERA, BUTTON, SWIPE,
    // Build-9 (như K7XD): AUTO_TAP = giữ phím thì tap lặp; RELEASE_TAP = tap
    // khi nhả phím; FREE_LOOK = bấm phím đổi chế độ nhìn tự do/con trỏ;
    // MACRO = 1 phím chạy chuỗi bước tap/chờ/vuốt.
    AUTO_TAP, RELEASE_TAP, FREE_LOOK, MACRO,
}

/** Loại bước trong Macro: TAP = tap 1 cái, WAIT = chờ ms, SWIPE = vuốt. */
enum class MacroStepKind { TAP, WAIT, SWIPE }

/**
 * Một bước Macro. x/y = điểm bắt đầu (tap/vuốt), x2/y2 = điểm kết thúc
 * (vuốt), ms = thời gian chờ (WAIT) hoặc thời lượng vuốt (SWIPE).
 * Tọa độ theo tỉ lệ 0..1.
 */
data class MacroStep(
    val kind: MacroStepKind = MacroStepKind.TAP,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val x2: Float = 0.5f,
    val y2: Float = 0.5f,
    val ms: Long = 0L,
)

/**
 * 1 phần tử trong profile. Tọa độ theo TỈ LỆ màn hình ngang (0.0..1.0).
 * size: joystick = bán kính; camera = nửa rộng vùng (nửa cao suy ra để vùng
 * vuông theo pixel); nút = bán kính hiển thị trên lớp phủ (tiêm dùng điểm tâm);
 * vuốt = bán kính 2 đầu mút.
 * SWIPE thêm điểm cuối (x2, y2) và thời gian vuốt (durationMs): bấm phím thì
 * ngón chạm ở (x, y), trượt tới (x2, y2) trong durationMs rồi nhả.
 * AUTO_TAP thêm intervalMs (khoảng tap lặp); MACRO thêm steps (chuỗi bước).
 */
data class ProfileElement(
    val type: ElementType,
    val x: Float,
    val y: Float,
    val size: Float,
    val keyCode: Int?, // BUTTON/SWIPE: mã Linux (phím hoặc nút chuột); joystick/camera: null
    val name: String,
    val enabled: Boolean = true,
    val x2: Float? = null, // SWIPE: điểm cuối (tỉ lệ); null = chưa đặt (dùng mặc định)
    val y2: Float? = null, // SWIPE: điểm cuối (tỉ lệ)
    val durationMs: Long = 400, // SWIPE: thời gian vuốt (ms), kẹp 50..3000 lúc parse
    // Build-9: AUTO_TAP — khoảng tap lặp (ms); MACRO — chuỗi bước.
    val intervalMs: Long = Config.AUTO_TAP_INTERVAL_DEFAULT_MS,
    val steps: List<MacroStep> = emptyList(),
)

/** Profile: 1 bộ bố cục + cài đặt cho 1 game. */
data class Profile(
    val id: String,
    val name: String,
    val packageName: String?, // game gán (null = chưa gán)
    val schemaVersion: Int,
    // Build-11: độ nhạy thang UI 1..100 (trước là hệ số Float 0.1..5.0).
    // toLayout() đổi sang hệ số engine bằng Config.sensFactor().
    val sensX: Int,
    val sensY: Int,
    val mapToggleKey: Int,
    val elements: List<ProfileElement>,
    // Phím chuyển chế độ con trỏ <-> nhìn tự do (mặc định F9). Có giá trị
    // mặc định để code cũ tạo Profile không cần sửa.
    val cursorToggleKey: Int = Config.CURSOR_TOGGLE_KEY,
    // Build-9: Đầm tâm X/Y (build-11: mức 1..100, 1 = không làm mượt) +
    // phím ẩn/hiện lớp phủ (mặc định F1 từ build-11, trước là F10).
    // Mặc định để profile cũ vẫn tạo được.
    val dampX: Int = Config.DAMP_DEFAULT,
    val dampY: Int = Config.DAMP_DEFAULT,
    val overlayToggleKey: Int = Config.OVERLAY_TOGGLE_KEY,
    // Build-11: giới hạn vùng con trỏ (0 = TẮT, 1 = TOÀN MÀN,
    // 2 = MÀN PHẢI) + phím bật/tắt chuột (0 = chưa gán).
    val cursorRegion: Int = Config.CURSOR_REGION_DEFAULT,
    val mouseToggleKey: Int = Config.MOUSE_TOGGLE_KEY_UNBOUND,
) {
    /** Chuyển profile thành bố cục cho TouchMapper. Thiếu joystick/camera thì dùng mặc định GĐ4. */
    fun toLayout(displayW: Int, displayH: Int): TouchLayout {
        val joy = elements.firstOrNull { it.enabled && it.type == ElementType.JOYSTICK }
        val cam = elements.firstOrNull { it.enabled && it.type == ElementType.CAMERA }
        // Build-12: 1 phím gán được cho NHIỀU phần tử — cộng dồn vào danh
        // sách, KHÔNG ghi đè (trước đây phần tử sau ghi đè phần tử trước
        // cùng phím nên chỉ 1 phần tử kích hoạt).
        val buttons = HashMap<Int, MutableList<Pair<Float, Float>>>()
        val swipes = HashMap<Int, MutableList<SwipeSpec>>()
        val autoTaps = HashMap<Int, MutableList<AutoTapSpec>>()
        val releaseTaps = HashMap<Int, MutableList<Pair<Float, Float>>>()
        val freeLookKeys = HashSet<Int>()
        val macros = HashMap<Int, MutableList<List<MacroStep>>>()
        for (e in elements) {
            if (!e.enabled || e.keyCode == null) continue
            when (e.type) {
                ElementType.BUTTON -> buttons.getOrPut(e.keyCode) { ArrayList() }.add(e.x to e.y)
                ElementType.SWIPE -> swipes.getOrPut(e.keyCode) { ArrayList() }.add(
                    SwipeSpec(
                        e.x, e.y,
                        e.x2 ?: (e.x + 0.15f).coerceIn(0f, 1f),
                        e.y2 ?: e.y,
                        e.durationMs.coerceIn(50L, 3000L),
                    ),
                )
                ElementType.AUTO_TAP -> autoTaps.getOrPut(e.keyCode) { ArrayList() }.add(
                    AutoTapSpec(
                        e.x, e.y,
                        e.intervalMs.coerceIn(
                            Config.AUTO_TAP_INTERVAL_MIN_MS,
                            Config.AUTO_TAP_INTERVAL_MAX_MS,
                        ),
                    ),
                )
                ElementType.RELEASE_TAP -> releaseTaps.getOrPut(e.keyCode) { ArrayList() }.add(e.x to e.y)
                ElementType.FREE_LOOK -> freeLookKeys.add(e.keyCode)
                ElementType.MACRO -> {
                    if (e.steps.isNotEmpty()) macros.getOrPut(e.keyCode) { ArrayList() }.add(e.steps)
                }
                else -> { /* joystick/camera không vào các bảng này */ }
            }
        }
        return TouchLayout(
            joyX = joy?.x ?: Config.JOY_CENTER_X,
            joyY = joy?.y ?: Config.JOY_CENTER_Y,
            joyR = joy?.size ?: Config.JOY_RADIUS,
            camX = cam?.x ?: Config.CAM_CENTER_X,
            camY = cam?.y ?: Config.CAM_CENTER_Y,
            // Camera là VÙNG TRÒN (build-9): size = bán kính, kẹp 0.02..0.12
            // (profile cũ size 0.26 tự thu về 0.12 — vòng tròn bé như yêu cầu).
            camHW = (cam?.size ?: Config.CAM_HALF_W)
                .coerceIn(Config.CAM_RADIUS_MIN, Config.CAM_RADIUS_MAX),
            // Vùng camera vuông theo pixel: nửa cao = nửa rộng * tỉ lệ màn hình.
            camHH = cam?.let { it.size * displayW / displayH } ?: Config.CAM_HALF_H,
            // Build-11: độ nhạy UI 1..100 -> hệ số engine (1 -> 0.05,
            // 20 -> 1.0, 100 -> 5.0); đầm tâm kẹp 1..100.
            sensX = Config.sensFactor(sensX),
            sensY = Config.sensFactor(sensY),
            buttons = buttons,
            swipes = swipes,
            mapToggleKey = mapToggleKey,
            cursorToggleKey = cursorToggleKey,
            autoTaps = autoTaps,
            releaseTaps = releaseTaps,
            freeLookKeys = freeLookKeys,
            macros = macros,
            dampX = dampX.coerceIn(Config.DAMP_MIN, Config.DAMP_MAX),
            dampY = dampY.coerceIn(Config.DAMP_MIN, Config.DAMP_MAX),
            overlayToggleKey = overlayToggleKey,
            cursorRegion = cursorRegion.coerceIn(
                Config.CURSOR_REGION_OFF, Config.CURSOR_REGION_RIGHT,
            ),
            mouseToggleKey = mouseToggleKey,
        )
    }
}

/** Serialize/deserialize Profile <-> JSON 1 dòng (lệnh PROFILE, file lưu, xuất/nhập). */
object ProfileCodec {
    // Build-11: version 2 — độ nhạy/đầm tâm chuyển sang thang 1..100
    // (v1: nhạy Float 0.1..5.0, đầm Int 1..10). fromJson migrate v1 -> v2
    // theo công thức trong Config (nhạy: *20; đầm: 1+(mức-1)*11).
    const val CURRENT_VERSION = 2

    sealed class Result {
        data class Ok(val profile: Profile) : Result()
        data class Err(val message: String) : Result()
    }

    /** Profile mặc định làm sẵn từ bố cục GĐ4 (Free Fire bản thường, package Thái). */
    fun defaultFreeFire(): Profile = defaultFreeFire("Free Fire", "com.dts.freefireth")

    /** Profile mặc định cho Free Fire MAX (package com.dts.freefiremax). */
    fun defaultFreeFireMax(): Profile = defaultFreeFire("Free Fire MAX", "com.dts.freefiremax")

    /** Profile trống chưa gán game (vẫn dùng bố cục GĐ4 mặc định) — seed duy nhất khi chưa có profile nào. */
    fun defaultEmpty(): Profile = defaultFreeFire("Game mới", null)

    private fun defaultFreeFire(name: String, packageName: String?): Profile {
        val buttonNames = mapOf(
            LinuxKey.BTN_LEFT to "Bắn",
            LinuxKey.BTN_RIGHT to "Ngắm",
            LinuxKey.SPACE to "Nhảy",
            LinuxKey.C to "Ngồi",
            LinuxKey.R to "Nạp đạn",
            LinuxKey.LEFTSHIFT to "Chạy",
            LinuxKey.KEY_1 to "Vũ khí 1",
            LinuxKey.KEY_2 to "Vũ khí 2",
            LinuxKey.KEY_3 to "Vũ khí 3",
        )
        val elements = ArrayList<ProfileElement>()
        elements.add(
            ProfileElement(
                ElementType.JOYSTICK, Config.JOY_CENTER_X, Config.JOY_CENTER_Y,
                Config.JOY_RADIUS, null, "Joystick",
            ),
        )
        elements.add(
            ProfileElement(
                ElementType.CAMERA, Config.CAM_CENTER_X, Config.CAM_CENTER_Y,
                Config.CAM_HALF_W, null, "FPS Look",
            ),
        )
        for ((code, point) in Config.DEFAULT_BUTTONS) {
            elements.add(
                ProfileElement(
                    ElementType.BUTTON, point.first, point.second, Config.DEFAULT_BUTTON_SIZE,
                    code, buttonNames[code] ?: LinuxKey.name(code),
                ),
            )
        }
        return Profile(
            id = UUID.randomUUID().toString(),
            name = name,
            packageName = packageName,
            schemaVersion = CURRENT_VERSION,
            sensX = Config.SENS_UI_DEFAULT,
            sensY = Config.SENS_UI_DEFAULT,
            mapToggleKey = Config.MAP_TOGGLE_KEY,
            elements = elements,
        )
    }

    fun toJson(p: Profile): String = Json.stringify(toJsonValue(p))

    /** Dạng JsonValue của profile (ProfileStore dùng để ghi file). */
    fun toJsonValue(p: Profile): JsonValue.Obj {
        val elements = p.elements.map { e ->
            val fields = LinkedHashMap<String, JsonValue>()
            fields["type"] = JsonValue.Str(e.type.name)
            fields["x"] = JsonValue.Num(e.x.toDouble())
            fields["y"] = JsonValue.Num(e.y.toDouble())
            fields["size"] = JsonValue.Num(e.size.toDouble())
            if (e.keyCode != null) fields["key"] = JsonValue.Num(e.keyCode.toDouble())
            fields["name"] = JsonValue.Str(e.name)
            fields["enabled"] = JsonValue.Bool(e.enabled)
            if (e.type == ElementType.SWIPE) {
                // Chỉ vuốt mới có điểm cuối + thời gian; loại khác không ghi
                // để JSON cũ vẫn đọc được y nguyên (tương thích ngược).
                if (e.x2 != null) fields["x2"] = JsonValue.Num(e.x2.toDouble())
                if (e.y2 != null) fields["y2"] = JsonValue.Num(e.y2.toDouble())
                fields["durationMs"] = JsonValue.Num(e.durationMs.toDouble())
            }
            // Build-9: chỉ ghi trường riêng của từng loại mới (JSON cũ thiếu
            // thì parse dùng mặc định — tương thích ngược, không cần tăng version).
            if (e.type == ElementType.AUTO_TAP) {
                fields["intervalMs"] = JsonValue.Num(e.intervalMs.toDouble())
            }
            if (e.type == ElementType.MACRO) {
                fields["steps"] = JsonValue.Arr(e.steps.map { s ->
                    val sf = LinkedHashMap<String, JsonValue>()
                    sf["k"] = JsonValue.Str(s.kind.name)
                    sf["x"] = JsonValue.Num(s.x.toDouble())
                    sf["y"] = JsonValue.Num(s.y.toDouble())
                    sf["x2"] = JsonValue.Num(s.x2.toDouble())
                    sf["y2"] = JsonValue.Num(s.y2.toDouble())
                    sf["ms"] = JsonValue.Num(s.ms.toDouble())
                    JsonValue.Obj(sf)
                })
            }
            JsonValue.Obj(fields)
        }
        val root = LinkedHashMap<String, JsonValue>()
        root["version"] = JsonValue.Num(CURRENT_VERSION.toDouble())
        root["id"] = JsonValue.Str(p.id)
        root["name"] = JsonValue.Str(p.name)
        if (p.packageName != null) root["package"] = JsonValue.Str(p.packageName)
        root["sensX"] = JsonValue.Num(p.sensX.toDouble())
        root["sensY"] = JsonValue.Num(p.sensY.toDouble())
        root["toggleKey"] = JsonValue.Num(p.mapToggleKey.toDouble())
        root["cursorKey"] = JsonValue.Num(p.cursorToggleKey.toDouble())
        // Build-9: đầm tâm + phím ẩn/hiện lớp phủ (JSON cũ thiếu -> mặc định).
        root["dampX"] = JsonValue.Num(p.dampX.toDouble())
        root["dampY"] = JsonValue.Num(p.dampY.toDouble())
        root["overlayKey"] = JsonValue.Num(p.overlayToggleKey.toDouble())
        // Build-11: giới hạn vùng con trỏ + phím bật/tắt chuột
        // (JSON cũ thiếu -> TẮT / chưa gán).
        root["cursorRegion"] = JsonValue.Num(p.cursorRegion.toDouble())
        root["mouseKey"] = JsonValue.Num(p.mouseToggleKey.toDouble())
        root["elements"] = JsonValue.Arr(elements)
        return JsonValue.Obj(root)
    }

    /**
     * Parse JSON thành Profile. Lỗi nặng (không phải JSON, không phải object,
     * version mới hơn app) -> Err. Trường thiếu/sai nhẹ thì dùng mặc định hoặc
     * bỏ phần tử đó (ghi rõ trong message khi có phần tử bị bỏ).
     */
    fun fromJson(text: String): Result {
        val root = Json.parse(text)?.obj()
            ?: return Result.Err("Không phải JSON object hợp lệ")
        val version = root["version"]?.int() ?: 1
        if (version > CURRENT_VERSION) {
            return Result.Err("Profile phiên bản $version mới hơn app (hỗ trợ tới $CURRENT_VERSION)")
        }
        if (version < 1) return Result.Err("Phiên bản schema không hợp lệ: $version")

        val id = root["id"]?.str()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val name = root["name"]?.str()?.takeIf { it.isNotBlank() } ?: "Profile"
        val pkg = root["package"]?.str()?.takeIf { it.isNotBlank() }
        // Build-11: v1 dùng thang cũ (nhạy Float 0.1..5.0, đầm Int 1..10)
        // -> migrate sang thang 1..100; v2 đọc thẳng thang mới.
        val sensX = if (version < 2) {
            root["sensX"]?.num()?.toFloat()?.takeIf { it.isFinite() }
                ?.let { Config.sensUiValue(it) } ?: Config.SENS_UI_DEFAULT
        } else {
            root["sensX"]?.int()?.coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX)
                ?: Config.SENS_UI_DEFAULT
        }
        val sensY = if (version < 2) {
            root["sensY"]?.num()?.toFloat()?.takeIf { it.isFinite() }
                ?.let { Config.sensUiValue(it) } ?: Config.SENS_UI_DEFAULT
        } else {
            root["sensY"]?.int()?.coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX)
                ?: Config.SENS_UI_DEFAULT
        }
        val toggleKey = root["toggleKey"]?.int() ?: Config.MAP_TOGGLE_KEY
        // JSON cũ (trước khi có chế độ con trỏ) thiếu cursorKey -> mặc định F9.
        val cursorKey = root["cursorKey"]?.int() ?: Config.CURSOR_TOGGLE_KEY
        // Build-9: JSON cũ thiếu dampX/dampY/overlayKey -> mặc định.
        // Đầm tâm v1 (1..10) migrate sang 1..100.
        val dampX = if (version < 2) {
            root["dampX"]?.int()?.let { Config.dampUiValue(it) } ?: Config.DAMP_DEFAULT
        } else {
            root["dampX"]?.int()?.coerceIn(Config.DAMP_MIN, Config.DAMP_MAX)
                ?: Config.DAMP_DEFAULT
        }
        val dampY = if (version < 2) {
            root["dampY"]?.int()?.let { Config.dampUiValue(it) } ?: Config.DAMP_DEFAULT
        } else {
            root["dampY"]?.int()?.coerceIn(Config.DAMP_MIN, Config.DAMP_MAX)
                ?: Config.DAMP_DEFAULT
        }
        // JSON cũ thiếu overlayKey -> mặc định F1 (build-11); profile đã
        // lưu F10 giữ nguyên F10 (không đè).
        val overlayKey = root["overlayKey"]?.int() ?: Config.OVERLAY_TOGGLE_KEY
        // Build-11: JSON cũ thiếu cursorRegion/mouseKey -> TẮT / chưa gán.
        val cursorRegion = root["cursorRegion"]?.int()
            ?.coerceIn(Config.CURSOR_REGION_OFF, Config.CURSOR_REGION_RIGHT)
            ?: Config.CURSOR_REGION_DEFAULT
        val mouseKey = root["mouseKey"]?.int() ?: Config.MOUSE_TOGGLE_KEY_UNBOUND

        val elements = ArrayList<ProfileElement>()
        val rawElements = root["elements"]?.arr() ?: emptyList()
        for (raw in rawElements) {
            val obj = raw.obj() ?: continue
            val type = try {
                ElementType.valueOf(obj["type"]?.str() ?: "")
            } catch (_: IllegalArgumentException) {
                continue // loại lạ: bỏ phần tử
            }
            val x = obj["x"]?.num()?.toFloat()?.takeIf { it.isFinite() }
            val y = obj["y"]?.num()?.toFloat()?.takeIf { it.isFinite() }
            if (x == null || y == null) continue // thiếu tọa độ: bỏ phần tử
            val size = obj["size"]?.num()?.toFloat()?.takeIf { it.isFinite() }
                ?.coerceIn(0.01f, 0.5f) ?: Config.DEFAULT_BUTTON_SIZE
            val key = obj["key"]?.int()
            val elName = obj["name"]?.str()?.takeIf { it.isNotBlank() }
                ?: when (type) {
                    ElementType.JOYSTICK -> "Joystick"
                    ElementType.CAMERA -> "FPS Look"
                    ElementType.BUTTON -> "Nút"
                    ElementType.SWIPE -> "Vuốt"
                    ElementType.AUTO_TAP -> "Tự động Tap"
                    ElementType.RELEASE_TAP -> "Thả Tap"
                    ElementType.FREE_LOOK -> "Xoay tự do"
                    ElementType.MACRO -> "Macro"
                }
            val enabled = obj["enabled"]?.bool() ?: true
            // Điểm cuối + thời gian chỉ có ý nghĩa với SWIPE; JSON cũ thiếu
            // thì dùng mặc định (vuốt ngang sang phải 15% chiều rộng, 400ms).
            val x2 = if (type == ElementType.SWIPE) {
                obj["x2"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            } else null
            val y2 = if (type == ElementType.SWIPE) {
                obj["y2"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            } else null
            val durationMs = if (type == ElementType.SWIPE) {
                obj["durationMs"]?.int()?.toLong()?.coerceIn(50L, 3000L) ?: 400L
            } else 400L
            // Build-9: khoảng tap lặp (AUTO_TAP) + chuỗi bước (MACRO).
            // Bước hỏng thì bỏ bước đó, không bỏ cả phần tử.
            val intervalMs = if (type == ElementType.AUTO_TAP) {
                obj["intervalMs"]?.int()?.toLong()?.coerceIn(
                    Config.AUTO_TAP_INTERVAL_MIN_MS,
                    Config.AUTO_TAP_INTERVAL_MAX_MS,
                ) ?: Config.AUTO_TAP_INTERVAL_DEFAULT_MS
            } else Config.AUTO_TAP_INTERVAL_DEFAULT_MS
            val steps = if (type == ElementType.MACRO) {
                (obj["steps"]?.arr() ?: emptyList()).mapNotNull { rawStep ->
                    val s = rawStep.obj() ?: return@mapNotNull null
                    val kind = try {
                        MacroStepKind.valueOf(s["k"]?.str() ?: "")
                    } catch (_: IllegalArgumentException) {
                        return@mapNotNull null
                    }
                    val sx = s["x"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                        ?: 0.5f
                    val sy = s["y"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                        ?: 0.5f
                    val sx2 = s["x2"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                        ?: sx
                    val sy2 = s["y2"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                        ?: sy
                    val sms = when (kind) {
                        MacroStepKind.WAIT -> s["ms"]?.int()?.toLong()
                            ?.coerceIn(0L, Config.MACRO_WAIT_MAX_MS) ?: 0L
                        MacroStepKind.SWIPE -> s["ms"]?.int()?.toLong()
                            ?.coerceIn(50L, 3000L) ?: 400L
                        MacroStepKind.TAP -> 0L
                    }
                    MacroStep(kind, sx, sy, sx2, sy2, sms)
                }
            } else emptyList()
            elements.add(
                ProfileElement(
                    type, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), size, key, elName, enabled,
                    x2, y2, durationMs, intervalMs, steps,
                ),
            )
        }
        val profile = Profile(
            id, name, pkg, CURRENT_VERSION, sensX, sensY, toggleKey, elements, cursorKey,
            dampX, dampY, overlayKey, cursorRegion, mouseKey,
        )
        return Result.Ok(profile)
    }
}

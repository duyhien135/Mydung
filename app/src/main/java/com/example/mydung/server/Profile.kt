package com.example.mydung.server

import java.util.UUID

/** Loại phần tử trên lớp phủ chỉnh nút. */
enum class ElementType { JOYSTICK, CAMERA, BUTTON, SWIPE }

/**
 * 1 phần tử trong profile. Tọa độ theo TỈ LỆ màn hình ngang (0.0..1.0).
 * size: joystick = bán kính; camera = nửa rộng vùng (nửa cao suy ra để vùng
 * vuông theo pixel); nút = bán kính hiển thị trên lớp phủ (tiêm dùng điểm tâm);
 * vuốt = bán kính 2 đầu mút.
 * SWIPE thêm điểm cuối (x2, y2) và thời gian vuốt (durationMs): bấm phím thì
 * ngón chạm ở (x, y), trượt tới (x2, y2) trong durationMs rồi nhả.
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
)

/** Profile: 1 bộ bố cục + cài đặt cho 1 game. */
data class Profile(
    val id: String,
    val name: String,
    val packageName: String?, // game gán (null = chưa gán)
    val schemaVersion: Int,
    val sensX: Float,
    val sensY: Float,
    val mapToggleKey: Int,
    val elements: List<ProfileElement>,
    // Phím chuyển chế độ con trỏ <-> nhìn tự do (mặc định F9). Có giá trị
    // mặc định để code cũ tạo Profile không cần sửa.
    val cursorToggleKey: Int = Config.CURSOR_TOGGLE_KEY,
) {
    /** Chuyển profile thành bố cục cho TouchMapper. Thiếu joystick/camera thì dùng mặc định GĐ4. */
    fun toLayout(displayW: Int, displayH: Int): TouchLayout {
        val joy = elements.firstOrNull { it.enabled && it.type == ElementType.JOYSTICK }
        val cam = elements.firstOrNull { it.enabled && it.type == ElementType.CAMERA }
        val buttons = HashMap<Int, Pair<Float, Float>>()
        val swipes = HashMap<Int, SwipeSpec>()
        for (e in elements) {
            if (!e.enabled || e.keyCode == null) continue
            when (e.type) {
                ElementType.BUTTON -> buttons[e.keyCode] = e.x to e.y
                ElementType.SWIPE -> swipes[e.keyCode] = SwipeSpec(
                    e.x, e.y,
                    e.x2 ?: (e.x + 0.15f).coerceIn(0f, 1f),
                    e.y2 ?: e.y,
                    e.durationMs.coerceIn(50L, 3000L),
                )
                else -> { /* joystick/camera không vào 2 bảng này */ }
            }
        }
        return TouchLayout(
            joyX = joy?.x ?: Config.JOY_CENTER_X,
            joyY = joy?.y ?: Config.JOY_CENTER_Y,
            joyR = joy?.size ?: Config.JOY_RADIUS,
            camX = cam?.x ?: Config.CAM_CENTER_X,
            camY = cam?.y ?: Config.CAM_CENTER_Y,
            camHW = cam?.size ?: Config.CAM_HALF_W,
            // Vùng camera vuông theo pixel: nửa cao = nửa rộng * tỉ lệ màn hình.
            camHH = cam?.let { it.size * displayW / displayH } ?: Config.CAM_HALF_H,
            sensX = sensX,
            sensY = sensY,
            buttons = buttons,
            swipes = swipes,
            mapToggleKey = mapToggleKey,
            cursorToggleKey = cursorToggleKey,
        )
    }
}

/** Serialize/deserialize Profile <-> JSON 1 dòng (lệnh PROFILE, file lưu, xuất/nhập). */
object ProfileCodec {
    const val CURRENT_VERSION = 1

    sealed class Result {
        data class Ok(val profile: Profile) : Result()
        data class Err(val message: String) : Result()
    }

    /** Profile mặc định làm sẵn từ bố cục GĐ4 (Free Fire bản thường, package Thái). */
    fun defaultFreeFire(): Profile = defaultFreeFire("Free Fire", "com.dts.freefireth")

    /** Profile mặc định cho Free Fire MAX (package com.dts.freefiremax). */
    fun defaultFreeFireMax(): Profile = defaultFreeFire("Free Fire MAX", "com.dts.freefiremax")

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
                Config.CAM_HALF_W, null, "Camera",
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
            sensX = Config.SENS_X,
            sensY = Config.SENS_Y,
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
        val sensX = root["sensX"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0.1f, 5f)
            ?: Config.SENS_X
        val sensY = root["sensY"]?.num()?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0.1f, 5f)
            ?: Config.SENS_Y
        val toggleKey = root["toggleKey"]?.int() ?: Config.MAP_TOGGLE_KEY
        // JSON cũ (trước khi có chế độ con trỏ) thiếu cursorKey -> mặc định F9.
        val cursorKey = root["cursorKey"]?.int() ?: Config.CURSOR_TOGGLE_KEY

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
                    ElementType.CAMERA -> "Camera"
                    ElementType.BUTTON -> "Nút"
                    ElementType.SWIPE -> "Vuốt"
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
            elements.add(
                ProfileElement(
                    type, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), size, key, elName, enabled,
                    x2, y2, durationMs,
                ),
            )
        }
        val profile = Profile(id, name, pkg, CURRENT_VERSION, sensX, sensY, toggleKey, elements, cursorKey)
        return Result.Ok(profile)
    }
}

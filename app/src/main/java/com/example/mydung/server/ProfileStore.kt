package com.example.mydung.server

import java.io.File

/**
 * Lưu profile ra 1 file JSON (filesDir/profiles.json).
 *
 * Vì sao lưu file thay DataStore/Room như spec gợi ý: cả 2 đều cần thêm
 * dependency Gradle (ngoài phạm vi sửa của trợ lý). Profile ít, nhỏ, và
 * định dạng file trùng định dạng xuất/nhập nên 1 file JSON là đủ, dễ đọc,
 * dễ sửa tay khi cần. Lớp này tách riêng nên đổi sang DataStore sau cũng
 * chỉ sửa 1 file. Nhận File qua constructor để unit test dùng file tạm.
 */
class ProfileStore(private val file: File) {

    data class State(val profiles: List<Profile>, val activeId: String?)

    /** Đọc file. File chưa có/hỏng -> danh sách rỗng (lần lưu sau ghi đè). */
    fun load(): State {
        if (!file.exists()) return State(emptyList(), null)
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return State(emptyList(), null)
        }
        val root = Json.parse(text)?.obj() ?: return State(emptyList(), null)
        val activeId = root["activeId"]?.str()
        val profiles = ArrayList<Profile>()
        for (raw in root["profiles"]?.arr() ?: emptyList()) {
            // Parse từng profile riêng: 1 profile hỏng không làm mất cả file.
            val one = Json.stringify(raw)
            when (val r = ProfileCodec.fromJson(one)) {
                is ProfileCodec.Result.Ok -> profiles.add(r.profile)
                is ProfileCodec.Result.Err -> { /* bỏ profile hỏng */ }
            }
        }
        val validActive = activeId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        return State(profiles, validActive)
    }

    /** Ghi toàn bộ danh sách + id profile đang dùng. */
    fun save(profiles: List<Profile>, activeId: String?) {
        val root = LinkedHashMap<String, JsonValue>()
        root["version"] = JsonValue.Num(ProfileCodec.CURRENT_VERSION.toDouble())
        if (activeId != null) root["activeId"] = JsonValue.Str(activeId)
        root["profiles"] = JsonValue.Arr(profiles.map { ProfileCodec.toJsonValue(it) })
        try {
            file.parentFile?.mkdirs()
            file.writeText(Json.stringify(JsonValue.Obj(root)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Ghi lỗi (hết dung lượng...): caller không làm gì được, bỏ qua.
        }
    }

    /**
     * Đảm bảo luôn có ít nhất 1 profile: rỗng thì tạo 2 profile mặc định —
     * Free Fire MAX (đang dùng, game chính của người dùng) + Free Fire
     * bản thường. Lần sau load lại thì giữ nguyên, không tạo thêm.
     */
    fun ensureDefault(): State {
        val current = load()
        if (current.profiles.isNotEmpty()) return current
        val max = ProfileCodec.defaultFreeFireMax()
        val ff = ProfileCodec.defaultFreeFire()
        save(listOf(max, ff), max.id)
        return State(listOf(max, ff), max.id)
    }

    companion object {
        /** File mặc định trong filesDir của app (caller truyền Context.filesDir). */
        fun defaultFile(filesDir: File): File = File(filesDir, Config.PROFILE_FILE_NAME)
    }
}

package com.example.mydung.server

/**
 * Logic thuần chọn profile khi app foreground đổi (GĐ6, tự đổi profile
 * theo game). Không phụ thuộc Android nên unit test được.
 */
object AutoSwitch {
    /**
     * Tìm profile cần chuyển khi foreground là [pkg]:
     * - Không có profile nào gán package này -> null (giữ nguyên).
     * - Profile tìm được đã là profile đang dùng -> null (không đổi).
     * - Nhiều profile cùng gán 1 package -> lấy profile đầu tiên.
     */
    fun pickProfileForPackage(
        profiles: List<Profile>,
        activeId: String?,
        pkg: String,
    ): Profile? {
        if (pkg.isBlank()) return null
        val found = profiles.firstOrNull { it.packageName == pkg } ?: return null
        if (found.id == activeId) return null
        return found
    }
}

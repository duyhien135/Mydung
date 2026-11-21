package com.example.mydung.server

/**
 * Chạy lệnh shell từ tiến trình Server (uid 2000, quyền shell — tương
 * đương `adb shell`). Dùng cho việc GĐ6: tắt/bật lại
 * block_untrusted_touches khi bật/tắt MAP.
 *
 * LƯU Ý: dùng đường dẫn tuyệt đối /system/bin/... vì môi trường của
 * app_process có thể không có PATH đầy đủ.
 */
object Shell {
    const val SETTINGS_BIN = "/system/bin/settings"
    const val SH_BIN = "/system/bin/sh"

    /** Lệnh tắt (off=true -> 0) / bật lại (off=false -> 1) chặn chạm không tin cậy. */
    fun blockUntrustedTouchesCommand(off: Boolean): String =
        "$SETTINGS_BIN put global block_untrusted_touches ${if (off) 0 else 1}"

    /** Lệnh khôi phục về giá trị cũ (đọc được trước khi tắt). */
    fun restoreBlockUntrustedTouchesCommand(previousValue: String): String =
        "$SETTINGS_BIN put global block_untrusted_touches $previousValue"

    /** Lệnh đọc giá trị hiện tại. */
    fun getBlockUntrustedTouchesCommand(): String =
        "$SETTINGS_BIN get global block_untrusted_touches"

    /** Chạy lệnh qua sh, chờ xong. Trả mã thoát, -1 khi lỗi. */
    fun run(cmd: String): Int {
        return try {
            val proc = exec(cmd)
            proc.inputStream.readAllBytes()
            proc.waitFor()
        } catch (_: Exception) {
            -1
        }
    }

    /** Chạy lệnh, trả stdout đã trim. Lỗi hoặc rỗng -> null. */
    fun runOutput(cmd: String): String? {
        return try {
            val proc = exec(cmd)
            val out = proc.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
            proc.waitFor()
            out.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Chạy lệnh qua sh với stderr gộp vào stdout: chỉ đọc 1 stream duy
     * nhất nên không deadlock pipe nếu tiến trình con ghi nhiều ra stderr.
     * Các lệnh dùng ở đây (settings/getevent/dumpsys) gần như không ghi
     * stderr nên việc gộp không ảnh hưởng kết quả parse.
     */
    private fun exec(cmd: String): Process =
        ProcessBuilder(SH_BIN, "-c", cmd).redirectErrorStream(true).start()
}

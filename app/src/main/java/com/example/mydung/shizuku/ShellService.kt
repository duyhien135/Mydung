package com.example.mydung.shizuku

import android.os.Bundle
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * UserService chạy trong tiến trình đặc quyền của Shizuku (uid 2000 nếu
 * Shizuku khởi động bằng ADB, uid 0 nếu bằng root).
 *
 * Shizuku server tự tạo instance bằng reflection: thử constructor (Context)
 * trước, không có thì dùng constructor không tham số. Class này CHỈ có
 * constructor không tham số (cố tình): tiến trình này không phải tiến trình
 * ứng dụng hợp lệ, Context nhận được là 1 Application trần (onCreate() của
 * app không chạy, nhiều API của Context không hoạt động) nên chạm vào
 * Context/singleton của app trong constructor có thể ném lỗi và khiến bind
 * không bao giờ hoàn thành. Constructor + class phải public và @Keep (R8
 * không được đổi tên/xóa).
 *
 * KHÔNG khai báo <service> cho class này trong Manifest: Shizuku server
 * không đọc Manifest, nó tự spawn bằng app_process với classloader của APK.
 */
@Keep
class ShellService : IShellService.Stub {

    @Keep
    constructor() : super()

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val proc = ProcessBuilder("sh", "-c", command)
            .directory(File("/"))
            .redirectErrorStream(false)
            .start()

        // Đọc 2 stream trên luồng riêng để không kẹt bộ đệm ống (pipe).
        val outSb = StringBuilder()
        val errSb = StringBuilder()
        val outT = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { outSb.appendLine(it) }
            } catch (_: Exception) {
            }
        }
        val errT = Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine { errSb.appendLine(it) }
            } catch (_: Exception) {
            }
        }
        outT.start()
        errT.start()

        val finished = proc.waitFor(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        outT.join(2000)
        errT.join(2000)

        return Bundle().apply {
            putInt("code", if (finished) proc.exitValue() else -1)
            putString("out", outSb.toString())
            putString(
                "err",
                if (finished) errSb.toString()
                else (errSb.toString() + "\n[timeout after ${timeoutMs}ms]"),
            )
        }
    }

    override fun exit() = destroy()

    /** Shizuku server gọi khi hủy service. BẮT BUỘC tự thoát tiến trình. */
    override fun destroy() {
        exitProcess(0)
    }
}

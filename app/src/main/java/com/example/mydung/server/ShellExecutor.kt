package com.example.mydung.server

import com.example.mydung.AdbConnectionManager
import com.example.mydung.ActivationMethod
import com.example.mydung.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Trừu tượng hóa "chạy 1 lệnh shell bằng quyền đặc quyền" để ServerManager
 * không gắn chặt vào ADB nữa. Cả 3 cách đều chạy được lệnh khởi động Server
 * (app_process) và đọc log Server:
 *
 * - [AdbShellExecutor]: qua kết nối ADB không dây (đường cũ GĐ1/GĐ2).
 * - [ShizukuShellExecutor]: qua UserService Shizuku (uid 2000) — mặc định.
 * - [RootShellExecutor]: qua `su -c` trên máy (uid 0) — cho máy đã root.
 *
 * Lệnh chạy nền phải tự tách tiến trình (`&` + redirect) vì [run] đợi lệnh
 * kết thúc mới trả về stdout.
 */
interface ShellExecutor {
    /** Tên hiển thị cho log/UI: "Shizuku", "Root", "ADB". */
    val label: String

    /** Phương thức kích hoạt tương ứng. */
    val method: ActivationMethod

    /** Executor đã sẵn sàng chạy lệnh chưa (ADB đã nối / Shizuku đã cấp quyền / có root). */
    suspend fun isReady(): Boolean

    /** Chạy lệnh, trả stdout đã trim. Lỗi thì ném ngoại lệ (kèm stderr nếu có). */
    suspend fun run(command: String): String
}

/** Chạy lệnh qua ADB không dây (libadb). runShellCommand là hàm chặn nên đưa sang IO. */
class AdbShellExecutor(private val adb: AdbConnectionManager) : ShellExecutor {
    override val label = "ADB"
    override val method = ActivationMethod.ADB
    override suspend fun isReady(): Boolean = adb.adbConnected
    override suspend fun run(command: String): String =
        withContext(Dispatchers.IO) { adb.runShellCommand(command) }
}

/** Chạy lệnh qua UserService Shizuku (sh -c trong tiến trình shell uid 2000/0). */
class ShizukuShellExecutor : ShellExecutor {
    override val label = "Shizuku"
    override val method = ActivationMethod.SHIZUKU
    override suspend fun isReady(): Boolean =
        ShizukuBridge.state.value is ShizukuBridge.State.Ready

    override suspend fun run(command: String): String =
        ShizukuBridge.exec(command).getOrThrow()
}

/**
 * Chạy lệnh qua `su -c` ngay trong tiến trình app (máy đã root, vd Y700
 * KernelSU). Quyền root cho uid 0 nên đọc /dev/input/event* và tiêm đều được.
 */
class RootShellExecutor : ShellExecutor {
    override val label = "Root"
    override val method = ActivationMethod.ROOT

    @Volatile
    private var cachedReady: Boolean? = null

    /** Dò root 1 lần rồi cache (cấp quyền xong trong app quản lý root như
     *  KernelSU thì gọi [reprobe] để dò lại). */
    override suspend fun isReady(): Boolean {
        cachedReady?.let { return it }
        val ok = runCatching { run("id") }
            .map { it.contains("uid=0") }
            .getOrDefault(false)
        cachedReady = ok
        return ok
    }

    /** Xóa cache rồi dò lại root (sau khi người dùng cấp quyền trong app quản lý root). */
    suspend fun reprobe(): Boolean {
        cachedReady = null
        return isReady()
    }

    override suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        val proc = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IOException("Không chạy được su (máy chưa root?): ${e.message}")
        }
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            // Che token trong lệnh (lệnh khởi động Server chứa token 32 hex).
            throw IOException("Lệnh root quá thời gian 30s: ${Config.redactSecrets(command.take(80))}")
        }
        val out = try {
            proc.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
            ""
        }
        if (proc.exitValue() != 0) {
            throw IOException("Lệnh root lỗi (exit ${proc.exitValue()}): ${Config.redactSecrets(out.take(300))}")
        }
        out
    }
}

package com.example.mydung.server

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * Fallback chạy lệnh shell qua Shizuku.newProcess bằng reflection.
 *
 * Bối cảnh (21/11/2026, máy thật Redmi Turbo 4 + Shizuku 13.5): đường chính
 * UserService + AIDL bind không bao giờ hoàn thành (bindService timeout sau
 * 35s, cả sau khi khởi động lại Shizuku server), nên mọi lệnh shell đều
 * chết ở bước bind. Shizuku.newProcess là đường khác tới cùng quyền shell
 * (uid 2000) mà không cần spawn UserService — chỉ cần binder Shizuku còn
 * sống và quyền đã cấp.
 *
 * newProcess đã private + @Deprecated từ Shizuku 13.x ("planned removal
 * from API 14") nên phải gọi bằng reflection. Signature (xác nhận từ source
 * Shizuku-API 13.1.5): `private static ShizukuRemoteProcess newProcess(
 * String[] cmd, String[] env, String dir)` — ShizukuRemoteProcess extends
 * java.lang.Process. Vì signature này đã đổi giữa các bản Shizuku nên tìm
 * method theo tên + số tham số (3) thay vì ghim signature.
 *
 * File này thuần JVM (không import Android / Shizuku / coroutines) để biên
 * dịch + unit test được bằng kotlinc cục bộ (xem check-local.sh). Log
 * fallback nằm ở bên gọi ([ShizukuBridge.exec]) — file này không log.
 */
object ShizukuNewProcess {

    /** Lỗi khi không gọi được newProcess hoặc lệnh chạy lỗi (không crash). */
    class NewProcessException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    /** Kết quả chạy 1 Process: mã thoát + stdout + stderr. */
    data class ProcessOutcome(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Chạy `sh -c command` bằng quyền shell qua Shizuku.newProcess.
     * Trả stdout thô (giống ShizukuBridge.exec). Lệnh exit != 0 hoặc gọi
     * newProcess lỗi thì ném [NewProcessException].
     */
    fun exec(command: String, timeoutMs: Int = 15_000): String {
        val outcome = runNewProcess(command, timeoutMs)
        if (outcome.exitCode != 0) {
            throw NewProcessException(
                "newProcess exit ${outcome.exitCode}: " +
                    outcome.stderr.ifBlank { outcome.stdout }.trim().take(300),
            )
        }
        return outcome.stdout
    }

    /** Gọi Shizuku.newProcess bằng reflection rồi chờ kết quả. */
    fun runNewProcess(command: String, timeoutMs: Int): ProcessOutcome {
        val shizukuClass = try {
            Class.forName("rikka.shizuku.Shizuku")
        } catch (e: ClassNotFoundException) {
            throw NewProcessException(
                "Không tìm thấy class rikka.shizuku.Shizuku (thiếu lib Shizuku API?)",
                e,
            )
        }
        val method = findNewProcessMethod(shizukuClass)
            ?: throw NewProcessException(
                "Không tìm thấy Shizuku.newProcess(String[], String[], String) — " +
                    "có thể đã bị xóa ở Shizuku API 14+.",
            )
        method.isAccessible = true
        val proc = try {
            val result = method.invoke(null, arrayOf("sh", "-c", command), null, null)
                ?: throw NewProcessException("Shizuku.newProcess trả về null")
            result as? Process
                ?: throw NewProcessException(
                    "Shizuku.newProcess trả về ${result.javaClass.name}, không phải Process",
                )
        } catch (e: NewProcessException) {
            throw e
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw NewProcessException("Shizuku.newProcess lỗi: ${cause?.message}", cause)
        } catch (e: Exception) {
            throw NewProcessException("Không gọi được Shizuku.newProcess: ${e.message}", e)
        }
        return drainProcess(proc, timeoutMs)
    }

    /**
     * Tìm method newProcess theo tên + số tham số (3), không ghim signature:
     * method này private + deprecated, signature đã đổi giữa các bản Shizuku.
     * Public để unit test được.
     */
    fun findNewProcessMethod(clazz: Class<*>): java.lang.reflect.Method? =
        clazz.declaredMethods.firstOrNull { it.name == "newProcess" && it.parameterCount == 3 }

    /**
     * Chờ 1 Process xong: đọc 2 stream song song (không kẹt pipe) + waitFor
     * có timeout. Quá giờ thì destroyForcibly, exitCode = -1. Public để unit
     * test được. Cùng mẫu với ShellService.exec.
     */
    fun drainProcess(proc: Process, timeoutMs: Int): ProcessOutcome {
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

        return ProcessOutcome(
            exitCode = if (finished) proc.exitValue() else -1,
            stdout = outSb.toString(),
            stderr = if (finished) errSb.toString()
            else (errSb.toString() + "\n[timeout after ${timeoutMs}ms]"),
        )
    }
}

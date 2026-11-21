package com.example.mydung.server

import android.system.ErrnoException
import android.system.Os
import java.io.FileDescriptor

/**
 * Chiếm thiết bị bằng ioctl EVIOCGRAB.
 *
 * Cách C (ƯU TIÊN, build-9): gọi thẳng android.system.Os.ioctlInt — API
 * CÔNG KHAI từ API 21, không qua hidden API nên không bị chặn như
 * reflection. Trên Y700 (Android 13/ZUI) reflection vào libcore bị chặn
 * nên cách A thất bại, grab không ăn -> con trỏ hệ thống vẫn di chuyển
 * cùng chấm con trỏ ảo (lỗi 2 con trỏ, máy thật 22/11/2026).
 *
 * Cách A (dự phòng): gọi Libcore.os.ioctlInt qua reflection (không cần mã
 * native/JNI). scrcpy và nhiều công cụ chạy bằng app_process cũng dùng
 * reflection vào API ẩn theo cách này.
 *
 * Cách B: không chiếm (con trỏ hệ thống vẫn di chuyển), KHÔNG sập.
 */
object Grabber {
    enum class Method { A_REFLECTION, B_NONE, C_OS }

    private var ioctlMethod: java.lang.reflect.Method? = null
    private var osInstance: Any? = null
    private var probed = false

    var method: Method = Method.B_NONE
        private set

    /** Dò 1 lần lúc Server khởi động. An toàn khi gọi nhiều lần. */
    @Synchronized
    fun probe(): Method {
        if (probed) return method
        probed = true
        // Cách C trước: thử gọi với fd không hợp lệ, kỳ vọng ném
        // ErrnoException (EBADF) — chứng tỏ hàm gọi được.
        try {
            Os.ioctlInt(FileDescriptor(), Config.EVIOCGRAB, 1)
            // Không lỗi (không ngờ với fd sai) vẫn tính là gọi được.
            method = Method.C_OS
            Server.log("GRAB: dò ioctl thành công (cách C, android.system.Os.ioctlInt).")
            return method
        } catch (e: ErrnoException) {
            // Mong đợi: ioctl trên fd sai phải lỗi EBADF.
            method = Method.C_OS
            Server.log("GRAB: dò ioctl thành công (cách C, android.system.Os.ioctlInt).")
            return method
        } catch (_: Exception) {
            // Không gọi được (stub JVM / lỗi khác): thử cách A.
        } catch (_: Error) {
            // NoSuchMethodError v.v.: thử cách A.
        }
        try {
            val libcore = Class.forName("libcore.io.Libcore")
            val osField = libcore.getDeclaredField("os").apply { isAccessible = true }
            val os = osField.get(null) ?: throw IllegalStateException("Libcore.os null")
            val m: java.lang.reflect.Method = os.javaClass.getMethod(
                "ioctlInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            // Thử gọi với fd không hợp lệ: kỳ vọng ném ErrnoException (EBADF),
            // chứng tỏ hàm gọi được. Lỗi reflection (nếu có) sẽ ném ra ngoài.
            try {
                m.invoke(os, FileDescriptor(), Config.EVIOCGRAB, 1)
            } catch (_: java.lang.reflect.InvocationTargetException) {
                // Mong đợi: ioctl trên fd sai phải lỗi EBADF.
            }
            ioctlMethod = m
            osInstance = os
            method = Method.A_REFLECTION
            Server.log("GRAB: dò ioctl thành công (cách A, libcore.ioctlInt).")
        } catch (e: Exception) {
            method = Method.B_NONE
            Server.log("GRAB: không dò được ioctl (${e.javaClass.simpleName}: ${e.message}) -> dùng cách B (không chiếm).")
        }
        return method
    }

    /** Chiếm (on=true) hoặc nhả (on=false) thiết bị qua fd của nó. */
    fun grab(fd: FileDescriptor, on: Boolean): Boolean {
        return when (method) {
            Method.C_OS -> try {
                Os.ioctlInt(fd, Config.EVIOCGRAB, if (on) 1 else 0)
                true
            } catch (e: Exception) {
                Server.log("GRAB: ioctl (cách C) thất bại (${e.javaClass.simpleName}: ${e.message})")
                false
            }
            Method.A_REFLECTION -> {
                val m = ioctlMethod ?: return false
                val os = osInstance ?: return false
                try {
                    m.invoke(os, fd, Config.EVIOCGRAB, if (on) 1 else 0)
                    true
                } catch (e: Exception) {
                    Server.log("GRAB: ioctl thất bại (${e.javaClass.simpleName}: ${e.message})")
                    false
                }
            }
            Method.B_NONE -> false
        }
    }
}

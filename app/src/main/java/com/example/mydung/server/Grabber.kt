package com.example.mydung.server

import java.io.FileDescriptor

/**
 * Chiếm thiết bị bằng ioctl EVIOCGRAB.
 *
 * Cách A: gọi Libcore.os.ioctlInt qua reflection (không cần mã native/JNI, không
 * cần sửa Gradle/NDK). scrcpy và nhiều công cụ chạy bằng app_process cũng dùng
 * reflection vào API ẩn theo cách này.
 *
 * CHƯA CHẮC: reflection vào libcore.io.Libcore trên Android 13/ZUI, tiến trình
 * shell. probe() dò lúc Server khởi động và ghi log kết quả. Nếu dò thất bại
 * thì mọi lệnh grab đều trả false -> dùng cách B (không chiếm, con trỏ hệ thống
 * vẫn di chuyển), KHÔNG sập. Nếu reflection bị chặn thì hướng sửa sau là JNI.
 */
object Grabber {
    enum class Method { A_REFLECTION, B_NONE }

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
        val m = ioctlMethod ?: return false
        val os = osInstance ?: return false
        return try {
            m.invoke(os, fd, Config.EVIOCGRAB, if (on) 1 else 0)
            true
        } catch (e: Exception) {
            Server.log("GRAB: ioctl thất bại (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }
}

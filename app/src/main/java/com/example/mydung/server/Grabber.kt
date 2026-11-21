package com.example.mydung.server

import java.io.FileDescriptor

/**
 * Chiếm thiết bị bằng ioctl EVIOCGRAB — CHỈ CÓ GRAB, KHÔNG CÓ NHẢ QUA IOCTL.
 *
 * Bẫy 1 — ioctlInt KHÔNG phải public API: `android.system.Os.ioctlInt`
 * không có trong android.jar (compileSdk 37 báo unresolved — CI build-9
 * fail vì gọi thẳng nó). Nó chỉ tồn tại ở runtime (libcore/framework,
 * @hide) nên PHẢI gọi qua reflection, tuyệt đối không import
 * android.system.* trực tiếp. Stub tự viết ở toolcheck/stubs từng CHE
 * lỗi này (test local đậu mà CI fail) nên đã xóa hẳn — file này thuần
 * JVM + reflection, biên dịch local và CI giống hệt nhau.
 *
 * Bẫy 2 — EVIOCGRAB nhận CON TRỎ, không nhận giá trị: kernel coi con trỏ
 * khác NULL = grab, NULL = nhả. ioctlInt truyền con trỏ tới int nên chỉ
 * grab được, KHÔNG nhả được. Nhả = ĐÓNG fd (kernel tự nhả khi fd đóng).
 * Vì vậy InputHub nhả grab bằng cách đóng reader rồi mở lại ngay
 * (DeviceScanner.reopenReader) — fd mới chưa grab, vẫn đọc được event.
 * Lưu ý: grab PHẢI làm trên đúng fd đang đọc event — fd khác grab thì
 * reader đói event (kernel chỉ gửi event cho fd đang grab).
 *
 * Bẫy 3 — EBUSY: grab bị EBUSY = process khác (thường là server cũ chưa
 * chết) đang giữ thiết bị. Báo lỗi riêng trong log để biết đường xử lý
 * (dừng server cũ), không lẫn với lỗi grab thường.
 */

/** Mã errno dùng để phân biệt lỗi grab (Linux, giống mọi kiến trúc). */
private const val ERRNO_EBUSY = 16

/** Kết quả 1 lần gọi ioctl EVIOCGRAB. */
sealed class GrabOutcome {
    /** Grab thành công. */
    object Grabbed : GrabOutcome()
    /** EBUSY: process khác (server cũ) đang giữ thiết bị. */
    object Busy : GrabOutcome()
    /** Lỗi khác (không resolve được ioctl, fd hỏng...). */
    data class Failed(val reason: String) : GrabOutcome()
}

/**
 * 1 cách thực hiện ioctl EVIOCGRAB lên fd. Interface để test thay bằng
 * fake — production dùng [ReflectiveEvdevIoctl].
 */
interface EvdevIoctl {
    /** Tên strategy đang dùng (để log + chuỗi trạng thái GRAB). "none" khi chưa resolve được. */
    val strategyName: String
    /** Dò ioctl 1 lần, ghi strategy vào log server. Gọi nhiều lần an toàn. */
    fun resolve()
    /** Grab thiết bị qua fd. Chỉ grab — nhả bằng cách đóng fd (xem Grabber). */
    fun grab(fd: FileDescriptor): GrabOutcome
}

/**
 * Lấy mã errno từ exception (ErrnoException thật qua reflection — không
 * import android.system.ErrnoException trực tiếp vì file này phải biên
 * dịch được cả khi không có android.jar). Trả null nếu không phải
 * ErrnoException. Nhận cả tên đầy đủ lẫn tên đơn (để test dùng fake
 * ErrnoException cùng tên).
 */
fun extractErrno(e: Throwable): Int? {
    var t: Throwable? = e
    while (t is java.lang.reflect.InvocationTargetException) t = t.targetException
    t ?: return null
    val cls = t.javaClass
    if (cls.name != "android.system.ErrnoException" && cls.simpleName != "ErrnoException") return null
    return try {
        cls.getField("errno").getInt(t)
    } catch (_: Exception) {
        null
    }
}

/**
 * ioctl EVIOCGRAB qua reflection vào libcore/framework (chạy trên máy thật).
 * Server chạy bằng app_process dưới shell/root nên hạn chế hidden API
 * thường không áp dụng (như scrcpy).
 *
 * Thứ tự thăm dò (dò 1 lần bằng fd không hợp lệ, kỳ vọng ErrnoException
 * EBADF chứng tỏ hàm gọi được tới kernel):
 *  1. Libcore.os.ioctlInt(fd, cmd, MutableInt) — Android 12+ (libcore
 *     hiện tại chỉ còn dạng này; đã kiểm chứng từ source AOSP Os.java).
 *  2. Libcore.os.ioctlInt(fd, cmd, Int32Ref) — Android cũ (≤11).
 *  3. android.system.Os.ioctlInt(fd, cmd, MutableInt) tĩnh — facade
 *     framework nếu có ở runtime (không có trong android.jar).
 *  4. android.system.Os.ioctlInt(fd, cmd, Int32Ref) tĩnh — facade cũ.
 *
 * KHÔNG dò dạng 2 tham số ioctlInt(fd, cmd): dạng này không có trong
 * interface libcore.io.Os, và ioctl 2 tham số truyền NULL làm đối số —
 * với EVIOCGRAB, NULL nghĩa là NHẢ chứ không phải grab, dùng nhầm thì
 * grab lặng lẽ không ăn.
 */
class ReflectiveEvdevIoctl : EvdevIoctl {
    private var invoker: ((FileDescriptor) -> Unit)? = null

    @Volatile
    private var resolved = false

    @Volatile
    override var strategyName: String = "none"
        private set

    @Synchronized
    override fun resolve() {
        if (resolved) return
        resolved = true
        val attempts = listOf(
            "lc-mut" to { libcoreIoctl("libcore.util.MutableInt") },
            "lc-i32" to { libcoreIoctl("libcore.io.Int32Ref") },
            "os-mut" to { staticOsIoctl("libcore.util.MutableInt") },
            "os-i32" to { staticOsIoctl("libcore.io.Int32Ref") },
        )
        val failures = ArrayList<String>()
        for ((name, attempt) in attempts) {
            try {
                val fn = attempt()
                // Dò bằng fd không hợp lệ: kỳ vọng ErrnoException (EBADF)
                // chứng tỏ hàm gọi được tới kernel. Không lỗi (không ngờ)
                // vẫn tính là gọi được.
                try {
                    fn(FileDescriptor())
                } catch (e: Exception) {
                    if (extractErrno(e) == null) throw e
                }
                invoker = fn
                strategyName = name
                Server.log("GRAB: dùng ioctl dạng $name.")
                return
            } catch (e: Exception) {
                failures.add("$name: ${e.javaClass.simpleName}")
            } catch (e: Error) {
                failures.add("$name: ${e.javaClass.simpleName}")
            }
        }
        strategyName = "none"
        Server.log("GRAB: không dò được ioctl nào (${failures.joinToString(", ")}) -> không grab.")
    }

    override fun grab(fd: FileDescriptor): GrabOutcome {
        if (!resolved) resolve()
        val fn = invoker ?: return GrabOutcome.Failed("không có ioctl")
        return try {
            fn(fd)
            GrabOutcome.Grabbed
        } catch (e: Exception) {
            val errno = extractErrno(e)
            if (errno == ERRNO_EBUSY) {
                Server.log("GRAB: EBUSY — server/process khác đang giữ thiết bị (dừng server cũ rồi thử lại).")
                GrabOutcome.Busy
            } else {
                val reason = "${e.javaClass.simpleName}: ${e.message}"
                Server.log("GRAB: ioctl thất bại ($reason)")
                GrabOutcome.Failed(reason)
            }
        } catch (e: Error) {
            val reason = "${e.javaClass.simpleName}: ${e.message}"
            Server.log("GRAB: ioctl thất bại ($reason)")
            GrabOutcome.Failed(reason)
        }
    }

    /** Libcore.os.ioctlInt(fd, cmd, refClass) — refClass là MutableInt hoặc Int32Ref. */
    private fun libcoreIoctl(refClassName: String): (FileDescriptor) -> Unit {
        val libcore = Class.forName("libcore.io.Libcore")
        val osField = libcore.getDeclaredField("os").apply { isAccessible = true }
        val os = osField.get(null) ?: throw IllegalStateException("Libcore.os null")
        val refClass = Class.forName(refClassName)
        val m = os.javaClass.getMethod(
            "ioctlInt",
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            refClass,
        )
        val refCtor = refClass.getConstructor(Int::class.javaPrimitiveType)
        return { fd ->
            val ref = refCtor.newInstance(1)
            m.invoke(os, fd, Config.EVIOCGRAB, ref)
        }
    }

    /** android.system.Os.ioctlInt(fd, cmd, refClass) tĩnh. */
    private fun staticOsIoctl(refClassName: String): (FileDescriptor) -> Unit {
        val osClass = Class.forName("android.system.Os")
        val refClass = Class.forName(refClassName)
        val m = osClass.getMethod(
            "ioctlInt",
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            refClass,
        )
        val refCtor = refClass.getConstructor(Int::class.javaPrimitiveType)
        return { fd ->
            val ref = refCtor.newInstance(1)
            m.invoke(null, fd, Config.EVIOCGRAB, ref)
        }
    }
}

/**
 * Điểm gọi grab duy nhất của Server. Backend mặc định là reflection máy
 * thật; test thay bằng fake qua [setIoctlForTest].
 */
object Grabber {
    @Volatile
    private var backend: EvdevIoctl = ReflectiveEvdevIoctl()

    /** Thay backend — CHỈ DÙNG CHO TEST. Production luôn dùng reflection. */
    fun setIoctlForTest(ioctl: EvdevIoctl) {
        backend = ioctl
    }

    /** Tên strategy đang dùng ("lc-mut", "lc-i32", "os-mut", "os-i32", "none"...). */
    val strategyName: String
        get() = backend.strategyName

    /** Dò ioctl 1 lần lúc Server khởi động, ghi strategy vào log. */
    fun resolve() = backend.resolve()

    /**
     * Grab thiết bị qua fd của reader. Trả true nếu grab được.
     * KHÔNG có nhả qua ioctl: muốn nhả thì đóng fd (xem
     * [DeviceScanner.reopenReader]).
     */
    fun grab(fd: FileDescriptor): Boolean {
        // EBUSY (server cũ còn giữ thiết bị) đã được backend log riêng.
        return backend.grab(fd) is GrabOutcome.Grabbed
    }
}

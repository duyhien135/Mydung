package com.example.mydung.server

import java.io.FileDescriptor

/**
 * Đọc khả năng thiết bị evdev bằng ioctl EVIOCGBIT qua reflection.
 *
 * Kiến trúc học theo GG Mouse Pro 3 (đã soi APK 23/11/2026): service đặc
 * quyền phân loại thiết bị bằng EVIOCGBIT (chuột/bàn phím/cảm ứng/nút
 * bên) thay vì chỉ dựa vào /proc/bus/input/devices. Code ở đây tự viết
 * từ đầu (không chép code GG — chỉ học kiến trúc).
 *
 * Hạn chế ioctlInt: chỉ truyền được 1 int (4 byte) nên mỗi lần đọc chỉ
 * lấy 4 byte đầu của bitmap — đủ cho:
 * - EV (bit 0-31): EV_KEY(1), EV_REL(2), EV_ABS(3).
 * - REL (bit 0-31): REL_X(0), REL_Y(1), REL_WHEEL(8).
 * - KEY word 0 (bit 0-31): phím chữ Q(16)-P(25), A(30), S(31).
 * BTN_LEFT (272) nằm ngoài 4 byte đầu nên chuột được nhận diện bằng
 * EV_REL + REL_X + REL_Y + EV_KEY thay vì bắt buộc BTN_LEFT như
 * classify() đọc /proc (nới hơn — bắt được chuột mà /proc bỏ sót).
 *
 * File thuần JVM + reflection (không import android.system.* — xem
 * Grabber: android.system.Os không phải public API, tuyệt đối không
 * tạo stub cho nó).
 */

/** 4 byte đầu của các bitmap EVIOCGBIT (bit 0-31 của mỗi loại). */
data class EvdevCapsMasks(val ev: Int, val keyWord0: Int, val rel: Int, val abs: Int)

/**
 * 1 cách gọi ioctlInt có đọc lại giá trị int. Interface để test thay
 * bằng fake — production dùng [ReflectiveEvdevCapsIoctl].
 */
interface EvdevCapsIoctl {
    /** Tên strategy đang dùng ("lc-mut", "lc-i32", "os-mut", "os-i32", "none"...). */
    val strategyName: String
    /** Dò ioctl 1 lần, ghi strategy vào log server. Gọi nhiều lần an toàn. */
    fun resolve()
    /**
     * Gọi ioctlInt(fd, cmd, int) rồi đọc lại giá trị int (EVIOCGBIT ghi
     * bitmap vào đó). null = gọi lỗi (không resolve được, fd hỏng...).
     * Trả 0 là giá trị hợp lệ (bitmap rỗng), khác với null.
     */
    fun ioctlIntValue(fd: FileDescriptor, cmd: Int): Int?
}

/**
 * ioctlInt qua reflection vào libcore/framework (chạy trên máy thật).
 * Cùng 4 dạng thăm dò như [ReflectiveEvdevIoctl] ở Grabber.kt, nhưng
 * nhận cmd bất kỳ và đọc lại giá trị int (EVIOCGBIT ghi bitmap vào
 * đó) thay vì ghim EVIOCGRAB. Dò bằng fd không hợp lệ, kỳ vọng
 * ErrnoException EBADF chứng tỏ hàm gọi được tới kernel.
 */
class ReflectiveEvdevCapsIoctl : EvdevCapsIoctl {
    private var invoker: ((FileDescriptor, Int) -> Int)? = null

    @Volatile
    private var resolved = false

    @Volatile
    override var strategyName: String = "none"
        private set

    @Synchronized
    override fun resolve() {
        if (resolved) return
        resolveWithAttempts(
            listOf(
                "lc-mut" to { libcoreIoctl("libcore.util.MutableInt") },
                "lc-i32" to { libcoreIoctl("libcore.io.Int32Ref") },
                "os-mut" to { staticOsIoctl("libcore.util.MutableInt") },
                "os-i32" to { staticOsIoctl("libcore.io.Int32Ref") },
            ),
        )
    }

    /**
     * Chọn strategy từ danh sách attempt cho trước. resolve() gọi hàm
     * này với 4 attempt reflection thật; test gọi trực tiếp với attempt
     * giả để kiểm chứng logic chọn phương án "chưa kiểm chứng".
     * Production KHÔNG gọi trực tiếp — dùng resolve().
     *
     * Luật chọn (build-14, sửa theo Claude): ưu tiên strategy đầu tiên
     * vượt qua kiểm chứng fd=-1 (không ném, hoặc ném lỗi errno như
     * EBADF — chứng tỏ hàm gọi được tới kernel). Nếu KHÔNG strategy
     * nào vượt qua kiểm chứng, giữ strategy đầu tiên có lỗi MƠ HỒ
     * (không phải lỗi cấu trúc) làm phương án cuối "chưa kiểm chứng"
     * (strategyName = tên + "?") — kiểm chứng fd giả có thể loại oan
     * trên ROM lạ (ném lỗi khác EBADF), trong khi ioctl thật trên fd
     * thiết bị vẫn có thể chạy được. Lỗi CẤU TRÚC (sai chữ ký, thiếu
     * method/field/class) đã chứng minh gọi sai nên bị loại khỏi ứng
     * viên. Chỉ trả "none" khi không còn ứng viên nào.
     */
    @Synchronized
    fun resolveWithAttempts(attempts: List<Pair<String, () -> (FileDescriptor, Int) -> Int>>) {
        resolved = true
        val failures = ArrayList<String>()
        var firstCandidate: Pair<String, (FileDescriptor, Int) -> Int>? = null
        for ((name, attempt) in attempts) {
            val fn = try {
                attempt()
            } catch (e: Exception) {
                failures.add("$name: ${e.javaClass.simpleName} (build)")
                continue
            } catch (e: Error) {
                failures.add("$name: ${e.javaClass.simpleName} (build)")
                continue
            }
            // Invoker xây được — kiểm chứng bằng fd không hợp lệ.
            val verifyError: Throwable? = try {
                fn(FileDescriptor(), Config.EVIOCGBIT_EV)
                null
            } catch (e: Exception) {
                e
            } catch (e: Error) {
                e
            }
            if (verifyError == null || extractErrno(verifyError) != null) {
                // Không ném, hoặc ném lỗi errno (vd EBADF) — gọi được tới kernel.
                invoker = fn
                strategyName = name
                Server.log("EVIOCGBIT: dùng ioctl dạng $name.")
                return
            }
            if (isStructuralError(verifyError)) {
                // Lỗi cấu trúc — đã chứng minh gọi sai, gọi trên fd thật
                // cũng lỗi y hệt nên loại khỏi ứng viên "chưa kiểm chứng".
                failures.add("$name: ${verifyError.javaClass.simpleName} (structural)")
                continue
            }
            // Lỗi mơ hồ — giữ làm ứng viên "chưa kiểm chứng" (chỉ lấy đầu tiên).
            if (firstCandidate == null) firstCandidate = name to fn
            failures.add("$name: ${verifyError.javaClass.simpleName}")
        }
        val fallback = firstCandidate
        if (fallback != null) {
            invoker = fallback.second
            strategyName = fallback.first + "?"
            Server.log(
                "EVIOCGBIT: không strategy nào vượt qua kiểm chứng fd=-1 " +
                    "(${failures.joinToString(", ")}) -> dùng ${fallback.first} chưa kiểm chứng.",
            )
        } else {
            strategyName = "none"
            Server.log("EVIOCGBIT: không dò được ioctl nào (${failures.joinToString(", ")}) -> không phân loại bằng EVIOCGBIT.")
        }
    }

    /**
     * Lỗi cấu trúc khi gọi thử: sai chữ ký/thiếu method/field/class —
     * gọi trên fd thật cũng lỗi y hệt nên không được làm ứng viên
     * "chưa kiểm chứng". Kiểm tra cả chuỗi cause (reflection bọc lỗi
     * thật trong InvocationTargetException).
     */
    private fun isStructuralError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is IllegalArgumentException,
                is ClassCastException,
                is NoSuchMethodError,
                is AbstractMethodError,
                is NoSuchFieldError,
                is NoClassDefFoundError -> return true
            }
            t = t.cause
        }
        return false
    }

    override fun ioctlIntValue(fd: FileDescriptor, cmd: Int): Int? {
        if (!resolved) resolve()
        val fn = invoker ?: return null
        return try {
            fn(fd, cmd)
        } catch (_: Exception) {
            null
        } catch (_: Error) {
            null
        }
    }

    /** Libcore.os.ioctlInt(fd, cmd, refClass) — refClass là MutableInt hoặc Int32Ref. */
    private fun libcoreIoctl(refClassName: String): (FileDescriptor, Int) -> Int {
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
        val valueField = refClass.getField("value")
        return { fd, cmd ->
            val ref = refCtor.newInstance(0)
            m.invoke(os, fd, cmd, ref)
            valueField.getInt(ref)
        }
    }

    /** android.system.Os.ioctlInt(fd, cmd, refClass) tĩnh. */
    private fun staticOsIoctl(refClassName: String): (FileDescriptor, Int) -> Int {
        val osClass = Class.forName("android.system.Os")
        val refClass = Class.forName(refClassName)
        val m = osClass.getMethod(
            "ioctlInt",
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            refClass,
        )
        val refCtor = refClass.getConstructor(Int::class.javaPrimitiveType)
        val valueField = refClass.getField("value")
        return { fd, cmd ->
            val ref = refCtor.newInstance(0)
            m.invoke(null, fd, cmd, ref)
            valueField.getInt(ref)
        }
    }
}

/**
 * Điểm gọi EVIOCGBIT duy nhất của Server. Backend mặc định là reflection
 * máy thật; test thay bằng fake qua [setIoctlForTest].
 */
object EvdevCaps {
    @Volatile
    private var backend: EvdevCapsIoctl = ReflectiveEvdevCapsIoctl()

    /** Thay backend — CHỈ DÙNG CHO TEST. Production luôn dùng reflection. */
    fun setIoctlForTest(ioctl: EvdevCapsIoctl) {
        backend = ioctl
    }

    /** Khôi phục backend reflection — CHỈ DÙNG CHO TEST (dọn sau mỗi test). */
    fun resetIoctlForTest() {
        backend = ReflectiveEvdevCapsIoctl()
    }

    /**
     * Tên strategy đang dùng ("lc-mut", "lc-i32", "os-mut", "os-i32",
     * "none"...). Hậu tố "?" = phương án chưa kiểm chứng (build-14:
     * không strategy nào vượt qua kiểm chứng fd=-1, giữ strategy đầu
     * tiên resolve được làm phương án cuối).
     */
    val strategyName: String
        get() = backend.strategyName

    /** Dò ioctl 1 lần lúc Server khởi động, ghi strategy vào log. */
    fun resolve() = backend.resolve()

    /** EVIOCGBIT probe dùng được không (đã dò được ioctl). Gọi resolve() nếu chưa dò. */
    fun isProbingAvailable(): Boolean {
        resolve()
        return strategyName != "none"
    }

    /** Kết quả 1 lần probe EVIOCGBIT trên 1 fd. */
    sealed class ProbeResult {
        /** Đọc được bitmap khả năng. */
        data class Ok(val masks: EvdevCapsMasks) : ProbeResult()
        /** Gọi ioctl lỗi (fd hỏng, không resolve được ioctl). */
        object IoctlFailed : ProbeResult()
        /**
         * Gọi được nhưng EV mask đọc về 0 — mọi thiết bị evdev đều có
         * EV_SYN (bit 0) nên 0 nghĩa là libcore không trả lại giá trị
         * ioctl, hoặc fd không phải node evdev.
         */
        object ZeroMask : ProbeResult()
    }

    /**
     * Đọc bitmap khả năng của 1 thiết bị qua fd đã mở (EV/KEY/REL/ABS,
     * mỗi loại 4 byte đầu). Không grab, không làm gì khác với thiết bị.
     */
    fun probe(fd: FileDescriptor): ProbeResult {
        if (!isProbingAvailable()) return ProbeResult.IoctlFailed
        val ev = backend.ioctlIntValue(fd, Config.EVIOCGBIT_EV) ?: return ProbeResult.IoctlFailed
        if (ev == 0) return ProbeResult.ZeroMask
        val key = backend.ioctlIntValue(fd, Config.EVIOCGBIT_KEY) ?: 0
        val rel = backend.ioctlIntValue(fd, Config.EVIOCGBIT_REL) ?: 0
        val abs = backend.ioctlIntValue(fd, Config.EVIOCGBIT_ABS) ?: 0
        return ProbeResult.Ok(EvdevCapsMasks(ev, key, rel, abs))
    }

    /**
     * Phân loại theo bitmap khả năng (không theo tên):
     * - Chuột = có EV_REL + REL_X + REL_Y + EV_KEY (nút).
     * - Bàn phím = có EV_KEY + phím chữ trong 4 byte đầu KEY bitmap.
     * - Cảm ứng/bút (chỉ EV_ABS, không REL, không phím chữ) -> null.
     * - Nút nguồn/cảm biến (EV_KEY nhưng không có phím chữ) -> null.
     */
    fun classify(m: EvdevCapsMasks): DeviceKind? {
        val hasKey = hasBit(m.ev, 1)
        val hasRel = hasBit(m.ev, 2)
        val hasAbs = hasBit(m.ev, 3)
        val hasLetters = hasLetterBits(m.keyWord0)
        if (hasAbs && !hasRel && !hasLetters) return null // màn cảm ứng / bút
        return when {
            hasRel && hasKey && hasBit(m.rel, 0) && hasBit(m.rel, 1) -> DeviceKind.MOUSE
            hasKey && hasLetters -> DeviceKind.KEYBOARD
            else -> null
        }
    }

    /**
     * Phím chữ trong 4 byte đầu KEY bitmap: Q(16)-P(25), A(30), S(31).
     * (Z-M là bit 44-50, ngoài 4 byte đầu nên không xét được.)
     */
    private fun hasLetterBits(keyWord0: Int): Boolean {
        for (code in 16..25) if (hasBit(keyWord0, code)) return true
        return hasBit(keyWord0, 30) || hasBit(keyWord0, 31)
    }

    private fun hasBit(mask: Int, bit: Int) = ((mask shr bit) and 1) == 1
}

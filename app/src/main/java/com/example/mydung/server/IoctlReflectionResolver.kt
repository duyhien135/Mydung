package com.example.mydung.server

/**
 * Dò 1 hàm ioctl qua reflection — DÙNG CHUNG cho EVIOCGBIT (EvdevCaps)
 * và EVIOCGRAB (Grabber). Build-15 tách ra từ resolveWithAttempts của
 * EvdevCaps sau khi Grabber gặp đúng bug cũ (kiểm chứng fd=-1 loại oan
 * cả 4 strategy -> "none", grab không bao giờ chạy thật): 2 chỗ dùng
 * chung 1 logic thì không lặp lại cùng 1 bug ở 2 nơi.
 *
 * Luật chọn: ưu tiên strategy đầu tiên vượt qua kiểm chứng fd=-1 (không
 * ném, hoặc ném lỗi errno như EBADF — chứng tỏ hàm gọi được tới kernel).
 * Nếu KHÔNG strategy nào vượt qua kiểm chứng, giữ strategy đầu tiên có
 * lỗi MƠ HỒ làm phương án cuối "chưa kiểm chứng" (tên + "?") — kiểm
 * chứng fd giả có thể loại oan trên ROM lạ (ném lỗi khác EBADF), trong
 * khi ioctl thật trên fd thiết bị vẫn có thể chạy được. Lỗi CẤU TRÚC
 * (sai chữ ký, thiếu method/field/class) đã chứng minh gọi sai nên bị
 * loại khỏi ứng viên. Chỉ trả "none" khi không còn ứng viên nào.
 */
object IoctlReflectionResolver {

    /** Kết quả dò: invoker (null khi "none") + tên strategy. */
    data class Resolved<Fn>(val invoker: Fn?, val strategyName: String)

    /**
     * Thử từng attempt theo thứ tự.
     * @param attempts danh sách (tên strategy, hàm xây invoker).
     * @param verify gọi thử invoker trên fd không hợp lệ (FileDescriptor()).
     * @param logTag tiền tố log ("EVIOCGBIT", "GRAB").
     */
    fun <Fn> resolve(
        attempts: List<Pair<String, () -> Fn>>,
        verify: (Fn) -> Unit,
        logTag: String,
    ): Resolved<Fn> {
        val failures = ArrayList<String>()
        var firstCandidate: Pair<String, Fn>? = null
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
                verify(fn)
                null
            } catch (e: Exception) {
                e
            } catch (e: Error) {
                e
            }
            if (verifyError == null || extractErrno(verifyError) != null) {
                // Không ném, hoặc ném lỗi errno (vd EBADF) — gọi được tới kernel.
                Server.log("$logTag: dùng ioctl dạng $name.")
                return Resolved(fn, name)
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
        return if (fallback != null) {
            Server.log(
                "$logTag: không strategy nào vượt qua kiểm chứng fd=-1 " +
                    "(${failures.joinToString(", ")}) -> dùng ${fallback.first} chưa kiểm chứng.",
            )
            Resolved(fallback.second, fallback.first + "?")
        } else {
            Server.log("$logTag: không dò được ioctl nào (${failures.joinToString(", ")}) -> không dùng ioctl.")
            Resolved(null, "none")
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
}

package com.example.mydung.server

import java.io.File
import java.io.FileOutputStream

/**
 * Tiêm cảm ứng bằng cách ghi event thô thẳng vào node cảm ứng thật
 * (GĐ6, học theo GG Mouse Pro). Không dùng injectInputEvent, không
 * Accessibility, không uinput (không tạo thiết bị ảo), không API ẩn.
 *
 * - Tìm node theo khả năng (Evdev.findTouchNode), KHÔNG hardcode eventN.
 * - Chỉ mở node khi người dùng chuyển sang chế độ này (lệnh INJECT evdev);
 *   chuyển về reflect thì đóng lại.
 * - Ghi lỗi -> đóng stream, available=false, không sập Server (người dùng
 *   chuyển về reflect để tiêm tiếp).
 * - Hủy khẩn: TouchHub.cancelAll đi qua inject() như thường; đặc tả
 *   ACTION_CANCEL dịch thành TRACKING_ID=-1 cho mọi ngón + BTN_TOUCH=0.
 *
 * CHƯA CHẮC: tiến trình shell có ghi được /dev/input/eventX không (lý
 * thuyết là được vì cùng nhóm input như đọc, nhưng chưa kiểm chứng trên
 * Y700/ZUI). Hạn chế: ngón thật và ngón tiêm dùng chung slot 0..9 — đang
 * bật MAP thì đừng chạm tay thật lên màn hình, kẻo slot đụng nhau.
 */
object RawEvdevInjector : TouchInjector {
    override val name: String = InjectorMode.EVDEV.key

    @Volatile
    override var available = false
        private set

    @Volatile
    override var errorCount = 0L
        private set
    private var loggedErrors = 0

    @Volatile
    private var stream: FileOutputStream? = null
    @Volatile
    private var node: Evdev.TouchNode? = null

    @Volatile
    private var displayW = 0

    @Volatile
    private var displayH = 0
    private var probed = false

    /** Kích thước màn hình (pixel) để đổi tọa độ sang khoảng ABS. */
    fun setDisplay(width: Int, height: Int) {
        displayW = width
        displayH = height
    }

    /** Node cảm ứng đang dùng (để log/debug). Null khi chưa dò được. */
    fun currentNode(): String? = node?.path

    /**
     * Tìm node + mở để ghi. An toàn khi gọi nhiều lần.
     * Khoảng ABS lấy qua `getevent -p <node>` (lệnh này tự gọi EVIOCGABS);
     * file /sys/.../capabilities/abs chỉ chứa bitmap nên không dùng được.
     */
    @Synchronized
    override fun probe(): Boolean {
        if (probed && available) return true
        probed = true
        closeLocked()
        available = false
        try {
            val procText = File("/proc/bus/input/devices").readText(Charsets.UTF_8)
            val found = Evdev.findTouchNode(procText) { nodePath ->
                Shell.runOutput("/system/bin/getevent -p $nodePath")
            } ?: run {
                Server.log("EVDEV: không tìm được node cảm ứng đa điểm trong /proc/bus/input/devices.")
                return false
            }
            stream = FileOutputStream(File(found.path))
            node = found
            available = true
            Server.log(
                "EVDEV: mở được ${found.path} " +
                    "(X ${found.xRange.min}..${found.xRange.max}, " +
                    "Y ${found.yRange.min}..${found.yRange.max}).",
            )
        } catch (e: Exception) {
            available = false
            Server.log("EVDEV: không mở được node cảm ứng (${e.javaClass.simpleName}: ${e.message}).")
        }
        return available
    }

    override fun inject(spec: TouchEngine.MotionEventSpec, displayId: Int): Boolean {
        val n = node
        if (!available || n == null) return false
        if (displayW <= 0 || displayH <= 0) {
            recordError("chưa có kích thước màn hình (cần DISPLAY trước)")
            return false
        }
        val events = Evdev.translateSpec(spec, displayW, displayH, n.xRange, n.yRange)
        if (events.isEmpty()) {
            // TouchEngine không bao giờ sinh đặc tả rỗng nên đây là lỗi,
            // không phải action lạ: báo lỗi thay vì im lặng trả true.
            recordError("đặc tả không dịch được (action=${spec.action}, pointers=${spec.pointers.size})")
            return false
        }
        val bytes = Evdev.encodeFrame(events, System.currentTimeMillis())
        // Ghi dưới cùng khóa object với probe()/close() để close() không
        // đóng stream ngay lúc đang ghi.
        synchronized(this) {
            val s = stream ?: return false
            try {
                s.write(bytes)
                s.flush()
            } catch (e: Exception) {
                recordError("ghi ${n.path} lỗi (${e.javaClass.simpleName}: ${e.message})")
                // Ghi lỗi: đóng stream, đánh dấu không khả dụng để người
                // dùng biết mà chuyển về reflect; không sập Server.
                closeLocked()
                available = false
                return false
            }
        }
        return true
    }

    /**
     * Hủy khẩn cấp toàn bộ slot 0..9 (không qua engine): dùng khi dừng
     * Server / đổi injector để không còn slot nào kẹt, kể cả slot mà
     * engine đã mất dấu. Cũng nhả ngón tay thật đang chạm nên chỉ gọi
     * ở những lúc đó.
     */
    fun releaseAllSlots() {
        if (!available) return
        val bytes = Evdev.encodeFrame(
            Evdev.releaseAllSlotsEvents(),
            System.currentTimeMillis(),
        )
        synchronized(this) {
            try {
                stream?.write(bytes)
                stream?.flush()
            } catch (e: Exception) {
                recordError("releaseAllSlots lỗi (${e.javaClass.simpleName}: ${e.message})")
                closeLocked()
                available = false
            }
        }
    }

    @Synchronized
    override fun close() {
        closeLocked()
        available = false
    }

    private fun closeLocked() {
        try {
            stream?.close()
        } catch (_: Exception) {
        }
        stream = null
        node = null
    }

    private fun recordError(msg: String) {
        errorCount++
        // Ghi 5 lỗi đầu đầy đủ, sau đó cứ 50 lỗi ghi 1 dòng để khỏi đầy log.
        if (loggedErrors < 5 || errorCount % 50L == 0L) {
            loggedErrors++
            Server.log("EVDEV LỖI ($errorCount): $msg")
        }
    }
}

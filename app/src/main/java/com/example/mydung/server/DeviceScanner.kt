package com.example.mydung.server

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.WatchService

/**
 * Quét /proc/bus/input/devices mỗi Config.DEVICE_SCAN_INTERVAL_MS, mở luồng đọc
 * cho thiết bị USB mới, đóng luồng khi thiết bị bị rút (kết thúc êm, không sập).
 *
 * Ngoài vòng quét định kỳ, còn lắng nghe inotify trên /dev/input (cắm/rút
 * OTG tạo/xóa node event*) để quét lại NGAY thay vì đợi tới 2s — lúc MAP
 * đang bật, chuột/phím cắm thêm được GRAB lại gần như tức thì. inotify
 * lỗi (không watch được /dev) thì vòng quét 2s vẫn làm dự phòng.
 */
class DeviceScanner(
    private val onEvent: (InputEvent) -> Unit,
    private val onDeviceAdded: (InputDeviceInfo) -> Unit = {},
) {
    @Volatile
    private var running = false
    private var scanThread: Thread? = null
    private var watchThread: Thread? = null
    private var watchService: WatchService? = null
    private val readers = HashMap<String, DeviceReader>() // key = id (event<N>)
    private val lock = Any()

    fun devices(): List<InputDeviceInfo> = synchronized(lock) { readers.values.map { it.info } }

    fun readerFor(id: String): DeviceReader? = synchronized(lock) { readers[id] }

    /**
     * Đóng reader rồi mở lại ngay. Dùng để nhả GRAB: kernel tự nhả khi
     * fd đóng (không nhả được bằng ioctl — xem Grabber). Mở lại ngay nên
     * gần như không mất event; KHÔNG phát onDeviceAdded (tránh re-grab
     * ngay lúc đang nhả) và không phát EV DEV (thiết bị không hề biến mất).
     * Trả false nếu thiết bị không còn trong map hoặc mở lại thất bại
     * (thiết bị rút đúng lúc đó — vòng quét sẽ thêm lại sau).
     */
    fun reopenReader(id: String): Boolean {
        synchronized(lock) {
            val old = readers.remove(id) ?: return false
            val info = old.info
            old.close()
            val reader = DeviceReader(info, onEvent)
            if (!reader.open()) {
                Server.log("Mở lại ${info.node} sau khi nhả GRAB thất bại.")
                return false
            }
            readers[id] = reader
            reader.start()
            return true
        }
    }

    fun start() {
        if (running) return
        running = true
        scanThread = Thread({
            while (running) {
                try {
                    scanOnce()
                } catch (e: Exception) {
                    Server.log("Quét thiết bị lỗi: ${e.message}")
                }
                try {
                    Thread.sleep(Config.DEVICE_SCAN_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "DeviceScanner").apply { isDaemon = true; start() }
        startWatchThread()
    }

    fun stop() {
        running = false
        scanThread?.interrupt()
        // Đóng WatchService trước để take() đang chặn bung ra (interrupt
        // không đánh thức được take()).
        try {
            watchService?.close()
        } catch (_: Exception) {
        }
        watchService = null
        watchThread?.interrupt()
        synchronized(lock) {
            readers.values.forEach { it.close() }
            readers.clear()
        }
    }

    /**
     * Lắng nghe inotify trên /dev/input: node event* xuất hiện/biến mất thì
     * quét lại ngay (re-grab chuột/phím cắm thêm lúc MAP đang bật). Lỗi
     * khởi tạo thì bỏ qua lặng lẽ — vòng quét 2s vẫn chạy.
     */
    private fun startWatchThread() {
        val ws = try {
            FileSystems.getDefault().newWatchService().also { svc ->
                File("/dev/input").toPath().register(svc, ENTRY_CREATE, ENTRY_DELETE)
            }
        } catch (e: Exception) {
            Server.log("Không lắng nghe được /dev/input (inotify lỗi: ${e.message}) — chỉ dùng quét 2s.")
            return
        }
        watchService = ws
        watchThread = Thread({
            try {
                while (running) {
                    val key = try {
                        ws.take()
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        // WatchService đã đóng (stop()) hoặc lỗi: thoát.
                        break
                    }
                    key.pollEvents()
                    key.reset()
                    // /proc/bus/input/devices có thể cập nhật chậm hơn node
                    // /dev/input một nhịp: quét ngay, vòng quét 2s sẽ bắt
                    // lại nếu lần này chưa thấy thiết bị mới.
                    try {
                        scanOnce()
                    } catch (e: Exception) {
                        Server.log("Quét thiết bị (inotify) lỗi: ${e.message}")
                    }
                }
            } finally {
                try {
                    ws.close()
                } catch (_: Exception) {
                }
            }
        }, "DeviceWatcher").apply { isDaemon = true; start() }
    }

    fun scanOnce() {
        val text = try {
            File("/proc/bus/input/devices").readText()
        } catch (e: Exception) {
            Server.log("Quét thiết bị: không đọc được /proc/bus/input/devices: ${e.message}")
            return
        }
        val found = InputEventParser.parseDevices(text)
        val foundIds = found.map { it.id }.toSet()
        val added = ArrayList<InputDeviceInfo>()
        synchronized(lock) {
            // Thiết bị bị rút
            val removedIds = readers.keys.filter { it !in foundIds }
            for (id in removedIds) {
                val r = readers.remove(id) ?: continue
                r.close()
                onEvent(InputEvent.Device(false, r.info))
                Server.log("Thiết bị rút: ${r.info.name} (${r.info.node})")
            }
            // Luồng đọc đã chết nhưng thiết bị vẫn còn (rút/cắm nhanh, lỗi đọc):
            // đóng rồi mở lại, không phát EV DEV (thiết bị không hề biến mất).
            val deadIds = readers.keys.filter { it in foundIds && readers[it]?.isAlive() == false }
            for (id in deadIds) {
                val r = readers.remove(id) ?: continue
                r.close()
                Server.log("Luồng đọc ${r.info.node} đã dừng, sẽ mở lại.")
            }
            // Thiết bị mới (gồm cả mở lại)
            for (info in found) {
                if (info.id in readers) continue
                val reader = DeviceReader(info, onEvent)
                if (reader.open()) {
                    readers[info.id] = reader
                    reader.start()
                    if (info.id in deadIds) {
                        Server.log("Đã mở lại ${info.node} (${info.name}).")
                    } else {
                        onEvent(InputEvent.Device(true, info))
                        Server.log("Thiết bị mới: ${info.name} (${info.node}, ${info.kind})")
                        added.add(info)
                    }
                }
            }
        }
        // Gọi callback ngoài lock để tránh deadlock (callback có thể gọi lại scanner).
        added.forEach { onDeviceAdded(it) }
    }
}

/** 1 luồng đọc chặn trên FileInputStream của 1 thiết bị. */
class DeviceReader(val info: InputDeviceInfo, private val onEvent: (InputEvent) -> Unit) {
    private var stream: FileInputStream? = null
    private var thread: Thread? = null

    @Volatile
    private var open = false
    private val assembler = FrameAssembler()

    /** FileDescriptor để GRAB. Null nếu chưa mở / đã đóng. */
    val fd: FileDescriptor?
        get() = try {
            stream?.fd
        } catch (_: Exception) {
            null
        }

    fun open(): Boolean {
        return try {
            stream = FileInputStream(File(info.node))
            open = true
            true
        } catch (e: Exception) {
            // Tiến trình shell thường đọc được /dev/input (nhóm input, như getevent).
            // Nếu không đọc được thì báo rõ lỗi để người dùng biết.
            Server.log("Không mở được ${info.node} (${info.name}): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun start() {
        val s = stream ?: return
        thread = Thread({
            val buf = ByteArray(InputEventParser.INPUT_EVENT_SIZE)
            try {
                while (open) {
                    if (!readFully(s, buf)) break // EOF / thiết bị rút
                    val raw = InputEventParser.parse(buf) ?: continue
                    assembler.feed(raw) { onEvent(it) }
                }
            } catch (e: Exception) {
                if (open) Server.log("Lỗi đọc ${info.node}: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Thiết bị rút / luồng kết thúc: nhả giả mọi phím đang đè.
                assembler.releaseAll(System.currentTimeMillis()) { onEvent(it) }
            }
        }, "InputReader-${info.id}").apply { isDaemon = true; start() }
    }

    /** Luồng đọc còn sống không (để scanner mở lại nếu luồng chết). */
    fun isAlive(): Boolean = thread?.isAlive == true

    /** Đóng fd (hệ thống tự nhả GRAB nếu có) và dừng luồng đọc. */
    fun close() {
        open = false
        try {
            stream?.close()
        } catch (_: Exception) {
        }
        thread?.interrupt()
    }

    /** Đọc đủ 24 byte (xử lý đọc thiếu). False khi EOF/lỗi. */
    private fun readFully(s: FileInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = try {
                s.read(buf, off, buf.size - off)
            } catch (_: Exception) {
                return false
            }
            if (n == -1) return false
            off += n
        }
        return true
    }
}

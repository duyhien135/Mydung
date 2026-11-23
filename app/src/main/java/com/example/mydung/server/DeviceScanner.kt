package com.example.mydung.server

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.WatchService

/**
 * Chẩn đoán lần quét thiết bị gần nhất (build-13, lỗi devices=0) — hiện
 * qua MAPSTATUS diag= + log Server để biết Server kẹt ở bước nào: đọc
 * /proc, parse, phân loại, hay mở fd.
 */
data class ScanDiagnostics(
    /** Đọc được /proc/bus/input/devices không. */
    val procReadOk: Boolean = false,
    /** Số khối thiết bị trong /proc (kể cả thiết bị nội đã bị lọc). */
    val procBlocks: Int = 0,
    /** Số thiết bị parseDevices phân loại được (đường /proc). */
    val parsedCount: Int = 0,
    /** Số node event* trên đĩa (/dev/input). */
    val nodesOnDisk: Int = 0,
    /** Lần quét này có thử fallback EVIOCGBIT không. */
    val fallbackUsed: Boolean = false,
    /** Số thiết bị fallback phân loại được ở lần quét này. */
    val fallbackClassified: Int = 0,
    /** Số lần mở fd thất bại ở lần quét này. */
    val openFailed: Int = 0,
    /** Lỗi mở fd gần nhất (ngắn, vd "event5: FileNotFoundException: Permission denied"). */
    val lastOpenError: String = "",
    /** Số thiết bị đang mở được (reader đang sống). */
    val openedCount: Int = 0,
) {
    /** Token 1 từ cho MAPSTATUS diag= (không chứa khoảng trắng). */
    fun token(): String {
        val proc = if (procReadOk) "$procBlocks" else "ERR"
        return "proc:$proc" +
            "_parsed:$parsedCount" +
            "_nodes:$nodesOnDisk" +
            "_open:$openedCount" +
            "_err:$openFailed" +
            "_fb:$fallbackClassified"
    }
}

/**
 * Quét /proc/bus/input/devices mỗi Config.DEVICE_SCAN_INTERVAL_MS, mở luồng đọc
 * cho thiết bị USB mới, đóng luồng khi thiết bị bị rút (kết thúc êm, không sập).
 *
 * Ngoài vòng quét định kỳ, còn lắng nghe inotify trên /dev/input (cắm/rút
 * OTG tạo/xóa node event*) để quét lại NGAY thay vì đợi tới 2s — lúc MAP
 * đang bật, chuột/phím cắm thêm được GRAB lại gần như tức thì. inotify
 * lỗi (không watch được /dev) thì vòng quét 2s vẫn làm dự phòng.
 *
 * Build-13 (lỗi devices=0 trên máy thật): nếu /proc parse ra 0 thiết bị
 * hoặc có node trên đĩa mà /proc không phân loại được, quét thêm bằng
 * fallback liệt kê trực tiếp /dev/input/event* + phân loại bằng
 * EVIOCGBIT (kiến trúc GG Mouse Pro 3, xem EvdevCaps) — bắt được chuột/
 * phím mà classify /proc bỏ sót (thiếu BTN_LEFT, thiếu handler kbd...).
 * Mỗi lần quét ghi chẩn đoán chi tiết vào log + [ScanDiagnostics].
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

    /** Chẩn đoán lần quét gần nhất (app đọc qua MAPSTATUS diag=). */
    @Volatile
    private var lastDiagnostics = ScanDiagnostics()

    /** Tóm tắt lần quét đã log gần nhất — chỉ log lại khi số liệu đổi (đỡ spam log mỗi 2s). */
    @Volatile
    private var lastSummaryLogged: String? = null

    /** Node fallback đã thử probe mà bị loại (id -> lần thử gần nhất, ms) —
     *  chỉ thử lại sau Config.FALLBACK_REPROBE_MS. */
    private val rejectedFallback = HashMap<String, Long>()

    /** Đã log cảnh báo "EVIOCGBIT đọc về 0" chưa (chỉ log 1 lần). */
    @Volatile
    private var zeroMaskLogged = false

    fun devices(): List<InputDeviceInfo> = synchronized(lock) { readers.values.map { it.info } }

    fun readerFor(id: String): DeviceReader? = synchronized(lock) { readers[id] }

    /** Chẩn đoán lần quét gần nhất (cho MAPSTATUS diag=). */
    fun diagnostics(): ScanDiagnostics = lastDiagnostics

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
        // 1. Đọc + parse /proc (đường chính).
        var procReadOk = false
        var procBlocks = 0
        var parsed: List<InputDeviceInfo> = emptyList()
        try {
            val text = File("/proc/bus/input/devices").readText()
            procReadOk = true
            procBlocks = text.split("\n\n").count { it.isNotBlank() }
            parsed = InputEventParser.parseDevices(text)
        } catch (e: Exception) {
            Server.log("Quét thiết bị: không đọc được /proc/bus/input/devices: ${e.message}")
        }
        // 2. Liệt kê node trên đĩa (cho fallback + chẩn đoán).
        val diskNodes: List<File> = try {
            File("/dev/input").listFiles { f -> f.name.startsWith("event") }?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val diskIds = diskNodes.map { it.name }.toSet()
        val foundIds = parsed.map { it.id }.toSet()

        var openFailed = 0
        var lastOpenError = ""
        var fallbackUsed = false
        var fallbackClassified = 0
        var opened = 0
        val added = ArrayList<InputDeviceInfo>()
        synchronized(lock) {
            // Thiết bị bị rút: không còn trong /proc VÀ không còn node
            // trên đĩa (thiết bị fallback không có trong /proc nên chỉ
            // xét node đĩa — còn node là còn thiết bị).
            val removedIds = readers.keys.filter { it !in foundIds && it !in diskIds }
            for (id in removedIds) {
                val r = readers.remove(id) ?: continue
                r.close()
                onEvent(InputEvent.Device(false, r.info))
                Server.log("Thiết bị rút: ${r.info.name} (${r.info.node})")
            }
            // Luồng đọc đã chết nhưng thiết bị vẫn còn (rút/cắm nhanh, lỗi đọc):
            // đóng rồi mở lại, không phát EV DEV (thiết bị không hề biến mất).
            val aliveIds = foundIds + diskIds
            val deadIds = readers.keys.filter { it in aliveIds && readers[it]?.isAlive() == false }
            for (id in deadIds) {
                val r = readers.remove(id) ?: continue
                r.close()
                Server.log("Luồng đọc ${r.info.node} đã dừng, sẽ mở lại.")
            }
            // Thiết bị mới từ /proc (gồm cả mở lại).
            for (info in parsed) {
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
                } else {
                    openFailed++
                    reader.lastOpenError?.let { lastOpenError = "${info.node}: $it" }
                }
            }
            // 3. Fallback: node trên đĩa mà /proc không phân loại được ->
            // thử mở + phân loại bằng EVIOCGBIT (kiến trúc GG Mouse Pro 3).
            // Chỉ thử node chưa mở, chưa có trong /proc, và chưa bị loại
            // gần đây (throttle 30s). Không dò được ioctl thì bỏ qua.
            if (EvdevCaps.isProbingAvailable()) {
                val nowMs = System.currentTimeMillis()
                for (node in diskNodes) {
                    val id = node.name
                    if (id in readers || id in foundIds) continue
                    val lastTry = rejectedFallback[id] ?: 0L
                    if (nowMs - lastTry < Config.FALLBACK_REPROBE_MS) continue
                    fallbackUsed = true
                    rejectedFallback[id] = nowMs
                    val info = probeFallbackNode(node)
                    if (info == null) continue
                    rejectedFallback.remove(id)
                    val reader = DeviceReader(info, onEvent)
                    if (reader.open()) {
                        readers[id] = reader
                        reader.start()
                        onEvent(InputEvent.Device(true, info))
                        Server.log("Thiết bị mới (fallback EVIOCGBIT): ${info.name} (${info.node}, ${info.kind})")
                        added.add(info)
                        fallbackClassified++
                    } else {
                        openFailed++
                        reader.lastOpenError?.let { lastOpenError = "${info.node}: $it" }
                    }
                }
            }
            // Dọn danh sách loại cho node đã biến mất khỏi đĩa.
            rejectedFallback.keys.retainAll(diskIds)
            opened = readers.size
        }
        // 4. Cập nhật chẩn đoán + log tóm tắt khi số liệu đổi.
        lastDiagnostics = ScanDiagnostics(
            procReadOk = procReadOk,
            procBlocks = procBlocks,
            parsedCount = parsed.size,
            nodesOnDisk = diskNodes.size,
            fallbackUsed = fallbackUsed,
            fallbackClassified = fallbackClassified,
            openFailed = openFailed,
            lastOpenError = lastOpenError,
            openedCount = opened,
        )
        logScanSummary(procReadOk, procBlocks, parsed.size, diskNodes.size, opened, openFailed, lastOpenError, fallbackClassified)
        // Gọi callback ngoài lock để tránh deadlock (callback có thể gọi lại scanner).
        added.forEach { onDeviceAdded(it) }
    }

    /**
     * Thử mở 1 node /dev/input/event* rồi phân loại bằng EVIOCGBIT.
     * Trả InputDeviceInfo nếu là chuột/bàn phím, null nếu không phải
     * hoặc mở/probe lỗi. Chỉ mở để probe rồi đóng ngay — DeviceReader
     * sẽ mở lại khi thêm thiết bị. Tên đọc từ sysfs (không đọc được
     * thì dùng tên node); tên nội (cảm ứng, nút nguồn...) thì loại.
     */
    private fun probeFallbackNode(node: File): InputDeviceInfo? {
        val stream = try {
            FileInputStream(node)
        } catch (e: Exception) {
            Server.log("Fallback: không mở được ${node.path}: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        try {
            val fd = try {
                stream.fd
            } catch (_: Exception) {
                return null
            }
            return when (val r = EvdevCaps.probe(fd)) {
                is EvdevCaps.ProbeResult.Ok -> {
                    val kind = EvdevCaps.classify(r.masks) ?: return null
                    val name = readSysfsName(node.name) ?: node.name
                    if (InputEventParser.isInternalName(name)) return null
                    InputDeviceInfo(node.name, name, node.path, kind)
                }
                EvdevCaps.ProbeResult.ZeroMask -> {
                    if (!zeroMaskLogged) {
                        zeroMaskLogged = true
                        Server.log("Fallback: EVIOCGBIT đọc về 0 — libcore có thể không trả lại giá trị ioctl, bỏ qua phân loại EVIOCGBIT.")
                    }
                    null
                }
                EvdevCaps.ProbeResult.IoctlFailed -> null
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Tên thiết bị từ sysfs (/sys/class/input/eventN/device/name), null nếu không đọc được. */
    private fun readSysfsName(id: String): String? = try {
        File("/sys/class/input/$id/device/name").readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Log tóm tắt 1 lần quét — chỉ log lại khi số liệu đổi (đỡ spam log
     * mỗi 2s), kèm gợi ý theo từng kiểu kẹt để lần test sau biết đường.
     */
    private fun logScanSummary(
        procReadOk: Boolean, procBlocks: Int, parsed: Int, nodesOnDisk: Int,
        opened: Int, openFailed: Int, lastOpenError: String, fallbackClassified: Int,
    ) {
        val summary = "Quét: /proc ${if (procReadOk) "$procBlocks khối" else "ĐỌC LỖI"}" +
            ", parse $parsed, đĩa $nodesOnDisk node, đang mở $opened" +
            ", mở lỗi $openFailed, fallback $fallbackClassified" +
            if (lastOpenError.isNotBlank()) ", lỗi cuối: ${lastOpenError.take(80)}" else ""
        if (summary == lastSummaryLogged) return
        lastSummaryLogged = summary
        Server.log(summary)
        if (!procReadOk) {
            Server.log("Gợi ý: không đọc được /proc/bus/input/devices — kiểm tra quyền của tiến trình Server.")
        } else if (parsed == 0 && nodesOnDisk > 0 && fallbackClassified == 0) {
            Server.log("Gợi ý: /proc parse ra 0 thiết bị nhưng /dev/input có $nodesOnDisk node — fallback EVIOCGBIT cũng không phân loại được node nào (xem strategy EVIOCGBIT ở đầu log).")
        }
        if (openFailed > 0 && opened == 0) {
            Server.log("Gợi ý: mở fd thất bại cho mọi thiết bị — kiểm tra UID/nhóm input/SELinux (xem dòng UID + Nhóm ở đầu log).")
        }
    }
}

/** 1 luồng đọc chặn trên FileInputStream của 1 thiết bị. */
class DeviceReader(val info: InputDeviceInfo, private val onEvent: (InputEvent) -> Unit) {
    private var stream: FileInputStream? = null
    private var thread: Thread? = null

    @Volatile
    private var open = false
    private val assembler = FrameAssembler()

    /** Lỗi mở gần nhất (để DeviceScanner đưa vào chẩn đoán). Null nếu mở OK. */
    var lastOpenError: String? = null
        private set

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
            lastOpenError = null
            true
        } catch (e: Exception) {
            // Tiến trình shell thường đọc được /dev/input (nhóm input, như getevent).
            // Nếu không đọc được thì báo rõ lỗi để người dùng biết.
            lastOpenError = "${e.javaClass.simpleName}: ${e.message}"
            Server.log("Không mở được ${info.node} (${info.name}): $lastOpenError")
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

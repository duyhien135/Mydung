package com.example.mydung.server

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Server chạy độc lập bằng quyền shell thông qua app_process.
 * LƯU Ý: Tuyệt đối KHÔNG sử dụng Context hoặc Android components (Activity, Service,...).
 */
@Keep
object Server {

    // Hằng số dùng chung ở Config (GĐ3 gom về 1 file cấu hình).
    private const val IDLE_TIMEOUT_MS = Config.IDLE_TIMEOUT_MS

    private val startTimeMs = System.currentTimeMillis()
    @Volatile
    private var lastActivityTimeMs = System.currentTimeMillis()
    private val isRunning = AtomicBoolean(true)

    private var serverPort: Int = 0
    private var authToken: String = ""
    private var apkPath: String = ""
    private var appVersionCode: Long = 0

    private var serverSocket: ServerSocket? = null

    // Số kết nối SUB_INPUT đang mở. Về 0 -> nhả mọi GRAB (InputHub.onAppDisconnected).
    private val activeSubscribers = AtomicInteger(0)

    // Ghi log "nhận kết nối đầu tiên" 1 lần duy nhất (chẩn đoán: có dòng
    // này nghĩa là app đã tới được Server + bắt tay OK).
    @Volatile
    private var firstConnectionLogged = false

    @JvmStatic
    fun main(args: Array<String>) {
        // Dòng đầu tiên: chứng tỏ dex đã load được + class Server đã chạy
        // (nếu log không có dòng này thì lỗi ở lệnh khởi động/CLASSPATH,
        // không phải lỗi trong Server).
        log("Server.main đã chạy (nhận ${args.size} tham số).")
        if (args.size < 4) {
            log("LỖI: Thiếu tham số dòng lệnh. Cần: <port> <token> <apkPath> <versionCode>")
            exitProcess(1)
        }

        try {
            serverPort = args[0].toInt()
            authToken = args[1]
            apkPath = args[2]
            appVersionCode = args[3].toLong()
        } catch (e: Exception) {
            log("LỖI: Tham số không hợp lệ: ${e.message}")
            exitProcess(1)
        }

        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()

        log("==========================================")
        log("Server đang khởi động...")
        log("PID=$myPid, UID=$myUid, Port=$serverPort, VersionCode=$appVersionCode")
        log("Đường dẫn APK: $apkPath")
        log("==========================================")

        // Đăng ký Shutdown Hook để đóng socket sạch sẽ
        Runtime.getRuntime().addShutdownHook(Thread {
            log("Nhận tín hiệu dừng (Shutdown Hook) -> nhả GRAB, hủy cảm ứng, đóng socket.")
            try {
                TouchHub.stop()
            } catch (_: Exception) {}
            try {
                ForegroundWatcher.stop()
            } catch (_: Exception) {}
            try {
                InputHub.stop()
            } catch (_: Exception) {}
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
        })

        // 1. Bind ServerSocket chỉ trên loopback 127.0.0.1
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverPort))
            serverSocket = socket
            log("ServerSocket đã lắng nghe thành công trên 127.0.0.1:$serverPort")
        } catch (e: Exception) {
            log("LỖI BIND CỔNG $serverPort: ${e.message}. Cổng có thể bị chiếm -> Server tự thoát ngay.")
            exitProcess(2)
        }

        // 2. Khởi động InputHub (GĐ3: đọc bàn phím/chuột, GRAB).
        try {
            InputHub.start()
            log("InputHub đã khởi động OK.")
        } catch (e: Exception) {
            log("Cảnh báo: InputHub khởi động lỗi (${e.message}). Server vẫn chạy, chỉ không có tính năng nhập.")
        }

        // 2b. Khởi động TouchHub (GĐ4: tiêm cảm ứng đa điểm).
        try {
            TouchHub.start()
            log("TouchHub đã khởi động OK.")
        } catch (e: Exception) {
            log("Cảnh báo: TouchHub khởi động lỗi (${e.message}). Server vẫn chạy, chỉ không tiêm được cảm ứng.")
        }

        // 2c. Khởi động ForegroundWatcher (GĐ6: tự đổi profile theo game).
        try {
            ForegroundWatcher.start()
            log("ForegroundWatcher đã khởi động OK.")
        } catch (e: Exception) {
            log("Cảnh báo: ForegroundWatcher khởi động lỗi (${e.message}). Server vẫn chạy, chỉ không tự đổi profile.")
        }

        // 3. Khởi động luồng giám sát (Watchdog: idle timeout & apk existence)
        startWatchdogThread()

        // 4. Vòng lặp nhận kết nối từ Client (App)
        log("Sẵn sàng nhận kết nối từ app...")
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                Thread {
                    handleClientConnection(clientSocket, myPid, myUid)
                }.start()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    log("Lỗi accept socket: ${e.message}")
                }
            }
        }

        log("Server vòng lặp kết thúc. Tạm biệt.")
        exitProcess(0)
    }

    private fun handleClientConnection(socket: Socket, pid: Int, uid: Int) {
        val clientAddress = socket.remoteSocketAddress
        val writeLock = Any()
        var subscriber: Subscriber? = null
        var fgSubscriber: FgSubscriber? = null
        try {
            socket.soTimeout = 15_000 // 15 giây timeout đọc cho mỗi yêu cầu

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            fun reply(s: String) = synchronized(writeLock) { writer.println(s) }

            var isAuthenticated = false

            while (isRunning.get()) {
                val line = try {
                    reader.readLine()
                } catch (_: SocketTimeoutException) {
                    // Hết timeout đọc một lệnh, đóng kết nối để giải phóng tài nguyên
                    break
                } ?: break // Client đóng socket

                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // Dòng đầu tiên BẮT BUỘC là AUTH <token>
                if (!isAuthenticated) {
                    if (trimmed.startsWith("AUTH ")) {
                        val providedToken = trimmed.substring(5).trim()
                        if (providedToken == authToken) {
                            isAuthenticated = true
                            lastActivityTimeMs = System.currentTimeMillis()
                            InputHub.noteAppActivity()
                            if (!firstConnectionLogged) {
                                firstConnectionLogged = true
                                log("Nhận kết nối đầu tiên từ app (AUTH OK).")
                            }
                            reply("OK")
                        } else {
                            log("Cảnh báo: Xác thực thất bại từ $clientAddress. Đóng kết nối.")
                            Thread.sleep(1000) // Trì hoãn 1s chống dò token
                            reply("ERR auth_failed")
                            break
                        }
                    } else {
                        log("Cảnh báo: Lệnh đầu tiên không phải AUTH từ $clientAddress")
                        Thread.sleep(1000)
                        reply("ERR auth_required")
                        break
                    }
                    continue
                }

                // Khi đã xác thực, cập nhật thời gian hoạt động
                lastActivityTimeMs = System.currentTimeMillis()
                InputHub.noteAppActivity()

                when {
                    trimmed == "PING" -> {
                        val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000
                        reply("PONG uid=$uid pid=$pid version=$appVersionCode uptime=$uptimeSec")
                    }
                    trimmed == "STOP" -> {
                        log("Nhận lệnh STOP từ app -> Dừng Server.")
                        reply("BYE")
                        TouchHub.stop()
                        ForegroundWatcher.stop()
                        InputHub.stop()
                        isRunning.set(false)
                        try {
                            serverSocket?.close()
                        } catch (_: Exception) {}
                        exitProcess(0)
                    }
                    trimmed == "DEVICES" -> {
                        for (info in InputHub.devices()) reply(InputProtocol.formatDevice(info))
                        reply("END")
                    }
                    trimmed == "SUB_INPUT" -> {
                        // Trả OK trước, rồi mới tạo Subscriber: tránh sự kiện
                        // chèn vào trước dòng OK làm app đọc handshake sai.
                        reply("OK SUBSCRIBED")
                        if (subscriber == null) {
                            socket.soTimeout = 0 // kết nối stream: không timeout đọc
                            val sub = Subscriber(writer, writeLock).also { it.start() }
                            subscriber = sub
                            activeSubscribers.incrementAndGet()
                            // Gửi thiết bị hiện có trước để app có danh sách ngay.
                            for (info in InputHub.devices()) {
                                sub.offer(InputProtocol.format(InputEvent.Device(true, info)))
                            }
                        }
                    }
                    trimmed == "UNSUB_INPUT" -> {
                        if (subscriber != null) {
                            subscriber?.close()
                            subscriber = null
                            if (activeSubscribers.decrementAndGet() <= 0) InputHub.onAppDisconnected()
                        }
                        if (fgSubscriber == null) socket.soTimeout = 15_000
                        reply("OK UNSUBSCRIBED")
                    }
                    trimmed == "SUB_FG" -> {
                        // GĐ6: stream riêng cho sự kiện foreground đổi
                        // ("FG <package>"). Không tính vào activeSubscribers:
                        // đóng kết nối này KHÔNG nhả GRAB.
                        reply("OK SUBSCRIBED_FG")
                        if (fgSubscriber == null) {
                            socket.soTimeout = 0 // kết nối stream: không timeout đọc
                            fgSubscriber = FgSubscriber(writer, writeLock).also { it.start() }
                        }
                    }
                    trimmed == "UNSUB_FG" -> {
                        fgSubscriber?.close()
                        fgSubscriber = null
                        if (subscriber == null) socket.soTimeout = 15_000
                        reply("OK UNSUBSCRIBED_FG")
                    }
                    trimmed.startsWith("GRAB ") -> {
                        val parsed = InputProtocol.parseGrab(trimmed)
                        if (parsed == null) reply("ERR bad_grab_args")
                        else reply(InputHub.setGrab(parsed.first, parsed.second))
                    }
                    trimmed.startsWith("DISPLAY ") -> {
                        val info = TouchProtocol.parseDisplay(trimmed)
                        if (info == null) reply("ERR bad_display_args")
                        else reply(TouchHub.setDisplay(info))
                    }
                    trimmed.startsWith("MAP ") -> {
                        val on = TouchProtocol.parseMap(trimmed)
                        if (on == null) reply("ERR bad_map_args")
                        else reply(TouchHub.setMap(on))
                    }
                    trimmed == "MAPSTATUS" -> {
                        reply(TouchHub.mapStatus())
                    }
                    trimmed.startsWith("TOUCHCFG ") -> {
                        val values = TouchProtocol.parseTouchCfg(trimmed)
                        if (values == null) reply("ERR bad_touchcfg_args")
                        else reply(TouchHub.applyTouchCfg(values))
                    }
                    trimmed.startsWith("TESTTOUCH ") -> {
                        val test = TouchProtocol.parseTestTouch(trimmed)
                        if (test == null) reply("ERR bad_testtouch_args")
                        else reply(TouchHub.testTouch(test))
                    }
                    trimmed.startsWith("PROFILE ") -> {
                        val json = trimmed.removePrefix("PROFILE ").trim()
                        when (val r = ProfileCodec.fromJson(json)) {
                            is ProfileCodec.Result.Ok -> reply(TouchHub.applyProfile(r.profile))
                            is ProfileCodec.Result.Err -> reply("ERR profile: ${r.message}")
                        }
                    }
                    trimmed.startsWith("INJECT ") -> {
                        val mode = TouchProtocol.parseInject(trimmed)
                        if (mode == null) reply("ERR bad_inject_args")
                        else reply(TouchHub.setInjector(mode))
                    }
                    else -> {
                        reply("ERR unknown_command")
                    }
                }
            }
        } catch (e: Exception) {
            log("Lỗi xử lý kết nối client: ${e.message}")
        } finally {
            if (subscriber != null) {
                subscriber?.close()
                if (activeSubscribers.decrementAndGet() <= 0) InputHub.onAppDisconnected()
            }
            fgSubscriber?.close()
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * 1 kết nối SUB_INPUT: nhận sự kiện từ InputBus qua hàng đợi giới hạn,
     * ghi ra socket trên luồng riêng. Sự kiện chuột cũ bị rớt khi app chậm;
     * phím/nút không rớt (chờ tối đa Config.KEY_EVENT_OFFER_TIMEOUT_MS).
     */
    private class Subscriber(
        private val writer: PrintWriter,
        private val writeLock: Any,
    ) {
        private val queue = LinkedBlockingQueue<String>(Config.EVENT_QUEUE_CAPACITY)

        @Volatile
        private var running = true
        private var thread: Thread? = null

        private val listener = InputBus.Listener { e -> offer(InputProtocol.format(e)) }

        fun start() {
            InputBus.subscribe(listener)
            thread = Thread({
                try {
                    while (running) {
                        val line = queue.take()
                        synchronized(writeLock) { writer.println(line) }
                        if (writer.checkError()) break
                    }
                } catch (_: InterruptedException) {
                    // close() gọi interrupt.
                } catch (_: Exception) {
                    // Socket hỏng: luồng kết thúc, client handler dọn dẹp sau.
                }
            }, "SubscriberWriter").apply { isDaemon = true; start() }
        }

        fun offer(line: String) {
            if (!running) return
            val isMouse = line.startsWith("EV M ") || line.startsWith("EV W ")
            if (isMouse) {
                if (!queue.offer(line)) {
                    // Rớt 1 sự kiện chuột cũ nhất rồi thử lại.
                    val it = queue.iterator()
                    while (it.hasNext()) {
                        val old = it.next()
                        if (old.startsWith("EV M ") || old.startsWith("EV W ")) {
                            it.remove()
                            break
                        }
                    }
                    queue.offer(line)
                }
            } else {
                try {
                    if (!queue.offer(line, Config.KEY_EVENT_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        Server.log("Hàng đợi SUB đầy, rớt sự kiện: $line")
                    }
                } catch (_: InterruptedException) {
                    // Đang đóng, bỏ qua.
                }
            }
        }

        fun close() {
            if (!running) return
            running = false
            InputBus.unsubscribe(listener)
            thread?.interrupt()
        }
    }

    /**
     * 1 kết nối SUB_FG (GĐ6): nhận package foreground đổi qua FgBus, ghi ra
     * socket trên luồng riêng. KHÔNG liên quan tới GRAB (đóng kết nối này
     * không nhả GRAB như SUB_INPUT).
     */
    private class FgSubscriber(
        private val writer: PrintWriter,
        private val writeLock: Any,
    ) {
        private val queue = LinkedBlockingQueue<String>(Config.FG_QUEUE_CAPACITY)

        @Volatile
        private var running = true
        private var thread: Thread? = null

        private val listener = FgBus.Listener { pkg -> offer("FG $pkg") }

        fun start() {
            FgBus.subscribe(listener)
            // Gửi package hiện tại ngay để app biết game đang mở mà không
            // phải đợi lần đổi foreground tiếp theo.
            ForegroundWatcher.currentPackage()?.let { offer("FG $it") }
            thread = Thread({
                try {
                    while (running) {
                        val line = queue.take()
                        synchronized(writeLock) { writer.println(line) }
                        if (writer.checkError()) break
                    }
                } catch (_: InterruptedException) {
                    // close() gọi interrupt.
                } catch (_: Exception) {
                    // Socket hỏng: luồng kết thúc, client handler dọn dẹp sau.
                }
            }, "FgSubscriberWriter").apply { isDaemon = true; start() }
        }

        fun offer(line: String) {
            if (!running) return
            if (!queue.offer(line)) {
                // FG hiếm nên hàng đợi đầy gần như không xảy ra; rớt dòng
                // cũ nhất rồi thử lại (chỉ cần package mới nhất).
                queue.poll()
                queue.offer(line)
            }
        }

        fun close() {
            if (!running) return
            running = false
            FgBus.unsubscribe(listener)
            thread?.interrupt()
        }
    }

    private fun startWatchdogThread() {
        val watchdog = Thread {
            var loopCount = 0
            while (isRunning.get()) {
                try {
                    Thread.sleep(5_000)
                    loopCount++

                    // Kiểm tra file APK còn tồn tại không (mỗi 15 giây = 3 vòng lặp)
                    if (loopCount % 3 == 0) {
                        val apkFile = File(apkPath)
                        if (!apkFile.exists()) {
                            log("WATCHDOG: File APK ($apkPath) không còn tồn tại (app bị gỡ hoặc cài lại). Server tự thoát.")
                            shutdownAndExit(0)
                            break
                        }
                    }

                    // Kiểm tra thời gian không hoạt động (Idle timeout 5 phút)
                    val idleTime = System.currentTimeMillis() - lastActivityTimeMs
                    if (idleTime > IDLE_TIMEOUT_MS) {
                        log("WATCHDOG: Không nhận được lệnh nào trong ${idleTime / 1000}s (quá ${IDLE_TIMEOUT_MS / 1000}s). Server tự thoát.")
                        shutdownAndExit(0)
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log("WATCHDOG lỗi: ${e.message}")
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "ServerWatchdog"
        watchdog.start()
    }

    private fun shutdownAndExit(code: Int) {
        isRunning.set(false)
        try {
            TouchHub.stop()
        } catch (_: Exception) {}
        try {
            ForegroundWatcher.stop()
        } catch (_: Exception) {}
        try {
            InputHub.stop()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        exitProcess(code)
    }

    @Synchronized
    fun log(msg: String) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        // Che token (32 hex) trước khi ghi: file log ở /data/local/tmp, app
        // khác đọc được sẽ lấy token tiêm chạm qua loopback.
        val line = "[$timeStr] ${Config.redactSecrets(msg)}\n"

        // Chỉ ghi trực tiếp vào file log (không print ra stdout, vì stdout
        // đã được redirect vào chính file này nên print sẽ gây trùng dòng).

        // Ghi trực tiếp vào file log với giới hạn kích thước
        try {
            val file = File(Config.LOG_FILE_PATH)
            if (file.exists() && file.length() > Config.MAX_LOG_SIZE_BYTES) {
                // Cắt bớt log nếu quá lớn
                file.writeText("[LOG ROTATED]\n")
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // Ghi log lỗi — thường do file log do uid khác tạo (đổi phương
            // thức kích hoạt root <-> shell): thử xóa rồi ghi lại 1 lần
            // (thư mục /data/local/tmp thường không có sticky bit nên
            // unlink được dù khác chủ). Vẫn lỗi thì bỏ qua, không để lỗi
            // ghi log làm crash server.
            try {
                val file = File(Config.LOG_FILE_PATH)
                if (file.delete()) {
                    FileOutputStream(file, true).use { fos ->
                        fos.write(line.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {
                // Không để lỗi ghi log làm crash server
            }
        }
    }
}

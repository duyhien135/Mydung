package com.example.mydung.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client phía app cho giao thức nhập GĐ3.
 * Thuần java.net, không phụ thuộc Android — dùng chung cho DEVICES/GRAB
 * (lệnh 1 lần) và SUB_INPUT (kết nối stream dài).
 */
object InputClient {

    /** Mở socket, AUTH, trả về (socket, reader, writer). Caller đóng socket. */
    private fun connect(port: Int, token: String, timeoutMs: Int = 3000): Triple<Socket, BufferedReader, PrintWriter> {
        val socket = Socket()
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        writer.println("AUTH $token")
        val authResp = reader.readLine()
        if (authResp != "OK") {
            socket.close()
            throw IllegalStateException("Xác thực thất bại: $authResp")
        }
        return Triple(socket, reader, writer)
    }

    /** Lấy danh sách thiết bị (đọc nhiều dòng tới "END"). */
    fun fetchDevices(port: Int, token: String): List<InputDeviceInfo> {
        val (socket, reader, writer) = connect(port, token)
        return socket.use {
            writer.println("DEVICES")
            val out = ArrayList<InputDeviceInfo>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line == "END") break
                InputProtocol.parseDeviceLine(line)?.let { out.add(it) }
            }
            out
        }
    }

    /** Gửi lệnh GRAB, trả phản hồi dạng "OK GRAB mouse=1 keyboard=0 method=A grabbed=2 failed=0". */
    fun setGrab(port: Int, token: String, target: String, on: Boolean): String {
        return command(port, token, "GRAB $target ${if (on) 1 else 0}")
    }

    /**
     * Gửi 1 lệnh 1 dòng, trả về 1 dòng phản hồi đầu tiên.
     * Dùng cho các lệnh GĐ4 (DISPLAY/MAP/MAPSTATUS/TOUCHCFG/TESTTOUCH).
     */
    fun command(port: Int, token: String, line: String): String {
        val (socket, reader, writer) = connect(port, token)
        return socket.use {
            writer.println(line)
            reader.readLine() ?: "ERR no_response"
        }
    }

    /** Gửi DISPLAY (kích thước thật, tính cả xoay). */
    fun sendDisplay(port: Int, token: String, width: Int, height: Int, rotation: Int, displayId: Int): String {
        return command(port, token, "DISPLAY $width $height $rotation $displayId")
    }

    /** Bật/tắt MAP. */
    fun setMap(port: Int, token: String, on: Boolean): String {
        return command(port, token, "MAP ${if (on) 1 else 0}")
    }

    /** Đổi chế độ con trỏ (true) <-> nhìn tự do (false). Trả "OK CURSOR 1|0". */
    fun setCursorMode(port: Int, token: String, on: Boolean): String {
        return command(port, token, "CURSORMODE ${if (on) 1 else 0}")
    }

    /** Lấy trạng thái MAP ("MAP map=1 display=... pointers=.. latencyMs=.. injectErr=.. injectOk=.."). */
    fun mapStatus(port: Int, token: String): String {
        return command(port, token, "MAPSTATUS")
    }

    /**
     * Thăm dò MAPSTATUS trên 1 kết nối giữ mở (chế độ con trỏ, ~30Hz): mở
     * socket + AUTH 1 lần, mỗi lần poll chỉ gửi "MAPSTATUS" và đọc 1 dòng.
     * Mở socket mới mỗi lần poll (như [mapStatus]) ở 30Hz sẽ spam kết nối
     * + thread trên Server. Server đóng kết nối sau 15s không có lệnh nên
     * poll lỗi thì mở lại 1 lần rồi mới trả null.
     */
    class StatusPoller(private val port: Int, private val token: String) {
        private var socket: Socket? = null
        private var reader: BufferedReader? = null
        private var writer: PrintWriter? = null

        /** 1 lần thăm dò, trả dòng MAPSTATUS hoặc null khi lỗi/mất kết nối. */
        fun poll(timeoutMs: Int = 1000): String? {
            pollOnce(timeoutMs)?.let { return it }
            // Kết nối cũ chết (server đóng sau 15s không có lệnh): mở lại 1 lần.
            close()
            return pollOnce(timeoutMs)
        }

        private fun pollOnce(timeoutMs: Int): String? {
            return try {
                if (socket == null) {
                    val (s, r, w) = connect(port, token, timeoutMs)
                    socket = s
                    reader = r
                    writer = w
                }
                writer?.println("MAPSTATUS")
                // readLine() trả null = server đã đóng kết nối (EOF).
                reader?.readLine()
            } catch (_: Exception) {
                close()
                null
            }
        }

        fun close() {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            reader = null
            writer = null
        }
    }

    /** Áp dụng cấu hình chạm (TOUCHCFG k=v ...). */
    /** Gửi profile JSON 1 dòng cho Server (GĐ5: lệnh PROFILE). */
    fun sendProfile(port: Int, token: String, json: String): String =
        command(port, token, "PROFILE $json")

    fun sendTouchCfg(port: Int, token: String, values: Map<String, Float>): String {
        val args = values.entries.joinToString(" ") { "${it.key}=${it.value}" }
        return command(port, token, "TOUCHCFG $args")
    }

    /** Chạy test tiêm: "TAP x y" | "SWIPE x1 y1 x2 y2 ms" | "TWO x1 y1 x2 y2" (tỉ lệ). */
    fun testTouch(port: Int, token: String, args: String): String {
        return command(port, token, "TESTTOUCH $args")
    }

    /** Đổi cách tiêm (GĐ6): "reflect" (mặc định) hoặc "evdev". */
    fun setInjector(port: Int, token: String, mode: String): String {
        return command(port, token, "INJECT $mode")
    }

    /**
     * Kết nối stream SUB_INPUT. Luồng đọc chạy nền, gọi [onLine] cho mỗi dòng
     * "EV ...". Đóng bằng [Subscription.close] (gửi UNSUB_INPUT rồi đóng socket).
     */
    class Subscription(
        port: Int,
        token: String,
        private val onLine: (String) -> Unit,
        private val onError: (String) -> Unit,
    ) {
        private val socket: Socket
        private val reader: BufferedReader
        private val writer: PrintWriter

        @Volatile
        private var running = true
        private val thread: Thread

        init {
            val (s, r, w) = connect(port, token)
            socket = s
            reader = r
            writer = w
            writer.println("SUB_INPUT")
            val resp = reader.readLine()
            if (resp != "OK SUBSCRIBED") {
                socket.close()
                throw IllegalStateException("SUB_INPUT thất bại: $resp")
            }
            socket.soTimeout = 0 // stream dài: không timeout đọc
            thread = Thread({
                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                } catch (e: Exception) {
                    if (running) onError(e.message ?: "Lỗi đọc stream")
                }
            }, "InputSubscription").apply { isDaemon = true; start() }
        }

        fun close() {
            if (!running) return
            running = false
            try {
                writer.println("UNSUB_INPUT")
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Kết nối stream SUB_FG (GĐ6). Luồng đọc chạy nền, gọi [onLine] cho mỗi
     * dòng "FG <package>". Đóng bằng [FgSubscription.close] (gửi UNSUB_FG
     * rồi đóng socket). Đóng kết nối này KHÔNG nhả GRAB (khác SUB_INPUT).
     */
    class FgSubscription(
        port: Int,
        token: String,
        private val onLine: (String) -> Unit,
        private val onError: (String) -> Unit,
    ) {
        private val socket: Socket
        private val reader: BufferedReader
        private val writer: PrintWriter

        @Volatile
        private var running = true
        private val thread: Thread

        /** Stream còn sống không (luồng đọc còn chạy). */
        val isAlive: Boolean get() = running && thread.isAlive

        init {
            val (s, r, w) = connect(port, token)
            socket = s
            reader = r
            writer = w
            writer.println("SUB_FG")
            val resp = reader.readLine()
            if (resp != "OK SUBSCRIBED_FG") {
                socket.close()
                throw IllegalStateException("SUB_FG thất bại: $resp")
            }
            socket.soTimeout = 0 // stream dài: không timeout đọc
            thread = Thread({
                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                } catch (e: Exception) {
                    if (running) onError(e.message ?: "Lỗi đọc stream FG")
                }
            }, "FgSubscription").apply { isDaemon = true; start() }
        }

        fun close() {
            if (!running) return
            running = false
            try {
                writer.println("UNSUB_FG")
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}

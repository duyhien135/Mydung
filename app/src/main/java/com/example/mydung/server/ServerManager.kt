package com.example.mydung.server

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat
import com.example.mydung.AdbConnectionManager
import com.example.mydung.ActivationMethod
import com.example.mydung.PairingState
import com.example.mydung.ServerKeepAliveService
import com.example.mydung.ui.readRealDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Giai đoạn khởi động Server bị kẹt (chẩn đoán cho màn hình chính):
 * tiến trình chết / cổng chưa lắng nghe / bắt tay thất bại / hết giờ /
 * đã có Server cũ còn chạy mà không dừng được / không chạy được lệnh shell
 * để kiểm tra (vd Shizuku bind UserService timeout — trường hợp này KHÔNG
 * được kết luận là PORT_CLOSED vì thực ra không kiểm tra được gì).
 */
enum class ServerStartStage(val labelVi: String) {
    PROCESS_DEAD("Tiến trình Server đã chết"),
    PORT_CLOSED("Tiến trình sống nhưng cổng chưa lắng nghe"),
    HANDSHAKE_FAILED("Cổng đã mở nhưng bắt tay thất bại"),
    TIMEOUT("Hết thời gian chờ"),
    STALE_SERVER("Đã có Server cũ đang chạy"),
    EXEC_FAILED("Không chạy được lệnh shell (Shizuku bind lỗi)"),
}

data class ServerInfo(
    val status: ServerStatus = ServerStatus.STOPPED,
    val uid: Int = -1,
    val pid: Int = -1,
    val port: Int = -1,
    val versionCode: Long = -1,
    val uptimeSec: Long = 0,
    val errorMessage: String? = null,
    /** Giai đoạn bị kẹt khi status == ERROR (null = chưa chẩn đoán). */
    val stage: ServerStartStage? = null,
    /** 25 dòng cuối của log Server khi khởi động lỗi (để hiện + sao chép). */
    val logTail: String? = null,
)

object ServerManager {

    private const val PREFS_NAME = "server_prefs"
    private const val KEY_PORT = "server_port"
    private const val KEY_TOKEN = "server_token"
    private const val KEY_PID = "server_pid"
    private const val KEY_VERSION = "server_version"

    // GĐ6: công tắc tự đổi profile theo game (mặc định bật).
    private const val PREFS_APP = "keytouch_prefs"
    private const val KEY_AUTO_SWITCH = "auto_switch_profile"

    private val _serverInfoFlow = MutableStateFlow(ServerInfo())
    val serverInfoFlow = _serverInfoFlow.asStateFlow()

    // Đã kiểm tra xong Server cũ lúc mở app (checkOrReconnectServer) chưa.
    // Màn chính đợi cờ này rồi mới tự khởi động Server — không thì auto-start
    // chạy đua với lần ping Server cũ, có thể đẻ ra 2 Server cùng lúc.
    private val _reconnectDoneFlow = MutableStateFlow(false)
    val reconnectDoneFlow = _reconnectDoneFlow.asStateFlow()

    // Người dùng đã bấm "Dừng Server" (tab Cài đặt) — auto-start không được
    // khởi động lại cho tới khi họ bấm khởi động tay (startServer xóa cờ này).
    // Chỉ lưu trong RAM: mở app lại từ đầu thì auto-start chạy như thường.
    @Volatile
    var userStoppedServer = false
        private set

    /** Đổi phương thức kích hoạt = ngữ cảnh mới: lần dừng Server cũ không còn áp dụng. */
    fun clearUserStopped() {
        userStoppedServer = false
    }

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Gửi DISPLAY ngay khi xoay màn hình (DisplayListener) + heartbeat 5s
    // làm dự phòng. lastSentDisplay dùng chung cho cả 2 đường để không gửi trùng.
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile
    private var lastSentDisplay: Triple<Int, Int, Int>? = null

    // GĐ6: stream SUB_FG nhận app foreground đổi từ Server.
    private var fgJob: Job? = null
    private var fgSubscription: InputClient.FgSubscription? = null

    // GĐ6: serialize các lần tự đổi profile để 2 lần đổi FG liên tiếp
    // không đảo thứ tự ghi file/gửi Server.
    private val fgSwitchMutex = Mutex()

    @Volatile
    private var lastFgPkg: String? = null

    /**
     * Kết quả dọn Server cũ còn sống trước khi khởi động bản mới
     * (xem [reapStaleServer]).
     */
    private sealed interface StaleCheck {
        /** Không còn Server cũ (hoặc đã dừng được) — cứ khởi động bản mới. */
        data object Clean : StaleCheck

        /** Server cũ cùng version còn chạy tốt — dùng lại, khỏi khởi động mới. */
        data class Reused(val info: ServerInfo, val port: Int, val token: String) : StaleCheck

        /** Server cũ còn sống mà không dừng được — chưa nên khởi động bản mới. */
        data class KillFailed(val reason: String) : StaleCheck
    }

    /** Heartbeat có đang chạy không (ServerKeepAliveService dùng để tránh nối lại thừa). */
    fun isHeartbeatRunning(): Boolean = heartbeatJob?.isActive == true

    /**
     * Giữ tiến trình app sống bằng foreground service trong lúc Server chạy
     * (xem ServerKeepAliveService): heartbeat + vòng thăm dò DISPLAY/FG chạy
     * trong scope của object này, sống theo TIẾN TRÌNH — không có foreground
     * service thì HyperOS giết tiến trình khi app ở nền, mất heartbeat 10s
     * là MAP tự tắt giữa trận.
     */
    private fun startKeepAlive(context: Context) {
        ServerKeepAliveService.start(context.applicationContext)
    }

    private fun stopKeepAlive(context: Context) {
        ServerKeepAliveService.stop(context.applicationContext)
    }
    fun isAutoSwitchEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SWITCH, true)
    }

    fun setAutoSwitchEnabled(context: Context, on: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SWITCH, on).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAppVersionCode(context: Context): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo)
        } catch (_: Exception) {
            1L
        }
    }

    /**
     * Tìm một cổng loopback còn trống ngẫu nhiên.
     */
    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    /**
     * Tạo token ngẫu nhiên 32 ký tự hex (16 bytes).
     */
    private fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Lấy cổng/token Server đã lưu (cho màn Kiểm tra thiết bị nhập, GĐ3).
     * Trả null nếu chưa có Server.
     */
    fun getSavedConnection(context: Context): Pair<Int, String>? {
        val prefs = getPrefs(context)
        val port = prefs.getInt(KEY_PORT, -1)
        val token = prefs.getString(KEY_TOKEN, null)
        return if (port > 0 && !token.isNullOrEmpty()) port to token else null
    }

    /**
     * Kết nối TCP gửi AUTH và thực hiện một lệnh đơn giản.
     */
    suspend fun sendCommand(port: Int, token: String, command: String, timeoutMs: Int = 3000): String = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)

            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // 1. Gửi AUTH
            writer.println("AUTH $token")
            val authResp = reader.readLine()
            if (authResp != "OK") {
                throw IllegalStateException("Xác thực Token thất bại: $authResp")
            }

            // 2. Gửi lệnh
            writer.println(command)
            val resp = reader.readLine() ?: throw IllegalStateException("Server đóng kết nối không phản hồi")
            resp
        }
    }

    /**
     * Gửi PING tới Server và parse phản hồi.
     */
    suspend fun pingServer(port: Int, token: String, timeoutMs: Int = 3000): ServerInfo? = withContext(Dispatchers.IO) {
        try {
            val response = sendCommand(port, token, "PING", timeoutMs)
            // Phản hồi mẫu: PONG uid=2000 pid=12345 version=1 uptime=42
            if (response.startsWith("PONG")) {
                var uid = -1
                var pid = -1
                var version = -1L
                var uptime = 0L

                val parts = response.substring(4).trim().split(" ")
                for (part in parts) {
                    val kv = part.split("=")
                    if (kv.size == 2) {
                        when (kv[0]) {
                            "uid" -> uid = kv[1].toIntOrNull() ?: -1
                            "pid" -> pid = kv[1].toIntOrNull() ?: -1
                            "version" -> version = kv[1].toLongOrNull() ?: -1L
                            "uptime" -> uptime = kv[1].toLongOrNull() ?: 0L
                        }
                    }
                }

                ServerInfo(
                    status = ServerStatus.RUNNING,
                    uid = uid,
                    pid = pid,
                    port = port,
                    versionCode = version,
                    uptimeSec = uptime
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Kiểm tra khi app mở lại: Nếu Server đang chạy hợp lệ thì tái sử dụng.
     * Nếu Server đang chạy nhưng version cũ (vừa build lại app) -> gửi STOP rồi dọn dẹp.
     */
    fun checkOrReconnectServer(context: Context) {
        scope.launch {
            val prefs = getPrefs(context)
            val port = prefs.getInt(KEY_PORT, -1)
            val token = prefs.getString(KEY_TOKEN, null)
            val currentAppVersion = getAppVersionCode(context)

            if (port > 0 && !token.isNullOrEmpty()) {
                PairingState.log("Kiểm tra Server đã lưu từ trước tại cổng $port...")
                val info = pingServer(port, token, timeoutMs = 2000)
                if (info != null) {
                    if (info.versionCode == currentAppVersion) {
                        PairingState.log("✅ TÁI SỬ DỤNG SERVER ĐANG CHẠY: PID=${info.pid}, UID=${info.uid}, Version=${info.versionCode}, Uptime=${info.uptimeSec}s")
                        _serverInfoFlow.value = info
                        startHeartbeat(context, port, token)
                        startDisplayListener(context, port, token)
                        startFgWatcher(context, port, token)
                        startKeepAlive(context)
                        _reconnectDoneFlow.value = true
                        return@launch
                    } else {
                        PairingState.log("⚠ Phát hiện Server cũ đang chạy version=${info.versionCode} (App hiện tại là $currentAppVersion). Đang dừng bản cũ để cập nhật...")
                        try {
                            sendCommand(port, token, "STOP", timeoutMs = 2000)
                        } catch (_: Exception) {}
                        delay(1000)
                        clearSavedServer(context)
                    }
                } else {
                    PairingState.log("Server cũ không còn phản hồi. Dọn dẹp cache.")
                    clearSavedServer(context)
                }
            }

            _serverInfoFlow.value = ServerInfo(status = ServerStatus.STOPPED)
            stopKeepAlive(context)
            _reconnectDoneFlow.value = true
        }
    }

    /**
     * Khởi động Server qua lệnh shell app_process, chạy bằng 1 [ShellExecutor]
     * (Shizuku / Root / ADB không dây). Lệnh khởi động và luồng thăm dò giống
     * nhau cho cả 3 đường, chỉ khác cách chạy lệnh.
     */
    fun startServer(context: Context, executor: ShellExecutor, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (_serverInfoFlow.value.status == ServerStatus.STARTING) {
            PairingState.log("Server đang trong quá trình khởi động, vui lòng chờ...")
            return
        }
        if (_serverInfoFlow.value.status == ServerStatus.RUNNING) {
            PairingState.log("Server đã chạy rồi, không khởi động thêm.")
            return
        }
        // Khởi động (tay hay tự động) đều xóa ý "đã dừng Server" trước đó.
        userStoppedServer = false

        _serverInfoFlow.value = ServerInfo(status = ServerStatus.STARTING)
        PairingState.log("── Bắt đầu khởi động Server đặc quyền (qua ${executor.label}) ──")

        scope.launch {
            if (!executor.isReady()) {
                val err = "Chưa kích hoạt ${executor.label}! " + when (executor.method) {
                    ActivationMethod.SHIZUKU -> "Mở app Shizuku bấm Start rồi cấp quyền cho KeyTouch trước."
                    ActivationMethod.ROOT -> "Máy chưa có quyền root (su không chạy được)."
                    ActivationMethod.ADB -> "Cần kết nối gỡ lỗi không dây trước để khởi động Server."
                }
                PairingState.log("❌ $err")
                _serverInfoFlow.value = ServerInfo(status = ServerStatus.ERROR, errorMessage = err)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, err)
                }
                return@launch
            }

            val appVersionCode = getAppVersionCode(context)
            var lastError: String? = null
            var lastStage: ServerStartStage? = null
            var lastLogTail: String? = null
            // Cổng/token của lần thử trước, để lần thử sau kiểm tra lại xem
            // server cũ có lên muộn không.
            var prevPort = -1
            var prevToken: String? = null

            // Server chạy bằng setsid nohup nên app chết Server vẫn sống:
            // dọn bản cũ TRƯỚC khi chạy bản mới (dùng lại nếu cùng version,
            // kill nếu khác version/không phản hồi). Không dọn thì 2 Server
            // cùng sống — bản cũ giữ GRAB, app có thể nói chuyện với bản cũ
            // sai token.
            when (val stale = reapStaleServer(executor, context, appVersionCode)) {
                is StaleCheck.Reused -> {
                    PairingState.log("♻ Server cũ (cùng version) còn chạy tốt — dùng lại, không khởi động bản mới.")
                    _serverInfoFlow.value = stale.info
                    startHeartbeat(context, stale.port, stale.token)
                    startDisplayListener(context, stale.port, stale.token)
                    startFgWatcher(context, stale.port, stale.token)
                    startKeepAlive(context)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true, null)
                    }
                    return@launch
                }
                is StaleCheck.KillFailed -> {
                    val err = "Không dừng được Server cũ: ${stale.reason}"
                    PairingState.log("❌ $err")
                    _serverInfoFlow.value = ServerInfo(
                        status = ServerStatus.ERROR,
                        errorMessage = err,
                        stage = ServerStartStage.STALE_SERVER,
                    )
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, err)
                    }
                    return@launch
                }
                StaleCheck.Clean -> { /* Không còn Server cũ — cứ khởi động bản mới. */ }
            }

            for (attempt in 1..2) {
                try {
                    val port = findFreePort()
                    val token = generateToken()
                    // Đường dẫn APK: lấy sourceDir của tiến trình app đang
                    // chạy TRƯỚC (không tốn lệnh shell — mỗi lệnh qua
                    // Shizuku bind lỗi tốn 10s); chỉ hỏi `pm path` khi
                    // sourceDir trống. sourceDir không bao giờ cũ: cài lại
                    // app thì hệ thống kill tiến trình cũ, tiến trình mới
                    // luôn chạy từ APK mới.
                    val apkPath = resolveApkPath(executor, context)
                    PairingState.log("[Lần $attempt/2] Đã chọn cổng ngẫu nhiên: $port, APK: $apkPath, VersionCode: $appVersionCode")

                    // Xóa file log cũ bằng chính executor hiện tại TRƯỚC khi
                    // chạy bản mới: file log do lần chạy trước tạo (uid root
                    // hay shell) có thể không ghi đè được khi đổi phương thức
                    // kích hoạt — file do root tạo thì shell không truncate
                    // được, khiến cả lệnh khởi động lỗi ngay ở bước redirect
                    // ">log". /data/local/tmp thường không có sticky bit nên
                    // rm xóa được dù khác chủ; rm lỗi thì thử chmod 666 (được
                    // khi cùng chủ) để Server mới ghi tiếp được vào file cũ.
                    runCatching {
                        executor.run(
                            "rm -f ${Config.LOG_FILE_PATH} 2>/dev/null; " +
                                "chmod 666 ${Config.LOG_FILE_PATH} 2>/dev/null; true",
                        )
                    }

                    // Lệnh chạy Server qua app_process với setsid và nohup.
                    // setsid tách tiến trình khỏi shell gọi (kẻo shell/
                    // UserService kết thúc thì con bị giết theo); nohup bỏ
                    // qua SIGHUP; </dev/null ngắt stdin để lệnh chạy shell
                    // không treo chờ EOF; stdout+stderr chuyển vào file log
                    // (">..." cắt file mỗi lần khởi động).
                    // Thêm "; echo LAUNCHED" cuối lệnh để shell báo lại là dòng lệnh
                    // đã chạy xong (lệnh nền tự nó không in gì cả).
                    val launchCmd = "CLASSPATH=$apkPath setsid nohup app_process /system/bin --nice-name=mydung_server com.example.mydung.server.Server $port $token $apkPath $appVersionCode </dev/null >/data/local/tmp/mydung_server.log 2>&1 & echo LAUNCHED"

                    PairingState.log("Gửi lệnh khởi động qua ${executor.label}...")
                    try {
                        val launchOut = executor.run(launchCmd)
                        PairingState.log("Lệnh khởi động trả về: $launchOut")
                    } catch (e: Exception) {
                        // Lệnh có thể đã chạy nhưng stream đóng muộn/không trả về.
                        // Vẫn thăm dò tiếp: nếu Server đã lên thì dùng luôn.
                        PairingState.log("⚠ Lệnh khởi động không trả về (${e.javaClass.simpleName}: ${e.message}). Vẫn thăm dò Server...")
                    }

                    // Thăm dò mỗi 250ms, tối đa 30s (lần đầu chạy sau khi cài
                    // lại app, ART/dex2oat có thể mất hơn 5s mới xong, server
                    // lên muộn). Dừng sớm nếu tiến trình đã chết.
                    PairingState.log("Đang đợi Server sẵn sàng (polling cổng $port mỗi 250ms, tối đa 30s)...")
                    var serverUpInfo: ServerInfo? = null
                    var upPort = port
                    var upToken = token
                    val startTime = System.currentTimeMillis()
                    var lastProgressLogSec = 0L
                    var lastProcessCheckMs = 0L
                    var lastPrevCheckMs = 0L
                    var processDead = false

                    while (System.currentTimeMillis() - startTime < Config.SERVER_START_TIMEOUT_MS) {
                        delay(Config.SERVER_POLL_MS)
                        val ping = pingServer(port, token, timeoutMs = Config.SERVER_PING_TIMEOUT_MS)
                        if (ping != null) {
                            serverUpInfo = ping
                            break
                        }
                        val elapsed = System.currentTimeMillis() - startTime
                        // Server của lần thử trước có thể lên muộn, kiểm tra lại cổng cũ mỗi 2s.
                        if (prevPort > 0 && prevToken != null &&
                            elapsed - lastPrevCheckMs >= Config.SERVER_PROCESS_CHECK_MS
                        ) {
                            lastPrevCheckMs = elapsed
                            val latePing = pingServer(prevPort, prevToken, timeoutMs = Config.SERVER_PING_TIMEOUT_MS)
                            if (latePing != null) {
                                PairingState.log("Server lần trước đã lên muộn trên cổng $prevPort, dùng lại luôn.")
                                serverUpInfo = latePing
                                upPort = prevPort
                                upToken = prevToken
                                break
                            }
                        }
                        // Tiến trình còn sống không? Kiểm tra mỗi 2s; chết thì
                        // dừng sớm (kiểm tra lại 1 lần sau 1s để khỏi kết luận
                        // vội vì lệnh ps chậm). Chỉ dừng khi CHẮC CHẮN chết
                        // (== false); null = không kiểm tra được (lệnh ps
                        // lỗi) thì cứ thăm dò tiếp (fail-open).
                        if (elapsed - lastProcessCheckMs >= Config.SERVER_PROCESS_CHECK_MS) {
                            lastProcessCheckMs = elapsed
                            if (isServerProcessAlive(executor) == false) {
                                delay(1000)
                                if (isServerProcessAlive(executor) == false) {
                                    PairingState.log("❌ Tiến trình mydung_server đã chết — dừng thăm dò sớm, đọc log xem nguyên nhân.")
                                    processDead = true
                                    break
                                }
                            }
                        }
                        val elapsedSec = elapsed / 1000
                        if (elapsedSec - lastProgressLogSec >= 10) {
                            lastProgressLogSec = elapsedSec
                            PairingState.log("... vẫn đang đợi Server khởi động (${elapsedSec}s)")
                        }
                    }

                    if (serverUpInfo != null) {
                        PairingState.log("✅ SERVER ĐÃ KHỞI ĐỘNG THÀNH CÔNG RỰC RỠ!")
                        PairingState.log("➡ Thông tin: PID=${serverUpInfo.pid}, UID=${serverUpInfo.uid}, Port=$upPort, Uptime=${serverUpInfo.uptimeSec}s")

                        saveServer(context, upPort, upToken, serverUpInfo.pid, appVersionCode)
                        _serverInfoFlow.value = serverUpInfo

                        startHeartbeat(context, upPort, upToken)
                        startDisplayListener(context, upPort, upToken)
                        startFgWatcher(context, upPort, upToken)
                        startKeepAlive(context)
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(true, null)
                        }
                        return@launch
                    } else {
                        // Chẩn đoán theo giai đoạn: (0) có chạy được lệnh
                        // shell để kiểm tra không, (1) tiến trình còn sống
                        // không, (2) cổng đã lắng nghe chưa, (3) bắt tay có
                        // thành công không. Kèm 25 dòng cuối của log Server.
                        // Lưu ý: processAlive == null (lệnh ps lỗi, vd
                        // Shizuku bind timeout) thì báo EXEC_FAILED — KHÔNG
                        // được kết luận PORT_CLOSED như trước (fail-open
                        // khiến app báo sai "tiến trình sống" trong khi
                        // thực ra không kiểm tra được gì).
                        val logTail = readLogTailLines(executor)
                        val stage: ServerStartStage
                        val detail: String
                        val processAlive = isServerProcessAlive(executor)
                        if (processDead || processAlive == false) {
                            stage = ServerStartStage.PROCESS_DEAD
                            detail = "Tiến trình Server chết ngay sau khi khởi động (xem log bên dưới)."
                        } else if (processAlive == null) {
                            stage = ServerStartStage.EXEC_FAILED
                            detail = "Không chạy được lệnh shell qua ${executor.label} " +
                                "(lệnh kiểm tra tiến trình lỗi — có thể Shizuku bind " +
                                "UserService timeout) nên không biết Server có chạy " +
                                "không. Xem log bên dưới."
                        } else if (!probePort(port)) {
                            stage = ServerStartStage.PORT_CLOSED
                            detail = "Tiến trình còn sống nhưng cổng $port chưa lắng nghe (Server chưa bind xong hoặc bind lỗi)."
                        } else {
                            stage = ServerStartStage.HANDSHAKE_FAILED
                            detail = "Cổng $port đã mở nhưng bắt tay (AUTH/PING) thất bại — có thể Server kẹt sau khi bind."
                        }
                        lastError = detail
                        lastStage = stage
                        lastLogTail = logTail
                        PairingState.log("⚠ [Lần $attempt] $detail")
                        PairingState.log("Log Server (25 dòng cuối):\n$logTail")
                        // Lưu lại để lần thử sau kiểm tra xem server này có lên muộn không
                        prevPort = port
                        prevToken = token
                    }
                } catch (e: Exception) {
                    lastError = "Lỗi ngoại lệ: ${e.message}"
                    lastStage = ServerStartStage.TIMEOUT
                    PairingState.log("❌ [Lần $attempt] $lastError")
                }

                delay(1000)
            }

            val finalErr = "Khởi động Server thất bại sau 2 lần thử: $lastError"
            _serverInfoFlow.value = ServerInfo(
                status = ServerStatus.ERROR,
                errorMessage = finalErr,
                stage = lastStage ?: ServerStartStage.TIMEOUT,
                logTail = lastLogTail,
            )
            withContext(Dispatchers.Main) {
                onComplete?.invoke(false, finalErr)
            }
        }
    }

    /** Khởi động Server qua ADB không dây (giữ lại cho màn cài đặt ADB). */
    fun startServer(context: Context, adbManager: AdbConnectionManager, onComplete: ((Boolean, String?) -> Unit)? = null) {
        startServer(context, AdbShellExecutor(adbManager), onComplete)
    }

    /**
     * Gửi lệnh PING kiểm tra thủ công.
     */
    fun ping(context: Context) {
        scope.launch {
            val prefs = getPrefs(context)
            val port = prefs.getInt(KEY_PORT, -1)
            val token = prefs.getString(KEY_TOKEN, null)

            if (port <= 0 || token.isNullOrEmpty()) {
                PairingState.log("⚠ Server chưa được khởi động hoặc chưa lưu thông tin.")
                return@launch
            }

            PairingState.log("Đang gửi PING tới 127.0.0.1:$port...")
            try {
                val ping = pingServer(port, token, timeoutMs = 2000)
                if (ping != null) {
                    PairingState.log("✅ Nhận phản hồi: PONG uid=${ping.uid} pid=${ping.pid} version=${ping.versionCode} uptime=${ping.uptimeSec}s")
                    _serverInfoFlow.value = ping
                } else {
                    PairingState.log("❌ Server không phản hồi PING (có thể đã dừng hoặc bị đóng).")
                    _serverInfoFlow.value = ServerInfo(status = ServerStatus.STOPPED, errorMessage = "Server không phản hồi")
                    stopHeartbeat()
                    stopDisplayListener()
                    stopFgWatcher()
                    stopKeepAlive(context)
                }
            } catch (e: Exception) {
                PairingState.log("❌ Lỗi khi Ping Server: ${e.message}")
            }
        }
    }

    /**
     * Dừng Server: Gửi STOP qua TCP socket.
     */
    fun stopServer(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        // Người dùng chủ động dừng: auto-start ở màn chính không được bật lại.
        userStoppedServer = true
        stopHeartbeat()
        stopDisplayListener()
        stopFgWatcher()
        stopKeepAlive(context)
        scope.launch {
            val prefs = getPrefs(context)
            val port = prefs.getInt(KEY_PORT, -1)
            val token = prefs.getString(KEY_TOKEN, null)

            if (port > 0 && !token.isNullOrEmpty()) {
                PairingState.log("Đang gửi lệnh STOP tới Server 127.0.0.1:$port...")
                try {
                    val resp = sendCommand(port, token, "STOP", timeoutMs = 2000)
                    PairingState.log("Server phản hồi: $resp")
                } catch (e: Exception) {
                    PairingState.log("Gửi STOP: ${e.message}")
                }
            }

            clearSavedServer(context)
            _serverInfoFlow.value = ServerInfo(status = ServerStatus.STOPPED)
            PairingState.log("Đã dừng Server và dọn dẹp trạng thái.")
            withContext(Dispatchers.Main) {
                onComplete?.invoke(true)
            }
        }
    }

    /**
     * Đọc đuôi file log Server qua 1 [ShellExecutor]. libadb hay tràn bộ đệm
     * đọc (BufferOverflowException) nên thử 2000 byte trước, thất bại thì
     * thử lại 500 byte; cả 2 lần đều lỗi thì ném ngoại lệ ra ngoài.
     * (Hàm suspend vì executor.run là suspend.)
     */
    private suspend fun readLogTail(executor: ShellExecutor, suffix: String = ""): String {
        return try {
            executor.run("tail -c 2000 /data/local/tmp/mydung_server.log$suffix")
        } catch (_: Exception) {
            executor.run("tail -c 500 /data/local/tmp/mydung_server.log$suffix")
        }
    }

    /**
     * Đọc log của Server qua 1 [ShellExecutor] (yêu cầu executor đã sẵn sàng).
     */
    fun readServerLog(executor: ShellExecutor, onResult: (String) -> Unit) {
        scope.launch {
            if (!executor.isReady()) {
                withContext(Dispatchers.Main) {
                    onResult("Chưa kích hoạt ${executor.label} – không thể đọc log.")
                }
                return@launch
            }
            try {
                val log = readLogTail(executor, " 2>/dev/null || echo '(Không có file log)'")
                withContext(Dispatchers.Main) {
                    onResult(log.ifBlank { "(File log rỗng)" })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("Lỗi đọc log: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Đọc log Server qua ADB (giữ lại cho màn cài đặt ADB). */
    fun readServerLogViaAdb(adbManager: AdbConnectionManager, onResult: (String) -> Unit) {
        readServerLog(AdbShellExecutor(adbManager), onResult)
    }

    /**
     * Lấy đường dẫn APK hiện tại. Dùng sourceDir của tiến trình app đang
     * chạy TRƯỚC (không tốn lệnh shell): cài lại app thì hệ thống kill
     * tiến trình cũ nên sourceDir không bao giờ cũ, kể cả phần ngẫu nhiên
     * trong /data/app/<random>/... Chỉ khi sourceDir null/rỗng (hiếm) mới
     * hỏi package manager bằng `pm path` qua executor. Cả 2 đều lỗi thì
     * trả "" — lệnh khởi động sẽ lỗi rõ ràng ở bước CLASSPATH thay vì
     * dùng đường dẫn cũ sai.
     */
    private suspend fun resolveApkPath(executor: ShellExecutor, context: Context): String {
        val sourceDir = context.applicationInfo.sourceDir
        if (!sourceDir.isNullOrEmpty()) return sourceDir
        return try {
            val out = executor.run("pm path ${context.packageName}").trim()
            // "package:/data/app/~~xxx/com.example.mydung-xxx/base.apk"
            // (dòng đầu là base.apk; app không dùng split APK).
            val path = out.lineSequence()
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (path == null) {
                PairingState.log("sourceDir trống và pm path không trả về đường dẫn (out='$out').")
                ""
            } else {
                path
            }
        } catch (e: Exception) {
            PairingState.log("sourceDir trống và không lấy được APK qua pm path (${e.javaClass.simpleName}: ${e.message}).")
            ""
        }
    }

    /** Tiến trình Server (tên mydung_server, đặt bằng --nice-name) còn sống không.
     *  Tri-state: true = còn sống, false = đã chết, null = KHÔNG KIỂM TRA
     *  ĐƯỢC (lệnh ps lỗi, vd Shizuku bind timeout). Bên gọi tự quyết theo
     *  null: vòng thăm dò coi null như còn sống (fail-open, cứ đợi tiếp);
     *  reapStaleServer coi null như sạch (fail-closed, cứ chạy bản mới);
     *  khối chẩn đoán cuối báo EXEC_FAILED (không kết luận bừa). */
    private suspend fun isServerProcessAlive(executor: ShellExecutor): Boolean? {
        return try {
            // [m]ydung_server: mẹo grep tự loại chính lệnh grep khỏi kết quả.
            val out = executor.run("ps -A | grep [m]ydung_server")
            out.isNotBlank()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dọn Server cũ còn sống trước khi khởi động bản mới (Server chạy bằng
     * setsid nohup nên app chết Server vẫn sống):
     * - Ping cổng đã lưu: còn phản hồi + đúng version app hiện tại -> dùng
     *   lại luôn ([StaleCheck.Reused]), khỏi chạy bản mới.
     * - Còn phản hồi nhưng khác version -> gửi STOP, rồi kiểm tra tiến trình.
     * - Không phản hồi nhưng tiến trình còn sống -> kill bằng pkill theo tên
     *   (không kill theo pid đã lưu: pid có thể đã tái sử dụng cho tiến
     *   trình khác).
     * - Vẫn sống sau khi kill -> [StaleCheck.KillFailed]: thường do bản cũ
     *   chạy bằng root còn phương thức hiện tại (shell) không có quyền kill.
     */
    private suspend fun reapStaleServer(
        executor: ShellExecutor,
        context: Context,
        appVersionCode: Long,
    ): StaleCheck {
        val prefs = getPrefs(context)
        val port = prefs.getInt(KEY_PORT, -1)
        val token = prefs.getString(KEY_TOKEN, null)
        if (port > 0 && !token.isNullOrEmpty()) {
            val info = pingServer(port, token, timeoutMs = 2000)
            if (info != null) {
                if (info.versionCode == appVersionCode) {
                    return StaleCheck.Reused(info, port, token)
                }
                PairingState.log("⚠ Server cũ (version ${info.versionCode}, app hiện tại $appVersionCode) còn chạy — gửi STOP để khởi động bản mới.")
                try {
                    sendCommand(port, token, "STOP", timeoutMs = 2000)
                } catch (_: Exception) {
                }
                delay(1000)
            }
        }
        // Tiến trình cũ còn sống không (kể cả khi không phản hồi cổng)?
        // != true: không kiểm tra được (null) thì coi như sạch, cứ chạy bản
        // mới (fail-closed — kẻo kẹt ở bước dọn vì lỗi ps).
        if (isServerProcessAlive(executor) != true) {
            if (port > 0) clearSavedServer(context)
            return StaleCheck.Clean
        }
        PairingState.log("⚠ Tiến trình mydung_server cũ còn sống — đang kill...")
        runCatching { executor.run("pkill -f mydung_server 2>/dev/null; true") }
        delay(1000)
        if (isServerProcessAlive(executor) != true) {
            clearSavedServer(context)
            PairingState.log("Đã dừng Server cũ.")
            return StaleCheck.Clean
        }
        return StaleCheck.KillFailed(
            "tiến trình mydung_server vẫn sống sau khi kill (có thể bản cũ chạy bằng root, " +
                "còn phương thức hiện tại không có quyền kill — đổi lại phương thức Root rồi " +
                "bấm Dừng Server, hoặc chờ 5 phút để Server cũ tự thoát).",
        )
    }

    /** Cổng TCP loopback đã lắng nghe chưa (kết nối thô, không AUTH). */
    private fun probePort(port: Int, timeoutMs: Int = 1000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Đọc 25 dòng cuối của log Server qua executor (app thường không đọc
     * trực tiếp được file trong /data/local/tmp). libadb hay tràn bộ đệm
     * nên lỗi thì thử lại 10 dòng.
     */
    private suspend fun readLogTailLines(executor: ShellExecutor): String {
        return try {
            executor.run("tail -n ${Config.SERVER_LOG_TAIL_LINES} /data/local/tmp/mydung_server.log 2>/dev/null || echo '(Không có file log)'")
                .ifBlank { "(File log rỗng)" }
        } catch (_: Exception) {
            try {
                executor.run("tail -n 10 /data/local/tmp/mydung_server.log 2>/dev/null || echo '(Không có file log)'")
                    .ifBlank { "(File log rỗng)" }
            } catch (e: Exception) {
                "(Không đọc được log: ${e.javaClass.simpleName}: ${e.message})"
            }
        }
    }

    private fun startHeartbeat(context: Context, port: Int, token: String) {
        stopHeartbeat()
        val appContext = context.applicationContext
        heartbeatJob = scope.launch {
            while (isActive) {
                // GĐ3: heartbeat 5s (Config) — phải nhanh hơn ngưỡng nhả GRAB
                // 10s của Server, nếu không GRAB sẽ bị nhả ngay khi app còn chạy.
                delay(Config.HEARTBEAT_INTERVAL_MS)
                try {
                    val ping = pingServer(port, token, timeoutMs = 2000)
                    if (ping != null) {
                        _serverInfoFlow.value = ping
                        // Dự phòng cho DisplayListener: gửi lại DISPLAY nếu
                        // kích thước/xoay đổi mà listener chưa gửi kịp.
                        sendDisplayIfChanged(appContext, port, token)
                    } else {
                        PairingState.log("⚠ Heartbeat: Server không phản hồi.")
                        _serverInfoFlow.value = ServerInfo(status = ServerStatus.STOPPED, errorMessage = "Server mất kết nối")
                        stopDisplayListener()
                        stopFgWatcher()
                        stopKeepAlive(appContext)
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Gửi DISPLAY cho Server ngay khi màn hình xoay/đổi kích thước
     * (DisplayListener), không đợi tới nhịp heartbeat 5s — vào game ngang
     * mà tọa độ tiêm còn theo màn hình dọc thì chạm lệch hết. Heartbeat
     * vẫn gửi lại định kỳ làm dự phòng (dùng chung lastSentDisplay để
     * không gửi trùng).
     */
    private fun startDisplayListener(context: Context, port: Int, token: String) {
        stopDisplayListener()
        val appContext = context.applicationContext
        val dm = try {
            appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        } catch (_: Exception) {
            return
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                // Chỉ quan tâm màn hình mặc định (màn hình chơi game).
                val d = readRealDisplay(appContext) ?: return
                if (d.displayId != displayId) return
                scope.launch {
                    if (sendDisplayIfChanged(appContext, port, token)) {
                        PairingState.log("🔄 Màn hình đổi (${d.width}x${d.height}, xoay=${d.rotation}) — đã gửi DISPLAY mới cho Server.")
                    }
                }
            }
        }
        displayManager = dm
        displayListener = listener
        try {
            dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            displayManager = null
            displayListener = null
        }
    }

    private fun stopDisplayListener() {
        val dm = displayManager
        val listener = displayListener
        displayManager = null
        displayListener = null
        lastSentDisplay = null
        if (dm != null && listener != null) {
            try {
                dm.unregisterDisplayListener(listener)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Đọc kích thước thật hiện tại, gửi DISPLAY cho Server nếu khác lần
     * gửi trước. Trả true khi đã gửi (dùng chung cho DisplayListener và
     * heartbeat). Chạy trên luồng gọi (socket chặn) nên đừng gọi trên
     * luồng chính — DisplayListener đã chuyển sang scope IO.
     */
    private fun sendDisplayIfChanged(context: Context, port: Int, token: String): Boolean {
        val d = readRealDisplay(context) ?: return false
        val key = Triple(d.width, d.height, d.rotation)
        if (key == lastSentDisplay) return false
        return try {
            InputClient.sendDisplay(port, token, d.width, d.height, d.rotation, d.displayId)
            lastSentDisplay = key
            true
        } catch (_: Exception) {
            // Lỗi gửi: lần sau thử lại.
            lastSentDisplay = null
            false
        }
    }

    /**
     * Mở stream SUB_FG để nhận app foreground đổi từ Server (GĐ6). Chạy
     * cùng vòng đời Server: bắt đầu khi Server lên, dừng khi Server dừng
     * hoặc mất kết nối. Stream chết thì tự nối lại sau 5s.
     */
    private fun startFgWatcher(context: Context, port: Int, token: String) {
        stopFgWatcher()
        val appContext = context.applicationContext
        fgJob = scope.launch {
            // Giữ subscription trong biến cục bộ: khối finally của job cũ
            // (chạy muộn sau khi hủy) không được đóng nhầm subscription mới.
            var mySub: InputClient.FgSubscription? = null
            while (isActive) {
                try {
                    val sub = InputClient.FgSubscription(
                        port, token,
                        onLine = { line ->
                            ForegroundParser.parseFgLine(line)?.let { pkg ->
                                handleFgChange(appContext, pkg)
                            }
                        },
                        onError = { msg -> PairingState.log("⚠ Stream FG lỗi: $msg") },
                    )
                    mySub = sub
                    fgSubscription = sub
                    while (isActive && sub.isAlive) delay(1000)
                    // Stream chết (Server đóng/mất kết nối): nối lại sau 5s.
                    if (isActive) {
                        PairingState.log("⚠ Stream FG bị ngắt, thử nối lại sau 5s.")
                        delay(5000)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break
                    PairingState.log("⚠ Không mở được stream FG (${e.message}), thử lại sau 5s.")
                    delay(5000)
                } finally {
                    try {
                        mySub?.close()
                    } catch (_: Exception) {
                    }
                    if (fgSubscription === mySub) fgSubscription = null
                    mySub = null
                }
            }
        }
    }

    private fun stopFgWatcher() {
        fgJob?.cancel()
        fgJob = null
        try {
            fgSubscription?.close()
        } catch (_: Exception) {
        }
        fgSubscription = null
        lastFgPkg = null
    }

    /**
     * Gửi DISPLAY mới nhất rồi PROFILE cho Server đang chạy. Chạy trên
     * luồng gọi (socket chặn) nên đừng gọi trên luồng chính. Trả về phản
     * hồi của Server ("OK PROFILE ..." hoặc "ERR ...").
     */
    fun sendProfileToServer(context: Context, profile: Profile): String {
        val c = getSavedConnection(context) ?: return "ERR no_server"
        return try {
            val d = readRealDisplay(context)
            if (d != null) {
                InputClient.sendDisplay(c.first, c.second, d.width, d.height, d.rotation, d.displayId)
            }
            InputClient.sendProfile(c.first, c.second, ProfileCodec.toJson(profile))
        } catch (e: Exception) {
            "ERR ${e.message}"
        }
    }

    /**
     * Foreground đổi sang [pkg] (GĐ6): nếu có profile gán cho game này và
     * chưa phải profile đang dùng thì tự đổi + gửi cho Server. Game chưa có
     * profile thì giữ nguyên, không làm gì. Chỉ ghi file sau khi Server đã
     * nhận OK — nếu gửi lỗi thì giữ nguyên activeId cũ để lần FG đổi sau
     * thử lại, tránh app và Server lệch nhau.
     */
    private fun handleFgChange(context: Context, pkg: String) {
        if (pkg == lastFgPkg) return
        lastFgPkg = pkg
        if (!isAutoSwitchEnabled(context)) return
        scope.launch {
            fgSwitchMutex.withLock {
                try {
                    val store = ProfileStore(ProfileStore.defaultFile(context.filesDir))
                    val state = store.load()
                    val target = AutoSwitch.pickProfileForPackage(state.profiles, state.activeId, pkg)
                        ?: return@withLock
                    val resp = sendProfileToServer(context, target)
                    if (resp.startsWith("OK")) {
                        store.save(state.profiles, target.id)
                        PairingState.log("🔄 Tự đổi profile: \"${target.name}\" (mở $pkg).")
                    } else {
                        PairingState.log("⚠ Tự đổi profile \"${target.name}\" nhưng Server trả: $resp")
                    }
                } catch (e: Exception) {
                    PairingState.log("⚠ Tự đổi profile lỗi: ${e.message}")
                }
            }
        }
    }

    private fun saveServer(context: Context, port: Int, token: String, pid: Int, version: Long) {
        getPrefs(context).edit()
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .putInt(KEY_PID, pid)
            .putLong(KEY_VERSION, version)
            .apply()
    }

    private fun clearSavedServer(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}

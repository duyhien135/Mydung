package com.example.mydung.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.example.mydung.BuildConfig
import com.example.mydung.PairingState
import com.example.mydung.server.BindFailureCache
import com.example.mydung.server.ShizukuNewProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * Cầu nối Shizuku (theo mẫu chính thức của Shizuku-API 13.1.5).
 *
 * Đường chính là bind UserService ([ShellService]) chạy bằng quyền shell
 * rồi gọi exec() qua AIDL. Shizuku.newProcess() đã private từ 13.x nên chỉ
 * dùng làm FALLBACK bằng reflection (xem [ShizukuNewProcess]) khi bind
 * UserService lỗi (timeout/binder hỏng) — lệnh chưa chạy nên thử lại an
 * toàn. Luồng đường chính:
 *
 * 1. Người dùng cài + khởi động app Shizuku (bằng gỡ lỗi không dây hoặc root).
 * 2. App xin quyền qua Shizuku.requestPermission() (hộp thoại do app Shizuku hiện).
 * 3. bindUserService() -> ShellService chạy ở tiến trình <app>:shell (uid 2000/0).
 * 4. exec("sh -c <lệnh>") chạy lệnh shell, vd lệnh khởi động Server đặc quyền.
 *
 * Gọi [install] 1 lần ở MainActivity.onCreate. Mọi lệnh Shizuku.* trừ
 * pingBinder()/đăng ký listener đều ném IllegalStateException khi chưa có
 * binder nên computeState() bọc runCatching.
 */
object ShizukuBridge {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    sealed interface State {
        /** Chưa có binder: chưa cài Shizuku, Shizuku chưa chạy, hoặc server vừa chết. */
        data object NotRunning : State

        /** Shizuku quá cũ (cần API 11+ cho checkSelfPermission, 10+ cho UserService). */
        data class Unsupported(val version: Int) : State

        /** Binder sống nhưng chưa cấp quyền. permanentlyDenied = người dùng tick "không hỏi lại". */
        data class NeedsPermission(val permanentlyDenied: Boolean) : State

        /** Binder sống + đã cấp quyền. uid 0 = root, 2000 = shell qua ADB. */
        data class Ready(val uid: Int, val version: Int, val patch: Int, val serviceBound: Boolean) : State
    }

    private const val REQUEST_CODE = 0x5A1C
    // Callback bind hoặc về nhanh hoặc không bao giờ về — 35s chỉ kéo dài
    // chu trình lỗi (máy thật 21/11/2026: các timeout 35s xếp chồng khiến
    // 1 lần khởi động Server mất ~10 phút mới báo lỗi).
    private const val BIND_TIMEOUT_MS = 10_000L

    private val _state = MutableStateFlow<State>(State.NotRunning)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var service: IShellService? = null

    private var pendingBind: CompletableDeferred<IShellService>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val execMutex = Mutex()
    private var installed = false

    /**
     * Nhớ lần bind lỗi gần nhất (xem [BindFailureCache]): trên máy bind
     * không bao giờ thành công, mỗi lần thử lại tốn đủ 10s rồi mới fallback
     * newProcess — khởi động Server chạy nhiều lệnh nên cộng dồn ~2 phút.
     * Trong 5 phút sau 1 lần bind lỗi, exec() bỏ qua bind, đi thẳng
     * newProcess; hết 5 phút (hoặc sau unbind) thì thử bind lại 1 lần.
     */
    private val bindFailureCache = BindFailureCache()

    /**
     * Đã từng gọi requestPermission() trong phiên này chưa. Cần để phân biệt
     * "chưa từng xin quyền" với "bị từ chối vĩnh viễn": cả 2 trường hợp
     * shouldShowRequestPermissionRationale() đều trả false (chỉ khi bị từ
     * chối 1 lần mà KHÔNG tick "không hỏi lại" nó mới trả true).
     */
    private var permissionRequested = false

    /**
     * Version cho UserServiceArgs: PHẢI đổi mỗi build (lấy từ BUILD_TIME
     * sinh lúc build, xem app/build.gradle.kts) để Shizuku server không
     * dùng lại bản ghi service cũ — versionCode 4 không đổi giữa các lần
     * cài debug là 1 phần của lỗi bind timeout trên máy thật (21/11/2026).
     * Ép về Int dương (version() nhận Int).
     */
    private fun userServiceVersion(): Int =
        (BuildConfig.USER_SERVICE_VERSION % Int.MAX_VALUE).toInt()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name),
    )
        // daemon(false): service chết khi unbind, không giữ bản ghi cũ qua
        // các lần mở app (daemon(true) + tag cố định + version không đổi
        // là kiểu dễ dính bản ghi trỏ tới binder tiến trình cũ).
        .daemon(false)
        .processNameSuffix("shell") // BẮT BUỘC, thiếu là NPE trong Shizuku
        // KHÔNG set debuggable: nghi ngờ gây lỗi bind trên 1 số máy.
        .tag("keytouch-shell") // ổn định dù R8 đổi tên class
        .version(userServiceVersion()) // đổi version -> server khởi động lại service

    private val connection = object : ServiceConnection {
        // Cả 2 callback đều được Shizuku post lên luồng chính.
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                PairingState.log("❌ Shizuku: binder UserService không hợp lệ (null hoặc pingBinder=false).")
                pendingBind?.completeExceptionally(
                    ShizukuBindException("UserService bind trả về binder không hợp lệ"),
                )
                pendingBind = null
                return
            }
            val svc = IShellService.Stub.asInterface(binder)
            service = svc
            bindFailureCache.clear() // bind được rồi: lần lỗi sau lại thử bind bình thường
            pendingBind?.complete(svc)
            pendingBind = null
            PairingState.log("✅ Shizuku: UserService đã bind (pingBinder=true).")
            _state.update { s -> if (s is State.Ready) s.copy(serviceBound = true) else s }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            PairingState.log("⚠ Shizuku: UserService đã ngắt kết nối.")
            service = null
            _state.update { s -> if (s is State.Ready) s.copy(serviceBound = false) else s }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDead = Shizuku.OnBinderDeadListener {
        PairingState.log("❌ Shizuku: binder chết (Shizuku server đã dừng/bị giết).")
        service = null
        pendingBind?.completeExceptionally(ShizukuBindException("Shizuku binder chết trong lúc bind"))
        pendingBind = null
        pendingPermission?.complete(false)
        pendingPermission = null
        _state.value = State.NotRunning
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        pendingPermission?.complete(granted)
        pendingPermission = null
        refreshState()
    }

    /** Đăng ký listener Shizuku. Gọi 1 lần, gọi lại không sao (có guard). */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        if (!Shizuku.pingBinder()) _state.value = State.NotRunning else refreshState()
    }

    fun refreshState() {
        _state.value = computeState()
    }

    private fun computeState(): State = runCatching {
        if (!Shizuku.pingBinder()) return State.NotRunning
        if (Shizuku.isPreV11()) return State.Unsupported(-1)
        val version = Shizuku.getVersion()
        if (version < 11) return State.Unsupported(version)
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            // shouldShowRequestPermissionRationale() = true nghĩa là bị từ chối
            // 1 lần nhưng VẪN xin lại được (chưa tick "không hỏi lại") — tức
            // là KHÔNG bị từ chối vĩnh viễn. Chỉ khi đã từng xin mà nó trả
            // false thì mới coi là từ chối vĩnh viễn (phải cấp tay trong app
            // Shizuku).
            return State.NeedsPermission(
                permanentlyDenied = permissionRequested &&
                    !Shizuku.shouldShowRequestPermissionRationale(),
            )
        }
        State.Ready(
            uid = Shizuku.getUid(),
            version = version,
            patch = runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1),
            serviceBound = service?.asBinder()?.pingBinder() == true,
        )
    }.getOrElse { State.NotRunning } // IllegalStateException("binder haven't been received")...

    /**
     * Hiện hộp thoại xin quyền Shizuku. Trả true khi được cấp.
     * Trả false ngay nếu chưa có binder hoặc người dùng đã chọn "không hỏi lại"
     * (trường hợp đó phải vào app Shizuku cấp tay).
     */
    suspend fun requestPermission(): Boolean {
        when (val s = computeState()) {
            is State.Ready -> return true
            is State.NeedsPermission -> if (s.permanentlyDenied) return false
            else -> return false
        }
        pendingPermission?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionRequested = true
        val granted = runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            deferred.await()
        }.getOrElse {
            pendingPermission = null
            false
        }
        if (granted) {
            // Vừa được cấp quyền: bind thử 1 lần ngay (hàm này đã chạy trên
            // coroutine nền của màn chính nên không chặn UI) để lần bind lỗi
            // đầu tiên (nếu có) xảy ra Ở ĐÂY chứ không phải lúc bấm Khởi
            // động Server — cache lỗi sẽ khiến các lệnh sau đi thẳng
            // newProcess, Server lên nhanh. Bind được thì càng tốt: service
            // đã sẵn cho lần exec đầu tiên.
            runCatching { bindService() }.onFailure { e ->
                if (e is ShizukuBindException) bindFailureCache.recordFailure()
            }
        }
        return granted
    }

    private suspend fun bindService(): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        pendingBind?.let { return it.await() }
        val st = computeState()
        check(st is State.Ready) { "Shizuku chưa sẵn sàng: $st" }
        check(st.version >= 10) { "UserService cần Shizuku API 10, đang có ${st.version}" }
        val deferred = CompletableDeferred<IShellService>()
        pendingBind = deferred
        return try {
            // Dọn bản ghi UserService cũ TRƯỚC mỗi lần bind: Shizuku server
            // có thể giữ bản ghi trỏ tới binder của tiến trình cũ, khiến lần
            // bind sau không bao giờ gọi lại onServiceConnected.
            // unbind(remove=true) xóa bản ghi + giết tiến trình cũ. Chỉ tới
            // đây khi chưa có service sống (đã return sớm ở trên) nên unbind
            // này an toàn. Lỗi unbind (chưa từng bind) thì bỏ qua.
            PairingState.log(
                "🔌 Shizuku: bind UserService (Shizuku API ${st.version}, uid ${st.uid}, " +
                    "serviceVersion ${userServiceVersion()})...",
            )
            withContext(Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                // bindUserService trả void (không có giá trị trả về) — bind
                // lỗi thì ServiceConnection.onServiceDisconnected hoặc
                // timeout sẽ báo; không check được ngay như hàm trả boolean.
                Shizuku.bindUserService(userServiceArgs, connection)
            }
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // Chỉ timeout của withTimeout mới tới đây (hủy từ bên ngoài ném
            // CancellationException khác, không bị nuốt). Gói lại thành
            // ShizukuBindException để exec() nhận diện và fallback newProcess.
            pendingBind = null
            val msg = "UserService bind timeout sau ${BIND_TIMEOUT_MS}ms " +
                "(onServiceConnected không gọi lại; Shizuku API ${st.version})"
            PairingState.log("❌ Shizuku: $msg")
            throw ShizukuBindException(msg, e)
        } catch (t: Throwable) {
            pendingBind = null
            throw t
        }
    }

    /**
     * Chạy `sh -c cmd` bằng quyền shell (uid 2000, hoặc 0 nếu Shizuku chạy bằng root).
     *
     * Thứ tự thử:
     * 0. Bind vừa lỗi gần đây (trong 5 phút): bỏ qua UserService, đi thẳng
     *    newProcess (xem [BindFailureCache]) — mỗi lần bind lỗi tốn 10s.
     * 1. UserService + AIDL (đường chính).
     * 2. Binder chết giữa chừng (Shizuku khởi động lại / bị giết) thì bind
     *    lại rồi thử lại 1 lần qua UserService.
     * 3. Bind lỗi (timeout / binder không hợp lệ): lệnh CHƯA BAO GIỜ chạy
     *    nên an toàn fallback sang Shizuku.newProcess (reflection).
     *
     * Lệnh đã chạy mà exit != 0 (ShellException) thì KHÔNG thử lại dưới mọi
     * hình thức — chạy lại 2 lần sẽ khởi động 2 Server.
     */
    suspend fun exec(cmd: String, timeoutMs: Int = 15_000): Result<String> {
        // Bind vừa lỗi gần đây: bỏ qua bind (đỡ 10s mỗi lệnh), đi thẳng
        // newProcess. Không log ở đây (mỗi lần khởi động Server chạy nhiều
        // lệnh, log mỗi lần sẽ spam); lần lỗi đầu đã log ở nhánh fallback.
        if (bindFailureCache.shouldSkipBind()) {
            return runCatching {
                withContext(Dispatchers.IO) { ShizukuNewProcess.exec(cmd, timeoutMs) }
            }
        }
        val first = runCatching { execOnce(cmd, timeoutMs) }
        if (first.isSuccess) return first
        var e = first.exceptionOrNull()!!
        // Binder chết giữa chừng: bind lại rồi thử lại 1 lần qua UserService.
        val isBinderDeath = e is android.os.RemoteException ||
            generateSequence(e.cause) { it.cause }.any { it is android.os.RemoteException }
        if (isBinderDeath) {
            service = null
            val second = runCatching { execOnce(cmd, timeoutMs) }
            if (second.isSuccess) return second
            e = second.exceptionOrNull()!!
        }
        if (e is ShizukuBindException) {
            bindFailureCache.recordFailure()
            PairingState.log("⚠ UserService bind lỗi (${e.message}), fallback sang Shizuku.newProcess.")
            return runCatching {
                withContext(Dispatchers.IO) { ShizukuNewProcess.exec(cmd, timeoutMs) }
            }
        }
        return Result.failure(e)
    }

    private suspend fun execOnce(cmd: String, timeoutMs: Int): String {
        val svc = bindService()
        val bundle = execMutex.withLock {
            withContext(Dispatchers.IO) { svc.exec(cmd, timeoutMs) }
        }
        val code = bundle.getInt("code", -1)
        val out = bundle.getString("out").orEmpty()
        val err = bundle.getString("err").orEmpty()
        if (code != 0) throw ShellException(code, out, err)
        return out
    }

    /** Hủy bind + giết tiến trình shell (server gửi transaction destroy). */
    fun unbindService() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        bindFailureCache.clear() // sau unbind thì thử bind lại 1 lần như mới
        _state.update { s -> if (s is State.Ready) s.copy(serviceBound = false) else s }
    }

    /** App Shizuku đã cài trên máy chưa. Cần <queries> package trong Manifest. */
    fun isAppInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Mở app Shizuku (để người dùng bấm Start). Chưa cài thì mở trang CH Play. */
    fun openShizukuApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            context.startActivity(launch)
            return
        }
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$SHIZUKU_PACKAGE"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(market)
        } catch (_: Exception) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }

    class ShellException(val exitCode: Int, val stdout: String, val stderr: String) :
        RuntimeException("exit $exitCode: ${stderr.ifBlank { stdout }.trim()}")

    /**
     * Lỗi ở bước bind UserService (timeout / binder không hợp lệ / binder
     * chết trong lúc bind): lệnh CHƯA BAO GIỜ chạy nên [exec] được phép
     * fallback sang newProcess. Khác [ShellException] (lệnh đã chạy rồi).
     */
    class ShizukuBindException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)
}

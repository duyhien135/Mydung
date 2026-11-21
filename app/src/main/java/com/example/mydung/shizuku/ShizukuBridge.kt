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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * Shizuku 13.x KHÔNG còn cho app gọi Shizuku.newProcess() (đã private) —
 * cách duy nhất là bind UserService ([ShellService]) chạy bằng quyền shell
 * rồi gọi exec() qua AIDL. Luồng:
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
    private const val BIND_TIMEOUT_MS = 35_000L // server tự xóa record sau 30s nếu bind không xong

    private val _state = MutableStateFlow<State>(State.NotRunning)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var service: IShellService? = null

    private var pendingBind: CompletableDeferred<IShellService>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val execMutex = Mutex()
    private var installed = false

    /**
     * Đã từng gọi requestPermission() trong phiên này chưa. Cần để phân biệt
     * "chưa từng xin quyền" với "bị từ chối vĩnh viễn": cả 2 trường hợp
     * shouldShowRequestPermissionRationale() đều trả false (chỉ khi bị từ
     * chối 1 lần mà KHÔNG tick "không hỏi lại" nó mới trả true).
     */
    private var permissionRequested = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name),
    )
        .daemon(true) // giữ service sống để lần mở app sau bind lại ngay
        .processNameSuffix("shell") // BẮT BUỘC, thiếu là NPE trong Shizuku
        .debuggable(BuildConfig.DEBUG)
        .tag("keytouch-shell") // ổn định dù R8 đổi tên class
        .version(BuildConfig.VERSION_CODE) // đổi version -> server khởi động lại service

    private val connection = object : ServiceConnection {
        // Cả 2 callback đều được Shizuku post lên luồng chính.
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                pendingBind?.completeExceptionally(IllegalStateException("invalid binder for $name"))
                pendingBind = null
                return
            }
            val svc = IShellService.Stub.asInterface(binder)
            service = svc
            pendingBind?.complete(svc)
            pendingBind = null
            _state.update { s -> if (s is State.Ready) s.copy(serviceBound = true) else s }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.update { s -> if (s is State.Ready) s.copy(serviceBound = false) else s }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku binder died"))
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
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            deferred.await()
        }.getOrElse {
            pendingPermission = null
            false
        }
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
            val sent = withContext(Dispatchers.Main) {
                Shizuku.bindUserService(userServiceArgs, connection)
            }
            // bindUserService trả false khi binder chưa sẵn sàng — lỗi ngay
            // thay vì đợi hết 35s timeout rồi mới biết.
            check(sent) { "Shizuku từ chối bind UserService (binder chưa sẵn sàng)" }
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            pendingBind = null
            throw t
        }
    }

    /**
     * Chạy `sh -c cmd` bằng quyền shell (uid 2000, hoặc 0 nếu Shizuku chạy bằng root).
     * Mã thoát khác 0 trả về failure (ShellException mang theo stdout/stderr).
     * Binder chết giữa chừng (Shizuku khởi động lại / bị giết) thì bind lại
     * rồi thử lại 1 lần; vẫn lỗi thì trả failure (không crash).
     */
    suspend fun exec(cmd: String, timeoutMs: Int = 15_000): Result<String> =
        runCatching { execOnce(cmd, timeoutMs) }
            .recoverCatching { e ->
                // DeadObjectException (binder chết) là RemoteException; lỗi
                // khác (vd ShellException do exit != 0) thì không thử lại.
                val isBinderDeath = e is android.os.RemoteException ||
                    generateSequence(e.cause) { it.cause }.any { it is android.os.RemoteException }
                if (!isBinderDeath) throw e
                service = null
                execOnce(cmd, timeoutMs)
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
}

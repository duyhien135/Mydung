package com.example.mydung

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.mydung.server.Config
import com.example.mydung.server.InputClient
import com.example.mydung.server.InputProtocol
import com.example.mydung.server.Profile
import com.example.mydung.server.ProfileStore
import com.example.mydung.server.ServerManager
import com.example.mydung.ui.EditorOverlay
import com.example.mydung.ui.CursorDot
import com.example.mydung.ui.FloatingButton
import com.example.mydung.ui.readRealDisplay
import com.example.mydung.ui.sendProfileToServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service giữ lớp phủ nổi (GĐ5, làm lại giao diện theo GG Mouse Pro).
 *
 * 2 chế độ:
 * - Chơi: nút nổi tròn chữ K (bấm = bật/tắt MAP, giữ = mở trình sửa, kéo =
 *   di chuyển). Cửa sổ WRAP_CONTENT nên vùng nhận chạm chỉ đúng bằng nút,
 *   phần còn lại của màn hình game vẫn chạm bình thường.
 * - Chỉnh: toàn màn hình, nhận chạm để kéo thả nút. Lưu xong quay về chế độ chơi.
 *
 * Service là LifecycleOwner + SavedStateRegistryOwner cho ComposeView (Compose
 * cần cả 2 — thiếu SavedStateRegistryOwner thì rememberSaveable sẽ lỗi).
 * Service bị hệ thống kill thì lớp phủ mất, MAP trong Server vẫn chạy bình
 * thường; mở app bấm lại nút lớp phủ là hiện lại.
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    companion object {
        const val ACTION_START_PLAY = "com.example.mydung.OVERLAY_PLAY"
        const val ACTION_START_EDIT = "com.example.mydung.OVERLAY_EDIT"
        const val ACTION_STOP = "com.example.mydung.OVERLAY_STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_ID = "keytouch_overlay"
        private const val POLL_MAP_MS = 2000L
        // Chế độ con trỏ: thăm dò ~30Hz cho chấm con trỏ mượt (100ms = 10Hz
        // thì chấm giật). Thăm dò qua StatusPoller (1 kết nối giữ mở) nên
        // không spam socket + AUTH mỗi lần.
        private const val POLL_CURSOR_MS = 33L
        private val CURSOR_REGEX = Regex("cursor=([0-9.]+),([0-9.]+)")

        // Vị trí nút nổi lưu theo TỈ LỆ màn hình (xoay màn hình không bị kẹt ngoài).
        private const val PREFS_APP = "keytouch_prefs"
        private const val KEY_FLOAT_X = "float_x_ratio"
        private const val KEY_FLOAT_Y = "float_y_ratio"
        private const val FLOAT_DEFAULT_X = 0.93f // mép phải
        private const val FLOAT_DEFAULT_Y = 0.12f // gần đỉnh

        /** Vị trí nút nổi đã lưu (tỉ lệ 0-1) — trình sửa dùng để cảnh báo phím trùng vùng nút nổi. */
        fun savedFloatRatio(context: Context): Pair<Float, Float> {
            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_FLOAT_X, FLOAT_DEFAULT_X) to
                prefs.getFloat(KEY_FLOAT_Y, FLOAT_DEFAULT_Y)
        }

        /** Lớp phủ (chơi hoặc chỉnh) có đang chạy không — màn chính đọc để hiện trạng thái. */
        val runningFlow = MutableStateFlow(false)

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun startPlay(context: Context) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_START_PLAY),
            )
        }

        fun startEdit(context: Context, profileId: String) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java)
                    .setAction(ACTION_START_EDIT)
                    .putExtra(EXTRA_PROFILE_ID, profileId),
            )
        }

        fun stop(context: Context) {
            runningFlow.value = false
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    /** Cửa sổ chấm con trỏ ảo (chỉ hiện ở chế độ con trỏ, không nhận chạm). */
    private var cursorView: View? = null

    private var pollJob: Job? = null
    private var editingProfile: Profile? = null

    /** Trạng thái MAP (poll mỗi 2s) cho chỉ báo chế độ chơi. */
    val mapOn = MutableStateFlow(false)

    /** Index phần tử đang chờ gán phím (null = không chờ). */
    val captureRequest = MutableStateFlow<Int?>(null)

    /** (index, mã phím) vừa bắt được — editor áp dụng rồi gọi clearCaptured(). */
    val capturedKey = MutableStateFlow<Pair<Int, Int>?>(null)

    private var captureSub: InputClient.Subscription? = null
    private var captureGen = 0 // chống subscription cũ chạy sau khi đã hủy
    private val captureMutex = Mutex() // serialize việc gán captureSub giữa các lần gán

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runningFlow.value = false
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_PLAY -> {
                runningFlow.value = true
                startAsForeground("Lớp phủ chơi đang chạy")
                showPlayOverlay()
            }
            ACTION_START_EDIT -> {
                val store = ProfileStore(ProfileStore.defaultFile(filesDir))
                val state = store.ensureDefault()
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                editingProfile = state.profiles.firstOrNull { it.id == id }
                    ?: state.profiles.firstOrNull { it.id == state.activeId }
                    ?: state.profiles.first()
                runningFlow.value = true
                startAsForeground("Đang chỉnh nút — ${editingProfile?.name}")
                showEditOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runningFlow.value = false
        removeOverlay()
        cancelKeyCapture()
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Lớp phủ KeyTouch", NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Thông báo giữ lớp phủ chơi game chạy nền"
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("KeyTouch")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE chỉ có từ API 34.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Config.OVERLAY_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Config.OVERLAY_NOTIFICATION_ID, notification)
        }
    }

    private fun showPlayOverlay() {
        removeOverlay()
        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent {
            FloatingButton(
                mapOn = mapOn,
                onToggleMap = { toggleMap() },
                onOpenEditor = { openEditor() },
                onDrag = { drag -> moveFloatingButton(drag.x, drag.y) },
            )
        }
        // Nút nổi CẦN nhận chạm (bấm/giữ/kéo) nên KHÔNG đặt NOT_TOUCHABLE.
        // Cửa sổ WRAP_CONTENT nên vùng nhận chạm chỉ đúng bằng nút, không
        // chặn cảm ứng game ở chỗ khác. NOT_TOUCH_MODAL: chạm ngoài nút đi
        // thẳng xuống game, không bị cửa sổ này giữ lại.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val pos = savedFloatPosition()
            x = pos.first
            y = pos.second
        }
        windowManager?.addView(view, params)
        overlayView = view
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        pollMapStatus()
    }

    /** Vị trí nút nổi đã lưu (tỉ lệ -> pixel, kẹp trong màn hình). */
    private fun savedFloatPosition(): Pair<Int, Int> {
        val (rx, ry) = savedFloatRatio(this)
        val d = readRealDisplay(this)
        val w = d?.width ?: resources.displayMetrics.widthPixels
        val h = d?.height ?: resources.displayMetrics.heightPixels
        val btnPx = (56 * resources.displayMetrics.density).toInt()
        return ((rx * w).toInt().coerceIn(0, (w - btnPx).coerceAtLeast(0)) to
            (ry * h).toInt().coerceIn(0, (h - btnPx).coerceAtLeast(0)))
    }

    /** Kéo nút nổi: cập nhật vị trí cửa sổ + lưu tỉ lệ để lần sau mở lại đúng chỗ. */
    private fun moveFloatingButton(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val d = readRealDisplay(this)
        val w = d?.width ?: resources.displayMetrics.widthPixels
        val h = d?.height ?: resources.displayMetrics.heightPixels
        val btnPx = (56 * resources.displayMetrics.density).toInt()
        params.x = (params.x + dx.toInt()).coerceIn(0, (w - btnPx).coerceAtLeast(0))
        params.y = (params.y + dy.toInt()).coerceIn(0, (h - btnPx).coerceAtLeast(0))
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
            return
        }
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit()
            .putFloat(KEY_FLOAT_X, params.x.toFloat() / w)
            .putFloat(KEY_FLOAT_Y, params.y.toFloat() / h)
            .apply()
    }

    /** Bật/tắt MAP từ nút nổi. Bật thì gửi DISPLAY mới nhất trước (Server cần mới cho bật). */
    private fun toggleMap() {
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService)
            if (c == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "Chưa có Server đang chạy", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                val wantOn = !mapOn.value
                if (wantOn) {
                    val d = readRealDisplay(this@OverlayService)
                    if (d != null) {
                        InputClient.sendDisplay(c.first, c.second, d.width, d.height, d.rotation, d.displayId)
                    }
                }
                val resp = InputClient.setMap(c.first, c.second, wantOn)
                if (resp.startsWith("OK")) {
                    mapOn.value = wantOn
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@OverlayService, "Không đổi được MAP: $resp", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "Lỗi MAP: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Giữ nút nổi -> mở trình sửa với profile đang dùng. */
    private fun openEditor() {
        val store = ProfileStore(ProfileStore.defaultFile(filesDir))
        val state = store.ensureDefault()
        editingProfile = state.profiles.firstOrNull { it.id == state.activeId }
            ?: state.profiles.first()
        startAsForeground("Đang chỉnh nút — ${editingProfile?.name}")
        showEditOverlay()
    }

    private fun showEditOverlay() {
        removeOverlay()
        val profile = editingProfile ?: return
        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent {
            EditorOverlay(
                profile = profile,
                onSave = { saved -> saveAndReturnToPlay(saved) },
                onCancel = { showPlayOverlay() },
                captureRequest = captureRequest,
                capturedKey = capturedKey,
                onRequestKeyCapture = { requestKeyCapture(it) },
                onClearCaptured = { capturedKey.value = null },
                onCancelCapture = { cancelKeyCapture() },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Toàn màn hình thật (kể cả vùng tai thỏ/thanh trạng thái) để
            // tỉ lệ 0-1 trong trình sửa khớp đúng tọa độ tiêm.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager?.addView(view, params)
        overlayView = view
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun removeOverlay() {
        pollJob?.cancel()
        pollJob = null
        hideCursorDot()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
            overlayView = null
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun pollMapStatus() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            // Thăm dò MAPSTATUS trên 1 kết nối giữ mở (StatusPoller): ở chế
            // độ con trỏ thăm dò 30Hz mà mở socket + AUTH mới mỗi lần sẽ
            // spam kết nối + thread trên Server.
            var poller: InputClient.StatusPoller? = null
            var pollerKey: Pair<Int, String>? = null
            try {
                while (true) {
                    var cursorMode = false
                    val c = ServerManager.getSavedConnection(this@OverlayService)
                    if (c != null) {
                        if (poller == null || pollerKey != c) {
                            poller?.close()
                            poller = InputClient.StatusPoller(c.first, c.second)
                            pollerKey = c
                        }
                        try {
                            val resp = poller.poll()
                            if (resp != null) {
                                mapOn.value = Regex("map=(\\d)").find(resp)?.groupValues?.get(1) == "1"
                                cursorMode = resp.contains("mode=cursor")
                                if (cursorMode) {
                                    val m = CURSOR_REGEX.find(resp)
                                    val rx = m?.groupValues?.get(1)?.toFloatOrNull()
                                    val ry = m?.groupValues?.get(2)?.toFloatOrNull()
                                    if (rx != null && ry != null) {
                                        withContext(Dispatchers.Main) { updateCursorDot(rx, ry) }
                                    }
                                }
                            } else {
                                mapOn.value = false
                            }
                        } catch (_: Exception) {
                            mapOn.value = false
                        }
                    } else {
                        mapOn.value = false
                        poller?.close()
                        poller = null
                        pollerKey = null
                    }
                    if (!cursorMode) {
                        withContext(Dispatchers.Main) { hideCursorDot() }
                    }
                    // Chế độ con trỏ thì thăm dò nhanh (~30Hz) để chấm con
                    // trỏ mượt; không thì 2s như trước (chỉ để cập nhật trạng
                    // thái MAP).
                    delay(if (cursorMode) POLL_CURSOR_MS else POLL_MAP_MS)
                }
            } finally {
                poller?.close()
            }
        }
    }

    /** Hiện/di chuyển chấm con trỏ ảo tới vị trí tỉ lệ (rx, ry). Chạy trên luồng chính. */
    private fun updateCursorDot(rx: Float, ry: Float) {
        val wm = windowManager ?: return
        val d = readRealDisplay(this)
        val w = d?.width ?: resources.displayMetrics.widthPixels
        val h = d?.height ?: resources.displayMetrics.heightPixels
        val dotPx = (24 * resources.displayMetrics.density).toInt()
        val x = (rx * w).toInt() - dotPx / 2
        val y = (ry * h).toInt() - dotPx / 2
        val view = cursorView
        if (view == null) {
            val v = ComposeView(this)
            v.setViewTreeLifecycleOwner(this)
            v.setViewTreeSavedStateRegistryOwner(this)
            v.setContent { CursorDot() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Chấm con trỏ chỉ để nhìn, TUYỆT ĐỐI không nhận chạm.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Alpha cửa sổ ≤ 0,8: Android 12+ chặn chạm xuyên qua lớp phủ
                // mờ đục (kể cả khi NOT_TOUCHABLE, cảm ứng tiêm xuống game
                // vẫn có thể bị chặn ở vùng chấm con trỏ che).
                alpha = 0.8f
                this.x = x
                this.y = y
            }
            try {
                wm.addView(v, params)
                cursorView = v
            } catch (_: Exception) {
            }
        } else {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            // Chỉ cập nhật khi di chuyển đủ xa để đỡ gọi binder liên tục.
            if (kotlin.math.abs(params.x - x) < 2 && kotlin.math.abs(params.y - y) < 2) return
            params.x = x
            params.y = y
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    /** Ẩn chấm con trỏ (rời chế độ con trỏ / tắt lớp phủ). */
    private fun hideCursorDot() {
        cursorView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
            cursorView = null
        }
    }

    /** Bắt đầu chờ bấm 1 phím/nút cho phần tử [index] (qua SUB_INPUT). */
    fun requestKeyCapture(index: Int) {
        captureGen++
        val gen = captureGen
        cancelKeyCapture()
        captureRequest.value = index
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService)
            if (c == null) {
                if (gen == captureGen) captureRequest.value = null
                return@launch
            }
            try {
                val sub = InputClient.Subscription(
                    c.first, c.second,
                    { line ->
                        if (gen != captureGen) return@Subscription
                        val code = InputProtocol.parseKeyPress(line)
                        if (code != null) {
                            capturedKey.value = index to code
                            cancelKeyCapture()
                        }
                    },
                    { _ ->
                        if (gen == captureGen) captureRequest.value = null
                    },
                )
                if (gen != captureGen) {
                    sub.close()
                    return@launch
                }
                captureMutex.withLock {
                    if (gen == captureGen && captureRequest.value != null) {
                        captureSub = sub
                    } else {
                        // Đã bị hủy/hoàn thành trong lúc đang kết nối: đóng ngay.
                        sub.close()
                    }
                }
            } catch (_: Exception) {
                if (gen == captureGen) captureRequest.value = null
            }
        }
    }

    fun cancelKeyCapture() {
        captureSub?.close()
        captureSub = null
        captureRequest.value = null
    }

    /** Lưu profile đã chỉnh, gửi cho Server, quay về lớp phủ chơi. */
    private fun saveAndReturnToPlay(saved: Profile) {
        cancelKeyCapture()
        scope.launch(Dispatchers.IO) {
            val store = ProfileStore(ProfileStore.defaultFile(filesDir))
            val state = store.load()
            val updated = state.profiles.map { if (it.id == saved.id) saved else it }
            val final = if (updated.any { it.id == saved.id }) updated else updated + saved
            store.save(final, state.activeId ?: saved.id)
            sendProfileToServer(this@OverlayService, saved)
            scope.launch(Dispatchers.Main) { showPlayOverlay() }
        }
    }
}

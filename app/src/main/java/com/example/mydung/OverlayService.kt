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
import android.view.PointerIcon
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
import com.example.mydung.server.KeyTouchPrefs
import com.example.mydung.server.LinuxKey
import com.example.mydung.server.Profile
import com.example.mydung.server.ProfileElement
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
 * - Chơi: nút nổi tròn chữ K (bấm = mở bảng điều khiển, giữ = mở trình
 *   sửa, kéo = di chuyển). Bảng điều khiển có bật/tắt keymap, chỉnh nút,
 *   đổi chế độ con trỏ ↔ nhìn tự do, cài đặt nhanh (độ nhạy X/Y, đầm tâm,
 *   tốc độ con trỏ, tần số quét chuột, kích thước con trỏ, trong suốt
 *   logo, phím ẩn/hiện lớp phủ), ẩn lớp phủ và mở app. Cửa sổ WRAP_CONTENT
 *   khi bảng đóng nên vùng nhận chạm chỉ đúng bằng nút, phần còn lại của
 *   màn hình game vẫn chạm bình thường; bảng mở thì cửa sổ đổi sang
 *   MATCH_PARENT.
 * - Chỉnh: toàn màn hình, nhận chạm để kéo thả nút. Lưu xong quay về chế độ chơi.
 *
 * Ẩn/hiện lớp phủ (build-9): lớp phủ chỉ hiện khi (đang trong game) VÀ
 * (không bị ẩn thủ công). Đang trong game = app foreground là package của
 * 1 game đã thêm (ServerManager.fgPackageFlow; chưa có event FG nào thì
 * coi như trong game — giữ hành vi cũ). Ẩn thủ công = nút "Ẩn lớp phủ"
 * trong bảng nút K hoặc phím nóng (mặc định F10, Server chặn phím rồi báo
 * qua MAPSTATUS overlay=1|0); trạng thái lưu SharedPreferences để mở lại
 * app vẫn nhớ, quên phím thì bấm "Hiện lại lớp phủ" trong tab Cài đặt.
 * Rời game thì tạm ngừng inject (nhớ trạng thái MAP), quay lại thì khôi
 * phục; trình sửa đang mở thì lưu trước khi ẩn. Keymap vẫn chạy ngầm khi
 * lớp phủ bị ẩn thủ công (chỉ ẩn phần hiển thị).
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
        // Build-9: hiện lại lớp phủ sau khi đã ẩn thủ công (nút trong tab Cài đặt).
        const val ACTION_SHOW_OVERLAY = "com.example.mydung.OVERLAY_SHOW"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_ID = "keytouch_overlay"
        private const val POLL_MAP_MS = 2000L
        // Chế độ con trỏ: thăm dò theo tần số quét chuột (pollRateHz, mặc
        // định 500Hz -> 2ms). Thăm dò qua StatusPoller (1 kết nối giữ mở)
        // nên không spam socket + AUTH mỗi lần.
        private val CURSOR_REGEX = Regex("cursor=([0-9.]+),([0-9.]+)")
        private val OVERLAY_REGEX = Regex("overlay=(\\d)")

        // Vị trí nút nổi lưu theo TỈ LỆ màn hình (xoay màn hình không bị kẹt ngoài).
        private const val PREFS_APP = "keytouch_prefs"
        private const val KEY_FLOAT_X = "float_x_ratio"
        private const val KEY_FLOAT_Y = "float_y_ratio"
        private const val FLOAT_DEFAULT_X = 0.93f // mép phải
        private const val FLOAT_DEFAULT_Y = 0.12f // gần đỉnh

        // Cài đặt nhanh trong bảng nổi (lưu cùng keytouch_prefs).
        private const val KEY_CURSOR_DOT_SIZE_DP = "cursor_dot_size_dp"
        private const val KEY_FLOAT_ALPHA = "float_alpha"
        private const val CURSOR_DOT_DEFAULT_DP = 18 // tam giác nhỏ (build-9, trước là chấm tròn 24dp)
        private const val FLOAT_DEFAULT_ALPHA = 0.65f

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

        /** Hiện lại lớp phủ (đã ẩn thủ công mà quên phím tắt) — nút trong tab Cài đặt. */
        fun showOverlay(context: Context) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_SHOW_OVERLAY),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    /** Cửa sổ chấm con trỏ ảo (chỉ hiện ở chế độ con trỏ, không nhận chạm). */
    private var cursorView: View? = null

    /** Cửa sổ ẩn con trỏ hệ thống (build-9): toàn màn hình, trong suốt,
     *  không nhận chạm, pointerIcon TYPE_NULL — chỉ hiện khi MAP bật (để
     *  con trỏ hệ thống không hiện cùng tam giác con trỏ ảo). */
    private var hiderView: View? = null

    private var pollJob: Job? = null
    private var fgJob: Job? = null
    private var hiderJob: Job? = null
    private var editingProfile: Profile? = null

    /** Đang ở chế độ chỉnh (true) hay chơi (false). */
    private var editing = false

    /** Các cửa sổ lớp phủ có đang gắn vào WindowManager không. */
    private var viewsShown = false

    /** MAP có đang bật trước khi rời game không (để khôi phục khi quay lại). */
    private var mapOnBeforeLeavingGame = false

    /** Đang trong game (app foreground là package của 1 game đã thêm) — chưa
     *  có event FG nào thì true (giữ hành vi cũ: hiện bình thường). */
    val inGame = MutableStateFlow(true)

    /** Lớp phủ có đang bị ẩn thủ công không (nút "Ẩn lớp phủ" / phím F10). */
    val manualHidden = MutableStateFlow(false)

    /** Trạng thái MAP (poll mỗi 2s) cho chỉ báo chế độ chơi. */
    val mapOn = MutableStateFlow(false)

    /** Chế độ con trỏ hiện tại (poll MAPSTATUS) — bảng nổi dùng để hiện đúng mục đổi chế độ. */
    val cursorMode = MutableStateFlow(false)

    /** Vị trí nút nổi (pixel, góc trên-trái cửa sổ) — bảng nổi dùng để neo vị trí. */
    val floatPosPx = MutableStateFlow(0 to 0)

    /** Độ nhạy chuột X/Y của profile đang dùng — 2 thanh trượt trong bảng Cài đặt (build-9). */
    val sensX = MutableStateFlow(Config.SENS_X)
    val sensY = MutableStateFlow(Config.SENS_Y)

    /** Đầm tâm X/Y (mức 1..10) của profile đang dùng — bảng Cài đặt (build-9). */
    val dampX = MutableStateFlow(Config.DAMP_DEFAULT)
    val dampY = MutableStateFlow(Config.DAMP_DEFAULT)

    /** Tốc độ con trỏ ảo (0.5..3.0, lưu SharedPreferences) — bảng Cài đặt (build-9). */
    val cursorSpeed = MutableStateFlow(Config.CURSOR_SPEED_DEFAULT)

    /** Tần số quét chuột (Hz, lưu SharedPreferences) — bảng Cài đặt (build-9). */
    val pollRateHz = MutableStateFlow(Config.DEFAULT_POLL_RATE_HZ)

    /** Phím ẩn/hiện lớp phủ của profile đang dùng (mặc định F10) — bảng Cài đặt (build-9). */
    val overlayToggleKey = MutableStateFlow(Config.OVERLAY_TOGGLE_KEY)

    /** Kích thước chấm con trỏ (dp) — thanh trượt trong bảng Cài đặt. */
    val cursorDotSizeDp = MutableStateFlow(CURSOR_DOT_DEFAULT_DP)

    /** Độ trong suốt nền nút K (0.2..1) — thanh trượt trong bảng Cài đặt. */
    val floatAlpha = MutableStateFlow(FLOAT_DEFAULT_ALPHA)

    /** Index phần tử đang chờ gán phím (null = không chờ; -1 = đang chờ gán phím ẩn/hiện lớp phủ). */
    val captureRequest = MutableStateFlow<Int?>(null)

    /** (index, mã phím) vừa bắt được — editor áp dụng rồi gọi clearCaptured(). */
    val capturedKey = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Danh sách phần tử hiện tại trong trình sửa (editor cập nhật qua
     *  onElementsChange) — rời game khi đang chỉnh thì lưu từ đây. */
    val editorElements = MutableStateFlow<List<ProfileElement>?>(null)

    private var captureSub: InputClient.Subscription? = null
    private var captureGen = 0 // chống subscription cũ chạy sau khi đã hủy
    private val captureMutex = Mutex() // serialize việc gán captureSub giữa các lần gán

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Cài đặt nhanh đã lưu (kích thước con trỏ, trong suốt logo).
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        cursorDotSizeDp.value =
            prefs.getInt(KEY_CURSOR_DOT_SIZE_DP, CURSOR_DOT_DEFAULT_DP).coerceIn(12, 48)
        floatAlpha.value =
            prefs.getFloat(KEY_FLOAT_ALPHA, FLOAT_DEFAULT_ALPHA).coerceIn(0.2f, 1f)
        // Build-9: tần số quét chuột / tốc độ con trỏ / trạng thái ẩn lớp phủ đã lưu.
        pollRateHz.value = KeyTouchPrefs.pollRateHz(this)
        cursorSpeed.value = KeyTouchPrefs.cursorSpeed(this)
        manualHidden.value = KeyTouchPrefs.overlayHidden(this)
        startFgCollector()
        startHiderCollector()
    }

    /**
     * Theo dõi app foreground (build-9): FG là package của 1 game đã thêm
     * thì hiện lớp phủ, FG khác (launcher, app khác) thì ẩn. Rời game thì
     * tạm ngừng inject (nhớ trạng thái MAP), quay lại thì khôi phục đúng
     * trạng thái trước khi rời (người dùng tắt tay thì giữ tắt).
     */
    private fun startFgCollector() {
        fgJob?.cancel()
        fgJob = scope.launch {
            ServerManager.fgPackageFlow.collect { pkg ->
                val nowInGame = pkg == null || isGamePackage(pkg)
                if (nowInGame == inGame.value) return@collect
                inGame.value = nowInGame
                if (!nowInGame) {
                    if (mapOn.value) {
                        mapOnBeforeLeavingGame = true
                        setMapOnServer(false)
                    }
                } else {
                    if (mapOnBeforeLeavingGame) {
                        mapOnBeforeLeavingGame = false
                        setMapOnServer(true)
                    }
                }
                applyOverlayVisibility()
            }
        }
    }

    /** FG package có phải 1 game đã thêm trong danh sách profile không. */
    private suspend fun isGamePackage(pkg: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val store = ProfileStore(ProfileStore.defaultFile(filesDir))
            store.load().profiles.any { it.packageName == pkg }
        }.getOrDefault(false)
    }

    /** Bật/tắt MAP trên Server (không toast — dùng cho tạm ngừng/khôi phục khi rời/vào game). */
    private fun setMapOnServer(on: Boolean) {
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService) ?: return@launch
            try {
                if (on) {
                    val d = readRealDisplay(this@OverlayService)
                    if (d != null) {
                        InputClient.sendDisplay(c.first, c.second, d.width, d.height, d.rotation, d.displayId)
                    }
                }
                val resp = InputClient.setMap(c.first, c.second, on)
                if (resp.startsWith("OK")) mapOn.value = on
            } catch (_: Exception) {
            }
        }
    }

    /** Cửa sổ ẩn con trỏ hệ thống chỉ hiện khi MAP bật VÀ lớp phủ đang hiện. */
    private fun startHiderCollector() {
        hiderJob?.cancel()
        hiderJob = scope.launch {
            mapOn.collect { on ->
                if (on && shouldShowOverlay()) showCursorHider() else hideCursorHider()
            }
        }
    }

    /** Lớp phủ có được hiện không: đang trong game VÀ không bị ẩn thủ công. */
    private fun shouldShowOverlay(): Boolean = inGame.value && !manualHidden.value

    /**
     * Áp dụng trạng thái ẩn/hiện lớp phủ (build-9): chỉ hiện khi đang trong
     * game VÀ không bị ẩn thủ công. Chuyển từ hiện sang ẩn khi đang mở trình
     * sửa thì lưu profile trước rồi về chế độ chơi.
     */
    private fun applyOverlayVisibility() {
        val show = shouldShowOverlay()
        if (show == viewsShown) return
        if (!show && editing) saveEditorSilently()
        viewsShown = show
        if (show) {
            if (editing) addEditView() else addPlayView()
            if (mapOn.value) showCursorHider()
        } else {
            removeOverlayViews()
        }
    }

    /** Lưu profile đang chỉnh (rời game / ẩn tay khi đang mở trình sửa). */
    private fun saveEditorSilently() {
        val profile = editingProfile ?: return
        val elements = editorElements.value ?: return
        val saved = profile.copy(elements = elements)
        editing = false
        editingProfile = null
        editorElements.value = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = ProfileStore(ProfileStore.defaultFile(filesDir))
                val state = store.load()
                val updated = state.profiles.map { if (it.id == saved.id) saved else it }
                val final = if (updated.any { it.id == saved.id }) updated else updated + saved
                store.save(final, state.activeId ?: saved.id)
                sendProfileToServer(this@OverlayService, saved)
            }
        }
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
            ACTION_SHOW_OVERLAY -> {
                // Hiện lại lớp phủ sau khi đã ẩn thủ công (quên phím tắt).
                manualHidden.value = false
                KeyTouchPrefs.setOverlayHidden(this, false)
                scope.launch(Dispatchers.IO) {
                    val c = ServerManager.getSavedConnection(this@OverlayService)
                        ?: return@launch
                    runCatching { InputClient.setOverlayHidden(c.first, c.second, false) }
                }
                runningFlow.value = true
                startAsForeground("Lớp phủ chơi đang chạy")
                showPlayOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runningFlow.value = false
        removeOverlay()
        cancelKeyCapture()
        fgJob?.cancel()
        hiderJob?.cancel()
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

    /** Chuyển sang chế độ chơi (nút nổi K). View chỉ gắn khi lớp phủ được hiện. */
    private fun showPlayOverlay() {
        removeOverlayViews()
        editing = false
        editorElements.value = null
        // Vị trí nút nổi + cài đặt của profile đang dùng (bảng nổi đọc).
        val pos = savedFloatPosition()
        floatPosPx.value = pos
        runCatching {
            val store = ProfileStore(ProfileStore.defaultFile(filesDir))
            val state = store.ensureDefault()
            val active = state.profiles.firstOrNull { it.id == state.activeId }
                ?: state.profiles.first()
            sensX.value = active.sensX
            sensY.value = active.sensY
            dampX.value = active.dampX
            dampY.value = active.dampY
            overlayToggleKey.value = active.overlayToggleKey
        }
        ensurePolling()
        applyOverlayVisibility()
    }

    /** Gắn cửa sổ nút nổi K (chỉ gọi khi lớp phủ được hiện). */
    private fun addPlayView() {
        val pos = floatPosPx.value
        val view = ComposeView(this)
        // Ẩn con trỏ hệ thống khi rê qua nút nổi (build-9, chống 2 con trỏ).
        view.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent {
            FloatingButton(
                mapOn = mapOn,
                cursorMode = cursorMode,
                buttonPosPx = floatPosPx,
                logoAlpha = floatAlpha,
                sensX = sensX,
                sensY = sensY,
                dampX = dampX,
                dampY = dampY,
                cursorSpeed = cursorSpeed,
                pollRateHz = pollRateHz,
                overlayToggleKey = overlayToggleKey,
                cursorDotSizeDp = cursorDotSizeDp,
                captureRequest = captureRequest,
                capturedKey = capturedKey,
                onToggleMap = { toggleMap() },
                onToggleCursorMode = { toggleCursorMode() },
                onOpenEditor = { openEditor() },
                onOpenApp = { openApp() },
                onHideOverlay = { hideOverlayManually() },
                onSensXChange = { v -> applySensX(v) },
                onSensYChange = { v -> applySensY(v) },
                onDampXChange = { v -> applyDampX(v) },
                onDampYChange = { v -> applyDampY(v) },
                onCursorSpeedChange = { v -> applyCursorSpeed(v) },
                onPollRateChange = { hz -> setPollRate(hz) },
                onOverlayKeyChange = { code -> applyOverlayKey(code) },
                onRequestOverlayKeyCapture = { requestOverlayKeyCapture() },
                onCancelCapture = { cancelKeyCapture() },
                onClearCaptured = { capturedKey.value = null },
                onCursorDotSizeChange = { dp -> setCursorDotSize(dp) },
                onLogoAlphaChange = { a -> setFloatAlpha(a) },
                onDrag = { drag -> moveFloatingButton(drag.x, drag.y) },
                onMenuOpenChange = { open -> setMenuOpen(open) },
            )
        }
        // Nút nổi CẦN nhận chạm (bấm/giữ/kéo) nên KHÔNG đặt NOT_TOUCHABLE.
        // Cửa sổ WRAP_CONTENT nên vùng nhận chạm chỉ đúng bằng nút, không
        // chặn cảm ứng game ở chỗ khác. NOT_TOUCH_MODAL: chạm ngoài nút đi
        // thẳng xuống game, không bị cửa sổ này giữ lại. Bảng nổi mở thì
        // setMenuOpen() đổi cửa sổ sang MATCH_PARENT.
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
            x = pos.first
            y = pos.second
        }
        windowManager?.addView(view, params)
        overlayView = view
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Đổi kích thước cửa sổ nút nổi theo bảng điều khiển: bảng mở thì
     * MATCH_PARENT toàn màn hình (bảng + lớp nền bấm-ra-ngoài có chỗ hiện;
     * nút K vẽ lại đúng vị trí đã lưu bên trong nội dung), bảng đóng thì
     * về WRAP_CONTENT ở đúng vị trí nút. Chạy trên luồng chính (gọi từ
     * pointerInput của Compose).
     */
    private fun setMenuOpen(open: Boolean) {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        try {
            if (open) {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
            } else {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                val pos = floatPosPx.value
                params.x = pos.first
                params.y = pos.second
            }
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
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
        floatPosPx.value = params.x to params.y
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

    /** Đổi chế độ con trỏ <-> nhìn tự do từ bảng nổi (lệnh CURSORMODE). */
    private fun toggleCursorMode() {
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService)
            if (c == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "Chưa có Server đang chạy", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                val wantCursor = !cursorMode.value
                val resp = InputClient.setCursorMode(c.first, c.second, wantCursor)
                if (resp.startsWith("OK")) {
                    cursorMode.value = wantCursor
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@OverlayService, "Không đổi được chế độ: $resp", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "Lỗi đổi chế độ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Mở app KeyTouch (mục trong bảng nổi — để vào phần kích hoạt/danh sách game). */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    /**
     * Lưu 1 trường vào profile đang dùng + gửi TOUCHCFG cho Server áp dụng
     * ngay (dùng chung cho độ nhạy X/Y, đầm tâm X/Y). Server chưa chạy thì
     * chỉ lưu profile, lần gửi profile sau sẽ áp dụng.
     */
    private fun updateActiveProfileAndCfg(
        transform: (Profile) -> Profile,
        cfg: Map<String, Float>,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = ProfileStore(ProfileStore.defaultFile(filesDir))
                val state = store.load()
                val activeId = state.activeId ?: state.profiles.firstOrNull()?.id
                if (activeId != null) {
                    val updated = state.profiles.map {
                        if (it.id == activeId) transform(it) else it
                    }
                    store.save(updated, activeId)
                }
            }
            val c = ServerManager.getSavedConnection(this@OverlayService) ?: return@launch
            runCatching {
                InputClient.sendTouchCfg(c.first, c.second, cfg)
            }
        }
    }

    /** Độ nhạy X (ngang) — thanh trượt riêng trong bảng Cài đặt (build-9). */
    private fun applySensX(value: Float) {
        val v = value.coerceIn(0.1f, 5f)
        sensX.value = v
        updateActiveProfileAndCfg({ it.copy(sensX = v) }, mapOf("sensX" to v))
    }

    /** Độ nhạy Y (dọc) — thanh trượt riêng trong bảng Cài đặt (build-9). */
    private fun applySensY(value: Float) {
        val v = value.coerceIn(0.1f, 5f)
        sensY.value = v
        updateActiveProfileAndCfg({ it.copy(sensY = v) }, mapOf("sensY" to v))
    }

    /** Đầm tâm X (mức 1..10) — bảng Cài đặt (build-9). */
    private fun applyDampX(level: Int) {
        val v = level.coerceIn(Config.DAMP_MIN, Config.DAMP_MAX)
        dampX.value = v
        updateActiveProfileAndCfg({ it.copy(dampX = v) }, mapOf("dampX" to v.toFloat()))
    }

    /** Đầm tâm Y (mức 1..10) — bảng Cài đặt (build-9). */
    private fun applyDampY(level: Int) {
        val v = level.coerceIn(Config.DAMP_MIN, Config.DAMP_MAX)
        dampY.value = v
        updateActiveProfileAndCfg({ it.copy(dampY = v) }, mapOf("dampY" to v.toFloat()))
    }

    /** Tốc độ con trỏ ảo (0.5..3.0, lưu SharedPreferences) — bảng Cài đặt (build-9). */
    private fun applyCursorSpeed(value: Float) {
        val v = value.coerceIn(Config.CURSOR_SPEED_MIN, Config.CURSOR_SPEED_MAX)
        cursorSpeed.value = v
        KeyTouchPrefs.setCursorSpeed(this, v)
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService) ?: return@launch
            runCatching {
                InputClient.sendTouchCfg(c.first, c.second, mapOf("cursorSpeed" to v))
            }
        }
    }

    /** Tần số quét chuột (125/250/500/1000 Hz, lưu SharedPreferences) — bảng Cài đặt (build-9). */
    private fun setPollRate(hz: Int) {
        val v = if (hz in Config.POLL_RATES) hz else Config.DEFAULT_POLL_RATE_HZ
        pollRateHz.value = v
        KeyTouchPrefs.setPollRateHz(this, v)
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService) ?: return@launch
            runCatching { InputClient.setPollRate(c.first, c.second, v) }
        }
    }

    /**
     * Đổi phím ẩn/hiện lớp phủ (build-9): lưu vào profile đang dùng + gửi
     * TOUCHCFG overlayKey. Chặn phím chuột (chỉ nhận phím bàn phím — Server
     * chỉ chặn InputEvent.Key) và chặn F8/F9 (đã dùng cho MAP / con trỏ).
     */
    private fun applyOverlayKey(code: Int) {
        scope.launch(Dispatchers.IO) {
            val isMouseButton = code == LinuxKey.BTN_LEFT || code == LinuxKey.BTN_RIGHT ||
                code == LinuxKey.BTN_MIDDLE || code == LinuxKey.BTN_SIDE ||
                code == LinuxKey.BTN_EXTRA
            if (isMouseButton) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OverlayService,
                        "Phím ẩn/hiện lớp phủ chỉ dùng phím bàn phím",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val store = ProfileStore(ProfileStore.defaultFile(filesDir))
            val state = runCatching { store.load() }.getOrNull()
            val active = state?.profiles?.firstOrNull { it.id == state.activeId }
            if (active != null &&
                (code == active.mapToggleKey || code == active.cursorToggleKey)
            ) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OverlayService,
                        "Phím ${LinuxKey.name(code)} đã dùng cho bật/tắt MAP / đổi chế độ",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            overlayToggleKey.value = code
            runCatching {
                if (state != null && active != null) {
                    val updated = state.profiles.map {
                        if (it.id == active.id) it.copy(overlayToggleKey = code) else it
                    }
                    store.save(updated, state.activeId)
                }
            }
            val c = ServerManager.getSavedConnection(this@OverlayService) ?: return@launch
            runCatching {
                InputClient.sendTouchCfg(c.first, c.second, mapOf("overlayKey" to code.toFloat()))
            }
        }
    }

    /** Ẩn lớp phủ thủ công (nút trong bảng nút K): ẩn toàn bộ cửa sổ nhưng
     *  keymap vẫn chạy ngầm. Hiện lại bằng phím nóng (F10) hoặc nút "Hiện
     *  lại lớp phủ" trong tab Cài đặt. */
    private fun hideOverlayManually() {
        manualHidden.value = true
        KeyTouchPrefs.setOverlayHidden(this, true)
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(this@OverlayService) ?: return@launch
            runCatching { InputClient.setOverlayHidden(c.first, c.second, true) }
        }
        applyOverlayVisibility()
    }

    /** Đổi kích thước chấm con trỏ (lưu prefs + cập nhật flow để CursorDot vẽ lại). */
    private fun setCursorDotSize(dp: Int) {
        val v = dp.coerceIn(12, 48)
        cursorDotSizeDp.value = v
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit()
            .putInt(KEY_CURSOR_DOT_SIZE_DP, v).apply()
    }

    /** Đổi độ trong suốt nền nút K (lưu prefs + cập nhật flow để nút vẽ lại). */
    private fun setFloatAlpha(alpha: Float) {
        val v = alpha.coerceIn(0.2f, 1f)
        floatAlpha.value = v
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit()
            .putFloat(KEY_FLOAT_ALPHA, v).apply()
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

    /** Chuyển sang chế độ chỉnh (trình sửa toàn màn hình). View chỉ gắn khi lớp phủ được hiện. */
    private fun showEditOverlay() {
        removeOverlayViews()
        editing = true
        editorElements.value = editingProfile?.elements
        ensurePolling()
        applyOverlayVisibility()
    }

    /** Gắn cửa sổ trình sửa (chỉ gọi khi lớp phủ được hiện). */
    private fun addEditView() {
        val profile = editingProfile ?: return
        val view = ComposeView(this)
        // Ẩn con trỏ hệ thống khi rê trong trình sửa (build-9, chống 2 con trỏ).
        view.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
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
                onElementsChange = { editorElements.value = it },
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

    /** Bắt đầu thăm dò MAPSTATUS nếu chưa chạy (giữ chạy cả khi lớp phủ ẩn
     *  để phím nóng F10 vẫn đổi được trạng thái ẩn/hiện). */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollMapStatus()
    }

    /** Gỡ toàn bộ cửa sổ lớp phủ (giữ nguyên polling + service). */
    private fun removeOverlayViews() {
        hideCursorDot()
        hideCursorHider()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
            overlayView = null
        }
        viewsShown = false
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun removeOverlay() {
        pollJob?.cancel()
        pollJob = null
        removeOverlayViews()
    }

    private fun pollMapStatus() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            // Thăm dò MAPSTATUS trên 1 kết nối giữ mở (StatusPoller): ở chế
            // độ con trỏ thăm dò theo tần số quét chuột mà mở socket + AUTH
            // mới mỗi lần sẽ spam kết nối + thread trên Server.
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
                                // Build-9: phím nóng F10 đổi trạng thái ẩn lớp
                                // phủ trên Server — app đọc overlay= rồi tự
                                // ẩn/hiện các cửa sổ + lưu lại để nhớ.
                                val overlayHidden =
                                    OVERLAY_REGEX.find(resp)?.groupValues?.get(1) == "1"
                                if (overlayHidden != manualHidden.value) {
                                    manualHidden.value = overlayHidden
                                    KeyTouchPrefs.setOverlayHidden(
                                        this@OverlayService, overlayHidden,
                                    )
                                    withContext(Dispatchers.Main) { applyOverlayVisibility() }
                                }
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
                    this@OverlayService.cursorMode.value = cursorMode
                    if (!cursorMode) {
                        withContext(Dispatchers.Main) { hideCursorDot() }
                    }
                    // Chế độ con trỏ thì thăm dò theo tần số quét chuột
                    // (mặc định 500Hz -> 2ms) để tam giác con trỏ mượt;
                    // không thì 2s như trước (chỉ để cập nhật trạng thái MAP).
                    delay(if (cursorMode) Config.pollIntervalMs(pollRateHz.value) else POLL_MAP_MS)
                }
            } finally {
                poller?.close()
            }
        }
    }

    /** Hiện/di chuyển tam giác con trỏ ảo tới vị trí tỉ lệ (rx, ry). Chạy trên luồng chính. */
    private fun updateCursorDot(rx: Float, ry: Float) {
        // Lớp phủ đang ẩn thì không hiện lại con trỏ.
        if (!shouldShowOverlay()) return
        val wm = windowManager ?: return
        val d = readRealDisplay(this)
        val w = d?.width ?: resources.displayMetrics.widthPixels
        val h = d?.height ?: resources.displayMetrics.heightPixels
        val dotPx = (cursorDotSizeDp.value * resources.displayMetrics.density).toInt()
        // Tam giác hướng lên: ĐỈNH tam giác (điểm nóng) nằm đúng vị trí con
        // trỏ — x căn giữa, y = đúng vị trí (không trừ nửa cỡ như chấm tròn).
        val x = (rx * w).toInt() - dotPx / 2
        val y = (ry * h).toInt()
        val view = cursorView
        if (view == null) {
            val v = ComposeView(this)
            v.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
            v.setViewTreeLifecycleOwner(this)
            v.setViewTreeSavedStateRegistryOwner(this)
            v.setContent { CursorDot(cursorDotSizeDp) }
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

    /**
     * Hiện cửa sổ ẩn con trỏ hệ thống (build-9): toàn màn hình, trong suốt,
     * không nhận chạm, pointerIcon TYPE_NULL — con trỏ hệ thống không hiện
     * khi rê chuột trong game (chỉ còn tam giác con trỏ ảo). Chạy trên luồng
     * chính. Best-effort: nếu hover không tới được cửa sổ NOT_TOUCHABLE thì
     * con trỏ hệ thống vẫn hiện — khi đó grab evdev (cách C) là tuyến phòng
     * thủ chính, cần kiểm chứng trên máy thật.
     */
    private fun showCursorHider() {
        if (hiderView != null) return
        val wm = windowManager ?: return
        val v = View(this)
        v.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT,
        )
        try {
            wm.addView(v, params)
            hiderView = v
        } catch (_: Exception) {
        }
    }

    /** Gỡ cửa sổ ẩn con trỏ hệ thống (MAP tắt / lớp phủ ẩn). */
    private fun hideCursorHider() {
        hiderView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
            hiderView = null
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

    /**
     * Bắt phím ẩn/hiện lớp phủ (bảng Cài đặt của nút K): dùng chung cơ chế
     * bắt phím của trình sửa, index -1 = phím lớp phủ (FloatingButton đọc
     * capturedKey.first == -1 rồi gọi applyOverlayKey).
     */
    fun requestOverlayKeyCapture() {
        requestKeyCapture(-1)
    }

    /** Lưu profile đã chỉnh, gửi cho Server, quay về lớp phủ chơi. */
    private fun saveAndReturnToPlay(saved: Profile) {
        cancelKeyCapture()
        editorElements.value = null
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

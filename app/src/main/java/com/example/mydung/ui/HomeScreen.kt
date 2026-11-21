package com.example.mydung.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.InputDevice
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.mydung.ActivationMethod
import com.example.mydung.AdbConnectionManager
import com.example.mydung.ConnectionState
import com.example.mydung.OverlayService
import com.example.mydung.PairingState
import com.example.mydung.server.AdbShellExecutor
import com.example.mydung.server.DeviceGrouping
import com.example.mydung.server.InputClient
import com.example.mydung.server.Profile
import com.example.mydung.server.ProfileCodec
import com.example.mydung.server.ProfileStore
import com.example.mydung.server.RootShellExecutor
import com.example.mydung.server.ServerManager
import com.example.mydung.server.ServerStatus
import com.example.mydung.server.ShellExecutor
import com.example.mydung.server.ShizukuShellExecutor
import com.example.mydung.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Tab Game — màn hình chính tối giản (build-8, theo ý người dùng: gọn như
 * GG Mouse Pro / K7XD):
 *
 * - Server TỰ khởi động khi mở app (không còn nút khởi động tay): màn chính
 *   đợi kiểm tra xong Server cũ + phương thức sẵn sàng rồi gọi startServer()
 *   ngầm 1 lần. Chỉ hiện "Đang chuẩn bị…" rồi "Sẵn sàng".
 * - Màn chính chỉ còn: 1 dòng trạng thái + danh sách game (logo + tên +
 *   nút "Vô"; nhấn giữ thẻ để xóa). Viên quyền / hàng thiết bị /
 *   checklist chỉ hiện khi CÓ gì chưa xong (quyền thiếu, đã cắm chuột/phím,
 *   bước kích hoạt chưa xong).
 * - Không hiện PID/UID/cổng lên UI nữa (chỉ ghi log). Lỗi Server thì hiện
 *   1 dòng ngắn + nút "Thử lại", log chi tiết vẫn ở dưới để sao chép.
 * - Chỉnh nút nằm trong bảng nút nổi K; Thử phím/Thử cảm ứng chuyển sang
 *   tab Cài đặt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    method: ActivationMethod,
    onOpenAdbSetup: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adbManager = remember { AdbConnectionManager.getInstance(context) }
    val rootExecutor = remember { RootShellExecutor() }

    val shizukuState by ShizukuBridge.state.collectAsState()
    val serverInfo by ServerManager.serverInfoFlow.collectAsState()
    val overlayRunning by OverlayService.runningFlow.collectAsState()
    val connState by PairingState.stateFlow.collectAsState()
    // Đã kiểm tra xong Server cũ lúc mở app chưa (đợi cờ này rồi mới tự start,
    // không thì chạy đua với lần ping Server cũ).
    val reconnectDone by ServerManager.reconnectDoneFlow.collectAsState()

    var hint by remember { mutableStateOf<String?>(null) }
    var profilesState by remember { mutableStateOf<ProfileStore.State?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var showGamePicker by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var pendingOverlayAction by remember { mutableStateOf<String?>(null) } // "game:<id>"

    fun profileStore() = ProfileStore(ProfileStore.defaultFile(context.filesDir))
    fun reloadProfiles() {
        profilesState = profileStore().ensureDefault()
    }

    LaunchedEffect(Unit) {
        reloadProfiles()
    }

    // ── Quyền: kiểm tra lại mỗi lần quay về app (đi cấp quyền ở Cài đặt về) ──
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val overlayGranted = remember(resumeTick) { Settings.canDrawOverlays(context) }
    val notifGranted = remember(resumeTick) { hasNotificationPermission(context) }
    val pinGranted = remember(resumeTick) { isIgnoringBatteryOptimizations(context) }
    val shizukuInstalled = remember(resumeTick) { ShizukuBridge.isAppInstalled(context) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { resumeTick++ }

    // ── Thiết bị cắm ngoài: tự phát hiện khi cắm/rút ──
    var devices by remember { mutableStateOf(listOf<UsbDevice>()) }
    fun refreshDevices() {
        devices = listExternalInputDevices()
    }
    DisposableEffect(Unit) {
        refreshDevices()
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { refreshDevices() }
            override fun onInputDeviceRemoved(deviceId: Int) { refreshDevices() }
            override fun onInputDeviceChanged(deviceId: Int) { refreshDevices() }
        }
        im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { im.unregisterInputDeviceListener(listener) }
    }

    // ── Trạng thái kích hoạt theo phương thức đã chọn ──
    var rootReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(method) {
        if (method == ActivationMethod.ROOT) {
            rootReady = rootExecutor.isReady()
        }
    }
    val activationReady = when (method) {
        ActivationMethod.SHIZUKU -> shizukuState is ShizukuBridge.State.Ready
        ActivationMethod.ROOT -> rootReady == true
        ActivationMethod.ADB -> connState == ConnectionState.CONNECTED
    }
    fun executorForMethod(): ShellExecutor = when (method) {
        ActivationMethod.SHIZUKU -> ShizukuShellExecutor()
        ActivationMethod.ROOT -> rootExecutor
        ActivationMethod.ADB -> AdbShellExecutor(adbManager)
    }

    val serverRunning = serverInfo.status == ServerStatus.RUNNING
    val serverStarting = serverInfo.status == ServerStatus.STARTING

    // ── Các hành động ──
    fun startServer() {
        hint = null
        ServerManager.startServer(context, executorForMethod())
    }

    // ── Tự động khởi động Server khi mở màn chính (không còn nút bấm tay) ──
    // Chỉ start 1 lần mỗi lần vào màn (remember reset khi rời tab): đợi kiểm
    // tra xong Server cũ + phương thức sẵn sàng + Server đang dừng/lỗi + người
    // dùng không vừa bấm Dừng Server. RUNNING/STARTING thì không start lại
    // (startServer cũng chặn thêm 1 lớp). Lỗi thì KHÔNG tự thử lại vòng lặp —
    // người dùng bấm "Thử lại" ở dòng trạng thái.
    var autoStartTried by remember { mutableStateOf(false) }
    LaunchedEffect(activationReady, reconnectDone, serverInfo.status) {
        if (!autoStartTried && reconnectDone && activationReady &&
            (serverInfo.status == ServerStatus.STOPPED || serverInfo.status == ServerStatus.ERROR) &&
            !ServerManager.userStoppedServer
        ) {
            autoStartTried = true
            startServer()
        }
    }

    fun doLaunchGame(profile: Profile) {
        val pkg = profile.packageName ?: return
        scope.launch(Dispatchers.IO) {
            val store = profileStore()
            val state = store.load()
            store.save(state.profiles, profile.id)
            sendProfileToServer(context, profile) // gửi DISPLAY + PROFILE
            val c = ServerManager.getSavedConnection(context)
            if (c != null) {
                try {
                    // Build-11: kiểm tra phản hồi — trước đây bỏ qua ERR
                    // (vd "ERR no_display" khi DISPLAY chưa tới Server) nên
                    // MAP lặng lẽ không bật mà người dùng không biết vì sao
                    // con trỏ đứng im / phím không ăn.
                    val resp = InputClient.setMap(c.first, c.second, true)
                    if (!resp.startsWith("OK")) {
                        withContext(Dispatchers.Main) {
                            hint = "Mở game được nhưng chưa bật được keymap ($resp) — " +
                                "bấm nút K trong game rồi chọn \"Bật keymap\"."
                        }
                    }
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                OverlayService.startPlay(context)
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    hint = "Không mở được game (chưa cài $pkg?)."
                }
                reloadProfiles()
            }
        }
    }

    // Xin quyền lớp phủ xong thì làm tiếp việc đang chờ (chỉ còn mở game).
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val pending = pendingOverlayAction
        pendingOverlayAction = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (!OverlayService.canDrawOverlays(context)) {
            hint = "Chưa cấp quyền \"Hiển thị trên các ứng dụng khác\" nên chưa bật được lớp phủ."
            return@rememberLauncherForActivityResult
        }
        hint = null
        if (pending.startsWith("game:")) {
            val id = pending.removePrefix("game:")
            profilesState?.profiles?.firstOrNull { it.id == id }?.let { doLaunchGame(it) }
        }
    }

    fun requestOverlayPermission(pending: String) {
        pendingOverlayAction = pending
        hint = "Cần cấp quyền \"Hiển thị trên các ứng dụng khác\" cho KeyTouch."
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        overlayPermissionLauncher.launch(intent)
    }

    fun launchGame(profile: Profile) {
        if (!serverRunning) {
            hint = "Server chưa sẵn sàng — đợi chút rồi thử lại."
            return
        }
        if (profile.packageName == null) {
            hint = "Profile này chưa gán game."
            return
        }
        if (!OverlayService.canDrawOverlays(context)) {
            requestOverlayPermission("game:${profile.id}")
            return
        }
        doLaunchGame(profile)
    }

    // ── Một dòng trạng thái duy nhất (không PID/UID/cổng — chỉ ghi log) ──
    // Lỗi thì hiện 1 dòng ngắn + nút "Thử lại"; người dùng vừa bấm Dừng Server
    // thì hiện nút "Khởi động lại" (đó là 2 trường hợp duy nhất có nút ở đây).
    val statusText: String
    val statusColor: Color
    val statusActionLabel: String?
    when {
        overlayRunning -> {
            statusText = "🟢 Đang chạy"
            statusColor = Color(0xFF2E7D32)
            statusActionLabel = null
        }
        serverStarting -> {
            statusText = "🟡 Đang chuẩn bị…"
            statusColor = Color(0xFFFF9800)
            statusActionLabel = null
        }
        serverRunning -> {
            statusText = "🟢 Sẵn sàng"
            statusColor = Color(0xFF2E7D32)
            statusActionLabel = null
        }
        serverInfo.status == ServerStatus.ERROR -> {
            statusText = "🔴 " + (serverInfo.errorMessage ?: "Không rõ nguyên nhân.")
            statusColor = Color(0xFFE53935)
            statusActionLabel = "Thử lại"
        }
        ServerManager.userStoppedServer && activationReady -> {
            // Đã dừng tay + phương thức vẫn sẵn sàng: chỉ còn nút khởi động lại.
            // (Chưa kích hoạt thì nhánh dưới thắng — checklist mới là việc cần làm.)
            statusText = "⚪ Server đã dừng"
            statusColor = Color(0xFF757575)
            statusActionLabel = "Khởi động lại"
        }
        !activationReady -> {
            statusText = "⚪ Chưa kích hoạt"
            statusColor = Color(0xFF757575)
            statusActionLabel = null
        }
        else -> {
            // STOPPED + đã kích hoạt: đang đợi kiểm tra Server cũ xong hoặc
            // auto-start sắp chạy.
            statusText = "🟡 Đang chuẩn bị…"
            statusColor = Color(0xFFFF9800)
            statusActionLabel = null
        }
    }

    // Game nào đã cài trên máy (để bật/tắt nút KHỞI CHẠY).
    val installedPkgs = remember(profilesState) {
        profilesState?.profiles?.mapNotNull { it.packageName }
            ?.filter { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?.toSet() ?: emptySet()
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "KeyTouch",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Keymap cho game Android — phím/chuột chơi game cảm ứng",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Viên quyền: chỉ hiện quyền CHƯA cấp (đủ quyền thì ẩn cả hàng).
        // Viên SHIZUKU chỉ liên quan khi dùng phương thức Shizuku.
        val shizukuMissing = method == ActivationMethod.SHIZUKU &&
            shizukuState !is ShizukuBridge.State.Ready
        if (!overlayGranted || !notifGranted || !pinGranted || shizukuMissing) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!overlayGranted) {
                    PermissionChip("LỚP PHỦ", false) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
                if (!notifGranted) {
                    PermissionChip("THÔNG BÁO", false) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    }
                }
                if (!pinGranted) {
                    PermissionChip("PIN", false) {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
                if (shizukuMissing) {
                    PermissionChip("SHIZUKU", false) {
                        ShizukuBridge.openShizukuApp(context)
                    }
                }
            }
        }

        // Thiết bị cắm ngoài: gộp theo thiết bị vật lý (1 dongle liệt kê
        // nhiều node Receiver/Keyboard/Mouse/System Control thì gộp thành
        // 1 cái tên), chỉ hiện 1 dòng gọn khi ĐÃ cắm chuột/phím.
        if (devices.isNotEmpty()) {
            val grouped = remember(devices) {
                DeviceGrouping.format(
                    DeviceGrouping.group(
                        devices.map { DeviceGrouping.Node(it.name, it.isMouse, it.isKeyboard) },
                    ),
                )
            }
            Text(
                text = grouped,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Một dòng trạng thái duy nhất: chỉ 1 Text trong 1 Row (thẻ cũ có
        // 2 Text label + detail; thẻ mới chỉ còn 1 chuỗi nên không còn chỗ
        // cho 2 đoạn chữ đè nhau).
        Card(
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (serverStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = statusColor,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (statusActionLabel != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { startServer() }) { Text(statusActionLabel) }
                }
            }
        }

        // Chi tiết lỗi Server khi khởi động thất bại: giai đoạn bị kẹt +
        // 25 dòng cuối của log Server + nút sao chép log.
        // serverInfo là delegated property nên không smart-cast được — hứng ra biến cục bộ.
        val logTail = serverInfo.logTail
        if (serverInfo.status == ServerStatus.ERROR && logTail != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Giai đoạn lỗi: ${serverInfo.stage?.labelVi ?: "Không rõ"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(
                                ClipData.newPlainText("KeyTouch server log", logTail),
                            )
                            Toast.makeText(context, "Đã chép log Server", Toast.LENGTH_SHORT).show()
                        }) { Text("Sao chép log") }
                    }
                    Text(
                        text = logTail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }

        hint?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        // Checklist kích hoạt: tự ẩn khi không còn bước nào chưa xong.
        ActivationChecklist(
            method = method,
            shizukuState = shizukuState,
            shizukuInstalled = shizukuInstalled,
            rootReady = rootReady,
            adbConnected = connState == ConnectionState.CONNECTED,
            onOpenShizuku = { ShizukuBridge.openShizukuApp(context) },
            onRequestShizukuPermission = {
                scope.launch { ShizukuBridge.requestPermission() }
            },
            onReprobeRoot = {
                scope.launch {
                    rootReady = null
                    rootReady = rootExecutor.reprobe()
                }
            },
            onOpenAdbSetup = onOpenAdbSetup,
        )

        // Danh sách game.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Game",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                showGamePicker = true
                if (launchableApps == null) {
                    scope.launch(Dispatchers.IO) {
                        launchableApps = queryLaunchableApps(context)
                    }
                }
            }) { Text("+ Thêm game") }
        }

        val profiles = profilesState?.profiles ?: emptyList()
        val activeId = profilesState?.activeId
        if (profiles.isEmpty()) {
            Text("Chưa có game nào.", style = MaterialTheme.typography.bodyMedium)
        }
        profiles.forEach { p ->
            val installed = p.packageName != null && installedPkgs.contains(p.packageName)
            // Thẻ game tối giản (build-9): chỉ LOGO game (icon lấy từ
            // PackageManager) + tên game + nút "Vô". Bấm thẻ = vào game;
            // NHẤN GIỮ thẻ = xóa game (hộp xác nhận). Không còn tên
            // package / số phần tử / nút XÓA trên thẻ.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (installed && serverRunning) launchGame(p) },
                        onLongClick = { deleteTarget = p },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GameIcon(packageName = p.packageName, size = 48.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = p.name + if (p.id == activeId) "  ● đang dùng" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!installed) {
                            Text(
                                text = if (p.packageName == null) "Chưa gán game" else "(chưa cài)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = { launchGame(p) },
                        enabled = installed && serverRunning,
                    ) { Text("Vô") }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Dialog xác nhận xóa game/profile.
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Xóa game?") },
            text = { Text("Xóa \"${p.name}\" khỏi danh sách? Profile của game cũng bị xóa.") },
            confirmButton = {
                Button(onClick = {
                    val current = profilesState
                    if (current != null) {
                        if (current.profiles.size <= 1) {
                            hint = "Không xóa được profile cuối cùng."
                        } else {
                            val remaining = current.profiles.filter { it.id != p.id }
                            val newActive = if (current.activeId == p.id) {
                                remaining.firstOrNull()?.id
                            } else {
                                current.activeId
                            }
                            profileStore().save(remaining, newActive)
                            reloadProfiles()
                        }
                    }
                    deleteTarget = null
                }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Hủy") }
            },
        )
    }

    // Dialog chọn game để thêm.
    if (showGamePicker) {
        AlertDialog(
            onDismissRequest = { showGamePicker = false },
            title = { Text("Thêm game") },
            text = {
                val apps = launchableApps
                if (apps == null) {
                    Text("Đang liệt kê app đã cài…")
                } else if (apps.isEmpty()) {
                    Text("Không liệt kê được app (cần quyền xem app đã cài).")
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(apps) { app ->
                            TextButton(
                                onClick = {
                                    val current = profilesState
                                    if (current != null) {
                                        if (current.profiles.any { it.packageName == app.packageName }) {
                                            hint = "Game này đã có trong danh sách."
                                        } else {
                                            val created = ProfileCodec.defaultFreeFire().copy(
                                                id = UUID.randomUUID().toString(),
                                                name = app.label,
                                                packageName = app.packageName,
                                            )
                                            profileStore().save(
                                                current.profiles + created,
                                                current.activeId,
                                            )
                                            reloadProfiles()
                                        }
                                    }
                                    showGamePicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(app.label, fontWeight = FontWeight.Bold)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGamePicker = false }) { Text("Đóng") }
            },
        )
    }
}

/** Viên trạng thái quyền (kiểu K7XD): xanh = đã cấp, cam = chưa. Bấm để cấp/mở cài đặt. */
@Composable
private fun PermissionChip(label: String, granted: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (granted) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = (if (granted) "✓ " else "") + label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private data class UsbDevice(val name: String, val isMouse: Boolean, val isKeyboard: Boolean)

/** Icon game lấy từ PackageManager (build-9: logo trên thẻ game tối giản).
 *  Vẽ Drawable thủ công sang Bitmap (không dùng core-ktx) rồi đổi sang
 *  ImageBitmap; không lấy được icon (game chưa gán/chưa cài) thì hiện 🎮. */
@Composable
private fun GameIcon(packageName: String?, size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        packageName?.let {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(it)
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("🎮", fontSize = 24.sp)
        }
    }
}

/** Liệt kê chuột/bàn phím cắm ngoài (USB OTG / Bluetooth). Bỏ thiết bị ảo và thiết bị trong máy. */
private fun listExternalInputDevices(): List<UsbDevice> {
    val out = ArrayList<UsbDevice>()
    for (id in InputDevice.getDeviceIds()) {
        try {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual || !d.isExternal) continue
            val sources = d.sources
            val isKb = sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
            val isMouse = sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
            if (!isKb && !isMouse) continue
            out.add(UsbDevice(d.name, isMouse, isKb))
        } catch (_: Exception) {
        }
    }
    return out.sortedBy { it.name }
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

// AppInfo + queryLaunchableApps dùng chung bản ở ProfileScreen.kt (cùng package ui).

/**
 * Checklist kích hoạt (kiểu GG Mouse Pro): CHỈ hiện các bước chưa xong,
 * xong hết thì tự ẩn cả thẻ. Không còn bước "Khởi động Server" — Server
 * tự chạy ngầm, trạng thái của nó nằm ở dòng trạng thái trên màn chính.
 * Chỉ nút của bước chưa xong ĐẦU TIÊN được bấm.
 */
@Composable
private fun ActivationChecklist(
    method: ActivationMethod,
    shizukuState: ShizukuBridge.State,
    shizukuInstalled: Boolean,
    rootReady: Boolean?,
    adbConnected: Boolean,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onReprobeRoot: () -> Unit,
    onOpenAdbSetup: () -> Unit,
) {
    data class Step(val text: String, val done: Boolean, val actionLabel: String?, val action: (() -> Unit)?)

    val steps: List<Step> = when (method) {
        ActivationMethod.SHIZUKU -> {
            val binderOk = shizukuState !is ShizukuBridge.State.NotRunning
            val permOk = shizukuState is ShizukuBridge.State.Ready
            val permDenied = (shizukuState as? ShizukuBridge.State.NeedsPermission)?.permanentlyDenied == true
            listOf(
                Step(
                    "Cài + khởi động app Shizuku",
                    binderOk,
                    if (shizukuInstalled) "Mở Shizuku" else "Cài Shizuku",
                    onOpenShizuku,
                ),
                Step(
                    "Cấp quyền cho KeyTouch trong Shizuku",
                    permOk,
                    if (permDenied) "Mở Shizuku để cấp tay" else "Cấp quyền",
                    if (permDenied) onOpenShizuku else onRequestShizukuPermission,
                ),
            )
        }
        ActivationMethod.ROOT -> listOf(
            Step("Cấp quyền root cho KeyTouch", rootReady == true, "Kiểm tra lại", onReprobeRoot),
        )
        ActivationMethod.ADB -> listOf(
            Step("Ghép cặp + kết nối ADB không dây", adbConnected, "Mở cài đặt ADB", onOpenAdbSetup),
        )
    }
    // Chỉ hiện bước chưa xong; xong hết thì ẩn cả thẻ.
    val pending = steps.filter { !it.done }
    if (pending.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Kích hoạt — làm từng bước",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            pending.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                Color(0xFF9E9E9E),
                                RoundedCornerShape(13.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${i + 1}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (i == 0 && step.actionLabel != null) {
                        OutlinedButton(
                            onClick = { step.action?.invoke() },
                        ) { Text(step.actionLabel, fontSize = 13.sp) }
                    }
                }
            }
            if (method == ActivationMethod.SHIZUKU && shizukuState is ShizukuBridge.State.NotRunning) {
                Text(
                    "Mở app Shizuku, bấm Start (qua gỡ lỗi không dây hoặc root) rồi quay lại đây. " +
                        "Sau mỗi lần khởi động lại máy phải Start lại Shizuku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

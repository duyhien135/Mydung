package com.example.mydung.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Tab Game — màn hình chính mới (theo mẫu K7XD + GG Mouse Pro):
 *
 * - Viên trạng thái quyền (LỚP PHỦ / THÔNG BÁO / PIN / SHIZUKU): xanh = đã cấp,
 *   cam = chưa; bấm vào để cấp/mở cài đặt.
 * - Viên trạng thái thiết bị: tên chuột/bàn phím đã cắm (tự phát hiện khi
 *   cắm/rút OTG).
 * - MỘT dòng trạng thái duy nhất + MỘT nút lớn (theo ý Claude):
 *   Chưa kích hoạt -> Đang kích hoạt -> Đã kết nối -> Đang chạy.
 * - Checklist kích hoạt từng bước có dấu ✓ (kiểu GG).
 * - Danh sách game: KHỞI CHẠY (mở game + bật keymap) / XÓA / + Thêm game.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    method: ActivationMethod,
    onOpenInputTest: () -> Unit,
    onOpenTouchTest: () -> Unit,
    onOpenAdbSetup: () -> Unit,
    onGoToProfiles: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adbManager = remember { AdbConnectionManager.getInstance(context) }
    val rootExecutor = remember { RootShellExecutor() }

    val shizukuState by ShizukuBridge.state.collectAsState()
    val serverInfo by ServerManager.serverInfoFlow.collectAsState()
    val overlayRunning by OverlayService.runningFlow.collectAsState()
    val connState by PairingState.stateFlow.collectAsState()

    var hint by remember { mutableStateOf<String?>(null) }
    var profilesState by remember { mutableStateOf<ProfileStore.State?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var showGamePicker by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var pendingOverlayAction by remember { mutableStateOf<String?>(null) } // "play" | "edit" | "game:<id>"

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
    val methodLabel = when (method) {
        ActivationMethod.SHIZUKU -> "Shizuku"
        ActivationMethod.ROOT -> "Root"
        ActivationMethod.ADB -> "ADB không dây"
    }

    val serverRunning = serverInfo.status == ServerStatus.RUNNING
    val serverStarting = serverInfo.status == ServerStatus.STARTING

    // ── Các hành động (thứ tự khai báo quan trọng: hàm gọi launcher phải
    // đứng sau launcher, launcher gọi doLaunchGame nên doLaunchGame đứng trước) ──
    fun startServer() {
        hint = null
        ServerManager.startServer(context, executorForMethod())
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
                    InputClient.setMap(c.first, c.second, true)
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

    // Xin quyền lớp phủ xong thì làm tiếp việc đang chờ.
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
        when {
            pending == "play" -> OverlayService.startPlay(context)
            pending == "edit" -> {
                profilesState?.activeId?.let { OverlayService.startEdit(context, it) }
            }
            pending.startsWith("game:") -> {
                val id = pending.removePrefix("game:")
                profilesState?.profiles?.firstOrNull { it.id == id }?.let { doLaunchGame(it) }
            }
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

    fun startPlayOverlay() {
        if (!OverlayService.canDrawOverlays(context)) {
            requestOverlayPermission("play")
            return
        }
        OverlayService.startPlay(context)
    }

    fun openEditor() {
        if (!serverRunning) {
            hint = "Khởi động Server trước đã."
            return
        }
        val activeId = profilesState?.activeId
        if (activeId == null) {
            hint = "Chưa có profile nào."
            return
        }
        if (!OverlayService.canDrawOverlays(context)) {
            requestOverlayPermission("edit")
            return
        }
        OverlayService.startEdit(context, activeId)
    }

    fun launchGame(profile: Profile) {
        if (!serverRunning) {
            hint = "Khởi động Server trước đã."
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

    fun stopKeymap() {
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(context)
            if (c != null) {
                try {
                    InputClient.setMap(c.first, c.second, false)
                } catch (_: Exception) {
                }
            }
        }
        OverlayService.stop(context)
    }

    // ── Dòng trạng thái + nút lớn ──
    val statusLabel: String
    val statusColor: Color
    val statusDetail: String
    when {
        overlayRunning -> {
            statusLabel = "🟢 Đang chạy"
            statusColor = Color(0xFF2E7D32)
            statusDetail = "Keymap đang bật trong game — nút nổi K đang hiện. Bấm nút K để bật/tắt, giữ để chỉnh nút."
        }
        serverStarting -> {
            statusLabel = "🟡 Đang khởi động Server…"
            statusColor = Color(0xFFFF9800)
            statusDetail = "Đang đợi Server phản hồi (tối đa 30s)."
        }
        serverRunning -> {
            statusLabel = "🟢 Đã kết nối"
            statusColor = Color(0xFF2E7D32)
            statusDetail = "Server đang chạy (PID ${serverInfo.pid}, UID ${serverInfo.uid}, cổng ${serverInfo.port}). " +
                "Bấm Bắt đầu chơi để hiện nút nổi, hoặc KHỞI CHẠY game bên dưới."
        }
        serverInfo.status == ServerStatus.ERROR -> {
            statusLabel = "🔴 Server lỗi"
            statusColor = Color(0xFFE53935)
            statusDetail = serverInfo.errorMessage ?: "Không rõ nguyên nhân."
        }
        activationReady -> {
            statusLabel = "🟡 Đã kích hoạt — Server chưa chạy"
            statusColor = Color(0xFFFF9800)
            statusDetail = "$methodLabel đã sẵn sàng. Chỉ còn khởi động Server."
        }
        else -> {
            statusLabel = "⚪ Chưa kích hoạt"
            statusColor = Color(0xFF757575)
            statusDetail = "Làm theo checklist bên dưới để kích hoạt bằng $methodLabel."
        }
    }

    val bigLabel: String
    val bigEnabled: Boolean
    val bigAction: () -> Unit
    when {
        serverStarting -> {
            bigLabel = "Đang khởi động…"
            bigEnabled = false
            bigAction = {}
        }
        overlayRunning -> {
            bigLabel = "⏹ Dừng keymap"
            bigEnabled = true
            bigAction = { stopKeymap() }
        }
        serverRunning -> {
            bigLabel = "▶ Bắt đầu chơi"
            bigEnabled = true
            bigAction = { startPlayOverlay() }
        }
        activationReady -> {
            bigLabel = "🚀 Khởi động Server"
            bigEnabled = true
            bigAction = { startServer() }
        }
        method == ActivationMethod.SHIZUKU -> {
            when (val s = shizukuState) {
                is ShizukuBridge.State.NeedsPermission -> {
                    if (s.permanentlyDenied) {
                        bigLabel = "Mở app Shizuku để cấp quyền"
                        bigEnabled = true
                        bigAction = { ShizukuBridge.openShizukuApp(context) }
                    } else {
                        bigLabel = "Cấp quyền Shizuku"
                        bigEnabled = true
                        bigAction = { scope.launch { ShizukuBridge.requestPermission() } }
                    }
                }
                is ShizukuBridge.State.Unsupported -> {
                    bigLabel = "Shizuku quá cũ — cập nhật app Shizuku"
                    bigEnabled = false
                    bigAction = {}
                }
                else -> {
                    bigLabel = if (shizukuInstalled) "Mở app Shizuku để khởi động" else "Cài app Shizuku"
                    bigEnabled = true
                    bigAction = { ShizukuBridge.openShizukuApp(context) }
                }
            }
        }
        method == ActivationMethod.ROOT -> {
            bigLabel = if (rootReady == null) "Đang kiểm tra root…" else "Kiểm tra lại quyền root"
            bigEnabled = rootReady != null
            bigAction = {
                scope.launch {
                    rootReady = null
                    rootReady = rootExecutor.reprobe()
                }
            }
        }
        else -> {
            bigLabel = "Kết nối ADB không dây"
            bigEnabled = true
            bigAction = onOpenAdbSetup
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

        // Viên trạng thái quyền (kiểu K7XD).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PermissionChip("LỚP PHỦ", overlayGranted) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            PermissionChip("THÔNG BÁO", notifGranted) {
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
            PermissionChip("PIN", pinGranted) {
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
            PermissionChip("SHIZUKU", shizukuState is ShizukuBridge.State.Ready) {
                ShizukuBridge.openShizukuApp(context)
            }
        }

        // Ghi chú riêng cho máy Xiaomi/HyperOS (Redmi): 2 chỗ hay quên.
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "⚠ Lưu ý cho máy Xiaomi/HyperOS",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "• Trong Tùy chọn nhà phát triển, bật thêm \"Gỡ lỗi USB (cài đặt bảo mật)\" " +
                            "— thiếu thì tiêm chạm có thể âm thầm thất bại.\n" +
                            "• Bật Tự khởi động cho KeyTouch trong Cài đặt → Ứng dụng → KeyTouch " +
                            "(HyperOS giết app nền rất mạnh).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Viên trạng thái thiết bị.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⌨️🖱️ Thiết bị đã cắm",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { refreshDevices() }) { Text("Làm mới") }
                }
                if (devices.isEmpty()) {
                    Text(
                        "Chưa thấy chuột/bàn phím nào. Cắm qua OTG rồi đợi 1–2 giây.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    devices.forEach { d ->
                        Text(
                            "• ${d.name} — " + when {
                                d.isMouse && d.isKeyboard -> "chuột + bàn phím"
                                d.isMouse -> "chuột"
                                else -> "bàn phím"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // Dòng trạng thái duy nhất + nút lớn.
        Card(
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Text(text = statusDetail, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = bigAction,
            enabled = bigEnabled,
            colors = if (overlayRunning) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            if (serverStarting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(bigLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

        // Checklist kích hoạt (chỉ hiện khi chưa xong).
        if (!activationReady || !serverRunning) {
            ActivationChecklist(
                method = method,
                shizukuState = shizukuState,
                shizukuInstalled = shizukuInstalled,
                rootReady = rootReady,
                adbConnected = connState == ConnectionState.CONNECTED,
                serverRunning = serverRunning,
                serverStarting = serverStarting,
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
                onStartServer = { startServer() },
            )
        }

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
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = p.name + if (p.id == activeId) "  ● đang dùng" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when {
                                p.packageName == null -> "Chưa gán game"
                                !installed -> "${p.packageName} (chưa cài)"
                                else -> p.packageName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${p.elements.size} phần tử",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { deleteTarget = p }) {
                        Text("XÓA", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = { launchGame(p) },
                        enabled = installed && serverRunning,
                    ) { Text("KHỞI CHẠY") }
                }
            }
        }
        if (!serverRunning) {
            Text(
                "Cần khởi động Server trước mới KHỞI CHẠY được game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onGoToProfiles) {
            Text("Quản lý profile, độ nhạy, phím bật/tắt →")
        }

        // Chỉnh nút + chế độ thử.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { openEditor() },
                enabled = serverRunning,
                modifier = Modifier.weight(1f),
            ) { Text("✏️ Chỉnh nút", fontSize = 13.sp) }
            Button(
                onClick = onOpenInputTest,
                enabled = serverRunning,
                modifier = Modifier.weight(1f),
            ) { Text("⌨️ Thử phím", fontSize = 13.sp) }
            Button(
                onClick = onOpenTouchTest,
                enabled = serverRunning,
                modifier = Modifier.weight(1f),
            ) { Text("👆 Thử cảm ứng", fontSize = 13.sp) }
        }
        Text(
            "Trong game: bấm nút nổi K để bật/tắt keymap, giữ nút K để mở trình sửa, " +
                "kéo nút K để di chuyển. Phím F8 trên bàn phím USB bật/tắt keymap, " +
                "F9 chuyển chế độ con trỏ (bấm giao diện game) ↔ nhìn tự do (kéo camera). " +
                "Đổi phím trong tab Cấu hình.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
 * Checklist kích hoạt từng bước có dấu ✓ (kiểu GG Mouse Pro). Chỉ nút của
 * bước chưa xong ĐẦU TIÊN được bấm — các bước sau chờ bước trước xong.
 */
@Composable
private fun ActivationChecklist(
    method: ActivationMethod,
    shizukuState: ShizukuBridge.State,
    shizukuInstalled: Boolean,
    rootReady: Boolean?,
    adbConnected: Boolean,
    serverRunning: Boolean,
    serverStarting: Boolean,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onReprobeRoot: () -> Unit,
    onOpenAdbSetup: () -> Unit,
    onStartServer: () -> Unit,
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
                Step("Khởi động Server", serverRunning, "Khởi động Server", onStartServer),
            )
        }
        ActivationMethod.ROOT -> listOf(
            Step("Cấp quyền root cho KeyTouch", rootReady == true, "Kiểm tra lại", onReprobeRoot),
            Step("Khởi động Server", serverRunning, "Khởi động Server", onStartServer),
        )
        ActivationMethod.ADB -> listOf(
            Step("Ghép cặp + kết nối ADB không dây", adbConnected, "Mở cài đặt ADB", onOpenAdbSetup),
            Step("Khởi động Server", serverRunning, "Khởi động Server", onStartServer),
        )
    }
    val firstPending = steps.indexOfFirst { !it.done }

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
            steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                if (step.done) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                                RoundedCornerShape(13.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (step.done) "✓" else "${i + 1}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (!step.done && i == firstPending && step.actionLabel != null) {
                        OutlinedButton(
                            onClick = { step.action?.invoke() },
                            enabled = !serverStarting,
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

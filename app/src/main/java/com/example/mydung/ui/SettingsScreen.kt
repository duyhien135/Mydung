package com.example.mydung.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydung.ActivationMethod
import com.example.mydung.ActivationPrefs
import com.example.mydung.AdbConnectionManager
import com.example.mydung.OverlayService
import com.example.mydung.PairingState
import com.example.mydung.server.AdbShellExecutor
import com.example.mydung.server.Config
import com.example.mydung.server.InputClient
import com.example.mydung.server.LinuxKey
import com.example.mydung.server.ProfileStore
import com.example.mydung.server.RootShellExecutor
import com.example.mydung.server.ServerManager
import com.example.mydung.server.ServerStatus
import com.example.mydung.server.ShellExecutor
import com.example.mydung.server.ShizukuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tab Cài đặt: chọn phương thức kích hoạt, cách tiêm cảm ứng, thao tác
 * Server (Ping / Dừng / Xem log), kiểm tra phím/cảm ứng, nhật ký gỡ lỗi,
 * thông tin app.
 */
@Composable
fun SettingsScreen(
    method: ActivationMethod,
    onMethodChange: (ActivationMethod) -> Unit,
    onOpenAdbSetup: () -> Unit,
    onOpenInputTest: () -> Unit,
    onOpenTouchTest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adbManager = remember { AdbConnectionManager.getInstance(context) }
    val rootExecutor = remember { RootShellExecutor() }

    val serverInfo by ServerManager.serverInfoFlow.collectAsState()
    val serverRunning = serverInfo.status == ServerStatus.RUNNING

    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var injector by remember { mutableStateOf("reflect") }
    var injectorNote by remember { mutableStateOf<String?>(null) }

    fun executorFor(m: ActivationMethod): ShellExecutor = when (m) {
        ActivationMethod.SHIZUKU -> ShizukuShellExecutor()
        ActivationMethod.ROOT -> rootExecutor
        ActivationMethod.ADB -> AdbShellExecutor(adbManager)
    }

    fun refreshInjector() {
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(context) ?: return@launch
            try {
                val resp = InputClient.mapStatus(c.first, c.second)
                injector = Regex("injector=(\\w+)").find(resp)?.groupValues?.get(1) ?: "reflect"
            } catch (_: Exception) {
            }
        }
    }

    fun setInjector(mode: String) {
        scope.launch(Dispatchers.IO) {
            val c = ServerManager.getSavedConnection(context)
            if (c == null) {
                injectorNote = "Chưa có Server đang chạy."
                return@launch
            }
            try {
                val resp = InputClient.setInjector(c.first, c.second, mode)
                injectorNote = "INJECT $mode: $resp"
                refreshInjector()
            } catch (e: Exception) {
                injectorNote = "Lỗi đổi injector: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshInjector()
        PairingState.logFlow.collectLatest { msg ->
            logMessages = listOf(msg) + logMessages.take(99)
        }
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
            "Cài đặt",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        // Phương thức kích hoạt.
        Text(
            "Phương thức kích hoạt",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                ActivationMethodRow(
                    method = ActivationMethod.SHIZUKU,
                    selected = method,
                    title = "Shizuku (khuyên dùng)",
                    desc = "Qua app Shizuku, không cần ghép nối ADB tay. Dùng cho máy không root (Redmi).",
                    onSelect = {
                        ActivationPrefs.set(context, it)
                        // Đổi phương thức = ngữ cảnh mới: lần dừng Server cũ
                        // không còn áp dụng, màn chính được auto-start lại.
                        ServerManager.clearUserStopped()
                        onMethodChange(it)
                    },
                )
                ActivationMethodRow(
                    method = ActivationMethod.ROOT,
                    selected = method,
                    title = "Root",
                    desc = "Qua su trên máy đã root. Ổn định nhất, không cần kích hoạt lại (Y700 KernelSU).",
                    onSelect = {
                        ActivationPrefs.set(context, it)
                        ServerManager.clearUserStopped()
                        onMethodChange(it)
                    },
                )
                ActivationMethodRow(
                    method = ActivationMethod.ADB,
                    selected = method,
                    title = "ADB không dây",
                    desc = "Ghép cặp gỡ lỗi không dây rồi tự nối (đường cũ). Cần Wi-Fi + ghép cặp 6 số.",
                    onSelect = {
                        ActivationPrefs.set(context, it)
                        ServerManager.clearUserStopped()
                        onMethodChange(it)
                    },
                )
                if (method == ActivationMethod.ADB) {
                    OutlinedButton(
                        onClick = onOpenAdbSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    ) { Text("Mở cài đặt ADB (ghép cặp / kết nối)") }
                }
            }
        }

        // Ghi chú riêng cho máy Xiaomi/HyperOS (Redmi): 2 chỗ hay quên.
        // (Trước nằm ở màn chính, dời sang đây cho màn chính gọn.)
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
                            "• Bật Tự khởi động cho H trong Cài đặt → Ứng dụng → H " +
                            "(HyperOS giết app nền rất mạnh).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Cách tiêm cảm ứng.
        Text(
            "Cách tiêm cảm ứng (đang dùng: $injector)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Phản chiếu là cách mặc định. Nếu test tiêm không ăn (vd ZUI chặn API ẩn) " +
                "thì thử Evdev thô: ghi event thẳng vào node cảm ứng.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { setInjector("reflect") },
                enabled = serverRunning && injector != "reflect",
                modifier = Modifier.weight(1f),
            ) { Text("Phản chiếu") }
            Button(
                onClick = { setInjector("evdev") },
                enabled = serverRunning && injector != "evdev",
                modifier = Modifier.weight(1f),
            ) { Text("Evdev thô") }
        }
        injectorNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // Thao tác Server.
        Text(
            "Server đặc quyền",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { ServerManager.ping(context) },
                enabled = serverRunning,
                modifier = Modifier.weight(1f),
            ) { Text("📡 Ping") }
            Button(
                onClick = { ServerManager.stopServer(context) },
                enabled = serverRunning,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f),
            ) { Text("⏹ Dừng Server") }
            OutlinedButton(
                onClick = {
                    ServerManager.readServerLog(executorFor(method)) { logContent ->
                        PairingState.log("📄 [Log Server]:\n$logContent")
                    }
                },
                modifier = Modifier.weight(1.3f),
            ) { Text("📄 Xem log Server", fontSize = 13.sp) }
        }

        // Lớp phủ (build-9): hiện lại khi đã ẩn mà quên phím tắt (mặc định
        // F1 từ build-11). Keymap vẫn chạy ngầm khi lớp phủ bị ẩn.
        // Phím hiện tại đọc từ profile đang dùng (đổi trong game: bấm nút
        // K -> ⚙️ Cài đặt -> "Phím ẩn/hiện lớp phủ" ở đầu bảng).
        val overlayKeyName = remember {
            runCatching {
                val store = ProfileStore(ProfileStore.defaultFile(context.filesDir))
                val state = store.ensureDefault()
                val active = state.profiles.firstOrNull { it.id == state.activeId }
                    ?: state.profiles.first()
                LinuxKey.name(active.overlayToggleKey)
            }.getOrDefault(LinuxKey.name(Config.OVERLAY_TOGGLE_KEY))
        }
        Text(
            "Lớp phủ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Phím ẩn/hiện lớp phủ hiện tại: $overlayKeyName " +
                "(đổi trong game: bấm nút K → ⚙️ Cài đặt → \"Phím ẩn/hiện lớp phủ\").",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { OverlayService.showOverlay(context) }) {
                Text("👁 Hiện lại lớp phủ")
            }
        }
        Text(
            "Dùng khi đã ẩn lớp phủ mà quên phím tắt. " +
                "Keymap vẫn chạy ngầm khi lớp phủ bị ẩn.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Kiểm tra phím/cảm ứng (trước nằm ở màn chính, dời sang đây).
        Text(
            "Kiểm tra",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

        // Nhật ký.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Nhật ký gỡ lỗi & Server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("H log", logMessages.joinToString("\n")))
                    Toast.makeText(context, "Đã chép log", Toast.LENGTH_SHORT).show()
                }) { Text("Chép log") }
                TextButton(onClick = { logMessages = emptyList() }) { Text("Xóa log") }
            }
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 340.dp),
        ) {
            val logScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .padding(10.dp)
                    .verticalScroll(logScroll),
            ) {
                if (logMessages.isEmpty()) {
                    Text("(Chưa có nhật ký hoạt động)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    logMessages.forEach { msg ->
                        Text(
                            text = msg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        // Thông tin app + vùng nguy hiểm.
        val versionName = remember {
            try {
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                pi.versionName ?: "?"
            } catch (_: Exception) {
                "?"
            }
        }
        Text(
            "H $versionName (code ${ServerManager.getAppVersionCode(context)}) — " +
                context.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            adbManager.resetPairing()
            PairingState.log("Đã xóa chứng chỉ ADB và cờ ghép cặp từ đầu.")
            Toast.makeText(context, "Đã xóa ghép cặp ADB", Toast.LENGTH_SHORT).show()
        }) {
            Text("Xóa ghép cặp ADB (làm lại từ đầu)", color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ActivationMethodRow(
    method: ActivationMethod,
    selected: ActivationMethod,
    title: String,
    desc: String,
    onSelect: (ActivationMethod) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = method == selected,
            onClick = { onSelect(method) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

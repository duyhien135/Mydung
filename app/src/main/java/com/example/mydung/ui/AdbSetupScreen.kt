package com.example.mydung.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.mydung.AdbConnectionManager
import com.example.mydung.ConnectionState
import com.example.mydung.NotificationHelper
import com.example.mydung.PairingState
import com.example.mydung.getWifiIpAddress
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn cài đặt ADB không dây (đường cũ GĐ1/GĐ2, tách ra khỏi màn chính):
 * ghép cặp 6 số + kết nối TLS qua mDNS. Chỉ dùng khi phương thức kích hoạt
 * là ADB — mặc định dùng Shizuku nên màn này ít khi cần tới.
 *
 * Sau khi kết nối ADB xong, quay lại tab Game bấm Khởi động Server.
 */
@Composable
fun AdbSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adbManager = remember { AdbConnectionManager.getInstance(context) }

    var currentWifiIp by remember { mutableStateOf(getWifiIpAddress(context)) }
    val connState by PairingState.stateFlow.collectAsState()
    val statusText by PairingState.statusTextFlow.collectAsState()

    var outputShell by remember { mutableStateOf<String?>(null) }
    var isOperating by remember { mutableStateOf(false) }
    var pairingCodeInput by remember { mutableStateOf("") }
    var manualConnectPortInput by remember { mutableStateOf("") }

    fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    var hasNotificationPermission by remember { mutableStateOf(checkNotificationPermission()) }
    var areSysNotificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        hasNotificationPermission = checkNotificationPermission()
        areSysNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (hasNotificationPermission && areSysNotificationsEnabled) {
            PairingState.log("✅ Quyền thông báo POST_NOTIFICATIONS: ĐÃ CẤP")
        } else {
            PairingState.log("⚠ Quyền thông báo bị từ chối hoặc đang tắt trong Cài đặt hệ thống")
        }
    }

    fun checkWifi(): Boolean {
        currentWifiIp = getWifiIpAddress(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: run {
            PairingState.stateFlow.value = ConnectionState.ERROR
            PairingState.statusTextFlow.value = "Lỗi: Wi-Fi chưa kết nối"
            PairingState.log("❌ Không có kết nối mạng – hãy bật Wi-Fi")
            return false
        }
        val caps = cm.getNetworkCapabilities(net)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            PairingState.stateFlow.value = ConnectionState.ERROR
            PairingState.statusTextFlow.value = "Lỗi: Cần kết nối Wi-Fi"
            PairingState.log("❌ Thiết bị chưa kết nối vào mạng Wi-Fi")
            return false
        }
        return true
    }

    fun openDevSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PairingState.log("Đã mở Tùy chọn nhà phát triển")
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                PairingState.log("Đã mở Cài đặt chung")
            } catch (e: Exception) {
                PairingState.log("❌ Không thể mở Cài đặt: ${e.message}")
            }
        }
    }

    // ── HÀM KẾT NỐI ADB (GHI LOG ĐẦY ĐỦ TỪNG BƯỚC) ──
    fun executeConnectAction(portOverride: Int? = null) {
        if (isOperating) return
        if (!checkWifi()) return

        isOperating = true
        PairingState.statusTextFlow.value = "Đang bắt đầu kết nối ADB..."

        scope.launch(Dispatchers.IO) {
            try {
                currentWifiIp = getWifiIpAddress(context)
                PairingState.log("── Bắt đầu quy trình kết nối ADB ──")
                PairingState.log("Thông tin thiết bị: IP Wi-Fi = $currentWifiIp, Khóa xác thực = ${adbManager.keyFingerprint}")

                // 1. Xác định cổng kết nối
                var port = portOverride
                    ?: manualConnectPortInput.trim().toIntOrNull()
                    ?: adbManager.lastDiscoveredConnectPort.takeIf { it > 0 }

                var targetHost = adbManager.lastDiscoveredHost.takeIf { it.isNotBlank() } ?: currentWifiIp

                if (port == null || port !in 1..65535) {
                    PairingState.log("[1/3] Chưa có cổng thủ công. Đang quét mDNS dịch vụ '_adb-tls-connect._tcp' (chờ 5s)...")
                    val mdnsRes = adbManager.discoverService(AdbMdns.SERVICE_TYPE_TLS_CONNECT, 5_000)
                    if (mdnsRes.port > 0) {
                        port = mdnsRes.port
                        if (!mdnsRes.host.isNullOrBlank()) {
                            targetHost = mdnsRes.host
                        }
                        PairingState.log("[1/3] ✅ mDNS tìm thấy dịch vụ kết nối: host=$targetHost, port=$port")
                    } else {
                        PairingState.log("[1/3] ⚠ mDNS không quét được dịch vụ kết nối sau 5s")
                    }
                } else {
                    PairingState.log("[1/3] Sử dụng cổng kết nối đã xác định: port=$port (host=$targetHost)")
                }

                if (port == null || port !in 1..65535) {
                    withContext(Dispatchers.Main) {
                        isOperating = false
                        // Chưa từng ghép cặp thì về ERROR (để nút chính chạy
                        // luồng ghép cặp), đừng hiện sai là "Đã ghép cặp".
                        PairingState.stateFlow.value = if (adbManager.isPaired) ConnectionState.PAIRED_NOT_CONNECTED else ConnectionState.ERROR
                        PairingState.statusTextFlow.value = "Không tìm thấy cổng tự động. Vui lòng nhập số cổng từ màn hình Cài đặt."
                        PairingState.log("❌ Dừng kết nối: Chưa có số cổng. Hãy nhìn vào dòng 'Địa chỉ IP & Cổng' trong Cài đặt và gõ số cổng vào ô bên dưới.")
                    }
                    return@launch
                }

                // 2. Thử kết nối qua targetHost, nếu lỗi thử tiếp IP Wi-Fi và 127.0.0.1
                val candidateHosts = listOf(targetHost, currentWifiIp, "127.0.0.1").distinct()
                var connectedOk = false
                var lastErr: Exception? = null

                for (host in candidateHosts) {
                    PairingState.log("[2/3] Đang thử kết nối TLS tới $host:$port...")
                    try {
                        val ok = adbManager.connectDevice(host, port)
                        if (ok) {
                            connectedOk = true
                            targetHost = host
                            break
                        } else {
                            PairingState.log("⚠ Thử $host:$port: connect() trả false")
                        }
                    } catch (e: Exception) {
                        lastErr = e
                        PairingState.log("⚠ Thử $host:$port thất bại: [${e.javaClass.simpleName}] ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    isOperating = false
                    if (connectedOk) {
                        adbManager.lastDiscoveredConnectPort = port
                        PairingState.discoveredConnectPortFlow.value = port
                        PairingState.stateFlow.value = ConnectionState.CONNECTED
                        PairingState.statusTextFlow.value = "Đã kết nối ADB thành công tới $targetHost:$port!"
                        PairingState.log("[3/3] ✅ KẾT NỐI ADB THÀNH CÔNG RỰC RỠ! (Host: $targetHost:$port)")
                    } else {
                        PairingState.stateFlow.value = if (adbManager.isPaired) ConnectionState.PAIRED_NOT_CONNECTED else ConnectionState.ERROR
                        val errDetail = lastErr?.let { " - ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                        PairingState.statusTextFlow.value = "Kết nối thất bại$errDetail. Hãy kiểm tra số cổng và gõ lại."
                        PairingState.log("[3/3] ❌ Kết nối thất bại. LƯU Ý: Khóa ghép cặp vẫn được GIỮ NGUYÊN (không bị xóa). Hãy kiểm tra cổng trong Cài đặt.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isOperating = false
                    PairingState.stateFlow.value = if (adbManager.isPaired) ConnectionState.PAIRED_NOT_CONNECTED else ConnectionState.ERROR
                    PairingState.statusTextFlow.value = "Lỗi kết nối: ${e.message}"
                    PairingState.log("❌ Lỗi ngoại lệ trong quy trình kết nối: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    // ── HÀM XỬ LÝ NÚT CHÍNH ADB ──
    fun onMainButtonClicked() {
        if (isOperating) return
        if (!checkWifi()) return

        // Bẫy cũ: ghép cặp thất bại (state ERROR, chưa có cờ ghép cặp) thì
        // nút hiện "Ghép cặp ADB" nhưng nhánh ERROR lại chạy kết nối, không
        // bao giờ ghép cặp lại được. Coi ERROR-chưa-ghép-cặp như NOT_PAIRED.
        val effectiveState = if (connState == ConnectionState.ERROR && !adbManager.isPaired) {
            ConnectionState.NOT_PAIRED
        } else {
            connState
        }

        when (effectiveState) {
            ConnectionState.NOT_PAIRED -> {
                val code = pairingCodeInput.trim()

                if (!checkNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }

                NotificationHelper.showPairingNotification(context, adbManager.lastDiscoveredPairingPort)

                if (code.length == 6) {
                    isOperating = true
                    PairingState.statusTextFlow.value = "Đang ghép cặp với mã $code..."
                    PairingState.log("Bắt đầu ghép cặp với mã $code (Khóa: ${adbManager.keyFingerprint})...")

                    scope.launch(Dispatchers.IO) {
                        try {
                            // Ưu tiên cổng người dùng gõ tay: mDNS hay bị popup chọn thiết bị chặn.
                            var port = manualConnectPortInput.trim().toIntOrNull()
                                ?: adbManager.lastDiscoveredPairingPort.takeIf { it > 0 }
                            if (port == null || port !in 1..65535) {
                                PairingState.log("Đang dò cổng ghép cặp qua mDNS...")
                                port = adbManager.discoverPort(AdbMdns.SERVICE_TYPE_TLS_PAIRING, 6_000)
                            }

                            if (port > 0) {
                                val candidateHosts = listOf(
                                    adbManager.lastDiscoveredHost.takeIf { it.isNotBlank() } ?: currentWifiIp,
                                    currentWifiIp,
                                    "127.0.0.1",
                                ).distinct()
                                var ok = false
                                for (host in candidateHosts) {
                                    PairingState.log("Đang gửi mã $code tới $host:$port...")
                                    if (adbManager.pairDevice(host, port, code)) {
                                        ok = true
                                        break
                                    }
                                }
                                if (ok) {
                                    PairingState.log("✅ Ghép cặp thành công! Đang TỰ ĐỘNG kết nối ADB ngay...")
                                    withContext(Dispatchers.Main) {
                                        // Xóa cổng ghép cặp đã gõ tay: cổng kết nối khác cổng ghép cặp,
                                        // để bước kết nối tự dò qua mDNS thay vì dùng nhầm cổng cũ.
                                        manualConnectPortInput = ""
                                        PairingState.stateFlow.value = ConnectionState.PAIRED_NOT_CONNECTED
                                        PairingState.statusTextFlow.value = "Ghép cặp xong! Đang tự kết nối..."
                                    }
                                    isOperating = false
                                    executeConnectAction()
                                } else {
                                    withContext(Dispatchers.Main) {
                                        isOperating = false
                                        PairingState.stateFlow.value = ConnectionState.ERROR
                                        PairingState.statusTextFlow.value = "Ghép cặp thất bại: Mã không đúng hoặc popup đã đóng"
                                        PairingState.log("❌ Ghép cặp thất bại – kiểm tra mã và popup trong Cài đặt")
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    isOperating = false
                                    PairingState.stateFlow.value = ConnectionState.ERROR
                                    PairingState.statusTextFlow.value = "Không tìm thấy cổng ghép cặp. Hãy mở popup trong Cài đặt."
                                    PairingState.log("❌ Không tìm thấy cổng qua mDNS sau 6s. Đã mở ô nhập cổng.")
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isOperating = false
                                PairingState.stateFlow.value = ConnectionState.ERROR
                                PairingState.statusTextFlow.value = "Lỗi ghép cặp: ${e.message}"
                                PairingState.log("❌ Lỗi ngoại lệ ghép cặp: ${e.message}")
                            }
                        }
                    }
                } else {
                    PairingState.statusTextFlow.value = "Đã hiện thông báo! Mở Cài đặt xem mã 6 số rồi nhập vào thông báo."
                    PairingState.log("🔔 Đã hiển thị thông báo. Hãy sang Cài đặt xem mã 6 số.")

                    isOperating = true
                    scope.launch(Dispatchers.IO) {
                        val port = adbManager.discoverPort(AdbMdns.SERVICE_TYPE_TLS_PAIRING, 6_000)
                        withContext(Dispatchers.Main) {
                            isOperating = false
                            if (port > 0) {
                                PairingState.log("✅ mDNS: Đã tìm thấy cổng ghép cặp $port! Hãy kéo thông báo xuống gửi mã.")
                                NotificationHelper.showPairingNotification(context, port)
                            } else {
                                PairingState.log("⚠ mDNS chưa thấy cổng sau 6s. Hãy đảm bảo popup Ghép nối đang mở.")
                            }
                        }
                    }
                }
            }

            ConnectionState.PAIRED_NOT_CONNECTED, ConnectionState.ERROR -> {
                executeConnectAction()
            }

            ConnectionState.CONNECTED -> {
                isOperating = true
                PairingState.statusTextFlow.value = "Đang thực thi lệnh id..."
                PairingState.log("Chạy lệnh: id")

                scope.launch(Dispatchers.IO) {
                    try {
                        val result = adbManager.runId()
                        withContext(Dispatchers.Main) {
                            isOperating = false
                            PairingState.statusTextFlow.value = "Thực thi lệnh id thành công!"
                            outputShell = result.ifBlank { "(Kết quả rỗng)" }
                            PairingState.log("➡ Kết quả lệnh id: $result")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isOperating = false
                            PairingState.stateFlow.value = ConnectionState.PAIRED_NOT_CONNECTED
                            PairingState.statusTextFlow.value = "Mất kết nối ADB: ${e.message}"
                            outputShell = "Lỗi: ${e.message}"
                            PairingState.log("❌ Lỗi chạy lệnh (vẫn GIỮ cờ ghép cặp): ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // Tự động kết nối khi mở màn (nếu đã ghép cặp trước đó).
    LaunchedEffect(Unit) {
        val permGranted = checkNotificationPermission()
        val sysEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        hasNotificationPermission = permGranted
        areSysNotificationsEnabled = sysEnabled

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (!permGranted || !sysEnabled)) {
            val toRequest = mutableListOf<String>()
            if (!permGranted) toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (toRequest.isNotEmpty()) {
                permissionLauncher.launch(toRequest.toTypedArray())
            }
        }

        if (adbManager.isPaired) {
            PairingState.stateFlow.value = ConnectionState.PAIRED_NOT_CONNECTED
            PairingState.statusTextFlow.value = "Đã ghép cặp. Đang tự động kết nối..."
            PairingState.log("Mở màn ADB: Phát hiện trạng thái ĐÃ GHÉP CẶP (Khóa: ${adbManager.keyFingerprint}) → Tự động kết nối ADB...")
            executeConnectAction()
        } else {
            PairingState.stateFlow.value = ConnectionState.NOT_PAIRED
            PairingState.statusTextFlow.value = "Chưa ghép cặp thiết bị"
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Quay lại") }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "ADB không dây",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Ghép cặp gỡ lỗi không dây rồi tự kết nối. Kết nối xong thì quay lại tab Game " +
                "để khởi động Server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val stateColor = when (connState) {
            ConnectionState.NOT_PAIRED -> Color(0xFFFF9800)
            ConnectionState.PAIRED_NOT_CONNECTED -> Color(0xFF2196F3)
            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            ConnectionState.ERROR -> Color(0xFFE53935)
        }
        val stateLabel = when (connState) {
            ConnectionState.NOT_PAIRED -> "⚪ ADB: Chưa ghép cặp"
            ConnectionState.PAIRED_NOT_CONNECTED -> "🔵 ADB: Đã ghép cặp, chưa kết nối"
            ConnectionState.CONNECTED -> "🟢 ADB: Đã kết nối"
            ConnectionState.ERROR -> "🔴 ADB: Lỗi"
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = stateColor.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )
                Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "IP Wi-Fi máy: $currentWifiIp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Khóa ID: ${adbManager.keyFingerprint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!hasNotificationPermission || !areSysNotificationsEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Thông báo đang tắt. Bật để nhập mã tiện hơn:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC62828),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }) {
                        Text("Bật ngay")
                    }
                }
            }
        }

        if (connState == ConnectionState.NOT_PAIRED) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Mã ghép cặp 6 số (Xem trong Tùy chọn nhà phát triển):",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = pairingCodeInput,
                    onValueChange = { pairingCodeInput = it.filter(Char::isDigit).take(6) },
                    placeholder = { Text("Nhập tại đây hoặc gõ qua thông báo") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (connState != ConnectionState.CONNECTED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Cổng kết nối ADB (Đọc từ dòng 'Địa chỉ IP & Cổng' trong Cài đặt):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "$currentWifiIp :",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedTextField(
                            value = manualConnectPortInput,
                            onValueChange = { manualConnectPortInput = it.filter(Char::isDigit).take(5) },
                            placeholder = { Text("VD: 44319") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        val mainButtonText = when {
            isOperating -> "Đang xử lý..."
            connState == ConnectionState.CONNECTED -> "Chạy thử (Lệnh `id`)"
            connState == ConnectionState.PAIRED_NOT_CONNECTED -> "Kết nối lại ADB"
            connState == ConnectionState.ERROR && adbManager.isPaired -> "Kết nối lại ADB"
            else -> "Ghép cặp ADB"
        }

        val mainButtonColor = when (connState) {
            ConnectionState.CONNECTED -> ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ConnectionState.PAIRED_NOT_CONNECTED -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ConnectionState.ERROR -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        }

        Button(
            onClick = { onMainButtonClicked() },
            enabled = !isOperating,
            colors = mainButtonColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (isOperating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(text = mainButtonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        outputShell?.let { out ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Kết quả lệnh `id` qua ADB:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = out,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (out.contains("uid=2000")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { openDevSettings() },
                modifier = Modifier.weight(1.3f),
            ) {
                Text("⚙ Mở Tùy chọn nhà phát triển", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(
                onClick = {
                    adbManager.resetPairing()
                    PairingState.stateFlow.value = ConnectionState.NOT_PAIRED
                    PairingState.statusTextFlow.value = "Đã xóa chứng chỉ. Hãy bấm 'Ghép cặp' để bắt đầu lại."
                    pairingCodeInput = ""
                    manualConnectPortInput = ""
                    outputShell = null
                    PairingState.log("Đã reset chứng chỉ và cờ ghép cặp từ đầu")
                },
                modifier = Modifier.weight(0.9f),
            ) {
                Text("Ghép cặp lại", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        if (connState == ConnectionState.CONNECTED) {
            Text(
                "Đã kết nối ADB. Quay lại tab Game để khởi động Server.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF2E7D32),
            )
        }
    }
}

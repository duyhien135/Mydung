package com.example.mydung.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.mydung.server.Config
import com.example.mydung.server.DeviceKind
import com.example.mydung.server.InputClient
import com.example.mydung.server.InputDeviceInfo
import com.example.mydung.server.LinuxKey
import com.example.mydung.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn "Kiểm tra thiết bị nhập" (GĐ3): theo dõi trực tiếp sự kiện từ
 * bàn phím/chuột USB qua Server, bật/tắt GRAB, xem trạng thái thực.
 */
@Composable
fun InputTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var monitoring by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(listOf<InputDeviceInfo>()) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Trạng thái trực tiếp từ sự kiện.
    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }
    var pressedButtons by remember { mutableStateOf(setOf<Int>()) }
    var mouseX by remember { mutableIntStateOf(0) }
    var mouseY by remember { mutableIntStateOf(0) }

    // Công tắc GRAB + trạng thái thực do Server trả về.
    var grabMouse by remember { mutableStateOf(false) }
    var grabKeyboard by remember { mutableStateOf(false) }
    var grabMouseStatus by remember { mutableStateOf("Chưa chiếm") }
    var grabKeyboardStatus by remember { mutableStateOf("Chưa chiếm") }

    // Bộ đệm dòng EV nhận từ luồng socket, xả vào state mỗi ~33ms (30 fps).
    val pending = remember { ArrayList<String>() }

    fun addLog(line: String) {
        logLines = (listOf(line) + logLines).take(Config.UI_MAX_LOG_LINES)
    }

    fun refreshDevices() {
        scope.launch(Dispatchers.IO) {
            val conn = ServerManager.getSavedConnection(context)
            if (conn == null) {
                errorText = "Chưa có Server đang chạy."
                return@launch
            }
            try {
                val list = InputClient.fetchDevices(conn.first, conn.second)
                devices = list
                errorText = null
            } catch (e: Exception) {
                errorText = "Không lấy được danh sách thiết bị: ${e.message}"
            }
        }
    }

    fun setGrab(target: String, on: Boolean) {
        scope.launch(Dispatchers.IO) {
            val conn = ServerManager.getSavedConnection(context) ?: return@launch
            try {
                val resp = InputClient.setGrab(conn.first, conn.second, target, on)
                // Phản hồi thật: "OK GRAB mouse=1 keyboard=0 method=A grabbed=2 failed=0"
                val grabbed = Regex("grabbed=(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val failed = Regex("failed=(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val method = Regex("method=([AB])").find(resp)?.groupValues?.get(1) ?: "?"
                val status = when {
                    !on -> "Đã nhả"
                    grabbed > 0 -> "Đã chiếm $grabbed thiết bị (cách $method)" + if (failed > 0) ", $failed lỗi" else ""
                    failed > 0 -> "Không chiếm được (cách $method)"
                    else -> "Không có thiết bị nào để chiếm"
                }
                if (target == "mouse") grabMouseStatus = status else grabKeyboardStatus = status
                addLog("GRAB $target ${if (on) "bật" else "tắt"}: $resp")
            } catch (e: Exception) {
                errorText = "Lỗi GRAB $target: ${e.message}"
            }
        }
    }

    // Lấy danh sách thiết bị khi mở màn.
    LaunchedEffect(Unit) { refreshDevices() }

    // Vòng theo dõi: mở SUB_INPUT khi bật, đóng khi tắt/rời màn.
    LaunchedEffect(monitoring) {
        if (!monitoring) return@LaunchedEffect
        val conn = ServerManager.getSavedConnection(context)
        if (conn == null) {
            errorText = "Chưa có Server đang chạy."
            monitoring = false
            return@LaunchedEffect
        }
        val sub = try {
            withContext(Dispatchers.IO) {
                InputClient.Subscription(
                    conn.first,
                    conn.second,
                    onLine = { line -> synchronized(pending) { pending.add(line) } },
                    onError = { err -> errorText = "Mất kết nối stream: $err" },
                )
            }
        } catch (e: Exception) {
            errorText = "Không mở được stream: ${e.message}"
            monitoring = false
            return@LaunchedEffect
        }
        addLog("Đã bắt đầu theo dõi (SUB_INPUT).")
        try {
            while (true) {
                delay(Config.UI_FLUSH_INTERVAL_MS)
                val batch: List<String>
                synchronized(pending) {
                    batch = pending.toList()
                    pending.clear()
                }
                if (batch.isEmpty()) continue
                var dx = 0
                var dy = 0
                for (line in batch) {
                    val p = line.split(" ")
                    if (p.size < 2 || p[0] != "EV") continue
                    when (p[1]) {
                        "K" -> {
                            val code = p.getOrNull(2)?.toIntOrNull() ?: continue
                            val down = p.getOrNull(3) == "1"
                            pressedKeys = if (down) pressedKeys + code else pressedKeys - code
                            addLog("Phím ${LinuxKey.name(code)} ${if (down) "nhấn" else "nhả"}")
                        }
                        "M" -> {
                            dx += p.getOrNull(2)?.toIntOrNull() ?: 0
                            dy += p.getOrNull(3)?.toIntOrNull() ?: 0
                        }
                        "B" -> {
                            val code = p.getOrNull(2)?.toIntOrNull() ?: continue
                            val down = p.getOrNull(3) == "1"
                            pressedButtons = if (down) pressedButtons + code else pressedButtons - code
                            addLog("Nút ${LinuxKey.name(code)} ${if (down) "nhấn" else "nhả"}")
                        }
                        "W" -> {
                            addLog("Lăn chuột: dọc ${p.getOrNull(2) ?: "?"} ngang ${p.getOrNull(3) ?: "?"}")
                        }
                        "DEV" -> {
                            val isAdd = p.getOrNull(2) == "add"
                            val id = p.getOrNull(3) ?: continue
                            val name = p.drop(4).joinToString(" ")
                            addLog("Thiết bị ${if (isAdd) "cắm vào" else "rút ra"}: $name ($id)")
                            refreshDevices()
                        }
                    }
                }
                if (dx != 0 || dy != 0) {
                    mouseX += dx
                    mouseY += dy
                }
            }
        } finally {
            sub.close()
            addLog("Đã dừng theo dõi.")
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Quay lại") }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Kiểm tra thiết bị nhập",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { monitoring = !monitoring },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text(if (monitoring) "⏹ Dừng theo dõi" else "▶ Bắt đầu theo dõi", fontWeight = FontWeight.Bold)
        }

        // Danh sách thiết bị.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Thiết bị (${devices.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { refreshDevices() }) { Text("Làm mới") }
                }
                if (devices.isEmpty()) {
                    Text("Chưa thấy thiết bị nào. Cắm phím/chuột USB rồi bấm Làm mới.", style = MaterialTheme.typography.bodySmall)
                }
                devices.forEach { d ->
                    Text(
                        "• ${d.name} — ${if (d.kind == DeviceKind.MOUSE) "chuột" else "bàn phím"} (${d.node})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Công tắc GRAB.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chiếm thiết bị (GRAB)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Chiếm chuột", modifier = Modifier.weight(1f))
                    Switch(
                        checked = grabMouse,
                        onCheckedChange = { on ->
                            grabMouse = on
                            setGrab("mouse", on)
                        },
                    )
                }
                Text("Trạng thái chuột: $grabMouseStatus", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Chiếm bàn phím", modifier = Modifier.weight(1f))
                    Switch(
                        checked = grabKeyboard,
                        onCheckedChange = { on ->
                            grabKeyboard = on
                            setGrab("keyboard", on)
                        },
                    )
                }
                Text("Trạng thái bàn phím: $grabKeyboardStatus", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Lưu ý: phím thoát khẩn cấp Ctrl trái + Alt trái + Esc giữ 1 giây sẽ nhả mọi GRAB ngay trong Server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Trạng thái trực tiếp.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trạng thái trực tiếp", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Phím đang đè: ${if (pressedKeys.isEmpty()) "(không có)" else pressedKeys.sorted().joinToString(", ") { LinuxKey.name(it) }}")
                Text("Tọa độ chuột tích lũy: X=$mouseX  Y=$mouseY")
                Text("Nút chuột đang đè: ${if (pressedButtons.isEmpty()) "(không có)" else pressedButtons.sorted().joinToString(", ") { LinuxKey.name(it) }}")
                OutlinedButton(onClick = { mouseX = 0; mouseY = 0 }) {
                    Text("Đặt lại tọa độ chuột")
                }
            }
        }

        // Log trực tiếp.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Log sự kiện (${logLines.size}/${Config.UI_MAX_LOG_LINES})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { logLines = emptyList() }) { Text("Xóa log") }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp),
        ) {
            val logScroll = rememberScrollState()
            Column(modifier = Modifier.padding(10.dp).verticalScroll(logScroll)) {
                if (logLines.isEmpty()) {
                    Text("(Chưa có sự kiện)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    logLines.forEach { msg ->
                        Text(msg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

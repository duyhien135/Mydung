package com.example.mydung.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mydung.server.InputClient
import com.example.mydung.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Màn "Thử cảm ứng" (GĐ4): gửi DISPLAY, bật/tắt MAP, test tiêm chạm/vuốt/
 * 2 ngón. Cấu hình joystick/camera/độ nhạy nằm trong profile (GĐ5) — mở
 * Quản lý profile để chỉnh.
 *
 * Làm nút TEST trước khi test bàn phím/chuột: nó cho biết ngay phần tiêm
 * có chạy không. Bật "Pointer location" trong Tùy chọn nhà phát triển để
 * thấy 2 con trỏ khi test 2 ngón.
 */
@Composable
fun TouchTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statusText by remember { mutableStateOf("Chưa lấy trạng thái.") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var mapOn by remember { mutableStateOf(false) }
    var injector by remember { mutableStateOf("reflect") }

    fun conn(): Pair<Int, String>? = ServerManager.getSavedConnection(context)

    fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            val c = conn()
            if (c == null) {
                errorText = "Chưa có Server đang chạy."
                return@launch
            }
            try {
                val resp = InputClient.mapStatus(c.first, c.second)
                // "MAP map=1 display=2560x1600 pointers=2 latencyMs=4 injectErr=0 injectOk=1"
                val map = Regex("map=(\\d)").find(resp)?.groupValues?.get(1) == "1"
                val disp = Regex("display=(\\S+)").find(resp)?.groupValues?.get(1) ?: "?"
                val pointers = Regex("pointers=(\\d+)").find(resp)?.groupValues?.get(1) ?: "?"
                val latency = Regex("latencyMs=(\\d+)").find(resp)?.groupValues?.get(1) ?: "?"
                val err = Regex("injectErr=(\\d+)").find(resp)?.groupValues?.get(1) ?: "?"
                val ok = Regex("injectOk=(\\d)").find(resp)?.groupValues?.get(1) == "1"
                val inj = Regex("injector=(\\w+)").find(resp)?.groupValues?.get(1) ?: "reflect"
                mapOn = map
                injector = inj
                statusText = "MAP: ${if (map) "BẬT" else "tắt"} | Màn hình: $disp | " +
                    "Ngón đang đè: $pointers | Độ trễ TB: ${latency}ms | " +
                    "Lỗi tiêm: $err | Tiêm: ${if (ok) "khả dụng" else "KHÔNG khả dụng"} ($inj)"
                errorText = null
            } catch (e: Exception) {
                errorText = "Không lấy được trạng thái MAP: ${e.message}"
            }
        }
    }

    fun sendDisplay(onDone: ((Boolean) -> Unit)? = null) {
        val d = readRealDisplay(context) ?: run {
            errorText = "Không đọc được kích thước màn hình."
            onDone?.invoke(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            val c = conn()
            if (c == null) {
                errorText = "Chưa có Server đang chạy."
                onDone?.invoke(false)
                return@launch
            }
            try {
                val resp = InputClient.sendDisplay(c.first, c.second, d.width, d.height, d.rotation, d.displayId)
                lastResult = "DISPLAY: $resp (${d.width}x${d.height}, xoay=${d.rotation})"
                onDone?.invoke(resp.startsWith("OK"))
            } catch (e: Exception) {
                errorText = "Lỗi gửi DISPLAY: ${e.message}"
                onDone?.invoke(false)
            }
        }
    }

    fun toggleMap() {
        if (!mapOn) {
            // Bật MAP: gửi DISPLAY mới nhất trước, rồi mới MAP 1.
            sendDisplay { ok ->
                if (!ok) return@sendDisplay
                scope.launch(Dispatchers.IO) {
                    val c = conn() ?: return@launch
                    try {
                        val resp = InputClient.setMap(c.first, c.second, true)
                        lastResult = "MAP bật: $resp"
                        refreshStatus()
                    } catch (e: Exception) {
                        errorText = "Lỗi bật MAP: ${e.message}"
                    }
                }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                val c = conn() ?: return@launch
                try {
                    val resp = InputClient.setMap(c.first, c.second, false)
                    lastResult = "MAP tắt: $resp"
                    refreshStatus()
                } catch (e: Exception) {
                    errorText = "Lỗi tắt MAP: ${e.message}"
                }
            }
        }
    }

    fun runTest(args: String, label: String) {
        scope.launch(Dispatchers.IO) {
            val c = conn()
            if (c == null) {
                errorText = "Chưa có Server đang chạy."
                return@launch
            }
            try {
                val resp = InputClient.testTouch(c.first, c.second, args)
                lastResult = "$label: $resp"
                refreshStatus()
            } catch (e: Exception) {
                errorText = "Lỗi test $label: ${e.message}"
            }
        }
    }

    fun setInjector(mode: String) {
        scope.launch(Dispatchers.IO) {
            val c = conn()
            if (c == null) {
                errorText = "Chưa có Server đang chạy."
                return@launch
            }
            try {
                val resp = InputClient.setInjector(c.first, c.second, mode)
                lastResult = "INJECT $mode: $resp"
                refreshStatus()
            } catch (e: Exception) {
                errorText = "Lỗi đổi injector: ${e.message}"
            }
        }
    }

    // Lấy trạng thái khi mở màn + cập nhật mỗi 1 giây.
    LaunchedEffect(Unit) {
        refreshStatus()
        while (true) {
            delay(1000)
            refreshStatus()
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
                "Thử cảm ứng",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trạng thái", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
                lastResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sendDisplay() }, modifier = Modifier.weight(1f)) {
                Text("Gửi DISPLAY")
            }
            Button(
                onClick = { toggleMap() },
                modifier = Modifier.weight(1f).height(50.dp),
            ) {
                Text(if (mapOn) "⏹ Tắt MAP (F8)" else "▶ Bật MAP (F8)", fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Bật MAP rồi mới dùng được WASD/chuột trong game. Phím F8 trên bàn phím USB cũng bật/tắt MAP. " +
                "Giữ Ctrl trái + Alt trái + Esc 1 giây để hủy khẩn mọi cảm ứng.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Chọn cách tiêm (GĐ6): reflect mặc định, evdev là dự phòng khi
        // reflect bị ZUI chặn.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Cách tiêm cảm ứng (đang dùng: $injector)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Phản chiếu là cách mặc định (GĐ4). Nếu test tiêm không ăn thì thử Evdev thô: " +
                        "ghi event thẳng vào node cảm ứng, không dùng API ẩn.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { setInjector("reflect") },
                        modifier = Modifier.weight(1f),
                        enabled = injector != "reflect",
                    ) { Text("Phản chiếu") }
                    Button(
                        onClick = { setInjector("evdev") },
                        modifier = Modifier.weight(1f),
                        enabled = injector != "evdev",
                    ) { Text("Evdev thô") }
                }
            }
        }

        // Nút TEST tiêm — làm trước khi test bàn phím/chuột.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test tiêm (không cần bàn phím/chuột)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Bấm từng nút rồi xem màn hình có phản ứng không. Với test 2 ngón, bật \"Pointer location\" " +
                        "trong Tùy chọn nhà phát triển để thấy 2 con trỏ.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { runTest("TAP 0.5 0.5", "Chạm 1 điểm") }, modifier = Modifier.fillMaxWidth()) {
                    Text("👆 Tiêm 1 chạm vào giữa màn hình")
                }
                Button(onClick = { runTest("SWIPE 0.3 0.5 0.7 0.5 500", "Vuốt") }, modifier = Modifier.fillMaxWidth()) {
                    Text("↔ Tiêm vuốt ngang (0.5 giây)")
                }
                Button(onClick = { runTest("TWO 0.4 0.4 0.6 0.6", "2 ngón") }, modifier = Modifier.fillMaxWidth()) {
                    Text("✌️ Tiêm 2 ngón cùng lúc")
                }
            }
        }

        // Cấu hình joystick/camera/độ nhạy nằm trong profile (GĐ5).
        Text(
            "Muốn chỉnh vị trí joystick/camera, độ nhạy hay phím bật/tắt MAP: " +
                "quay lại màn chính → Quản lý profile (hoặc nút Chỉnh nút để kéo thả trên game).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

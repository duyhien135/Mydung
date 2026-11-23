package com.example.mydung.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mydung.ActivationMethod
import com.example.mydung.ActivationPrefs
import com.example.mydung.AdbConnectionManager
import com.example.mydung.PairingState
import com.example.mydung.server.ServerManager

/**
 * Tab Cài đặt: chọn phương thức kích hoạt, lưu ý HyperOS, thông tin app.
 * (Các mục gỡ lỗi cũ — cách tiêm, thao tác Server, kiểm tra phím/cảm ứng,
 * nhật ký — đã bỏ cho gọn như GG Mouse/K7XD.)
 */
@Composable
fun SettingsScreen(
    method: ActivationMethod,
    onMethodChange: (ActivationMethod) -> Unit,
    onOpenAdbSetup: () -> Unit,
) {
    val context = LocalContext.current
    val adbManager = remember { AdbConnectionManager.getInstance(context) }

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

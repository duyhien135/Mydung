package com.example.mydung

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.example.mydung.server.ServerManager
import com.example.mydung.shizuku.ShizukuBridge
import com.example.mydung.ui.AdbSetupScreen
import com.example.mydung.ui.HomeScreen
import com.example.mydung.ui.InputTestScreen
import com.example.mydung.ui.ProfileScreen
import com.example.mydung.ui.SettingsScreen
import com.example.mydung.ui.TouchTestScreen
import java.net.Inet4Address

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createNotificationChannel(this)
        // Đăng ký listener Shizuku 1 lần (idempotent, gọi lại không sao).
        ShizukuBridge.install()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KeyTouchApp()
                }
            }
        }
    }
}

// ── Hàm lấy địa chỉ IP Wi-Fi cục bộ của máy (màn ADB + receiver ghép cặp dùng) ──
fun getWifiIpAddress(context: Context): String {
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork)
        if (lp != null) {
            for (la in lp.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (_: Exception) {
    }
    return "127.0.0.1"
}

/**
 * Khung app mới: thanh điều hướng dưới 3 tab (kiểu K7XD) + các màn con
 * toàn màn hình (thử phím / thử cảm ứng / cài đặt ADB).
 *
 * - Tab Game: màn hình chính tối giản (1 dòng trạng thái + danh sách game).
 * - Tab Cấu hình: quản lý profile (độ nhạy, phím bật/tắt MAP, gán game).
 * - Tab Cài đặt: phương thức kích hoạt, cách tiêm, Server, kiểm tra, nhật ký.
 */
@Composable
fun KeyTouchApp() {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) } // 0 = Game, 1 = Cấu hình, 2 = Cài đặt
    var method by remember { mutableStateOf(ActivationPrefs.get(context)) }

    var showInputTest by remember { mutableStateOf(false) }
    var showTouchTest by remember { mutableStateOf(false) }
    var showAdbSetup by remember { mutableStateOf(false) }

    // Kiểm tra Server đã lưu khi mở app: đang chạy hợp lệ thì tái sử dụng.
    LaunchedEffect(Unit) {
        ServerManager.checkOrReconnectServer(context)
    }

    if (showInputTest) {
        InputTestScreen(onBack = { showInputTest = false })
        return
    }
    if (showTouchTest) {
        TouchTestScreen(onBack = { showTouchTest = false })
        return
    }
    if (showAdbSetup) {
        AdbSetupScreen(onBack = { showAdbSetup = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("🎮", fontSize = 22.sp) },
                    label = { Text("Game") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("📋", fontSize = 22.sp) },
                    label = { Text("Cấu hình") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("⚙️", fontSize = 22.sp) },
                    label = { Text("Cài đặt") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(
                    method = method,
                    onOpenAdbSetup = { showAdbSetup = true },
                )
                1 -> ProfileScreen(onBack = {}, showBack = false)
                else -> SettingsScreen(
                    method = method,
                    onMethodChange = { method = it },
                    onOpenAdbSetup = { showAdbSetup = true },
                )
            }
        }
    }
}

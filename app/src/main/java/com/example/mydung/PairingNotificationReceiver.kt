package com.example.mydung

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.android.AdbMdns

/**
 * Nhận mã ghép cặp 6 số người dùng gõ qua thông báo (RemoteInput) rồi tự
 * ghép cặp + kết nối ADB trong nền.
 *
 * Chạy cả khi app đã đóng (receiver khai báo trong Manifest): dùng goAsync()
 * để làm việc mạng trên luồng riêng, xong mới finish(). Kết quả báo lại bằng
 * thông báo + ghi vào PairingState (màn hình chính đọc được khi mở app).
 */
class PairingNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_PAIRING_CODE) return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(NotificationHelper.KEY_PAIRING_CODE)
            ?.toString()?.trim().orEmpty()
        if (!code.matches(Regex("\\d{6}"))) {
            NotificationHelper.updateNotification(
                context,
                "Mã không hợp lệ",
                "Mã ghép cặp phải gồm 6 chữ số. Mở lại thông báo gõ lại.",
            )
            return
        }

        val pending = goAsync()
        Thread {
            try {
                pairWithCode(context.applicationContext, code)
            } catch (e: Exception) {
                PairingState.log("❌ Ghép cặp qua thông báo lỗi: ${e.message}")
                NotificationHelper.updateNotification(
                    context.applicationContext,
                    "Ghép cặp lỗi",
                    e.message ?: "Không rõ nguyên nhân",
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun pairWithCode(appContext: Context, code: String) {
        val adb = AdbConnectionManager.getInstance(appContext)
        PairingState.log("📩 Nhận mã ghép cặp qua thông báo, đang ghép cặp...")
        NotificationHelper.updateNotification(
            appContext,
            "Đang ghép cặp…",
            "Đã nhận mã, đang ghép cặp với máy.",
            ongoing = true,
        )

        var port = adb.lastDiscoveredPairingPort.takeIf { it > 0 }
        if (port == null || port !in 1..65535) {
            port = adb.discoverPort(AdbMdns.SERVICE_TYPE_TLS_PAIRING, 6_000)
        }
        if (port == null || port <= 0) {
            PairingState.stateFlow.value = ConnectionState.ERROR
            PairingState.statusTextFlow.value = "Không tìm thấy cổng ghép cặp."
            NotificationHelper.updateNotification(
                appContext,
                "Ghép cặp thất bại",
                "Không tìm thấy cổng ghép cặp. Mở popup Ghép nối trong Cài đặt rồi thử lại.",
            )
            return
        }

        val wifiIp = getWifiIpAddress(appContext)
        val hosts = listOf(
            adb.lastDiscoveredHost.takeIf { it.isNotBlank() } ?: wifiIp,
            wifiIp,
            "127.0.0.1",
        ).distinct()
        var ok = false
        for (host in hosts) {
            try {
                if (adb.pairDevice(host, port, code)) {
                    ok = true
                    break
                }
            } catch (_: Exception) {
                // Thử host tiếp theo.
            }
        }
        if (!ok) {
            PairingState.stateFlow.value = ConnectionState.ERROR
            PairingState.statusTextFlow.value = "Ghép cặp thất bại: mã không đúng hoặc popup đã đóng."
            NotificationHelper.updateNotification(
                appContext,
                "Ghép cặp thất bại",
                "Mã không đúng hoặc popup ghép nối đã đóng. Mở popup mới rồi gõ lại mã.",
            )
            return
        }

        // Ghép cặp xong thì tự kết nối ADB ngay.
        PairingState.stateFlow.value = ConnectionState.PAIRED_NOT_CONNECTED
        PairingState.statusTextFlow.value = "Ghép cặp xong! Đang tự kết nối..."
        val connected = try {
            adb.autoConnectDevice(7_000)
        } catch (_: Exception) {
            false
        }
        if (connected) {
            PairingState.stateFlow.value = ConnectionState.CONNECTED
            PairingState.statusTextFlow.value = "Đã kết nối ADB thành công!"
            NotificationHelper.updateNotification(
                appContext,
                "Đã kết nối ADB",
                "Ghép cặp + kết nối thành công. Mở app để khởi động Server.",
            )
        } else {
            NotificationHelper.updateNotification(
                appContext,
                "Ghép cặp xong",
                "Ghép cặp thành công nhưng chưa tự kết nối được. Mở app bấm Kết nối lại.",
            )
        }
    }
}

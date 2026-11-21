package com.example.mydung

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

object NotificationHelper {
    // Dùng channel v3 mới để đảm bảo IMPORTANCE_HIGH được tạo tươi mới trong hệ thống
    const val CHANNEL_ID = "adb_pairing_channel_v3"
    const val NOTIFICATION_ID = 1001
    const val KEY_PAIRING_CODE = "key_pairing_code"
    const val ACTION_PAIRING_CODE = "com.example.mydung.ACTION_PAIRING_CODE"

    fun createNotificationChannel(context: Context): NotificationChannel? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) {
                PairingState.log("[Kênh] Đã tồn tại channel: $CHANNEL_ID, importance=${existing.importance}")
                return existing
            }

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghép cặp ADB",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Kênh nhận mã ghép cặp gỡ lỗi không dây qua thông báo"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
            val created = manager.getNotificationChannel(CHANNEL_ID)
            PairingState.log("[Kênh] Đã tạo mới channel: $CHANNEL_ID, importance=${created?.importance}")
            return created
        }
        return null
    }

    fun showPairingNotification(context: Context, port: Int = -1): Boolean {
        try {
            PairingState.log("── Bắt đầu tạo thông báo ghép cặp ──")

            // 1. Kiểm tra quyền hiển thị thông báo
            val managerCompat = NotificationManagerCompat.from(context)
            val areEnabled = managerCompat.areNotificationsEnabled()
            PairingState.log("[1/5] areNotificationsEnabled = $areEnabled")
            if (!areEnabled) {
                PairingState.log("❌ LỖI: Thông báo của ứng dụng đang BỊ TẮT trong cài đặt hệ thống!")
                return false
            }

            // 2. Tạo hoặc lấy NotificationChannel với IMPORTANCE_HIGH
            val channel = createNotificationChannel(context)
            if (channel != null && channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                PairingState.log("⚠ Cảnh báo: Channel importance hiện tại là ${channel.importance} (cần 4 = HIGH)")
            }
            PairingState.log("[2/5] Kênh thông báo sẵn sàng (ID: $CHANNEL_ID)")

            // 3. Tạo RemoteInput
            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel("Nhập mã 6 số (VD: 849205)")
                .build()
            PairingState.log("[3/5] Đã khởi tạo RemoteInput (key: $KEY_PAIRING_CODE)")

            // 4. Tạo PendingIntent với FLAG_MUTABLE
            val intent = Intent(context, PairingNotificationReceiver::class.java).apply {
                action = ACTION_PAIRING_CODE
                setPackage(context.packageName)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            PairingState.log("[4/5] Đã tạo PendingIntent với FLAG_UPDATE_CURRENT or FLAG_MUTABLE")

            // 5. Tạo Action có gắn RemoteInput và SEMANTIC_ACTION_REPLY
            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_input_add,
                "Nhập mã ghép cặp 6 số",
                pendingIntent,
            )
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(true)
                .build()

            // Intent khi chạm vào thân thông báo (mở lại MainActivity)
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                1002,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val portText = if (port > 0) " (Cổng: $port)" else " (Đang dò cổng...)"
            val subText = if (port > 0) "Cổng mDNS: $port" else "Đang dò cổng mDNS..."

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Ghép cặp Gỡ lỗi không dây$portText")
                .setContentText("Nhấn 'Nhập mã ghép cặp 6 số' để gõ mã từ Cài đặt")
                .setSubText(subText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle("Ghép cặp Gỡ lỗi không dây$portText")
                        .bigText("Nhấn nút 'Nhập mã ghép cặp 6 số' bên dưới (hoặc vuốt mở rộng thông báo) để nhập mã từ popup Cài đặt."),
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(replyAction)
                .build()

            managerCompat.notify(NOTIFICATION_ID, notification)
            PairingState.log("[5/5] ✅ ĐÃ GỬI THÔNG BÁO THÀNH CÔNG (ID: $NOTIFICATION_ID)")
            return true
        } catch (e: SecurityException) {
            PairingState.log("❌ Lỗi SecurityException khi notify(): ${e.message}")
            return false
        } catch (e: Exception) {
            PairingState.log("❌ Lỗi ngoại lệ khi tạo thông báo: ${e.javaClass.simpleName} - ${e.message}")
            return false
        }
    }

    fun updateNotification(context: Context, title: String, message: String, ongoing: Boolean = false) {
        try {
            createNotificationChannel(context)
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                1002,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppPendingIntent)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            PairingState.log("Không có quyền hiển thị thông báo")
        } catch (e: Exception) {
            PairingState.log("Lỗi cập nhật thông báo: ${e.message}")
        }
    }

    fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}

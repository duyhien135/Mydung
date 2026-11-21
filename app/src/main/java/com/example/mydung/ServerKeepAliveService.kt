package com.example.mydung

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mydung.server.Config
import com.example.mydung.server.ServerManager

/**
 * Foreground service giữ tiến trình app sống trong lúc Server đang chạy.
 *
 * Vì sao cần: heartbeat (PING 5s) + vòng thăm dò DISPLAY/FG chạy trong
 * ServerManager.scope — scope này sống theo TIẾN TRÌNH app, không gắn
 * Activity nào, nhưng lúc chơi game app nằm nền, HyperOS có thể giết cả
 * tiến trình. Mất heartbeat quá 10s thì watchdog trong Server nhả GRAB và
 * tắt MAP giữa trận. Foreground service (kèm thông báo ongoing) giữ tiến
 * trình ở mức ưu tiên cao nên heartbeat không bị giết.
 *
 * Vòng đời gắn với Server, KHÔNG gắn với lớp phủ: bắt đầu khi Server lên
 * (ServerManager.startServer / checkOrReconnectServer dùng lại Server cũ),
 * dừng khi Server dừng/lỗi/mất kết nối. Lớp phủ (OverlayService) cũng là
 * foreground service nhưng chỉ chạy lúc chơi game — service này lấp khoảng
 * trống khi Server chạy mà chưa mở lớp phủ.
 *
 * Service bị hệ thống kill rồi khởi động lại (START_STICKY) thì tự nối lại
 * Server đã lưu (nếu heartbeat chưa chạy) để heartbeat sống lại.
 */
class ServerKeepAliveService : Service() {

    companion object {
        private const val ACTION_START = "com.example.mydung.KEEPALIVE_START"
        private const val ACTION_STOP = "com.example.mydung.KEEPALIVE_STOP"
        private const val CHANNEL_ID = "keytouch_keepalive"

        /** Bắt đầu giữ tiến trình sống (gọi khi Server đã lên). */
        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, ServerKeepAliveService::class.java)
                        .setAction(ACTION_START),
                )
            } catch (_: Exception) {
                // Android 12+ cấm startForegroundService từ nền trong một số
                // trường hợp: Server vẫn chạy, chỉ không có lớp bảo vệ thêm.
            }
        }

        /** Dừng giữ tiến trình (gọi khi Server dừng/lỗi). */
        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, ServerKeepAliveService::class.java)
                        .setAction(ACTION_STOP),
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Khởi động lại sau khi bị kill (START_STICKY): heartbeat
                // đã chết theo tiến trình cũ, nối lại Server đã lưu để
                // heartbeat sống lại. Heartbeat còn chạy thì không làm gì
                // (tránh vòng lặp: checkOrReconnect lại gọi start()).
                if (!ServerManager.isHeartbeatRunning()) {
                    ServerManager.checkOrReconnectServer(this)
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Server KeyTouch", NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Giữ kết nối tới Server chơi game khi app ở nền"
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("KeyTouch")
            .setContentText("Server đang chạy — giữ kết nối khi chơi game")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE chỉ có từ API 34.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Config.KEEPALIVE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Config.KEEPALIVE_NOTIFICATION_ID, notification)
        }
    }
}

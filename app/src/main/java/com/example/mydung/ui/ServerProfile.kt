package com.example.mydung.ui

import android.content.Context
import com.example.mydung.server.Profile
import com.example.mydung.server.ServerManager

/**
 * Gửi DISPLAY mới nhất rồi PROFILE cho Server đang chạy. Chạy trên luồng
 * gọi (socket chặn) nên đừng gọi trên luồng chính. Trả về phản hồi của
 * Server ("OK PROFILE ..." hoặc "ERR ...").
 * (Bản thực ở ServerManager để cả auto-switch GĐ6 dùng chung.)
 */
fun sendProfileToServer(context: Context, profile: Profile): String =
    ServerManager.sendProfileToServer(context, profile)

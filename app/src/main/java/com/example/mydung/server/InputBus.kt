package com.example.mydung.server

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bus nội bộ của Server: bên đọc thiết bị publish, bên nghe đăng ký.
 * GĐ3: kết nối app là 1 người nghe. GĐ4: bộ máy cảm ứng là người nghe thứ hai.
 */
object InputBus {
    fun interface Listener {
        fun onEvent(e: InputEvent)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun subscribe(l: Listener) {
        listeners.add(l)
    }

    fun unsubscribe(l: Listener) {
        listeners.remove(l)
    }

    fun publish(e: InputEvent) {
        for (l in listeners) {
            try {
                l.onEvent(e)
            } catch (_: Exception) {
                // 1 người nghe lỗi không được sập bus / luồng đọc.
            }
        }
    }
}

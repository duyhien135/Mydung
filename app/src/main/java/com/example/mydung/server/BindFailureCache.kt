package com.example.mydung.server

/**
 * Nhớ lần bind UserService lỗi gần nhất để [com.example.mydung.shizuku.ShizukuBridge]
 * không thử bind lại vô ích.
 *
 * Vấn đề (máy thật 21/11/2026): trên máy bind KHÔNG BAO GIỜ thành công —
 * mỗi lần bind tốn đủ BIND_TIMEOUT_MS (10s) rồi mới fallback sang
 * ShizukuNewProcess. Khởi động Server chạy nhiều lệnh shell (pm path, rm
 * log, launch, ps kiểm tra trong vòng thăm dò), mỗi lệnh +10s, cộng dồn
 * ~2 phút mới lên Server.
 *
 * Cách dùng: sau khi bind ném ShizukuBindException thì [recordFailure];
 * [shouldSkipBind] trả true trong [cooldownMs] sau đó — exec() bỏ qua bind
 * UserService, đi thẳng newProcess. Hết cooldown thì thử bind lại 1 lần
 * (biết đâu Shizuku đã khởi động lại đúng cách). Bind thành công hoặc
 * unbindService() thì [clear] để lần lỗi sau lại thử bind bình thường.
 *
 * Chỉ cache lỗi BIND (ShizukuBindException) — lỗi khác (lệnh đã chạy rồi
 * exit != 0, RemoteException giữa chừng) không ghi vào đây; binder chết
 * giữa chừng vẫn bind lại như cũ ở ShizukuBridge.
 *
 * Thuần JVM (không import Android) nên unit test local được; `clock` tiêm
 * vào để test không phải chờ thật.
 */
class BindFailureCache(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** 5 phút: đủ cho 1 lần khởi động Server + thăm dò, ngắn để Shizuku
         *  khởi động lại đúng cách thì lần thử sau không phải chờ lâu. */
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L
    }

    @Volatile
    private var lastFailureMs: Long = -1L

    /** Bind có vừa lỗi gần đây (trong cooldown) không — true thì bỏ qua bind. */
    fun shouldSkipBind(nowMs: Long = clock()): Boolean {
        val last = lastFailureMs
        return last >= 0 && nowMs - last < cooldownMs
    }

    /** Ghi lại 1 lần bind lỗi (chỉ gọi khi lỗi đúng là ShizukuBindException). */
    fun recordFailure(nowMs: Long = clock()) {
        lastFailureMs = nowMs
    }

    /** Bind thành công / unbind: xóa cache, lần lỗi sau lại thử bind bình thường. */
    fun clear() {
        lastFailureMs = -1L
    }
}

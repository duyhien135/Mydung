package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    // Token là 32 ký tự hex — redactSecrets phải che đúng nó trong log
    // (log Server ở /data/local/tmp, app khác đọc được).
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun redactSecrets_masksTokenInLogLine() {
        val line = "Lệnh khởi động: app_process com.example.mydung.server.Server 45678 $token /data/app/x/base.apk 4"
        val redacted = Config.redactSecrets(line)
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("***"))
        // Phần còn lại giữ nguyên (cổng, đường dẫn APK).
        assertTrue(redacted.contains("45678"))
        assertTrue(redacted.contains("/data/app/x/base.apk"))
    }

    @Test
    fun redactSecrets_masksBareToken() {
        assertEquals("***", Config.redactSecrets(token))
    }

    @Test
    fun redactSecrets_leavesNormalTextUntouched() {
        // Chuỗi hex ngắn hơn 32 ký tự không phải token -> giữ nguyên.
        assertEquals("pid=12345 uid=2000", Config.redactSecrets("pid=12345 uid=2000"))
        assertEquals("Server đã khởi động OK.", Config.redactSecrets("Server đã khởi động OK."))
        // Fingerprint có dấu ':' xen giữa -> không khớp mẫu 32 hex liền nhau.
        assertEquals("Fingerprint=AA:BB:CC", Config.redactSecrets("Fingerprint=AA:BB:CC"))
    }
}

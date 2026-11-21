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

    @Test
    fun pollIntervalMs_rates() {
        assertEquals(16L, Config.pollIntervalMs(60))
        assertEquals(8L, Config.pollIntervalMs(125))
        assertEquals(4L, Config.pollIntervalMs(250))
        assertEquals(2L, Config.pollIntervalMs(500))
        assertEquals(1L, Config.pollIntervalMs(1000))
        // 0 = NO LIMIT: không gộp MOVE.
        assertEquals(0L, Config.pollIntervalMs(0))
        // Ngoài dải thì kẹp 1..20ms.
        assertEquals(1L, Config.pollIntervalMs(2000))
    }

    @Test
    fun pollDelayMs_neverZero() {
        // Vòng poll app: tối thiểu 1ms để không quay chặt khi NO LIMIT.
        assertEquals(1L, Config.pollDelayMs(0))
        assertEquals(16L, Config.pollDelayMs(60))
        assertEquals(1L, Config.pollDelayMs(1000))
    }

    // ── Build-11: thang UI 1..100 ──

    @Test
    fun sensFactor_uiToEngine() {
        assertEquals(0.05f, Config.sensFactor(1), 0.001f)
        assertEquals(1.0f, Config.sensFactor(20), 0.001f)
        assertEquals(5.0f, Config.sensFactor(100), 0.001f)
        // Ngoài thang thì kẹp.
        assertEquals(0.05f, Config.sensFactor(0), 0.001f)
        assertEquals(5.0f, Config.sensFactor(200), 0.001f)
    }

    @Test
    fun sensUiValue_engineToUi() {
        assertEquals(20, Config.sensUiValue(1.0f))
        assertEquals(2, Config.sensUiValue(0.1f))
        assertEquals(100, Config.sensUiValue(5.0f))
        assertEquals(50, Config.sensUiValue(2.5f))
    }

    @Test
    fun dampLevel_uiToEngine() {
        assertEquals(1.0f, Config.dampLevel(1), 0.001f)
        assertEquals(2.0f, Config.dampLevel(12), 0.001f)
        assertEquals(10.0f, Config.dampLevel(100), 0.001f)
    }

    @Test
    fun dampAlpha_uiToEma() {
        assertEquals(1.0f, Config.dampAlpha(1), 0.001f) // mức 1 = không làm mượt
        assertEquals(0.5f, Config.dampAlpha(12), 0.001f)
        assertEquals(0.1f, Config.dampAlpha(100), 0.001f) // đầm nhất
    }

    @Test
    fun dampUiValue_oldLevelToUi() {
        assertEquals(1, Config.dampUiValue(1))
        assertEquals(45, Config.dampUiValue(5))
        assertEquals(100, Config.dampUiValue(10))
    }
}

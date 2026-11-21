package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTest {

    @Test
    fun blockUntrustedTouchesCommand() {
        assertEquals(
            "/system/bin/settings put global block_untrusted_touches 0",
            Shell.blockUntrustedTouchesCommand(off = true),
        )
        assertEquals(
            "/system/bin/settings put global block_untrusted_touches 1",
            Shell.blockUntrustedTouchesCommand(off = false),
        )
    }

    @Test
    fun restoreCommand_usesPreviousValue() {
        assertEquals(
            "/system/bin/settings put global block_untrusted_touches 1",
            Shell.restoreBlockUntrustedTouchesCommand("1"),
        )
        // Giá trị cũ là 0 thì khôi phục về 0 (không ép về 1).
        assertEquals(
            "/system/bin/settings put global block_untrusted_touches 0",
            Shell.restoreBlockUntrustedTouchesCommand("0"),
        )
    }

    @Test
    fun getCommand() {
        assertEquals(
            "/system/bin/settings get global block_untrusted_touches",
            Shell.getBlockUntrustedTouchesCommand(),
        )
    }
}

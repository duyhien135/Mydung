package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShizukuNewProcessTest {

    /** Giả lập class Shizuku: newProcess private static, 3 tham số. */
    @Suppress("unused")
    class FakeShizuku {
        companion object {
            @JvmStatic
            private fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Any? = null
        }
    }

    @Suppress("unused")
    class FakeWithoutNewProcess {
        companion object {
            @JvmStatic
            private fun otherMethod(cmd: Array<String>): Any? = null
        }
    }

    /** newProcess sai số tham số (2 thay vì 3) — phải bị bỏ qua. */
    @Suppress("unused")
    class FakeWrongArity {
        companion object {
            @JvmStatic
            private fun newProcess(cmd: Array<String>, env: Array<String>?): Any? = null
        }
    }

    @Test
    fun findNewProcessMethod_findsPrivateStatic3Arg() {
        val m = ShizukuNewProcess.findNewProcessMethod(FakeShizuku::class.java)
        assertNotNull(m)
        assertEquals("newProcess", m!!.name)
        assertEquals(3, m.parameterCount)
    }

    @Test
    fun findNewProcessMethod_returnsNullWhenAbsent() {
        assertNull(ShizukuNewProcess.findNewProcessMethod(FakeWithoutNewProcess::class.java))
    }

    @Test
    fun findNewProcessMethod_ignoresWrongArity() {
        assertNull(ShizukuNewProcess.findNewProcessMethod(FakeWrongArity::class.java))
    }

    @Test
    fun drainProcess_capturesStdoutAndExitCode() {
        val proc = ProcessBuilder("sh", "-c", "echo hello").start()
        val out = ShizukuNewProcess.drainProcess(proc, 5000)
        assertEquals(0, out.exitCode)
        assertEquals("hello\n", out.stdout)
        assertEquals("", out.stderr)
    }

    @Test
    fun drainProcess_capturesStderrAndNonZeroExit() {
        val proc = ProcessBuilder("sh", "-c", "echo out; echo err 1>&2; exit 3").start()
        val out = ShizukuNewProcess.drainProcess(proc, 5000)
        assertEquals(3, out.exitCode)
        assertEquals("out\n", out.stdout)
        assertEquals("err\n", out.stderr)
    }

    @Test
    fun drainProcess_timeoutKillsProcess() {
        val proc = ProcessBuilder("sh", "-c", "sleep 30").start()
        val out = ShizukuNewProcess.drainProcess(proc, 500)
        assertEquals(-1, out.exitCode)
        assertTrue(out.stderr.contains("[timeout after 500ms]"))
    }

    @Test
    fun exec_withoutShizukuClass_throwsDescriptiveError() {
        // Trên JVM thường không có class rikka.shizuku.Shizuku.
        try {
            ShizukuNewProcess.exec("echo hi", 5000)
            fail("exec phải ném NewProcessException khi thiếu class Shizuku")
        } catch (e: ShizukuNewProcess.NewProcessException) {
            assertTrue(e.message!!.contains("rikka.shizuku.Shizuku"))
        }
    }
}

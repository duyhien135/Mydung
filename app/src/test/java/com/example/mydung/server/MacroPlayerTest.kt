package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroPlayerTest {

    private fun player(): Pair<TouchPointerSet, MacroPlayer> {
        // MacroPlayer cần engine + allocId (dải 2..9 như TouchMapper) +
        // kích thước màn hình.
        val engine = TouchPointerSet()
        val p = MacroPlayer(engine, { (2..9).firstOrNull { !engine.isDown(it) } }) { 1000 to 500 }
        return engine to p
    }

    private fun assertPoint(spec: TouchEngine.MotionEventSpec?, id: Int, x: Float, y: Float) {
        val p = spec?.pointers?.single { it.id == id }
        assertEquals(x, p?.x ?: Float.NaN, 0.01f)
        assertEquals(y, p?.y ?: Float.NaN, 0.01f)
    }

    @Test
    fun emptySteps_doesNothing() {
        val (_, p) = player()
        assertTrue(p.start(1, emptyList(), 100).isEmpty())
        assertFalse(p.isPlaying(1))
    }

    @Test
    fun swipeStep_interpolatesAndReleasesNextTick() {
        val (engine, p) = player()
        val steps = listOf(MacroStep(MacroStepKind.SWIPE, 0.3f, 0.5f, 0.7f, 0.5f, 400L))
        val down = p.start(1, steps, 100)
        assertEquals(1, down.size)
        assertPoint(down[0], 2, 300f, 250f)
        // Nửa thời gian: vị trí giữa đường, chưa nhả.
        assertTrue(p.tick(300).isEmpty())
        val mid = engine.flushMove(300)
        assertPoint(mid, 2, 500f, 250f)
        // Hết 400ms: đặt đúng điểm cuối, tick này CHƯA nhả.
        assertTrue(p.tick(500).isEmpty())
        val end = engine.flushMove(500)
        assertPoint(end, 2, 700f, 250f)
        // Tick sau mới nhả.
        val up = p.tick(520)
        assertEquals(1, up.size)
        assertEquals(TouchEngine.ACTION_UP, up[0].action)
        assertFalse(p.isPlaying(1))
    }

    @Test
    fun waitZero_advancesImmediately() {
        val (_, p) = player()
        val steps = listOf(
            MacroStep(MacroStepKind.WAIT, ms = 0L),
            MacroStep(MacroStepKind.TAP, 0.5f, 0.5f),
        )
        // WAIT 0ms thì sang bước sau ngay trong lần start.
        val specs = p.start(1, steps, 100)
        assertEquals(1, specs.size)
        assertPoint(specs[0], 2, 500f, 250f)
    }

    @Test
    fun cancelAll_releasesAllFingers() {
        val (engine, p) = player()
        p.start(1, listOf(MacroStep(MacroStepKind.TAP, 0.5f, 0.5f)), 100)
        p.start(2, listOf(MacroStep(MacroStepKind.TAP, 0.8f, 0.8f)), 100)
        assertEquals(2, engine.count)
        val ups = p.cancelAll(110)
        assertEquals(2, ups.size)
        assertEquals(0, engine.count)
    }

    @Test
    fun clear_dropsPlaybackWithoutReleasing() {
        // clear() chỉ dùng khi caller CANCEL toàn bộ engine ngay sau đó:
        // ngón vẫn còn trong engine (caller lo nhả), nhưng macro không chạy tiếp.
        val (engine, p) = player()
        p.start(1, listOf(MacroStep(MacroStepKind.TAP, 0.5f, 0.5f)), 100)
        p.clear()
        assertFalse(p.isPlaying(1))
        assertTrue(p.tick(200).isEmpty())
        assertEquals(1, engine.count) // ngón vẫn đè — caller phải CANCEL
    }
}

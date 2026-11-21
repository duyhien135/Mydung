package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchEngineTest {

    private val DOWN = TouchEngine.ACTION_DOWN
    private val UP = TouchEngine.ACTION_UP
    private val MOVE = TouchEngine.ACTION_MOVE
    private val CANCEL = TouchEngine.ACTION_CANCEL
    private val PDOWN = TouchEngine.ACTION_POINTER_DOWN
    private val PUP = TouchEngine.ACTION_POINTER_UP
    private val SHIFT = TouchEngine.ACTION_POINTER_INDEX_SHIFT

    @Test
    fun singleFinger_downMoveUp() {
        val s = TouchPointerSet()
        val down = s.press(0, 10f, 20f, 100L)
        assertEquals(DOWN, down?.action)
        assertEquals(listOf(TouchEngine.Pointer(0, 10f, 20f)), down?.pointers)
        assertEquals(100L, down?.downTime)

        s.setPosition(0, 12f, 22f)
        val move = s.flushMove(150L)
        assertEquals(MOVE, move?.action)
        assertEquals(listOf(TouchEngine.Pointer(0, 12f, 22f)), move?.pointers)
        assertEquals(100L, move?.downTime) // downTime không đổi suốt cử chỉ

        val up = s.release(0, 200L)
        assertEquals(UP, up?.action)
        assertEquals(listOf(TouchEngine.Pointer(0, 12f, 22f)), up?.pointers)
        assertEquals(0, s.count)
    }

    @Test
    fun twoFingers_pointerDownUpIndexes() {
        val s = TouchPointerSet()
        s.press(0, 0f, 0f, 100L)
        val pDown = s.press(1, 10f, 10f, 110L)
        assertEquals(PDOWN or (1 shl SHIFT), pDown?.action)
        assertEquals(listOf(0, 1), pDown?.pointers?.map { it.id })
        assertEquals(100L, pDown?.downTime)

        // Nhả ngón 1: mảng VẪN chứa ngón sắp nhả, chỉ số 1.
        val pUp = s.release(1, 120L)
        assertEquals(PUP or (1 shl SHIFT), pUp?.action)
        assertEquals(listOf(0, 1), pUp?.pointers?.map { it.id })

        val up = s.release(0, 130L)
        assertEquals(UP, up?.action)
        assertEquals(listOf(0), up?.pointers?.map { it.id })
    }

    @Test
    fun threeFingers_releaseOutOfOrder() {
        val s = TouchPointerSet()
        s.press(0, 0f, 0f, 100L)
        s.press(1, 1f, 1f, 101L)
        s.press(2, 2f, 2f, 102L)

        // Nhả ngón giữa (id 1, chỉ số 1 trong mảng [0,1,2]).
        val up1 = s.release(1, 110L)
        assertEquals(PUP or (1 shl SHIFT), up1?.action)
        assertEquals(listOf(0, 1, 2), up1?.pointers?.map { it.id })

        // Nhả id 2: mảng còn [0,2], chỉ số của 2 là 1.
        val up2 = s.release(2, 120L)
        assertEquals(PUP or (1 shl SHIFT), up2?.action)
        assertEquals(listOf(0, 2), up2?.pointers?.map { it.id })

        // Ngón cuối -> ACTION_UP.
        val up0 = s.release(0, 130L)
        assertEquals(UP, up0?.action)
        assertEquals(0, s.count)
    }

    @Test
    fun pointerDown_indexIsSortedPosition() {
        val s = TouchPointerSet()
        s.press(5, 0f, 0f, 100L) // DOWN
        // Ngón 2 thêm vào: mảng sắp xếp [2,5], chỉ số của 2 là 0.
        val pDown = s.press(2, 1f, 1f, 101L)
        assertEquals(PDOWN or (0 shl SHIFT), pDown?.action)
        assertEquals(listOf(2, 5), pDown?.pointers?.map { it.id })
    }

    @Test
    fun cancel_clearsAllAndResetsDownTime() {
        val s = TouchPointerSet()
        s.press(0, 0f, 0f, 100L)
        s.press(1, 1f, 1f, 101L)
        val cancel = s.cancelAll(120L)
        assertEquals(CANCEL, cancel?.action)
        assertEquals(listOf(0, 1), cancel?.pointers?.map { it.id })
        assertEquals(0, s.count)
        // Cử chỉ mới bắt đầu downTime mới.
        val down = s.press(0, 0f, 0f, 500L)
        assertEquals(500L, down?.downTime)
        // Hủy cử chỉ mới rồi hủy tiếp -> null vì đã hết ngón.
        assertEquals(CANCEL, s.cancelAll(600L)?.action)
        assertNull(s.cancelAll(700L))
    }

    @Test
    fun cancel_emptyReturnsNull() {
        assertNull(TouchPointerSet().cancelAll(100L))
    }

    @Test
    fun pressExistingId_updatesPositionNoEvent() {
        val s = TouchPointerSet()
        s.press(0, 0f, 0f, 100L)
        assertNull(s.press(0, 5f, 5f, 110L))
        val move = s.flushMove(120L)
        assertEquals(MOVE, move?.action)
        assertEquals(TouchEngine.Pointer(0, 5f, 5f), move?.pointers?.single())
    }

    @Test
    fun releaseUnknownId_returnsNull() {
        val s = TouchPointerSet()
        s.press(0, 0f, 0f, 100L)
        assertNull(s.release(7, 110L))
        assertEquals(1, s.count)
    }

    @Test
    fun flushMove_noChangeReturnsNull() {
        val s = TouchPointerSet()
        s.press(0, 0f, 0f, 100L)
        assertNull(s.flushMove(110L)) // press đã xóa dirty
        s.setPosition(0, 1f, 1f)
        assertTrue(s.flushMove(120L) != null)
        assertNull(s.flushMove(130L)) // đã flush rồi
    }

    @Test
    fun maxPointers_enforced() {
        val s = TouchPointerSet()
        for (id in 0 until TouchEngine.MAX_POINTERS) {
            assertTrue(s.press(id, 0f, 0f, 100L + id) != null)
        }
        assertNull(s.press(99, 0f, 0f, 200L))
        assertEquals(TouchEngine.MAX_POINTERS, s.count)
    }
}

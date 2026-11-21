package com.example.mydung.server

/**
 * Chạy chuỗi bước Macro (build-9): 1 phím chạy 1 chuỗi TAP/WAIT/SWIPE theo
 * thứ tự. Thuần logic (thời gian do caller truyền) nên unit test được.
 *
 * - TAP: DOWN ở (x, y), giữ Config.MACRO_TAP_HOLD_MS rồi UP.
 * - WAIT: chờ ms rồi sang bước sau (ms = 0 thì sang ngay).
 * - SWIPE: DOWN ở (x, y), trượt tới (x2, y2) trong ms, tới điểm cuối thì
 *   đặt đúng điểm cuối rồi tick sau mới UP (giống vuốt của TouchMapper —
 *   MOVE tới điểm cuối phải được tiêm trước UP).
 *
 * Bấm phím lại trong lúc đang chạy thì chạy lại từ đầu; nhả phím thì hủy
 * ngay (nhả ngón đang đè). Hết ngón (id 2..9 bận) thì bỏ bước đó, chạy tiếp
 * bước sau — macro không bao giờ kẹt chờ ngón.
 *
 * Build-12: 1 phím gán được cho NHIỀU macro (gán trùng cho phép) — mỗi phím
 * giữ 1 DANH SÁCH playback, bấm phím thì chạy tất cả cùng lúc.
 */
class MacroPlayer(
    private val engine: TouchPointerSet,
    private val allocId: () -> Int?,
    private val displaySize: () -> Pair<Int, Int>,
) {
    private enum class Phase { NEXT, TAP_HOLD, WAIT, SWIPE, SWIPE_END }

    private data class Playback(
        val code: Int,
        val steps: List<MacroStep>,
        var index: Int = 0,
        var phase: Phase = Phase.NEXT,
        var pointerId: Int? = null,
        var untilMs: Long = 0L, // TAP_HOLD: lúc nhả tap; WAIT: lúc hết chờ
        var swipeStartMs: Long = 0L,
        var lastInterpMs: Long = 0L,
        var finished: Boolean = false, // chạy hết chuỗi -> tick gỡ khỏi danh sách
    )

    // Build-12: 1 phím -> NHIỀU playback (mỗi phần tử MACRO gán cùng phím
    // là 1 playback riêng, chạy song song).
    private val playbacks = mutableMapOf<Int, MutableList<Playback>>()

    fun isPlaying(code: Int): Boolean = playbacks[code]?.isNotEmpty() == true

    /** Bắt đầu macro cho [code] (bấm phím). Macro cũ cùng phím thì hủy rồi chạy lại từ đầu. */
    fun start(code: Int, steps: List<MacroStep>, nowMs: Long): List<TouchEngine.MotionEventSpec> =
        startAll(code, listOf(steps), nowMs)

    /**
     * Bắt đầu TẤT CẢ macro gán cho [code] (bấm phím/lăn chuột). Hủy mọi
     * playback cũ cùng phím rồi chạy lại từ đầu từng cái một.
     */
    fun startAll(code: Int, stepLists: List<List<MacroStep>>, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        out.addAll(cancel(code, nowMs))
        val nonEmpty = stepLists.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return out
        val list = playbacks.getOrPut(code) { ArrayList() }
        for (steps in nonEmpty) {
            val pb = Playback(code, steps)
            list.add(pb)
            out.addAll(advance(pb, nowMs))
        }
        // Macro toàn WAIT 0ms có thể xong ngay trong lần advance đầu.
        list.removeAll { it.finished }
        if (list.isEmpty()) playbacks.remove(code)
        return out
    }

    /** Hủy mọi macro của [code] (nhả phím / tắt MAP): nhả ngón đang đè (nếu có). */
    fun cancel(code: Int, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        val list = playbacks.remove(code) ?: return out
        for (pb in list) {
            val id = pb.pointerId
            if (id != null) {
                engine.release(id, nowMs)?.let { out.add(it) }
            }
        }
        return out
    }

    fun cancelAll(nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        for (code in playbacks.keys.toList()) out.addAll(cancel(code, nowMs))
        return out
    }

    /**
     * Xóa mọi macro đang chạy KHÔNG nhả ngón — chỉ dùng khi caller sẽ
     * CANCEL toàn bộ engine ngay sau đó (TouchMapper.reset). Nhả riêng ở
     * đây mà bỏ đặc tả UP thì ngón kẹt trên máy (engine đã quên ngón đó
     * nên CANCEL sau đó không bao gồm nó).
     */
    fun clear() {
        playbacks.clear()
    }

    /** Tiến triển mọi macro đang chạy (TouchHub gọi mỗi vòng worker qua TouchMapper.tick). */
    fun tick(nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        for ((code, list) in playbacks.toMap()) {
            val it = list.iterator()
            while (it.hasNext()) {
                val pb = it.next()
                out.addAll(advance(pb, nowMs))
                if (pb.finished) it.remove()
            }
            if (list.isEmpty()) playbacks.remove(code)
        }
        return out
    }

    /**
     * Tiến triển 1 macro: tối đa 100 lần đổi bước trong 1 lần gọi (chống
     * vòng lặp vô hạn với chuỗi toàn WAIT 0ms). Bước TAP/WAIT hết hạn thì
     * sang bước sau ngay trong lần gọi này; bước SWIPE tới điểm cuối thì
     * dừng lại, lần gọi sau mới UP.
     */
    private fun advance(pb: Playback, nowMs: Long): List<TouchEngine.MotionEventSpec> {
        val out = ArrayList<TouchEngine.MotionEventSpec>()
        var guard = 0
        while (guard++ < 100) {
            when (pb.phase) {
                Phase.NEXT -> {
                    if (pb.index >= pb.steps.size) {
                        pb.finished = true
                        return out
                    }
                    val step = pb.steps[pb.index]
                    val (w, h) = displaySize()
                    when (step.kind) {
                        MacroStepKind.TAP -> {
                            val id = allocId()
                            if (id == null) {
                                pb.index++
                                continue // hết ngón: bỏ bước tap
                            }
                            pb.pointerId = id
                            engine.press(id, step.x * w, step.y * h, nowMs)?.let { out.add(it) }
                            pb.phase = Phase.TAP_HOLD
                            pb.untilMs = nowMs + Config.MACRO_TAP_HOLD_MS
                        }
                        MacroStepKind.WAIT -> {
                            pb.phase = Phase.WAIT
                            pb.untilMs = nowMs + step.ms
                        }
                        MacroStepKind.SWIPE -> {
                            val id = allocId()
                            if (id == null) {
                                pb.index++
                                continue // hết ngón: bỏ bước vuốt
                            }
                            pb.pointerId = id
                            engine.press(id, step.x * w, step.y * h, nowMs)?.let { out.add(it) }
                            pb.phase = Phase.SWIPE
                            pb.swipeStartMs = nowMs
                            pb.lastInterpMs = nowMs
                        }
                    }
                }
                Phase.TAP_HOLD -> {
                    if (nowMs < pb.untilMs) return out
                    val id = pb.pointerId
                    if (id != null) engine.release(id, nowMs)?.let { out.add(it) }
                    pb.pointerId = null
                    pb.index++
                    pb.phase = Phase.NEXT
                }
                Phase.WAIT -> {
                    if (nowMs < pb.untilMs) return out
                    pb.index++
                    pb.phase = Phase.NEXT
                }
                Phase.SWIPE -> {
                    val step = pb.steps[pb.index]
                    val dur = step.ms.coerceAtLeast(1L)
                    val elapsed = nowMs - pb.swipeStartMs
                    val (w, h) = displaySize()
                    val id = pb.pointerId
                    if (elapsed >= dur) {
                        // Tới điểm cuối: đặt đúng điểm cuối, lần gọi sau mới
                        // UP (MOVE tới điểm cuối được tiêm ở vòng worker này,
                        // UP ở vòng sau — không thì UP mang vị trí cũ).
                        if (id != null) engine.setPosition(id, step.x2 * w, step.y2 * h)
                        pb.phase = Phase.SWIPE_END
                        return out
                    }
                    if (nowMs - pb.lastInterpMs >= Config.MOVE_MIN_INTERVAL_MS) {
                        pb.lastInterpMs = nowMs
                        val f = (elapsed.toFloat() / dur).coerceIn(0f, 1f)
                        if (id != null) {
                            val x = (step.x + (step.x2 - step.x) * f) * w
                            val y = (step.y + (step.y2 - step.y) * f) * h
                            engine.setPosition(id, x, y)
                        }
                    }
                    return out
                }
                Phase.SWIPE_END -> {
                    val id = pb.pointerId
                    if (id != null) engine.release(id, nowMs)?.let { out.add(it) }
                    pb.pointerId = null
                    pb.index++
                    pb.phase = Phase.NEXT
                }
            }
        }
        return out
    }
}

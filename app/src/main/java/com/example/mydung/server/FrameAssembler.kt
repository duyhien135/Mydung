package com.example.mydung.server

/**
 * Gom sự kiện thô của 1 thiết bị thành InputEvent theo khung EV_SYN.
 * Mỗi thiết bị có 1 FrameAssembler riêng (giữ trong DeviceReader).
 */
class FrameAssembler {
    private var dx = 0
    private var dy = 0
    private var wheelV = 0
    private var wheelH = 0
    private val pressedKeys = mutableSetOf<Int>()
    private val pressedButtons = mutableSetOf<Int>()

    /** Đưa 1 RawEvent vào; sự kiện sinh ra (nếu có) được đẩy qua emit. */
    fun feed(e: RawEvent, emit: (InputEvent) -> Unit) {
        when (e.type) {
            InputEventParser.EV_SYN -> flush(e.timeMs, emit)
            InputEventParser.EV_KEY -> {
                if (e.value == 2) return // bỏ qua lặp phím
                val pressed = e.value == 1
                if (e.code in 0x110..0x117) { // nút chuột BTN_LEFT..BTN_TASK
                    if (pressed) pressedButtons.add(e.code) else pressedButtons.remove(e.code)
                    emit(InputEvent.Button(e.code, pressed, e.timeMs))
                } else {
                    if (pressed) pressedKeys.add(e.code) else pressedKeys.remove(e.code)
                    emit(InputEvent.Key(e.code, pressed, e.timeMs))
                }
            }
            InputEventParser.EV_REL -> when (e.code) {
                InputEventParser.REL_X -> dx += e.value
                InputEventParser.REL_Y -> dy += e.value
                InputEventParser.REL_WHEEL -> wheelV += e.value
                InputEventParser.REL_HWHEEL -> wheelH += e.value
            }
        }
    }

    /** Hết 1 khung: gộp dx/dy (và cuộn) trong khung thành 1 sự kiện. Không gộp qua khung. */
    private fun flush(timeMs: Long, emit: (InputEvent) -> Unit) {
        if (dx != 0 || dy != 0) {
            emit(InputEvent.MouseMove(dx, dy, timeMs))
            dx = 0
            dy = 0
        }
        if (wheelV != 0 || wheelH != 0) {
            emit(InputEvent.Wheel(wheelV, wheelH, timeMs))
            wheelV = 0
            wheelH = 0
        }
    }

    /** Thiết bị bị rút: coi như mọi phím/nút đã nhả, phát sự kiện nhả giả. */
    fun releaseAll(timeMs: Long, emit: (InputEvent) -> Unit) {
        pressedKeys.forEach { emit(InputEvent.Key(it, false, timeMs)) }
        pressedButtons.forEach { emit(InputEvent.Button(it, false, timeMs)) }
        pressedKeys.clear()
        pressedButtons.clear()
        dx = 0
        dy = 0
        wheelV = 0
        wheelH = 0
    }
}

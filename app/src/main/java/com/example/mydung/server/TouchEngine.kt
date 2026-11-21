package com.example.mydung.server

/**
 * Logic thuần quản lý bộ ngón cảm ứng và sinh "đặc tả" MotionEvent để tiêm.
 * Không phụ thuộc Android: lớp Injector mới biến đặc tả thành MotionEvent thật.
 *
 * Quy tắc MotionEvent (android.view.MotionEvent, ổn định từ API 1):
 * - Ngón đầu tiên DOWN -> ACTION_DOWN; các ngón sau -> ACTION_POINTER_DOWN |
 *   (chỉ_số << ACTION_POINTER_INDEX_SHIFT).
 * - Nhả ngón khi còn ngón khác -> ACTION_POINTER_UP | (chỉ_số << 8), mảng
 *   VẪN chứa ngón sắp nhả; nhả ngón cuối -> ACTION_UP, mảng chỉ còn ngón đó.
 * - ACTION_MOVE chứa TẤT CẢ ngón đang đè trong 1 sự kiện.
 * - downTime giữ nguyên suốt 1 chuỗi cử chỉ (từ DOWN đầu đến UP cuối).
 * - ACTION_CANCEL chứa toàn bộ ngón, dùng khi tắt MAP / thoát khẩn cấp.
 *
 * Hằng số action dưới đây copy giá trị từ android.view.MotionEvent để file
 * này biên dịch được mà không cần android.jar (unit test chạy trên JVM thuần).
 */
object TouchEngine {

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6
    const val ACTION_POINTER_INDEX_SHIFT = 8

    /** id ngón: 0 = joystick, 1 = camera, 2..9 = các nút / chạm test. */
    const val POINTER_JOYSTICK = 0
    const val POINTER_CAMERA = 1
    const val BUTTON_ID_FIRST = 2
    const val BUTTON_ID_LAST = 9
    const val MAX_POINTERS = 10

    /** 1 ngón trong đặc tả: id ổn định + tọa độ pixel. */
    data class Pointer(val id: Int, val x: Float, val y: Float)

    /**
     * Đặc tả 1 MotionEvent để tiêm: mã action (đã gồm chỉ số ngón nếu có),
     * mảng ngón (sắp xếp theo id), downTime/eventTime (uptime ms).
     */
    data class MotionEventSpec(
        val action: Int,
        val pointers: List<Pointer>,
        val downTime: Long,
        val eventTime: Long,
    )
}

/**
 * Máy trạng thái ngón. Mỗi phương thức trả về đặc tả cần tiêm, hoặc null
 * nếu không có gì để tiêm. eventTime do caller truyền (uptime ms).
 */
class TouchPointerSet {
    private val pointers = sortedMapOf<Int, TouchEngine.Pointer>()
    private var downTime = 0L
    private var dirty = false

    val count: Int get() = pointers.size
    fun isDown(id: Int): Boolean = pointers.containsKey(id)
    fun position(id: Int): TouchEngine.Pointer? = pointers[id]

    /** Nhấn ngón mới. Ngón đã đè thì chỉ cập nhật vị trí, không sinh sự kiện. */
    fun press(id: Int, x: Float, y: Float, eventTime: Long): TouchEngine.MotionEventSpec? {
        if (pointers.containsKey(id)) {
            setPosition(id, x, y)
            return null
        }
        if (pointers.size >= TouchEngine.MAX_POINTERS) return null
        pointers[id] = TouchEngine.Pointer(id, x, y)
        val list = pointers.values.toList()
        return if (list.size == 1) {
            downTime = eventTime
            dirty = false
            TouchEngine.MotionEventSpec(TouchEngine.ACTION_DOWN, list, downTime, eventTime)
        } else {
            val index = list.indexOfFirst { it.id == id }
            dirty = false
            TouchEngine.MotionEventSpec(
                TouchEngine.ACTION_POINTER_DOWN or (index shl TouchEngine.ACTION_POINTER_INDEX_SHIFT),
                list, downTime, eventTime,
            )
        }
    }

    /** Cập nhật vị trí ngón đang đè (không sinh sự kiện ngay; flushMove sẽ gộp). */
    fun setPosition(id: Int, x: Float, y: Float) {
        if (!pointers.containsKey(id)) return
        pointers[id] = TouchEngine.Pointer(id, x, y)
        dirty = true
    }

    /** Gộp MOVE: nếu có vị trí đổi từ lần MOVE trước thì sinh 1 ACTION_MOVE. */
    fun flushMove(eventTime: Long): TouchEngine.MotionEventSpec? {
        if (!dirty || pointers.isEmpty()) return null
        dirty = false
        return TouchEngine.MotionEventSpec(
            TouchEngine.ACTION_MOVE, pointers.values.toList(), downTime, eventTime,
        )
    }

    /** Nhả 1 ngón. Ngón cuối -> ACTION_UP; còn ngón khác -> ACTION_POINTER_UP. */
    fun release(id: Int, eventTime: Long): TouchEngine.MotionEventSpec? {
        if (!pointers.containsKey(id)) return null
        val list = pointers.values.toList() // mảng VẪN chứa ngón sắp nhả
        val spec = if (list.size == 1) {
            TouchEngine.MotionEventSpec(TouchEngine.ACTION_UP, list, downTime, eventTime)
        } else {
            val index = list.indexOfFirst { it.id == id }
            TouchEngine.MotionEventSpec(
                TouchEngine.ACTION_POINTER_UP or (index shl TouchEngine.ACTION_POINTER_INDEX_SHIFT),
                list, downTime, eventTime,
            )
        }
        pointers.remove(id)
        // POINTER_UP/UP đã mang vị trí mới nhất của các ngón còn lại nên
        // không cần MOVE dư sau đó.
        dirty = false
        if (pointers.isEmpty()) {
            downTime = 0L
        }
        return spec
    }

    /** Hủy khẩn: ACTION_CANCEL với toàn bộ ngón, xóa hết trạng thái. */
    fun cancelAll(eventTime: Long): TouchEngine.MotionEventSpec? {
        if (pointers.isEmpty()) return null
        val spec = TouchEngine.MotionEventSpec(
            TouchEngine.ACTION_CANCEL, pointers.values.toList(), downTime, eventTime,
        )
        pointers.clear()
        downTime = 0L
        dirty = false
        return spec
    }
}

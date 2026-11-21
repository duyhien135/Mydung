package com.example.mydung.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Nút nổi trong game (kiểu nút G của GG Mouse Pro): vòng tròn chữ K, viền
 * xanh lá khi MAP đang bật, xám khi tắt.
 *
 * - Bấm 1 cái: bật/tắt keymap (MAP).
 * - Bấm giữ ~0,5s: mở trình sửa keymap.
 * - Kéo: di chuyển nút (vị trí lưu lại cho lần sau).
 *
 * Cử chỉ tự viết bằng awaitEachGesture (không dùng detectTapGestures +
 * detectDragGestures chồng nhau — 2 detector đó tranh nhau consume sự kiện
 * nên kéo hay bị nhận nhầm thành bấm). Luật: long-press chỉ tính khi chưa
 * kéo quá touch slop; tap chỉ tính khi nhả tay mà chưa kéo quá slop và
 * chưa long-press.
 *
 * Cửa sổ chứa nút là WRAP_CONTENT nên vùng nhận chạm chỉ đúng bằng nút,
 * phần còn lại của màn hình game vẫn chạm bình thường.
 */
@Composable
fun FloatingButton(
    mapOn: StateFlow<Boolean>,
    onToggleMap: () -> Unit,
    onOpenEditor: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    val on by mapOn.collectAsState()
    val borderColor = if (on) Color(0xFF4CAF50) else Color(0xFF9E9E9E)

    Box(
        modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val downId = down.id
                    val downTime = down.uptimeMillis
                    var dragTotal = Offset.Zero
                    var longPressed = false
                    while (true) {
                        // Chờ sự kiện tiếp theo, nhưng không quá thời điểm long-press.
                        val elapsed = SystemClock.uptimeMillis() - downTime
                        val remaining = longPressTimeoutMs - elapsed
                        val event = if (!longPressed && remaining > 0) {
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            // Hết thời gian long-press mà chưa kéo quá slop -> giữ.
                            if (dragTotal.distance() < touchSlop) {
                                longPressed = true
                                onOpenEditor()
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == downId }
                            ?: continue
                        // Nhả tay hoặc bị hủy: changedToUpOrCanceled() không có trong BOM này.
                        if (!change.pressed) {
                            if (!longPressed && dragTotal.distance() < touchSlop) {
                                onToggleMap()
                            }
                            break
                        }
                        // Chỉ xử lý sự kiện Move (Press của ngón khác thì bỏ qua).
                        if (event.type != PointerEventType.Move) continue
                        // positionChange() không còn là hàm trong BOM này.
                        val drag = change.position - change.previousPosition
                        if (drag != Offset.Zero) {
                            dragTotal += drag
                            if (dragTotal.distance() >= touchSlop) {
                                change.consume()
                                onDrag(drag)
                            }
                        }
                    }
                }
            }
            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
            .border(3.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "K",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
        )
        // Chấm trạng thái góc trên phải: xanh = MAP bật, xám = tắt.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-2).dp, y = 2.dp)
                .size(12.dp)
                .background(borderColor, CircleShape)
                .border(1.dp, Color.White, CircleShape),
        )
    }
}

private fun Offset.distance(): Float = kotlin.math.sqrt(x * x + y * y)

/**
 * Chấm con trỏ ảo (chế độ con trỏ): vòng tròn nhỏ trắng mờ, chỉ để nhìn
 * vị trí con trỏ trên màn hình game. Cửa sổ chứa nó đặt NOT_TOUCHABLE nên
 * không nhận chạm, không chặn game.
 */
@Composable
fun CursorDot() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color.White.copy(alpha = 0.25f), CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Color.White, CircleShape),
        )
    }
}

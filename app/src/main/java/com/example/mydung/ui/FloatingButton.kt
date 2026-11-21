package com.example.mydung.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val BUTTON_SIZE = 56.dp
private val MENU_WIDTH = 240.dp
private val MENU_ITEM_HEIGHT = 48.dp
// Chiều cao lớn nhất trong 2 bảng (menu chính 5 mục = 256dp, bảng cài đặt
// ≈ 268dp) — dùng để tính hướng mở menu, tránh tràn màn hình.
private val PANEL_HEIGHT = 272.dp
private val MENU_GAP = 8.dp

/**
 * Nút nổi trong game (kiểu nút G của GG Mouse Pro): vòng tròn chữ K, viền
 * xanh lá khi MAP đang bật, xám khi tắt.
 *
 * - Bấm 1 cái: mở bảng điều khiển (Bật/tắt keymap / Chỉnh nút / đổi chế độ
 *   con trỏ ↔ nhìn tự do / Cài đặt / Mở app). Bấm lại nút K hoặc bấm ra
 *   ngoài thì đóng bảng.
 * - Bấm giữ ~0,5s: mở thẳng trình sửa keymap.
 * - Kéo: di chuyển nút (vị trí lưu lại cho lần sau). Không kéo được khi
 *   bảng đang mở — đóng bảng trước rồi kéo.
 *
 * Bảng Cài đặt (mục "⚙️ Cài đặt" trong bảng chính): độ nhạy chuột, kích
 * thước con trỏ, độ trong suốt logo. Không có "hiển thị điểm tiếp xúc"
 * (Server không báo tọa độ ngón về app) và "tần số lấy mẫu" (sự kiện đến
 * theo tốc độ thiết bị evdev, không có núm chỉnh thật).
 *
 * Mọi bảng render INLINE trong cùng ComposeView của lớp phủ (TUYỆT ĐỐI
 * KHÔNG dùng Dialog/AlertDialog/PopupWindow dạng window riêng: cửa sổ
 * overlay của Service không có token Activity hợp lệ nên dialog riêng
 * crash BadTokenException). Khi bảng mở, Service đổi cửa sổ sang
 * MATCH_PARENT (qua [onMenuOpenChange]) để bảng có chỗ hiện; khi đóng thì
 * về WRAP_CONTENT như cũ.
 *
 * Cử chỉ tự viết bằng awaitEachGesture (không dùng detectTapGestures +
 * detectDragGestures chồng nhau — 2 detector đó tranh nhau consume sự kiện
 * nên kéo hay bị nhận nhầm thành bấm). Luật: long-press chỉ tính khi chưa
 * kéo quá touch slop; tap chỉ tính khi nhả tay mà chưa kéo quá slop và
 * chưa long-press.
 *
 * Cửa sổ chứa nút là WRAP_CONTENT khi bảng đóng nên vùng nhận chạm chỉ đúng
 * bằng nút, phần còn lại của màn hình game vẫn chạm bình thường.
 */
@Composable
fun FloatingButton(
    mapOn: StateFlow<Boolean>,
    cursorMode: StateFlow<Boolean>,
    buttonPosPx: StateFlow<Pair<Int, Int>>,
    logoAlpha: StateFlow<Float>,
    sensitivity: StateFlow<Float>,
    cursorDotSizeDp: StateFlow<Int>,
    onToggleMap: () -> Unit,
    onToggleCursorMode: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenApp: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onCursorDotSizeChange: (Int) -> Unit,
    onLogoAlphaChange: (Float) -> Unit,
    onDrag: (Offset) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val on by mapOn.collectAsState()
    val cursor by cursorMode.collectAsState()
    val (btnX, btnY) = buttonPosPx.collectAsState().value
    val alpha by logoAlpha.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        showSettings = false // lần mở sau lại về bảng chính
        onMenuOpenChange(false)
    }

    fun toggleMenu() {
        menuOpen = !menuOpen
        if (!menuOpen) showSettings = false
        onMenuOpenChange(menuOpen)
    }

    val borderColor = if (on) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    val density = LocalDensity.current

    // Bảng đóng: gói gọn theo nút (56dp) để cửa sổ WRAP_CONTENT; bảng mở:
    // toàn màn hình để lớp nền bấm-ra-ngoài + bảng có chỗ hiện.
    BoxWithConstraints(
        modifier = if (menuOpen) Modifier.fillMaxSize() else Modifier.wrapContentSize(),
    ) {
        // Vị trí bảng (chỉ dùng khi mở): neo vào nút K, mở xuống dưới /
        // sang phải nếu còn đủ chỗ, không thì mở lên trên / sang trái để
        // không tràn màn hình.
        val btnXDp = with(density) { btnX.toDp() }
        val btnYDp = with(density) { btnY.toDp() }
        val openDown = btnYDp + BUTTON_SIZE + MENU_GAP + PANEL_HEIGHT <= maxHeight
        val alignLeft = btnXDp + MENU_WIDTH <= maxWidth
        val menuXDp =
            (if (alignLeft) btnXDp else btnXDp + BUTTON_SIZE - MENU_WIDTH).coerceAtLeast(0.dp)
        val menuYDp =
            (if (openDown) btnYDp + BUTTON_SIZE + MENU_GAP else btnYDp - MENU_GAP - PANEL_HEIGHT)
                .coerceAtLeast(0.dp)

        // Lớp nền bấm ra ngoài để đóng bảng (chỉ khi bảng mở).
        if (menuOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { closeMenu() },
            )
        }

        // Nút K: bảng đóng thì cửa sổ WRAP_CONTENT nên nút ở góc (0,0) của
        // cửa sổ; bảng mở thì cửa sổ toàn màn hình nên nút vẽ lại đúng vị
        // trí đã lưu (buttonPosPx).
        Box(
            modifier = Modifier
                .offset { if (menuOpen) IntOffset(btnX, btnY) else IntOffset.Zero }
                .size(BUTTON_SIZE)
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
                                    closeMenu()
                                    onOpenEditor()
                                }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == downId }
                                ?: continue
                            // Nhả tay hoặc bị hủy: changedToUpOrCanceled() không có trong BOM này.
                            if (!change.pressed) {
                                if (!longPressed && dragTotal.distance() < touchSlop) {
                                    toggleMenu()
                                }
                                break
                            }
                            // Chỉ xử lý sự kiện Move (Press của ngón khác thì bỏ qua).
                            if (event.type != PointerEventType.Move) continue
                            // Bảng đang mở thì không kéo nút (kéo lúc này chỉ
                            // tính là bấm để đóng bảng khi nhả tay).
                            if (menuOpen) continue
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
                .background(Color.Black.copy(alpha = alpha), CircleShape)
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

        // Bảng điều khiển (render inline trong cùng cửa sổ overlay — xem
        // ghi chú ở đầu file, không dùng Dialog).
        if (menuOpen) {
            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(menuXDp.roundToPx(), menuYDp.roundToPx())
                    }
                    .width(MENU_WIDTH)
                    .background(Color(0xFF222222), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(vertical = 8.dp),
            ) {
                if (!showSettings) {
                    MenuItem(if (on) "⏸ Tắt keymap" else "▶ Bật keymap") {
                        closeMenu(); onToggleMap()
                    }
                    MenuItem("✏️ Chỉnh nút") { closeMenu(); onOpenEditor() }
                    MenuItem(if (cursor) "🎥 Nhìn tự do" else "🖱 Chế độ con trỏ") {
                        closeMenu(); onToggleCursorMode()
                    }
                    MenuItem("⚙️ Cài đặt") { showSettings = true }
                    MenuItem("📱 Mở app KeyTouch") { closeMenu(); onOpenApp() }
                } else {
                    SettingsPanel(
                        sensitivity = sensitivity,
                        cursorDotSizeDp = cursorDotSizeDp,
                        logoAlpha = logoAlpha,
                        onBack = { showSettings = false },
                        onSensitivityChange = onSensitivityChange,
                        onCursorDotSizeChange = onCursorDotSizeChange,
                        onLogoAlphaChange = onLogoAlphaChange,
                    )
                }
            }
        }
    }
}

/** 1 mục trong bảng điều khiển: cao 48dp, bấm cả hàng. */
@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ITEM_HEIGHT)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color.White, fontSize = 15.sp)
    }
}

/**
 * Bảng Cài đặt (kiểu "Cài đặt cơ bản" của GG Mouse Pro): 3 thanh trượt.
 * Độ nhạy áp dụng khi nhả tay (mỗi lần áp dụng là 1 lệnh TOUCHCFG qua
 * mạng, kéo mà gửi liên tục thì spam); kích thước con trỏ + trong suốt
 * logo áp dụng ngay khi kéo (chỉ đổi StateFlow trong app).
 */
@Composable
private fun SettingsPanel(
    sensitivity: StateFlow<Float>,
    cursorDotSizeDp: StateFlow<Int>,
    logoAlpha: StateFlow<Float>,
    onBack: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onCursorDotSizeChange: (Int) -> Unit,
    onLogoAlphaChange: (Float) -> Unit,
) {
    val sensValue by sensitivity.collectAsState()
    val dotSizeValue by cursorDotSizeDp.collectAsState()
    val alphaValue by logoAlpha.collectAsState()

    // Giá trị trượt cục bộ: đồng bộ lại khi flow đổi từ nơi khác (vd tab
    // Cấu hình trong app đổi độ nhạy); lúc đang kéo thì flow chưa đổi nên
    // không bị giằng lại.
    var sensSlider by remember { mutableStateOf(sensValue) }
    var dotSlider by remember { mutableStateOf(dotSizeValue.toFloat()) }
    var alphaSlider by remember { mutableStateOf(alphaValue) }
    LaunchedEffect(sensValue) { sensSlider = sensValue }
    LaunchedEffect(dotSizeValue) { dotSlider = dotSizeValue.toFloat() }
    LaunchedEffect(alphaValue) { alphaSlider = alphaValue }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ITEM_HEIGHT)
            .clickable { onBack() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹ Quay lại",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(text = "Cài đặt", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
    SettingSlider(
        label = "Độ nhạy chuột",
        valueText = "%.1f".format(sensSlider),
        value = sensSlider,
        range = 0.1f..5f,
        onValueChange = { sensSlider = it },
        onValueChangeFinished = { onSensitivityChange(sensSlider) },
    )
    SettingSlider(
        label = "Kích thước con trỏ",
        valueText = "${dotSlider.roundToInt()}dp",
        value = dotSlider,
        range = 12f..48f,
        onValueChange = {
            dotSlider = it
            onCursorDotSizeChange(it.roundToInt())
        },
    )
    SettingSlider(
        label = "Độ trong suốt logo",
        valueText = "${(alphaSlider * 100).roundToInt()}%",
        value = alphaSlider,
        range = 0.2f..1f,
        onValueChange = {
            alphaSlider = it
            onLogoAlphaChange(it)
        },
    )
}

/** 1 hàng cài đặt: nhãn + giá trị ở trên, thanh trượt ở dưới. */
@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = Color.White, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = valueText, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
        )
    }
}

private fun Offset.distance(): Float = kotlin.math.sqrt(x * x + y * y)

/**
 * Chấm con trỏ ảo (chế độ con trỏ): vòng tròn nhỏ trắng mờ, chỉ để nhìn
 * vị trí con trỏ trên màn hình game. Cửa sổ chứa nó đặt NOT_TOUCHABLE nên
 * không nhận chạm, không chặn game. Kích thước đọc từ [sizeDp] (đổi trong
 * bảng Cài đặt của nút nổi).
 */
@Composable
fun CursorDot(sizeDp: StateFlow<Int>) {
    val size by sizeDp.collectAsState()
    Box(
        modifier = Modifier
            .size(size.dp)
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

package com.example.mydung.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydung.server.LinuxKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val BUTTON_SIZE = 56.dp
private val MENU_WIDTH = 240.dp
private val MENU_ITEM_HEIGHT = 48.dp
// Bảng chính 6 mục = 304dp; bảng Cài đặt cuộn được, cao tối đa 75% màn hình.
private val MENU_PANEL_HEIGHT = 304.dp
private const val SETTINGS_MAX_HEIGHT_FRACTION = 0.75f
private val MENU_GAP = 8.dp

/**
 * Nút nổi trong game (kiểu nút G của GG Mouse Pro): vòng tròn chữ K, viền
 * xanh lá khi MAP đang bật, xám khi tắt.
 *
 * - Bấm 1 cái: mở bảng điều khiển (Bật/tắt keymap / Chỉnh nút / đổi chế độ
 *   con trỏ ↔ nhìn tự do / Cài đặt / Ẩn lớp phủ / Mở app). Bấm lại nút K
 *   hoặc bấm ra ngoài thì đóng bảng.
 * - Bấm giữ ~0,5s: mở thẳng trình sửa keymap.
 * - Kéo: di chuyển nút (vị trí lưu lại cho lần sau). Không kéo được khi
 *   bảng đang mở — đóng bảng trước rồi kéo.
 *
 * Bảng Cài đặt (mục "⚙️ Cài đặt" trong bảng chính): tốc độ con trỏ, phím
 * ẩn/hiện lớp phủ (đưa lên đầu bảng từ build-11 để dễ thấy), kích thước
 * con trỏ, độ trong suốt logo, và dòng trạng thái GRAB (chẩn đoán lỗi 2
 * con trỏ). Độ nhạy X/Y, đầm tâm X/Y và tần số quét chuột đã chuyển sang
 * bảng chỉnh camera trong trình sửa (build-11). Không có "hiển thị điểm
 * tiếp xúc" (Server không báo tọa độ ngón về app).
 *
 * Mục "🙈 Ẩn lớp phủ": ẩn toàn bộ mọi thứ trên màn hình kể cả nút K (keymap
 * vẫn chạy ngầm); hiện lại bằng phím ẩn/hiện lớp phủ (mặc định F1, gán
 * được trong bảng Cài đặt này) hoặc nút "Hiện lại lớp phủ" trong tab Cài
 * đặt của app.
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
    cursorSpeed: StateFlow<Float>,
    overlayToggleKey: StateFlow<Int>,
    grabMethod: StateFlow<String>,
    grabDetail: StateFlow<String>,
    cursorDotSizeDp: StateFlow<Int>,
    captureRequest: StateFlow<Int?>,
    capturedKey: StateFlow<Pair<Int, Int>?>,
    onToggleMap: () -> Unit,
    onToggleCursorMode: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenApp: () -> Unit,
    onHideOverlay: () -> Unit,
    onCursorSpeedChange: (Float) -> Unit,
    onOverlayKeyChange: (Int) -> Unit,
    onRequestOverlayKeyCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onClearCaptured: () -> Unit,
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
        // không tràn màn hình. Bảng Cài đặt cao tối đa 75% màn hình (cuộn
        // được), bảng chính cao 304dp.
        val btnXDp = with(density) { btnX.toDp() }
        val btnYDp = with(density) { btnY.toDp() }
        val panelHeight =
            if (showSettings) maxHeight * SETTINGS_MAX_HEIGHT_FRACTION else MENU_PANEL_HEIGHT
        val openDown = btnYDp + BUTTON_SIZE + MENU_GAP + panelHeight <= maxHeight
        val alignLeft = btnXDp + MENU_WIDTH <= maxWidth
        val menuXDp =
            (if (alignLeft) btnXDp else btnXDp + BUTTON_SIZE - MENU_WIDTH).coerceAtLeast(0.dp)
        val menuYDp =
            (if (openDown) btnYDp + BUTTON_SIZE + MENU_GAP else btnYDp - MENU_GAP - panelHeight)
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
                    .padding(vertical = 8.dp)
                    .then(
                        if (showSettings) {
                            Modifier.heightIn(max = maxHeight * SETTINGS_MAX_HEIGHT_FRACTION)
                        } else {
                            Modifier
                        },
                    ),
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
                    MenuItem("🙈 Ẩn lớp phủ") { closeMenu(); onHideOverlay() }
                    MenuItem("📱 Mở app H") { closeMenu(); onOpenApp() }
                } else {
                    SettingsPanel(
                        cursorSpeed = cursorSpeed,
                        overlayToggleKey = overlayToggleKey,
                        grabMethod = grabMethod,
                        grabDetail = grabDetail,
                        cursorDotSizeDp = cursorDotSizeDp,
                        logoAlpha = logoAlpha,
                        captureRequest = captureRequest,
                        capturedKey = capturedKey,
                        onBack = { showSettings = false },
                        onCursorSpeedChange = onCursorSpeedChange,
                        onOverlayKeyChange = onOverlayKeyChange,
                        onRequestOverlayKeyCapture = onRequestOverlayKeyCapture,
                        onCancelCapture = onCancelCapture,
                        onClearCaptured = onClearCaptured,
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
 * Bảng Cài đặt (kiểu "Cài đặt cơ bản" của GG Mouse Pro): phím ẩn/hiện lớp
 * phủ (đưa lên ĐẦU bảng từ build-11 — trước đây nằm sâu dưới nhiều thanh
 * trượt nên người dùng không tìm thấy), tốc độ con trỏ, kích thước con trỏ,
 * độ trong suốt logo, dòng trạng thái GRAB. Độ nhạy X/Y, đầm tâm X/Y và
 * tần số quét chuột đã chuyển sang bảng chỉnh camera trong trình sửa.
 * Tốc độ con trỏ áp dụng khi nhả tay (mỗi lần áp dụng là 1 lệnh TOUCHCFG
 * qua mạng, kéo mà gửi liên tục thì spam); kích thước con trỏ + trong
 * suốt logo áp dụng ngay khi kéo (chỉ đổi StateFlow trong app).
 */
@Composable
private fun SettingsPanel(
    cursorSpeed: StateFlow<Float>,
    overlayToggleKey: StateFlow<Int>,
    grabMethod: StateFlow<String>,
    grabDetail: StateFlow<String>,
    cursorDotSizeDp: StateFlow<Int>,
    logoAlpha: StateFlow<Float>,
    captureRequest: StateFlow<Int?>,
    capturedKey: StateFlow<Pair<Int, Int>?>,
    onBack: () -> Unit,
    onCursorSpeedChange: (Float) -> Unit,
    onOverlayKeyChange: (Int) -> Unit,
    onRequestOverlayKeyCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onClearCaptured: () -> Unit,
    onCursorDotSizeChange: (Int) -> Unit,
    onLogoAlphaChange: (Float) -> Unit,
) {
    val speedValue by cursorSpeed.collectAsState()
    val overlayKeyValue by overlayToggleKey.collectAsState()
    val grabValue by grabMethod.collectAsState()
    val grabDetailValue by grabDetail.collectAsState()
    val dotSizeValue by cursorDotSizeDp.collectAsState()
    val alphaValue by logoAlpha.collectAsState()
    val captureIndex by captureRequest.collectAsState()
    val captured by capturedKey.collectAsState()

    // Giá trị trượt cục bộ: đồng bộ lại khi flow đổi từ nơi khác; lúc đang
    // kéo thì flow chưa đổi nên không bị giằng lại.
    var speedSlider by remember { mutableStateOf(speedValue) }
    var dotSlider by remember { mutableStateOf(dotSizeValue.toFloat()) }
    var alphaSlider by remember { mutableStateOf(alphaValue) }
    LaunchedEffect(speedValue) { speedSlider = speedValue }
    LaunchedEffect(dotSizeValue) { dotSlider = dotSizeValue.toFloat() }
    LaunchedEffect(alphaValue) { alphaSlider = alphaValue }

    // Phím ẩn/hiện lớp phủ vừa bắt được (index -1): áp dụng rồi xóa.
    LaunchedEffect(captured) {
        val c = captured ?: return@LaunchedEffect
        if (c.first == -1) {
            onOverlayKeyChange(c.second)
            onClearCaptured()
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
        // Phím ẩn/hiện lớp phủ — ĐẦU BẢNG để dễ thấy (build-11): gán phím
        // tùy ý (mặc định F1), bấm phím này trong game để ẩn/hiện toàn bộ
        // lớp phủ (keymap vẫn chạy ngầm khi ẩn).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Phím ẩn/hiện lớp phủ", color = Color.White, fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = LinuxKey.name(overlayKeyValue),
                    color = Color(0xFFFFD54F),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (captureIndex == -1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCancelCapture() }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = "Đang chờ bấm phím… (bấm để hủy)",
                        color = Color(0xFFFFD54F),
                        fontSize = 14.sp,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRequestOverlayKeyCapture() }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = "Đổi phím (bấm rồi bấm phím mới)",
                        color = Color(0xFF81C784),
                        fontSize = 14.sp,
                    )
                }
            }
            Text(
                text = "Bấm phím này trong game để ẩn/hiện toàn bộ lớp phủ (keymap vẫn chạy ngầm).",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
        SettingSlider(
            label = "Tốc độ con trỏ",
            valueText = "%.1f".format(speedSlider),
            value = speedSlider,
            range = 0.5f..3f,
            onValueChange = { speedSlider = it },
            onValueChangeFinished = { onCursorSpeedChange(speedSlider) },
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
        // Trạng thái GRAB (chỉ đọc): cách Server đang chiếm chuột/phím.
        // "none" = chưa grab được — con trỏ hệ thống vẫn hiện cùng tam giác
        // con trỏ ảo (xem log Server để biết vì sao).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Grab chuột", color = Color.White, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (grabValue == "none") "không được" else grabValue,
                color = if (grabValue == "none") Color(0xFFEF9A9A) else Color(0xFF81C784),
                fontSize = 14.sp,
            )
        }
        // Build-12: số liệu grab thật + số thiết bị Server thấy (chẩn đoán).
        // "Server không thấy thiết bị nào" = Server không đọc được event
        // (mở fd lỗi hay bị lọc) — đó là gốc của tam giác đứng im + gán
        // phím không ăn.
        if (grabDetailValue.isNotBlank()) {
            Text(
                text = grabDetailValue,
                color = if (grabDetailValue.startsWith("Server không thấy")) {
                    Color(0xFFEF9A9A)
                } else {
                    Color.White.copy(alpha = 0.6f)
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Text(
            text = "Độ nhạy, đầm tâm và tần số quét chuột chỉnh trong trình sửa: " +
                "chạm vào vòng camera rồi bấm \"edit\".",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
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
    steps: Int = 0,
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
            steps = steps,
        )
    }
}

private fun Offset.distance(): Float = kotlin.math.sqrt(x * x + y * y)

/**
 * Con trỏ ảo (chế độ con trỏ): HÌNH TAM GIÁC nhỏ hướng lên trên (build-9,
 * trước là chấm tròn) — đỉnh tam giác là điểm nóng, nằm đúng vị trí con trỏ
 * trên màn hình game. Cửa sổ chứa nó đặt NOT_TOUCHABLE nên không nhận chạm,
 * không chặn game. Kích thước đọc từ [sizeDp] (đổi trong bảng Cài đặt của
 * nút nổi, mặc định 18dp).
 *
 * Build-11: tam giác XANH LÁ đậm + viền trắng (trước: trắng + viền xanh
 * lá đậm) để phân biệt rõ với con trỏ hệ thống màu trắng — người dùng báo
 * ở build-10 là không phân biệt được đâu là chuột app đâu là chuột điện thoại.
 */
@Composable
fun CursorDot(sizeDp: StateFlow<Int>) {
    val size by sizeDp.collectAsState()
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = size.dp.toPx()
        val path = Path().apply {
            moveTo(w / 2f, 0f) // đỉnh trên = điểm nóng
            lineTo(w, w) // góc dưới phải
            lineTo(0f, w) // góc dưới trái
            close()
        }
        drawPath(path, color = Color(0xFF00C853).copy(alpha = 0.95f))
        drawPath(path, color = Color.White, style = Stroke(width = 2.dp.toPx()))
    }
}

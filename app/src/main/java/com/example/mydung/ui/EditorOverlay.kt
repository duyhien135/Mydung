package com.example.mydung.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydung.server.Config
import com.example.mydung.server.ElementType
import com.example.mydung.server.LinuxKey
import com.example.mydung.server.Profile
import com.example.mydung.server.ProfileElement
import com.example.mydung.OverlayService
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Lớp phủ chỉnh nút (GĐ5, làm lại giao diện theo GG Mouse Pro): kéo thả phần
 * tử, thanh trượt đổi cỡ, chạm vào phần tử để chọn, gán phím bằng bàn phím/
 * chuột USB. Nền trong suốt để thấy game phía dưới mà căn vị trí nút theo
 * nút game. Phím là vòng tròn có nhãn nằm đúng vị trí chạm (kiểu GG).
 *
 * Lưu ý: callback kéo dùng rememberUpdatedState để pointerInput (key Unit,
 * chạy 1 lần) luôn thấy danh sách mới nhất — key theo phần tử sẽ làm
 * pointerInput khởi động lại giữa chừng và cắt ngang cú kéo.
 *
 * Tọa độ phần tử lưu theo TỈ LỆ (0-1) trên kích thước THẬT của màn hình
 * (readRealDisplay), không theo kích thước cửa sổ — cửa sổ chỉnh đã đặt cờ
 * FLAG_LAYOUT_NO_LIMITS + tràn tai thỏ nên 2 kích thước này bằng nhau, nhưng
 * lấy kích thước thật vẫn đúng ngay cả khi cửa sổ bị inset.
 */
@Composable
fun EditorOverlay(
    profile: Profile,
    onSave: (Profile) -> Unit,
    onCancel: () -> Unit,
    captureRequest: StateFlow<Int?>,
    capturedKey: StateFlow<Pair<Int, Int>?>,
    onRequestKeyCapture: (Int) -> Unit,
    onClearCaptured: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    var elements by remember { mutableStateOf(profile.elements) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var undoStack by remember { mutableStateOf<List<List<ProfileElement>>>(emptyList()) }
    var warning by remember { mutableStateOf<String?>(null) }
    var sliderUndoPushed by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // Kích thước thật của màn hình (tính cả xoay) làm mốc tỉ lệ 0-1.
    val realDisplay = remember { readRealDisplay(context) }

    val currentElements by rememberUpdatedState(elements)
    val captureIndex by captureRequest.collectAsState()
    val captured by capturedKey.collectAsState()

    fun pushUndo() {
        undoStack = (undoStack + listOf(currentElements)).takeLast(20)
    }

    fun addElement(e: ProfileElement) {
        pushUndo()
        val newIndex = currentElements.size
        elements = currentElements + e
        selected = newIndex
    }

    fun dragElement(i: Int, dxRatio: Float, dyRatio: Float) {
        val list = currentElements
        if (i !in list.indices) return
        elements = list.mapIndexed { j, old ->
            if (j == i) {
                // Vuốt: kéo điểm đầu thì điểm cuối đi theo (giữ nguyên vector vuốt).
                if (old.type == ElementType.SWIPE) {
                    old.copy(
                        x = (old.x + dxRatio).coerceIn(0f, 1f),
                        y = (old.y + dyRatio).coerceIn(0f, 1f),
                        x2 = ((old.x2 ?: old.x + 0.15f) + dxRatio).coerceIn(0f, 1f),
                        y2 = ((old.y2 ?: old.y) + dyRatio).coerceIn(0f, 1f),
                    )
                } else {
                    old.copy(
                        x = (old.x + dxRatio).coerceIn(0f, 1f),
                        y = (old.y + dyRatio).coerceIn(0f, 1f),
                    )
                }
            } else old
        }
    }

    /** Kéo tay cầm điểm cuối của vuốt: chỉ đổi (x2, y2), điểm đầu giữ nguyên. */
    fun dragSwipeEnd(i: Int, dxRatio: Float, dyRatio: Float) {
        val list = currentElements
        if (i !in list.indices) return
        val old = list[i]
        if (old.type != ElementType.SWIPE) return
        elements = list.mapIndexed { j, e ->
            if (j == i) {
                e.copy(
                    x2 = ((e.x2 ?: e.x + 0.15f) + dxRatio).coerceIn(0f, 1f),
                    y2 = ((e.y2 ?: e.y) + dyRatio).coerceIn(0f, 1f),
                )
            } else e
        }
    }

    fun updateElement(i: Int, e: ProfileElement) {
        elements = currentElements.mapIndexed { j, old -> if (j == i) e else old }
    }

    // Áp dụng phím vừa bắt được (chỉ khi index còn đúng phần tử đang chọn).
    LaunchedEffect(captured) {
        val c = captured ?: return@LaunchedEffect
        val (index, code) = c
        if (index !in currentElements.indices || index != selected) {
            onClearCaptured()
            return@LaunchedEffect
        }
        val dup = currentElements.indexOfFirst {
            it.enabled && (it.type == ElementType.BUTTON || it.type == ElementType.SWIPE) &&
                it.keyCode == code
        }
        if (dup >= 0 && dup != index) {
            warning = "Phím ${LinuxKey.name(code)} đã gán cho \"${currentElements[dup].name}\"."
        } else {
            pushUndo()
            elements = currentElements.mapIndexed { i, e ->
                if (i == index) e.copy(keyCode = code) else e
            }
            warning = null
        }
        onClearCaptured()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = realDisplay?.width?.toFloat() ?: constraints.maxWidth.toFloat()
        val screenH = realDisplay?.height?.toFloat() ?: constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        // Phần tử nào trùng vùng nút nổi K (nút nổi 56dp, vị trí đã lưu):
        // chạm tiêm đè lên đó sẽ bị nút nổi nuốt nên cảnh báo để dời đi.
        val floatRatio = remember { OverlayService.savedFloatRatio(context) }
        val floatOverlapNames = remember(elements, screenW, screenH) {
            val fPx = with(density) { 28.dp.toPx() }
            val fRx = fPx / screenW
            val fRy = fPx / screenH
            fun overlaps(x: Float, y: Float, size: Float): Boolean {
                val eRx = size
                val eRy = size * screenW / screenH
                val dx = (x - floatRatio.first) / (fRx + eRx)
                val dy = (y - floatRatio.second) / (fRy + eRy)
                return dx * dx + dy * dy < 1f
            }
            elements.filter { e ->
                overlaps(e.x, e.y, e.size) ||
                    (e.type == ElementType.SWIPE &&
                        overlaps(e.x2 ?: e.x + 0.15f, e.y2 ?: e.y, e.size))
            }.map { it.name }
        }

        // Chạm vào chỗ trống: bỏ chọn.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { selected = null },
        )

        // Đường vuốt (vẽ dưới vòng tròn): đường thẳng + mũi tên ở điểm cuối.
        val hasSwipe = elements.any { it.type == ElementType.SWIPE }
        if (hasSwipe) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (e in elements) {
                    if (e.type != ElementType.SWIPE) continue
                    val x1 = e.x * screenW
                    val y1 = e.y * screenH
                    val x2 = (e.x2 ?: e.x + 0.15f) * screenW
                    val y2 = (e.y2 ?: e.y) * screenH
                    val color = Color(0xFFAB47BC)
                    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 6f)
                    // Đầu mũi tên: 2 đoạn ngắn chéo ở điểm cuối.
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val ux = dx / len
                    val uy = dy / len
                    val head = 28f
                    drawLine(
                        color,
                        Offset(x2, y2),
                        Offset(x2 - head * (ux * 0.866f - uy * 0.5f), y2 - head * (uy * 0.866f + ux * 0.5f)),
                        strokeWidth = 6f,
                    )
                    drawLine(
                        color,
                        Offset(x2, y2),
                        Offset(x2 - head * (ux * 0.866f + uy * 0.5f), y2 - head * (uy * 0.866f - ux * 0.5f)),
                        strokeWidth = 6f,
                    )
                }
            }
        }

        // Các phần tử.
        elements.forEachIndexed { i, e ->
            ElementView(
                element = e,
                selected = selected == i,
                screenW = screenW,
                screenH = screenH,
                onSelect = { selected = i },
                onDragStart = { pushUndo() },
                onDrag = { dx, dy -> dragElement(i, dx / screenW, dy / screenH) },
            )
        }

        // Tay cầm điểm cuối của vuốt (vòng tròn nhỏ tím, kéo để đổi điểm cuối).
        elements.forEachIndexed { i, e ->
            if (e.type != ElementType.SWIPE) return@forEachIndexed
            SwipeEndHandle(
                element = e,
                screenW = screenW,
                screenH = screenH,
                onSelect = { selected = i },
                onDragStart = { pushUndo() },
                onDrag = { dx, dy -> dragSwipeEnd(i, dx / screenW, dy / screenH) },
            )
        }

        // Thanh trên.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chỉnh nút — ${profile.name}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = { showAddMenu = true },
                enabled = captureIndex == null,
            ) { Text("＋ Thêm thành phần", color = Color.White) }
            TextButton(
                onClick = {
                    elements = undoStack.last()
                    undoStack = undoStack.dropLast(1)
                    selected = null
                },
                enabled = undoStack.isNotEmpty() && captureIndex == null,
            ) { Text("↩ Hoàn tác", color = Color.White) }
            Button(onClick = { onSave(profile.copy(elements = currentElements)) }) {
                Text("Lưu")
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onCancel) { Text("Hủy", color = Color.White) }
        }

        // Hộp "Thêm thành phần" (kiểu GG Mouse Pro): Chạm / Joystick / Vuốt / Camera.
        if (showAddMenu) {
            val hasJoystick = currentElements.any { it.type == ElementType.JOYSTICK }
            val hasCamera = currentElements.any { it.type == ElementType.CAMERA }
            AlertDialog(
                onDismissRequest = { showAddMenu = false },
                title = { Text("Thêm thành phần") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Chọn một thành phần để đặt lên keymap.")
                        TextButton(
                            onClick = {
                                showAddMenu = false
                                addElement(
                                    ProfileElement(
                                        ElementType.BUTTON, 0.5f, 0.5f,
                                        Config.DEFAULT_BUTTON_SIZE, null, "Nút mới",
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("👆 Chạm — chạm 1 điểm khi bấm phím", fontSize = 15.sp) }
                        TextButton(
                            onClick = {
                                showAddMenu = false
                                addElement(
                                    ProfileElement(
                                        ElementType.JOYSTICK, Config.JOY_CENTER_X,
                                        Config.JOY_CENTER_Y, Config.JOY_RADIUS, null, "Joystick",
                                    ),
                                )
                            },
                            enabled = !hasJoystick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (hasJoystick) "🕹 Joystick — đã có rồi" else "🕹 Joystick — di chuyển bằng WASD",
                                fontSize = 15.sp,
                            )
                        }
                        TextButton(
                            onClick = {
                                showAddMenu = false
                                addElement(
                                    ProfileElement(
                                        ElementType.CAMERA, Config.CAM_CENTER_X,
                                        Config.CAM_CENTER_Y, Config.CAM_HALF_W, null, "Camera",
                                    ),
                                )
                            },
                            enabled = !hasCamera,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (hasCamera) "🎥 Camera — đã có rồi" else "🎥 Camera — nhìn bằng chuột (kéo vô hạn)",
                                fontSize = 15.sp,
                            )
                        }
                        TextButton(
                            onClick = {
                                showAddMenu = false
                                addElement(
                                    ProfileElement(
                                        ElementType.SWIPE, 0.35f, 0.5f,
                                        Config.DEFAULT_BUTTON_SIZE, null, "Vuốt mới",
                                        enabled = true, x2 = 0.65f, y2 = 0.5f, durationMs = 400L,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("↔ Vuốt — trượt từ điểm đầu tới điểm cuối", fontSize = 15.sp) }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showAddMenu = false }) { Text("Đóng") }
                },
            )
        }

        // Cảnh báo (phím trùng / phần tử trùng vùng nút nổi K).
        if (warning != null || floatOverlapNames.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                warning?.let { w ->
                    Text(
                        text = w,
                        color = Color(0xFFFFB4A2),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (floatOverlapNames.isNotEmpty()) {
                    Text(
                        text = "⚠ ${floatOverlapNames.take(3).joinToString(", ")} trùng vùng nút nổi K — " +
                            "chạm tiêm vào đó có thể bị nút nổi nuốt. Kéo nút K ra chỗ khác (trong game) hoặc dời phần tử.",
                        color = Color(0xFFFFB4A2),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Bảng chỉnh phần tử đang chọn.
        selected?.let { i ->
            val e = elements.getOrNull(i)
            if (e != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = e.name,
                                onValueChange = { updateElement(i, e.copy(name = it)) },
                                label = { Text("Tên") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Bật")
                            Switch(
                                checked = e.enabled,
                                onCheckedChange = {
                                    pushUndo()
                                    updateElement(i, e.copy(enabled = it))
                                },
                            )
                        }
                        if (e.type == ElementType.BUTTON || e.type == ElementType.SWIPE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Phím: " + (e.keyCode?.let { LinuxKey.name(it) } ?: "Chưa gán"),
                                    modifier = Modifier.weight(1f),
                                )
                                if (captureIndex == i) {
                                    OutlinedButton(onClick = onCancelCapture) {
                                        Text("Đang chờ bấm phím… (bấm để hủy)")
                                    }
                                } else {
                                    Button(onClick = { onRequestKeyCapture(i) }) {
                                        Text("Gán phím")
                                    }
                                }
                            }
                        }
                        if (e.type == ElementType.SWIPE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Vuốt", modifier = Modifier.width(48.dp))
                                Slider(
                                    value = e.durationMs.toFloat(),
                                    onValueChange = { v ->
                                        if (!sliderUndoPushed) {
                                            pushUndo()
                                            sliderUndoPushed = true
                                        }
                                        updateElement(i, e.copy(durationMs = v.toLong()))
                                    },
                                    onValueChangeFinished = { sliderUndoPushed = false },
                                    valueRange = 100f..2000f,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${e.durationMs}ms",
                                    modifier = Modifier.width(56.dp),
                                    textAlign = TextAlign.End,
                                )
                            }
                            Text(
                                "Kéo vòng tròn tím ở điểm cuối để đổi hướng/dài vuốt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (e.type == ElementType.CAMERA) {
                            Text(
                                "Vùng chuột kéo camera (nhìn tự do). Chuột ra khỏi vùng thì tự nhấc " +
                                    "rồi chạm lại ở rìa đối diện để kéo tiếp không hết vùng. " +
                                    "Phím ${LinuxKey.name(profile.cursorToggleKey)} chuyển sang chế độ con trỏ để bấm giao diện game.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cỡ", modifier = Modifier.width(32.dp))
                            Slider(
                                value = e.size,
                                onValueChange = { v ->
                                    if (!sliderUndoPushed) {
                                        pushUndo()
                                        sliderUndoPushed = true
                                    }
                                    updateElement(i, e.copy(size = v))
                                },
                                onValueChangeFinished = { sliderUndoPushed = false },
                                valueRange = 0.01f..0.5f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "%.0f%%".format(e.size * 100),
                                modifier = Modifier.width(44.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    pushUndo()
                                    elements = currentElements.filterIndexed { j, _ -> j != i }
                                    selected = null
                                },
                                enabled = captureIndex == null,
                            ) { Text("Xóa") }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { selected = null }) { Text("Xong") }
                        }
                    }
                }
            }
        }
    }
}

/** Vẽ 1 phần tử: joystick = vòng tròn xanh lá, camera = khung vuông xanh dương,
 *  nút/vuốt = vòng tròn nền đen viền xanh dương/tím có nhãn tên phím (kiểu GG). */
@Composable
private fun ElementView(
    element: ProfileElement,
    selected: Boolean,
    screenW: Float,
    screenH: Float,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    // Bán kính hiển thị theo pixel: joystick/camera/nút đều dùng size * chiều rộng.
    val radiusPx = element.size * screenW
    val cx = element.x * screenW
    val cy = element.y * screenH
    val borderColor = if (selected) Color(0xFFFFD54F) else when (element.type) {
        ElementType.JOYSTICK -> Color(0xFF66BB6A)
        ElementType.CAMERA -> Color(0xFF42A5F5)
        ElementType.BUTTON -> Color(0xFF42A5F5)
        ElementType.SWIPE -> Color(0xFFAB47BC)
    }
    val fillColor = when (element.type) {
        ElementType.JOYSTICK -> Color(0xFF4CAF50).copy(alpha = 0.25f)
        ElementType.CAMERA -> Color(0xFF2196F3).copy(alpha = 0.15f)
        ElementType.BUTTON -> Color.Black.copy(alpha = 0.45f)
        ElementType.SWIPE -> Color(0xFF7B1FA2).copy(alpha = 0.35f)
    }
    val shape = if (element.type == ElementType.CAMERA) RoundedCornerShape(8.dp) else CircleShape
    val label = when (element.type) {
        ElementType.BUTTON, ElementType.SWIPE ->
            element.keyCode?.let { LinuxKey.name(it) } ?: element.name
        else -> element.name
    }
    val density = LocalDensity.current
    // Vùng chạm tối thiểu 48dp (dễ chạm hơn vòng tròn nhỏ), vòng tròn vẽ ở
    // giữa nên tâm hiển thị luôn trùng điểm tiêm.
    val minTouchPx = with(density) { 48.dp.toPx() }
    val boxPx = maxOf(radiusPx * 2, minTouchPx)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (cx - boxPx / 2).roundToInt(),
                    (cy - boxPx / 2).roundToInt(),
                )
            }
            .size(with(density) { boxPx.toDp() })
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onSelect() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        onSelect()
                        onDragStart()
                    },
                    onDrag = { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(with(density) { (radiusPx * 2).toDp() })
                .background(fillColor, shape)
                .border(2.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Tay cầm điểm cuối của vuốt: vòng tròn nhỏ tím, kéo để đổi điểm cuối (x2, y2). */
@Composable
private fun SwipeEndHandle(
    element: ProfileElement,
    screenW: Float,
    screenH: Float,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val cx = (element.x2 ?: element.x + 0.15f) * screenW
    val cy = (element.y2 ?: element.y) * screenH
    val boxPx = with(density) { 48.dp.toPx() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (cx - boxPx / 2).roundToInt(),
                    (cy - boxPx / 2).roundToInt(),
                )
            }
            .size(with(density) { boxPx.toDp() })
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onSelect() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        onSelect()
                        onDragStart()
                    },
                    onDrag = { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color(0xFFAB47BC).copy(alpha = 0.6f), CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

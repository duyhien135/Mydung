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
import com.example.mydung.server.MacroStep
import com.example.mydung.server.MacroStepKind
import com.example.mydung.server.Profile
import com.example.mydung.server.ProfileCodec
import com.example.mydung.server.ProfileElement
import com.example.mydung.OverlayService
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Các loại phần tử gán phím được (kiểm tra phím trùng + hàng "Gán phím"). */
private val KEYED_TYPES = setOf(
    ElementType.BUTTON, ElementType.SWIPE, ElementType.AUTO_TAP,
    ElementType.RELEASE_TAP, ElementType.FREE_LOOK, ElementType.MACRO,
)

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
    // Build-9: báo danh sách phần tử hiện tại cho Service (rời game khi đang
    // chỉnh thì Service lưu từ đây, không cần composable còn sống).
    onElementsChange: (List<ProfileElement>) -> Unit = {},
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

    // Báo danh sách hiện tại cho Service mỗi khi đổi (để lưu khi rời game).
    LaunchedEffect(elements) { onElementsChange(elements) }

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
            it.enabled && it.keyCode == code && it.type in KEYED_TYPES
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
            // Build-9: Đặt lại về bố cục mặc định của game (như K7XD).
            TextButton(
                onClick = {
                    pushUndo()
                    val defaults = if (profile.packageName == "com.dts.freefiremax") {
                        ProfileCodec.defaultFreeFireMax().elements
                    } else {
                        ProfileCodec.defaultFreeFire().elements
                    }
                    elements = defaults
                    selected = null
                },
                enabled = captureIndex == null,
            ) { Text("⟲ Đặt lại", color = Color.White) }
            Button(onClick = { onSave(profile.copy(elements = currentElements)) }) {
                Text("Lưu")
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onCancel) { Text("Hủy", color = Color.White) }
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
                        if (e.type in KEYED_TYPES) {
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
                        if (e.type == ElementType.AUTO_TAP) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Khoảng tap", modifier = Modifier.width(80.dp))
                                Slider(
                                    value = e.intervalMs.toFloat(),
                                    onValueChange = { v ->
                                        if (!sliderUndoPushed) {
                                            pushUndo()
                                            sliderUndoPushed = true
                                        }
                                        updateElement(i, e.copy(intervalMs = v.toLong()))
                                    },
                                    onValueChangeFinished = { sliderUndoPushed = false },
                                    valueRange = 50f..2000f,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${e.intervalMs}ms",
                                    modifier = Modifier.width(64.dp),
                                    textAlign = TextAlign.End,
                                )
                            }
                            Text(
                                "Giữ phím thì tap lặp tại vị trí này mỗi ${e.intervalMs}ms.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (e.type == ElementType.RELEASE_TAP) {
                            Text(
                                "Tap 1 cái tại vị trí này khi NHẢ phím (thay vì khi bấm).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (e.type == ElementType.FREE_LOOK) {
                            Text(
                                "Bấm phím này để đổi giữa chế độ nhìn tự do (kéo camera) và " +
                                    "con trỏ (như phím ${LinuxKey.name(profile.cursorToggleKey)}). " +
                                    "Chỉ dùng phím bàn phím.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (e.type == ElementType.MACRO) {
                            MacroStepEditor(
                                element = e,
                                onUpdate = { updated ->
                                    pushUndo()
                                    updateElement(i, updated)
                                },
                            )
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
                                "FPS Look — vòng tròn nhỏ để chuột kéo camera (nhìn tự do). " +
                                    "Chuột ra khỏi vòng thì tự nhấc rồi chạm lại ở điểm đối xứng " +
                                    "qua tâm để kéo tiếp không hết vùng. Phím " +
                                    "${LinuxKey.name(profile.cursorToggleKey)} chuyển sang chế độ " +
                                    "con trỏ để bấm giao diện game.",
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
                                // Camera là vòng tròn bé: bán kính 2%..12% chiều rộng.
                                valueRange = if (e.type == ElementType.CAMERA) {
                                    Config.CAM_RADIUS_MIN..Config.CAM_RADIUS_MAX
                                } else {
                                    0.01f..0.5f
                                },
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

        // Hộp "Thêm thành phần" (kiểu GG Mouse Pro): Chạm / Xoay tự do / Tự
        // động Tap / Joystick / Thả Tap / Macro / FPS Look / Vuốt. Render
        // INLINE trong cùng cửa sổ overlay (lớp nền mờ + Card ở giữa) —
        // TUYỆT ĐỐI KHÔNG dùng AlertDialog: nó tạo window riêng không có
        // token Activity hợp lệ trong Service nên crash BadTokenException,
        // văng ra màn hình chính (máy thật 22/11/2026).
        // Đặt cuối cùng trong Box để luôn nằm trên thanh trên/bảng chỉnh.
        if (showAddMenu) {
            val hasJoystick = currentElements.any { it.type == ElementType.JOYSTICK }
            val hasCamera = currentElements.any { it.type == ElementType.CAMERA }
            // Lớp nền mờ: bấm ra ngoài để đóng.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { showAddMenu = false },
            )
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.85f),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Thêm thành phần",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
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
                                    ElementType.FREE_LOOK, 0.5f, 0.5f,
                                    Config.DEFAULT_BUTTON_SIZE, null, "Xoay tự do",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🔄 Xoay tự do — bấm phím để đổi nhìn tự do/con trỏ", fontSize = 15.sp) }
                    TextButton(
                        onClick = {
                            showAddMenu = false
                            addElement(
                                ProfileElement(
                                    ElementType.AUTO_TAP, 0.5f, 0.5f,
                                    Config.DEFAULT_BUTTON_SIZE, null, "Tự động Tap",
                                    intervalMs = Config.AUTO_TAP_INTERVAL_DEFAULT_MS,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🔁 Tự động Tap — giữ phím để tap lặp", fontSize = 15.sp) }
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
                                    ElementType.RELEASE_TAP, 0.5f, 0.5f,
                                    Config.DEFAULT_BUTTON_SIZE, null, "Thả Tap",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("👆⬆ Thả Tap — tap 1 cái khi nhả phím", fontSize = 15.sp) }
                    TextButton(
                        onClick = {
                            showAddMenu = false
                            addElement(
                                ProfileElement(
                                    ElementType.MACRO, 0.5f, 0.5f,
                                    Config.DEFAULT_BUTTON_SIZE, null, "Macro",
                                    steps = listOf(
                                        MacroStep(MacroStepKind.TAP, 0.5f, 0.5f),
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🎬 Macro — 1 phím chạy chuỗi thao tác", fontSize = 15.sp) }
                    TextButton(
                        onClick = {
                            showAddMenu = false
                            addElement(
                                ProfileElement(
                                    ElementType.CAMERA, Config.CAM_CENTER_X,
                                    Config.CAM_CENTER_Y, Config.CAM_HALF_W, null, "FPS Look",
                                ),
                            )
                        },
                        enabled = !hasCamera,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (hasCamera) "🎥 FPS Look — đã có rồi" else "🎥 FPS Look — nhìn bằng chuột (vòng tròn nhỏ)",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showAddMenu = false }) { Text("Đóng") }
                    }
                }
            }
        }
    }
}

/** Trình sửa chuỗi bước Macro (build-9): danh sách bước + thêm/xóa bước.
 *  Tap lấy vị trí hiện tại của phần tử; Chờ thêm 200ms (bấm −/+ để đổi);
 *  Vuốt từ vị trí phần tử sang phải 15% chiều rộng. */
@Composable
private fun MacroStepEditor(
    element: ProfileElement,
    onUpdate: (ProfileElement) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Chuỗi Macro (${element.steps.size} bước) — bấm phím thì chạy lần lượt:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        element.steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (step.kind) {
                        MacroStepKind.TAP -> "${i + 1}. Tap (${(step.x * 100).roundToInt()}%, ${(step.y * 100).roundToInt()}%)"
                        MacroStepKind.WAIT -> "${i + 1}. Chờ ${step.ms}ms"
                        MacroStepKind.SWIPE -> "${i + 1}. Vuốt (${(step.x * 100).roundToInt()}%, ${(step.y * 100).roundToInt()}%) → (${(step.x2 * 100).roundToInt()}%, ${(step.y2 * 100).roundToInt()}%) ${step.ms}ms"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                )
                if (step.kind == MacroStepKind.WAIT) {
                    TextButton(onClick = {
                        val steps = element.steps.mapIndexed { j, s ->
                            if (j == i) s.copy(ms = (s.ms - 100).coerceAtLeast(0L)) else s
                        }
                        onUpdate(element.copy(steps = steps))
                    }) { Text("−100") }
                    TextButton(onClick = {
                        val steps = element.steps.mapIndexed { j, s ->
                            if (j == i) {
                                s.copy(ms = (s.ms + 100).coerceAtMost(Config.MACRO_WAIT_MAX_MS))
                            } else s
                        }
                        onUpdate(element.copy(steps = steps))
                    }) { Text("+100") }
                }
                TextButton(onClick = {
                    val steps = element.steps.filterIndexed { j, _ -> j != i }
                    onUpdate(element.copy(steps = steps))
                }) { Text("✕", color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                onUpdate(
                    element.copy(
                        steps = element.steps + MacroStep(
                            MacroStepKind.TAP, element.x, element.y,
                        ),
                    ),
                )
            }) { Text("+ Tap tại đây", fontSize = 13.sp) }
            OutlinedButton(onClick = {
                onUpdate(
                    element.copy(
                        steps = element.steps + MacroStep(MacroStepKind.WAIT, ms = 200L),
                    ),
                )
            }) { Text("+ Chờ 200ms", fontSize = 13.sp) }
            OutlinedButton(onClick = {
                onUpdate(
                    element.copy(
                        steps = element.steps + MacroStep(
                            MacroStepKind.SWIPE, element.x, element.y,
                            (element.x + 0.15f).coerceIn(0f, 1f), element.y, 400L,
                        ),
                    ),
                )
            }) { Text("+ Vuốt", fontSize = 13.sp) }
        }
    }
}

/** Vẽ 1 phần tử: joystick = vòng tròn xanh lá, camera = vòng tròn xanh dương
 *  (build-9: FPS Look là vòng tròn nhỏ), nút/vuốt/tự động tap/thả tap/xoay
 *  tự do/macro = vòng tròn nền đen viền màu có nhãn tên phím (kiểu GG). */
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
        ElementType.AUTO_TAP -> Color(0xFFFF9800)
        ElementType.RELEASE_TAP -> Color(0xFF26A69A)
        ElementType.FREE_LOOK -> Color(0xFFFFEB3B)
        ElementType.MACRO -> Color(0xFFEF5350)
    }
    val fillColor = when (element.type) {
        ElementType.JOYSTICK -> Color(0xFF4CAF50).copy(alpha = 0.25f)
        ElementType.CAMERA -> Color(0xFF2196F3).copy(alpha = 0.15f)
        ElementType.BUTTON -> Color.Black.copy(alpha = 0.45f)
        ElementType.SWIPE -> Color(0xFF7B1FA2).copy(alpha = 0.35f)
        ElementType.AUTO_TAP -> Color(0xFFEF6C00).copy(alpha = 0.35f)
        ElementType.RELEASE_TAP -> Color(0xFF00796B).copy(alpha = 0.35f)
        ElementType.FREE_LOOK -> Color(0xFFF9A825).copy(alpha = 0.35f)
        ElementType.MACRO -> Color(0xFFC62828).copy(alpha = 0.35f)
    }
    // Mọi loại đều là vòng tròn (camera trước là khung chữ nhật, build-9 đổi
    // thành vòng tròn nhỏ như FPS Look).
    val shape = CircleShape
    val label = when (element.type) {
        ElementType.BUTTON, ElementType.SWIPE, ElementType.AUTO_TAP,
        ElementType.RELEASE_TAP, ElementType.FREE_LOOK, ElementType.MACRO ->
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

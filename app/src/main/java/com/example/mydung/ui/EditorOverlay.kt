package com.example.mydung.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.mydung.server.DoubleTapDetector
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

/** Các loại phần tử gán phím được (hàng "Gán phím"). Build-12: 1 phím gán được cho nhiều phần tử nên không kiểm tra trùng nữa. */
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
 * Build-11:
 * - Chạm 1 lần vào phần tử chỉ CHỌN (viền vàng) / kéo để dời; phải
 *   DOUBLE-TAP mới mở bảng chỉnh của phần tử đó (trước đây chạm 1 lần là
 *   mở bảng ngay). Camera là ngoại lệ: double-tap vòng camera thì bật/tắt
 *   chuột; chạm 1 lần vào camera thì hiện chữ "edit" + nút -/+ chỉnh X/Y
 *   quanh vòng (kiểu K7XD), bấm "edit" mới mở bảng chỉnh camera.
 * - Chạm vào khoảng trống thì đề xuất gán phím cho 1 nút Tap mới tại đúng
 *   chỗ đó (bấm phím là tạo xong), khỏi mở "Thêm thành phần".
 *
 * Lưu ý: callback kéo dùng rememberUpdatedState để pointerInput (key Unit,
 * chạy 1 lần) luôn thấy danh sách mới nhất — key theo phần tử sẽ làm
 * pointerInput khởi động lại giữa chừng và cắt ngang cú kéo.
 *
 * Tọa độ phần tử lưu theo TỈ LỆ (0-1) trên kích thước THẬT của màn hình
 * (readRealDisplay), không theo kích thước cửa sổ — cửa sổ chỉnh đã đặt cờ
 * FLAG_LAYOUT_NO_LIMITS + tràn tai thỏ nên 2 kích thước này bằng nhau, nhưng
 * lấy kích thước thật vẫn đúng ngay cả khi cửa sổ bị inset.
 *
 * Index bắt phím đặc biệt (xem OverlayService): -1 = phím ẩn/hiện lớp phủ
 * (không dùng trong trình sửa), -2 = gán nhanh Tap tại khoảng trống,
 * -3 = phím bật/tắt chuột (bảng chỉnh camera).
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
    // Build-11: cài đặt cho bảng chỉnh camera (Service giữ flow + áp dụng).
    sensX: StateFlow<Int>,
    sensY: StateFlow<Int>,
    dampX: StateFlow<Int>,
    dampY: StateFlow<Int>,
    pollRateHz: StateFlow<Int>,
    cursorRegion: StateFlow<Int>,
    mouseToggleKey: StateFlow<Int>,
    mouseOn: StateFlow<Boolean>,
    overlayToggleKey: StateFlow<Int>,
    onSensXChange: (Int) -> Unit,
    onSensYChange: (Int) -> Unit,
    onDampXChange: (Int) -> Unit,
    onDampYChange: (Int) -> Unit,
    onPollRateChange: (Int) -> Unit,
    onCursorRegionChange: (Int) -> Unit,
    onMouseToggleKeyChange: (Int) -> Unit,
    onRequestMouseKeyCapture: () -> Unit,
    onToggleMouse: () -> Unit,
) {
    var elements by remember { mutableStateOf(profile.elements) }
    var selected by remember { mutableStateOf<Int?>(null) }
    // Build-11: phần tử nào đang MỞ BẢNG CHỈNH (khác với `selected` chỉ
    // chọn/highlight): chỉ double-tap mới mở bảng.
    var panelIndex by remember { mutableStateOf<Int?>(null) }
    // Build-11: bảng chỉnh camera (mở bằng nút "edit" quanh vòng camera).
    var cameraPanelOpen by remember { mutableStateOf(false) }
    // Build-11: vị trí (tỉ lệ) đang đề xuất gán nhanh Tap (chạm khoảng trống).
    var quickAssignPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var undoStack by remember { mutableStateOf<List<List<ProfileElement>>>(emptyList()) }
    var sliderUndoPushed by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    // Build-11: nhận diện double-tap (2 lần chạm cùng phần tử trong 300ms).
    val doubleTap = remember { DoubleTapDetector() }

    val context = LocalContext.current
    // Kích thước thật của màn hình (tính cả xoay) làm mốc tỉ lệ 0-1.
    val realDisplay = remember { readRealDisplay(context) }

    val currentElements by rememberUpdatedState(elements)
    val captureIndex by captureRequest.collectAsState()
    val captured by capturedKey.collectAsState()
    // Giá trị hiện tại cho bảng chỉnh camera.
    val sensXValue by sensX.collectAsState()
    val sensYValue by sensY.collectAsState()
    val dampXValue by dampX.collectAsState()
    val dampYValue by dampY.collectAsState()
    val pollHzValue by pollRateHz.collectAsState()
    val cursorRegionValue by cursorRegion.collectAsState()
    val mouseKeyValue by mouseToggleKey.collectAsState()
    val mouseOnValue by mouseOn.collectAsState()
    val overlayKeyValue by overlayToggleKey.collectAsState()

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
        panelIndex = null
        cameraPanelOpen = false
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

    /** Đóng mọi bảng chỉnh (bảng nút / bảng camera / gợi ý gán nhanh). */
    fun closePanels() {
        panelIndex = null
        cameraPanelOpen = false
        if (quickAssignPos != null) {
            quickAssignPos = null
            onCancelCapture()
        }
    }

    /**
     * Chạm 1 lần vào phần tử [i] (build-11): lần 1 chỉ chọn (viền vàng) +
     * đóng bảng khác đang mở; lần 2 trong 300ms (double-tap) thì mở bảng
     * chỉnh của nút đó — riêng camera thì double-tap bật/tắt chuột ngay,
     * bảng chỉnh camera mở bằng nút "edit" quanh vòng.
     */
    fun handleElementTap(i: Int) {
        val e = currentElements.getOrNull(i) ?: return
        // Chạm vào phần tử thì hủy gợi ý gán nhanh (nếu đang chờ bấm phím).
        if (quickAssignPos != null) {
            quickAssignPos = null
            onCancelCapture()
        }
        if (doubleTap.tap(i, SystemClock.uptimeMillis())) {
            if (e.type == ElementType.CAMERA) {
                onToggleMouse()
            } else {
                panelIndex = i
                cameraPanelOpen = false
            }
        } else {
            selected = i
            if (panelIndex != i) panelIndex = null
        }
    }

    // Áp dụng phím vừa bắt được.
    LaunchedEffect(captured) {
        val c = captured ?: return@LaunchedEffect
        val (index, code) = c
        when (index) {
            // Build-11: gán nhanh Tap tại khoảng trống — tạo nút mới tại
            // vị trí đã chạm. Build-12: 1 phím gán được cho nhiều phần tử
            // nên không chặn trùng phím nữa (bấm phím thì TẤT CẢ cùng kích
            // hoạt).
            -2 -> {
                val pos = quickAssignPos
                quickAssignPos = null
                if (pos == null) {
                    onClearCaptured()
                    return@LaunchedEffect
                }
                pushUndo()
                val newIndex = currentElements.size
                elements = currentElements + ProfileElement(
                    ElementType.BUTTON, pos.first, pos.second,
                    Config.DEFAULT_BUTTON_SIZE, code, LinuxKey.name(code),
                )
                selected = newIndex
                panelIndex = null
                onClearCaptured()
                return@LaunchedEffect
            }
            // Build-11: phím bật/tắt chuột (bảng chỉnh camera) — Service
            // kiểm tra trùng (F8/F9/phím lớp phủ) rồi mới lưu.
            -3 -> {
                onMouseToggleKeyChange(code)
                onClearCaptured()
                return@LaunchedEffect
            }
            -1 -> {
                // Phím ẩn/hiện lớp phủ chỉ gán ở bảng nút K, không ở đây.
                onClearCaptured()
                return@LaunchedEffect
            }
        }
        // Gán phím cho phần tử (chỉ khi index còn đúng phần tử đang mở bảng).
        // Build-12: 1 phím gán được cho nhiều phần tử nên không chặn trùng
        // phím giữa các phần tử (bấm phím thì TẤT CẢ cùng kích hoạt). Chặn
        // trùng chỉ còn cho phím hệ thống (F8/F9/phím lớp phủ/phím bật tắt
        // chuột) — kiểm tra ở OverlayService.applyMouseKey/applyOverlayKey.
        if (index !in currentElements.indices || index != panelIndex) {
            onClearCaptured()
            return@LaunchedEffect
        }
        pushUndo()
        elements = currentElements.mapIndexed { i, e ->
            if (i == index) e.copy(keyCode = code) else e
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

        // Chạm vào chỗ trống (build-11): nếu đang mở bảng thì chỉ đóng bảng;
        // nếu không thì đề xuất gán phím cho 1 nút Tap mới tại đúng chỗ đó
        // (bấm phím là tạo xong) — chạm lại vào khoảng trống thì hủy đề xuất.
        // Bỏ qua vùng thanh trên (~64dp đầu màn hình).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(panelIndex, cameraPanelOpen, showAddMenu, quickAssignPos) {
                    detectTapGestures { offset ->
                        if (offset.y < with(density) { 64.dp.toPx() }) return@detectTapGestures
                        if (panelIndex != null || cameraPanelOpen || showAddMenu) {
                            selected = null
                            closePanels()
                            return@detectTapGestures
                        }
                        if (quickAssignPos != null) {
                            quickAssignPos = null
                            onCancelCapture()
                            return@detectTapGestures
                        }
                        selected = null
                        quickAssignPos = (offset.x / screenW) to (offset.y / screenH)
                        onRequestKeyCapture(-2)
                    }
                },
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

        // Các phần tử: chạm = chọn/double-tap mở bảng (handleElementTap);
        // bắt đầu kéo thì chỉ chọn + đẩy undo (kéo không tính là chạm).
        elements.forEachIndexed { i, e ->
            ElementView(
                element = e,
                selected = selected == i,
                screenW = screenW,
                screenH = screenH,
                onSelect = { handleElementTap(i) },
                onDragStart = {
                    selected = i
                    pushUndo()
                },
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
                onSelect = { handleElementTap(i) },
                onDragStart = {
                    selected = i
                    pushUndo()
                },
                onDrag = { dx, dy -> dragSwipeEnd(i, dx / screenW, dy / screenH) },
            )
        }

        // Build-11: camera đang chọn thì hiện chữ "edit" + nút -/+ chỉnh X/Y
        // quanh vòng (kiểu K7XD) — bấm "edit" mở bảng chỉnh camera.
        selected?.let { i ->
            val e = elements.getOrNull(i)
            if (e != null && e.type == ElementType.CAMERA) {
                CameraQuickEdit(
                    element = e,
                    screenW = screenW,
                    screenH = screenH,
                    sensX = sensXValue,
                    sensY = sensYValue,
                    mouseOn = mouseOnValue,
                    onSensXChange = onSensXChange,
                    onSensYChange = onSensYChange,
                    onEditClick = { cameraPanelOpen = true },
                    onToggleMouse = onToggleMouse,
                )
            }
        }

        // Build-11: gợi ý gán nhanh Tap tại khoảng trống vừa chạm.
        quickAssignPos?.let { pos ->
            QuickAssignHint(
                xRatio = pos.first,
                yRatio = pos.second,
                screenW = screenW,
                screenH = screenH,
                waiting = captureIndex == -2,
                onCancel = {
                    quickAssignPos = null
                    onCancelCapture()
                },
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
                    panelIndex = null
                    cameraPanelOpen = false
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
                    panelIndex = null
                    cameraPanelOpen = false
                },
                enabled = captureIndex == null,
            ) { Text("⟲ Đặt lại", color = Color.White) }
            Button(onClick = { onSave(profile.copy(elements = currentElements)) }) {
                Text("Lưu")
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onCancel) { Text("Hủy", color = Color.White) }
        }

        // Cảnh báo phần tử trùng vùng nút nổi K (build-12: đã bỏ cảnh báo
        // phím trùng — 1 phím gán được cho nhiều phần tử).
        if (floatOverlapNames.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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

        // Bảng chỉnh phần tử đang mở (chỉ mở bằng DOUBLE-TAP — chạm 1 lần
        // chỉ chọn, xem handleElementTap). Camera không dùng bảng này mà
        // dùng bảng chỉnh camera riêng (mở bằng nút "edit" quanh vòng).
        panelIndex?.let { i ->
            val e = elements.getOrNull(i)
            if (e != null && e.type != ElementType.CAMERA) {
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
                                        Text("Đang chờ bấm phím/chuột… (bấm để hủy)")
                                    }
                                } else {
                                    Button(onClick = { onRequestKeyCapture(i) }) {
                                        Text("Gán phím")
                                    }
                                }
                            }
                        }
                        if (e.type == ElementType.AUTO_TAP) {
                            // Tốc độ tap tính theo lần/s (1..20), vẫn lưu intervalMs
                            // trong profile (intervalMs = round(1000 / rate)). Profile
                            // cũ lưu intervalMs lẻ thì hiển thị rate làm tròn gần nhất.
                            val rate = (1000f / e.intervalMs.coerceAtLeast(1L))
                                .roundToInt().coerceIn(1, 20)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tốc độ tap", modifier = Modifier.width(80.dp))
                                TextButton(onClick = {
                                    val newRate = (rate - 1).coerceIn(1, 20)
                                    pushUndo()
                                    updateElement(
                                        i,
                                        e.copy(intervalMs = (1000f / newRate).roundToInt().toLong()),
                                    )
                                }) { Text("−", fontSize = 18.sp) }
                                Text(
                                    text = "$rate lần/s",
                                    modifier = Modifier.width(72.dp),
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                )
                                TextButton(onClick = {
                                    val newRate = (rate + 1).coerceIn(1, 20)
                                    pushUndo()
                                    updateElement(
                                        i,
                                        e.copy(intervalMs = (1000f / newRate).roundToInt().toLong()),
                                    )
                                }) { Text("+", fontSize = 18.sp) }
                            }
                            Text(
                                "Giữ phím thì tap lặp tại vị trí này $rate lần mỗi giây.",
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
                                    panelIndex = null
                                },
                                enabled = captureIndex == null,
                            ) { Text("Xóa") }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { panelIndex = null }) { Text("Xong") }
                        }
                    }
                }
            }
        }

        // Bảng chỉnh camera (build-11, kiểu K7XD — mở bằng nút "edit" quanh
        // vòng camera): độ nhạy X/Y, kháng nhạy X/Y, tần số quét chuột,
        // giới hạn vùng con trỏ, phím bật/tắt chuột. Render INLINE (không
        // AlertDialog — xem ghi chú ở hộp "Thêm thành phần").
        if (cameraPanelOpen) {
            val camIndex = elements.indexOfFirst { it.type == ElementType.CAMERA }
            val cam = elements.getOrNull(camIndex)
            if (cam != null) {
                CameraPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    element = cam,
                    sensX = sensXValue,
                    sensY = sensYValue,
                    dampX = dampXValue,
                    dampY = dampYValue,
                    pollHz = pollHzValue,
                    cursorRegion = cursorRegionValue,
                    mouseKey = mouseKeyValue,
                    mouseOn = mouseOnValue,
                    overlayKey = overlayKeyValue,
                    captureIndex = captureIndex,
                    onElementUpdate = { updated -> updateElement(camIndex, updated) },
                    onPushUndo = { pushUndo() },
                    onDelete = {
                        pushUndo()
                        elements = currentElements.filterIndexed { j, _ -> j != camIndex }
                        selected = null
                        cameraPanelOpen = false
                    },
                    onSensXChange = onSensXChange,
                    onSensYChange = onSensYChange,
                    onDampXChange = onDampXChange,
                    onDampYChange = onDampYChange,
                    onPollRateChange = onPollRateChange,
                    onCursorRegionChange = onCursorRegionChange,
                    onMouseToggleKeyChange = onMouseToggleKeyChange,
                    onRequestMouseKeyCapture = onRequestMouseKeyCapture,
                    onCancelCapture = onCancelCapture,
                    onToggleMouse = onToggleMouse,
                    onClose = { cameraPanelOpen = false },
                )
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
                // Bắt đầu kéo chỉ chọn + đẩy undo (qua onDragStart), KHÔNG
                // gọi onSelect — kéo không tính là chạm (kẻo chạm 1 lần rồi
                // kéo ngay trong 300ms bị nhận nhầm thành double-tap).
                detectDragGestures(
                    onDragStart = { onDragStart() },
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
                // Như ElementView: kéo không tính là chạm.
                detectDragGestures(
                    onDragStart = { onDragStart() },
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

/**
 * Chữ "edit" + nút -/+ chỉnh X/Y quanh vòng camera khi camera đang chọn
 * (build-11, kiểu K7XD — ảnh 1): cụm X ở trên vòng, cụm Y ở dưới vòng,
 * nút "edit" ở bên phải vòng. Bấm -/+ chỉnh độ nhạy X/Y trực tiếp (mỗi
 * lần ±5, thang 1..100); bấm "edit" mở bảng chỉnh camera đầy đủ.
 */
@Composable
private fun CameraQuickEdit(
    element: ProfileElement,
    screenW: Float,
    screenH: Float,
    sensX: Int,
    sensY: Int,
    mouseOn: Boolean,
    onSensXChange: (Int) -> Unit,
    onSensYChange: (Int) -> Unit,
    onEditClick: () -> Unit,
    onToggleMouse: () -> Unit,
) {
    val density = LocalDensity.current
    val radiusPx = element.size * screenW
    val cx = element.x * screenW
    val cy = element.y * screenH
    val stepperW = with(density) { 132.dp.toPx() }
    val stepperH = with(density) { 32.dp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    // Kẹp trong màn hình để vòng camera ở sát mép vẫn bấm được nút.
    val stepperX = (cx - stepperW / 2).coerceIn(0f, (screenW - stepperW).coerceAtLeast(0f))
    val xY = (cy - radiusPx - gap - stepperH).coerceAtLeast(0f)
    val yY = (cy + radiusPx + gap).coerceAtMost((screenH - stepperH).coerceAtLeast(0f))
    val editW = with(density) { 52.dp.toPx() }
    val editH = with(density) { 32.dp.toPx() }
    val editX = (cx + radiusPx + gap).coerceAtMost((screenW - editW).coerceAtLeast(0f))
    val editY = (cy - editH / 2).coerceIn(0f, (screenH - editH).coerceAtLeast(0f))

    // Cụm chỉnh X (trên vòng).
    Row(
        modifier = Modifier
            .offset { IntOffset(stepperX.roundToInt(), xY.roundToInt()) }
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            onSensXChange((sensX - 5).coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX))
        }) { Text("−", color = Color.White, fontSize = 18.sp) }
        Text(
            text = "X $sensX",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = {
            onSensXChange((sensX + 5).coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX))
        }) { Text("+", color = Color.White, fontSize = 18.sp) }
    }
    // Cụm chỉnh Y (dưới vòng).
    Row(
        modifier = Modifier
            .offset { IntOffset(stepperX.roundToInt(), yY.roundToInt()) }
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            onSensYChange((sensY - 5).coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX))
        }) { Text("−", color = Color.White, fontSize = 18.sp) }
        Text(
            text = "Y $sensY",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = {
            onSensYChange((sensY + 5).coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX))
        }) { Text("+", color = Color.White, fontSize = 18.sp) }
    }
    // Nút "edit" (bên phải vòng) + chấm trạng thái chuột.
    Row(
        modifier = Modifier.offset { IntOffset(editX.roundToInt(), editY.roundToInt()) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xFF1976D2), RoundedCornerShape(16.dp))
                .clickable { onEditClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "edit", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        // Chấm trạng thái chuột: xanh = đang bật, đỏ = đang tắt (bấm để đổi).
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    if (mouseOn) Color(0xFF4CAF50) else Color(0xFFEF5350),
                    CircleShape,
                )
                .border(1.dp, Color.White, CircleShape)
                .clickable { onToggleMouse() },
        )
    }
}

/**
 * Gợi ý gán nhanh Tap (build-11): vòng tròn đánh dấu vị trí vừa chạm +
 * thẻ gợi ý bên dưới ("Bấm phím để tạo nút Tap ở đây"). Bấm phím là tạo
 * xong nút tại đúng chỗ đó; bấm Hủy (hoặc chạm khoảng trống lần nữa) thì bỏ.
 */
@Composable
private fun QuickAssignHint(
    xRatio: Float,
    yRatio: Float,
    screenW: Float,
    screenH: Float,
    waiting: Boolean,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val cx = xRatio * screenW
    val cy = yRatio * screenH
    val markerPx = with(density) { 48.dp.toPx() }
    val cardW = with(density) { 260.dp.toPx() }
    val cardX = (cx - cardW / 2).coerceIn(0f, (screenW - cardW).coerceAtLeast(0f))
    // Thẻ ở dưới vòng đánh dấu; gần đáy màn hình thì đẩy lên trên.
    val cardY = (cy + markerPx / 2 + with(density) { 8.dp.toPx() })
        .coerceAtMost((screenH - with(density) { 110.dp.toPx() }).coerceAtLeast(0f))
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (cx - markerPx / 2).roundToInt(),
                    (cy - markerPx / 2).roundToInt(),
                )
            }
            .size(with(density) { markerPx.toDp() })
            .background(Color(0xFF42A5F5).copy(alpha = 0.3f), CircleShape)
            .border(2.dp, Color(0xFF42A5F5), CircleShape),
    )
    Card(
        modifier = Modifier.offset {
            IntOffset(cardX.roundToInt(), cardY.roundToInt())
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Bấm phím/nút chuột để tạo nút Tap ở đây",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                text = if (waiting) "Đang chờ bấm phím/chuột…" else "Chạm khoảng trống lần nữa để hủy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Hủy") }
            }
        }
    }
}

/**
 * Bảng chỉnh camera (build-11, kiểu K7XD — ảnh 2): độ nhạy X/Y, kháng nhạy
 * X/Y (thang 1..100), tần số quét chuột (60/125/250/500/1000 Hz + NO
 * LIMIT), giới hạn vùng con trỏ (TẮT / TOÀN MÀN / MÀN PHẢI), phím bật/tắt
 * chuột, cỡ vòng. Render INLINE trong cùng cửa sổ overlay (không
 * AlertDialog). Các chỉnh áp dụng ngay khi nhả tay / bấm chip; nút Lưu
 * chỉ đóng bảng (giá trị đã lưu rồi).
 */
@Composable
private fun CameraPanel(
    modifier: Modifier = Modifier,
    element: ProfileElement,
    sensX: Int,
    sensY: Int,
    dampX: Int,
    dampY: Int,
    pollHz: Int,
    cursorRegion: Int,
    mouseKey: Int,
    mouseOn: Boolean,
    overlayKey: Int,
    captureIndex: Int?,
    onElementUpdate: (ProfileElement) -> Unit,
    onPushUndo: () -> Unit,
    onDelete: () -> Unit,
    onSensXChange: (Int) -> Unit,
    onSensYChange: (Int) -> Unit,
    onDampXChange: (Int) -> Unit,
    onDampYChange: (Int) -> Unit,
    onPollRateChange: (Int) -> Unit,
    onCursorRegionChange: (Int) -> Unit,
    onMouseToggleKeyChange: (Int) -> Unit,
    onRequestMouseKeyCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onToggleMouse: () -> Unit,
    onClose: () -> Unit,
) {
    var sensXSlider by remember { mutableStateOf(sensX.toFloat()) }
    var sensYSlider by remember { mutableStateOf(sensY.toFloat()) }
    var dampXSlider by remember { mutableStateOf(dampX.toFloat()) }
    var dampYSlider by remember { mutableStateOf(dampY.toFloat()) }
    var sizeSliderUndoPushed by remember { mutableStateOf(false) }
    LaunchedEffect(sensX) { sensXSlider = sensX.toFloat() }
    LaunchedEffect(sensY) { sensYSlider = sensY.toFloat() }
    LaunchedEffect(dampX) { dampXSlider = dampX.toFloat() }
    LaunchedEffect(dampY) { dampYSlider = dampY.toFloat() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .heightIn(max = 520.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Chỉnh camera — FPS Look",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (mouseOn) "Chuột: Bật" else "Chuột: Tắt",
                    color = if (mouseOn) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontSize = 13.sp,
                )
            }
            Text(
                "FPS Look — vòng tròn nhỏ để chuột kéo camera (nhìn tự do). " +
                    "Chuột ra khỏi vòng thì tự nhấc rồi chạm lại ở điểm đối xứng " +
                    "qua tâm để kéo tiếp không hết vùng.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = element.name,
                    onValueChange = { onElementUpdate(element.copy(name = it)) },
                    label = { Text("Tên") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bật")
                Switch(
                    checked = element.enabled,
                    onCheckedChange = {
                        onPushUndo()
                        onElementUpdate(element.copy(enabled = it))
                    },
                )
            }
            // Độ nhạy X/Y (thang 1..100).
            CameraSliderRow(
                label = "Độ nhạy X (ngang)",
                value = sensXSlider,
                onValueChange = { sensXSlider = it },
                onValueChangeFinished = { onSensXChange(sensXSlider.roundToInt()) },
            )
            CameraSliderRow(
                label = "Độ nhạy Y (dọc)",
                value = sensYSlider,
                onValueChange = { sensYSlider = it },
                onValueChangeFinished = { onSensYChange(sensYSlider.roundToInt()) },
            )
            // Kháng nhạy (đầm tâm) X/Y + ghi chú như K7XD.
            CameraSliderRow(
                label = "Kháng nhạy X",
                value = dampXSlider,
                onValueChange = { dampXSlider = it },
                onValueChangeFinished = { onDampXChange(dampXSlider.roundToInt()) },
            )
            CameraSliderRow(
                label = "Kháng nhạy Y",
                value = dampYSlider,
                onValueChange = { dampYSlider = it },
                onValueChangeFinished = { onDampYChange(dampYSlider.roundToInt()) },
            )
            Text(
                "Tắt: Không kháng / Thấp: Nhẹ hơn / Cao: Nặng hơn " +
                    "(mức 1 = không kháng, mức càng cao càng đầm).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Tần số quét chuột.
            Text("Tần số quét chuột", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rates = Config.POLL_RATES + Config.POLL_RATE_NO_LIMIT
                for (hz in rates) {
                    val label = if (hz == Config.POLL_RATE_NO_LIMIT) "NO LIMIT" else "${hz}Hz"
                    Box(
                        modifier = Modifier
                            .background(
                                if (hz == pollHz) Color(0xFF1976D2)
                                else Color.Black.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onPollRateChange(hz) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (hz == pollHz) Color.White else Color.Black,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Text(
                "Tốc độ xử lý nội bộ của app, không đổi được polling rate phần cứng của chuột.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Giới hạn vùng con trỏ.
            Text("Giới hạn vùng con trỏ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val regions = listOf(
                    Config.CURSOR_REGION_OFF to "TẮT",
                    Config.CURSOR_REGION_FULL to "TOÀN MÀN",
                    Config.CURSOR_REGION_RIGHT to "MÀN PHẢI",
                )
                for ((value, label) in regions) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (value == cursorRegion) Color(0xFF1976D2)
                                else Color.Black.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onCursorRegionChange(value) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (value == cursorRegion) Color.White else Color.Black,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Text(
                "MÀN PHẢI: con trỏ chỉ di chuyển ở nửa phải màn hình (tránh chạm nhầm joystick).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Phím bật/tắt chuột.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Phím bật/tắt chuột: " +
                        (if (mouseKey == 0) "Chưa gán" else LinuxKey.name(mouseKey)),
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                )
                if (captureIndex == -3) {
                    OutlinedButton(onClick = onCancelCapture) {
                        Text("Đang chờ bấm phím… (bấm để hủy)")
                    }
                } else {
                    Button(onClick = onRequestMouseKeyCapture) { Text("Gán phím") }
                }
            }
            Text(
                "Bấm phím này để bật/tắt chuột ngay trong game (tắt thì con trỏ " +
                    "đứng yên + camera không xoay; phím/nút vẫn chạy). Double-tap " +
                    "vòng camera cũng bật/tắt được. Không gán trùng F8/F9/" +
                    "${LinuxKey.name(overlayKey)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onToggleMouse) {
                    Text(if (mouseOn) "Tắt chuột ngay" else "Bật chuột ngay")
                }
            }
            // Cỡ vòng camera.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cỡ vòng", modifier = Modifier.width(64.dp))
                Slider(
                    value = element.size,
                    onValueChange = { v ->
                        if (!sizeSliderUndoPushed) {
                            onPushUndo()
                            sizeSliderUndoPushed = true
                        }
                        onElementUpdate(element.copy(size = v))
                    },
                    onValueChangeFinished = { sizeSliderUndoPushed = false },
                    valueRange = Config.CAM_RADIUS_MIN..Config.CAM_RADIUS_MAX,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%.0f%%".format(element.size * 100),
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = captureIndex == null,
                ) { Text("Xóa") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onClose) { Text("Lưu") }
            }
        }
    }
}

/** 1 hàng trượt 1..100 trong bảng chỉnh camera (nhãn + giá trị + thanh trượt). */
@Composable
private fun CameraSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(110.dp), fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 1f..100f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${value.roundToInt()}",
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            fontSize = 14.sp,
        )
    }
}

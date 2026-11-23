package com.example.mydung.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mydung.server.Config
import com.example.mydung.server.LinuxKey
import com.example.mydung.server.Profile
import com.example.mydung.server.ProfileCodec
import com.example.mydung.server.ProfileStore
import com.example.mydung.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Màn quản lý profile (GĐ5): danh sách profile, chọn profile đang dùng
 * (gửi PROFILE cho Server), đổi tên/nhân bản/xóa, chỉnh độ nhạy + phím
 * bật/tắt MAP, gán game (chọn tay từ app đã cài), xuất/nhập profile qua
 * file JSON. GĐ6: công tắc tự đổi profile theo game đang mở (Server báo
 * app foreground qua SUB_FG, không cần quyền UsageStats).
 */
@Composable
fun ProfileScreen(onBack: () -> Unit, showBack: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProfileStore(ProfileStore.defaultFile(context.filesDir)) }

    var state by remember { mutableStateOf<ProfileStore.State?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var gamePickerTarget by remember { mutableStateOf<Profile?>(null) }
    var launchableApps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var autoSwitch by remember { mutableStateOf(ServerManager.isAutoSwitchEnabled(context)) }

    var exportTarget by remember { mutableStateOf<Profile?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val p = exportTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(ProfileCodec.toJson(p).toByteArray(Charsets.UTF_8))
                }
                message = "Đã xuất profile \"${p.name}\"."
            } catch (e: Exception) {
                message = "Xuất lỗi: ${e.message}"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@launch
                when (val r = ProfileCodec.fromJson(text)) {
                    is ProfileCodec.Result.Ok -> {
                        val current = state ?: return@launch
                        // ID mới để không đụng profile đã có.
                        val imported = r.profile.copy(id = UUID.randomUUID().toString())
                        persist(store, current.profiles + imported, current.activeId) { state = it }
                        message = "Đã nhập profile \"${imported.name}\"."
                    }
                    is ProfileCodec.Result.Err -> message = "Nhập lỗi: ${r.message}"
                }
            } catch (e: Exception) {
                message = "Nhập lỗi: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        state = store.ensureDefault()
        // Làm mới mỗi 2s để badge "đang dùng" cập nhật khi auto-switch
        // (GĐ6) đổi profile lúc màn đang mở (vd chia đôi màn hình).
        while (true) {
            delay(2000)
            val fresh = store.load()
            if (fresh != state) state = fresh
        }
    }

    fun useProfile(p: Profile) {
        val current = state ?: return
        persist(store, current.profiles, p.id) { state = it }
        scope.launch(Dispatchers.IO) {
            val resp = sendProfileToServer(context, p)
            message = if (resp.startsWith("OK")) {
                "Đang dùng profile \"${p.name}\"."
            } else {
                "Đã chọn \"${p.name}\" nhưng chưa gửi được cho Server ($resp). Bấm lại sau khi Server chạy."
            }
        }
    }

    fun updateProfile(p: Profile) {
        val current = state ?: return
        val updated = current.profiles.map { if (it.id == p.id) p else it }
        persist(store, updated, current.activeId) { state = it }
        // Profile đang dùng thì gửi lại cho Server ngay.
        if (p.id == current.activeId) {
            scope.launch(Dispatchers.IO) { sendProfileToServer(context, p) }
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                TextButton(onClick = onBack) { Text("← Quay lại") }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                "Quản lý profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val current = state ?: return@Button
                val n = current.profiles.size + 1
                val created = ProfileCodec.defaultFreeFire().copy(
                    name = "Profile $n",
                    packageName = null,
                )
                persist(store, current.profiles + created, current.activeId) { state = it }
                expandedId = created.id
            }) { Text("＋ Tạo profile mới") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text("Nhập profile")
            }
        }

        // GĐ6: tự đổi profile theo game đang mở.
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Tự đổi profile theo game",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Mở game đã gán thì app tự chuyển sang profile của game đó. " +
                            "Game chưa gán profile thì giữ nguyên profile đang dùng.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autoSwitch,
                    onCheckedChange = {
                        autoSwitch = it
                        ServerManager.setAutoSwitchEnabled(context, it)
                    },
                )
            }
        }

        val profiles = state?.profiles ?: emptyList()
        val activeId = state?.activeId
        profiles.forEach { p ->
            ProfileCard(
                profile = p,
                isActive = p.id == activeId,
                expanded = expandedId == p.id,
                onToggleExpand = { expandedId = if (expandedId == p.id) null else p.id },
                onUse = { useProfile(p) },
                onRename = { renameTarget = p },
                onDuplicate = {
                    val current = state ?: return@ProfileCard
                    val copy = p.copy(
                        id = UUID.randomUUID().toString(),
                        name = "${p.name} (bản sao)",
                    )
                    persist(store, current.profiles + copy, current.activeId) { state = it }
                },
                onDelete = { deleteTarget = p },
                onUpdate = { updateProfile(it) },
                onPickGame = {
                    gamePickerTarget = p
                    if (launchableApps == null) {
                        scope.launch(Dispatchers.IO) {
                            launchableApps = queryLaunchableApps(context)
                        }
                    }
                },
                onExport = {
                    exportTarget = p
                    exportLauncher.launch("${p.name}.json")
                },
            )
        }
        if (profiles.isEmpty()) {
            Text("Chưa có profile nào.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Chọn profile rồi bấm \"Dùng\" để gửi cho Server. Bật \"Tự đổi profile theo game\" " +
                "ở trên thì mở game đã gán là app tự đổi, không cần quyền UsageStats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Dialog đổi tên.
    renameTarget?.let { p ->
        var nameText by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Đổi tên profile") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Tên") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = nameText.trim()
                    if (name.isNotEmpty()) updateProfile(p.copy(name = name))
                    renameTarget = null
                }) { Text("Lưu") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Hủy") }
            },
        )
    }

    // Dialog xác nhận xóa.
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Xóa profile?") },
            text = { Text("Xóa \"${p.name}\"? Không khôi phục được.") },
            confirmButton = {
                Button(onClick = {
                    val current = state
                    if (current != null) {
                        if (current.profiles.size <= 1) {
                            message = "Không xóa được profile cuối cùng."
                        } else {
                            val remaining = current.profiles.filter { it.id != p.id }
                            val newActive = if (current.activeId == p.id) {
                                remaining.firstOrNull()?.id
                            } else {
                                current.activeId
                            }
                            persist(store, remaining, newActive) { state = it }
                            if (expandedId == p.id) expandedId = null
                        }
                    }
                    deleteTarget = null
                }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Hủy") }
            },
        )
    }

    // Dialog chọn game.
    gamePickerTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { gamePickerTarget = null },
            title = { Text("Gán game cho \"${p.name}\"") },
            text = {
                val apps = launchableApps
                if (apps == null) {
                    Text("Đang liệt kê app đã cài…")
                } else if (apps.isEmpty()) {
                    Text("Không liệt kê được app (cần quyền xem app đã cài).")
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        item {
                            TextButton(onClick = {
                                updateProfile(p.copy(packageName = null))
                                gamePickerTarget = null
                            }) { Text("— Không gán game —") }
                        }
                        items(apps) { app ->
                            TextButton(
                                onClick = {
                                    updateProfile(p.copy(packageName = app.packageName))
                                    gamePickerTarget = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                GameIcon(packageName = app.packageName, size = 40.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, fontWeight = FontWeight.Bold)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { gamePickerTarget = null }) { Text("Đóng") }
            },
        )
    }
}

/** App có icon trên launcher — dùng chung cho HomeScreen (thêm game) và ProfileScreen (gán game). */
data class AppInfo(val label: String, val packageName: String)

/** Lưu danh sách + id đang dùng xuống file, rồi cập nhật state hiển thị. */
private fun persist(
    store: ProfileStore,
    profiles: List<Profile>,
    activeId: String?,
    onState: (ProfileStore.State) -> Unit,
) {
    store.save(profiles, activeId)
    onState(ProfileStore.State(profiles, activeId))
}

/** Liệt kê app có icon trên launcher (trừ chính app này). Cần <queries> trong Manifest. */
fun queryLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return try {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        infos
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .sortedBy { it.label.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    isActive: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (Profile) -> Unit,
    onPickGame: () -> Unit,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name + if (isActive) "  ● đang dùng" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = profile.packageName ?: "Chưa gán game",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${profile.elements.size} phần tử · Nhạy ${profile.sensX}/${profile.sensY} · " +
                            "Bật/tắt: ${LinuxKey.name(profile.mapToggleKey)} · " +
                            "Con trỏ: ${LinuxKey.name(profile.cursorToggleKey)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onToggleExpand) {
                    Text(if (expanded) "Thu gọn" else "Chỉnh")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    Button(onClick = onUse) { Text("Dùng") }
                }
                OutlinedButton(onClick = onRename) { Text("Đổi tên") }
                OutlinedButton(onClick = onDuplicate) { Text("Nhân bản") }
                OutlinedButton(onClick = onDelete) { Text("Xóa") }
            }
            if (expanded) {
                ProfileDetailEditor(
                    profile = profile,
                    onUpdate = onUpdate,
                    onPickGame = onPickGame,
                    onExport = onExport,
                )
            }
        }
    }
}

@Composable
private fun ProfileDetailEditor(
    profile: Profile,
    onUpdate: (Profile) -> Unit,
    onPickGame: () -> Unit,
    onExport: () -> Unit,
) {
    var sensXText by remember(profile.id) { mutableStateOf(profile.sensX.toString()) }
    var sensYText by remember(profile.id) { mutableStateOf(profile.sensY.toString()) }
    var sensError by remember(profile.id) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sensXText,
                onValueChange = { sensXText = it },
                label = { Text("Độ nhạy X (1–100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = sensYText,
                onValueChange = { sensYText = it },
                label = { Text("Độ nhạy Y (1–100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val x = sensXText.trim().toIntOrNull()
                val y = sensYText.trim().toIntOrNull()
                if (x == null || y == null) {
                    sensError = "Độ nhạy phải là số nguyên 1–100."
                    return@Button
                }
                sensError = null
                onUpdate(
                    profile.copy(
                        sensX = x.coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX),
                        sensY = y.coerceIn(Config.SENS_UI_MIN, Config.SENS_UI_MAX),
                    ),
                )
            }) { Text("Áp dụng") }
        }
        sensError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text("Phím bật/tắt MAP:", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LinuxKey.F8 to "F8",
                LinuxKey.F9 to "F9",
                LinuxKey.F10 to "F10",
                LinuxKey.F11 to "F11",
                LinuxKey.F12 to "F12",
            ).forEach { (code, label) ->
                FilterChip(
                    selected = profile.mapToggleKey == code,
                    onClick = { onUpdate(profile.copy(mapToggleKey = code)) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "Phím chuyển chế độ con trỏ ↔ nhìn tự do (con trỏ để bấm giao diện game):",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LinuxKey.F9 to "F9",
                LinuxKey.F10 to "F10",
                LinuxKey.F11 to "F11",
                LinuxKey.F12 to "F12",
                LinuxKey.F8 to "F8",
            ).forEach { (code, label) ->
                FilterChip(
                    selected = profile.cursorToggleKey == code,
                    onClick = { onUpdate(profile.copy(cursorToggleKey = code)) },
                    label = { Text(label) },
                )
            }
        }
        if (profile.cursorToggleKey == profile.mapToggleKey) {
            Text(
                "⚠ Phím chuyển con trỏ trùng phím bật/tắt MAP — bấm phím này sẽ chỉ bật/tắt MAP, " +
                    "không chuyển được chế độ con trỏ. Đổi 1 trong 2 phím.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickGame) { Text("Chọn game") }
            OutlinedButton(onClick = onExport) { Text("Xuất file JSON") }
        }
    }
}

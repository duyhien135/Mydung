# KeyTouch — keymap chơi game Android bằng phím/chuột

App gán bàn phím/chuột USB (cắm qua OTG) thành cảm ứng trong game:
WASD di chuyển joystick, chuột ngắm/nhìn, các phím bấm nút game.
Game chính: **Free Fire MAX**.

> Bản làm lại giao diện (11/2026) theo mẫu **GG Mouse Pro 3** (checklist kích
> hoạt, trình sửa keymap trực quan, nút nổi trong game) + **K7XD ENGINE**
> (viên trạng thái quyền/thiết bị, tab Game / Cấu hình / Cài đặt).
> Engine (Server đặc quyền) giữ nguyên từ bản stage5/6, chỉ thêm: kích hoạt
> bằng Shizuku/Root, phần tử Vuốt (SWIPE), tự gửi lại DISPLAY khi xoay màn hình.
>
> Sửa theo Claude soi (11/2026): chẩn đoán Server theo giai đoạn + log tail
> trong app, chế độ con trỏ (F9) kèm chấm con trỏ trên màn hình, camera chạm
> lại ở rìa đối diện, tự GRAB khi bật MAP, tiêm nguyên tử trong khóa, DISPLAY
> gửi ngay khi xoay (display listener), Shizuku thử lại khi binder chết,
> cảnh báo phím trùng vùng nút nổi, ghi chú Xiaomi/HyperOS.
>
> Sửa đợt 2 theo Claude soi lại (11/2026): giữ heartbeat bằng foreground
> service (ServerKeepAliveService), dọn Server cũ còn sống trước khi chạy
> bản mới (dùng lại nếu cùng version, kill nếu khác), xóa file log cũ trước
> khi khởi động (đổi phương thức kích hoạt không bị kẹt quyền file), che
> token trong mọi log, GRAB chỉ chuột + bàn phím ngoài (không grab cảm
> ứng/phím cứng/vân tay) + inotify re-grab khi cắm/rút OTG, F9 nhả cả nút
> chuột đang giữ, camera UP rồi DOWN lại cách nhau 20ms, thăm dò con trỏ
> ~30Hz trên 1 kết nối giữ mở.

## Luồng dùng (bản mới)

```
Mở app → tab Game
  1. Viên quyền: LỚP PHỦ / THÔNG BÁO / PIN / SHIZUKU (xanh = đã cấp, cam = chưa)
  2. Viên thiết bị: tên chuột/bàn phím đã cắm (tự phát hiện khi cắm/rút OTG)
  3. Checklist kích hoạt (Shizuku mặc định):
       Cài + Start app Shizuku → Cấp quyền cho KeyTouch → Khởi động Server
  4. Nút lớn duy nhất theo trạng thái:
       Chưa kích hoạt → Đã kích hoạt → Đã kết nối → Đang chạy
  5. KHỞI CHẠY game → mở game + hiện nút nổi K + bật keymap
Trong game: bấm nút K = bật/tắt keymap, GIỮ nút K = mở trình sửa,
kéo nút K = di chuyển (vị trí lưu lại).
```

- **Tab Cấu hình**: quản lý profile (đổi tên, nhân bản, xóa, xuất/nhập JSON),
  độ nhạy chuột, phím bật/tắt MAP (F8–F12), phím chuyển chế độ con trỏ ↔
  nhìn tự do (F9–F12, mặc định F9; cảnh báo nếu trùng phím MAP), gán game,
  tự đổi profile theo game.
- **Tab Cài đặt**: phương thức kích hoạt (Shizuku / Root / ADB không dây),
  cách tiêm cảm ứng (Phản chiếu / Evdev thô), Ping / Dừng Server / Xem log
  Server, nhật ký gỡ lỗi.
- **Chỉnh nút** (trình sửa trên lớp phủ): mỗi phím là vòng tròn có nhãn nằm
  đúng vị trí chạm trên game; kéo-thả di chuyển, bấm vào để gán phím / đổi
  cỡ / xóa; nút **＋ Thêm thành phần** (Chạm, Joystick, Vuốt, Camera).
  Vuốt có thêm tay cầm điểm cuối (vòng tròn tím) để đổi hướng/dài vuốt +
  thanh trượt thời gian vuốt. Tọa độ lưu theo **tỉ lệ 0–1** trên kích thước
  thật của màn hình. Phần tử trùng vùng nút nổi K thì hiện cảnh báo (chạm
  tiêm vào đó có thể bị nút nổi nuốt).
- **Chế độ thử phím** (tab Game): hiện phím/nút đang bấm, tọa độ chuột, công
  tắc GRAB chuột/bàn phím.
- **Trong game**: F8 bật/tắt keymap, F9 chuyển chế độ con trỏ (chuột thành
  con trỏ ảo bấm giao diện game, có chấm trắng hiện vị trí) ↔ nhìn tự do
  (chuột kéo camera). Bật MAP tự GRAB chuột/phím, tắt MAP tự nhả.
- **Server lỗi** (tab Game): khi khởi động Server thất bại, app hiện thẻ
  chẩn đoán — giai đoạn bị kẹt (tiến trình chết / cổng chưa nghe / bắt tay
  lỗi / hết giờ) + 25 dòng cuối log Server + nút "Sao chép log".

## Kiến trúc

```
app (uid thường)                      Server đặc quyền (uid 2000 shell / 0 root)
┌────────────────────────────┐        ┌──────────────────────────────────────┐
│ MainActivity (3 tab)       │  TCP   │ Server.kt (loopback, AUTH token)     │
│ HomeScreen / ProfileScreen │◄──────►│ InputHub: đọc /dev/input/event*,     │
│ SettingsScreen / AdbSetup  │ 127.0.0.1│   GRAB chuột/phím (EVIOCGRAB)      │
│ OverlayService (nút nổi K, │        │ TouchHub: WASD→joystick, chuột→     │
│   trình sửa keymap)        │        │   camera, phím→chạm/vuốt, tiêm đa    │
│ ServerManager (quản lý     │        │   điểm (reflect hoặc evdev thô)      │
│   vòng đời Server)         │        │ ForegroundWatcher: tự đổi profile    │
│ ServerKeepAliveService     │        │ DeviceScanner: inotify /dev/input    │
│   (foreground service giữ  │        │   để re-grab khi cắm/rút OTG        │
│   tiến trình sống khi      │        │                                      │
│   Server chạy)             │        │                                      │
└────────────┬───────────────┘        └──────────────────────────────────────┘
             │ khởi động Server bằng ShellExecutor
             │ khởi động Server bằng ShellExecutor
             ├─ ShizukuShellExecutor (mặc định): UserService Shizuku
             │   (shizuku/ShellService qua AIDL) chạy `sh -c <lệnh app_process>`
             ├─ RootShellExecutor: `su -c <lệnh>` (máy đã root, vd Y700)
             └─ AdbShellExecutor: libadb qua gỡ lỗi không dây (đường cũ)
```

- **Shizuku**: API 13.1.5. `Shizuku.newProcess()` đã private từ 13.0 nên dùng
  UserService + AIDL (`IShellService.aidl`: `exec(cmd, timeoutMs)` trả Bundle
  code/out/err). Cần `ShizukuProvider` trong Manifest + `aidl = true` +
  `buildConfig = true` + keep rule cho `ShellService` (Shizuku server tạo
  instance bằng reflection).
- **Profile JSON** (`filesDir/profiles.json`, schema v1): joystick, camera,
  button, **swipe** (điểm cuối x2/y2 + durationMs). Mặc định có 2 profile:
  Free Fire MAX (`com.dts.freefiremax`, đang dùng) + Free Fire
  (`com.dts.freefireth`).
- **DISPLAY tự làm mới**: display listener gửi DISPLAY ngay khi xoay/đổi
  kích thước màn hình; heartbeat 5s chỉ làm dự phòng. Cả overlay lẫn server
  đều dùng kích thước thật (WindowMetrics `maximumWindowMetrics`, gồm cả
  vùng tai thỏ + thanh điều hướng).
- **Nút nổi**: cửa sổ WRAP_CONTENT (chỉ nút nhận chạm, game vẫn chạm bình
  thường), alpha 0.65; cửa sổ chơi thêm `FLAG_NOT_TOUCH_MODAL`; cửa sổ
  chỉnh toàn màn hình với `FLAG_LAYOUT_NO_LIMITS` + tràn tai thỏ để tỉ lệ
  0–1 khớp tọa độ tiêm.
- **Chẩn đoán Server** (`ServerManager`): khởi động bằng
  `setsid nohup app_process ... </dev/null >log 2>&1 &` (sống độc lập với
  shell/UserService), đường dẫn APK lấy bằng `pm path` lúc khởi động,
  thăm dò bắt tay mỗi 250ms, kiểm tra tiến trình/cổng mỗi 2s, dừng sớm nếu
  tiến trình chết. Server ghi log từng bước vào
  `/data/local/tmp/mydung_server.log` (cắt mỗi lần khởi động); app đọc
  25 dòng cuối qua chính executor khi lỗi.
- **Server cũ còn sống** (`ServerManager.reapStaleServer`): Server chạy
  bằng setsid nohup nên app chết Server vẫn sống — trước khi khởi động bản
  mới, app ping cổng đã lưu: cùng version thì dùng lại luôn, khác version
  thì gửi STOP rồi kill; tiến trình còn sống mà không phản hồi thì kill
  bằng `pkill -f mydung_server`; kill không được (vd bản cũ chạy bằng
  root, phương thức hiện tại không có quyền) thì hiện giai đoạn
  "Đã có Server cũ đang chạy" thay vì chạy bản mới chồng lên.
- **Quyền sở hữu file log**: trước mỗi lần khởi động, app xóa file log cũ
  bằng chính executor hiện tại (kèm `chmod 666` dự phòng) — file do root
  tạo thì shell không truncate được và ngược lại, khiến lệnh khởi động lỗi
  ngay ở bước redirect `>log` khi đổi phương thức kích hoạt. Server cũng
  tự xóa + ghi lại log 1 lần nếu lần ghi đầu lỗi.
- **Token không nằm trong log**: mọi dòng log của Server, PairingState và
  thông điệp lỗi của RootShellExecutor đều qua `Config.redactSecrets()`
  (che chuỗi 32 hex thành `***`) — log ở `/data/local/tmp` app khác đọc
  được, lộ token là tiêm được chạm qua loopback.
- **Giữ heartbeat bằng foreground service**: heartbeat 5s + display
  listener + vòng thăm dò chạy trong scope của `ServerManager` (sống theo
  TIẾN TRÌNH, không gắn Activity nào) — để HyperOS không giết tiến trình
  khi app ở nền lúc chơi game, `ServerKeepAliveService` (foreground
  service, thông báo ongoing) chạy suốt lúc Server sống; Server dừng/mất
  kết nối thì service tự dừng. Service bị kill thì tự nối lại Server
  (START_STICKY + `checkOrReconnectServer`).
- **GRAB lọc đúng thiết bị**: `setGrab("all")` chỉ duyệt thiết bị đã phân
  loại là KEYBOARD/MOUSE — màn cảm ứng (ABS_MT, không REL/kbd), phím cứng
  nguồn/âm lượng (gpio-keys/qpnp), cảm biến vân tay (tên chứa
  "fingerprint") đều bị loại ở `InputEventParser`. `DeviceScanner` lắng
  nghe inotify trên `/dev/input` để re-grab ngay khi cắm/rút OTG lúc MAP
  đang bật (vòng quét 2s vẫn giữ làm dự phòng).
- **Camera**: chuột ra khỏi vùng camera thì tự nhấc rồi chạm lại ở **rìa đối
  diện** (cách rìa 5% nửa chiều vùng), trục còn trong vùng giữ nguyên —
  kéo camera liên tục không hết vùng. UP và DOWN lại cách nhau 20ms (1
  tick), không cùng khung — game nhận ra là 2 lần chạm riêng; chuột di
  chuyển trong lúc chờ thì cộng dồn vào vị trí chạm lại.
- **Chế độ con trỏ**: con trỏ ảo khởi tạo ở tâm vùng camera; chuột di chuyển
  con trỏ (kẹp trong màn hình), nút trái/phải bấm tại vị trí con trỏ
  (giữ nút rồi kéo = kéo/thả). MAPSTATUS trả thêm `mode=camera|cursor` +
  `cursor=x,y`; app thăm dò **~30Hz** ở chế độ con trỏ qua `StatusPoller`
  (1 kết nối giữ mở, không mở socket + AUTH mới mỗi lần) để vẽ chấm con
  trỏ mượt. Chấm con trỏ 24dp, cửa sổ `FLAG_NOT_TOUCHABLE`, alpha 0.8.
  **F9** chuyển chế độ thì nhả camera VÀ nhả nút chuột/vuốt đang giữ trước
  (không thì kẹt chạm); joystick dùng pointer id 0, camera/con trỏ dùng
  id 1 nên giữ WASD ở chế độ con trỏ không đụng.
- **Tiêm nguyên tử**: mọi tiêm trong TouchHub đều nằm trong cùng một lần khóa
  với lúc tạo đặc tả — DOWN không bao giờ tiêm sau CANCEL; DOWN và MOVE đầu
  không cùng khung; vuốt giữ điểm cuối 1 tick trước khi UP; bước nội suy
  tối thiểu 8ms; id pointer 2..9 cấp phát có khóa, chỉ giải phóng sau UP.
- **Shizuku**: `exec()` thử lại 1 lần khi binder chết (rebind rồi gọi lại);
  binder chết thì viên SHIZUKU chuyển cam thay vì crash; checklist nhắc
  Start lại Shizuku sau mỗi lần reboot máy.
- **Xiaomi/HyperOS**: màn chính hiện ghi chú riêng cho máy Xiaomi — bật
  "Gỡ lỗi USB (cài đặt bảo mật)" trong Tùy chọn nhà phát triển + bật Tự
  khởi động cho KeyTouch (HyperOS giết app nền rất mạnh).

## Lưu ý an toàn

- Chỉ làm **ánh xạ phím thuần túy**. KHÔNG làm Macro, kiểm soát giật, tăng
  cường thị lực — Free Fire có thể coi là gian lận và khóa tài khoản.
- Server chỉ nghe trên loopback 127.0.0.1 + token ngẫu nhiên 32 hex mỗi lần
  khởi động; tự thoát sau 5 phút không có heartbeat.

## Kiểm tra cục bộ (không cần Android SDK)

```bash
./check-local.sh   # kotlinc + JUnit cho code thuần (server/profile/mapper)
```

148 test (11/2026): 141 cũ + 7 mới (che token trong log ×3, bộ lọc vân tay/
phím cứng ×1, camera chạm lại ×1, F9 nhả nút đang giữ ×1, F9 không nhả
joystick ×1).
Code UI/Compose và Shizuku chỉ kiểm tra được bằng build CI (GitHub Actions)
— VM không có Gradle. Trước khi build, đọc lại checklist trong
`../goals/free-fire-keymapper-app/GOAL.md` (mục stage5/6) và file này.

## Còn thiếu / cần người dùng quyết

- **Tên app mới?** Hiện vẫn là "KeyTouch", package `com.example.mydung`.
  Đổi tên hiển thị thì sửa `android:label` trong Manifest; đổi package thì
  phải sửa cả code + profile mặc định + lệnh khởi động Server.
- **Server GĐ2 chưa kiểm chứng chạy được trên máy thật** (lệnh khởi động trả
  LAUNCHED nhưng Server không phản hồi — đã sửa timeout 5s→30s + thêm chẩn
  đoán theo giai đoạn và log tail trong app, chưa test lại). Lần build đầu
  tiên cần test: Shizuku → Khởi động Server → xem thẻ chẩn đoán ghi giai
  đoạn nào. Cách khoanh vùng nhanh nhất: chạy đường root trên Y700 trước —
  chạy được thì lỗi ở Shizuku/UserService, không thì lỗi ở server/kết nối.
- **Test xoay trên máy thật: thử cả 2 chiều ngang 90° và 270°** — tiêm tọa
  độ thô thường sai khi xoay ngược (tọa độ lật/tráo trục), nhất là khi đổi
  chiều ngang giữa trận; kiểm tra joystick/camera/nút chạm có đúng vị trí
  ở cả 2 chiều trước khi tin là xoay màn đã ổn.
- Menu quạt nhiều mục (kiểu GG) chưa làm — bản đầu chỉ có nút nổi K với
  3 thao tác (bấm/giữ/kéo).
- Chưa có tự kết nối lại Server sau khi khởi động lại máy (Shizuku cũng phải
  Start lại tay sau mỗi lần reboot — giới hạn của Shizuku, không phải của app).
- Cuộn chuột, Chế độ xem FPS, Chọn chuột (trong hộp Thêm thành phần của GG)
  chưa làm — bản đầu chỉ có Chạm / Joystick / Vuốt / Camera.

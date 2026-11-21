package com.example.mydung.shizuku;

// Giao diện AIDL cho UserService chạy bằng quyền shell (uid 2000) qua Shizuku.
// App gọi exec() để chạy lệnh shell (khởi động Server đặc quyền, đọc log...)
// mà không cần ADB. Xem ShizukuBridge.kt để biết luồng bind.
//
// LƯU Ý: destroy() dùng transaction 16777114 (= 16777115 - FIRST_CALL_TRANSACTION),
// là mã dành riêng Shizuku server gọi để hủy service. Khi gán id tường minh
// cho 1 phương thức thì phải gán cho tất cả (theo demo chính thức của Shizuku).
interface IShellService {

    // Phương thức hủy do Shizuku server gọi (transaction 16777115 oneway).
    void destroy() = 16777114;

    // Phương thức thoát do app tự gọi.
    void exit() = 1;

    // Chạy `sh -c command` trong tiến trình shell. Trả về Bundle gồm:
    // "code" (int, mã thoát, -1 nếu quá thời gian), "out" (String stdout),
    // "err" (String stderr).
    Bundle exec(String command, int timeoutMs) = 2;
}

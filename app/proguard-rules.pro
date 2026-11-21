# Keep rule cho Server đặc quyền chạy qua app_process
-keep class com.example.mydung.server.Server { *; }
-keepclassmembers class com.example.mydung.server.Server {
    public static void main(java.lang.String[]);
}

# Keep rule cho Shizuku UserService: Shizuku server tạo instance bằng
# reflection theo tên class + constructor (Context). Đổi tên class hoặc
# xóa constructor là bind thất bại (server im lặng xóa record sau 30s).
-keep class com.example.mydung.shizuku.ShellService {
    public <init>(...);
    public <methods>;
}
-keep class com.example.mydung.shizuku.IShellService** { *; }

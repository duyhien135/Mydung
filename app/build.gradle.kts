import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Keystore ký debug CỐ ĐỊNH (cùng key với Cube Timer): trước đây mỗi lần
// build trên GitHub Actions tự sinh key mới nên Android không cho cài đè
// (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Keystore lưu dạng base64 trong repo
// (debug.keystore.b64) vì file nhị phân không đẩy qua connector được; giải mã
// ra debug.keystore ngay lúc cấu hình (trước mọi task, để AGP đọc được khi
// ký). Đây là key DEBUG (alias androiddebugkey, mật khẩu android).
// Lưu ý: KHÔNG đặt code này trong khối android {} vì `java.util.Base64`
// không phân giải được ở đó.
val debugKeystoreFile = layout.projectDirectory.file("../debug.keystore").asFile
if (!debugKeystoreFile.exists()) {
    val b64 = layout.projectDirectory.file("../debug.keystore.b64").asFile.readText()
    debugKeystoreFile.writeBytes(Base64.getMimeDecoder().decode(b64))
}

android {
    namespace = "com.example.mydung"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        // getByName: config debug đã có sẵn do AGP tạo, chỉ sửa lại key;
        // dùng create("debug") sẽ lỗi trùng tên.
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    defaultConfig {
        applicationId = "com.example.mydung"
        minSdk = 30
        targetSdk = 37
        versionCode = 8
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build-11 (rút gọn APK theo Claude soi): chỉ đóng gói native lib
        // arm64-v8a — cả 2 máy người dùng (Redmi Turbo 4, Y700) đều arm64,
        // bỏ 3 ABI còn lại của Conscrypt (~6-9MB).
        ndk {
            abiFilters += "arm64-v8a"
        }

        // UserService Shizuku: version PHẢI đổi mỗi build (lấy giờ build)
        // để Shizuku server không dùng lại bản ghi service cũ — versionCode
        // không đổi giữa các lần cài debug là 1 phần của lỗi bind timeout
        // 35s trên máy thật (21/11/2026). ShizukuBridge dùng qua
        // BuildConfig.USER_SERVICE_VERSION (ép về Int, xem userServiceVersion).
        buildConfigField("long", "USER_SERVICE_VERSION", "${System.currentTimeMillis()}L")
    }

    // Build-11 (rút gọn APK theo Claude soi): chỉ giữ chuỗi tài nguyên
    // tiếng Việt + tiếng Anh (bỏ ~80 locale khác trong các thư viện).
    androidResources {
        localeFilters += setOf("vi", "en")
    }

    buildTypes {
        release {
            // Ký release bằng key DEBUG cố định để cài đè được lên bản debug
            // cũ (cùng keystore với mọi bản trước).
            signingConfig = signingConfigs.getByName("debug")
            // R8 thu nhỏ toàn bộ (build-11: BỎ packageScope hẹp trước đây
            // chỉ gồm androidx/kotlin — để R8 cắt cả BouncyCastle /
            // Conscrypt / libadb / Compose, là phần to nhất của APK).
            optimization {
                enable = true
            }
            // AGP 9.3+: KHÔNG dùng proguardFiles nữa — keep rule nằm trong
            // src/main/keepRules/*.keep (xem keepRules/rules.keep).
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AIDL: bắt buộc để biên dịch IShellService.aidl (Shizuku UserService).
        // AGP 8+ mặc định tắt aidl.
        aidl = true
        // BuildConfig: ShizukuBridge dùng APPLICATION_ID / DEBUG /
        // USER_SERVICE_VERSION (version đổi mỗi build cho UserServiceArgs).
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // Build-11: BỎ ui.tooling.preview + debugImplementation ui.tooling
    // (repo không có @Preview nào) và ui.test.manifest (không có androidTest).
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // MuntashirAkon ADB & Crypto dependencies
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Shizuku: kích hoạt Server bằng quyền shell không cần ADB (13.1.5 là bản
    // mới nhất trên Maven Central; đường chính là UserService + AIDL, còn
    // newProcess (đã private từ 13.0) chỉ dùng làm fallback bằng reflection
    // khi bind UserService lỗi — xem shizuku/ShizukuBridge.kt và
    // server/ShizukuNewProcess.kt).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
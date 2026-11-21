package com.example.mydung

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AdbConnectionManager private constructor(
    private val appContext: Context,
) : AbsAdbConnectionManager() {

    private val keyDirectory = File(appContext.filesDir, "adb")
    private val privateKeyFile = File(keyDirectory, "private.pk8")
    private val certificateFile = File(keyDirectory, "certificate.der")
    private val pairedFlagFile = File(keyDirectory, "paired.flag")

    private lateinit var privateKey: PrivateKey
    private lateinit var certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        setTimeout(8, TimeUnit.SECONDS)
        loadOrCreateIdentity()
    }

    companion object {
        @Volatile
        private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext)
                    .also { instance = it }
            }
        }
    }

    // ── Trạng thái ghép cặp lưu bền vững ──

    val isPaired: Boolean
        get() = pairedFlagFile.exists()

    fun markPaired() {
        pairedFlagFile.parentFile?.mkdirs()
        pairedFlagFile.writeText("1")
    }

    fun clearPaired() {
        pairedFlagFile.delete()
    }

    fun resetPairing() {
        try {
            disconnect()
        } catch (_: Exception) {
        }
        pairedFlagFile.delete()
        privateKeyFile.delete()
        certificateFile.delete()
        lastDiscoveredPairingPort = -1
        lastDiscoveredConnectPort = -1
        lastDiscoveredHost = "127.0.0.1"
        loadOrCreateIdentity()
        PairingState.log("🗑 Đã xóa sạch chứng chỉ cũ và cờ ghép cặp. Đã tạo cặp khóa mới.")
    }

    // ── Fingerprint khóa RSA / Chứng chỉ ──

    val keyFingerprint: String
        get() {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(certificate.encoded)
                hash.take(8).joinToString("") { "%02X".format(it) }
            } catch (_: Exception) {
                "UNKNOWN"
            }
        }

    // ── Sinh & nạp khóa RSA / chứng chỉ X.509 ──

    private fun loadOrCreateIdentity() {
        keyDirectory.mkdirs()

        if (privateKeyFile.exists() && certificateFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyFile.readBytes()))
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateFile.inputStream())
            PairingState.log("🔑 Đã nạp khóa đã lưu bền vững (Fingerprint=$keyFingerprint, Ngày lưu=${Date(privateKeyFile.lastModified())})")
            return
        }

        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        val keyPair = gen.generateKeyPair()
        privateKey = keyPair.private
        val pub = keyPair.public

        val now = Date()
        val oneYear = Date(System.currentTimeMillis() + 365L * 86_400_000)
        val serial = BigInteger(64, SecureRandom())
        val subj = X500Name("CN=MyDung")

        val builder = JcaX509v3CertificateBuilder(subj, serial, now, oneYear, subj, pub)
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(pub),
        )
        val signer = JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
        certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        privateKeyFile.writeBytes(privateKey.encoded)
        certificateFile.writeBytes(certificate.encoded)
        PairingState.log("🔑 Đã tạo khóa RSA & Chứng chỉ X.509 mới (Fingerprint=$keyFingerprint)")
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "MyDung"

    // ── mDNS discovery ──

    @Volatile
    var lastDiscoveredPairingPort: Int = -1

    @Volatile
    var lastDiscoveredConnectPort: Int = -1

    @Volatile
    var lastDiscoveredHost: String = "127.0.0.1"

    /**
     * Đã kết nối ADB thành công chưa — cờ do app tự theo dõi:
     * connectDevice/autoConnectDevice đặt true khi bắt tay TLS xong,
     * runShellCommand đặt false khi mở stream lỗi (kết nối đã rớt).
     * Đặt tên adbConnected (không phải isConnected) vì lớp cha
     * AbsAdbConnectionManager đã có hàm isConnected() — đặt trùng tên
     * thì getter của thuộc tính đụng JVM signature với hàm đó (lỗi
     * "Accidental override" ở CI build-4).
     */
    @Volatile
    var adbConnected: Boolean = false
        private set

    data class MdnsResult(val host: String?, val port: Int)

    fun discoverService(serviceType: String, timeoutMs: Long = 5_000): MdnsResult {
        val latch = CountDownLatch(1)
        val foundPort = AtomicInteger(-1)
        val foundHost = AtomicReference<String>(null)

        PairingState.log("mDNS: Đang tìm dịch vụ '$serviceType' (chờ tối đa ${timeoutMs / 1000}s)...")

        val mdns = AdbMdns(appContext, serviceType) { inetAddress, port ->
            if (port > 0) {
                val h = inetAddress?.hostAddress ?: "127.0.0.1"
                foundPort.set(port)
                foundHost.set(h)
                lastDiscoveredHost = h
                if (serviceType == AdbMdns.SERVICE_TYPE_TLS_PAIRING) {
                    lastDiscoveredPairingPort = port
                } else if (serviceType == AdbMdns.SERVICE_TYPE_TLS_CONNECT) {
                    lastDiscoveredConnectPort = port
                }
                PairingState.log("mDNS: ✅ Tìm thấy '$serviceType' tại host=$h, port=$port")
                latch.countDown()
            }
        }
        mdns.start()
        try {
            val resolved = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!resolved) {
                PairingState.log("mDNS: ⚠ Hết ${timeoutMs / 1000}s không nhận được gói mDNS cho '$serviceType'")
            }
        } catch (_: InterruptedException) {
            PairingState.log("mDNS: Bị ngắt khi đang tìm")
        } finally {
            mdns.stop()
        }
        return MdnsResult(foundHost.get(), foundPort.get())
    }

    fun discoverPort(serviceType: String, timeoutMs: Long = 5_000): Int {
        return discoverService(serviceType, timeoutMs).port
    }

    // ── Ghép cặp ──

    fun pairDevice(host: String, port: Int, pairingCode: String): Boolean {
        require(pairingCode.matches(Regex("\\d{6}"))) { "Mã ghép đôi phải gồm 6 chữ số" }
        require(port in 1..65535) { "Cổng ghép đôi không hợp lệ" }
        PairingState.log("Ghép cặp: Gửi mã $pairingCode tới $host:$port bằng khóa $keyFingerprint...")
        val ok = pair(host, port, pairingCode)
        if (ok) {
            markPaired()
            PairingState.log("Ghép cặp: ✅ THÀNH CÔNG! Đã lưu cờ ghép cặp với khóa $keyFingerprint")
        } else {
            PairingState.log("Ghép cặp: ❌ Thất bại (pair() trả về false)")
        }
        return ok
    }

    // ── Kết nối ──

    fun connectDevice(host: String, port: Int): Boolean {
        require(host.isNotBlank()) { "Địa chỉ IP không được để trống" }
        require(port in 1..65535) { "Cổng kết nối không hợp lệ" }

        // Đóng kết nối cũ còn sót nếu có
        try {
            disconnect()
        } catch (_: Exception) {
        }
        adbConnected = false

        PairingState.log("Kết nối ADB: Đang bắt đầu bắt tay TLS tới $host:$port bằng khóa $keyFingerprint...")
        return try {
            val ok = connect(host, port)
            adbConnected = ok
            if (ok) {
                PairingState.log("Kết nối ADB: ✅ THÀNH CÔNG! Đã kết nối với $host:$port")
            } else {
                PairingState.log("Kết nối ADB: ❌ connect() trả về false (hệ thống từ chối hoặc socket chưa sẵn sàng)")
            }
            ok
        } catch (e: AdbPairingRequiredException) {
            adbConnected = false
            PairingState.log("❌ Lỗi AdbPairingRequiredException: Thiết bị từ chối kết nối (yêu cầu ghép cặp lại). Chi tiết: ${e.message}")
            throw e
        } catch (e: AdbAuthenticationFailedException) {
            adbConnected = false
            PairingState.log("❌ Lỗi AdbAuthenticationFailedException: Thiết bị từ chối xác thực. Chi tiết: ${e.message}")
            throw e
        } catch (e: IOException) {
            adbConnected = false
            PairingState.log("❌ Lỗi kết nối mạng IO (${e.javaClass.simpleName}): ${e.message}")
            throw e
        } catch (e: Exception) {
            adbConnected = false
            PairingState.log("❌ Lỗi ngoại lệ kết nối (${e.javaClass.simpleName}): ${e.message}")
            throw e
        }
    }

    /**
     * Tự động kết nối qua mDNS.
     */
    fun autoConnectDevice(timeoutMs: Long = 7_000): Boolean {
        PairingState.log("Tự kết nối: Đang dò mDNS và kết nối (timeout ${timeoutMs / 1000}s) với khóa $keyFingerprint...")
        return try {
            val ok = autoConnect(appContext, timeoutMs)
            adbConnected = ok
            if (ok) {
                PairingState.log("Tự kết nối: ✅ THÀNH CÔNG!")
            } else {
                PairingState.log("Tự kết nối: ❌ autoConnect trả về false")
            }
            ok
        } catch (e: Exception) {
            adbConnected = false
            PairingState.log("Tự kết nối: ❌ Ngoại lệ (${e.javaClass.simpleName}): ${e.message}")
            throw e
        }
    }

    // ── Chạy lệnh shell ──

    fun runShellCommand(command: String): String {
        require(command.isNotBlank()) { "Lệnh không được để trống" }
        val stream: AdbStream = try {
            openStream("shell:$command")
        } catch (e: Exception) {
            // Mở stream lỗi = kết nối đã rớt (đổi Wi-Fi, tắt gỡ lỗi...).
            adbConnected = false
            throw e
        }
        val out = StringBuilder()
        try {
            val reader = stream.openInputStream().bufferedReader()
            val buf = CharArray(4096)
            while (true) {
                try {
                    val n = reader.read(buf)
                    if (n == -1) break
                    out.append(buf, 0, n)
                } catch (e: IOException) {
                    // libadb-android ném "Stream closed." thay vì trả -1 khi bên
                    // kia đóng stream mà không còn dữ liệu trong hàng đợi. Lệnh
                    // không in gì (như lệnh khởi động Server) luôn gặp trường hợp
                    // này. Coi đó là hết output, trả về những gì đã đọc được.
                    if (e.message == "Stream closed." || e.cause is InterruptedException) {
                        break
                    }
                    throw e
                } catch (e: java.nio.BufferOverflowException) {
                    // Output của lệnh vượt quá bộ đệm đọc của libadb (thiết bị
                    // báo max-data nhỏ hơn lượng dữ liệu gửi về trong một gói).
                    // Trả về phần đã đọc được thay vì lỗi hẳn.
                    break
                }
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
        return out.toString().trim()
    }

    fun runId(): String = runShellCommand("id")
}

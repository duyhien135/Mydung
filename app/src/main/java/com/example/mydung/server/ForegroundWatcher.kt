package com.example.mydung.server

import android.app.ActivityManager
import android.os.IBinder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bus nội bộ cho sự kiện app foreground đổi (GĐ6): ForegroundWatcher
 * publish, các kết nối SUB_FG trong Server là người nghe.
 */
object FgBus {
    fun interface Listener {
        fun onForeground(pkg: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun subscribe(l: Listener) {
        listeners.add(l)
    }

    fun unsubscribe(l: Listener) {
        listeners.remove(l)
    }

    fun publish(pkg: String) {
        for (l in listeners) {
            try {
                l.onForeground(pkg)
            } catch (_: Exception) {
                // 1 người nghe lỗi không được sập watcher.
            }
        }
    }
}

/** Parse thuần cho việc nhận diện app foreground (unit test được). */
object ForegroundParser {
    /** Parse dòng "FG <package>" từ Server. Sai -> null. */
    fun parseFgLine(line: String): String? {
        val p = line.trim().split("\\s+".toRegex())
        if (p.size != 2 || p[0] != "FG") return null
        return p[1].takeIf { it.isNotBlank() }
    }

    /** Chọn package foreground trong danh sách tiến trình đang chạy. */
    fun pickForeground(processes: List<ActivityManager.RunningAppProcessInfo>): String? {
        for (p in processes) {
            if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return p.pkgList.firstOrNull()
            }
        }
        return null
    }

    /** Parse package từ output `dumpsys window`: dòng mCurrentFocus=Window{... u0 <pkg>/...}. */
    fun parseDumpsysWindow(output: String): String? {
        val m = Regex("""mCurrentFocus=Window\{[^}]*\s+u\d+\s+([A-Za-z0-9_.]+)/""").find(output)
        return m?.groupValues?.get(1)
    }
}

/**
 * Theo dõi app đang foreground trong tiến trình Server (quyền shell),
 * publish qua FgBus khi đổi. App nhận qua lệnh SUB_FG rồi tự đổi profile
 * theo game (GĐ6). Không cần quyền Usage Access.
 *
 * Cách nhận diện (thử theo thứ tự, mỗi lần quét):
 *  1. IActivityManager.getRunningAppProcesses() qua reflection (giống
 *     cách Injector dò ServiceManager): tìm tiến trình có
 *     importance == IMPORTANCE_FOREGROUND.
 *  2. Dự phòng: parse `dumpsys window` (chỉ lấy dòng mCurrentFocus qua
 *     grep, tránh đọc cả output vài MB mỗi giây).
 *
 * LƯU Ý: không dùng IProcessObserver như GG Mouse Pro vì lớp Stub của nó
 * là API ẩn, không biên dịch được với android.jar công khai (không tạo
 * được subclass của Stub bằng reflection). Quét mỗi 1 giây đủ nhanh cho
 * việc đổi profile (không nằm trong đường xử lý input real-time).
 *
 * CHƯA CHẮC: shell (uid 2000) có thấy hết tiến trình qua
 * getRunningAppProcesses không (app thường thì chỉ thấy tiến trình của
 * mình; shell thường được xem là đặc quyền). Nếu cách 1 luôn trả null
 * thì cách 2 (dumpsys) sẽ lo phần còn lại — xem log Server để biết.
 */
object ForegroundWatcher {
    @Volatile
    private var started = false
    private var thread: Thread? = null

    @Volatile
    private var lastPkg: String? = null

    private var activityManager: Any? = null
    private var getRunningAppProcessesMethod: java.lang.reflect.Method? = null
    private var loggedAmError = false

    fun start() {
        if (started) return
        started = true
        thread = Thread({
            while (started) {
                try {
                    val pkg = detectOnce()
                    if (pkg != null && pkg != lastPkg) {
                        lastPkg = pkg
                        FgBus.publish(pkg)
                        Server.log("FG: $pkg")
                    }
                } catch (e: Exception) {
                    Server.log("FG: lỗi vòng lặp (${e.message})")
                }
                try {
                    Thread.sleep(Config.FG_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ForegroundWatcher").apply { isDaemon = true; start() }
        Server.log("ForegroundWatcher đã khởi động (quét mỗi ${Config.FG_POLL_MS}ms).")
    }

    fun stop() {
        if (!started) return
        started = false
        thread?.interrupt()
        thread = null
        lastPkg = null
    }

    /** Package foreground hiện tại (null nếu chưa nhận diện được lần nào). */
    fun currentPackage(): String? = lastPkg

    private fun detectOnce(): String? {
        try {
            detectViaActivityManager()?.let { return it }
        } catch (e: Exception) {
            // Chỉ ghi 1 lần để khỏi đầy log mỗi giây.
            if (!loggedAmError) {
                loggedAmError = true
                Server.log("FG: không dùng được ActivityManager (${e.javaClass.simpleName}: ${e.message}), dùng dumpsys.")
            }
        }
        return try {
            detectViaDumpsys()
        } catch (_: Exception) {
            null
        }
    }

    /** Cách 1: reflection vào IActivityManager (không cần biên dịch API ẩn). */
    private fun detectViaActivityManager(): String? {
        if (activityManager == null) {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
            val binder = getService.invoke(null, "activity") as? IBinder ?: return null
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
            val am = asInterface.invoke(null, binder) ?: return null
            val m = am.javaClass.getMethod("getRunningAppProcesses").apply { isAccessible = true }
            activityManager = am
            getRunningAppProcessesMethod = m
            Server.log("FG: dò ActivityManager xong, dùng getRunningAppProcesses.")
        }
        val m = getRunningAppProcessesMethod ?: return null
        @Suppress("UNCHECKED_CAST")
        val list = m.invoke(activityManager) as? List<ActivityManager.RunningAppProcessInfo>
            ?: return null
        return ForegroundParser.pickForeground(list)
    }

    /** Cách 2 (dự phòng): `dumpsys window`, lấy package ở mCurrentFocus. */
    private fun detectViaDumpsys(): String? {
        // Chỉ lấy dòng mCurrentFocus (grep) thay vì đọc cả output dumpsys
        // (vài MB) mỗi giây. Không có dòng đó -> grep ra rỗng -> null.
        val out = Shell.runOutput("/system/bin/dumpsys window | /system/bin/grep mCurrentFocus") ?: return null
        return ForegroundParser.parseDumpsysWindow(out)
    }
}

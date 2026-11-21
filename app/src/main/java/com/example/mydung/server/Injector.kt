package com.example.mydung.server

import android.os.IBinder
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * Tiêm MotionEvent vào hệ thống qua InputManager (cách scrcpy làm).
 * KHÔNG dùng lệnh `input` (chỉ 1 điểm, chậm, mở tiến trình mới mỗi lần).
 *
 * Thứ tự dò (ghi log từng bước):
 *  (1) InputManager.getInstance() (hàm tĩnh ẩn) + injectInputEvent(InputEvent, int).
 *  (2) ServiceManager.getService("input") -> IInputManager.Stub.asInterface ->
 *      injectInputEvent.
 *
 * CHƯA CHẮC: chữ ký chính xác trên API 33 / ZUI, và hidden API có bị chặn
 * với tiến trình shell không. probe() dò bằng getDeclaredMethods và log danh
 * sách hàm khớp tên để chẩn đoán. Dò hỏng -> available=false, mọi lần tiêm
 * trả false và đếm lỗi, KHÔNG sập Server. Trạng thái xem bằng MAPSTATUS.
 *
 * GĐ6: triển khai TouchInjector để TouchHub đổi được sang RawEvdevInjector.
 */
object Injector : TouchInjector {
    /** INJECT_INPUT_EVENT_MODE_ASYNC (không chờ kết quả). */
    const val MODE_ASYNC = 0

    override val name: String = InjectorMode.REFLECT.key

    private var injectMethod: java.lang.reflect.Method? = null
    private var inputManager: Any? = null
    private var setDisplayIdMethod: java.lang.reflect.Method? = null
    private var probed = false

    @Volatile
    override var available = false
        private set

    /** Số lần tiêm lỗi (xem trong MAPSTATUS). */
    @Volatile
    override var errorCount = 0L
        private set
    private var loggedErrors = 0

    /** Dò 1 lần lúc TouchHub khởi động. An toàn khi gọi nhiều lần. */
    @Synchronized
    override fun probe(): Boolean {
        if (probed) return available
        probed = true
        try {
            probePath1()
        } catch (e: Exception) {
            Server.log("TIÊM: đường 1 (InputManager.getInstance) hỏng (${e.javaClass.simpleName}: ${e.message}), thử đường 2.")
            try {
                probePath2()
            } catch (e2: Exception) {
                Server.log("TIÊM: đường 2 (ServiceManager) hỏng (${e2.javaClass.simpleName}: ${e2.message}). Không tiêm được.")
            }
        }
        // setDisplayId là API ẩn, không có cũng vẫn tiêm được (mặc định display 0).
        try {
            setDisplayIdMethod = MotionEvent::class.java.getMethod(
                "setDisplayId", Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        } catch (_: Exception) {
            Server.log("TIÊM: không có MotionEvent.setDisplayId, dùng display mặc định.")
        }
        available = injectMethod != null && inputManager != null
        Server.log("TIÊM: dò xong, available=$available.")
        return available
    }

    /** Đường 1: InputManager.getInstance() + injectInputEvent. */
    private fun probePath1() {
        val imClass = Class.forName("android.hardware.input.InputManager")
        val getInstance = imClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
        val im = getInstance.invoke(null) ?: throw IllegalStateException("getInstance() trả null")
        val matches = imClass.declaredMethods.filter { it.name == "injectInputEvent" }
        Server.log("TIÊM: InputManager có ${matches.size} hàm injectInputEvent: ${matches.joinToString { it.toString() }}")
        val m = findInjectMethod(imClass) ?: throw NoSuchMethodException("không tìm thấy injectInputEvent(InputEvent, int)")
        injectMethod = m
        inputManager = im
        Server.log("TIÊM: dùng đường 1 (InputManager.getInstance).")
    }

    /** Đường 2: ServiceManager.getService("input") -> IInputManager.Stub.asInterface. */
    private fun probePath2() {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
        val binder = getService.invoke(null, "input") as? IBinder
            ?: throw IllegalStateException("getService(\"input\") trả null")
        val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
        val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
        val iim = asInterface.invoke(null, binder) ?: throw IllegalStateException("asInterface trả null")
        val m = findInjectMethod(iim.javaClass) ?: throw NoSuchMethodException("không tìm thấy injectInputEvent trên IInputManager")
        injectMethod = m
        inputManager = iim
        Server.log("TIÊM: dùng đường 2 (ServiceManager/IInputManager).")
    }

    private fun findInjectMethod(cls: Class<*>): java.lang.reflect.Method? {
        return try {
            cls.getDeclaredMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            try {
                cls.getMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                null
            }
        }
    }

    /** Tiêm 1 đặc tả MotionEvent. Trả false nếu chưa dò được hoặc tiêm lỗi. */
    override fun inject(spec: TouchEngine.MotionEventSpec, displayId: Int): Boolean {
        val m = injectMethod ?: return false
        val im = inputManager ?: return false
        val ev = try {
            buildEvent(spec, displayId)
        } catch (e: Exception) {
            recordError("tạo MotionEvent lỗi (${e.javaClass.simpleName}: ${e.message})")
            return false
        }
        return try {
            val ok = m.invoke(im, ev, MODE_ASYNC) as Boolean
            if (!ok) recordError("injectInputEvent trả false (action=${spec.action})")
            ok
        } catch (e: Exception) {
            recordError("inject lỗi (${e.javaClass.simpleName}: ${e.message})")
            false
        } finally {
            try {
                ev.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildEvent(spec: TouchEngine.MotionEventSpec, displayId: Int): MotionEvent {
        val n = spec.pointers.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = spec.pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = spec.pointers[i].x
                y = spec.pointers[i].y
                pressure = 1f
                size = 1f
            }
        }
        val ev = MotionEvent.obtain(
            spec.downTime, spec.eventTime, spec.action, n, props, coords,
            0, 0, 1f, 1f, Config.INJECT_DEVICE_ID, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            setDisplayIdMethod?.invoke(ev, displayId)
        } catch (_: Exception) {
            // setDisplayId không bắt buộc.
        }
        return ev
    }

    private fun recordError(msg: String) {
        errorCount++
        // Ghi 5 lỗi đầu đầy đủ, sau đó cứ 50 lỗi ghi 1 dòng để khỏi đầy log.
        if (loggedErrors < 5 || errorCount % 50L == 0L) {
            loggedErrors++
            Server.log("TIÊM LỖI ($errorCount): $msg")
        }
    }
}

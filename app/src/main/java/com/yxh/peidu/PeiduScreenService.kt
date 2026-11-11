package com.yxh.peidu

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * 只为「截屏」而存在的无障碍服务：不读窗口内容（canRetrieveWindowContent=false），
 * 只用 Android 11+ 的 takeScreenshot()。系统弹授权、比 MediaProjection 省事得多。
 */
class PeiduScreenService : AccessibilityService() {

    companion object {
        const val TAG = "peidu"

        @Volatile
        private var inst: PeiduScreenService? = null

        val isAlive: Boolean get() = inst != null

        /** 截一张全屏图（软件位图，可直接裁剪/压缩） */
        fun capture(cb: (Bitmap?, String) -> Unit) {
            val s = inst
            if (s == null) {
                cb(null, "无障碍服务没在运行")
                return
            }
            try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            hb?.close()
                        } catch (e: Throwable) {
                            Log.w(TAG, "截图转位图失败: ${e.message}")
                        }
                        cb(bmp, if (bmp == null) "截图转位图失败" else "")
                    }

                    override fun onFailure(errorCode: Int) {
                        val msg = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截太频繁，等 1 秒再试"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "屏幕无效"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍权限已失效"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误"
                            else -> "截屏失败($errorCode)"
                        }
                        cb(null, msg)
                    }
                })
            } catch (e: SecurityException) {
                // 连接已被系统解绑但实例还在（无障碍被回收 / 重新授权时的典型状态）
                inst = null
                Log.w(TAG, "无障碍连接已失效，已标记为未就绪")
                cb(null, "无障碍权限已失效，请到设置里重开「陪读」")
            } catch (e: Throwable) {
                cb(null, "截屏异常：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        Log.i(TAG, "无障碍服务已连接，可以截屏")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        inst = null
        Log.w(TAG, "无障碍服务被解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要事件，只用截屏能力
    }

    override fun onInterrupt() {}
}

package com.yinyue.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor

/**
 * 无障碍服务主体：Agent 的眼睛（UI 树 / 截图）和手（手势 / 全局按键）。
 * 通过 [instance] 向全进程暴露当前连接的服务实例。
 */
class CoomiAccessibilityService : AccessibilityService() {

    @Volatile
    var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { foregroundPackage = it }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** API 30+ 使用 AccessibilityService.takeScreenshot，无需 MediaProjection 授权 */
    fun captureScreenshot(executor: Executor, callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        } catch (e: Exception) {
                            null
                        }
                        try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                        callback(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(null)
                    }
                },
            )
        } catch (e: Exception) {
            callback(null)
        }
    }

    companion object {
        @Volatile
        var instance: CoomiAccessibilityService? = null
    }
}

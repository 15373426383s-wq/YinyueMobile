package com.yinyue.mobile.service

import android.graphics.Bitmap
import com.yinyue.mobile.accessibility.CoomiAccessibilityService
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 屏幕采集（对应方案中的 ScreenCaptureService）。
 * MVP 基于 AccessibilityService.takeScreenshot（API 30+），免 MediaProjection 授权弹窗；
 * 低版本或失败时返回 null，Agent 退化为仅用 UI 树感知。
 * 后续如需视频流镜像，可在此替换为 MediaProjection 实现。
 */
object ScreenCapture {

    suspend fun capture(service: CoomiAccessibilityService): Bitmap? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                service.captureScreenshot(directExecutor) { bitmap ->
                    if (cont.isActive) cont.resume(bitmap)
                }
            }
        }

    private val directExecutor = Executor { it.run() }
}

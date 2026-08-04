package com.yinyue.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** 复杂手势：点按 / 滑动，基于 dispatchGesture */
class GestureController(private val service: AccessibilityService) {

    fun tap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), callback)
    }

    fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), callback)
    }

    private fun dispatch(gesture: GestureDescription, callback: ((Boolean) -> Unit)?) {
        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            },
            null,
        )
    }

    companion object {
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 400L
    }
}

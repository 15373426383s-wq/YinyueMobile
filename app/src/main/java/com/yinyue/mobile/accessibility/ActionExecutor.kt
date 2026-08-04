package com.yinyue.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class ActionExecutor(private val service: CoomiAccessibilityService) {

    private val gestures = GestureController(service)
    private val reader = ScreenReader(service)

    suspend fun clickElement(index: Int): String {
        val node = reader.nodeAt(index) ?: return "No element at index $index, please re-read screen"
        return try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "Clicked element $index"
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) {
                    "Element $index not clickable and area is empty"
                } else {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            gestures.tap(rect.centerX().toFloat(), rect.centerY().toFloat()) { ok ->
                                cont.resume(if (ok) "Gesture click on element $index" else "Gesture click cancelled")
                            }
                        }
                    }
                }
            }
        } finally {
            node.recycle()
        }
    }

    suspend fun inputText(index: Int, text: String): String {
        val node = reader.nodeAt(index) ?: return "No input field at index $index"
        return try {
            val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                "Input text at element $index (${text.length} chars)"
            } else {
                "Input failed: target does not support ACTION_SET_TEXT"
            }
        } finally {
            node.recycle()
        }
    }

    suspend fun scroll(direction: String): String {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val delta = dm.heightPixels * SCROLL_RATIO
        val (startY, endY) = when (direction.lowercase()) {
            "up" -> cy + delta to cy - delta
            "down" -> cy - delta to cy + delta
            "left" -> return swipeHorizontal(cx, cy, dm.widthPixels * SCROLL_RATIO, forward = true)
            "right" -> return swipeHorizontal(cx, cy, dm.widthPixels * SCROLL_RATIO, forward = false)
            else -> return "Unknown direction: $direction (up/down/left/right)"
        }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                gestures.swipe(cx, startY, cx, endY) { ok ->
                    cont.resume(if (ok) "Scrolled $direction" else "Scroll gesture cancelled")
                }
            }
        }
    }

    private suspend fun swipeHorizontal(cx: Float, cy: Float, delta: Float, forward: Boolean): String {
        val (startX, endX) = if (forward) cx + delta to cx - delta else cx - delta to cx + delta
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                gestures.swipe(startX, cy, endX, cy) { ok ->
                    cont.resume(if (ok) "Horizontal swipe" else "Swipe gesture cancelled")
                }
            }
        }
    }

    fun globalBack(): String =
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) "Back pressed" else "Back action failed"

    fun globalHome(): String =
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) "Home pressed" else "Home action failed"

    companion object {
        private const val SCROLL_RATIO = 0.35f
    }
}

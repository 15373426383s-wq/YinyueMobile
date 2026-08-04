package com.yinyue.mobile.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** An interactive element extracted from screen, with [index] as its Agent operation number */
data class UiElement(
    val index: Int,
    val text: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
)

/** UI tree parser: flattens the current window's interactive nodes into a numbered element list */
class ScreenReader(private val service: CoomiAccessibilityService) {

    @Volatile
    var lastElements: List<UiElement> = emptyList()
        private set

    fun foregroundPackage(): String? = service.foregroundPackage

    fun readTree(): List<UiElement> {
        // Bug 8: return emptyList() if root is null to avoid stale lastElements misleading Agent
        val root = service.rootInActiveWindow ?: return emptyList()
        try {
            val elements = mutableListOf<UiElement>()
            walk(root) { node, rect ->
                if (isInteractive(node) && !rect.isEmpty && node.isVisibleToUser && elements.size < MAX_ELEMENTS) {
                    elements += UiElement(
                        index = elements.size,
                        text = (node.text ?: node.contentDescription)?.toString()?.trim()?.take(60).orEmpty(),
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        bounds = Rect(rect),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                    )
                }
                false
            }
            lastElements = elements
            return elements
        } finally {
            // Bug 1: root node must be recycled after use to avoid pool exhaustion crash on API<33
            root.recycle()
        }
    }

    /** Re-traverse and locate node by index (nodes may have refreshed; index based on last readTree) */
    fun nodeAt(index: Int): AccessibilityNodeInfo? {
        // Bug 6: out-of-bounds returns null directly, consistent with MAX_ELEMENTS cutoff
        if (index < 0 || index >= MAX_ELEMENTS) return null
        val root = service.rootInActiveWindow ?: return null
        var counter = -1
        var found: AccessibilityNodeInfo? = null
        try {
            walk(root) { node, rect ->
                if (found == null && isInteractive(node) && !rect.isEmpty && node.isVisibleToUser) {
                    counter++
                    if (counter == index) {
                        found = node
                        return@walk true // Bug 5: stop on match to avoid traversing max 800 nodes
                    }
                }
                false
            }
            return found
        } finally {
            // Bug 1: recycle root; 'found' is not recycled here, caller is responsible
            root.recycle()
        }
    }

    private fun isInteractive(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isEditable || node.isScrollable || node.isCheckable

    /**
     * BFS traverse UI tree.
     * [visit] returning true signals early termination; remaining nodes in stack are recycled on stop.
     * Note: root node recycle is handled by caller in finally block.
     */
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo, Rect) -> Boolean) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            visited++
            val node = stack.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val shouldStop = visit(node, rect)
            if (shouldStop) {
                // Early termination: recycle unvisited nodes in stack to avoid leaks
                stack.forEach { it.recycle() }
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }
    }

    companion object {
        const val MAX_ELEMENTS = 80
        const val MAX_NODES = 800
    }
}

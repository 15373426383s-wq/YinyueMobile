package com.yinyue.mobile.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.yinyue.mobile.accessibility.UiElement

/** 在截图上叠加可交互元素的编号高亮框 */
@Composable
fun ElementOverlay(
    elements: List<UiElement>,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    modifier: Modifier = Modifier,
) {
    val boxColor = Color(0xFF0057D8)
    // 用 remember 缓存 Paint，避免每次重组创建新对象
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 22f
            isAntiAlias = true
        }
    }
    val labelBgPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#0057D8")
            style = android.graphics.Paint.Style.FILL
        }
    }

    Canvas(modifier) {
        elements.forEach { e ->
            val left = offsetX + e.bounds.left * scale
            val top = offsetY + e.bounds.top * scale
            val width = e.bounds.width() * scale
            val height = e.bounds.height() * scale
            if (width <= 0 || height <= 0) return@forEach

            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = 2f),
            )
            // 编号标签（左上角）
            val label = e.index.toString()
            val labelW = labelPaint.measureText(label) + 10f
            drawContext.canvas.nativeCanvas.apply {
                drawRect(left, top - 28f, left + labelW, top, labelBgPaint)
                drawText(label, left + 5f, top - 6f, labelPaint)
            }
        }
    }
}

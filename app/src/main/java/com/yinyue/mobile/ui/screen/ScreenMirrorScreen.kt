package com.yinyue.mobile.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yinyue.mobile.ui.chat.ChatViewModel
import kotlin.math.min

/** 实时屏幕镜像面板：截图 + 可交互元素高亮框 */
@Composable
fun ScreenMirrorScreen(modifier: Modifier = Modifier, viewModel: ChatViewModel) {
    val screenshot by viewModel.screenshot.collectAsState()
    val elements by viewModel.elements.collectAsState()

    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "实时屏幕镜像（${elements.size} 个元素）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.refreshScreen() }) { Text("刷新") }
        }

        val bmp = screenshot
        if (bmp == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无画面\n启动一个任务或点击右上角刷新",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = min(boxW / bmp.width, boxH / bmp.height)
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                val offX = (boxW - drawW) / 2f
                val offY = (boxH - drawH) / 2f

                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "屏幕截图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    ElementOverlay(
                        elements = elements,
                        scale = scale,
                        offsetX = offX,
                        offsetY = offY,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

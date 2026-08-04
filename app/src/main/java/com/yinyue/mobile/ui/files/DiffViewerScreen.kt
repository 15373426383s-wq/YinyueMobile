package com.yinyue.mobile.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class DiffType { SAME, ADDED, REMOVED }

data class DiffLine(val type: DiffType, val text: String)

/** 基于行级 LCS 的简易 diff（MVP，适合小文件） */
fun diffLines(oldText: String, newText: String): List<DiffLine> {
    val a = oldText.lines()
    val b = newText.lines()
    if (a.size > MAX_LINES || b.size > MAX_LINES) {
        return listOf(DiffLine(DiffType.SAME, "（文件过大，跳过对比）"))
    }
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> { result += DiffLine(DiffType.SAME, a[i]); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { result += DiffLine(DiffType.REMOVED, a[i]); i++ }
            else -> { result += DiffLine(DiffType.ADDED, b[j]); j++ }
        }
    }
    while (i < a.size) { result += DiffLine(DiffType.REMOVED, a[i]); i++ }
    while (j < b.size) { result += DiffLine(DiffType.ADDED, b[j]); j++ }
    return result
}

private const val MAX_LINES = 500

/** 文件变更对比查看器（弹层） */
@Composable
fun DiffViewerScreen(
    title: String,
    oldText: String,
    newText: String,
    onClose: () -> Unit,
) {
    val diff = remember(oldText, newText) { diffLines(oldText, newText) }
    val addedBg = Color(0xFFD6F5D6)
    val removedBg = Color(0xFFFFD9D9)
    val addedFg = Color(0xFF1B5E20)
    val removedFg = Color(0xFFB71C1C)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, maxLines = 1) },
        text = {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(diff) { line ->
                    val (bg, fg, prefix) = when (line.type) {
                        DiffType.ADDED -> Triple(addedBg, addedFg, "+ ")
                        DiffType.REMOVED -> Triple(removedBg, removedFg, "- ")
                        DiffType.SAME -> Triple(Color.Transparent, Color(0xFF44474E), "  ")
                    }
                    Column(Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp)) {
                        Text(
                            prefix + line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = fg,
                            maxLines = 3,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

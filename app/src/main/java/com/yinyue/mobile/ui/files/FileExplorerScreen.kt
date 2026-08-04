package com.yinyue.mobile.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yinyue.mobile.tools.WorkspaceDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 文件浏览面板：查看/编辑 Agent 工作目录中的文件，支持变更对比 */
@Composable
fun FileExplorerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val root = remember { WorkspaceDir.root(context) }
    var currentDir by remember { mutableStateOf(root) }
    var refreshTick by remember { mutableStateOf(0) }
    var openFile by remember { mutableStateOf<File?>(null) }

    // 每次进入文件标签页时刷新目录列表（确保 Agent 刚写入的文件立即可见）
    LaunchedEffect(Unit) {
        if (!root.exists()) root.mkdirs()
        refreshTick++
    }

    // 文件列表读取切到 IO 派发器，避免阻塞主线程
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(currentDir, refreshTick) {
        withContext(Dispatchers.IO) {
            val files = currentDir.listFiles()?.toList() ?: emptyList()
            children = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    currentDir.parentFile?.let {
                        // 用 canonicalPath + File.separator 防止前缀绕过（如 /root_evil）
                        if (it.canonicalPath == root.canonicalPath ||
                            it.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                            currentDir = it
                        }
                    }
                },
                enabled = currentDir.path != root.path,
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "上一级")
            }
            Text(
                currentDir.relativeTo(root).path.ifEmpty { "/" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        if (children.isEmpty()) {
            Text(
                "空目录。Agent 写入的文件会出现在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(children, key = { it.path }) { file ->
                ListItem(
                    leadingContent = {
                        Icon(
                            if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                        )
                    },
                    headlineContent = { Text(file.name, maxLines = 1) },
                    supportingContent = {
                        Text(if (file.isDirectory) "目录" else "${file.length()} B")
                    },
                    modifier = Modifier.clickable {
                        if (file.isDirectory) currentDir = file else openFile = file
                    },
                )
            }
        }
    }

    openFile?.let { file ->
        FileEditorDialog(
            file = file,
            onClose = { openFile = null },
            onSaved = { refreshTick++ },
        )
    }
}

@Composable
private fun FileEditorDialog(file: File, onClose: () -> Unit, onSaved: () -> Unit) {
    // 文件读取切到 IO 派发器，避免阻塞主线程
    var original by remember(file) { mutableStateOf("") }
    var edited by remember(file) { mutableStateOf("") }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            val content = runCatching { file.readText() }.getOrDefault("")
            original = content
            edited = content
        }
    }
    var showDiff by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(file.name, maxLines = 1) },
        text = {
            OutlinedTextField(
                value = edited,
                onValueChange = { edited = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 14,
            )
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { showDiff = true },
                    enabled = edited != original,
                ) { Text("对比变更") }
                TextButton(
                    onClick = {
                        // 文件写入切到 IO 派发器，避免阻塞主线程
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { file.writeText(edited) }
                            }
                            onSaved()
                            onClose()
                        }
                    },
                    enabled = edited != original,
                ) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )

    if (showDiff) {
        DiffViewerScreen(
            title = "${file.name} 变更",
            oldText = original,
            newText = edited,
            onClose = { showDiff = false },
        )
    }
}

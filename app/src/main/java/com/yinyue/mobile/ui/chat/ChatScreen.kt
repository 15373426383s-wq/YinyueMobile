package com.yinyue.mobile.ui.chat


import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinyue.mobile.data.model.MessageEntity
import com.yinyue.mobile.data.model.MessageKind
import com.yinyue.mobile.data.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ChatScreen(modifier: Modifier = Modifier, viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val running by viewModel.running.collectAsState()
    val pending by viewModel.pendingApproval.collectAsState()
    var input by remember { mutableStateOf("") }
    var plusExpanded by remember { mutableStateOf(false) }
    var pendingAttachment by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    // 相册选择
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) pendingAttachment = uri
    }

    // 文件选择
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) pendingAttachment = uri
    }

    Column(modifier) {
        val chatItems = remember(messages) { messages.groupTurns() }

        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // 方案3：监听用户拖拽——手指按住时不补偿不自动滚
        val isScrolling = listState.isScrollInProgress

        // 方案3：检测是否处于贴底状态
        val isAtBottom by remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                val totalCount = listState.layoutInfo.totalItemsCount
                totalCount > 0 && lastVisible >= totalCount - 1
            }
        }

        // 方案3：仅在贴底且非拖拽时自动滚底
        LaunchedEffect(messages.size) {
            if (isAtBottom && !isScrolling) {
                val targetIndex = chatItems.size - 1 + if (running) 1 else 0
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                }
            }
        }

        // 判断是否显示"回到底部"箭头按钮：最后一个可见项不是列表末尾时显示
        val showScrollToBottom by remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                val totalCount = listState.layoutInfo.totalItemsCount
                totalCount > 0 && lastVisible < totalCount - 1
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chatItems, key = { chatItem ->
                    when (chatItem) {
                        is ChatItem.Plain -> "msg_${chatItem.message.id}"
                        is ChatItem.Turn -> "turn_${chatItem.group.thinking.id}"
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.Plain -> {
                            when (item.message.role) {
                                Role.USER -> UserBubble(item.message.content)
                                Role.ASSISTANT -> AssistantBubble(item.message.content)
                                else -> SystemNote(item.message.content)
                            }
                        }
                        is ChatItem.Turn -> {
                            ThinkingGroup(
                                thinking = item.group.thinking,
                                tools = item.group.tools,
                                isLast = item.group.isLast,
                                running = running,
                                listState = listState,
                                isScrolling = isScrolling,
                                isAtBottom = isAtBottom,
                                scope = scope,
                            )
                        }
                    }
                }

                if (running) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Agent 运行中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 右下角浮动箭头按钮：离开底部显示，点击平滑滚到底部
            if (showScrollToBottom) {
                FloatingActionButton(
                    onClick = {
                        val totalItems = chatItems.size + if (running) 1 else 0
                        if (totalItems > 0) {
                            scope.launch {
                                listState.animateScrollToItem(totalItems - 1)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "回到底部",
                    )
                }
            }
        }

        pending?.let {
            ApprovalCard(
                pending = it,
                onApprove = viewModel::approve,
                onReject = viewModel::reject,
                onAlwaysAllow = viewModel::alwaysAllow, // 新增：始终允许回调
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { plusExpanded = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
                DropdownMenu(
                    expanded = plusExpanded,
                    onDismissRequest = { plusExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("相册") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                        onClick = { plusExpanded = false; photoPicker.launch(PickVisualMediaRequest()) },
                    )
                    DropdownMenuItem(
                        text = { Text("文件") },
                        leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                        onClick = { plusExpanded = false; filePicker.launch("*/*") },
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                // 待发送附件预览
                pendingAttachment?.let { uri ->
                    val name = remember(uri) { extractFileName(context, uri) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Card(
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    modifier = Modifier.size(18.dp),
                                    onClick = { pendingAttachment = null },
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "移除附件",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("") },
                    maxLines = 3,
                )
            }
            if (running) {
                IconButton(onClick = { viewModel.stopAgent() }) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                IconButton(
                    onClick = {
                        val uri = pendingAttachment
                        if (uri != null) {
                            scope.launch {
                                val path = copyToWorkspace(context, uri)
                                if (path != null) {
                                    viewModel.send(input, listOf(path))
                                    input = ""
                                    pendingAttachment = null
                                }
                            }
                        } else {
                            viewModel.send(input)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() || pendingAttachment != null,
                ) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/** 从 URI 提取文件名（主线程可直接调用，结果被 remember 缓存） */
private fun extractFileName(context: android.content.Context, uri: Uri): String {
    return try {
        val cr = context.contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
        } ?: "unknown"
    } catch (_: Exception) { "unknown" }
}

/** 将 URI 对应的文件复制到 workspace/uploads，返回绝对路径 */
private suspend fun copyToWorkspace(context: android.content.Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val cr = context.contentResolver
            val name = extractFileName(context, uri)
            val workspace = (context.applicationContext as com.yinyue.mobile.YinyueApp).settings.workspaceDir
                .ifBlank { context.filesDir.absolutePath }
            val dest = File(workspace, "uploads").also { it.mkdirs() }.resolve(name)
            cr.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (_: Exception) { null }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 300.dp)) {
                if (text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 320.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ToolResultCard(msg: MessageEntity) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "工具调用: ${msg.toolName ?: "?"}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            msg.toolArgs?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
            }
            Text(
                msg.toolResult ?: msg.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 消息条目（按原始时间顺序） ──

private sealed class ChatItem {
    data class Plain(val message: MessageEntity) : ChatItem()
    data class Turn(val group: TurnGroup) : ChatItem()
}

private data class TurnGroup(
    val thinking: MessageEntity,
    val tools: List<MessageEntity>,
    val isLast: Boolean,
)

// 判断是否为工具调用消息（TOOL kind 或非 THINKING 但角色为 TOOL）
private fun isToolMessage(msg: MessageEntity): Boolean =
    msg.kind == MessageKind.TOOL || (msg.kind != MessageKind.THINKING && msg.role == Role.TOOL)

private fun List<MessageEntity>.groupTurns(): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var i = 0
    while (i < size) {
        val msg = this[i]
        if (msg.kind != MessageKind.THINKING) {
            items.add(ChatItem.Plain(msg))
            i++
            continue
        }

        // msg is THINKING — 开始处理一轮 agent turn
        // 一轮内连续的 (THINKING+ → TOOL+) 合并为单个 TurnGroup
        val thinkingContents = mutableListOf<String>()
        val tools = mutableListOf<MessageEntity>()
        var groupRefMsg: MessageEntity? = null // 用于 TurnGroup 的 thinking 字段（取最后一条有 TOOL 跟随的 THINKING）

        while (i < size && this[i].kind == MessageKind.THINKING) {
            // 收集连续的 THINKING 消息作为一个思考块
            val blockStart = i
            while (i < size && this[i].kind == MessageKind.THINKING) i++
            val thinkBlock = (blockStart until i).map { this[it] }

            if (i < size && isToolMessage(this[i])) {
                // 有 TOOL 跟随 → 中间思考步骤，合并到 group 中
                thinkingContents.addAll(thinkBlock.map { it.content })
                groupRefMsg = thinkBlock.last()
                while (i < size && isToolMessage(this[i])) {
                    tools.add(this[i])
                    i++
                }
                // 如果 TOOL 之后没有更多 THINKING（或已到末尾），立即输出合并后的 TurnGroup
                if (i >= size || this[i].kind != MessageKind.THINKING) {
                    val synthetic = groupRefMsg.copy(content = thinkingContents.joinToString("\n\n"))
                    items.add(ChatItem.Turn(TurnGroup(synthetic, tools, false)))
                    break
                }
            } else {
                // 无 TOOL 跟随 → 最终回答
                if (thinkingContents.isNotEmpty() && groupRefMsg != null) {
                    val synthetic = groupRefMsg.copy(content = thinkingContents.joinToString("\n\n"))
                    items.add(ChatItem.Turn(TurnGroup(synthetic, tools, false)))
                }
                for (t in thinkBlock) items.add(ChatItem.Plain(t))
                break
            }
        }
    }
    // 标记最后一个 Turn 为 isLast
    val lastTurnIndex = items.indexOfLast { it is ChatItem.Turn }
    if (lastTurnIndex >= 0) {
        val lastTurn = items[lastTurnIndex] as ChatItem.Turn
        items[lastTurnIndex] = ChatItem.Turn(lastTurn.group.copy(isLast = true))
    }
    return items
}

@Composable
private fun ThinkingGroup(
    thinking: MessageEntity,
    tools: List<MessageEntity>,
    isLast: Boolean,
    running: Boolean,
    listState: LazyListState,
    isScrolling: Boolean,
    isAtBottom: Boolean,
    scope: CoroutineScope,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(running, isLast) {
        if (running && isLast && isAtBottom) expanded = true
    }

    // 方案3：animateContentSize + finished 回调中一次性 scrollBy 补偿高度差
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.animateContentSize(
            finishedListener = { initialSize, targetSize ->
                if (!isScrolling) {
                    val delta = targetSize.height - initialSize.height
                    if (delta != 0) {
                        scope.launch {
                            listState.scrollBy(delta.toFloat())
                        }
                    }
                }
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "思考过程",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running && isLast) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            }
        }

        if (expanded) {
            Column(
                Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    thinking.content,
                    Modifier.padding(start = 20.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )

                tools.forEach { ToolResultCard(it) }
            }
        }
    }
}

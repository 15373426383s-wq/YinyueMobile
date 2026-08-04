package com.yinyue.mobile.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.mobile.YinyueApp
import com.yinyue.mobile.accessibility.CoomiAccessibilityService
import com.yinyue.mobile.agent.AgentMode
import com.yinyue.mobile.agent.ModelManager
import com.yinyue.mobile.ui.chat.ChatScreen
import com.yinyue.mobile.ui.chat.ChatViewModel
import com.yinyue.mobile.ui.chat.ThreadListScreen
import com.yinyue.mobile.ui.files.FileExplorerScreen
import com.yinyue.mobile.ui.history.TaskHistoryScreen
import com.yinyue.mobile.ui.screen.ScreenMirrorScreen
import com.yinyue.mobile.ui.theme.YinyueTheme
import android.widget.Toast
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YinyueTheme { MainNavHost() } }
    }
}

private enum class Panel { CHAT, SCREEN, FILES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavHost(viewModel: ChatViewModel = viewModel()) {
    var panel by remember { mutableStateOf(Panel.CHAT) }
    var showHistory by remember { mutableStateOf(false) }
    var showThreads by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsState()
    val taskId by viewModel.taskId.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if ((context.applicationContext as YinyueApp).settings.apiKey.isBlank()) {
            showApiKeyDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showHistory) "任务历史" else "银月") },
                navigationIcon = {
                    if (!showHistory) {
                        IconButton(onClick = { showThreads = true }) {
                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "线程")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(Icons.Default.History, contentDescription = "历史")
                    }
                    IconButton(onClick = { showApiKeyDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = panel == Panel.CHAT && !showHistory,
                    onClick = { panel = Panel.CHAT; showHistory = false },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("对话") },
                )
                NavigationBarItem(
                    selected = panel == Panel.SCREEN && !showHistory,
                    onClick = { panel = Panel.SCREEN; showHistory = false },
                    icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                    label = { Text("屏幕") },
                )
                NavigationBarItem(
                    selected = panel == Panel.FILES && !showHistory,
                    onClick = { panel = Panel.FILES; showHistory = false },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("文件") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            showHistory -> TaskHistoryScreen(
                modifier = modifier,
                tasks = tasks,
                onOpenTask = { id ->
                    viewModel.openTask(id)
                    showHistory = false
                    panel = Panel.CHAT
                },
                onDeleteTask = viewModel::deleteTask,
                onClearAll = viewModel::clearAllTasks,
            )
            panel == Panel.CHAT -> ChatScreen(modifier = modifier, viewModel = viewModel)
            panel == Panel.SCREEN -> ScreenMirrorScreen(modifier = modifier, viewModel = viewModel)
            panel == Panel.FILES -> FileExplorerScreen(modifier = modifier)
        }
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(onDismiss = { showApiKeyDialog = false }, viewModel = viewModel)
    }

    if (showThreads) {
        ThreadListScreen(
            tasks = tasks,
            currentTaskId = taskId,
            onSelect = { id ->
                viewModel.openTask(id)
                showThreads = false
            },
            onNewConversation = {
                viewModel.newConversation()
                showThreads = false
            },
            onDismiss = { showThreads = false },
        )
    }
}

@Composable
private fun ApiKeyDialog(onDismiss: () -> Unit, viewModel: ChatViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as YinyueApp
    val settings = app.settings
    val modelManager = remember { ModelManager(context) }
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var useLocal by remember { mutableStateOf(settings.useLocalModel) }
    var workspaceDir by remember { mutableStateOf(settings.workspaceDir) }
    var a11yOn by remember { mutableStateOf(CoomiAccessibilityService.instance != null) }

    // 下载状态
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatus by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(
        if (settings.localBackend == "mnn") modelManager.isMnnModelReady
        else modelManager.isModelReady
    ) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("银月 设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // ── 云端 API ──
                if (!useLocal) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("API Key") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("模型") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = workspaceDir, onValueChange = { workspaceDir = it; settings.workspaceDir = it },
                    label = { Text("文件保存路径（留空为默认）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如: /storage/emulated/0/Yinyue") },
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // ── Agent 模式 ──
                val currentMode by viewModel.agentMode.collectAsState()
                var modeExpanded by remember { mutableStateOf(false) }
                Text("Agent 模式", style = MaterialTheme.typography.bodyMedium)
                Box {
                    TextButton(onClick = { modeExpanded = true }) {
                        Text("当前: ${currentMode.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    DropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false },
                    ) {
                        AgentMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(if (mode == currentMode) "${mode.label} ?" else mode.label) },
                                onClick = { viewModel.setAgentMode(mode); modeExpanded = false },
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // ── 本地模型 ──
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "本地模型（离线推理）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (settings.localBackend == "mnn") {
                                if (modelReady) "MNN 模型已就绪"
                                else "MNN 模型未部署"
                            } else {
                                if (modelReady) "${modelManager.currentModel.name} 已就绪"
                                else "模型未下载，需约 ${"%.1f".format(modelManager.currentModel.ggufSize.toFloat() / 1024 / 1024 / 1024)}GB 存储"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useLocal, onCheckedChange = { useLocal = it })
                }

                if (!modelReady && settings.localBackend != "mnn") {
                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            downloadStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    downloading = true
                                    try {
                                        modelManager.download(modelManager.currentModel)
                                            .collect { (dl, total, desc) ->
                                                downloadProgress = if (total > 0) dl.toFloat() / total else 0f
                                                downloadStatus = "$desc: ${dl / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                                            }
                                        modelReady = true
                                        downloadStatus = "下载完成"
                                        if (useLocal) {
                                            app.loadLocalModel { msg ->
                                                downloadStatus = msg
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        downloadStatus = "下载失败: ${e.message}"
                                        Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        downloading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("下载本地模型（~${"%.1f".format(modelManager.currentModel.ggufSize.toFloat() / 1024 / 1024 / 1024)}GB）")
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // ── 无障碍 ──
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "屏幕操作（无障碍服务）",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = a11yOn,
                        onCheckedChange = { on ->
                            a11yOn = on
                            if (on) {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.apiKey = key
                settings.baseUrl = baseUrl
                settings.model = model
                settings.useLocalModel = useLocal
                if (useLocal && modelReady) {
                    if (app.localEngine == null) {
                        app.loadLocalModel { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                } else if (!useLocal) {
                    app.freeLocalModel()
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

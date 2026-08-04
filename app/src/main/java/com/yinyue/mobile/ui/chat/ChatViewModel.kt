package com.yinyue.mobile.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.mobile.YinyueApp
import com.yinyue.mobile.accessibility.ClickElementTool
import com.yinyue.mobile.accessibility.CoomiAccessibilityService
import com.yinyue.mobile.accessibility.GlobalActionTool
import com.yinyue.mobile.accessibility.InputTextTool
import com.yinyue.mobile.accessibility.ReadScreenTool
import com.yinyue.mobile.accessibility.ScreenReader
import com.yinyue.mobile.accessibility.ScrollTool
import com.yinyue.mobile.accessibility.UiElement
import com.yinyue.mobile.agent.AgentLoop
import com.yinyue.mobile.agent.AgentMode
import com.yinyue.mobile.agent.AgentStep
import com.yinyue.mobile.agent.LLMClient
import com.yinyue.mobile.agent.ToolRegistry
import com.yinyue.mobile.data.model.MessageEntity
import com.yinyue.mobile.data.model.MessageKind
import com.yinyue.mobile.data.model.Role
import com.yinyue.mobile.data.model.TaskEntity
import com.yinyue.mobile.data.model.TaskStatus
import com.yinyue.mobile.data.repository.ChatRepository
import com.yinyue.mobile.data.repository.TaskRepository
import com.yinyue.mobile.service.AgentForegroundService
import com.yinyue.mobile.service.ScreenCapture
import com.yinyue.mobile.tools.ClipboardTool
import com.yinyue.mobile.tools.DeleteFileTool
import com.yinyue.mobile.tools.FetchUrlTool
import com.yinyue.mobile.tools.LaunchAppTool
import com.yinyue.mobile.tools.ListAppsTool
import com.yinyue.mobile.tools.ListFilesTool
import com.yinyue.mobile.tools.OpenUrlTool
// import com.yinyue.mobile.tools.PythonExecTool  // Disabled: Chaquopy plugin not available
import com.yinyue.mobile.tools.ShellTool
import com.yinyue.mobile.tools.WebSearchTool
import com.yinyue.mobile.tools.WriteFileTool
import com.yinyue.mobile.tools.ReadFileTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingApproval(val toolName: String, val args: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    // Fix #5: Renamed variable from same-named class YinyueApp to yinyueApp
    private val yinyueApp = app as YinyueApp
    private val chatRepo = ChatRepository(yinyueApp.database)
    private val taskRepo = TaskRepository(yinyueApp.database)

    // Inject context for permission system persistence (Operit-style ToolPermissionSystem)
    private val registry = ToolRegistry(context = app).apply {
        // Screen operations
        register(ClickElementTool())
        register(InputTextTool())
        register(ScrollTool())
        register(GlobalActionTool())
        register(ReadScreenTool())
        // App & system
        register(LaunchAppTool(app))
        register(ListAppsTool(app))
        register(ClipboardTool(app))
        // Files
        register(ListFilesTool(app))
        register(ReadFileTool(app))
        register(WriteFileTool(app))
        register(DeleteFileTool(app))
        // Network
        register(ShellTool())
        register(FetchUrlTool())
        register(OpenUrlTool(app))
        // register(PythonExecTool())  // Disabled: Chaquopy plugin not available
        register(WebSearchTool())
    }
    private val agentLoop = AgentLoop(
        yinyueApp.settings,
        LLMClient(yinyueApp.settings),
        registry,
    )

    val tasks: StateFlow<List<TaskEntity>> = taskRepo.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _taskId = MutableStateFlow<String?>(null)
    val taskId = _taskId.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval = _pendingApproval.asStateFlow()

    private val _screenshot = MutableStateFlow<Bitmap?>(null)
    val screenshot = _screenshot.asStateFlow()

    private val _elements = MutableStateFlow<List<UiElement>>(emptyList())
    val elements = _elements.asStateFlow()

    private val _agentMode = MutableStateFlow(AgentMode.FULL)
    val agentMode = _agentMode.asStateFlow()

    val showThinking: Boolean get() = yinyueApp.settings.showThinking

    fun setAgentMode(mode: AgentMode) { _agentMode.value = mode }

    private var messagesJob: Job? = null
    private var agentJob: Job? = null

    fun openTask(id: String) {
        if (_taskId.value == id) return
        _taskId.value = id
        agentLoop.resetConversation()
        messagesJob?.cancel()
        messagesJob = chatRepo.messages(id)
            .onEach { _messages.value = it }
            .launchIn(viewModelScope)
    }

    fun send(text: String, imagePaths: List<String> = emptyList()) {
        // trim before emptiness check to avoid ifEmpty guard always false
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && imagePaths.isEmpty()) || _running.value) return
        val goal = trimmed.ifEmpty { "Please view the image below" }
        viewModelScope.launch {
            val id = _taskId.value ?: taskRepo.create(goal.take(24)).id.also { openTask(it) }
            val imgs = imagePaths.ifEmpty { null }?.joinToString(",")
            chatRepo.append(MessageEntity(
                taskId = id, role = Role.USER, content = goal, imagePaths = imgs,
            ))
            runAgent(id, goal, imagePaths)
        }
    }

    private fun runAgent(id: String, goal: String, imagePaths: List<String> = emptyList()) {
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            _running.value = true
            taskRepo.setStatus(id, TaskStatus.RUNNING)
            AgentForegroundService.start(getApplication(), goal.take(30))
            try {
                agentLoop.run(goal, _agentMode.value, imagePaths).collect { step ->
                    when (step) {
                        is AgentStep.Thinking -> chatRepo.append(
                            MessageEntity(taskId = id, role = Role.ASSISTANT, content = step.text, kind = MessageKind.THINKING),
                        )
                        is AgentStep.ToolCallStarted -> Unit
                        is AgentStep.ToolFinished -> chatRepo.append(
                            MessageEntity(
                                taskId = id, role = Role.TOOL,
                                content = step.result,
                                kind = MessageKind.TOOL,
                                toolName = step.call.name,
                                toolArgs = step.call.argumentsJson,
                                toolResult = step.result,
                            ),
                        )
                        is AgentStep.WaitingApproval -> {
                            _pendingApproval.value = PendingApproval(step.call.name, step.call.argumentsJson)
                            taskRepo.setStatus(id, TaskStatus.WAITING_APPROVAL)
                        }
                        is AgentStep.PlanReady -> {
                            chatRepo.append(
                                MessageEntity(taskId = id, role = Role.SYSTEM, content = step.planText),
                            )
                            taskRepo.setStatus(id, TaskStatus.DONE)
                        }
                        is AgentStep.ScreenUpdated -> {
                            _screenshot.value = step.screenshot
                            _elements.value = CoomiAccessibilityService.instance
                                ?.let { ScreenReader(it).lastElements }
                                .orEmpty()
                        }
                        is AgentStep.Done -> taskRepo.setStatus(id, TaskStatus.DONE)
                        is AgentStep.Failed -> {
                            chatRepo.append(
                                MessageEntity(taskId = id, role = Role.SYSTEM, content = step.reason),
                            )
                            taskRepo.setStatus(id, TaskStatus.FAILED)
                        }
                    }
                }
            } catch (e: Exception) {
                // Fix #3: Clear _pendingApproval on exception to avoid UI stuck on confirmation dialog
                _pendingApproval.value = null
                chatRepo.append(
                    MessageEntity(taskId = id, role = Role.SYSTEM, content = "Run error: ${e.message}"),
                )
                taskRepo.setStatus(id, TaskStatus.FAILED)
            } finally {
                _running.value = false
                AgentForegroundService.stop(getApplication())
            }
        }
    }

    fun approve() {
        _pendingApproval.value = null
        agentLoop.approvalGate.resolve(true)
    }

    fun reject() {
        _pendingApproval.value = null
        agentLoop.approvalGate.resolve(false)
    }

    /** Always allow: persist override as ALLOW, then release current. Inspired by Operit ALWAYS_ALLOW. */
    fun alwaysAllow() {
        _pendingApproval.value?.let { registry.setAlwaysAllow(it.toolName) }
        _pendingApproval.value = null
        agentLoop.approvalGate.resolve(true)
    }

    fun stopAgent() {
        agentJob?.cancel()
        agentLoop.approvalGate.resolve(false)
    }

    /** Manually refresh screen panel */
    fun refreshScreen() {
        viewModelScope.launch {
            val service = CoomiAccessibilityService.instance ?: return@launch
            try {
                val reader = ScreenReader(service)
                // readTree is CPU-intensive, dispatch to Default
                _elements.value = withContext(Dispatchers.Default) { reader.readTree() }
                // screenshot involves IO, dispatch to IO
                _screenshot.value = withContext(Dispatchers.IO) { ScreenCapture.capture(service) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun newConversation() {
        _taskId.value = null
        messagesJob?.cancel()
        _messages.value = emptyList()
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            taskRepo.delete(taskId)
            chatRepo.deleteAll(taskId)
            if (_taskId.value == taskId) _taskId.value = null
        }
    }

    fun clearAllTasks() {
        viewModelScope.launch {
            taskRepo.clearAll()
            chatRepo.clearAll()
            _taskId.value = null
        }
    }
}

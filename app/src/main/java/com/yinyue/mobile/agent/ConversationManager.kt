package com.yinyue.mobile.agent

import com.yinyue.mobile.data.model.Role

/** 对话历史管理器。借鉴 Operit AIMessageManager，从 AgentLoop 抽离历史管理职责。 */
class ConversationManager(private val prompts: PromptBuilder) {
    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages

    fun reset() {
        _messages.clear()
        _messages += ChatMessage.text(Role.SYSTEM, prompts.systemPrompt())
    }

    fun add(message: ChatMessage) { _messages += message }

    fun addUserText(text: String) {
        _messages += ChatMessage.text(Role.USER, text)
    }

    fun addUserWithImages(text: String, imagePaths: List<String>) {
        _messages += ChatMessage.withImages(Role.USER, text, imagePaths)
    }

    fun addToolResult(toolCallId: String, name: String, result: String) {
        _messages += ChatMessage.text(
            Role.TOOL, result.take(ToolExecutionLimits.TOOL_RESULT_MAX_CHARS),
            toolCallId = toolCallId, name = name,
        )
    }

    /** 截断历史，保留 SYSTEM + 最近 N 条，回溯补齐 tool 配对。借鉴 Operit 上下文限制器。 */
    fun trim(maxMessages: Int = ToolExecutionLimits.AGENT_MAX_CONVERSATION_MESSAGES) {
        if (_messages.size <= maxMessages + 1) return
        val systemMsg = _messages.firstOrNull { it.role == Role.SYSTEM }
        val recent = _messages.takeLast(maxMessages).toMutableList()
        // 修复 1.3：向前回溯，确保 tool 响应前面带对应的 assistant.tool_calls，避免 API 400
        while (recent.isNotEmpty() && recent.first().role == Role.TOOL) {
            val idx = _messages.indexOf(recent.first()) - 1
            if (idx < 0) break
            recent.add(0, _messages[idx])
        }
        _messages.clear()
        if (systemMsg != null) _messages.add(systemMsg)
        _messages.addAll(recent)
    }

    fun isEmpty() = _messages.isEmpty()
    fun size() = _messages.size
}
package com.yinyue.mobile.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yinyue.mobile.YinyueApp
import com.yinyue.mobile.accessibility.CoomiAccessibilityService
import com.yinyue.mobile.accessibility.ScreenReader
import com.yinyue.mobile.data.SettingsStore
import com.yinyue.mobile.data.model.Role
import com.yinyue.mobile.data.model.ToolCall
import com.yinyue.mobile.service.ScreenCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Agent step events emitted during execution */
sealed interface AgentStep {
    data class Thinking(val text: String) : AgentStep
    data class ToolCallStarted(val call: ToolCall) : AgentStep
    data class ToolFinished(val call: ToolCall, val result: String) : AgentStep
    data class WaitingApproval(val call: ToolCall) : AgentStep
    data class ScreenUpdated(val elementCount: Int, val screenshot: Bitmap?) : AgentStep
    data class PlanReady(val calls: List<ToolCall>, val planText: String) : AgentStep
    data class Done(val summary: String) : AgentStep
    data class Failed(val reason: String) : AgentStep
}

/** Approval gate for dangerous operations */
class ApprovalGate {
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    suspend fun awaitApproval(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return deferred.await()
    }

    fun resolve(approved: Boolean) {
        pending?.complete(approved)
        pending = null
    }
}

/**
 * Agent core loop: perception (screenshot + UI tree) -> decision (LLM) -> execution (tools) -> verify (next perception).
 * Delegates conversation history management to ConversationManager and tool execution to ToolExecutionManager.
 */
class AgentLoop(
    private val settings: SettingsStore,
    private val llm: LLMClient,
    private val tools: ToolRegistry,
    private val prompts: PromptBuilder = PromptBuilder(),
) {
    val approvalGate = ApprovalGate()

    @Volatile var lastScreenshot: Bitmap? = null
        private set

    private val conversation = ConversationManager(prompts)
    private val toolExec = ToolExecutionManager(tools, approvalGate)

    fun resetConversation() = conversation.reset()

    fun run(goal: String, agentMode: AgentMode = AgentMode.FULL,
            imagePaths: List<String> = emptyList()): Flow<AgentStep> = flow {
        val service = CoomiAccessibilityService.instance
        val textOnly = service == null
        if (!settings.useLocalModel && settings.apiKey.isBlank()) {
            emit(AgentStep.Failed("API Key not configured: tap the settings icon in top-right to fill in, or switch to local model"))
            return@flow
        }
        if (conversation.isEmpty()) resetConversation()
        val reader = if (!textOnly) ScreenReader(service!!) else null
        // Add user message
        if (imagePaths.isNotEmpty()) {
            conversation.addUserWithImages(goal, imagePaths)
        } else {
            conversation.addUserText(goal)
        }

        val firstImage: Bitmap? = if (imagePaths.isNotEmpty()) {
            imagePaths.firstOrNull()?.let { BitmapFactory.decodeFile(it) }
        } else null

        // ASK mode: skip perception and tools, pure text chat
        if (agentMode == AgentMode.ASK) {
            val choice = try {
                llm.chat(conversation.messages, emptyList(), image = firstImage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit(AgentStep.Failed("Model call failed: ${e.message}"))
                return@flow
            }
            val text = choice.message.contentText()
            emit(AgentStep.Thinking(text))
            emit(AgentStep.Done(text.ifEmpty { "Task complete" }))
            return@flow
        }

        repeat(ToolExecutionLimits.AGENT_MAX_STEPS) {
            // Trim conversation before each LLM call
            conversation.trim()

            if (!textOnly) {
                // 1. Perception: screenshot + UI tree
                val tPercep = System.currentTimeMillis()
                val elements = reader!!.readTree()
                val screenshot = ScreenCapture.capture(service!!)
                lastScreenshot = screenshot
                emit(AgentStep.ScreenUpdated(elements.size, screenshot))
                conversation.addUserText(
                    prompts.buildScreenMessage(elements, reader.foregroundPackage()),
                )
                val tPercepEnd = System.currentTimeMillis()
                android.util.Log.i("AgentLoop", "[TIMING] step:$it perception(screenshot+tree): ${tPercepEnd - tPercep}ms")
            }

            // Step 0 uses user image if provided, subsequent steps use screenshot
            val imageForLLM = if (it == 0 && firstImage != null) firstImage else lastScreenshot

            // 2. Decision: call LLM
            val tLlm = System.currentTimeMillis()
            val choice = try {
                llm.chat(conversation.messages, tools.toolSpecs(), image = imageForLLM)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit(AgentStep.Failed("Model call failed: ${e.message}"))
                return@flow
            }
            val tLlmEnd = System.currentTimeMillis()
            android.util.Log.i("AgentLoop", "[TIMING] step:$it LLM call: ${tLlmEnd - tLlm}ms")
            val msg = choice.message
            conversation.add(msg)
            val text = stripThinking(msg.contentText())
            if (text.isNotEmpty()) emit(AgentStep.Thinking(text))

            // 3. No tool calls = task done
            val calls = msg.toolCalls.orEmpty()
                .map { ToolCall(it.id, it.function.name, it.function.arguments) }
            if (calls.isEmpty()) {
                emit(AgentStep.Done(text.ifEmpty { "Task complete" }))
                return@flow
            }

            // PLAN mode: output plan on first tool call and pause
            if (agentMode == AgentMode.PLAN) {
                val planText = buildString {
                    appendLine("Plan (${calls.size} steps):")
                    calls.forEachIndexed { i, c ->
                        appendLine("${i + 1}. ${c.name}(${c.argumentsJson})")
                    }
                }
                conversation.add(ChatMessage.text(
                    Role.SYSTEM,
                    "[PLAN MODE] The following plan has been generated. Switch to FULL/SIMULATE mode to continue:\n$planText",
                ))
                emit(AgentStep.PlanReady(calls, planText))
                return@flow
            }

            // 4. Execute tools: permission check + confirmation + execution + result backfill
            toolExec.executeCalls(
                calls = calls,
                agentMode = agentMode,
                emit = { emit(it) },
            ) { call, result ->
                conversation.addToolResult(call.id, call.name, result)
            }
        }
        firstImage?.recycle()
        emit(AgentStep.Done("Reached max steps ($MAX_STEPS), task has too many sub-steps. Continue by telling Agent what to do next."))
    }.flowOn(Dispatchers.Default)

    /**
     * Strip <think>...</think> blocks from model output when showThinking is disabled.
     * Handles well-formed <think>...</think> and malformed ...</think> (missing opening tag).
     */
    private fun stripThinking(text: String): String {
        if (settings.showThinking) return text
        var cleaned = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        val idx = cleaned.indexOf("</think>")
        if (idx >= 0) {
            cleaned = cleaned.substring(idx + 8).trimStart()
        }
        return cleaned.trim()
    }

    companion object {
        val MAX_STEPS = ToolExecutionLimits.AGENT_MAX_STEPS
        val MAX_CONVERSATION_MESSAGES = ToolExecutionLimits.AGENT_MAX_CONVERSATION_MESSAGES
    }
}

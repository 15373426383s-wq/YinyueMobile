package com.yinyue.mobile.agent

import android.graphics.Bitmap
import android.util.Base64
import com.yinyue.mobile.YinyueApp
import com.yinyue.mobile.data.SettingsStore
import com.yinyue.mobile.data.model.Role
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// ---------- OpenAI 兼容协议 DTO（DeepSeek 直接可用） ----------

data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun text(role: String, text: String, toolCalls: List<ToolCallDto>? = null,
                 toolCallId: String? = null, name: String? = null) =
            ChatMessage(role, JsonPrimitive(text), toolCalls, toolCallId, name)

        fun withImages(role: String, text: String, imagePaths: List<String>,
                       toolCalls: List<ToolCallDto>? = null, toolCallId: String? = null, name: String? = null): ChatMessage {
            val arr = JsonArray()
            arr.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
            for (path in imagePaths) {
                try {
                    val bytes = File(path).readBytes()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val mime = when {
                        path.endsWith(".png", ignoreCase = true) -> "image/png"
                        path.endsWith(".gif", ignoreCase = true) -> "image/gif"
                        path.endsWith(".webp", ignoreCase = true) -> "image/webp"
                        else -> "image/jpeg"
                    }
                    arr.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            addProperty("url", "data:$mime;base64,$b64")
                        })
                    })
                } catch (e: Exception) {
                    android.util.Log.w("LLMClient", "图片加载失败: $path", e) // 修复 2.2：不再静默吞异常，至少打日志
                }
            }
            return ChatMessage(role, arr, toolCalls, toolCallId, name)
        }
    }

    /** 提取纯文本内容（兼容纯文本和多模态消息） */
    fun contentText(): String = when (content) {
        is JsonPrimitive -> content.asString
        is JsonArray -> {
            content.firstOrNull { it.asJsonObject["type"]?.asString == "text" }
                ?.asJsonObject?.get("text")?.asString ?: ""
        }
        else -> ""
    }
}

data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

data class FunctionCallDto(
    val name: String,
    val arguments: String,
)

data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val temperature: Double = 0.2,
)

data class ChatResponse(
    val choices: List<Choice>? = null,
    val error: ApiError? = null,
)

data class ApiError(val message: String? = null)

data class Choice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerializedName("finish_reason") val finishReason: String? = null,
)

interface DeepSeekApi {
    @POST("chat/completions")
    suspend fun chat(@Body request: ChatRequest): ChatResponse
}

/**
 * 统一推理客户端，根据 SettingsStore.useLocalModel 自动路由：
 * - false → DeepSeek / OpenAI 兼容 API（Retrofit）
 * - true  → 本地推理（LLMEngine，动态读取 YinyueApp.instance.localEngine）
 */
class LLMClient(
    private val settings: SettingsStore,
) {

    // ── 云端 API ──
    private val api: DeepSeekApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${settings.apiKey}")
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(settings.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeepSeekApi::class.java)
    }

    /** 按当前设置路由：本地 or 云端。image 仅本地视觉模式使用（见修复 1.2） */
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>, image: Bitmap? = null): Choice {
        if (!settings.useLocalModel) return chatRemote(messages, tools)

        // 按需自动加载：首次使用时 localEngine 为 null，同步等待加载完成后再推理
        val app = YinyueApp.instance
        if (app.localEngine == null) {
            val loaded = CompletableDeferred<Boolean>()
            app.loadLocalModel { err ->
                if (loaded.isCompleted) return@loadLocalModel
                loaded.complete(false)
            }
            // loadLocalModel 在 loadJob 中执行，成功后会设置 localEngine。等待最多 120 秒。
            val ok = withTimeoutOrNull(120_000L) {
                // 轮询等待 localEngine 就位或加载失败
                while (app.localEngine == null) {
                    if (loaded.isCompleted && loaded.getCompleted() == false) return@withTimeoutOrNull false
                    kotlinx.coroutines.delay(200)
                }
                true
            }
            if (ok != true) {
                throw IllegalStateException("本地模型加载超时，请检查设备内存或重试。")
            }
        }

        val engine = app.localEngine!!
        return chatLocal(messages, tools, engine, image)
    }

    private suspend fun chatRemote(messages: List<ChatMessage>, tools: List<ToolSpec>): Choice {
        val response = api.chat(
            ChatRequest(
                model = settings.model,
                messages = messages,
                tools = tools.ifEmpty { null },
            ),
        )
        response.error?.let { throw IllegalStateException("API 错误: ${it.message}") }
        val choice = response.choices?.firstOrNull()
            ?: throw IllegalStateException("API 返回为空")
        // 修复 2.1：Gson 用 Unsafe.allocateInstance 不调构造器，非 null 约束不生效，需手动判空
        choice.message ?: throw IllegalStateException("API 返回 message 为空")
        return choice
    }

    /**
     * 本地推理：将 messages 拼接为提示词 → llama.cpp 生成。
     *
     * 修复闪退:本地模型默认不走 grammar 路径(llama_sampler_init_grammar 的 GBNF 解析
     * 在某些 llama.cpp 版本上会 abort(),native 崩溃无法 catch)。改用 prompt 工程 +
     * StructuredToolCallBridge 宽松解析。grammar 代码保留(见 LlamaNative.chatWithTools),
     * 修复 GBNF 语法后可在 Android Studio 中手动启用。
     */
    /**
     * 本地推理：把完整消息列表（含 system + 工具说明）交给 C++ 统一套 chat template。
     *
     * 核心修复：之前 Kotlin 手工拼 "系统:/用户:/助手:" 格式的 prompt 字符串，
     * 再由 C++ 套 chat template → 双重拼接。模型看到 <|im_start|>user\n系统:... 的混乱结构，
     * 会按模板格式"扮演"整段对话（echo 历史、输出 user/assistant 标签、末尾 <|im_end|>）。
     * 现在 roles/contents 由 C++ 的 llama_chat_apply_template 唯一处理。
     */
    private suspend fun chatLocal(messages: List<ChatMessage>, tools: List<ToolSpec>, engine: LLMEngine, image: Bitmap? = null): Choice {
        val t0 = System.currentTimeMillis()
        val bridge = StructuredToolCallBridge()

        // 构造完整消息列表：
        // - 第一条 system：原始 persona（保持简洁，模型不易 echo）
        // - 第二条 system：工具指令（仅列工具名 + 一句格式提示，不放大段示例 JSON，
        //   3B 模型对复杂示例容易照抄导致输出乱码）
        val fullMessages = buildList {
            messages.firstOrNull { it.role == "system" }?.let {
                if (it.contentText().isNotBlank()) add(it)
            }
            if (tools.isNotEmpty()) {
                add(ChatMessage.text(
                    Role.SYSTEM,
                    "可用工具: ${tools.joinToString(" ") { it.function.name }}。" +
                    "调用工具时，输出 {\"tool_calls\":[...]} 数组，每条调用任选一种格式：" +
                    "A) {\"id\":\"c1\",\"function\":{\"name\":\"工具名\",\"arguments\":\"参数JSON\"}} 或 " +
                    "B) {\"tool_name\":\"工具名\",\"tool_args\":参数JSON}。" +
                    "无需工具则自然回复。",
                ))
            }
            // 追加非 system 消息（保留 user/assistant/tool 顺序）
            addAll(messages.filter { it.role != Role.SYSTEM })
        }

        val roles = fullMessages.map { it.role }.toTypedArray()
        val contents = fullMessages.map { it.contentText() }.toTypedArray()

        val t1 = System.currentTimeMillis()
        android.util.Log.i("LLMClient", "[TIMING] buildMessages: ${t1 - t0}ms, msgs=${fullMessages.size}")

        val result = withTimeoutOrNull(120_000L) {
            engine.chatWithMessages(roles, contents, image)
        }
        val t2 = System.currentTimeMillis()
        android.util.Log.i("LLMClient", "[TIMING] engine.chatWithMessages: ${t2 - t1}ms${if (result == null) " (TIMEOUT)" else ""}")

        if (result == null) return Choice(
            message = ChatMessage.text("assistant", "本地模型推理超时（2分钟）。请尝试更短的提示词或切换云端模型。"),
            finishReason = "timeout",
        )

        if (tools.isEmpty()) {
            return Choice(
                message = ChatMessage.text("assistant", bridge.sanitizeText(result)),
                finishReason = "stop",
            )
        }

        // 宽松解析工具调用(模型可能不输出 JSON 格式,parseToolCalls 有容错)
        val calls = bridge.parseToolCalls(result)
        return if (calls.isEmpty()) {
            // 无工具调用:当作纯文本回复(清洗模板标记,避免 <|im_end|> 等上屏)
            Choice(message = ChatMessage.text("assistant", bridge.sanitizeText(result)), finishReason = "stop")
        } else {
            val content = bridge.parseContent(result)?.takeIf { it.isNotBlank() }
                ?.let { bridge.sanitizeText(it) }
            Choice(
                message = ChatMessage.text("assistant", content.orEmpty(), toolCalls = calls),
                finishReason = "tool_calls",
            )
        }
    }

    /** 将消息列表拼接为本地模型用的纯文本提示词，超出 token 预算时截断。
     *  角色前缀精简：不再用 XML 标签包裹，减少 token 开销。 */
    private fun buildLocalPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val text = m.contentText().trim()
            if (text.isEmpty()) continue
            when (m.role) {
                "system" -> sb.append("系统: $text\n\n")
                "user"   -> sb.append("用户: $text\n")
                "assistant" -> sb.append("助手: $text\n")
                "tool"   -> sb.append("[工具: ${m.name}] $text\n")
            }
        }
        if (sb.isEmpty()) sb.append(messages.lastOrNull()?.contentText().orEmpty())
        return truncatePrompt(sb)
    }

    /** 构建带工具描述的本地 prompt。工具指令替换原始 system 消息，不重复注入。 */
    private fun buildLocalPromptWithTools(messages: List<ChatMessage>, tools: List<ToolSpec>): String {
        val sb = StringBuilder()
        sb.append("系统: 你是银月,可调用工具。需要时输出:{\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"工具名\",\"arguments\":\"{\\\"参数\\\":\\\"值\\\"}\"}}]}。无需工具则自然回复。工具:${tools.joinToString(" ") { it.function.name }}\n\n")
        for (m in messages) {
            val text = m.contentText().trim()
            if (text.isEmpty()) continue
            when (m.role) {
                "system" -> { /* 跳过原始 system 消息,工具指令已替换 */ }
                "user"   -> sb.append("用户: $text\n")
                "assistant" -> sb.append("助手: $text\n")
                "tool"   -> sb.append("[工具: ${m.name}] $text\n")
            }
        }
        if (sb.isEmpty()) sb.append(messages.lastOrNull()?.contentText().orEmpty())
        return truncatePrompt(sb)
    }

    /** 截断超长 prompt：中文按 ~1.0 chars/token，目标 ~1400 tokens（nCtx=2048 留 600 给生成）。保留首部系统指令 + 尾部近期对话 */
    private fun truncatePrompt(sb: StringBuilder): String {
        val maxChars = 1400  // 中文 ~1:1 chars/token，1400 chars ≈ 1400 tokens
        if (sb.length <= maxChars) return sb.toString()
        val truncated = StringBuilder(sb.substring(0, maxChars / 4))
        truncated.append("\n... (已截断)\n")
        val tail = sb.substring(sb.length - maxChars * 3 / 4)
        truncated.append(tail)
        return truncated.toString()
    }
}

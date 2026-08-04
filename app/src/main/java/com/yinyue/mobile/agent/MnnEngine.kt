package com.yinyue.mobile.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * MNN 推理引擎（替代 LocalLLMEngine）。
 *
 * 使用 MNN transformers LLM engine + OpenCL 后端，
 * 经 NNAPI/OpenCL 调度天玑 APU790。
 *
 * 模型加载要求：
 *   1. MNN 格式模型文件（mnnconvert 从 HF 导出）
 *   2. config JSON 文件指定模型路径、后端、线程数等
 *
 * 线程模型：
 * - MNN 内部使用线程池（thread_num 配置），不绑定调用线程
 * - JNI 调用可由 Kotlin 协程在任意线程发起
 */
class MnnEngine(
    private val configPath: String,
    private val showThinking: Boolean = false,
) {
    // MNN 内部线程安全，不需要单线程 dispatcher
    private val dispatcher = Dispatchers.IO
    private val initLock = Any()

    private var initialized = false

    suspend fun load() = withContext(dispatcher) {
        // CRITICAL-4: validate model path exists before native load
        val configFile = File(configPath)
        if (!configFile.exists()) {
            throw IllegalStateException("MNN config not found: $configPath")
        }
        synchronized(initLock) {
            MnnNative.ensure()
            val ok = MnnNative.init(configPath)
            if (!ok) throw IllegalStateException("MNN 模型加载失败: $configPath")
            initialized = true
        }
    }

    /** 阻塞式对话 */
    suspend fun chat(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int = 256,
    ): String = withContext(dispatcher) {
        if (!initialized) throw IllegalStateException("模型未加载")
        val raw = MnnNative.chat(roles, contents, maxTokens)
        stripThinking(raw)
    }

    /** 流式对话，返回 Flow<String>
     *
     * 实现：MNN chatStream 在后台线程逐 token 回调，
     * 通过 Channel 中转写入，Flow 侧收集后 emit 给上层。
     * Channel 用 CONFLATED 合并积压 token（降低 UI 跳帧压力）。
     */
    fun chatFlow(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int = 512,
    ): Flow<String> = flow {
        if (!initialized) throw IllegalStateException("模型未加载")

        val channel = Channel<String>(Channel.CONFLATED)
        val callback = object : MnnStreamCallback {
            @Volatile var stopped = false
            override fun onToken(text: String): Boolean {
                if (stopped) return true
                val sent = channel.trySend(text)
                if (sent.isFailure) {
                    stopped = true
                    return true
                }
                return false
            }
        }

        // chatStream 阻塞调用 → 在 IO 线程执行
        withContext(dispatcher) {
            MnnNative.chatStream(roles, contents, maxTokens, callback)
            channel.close()
        }

        // LOW-21: strip <think>...</think> blocks when showThinking is disabled.
        // Buffer the entire response before stripping: Qwen3-4B often omits <think>
        // but includes </think> — incremental per-token emit would leak reasoning
        // before </think> arrives in the stream.
        if (!showThinking) {
            val full = buildString {
                for (token in channel) {
                    append(token)
                }
            }
            val cleaned = stripThinking(full)
            if (cleaned.isNotEmpty()) {
                emit(cleaned)
            }
        } else {
            for (token in channel) {
                emit(token)
            }
        }
    }.flowOn(dispatcher)

    /** Strip <think>...</think> blocks from raw model output.
     *  Handles both well-formed <think>...</think> and malformed ...</think> (missing opening tag). */
    private fun stripThinking(text: String): String {
        if (showThinking) return text
        // Case 1: well-formed <think>...</think>
        var cleaned = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        // Case 2: malformed ...</think> without opening tag (common with Qwen3-4B)
        val idx = cleaned.indexOf("</think>")
        if (idx >= 0) {
            cleaned = cleaned.substring(idx + 8).trimStart()
        }
        return cleaned.trim()
    }

    suspend fun reset() = withContext(dispatcher) {
        MnnNative.reset()
    }

    suspend fun free() {
        withContext(dispatcher) {
            if (initialized) {
                MnnNative.free()
                initialized = false
            }
        }
    }

    val isLoaded: Boolean get() = initialized && MnnNative.isLoaded()

    fun getPerfInfo(): String = MnnNative.getPerfInfo()
    fun dumpConfig(): String = MnnNative.dumpConfig()
}

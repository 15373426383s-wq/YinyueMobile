package com.yinyue.mobile.agent

import android.graphics.Bitmap
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 本地 llama.cpp 推理引擎。
 * 封装 JNI 调用，提供协程安全的推理接口。
 *
 * 注意：llama.cpp 的 ggml_graph_compute 内部依赖 pthread_self()，
 * 所有 JNI 调用必须在同一线程上执行。这里用单线程 dispatcher 保证线程亲和性。
 */
class LocalLLMEngine(
    private val modelPath: String,
    private val mmprojPath: String,
    private val nCtx: Int = 4096,
) : LLMEngine {
    // llama.cpp 要求 load 和 chat 在同一线程
    private val llamaDispatcher = newSingleThreadContext("llama-worker")

    private var initialized = false

    /** 是否为多模态模型（有视觉投影器） */
    val hasVision: Boolean get() = mmprojPath.isNotEmpty()

    /** 加载模型（必须在与 chat 相同的线程上调用） */
    suspend fun load() = withContext(llamaDispatcher) {
        LlamaNative.ensure()
        val ok = LlamaNative.init(modelPath, mmprojPath, nCtx)
        if (!ok) throw IllegalStateException("模型加载失败: $modelPath")
        initialized = true
    }

    /** 带可选图像的对话 */
    suspend fun chat(
        prompt: String,
        image: Bitmap? = null,
        maxTokens: Int = 256,
    ): String = withContext(llamaDispatcher) {
        if (!initialized) throw IllegalStateException("模型未加载")

        val t0 = System.currentTimeMillis()
        if (hasVision && image != null) {
            val bytes = bitmapToRgbBytes(image)
            val t1 = System.currentTimeMillis()
            android.util.Log.i("LocalLLM", "[TIMING] bitmapToRgbBytes: ${t1 - t0}ms")
            val ok = LlamaNative.processImage(bytes, image.width, image.height, 3)
            if (!ok) throw IllegalStateException("图像编码失败")
        }

        // 无历史注入，清空上下文重新开始
        LlamaNative.setHistory(emptyArray(), emptyArray())
        val t2 = System.currentTimeMillis()
        val result = LlamaNative.chat(prompt, maxTokens)
        val t3 = System.currentTimeMillis()
        android.util.Log.i("LocalLLM", "[TIMING] native chat: ${t3 - t2}ms, result_len=${result.length}")
        result
    }

    /**
     * 带工具的对话（借鉴 Operit grammar Function Calling）。
     * 有 tools 时用 grammar 约束输出工具调用 JSON；无 tools 时走原 chat() 路径，
     * 保证不破坏现有本地推理功能。
     */
    suspend fun chatWithTools(
        prompt: String,
        image: Bitmap? = null,
        tools: List<ToolSpec> = emptyList(),
        maxTokens: Int = 256,
    ): String = withContext(llamaDispatcher) {
        if (!initialized) throw IllegalStateException("模型未加载")

        if (hasVision && image != null) {
            val bytes = bitmapToRgbBytes(image)
            val ok = LlamaNative.processImage(bytes, image.width, image.height, 3)
            if (!ok) return@withContext "[图像处理失败]"
        }

        if (tools.isEmpty()) {
            // 无工具：走原路径，保持现有行为
            LlamaNative.setHistory(emptyArray(), emptyArray())
            LlamaNative.chat(prompt, maxTokens)
        } else {
            // 有工具：把 ToolSpec 序列化为 toolsJson，调 chatWithTools 走 grammar 约束
            val toolsJson = StructuredToolCallBridge().toolsToJson(tools)
            LlamaNative.setHistory(emptyArray(), emptyArray())
            LlamaNative.chatWithTools(prompt, maxTokens, toolsJson)
        }
    }

    /**
     * 完整消息列表对话（核心路径）。
     * 由调用方传入完整 roles/contents（含 system），C++ 统一套 chat template。
     * 修复：之前 Kotlin 手工拼 "系统:/用户:/助手:" prompt 再套模板，双重拼接导致
     * 模型 echo 历史、输出 user/assistant 标签。
     */
    override suspend fun chatWithMessages(
        roles: Array<String>,
        contents: Array<String>,
        image: Bitmap?,
        maxTokens: Int,
    ): String = withContext(llamaDispatcher) {
        if (!initialized) throw IllegalStateException("模型未加载")

        if (hasVision && image != null) {
            val bytes = bitmapToRgbBytes(image)
            val ok = LlamaNative.processImage(bytes, image.width, image.height, 3)
            if (!ok) throw IllegalStateException("图像编码失败")
        }

        LlamaNative.resetChat() // 清空 KV，C++ chatWithMessages 内部也会再清一次（幂等）
        LlamaNative.chatWithMessages(roles, contents, maxTokens)
    }

    /** 带历史注入的对话（调用方从 Room 取历史传入） */
    suspend fun chatWithHistory(
        prompt: String,
        historyRoles: List<String>,
        historyContents: List<String>,
        image: Bitmap? = null,
        maxTokens: Int = 256,
    ): String = withContext(llamaDispatcher) {
        if (!initialized) throw IllegalStateException("模型未加载")

        if (hasVision && image != null) {
            val bytes = bitmapToRgbBytes(image)
            val ok = LlamaNative.processImage(bytes, image.width, image.height, 3)
            if (!ok) throw IllegalStateException("图像编码失败")
        }

        LlamaNative.resetChat()  // 清空上一轮 KV cache，避免历史堆积超 nCtx
        LlamaNative.setHistory(historyRoles.toTypedArray(), historyContents.toTypedArray())
        LlamaNative.chat(prompt, maxTokens)
    }

    /** 清空对话上下文 */
    suspend fun reset() = withContext(llamaDispatcher) {
        LlamaNative.resetChat()
    }

    /** 释放模型（必须在 llama 线程上调用以匹配 init 的线程） */
    override suspend fun free() {
        withContext(llamaDispatcher) {
            // 修复 4.2：无条件调用 native free，避免 init 部分失败后句柄泄漏（native 侧判空）
            LlamaNative.free()
            initialized = false
        }
        llamaDispatcher.close() // 修复 4.1：释放常驻线程，避免每次切换模型泄漏一个线程
    }

    private fun bitmapToRgbBytes(bmp: Bitmap): ByteArray {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val bos = ByteArrayOutputStream(pixels.size * 3)
        for (p in pixels) {
            bos.write((p shr 16) and 0xFF) // R
            bos.write((p shr 8) and 0xFF)  // G
            bos.write(p and 0xFF)          // B
        }
        return bos.toByteArray()
    }
}

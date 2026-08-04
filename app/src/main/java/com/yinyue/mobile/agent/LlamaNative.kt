package com.yinyue.mobile.agent

/**
 * llama.cpp JNI 桥接。加载 libllama_jni.so 后直接调用。
 * 全局单例——一个进程内只持有一个模型实例。
 */
object LlamaNative {

    @Volatile private var loaded = false // 修复 3.1：跨线程读写需 @Volatile 保证可见性

    fun ensure() {
        if (!loaded) {
            System.loadLibrary("llama_jni")
            loaded = true
        }
    }

    /** 初始化模型 + 多模态投影器，nCtx 为上下文长度 */
    external fun init(modelPath: String, mmprojPath: String, nCtx: Int): Boolean

    /** 将 RGB 图像 bytes 送入视觉编码器，追加到上下文 */
    external fun processImage(imageBytes: ByteArray, width: Int, height: Int, channels: Int): Boolean

    /** 注入对话历史（roles/contents 一一对应，空数组=清空） */
    external fun setHistory(roles: Array<String>, contents: Array<String>)

    /** 对话推理，maxTokens 限制输出，返回完整回复文本 */
    external fun chat(prompt: String, maxTokens: Int): String

    /**
     * 完整消息列表对话（核心路径）。
     * roles/contents 一一对应，需包含 system 消息（如有）；C++ 统一套 chat template，
     * 避免 Kotlin 手工拼接 prompt 导致双重模板化（模型 echo 历史/输出 user/assistant 标签）。
     */
    external fun chatWithMessages(roles: Array<String>, contents: Array<String>, maxTokens: Int): String

    /** 清空 KV cache + 对话历史 */
    external fun resetChat()

    /** 释放模型资源 */
    external fun free()

    // ── 阶段3:端侧 grammar Function Calling(借鉴 Operit StructuredToolCallBridge) ──

    /** 带工具的对话：在 prompt 前注入工具描述 system 消息，并用 grammar 约束输出为 tool_calls JSON。
     *  toolsJson 为 OpenAI 风格 tools 数组 JSON（由 StructuredToolCallBridge.toolsToJson 生成）。 */
    external fun chatWithTools(prompt: String, maxTokens: Int, toolsJson: String): String

    /** 启用/禁用工具调用 grammar 约束。启用后常规 chat() 也会用 grammar 约束输出；
     *  chatWithTools() 始终自带 grammar，本开关主要影响 chat() 路径。 */
    external fun setToolCallGrammar(enabled: Boolean)

    /** 解析 LLM 输出为标准 tool_calls JSON（容错：去 markdown fence、提取首个 JSON 对象）。
     *  无 JSON 时返回 {"tool_calls":[]}。Kotlin 侧推荐直接用 StructuredToolCallBridge.parseToolCalls。 */
    external fun parseToolCallResponse(response: String): String
}

package com.yinyue.mobile.agent

/**
 * MNN 流式输出回调接口。
 * onToken 返回 true 表示停止生成。
 */
interface MnnStreamCallback {
    fun onToken(text: String): Boolean
}

/**
 * MNN JNI 原生桥接（替代 LlamaNative）。
 * 基于 MNN transformers LLM engine + OpenCL GPU 加速。
 */
object MnnNative {

    @Volatile private var loaded = false
    fun ensure() {
        if (!loaded) {
            System.loadLibrary("mnn_llm_jni")
            loaded = true
        }
    }

    /** 从 config JSON 文件加载模型 */
    external fun init(configPath: String): Boolean

    /** 阻塞式对话（兼容旧 agent 循环） */
    external fun chat(roles: Array<String>, contents: Array<String>, maxTokens: Int): String

    /** 流式对话：每 token 通过 callback.onToken 回调 */
    external fun chatStream(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        callback: MnnStreamCallback
    ): Boolean

    /** 清空 KV cache */
    external fun reset()

    /** 释放模型 */
    external fun free()

    /** 模型是否已加载 */
    external fun isLoaded(): Boolean

    /** 获取上次推理的性能指标（JSON） */
    external fun getPerfInfo(): String

    /** 输出当前模型配置（JSON） */
    external fun dumpConfig(): String
}

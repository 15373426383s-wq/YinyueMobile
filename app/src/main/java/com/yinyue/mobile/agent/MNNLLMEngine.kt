package com.yinyue.mobile.agent

import android.graphics.Bitmap

/**
 * MNN 端侧推理引擎，实现 LLMEngine 接口。
 *
 * 使用 MNN transformers LLM engine + OpenCL 后端，
 * 经 NNAPI/OpenCL 调度天玑 APU790。
 *
 * @param configPath MNN config JSON 文件的绝对路径
 */
class MNNLLMEngine(
    private val configPath: String,
    private val showThinking: Boolean = false,
) : LLMEngine {

    private val engine = MnnEngine(configPath, showThinking)

    private var loaded = false

    suspend fun load() {
        engine.load()
        loaded = true
    }

    override suspend fun chatWithMessages(
        roles: Array<String>,
        contents: Array<String>,
        image: Bitmap?,
        maxTokens: Int,
    ): String {
        if (!loaded) throw IllegalStateException("MNN 引擎未加载")
        return engine.chat(roles, contents, maxTokens)
    }

    override suspend fun free() {
        if (loaded) {
            engine.free()
            loaded = false
        }
    }
}

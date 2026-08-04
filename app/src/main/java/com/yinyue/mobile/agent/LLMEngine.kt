package com.yinyue.mobile.agent

import android.graphics.Bitmap

/**
 * 统一本地推理引擎接口。
 * 支持 llama.cpp 和 MNN 两种后端，对外暴露相同的 chatWithMessages / free 契约。
 */
interface LLMEngine {
    suspend fun chatWithMessages(
        roles: Array<String>,
        contents: Array<String>,
        image: Bitmap? = null,
        maxTokens: Int = 512,
    ): String

    suspend fun free()
}

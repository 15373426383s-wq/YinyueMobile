package com.yinyue.mobile.agent

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/** LLM 服务抽象层。借鉴 Operit AIService,统一端侧与云端接口。 */
interface AIService {
    /** 是否已就绪(模型加载/API 配置完成) */
    val isReady: Boolean

    /** 同步对话(非流式)。返回完整响应。 */
    suspend fun chat(prompt: String, image: Bitmap? = null, maxTokens: Int = 2048): String

    /** 流式对话,逐 token 返回。 */
    fun chatStream(prompt: String, image: Bitmap? = null, maxTokens: Int = 2048): Flow<String>

    /** 释放资源 */
    suspend fun release()
}

package com.yinyue.mobile.mnn

import android.util.Log

/** MNN LLM JNI 桥接。借鉴 Operit MNNLlmNative.kt。 */
object MNNLlmNative {
    @Volatile private var loaded = false

    fun ensure() {
        if (!loaded) {
            try {
                System.loadLibrary("MnnLlmWrapper")
                loaded = true
                Log.i("MNNLlmNative", "MNN native 库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("MNNLlmNative", "MNN native 库加载失败", e)
                throw e
            }
        }
    }

    external fun nativeInit(configPath: String): Boolean
    external fun nativeReset()
    external fun nativeChat(prompt: String, maxTokens: Int): String
    external fun nativeFree()
}

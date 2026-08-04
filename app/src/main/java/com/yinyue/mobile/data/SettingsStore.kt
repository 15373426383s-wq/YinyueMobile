package com.yinyue.mobile.data

import android.content.Context

/** 简单设置存储：API 配置 / 本地模型开关 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("Yinyue_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            var v = value.trim()
            if (v.isEmpty()) {
                prefs.edit().remove(KEY_BASE_URL).apply() // 空值移除 key，回退默认
                return
            }
            if (!v.endsWith("/")) v += "/"
            prefs.edit().putString(KEY_BASE_URL, v).apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            val v = value.trim()
            if (v.isEmpty()) {
                prefs.edit().remove(KEY_MODEL).apply() // 空值移除 key，回退默认
                return
            }
            prefs.edit().putString(KEY_MODEL, v).apply()
        }

    var workspaceDir: String
        get() = prefs.getString(KEY_WORKSPACE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORKSPACE, value.trim()).apply()

    /** true=本地模型，false=云端API */
    var useLocalModel: Boolean
        get() = prefs.getBoolean(KEY_LOCAL, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL, value).apply()

    /**
     * 本地推理后端选择（借鉴 Operit 双后端设计）。
     * - "llama" = llama.cpp（默认，CPU + GGUF）
     * - "mnn"   = MNN（GPU 加速，Vulkan/OpenCL + KleidiAI int8）
     */
    var localBackend: String
        get() = prefs.getString(KEY_LOCAL_BACKEND, "mnn") ?: "mnn"
        set(value) = prefs.edit().putString(KEY_LOCAL_BACKEND, value).apply()

    /** 是否显示思考过程（默认开启） */
    var showThinking: Boolean
        get() = prefs.getBoolean(KEY_SHOW_THINKING, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_THINKING, value).apply()

    companion object {
        private const val KEY_API = "deepseek_api_key"
        private const val KEY_BASE_URL = "deepseek_base_url"
        private const val KEY_MODEL = "deepseek_model"
        private const val KEY_WORKSPACE = "workspace_dir"
        private const val KEY_LOCAL = "use_local_model"
        private const val KEY_LOCAL_BACKEND = "local_backend" // 阶段4:本地后端选择 llama/mnn
        private const val KEY_SHOW_THINKING = "show_thinking"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/"
        const val DEFAULT_MODEL = "deepseek-chat"

        // 后端枚举常量
        const val BACKEND_LLAMA = "llama"
        const val BACKEND_MNN = "mnn"
    }
}

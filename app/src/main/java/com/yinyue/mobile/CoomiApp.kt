package com.yinyue.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.yinyue.mobile.agent.LLMEngine
import com.yinyue.mobile.agent.LocalLLMEngine
import com.yinyue.mobile.agent.MNNLLMEngine
import com.yinyue.mobile.agent.ModelManager
import com.yinyue.mobile.data.SettingsStore
import com.yinyue.mobile.data.db.AppDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class YinyueApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val modelManager: ModelManager by lazy { ModelManager(this) }

    /** 本地推理引擎（按需初始化，释放后置 null）。支持 llama.cpp 和 MNN 后端。 */
    var localEngine: LLMEngine? = null
        private set

    /** 上一次加载任务，避免并发竞态 */
    private var loadJob: Job? = null

    /** 引擎替换锁，避免 synchronized 内调用 suspend 函数 */
    private val engineLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT,
                getString(R.string.channel_agent),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_agent_description) },
        )
        // 冷启动自动加载：暂时禁用。设备内存仅 ~115MB 空闲，启动时加载 5.5GB
        // 模型 + Android 服务初始化会导致 OOM→native crash。改为首次使用时按需加载。
        /*
        if (settings.useLocalModel && modelManager.isModelReady) {
            Log.i("YinyueApp", "冷启动：尝试加载本地模型...")
            loadLocalModel { err ->
                Log.e("YinyueApp", "冷启动加载本地模型失败: $err")
            }
        }
        */
    }

    /**
     * 异步加载本地模型。
     * 根据 settings.localBackend 选择 llama.cpp 或 MNN 后端。
     */
    fun loadLocalModel(onError: (String) -> Unit) {
        val mm = modelManager
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                when (settings.localBackend) {
                    SettingsStore.BACKEND_MNN -> loadMnnEngine(mm)
                    else -> loadLlamaEngine(mm)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError("模型加载失败: ${e.message}")
            }
        }
    }

    /** 加载 llama.cpp 引擎。
     * 必须先释放旧引擎再加载新引擎——JNI 使用全局静态 g_ctx/g_model，
     * 若先 load 新引擎（设置 g_ctx）再 free 旧引擎（清零 g_ctx），
     * 新引擎的模型上下文就被旧引擎的 free() 误清。 */
    private suspend fun loadLlamaEngine(mm: ModelManager) {
        engineLock.withLock {
            localEngine?.free() // 释放旧引擎 → g_ctx=nullptr（全局清空）
            localEngine = null  // 标记为未加载，避免 LLMClient 在加载期间使用空引擎
        }
        val engine = LocalLLMEngine(
            modelPath = mm.ggufFile().absolutePath,
            mmprojPath = if (mm.currentModel.mmprojUrl.isEmpty()) "" else mm.mmprojFile().absolutePath,
            nCtx = 2048,   // 降低上下文窗口以适配 4GB 可用内存（4096→2048，KV cache 减半）
        )
        engine.load()
        engineLock.withLock {
            localEngine = engine  // 新引擎就位，g_ctx 已指向新模型
        }
        Log.i("YinyueApp", "本地模型加载成功 (nCtx=2048)")
    }

    /** MNN 引擎加载：使用 MnnEngine + MnnNative，模型目录含 config.json */
    private suspend fun loadMnnEngine(mm: ModelManager) {
        engineLock.withLock {
            localEngine?.free()
            localEngine = null
        }
        val configPath = File(mm.mnnModelDir(), "config.json").absolutePath
        val engine = MNNLLMEngine(configPath, settings.showThinking)
        engine.load()
        engineLock.withLock {
            localEngine = engine
        }
        Log.i("YinyueApp", "MNN 模型加载成功: $configPath")
    }

    /** 释放本地模型（释放约 4GB 内存） */
    fun freeLocalModel() {
        val llama = localEngine
        localEngine = null // 先置 null 防止再次使用
        // 异步释放，避免在主线程 runBlocking 阻塞
        CoroutineScope(Dispatchers.IO).launch {
            try {
                llama?.free()
            } catch (e: Exception) {
                Log.w("YinyueApp", "释放本地模型失败", e)
            }
        }
    }

    companion object {
        const val CHANNEL_AGENT = "agent_status"

        lateinit var instance: YinyueApp
            private set
    }
}

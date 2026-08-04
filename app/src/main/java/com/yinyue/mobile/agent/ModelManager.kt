package com.yinyue.mobile.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型下载与存储管理。
 * 从 HuggingFace 下载 GGUF + mmproj，存到 app 私有目录。
 */
class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("model_info", Context.MODE_PRIVATE)

    data class ModelInfo(
        val name: String,
        val ggufUrl: String,   // GGUF 文件直链
        val mmprojUrl: String, // 视觉投影器直链（纯文本模型为空）
        val ggufSize: Long,
    )

    /** 当前使用的模型（默认 Qwen2.5-3B：非思考型、回答直接、手机端速度可用） */
    var currentModel: ModelInfo = QWEN25_3B

    /** 已下载完成的模型名（持久化，重启后仍能正确判断就绪状态） */
    var downloadedModelName: String
        get() = prefs.getString(KEY_DOWNLOADED_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOWNLOADED_MODEL, value).apply()

    /** 当前本地模型是否已下载完成：文件存在 + 模型名匹配当前配置。
     *  修复：之前只看文件存在，旧模型文件会让新模型误判为"已就绪"，
     *  导致下载入口被隐藏、加载错误的模型文件。 */
    val isModelReady: Boolean
        get() = downloadedModelName == currentModel.name &&
            ggufFile().exists() &&
            (currentModel.mmprojUrl.isEmpty() || mmprojFile().exists())

    /** MNN 模型是否已就绪（config.json 存在即视为已部署） */
    val isMnnModelReady: Boolean
        get() = File(mnnModelDir(), "config.json").exists()

    fun modelDir(): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    /** MNN 模型目录（与 llama 模型分离） */
    fun mnnModelDir(): File =
        File(context.filesDir, "mnn_models").also { it.mkdirs() }

    fun ggufFile(): File = File(modelDir(), "model.gguf")
    fun mmprojFile(): File = File(modelDir(), "mmproj.gguf")

    private fun mirrorUrl(url: String): String =
        url.replace("huggingface.co", "hf-mirror.com")

    /** 下载进度流：emit (downloadedBytes, totalBytes, description) */
    fun download(model: ModelInfo): Flow<Triple<Long, Long, String>> = flow {
        modelDir()

        // 下载 GGUF（优先镜像站）
        emit(Triple(0L, model.ggufSize, "正在下载模型 (~${model.ggufSize / 1024 / 1024}MB)..."))
        try {
            downloadFile(mirrorUrl(model.ggufUrl), model.ggufUrl, ggufFile()) { dl, _ ->
                emit(Triple(dl, model.ggufSize, "模型"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // 修复 5.1：不吞取消异常，保住协程取消语义
            throw RuntimeException("模型下载失败: ${e.message ?: "网络错误"}", e)
        }

        // 纯文本模型跳过 mmproj 下载
        if (model.mmprojUrl.isNotEmpty()) {
            emit(Triple(0L, 1L, "正在下载视觉投影器..."))
            try {
                downloadFile(mirrorUrl(model.mmprojUrl), model.mmprojUrl, mmprojFile()) { dl, total ->
                    if (total > 0) emit(Triple(dl, total, "视觉投影器"))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e // 修复 5.1：不吞取消异常，保住协程取消语义
                throw RuntimeException("视觉投影器下载失败: ${e.message ?: "网络错误"}", e)
            }
        }

        // 全部下载完成后记录模型名，使 isModelReady 正确返回 true
        downloadedModelName = model.name
        emit(Triple(1L, 1L, "下载完成"))
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFile(
        urlStr: String, fallbackUrl: String, dest: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        var lastError: Exception? = null
        for (urlCandidate in listOf(urlStr, fallbackUrl).distinct()) { // 修复 5.3：镜像与原 URL 相同时只下载一次
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlCandidate)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 30000
                conn.readTimeout = 600_000
                conn.setRequestProperty("User-Agent", "Yinyue/0.2")
                conn.instanceFollowRedirects = true

                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e // 修复 5.1：不吞取消异常，保住协程取消语义
                lastError = e
                if (dest.exists()) dest.delete() // 清理失败残留
            } finally {
                conn?.disconnect() // 修复 5.2：异常路径也 disconnect，避免连接泄漏
            }
        }
        throw lastError ?: RuntimeException("下载失败")
    }

    companion object {
        private const val KEY_DOWNLOADED_MODEL = "downloaded_model_name"

        /** Qwen2.5-VL-7B-Instruct Q4_K_M（多模态，约 4.4GB + 1.2GB mmproj） */
        val QWEN25_VL_7B = ModelInfo(
            name = "Qwen2.5-VL-7B-Instruct",
            ggufUrl = "https://huggingface.co/bartowski/Qwen_Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen_Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
            mmprojUrl = "https://huggingface.co/bartowski/Qwen_Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-Qwen_Qwen2.5-VL-7B-Instruct-f16.gguf",
            ggufSize = 4_683_072_320L,
        )

        /** Qwen3-4B-Instruct Q4_K_M（纯文本，约 2.4GB，无 mmproj。注意：Qwen3 是思考型模型，手机端生成慢且输出含思维链，不推荐日常使用） */
        val QWEN3_4B = ModelInfo(
            name = "Qwen3-4B-Instruct",
            ggufUrl = "https://huggingface.co/Open4bits/Qwen3-4B-Instruct-2507-GGUF/resolve/main/qwen3-4b-instruct-2507-Q4_K_M.gguf",
            mmprojUrl = "", // 纯文本，不需要视觉投影器
            ggufSize = 2_502_000_000L,
        )

        /** Qwen2.5-3B-Instruct Q4_K_M（纯文本，约 1.9GB，无 mmproj，手机端推荐：非思考型、回答直接） */
        val QWEN25_3B = ModelInfo(
            name = "Qwen2.5-3B-Instruct",
            ggufUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            mmprojUrl = "", // 纯文本，不需要视觉投影器
            ggufSize = 1_929_903_264L,
        )
    }
}

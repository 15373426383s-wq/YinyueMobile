package com.yinyue.mobile.tools

import com.yinyue.mobile.agent.ToolExecutionLimits
import com.yinyue.mobile.agent.YinyueTool
import com.yinyue.mobile.agent.schemaOf
import com.yinyue.mobile.agent.stringParam
import com.google.gson.JsonObject
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 网页抓取：HTTP GET + 粗粒度去标签转纯文本（MVP 版，后续可换 WebView 渲染） */
class FetchUrlTool : YinyueTool {
    override val name = "fetch_url"
    override val description = "抓取一个网页并返回纯文本正文（截断）"
    override val parameters = schemaOf("url" to stringParam("http/https 链接"), required = listOf("url"))

    // 引用集中限制常量（借鉴 Operit ToolExecutionLimits）
    private val client = OkHttpClient.Builder()
        .connectTimeout(ToolExecutionLimits.HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ToolExecutionLimits.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = runCatching { args.get("url")?.asString }.getOrNull()
            ?: return@withContext "缺少 url 参数"
        // 严格校验 http/https scheme，大小写不敏感
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url))
            return@withContext "URL 必须以 http:// 或 https:// 开头"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) YinyueMobile/0.1")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val html = resp.body?.string().orEmpty().take(MAX_HTML_CHARS)
                htmlToText(html).take(MAX_TEXT_CHARS)
            }
        } catch (e: Exception) {
            "抓取失败: ${e.message}"
        }
    }

    private fun htmlToText(html: String): String {
        var text = html
        text = Regex("(?is)<script.*?>.*?</script>").replace(text, " ")
        text = Regex("(?is)<style.*?>.*?</style>").replace(text, " ")
        text = Regex("(?s)<[^>]+>").replace(text, " ")
        // 用 Html.fromHtml 解码全部 HTML 实体，补全手动替换的遗漏
        text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(text).toString()
        }
        text = Regex("[ \\t]+").replace(text, " ")
        text = Regex("\\n\\s*\\n+").replace(text, "\n")
        return text.trim()
    }

    companion object {
        private const val MAX_HTML_CHARS = 200_000
        private const val MAX_TEXT_CHARS = 4000
    }
}

/** 用系统默认浏览器打开网页（与 fetch_url 互补：fetch 是后台抓取，open 是前台打开） */
class OpenUrlTool(private val context: Context) : YinyueTool {
    override val name = "open_url"
    override val description = "用系统默认浏览器打开一个网页链接"
    override val parameters = schemaOf("url" to stringParam("http/https 链接"), required = listOf("url"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.Main) {
        val url = runCatching { args.get("url")?.asString }.getOrNull()
            ?: return@withContext "缺少 url 参数"
        // 严格校验 http/https scheme，大小写不敏感
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url))
            return@withContext "URL 必须以 http:// 或 https:// 开头"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "已在浏览器中打开 $url"
        } catch (e: Exception) {
            "打开失败: ${e.message}"
        }
    }
}

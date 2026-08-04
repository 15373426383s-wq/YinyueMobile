package com.yinyue.mobile.tools

import com.yinyue.mobile.agent.ToolExecutionLimits
import com.yinyue.mobile.agent.YinyueTool
import com.yinyue.mobile.agent.schemaOf
import com.yinyue.mobile.agent.stringParam
import com.yinyue.mobile.agent.intParam
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 联网搜索 —— 抓取百度搜索结果页 HTML 并解析，国内可用，无需 API Key。
 */
class WebSearchTool : YinyueTool {
    override val name = "web_search"
    override val description = "联网搜索（百度），返回标题+摘要+链接"
    override val parameters = schemaOf(
        "query" to stringParam("搜索关键词"),
        "max_results" to intParam("最大返回条数，默认5，上限10"),
        required = listOf("query"),
    )

    // 引用集中限制常量（借鉴 Operit ToolExecutionLimits）
    private val client = OkHttpClient.Builder()
        .connectTimeout(ToolExecutionLimits.HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ToolExecutionLimits.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val query = runCatching { args.get("query")?.asString }.getOrNull()
            ?: return@withContext "缺少 query 参数"
        val max = (runCatching { args.get("max_results")?.asInt }.getOrNull() ?: 5).coerceIn(1, 10)

        return@withContext try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.baidu.com/s?wd=$encoded&rn=10"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            // 用 .use{} 确保 Response 在异常路径也关闭，先判 isSuccessful 再读 body
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "搜索失败 HTTP ${response.code}"
                val html = response.body?.string().orEmpty()

                val results = parseBaiduResults(html, max)
                if (results.isEmpty()) "未找到与「$query」相关的结果"
                else results.trimEnd()
            }
        } catch (e: Exception) {
            "搜索出错: ${e.message}"
        }
    }

    private fun parseBaiduResults(html: String, max: Int): String {
        val sb = StringBuilder()
        // 匹配每个结果块：<div class="result c-container ...> ... </div>
        // TODO: 正则解析对嵌套 div 会过早截断，无 Jsoup 依赖下为已知限制
        val blockPattern = Regex(
            """<div[^>]*class="[^"]*result[^"]*c-container[^"]*"[^>]*>(.*?)</div>\s*(?=<div[^>]*class="[^"]*result|\Z)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val blocks = blockPattern.findAll(html).toList()
        var count = 0

        for (block in blocks) {
            if (count >= max) break
            val blockHtml = block.groupValues[1]

            // 提取标题
            val title = Regex("""<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(blockHtml)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&nbsp;", " ")
                ?.trim() ?: continue
            if (title.isBlank()) continue

            // 提取 URL
            val url = Regex("""<a[^>]*href="([^"]*)"[^>]*>""")
                .find(blockHtml)?.groupValues?.get(1) ?: ""

            // 提取摘要
            val summary = Regex(
                """<span[^>]*class="[^"]*c-abstract[^"]*"[^>]*>(.*?)</span>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(blockHtml)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&nbsp;", " ")
                ?.replace("&mdash;", "—")
                ?.trim()
                ?: (Regex(
                    """<div[^>]*class="[^"]*c-abstract[^"]*"[^>]*>(.*?)</div>""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(blockHtml)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.trim() ?: "")

            count++
            sb.appendLine("$count. $title")
            if (summary.isNotEmpty()) sb.appendLine("   $summary")
            if (url.isNotEmpty()) sb.appendLine("   $url")
        }
        return sb.toString()
    }

    override suspend fun mock(args: JsonObject): String {
        val query = args.get("query")?.asString ?: ""
        return "模拟搜索: $query"
    }
}

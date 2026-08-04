package com.yinyue.mobile.tools

import android.content.Context
import android.content.Intent
import com.yinyue.mobile.agent.YinyueTool
import com.yinyue.mobile.agent.schemaOf
import com.yinyue.mobile.agent.stringParam
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LaunchAppTool(private val context: Context) : YinyueTool {
    override val name = "launch_app"
    override val description = "按包名或应用名启动一个应用"
    override val parameters = schemaOf(
        "package_name" to stringParam("应用包名，如 com.tencent.mm；或提供应用名 app_name 自动解析"),
        "app_name" to stringParam("应用名称（如 微信），未提供 package_name 时按名称模糊查找"),
        required = listOf(),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        // 兼容三种参数形式：package_name / app_package / app_name
        val pkg = runCatching { args.get("package_name")?.asString }.getOrNull()
            ?: runCatching { args.get("app_package")?.asString }.getOrNull()
            ?: runCatching { args.get("app_name")?.asString }.getOrNull()?.let { name ->
                // 应用名 → 模糊查找包名（精确匹配优先，否则包含匹配）
                resolvePackageByName(name)
            }
            ?: return@withContext "缺少 package_name/app_name 参数"
        // PackageManager 查询移到 IO 线程，避免阻塞主线程
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return@withContext "未找到应用 $pkg（可能未安装或不可启动）"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(intent)
                "已启动 $pkg"
            } catch (e: Exception) {
                "启动失败: ${e.message}"
            }
        }
    }

    /** 按应用名模糊解析包名：先精确匹配 label，再包含匹配，无结果返回 null */
    private fun resolvePackageByName(name: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
        val exact = apps.firstOrNull { it.second == name }
        if (exact != null) return exact.first
        return apps.firstOrNull { it.second.contains(name) || name.contains(it.second) }?.first
    }
}

class ListAppsTool(private val context: Context) : YinyueTool {
    override val name = "list_apps"
    override val description = "列出已安装且可启动的应用（名称 + 包名），可用关键字过滤"
    override val parameters = schemaOf("filter" to stringParam("名称过滤关键字，可为空"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val filter = runCatching { args.get("filter")?.asString }.getOrNull()?.lowercase().orEmpty()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { resolve ->
                val label = resolve.loadLabel(pm).toString()
                val pkg = resolve.activityInfo.packageName
                label to pkg
            }
            .distinctBy { it.second }
            .filter { filter.isEmpty() || it.first.lowercase().contains(filter) || it.second.contains(filter) }
            .sortedBy { it.first }
            .take(MAX_APPS)
        if (apps.isEmpty()) "未找到匹配应用"
        else apps.joinToString("\n") { (label, pkg) -> "$label -> $pkg" }
    }

    companion object { private const val MAX_APPS = 100 }
}

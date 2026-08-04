package com.yinyue.mobile.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.yinyue.mobile.agent.YinyueTool
import com.yinyue.mobile.agent.schemaOf
import com.yinyue.mobile.agent.stringParam
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 剪贴板读写（注意：Android 10+ 应用在后台时读取剪贴板可能返回空） */
class ClipboardTool(private val context: Context) : YinyueTool {
    override val name = "clipboard"
    override val description = "读取或写入系统剪贴板"
    override val parameters = schemaOf(
        "action" to stringParam("read 或 write", listOf("read", "write")),
        "text" to stringParam("write 时要写入的文本"),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return@withContext "剪贴板服务不可用"
        when (runCatching { args.get("action")?.asString }.getOrNull()?.lowercase()) {
            "read" -> {
                val clip = cm.primaryClip
                // Android 10+ 应用在后台时读取剪贴板返回空，提示需区分
                if (clip == null || clip.itemCount == 0) "（剪贴板为空，或 Android 10+ 后台无读取权限）"
                else clip.getItemAt(0).text?.toString()?.take(2000) ?: "（剪贴板内容非文本）"
            }
            "write" -> {
                val text = runCatching { args.get("text")?.asString }.getOrNull()
                    ?: return@withContext "缺少 text 参数"
                cm.setPrimaryClip(ClipData.newPlainText("Yinyue", text))
                "已写入剪贴板（${text.length} 字）"
            }
            else -> "未知 action（支持 read/write）"
        }
    }
}

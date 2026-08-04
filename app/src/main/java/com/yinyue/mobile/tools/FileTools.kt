package com.yinyue.mobile.tools

import android.content.Context
import android.os.Environment
import com.yinyue.mobile.YinyueApp
import com.yinyue.mobile.agent.ToolExecutionLimits
import com.yinyue.mobile.agent.YinyueTool
import com.yinyue.mobile.agent.schemaOf
import com.yinyue.mobile.agent.stringParam
import com.google.gson.JsonObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 文件工具的工作根目录：优先使用用户自定义路径，未设置则使用应用专属 Downloads/YinyueWork */
object WorkspaceDir {
    private const val DEFAULT_WORKSPACE_NAME = "YinyueWork"

    fun root(context: Context): File {
        val app = context.applicationContext as YinyueApp
        val custom = app.settings.workspaceDir
        if (custom.isNotBlank()) {
            val dir = File(custom)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("无法创建工作目录: $dir")
            }
            return dir
        }
        // Android 10+ 公共 Downloads 不可直接写（targetSdk=34），改用应用专属目录
        val defaultDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            DEFAULT_WORKSPACE_NAME,
        )
        if (!defaultDir.exists() && !defaultDir.mkdirs()) {
            throw IllegalStateException("无法创建工作目录: $defaultDir")
        }
        return defaultDir
    }

    /** 校验路径在根目录内，防路径遍历。改用 PathValidator.validateRelative（借鉴 Operit PathValidator）。 */
    fun resolveSafe(context: Context, relative: String): File =
        PathValidator.validateRelative(root(context), relative)
}

class ListFilesTool(private val context: Context) : YinyueTool {
    override val name = "list_files"
    override val description = "列出工作目录下的文件和子目录"
    override val parameters = schemaOf("path" to stringParam("相对目录路径，空字符串为根目录"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val dir = WorkspaceDir.resolveSafe(context, runCatching { args.get("path")?.asString }.getOrNull() ?: "")
        if (!dir.exists()) return@withContext "目录不存在"
        if (!dir.isDirectory) return@withContext "不是目录"
        val children = dir.listFiles().orEmpty()
        if (children.isEmpty()) return@withContext "（空目录）"
        children.joinToString("\n") { f ->
            val type = if (f.isDirectory) "[dir] " else "[file]"
            "$type ${f.name} (${f.length()}B)"
        }
    }
}

class ReadFileTool(private val context: Context) : YinyueTool {
    override val name = "read_file"
    override val description = "读取工作目录中的文本文件内容"
    override val parameters = schemaOf("path" to stringParam("相对文件路径"), required = listOf("path"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = runCatching { args.get("path")?.asString }.getOrNull()
            ?: return@withContext "缺少 path 参数"
        val file = WorkspaceDir.resolveSafe(context, path)
        if (!file.isFile) return@withContext "文件不存在: $path"
        // 限量读取，避免大文件 OOM
        file.bufferedReader().use { reader ->
            val buf = CharArray(MAX_READ_CHARS)
            val n = reader.read(buf)
            if (n < 0) "" else String(buf, 0, n)
        }
    }

    companion object {
        // 引用集中限制常量（借鉴 Operit ToolExecutionLimits）
        private val MAX_READ_CHARS = ToolExecutionLimits.FILE_MAX_READ_CHARS
    }
}

class DeleteFileTool(private val context: Context) : YinyueTool {
    override val name = "delete_file"
    override val description = "删除工作目录中的文件或空目录"
    override val dangerous = true
    override val parameters = schemaOf("path" to stringParam("相对文件路径"), required = listOf("path"))

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = runCatching { args.get("path")?.asString }.getOrNull()
            ?: return@withContext "缺少 path 参数"
        val file = WorkspaceDir.resolveSafe(context, path)
        if (!file.exists()) return@withContext "文件不存在: $path"
        // 先尝试删除，依据返回值判断，避免 listFiles TOCTOU 竞态
        val ok = file.delete()
        if (!ok) {
            if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) "目录非空，请先清空: $path"
            else "删除失败: $path（可能被占用）"
        } else "已删除 $path"
    }
}

class WriteFileTool(private val context: Context) : YinyueTool {
    override val name = "write_file"
    override val description = "写入（覆盖）工作目录中的文本文件"
    override val dangerous = true
    override val parameters = schemaOf(
        "path" to stringParam("相对文件路径"),
        "content" to stringParam("完整文件内容"),
        required = listOf("path", "content"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = runCatching { args.get("path")?.asString }.getOrNull()
            ?: return@withContext "缺少 path 参数"
        val content = runCatching { args.get("content")?.asString }.getOrNull()
            ?: return@withContext "缺少 content 参数"
        val file = WorkspaceDir.resolveSafe(context, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        "已写入 ${file.name} (${file.length()}B)"
    }
}

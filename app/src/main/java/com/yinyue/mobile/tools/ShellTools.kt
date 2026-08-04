package com.yinyue.mobile.tools

import com.yinyue.mobile.agent.ToolExecutionLimits
import com.yinyue.mobile.agent.YinyueTool
import com.yinyue.mobile.agent.schemaOf
import com.yinyue.mobile.agent.stringParam
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shell 命令执行（应用沙箱权限内；危险操作，需用户确认） */
class ShellTool : YinyueTool {
    override val name = "shell"
    override val description = "在应用沙箱内执行 shell 命令，返回输出（15 秒超时）"
    override val dangerous = true
    override val parameters = schemaOf(
        "command" to stringParam("要执行的 shell 命令"),
        required = listOf("command"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val command = runCatching { args.get("command")?.asString }.getOrNull()
            ?: return@withContext "缺少 command 参数"
        val process = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext "启动命令失败: ${e.message}"
        }
        // 并发读取输出流，避免输出 >64KB 时管道阻塞导致 waitFor 死锁
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS) // 确认子进程已退出
            "命令超时（${TIMEOUT_SECONDS}s）已终止"
        } else {
            val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (e: Exception) { "" }
            "exit=${process.exitValue()}\n${output.take(MAX_OUTPUT_CHARS)}"
        }
    }

    companion object {
        // 引用集中限制常量（借鉴 Operit ToolExecutionLimits）
        private val TIMEOUT_SECONDS = ToolExecutionLimits.SHELL_TIMEOUT_SECONDS
        private val MAX_OUTPUT_CHARS = ToolExecutionLimits.SHELL_MAX_OUTPUT_CHARS
    }
}

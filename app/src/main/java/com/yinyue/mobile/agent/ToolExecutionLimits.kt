package com.yinyue.mobile.agent

/** 工具执行限制集中管理。借鉴 Operit ToolExecutionLimits，避免常量散落各工具。 */
object ToolExecutionLimits {
    /** Shell 命令超时（秒） */
    const val SHELL_TIMEOUT_SECONDS = 15L
    /** Shell 输出最大字符数 */
    const val SHELL_MAX_OUTPUT_CHARS = 4000
    /** 文件读取最大字符数 */
    const val FILE_MAX_READ_CHARS = 8000
    /** 文件写入最大字符数（防止 LLM 写超大文件） */
    const val FILE_MAX_WRITE_CHARS = 50000
    /** 工具结果返回给 LLM 的最大字符数 */
    const val TOOL_RESULT_MAX_CHARS = 4000
    /** Python 执行超时（秒） */
    const val PYTHON_TIMEOUT_SECONDS = 5L
    /** HTTP 请求超时（毫秒） */
    const val HTTP_CONNECT_TIMEOUT_MS = 30000L
    const val HTTP_READ_TIMEOUT_MS = 60000L
    /** 网页抓取最大字符数 */
    const val WEB_FETCH_MAX_CHARS = 8000
    /** Agent 单次任务最大推理步数 */
    const val AGENT_MAX_STEPS = 50
    /** 传给 LLM 的历史消息上限（不含 SYSTEM） */
    const val AGENT_MAX_CONVERSATION_MESSAGES = 40
}

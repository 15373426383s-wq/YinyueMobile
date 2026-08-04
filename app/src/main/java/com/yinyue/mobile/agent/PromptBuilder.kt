package com.yinyue.mobile.agent

import com.yinyue.mobile.accessibility.UiElement

/** System Prompt 与屏幕状态消息构建 */
class PromptBuilder {

    fun systemPrompt(): String = """你是银月，Android智能助手。可用工具:list_files,read_file,write_file,delete_file,shell,list_apps,launch_app,clipboard,open_url,fetch_url,web_search,python_exec,read_screen,click_element,input_text,scroll,global_action。屏幕工具需无障碍服务。回复纯文本，禁止Markdown。简洁直接。"""

    fun buildScreenMessage(elements: List<UiElement>, packageName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("【屏幕感知】前台应用: ${packageName ?: "未知"}")
        sb.appendLine("可交互元素（编号: 类型 \"文本\" 区域 标记）:")
        if (elements.isEmpty()) {
            sb.appendLine("（未读取到可交互元素，可尝试 scroll 或 global_action）")
        }
        elements.forEach { e ->
            val marks = buildList {
                if (e.clickable) add("clickable")
                if (e.editable) add("editable")
            }.joinToString(",")
            sb.appendLine("${e.index}: ${e.className} \"${e.text}\" ${e.bounds.toShortString()} $marks")
        }
        return sb.toString()
    }
}

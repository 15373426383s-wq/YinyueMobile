package com.yinyue.mobile.agent

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 端侧 Function Calling 桥接。借鉴 Operit StructuredToolCallBridge。
 *
 * 职责：
 * 1. 把工具规格（ToolSpec 列表）转为 OpenAI 风格 tools JSON，注入 system prompt；
 * 2. 解析本地模型（经 grammar 约束）输出的 tool_calls JSON 为 [ToolCallDto]。
 *
 * 注意：本类放在 [com.yinyue.mobile.agent] 包，与 [ToolSpec]/[ToolCallDto]/[FunctionCallDto]
 * （定义在 LLMClient.kt）同包，可直接引用；parseToolCalls 返回 [ToolCallDto] 而非
 * data.model.ToolCall，以便直接填入 [ChatMessage.toolCalls]，无需二次转换。
 */
class StructuredToolCallBridge(private val gson: Gson = Gson()) {

    /** 把 ToolSpec 列表转为 OpenAI 风格 tools JSON，供注入 system prompt。 */
    fun toolsToJson(specs: List<ToolSpec>): String = gson.toJson(specs)

    /**
     * 解析 LLM 输出为 [ToolCallDto] 列表（容错：去 markdown fence、提取首个 JSON 对象）。
     * 空白、无 JSON、解析失败均返回空列表。
     * 兼容两种 tool_calls 元素格式（3B 模型常发明简化版）：
     *   A) OpenAI 标准: {"id":"call_1","function":{"name":"launch_app","arguments":"{\"app_package\":\"x\"}"}}
     *   B) 简化版:   {"tool_name":"launch_app","tool_args":{"app_package":"x"}}
     * arguments 兼容 JSON 对象或字符串。
     */
    fun parseToolCalls(response: String): List<ToolCallDto> {
        if (response.isBlank()) return emptyList()
        val cleaned = cleanResponse(response) ?: return emptyList()
        return try {
            val obj = gson.fromJson(cleaned, JsonObject::class.java) ?: return emptyList()
            val calls = obj.getAsJsonArray("tool_calls") ?: return emptyList()
            calls.mapNotNull { el ->
                val c = el.asJsonObject ?: return@mapNotNull null
                val id = c.get("id")?.asString ?: "call_${System.currentTimeMillis()}"
                val fn = c.getAsJsonObject("function")
                if (fn != null) {
                    // 格式 A: function.{name, arguments}
                    val name = fn.get("name")?.asString ?: return@mapNotNull null
                    val argsElem = fn.get("arguments")
                    val args = when {
                        argsElem == null || argsElem.isJsonNull -> "{}"
                        argsElem.isJsonObject -> gson.toJson(argsElem)
                        else -> argsElem.asString
                    }
                    ToolCallDto(id, "function", FunctionCallDto(name, args))
                } else {
                    // 格式 B: tool_name + tool_args（3B 模型常输出此简化版）
                    val name = c.get("tool_name")?.asString ?: return@mapNotNull null
                    val argsElem = c.get("tool_args")
                    val args = when {
                        argsElem == null || argsElem.isJsonNull -> "{}"
                        argsElem.isJsonObject -> gson.toJson(argsElem)
                        else -> argsElem.asString
                    }
                    ToolCallDto(id, "function", FunctionCallDto(name, args))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 提取 LLM 输出中的 content 字段。grammar 允许模型在 content 中放自然语言回复，
     * 当无工具调用时用于改善展示给用户的文本（而非裸 JSON）。无 content 返回 null。
     */
    fun parseContent(response: String): String? {
        if (response.isBlank()) return null
        val cleaned = cleanResponse(response) ?: return null
        return try {
            val obj = gson.fromJson(cleaned, JsonObject::class.java) ?: return null
            obj.get("content")?.takeIf { !it.isJsonNull }?.asString
        } catch (e: Exception) {
            null
        }
    }

    /** 容错清理：去 markdown fence、提取首个完整 {...} JSON 对象（括号配对，防止多个 JSON/文本混杂）。
     *  无 JSON 返回 null。 */
    private fun cleanResponse(s: String): String? {
        var r = s.trim()
        // 去 ```json ... ``` fence
        if (r.startsWith("```")) {
            r = r.removePrefix("```json").removePrefix("```").trim()
            if (r.endsWith("```")) r = r.removeSuffix("```").trim()
        }
        // 括号配对提取首个 {...} 完整对象（比"首{到末}"稳：输出可能含多个 JSON 或夹杂文本）
        val start = r.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until r.length) {
            val c = r[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return r.substring(start, i + 1)
                }
            }
        }
        return null // 括号不闭合，非完整 JSON
    }

    /**
     * 剥掉 LLM 输出中的 chat template 标记（如 <|im_end|>、<|im_start|>user 等），
     * 作为纯文本回复的兜底清洗。与 C++ generate_loop 的 EOG 子串截断形成双保险。
     * 同时清理 Qwen tokenizer 把特殊 token 输出为 "pry]ruby" 等异常残片。
     */
    fun sanitizeText(text: String): String {
        if (text.isBlank()) return text
        val cleaned = text.replace(Regex("<\\|[^|]*\\|>"), "")
            .replace(Regex("\\s*<\\|im_(start|end)\\|>\\s*(user|assistant|system)?"), " ")
            .replace(Regex("\\bpry\\]?\\s*ruby\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bpry\\]"), "")
            .trim()
        return cleaned.replace(Regex("\\{\"tool_calls\":\\[.*?\\]\\}"), "").trim()
    }
}

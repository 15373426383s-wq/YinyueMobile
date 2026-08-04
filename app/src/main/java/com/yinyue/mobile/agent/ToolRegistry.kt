package com.yinyue.mobile.agent

import android.content.Context
import com.yinyue.mobile.data.model.ToolCall
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 工具接口：所有 Agent 工具实现它 */
interface YinyueTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val dangerous: Boolean get() = false
    /** 权限级别：默认由 dangerous 映射，工具可覆盖。借鉴 Operit PermissionLevel。 */
    val permissionLevel: PermissionLevel get() = PermissionLevel.fromDangerous(dangerous)
    suspend fun execute(args: JsonObject): String
    /** 模拟执行：返回虚假结果，不碰系统。默认返回摘要。 */
    suspend fun mock(args: JsonObject): String {
        return "模拟执行 $name(${args.toString().take(80)})"
    }
}

/** 工具注册与调度中心 */
class ToolRegistry(
    private val gson: Gson = Gson(),
    context: Context? = null,
) {

    private val tools = LinkedHashMap<String, YinyueTool>()
    /** 权限系统：用户覆盖持久化。借鉴 Operit ToolPermissionSystem。 */
    private val permissionSystem = context?.let { ToolPermissionSystem(it) }

    fun register(tool: YinyueTool) {
        tools[tool.name] = tool
    }

    fun toolSpecs(): List<ToolSpec> = tools.values.map {
        ToolSpec(function = FunctionSpec(it.name, it.description, it.parameters))
    }

    /** 工具的默认权限级别 */
    fun defaultPermission(name: String): PermissionLevel =
        tools[name]?.permissionLevel ?: PermissionLevel.ALLOW

    /** 工具的有效权限级别（用户覆盖优先） */
    fun effectivePermission(name: String): PermissionLevel =
        if (permissionSystem != null) permissionSystem.effectiveLevel(name, defaultPermission(name))
        else defaultPermission(name)

    /** 是否需要用户确认（ASK 级别） */
    fun needsApproval(name: String): Boolean =
        effectivePermission(name) == PermissionLevel.ASK

    /** 是否被禁止 */
    fun isForbidden(name: String): Boolean =
        effectivePermission(name) == PermissionLevel.FORBID

    /** 设置"始终允许"（用户确认时勾选） */
    fun setAlwaysAllow(name: String) {
        permissionSystem?.setOverride(name, PermissionLevel.ALLOW)
    }

    /** 清除覆盖，恢复默认 */
    fun clearOverride(name: String) {
        permissionSystem?.clearOverride(name)
    }

    /** @deprecated 用 effectivePermission/needsApproval 替代 */
    fun isDangerous(name: String): Boolean = defaultPermission(name) == PermissionLevel.ASK

    suspend fun execute(call: ToolCall): String {
        val tool = tools[call.name] ?: return "错误：未注册的工具 ${call.name}"
        if (isForbidden(call.name)) return "错误：工具 ${call.name} 已被用户禁止"
        val args = try {
            gson.fromJson(call.argumentsJson, JsonObject::class.java) ?: JsonObject()
        } catch (e: Exception) {
            return "错误：工具参数不是合法 JSON - ${e.message}"
        }
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // 修复 6.1：不吞取消异常，保住协程取消语义
            "工具 ${call.name} 执行失败: ${e.message}"
        }
    }

    suspend fun mockExecute(call: ToolCall): String {
        val tool = tools[call.name] ?: return "错误：未注册的工具 ${call.name}"
        val args = try {
            gson.fromJson(call.argumentsJson, JsonObject::class.java) ?: JsonObject()
        } catch (e: Exception) {
            return "错误：工具参数不是合法 JSON - ${e.message}"
        }
        return try {
            "[模拟] ${tool.mock(args)}"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // 修复 6.1：不吞取消异常，保住协程取消语义
            "工具 ${call.name} 模拟失败: ${e.message}"
        }
    }
}

// ---------- JSON Schema 构造辅助 ----------

fun schemaOf(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject {
    val schema = JsonObject()
    schema.addProperty("type", "object")
    val props = JsonObject()
    properties.forEach { (k, v) -> props.add(k, v) }
    schema.add("properties", props)
    if (required.isNotEmpty()) {
        val arr = JsonArray()
        required.forEach { arr.add(it) }
        schema.add("required", arr)
    }
    return schema
}

fun stringParam(desc: String, enum: List<String>? = null): JsonObject = JsonObject().apply {
    addProperty("type", "string")
    addProperty("description", desc)
    enum?.let { values ->
        val arr = JsonArray()
        values.forEach { arr.add(it) }
        add("enum", arr)
    }
}

fun intParam(desc: String): JsonObject = JsonObject().apply {
    addProperty("type", "integer")
    addProperty("description", desc)
}

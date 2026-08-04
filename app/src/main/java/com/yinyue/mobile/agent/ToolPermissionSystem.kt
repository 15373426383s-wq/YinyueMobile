package com.yinyue.mobile.agent

import android.content.Context
import android.content.SharedPreferences

/** 工具权限管理系统：默认级别 + 用户覆盖 + 持久化。借鉴 Operit ToolPermissionSystem。 */
class ToolPermissionSystem(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tool_permissions", Context.MODE_PRIVATE)

    /** 获取工具的有效权限级别（用户覆盖优先，否则工具默认） */
    fun effectiveLevel(toolName: String, defaultLevel: PermissionLevel): PermissionLevel {
        val stored = prefs.getString(toolName, null)
        return stored?.let { runCatching { PermissionLevel.valueOf(it) }.getOrNull() } ?: defaultLevel
    }

    /** 用户覆盖并持久化（用于"始终允许"或"禁止"） */
    fun setOverride(toolName: String, level: PermissionLevel) {
        prefs.edit().putString(toolName, level.name).apply()
    }

    /** 清除用户覆盖，恢复默认 */
    fun clearOverride(toolName: String) {
        prefs.edit().remove(toolName).apply()
    }

    /** 检查是否需要询问用户 */
    fun needsApproval(toolName: String, defaultLevel: PermissionLevel): Boolean =
        effectiveLevel(toolName, defaultLevel) == PermissionLevel.ASK

    /** 检查是否被禁止 */
    fun isForbidden(toolName: String, defaultLevel: PermissionLevel): Boolean =
        effectiveLevel(toolName, defaultLevel) == PermissionLevel.FORBID
}

package com.yinyue.mobile.agent

/** 工具权限级别。借鉴 Operit 的 ALLOW/ASK/FORBID 三态 + ALWAYS_ALLOW 持久化。 */
enum class PermissionLevel {
    /** 直接执行，无需确认 */
    ALLOW,
    /** 每次询问用户（默认对危险工具） */
    ASK,
    /** 禁止执行 */
    FORBID;

    companion object {
        /** 从布尔 dangerous 兼容映射：dangerous=true→ASK，false→ALLOW */
        fun fromDangerous(dangerous: Boolean): PermissionLevel =
            if (dangerous) ASK else ALLOW
    }
}

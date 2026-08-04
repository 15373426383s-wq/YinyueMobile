package com.yinyue.mobile.tools

import java.io.File

/** 路径校验工具，防止路径遍历攻击。借鉴 Operit PathValidator。 */
object PathValidator {
    /** 校验 target 在 root 目录内（用 canonicalPath + 分隔符，防前缀绕过） */
    fun ensureWithin(root: File, target: File) {
        val rootCanon = root.canonicalPath
        val targetCanon = target.canonicalPath
        if (targetCanon != rootCanon && !targetCanon.startsWith(rootCanon + File.separator)) {
            throw SecurityException("路径越界: ${target.path} 不在 ${root.path} 内")
        }
    }

    /** 校验路径非空且规范化后无越界 */
    fun validateRelative(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        ensureWithin(root, target)
        return target
    }
}

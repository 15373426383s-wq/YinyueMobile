package com.yinyue.mobile.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一个任务 = 一条线程（Thread） */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

object TaskStatus {
    const val RUNNING = "running"
    const val WAITING_APPROVAL = "waiting_approval"
    const val DONE = "done"
    const val FAILED = "failed"
}

// 添加外键级联删除，避免任务删除后产生孤儿消息
@Entity(
    tableName = "messages",
    indices = [Index("taskId")],
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val role: String,
    val content: String,
    val imagePaths: String? = null, // 逗号分隔的图片路径，用于 USER 消息附带的图片
    val kind: String = MessageKind.TEXT,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

object MessageKind {
    const val TEXT = "text"
    const val THINKING = "thinking"
    const val TOOL = "tool"
}

/** Agent 运行期的一次工具调用（内存模型，对应 OpenAI tool_calls） */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

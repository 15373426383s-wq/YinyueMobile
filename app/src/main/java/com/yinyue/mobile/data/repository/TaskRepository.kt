package com.yinyue.mobile.data.repository

import com.yinyue.mobile.data.db.AppDatabase
import com.yinyue.mobile.data.model.TaskEntity
import com.yinyue.mobile.data.model.TaskStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val db: AppDatabase) {

    val tasks: Flow<List<TaskEntity>> = db.taskDao().observeTasks()

    suspend fun create(title: String): TaskEntity {
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "未命名任务" },
            status = TaskStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
        )
        db.taskDao().upsert(task)
        return task
    }

    suspend fun setStatus(taskId: String, status: String) =
        db.taskDao().updateStatus(taskId, status, System.currentTimeMillis())

    suspend fun delete(taskId: String) = db.taskDao().delete(taskId)

    suspend fun clearAll() = db.taskDao().deleteAll()
}

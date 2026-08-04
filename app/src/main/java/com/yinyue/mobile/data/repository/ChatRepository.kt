package com.yinyue.mobile.data.repository

import com.yinyue.mobile.data.db.AppDatabase
import com.yinyue.mobile.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val db: AppDatabase) {

    fun messages(taskId: String): Flow<List<MessageEntity>> =
        db.chatDao().observeMessages(taskId)

    suspend fun append(message: MessageEntity): Long = db.chatDao().insert(message)

    suspend fun deleteAll(taskId: String) = db.chatDao().clearTask(taskId)

    suspend fun clearAll() = db.chatDao().clearAll()
}

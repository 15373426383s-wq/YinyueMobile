package com.yinyue.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yinyue.mobile.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY timestamp ASC, id ASC")
    fun observeMessages(taskId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE taskId = :taskId")
    suspend fun clearTask(taskId: String)

    @Query("SELECT * FROM messages WHERE taskId = :taskId AND role IN ('user', 'assistant') ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecent(taskId: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

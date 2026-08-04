package com.yinyue.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yinyue.mobile.data.model.MessageEntity
import com.yinyue.mobile.data.model.TaskEntity

@Database(
    entities = [TaskEntity::class, MessageEntity::class],
    version = 5, // 4→5：补 messages 表索引；3→4：外键级联删除
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "Yinyue.db",
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    // 仅在降级时销毁数据，升级走 Migration
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }

        // 添加 ForeignKey 级联删除的迁移：重建 messages 表
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 清理前次失败迁移可能残留的临时表
                db.execSQL("DROP TABLE IF EXISTS messages_new")
                db.execSQL("""
                    CREATE TABLE messages_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        taskId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        imagePaths TEXT,
                        kind TEXT NOT NULL,
                        toolName TEXT,
                        toolArgs TEXT,
                        toolResult TEXT,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_taskId ON messages_new(taskId)")
                db.execSQL("""
                    INSERT INTO messages_new (id, taskId, role, content, imagePaths, kind, toolName, toolArgs, toolResult, timestamp)
                    SELECT id, taskId, role, content, imagePaths, kind, toolName, toolArgs, toolResult, timestamp FROM messages
                """.trimIndent())
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
            }
        }

        // 兜底补索引（4→5）：如果 MIGRATION_3_4 因旧版代码遗漏索引，这里补齐
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_taskId ON messages(taskId)")
            }
        }
    }
}

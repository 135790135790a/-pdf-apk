package com.mangashelf.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Book::class, PageEntry::class, ImportTask::class],
    version = 4,
    // 打开 schema 导出：schemas/ 下的 JSON 是以后写迁移与迁移测试的唯一依据
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun pageDao(): PageDao

    abstract fun importTaskDao(): ImportTaskDao

    companion object {
        const val DB_NAME = "mangashelf.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }

        /**
         * 释放当前实例。数据库文件损坏、需要删库重建时调用——
         * 旧连接不关，新库会被它锁住或者继续用坏文件。
         */
        @Synchronized
        fun reset() {
            runCatching { instance?.close() }
            instance = null
        }
    }
}

package com.mangashelf.reader.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 → v3 迁移测试（设备/模拟器：`gradlew connectedDebugAndroidTest`）。
 *
 * 覆盖 [MIGRATION_2_3] 最容易出错的几件事：
 * 1. 重建 pages 表后行数、id、idx、manualPos、大小/时间全部原样保留
 * 2. 新列 originName 拿到空串（不是 NULL）
 * 3. `index_pages_bookId` 还在
 * 4. 外键 ON DELETE CASCADE 重建后依然生效（重建表最大的坑）
 */
@RunWith(AndroidJUnit4::class)
class MigrationV2ToV3Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val v2Create = listOf(
        """
        CREATE TABLE IF NOT EXISTS `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `folder` TEXT NOT NULL,
            `pageCount` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `lastPage` INTEGER NOT NULL,
            `rtl` INTEGER NOT NULL,
            `sortMode` TEXT NOT NULL,
            `sortAsc` INTEGER NOT NULL,
            `sourceKey` TEXT,
            `sourceTreeUri` TEXT,
            `sourceType` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `pages` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `bookId` INTEGER NOT NULL,
            `idx` INTEGER NOT NULL,
            `relPath` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `modifiedAt` INTEGER NOT NULL,
            `manualPos` INTEGER,
            FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)"
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("migration-v2.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-v2.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        v2Create.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        Unit
                })
                .build()
        )
        db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    @After
    fun tearDown() {
        db.close()
        helper.close()
    }

    private fun seed() {
        db.execSQL(
            "INSERT INTO books (id,title,folder,pageCount,createdAt,lastPage,rtl,sortMode,sortAsc,sourceKey,sourceTreeUri,sourceType)" +
                " VALUES (1,'甲','fA',2,1000,1,1,'FIXED',1,NULL,NULL,'FIXED')"
        )
        db.execSQL(
            "INSERT INTO books (id,title,folder,pageCount,createdAt,lastPage,rtl,sortMode,sortAsc,sourceKey,sourceTreeUri,sourceType)" +
                " VALUES (2,'乙','fB',3,2000,0,0,'NATURAL',1,'k','content://t','FOLDER')"
        )
        db.execSQL("INSERT INTO pages (id,bookId,idx,relPath,sizeBytes,modifiedAt,manualPos) VALUES (1,1,0,'page_001.jpg',1000,111,NULL)")
        db.execSQL("INSERT INTO pages (id,bookId,idx,relPath,sizeBytes,modifiedAt,manualPos) VALUES (2,1,1,'page_002.jpg',2000,222,NULL)")
        db.execSQL("INSERT INTO pages (id,bookId,idx,relPath,sizeBytes,modifiedAt,manualPos) VALUES (3,2,0,'page_001.jpg',3000,333,5)")
        db.execSQL("INSERT INTO pages (id,bookId,idx,relPath,sizeBytes,modifiedAt,manualPos) VALUES (4,2,1,'page_002.jpg',4000,444,NULL)")
        db.execSQL("INSERT INTO pages (id,bookId,idx,relPath,sizeBytes,modifiedAt,manualPos) VALUES (5,2,2,'page_003.jpg',5000,555,NULL)")
    }

    @Test
    fun migrationKeepsEveryPageRowAndFillsOriginName() {
        seed()
        MIGRATION_2_3.migrate(db)

        db.query(
            "SELECT id, bookId, idx, relPath, originName, sizeBytes, modifiedAt, manualPos FROM pages ORDER BY id"
        ).use { c ->
            assertEquals(5, c.count)
            val expected = listOf(
                Triple(1L, 1L, 0) to Triple("page_001.jpg", "", 1000L),
                Triple(2L, 1L, 1) to Triple("page_002.jpg", "", 2000L),
                Triple(3L, 2L, 0) to Triple("page_001.jpg", "", 3000L),
                Triple(4L, 2L, 1) to Triple("page_002.jpg", "", 4000L),
                Triple(5L, 2L, 2) to Triple("page_003.jpg", "", 5000L)
            )
            var i = 0
            while (c.moveToNext()) {
                assertEquals(expected[i].first, Triple(c.getLong(0), c.getLong(1), c.getInt(2)))
                assertEquals(expected[i].second, Triple(c.getString(3), c.getString(4), c.getLong(5)))
                i++
            }
            assertEquals(5, i)
        }

        // manualPos 原样保留（第 3 行是 5）
        db.query("SELECT manualPos FROM pages WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5, c.getInt(0))
        }

        // 索引还在
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_pages_bookId'").use { c ->
            assertEquals(1, c.count)
        }
    }

    @Test
    fun cascadeDeleteStillWorksAfterTableRebuild() {
        seed()
        MIGRATION_2_3.migrate(db)

        db.execSQL("DELETE FROM books WHERE id = 1")
        db.query("SELECT COUNT(*) FROM pages WHERE bookId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM pages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
    }
}

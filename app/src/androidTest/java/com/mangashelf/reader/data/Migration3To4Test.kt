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
 * v3 → v4 迁移测试：只新增 import_tasks 表，不碰既有数据。
 *
 * 这条迁移最容易犯的错是"新表结构与 Room 期望对不上"（列顺序、NOT NULL、索引名）。
 * 单元测试跑不到真实 SQLite，所以这层验证放在设备上，等项目有真机时用
 * `gradlew connectedDebugAndroidTest` 跑一次即可。
 */
@RunWith(AndroidJUnit4::class)
class Migration3To4Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val v3CreateBooks = """
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
    """.trimIndent()

    private val v3CreatePages = """
        CREATE TABLE IF NOT EXISTS `pages` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `bookId` INTEGER NOT NULL,
            `idx` INTEGER NOT NULL,
            `relPath` TEXT NOT NULL,
            `originName` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `modifiedAt` INTEGER NOT NULL,
            `manualPos` INTEGER,
            FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
    """.trimIndent()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("migration-3-4.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-3-4.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v3CreateBooks)
                        db.execSQL(v3CreatePages)
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        helper.close()
    }

    @Test
    fun addsTaskTableAndKeepsExistingData() {
        db.execSQL(
            "INSERT INTO books (id, title, folder, pageCount, createdAt, lastPage, rtl, sortMode, sortAsc, sourceType) " +
                "VALUES (1, '老书', 'fA', 2, 1000, 1, 1, 'NATURAL', 1, 'FOLDER')"
        )
        db.execSQL(
            "INSERT INTO pages (bookId, idx, relPath, originName, sizeBytes, modifiedAt) " +
                "VALUES (1, 0, 'page_001.jpg', '01.jpg', 100, 200)"
        )

        MIGRATION_3_4.migrate(db)

        // 1) 老表数据一条不少
        db.query("SELECT title, pageCount, sortMode FROM books").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("老书", c.getString(0))
            assertEquals(2, c.getInt(1))
            assertEquals("NATURAL", c.getString(2))
        }
        db.query("SELECT relPath, originName, sizeBytes FROM pages").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("page_001.jpg", c.getString(0))
            assertEquals("01.jpg", c.getString(1))
            assertEquals(100, c.getInt(2))
        }

        // 2) 新表存在、为空、列齐全
        db.query("SELECT COUNT(*) FROM import_tasks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // 3) 能正常写入并读回（列顺序/类型不对会在这里炸）
        db.execSQL(
            "INSERT INTO import_tasks " +
                "(batchId, title, sourceKey, sourceTreeUri, sourceType, sortIndex, status, error, createdAt, updatedAt) " +
                "VALUES ('b1', '第001卷', 'k1', 'content://tree/1', 'IMAGE', 0, 'PENDING', NULL, 100, 100)"
        )
        db.query(
            "SELECT batchId, title, status, sortIndex FROM import_tasks"
        ).use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("b1", c.getString(0))
            assertEquals("第001卷", c.getString(1))
            assertEquals("PENDING", c.getString(2))
            assertEquals(0, c.getInt(3))
        }

        // 4) batchId 索引在（重试时要按它查整批）
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_import_tasks_batchId'"
        ).use { c ->
            assertEquals(1, c.count)
        }
    }
}

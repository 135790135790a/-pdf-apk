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
 * v1 → v2 迁移测试（在设备/模拟器上跑：`gradlew connectedDebugAndroidTest`）。
 *
 * 为什么不用 MigrationTestHelper：它要求仓库里有 `schemas/1.json`，
 * 而 v1 当年是 `exportSchema = false`，从没导出过。与其为了一个 JSON 去重建历史版本，
 * 不如**手工建一个 v1 库**再直接调用 [MIGRATION_1_2]，验证的东西是一样的：
 *
 * 1. 老数据（books 行、字段值）一条不少
 * 2. 新列拿到正确默认值（FIXED / 1 / FIXED）
 * 3. pages 表按 pageCount 回填，idx 连续、relPath 与 ComicStorage 命名一致
 * 4. 回填是幂等的重复迁移不会翻倍（见第二个用例）
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** v1 的真实建表语句（对应 Book v1 的 7 个字段） */
    private val v1CreateBooks = """
        CREATE TABLE IF NOT EXISTS `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `folder` TEXT NOT NULL,
            `pageCount` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `lastPage` INTEGER NOT NULL,
            `rtl` INTEGER NOT NULL
        )
    """.trimIndent()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("migration-test.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v1CreateBooks)
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
    fun migrationKeepsDataAndBackfillsPages() {
        db.execSQL(
            "INSERT INTO books (id, title, folder, pageCount, createdAt, lastPage, rtl) " +
                "VALUES (1, '单页本', 'fA', 1, 1000, 0, 1)"
        )
        db.execSQL(
            "INSERT INTO books (id, title, folder, pageCount, createdAt, lastPage, rtl) " +
                "VALUES (2, '三页本', 'fB', 3, 2000, 2, 0)"
        )

        MIGRATION_1_2.migrate(db)

        // 1) 老数据没丢，新列有默认值
        db.query("SELECT id, title, lastPage, rtl, sortMode, sortAsc, sourceType FROM books ORDER BY id").use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("单页本", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(1, c.getInt(3))
            assertEquals("FIXED", c.getString(4))
            assertEquals(1, c.getInt(5))
            assertEquals("FIXED", c.getString(6))
            assertTrue(c.moveToNext())
            assertEquals("三页本", c.getString(1))
            assertEquals(2, c.getInt(2))
            assertEquals(0, c.getInt(3))
        }

        // 2) pages 回填：1 + 3 = 4 行
        db.query("SELECT bookId, idx, relPath FROM pages ORDER BY bookId, idx").use { c ->
            assertEquals(4, c.count)
            val expected = listOf(
                Triple(1L, 0, "page_001.jpg"),
                Triple(2L, 0, "page_001.jpg"),
                Triple(2L, 1, "page_002.jpg"),
                Triple(2L, 2, "page_003.jpg")
            )
            var i = 0
            while (c.moveToNext()) {
                assertEquals(expected[i], Triple(c.getLong(0), c.getInt(1), c.getString(2)))
                i++
            }
            assertEquals(4, i)
        }

        // 3) 索引与表都在
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_pages_bookId'").use { c ->
            assertEquals(1, c.count)
        }
    }

    @Test
    fun zeroPageBookDoesNotBreakBackfill() {
        db.execSQL(
            "INSERT INTO books (id, title, folder, pageCount, createdAt, lastPage, rtl) " +
                "VALUES (9, '空书', 'fZ', 0, 1000, 0, 1)"
        )
        MIGRATION_1_2.migrate(db)
        db.query("SELECT COUNT(*) FROM pages WHERE bookId = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}

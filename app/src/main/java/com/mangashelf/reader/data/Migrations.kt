package com.mangashelf.reader.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 → v3：给 pages 加 originName（原始文件名），供「按名称排序」。
 *
 * 同样用重建表：`ALTER TABLE ... ADD COLUMN originName TEXT NOT NULL DEFAULT ''`
 * 会在 sqlite_master 里留下 DEFAULT 子句，和 Room 期望的结构对不上。
 * pages 的行数等于总页数（几千行），重建一次是毫秒级，不值得冒 schema 不一致的风险。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pages_new` (
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
        )
        database.execSQL(
            """
            INSERT INTO `pages_new`
                (`id`, `bookId`, `idx`, `relPath`, `originName`, `sizeBytes`, `modifiedAt`, `manualPos`)
            SELECT `id`, `bookId`, `idx`, `relPath`, '', `sizeBytes`, `modifiedAt`, `manualPos`
            FROM `pages`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `pages`")
        database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)")
    }
}

/**
 * v1 → v2：为"批量导入 + 每本书独立排序"打地基。
 *
 * 原则：**绝不丢数据、绝不重排老页序**。
 * - 老书 sortMode/sourceType 置为 'FIXED'，表示"顺序固定、不参与重排"，
 *   页序与升级前完全一致。
 * - 新建 pages 表，按每本 pageCount 回填 idx，relPath 与 ComicStorage 命名一致。
 *
 * 为什么 books 用"重建表"而不是 `ALTER TABLE ADD COLUMN ... DEFAULT`：
 * ALTER 加的列会在 sqlite_master 里留下 `DEFAULT 'FIXED'`，而 Room 从实体生成的
 * 期望 schema 里没有 DEFAULT 子句，两者对不上。Room 正常升级路径不校验 schema，
 * 但只要 identity hash 出问题、或用工具检查，就会报 "Migration didn't properly
 * handle" 而打不开库。重建表能让迁移后的结构**与 Room 期望逐字符一致**，
 * 代价只是多几条 SQL（整段在 Room 的事务里执行，中途失败会整体回滚）。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1) 按 Room 期望的结构重建 books（新列不带 DEFAULT，值在 INSERT SELECT 里给定）
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `books_new` (
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
        )
        database.execSQL(
            """
            INSERT INTO `books_new`
                (`id`, `title`, `folder`, `pageCount`, `createdAt`, `lastPage`, `rtl`,
                 `sortMode`, `sortAsc`, `sourceKey`, `sourceTreeUri`, `sourceType`)
            SELECT `id`, `title`, `folder`, `pageCount`, `createdAt`, `lastPage`, `rtl`,
                   'FIXED', 1, NULL, NULL, 'FIXED'
            FROM `books`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `books`")
        database.execSQL("ALTER TABLE `books_new` RENAME TO `books`")

        // 2) 页面顺序表
        database.execSQL(
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
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)")

        // 3) 老书回填：每本插入 pageCount 行，relPath 与 ComicStorage.pageFile 的命名一致
        database.execSQL(
            """
            INSERT INTO `pages` (`bookId`, `idx`, `relPath`, `sizeBytes`, `modifiedAt`)
            WITH RECURSIVE `seq`(`bookId`, `pos`, `total`) AS (
                SELECT `id`, 0, `pageCount` FROM `books` WHERE `pageCount` > 0
                UNION ALL
                SELECT `bookId`, `pos` + 1, `total` FROM `seq` WHERE `pos` + 1 < `total`
            )
            SELECT `bookId`, `pos`, printf('page_%03d.jpg', `pos` + 1), 0, 0 FROM `seq`
            """.trimIndent()
        )
    }
}

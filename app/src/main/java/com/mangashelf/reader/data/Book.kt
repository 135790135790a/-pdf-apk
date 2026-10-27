package com.mangashelf.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一本漫画书。
 *
 * 页面图片以 JPEG 的形式存放在应用私有目录 `files/comics/<folder>/page_001.jpg` 下，
 * 数据库里只保存目录名与页数，避免路径随应用安装变化而失效。
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 书名，导入时取自文件名，可在书架里重命名 */
    val title: String,
    /** `files/comics` 下的子目录名 */
    val folder: String,
    /** 总页数 */
    val pageCount: Int,
    /** 导入时间，用于书架倒序排列 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 上次读到的页码（0 基） */
    val lastPage: Int = 0,
    /** true = 日漫从右往左翻页；false = 从左往右 */
    val rtl: Boolean = true,

    // ---------------------------------------------------------------------
    // v1 → v2 新增（见 Migrations.kt）
    // 全部带默认值，老数据的语义是"FIXED：页序固定，不参与重排"，与升级前行为一致。
    // ---------------------------------------------------------------------

    /** 排序规则：NATURAL / NAME / MTIME / SIZE / MANUAL / FIXED */
    val sortMode: String = "FIXED",
    /** 排序方向：true = 正序 */
    val sortAsc: Boolean = true,
    /** 去重键：同一源文件夹再次导入时用来识别"已经导过" */
    val sourceKey: String? = null,
    /** 源目录的 tree URI（已 takePersistableUriPermission），便于以后重新读取 */
    val sourceTreeUri: String? = null,
    /** 来源类型：IMAGE / PDF / FIXED（FIXED = 老数据，未知） */
    val sourceType: String = "FIXED",

    // ---------------------------------------------------------------------
    // v4 → v5 新增（见 Migrations.kt MIGRATION_4_5）
    // 带 defaultValue：迁移用 ALTER TABLE ADD COLUMN，Room 期望的结构里也得有默认值才一致。
    // ---------------------------------------------------------------------

    /** 原始来源大小（PDF 文件字节数 / 文件夹里图片总字节数）；0 = 未知 */
    @ColumnInfo(defaultValue = "0")
    val sourceSizeBytes: Long = 0L,
    /** 封面用第几页（0 基，指的是"未删除页里的第几个"）；被删则回退第一页 */
    @ColumnInfo(defaultValue = "0")
    val coverPageIdx: Int = 0
) {
    val progressFraction: Float
        get() = if (pageCount <= 1) 0f else (lastPage.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
}

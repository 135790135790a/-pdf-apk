package com.mangashelf.reader.data

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
    val rtl: Boolean = true
) {
    val progressFraction: Float
        get() = if (pageCount <= 1) 0f else (lastPage.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
}

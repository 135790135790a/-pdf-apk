package com.mangashelf.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导入任务的一条记录。
 *
 * 存在的唯一理由：进程被系统回收时（后台太久、内存吃紧），内存里的队列会跟着消失，
 * 用户再打开 App 只会看到"少了几本书"，不知道发生了什么。把任务落到磁盘上，
 * 下次启动就能说清楚"上次有 N 本没导完"，并且能按 [sourceTreeUri] + [sourceKey] 重试。
 *
 * 成功导入的书不依赖这张表：书本身已经在 books 里了。这张表只记录"批次与进度"。
 */
@Entity(
    tableName = "import_tasks",
    indices = [Index("batchId")]
)
data class ImportTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 一次批量导入共享一个 batchId，重试时按它找回整批 */
    val batchId: String,
    val title: String,
    /** SAF document id，用于去重和重试定位；PDF/单文件夹导入也有 */
    val sourceKey: String?,
    val sourceTreeUri: String?,
    /** PDF / IMAGE / FOLDER */
    val sourceType: String,
    /** 批次内顺序，保持重试时的原始顺序 */
    val sortIndex: Int,
    /** PENDING / RUNNING / DONE / FAILED / CANCELLED */
    val status: String,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,

    // ---------------------------------------------------------------------
    // v6 → v7 新增（见 Migrations.kt MIGRATION_6_7）
    // 页级渲染进度：一本 1000 页的 PDF 要渲染十几分钟，只有"第几本"会让人以为卡死。
    // ---------------------------------------------------------------------

    /** 预计总页数；PDF 在打开文档后就知道，图片/文件夹要边导边数，未知时为 0 */
    @ColumnInfo(defaultValue = "0")
    val totalPages: Int = 0,
    /** 已经渲染完的页数 */
    @ColumnInfo(defaultValue = "0")
    val renderedPages: Int = 0,
    /** 当前正在渲染的页码（1 基，0 表示还没开始） */
    @ColumnInfo(defaultValue = "0")
    val currentPage: Int = 0
) {
    companion object {
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}

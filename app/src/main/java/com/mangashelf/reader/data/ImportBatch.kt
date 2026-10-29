package com.mangashelf.reader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次「导入动作」的批次记录（v1.5.6 新增）。
 *
 * 与 [ImportTask] 的关系：
 * - 一个批次 = 用户点了一次「批量导入」，下辖 N 本书，每本书是一条 [ImportTask]（`batchId` 相同）；
 * - 批次记录的是「这次导入整体怎么样」：总书数、成功/失败/跳过、总页数、总大小、耗时、最终状态；
 * - 导入历史页面与饼状图直接读这张表，不再从 tasks 现场聚合（几千条 task 聚合会卡）。
 *
 * 为什么状态里没有「书的 PAUSED」：暂停只在**书册边界**生效，暂停时当前这本已经导完，
 * 剩下的书仍是 PENDING，批次标 PAUSED 就够表达了。
 */
@Entity(
    tableName = "import_batches",
    indices = [Index("batchId"), Index("createdAt")]
)
data class ImportBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 与 ImportTask.batchId 对应 */
    val batchId: String,
    val createdAt: Long,
    /** 结束时间；未结束时为 0 */
    val finishedAt: Long = 0,
    /** FOLDER / PDF / IMAGE / CBZ */
    val sourceType: String = "FOLDER",
    /** 父文件夹树 URI（批量重试要用父树，子树没有 SAF 授权） */
    val rootTreeUri: String? = null,
    val status: String = "QUEUED",
    val totalBooks: Int = 0,
    val doneBooks: Int = 0,
    val failedBooks: Int = 0,
    val skippedBooks: Int = 0,
    val totalPages: Int = 0,
    val donePages: Int = 0,
    val sizeBytes: Long = 0,
    val elapsedMs: Long = 0,
    val failReason: String? = null
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val PAUSED = "PAUSED"
        const val DONE = "DONE"
        /** 有成功也有失败 */
        const val PARTIAL = "PARTIAL"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"

        /** 还没跑完的状态（App 启动时用来恢复） */
        val UNFINISHED = listOf(QUEUED, RUNNING, PAUSED)

        fun isFinished(status: String): Boolean =
            status == DONE || status == PARTIAL || status == FAILED || status == CANCELLED
    }
}

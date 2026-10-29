package com.mangashelf.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ImportBatchDao {

    @Insert
    suspend fun insert(batch: ImportBatch): Long

    @Query("SELECT * FROM import_batches WHERE batchId = :batchId LIMIT 1")
    suspend fun byBatchId(batchId: String): ImportBatch?

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ImportBatch>

    /** 全部历史（导入历史页用；配合 [page] 做懒加载） */
    @Query(
        "SELECT * FROM import_batches WHERE (:since = 0 OR createdAt >= :since) " +
            "AND (:status = '' OR status = :status) " +
            "AND (:sourceType = '' OR sourceType = :sourceType) " +
            "ORDER BY createdAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun page(since: Long, status: String, sourceType: String, limit: Int, offset: Int): List<ImportBatch>

    @Query(
        "SELECT COUNT(*) FROM import_batches WHERE (:since = 0 OR createdAt >= :since) " +
            "AND (:status = '' OR status = :status) " +
            "AND (:sourceType = '' OR sourceType = :sourceType)"
    )
    suspend fun count(since: Long, status: String, sourceType: String): Int

    @Query("SELECT * FROM import_batches WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY createdAt")
    suspend fun unfinished(): List<ImportBatch>

    @Query(
        "UPDATE import_batches SET status = :status, finishedAt = :finishedAt, " +
            "doneBooks = :doneBooks, failedBooks = :failedBooks, skippedBooks = :skippedBooks, " +
            "totalPages = :totalPages, donePages = :donePages, sizeBytes = :sizeBytes, " +
            "elapsedMs = :elapsedMs, failReason = :failReason WHERE batchId = :batchId"
    )
    suspend fun finish(
        batchId: String,
        status: String,
        finishedAt: Long,
        doneBooks: Int,
        failedBooks: Int,
        skippedBooks: Int,
        totalPages: Int,
        donePages: Int,
        sizeBytes: Long,
        elapsedMs: Long,
        failReason: String?
    )

    @Query("UPDATE import_batches SET status = :status WHERE batchId = :batchId")
    suspend fun setStatus(batchId: String, status: String)

    @Query("UPDATE import_batches SET doneBooks = :doneBooks, donePages = :donePages WHERE batchId = :batchId")
    suspend fun setProgress(batchId: String, doneBooks: Int, donePages: Int)

    @Query("UPDATE import_batches SET totalBooks = :totalBooks, totalPages = :totalPages WHERE batchId = :batchId")
    suspend fun setTotals(batchId: String, totalBooks: Int, totalPages: Int)

    /** 清空历史（不影响已导入的书） */
    @Query("DELETE FROM import_batches")
    suspend fun deleteAll()

    @Query("DELETE FROM import_batches WHERE createdAt < :before")
    suspend fun pruneBefore(before: Long)

    /** v1.5.6：导入历史用——全部批次，新的在前 */
    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ImportBatch>>
}

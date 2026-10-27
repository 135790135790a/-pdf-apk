package com.mangashelf.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ImportTaskDao {

    @Insert
    suspend fun insertAll(tasks: List<ImportTask>): List<Long>

    @Insert
    suspend fun insert(task: ImportTask): Long

    @Query("UPDATE import_tasks SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?, now: Long)

    @Query("UPDATE import_tasks SET status = :status, updatedAt = :now WHERE batchId = :batchId AND status = 'PENDING'")
    suspend fun cancelPending(batchId: String, status: String, now: Long)

    /** 上次没跑完的（进程被杀时会留在这个状态） */
    @Query("SELECT * FROM import_tasks WHERE status IN ('PENDING', 'RUNNING') ORDER BY createdAt, sortIndex")
    suspend fun unfinished(): List<ImportTask>

    @Query("SELECT * FROM import_tasks WHERE batchId = :batchId ORDER BY sortIndex")
    suspend fun byBatch(batchId: String): List<ImportTask>

    @Query("UPDATE import_tasks SET status = :status, error = :error, updatedAt = :now WHERE batchId = :batchId AND status IN ('PENDING', 'RUNNING')")
    suspend fun failUnfinished(batchId: String, status: String, error: String?, now: Long)

    @Query("UPDATE import_tasks SET status = 'PENDING', error = NULL, updatedAt = :now WHERE batchId = :batchId AND status = 'FAILED'")
    suspend fun resetFailed(batchId: String, now: Long)

    /** 只保留最近 N 条批次记录，避免这张表无限长 */
    @Query("DELETE FROM import_tasks WHERE createdAt < :before")
    suspend fun pruneBefore(before: Long)
}

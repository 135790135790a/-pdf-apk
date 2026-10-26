package com.mangashelf.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY idx ASC")
    fun observeByBook(bookId: Long): Flow<List<PageEntry>>

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY idx ASC")
    suspend fun getByBook(bookId: Long): List<PageEntry>

    @Insert
    suspend fun insertAll(pages: List<PageEntry>)

    @Query("DELETE FROM pages WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("UPDATE pages SET idx = :idx WHERE id = :id")
    suspend fun updateIndex(id: Long, idx: Int)

    @Query("UPDATE pages SET manualPos = :pos WHERE id = :id")
    suspend fun updateManualPos(id: Long, pos: Int)

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    suspend fun countByBook(bookId: Long): Int

    /**
     * 整本替换页序：删旧行 + 插新行必须原子，否则中途失败会留下"没有 pages 行"的书，
     * 阅读器只能回退到按文件名猜顺序。
     */
    @Transaction
    suspend fun replaceAll(bookId: Long, pages: List<PageEntry>) {
        deleteByBook(bookId)
        insertAll(pages)
    }
}

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

    // ---------------------------------------------------------------------
    // v5：页面级管理
    // ---------------------------------------------------------------------

    /** 未删除的页（阅读器 / 书架统计用） */
    @Query("SELECT * FROM pages WHERE bookId = :bookId AND deleted = 0 ORDER BY idx ASC")
    suspend fun getVisible(bookId: Long): List<PageEntry>

    /** 已软删除的页（回收站用） */
    @Query("SELECT * FROM pages WHERE bookId = :bookId AND deleted = 1 ORDER BY idx ASC")
    suspend fun getDeleted(bookId: Long): List<PageEntry>

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId AND deleted = 0")
    suspend fun countVisible(bookId: Long): Int

    /** 取第 [position] 个可见页（0 基），用于拼封面路径 */
    @Query(
        "SELECT * FROM pages WHERE bookId = :bookId AND deleted = 0 ORDER BY idx ASC LIMIT 1 OFFSET :position"
    )
    suspend fun getAt(bookId: Long, position: Int): PageEntry?

    @Query("UPDATE pages SET deleted = 1 WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<Long>)

    @Query("UPDATE pages SET deleted = 0 WHERE id IN (:ids)")
    suspend fun markVisible(ids: List<Long>)

    @Query("DELETE FROM pages WHERE bookId = :bookId AND deleted = 1")
    suspend fun purgeDeleted(bookId: Long)

    /** 彻底删掉某几行（回收站清空 / 从 PDF 重建前清旧行） */
    @Query("DELETE FROM pages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 单页替换缩略图/尺寸（懒生成后回写，避免整本重写） */
    @Query("UPDATE pages SET thumbRelPath = :thumb, width = :w, height = :h WHERE id = :id")
    suspend fun updateThumb(id: Long, thumb: String?, w: Int, h: Int)

    @Query("UPDATE pages SET originPage = :originPage WHERE id = :id")
    suspend fun updateOriginPage(id: Long, originPage: Int)

    /**
     * 批量重排：把 [orderedIds] 的下标依次写成 idx = 0..n-1。
     *
     * 用一个事务包住，避免"改到一半失败"留下重复/断号的 idx（那会让阅读器定位错乱）。
     * 页数上千时也就是上千条 UPDATE，走事务是毫秒级。
     */
    @Transaction
    suspend fun applyOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updateIndex(id, index) }
    }

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

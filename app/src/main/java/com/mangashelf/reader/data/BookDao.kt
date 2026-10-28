package com.mangashelf.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeOne(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    /** 批量导入前用它判断"这个文件夹是不是已经导过" */
    @Query("SELECT * FROM books WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun findBySourceKey(sourceKey: String): Book?

    @Query("UPDATE books SET sortMode = :mode, sortAsc = :asc WHERE id = :id")
    suspend fun updateSort(id: Long, mode: String, asc: Boolean)

    /** 启动时清理孤儿目录用：这些目录还要，其余的可以删 */
    @Query("SELECT folder FROM books")
    suspend fun allFolders(): List<String>

    /** 备份用：一次性拿全部书（不走 Flow） */
    @Query("SELECT * FROM books ORDER BY createdAt ASC")
    suspend fun allBooks(): List<Book>

    /** v1.5.5 编辑信息：作者与标签，供书架搜索命中 */
    @Query("UPDATE books SET author = :author, tags = :tags WHERE id = :id")
    suspend fun updateMeta(id: Long, author: String, tags: String)
}

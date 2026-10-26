package com.mangashelf.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一页的元数据。
 *
 * 为什么要有这张表（而不是只用 page_001.jpg 的文件名顺序）：
 * 需求要求"每本书册独立记住排序规则、退出重进能恢复"。页面文件一旦按序号命名，
 * 顺序就固化在文件名里了，事后改排序只能重命名/重写文件（几百页要重写上百 MB，中途失败就废）。
 * 所以在数据库里加一层"顺序间接层"：
 *
 * - 文件永远叫 page_NNN.jpg，内容不动
 * - [idx] 是当前显示顺序，排序只改这张表
 * - 同时把导入时的 [relPath]/[sizeBytes]/[modifiedAt] 存下来，
 *   于是"按文件名/时间/大小排序"都不需要重新读磁盘
 *
 * 对老用户：升级时按 pageCount 回填 idx = 0..n-1，sortMode 置为 FIXED（保持原页序，不重排）。
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class PageEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 所属书册 */
    val bookId: Long,
    /** 当前显示顺序（0 基），排序的结果就存在这里 */
    val idx: Int,
    /** 导入时的原始相对路径，用于"重新排序后反查是哪一张图" */
    val relPath: String,
    /** 原始文件大小，供"按大小排序" */
    val sizeBytes: Long = 0L,
    /** 原始文件修改时间，供"按时间排序" */
    val modifiedAt: Long = 0L,
    /** 手动拖动排序时的位置；null 表示没被手动摆过 */
    val manualPos: Int? = null
)

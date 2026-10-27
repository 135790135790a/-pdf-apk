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
    /**
     * 原始文件名（导入时在源文件夹里的名字，如 `第012话_03.jpg`）。
     * 落盘后文件统一叫 `page_NNN.jpg`，原始名字只留在这里——
     * 「按名称排序」用的就是它，否则只能按落盘序号排，等于没排。
     */
    val originName: String = "",
    /** 原始文件大小，供"按大小排序" */
    val sizeBytes: Long = 0L,
    /** 原始文件修改时间，供"按时间排序" */
    val modifiedAt: Long = 0L,
    /** 手动拖动排序时的位置；null 表示没被手动摆过 */
    val manualPos: Int? = null,

    // ---------------------------------------------------------------------
    // v4 → v5 新增（见 Migrations.kt MIGRATION_4_5）
    // ---------------------------------------------------------------------

    /**
     * PDF 里的原始页码（1 基）。
     * 非 PDF 来源（文件夹/图片）为 0 —— 「按原始页码排序」对它们是空操作。
     */
    val originPage: Int = 0,
    /** 缩略图相对路径（`thumbs/xxx.webp`）；null = 还没生成，用原图兜底 */
    val thumbRelPath: String? = null,
    /** 原图宽（像素），网格按比例占位，避免加载时跳一下 */
    val width: Int = 0,
    /** 原图高（像素） */
    val height: Int = 0,
    /** 1 = 已被软删除：页图和记录都还在，能恢复；清理回收站才物理删除 */
    val deleted: Int = 0
)

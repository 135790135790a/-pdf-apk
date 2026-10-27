package com.mangashelf.reader.data

/**
 * 导入器回传给数据层的一页信息。
 *
 * 为什么要记录原始信息：落盘后文件统一叫 `page_NNN.jpg`，原始文件名/大小/时间就丢了；
 * 而「按名称 / 按时间 / 按大小重排页序」必须用原始值，所以导入时顺手存下来，
 * 事后排序只读数据库，不用再去碰 SAF。
 */
data class PageSource(
    /** 落盘后的文件名，如 `page_001.jpg` */
    val relPath: String,
    /** 源文件名，如 `第012话_03.jpg`（PDF 生成的页为空串） */
    val originName: String,
    /** 源文件字节数，取不到时 0 */
    val sizeBytes: Long,
    /** 源文件最后修改时间，取不到时 0 */
    val modifiedAt: Long,
    /** PDF 原始页码（1 基）；文件夹/图片来源为 0 */
    val originPage: Int = 0,
    /** 落盘图宽（像素），取不到时 0 */
    val width: Int = 0,
    /** 落盘图高（像素），取不到时 0 */
    val height: Int = 0
)

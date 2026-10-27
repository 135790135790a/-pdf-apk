package com.mangashelf.reader.importer

import android.content.Context
import android.os.StatFs

/**
 * 导入前的存储空间预检。
 *
 * 为什么要先查：导入的做法是「先把整本漫画复制进应用私有目录，再写数据库」。
 * 空间不够时会写到一半失败，已经写进去的页会被回滚删掉，用户白等一场。
 * 提前算清楚要多少、还剩多少，比中途失败友好得多。
 */
object StorageGuard {

    /**
     * 一页预渲染后的大概体积。
     * 1440px 宽、JPEG 质量 88 的一页漫画，实测多在 200–400KB，取 320KB 作中位值。
     * 估得偏大一点没关系，宁可拦早不可拦晚。
     */
    private const val PAGE_BYTES = 320L * 1024

    /** 留 15% 余量给崩溃日志、数据库、缩略图缓存这些零碎。 */
    private const val SAFETY_RATIO = 1.15

    /** 数量无法提前知道时（文件夹 / 批量导入）的最低门槛。 */
    private const val MIN_UNKNOWN_BYTES = 500L * 1024 * 1024

    /**
     * 当前可写给本应用的字节数。
     * 统计失败时返回 [Long.MAX_VALUE]：统计不出来就放行，不能因为预检本身挡了用户。
     */
    fun available(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * @param needBytes 预计占用的空间
     * @return null 表示够用；否则是能直接显示给用户的提示
     */
    fun check(context: Context, needBytes: Long): String? {
        val available = available(context)
        if (available == Long.MAX_VALUE) return null
        val required = (needBytes * SAFETY_RATIO).toLong()
        if (available >= required) return null
        return "存储空间不够：这次大约要 ${mb(required)}，手机只剩 ${mb(available)}。\n\n" +
            "先清理一些空间再试。书架里不想要的漫画可以长按删除。"
    }

    /** PDF：页数已知，按页估算。 */
    fun forPdf(pages: Int): Long = pages.coerceAtLeast(1).toLong() * PAGE_BYTES

    /** 一批图片：张数已知，按张估算。 */
    fun forImages(count: Int): Long = count.coerceAtLeast(1).toLong() * PAGE_BYTES

    /** 文件夹 / 批量导入：要列完目录才知道数量，用固定门槛兜底。 */
    fun forUnknown(): Long = MIN_UNKNOWN_BYTES

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1024.0 / 1024.0)
}

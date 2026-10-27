package com.mangashelf.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 页面缩略图：把页面图压成 400px 宽的 WebP 存到书目录的 `thumbs/` 下。
 *
 * 为什么需要它：页面管理页一次要显示几百上千张缩略图。直接用原图（1440×2048、200~400 KB）
 * 会让 Coil 一直解码大图，快速滚动时明显掉帧，内存也吃不消。
 * 400px 的 WebP 只有 8~20 KB，解码快一个数量级。
 *
 * 生成是**懒**的：网格滑动到某页才生成，生成完把路径回写数据库，下次直接命中。
 * 删页时 thumbs/ 里的同名文件跟着删（见 BookRepository.purgeDeleted）。
 */
object ThumbStore {

    private const val TARGET_WIDTH = 400
    private const val QUALITY = 80

    /**
     * 确保 [page] 的缩略图存在，返回相对路径；失败返回 null（调用方用原图兜底，不影响显示）。
     */
    suspend fun ensure(storage: ComicStorage, folder: String, page: PageEntry): String? =
        withContext(Dispatchers.IO) {
            val rel = storage.thumbRelPath(page.relPath)
            val target = storage.thumbFile(folder, page.relPath)
            if (target.isFile && target.length() > 0) return@withContext rel

            val source = File(storage.dirOf(folder), page.relPath)
            if (!source.isFile) return@withContext null

            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, bounds)
                val sample = if (bounds.outWidth > TARGET_WIDTH * 2) {
                    bounds.outWidth / TARGET_WIDTH
                } else {
                    1
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                val bitmap = BitmapFactory.decodeFile(source.absolutePath, opts)
                    ?: return@runCatching null

                val scaled = if (bitmap.width > TARGET_WIDTH) {
                    val h = (bitmap.height.toFloat() * TARGET_WIDTH / bitmap.width).toInt()
                        .coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, h, true).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    scaled.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
                }
                scaled.recycle()
                rel
            }.getOrNull()
        }

    /** 删掉某一页的缩略图文件（页被彻底删除时调用） */
    fun delete(storage: ComicStorage, folder: String, relPath: String) {
        runCatching { storage.thumbFile(folder, relPath).delete() }
    }
}

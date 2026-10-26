package com.mangashelf.reader.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.mangashelf.reader.data.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 把 PDF 逐页渲染成 JPEG，落到书籍目录里。
 *
 * 之所以是"预渲染"而不是"打开时再渲染"：
 * - PdfRenderer 只能一条一条顺序渲染，渲染一张要几十到几百毫秒，翻页时会明显卡；
 * - 预渲染后页面就是普通图片，翻页只受图片解码限制，体验和看本地图片一致。
 */
object PdfImporter {

    /** 渲染宽度。1440px 在主流手机竖屏缩放下已足够清晰，又不会让整本漫画占满存储。 */
    private const val TARGET_WIDTH = 1440
    private const val JPEG_QUALITY = 88

    suspend fun import(
        context: Context,
        uri: Uri,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> = withContext(Dispatchers.IO) {
        destDir.mkdirs()

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开这个 PDF 文件")

        descriptor.use { pfd ->
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                throw IllegalStateException("这个 PDF 有密码保护，暂时不支持导入")
            } catch (e: Exception) {
                throw IllegalStateException("PDF 解析失败：${e.message}")
            }

            try {
                val total = renderer.pageCount
                if (total <= 0) return@withContext emptyList<PageSource>()

                val out = ArrayList<PageSource>(total)
                for (index in 0 until total) {
                    renderer.openPage(index).use { page ->
                        val width = TARGET_WIDTH
                        val height = (page.height.toFloat() * width / page.width.toFloat())
                            .toInt()
                            .coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PDF 页默认透明，先铺白底，否则导出 JPEG 会变成黑底
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val relPath = PageFileNaming.of(index)
                        val file = File(destDir, relPath)
                        FileOutputStream(file).use { outStream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outStream)
                        }
                        bitmap.recycle()
                        // PDF 没有"原文件名"，originName 留空，靠页序排；大小/时间取落盘结果
                        out.add(
                            PageSource(
                                relPath = relPath,
                                originName = "",
                                sizeBytes = file.length(),
                                modifiedAt = file.lastModified()
                            )
                        )
                    }
                    onProgress(index + 1, total)
                }
                out
            } finally {
                renderer.close()
            }
        }
    }
}

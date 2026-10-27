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
 *
 * v1.5.3 起额外把**原 PDF 复制一份**到书目录（`source.pdf`）：
 * 用户要求"删掉的页能从原 PDF 重新解析回来"。只存 SAF Uri 不够——授权会在进程重启后失效。
 * 代价是 PDF 书占双份空间，导入前的空间预检按 2 倍估算。
 */
object PdfImporter {

    /** 渲染宽度。1440px 在主流手机竖屏缩放下已足够清晰，又不会让整本漫画占满存储。 */
    private const val TARGET_WIDTH = 1440
    private const val JPEG_QUALITY = 88

    /**
     * 只读出页数，给导入前的空间预检用。
     *
     * 读不出来（文件损坏、有密码）就返回 0，让调用方按「数量未知」处理——
     * 预检失败不该阻止导入，真正的错误留给 [import] 去报。
     */
    fun pageCount(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        } ?: 0
    }.getOrDefault(0)

    /**
     * @param keepSource true = 额外保存一份原 PDF 到 [destDir]/source.pdf（供"重新解析"用）
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        destDir: File,
        keepSource: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> = withContext(Dispatchers.IO) {
        destDir.mkdirs()

        if (keepSource) {
            // 先复制原件：复制失败就别往下导了，否则会留下一本"删了页就再也回不来"的书
            val target = File(destDir, "source.pdf")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取这个 PDF 文件")
            }.onFailure {
                target.delete()
                throw IllegalStateException("保存原 PDF 失败：${it.message}")
            }
        }

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
                                modifiedAt = file.lastModified(),
                                originPage = index + 1,
                                width = width,
                                height = height
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

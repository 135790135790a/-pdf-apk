package com.mangashelf.reader.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * 图片解码 + 规范化。所有导入入口（多选文件、整文件夹、批量导入）共用这一套：
 *
 * 1. 只读尺寸做采样，避免大图 OOM
 * 2. 按 EXIF 修正方向（手机拍的图 orientation 常是 6/8，不处理会横躺）
 * 3. 合成到白底（PNG 透明区域直转 JPEG 会变黑，漫画对话框常是透明底）
 * 4. 最长边限制到 [MAX_EDGE]，统一输出 JPEG
 *
 * 防崩设计：这里返回 null 表示「这一页读不了」，上层跳过即可。
 * 解码阶段任何异常（含 OutOfMemoryError）都被吃掉——几千页的图里混着
 * 一张 2 亿像素的扫描件，不能让整本书导不进来。
 */
internal object ImageCodec {

    private const val MAX_EDGE = 1800
    private const val JPEG_QUALITY = 88

    fun load(context: Context, uri: Uri): Bitmap? {
        val bounds = readBounds(context, uri) ?: return null
        val orientation = readOrientation(context, uri)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)

        // 先按 1800px 长边解码；超大图（扫描件、瀑布流长图）连这一步都可能 OOM，
        // 就退到 900px 再试一次，两次都不行才放弃这一页。
        for (edge in intArrayOf(MAX_EDGE, MAX_EDGE / 2)) {
            try {
                val decoded = decodeSampled(context, uri, longest, edge) ?: return null
                return normalize(decoded, orientation)
            } catch (_: OutOfMemoryError) {
                System.gc()
                // 换更小的采样率重试
            } catch (_: RuntimeException) {
                // Bitmap 尺寸非法之类的怪图，直接跳过
                return null
            }
        }
        return null
    }

    fun write(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    /**
     * 从本地文件解码（CBZ/ZIP 解压出来的条目用）。
     *
     * 和 [load] 同一套防 OOM 策略，只是不经过 ContentResolver——
     * 压缩包里的条目没有 Uri，也不该为了读两遍尺寸先落一次盘。
     */
    fun load(file: File): Bitmap? = decodeTwoPass(
        peek = { options -> BitmapFactory.decodeFile(file.absolutePath, options) },
        decode = { options -> BitmapFactory.decodeFile(file.absolutePath, options) }
    )

    /** 从内存字节解码（压缩包条目） */
    fun loadBytes(bytes: ByteArray): Bitmap? = decodeTwoPass(
        peek = { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) },
        decode = { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
    )

    /**
     * 两遍解码：第一遍只要尺寸（`inJustDecodeBounds`，此时返回值一定是 null，只能看 outWidth/outHeight），
     * 第二遍按算出来的采样率真正解码；OOM 时降档重试一次。
     */
    private fun decodeTwoPass(
        peek: (BitmapFactory.Options) -> Bitmap?,
        decode: (BitmapFactory.Options) -> Bitmap?
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        peek(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        for (edge in intArrayOf(MAX_EDGE, MAX_EDGE / 2)) {
            try {
                var sample = 1
                while (longest / sample > edge * 2) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = decode(options) ?: return null
                return normalize(decoded, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: OutOfMemoryError) {
                System.gc()
            } catch (_: RuntimeException) {
                return null
            }
        }
        return null
    }

    /**
     * 第一遍只读尺寸。注意 inJustDecodeBounds = true 时 decodeStream 一定返回 null，
     * 所以不能拿返回值判断成功与否，只能看 outWidth / outHeight。
     */
    private fun readBounds(context: Context, uri: Uri): BitmapFactory.Options? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds
    }

    private fun readOrientation(context: Context, uri: Uri): Int =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * 按目标长边算 inSampleSize 再解码。
     *
     * sample 只取 2 的幂（BitmapFactory 会向下取整，传别的也没用），
     * 解出来的最长边落在 (targetEdge, 2 × targetEdge] 之间：
     * 1800 档最多 3600px（约 52MB），900 档最多 1800px（约 13MB）。
     */
    private fun decodeSampled(
        context: Context,
        uri: Uri,
        longest: Int,
        targetEdge: Int
    ): Bitmap? {
        var sample = 1
        while (longest / sample > targetEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    /** 应用 EXIF 旋转，并合成到白色底上 */
    private fun normalize(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> Unit
        }

        val rotated = if (matrix.isIdentity) {
            source
        } else {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
        if (rotated !== source) source.recycle()

        // JPEG 没有透明通道。没有 alpha 的图不用再合成白底，省掉一次全尺寸拷贝
        // （这是之前峰值内存的 2/3，也是大图 OOM 的常见触发点）。
        if (!rotated.hasAlpha()) return rotated

        val flattened = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(rotated, 0f, 0f, null)
        }
        rotated.recycle()
        return flattened
    }
}

package com.mangashelf.reader.data

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 漫画页面在应用私有目录里的落盘规则。
 *
 * 之所以要把导入的内容"复制一份"到私有目录，而不是保存原始 Uri：
 * 1. SAF 给的临时授权在进程重启后会失效，重进 App 就打不开原文件了；
 * 2. PDF 必须逐页渲染成位图才能翻页显示，渲染结果本来就要落盘；
 * 3. 顺手把图片格式/旋转/尺寸统一，阅读时不用再做解码判断。
 */
class ComicStorage(context: Context) {

    private val root: File = File(context.filesDir, "comics")

    /** 为一本新书创建独立目录，返回目录名 */
    fun newFolder(): String {
        val name = UUID.randomUUID().toString().replace("-", "").take(12)
        File(root, name).mkdirs()
        return name
    }

    fun dirOf(folder: String): File = File(root, folder)

    fun pageFile(folder: String, index: Int): File =
        File(dirOf(folder), String.format(Locale.US, "page_%03d.jpg", index + 1))

    fun pagePath(folder: String, index: Int): String = pageFile(folder, index).absolutePath

    fun coverPath(folder: String): String = pagePath(folder, 0)

    fun deleteFolder(folder: String) {
        dirOf(folder).deleteRecursively()
    }
}

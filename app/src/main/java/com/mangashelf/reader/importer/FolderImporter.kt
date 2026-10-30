package com.mangashelf.reader.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.mangashelf.reader.data.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale

/**
 * 整个文件夹 = 一本书。
 *
 * 规则（和网页版一致）：
 * - 递归读子文件夹，子文件夹按章节顺序接在后面
 * - 按**相对路径自然排序**：2.png 在 10.png 之前，ch2/ 在 ch10/ 之前
 * - 非图片文件（说明.txt、Thumbs.db、.DS_Store）自动忽略
 * - 坏图跳过并继续，不让整本失败
 *
 * 超大目录（几千个文件）做了节流：目录遍历和逐页转码都会定期 yield。
 * 每个目录条目都是一次跨进程查询，不节流的话取消/暂停要等整本转完才生效，
 * IO 线程也会被占死、其他协程跟着饿。
 */
object FolderImporter {

    private const val MAX_PAGES = 3000
    private const val MAX_DEPTH = 8

    /** 每转码这么多页让出一次 */
    private const val YIELD_EVERY_PAGES = 24

    /** 每遍历这么多目录条目让出一次 */
    private const val YIELD_EVERY_ENTRIES = 128

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "tif", "tiff")

    suspend fun import(
        context: Context,
        treeUri: Uri,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("打不开这个文件夹")
        importDir(context, root, destDir, onProgress)
    }

    /**
     * 父文件夹下的一个子文件夹 = 一本书（批量导入用）。
     *
     * @param name 子文件夹名（即书名）
     * @param key  稳定的去重键（DocumentId，如 `primary:Comics/OP1`），换机器/重装后仍一致
     */
    class ChildFolder(val name: String, val key: String)

    /**
     * 列出父文件夹下所有**直接子文件夹**，按名称自然排序。
     *
     * 这正是「海贼王 1-100 卷」那种结构：父目录里每个子目录是一本。
     * 父目录里直接散放的图片不在这里返回——那种情况仍走单本 [import]。
     *
     * 这里只返回 DocumentId，不返回子目录的 tree URI：SAF 只授权了用户选中的
     * 那一棵树，子目录的 tree URI 没有 grant，拿它去 listFiles() 会被拒。
     * 真正读取时用 [importChild] 从父树往下定位。
     */
    suspend fun listChildFolders(context: Context, rootTreeUri: Uri): List<ChildFolder> {
        val root = DocumentFile.fromTreeUri(context, rootTreeUri) ?: return emptyList()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return emptyList()

        val out = ArrayList<ChildFolder>(32)
        var scanned = 0
        for (child in children) {
            // 父目录下有几百个子文件夹时，遍历本身也可能要好几秒
            if (++scanned % 64 == 0) yield()
            if (!child.isDirectory) continue
            val name = child.name?.trim().orEmpty()
            if (name.isEmpty() || name.startsWith(".")) continue
            val key = runCatching { DocumentsContract.getDocumentId(child.uri) }.getOrNull()
            if (key.isNullOrBlank()) continue
            out.add(ChildFolder(name, key))
        }
        return out.sortedWith { a, b -> compareNatural(a.name, b.name) }
    }

    /**
     * 批量导入：从已授权的父树里取出 documentId 对应的子文件夹，当成一本书导入。
     *
     * 必须走父树（[parentTreeUri] 是用户当初在系统选择器里选中的那棵树），
     * 不能对子目录另造 tree URI——那会丢授权，所有子目录都会被判成「没有图片」。
     */
    suspend fun importChild(
        context: Context,
        parentTreeUri: Uri,
        childDocId: String,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, parentTreeUri)
            ?: throw IllegalStateException("打不开父文件夹")
        val children = runCatching { root.listFiles() }.getOrNull()
            ?: throw IllegalStateException("读不到父文件夹里的内容，请重新选择父文件夹")
        val target = children.firstOrNull {
            runCatching { DocumentsContract.getDocumentId(it.uri) }.getOrNull() == childDocId
        } ?: throw IllegalStateException("找不到子文件夹，可能已被移动或删除")
        importDir(context, target, destDir, onProgress)
    }

    /** 真正读一本：dir 必须是已授权的树（或父树下的子节点），递归收集图片并转码 */
    private suspend fun importDir(
        context: Context,
        dir: DocumentFile,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> {
        destDir.mkdirs()

        val found = ArrayList<Entry>(64)
        collect(dir, "", found, 0)
        return materialize(context, found, destDir, onProgress)
    }

    /**
     * 导入「已授权树内任意深度的一个目录」。
     *
     * 和 [importChild] 的区别：那个只能在父树的**直接子项**里找，
     * 因为 DocumentFile 拿到子目录对象后就丢授权了。
     * 这里改成先用 ContentResolver 递归把图片列出来（只看 docId，不需要子目录的授权），
     * 再走同一套落盘逻辑，所以进到第三层、第五层都能导。
     */
    suspend fun importDoc(
        context: Context,
        rootTreeUri: Uri,
        docId: String,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> = withContext(Dispatchers.IO) {
        val found = ArrayList<Entry>(64)
        collectViaResolver(context, rootTreeUri, docId, "", found, 0)
        materialize(context, found, destDir, onProgress)
    }

    /**
     * 把「收集到的图片」落盘成页文件。
     *
     * 收集有两条路子（DocumentFile / ContentResolver），落盘必须共用这一条：
     * 编号、进度、异常文案只要有一处不一致，两类导入的行为就会分叉。
     */
    private suspend fun materialize(
        context: Context,
        found: List<Entry>,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> {
        destDir.mkdirs()
        if (found.isEmpty()) {
            throw IllegalStateException("这个文件夹里没有图片（支持 jpg/png/webp/bmp/gif/avif/tif）")
        }
        if (found.size > MAX_PAGES) {
            throw IllegalStateException("图片太多（${found.size} 张），上限 $MAX_PAGES 张")
        }

        val ordered = found.sortedWith(Comparator { a, b -> compareNatural(a.path, b.path) })

        val out = ArrayList<PageSource>(ordered.size)
        ordered.forEachIndexed { index, entry ->
            // 上千页的目录，这里定期让出，取消能及时生效、进度也能滚动起来
            if (index > 0 && index % YIELD_EVERY_PAGES == 0) yield()
            val bitmap = runCatching { ImageCodec.load(context, entry.uri) }.getOrNull()
            if (bitmap != null) {
                val relPath = PageFileNaming.of(out.size)
                try {
                    ImageCodec.write(bitmap, File(destDir, relPath))
                    out.add(
                        PageSource(
                            relPath = relPath,
                            originName = entry.path,
                            sizeBytes = entry.size,
                            modifiedAt = entry.modified
                        )
                    )
                } finally {
                    bitmap.recycle()
                }
            }
            onProgress(index + 1, ordered.size)
        }
        if (out.isEmpty()) throw IllegalStateException("这个文件夹里的图片都读不出来")
        return out
    }

    /** 用 ContentResolver 递归收集（不受「子目录没授权」限制，只看 docId） */
    private suspend fun collectViaResolver(
        context: Context,
        rootTreeUri: Uri,
        docId: String,
        prefix: String,
        out: MutableList<Entry>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        var scanned = 0
        for (node in childrenOf(context, rootTreeUri, docId)) {
            if (++scanned % YIELD_EVERY_ENTRIES == 0) yield()
            if (node.name.startsWith(".")) continue
            if (node.dir) {
                collectViaResolver(context, rootTreeUri, node.docId, "$prefix${node.name}/", out, depth + 1)
            } else if (isImage(node.name)) {
                out.add(
                    Entry(
                        path = "$prefix${node.name}",
                        uri = DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, node.docId),
                        size = node.size,
                        modified = node.modified
                    )
                )
            }
        }
    }

    private class Node(
        val docId: String,
        val name: String,
        val dir: Boolean,
        val size: Long,
        val modified: Long
    )

    /** 列某个 docId 的直接子项。查不到（被删除/无权限）时返回空表，不抛。 */
    private fun childrenOf(context: Context, rootTreeUri: Uri, docId: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<Node>(32)
        runCatching {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(Node(id, name, isDir, c.getLong(3), c.getLong(4)))
                }
            }
        }
        return out
    }

    private class Entry(
        val path: String,
        val uri: Uri,
        val size: Long,
        val modified: Long
    )

    private suspend fun collect(dir: DocumentFile, prefix: String, out: MutableList<Entry>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        var scanned = 0
        for (child in children) {
            // 每个条目 = 一次跨进程查询；上千个条目时定期让出
            if (++scanned % YIELD_EVERY_ENTRIES == 0) yield()
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                collect(child, "$prefix$name/", out, depth + 1)
            } else if (child.isFile && isImage(name)) {
                out.add(
                    Entry(
                        path = "$prefix$name",
                        uri = child.uri,
                        size = runCatching { child.length() }.getOrDefault(0L),
                        modified = runCatching { child.lastModified() }.getOrDefault(0L)
                    )
                )
            }
        }
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in IMAGE_EXT
    }

    /** 自然排序：把字符串切成"文本段 + 数字段"，数字段按数值比较 */
    internal fun compareNatural(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        var i = 0
        while (i < ta.size && i < tb.size) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x is Long && y is Long) {
                x.compareTo(y)
            } else {
                x.toString().compareTo(y.toString(), ignoreCase = true)
            }
            if (c != 0) return c
            i++
        }
        return ta.size - tb.size
    }

    private fun tokenize(text: String): List<Any> {
        val out = ArrayList<Any>(8)
        val buffer = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isDigit()) {
                if (buffer.isNotEmpty()) {
                    out.add(buffer.toString())
                    buffer.setLength(0)
                }
                var end = i
                while (end < text.length && text[end].isDigit()) end++
                out.add(text.substring(i, end).toLongOrNull() ?: 0L)
                i = end
            } else {
                buffer.append(ch)
                i++
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }
}

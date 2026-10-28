package com.mangashelf.reader.data

import com.mangashelf.reader.importer.FolderImporter
import com.mangashelf.reader.importer.PdfImporter
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(
    private val dao: BookDao,
    private val pageDao: PageDao,
    private val taskDao: ImportTaskDao,
    val storage: ComicStorage
) {

    /** 整库备份（含图片），见 [BackupManager] */
    suspend fun exportBackup(
        context: Context,
        target: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BackupManager.ExportResult = withContext(Dispatchers.IO) {
        BackupManager.export(context, storage, dao.allBooks(), pageDao, target, onProgress)
    }

    /** 从备份恢复（不覆盖现有书，冲突时重新分配目录） */
    suspend fun restoreBackup(
        context: Context,
        source: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BackupManager.RestoreResult = withContext(Dispatchers.IO) {
        BackupManager.restore(context, storage, this@BookRepository, source, onProgress)
    }

    /**
     * 书架列表。查询出错（数据库文件异常、磁盘 IO 错误）时吞掉异常返回空列表，
     * 让 UI 显示"空书架"而不是整页崩溃——重启通常就恢复了。
     */
    fun observeBooks(): Flow<List<Book>> = dao.observeAll().catch { emit(emptyList()) }

    fun observeBook(id: Long): Flow<Book?> = dao.observeOne(id).catch { emit(null) }

    suspend fun insert(book: Book): Long = dao.insert(book)

    /**
     * 插入一本书并同时写入页序表。
     *
     * 两件事必须在一起做：只写书不写页序，阅读器就只能回退到"按文件名猜顺序"，
     * 页序排序功能等于没有。
     */
    suspend fun insertBook(book: Book, pages: List<PageSource>): Long = withContext(Dispatchers.IO) {
        val id = dao.insert(book)
        if (pages.isNotEmpty()) {
            pageDao.insertAll(
                pages.mapIndexed { index, source ->
                    PageEntry(
                        bookId = id,
                        idx = index,
                        relPath = source.relPath,
                        originName = source.originName,
                        sizeBytes = source.sizeBytes,
                        modifiedAt = source.modifiedAt,
                        originPage = source.originPage,
                        width = source.width,
                        height = source.height
                    )
                }
            )
        }
        id
    }

    /** 批量导入前判断"这个文件夹是不是已经导过"（断点续传） */
    suspend fun findBySourceKey(key: String): Book? = dao.findBySourceKey(key)

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        // 显式删 pages，不依赖外键级联（部分设备/配置下 foreign_keys 未必生效）
        pageDao.deleteByBook(book.id)
        dao.delete(book.id)
        storage.deleteFolder(book.folder)
    }

    suspend fun rename(bookId: Long, title: String) {
        val book = dao.get(bookId) ?: return
        if (title.isNotBlank() && title != book.title) dao.update(book.copy(title = title))
    }

    /** v1.5.5：作者与标签，只用于搜索展示，不影响页序与封面 */
    suspend fun updateMeta(bookId: Long, author: String, tags: String) {
        dao.updateMeta(bookId, author.trim(), tags.trim())
    }

    suspend fun setRtl(bookId: Long, rtl: Boolean) {
        val book = dao.get(bookId) ?: return
        if (book.rtl != rtl) dao.update(book.copy(rtl = rtl))
    }

    /** v1.5.5 需求三：阅读模式按书存 —— HORIZONTAL / VERTICAL / DOUBLE */
    suspend fun setReadMode(bookId: Long, mode: String) {
        val book = dao.get(bookId) ?: return
        if (book.readMode != mode) dao.update(book.copy(readMode = mode, twoPage = mode == "DOUBLE"))
    }

    /** 双页模式按书存：跨页大图的书适合双页，普通单页漫画还是单页清楚 */
    suspend fun setTwoPage(bookId: Long, twoPage: Boolean) {
        val book = dao.get(bookId) ?: return
        if (book.twoPage != twoPage) dao.update(book.copy(twoPage = twoPage))
    }

    suspend fun saveProgress(bookId: Long, page: Int) {
        val book = dao.get(bookId) ?: return
        val clamped = page.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
        val now = System.currentTimeMillis()
        // 除了页码，还要记"最后阅读时间"：书架「最近阅读」排序和顶部「继续阅读」卡片都靠它。
        // 30 秒节流——同一本书连续翻页时不重复写库，但隔一会儿再看总会刷新一次。
        if (book.lastPage != clamped || now - book.lastReadAt > 30_000L) {
            dao.update(book.copy(lastPage = clamped, lastReadAt = now))
        }
    }

    // ---------------------------------------------------------------------
    // 页面顺序（v2 起的"顺序间接层"，v3 起真正按 relPath 取文件）
    // ---------------------------------------------------------------------

    fun observePages(bookId: Long): Flow<List<PageEntry>> = pageDao.observeByBook(bookId)

    suspend fun pageEntries(bookId: Long): List<PageEntry> = pageDao.getByBook(bookId)

    /**
     * 按**落盘文件名**拼路径（v1 的老逻辑）。
     * 只用于封面这类"第一张图"的场景；阅读器必须走 [loadPagePaths]，
     * 否则重排页序后看到的还是旧顺序。
     */
    fun pagePaths(book: Book): List<String> =
        (0 until book.pageCount).map { storage.pagePath(book.folder, it) }

    /**
     * 顺序间接层的真正实现：按 `pages.idx` 顺序、用每行的 `relPath` 拼路径。
     *
     * 这里**不重命名任何文件**——排序只改 idx。几百页的漫画如果靠重命名文件来排序，
     * 中途失败就是半套顺序，而且每次排序要写几百次磁盘。
     */
    suspend fun loadPagePaths(book: Book): List<String> = withContext(Dispatchers.IO) {
        val entries = entriesOrBackfill(book)
        if (entries.isEmpty()) {
            pagePaths(book)
        } else {
            entries.map { File(storage.dirOf(book.folder), it.relPath).absolutePath }
        }
    }

    /**
     * 按指定规则重排页序（只改 pages.idx，不动文件），并把规则记在 books 上，
     * 下次进阅读器/重启 App 都还是这个顺序。
     */
    suspend fun applyPageOrder(book: Book, mode: PageSortMode, asc: Boolean) =
        withContext(Dispatchers.IO) {
            val entries = entriesOrBackfill(book)
            if (entries.isEmpty()) return@withContext

            val comparator: Comparator<PageEntry>? = when (mode) {
                PageSortMode.NATURAL -> Comparator { a, b ->
                    FolderImporter.compareNatural(a.nameKey(), b.nameKey())
                }
                PageSortMode.NAME -> Comparator { a, b ->
                    a.nameKey().compareTo(b.nameKey(), ignoreCase = true)
                }
                PageSortMode.MTIME -> compareBy { it.modifiedAt }
                PageSortMode.SIZE -> compareBy { it.sizeBytes }
                PageSortMode.FIXED, PageSortMode.MANUAL -> null
            }

            val ordered = if (comparator == null) {
                entries
            } else {
                val sorted = entries.sortedWith(comparator)
                if (asc) sorted else sorted.reversed()
            }

            // 整表重建：几百行逐条 update 是几百次事务，中途失败会留下半套顺序
            pageDao.replaceAll(
                book.id,
                ordered.mapIndexed { position, entry ->
                    entry.copy(id = 0L, idx = position)
                }
            )
            dao.updateSort(book.id, mode.name, asc)
        }

    /**
     * 取 pages 行；为 0 行时（v1/v2 时代导入的老书）从磁盘懒生成一次。
     *
     * v2 迁移回填过的书 relPath 是 `page_001.jpg`，与文件一致，不需要重建；
     * 但那些行的 sizeBytes / modifiedAt 都是 0，排序会退化成"全部相等"，
     * 所以顺手用磁盘上的真实大小/时间补一次。
     */
    private suspend fun entriesOrBackfill(book: Book): List<PageEntry> {
        val existing = pageDao.getByBook(book.id)
        if (existing.isNotEmpty() && existing.any { it.sizeBytes > 0L }) return existing

        val dir = storage.dirOf(book.folder)
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("page_") && file.name.endsWith(".jpg")
        }?.sortedWith { a, b -> FolderImporter.compareNatural(a.name, b.name) }.orEmpty()
        if (files.isEmpty()) return existing

        val regenerated = files.mapIndexed { index, file ->
            PageEntry(
                bookId = book.id,
                idx = index,
                relPath = file.name,
                originName = file.name,
                sizeBytes = file.length(),
                modifiedAt = file.lastModified(),
                originPage = index + 1
            )
        }
        pageDao.replaceAll(book.id, regenerated)
        return pageDao.getByBook(book.id)
    }

    private fun PageEntry.nameKey(): String = originName.ifBlank { relPath }

    /** 当前页面列表用于"缩略图选页" */
    suspend fun pageFiles(book: Book): List<File> = withContext(Dispatchers.IO) {
        val entries = entriesOrBackfill(book)
        if (entries.isEmpty()) {
            pagePaths(book).map { File(it) }
        } else {
            entries.map { File(storage.dirOf(book.folder), it.relPath) }
        }
    }

    fun coverPath(book: Book): String = storage.coverPath(book.folder)

    /**
     * 封面路径（v5）：按 `coverPageIdx` 找可见页的真实文件名。
     *
     * 不能再用 `page_001.jpg`——页序重排/删页之后，第 1 页可能已经是别的文件了。
     */
    suspend fun coverPathOf(book: Book, quality: CoverQuality = CoverQuality.THUMB): String =
        withContext(Dispatchers.IO) {
            val target = runCatching {
                pageDao.getAt(book.id, book.coverPageIdx.coerceAtLeast(0))
                    ?: pageDao.getAt(book.id, 0)
            }.getOrNull()
            if (target == null) {
                return@withContext storage.pagePath(book.folder, 0)
            }
            // 缩略图优先：懒生成 + 文件校验，任何一步失败都回退原图，绝不让书架空着。
            if (quality == CoverQuality.THUMB) {
                val rel = runCatching {
                    ThumbStore.ensure(storage, book.folder, target)
                }.getOrNull()
                if (rel != null) {
                    val thumb = File(storage.dirOf(book.folder), rel)
                    if (thumb.isFile && thumb.length() > 0L) {
                        return@withContext thumb.absolutePath
                    }
                }
            }
            File(storage.dirOf(book.folder), target.relPath).absolutePath
        }

    // ---------------------------------------------------------------------
    // 导入任务（v4）：让"导到一半被杀"这件事有据可查
    // ---------------------------------------------------------------------

    suspend fun enqueueTasks(tasks: List<ImportTask>): List<Long> = withContext(Dispatchers.IO) {
        taskDao.insertAll(tasks)
    }

    suspend fun taskStatus(id: Long, status: String, error: String? = null) =
        withContext(Dispatchers.IO) {
            taskDao.setStatus(id, status, error, System.currentTimeMillis())
        }

    /** v1.5.5：页级渲染进度回写（调用方已节流，不要每页都调） */
    suspend fun taskProgress(id: Long, total: Int, rendered: Int, current: Int) =
        withContext(Dispatchers.IO) {
            taskDao.setProgress(id, total, rendered, current, System.currentTimeMillis())
        }

    /** 上次进程死掉时没跑完的任务（PENDING / RUNNING） */
    suspend fun unfinishedTasks(): List<ImportTask> = withContext(Dispatchers.IO) {
        taskDao.unfinished()
    }

    suspend fun tasksOfBatch(batchId: String): List<ImportTask> = withContext(Dispatchers.IO) {
        taskDao.byBatch(batchId)
    }

    /** 把一批里没跑完的标成失败，并记下原因 */
    suspend fun failUnfinished(batchId: String, reason: String) = withContext(Dispatchers.IO) {
        taskDao.failUnfinished(batchId, ImportTask.FAILED, reason, System.currentTimeMillis())
    }

    /** 重试前把失败项恢复成待办 */
    suspend fun resetFailed(batchId: String) = withContext(Dispatchers.IO) {
        taskDao.resetFailed(batchId, System.currentTimeMillis())
    }

    suspend fun cancelPending(batchId: String) = withContext(Dispatchers.IO) {
        taskDao.cancelPending(batchId, ImportTask.CANCELLED, System.currentTimeMillis())
    }

    /** 任务表只留最近 30 天，避免无限增长 */
    suspend fun pruneTasks() = withContext(Dispatchers.IO) {
        taskDao.pruneBefore(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    }

    /** 删掉没有对应书籍的残留目录（只在启动时、无导入进行中时调用） */
    suspend fun cleanupOrphanFolders(): Int = withContext(Dispatchers.IO) {
        runCatching { storage.cleanupOrphans(dao.allFolders().toSet()) }.getOrDefault(0)
    }

    // ---------------------------------------------------------------------
    // 页面级管理（v5）：删页 / 恢复 / 重排 / 封面 / 缩略图 / 重新解析
    // ---------------------------------------------------------------------

    /** 全部页（含已软删除的），页面管理页用 */
    suspend fun allPageEntries(book: Book): List<PageEntry> = withContext(Dispatchers.IO) {
        val existing = pageDao.getByBook(book.id)
        if (existing.isNotEmpty()) existing else entriesOrBackfill(book)
    }

    /** 未删除的页，阅读器/统计用 */
    suspend fun visiblePageEntries(book: Book): List<PageEntry> = withContext(Dispatchers.IO) {
        val all = allPageEntries(book)
        all.filter { it.deleted == 0 }.sortedBy { it.idx }
    }

    /**
     * 软删除若干页：只打标记，页图和记录都留着（回收站可恢复）。
     * 删除后必须重排剩余页的 idx（连续 0..n-1），否则阅读器 `pages[idx]` 会错位。
     *
     * @return 剩余未删页数；为 0 表示用户删光了，调用方应阻止（至少留一页）
     */
    suspend fun softDeletePages(book: Book, ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext visiblePageEntries(book).size
        pageDao.markDeleted(ids)
        val remaining = pageDao.getVisible(book.id)
        pageDao.applyOrder(remaining.map { it.id })
        syncBookAfterPageChange(book, remaining.size)
        remaining.size
    }

    /** 从回收站恢复 */
    suspend fun restorePages(book: Book, ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        pageDao.markVisible(ids)
        val remaining = pageDao.getVisible(book.id)
        // 恢复的页按 originPage 重新插回，而不是全丢到末尾
        val ordered = remaining.sortedWith(
            compareBy<PageEntry> { it.originPage.takeIf { p -> p > 0 } ?: (it.idx + 1) }
                .thenBy { it.relPath }
        )
        pageDao.applyOrder(ordered.map { it.id })
        syncBookAfterPageChange(book, ordered.size)
        ids.size
    }

    /**
     * 永久清空回收站：删页图 + 删缩略图 + 删记录。
     * @return (删除页数, 释放字节数)
     */
    suspend fun purgeDeletedPages(book: Book): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val deleted = pageDao.getDeleted(book.id)
        if (deleted.isEmpty()) return@withContext 0 to 0L

        var freed = 0L
        val dir = storage.dirOf(book.folder)
        deleted.forEach { page ->
            val file = File(dir, page.relPath)
            freed += file.length()
            runCatching { file.delete() }
            ThumbStore.delete(storage, book.folder, page.relPath)
        }
        pageDao.deleteByIds(deleted.map { it.id })
        deleted.size to freed
    }

    /** 按给定的页 id 顺序重排（上移/下移/拖拽到最后都走这里） */
    suspend fun reorderPages(book: Book, orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        if (orderedIds.isEmpty()) return@withContext
        pageDao.applyOrder(orderedIds)
        dao.updateSort(book.id, PageSortMode.MANUAL.name, true)
    }

    /**
     * 恢复默认顺序：
     * - PDF 来源按 `originPage`（PDF 原始页码）；
     * - 其他来源按落盘文件名自然序。
     */
    suspend fun resetPageOrder(book: Book) = withContext(Dispatchers.IO) {
        val visible = pageDao.getVisible(book.id)
        if (visible.isEmpty()) return@withContext
        val ordered = if (visible.any { it.originPage > 0 }) {
            visible.sortedBy { it.originPage }
        } else {
            visible.sortedWith { a, b -> FolderImporter.compareNatural(a.relPath, b.relPath) }
        }
        pageDao.applyOrder(ordered.map { it.id })
        dao.updateSort(book.id, PageSortMode.FIXED.name, true)
    }

    /** 把某页设为封面；[pageIdx] 是未删除页里的位置 */
    suspend fun setCoverPage(book: Book, pageIdx: Int) = withContext(Dispatchers.IO) {
        val count = pageDao.countVisible(book.id)
        if (count == 0) return@withContext
        dao.update(book.copy(coverPageIdx = pageIdx.coerceIn(0, count - 1)))
    }

    /** 生成（或命中）某页缩略图，返回相对路径；失败返回 null 由 UI 用原图兜底 */
    suspend fun ensureThumb(book: Book, page: PageEntry): String? = withContext(Dispatchers.IO) {
        val rel = ThumbStore.ensure(storage, book.folder, page) ?: return@withContext null
        if (page.thumbRelPath != rel) {
            runCatching { pageDao.updateThumb(page.id, rel, page.width, page.height) }
        }
        rel
    }

    /** 缩略图的绝对路径（已存在才返回） */
    fun thumbPath(book: Book, page: PageEntry): String? {
        val rel = page.thumbRelPath ?: return null
        val file = File(storage.dirOf(book.folder), rel)
        return if (file.isFile) file.absolutePath else null
    }

    /** 书架长按信息面板：占用 / 页数 / 回收站页数 */
    suspend fun bookStats(book: Book): BookStats = withContext(Dispatchers.IO) {
        val all = allPageEntries(book)
        BookStats(
            pageCount = all.count { it.deleted == 0 },
            deletedCount = all.count { it.deleted == 1 },
            dirBytes = storage.dirSize(book.folder),
            sourceBytes = book.sourceSizeBytes,
            hasSourcePdf = storage.sourcePdf(book.folder).isFile
        )
    }

    /**
     * 用保留的 `source.pdf` 重新解析整本。
     *
     * 会覆盖当前页序、封面和回收站（旧的页面记录整个重建），所以调用方必须先二次确认。
     * 没有原件（老版本导入的书 / 文件夹来源）时返回 -1。
     */
    suspend fun reparsePdf(context: Context, book: Book): Int = withContext(Dispatchers.IO) {
        val source = storage.sourcePdf(book.folder)
        if (!source.isFile || source.length() <= 0L) return@withContext -1

        val dir = storage.dirOf(book.folder)
        val pages = PdfImporter.import(
            context = context,
            uri = Uri.fromFile(source),
            destDir = dir,
            keepSource = false,
            onProgress = { _, _ -> }
        )
        if (pages.isEmpty()) return@withContext -2

        pageDao.replaceAll(
            book.id,
            pages.mapIndexed { index, source ->
                PageEntry(
                    bookId = book.id,
                    idx = index,
                    relPath = source.relPath,
                    originName = source.originName,
                    sizeBytes = source.sizeBytes,
                    modifiedAt = source.modifiedAt,
                    originPage = source.originPage,
                    width = source.width,
                    height = source.height
                )
            }
        )
        dao.update(
            book.copy(
                pageCount = pages.size,
                lastPage = 0,
                coverPageIdx = 0,
                sortMode = PageSortMode.FIXED.name
            )
        )
        pages.size
    }

    /** 删页/恢复后同步 books 的页数、进度、封面（避免阅读器越界、封面指向空） */
    private suspend fun syncBookAfterPageChange(book: Book, pageCount: Int) {
        val fresh = dao.get(book.id) ?: return
        val cover = fresh.coverPageIdx.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val lastPage = fresh.lastPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (fresh.pageCount != pageCount || fresh.coverPageIdx != cover || fresh.lastPage != lastPage) {
            dao.update(fresh.copy(pageCount = pageCount, coverPageIdx = cover, lastPage = lastPage))
        }
    }
}

/** 书架长按信息面板要展示的统计 */
data class BookStats(
    val pageCount: Int,
    val deletedCount: Int,
    /** 整本目录占用（页面图 + 缩略图 + 原 PDF） */
    val dirBytes: Long,
    /** 原始来源大小（导入时记的 PDF/文件夹大小） */
    val sourceBytes: Long,
    /** 是否保留着原 PDF，决定「重新解析」入口是否可用 */
    val hasSourcePdf: Boolean
)

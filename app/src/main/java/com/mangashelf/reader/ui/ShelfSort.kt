package com.mangashelf.reader.ui

import com.mangashelf.reader.data.Book
import java.text.Collator
import java.util.Locale

/** 书架排序依据。顺序即菜单里的显示顺序。 */
enum class ShelfSort(val label: String, val defaultAsc: Boolean) {
    /** 新导入的在前 */
    RECENT("导入时间", false),

    /** 按书名（中文按拼音，混合英文也能排） */
    TITLE("书名", true),

    /** 按总页数 */
    PAGES("页数", false)
}

/**
 * 当前排序 = 依据 + 方向。
 *
 * 为什么放在 ViewModel 里而不是数据库查询里：
 * 书架列表本来就要全量加载（几百本很快），在内存里排一次比写三条 SQL 更简单，
 * 也避免每换一次排序就重新查库、重新触发 Room 的 Flow。
 */
data class ShelfOrder(
    val sort: ShelfSort = ShelfSort.RECENT,
    val asc: Boolean = false
) {
    fun apply(books: List<Book>): List<Book> {
        if (books.size <= 1) return books
        val comparator: Comparator<Book> = when (sort) {
            ShelfSort.RECENT -> compareBy { it.createdAt }
            ShelfSort.TITLE -> Comparator { a, b -> collator.compare(a.title, b.title) }
            ShelfSort.PAGES -> compareBy { it.pageCount }
        }
        val sorted = books.sortedWith(comparator)
        return if (asc) sorted else sorted.reversed()
    }

    fun storageKey(): String = "${sort.name}:$asc"

    companion object {
        private val collator: Collator = Collator.getInstance(Locale.CHINA)

        fun fromStorage(key: String?): ShelfOrder {
            val parts = key?.split(':') ?: return ShelfOrder()
            val sort = runCatching { ShelfSort.valueOf(parts[0]) }.getOrNull() ?: ShelfSort.RECENT
            val asc = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: sort.defaultAsc
            return ShelfOrder(sort, asc)
        }
    }
}

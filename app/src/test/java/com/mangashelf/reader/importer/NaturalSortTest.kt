package com.mangashelf.reader.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件夹导入的排序规则测试。
 *
 * 这类排序错一次，整本书的页序就全乱了，所以专门用单元测试盯住。
 */
class NaturalSortTest {

    private fun sort(input: List<String>): List<String> =
        input.sortedWith(Comparator { a, b -> FolderImporter.compareNatural(a, b) })

    @Test
    fun `数字按数值排序而不是字典序`() {
        assertEquals(
            listOf("1.png", "2.png", "10.png", "11.png"),
            sort(listOf("10.png", "2.png", "11.png", "1.png"))
        )
    }

    @Test
    fun `章节文件夹 ch2 在 ch10 前面`() {
        assertEquals(
            listOf("ch2/1.png", "ch2/2.png", "ch2/10.png", "ch10/1.png"),
            sort(listOf("ch10/1.png", "ch2/10.png", "ch2/1.png", "ch2/2.png"))
        )
    }

    @Test
    fun `顶层文件在子文件夹前面，中文章节按数字顺序`() {
        assertEquals(
            listOf("1.png", "2.png", "10.png", "第01话/001.png", "第02话/001.png"),
            sort(listOf("第02话/001.png", "10.png", "1.png", "第01话/001.png", "2.png"))
        )
    }

    @Test
    fun `纯数字文件名与补零文件名都能排对`() {
        assertEquals(
            listOf("001.jpg", "002.jpg", "010.jpg", "100.jpg"),
            sort(listOf("100.jpg", "010.jpg", "001.jpg", "002.jpg"))
        )
    }
}

package com.mangashelf.reader.data

/**
 * 页序排序规则（按书独立保存，存在 `books.sortMode`）。
 *
 * FIXED 是"不排序"：v1/v2 时代导入的书升级上来默认就是它，保证页序与升级前一致。
 */
enum class PageSortMode(
    val label: String,
    val hint: String,
    val defaultAsc: Boolean = true
) {
    /** 原始文件名自然排序：`2.jpg` 在 `10.jpg` 前，`ch2/` 在 `ch10/` 前 */
    NATURAL("自然顺序", "2 排在 10 前面，符合看图直觉", true),

    /** 原始文件名字典序（和自然顺序的区别在数字：`10` 会排在 `2` 前面） */
    NAME("文件名", "按字典序，10 会排在 2 前面", true),

    /** 源文件修改时间 */
    MTIME("修改时间", "按源文件时间，早的在前", true),

    /** 源文件大小 */
    SIZE("文件大小", "按源文件体积，小的在前", true),

    /** 固定：保持导入时的顺序，不参与重排（导入时选它 = 按读取到的顺序原样入库） */
    FIXED("导入顺序", "保持读取到的顺序，不做重排", true),

    /**
     * 手动排序：用户在页面管理里上移/下移/拖动过的结果。
     * 不是"可选规则"——菜单里不出现这一项，只是把"当前顺序是人摆的"记在书上，
     * 于是再点「恢复默认顺序」能明确知道该还原。
     */
    MANUAL("手动顺序", "按你自己拖动/移动后的顺序", true);

    companion object {
        fun fromStorage(raw: String?): PageSortMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FIXED
    }
}

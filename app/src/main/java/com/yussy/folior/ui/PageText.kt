package com.yussy.folior.ui

/**
 * 顶栏页码文字。双联时给「左–右」区间，只有一侧时给那一页，都没有给「-」。
 */
fun spreadPageText(left: Int, right: Int, total: Int): String {
    val range = when {
        left >= 0 && right >= 0 -> "${left + 1}–${right + 1}"
        right >= 0 -> "${right + 1}"
        left >= 0 -> "${left + 1}"
        else -> "-"
    }
    return "$range / $total"
}

/** 单页页码文字。 */
fun singlePageText(index: Int, total: Int): String = "${index + 1} / $total"

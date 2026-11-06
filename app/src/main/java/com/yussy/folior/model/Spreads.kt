package com.yussy.folior.model

/**
 * 书籍跨页配对：封面单右、封底单左，中间 (2,3)(4,5)…
 * 索引 0 基，-1 表示该侧为空。编辑器与放映共用同一套配对，翻页位置才不会对不上。
 */
fun spreadPairs(pageCount: Int): List<Pair<Int, Int>> {
    val res = mutableListOf(-1 to 0)
    var i = 1
    while (i < pageCount) {
        if (i == pageCount - 1 && pageCount % 2 == 0) {
            res.add(i to -1); i++
        } else {
            res.add(i to (if (i + 1 < pageCount) i + 1 else -1)); i += 2
        }
    }
    return res
}

package com.yussy.folior.ui

/**
 * 界面用色。所有组件的颜色统一从这里取，同一个色值不再散落在各个文件里。
 */
object Palette {
    /** 正文、标题、描边主色 */
    val INK = 0xFF1A1A18.toInt()

    /** 次级文字：说明、标签 */
    val INK_SOFT = 0xFF6B6B66.toInt()

    /** 提示文字：分组标题、单位 */
    val MUTED = 0xFF8A8A84.toInt()

    /** 更浅的提示：未激活图标、次要刻度 */
    val MUTED_LIGHT = 0xFFA8A8A0.toInt()

    /** 纸张底色 */
    val PAPER = 0xFFFAFAF7.toInt()

    /** 分隔线与细描边 */
    val LINE = 0xFFDDDDD6.toInt()

    /** 强调色：选中态、当前项 */
    val ACCENT = 0xFFB5442A.toInt()

    val WHITE = 0xFFFFFFFF.toInt()
}

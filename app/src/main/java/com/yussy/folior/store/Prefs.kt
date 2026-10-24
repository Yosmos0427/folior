package com.yussy.folior.store

import android.content.Context

/**
 * 全局设置（跨项目共享）：PDF 导出精度与体积上限。
 * 两项设置相互独立：
 *  - 精度：决定渲染分辨率（轻量 72 / 标准 192 / 精细 300 / 自定义）
 *  - 体积上限：导出后的目标体积（默认无上限）
 * SharedPreferences 存储，改动即时生效，无需保存按钮。
 */
object Prefs {

    /** 精度档位标识 */
    const val DPI_LIGHT = 72
    const val DPI_STANDARD = 192
    const val DPI_FINE = 300
    const val MODE_CUSTOM = "custom"

    private const val NAME = "folior_settings"
    private const val K_DPI_MODE = "pdf_dpi_mode_v2"
    private const val K_DPI_CUSTOM = "pdf_dpi_custom"
    private const val K_LIMIT_MB = "pdf_limit_mb"
    private const val K_PREVIEW_BG = "preview_bg_color"
    private const val K_PREVIEW_KEEP_BLANK = "preview_keep_blank"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------- 精度 ----------------

    /** 当前精度档：返回 "72" / "192" / "300" / "custom" */
    fun pdfDpiMode(c: Context): String =
        sp(c).getString(K_DPI_MODE, DPI_STANDARD.toString()) ?: DPI_STANDARD.toString()

    fun setPdfDpiMode(c: Context, mode: String) {
        sp(c).edit().putString(K_DPI_MODE, mode).apply()
    }

    fun pdfCustomDpi(c: Context): Int = sp(c).getInt(K_DPI_CUSTOM, 240).coerceIn(72, 600)

    fun setPdfCustomDpi(c: Context, dpi: Int) {
        sp(c).edit().putInt(K_DPI_CUSTOM, dpi.coerceIn(72, 600)).apply()
    }

    /** 实际导出精度 */
    fun pdfDpi(c: Context): Int = when (pdfDpiMode(c)) {
        DPI_LIGHT.toString() -> DPI_LIGHT
        DPI_FINE.toString() -> DPI_FINE
        MODE_CUSTOM -> pdfCustomDpi(c)
        else -> DPI_STANDARD
    }

    fun pdfDpiLabel(c: Context): String = when (pdfDpiMode(c)) {
        MODE_CUSTOM -> "自定义 ${pdfCustomDpi(c)} dpi"
        else -> "${pdfDpi(c)} dpi"
    }

    // ---------------- 体积上限 ----------------

    /** 体积上限（MB）；0 = 无上限 */
    fun pdfLimitMb(c: Context): Int = sp(c).getInt(K_LIMIT_MB, 0).coerceIn(0, 2000)

    fun setPdfLimitMb(c: Context, mb: Int) {
        sp(c).edit().putInt(K_LIMIT_MB, mb.coerceIn(0, 2000)).apply()
    }

    fun pdfLimitBytes(c: Context): Long = pdfLimitMb(c).toLong() * 1024L * 1024L

    fun pdfLimitLabel(c: Context): String {
        val mb = pdfLimitMb(c)
        return if (mb <= 0) "体积不限" else "上限 $mb MB"
    }

    // ---------------- 预览图导出 ----------------

    /** 预览图背景色（ARGB），默认白色 */
    fun previewBg(c: Context): Int = sp(c).getInt(K_PREVIEW_BG, 0xFFFFFFFF.toInt())

    fun setPreviewBg(c: Context, color: Int) {
        sp(c).edit().putInt(K_PREVIEW_BG, color).apply()
    }

    /** 预览图是否保留空白页（未放置任何元素的页面），默认保留 */
    fun previewKeepBlank(c: Context): Boolean =
        sp(c).getBoolean(K_PREVIEW_KEEP_BLANK, true)

    fun setPreviewKeepBlank(c: Context, keep: Boolean) {
        sp(c).edit().putBoolean(K_PREVIEW_KEEP_BLANK, keep).apply()
    }
}

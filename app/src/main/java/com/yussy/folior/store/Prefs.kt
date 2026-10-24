package com.yussy.folior.store

import android.content.Context

/**
 * 全局设置（跨项目共享）：目前是 PDF 导出档位。
 * SharedPreferences 存储，改动即时生效，无需保存按钮。
 */
object Prefs {

    const val PDF_SMALL = "small"
    const val PDF_STANDARD = "standard"
    const val PDF_FULL = "full"
    const val PDF_CUSTOM = "custom"

    private const val NAME = "folior_settings"
    private const val K_PDF_MODE = "pdf_size_mode"
    private const val K_PDF_CUSTOM_MB = "pdf_custom_mb"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun pdfSizeMode(c: Context): String =
        sp(c).getString(K_PDF_MODE, PDF_STANDARD) ?: PDF_STANDARD

    fun setPdfSizeMode(c: Context, mode: String) {
        sp(c).edit().putString(K_PDF_MODE, mode).apply()
    }

    fun pdfCustomMb(c: Context): Int = sp(c).getInt(K_PDF_CUSTOM_MB, 50).coerceIn(1, 2000)

    fun setPdfCustomMb(c: Context, mb: Int) {
        sp(c).edit().putInt(K_PDF_CUSTOM_MB, mb.coerceIn(1, 2000)).apply()
    }

    /** 各档位的起始渲染精度（dpi）；超限时会自动逐级下调 */
    fun pdfDpi(c: Context): Int = when (pdfSizeMode(c)) {
        PDF_SMALL -> 150
        PDF_STANDARD -> 225
        PDF_FULL -> 300
        PDF_CUSTOM -> 300
        else -> 225
    }

    /** 硬性体积上限（MB）；0 表示不限制 */
    fun pdfLimitMb(c: Context): Int = when (pdfSizeMode(c)) {
        PDF_SMALL -> 20
        PDF_STANDARD -> 100
        PDF_FULL -> 0
        PDF_CUSTOM -> pdfCustomMb(c)
        else -> 100
    }

    fun pdfModeLabel(c: Context): String = when (pdfSizeMode(c)) {
        PDF_SMALL -> "小型"
        PDF_STANDARD -> "标准"
        PDF_FULL -> "完整"
        PDF_CUSTOM -> "自定义 ${pdfCustomMb(c)} MB"
        else -> "标准"
    }
}

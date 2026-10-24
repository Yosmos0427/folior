package com.yussy.folior.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.model.heightMm
import com.yussy.folior.model.widthMm
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 项目预览图：把全部页面 / 墙体按顺序平铺成**一张图片**，用于整体浏览项目概况。
 *
 * 布局规则：
 *  - 摄影书：列数取**偶数**，并在封面之前留一格空位——于是从第 2 页起，同一行的左右两页
 *    正好对应"翻开时的左页与右页"（2|3、4|5…）；封底单独落在最后一行也没关系。
 *  - 展览：无明显对开关系，取近似方形列数，并避免末行只剩一个。
 *
 * 尺寸不固定：单格以 420px 为基准，总像素超出内存预算时自动等比缩小。
 */
object ProjectPreview {

    private const val BASE_CELL_W = 420     // 单格基准宽度（像素）
    private const val BASE_MARGIN = 48      // 外边距
    private const val BASE_GAP = 24         // 格间距
    private const val MAX_ASPECT = 3f       // 单格最高 = cellW × 3，超长墙体改为窄幅居中

    /**
     * @param bgColor 背景色
     * @param keepBlank 是否保留没有任何元素的空白页
     * @param onProgress (已完成页数, 总页数)
     */
    fun build(
        ctx: Context,
        project: Project,
        bgColor: Int = Color.WHITE,
        keepBlank: Boolean = true,
        onProgress: (Int, Int) -> Unit
    ): Bitmap {
        require(project.pages.isNotEmpty()) { "项目中没有页面" }
        // 空白页 = 未放置任何元素（照片 / 文字 / 色块 / 笔画）
        val pages: List<Page> =
            if (keepBlank) project.pages else project.pages.filter { it.items.isNotEmpty() }
        require(pages.isNotEmpty()) { "所有页面都是空白页，没有可显示的内容" }

        val isBook = project.type == Project.TYPE_BOOK
        val n = pages.size
        // 摄影书在封面之前留一格间隔
        val lead = if (isBook) 1 else 0
        val slotCount = n + lead

        val cols = if (isBook) evenCols(slotCount) else autoCols(slotCount)
        val rows = ceil(slotCount.toDouble() / cols).toInt()

        // 每个格位对应的高宽比；空位（封面前的间隔）为 0
        val aspects = FloatArray(slotCount) { idx ->
            if (idx < lead) 0f else {
                val p = pages[idx - lead]
                p.heightMm(project).coerceAtLeast(1f) / p.widthMm(project).coerceAtLeast(1f)
            }
        }

        // ---- 尺寸：按可用堆收缩，避免大项目 OOM ----
        val heap = Runtime.getRuntime().maxMemory()
        val maxPx = ((heap * 0.22) / 4.0).toLong().coerceAtLeast(2_000_000L)

        var cellW = BASE_CELL_W
        var layout = layoutOf(cellW, cols, rows, slotCount, aspects)
        var guard = 0
        while (layout.totalW.toLong() * layout.totalH > maxPx && cellW > 90 && guard < 14) {
            cellW = max(90, (cellW * 0.86).toInt())
            layout = layoutOf(cellW, cols, rows, slotCount, aspects)
            guard++
        }

        val bmp = Bitmap.createBitmap(
            max(1, layout.totalW), max(1, layout.totalH), Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        c.drawColor(bgColor)

        val scale = cellW.toFloat() / BASE_CELL_W
        val margin = max(8, (BASE_MARGIN * scale).toInt())
        val gap = max(4, (BASE_GAP * scale).toInt())
        // 格线随背景明暗自动取色，深色背景上也能看清页边界
        val lum = (Color.red(bgColor) * 299 + Color.green(bgColor) * 587 +
                Color.blue(bgColor) * 114) / 1000f / 255f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, cellW / 200f)
            color = if (lum > 0.55f) 0xFFC6C6BE.toInt() else 0x55FFFFFF
        }

        var y = margin.toFloat()
        var drawn = 0
        for (r in 0 until rows) {
            val rowH = layout.rowHeights[r]
            for (col in 0 until cols) {
                val slot = r * cols + col
                if (slot >= slotCount) break
                if (slot < lead) continue       // 封面前的空位：留白
                val page = pages[slot - lead]
                val aspect = aspects[slot]
                val idealH = cellW * aspect
                val capped = idealH > cellW * MAX_ASPECT
                val ch = min(idealH, cellW * MAX_ASPECT).roundToInt().coerceAtLeast(1)
                // 超长页面：高度受限后宽度按比例变窄，格内水平居中（不变形）
                val cw = if (capped) (ch / aspect).roundToInt().coerceAtLeast(1) else cellW
                val x = margin + col * (cellW + gap) + (cellW - cw) / 2f
                val oy = y + (rowH - ch) / 2f

                val thumb = PageRenderer.renderThumb(ctx, project, page, cw, ch)
                c.drawBitmap(thumb, x, oy, null)
                thumb.recycle()
                c.drawRect(x, oy, x + cw, oy + ch, border)
                drawn++
                onProgress(drawn, n)
            }
            y += rowH + gap
        }
        return bmp
    }

    /** 摄影书：偶数列表（奇数则向上取偶），保证同一行左右两页成对 */
    private fun evenCols(total: Int): Int {
        var c = ceil(sqrt(total.toDouble())).toInt()
        if (c % 2 == 1) c += 1
        return c.coerceIn(2, 12)
    }

    /** 展览：近似方形，并避免"最后一行只剩一个" */
    private fun autoCols(total: Int): Int {
        var c = ceil(sqrt(total.toDouble())).toInt().coerceIn(1, 12)
        if (total > c && total % c == 1) c = if (c < 12) c + 1 else c - 1
        return c
    }

    private class Layout(val totalW: Int, val totalH: Int, val rowHeights: IntArray)

    private fun layoutOf(
        cellW: Int, cols: Int, rows: Int, slots: Int, aspects: FloatArray
    ): Layout {
        val scale = cellW.toFloat() / BASE_CELL_W
        val margin = max(8, (BASE_MARGIN * scale).toInt())
        val gap = max(4, (BASE_GAP * scale).toInt())
        val hs = IntArray(rows)
        for (r in 0 until rows) {
            var maxH = 1
            for (col in 0 until cols) {
                val slot = r * cols + col
                if (slot >= slots) break
                val a = aspects[slot]
                if (a <= 0f) continue           // 空位不参与行高
                val ch = min(cellW * a, cellW * MAX_ASPECT).roundToInt()
                if (ch > maxH) maxH = ch
            }
            hs[r] = maxH.coerceAtLeast(1)
        }
        val w = margin * 2 + cols * cellW + (cols - 1) * gap
        val h = margin * 2 + hs.sum() + (rows - 1) * gap
        return Layout(w, h, hs)
    }
}

package com.yussy.folior.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.yussy.folior.model.Project
import com.yussy.folior.model.heightMm
import com.yussy.folior.model.widthMm
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 项目预览图：把全部页面 / 墙体按顺序平铺成**一张白底图片**，用于整体浏览项目概况。
 *
 * 尺寸不固定：行列按页数自动决定；单格大小以 420px 为基准，
 * 总像素超出内存预算时自动等比缩小（页数极多或含超大幅面墙体时触发）。
 */
object ProjectPreview {

    private const val BASE_CELL_W = 420     // 单格基准宽度（像素）
    private const val BASE_MARGIN = 48      // 外边距
    private const val BASE_GAP = 24         // 格间距
    private const val MAX_ASPECT = 3f       // 单格最高 = cellW × 3，超长墙体改为窄幅居中

    /**
     * @param onProgress (已完成页数, 总页数)
     */
    fun build(ctx: Context, project: Project, onProgress: (Int, Int) -> Unit): Bitmap {
        val pages = project.pages
        require(pages.isNotEmpty()) { "项目中没有页面" }
        val n = pages.size

        // ---- 行列：近似方形，并避免最后一行只剩一个 ----
        var cols = ceil(sqrt(n.toDouble())).toInt().coerceIn(1, 12)
        if (n > cols && n % cols == 1) cols = if (cols < 12) cols + 1 else cols - 1
        val rows = ceil(n.toDouble() / cols).toInt()

        // 每页高宽比（h / w）
        val aspects = FloatArray(n) { i ->
            val p = pages[i]
            p.heightMm(project).coerceAtLeast(1f) / p.widthMm(project).coerceAtLeast(1f)
        }

        // ---- 尺寸：按可用堆收缩，避免大项目 OOM ----
        val heap = Runtime.getRuntime().maxMemory()
        val maxPx = ((heap * 0.22) / 4.0).toLong().coerceAtLeast(2_000_000L)

        var cellW = BASE_CELL_W
        var layout = layoutOf(cellW, cols, rows, n, aspects)
        var guard = 0
        while (layout.totalW.toLong() * layout.totalH > maxPx && cellW > 90 && guard < 14) {
            cellW = max(90, (cellW * 0.86).toInt())
            layout = layoutOf(cellW, cols, rows, n, aspects)
            guard++
        }

        val bmp = Bitmap.createBitmap(
            max(1, layout.totalW), max(1, layout.totalH), Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val scale = cellW.toFloat() / BASE_CELL_W
        val margin = max(8, (BASE_MARGIN * scale).toInt())
        val gap = max(4, (BASE_GAP * scale).toInt())
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, cellW / 200f)
            color = 0xFFC6C6BE.toInt()
        }

        var y = margin.toFloat()
        for (r in 0 until rows) {
            val rowH = layout.rowHeights[r]
            for (col in 0 until cols) {
                val i = r * cols + col
                if (i >= n) break
                val aspect = aspects[i]
                val idealH = cellW * aspect
                val capped = idealH > cellW * MAX_ASPECT
                val ch = min(idealH, cellW * MAX_ASPECT).roundToInt().coerceAtLeast(1)
                // 超长页面：高度受限后宽度按比例变窄，格内水平居中（不变形）
                val cw = if (capped) (ch / aspect).roundToInt().coerceAtLeast(1) else cellW
                val x = margin + col * (cellW + gap) + (cellW - cw) / 2f
                val oy = y + (rowH - ch) / 2f

                val thumb = PageRenderer.renderThumb(ctx, project, pages[i], cw, ch)
                c.drawBitmap(thumb, x, oy, null)
                thumb.recycle()
                c.drawRect(x, oy, x + cw, oy + ch, border)
                onProgress(i + 1, n)
            }
            y += rowH + gap
        }
        return bmp
    }

    private class Layout(val totalW: Int, val totalH: Int, val rowHeights: IntArray)

    private fun layoutOf(
        cellW: Int, cols: Int, rows: Int, n: Int, aspects: FloatArray
    ): Layout {
        val scale = cellW.toFloat() / BASE_CELL_W
        val margin = max(8, (BASE_MARGIN * scale).toInt())
        val gap = max(4, (BASE_GAP * scale).toInt())
        val hs = IntArray(rows)
        for (r in 0 until rows) {
            var maxH = 1
            for (col in 0 until cols) {
                val i = r * cols + col
                if (i >= n) break
                val ch = min(cellW * aspects[i], cellW * MAX_ASPECT).roundToInt()
                if (ch > maxH) maxH = ch
            }
            hs[r] = maxH.coerceAtLeast(1)
        }
        val w = margin * 2 + cols * cellW + (cols - 1) * gap
        val h = margin * 2 + hs.sum() + (rows - 1) * gap
        return Layout(w, h, hs)
    }
}

package com.yussy.folior.ui

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import com.yussy.folior.model.Project
import java.io.OutputStream

/**
 * PDF 导出。
 * 三种版式：
 *  1) 单页标准：每页一个 PDF 页，尺寸 = 项目真实尺寸
 *  2) 单页 + 首页空白：在最前插入一张空白页，使封面落在双页视图的右半页，
 *     修正阅读器「第 1 页默认放左」造成的整本错位（推荐）
 *  3) 双页合并：每个 PDF 页 = 一组对开页（宽 = 2 倍页宽）
 *
 * 性能要点：
 *  - 图片按成品 300dpi 下的实际占版尺寸解码（pxHint），不做无意义的超大图解码
 *  - 相邻页只补画真正越界（跨页）的元素，避免把整页内容重复嵌入三次
 *  - 进度分为「渲染页面」与「写入文件」两个阶段，避免 100% 后长时间无反馈
 */
object PdfExporter {

    data class Options(
        val insertBlankFirst: Boolean = false,
        val mergeSpreads: Boolean = false
    )

    interface Progress {
        fun onProgress(done: Int, total: Int)
        fun onStage(text: String) {}
        fun onDone(ok: Boolean, error: String?)
    }

    /** 与编辑器一致的书籍配对：封面单右、中间 (2,3)(4,5)…、封底单左。 */
    private fun spreadsOf(n: Int): List<Pair<Int, Int>> {
        if (n <= 0) return listOf(-1 to 0)
        val res = mutableListOf(-1 to 0)
        var i = 1
        while (i < n) {
            if (i == n - 1 && n % 2 == 0) { res.add(i to -1); i++ }
            else { res.add(i to (if (i + 1 < n) i + 1 else -1)); i += 2 }
        }
        return res
    }

    /** 页宽（pt）→ 300dpi 下的像素宽，用于决定图片解码尺寸。 */
    private fun pxAt300(pageWidthPt: Float): Int = (pageWidthPt / 72f * 300f).toInt()

    fun export(ctx: Context, project: Project, out: OutputStream, opts: Options, progress: Progress) {
        Thread {
            var ok = false
            var err: String? = null
            try {
                val doc = PdfDocument()
                val pw = project.pageWmm / 25.4f * 72f   // mm → pt
                val ph = project.pageHmm / 25.4f * 72f
                val bg = com.yussy.folior.model.parseColor(project.defaultBg, Color.WHITE)
                val handler = Handler(Looper.getMainLooper())
                // 单页内元素的解码像素上限（按页宽 300dpi）
                val pxHintSingle = pxAt300(pw)

                if (opts.mergeSpreads) {
                    val spreads = spreadsOf(project.pages.size)
                    val total = spreads.size
                    spreads.forEachIndexed { idx, pair ->
                        val info = PdfDocument.PageInfo.Builder(
                            Math.round(pw * 2f), Math.round(ph), idx + 1).create()
                        val pdfPage = doc.startPage(info)
                        val c = pdfPage.canvas
                        val leftRect = RectF(0f, 0f, pw, ph)
                        val rightRect = RectF(pw, 0f, pw * 2f, ph)

                        c.drawRect(leftRect, android.graphics.Paint().apply { color = bg })
                        c.drawRect(rightRect, android.graphics.Paint().apply { color = bg })
                        pair.first.takeIf { it >= 0 }?.let { i ->
                            project.pages.getOrNull(i)?.let {
                                PageRenderer.drawPageBackground(project, it, c, leftRect)
                            }
                        }
                        pair.second.takeIf { it >= 0 }?.let { i ->
                            project.pages.getOrNull(i)?.let {
                                PageRenderer.drawPageBackground(project, it, c, rightRect)
                            }
                        }
                        pair.first.takeIf { it >= 0 }?.let { i ->
                            project.pages.getOrNull(i)?.let {
                                PageRenderer.drawItems(ctx, project, it, c, leftRect, true, pxHintSingle)
                            }
                        }
                        pair.second.takeIf { it >= 0 }?.let { i ->
                            project.pages.getOrNull(i)?.let {
                                PageRenderer.drawItems(ctx, project, it, c, rightRect, true, pxHintSingle)
                            }
                        }
                        doc.finishPage(pdfPage)
                        handler.post { progress.onProgress(idx + 1, total) }
                    }
                } else {
                    val list = ArrayList<com.yussy.folior.model.Page?>(project.pages.size + 1)
                    if (opts.insertBlankFirst) list.add(null)
                    list.addAll(project.pages)
                    val total = list.size
                    val pr = RectF(0f, 0f, pw, ph)
                    val offset = if (opts.insertBlankFirst) 1 else 0

                    list.forEachIndexed { i, page ->
                        val info = PdfDocument.PageInfo.Builder(
                            Math.round(pw), Math.round(ph), i + 1).create()
                        val pdfPage = doc.startPage(info)
                        val canvas = pdfPage.canvas
                        if (page == null) {
                            canvas.drawColor(bg)
                        } else {
                            PageRenderer.drawPageBackground(project, page, canvas, pr)
                            if (project.type == Project.TYPE_BOOK) {
                                val realIdx = i - offset
                                // 只补画真正越界到本页的元素（避免整页重复嵌入）
                                project.pages.getOrNull(realIdx - 1)?.let { prev ->
                                    drawOverflow(ctx, project, prev, canvas, pr,
                                        toRight = true, pxHint = pxHintSingle)
                                }
                                project.pages.getOrNull(realIdx + 1)?.let { next ->
                                    drawOverflow(ctx, project, next, canvas, pr,
                                        toRight = false, pxHint = pxHintSingle)
                                }
                            }
                            PageRenderer.drawItems(ctx, project, page, canvas, pr, true, pxHintSingle)
                        }
                        doc.finishPage(pdfPage)
                        handler.post { progress.onProgress(i + 1, total) }
                    }
                }

                // 第二阶段：写入文件（进度条已满但仍在工作，需要明确提示）
                handler.post { progress.onStage("正在写入文件…") }
                out.use {
                    doc.writeTo(it)
                    it.flush()
                }
                doc.close()
                ok = true
            } catch (e: Exception) {
                err = e.message
            }
            val fOk = ok; val fErr = err
            Handler(Looper.getMainLooper()).post {
                if (fOk) progress.onStage("已保存")
                progress.onDone(fOk, fErr)
            }
        }.start()
    }

    /**
     * 把相邻页中越界（跨书脊）的元素补画到当前页。
     * toRight=true 表示相邻页在左侧，其 x+w>1 的部分溢出到本页；
     * toRight=false 表示相邻页在右侧，其 x<0 的部分溢出到本页。
     */
    private fun drawOverflow(
        ctx: Context, project: Project, neighbor: com.yussy.folior.model.Page,
        canvas: android.graphics.Canvas, pr: RectF, toRight: Boolean, pxHint: Int
    ) {
        val target = RectF(
            if (toRight) pr.left - pr.width() else pr.right,
            pr.top,
            if (toRight) pr.left else pr.right + pr.width(),
            pr.bottom
        )
        val items = neighbor.items.filter { it ->
            if (toRight) it.x + it.w > 1.0001f else it.x < -0.0001f
        }
        if (items.isEmpty()) return
        val isExhibition = project.type == Project.TYPE_EXHIBITION
        items.forEach { item ->
            PageRenderer.drawItem(ctx, item, canvas, target.left, target.top,
                target.width(), target.height(), isExhibition, true, pxHint)
        }
    }
}

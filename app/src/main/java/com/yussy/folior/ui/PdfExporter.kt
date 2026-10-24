package com.yussy.folior.ui

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import com.yussy.folior.model.Project
import java.io.OutputStream
import kotlin.math.max

/**
 * PDF 导出（支持每页独立尺寸：自定义页 / 墙体）。
 * 三种版式：
 *  1) 单页标准：每页一个 PDF 页，尺寸 = 该页实际尺寸
 *  2) 单页 + 首页空白：修正双页视图「第 1 页默认放左」造成的错位（推荐）
 *  3) 双页合并：每个 PDF 页 = 一组对开页
 * 性能：图片按成品 300dpi 占版尺寸解码；相邻页只续画真正越界的元素。
 */
object PdfExporter {

    data class Options(
        val insertBlankFirst: Boolean = false,
        val mergeSpreads: Boolean = false,
        /** 渲染精度：小型档用较低 dpi 控制体积，完整档用 300 */
        val dpi: Int = 300
    )

    interface Progress {
        fun onProgress(done: Int, total: Int)
        fun onStage(text: String) {}
        fun onDone(ok: Boolean, error: String?)
    }

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

    private fun wPt(project: Project, page: com.yussy.folior.model.Page?): Float =
        ((page?.wmm ?: project.pageWmm) / 25.4f * 72f)

    private fun hPt(project: Project, page: com.yussy.folior.model.Page?): Float =
        ((page?.hmm ?: project.pageHmm) / 25.4f * 72f)

    private fun pxAt(dpi: Int, ptWidth: Float): Int = (ptWidth / 72f * dpi).toInt()

    /** 异步导出（内部起线程）；需要分档重试时用 exportBlocking */
    fun export(ctx: Context, project: Project, out: OutputStream, opts: Options, progress: Progress) {
        Thread { runExport(ctx, project, out, opts, progress) }.start()
    }

    /** 同步导出：在调用线程执行，返回时文件已写完（供"超限降精度重导"循环使用） */
    fun exportBlocking(
        ctx: Context, project: Project, out: OutputStream, opts: Options, progress: Progress
    ) {
        runExport(ctx, project, out, opts, progress)
    }

    private fun runExport(
        ctx: Context, project: Project, out: OutputStream, opts: Options, progress: Progress
    ) {
        run {
            var ok = false
            var err: String? = null
            try {
                val doc = PdfDocument()
                val bg = com.yussy.folior.model.parseColor(project.defaultBg, Color.WHITE)
                val handler = Handler(Looper.getMainLooper())

                if (opts.mergeSpreads) {
                    val spreads = spreadsOf(project.pages.size)
                    val total = spreads.size
                    spreads.forEachIndexed { idx, pair ->
                        val pageL = if (pair.first >= 0) project.pages.getOrNull(pair.first) else null
                        val pageR = if (pair.second >= 0) project.pages.getOrNull(pair.second) else null
                        val wl = wPt(project, pageL); val hl = hPt(project, pageL)
                        val wr = wPt(project, pageR); val hr = hPt(project, pageR)
                        val H = max(hl, hr)
                        val info = PdfDocument.PageInfo.Builder(
                            Math.round(wl + wr), Math.round(H), idx + 1).create()
                        val pdfPage = doc.startPage(info)
                        val c = pdfPage.canvas
                        c.drawColor(bg)
                        val leftRect = RectF(0f, (H - hl) / 2f, wl, (H + hl) / 2f)
                        val rightRect = RectF(wl, (H - hr) / 2f, wl + wr, (H + hr) / 2f)
                        pageL?.let { PageRenderer.drawPageBackground(project, it, c, leftRect) }
                        pageR?.let { PageRenderer.drawPageBackground(project, it, c, rightRect) }
                        c.save()
                        c.clipRect(0f, 0f, wl + wr, H)
                        pageL?.let {
                            PageRenderer.drawItems(ctx, project, it, c, leftRect, true, pxAt(opts.dpi, wl)) }
                        pageR?.let {
                            PageRenderer.drawItems(ctx, project, it, c, rightRect, true, pxAt(opts.dpi, wr)) }
                        c.restore()
                        doc.finishPage(pdfPage)
                        handler.post { progress.onProgress(idx + 1, total) }
                    }
                } else {
                    val list = ArrayList<com.yussy.folior.model.Page?>(project.pages.size + 1)
                    if (opts.insertBlankFirst) list.add(null)
                    list.addAll(project.pages)
                    val total = list.size
                    val offset = if (opts.insertBlankFirst) 1 else 0

                    list.forEachIndexed { i, page ->
                        val pw = wPt(project, page)
                        val ph = hPt(project, page)
                        val info = PdfDocument.PageInfo.Builder(
                            Math.round(pw), Math.round(ph), i + 1).create()
                        val pdfPage = doc.startPage(info)
                        val canvas = pdfPage.canvas
                        if (page == null) {
                            canvas.drawColor(bg)
                        } else {
                            val pr = RectF(0f, 0f, pw, ph)
                            PageRenderer.drawPageBackground(project, page, canvas, pr)
                            if (project.type == Project.TYPE_BOOK) {
                                val realIdx = i - offset
                                project.pages.getOrNull(realIdx - 1)?.let { prev ->
                                    drawOverflow(ctx, project, prev, canvas,
                                        RectF(-wPt(project, prev), 0f, 0f, hPt(project, prev)),
                                        toRight = true, pxHint = pxAt(opts.dpi, wPt(project, prev)))
                                }
                                project.pages.getOrNull(realIdx + 1)?.let { next ->
                                    drawOverflow(ctx, project, next, canvas,
                                        RectF(pw, 0f, pw + wPt(project, next), hPt(project, next)),
                                        toRight = false, pxHint = pxAt(opts.dpi, wPt(project, next)))
                                }
                            }
                            PageRenderer.drawItems(ctx, project, page, canvas, pr, true, pxAt(opts.dpi, pw))
                        }
                        doc.finishPage(pdfPage)
                        handler.post { progress.onProgress(i + 1, total) }
                    }
                }

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
        }
    }

    /** 把相邻页中真正越界（跨书脊）的元素补画到当前页。 */
    private fun drawOverflow(
        ctx: Context, project: Project, neighbor: com.yussy.folior.model.Page,
        canvas: android.graphics.Canvas, target: RectF, toRight: Boolean, pxHint: Int
    ) {
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

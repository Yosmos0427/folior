package com.yussy.folior.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.model.parseColor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PDF 导出（每页独立尺寸，支持自定义页 / 墙体）。
 *
 * 版式：
 *  1) 单页 —— 每页一个 PDF 页
 *  2) 双联 —— 每两个页面合并为一组对开页
 *  3) 单页 + 空白首页 —— 修正双页阅读时第 1 页落在左侧的错位
 *
 * 流程（只渲染一次，绝不为体积反复重导）：
 *   渲染页面 → JPEG 编码（临时文件）→ [超上限时：仅重压 JPEG，不重渲染] → 写 PDF
 *
 * PDF 由本类直接写出（每页一张 DCTDecode JPEG），因此：
 *  - 体积由「像素数 × JPEG 质量」精确决定，可逐级压缩而无需重新渲染
 *  - 页面尺寸由 MediaBox 决定，与像素数解耦；内存不足时自动降低有效像素（尺寸不变）
 */
object PdfExporter {

    data class Options(
        val insertBlankFirst: Boolean = false,
        val mergeSpreads: Boolean = false,
        /** 渲染精度（dpi） */
        val dpi: Int = 192,
        /** 目标体积（字节）；<= 0 表示不限制 */
        val limitBytes: Long = 0L
    )

    interface Progress {
        /** @param percent 0–100 */
        fun onStage(text: String, percent: Int)
        /** 结束回调；note 用于"已压缩但仍超上限"等提示（可为 null） */
        fun onDone(ok: Boolean, error: String?, note: String?)
    }

    /** 首次编码质量：高质量起点，压缩阶段只降不升 */
    private const val INITIAL_QUALITY = 92
    /** 压缩阶梯（体积超上限时依次尝试） */
    private val SHRINK_QUALITIES = intArrayOf(84, 74, 64, 54, 44)

    private sealed class Spec {
        class Blank : Spec()
        class Single(val page: Page) : Spec()
        class Spread(val l: Page?, val r: Page?) : Spec()
    }

    private class Rendered(
        val wPt: Float,
        val hPt: Float,
        /** 首次渲染出的原始 JPEG（始终保持不变，所有压缩都从它出发） */
        val orig: File,
        /** 当前选用的 JPEG（可能被压缩替换） */
        var file: File,
        var pxW: Int,
        var pxH: Int
    )

    // ---------------- 入口 ----------------

    fun export(
        ctx: Context, project: Project, out: OutputStream,
        opts: Options, progress: Progress
    ) {
        Thread { exportBlocking(ctx, project, out, opts, progress) }.start()
    }

    /** 同步导出（在调用线程执行） */
    fun exportBlocking(
        ctx: Context, project: Project, out: OutputStream,
        opts: Options, progress: Progress
    ) {
        var ok = false
        var err: String? = null
        var note: String? = null
        val tmp = File(ctx.cacheDir, "pdf_tmp")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            val dpi = opts.dpi.coerceIn(36, 600)
            // 百分比单调递增包装：压缩补轮时不会让进度条倒退
            var lastPct = 0
            val mono = object : Progress {
                override fun onStage(text: String, percent: Int) {
                    val pc = max(lastPct, percent.coerceIn(0, 100))
                    lastPct = pc
                    progress.onStage(text, pc)
                }
                override fun onDone(ok: Boolean, error: String?, note: String?) = Unit
            }

            val specs = buildSpecs(project, opts)
            val pages = renderAll(ctx, project, specs, dpi, tmp, mono)

            if (opts.limitBytes > 0) {
                val total = pages.sumOf { it.orig.length() }
                if (total > opts.limitBytes) {
                    note = shrinkToLimit(pages, opts.limitBytes, mono)
                }
            }

            mono.onStage("正在写入 PDF…", 97)
            BufferedOutputStream(out, 128 * 1024).use { writePdf(it, pages) }
            ok = true
        } catch (e: OutOfMemoryError) {
            err = "内存不足：页面尺寸过大，请在设置中降低导出精度"
        } catch (t: Throwable) {
            err = t.message ?: t.javaClass.simpleName
        } finally {
            tmp.deleteRecursively()
        }
        progress.onDone(ok, err, note)
    }

    // ---------------- 版式 ----------------

    private fun spreadsOf(n: Int): List<Pair<Int, Int>> {
        if (n <= 0) return listOf(-1 to 0)
        val res = mutableListOf(-1 to 0)
        var i = 1
        while (i < n) {
            if (i == n - 1 && n % 2 == 0) {
                res.add(i to -1); i++
            } else {
                res.add(i to (if (i + 1 < n) i + 1 else -1)); i += 2
            }
        }
        return res
    }

    private fun buildSpecs(project: Project, opts: Options): List<Spec> {
        if (project.pages.isEmpty()) throw IllegalStateException("项目中没有页面")
        val list = ArrayList<Spec>()
        if (opts.mergeSpreads) {
            spreadsOf(project.pages.size).forEach { (a, b) ->
                list.add(Spec.Spread(
                    if (a >= 0) project.pages.getOrNull(a) else null,
                    if (b >= 0) project.pages.getOrNull(b) else null
                ))
            }
        } else {
            if (opts.insertBlankFirst) list.add(Spec.Blank())
            project.pages.forEach { list.add(Spec.Single(it)) }
        }
        return list
    }

    private fun wPt(project: Project, page: Page?): Float =
        ((page?.wmm ?: project.pageWmm) / 25.4f * 72f)

    private fun hPt(project: Project, page: Page?): Float =
        ((page?.hmm ?: project.pageHmm) / 25.4f * 72f)

    private fun specSize(project: Project, spec: Spec): Pair<Float, Float> = when (spec) {
        is Spec.Blank -> wPt(project, null) to hPt(project, null)
        is Spec.Single -> wPt(project, spec.page) to hPt(project, spec.page)
        is Spec.Spread -> {
            val wl = if (spec.l != null) wPt(project, spec.l) else 0f
            val wr = if (spec.r != null) wPt(project, spec.r) else 0f
            val hl = if (spec.l != null) hPt(project, spec.l) else 0f
            val hr = if (spec.r != null) hPt(project, spec.r) else 0f
            (wl + wr) to max(hl, hr)
        }
    }

    // ---------------- 渲染（唯一一次） ----------------

    private fun renderAll(
        ctx: Context, project: Project, specs: List<Spec>, dpi: Int,
        tmp: File, progress: Progress
    ): List<Rendered> {
        val total = specs.size
        val out = arrayOfNulls<Rendered>(total)
        val done = AtomicInteger(0)
        val k = dpi / 72f
        val bg = parseColor(project.defaultBg, Color.WHITE)

        // 单张位图预算：堆的 22%（约 45 MB @ 256MB 堆），超出则等比缩小像素（页面尺寸不变）
        val heap = Runtime.getRuntime().maxMemory()
        val budgetBytes = (heap * 0.22).toLong()

        var worstPx = 1L
        specs.forEach { s ->
            val (w, h) = specSize(project, s)
            val pw = max(1L, (w * k).roundToInt().toLong())
            val ph = max(1L, (h * k).roundToInt().toLong())
            val px = pw * ph
            if (px > worstPx) worstPx = px
        }
        // 并行渲染：仅当两张最大页位图 + 余量仍在堆内
        val threads = if (worstPx * 4L * 3L <= heap) 2 else 1

        fun onPageDone() {
            val d = done.incrementAndGet()
            progress.onStage("渲染页面 $d / $total", (d * 70 / max(1, total)).coerceIn(0, 70))
        }

        fun renderOne(i: Int) {
            val spec = specs[i]
            val (wPtV, hPtV) = specSize(project, spec)
            var pw = max(1, (wPtV * k).roundToInt())
            var ph = max(1, (hPtV * k).roundToInt())
            // 超过预算 → 等比缩小像素（PDF 页面尺寸仍按 pt，等效只降有效精度）
            if (pw.toLong() * ph * 4L > budgetBytes) {
                val targetPx = budgetBytes.toDouble() / 4.0
                // 0.98 留出取整余量，确保收缩后仍在预算内
                val sc = (sqrt(targetPx / (pw.toLong() * ph.toDouble())) * 0.98)
                    .toFloat().coerceIn(0.002f, 1f)
                pw = max(1, (pw * sc).toInt())
                ph = max(1, (ph * sc).toInt())
            }

            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            try {
                val c = Canvas(bmp)
                c.drawColor(bg)
                drawSpec(ctx, project, spec, c, pw, ph)
                val f = File(tmp, "p_$i.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, INITIAL_QUALITY, it) }
                out[i] = Rendered(wPtV, hPtV, f, f, pw, ph)
            } finally {
                bmp.recycle()
            }
        }

        if (threads <= 1) {
            for (i in 0 until total) {
                renderOne(i)
                onPageDone()
            }
        } else {
            val pool = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(total)
            val lock = Any()
            var failure: Throwable? = null
            for (i in 0 until total) {
                pool.execute {
                    try {
                        renderOne(i)
                    } catch (t: Throwable) {
                        synchronized(lock) { if (failure == null) failure = t }
                    } finally {
                        onPageDone()
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()
            failure?.let { throw it }
        }

        return out.map { it ?: throw IllegalStateException("页面渲染失败") }
    }

    private fun drawSpec(
        ctx: Context, project: Project, spec: Spec, c: Canvas, pw: Int, ph: Int
    ) {
        when (spec) {
            is Spec.Blank -> Unit   // 已铺底色
            is Spec.Single -> {
                val pr = RectF(0f, 0f, pw.toFloat(), ph.toFloat())
                PageRenderer.drawPageBackground(project, spec.page, c, pr)
                PageRenderer.drawItems(ctx, project, spec.page, c, pr, true, pw)
            }
            is Spec.Spread -> {
                val wl = if (spec.l != null) wPt(project, spec.l) else 0f
                val wr = if (spec.r != null) wPt(project, spec.r) else 0f
                val hl = if (spec.l != null) hPt(project, spec.l) else 0f
                val hr = if (spec.r != null) hPt(project, spec.r) else 0f
                val H = max(hl, hr)
                if (wl + wr <= 0f || H <= 0f) return
                val kx = pw / (wl + wr)
                val ky = ph / H
                val leftRect = RectF(0f, (H - hl) / 2f * ky, wl * kx, (H + hl) / 2f * ky)
                val rightRect = RectF(wl * kx, (H - hr) / 2f * ky, (wl + wr) * kx, (H + hr) / 2f * ky)
                spec.l?.let { PageRenderer.drawPageBackground(project, it, c, leftRect) }
                spec.r?.let { PageRenderer.drawPageBackground(project, it, c, rightRect) }
                c.save()
                c.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
                spec.l?.let {
                    PageRenderer.drawItems(ctx, project, it, c, leftRect, true, (wl * kx).toInt())
                }
                spec.r?.let {
                    PageRenderer.drawItems(ctx, project, it, c, rightRect, true, (wr * kx).toInt())
                }
                c.restore()
            }
        }
    }

    // ---------------- 压缩（只重编码 JPEG，不重渲染） ----------------

    /**
     * 体积超上限时的压缩：**只重编码图片，不重新渲染页面**。
     *
     * 为省时间，先用少量页采样建立「质量 → 体积」曲线，据此一次性定位目标质量，
     * 再对全部页各编码一次；只有估算偏差时才补一轮。避免"每一档都全量重编一遍"。
     *
     * @return 提示语（压到最低仍超限时），达标返回 null
     */
    private fun shrinkToLimit(pages: List<Rendered>, limit: Long, progress: Progress): String? {
        val totalNow = pages.sumOf { it.orig.length() }
        if (totalNow <= limit) return null

        // ---- 1) 采样：取体积最大的几页建立「质量 → 体积」曲线 ----
        // 选大页而非首页，估算偏保守（宁可多压一点也不补轮）
        val sampleIdx = pages.indices
            .sortedByDescending { pages[it].orig.length() }
            .take(min(4, pages.size))
        val ratio = HashMap<Int, Double>()
        for (q in SHRINK_QUALITIES) {
            var after = 0L
            var before = 0L
            for ((n, i) in sampleIdx.withIndex()) {
                progress.onStage("估算压缩比（质量 $q） ${n + 1} / ${sampleIdx.size}", 70 + n)
                val dst = File(pages[i].orig.parentFile, "probe_${q}_$i.jpg")
                val srcLen = pages[i].orig.length()
                if (reencode(pages[i].orig, dst, q, 1f)) {
                    after += dst.length()
                    before += srcLen
                }
                dst.delete()
            }
            if (before > 0) ratio[q] = after.toDouble() / before.toDouble()
        }
        progress.onStage("按目标体积压缩…", 74)

        // ---- 2) 选能落在上限内的最高质量（预留 5% 余量吸收估算误差） ----
        val target = (limit * 0.95).toLong()
        val chosenQ = SHRINK_QUALITIES.firstOrNull { q ->
            (ratio[q] ?: Double.MAX_VALUE) * totalNow <= target
        }

        if (chosenQ != null) {
            val sum = encodeAll(pages, chosenQ, 1f, progress, 72, 94)
            if (sum <= limit) return null
            // 估算偏差：按下一档再补一轮
            val nextQ = SHRINK_QUALITIES.getOrNull(SHRINK_QUALITIES.indexOf(chosenQ) + 1)
            if (nextQ != null) {
                val sum2 = encodeAll(pages, nextQ, 1f, progress, 72, 94)
                if (sum2 <= limit) return null
            }
        }

        // ---- 3) 兜底：降采样（页面尺寸不变，等效降低有效精度） ----
        val sumLow = encodeAll(pages, 44, 0.7f, progress, 88, 96)
        return if (sumLow <= limit) null else
            "已压缩到 ${fmtMb(sumLow)}，仍超过设定上限 ${fmtMb(limit)}（当前已是可用最低画质）"
    }

    /** 对全部页按指定质量/缩放编码，返回总体积；编码期间更新进度 */
    private fun encodeAll(
        pages: List<Rendered>, quality: Int, scale: Float,
        progress: Progress, fromPct: Int, toPct: Int
    ): Long {
        var sum = 0L
        val n = pages.size
        for ((i, p) in pages.withIndex()) {
            progress.onStage(
                "压缩图片（质量 $quality） ${i + 1} / $n",
                fromPct + (i * (toPct - fromPct) / max(1, n))
            )
            val dst = File(p.file.parentFile, "q${quality}_${scale}_$i.jpg")
            if (!reencode(p.orig, dst, quality, scale)) {
                dst.delete()
                sum += p.file.length()      // 失败按未压缩计，避免误判为已达标
                continue
            }
            p.file = dst
            val (w, h) = jpegSize(dst)
            if (w > 0 && h > 0) { p.pxW = w; p.pxH = h }
            sum += dst.length()
        }
        return sum
    }

    /** 重新编码 JPEG：[scale] < 1 时先降采样 */
    private fun reencode(src: File, dst: File, quality: Int, scale: Float): Boolean {
        var bmp: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            bmp = BitmapFactory.decodeFile(src.path) ?: return false
            val target = if (scale < 0.999f) {
                val w = max(1, (bmp.width * scale).toInt())
                val h = max(1, (bmp.height * scale).toInt())
                Bitmap.createScaledBitmap(bmp, w, h, true).also { scaled = it }
            } else bmp
            FileOutputStream(dst).use { target.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            true
        } catch (t: Throwable) {
            false
        } finally {
            scaled?.recycle()
            bmp?.recycle()
        }
    }

    private fun jpegSize(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    private fun fmtMb(b: Long): String =
        String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)

    // ---------------- 写 PDF ----------------

    private fun writePdf(rawOut: OutputStream, pages: List<Rendered>) {
        val out = BufferedOutputStream(rawOut, 128 * 1024)
        var pos = 0L
        fun w(b: ByteArray) { out.write(b); pos += b.size }
        fun w(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); pos += len }
        fun s(v: String) = w(v.toByteArray(Charsets.ISO_8859_1))
        fun f(v: Float) = String.format(Locale.US, "%.2f", v)

        val objCount = 3 + pages.size * 3      // 对象 0..(2+3n)
        val xref = LongArray(objCount)

        s("%PDF-1.5\n")
        // 二进制标记，避免传输时被当作纯文本处理
        w(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        xref[1] = pos
        s("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val kids = StringBuilder()
        pages.indices.forEach {
            if (it > 0) kids.append(' ')
            kids.append(3 + it * 3).append(" 0 R")
        }
        xref[2] = pos
        s("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

        pages.forEachIndexed { i, pg ->
            val pageNo = 3 + i * 3
            val imgNo = pageNo + 1
            val cntNo = pageNo + 2

            xref[pageNo] = pos
            s("$pageNo 0 obj\n<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 ${f(pg.wPt)} ${f(pg.hPt)}] " +
                "/Resources << /XObject << /Im0 $imgNo 0 R >> >> " +
                "/Contents $cntNo 0 R >>\nendobj\n")

            xref[imgNo] = pos
            s("$imgNo 0 obj\n<< /Type /XObject /Subtype /Image " +
                "/Width ${pg.pxW} /Height ${pg.pxH} /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${pg.file.length()} >>\nstream\n")
            pg.file.inputStream().use { ins ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    w(buf, 0, n)
                }
            }
            s("\nendstream\nendobj\n")

            // 图像铺满整页：(w 0 0 h 0 0 cm) 把单位正方形映射到页面尺寸
            val content = "q ${f(pg.wPt)} 0 0 ${f(pg.hPt)} 0 0 cm /Im0 Do Q\n"
            val cb = content.toByteArray(Charsets.ISO_8859_1)
            xref[cntNo] = pos
            s("$cntNo 0 obj\n<< /Length ${cb.size} >>\nstream\n")
            w(cb)
            s("endstream\nendobj\n")
        }

        val xrefPos = pos
        s("xref\n0 $objCount\n")
        s("0000000000 65535 f \n")
        for (i in 1 until objCount) {
            s(String.format(Locale.US, "%010d 00000 n \n", xref[i]))
        }
        s("trailer\n<< /Size $objCount /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.flush()
    }
}

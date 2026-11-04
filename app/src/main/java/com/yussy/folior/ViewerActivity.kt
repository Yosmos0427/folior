package com.yussy.folior

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yussy.folior.model.*
import com.yussy.folior.model.Project
import com.yussy.folior.store.Prefs
import com.yussy.folior.store.Store
import com.yussy.folior.ui.PageRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 全屏放映。
 * - 摄影书 + 横屏：以书籍形式摊开，左右两页并排；封面单右、封底单左，跨页图连续
 * - 其余情况：单页放映
 *
 * 交互：
 * - 单击左半屏 / 右半屏 = 上一页 / 下一页（跨页模式则为上一跨页 / 下一跨页）
 * - 双击 = 放大到 100%（照片原始像素与屏幕像素 1:1）；再次双击回到适应屏幕
 * - 放大后单指拖动看局部，单击退出放大
 * - 底部拖动条快速定位页面；右上角 ✕ 退出
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var project: Project
    private var pageIndex = 0
    private lateinit var label: TextView
    private lateinit var viewer: View
    private lateinit var scrubber: Scrubber
    /** 拖动条拖动中：低清快速渲染（照片小尺寸解码）；抬手后高清定稿一次 */
    private var fastPreview = false

    // ---- 视图变换（放大 / 平移）----
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    /** 当前倍数是不是"照片原始像素 1:1"算出来的（决定标签写 100% 还是 ×N） */
    private var zoomIsNative = false

    /** 放映取图来源：原图 / 工作图（全局设置） */
    private var src = Store.SRC_ORIGINAL
    private val onDecoded = { viewer.invalidate() }

    // ---- 点击 / 双击判定 ----
    private val ui = Handler(Looper.getMainLooper())
    private var pendingTap: Runnable? = null
    private var suppressClick = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastUpX = 0f
    private var lastUpY = 0f
    private var lastUpTime = 0L

    private val dark = 0xFF14140F.toInt()

    private val density get() = resources.displayMetrics.density
    private val tapSlop get() = 40f * density
    private val doubleTapMs get() = 300L
    private val tapDelayMs get() = 320L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: finish().let { return }
        project = Store.listProjects(this).find { it.id == id } ?: finish().let { return }
        if (project.pages.isEmpty()) { finish(); return }
        // 进入放映默认定位到编辑器当前页
        pageIndex = (intent.getIntExtra("page", 0)).coerceIn(0, project.pages.size - 1)
        src = if (Prefs.viewerSource(this) == Prefs.VIEWER_PROXY) Store.SRC_PROXY
        else Store.SRC_ORIGINAL
        Store.addDecodeListener(onDecoded)

        label = TextView(this).apply {
            textSize = 12.5f
            letterSpacing = 0.08f
            setTextColor(0x99FFFFFF.toInt())
            val d = resources.displayMetrics.density
            setPadding(0, 0, 0, (16 * d).toInt())
            gravity = Gravity.CENTER
        }

        viewer = object : View(this) {

            @Suppress("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val now = System.currentTimeMillis()
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 第二击落在双击窗口内、且位置没跑偏 → 取消这次"单击翻页"
                        val p = pendingTap
                        if (p != null && now - lastUpTime <= doubleTapMs &&
                            abs(ev.x - lastUpX) <= tapSlop && abs(ev.y - lastUpY) <= tapSlop) {
                            ui.removeCallbacks(p)
                            pendingTap = null
                            suppressClick = true
                            toggleZoom(ev.x, ev.y)
                        }
                        downX = ev.x; downY = ev.y
                        lastX = ev.x; lastY = ev.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (zoom > 1.001f) {
                            if (!dragging && hypot(ev.x - downX, ev.y - downY) > tapSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                panX += ev.x - lastX
                                panY += ev.y - lastY
                                lastX = ev.x; lastY = ev.y
                                clampPan()
                                invalidate()
                            }
                        } else if (!dragging && hypot(ev.x - downX, ev.y - downY) > tapSlop) {
                            dragging = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val tapped = !dragging && hypot(ev.x - downX, ev.y - downY) <= tapSlop
                        dragging = false
                        if (!tapped) {
                            // 拖动结束：吸附回边界
                            suppressClick = false
                            clampPan(); invalidate()
                        } else if (suppressClick) {
                            suppressClick = false          // 这一击属于双击，消费掉
                        } else if (zoom > 1.001f) {
                            resetZoom()                    // 放大态单击 = 退出放大
                        } else {
                            scheduleTap(ev.x)
                        }
                        lastUpX = ev.x; lastUpY = ev.y; lastUpTime = System.currentTimeMillis()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        suppressClick = false
                    }
                }
                return true
            }

            override fun onDraw(c: Canvas) {
                c.drawColor(dark)
                if (width <= 0 || height <= 0) return
                val scaled = zoom != 1f || panX != 0f || panY != 0f
                if (scaled) {
                    val save = c.save()
                    c.translate(panX, panY)
                    c.scale(zoom, zoom, width / 2f, height / 2f)
                    if (isSpreadMode()) drawSpread(c, !fastPreview) else drawSingle(c, !fastPreview)
                    c.restoreToCount(save)
                } else {
                    if (isSpreadMode()) drawSpread(c, !fastPreview) else drawSingle(c, !fastPreview)
                }
            }
        }
        viewer.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val d = resources.displayMetrics.density
        val frame = FrameLayout(this).apply { setBackgroundColor(dark) }
        frame.addView(viewer)
        // 页码标签：抬高到底部拖动条之上
        label.setPadding(0, 0, 0, (56 * d).toInt())
        frame.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER))

        // 右上角实体退出按钮
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            setOnClickListener { finish() }
        }
        frame.addView(closeBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))

        // 底部页面拖动条
        scrubber = Scrubber(this).apply {
            onSeek = { frac, drag ->
                resetZoom()
                val n = project.pages.size
                if (n > 1) {
                    val target = (frac * (n - 1)).toInt().coerceIn(0, n - 1)
                    if (target != pageIndex) {
                        pageIndex = target
                        fastPreview = drag
                        updateLabel()
                        viewer.invalidate()   // 拖动中低清渲染，抬手后下一帧即高清
                    } else if (!drag) {
                        // 页码未变但抬手了：仍做一次高清定稿
                        fastPreview = false
                        viewer.invalidate()
                    }
                }
            }
        }
        frame.addView(scrubber, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt(),
            Gravity.BOTTOM or Gravity.CENTER))

        setContentView(frame)

        scrubber.frac = if (project.pages.size > 1)
            pageIndex.toFloat() / (project.pages.size - 1) else 0f
        updateLabel()
        prefetchAroundDelayed()
    }

    // ---------------- 点击 / 缩放 ----------------

    // ---------------- 预取 ----------------

    private val prefetchTick = Runnable { prefetchAround() }

    private fun prefetchAroundDelayed() {
        ui.removeCallbacks(prefetchTick)
        ui.postDelayed(prefetchTick, 220)
    }

    /**
     * 把当前页与前后页的照片，按「现在这个倍数需要的尺寸」提前解码好。
     * 翻页和双击放大时就不必在 onDraw 里同步解码，卡顿感主要来自这里。
     */
    private fun prefetchAround() {
        if (!::viewer.isInitialized) return
        if (viewer.width <= 0) return
        val pageW = if (isSpreadMode()) layoutSpread().halfW else singleRect().width()
        if (pageW <= 0f) return
        val hint = pageW * zoom
        val targets = ArrayList<Int>()
        if (isSpreadMode()) {
            val list = spreads()
            val cur = currentSpread()
            val idx = list.indexOfFirst { it.first == cur.first && it.second == cur.second }
            for (k in intArrayOf(idx - 1, idx, idx + 1)) {
                list.getOrNull(k)?.let { targets.add(it.first); targets.add(it.second) }
            }
        } else {
            targets.add(pageIndex - 1)
            targets.add(pageIndex)
            targets.add(pageIndex + 1)
        }
        for (i in targets) {
            if (i < 0 || i >= project.pages.size) continue
            for (item in project.pages[i].items) {
                if (item.type != Item.TYPE_PHOTO) continue
                val imgId = item.imgId ?: continue
                val dim = (item.w * hint).toInt().coerceIn(320, 4200)
                Store.requestDecode(this, imgId, dim, src)
            }
        }
    }

    /** 单击延时执行：留够双击窗口，避免"想放大却先翻了一页"。 */
    private fun scheduleTap(x: Float) {
        pendingTap?.let { ui.removeCallbacks(it) }
        val dir = if (x < viewer.width / 2f) -1 else 1
        val r = Runnable {
            pendingTap = null
            step(dir)
        }
        pendingTap = r
        ui.postDelayed(r, tapDelayMs)
    }

    private fun toggleZoom(x: Float, y: Float) {
        if (zoom > 1.001f) { resetZoom(); return }
        val vw = viewer.width.toFloat()
        val vh = viewer.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val (z, native) = nativeZoom()
        zoom = z
        zoomIsNative = native
        // 以双击点为不动点：screen = zoom*(p - center) + center + pan
        panX = (x - vw / 2f) * (1f - z)
        panY = (y - vh / 2f) * (1f - z)
        clampPan()
        updateLabel()
        viewer.invalidate()
        prefetchAroundDelayed()
    }

    private fun resetZoom() {
        if (zoom == 1f && panX == 0f && panY == 0f) return
        zoom = 1f
        panX = 0f
        panY = 0f
        zoomIsNative = false
        updateLabel()
        viewer.invalidate()
    }

    /** 把内容框限制在屏幕内：比屏幕大则贴边滑动，比屏幕小则居中。 */
    private fun clampPan() {
        val vw = viewer.width.toFloat()
        val vh = viewer.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val b = contentRect() ?: return
        val l = vw / 2f + zoom * (b.left - vw / 2f) + panX
        val r = vw / 2f + zoom * (b.right - vw / 2f) + panX
        val t = vh / 2f + zoom * (b.top - vh / 2f) + panY
        val bt = vh / 2f + zoom * (b.bottom - vh / 2f) + panY
        panX += if (r - l <= vw) (vw - (l + r)) / 2f
        else when {
            l > 0f -> -l
            r < vw -> vw - r
            else -> 0f
        }
        panY += if (bt - t <= vh) (vh - (t + bt)) / 2f
        else when {
            t > 0f -> -t
            bt < vh -> vh - bt
            else -> 0f
        }
    }

    /**
     * 双击要放多大：让当前页面积最大的照片做到「1 图像像素 = 1 屏幕像素」。
     * 页面里没有照片（纯文字）时给 ×2，方便看排版权式细节。
     */
    private fun nativeZoom(): Pair<Float, Boolean> {
        val cands = ArrayList<Pair<Item, RectF>>()
        if (isSpreadMode()) {
            val lay = layoutSpread()
            val (li, ri) = currentSpread()
            lay.left?.let { r ->
                project.pages.getOrNull(li)?.items?.forEach {
                    if (it.type == Item.TYPE_PHOTO && it.imgId != null) cands.add(it to r)
                }
            }
            lay.right?.let { r ->
                project.pages.getOrNull(ri)?.items?.forEach {
                    if (it.type == Item.TYPE_PHOTO && it.imgId != null) cands.add(it to r)
                }
            }
        } else {
            val r = singleRect()
            project.pages.getOrNull(pageIndex)?.items?.forEach {
                if (it.type == Item.TYPE_PHOTO && it.imgId != null) cands.add(it to r)
            }
        }
        val main = cands.maxByOrNull { it.first.w * it.first.h } ?: return 2f to false
        val item = main.first
        val rect = main.second
        val imgId = item.imgId ?: return 2f to false
        val (ow, oh, _) = Store.photoMeta(this, imgId)
        if (ow <= 0 || oh <= 0 || rect.width() <= 0f || rect.height() <= 0f) return 2f to false
        val fw = item.w * rect.width()
        val fh = item.h * rect.height()
        if (fw <= 0f || fh <= 0f) return 2f to false
        // 照片 cover 铺满框后的实际绘制宽度（与 PageRenderer.drawCroppedBitmap 同口径）
        val aImg = ow.toFloat() / oh
        val aFrame = fw / fh
        val drawnW = fw * item.cropZoom * max(1f, aImg / aFrame)
        val z = ow / drawnW
        if (z < 1.02f) return 2f to false       // 屏幕显示已经超过原始像素密度
        return z.coerceAtMost(10f) to true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resetZoom()
        updateLabel()
        viewer.invalidate()
    }

    override fun onDestroy() {
        pendingTap?.let { ui.removeCallbacks(it) }
        pendingTap = null
        ui.removeCallbacksAndMessages(null)
        Store.removeDecodeListener(onDecoded)
        super.onDestroy()
    }

    private fun isSpreadMode(): Boolean =
        project.type == Project.TYPE_BOOK &&
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---------------- 分页 ----------------

    /** 与编辑器一致的书籍配对：封面单右，中间 (2,3)(4,5)…，封底单左。 */
    private fun spreads(): List<Pair<Int, Int>> {
        val n = project.pages.size
        val res = mutableListOf(-1 to 0)
        var i = 1
        while (i < n) {
            if (i == n - 1 && n % 2 == 0) { res.add(i to -1); i++ }
            else { res.add(i to (if (i + 1 < n) i + 1 else -1)); i += 2 }
        }
        return res
    }

    private fun currentSpread(): Pair<Int, Int> {
        val list = spreads()
        return list.firstOrNull { it.first == pageIndex || it.second == pageIndex }
            ?: list.last()
    }

    private fun step(dir: Int) {
        resetZoom()
        if (isSpreadMode()) {
            val list = spreads()
            val cur = currentSpread()
            val idx = list.indexOfFirst { it.first == cur.first && it.second == cur.second }
                .let { if (it < 0) 0 else it }
            val t = (idx + dir).coerceIn(0, list.size - 1)
            if (t == idx) return
            pageIndex = list[t].first.takeIf { it >= 0 } ?: list[t].second
        } else {
            val ni = (pageIndex + dir).coerceIn(0, project.pages.size - 1)
            if (ni == pageIndex) return
            pageIndex = ni
        }
        scrubber.frac = if (project.pages.size > 1) pageIndex.toFloat() / (project.pages.size - 1) else 0f
        scrubber.invalidate()
        updateLabel()
        viewer.invalidate()
        prefetchAroundDelayed()
    }

    private fun updateLabel() {
        val base = if (isSpreadMode()) {
            val (l, r) = currentSpread()
            val range = when {
                l >= 0 && r >= 0 -> "${l + 1}–${r + 1}"
                r >= 0 -> "${r + 1}"
                l >= 0 -> "${l + 1}"
                else -> "-"
            }
            "$range / ${project.pages.size}"
        } else "${pageIndex + 1} / ${project.pages.size}"
        label.text = if (zoom > 1.001f) {
            val z = if (zoomIsNative) "100%" else String.format("×%.1f", zoom)
            "$base  ·  $z"
        } else base
    }

    // ---------------- 版面几何 ----------------

    /** 单页在屏幕上的落位（与 drawSingle 完全同一套算法）。 */
    private fun singleRect(): RectF {
        val page = project.pages.getOrNull(pageIndex) ?: return RectF()
        val vw = viewer.width.toFloat()
        val vh = viewer.height.toFloat()
        val aspect = page.widthMm(project) / page.heightMm(project)
        var pw = vw * 0.92f
        var ph = pw / aspect
        if (ph > vh * 0.9f) { ph = vh * 0.9f; pw = ph * aspect }
        val l = (vw - pw) / 2f
        val t = (vh - ph) / 2f - 8f
        return RectF(l, t, l + pw, t + ph)
    }

    private class SpreadLayout(val left: RectF?, val right: RectF?, val book: RectF, val halfW: Float)

    /** 双页摊开的落位（与 drawSpread 完全同一套算法）。 */
    private fun layoutSpread(): SpreadLayout {
        val (li, ri) = currentSpread()
        val vw = viewer.width.toFloat()
        val vh = viewer.height.toFloat()
        val d = resources.displayMetrics.density
        val gutter = 1.5f * d

        val availW = vw * 0.94f - gutter
        val halfW = availW / 2f
        val maxPh = vh * 0.84f
        val cy = vh / 2f - 6f * d
        val cx = vw / 2f
        fun fit(aspect: Float): Pair<Float, Float> {
            var pw = halfW
            var ph = pw / aspect
            if (ph > maxPh) { ph = maxPh; pw = ph * aspect }
            return pw to ph
        }
        val pageL = project.pages.getOrNull(li)
        val pageR = project.pages.getOrNull(ri)
        val aspectL = (pageL?.widthMm(project) ?: project.pageWmm) /
                (pageL?.heightMm(project) ?: project.pageHmm)
        val aspectR = (pageR?.widthMm(project) ?: project.pageWmm) /
                (pageR?.heightMm(project) ?: project.pageHmm)
        val (pwL, phL) = fit(aspectL)
        val (pwR, phR) = fit(aspectR)
        val leftRect = if (li >= 0)
            RectF(cx - gutter / 2f - pwL, cy - phL / 2f, cx - gutter / 2f, cy + phL / 2f) else null
        val rightRect = if (ri >= 0)
            RectF(cx + gutter / 2f, cy - phR / 2f, cx + gutter / 2f + pwR, cy + phR / 2f) else null

        val book = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        leftRect?.let { book.union(it) }
        rightRect?.let { book.union(it) }
        if (book.left == Float.MAX_VALUE) book.set(cx, cy, cx, cy)
        return SpreadLayout(leftRect, rightRect, book, halfW)
    }

    /** 当前内容（单页或整本书）在未缩放坐标系里的外框。 */
    private fun contentRect(): RectF? = if (isSpreadMode()) layoutSpread().book
    else singleRect()

    // ---------------- 绘制 ----------------

    private fun drawSingle(c: Canvas, hq: Boolean) {
        val page = project.pages.getOrNull(pageIndex) ?: return
        val rect = singleRect()
        if (rect.width() <= 0f) return
        drawShadow(c, rect)
        // 放大时按屏幕上页面的实际像素宽度提升解码尺寸，100% 才真的是原始像素
        val hint = (rect.width() * zoom).toInt().coerceAtLeast(1)
        // 单页放映也带上跨页照片：邻页溢入本页的那一半照常补上，
        // 与横屏摊开时这一页所见一致
        PageRenderer.drawPageWithSpill(this, project, page, pageIndex, c, rect,
            highQuality = hq, pxHint = hint, source = src)
    }

    private fun drawSpread(c: Canvas, hq: Boolean) {
        val lay = layoutSpread()
        val (li, ri) = currentSpread()
        val leftRect = lay.left
        val rightRect = lay.right
        val d = resources.displayMetrics.density

        if (leftRect != null || rightRect != null) drawShadow(c, lay.book)

        // 页面底色
        li.takeIf { it >= 0 }?.let { i ->
            leftRect?.let { r ->
                project.pages.getOrNull(i)?.let { PageRenderer.drawPageBackground(project, it, c, r) }
            }
        }
        ri.takeIf { it >= 0 }?.let { i ->
            rightRect?.let { r ->
                project.pages.getOrNull(i)?.let { PageRenderer.drawPageBackground(project, it, c, r) }
            }
        }

        // 页面内容：双页合围区域内连续绘制（跨页图跨书脊不断）
        c.save()
        c.clipRect(lay.book)
        val hintL = (lay.halfW * zoom).toInt().coerceAtLeast(1)
        li.takeIf { it >= 0 }?.let { i ->
            leftRect?.let { r ->
                project.pages.getOrNull(i)?.let {
                    PageRenderer.drawItems(this, project, it, c, r, highQuality = hq,
                        pxHint = hintL, source = src)
                }
            }
        }
        ri.takeIf { it >= 0 }?.let { i ->
            rightRect?.let { r ->
                project.pages.getOrNull(i)?.let {
                    PageRenderer.drawItems(this, project, it, c, r, highQuality = hq,
                        pxHint = hintL, source = src)
                }
            }
        }
        c.restore()

        // 外缘发丝线，强调纸边
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = Math.max(1f, d * 0.6f)
        edge.color = 0x33FFFFFF
        leftRect?.let { c.drawRect(it, edge) }
        rightRect?.let { c.drawRect(it, edge) }
    }

    private fun drawShadow(c: Canvas, r: RectF) {
        val sh = Paint(Paint.ANTI_ALIAS_FLAG)
        sh.color = 0x66000000
        sh.maskFilter = android.graphics.BlurMaskFilter(26f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRect(r.left + 3f, r.top + 8f, r.right + 3f, r.bottom + 8f, sh)
    }

    /** 简易可拖动条：细轨道 + 圆形滑块，拖动即时跳页。 */
    private class Scrubber(ctx: android.content.Context) : View(ctx) {
        var onSeek: ((Float, Boolean) -> Unit)? = null   // (frac, dragging)
        var frac = 0f          // 0..1

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val pad = 24f * d
            val cy = height / 2f
            val w = width - pad * 2f
            // 轨道
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = 0x33FFFFFF
            tp.strokeCap = Paint.Cap.ROUND
            tp.strokeWidth = 2f * d
            c.drawLine(pad, cy, pad + w, cy, tp)
            // 滑块
            val th = Paint(Paint.ANTI_ALIAS_FLAG)
            th.color = 0xE6FFFFFF.toInt()
            val x = pad + w * frac
            c.drawCircle(x, cy, 7f * d, th)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val d = resources.displayMetrics.density
            val pad = 24f * d
            val w = width - pad * 2f
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    frac = ((ev.x - pad) / w).coerceIn(0f, 1f)
                    onSeek?.invoke(frac, true)      // 拖动中：低清预览
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onSeek?.invoke(frac, false)     // 抬手：高清定稿
                    invalidate()
                    return true
                }
            }
            return true
        }
    }
}

package com.yussy.folior

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yussy.folior.model.*
import com.yussy.folior.model.Project
import com.yussy.folior.store.Store
import com.yussy.folior.ui.PageRenderer
import kotlin.math.abs

/**
 * 全屏放映。
 * - 摄影书 + 横屏：以书籍形式摊开，左右两页并排；封面单右、封底单左，跨页图连续
 * - 其余情况：单页放映
 * 左右滑动翻页/翻跨页；右上角 ✕ 退出；底部拖动条快速定位页面。
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var project: Project
    private var pageIndex = 0
    private lateinit var label: TextView
    private lateinit var viewer: View
    private lateinit var scrubber: Scrubber
    /** 拖动条拖动中：低清快速渲染（照片小尺寸解码）；抬手后高清定稿一次 */
    private var fastPreview = false

    private val dark = 0xFF14140F.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: finish().let { return }
        project = Store.listProjects(this).find { it.id == id } ?: finish().let { return }
        if (project.pages.isEmpty()) { finish(); return }
        // 进入放映默认定位到编辑器当前页
        pageIndex = (intent.getIntExtra("page", 0)).coerceIn(0, project.pages.size - 1)

        label = TextView(this).apply {
            textSize = 12.5f
            letterSpacing = 0.08f
            setTextColor(0x99FFFFFF.toInt())
            val d = resources.displayMetrics.density
            setPadding(0, 0, 0, (16 * d).toInt())
            gravity = Gravity.CENTER
        }

        viewer = object : View(this) {
            private var downX = 0f
            private var downY = 0f

            @Suppress("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                    MotionEvent.ACTION_UP -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (abs(dx) > 70f * resources.displayMetrics.density && abs(dx) > abs(dy)) {
                            step(if (dx < 0) 1 else -1)
                        }
                    }
                }
                return true
            }

            override fun onDraw(c: Canvas) {
                c.drawColor(dark)
                if (isSpreadMode()) drawSpread(c, !fastPreview) else drawSingle(c, !fastPreview)
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
            onSeek = { frac, dragging ->
                val n = project.pages.size
                if (n > 1) {
                    val target = (frac * (n - 1)).toInt().coerceIn(0, n - 1)
                    if (target != pageIndex) {
                        pageIndex = target
                        fastPreview = dragging
                        updateLabel()
                        viewer.invalidate()   // 拖动中低清渲染，抬手后下一帧即高清
                    } else if (!dragging) {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLabel()
        viewer.invalidate()
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
    }

    private fun updateLabel() {
        label.text = if (isSpreadMode()) {
            val (l, r) = currentSpread()
            val range = when {
                l >= 0 && r >= 0 -> "${l + 1}–${r + 1}"
                r >= 0 -> "${r + 1}"
                l >= 0 -> "${l + 1}"
                else -> "-"
            }
            "$range / ${project.pages.size}"
        } else "${pageIndex + 1} / ${project.pages.size}"
    }

    // ---------------- 绘制 ----------------

    private fun drawSingle(c: Canvas, hq: Boolean) {
        val page = project.pages.getOrNull(pageIndex) ?: return
        val vw = viewer.width.toFloat(); val vh = viewer.height.toFloat()
        val aspect = page.widthMm(project) / page.heightMm(project)
        var pw = vw * 0.92f
        var ph = pw / aspect
        if (ph > vh * 0.9f) { ph = vh * 0.9f; pw = ph * aspect }
        val l = (vw - pw) / 2f
        val t = (vh - ph) / 2f - 8f
        drawShadow(c, RectF(l, t, l + pw, t + ph))
        PageRenderer.drawPage(this, project, page, c, l, t, pw, ph, highQuality = hq)
    }

    private fun drawSpread(c: Canvas, hq: Boolean) {
        val (li, ri) = currentSpread()
        val vw = viewer.width.toFloat(); val vh = viewer.height.toFloat()
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

        // 整本书的投影
        val book = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        leftRect?.let { book.union(it) }
        rightRect?.let { book.union(it) }
        if (book.left != Float.MAX_VALUE) drawShadow(c, book)

        // 页面底色
        li.takeIf { it >= 0 }?.let { i ->
            project.pages.getOrNull(i)?.let { PageRenderer.drawPageBackground(project, it, c, leftRect!!) }
        }
        ri.takeIf { it >= 0 }?.let { i ->
            project.pages.getOrNull(i)?.let { PageRenderer.drawPageBackground(project, it, c, rightRect!!) }
        }

        // 页面内容：双页合围区域内连续绘制（跨页图跨书脊不断）
        c.save()
        c.clipRect(book)
        li.takeIf { it >= 0 }?.let { i ->
            project.pages.getOrNull(i)?.let {
                PageRenderer.drawItems(this, project, it, c, leftRect!!, highQuality = hq)
            }
        }
        ri.takeIf { it >= 0 }?.let { i ->
            project.pages.getOrNull(i)?.let {
                PageRenderer.drawItems(this, project, it, c, rightRect!!, highQuality = hq)
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
}

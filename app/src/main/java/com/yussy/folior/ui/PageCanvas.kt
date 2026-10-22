package com.yussy.folior.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.yussy.folior.model.*
import kotlin.math.*

/**
 * 页面编辑画布（支持单页 / 跨页两种模式）。
 *
 * 手势：
 * - 单指点按：选中 / 取消选中
 * - 单指拖动：移动选中项（中线/三分线吸附）
 * - 双指：缩放 + 旋转（自动吸附 0/90/180/270）
 * - 长按拖动：调整同页内层级顺序
 * - 双击照片：裁切
 * - 空白处左右滑动：翻跨页
 *
 * 跨页模式（横屏摄影书）：左页/右页并排显示，元素坐标可越出本页边界实现跨页展示。
 */
@SuppressLint("ViewConstructor")
class PageCanvas @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var project: Project? = null

    /** 跨页模式开关（横屏 + 摄影书，由 Activity 控制） */
    var spreadMode = false

    var leftIndex = -1
    var rightIndex = 0
    var activeIndex = 0

    var onSelectionChanged: ((Item?) -> Unit)? = null
    var onModified: (() -> Unit)? = null
    var onOpenCrop: ((Item) -> Unit)? = null
    var onFlip: ((Int) -> Unit)? = null          // -1 上一跨页 / +1 下一跨页
    var onSpanResult: ((Boolean) -> Unit)? = null
    /** 双击文字：请求编辑（Activity 打开文字对话框） */
    var onEditText: ((Item) -> Unit)? = null

    /** 元素拖动时查询预览栏目标页（raw 坐标），返回页索引或 -1 */
    var onDragTargetAt: ((rawX: Float, rawY: Float) -> Int)? = null
    /** 高亮预览栏目标格（-1 清除） */
    var onStripHighlight: ((Int) -> Unit)? = null
    /** 元素拖放到目标页/墙 */
    var onItemDropToPage: ((item: Item, pageIdx: Int) -> Unit)? = null
    private var dropTargetIdx = -1

    private data class Slot(val index: Int, val rect: RectF)
    private val slots = mutableListOf<Slot>()

    var selected: Item? = null
        private set

    private var dragging = false
    private var resizing = false
    private var reordering = false
    private var reorderIndex = -1
    private val lastTouch = PointF()

    // 双指
    private var multiInitialDist = 0f
    private var multiInitialAngle = 0f
    private var multiInitialW = 0f
    private var multiInitialH = 0f
    private var multiInitialX = 0f
    private var multiInitialY = 0f
    private var multiInitialPrintMm = 0f
    private var multiInitialRot = 0f
    private var multiActive = false

    // 吸附
    private var guideV = -1f
    private var guideH = -1f

    // 裁切
    var cropItem: Item? = null
        private set
    private var cropBmp: Bitmap? = null
    private var cropRect = RectF()
    private var cropTempZoom = 1f
    private var cropTempCx = 0.5f
    private var cropTempCy = 0.5f
    private var cropLastX = 0f
    private var cropLastY = 0f
    private var cropPinchDist = 0f
    var onCropChanged: (() -> Unit)? = null

    // 空白滑动翻页
    private var emptyDownX = 0f
    private var emptyDownY = 0f
    private var hasItemAtDown = false

    // ---------------- 工作区视图变换（双指缩放/平移，查看局部细节） ----------------

    private var viewScale = 1f
    private var viewX = 0f
    private var viewY = 0f
    private var zooming = false
    private var zoomInitDist = 0f
    private var zoomInitScale = 1f
    private var zoomInitMidX = 0f
    private var zoomInitMidY = 0f
    private var zoomInitViewX = 0f
    private var zoomInitViewY = 0f

    /** 复位视图（=1 倍、居中）。 */
    fun resetView() {
        if (viewScale == 1f && viewX == 0f && viewY == 0f) return
        viewScale = 1f; viewX = 0f; viewY = 0f
        invalidate()
    }

    /** 把事件坐标从视图系换算到内容系（返回原事件或变换副本）。 */
    private fun contentEvent(ev: MotionEvent): MotionEvent {
        if (viewScale == 1f && viewX == 0f && viewY == 0f) return ev
        val m = Matrix()
        m.setScale(1f / viewScale, 1f / viewScale)
        m.postTranslate(-viewX / viewScale, -viewY / viewScale)
        return MotionEvent.obtain(ev).apply { transform(m) }
    }

    /** 平移边界：内容始终保持覆盖视口大部分区域。 */
    private fun clampView() {
        if (viewScale <= 1f) { viewX = 0f; viewY = 0f; return }
        val slackX = width * 0.15f
        val slackY = height * 0.15f
        viewX = viewX.coerceIn(-(width * (viewScale - 1f)) - slackX, slackX)
        viewY = viewY.coerceIn(-(height * (viewScale - 1f)) - slackY, slackY)
    }

    private fun midX(ev: MotionEvent) = (ev.getX(0) + ev.getX(1)) / 2f
    private fun midY(ev: MotionEvent) = (ev.getY(0) + ev.getY(1)) / 2f

    // ---------------- 绘制模式（S Pen / 手指） ----------------

    class DrawBrush {
        var width = 0.011f          // 线宽（页高比例），默认=中笔
        var color = "#1A1A18"
        var alpha = 1f
        var marker = false
    }

    var drawMode = false
    var eraserMode = false
    val brush = DrawBrush()

    private var drawing = false
    private var drawPtrId = -1
    private var drawIsStylus = false
    private var drawSlot: Slot? = null
    private val liveRaw = mutableListOf<Float>()   // 页归一化坐标 x,y,p
    private var liveStroke: Item? = null

    class UndoOp(val add: Boolean, val page: Page, val item: Item)
    private val undoStack = ArrayDeque<UndoOp>()

    /** 撤销最近一次绘制/擦除，最多回溯 20 步。 */
    fun undoDraw(): Boolean {
        if (undoStack.isEmpty()) return false
        val op = undoStack.removeLast()
        if (op.add) op.page.items.remove(op.item)
        else op.page.items.add(op.item)
        onModified?.invoke()
        invalidate()
        return true
    }

    fun clearDrawUndo() { undoStack.clear() }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (cropItem != null) return true
            select(hitItem(e.x, e.y))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (cropItem != null) return true
            val it = hitItem(e.x, e.y)
            if (it == null) { resetView(); return true }   // 双击空白：复位缩放
            if (it.type == Item.TYPE_PHOTO && it.imgId != null) onOpenCrop?.invoke(it)
            if (it.type == Item.TYPE_TEXT) onEditText?.invoke(it)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (cropItem != null) return
            val it = hitItem(e.x, e.y) ?: return
            val owner = pageOf(it) ?: return
            val idx = owner.items.indexOf(it)
            if (idx >= 0) {
                reordering = true
                reorderIndex = idx
                selected = it
                onSelectionChanged?.invoke(it)
                invalidate()
            }
        }
    })

    // ---------------- 页与槽位 ----------------

    fun currentPage(): Page? = project?.pages?.getOrNull(activeIndex)

    fun pageOf(item: Item): Page? =
        project?.pages?.firstOrNull { pg -> pg.items.any { it === item } }

    private fun slotOf(item: Item): Slot? {
        val owner = pageOf(item) ?: return null
        return slots.firstOrNull { it.index >= 0 && project?.pages?.getOrNull(it.index) === owner }
    }

    fun select(item: Item?) {
        selected = item
        if (item != null) {
            val owner = pageOf(item)
            val idx = if (owner == null) -1 else project?.pages?.indexOfFirst { it === owner } ?: -1
            if (idx >= 0) activeIndex = idx
        }
        onSelectionChanged?.invoke(item)
        invalidate()
    }

    fun exitCrop(apply: Boolean) {
        val it = cropItem ?: return
        if (apply) {
            it.cropZoom = cropTempZoom
            it.cropCx = cropTempCx
            it.cropCy = cropTempCy
            onModified?.invoke()
        }
        cropItem = null
        cropBmp = null
        invalidate()
    }

    fun enterCrop(item: Item) {
        resetView()   // 裁切在 1 倍视图下进行
        cropItem = item
        cropRect.setEmpty()
        cropBmp = item.imgId?.let { com.yussy.folior.store.Store.decodeFull(context, it, 2048) }
        cropTempZoom = item.cropZoom
        cropTempCx = item.cropCx
        cropTempCy = item.cropCy
        selected = null
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSlots()
    }

    /** 重新计算页位（尺寸/方向/跨页模式变化时调用） */
    fun invalidateLayout() {
        cropRect.setEmpty()
        resetView()   // 方向/模式变化时复位缩放
        layoutSlots()
        invalidate()
    }

    private fun layoutSlots() {
        val p = project ?: return
        if (width == 0 || height == 0) return
        slots.clear()
        val vw = width.toFloat(); val vh = height.toFloat()
        val d = resources.displayMetrics.density
        val margin = 22f * d

        // 每页可有独立尺寸（自定义页 / 墙体）
        fun aspectOf(idx: Int): Float {
            val pg = p.pages.getOrNull(idx)
            return (pg?.wmm ?: p.pageWmm) / (pg?.hmm ?: p.pageHmm)
        }

        if (spreadMode) {
            val gutter = 3f * d
            val halfW = (vw - margin * 2 - gutter) / 2f
            val maxPh = vh - margin * 2
            val cy = vh / 2f
            fun fit(aspect: Float): Pair<Float, Float> {
                var pw = halfW
                var ph = pw / aspect
                if (ph > maxPh) { ph = maxPh; pw = ph * aspect }
                return pw to ph
            }
            if (leftIndex in 0 until p.pages.size) {
                val (pw, ph) = fit(aspectOf(leftIndex))
                slots.add(Slot(leftIndex, RectF(
                    vw / 2f - gutter / 2f - pw, cy - ph / 2f,
                    vw / 2f - gutter / 2f, cy + ph / 2f)))
            }
            if (rightIndex in 0 until p.pages.size) {
                val (pw, ph) = fit(aspectOf(rightIndex))
                slots.add(Slot(rightIndex, RectF(
                    vw / 2f + gutter / 2f, cy - ph / 2f,
                    vw / 2f + gutter / 2f + pw, cy + ph / 2f)))
            }
        } else {
            val aspect = aspectOf(activeIndex)
            var pw = vw - margin * 2
            var ph = pw / aspect
            if (ph > vh - margin * 2) { ph = vh - margin * 2; pw = ph * aspect }
            slots.add(Slot(activeIndex, RectF(
                (vw - pw) / 2f, (vh - ph) / 2f,
                (vw + pw) / 2f, (vh + ph) / 2f)))
        }
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        val p = project ?: return
        if (slots.isEmpty()) layoutSlots()
        canvas.drawColor(0xFFF2F2EE.toInt())
        // 工作区视图变换：缩放/平移下查看书页与墙体的局部细节
        canvas.save()
        canvas.translate(viewX, viewY)
        canvas.scale(viewScale, viewScale)

        // 页面底色 + 投影
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        shadow.color = 0x22000000
        shadow.maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
        for (s in slots) {
            val page = p.pages.getOrNull(s.index) ?: continue
            canvas.drawRect(s.rect.left + 3f, s.rect.top + 6f,
                s.rect.right + 3f, s.rect.bottom + 6f, shadow)
            PageRenderer.drawPageBackground(p, page, canvas, s.rect)
        }

        if (slots.isNotEmpty()) {
            val union = RectF(slots[0].rect)
            slots.forEach { union.union(it.rect) }

            // 墙外延伸预览（默认开启）：把超出页面的画面以半透明"幽灵"画在墙面之外，
            // 便于判断被裁掉的内容；墙面内仍按正常不透明度渲染。
            val hasOverflow = slots.any { s ->
                p.pages.getOrNull(s.index)?.items?.any {
                    it.x < -0.001f || it.y < -0.001f ||
                        it.x + it.w > 1.001f || it.y + it.h > 1.001f
                } == true
            }
            if (hasOverflow && cropItem == null) {
                val ghostArea = RectF(union)
                val mx = union.width() * 0.45f
                val my = union.height() * 0.45f
                ghostArea.set(union.left - mx, union.top - my, union.right + mx, union.bottom + my)
                val layer = canvas.saveLayerAlpha(null, 56)   // ≈22% 不透明度
                slots.forEach { canvas.clipOutRect(it.rect) } // 只画在墙面之外
                canvas.clipRect(ghostArea)
                for (s in slots) {
                    val pg = p.pages.getOrNull(s.index) ?: continue
                    PageRenderer.drawItems(context, p, pg, canvas, s.rect)
                }
                canvas.restoreToCount(layer)
            }

            // 内容：裁切到"双页合围"区域，墙面内正常渲染
            canvas.save()
            canvas.clipRect(union)
            for (s in slots) {
                val page = p.pages.getOrNull(s.index) ?: continue
                PageRenderer.drawItems(context, p, page, canvas, s.rect)
            }
            canvas.restore()
        }

        // 绘制模式：落笔中的实时笔画预览（抬笔后成为正式元素）
        liveStroke?.let { st ->
            drawSlot?.let { s ->
                canvas.save()
                canvas.clipRect(s.rect)
                PageRenderer.drawItem(context, st, canvas,
                    s.rect.left, s.rect.top, s.rect.width(), s.rect.height(), false)
                canvas.restore()
            }
        }

        if (cropItem != null) { canvas.restore(); drawCropMode(canvas); return }

        // 展览：工作区边缘实际尺寸标尺（单位：米）
        if (p.type == Project.TYPE_EXHIBITION && slots.isNotEmpty()) {
            val dd = resources.displayMetrics.density
            val rp = Paint(Paint.ANTI_ALIAS_FLAG)
            rp.color = 0x736B6B66.toInt()
            rp.strokeWidth = 1f
            // 长度刻度文字：左对齐（贴在刻度右侧）
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = 0xFF8A8A84.toInt()
            tp.textSize = 9f * dd
            // 高度刻度文字：右对齐（贴在刻度左侧，避免压到墙面）
            val tpV = Paint(Paint.ANTI_ALIAS_FLAG)
            tpV.color = 0xFF8A8A84.toInt()
            tpV.textSize = 9f * dd
            tpV.textAlign = Paint.Align.RIGHT

            for (sl in slots) {
                val pg = p.pages.getOrNull(sl.index) ?: continue
                val ww = pg.widthMm(p); val wh = pg.heightMm(p)
                val pxmm = sl.rect.width() / ww
                val pymm = sl.rect.height() / wh
                val stepX = if (ww > 8000) 500f else 100f
                val stepY = if (wh > 8000) 500f else 100f

                // 长度：沿底边，原点在左下角，向右递增
                var mm = 0f
                while (mm <= ww + 0.5f) {
                    val x = sl.rect.left + mm * pxmm
                    val major = (mm.toInt() % 1000) == 0
                    val len = if (major) 9f * dd else if ((mm.toInt() % 500) == 0) 6f * dd else 3f * dd
                    canvas.drawLine(x, sl.rect.bottom, x, sl.rect.bottom + len, rp)
                    if (major && mm > 0) canvas.drawText(
                        "${(mm / 1000).toInt()}", x + 3f, sl.rect.bottom + 10f * dd, tp)
                    mm += stepX
                }

                // 高度：沿左边，原点在左下角，向上递增
                var mv = 0f
                while (mv <= wh + 0.5f) {
                    val y = sl.rect.bottom - mv * pymm
                    val major = (mv.toInt() % 1000) == 0
                    val len = if (major) 9f * dd else if ((mv.toInt() % 500) == 0) 6f * dd else 3f * dd
                    canvas.drawLine(sl.rect.left - len, y, sl.rect.left, y, rp)
                    if (major && mv > 0) canvas.drawText(
                        "${(mv / 1000).toInt()}", sl.rect.left - 4f * dd, y + 3f * dd, tpV)
                    mv += stepY
                }

                // 单位：标注在原点（左下角）
                canvas.drawText("m", sl.rect.left - 4f * dd, sl.rect.bottom + 10f * dd, tpV)
            }
        }

        // 书脊
        if (spreadMode && slots.size == 2) {
            val gx = (slots[0].rect.right + slots[1].rect.left) / 2f
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            gp.color = 0x14000000
            gp.strokeWidth = 2f
            canvas.drawLine(gx, slots[0].rect.top, gx, slots[0].rect.bottom, gp)
        }

        // 吸附参考线
        val sel = selected
        if (sel != null && (guideV >= 0 || guideH >= 0)) {
            val slot = slotOf(sel)
            if (slot != null) {
                val gp = Paint(Paint.ANTI_ALIAS_FLAG)
                gp.color = 0x80B5442A.toInt()
                gp.strokeWidth = 1.5f
                gp.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                if (guideV >= 0) {
                    val x = slot.rect.left + guideV * slot.rect.width()
                    canvas.drawLine(x, slot.rect.top, x, slot.rect.bottom, gp)
                }
                if (guideH >= 0) {
                    val y = slot.rect.top + guideH * slot.rect.height()
                    canvas.drawLine(slot.rect.left, y, slot.rect.right, y, gp)
                }
            }
        }
        if (sel != null) drawSelection(canvas, sel)
        canvas.restore()

        // 缩放指示（视图系，不随内容变换）
        if (viewScale > 1.01f) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = 0x996B6B66.toInt()
            tp.textSize = 10f * resources.displayMetrics.density
            tp.textAlign = Paint.Align.CENTER
            canvas.drawText("×${"%.1f".format(viewScale)} · 双击空白复位",
                width / 2f, height - 8f * resources.displayMetrics.density, tp)
        }
    }

    private fun drawSelection(canvas: Canvas, it: Item) {
        val slot = slotOf(it) ?: return
        val cx = slot.rect.left + (it.x + it.w / 2f) * slot.rect.width()
        val cy = slot.rect.top + (it.y + it.h / 2f) * slot.rect.height()
        val fw = it.w * slot.rect.width()
        val fh = it.h * slot.rect.height()
        canvas.save()
        canvas.rotate(it.rotation, cx, cy)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        sp.style = Paint.Style.STROKE
        sp.color = 0xFFB5442A.toInt()
        sp.strokeWidth = 2f
        canvas.drawRect(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f, sp)
        sp.style = Paint.Style.FILL
        canvas.drawCircle(cx - fw / 2f, cy - fh / 2f, 6f, sp)
        // 右下角：拖拽调节大小手柄（白底红圈，更醒目）
        val d = resources.displayMetrics.density
        val hx = cx + fw / 2f; val hy = cy + fh / 2f
        sp.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(hx, hy, 11f * d, sp)
        sp.style = Paint.Style.STROKE
        sp.strokeWidth = 2.5f
        sp.color = 0xFFB5442A.toInt()
        canvas.drawCircle(hx, hy, 11f * d, sp)
        sp.style = Paint.Style.FILL
        canvas.restore()
        if (reordering) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = 0xFFB5442A.toInt()
            tp.textSize = 30f
            tp.textAlign = Paint.Align.CENTER
            canvas.drawText("拖动调整层级", width / 2f, 40f, tp)
        }
    }

    private fun drawCropMode(canvas: Canvas) {
        val it = cropItem ?: return
        val bmp = cropBmp ?: return
        canvas.drawColor(0xE6111110.toInt())
        if (cropRect.isEmpty) {
            val aspect = it.w / it.h
            var w = width * 0.8f
            var h = w / aspect
            if (h > height * 0.66f) { h = height * 0.66f; w = h * aspect }
            cropRect = RectF((width - w) / 2f, (height - h) / 2f - 30f,
                (width + w) / 2f, (height + h) / 2f - 30f)
        }
        PageRenderer.drawCroppedBitmap(canvas, bmp,
            Item(cropZoom = cropTempZoom, cropCx = cropTempCx, cropCy = cropTempCy),
            cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.color = 0x60FFFFFF.toInt()
        gp.strokeWidth = 1f
        for (i in 1..2) {
            canvas.drawLine(cropRect.left + cropRect.width() * i / 3f, cropRect.top,
                cropRect.left + cropRect.width() * i / 3f, cropRect.bottom, gp)
            canvas.drawLine(cropRect.left, cropRect.top + cropRect.height() * i / 3f,
                cropRect.right, cropRect.top + cropRect.height() * i / 3f, gp)
        }
        val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        bp.style = Paint.Style.STROKE
        bp.color = 0xFFFFFFFF.toInt()
        bp.strokeWidth = 2.5f
        canvas.drawRect(cropRect, bp)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = 0xCCFFFFFF.toInt()
        tp.textSize = 28f
        tp.textAlign = Paint.Align.CENTER
        canvas.drawText("单指移动 · 双指缩放 · 双击完成", width / 2f, cropRect.bottom + 52f, tp)
    }

    // ---------------- 触摸 ----------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (cropItem != null) return cropTouch(ev)
        val rawX = ev.rawX
        val rawY = ev.rawY
        val ce = contentEvent(ev)
        val r = when {
            drawMode -> drawTouch(ce)
            else -> editTouch(ce, rawX, rawY)
        }
        if (ce !== ev) ce.recycle()
        return r
    }

    // ---------------- 绘制触摸 ----------------

    /**
     * S Pen 适配：
     * - 压感笔锋：TOOL_TYPE_STYLUS 时按 getPressure() 调制线宽；手指恒定线宽
     * - 笔落下自动接管：绘画中笔尖落下会取消手指未完成的笔画，避免混线
     * - 防误触：仅跟踪落笔那根指针，手掌/其他手指落下与移动全部忽略
     */
    private fun drawTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(ev)   // 降低笔迹延迟
                val i = ev.actionIndex
                if (eraserMode) {
                    eraseAt(ev.getX(i), ev.getY(i))
                    return true
                }
                startDrawPointer(ev, i)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (eraserMode) return true
                val i = ev.actionIndex
                if (ev.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS && !drawIsStylus) {
                    // S Pen 接管：手指未完成的笔画作废，笔迹重新开始
                    endStroke(false)
                    startDrawPointer(ev, i)
                }
                // 其余新指针（手掌等）一律忽略
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (eraserMode) {
                    for (i in 0 until ev.pointerCount) eraseAt(ev.getX(i), ev.getY(i))
                    return true
                }
                val i = if (drawPtrId >= 0) ev.findPointerIndex(drawPtrId) else -1
                if (drawing && i >= 0)
                    extendStroke(ev.getX(i), ev.getY(i), pressureOf(ev, i))
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (eraserMode) return true
                val i = ev.actionIndex
                if (ev.getPointerId(i) == drawPtrId) endStroke(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (eraserMode) return true
                if (ev.getPointerId(ev.actionIndex) == drawPtrId) endStroke(true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> endStroke(false)
        }
        return true
    }

    private fun startDrawPointer(ev: MotionEvent, i: Int) {
        drawPtrId = ev.getPointerId(i)
        drawIsStylus = ev.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS
        beginStroke(ev.getX(i), ev.getY(i), pressureOf(ev, i))
    }

    private fun pressureOf(ev: MotionEvent, i: Int): Float =
        if (drawIsStylus) ev.getPressure(i).coerceIn(0.05f, 1f) else 1f

    private fun slotAt(vx: Float, vy: Float): Slot? =
        slots.firstOrNull { it.rect.contains(vx, vy) }
            ?: slots.minByOrNull {
                val dx = max(0f, max(it.rect.left - vx, vx - it.rect.right))
                val dy = max(0f, max(it.rect.top - vy, vy - it.rect.bottom))
                dx + dy
            }

    private fun beginStroke(vx: Float, vy: Float, pressure: Float) {
        val slot = slotAt(vx, vy) ?: return
        drawSlot = slot
        liveRaw.clear()
        liveRaw.add((vx - slot.rect.left) / slot.rect.width())
        liveRaw.add((vy - slot.rect.top) / slot.rect.height())
        liveRaw.add(pressure)
        drawing = true
        rebuildLive()
        invalidate()
    }

    private fun extendStroke(vx: Float, vy: Float, pressure: Float) {
        val slot = drawSlot ?: return
        liveRaw.add((vx - slot.rect.left) / slot.rect.width())
        liveRaw.add((vy - slot.rect.top) / slot.rect.height())
        liveRaw.add(pressure)
        rebuildLive()
        invalidate()
    }

    /** 由原始点列重建实时笔画（边界框 + 框内相对坐标），抬笔即原样提交。 */
    private fun rebuildLive() {
        if (liveRaw.size < 3) return
        val slot = drawSlot ?: return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var k = 0
        while (k < liveRaw.size) {
            if (liveRaw[k] < minX) minX = liveRaw[k]
            if (liveRaw[k] > maxX) maxX = liveRaw[k]
            if (liveRaw[k + 1] < minY) minY = liveRaw[k + 1]
            if (liveRaw[k + 1] > maxY) maxY = liveRaw[k + 1]
            k += 3
        }
        // 半线宽外扩（含圆头），x 按页面宽高比换算归一化
        val aspect = slot.rect.width() / slot.rect.height()
        val padY = brush.width * 0.7f
        val padX = padY / aspect
        val x0 = minX - padX; val y0 = minY - padY
        val bw = (maxX - minX) + padX * 2f
        val bh = (maxY - minY) + padY * 2f
        val rel = mutableListOf<Float>()
        var j = 0
        while (j < liveRaw.size) {
            rel.add((liveRaw[j] - x0) / bw)
            rel.add((liveRaw[j + 1] - y0) / bh)
            rel.add(liveRaw[j + 2])
            j += 3
        }
        liveStroke = Item(
            type = Item.TYPE_STROKE,
            x = x0, y = y0, w = bw, h = bh,
            strokeColor = brush.color, strokeW = brush.width,
            strokeAlpha = brush.alpha, strokeMarker = brush.marker,
            pts = rel
        )
    }

    private fun endStroke(commit: Boolean) {
        val st = liveStroke
        drawing = false
        liveStroke = null
        liveRaw.clear()
        val slot = drawSlot
        drawSlot = null
        if (!commit || st == null || slot == null) { invalidate(); return }
        val pg = project?.pages?.getOrNull(slot.index) ?: return
        pg.items.add(st)
        undoStack.add(UndoOp(true, pg, st))
        if (undoStack.size > 20) undoStack.removeFirst()
        onModified?.invoke()
        invalidate()
    }

    /** 对象级橡皮：命中任一可见页上的笔画即整条删除。 */
    private fun eraseAt(vx: Float, vy: Float) {
        val p = project ?: return
        for (slot in slots) {
            val pg = p.pages.getOrNull(slot.index) ?: continue
            val idx = pg.items.indexOfLast {
                it.type == Item.TYPE_STROKE && strokeHit(it, slot, vx, vy)
            }
            if (idx >= 0) {
                val item = pg.items.removeAt(idx)
                undoStack.add(UndoOp(false, pg, item))
                if (undoStack.size > 20) undoStack.removeFirst()
                onModified?.invoke()
                invalidate()
                return
            }
        }
    }

    private fun strokeHit(it: Item, slot: Slot, vx: Float, vy: Float): Boolean {
        val r = slot.rect
        val d = resources.displayMetrics.density
        val thresh = it.strokeW * r.height() * 0.6f + 10f * d
        var ax = 0f; var ay = 0f
        var count = 0
        var best = Float.MAX_VALUE
        var k = 0
        while (k + 2 < it.pts.size) {
            val x = r.left + (it.x + it.pts[k] * it.w) * r.width()
            val y = r.top + (it.y + it.pts[k + 1] * it.h) * r.height()
            if (count > 0) best = min(best, distToSeg(vx, vy, ax, ay, x, y))
            ax = x; ay = y; count++
            k += 3
        }
        if (count == 1) best = hypot(vx - ax, vy - ay)
        return best <= thresh
    }

    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-8f) 0f
        else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun cropTouch(ev: MotionEvent): Boolean {
        val it = cropItem ?: return false
        val bmp = cropBmp ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) cropPinchDist = dist(ev)
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    val d = dist(ev)
                    if (cropPinchDist > 0) {
                        cropTempZoom = (cropTempZoom * (d / cropPinchDist)).coerceIn(1f, 12f)
                        it.cropZoom = cropTempZoom
                        clampCrop(it, bmp)
                        invalidate(); onCropChanged?.invoke()
                    }
                    cropPinchDist = d
                } else {
                    val base = Math.max(cropRect.width() / bmp.width, cropRect.height() / bmp.height) * it.cropZoom
                    cropTempCx -= (ev.x - cropLastX) / (bmp.width * base)
                    cropTempCy -= (ev.y - cropLastY) / (bmp.height * base)
                    it.cropZoom = cropTempZoom
                    clampCrop(it, bmp)
                    invalidate(); onCropChanged?.invoke()
                    cropLastX = ev.x; cropLastY = ev.y
                }
            }
            MotionEvent.ACTION_DOWN -> { cropLastX = ev.x; cropLastY = ev.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cropPinchDist = 0f
        }
        return true
    }

    private fun clampCrop(it: Item, bmp: Bitmap) {
        val baseScale = Math.max(cropRect.width() / bmp.width, cropRect.height() / bmp.height)
        val coverWn = 1f / it.cropZoom
        val coverHn = (baseScale * bmp.height / cropRect.height()) / it.cropZoom
        cropTempCx = cropTempCx.coerceIn(coverWn / 2f, 1f - coverWn / 2f)
        cropTempCy = cropTempCy.coerceIn(coverHn / 2f, 1f - coverHn / 2f)
        it.cropCx = cropTempCx; it.cropCy = cropTempCy
    }

    private fun editTouch(ev: MotionEvent, rawX: Float, rawY: Float): Boolean {
        gesture.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selected != null && hitResizeHandle(ev.x, ev.y)) {
                    resizing = true
                    lastTouch.set(ev.x, ev.y)
                    return true
                }
                lastTouch.set(ev.x, ev.y)
                emptyDownX = ev.x; emptyDownY = ev.y
                val hit = hitItem(ev.x, ev.y)
                hasItemAtDown = hit != null
                dragging = hit != null && hit === selected
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && selected != null) {
                    val it = selected!!
                    if (slotOf(it) != null) {
                        multiActive = true
                        multiInitialDist = dist(ev)
                        multiInitialAngle = angle(ev)
                        multiInitialW = it.w; multiInitialH = it.h
                        multiInitialX = it.x; multiInitialY = it.y
                        multiInitialPrintMm = it.printMm ?: 0f
                        multiInitialRot = it.rotation
                        dragging = false
                    }
                } else if (ev.pointerCount == 2 && selected == null && !reordering && !zooming) {
                    // 未选中任何内容：双指 = 工作区缩放/平移
                    zooming = true
                    zoomInitDist = dist(ev).coerceAtLeast(1f)
                    zoomInitScale = viewScale
                    zoomInitMidX = midX(ev); zoomInitMidY = midY(ev)
                    zoomInitViewX = viewX; zoomInitViewY = viewY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (zooming && ev.pointerCount >= 2) {
                    // 锚点缩放：双指中点对应的内容位置在变换前后保持不动
                    val s = (zoomInitScale * dist(ev) / zoomInitDist).coerceIn(1f, 8f)
                    val cX = (zoomInitMidX - zoomInitViewX) / zoomInitScale
                    val cY = (zoomInitMidY - zoomInitViewY) / zoomInitScale
                    viewScale = s
                    viewX = midX(ev) - cX * s
                    viewY = midY(ev) - cY * s
                    clampView()
                    invalidate()
                    return true
                }
                if (resizing) { handleResizeMove(ev); return true }
                if (reordering) {
                    handleReorderMove(ev)
                    updateDropTarget(rawX, rawY)
                    lastTouch.set(ev.x, ev.y); return true
                }
                if (multiActive && ev.pointerCount >= 2 && selected != null) {
                    val it = selected!!
                    val scale = dist(ev) / multiInitialDist
                    val deltaAngle = Math.toDegrees((angle(ev) - multiInitialAngle).toDouble()).toFloat()
                    // 以图像中心为锚点缩放：保持中心不动
                    val cx0 = multiInitialX + multiInitialW / 2f
                    val cy0 = multiInitialY + multiInitialH / 2f
                    it.w = (multiInitialW * scale).coerceIn(0.02f, 4f)
                    it.h = (multiInitialH * scale).coerceIn(0.02f, 4f)
                    it.x = cx0 - it.w / 2f
                    it.y = cy0 - it.h / 2f
                    // 墙上的照片按实际尺寸等比缩放
                    if (it.printMm != null && multiInitialPrintMm > 0f)
                        it.printMm = multiInitialPrintMm * scale
                    it.rotation = snapAngle(multiInitialRot + deltaAngle)
                    invalidate()
                } else if (dragging && selected != null) {
                    val it = selected!!
                    val slot = slotOf(it) ?: return true
                    it.x += (ev.x - lastTouch.x) / slot.rect.width()
                    it.y += (ev.y - lastTouch.y) / slot.rect.height()
                    applySnap(it)
                    invalidate()
                }
                if (dragging) updateDropTarget(rawX, rawY)
                lastTouch.set(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val isUp = ev.actionMasked == MotionEvent.ACTION_UP
                if (zooming) { zooming = false; if (!isUp) return true }
                if (resizing) {
                    if (isUp) { resizing = false; onModified?.invoke() }
                    else resizing = false
                    invalidate()
                    return true
                }
                if (reordering) {
                    val item = selected
                    if (isUp && dropTargetIdx >= 0 && item != null) {
                        val target = dropTargetIdx
                        dropTargetIdx = -1
                        onStripHighlight?.invoke(-1)
                        reordering = false
                        onItemDropToPage?.invoke(item, target)
                        return true
                    }
                    dropTargetIdx = -1
                    onStripHighlight?.invoke(-1)
                    reordering = false
                    onModified?.invoke()
                    invalidate()
                    return true
                }
                if (isUp && (dragging || multiActive) && dropTargetIdx >= 0 && selected != null) {
                    val item = selected!!
                    val target = dropTargetIdx
                    dropTargetIdx = -1
                    dragging = false; multiActive = false
                    onStripHighlight?.invoke(-1)
                    onItemDropToPage?.invoke(item, target)
                    return true
                }
                dropTargetIdx = -1
                onStripHighlight?.invoke(-1)
                if (multiActive || dragging) onModified?.invoke()
                val dx = ev.x - emptyDownX
                if (!hasItemAtDown && !multiActive &&
                    abs(dx) > 70f * resources.displayMetrics.density &&
                    abs(dx) > abs(ev.y - emptyDownY) * 1.4f) {
                    onFlip?.invoke(if (dx < 0) 1 else -1)
                }
                multiActive = false
                dragging = false
                guideV = -1f; guideH = -1f
                invalidate()
            }
        }
        return true
    }

    private fun dist(ev: MotionEvent): Float =
        hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun angle(ev: MotionEvent): Float =
        atan2((ev.getY(1) - ev.getY(0)).toDouble(), (ev.getX(1) - ev.getX(0)).toDouble()).toFloat()

    private fun updateDropTarget(rawX: Float, rawY: Float) {
        val it0 = selected
        val ownerIdx = it0?.let { item ->
            pageOf(item)?.let { pg -> project?.pages?.indexOfFirst { it === pg } }
        } ?: -1
        val t = onDragTargetAt?.invoke(rawX, rawY) ?: -1
        dropTargetIdx = if (t >= 0 && t != ownerIdx) t else -1
        onStripHighlight?.invoke(dropTargetIdx)
    }

    /** 旋转吸附：靠近 0 / 90 / 180 / 270 度时自动对齐（阈值 8°）。 */
    fun snapAngle(deg: Float): Float {
        var a = deg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        val nearest = (a / 90f).roundToInt() * 90f
        return if (abs(a - nearest) <= 8f) nearest else a
    }

    private fun applySnap(it: Item) {
        val snapT = 0.012f
        guideV = -1f; guideH = -1f
        val cx = it.x + it.w / 2f
        val cy = it.y + it.h / 2f
        for (target in floatArrayOf(0.5f, 1f / 3f, 2f / 3f)) {
            if (abs(cx - target) < snapT) { it.x += target - cx; guideV = target; break }
        }
        for (target in floatArrayOf(0.5f, 1f / 3f, 2f / 3f)) {
            if (abs(cy - target) < snapT) { it.y += target - cy; guideH = target; break }
        }
    }

    private fun handleReorderMove(ev: MotionEvent) {
        val it = selected ?: return
        val pg = pageOf(it) ?: return
        val slot = slotOf(it) ?: return
        it.x += (ev.x - lastTouch.x) / slot.rect.width()
        it.y += (ev.y - lastTouch.y) / slot.rect.height()

        var bestIdx = -1; var bestDist = Float.MAX_VALUE
        val myCx = it.x + it.w / 2f; val myCy = it.y + it.h / 2f
        for (i in pg.items.indices) {
            val o = pg.items[i]
            if (o === it) continue
            val dd = hypot(o.x + o.w / 2f - myCx, o.y + o.h / 2f - myCy)
            if (dd < bestDist) { bestDist = dd; bestIdx = i }
        }
        if (bestIdx >= 0 && bestDist < 0.14f) {
            val cur = pg.items.indexOf(it)
            if (cur >= 0 && cur != bestIdx) {
                pg.items.removeAt(cur)
                pg.items.add(bestIdx, it)
                reorderIndex = bestIdx
            }
        }
        invalidate()
    }

    private fun hitItem(vx: Float, vy: Float): Item? {
        // 展示物（照片/文字/色块）优先命中；笔画在最底层，未被覆盖时才可选中
        hitNonStrokeFirst(vx, vy)?.let { return it }
        return hitByType(vx, vy, strokeOnly = true)
    }

    private fun hitNonStrokeFirst(vx: Float, vy: Float): Item? =
        hitByType(vx, vy, strokeOnly = false)

    private fun hitByType(vx: Float, vy: Float, strokeOnly: Boolean): Item? {
        val p = project ?: return null
        for (si in slots.indices.reversed()) {
            val slot = slots[si]
            val page = p.pages.getOrNull(slot.index) ?: continue
            for (i in page.items.indices.reversed()) {
                val it = page.items[i]
                if ((it.type == Item.TYPE_STROKE) != strokeOnly) continue
                val cx = slot.rect.left + (it.x + it.w / 2f) * slot.rect.width()
                val cy = slot.rect.top + (it.y + it.h / 2f) * slot.rect.height()
                val fw = it.w * slot.rect.width(); val fh = it.h * slot.rect.height()
                val rad = -Math.toRadians(it.rotation.toDouble())
                val dx = vx - cx; val dy = vy - cy
                val lx = dx * cos(rad).toFloat() - dy * sin(rad).toFloat()
                val ly = dx * sin(rad).toFloat() + dy * cos(rad).toFloat()
                val pad = if (it.type == Item.TYPE_TEXT) 10f else 2f
                if (lx >= -fw / 2f - pad && lx <= fw / 2f + pad &&
                    ly >= -fh / 2f - pad && ly <= fh / 2f + pad) return it
            }
        }
        return null
    }

    /** 右下角手柄命中测试（含旋转）。 */
    private fun hitResizeHandle(vx: Float, vy: Float): Boolean {
        val it = selected ?: return false
        val slot = slotOf(it) ?: return false
        val cx = slot.rect.left + (it.x + it.w / 2f) * slot.rect.width()
        val cy = slot.rect.top + (it.y + it.h / 2f) * slot.rect.height()
        val fw = it.w * slot.rect.width(); val fh = it.h * slot.rect.height()
        val rad = Math.toRadians(it.rotation.toDouble())
        val lxp = fw / 2f; val lyp = fh / 2f
        val hx = cx + (lxp * cos(rad) - lyp * sin(rad)).toFloat()
        val hy = cy + (lxp * sin(rad) + lyp * cos(rad)).toFloat()
        return hypot(vx - hx, vy - hy) <= 22f * resources.displayMetrics.density
    }

    /**
     * 拖拽右下角手柄调节大小（中心保持不动）：
     * 横排文字 → 调宽、高度按内容自适应；竖排文字 → 调高、宽度按列数自适应；
     * 其余元素 → 自由缩放。
     */
    private fun handleResizeMove(ev: MotionEvent) {
        val it = selected ?: return
        val slot = slotOf(it) ?: return
        val cxN = it.x + it.w / 2f
        val cyN = it.y + it.h / 2f
        val cx = slot.rect.left + cxN * slot.rect.width()
        val cy = slot.rect.top + cyN * slot.rect.height()
        val rad = -Math.toRadians(it.rotation.toDouble())
        val dx = ev.x - cx; val dy = ev.y - cy
        val lx = abs(dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = abs(dx * sin(rad) + dy * cos(rad)).toFloat()
        when {
            it.type == Item.TYPE_TEXT && !it.vertical -> {
                val wPx = (lx * 2f).coerceAtLeast(16f)
                it.w = (wPx / slot.rect.width()).coerceIn(0.03f, 8f)
                it.h = (PageRenderer.textLayoutHeightPx(it, wPx, slot.rect.height()) /
                    slot.rect.height()).coerceIn(0.02f, 8f)
            }
            it.type == Item.TYPE_TEXT && it.vertical -> {
                val hPx = (ly * 2f).coerceAtLeast(16f)
                it.h = (hPx / slot.rect.height()).coerceIn(0.03f, 8f)
                it.w = (PageRenderer.verticalTextWidthPx(it, hPx, slot.rect.height()) /
                    slot.rect.width()).coerceIn(0.02f, 8f)
            }
            else -> {
                it.w = (lx * 2f / slot.rect.width()).coerceIn(0.02f, 8f)
                it.h = (ly * 2f / slot.rect.height()).coerceIn(0.02f, 8f)
            }
        }
        // 中心保持
        it.x = cxN - it.w / 2f
        it.y = cyN - it.h / 2f
        invalidate()
    }

    /** 文字内容/样式变化后按内容自适应尺寸（外露给 Activity 调用）。 */
    fun fitText(item: Item) {
        if (item.type != Item.TYPE_TEXT) return
        val slot = slotOf(item) ?: return
        if (item.vertical) {
            item.w = (PageRenderer.verticalTextWidthPx(
                item, item.h * slot.rect.height(), slot.rect.height()) /
                slot.rect.width()).coerceAtLeast(0.02f)
        } else {
            item.h = (PageRenderer.textLayoutHeightPx(
                item, item.w * slot.rect.width(), slot.rect.height()) /
                slot.rect.height()).coerceAtLeast(0.02f)
        }
        invalidate()
    }

    /** 选中照片铺满整个跨页（跨书脊）。 */
    fun spanSelected(): Boolean {
        val it = selected ?: return false
        if (!spreadMode || slots.size < 2) return false
        val slot = slotOf(it) ?: return false
        val isLeftSlot = slot === slots[0]
        it.y = 0f; it.h = 1f
        it.w = 2f
        it.x = if (isLeftSlot) 0f else -1f
        onModified?.invoke()
        invalidate()
        return true
    }

    /** 90° 步进旋转（带吸附语义）。 */
    fun rotateSelected90() {
        val it = selected ?: return
        it.rotation = snapAngle(it.rotation + 90f)
        onModified?.invoke()
        invalidate()
    }
}

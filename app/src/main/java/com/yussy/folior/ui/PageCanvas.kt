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

    private data class Slot(val index: Int, val rect: RectF)
    private val slots = mutableListOf<Slot>()

    var selected: Item? = null
        private set

    private var dragging = false
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

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (cropItem != null) return true
            select(hitItem(e.x, e.y))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (cropItem != null) return true
            val it = hitItem(e.x, e.y) ?: return false
            if (it.type == Item.TYPE_PHOTO && it.imgId != null) onOpenCrop?.invoke(it)
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

        // 内容：裁切到"双页合围"区域，允许元素跨书脊连续显示
        if (slots.isNotEmpty()) {
            val union = RectF(slots[0].rect)
            slots.forEach { union.union(it.rect) }
            canvas.save()
            canvas.clipRect(union)
            for (s in slots) {
                val page = p.pages.getOrNull(s.index) ?: continue
                PageRenderer.drawItems(context, p, page, canvas, s.rect)
            }
            canvas.restore()
        }

        if (cropItem != null) { drawCropMode(canvas); return }

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
        canvas.drawCircle(cx + fw / 2f, cy + fh / 2f, 7f, sp)
        canvas.drawCircle(cx - fw / 2f, cy - fh / 2f, 7f, sp)
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
    override fun onTouchEvent(ev: MotionEvent): Boolean =
        if (cropItem != null) cropTouch(ev) else editTouch(ev)

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

    private fun editTouch(ev: MotionEvent): Boolean {
        gesture.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                        multiInitialRot = it.rotation
                        dragging = false
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (reordering) { handleReorderMove(ev); lastTouch.set(ev.x, ev.y); return true }
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
                lastTouch.set(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (reordering) {
                    reordering = false
                    onModified?.invoke()
                    invalidate()
                    return true
                }
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
        val p = project ?: return null
        for (si in slots.indices.reversed()) {
            val slot = slots[si]
            val page = p.pages.getOrNull(slot.index) ?: continue
            for (i in page.items.indices.reversed()) {
                val it = page.items[i]
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

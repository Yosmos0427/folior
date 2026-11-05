package com.yussy.folior.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.yussy.folior.model.*
import com.yussy.folior.store.Prefs
import com.yussy.folior.store.Store
import kotlin.math.*

/** 吸附容差（屏幕 dp）：与缩放、页面尺寸无关，手感才稳定。 */
private const val SNAP_TOL_DP = 8f

/**
 * 页面编辑画布（支持单页 / 跨页两种模式）。
 *
 * 手势：
 * - 单指点按：选中 / 取消选中
 * - 单指拖动：移动（边 / 中心 / 等距 / 指示线，容差固定 8dp 屏幕）
 * - 长按：加选 / 减选（多选后可整体拖动、对齐、分布）
 * - 双指：缩放 + 旋转（吸附 0/90/180/270，并与指示线平行）
 * - 双击照片：裁切
 * - 空白处左右滑动：翻跨页
 * - 指示线模式：拖线体平移、拖两端旋转（吸附 0/30/45/60/90）
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
    /** 画布上任意按下一律回调（外部据此收起预览栏的删除按钮固定态等临时 UI） */
    var onTouchDown: (() -> Unit)? = null
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

    /** 当前选择集合（含 [selected]），多选时顺序即加入顺序。**只能是同一页的元素。** */
    private val selList = ArrayList<Item>()
    val selection: List<Item> get() = selList
    val multiCount: Int get() = selList.size

    private var dragging = false
    private var resizing = false
    private var reordering = false
    private var reorderIndex = -1
    private val lastTouch = PointF()

    // ---------------- 拖动：raw（手指）与显示分离 ----------------
    // 吸附绝不能改写拖动基准，否则手指继续拖也拖不走（旧实现正是这个毛病）。
    private var dragRawDx = 0f
    private var dragRawDy = 0f
    private val dragOriginX = ArrayList<Float>()
    private val dragOriginY = ArrayList<Float>()

    // 吸附结果与"拖走"状态
    private var snapV: AxisSnapper.Hit? = null
    private var snapH: AxisSnapper.Hit? = null
    private var stuckVKind: SnapKind? = null
    private var stuckVValue = 0f
    private var stuckVRaw = 0f
    private var stuckHKind: SnapKind? = null
    private var stuckHValue = 0f
    private var stuckHRaw = 0f
    private var coolVUntil = 0L
    private var coolHUntil = 0L

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

    // ---------------- 指示线（贯穿工作区的构图参考线） ----------------

    /** 指示线模式：进入后只操作线，元素不可选，避免挥手就误选 */
    var guideMode = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) activeGuide = null
            invalidate()
        }

    /** 指示线被增删改后回调（Activity 据此存盘/记撤销） */
    var onGuidesChanged: (() -> Unit)? = null

    /** 开始拖动指示线：Activity 借此记录操作前快照 */
    var onGuideEditStart: (() -> Unit)? = null

    /** 当前线换成另一条（点选）：Activity 据此刷新菜单里「镜像 / 解除」的文案 */
    var onGuidePicked: (() -> Unit)? = null

    private var activeGuide: Guide? = null
    private var guideDragMode = 0          // 0=无 1=平移 2=旋转
    private var guideStartOffset = 0f
    private var guideStartAngle = 0f


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
            select(hitItem(e.x, e.y))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val it = hitItem(e.x, e.y)
            if (it == null) { resetView(); return true }   // 双击空白：复位缩放
            if (it.type == Item.TYPE_TEXT) onEditText?.invoke(it)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val it = hitItem(e.x, e.y) ?: return
            // 长按 = 加选 / 减选（层级调整已有「下移 / 置顶」按钮，不必再占一个手势）
            if (selList.isEmpty()) { select(it); return }
            if (selList.size == 1 && selList[0] === it) return
            if (toggleMulti(it) && selList.size > 1) {
                dragging = true
                lastTouch.set(e.x, e.y)
                beginDrag()
            }
        }
    })

    // ---------------- 页与槽位 ----------------

    fun currentPage(): Page? = project?.pages?.getOrNull(activeIndex)

    fun pageOf(item: Item): Page? =
        project?.pages?.firstOrNull { pg -> pg.items.any { it === item } }

    /** 当前可见页面（单页/跨页）的联合矩形，用于判断点击是否落在页面左右两侧的空白 */
    private fun visiblePagesRect(): RectF? {
        var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE
        var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        for (s in slots) {
            if (s.index < 0) continue
            any = true
            if (s.rect.left < l) l = s.rect.left
            if (s.rect.right > r) r = s.rect.right
            if (s.rect.top < t) t = s.rect.top
            if (s.rect.bottom > b) b = s.rect.bottom
        }
        return if (any) RectF(l, t, r, b) else null
    }

    private fun slotOf(item: Item): Slot? {
        val owner = pageOf(item) ?: return null
        return slots.firstOrNull { it.index >= 0 && project?.pages?.getOrNull(it.index) === owner }
    }

    fun select(item: Item?) {
        selList.clear()
        if (item != null) selList.add(item)
        selected = item
        if (item != null) {
            val owner = pageOf(item)
            val idx = if (owner == null) -1 else project?.pages?.indexOfFirst { it === owner } ?: -1
            if (idx >= 0) activeIndex = idx
        }
        resetSnapState()
        onSelectionChanged?.invoke(item)
        invalidate()
    }

    /**
     * 加选 / 减选（长按元素）。只允许同一页——不同页的 x/y 是各自归一化的，
     * 混在一起对齐会错位。
     */
    fun toggleMulti(item: Item): Boolean {
        val owner = pageOf(item) ?: return false
        if (selList.isNotEmpty() && pageOf(selList[0]) !== owner) return false
        if (selList.contains(item)) {
            if (selList.size == 1) return false       // 至少留一个
            selList.remove(item)
        } else {
            selList.add(item)
        }
        selected = selList.firstOrNull()
        onSelectionChanged?.invoke(selected)
        invalidate()
        return true
    }

    /** 全选当前页（多选用） */
    fun selectAllOnPage() {
        val owner = currentPage() ?: return
        selList.clear()
        owner.items.filter { it.type != Item.TYPE_STROKE }.forEach { selList.add(it) }
        selected = selList.firstOrNull()
        onSelectionChanged?.invoke(selected)
        invalidate()
    }

    /** 直接设定选择集合（复制一组元素后选中副本时用） */
    fun setSelection(items: List<Item>) {
        selList.clear()
        selList.addAll(items)
        selected = selList.firstOrNull()
        resetSnapState()
        onSelectionChanged?.invoke(selected)
        invalidate()
    }

    private fun resetSnapState() {
        snapV = null; snapH = null
        stuckVKind = null; stuckHKind = null
        coolVUntil = 0L; coolHUntil = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSlots()
    }

    /** 重新计算页位（尺寸/方向/跨页模式变化时调用） */
    fun invalidateLayout() {
        resetView()   // 方向/模式变化时复位缩放
        layoutSlots()
        invalidate()
    }

    /**
     * 重新计算页位但保留视图缩放/平移。
     * 撤销/重做只改内容，用户放大看的那个局部不该被弹回 1 倍。
     */
    fun relayoutKeepView() {
        layoutSlots()
        clampView()
        invalidate()
    }

    /**
     * 整份工程被替换（撤销 / 重做）后调用：清掉所有指向旧对象引用的临时状态。
     *
     * 撤销是把工程从 JSON 重建一遍，元素、指示线、笔画都换成了新实例。
     * 这些字段如果继续持有旧引用，会出现「指示线怎么点都选不中」
     * （hitGuide 只认 activeGuide 那条），以及绘制撤销把笔画从旧页里删掉的问题。
     */
    fun onProjectReplaced() {
        selList.clear()
        selected = null
        activeGuide = null
        guideDragMode = 0
        undoStack.clear()
        dropTargetIdx = -1
        dragging = false
        resizing = false
        reordering = false
        multiActive = false
        drawing = false
        liveStroke = null
        liveRaw.clear()
        resetSnapState()
        onSelectionChanged?.invoke(null)
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
            val gutter = spineGapPx()
            val maxW = (vw - margin * 2 - gutter).coerceAtLeast(1f)
            val maxH = vh - margin * 2
            val cy = vh / 2f
            val hasL = leftIndex in 0 until p.pages.size
            val hasR = rightIndex in 0 until p.pages.size
            val lw = if (hasL) p.pages[leftIndex].widthMm(p) else 0f
            val lh = if (hasL) p.pages[leftIndex].heightMm(p) else 0f
            val rw = if (hasR) p.pages[rightIndex].widthMm(p) else 0f
            val rh = if (hasR) p.pages[rightIndex].heightMm(p) else 0f

            if (hasL && hasR) {
                // 两页共用一个 px/mm 比例：同一物理平面上并置，跨页照片才能严格连续
                val k = min(maxW / (lw + rw).coerceAtLeast(1f), maxH / max(lh, rh).coerceAtLeast(1f))
                slots.add(Slot(leftIndex, RectF(
                    vw / 2f - gutter / 2f - lw * k, cy - lh * k / 2f,
                    vw / 2f - gutter / 2f, cy + lh * k / 2f)))
                slots.add(Slot(rightIndex, RectF(
                    vw / 2f + gutter / 2f, cy - rh * k / 2f,
                    vw / 2f + gutter / 2f + rw * k, cy + rh * k / 2f)))
            } else if (hasL) {
                // 只有左页（末页单独成页）：留在左半幅
                val k = min(maxW / 2f / lw.coerceAtLeast(1f), maxH / lh.coerceAtLeast(1f))
                slots.add(Slot(leftIndex, RectF(
                    vw / 2f - gutter / 2f - lw * k, cy - lh * k / 2f,
                    vw / 2f - gutter / 2f, cy + lh * k / 2f)))
            } else if (hasR) {
                // 只有右页（封面）：留在右半幅
                val k = min(maxW / 2f / rw.coerceAtLeast(1f), maxH / rh.coerceAtLeast(1f))
                slots.add(Slot(rightIndex, RectF(
                    vw / 2f + gutter / 2f, cy - rh * k / 2f,
                    vw / 2f + gutter / 2f + rw * k, cy + rh * k / 2f)))
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

    /** 双联时两页之间的书脊间距（px），由全局设置决定 */
    private fun spineGapPx(): Float =
        Prefs.spineGapDp(context) * resources.displayMetrics.density

    /** 书脊在两页之间的矩形区域；无书脊间隙时返回 null */
    private fun spineRect(): RectF? {
        val gap = spineGapPx()
        if (gap <= 0.5f || slots.size < 2) return null
        val l = slots[0].rect
        val r = slots[1].rect
        val cx = (l.right + r.left) / 2f
        return RectF(cx - gap / 2f, min(l.top, r.top), cx + gap / 2f, max(l.bottom, r.bottom))
    }

    /** 书脊底板：纸面填充，画在内容之下 */
    private fun drawSpineBase(canvas: Canvas, p: Project) {
        val rect = spineRect() ?: return
        val page = p.pages.getOrNull(slots[0].index)
        val base = if (page != null) PageRenderer.pageBgColor(p, page) else Color.WHITE
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = base
        canvas.drawRect(rect, fill)
    }

    /** 书脊细节（折痕 / 缝线 / 金属环），画在内容之上 */
    private fun drawSpineDetail(canvas: Canvas, p: Project) {
        val rect = spineRect() ?: return
        val cx = (slots[0].rect.right + slots[1].rect.left) / 2f
        when (Prefs.spineStyle(context)) {
            Prefs.SPINE_STITCH -> drawStitchSpine(canvas, rect, cx)
            Prefs.SPINE_RING -> drawRingSpine(canvas, rect, cx)
        }
    }

    /** 线编：两条折痕 + 一列交叉缝线 */
    private fun drawStitchSpine(canvas: Canvas, rect: RectF, cx: Float) {
        val d = resources.displayMetrics.density
        val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x26000000
            strokeWidth = 1f * d
        }
        val inset = rect.width() * 0.3f
        canvas.drawLine(cx - inset, rect.top, cx - inset, rect.bottom, crease)
        canvas.drawLine(cx + inset, rect.top, cx + inset, rect.bottom, crease)

        val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A6A4B.toInt()
            strokeWidth = 1.1f * d
            strokeCap = Paint.Cap.ROUND
        }
        val step = 16f * d
        val half = rect.width() * 0.42f
        val drop = rect.width() * 0.26f
        var y = rect.top + step * 0.6f
        while (y < rect.bottom) {
            canvas.drawLine(cx - half, y - drop, cx + half, y + drop, thread)
            canvas.drawLine(cx - half, y + drop, cx + half, y - drop, thread)
            y += step
        }
    }

    /** 活页：一列金属环 */
    private fun drawRingSpine(canvas: Canvas, rect: RectF, cx: Float) {
        val d = resources.displayMetrics.density
        val step = 36f * d
        val ringW = rect.width() * 0.74f
        val ringH = 15f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.1f * d
        }
        var y = rect.top + step * 0.5f
        while (y < rect.bottom) {
            p.shader = LinearGradient(
                cx - ringW / 2f, 0f, cx + ringW / 2f, 0f,
                intArrayOf(0xFFE2E4EA.toInt(), 0xFF9AA0AA.toInt(),
                    0xFFF0F2F6.toInt(), 0xFF8E939B.toInt()),
                floatArrayOf(0f, 0.35f, 0.62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(
                RectF(cx - ringW / 2f, y - ringH / 2f, cx + ringW / 2f, y + ringH / 2f),
                ringH / 2f, ringH / 2f, p)
            y += step
        }
        p.shader = null
    }

    /** 后台补完更清晰的图就重绘一帧（缩放/首帧先给缓存里的近似尺寸） */
    private val onDecoded = { postInvalidateOnAnimation() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Store.addDecodeListener(onDecoded)
    }

    override fun onDetachedFromWindow() {
        Store.removeDecodeListener(onDecoded)
        super.onDetachedFromWindow()
    }

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

        // 书脊底色（双联摄影书）：先铺纸面，细节（折痕/缝线/环）留到最后画在内容之上
        if (spreadMode && p.type == Project.TYPE_BOOK) drawSpineBase(canvas, p)

        if (slots.isNotEmpty()) {
            val union = RectF(slots[0].rect)
            slots.forEach { union.union(it.rect) }

            // 墙外延伸预览（默认开启）：把超出页面的画面以半透明"幽灵"画在墙面之外，
            // 便于判断被裁掉的内容；墙面内仍按正常不透明度渲染。
            // 跨页照片不算溢出——它完整地存在于对开的另一页上。
            // 跨页是否成立只取决于对开配对，与当前是单页还是双联无关：
            // 单页视图把邻页溢入的那一半补上，双联视图让越界部分接到邻页上。
            val bookPages = p.type == Project.TYPE_BOOK
            fun canSpan(idx: Int): Boolean = bookPages && (
                leftNeighborOf(idx) in p.pages.indices ||
                    rightNeighborOf(idx, p.pages.size) in p.pages.indices)
            val canCross = spreadMode && slots.size == 2 && bookPages
            val hasOverflow = slots.any { s ->
                p.pages.getOrNull(s.index)?.items?.any {
                    if (canSpan(s.index) && it.crossesHorizontally()) false
                    else it.x < -0.001f || it.y < -0.001f ||
                        it.x + it.w > 1.001f || it.y + it.h > 1.001f
                } == true
            }
            if (hasOverflow) {
                val ghostArea = RectF(union)
                val mx = union.width() * 0.45f
                val my = union.height() * 0.45f
                ghostArea.set(union.left - mx, union.top - my, union.right + mx, union.bottom + my)
                val layer = canvas.saveLayerAlpha(null, 56)   // ≈22% 不透明度
                slots.forEach { canvas.clipOutRect(it.rect) } // 只画在墙面之外
                canvas.clipRect(ghostArea)
                for (s in slots) {
                    val pg = p.pages.getOrNull(s.index) ?: continue
                    PageRenderer.drawItems(context, p, pg, canvas, s.rect,
                        skipCrossing = canSpan(s.index))
                }
                canvas.restoreToCount(layer)
            }

            // 跨页照片：横向越界的照片以所在页为坐标系绘制，裁到「双页合围」区域 ——
            // 哪个方向有页面，超出部分就画到那一页上（左右对称，纯几何判定）
            if (canCross) {
                canvas.save()
                canvas.clipRect(union)
                for (s in slots) {
                    val pg = p.pages.getOrNull(s.index) ?: continue
                    PageRenderer.drawCrossingPhotos(context, p, pg, canvas, s.rect)
                }
                canvas.restore()
            }

            // 内容：逐页裁到各自页面内——普通元素越界即被裁掉（越界部分另有浅色预览），
            // 跨页照片已在上一层单独绘制。
            // 单页视图（竖屏工作台）在这里补上「邻页溢入本页的那一半」，
            // 于是这一页看起来和横屏双联里同一页完全一样。
            for (s in slots) {
                val page = p.pages.getOrNull(s.index) ?: continue
                canvas.save()
                canvas.clipRect(s.rect)
                if (!canCross && canSpan(s.index)) {
                    PageRenderer.drawSpillFrom(context, p,
                        p.pages.getOrNull(leftNeighborOf(s.index)), page, canvas, s.rect,
                        toRight = false)
                    PageRenderer.drawSpillFrom(context, p,
                        p.pages.getOrNull(rightNeighborOf(s.index, p.pages.size)), page, canvas, s.rect,
                        toRight = true)
                    PageRenderer.drawCrossingPhotos(context, p, page, canvas, s.rect)
                    PageRenderer.drawItems(context, p, page, canvas, s.rect, skipCrossing = true)
                } else {
                    PageRenderer.drawItems(context, p, page, canvas, s.rect, skipCrossing = canCross)
                }
                canvas.restore()
            }

            // 书脊细节压在最上层：跨页大图会被折痕/缝线/金属环「分割」，
            // 与实体书摊开时的观感一致
            if (spreadMode && p.type == Project.TYPE_BOOK) drawSpineDetail(canvas, p)
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

        // 吸附参考线：颜色区分「对齐到什么」——朱红=元素，灰=页面，青=指示线，赭黄=等距，橄榄=整厘米
        val snapSlot = selList.firstOrNull()?.let { slotOf(it) }
        if (snapSlot != null && (snapV != null || snapH != null)) {
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            gp.strokeWidth = 1.2f * resources.displayMetrics.density / viewScale
            gp.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            snapV?.let { hit ->
                gp.color = snapColor(hit.kind)
                val x = snapSlot.rect.left + hit.value * snapSlot.rect.width()
                canvas.drawLine(x, snapSlot.rect.top - 6f, x, snapSlot.rect.bottom + 6f, gp)
            }
            snapH?.let { hit ->
                gp.color = snapColor(hit.kind)
                val y = snapSlot.rect.top + hit.value * snapSlot.rect.height()
                canvas.drawLine(snapSlot.rect.left - 6f, y, snapSlot.rect.right + 6f, y, gp)
            }
        }
        drawGuides(canvas)
        if (selList.isNotEmpty()) {
            for (it in selList) drawSelection(canvas, it, it === selected)
        }
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

    private fun snapColor(kind: SnapKind): Int = when (kind) {
        SnapKind.GUIDE -> 0xCC2F7C8C.toInt()
        SnapKind.ELEMENT -> 0xCCB5442A.toInt()
        SnapKind.PAGE -> 0x996B6B66.toInt()
        SnapKind.EQUAL -> 0xCCC08A2E.toInt()
        SnapKind.GRID -> 0xCC7A8C4A.toInt()
    }

    /** 指示线：贯穿整个工作区；模式内显示两端旋转手柄 */
    private fun drawGuides(canvas: Canvas) {
        val p = project ?: return
        if (p.guides.isEmpty() || slots.isEmpty()) return
        val center = contentCenterPx() ?: return
        val slot = slots[0]
        val page = p.pages.getOrNull(slot.index) ?: return
        val pxPerMm = slot.rect.width() / page.widthMm(p).coerceAtLeast(1f)
        val d = resources.displayMetrics.density
        val len = hypot(width.toFloat(), height.toFloat()) * 1.8f
        for (g in p.guides) {
            val rad = Math.toRadians(g.angle.toDouble())
            val ux = cos(rad).toFloat(); val uy = sin(rad).toFloat()
            val nx = -uy; val ny = ux
            val cx = center.x + g.axMm * pxPerMm
            val cy = center.y + g.ayMm * pxPerMm
            val on = guideMode && g === activeGuide
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            // 黑线：编辑中的那条实心，其余压到半透明，避免多条线糊成一片
            gp.color = if (on) 0xFF000000.toInt() else 0x73000000
            gp.strokeWidth = (if (on) 2f else 1.2f) * d / viewScale
            canvas.drawLine(cx - ux * len, cy - uy * len, cx + ux * len, cy + uy * len, gp)
            // 旋转手柄只给当前线，多条线时不至于满屏圆点
            if (on) {
                val hr = 9f * d / viewScale
                val off = 120f * d / viewScale
                val hp = Paint(Paint.ANTI_ALIAS_FLAG)
                hp.style = Paint.Style.FILL
                hp.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(cx + ux * off, cy + uy * off, hr, hp)
                canvas.drawCircle(cx - ux * off, cy - uy * off, hr, hp)
                hp.style = Paint.Style.STROKE
                hp.strokeWidth = 2f * d / viewScale
                hp.color = 0xFF000000.toInt()
                canvas.drawCircle(cx + ux * off, cy + uy * off, hr, hp)
                canvas.drawCircle(cx - ux * off, cy - uy * off, hr, hp)
            }
        }
    }

    private fun drawSelection(canvas: Canvas, it: Item, primary: Boolean) {
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
        sp.strokeWidth = if (primary) 2f else 1.4f
        canvas.drawRect(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f, sp)
        if (primary) {
            sp.style = Paint.Style.FILL
            canvas.drawCircle(cx - fw / 2f, cy - fh / 2f, 6f, sp)
            // 右下角：拖拽调节大小手柄（小尺寸白点 + 加粗朱红描边）
            val d = resources.displayMetrics.density
            val hx = cx + fw / 2f; val hy = cy + fh / 2f
            sp.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(hx, hy, 5.5f * d, sp)
            sp.style = Paint.Style.STROKE
            sp.strokeWidth = 3f
            sp.color = 0xFFB5442A.toInt()
            canvas.drawCircle(hx, hy, 5.5f * d, sp)
            sp.style = Paint.Style.FILL
        }
        canvas.restore()
        if (reordering) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = 0xFFB5442A.toInt()
            tp.textSize = 30f
            tp.textAlign = Paint.Align.CENTER
            canvas.drawText("拖动调整层级", width / 2f, 40f, tp)
        }
    }

    // ---------------- 指示线 ----------------

    /** 进入 / 退出指示线模式。进入时若还没有线，先放一条水平的。 */
    fun editGuides(on: Boolean) {
        if (on && project?.guides.isNullOrEmpty()) addGuide()
        // 进入时把最近加的那条设为当前线，手柄立刻可见；点别的线可以换
        if (on) activeGuide = project?.guides?.lastOrNull() else activeGuide = null
        guideMode = on
        invalidate()
    }

    /** 当前的线数 / 有没有可删的线（Activity 据此启用菜单项） */
    val guideCount: Int get() = project?.guides?.size ?: 0

    /**
     * 加一条线。新线错开摆放——否则几条线完全重叠，看着仍像只有一条。
     * 相邻间距 20mm，超出页高就翻到另一侧，最多试 32 个位置。
     */
    fun addGuide(): Guide? {
        val p = project ?: return null
        val h = currentPage()?.heightMm(p) ?: 297f
        val step = 20f
        var ay = 0f
        var guard = 0
        while (guard++ < 32 && p.guides.any {
                abs(normalizeGuideAngle(it.angle)) < 1f && abs(it.ayMm - ay) < step * 0.5f
            }) {
            ay -= step
            if (ay < -h / 2f) ay = h / 2f
        }
        val g = Guide(angle = 0f, axMm = 0f, ayMm = ay)
        p.guides.add(g)
        activeGuide = g
        onGuidesChanged?.invoke()
        invalidate()
        return g
    }

    /** 删掉当前选中的线；没有选中就删最后加的那条。 */
    fun removeActiveGuide(): Boolean {
        val p = project ?: return false
        val g = activeGuide ?: p.guides.lastOrNull() ?: return false
        val gid = g.group
        p.guides.removeAll { it === g }
        // 组里只剩一条时已经没有镜像对象，恢复成独立线
        if (gid != 0) {
            val rest = p.guides.filter { it.group == gid }
            if (rest.size < 2) rest.forEach { it.group = 0; it.role = 0 }
        }
        activeGuide = null
        onGuidesChanged?.invoke()
        invalidate()
        return true
    }

    // ---------------- 镜像 ----------------

    /** 当前操作对象：选中的那条，没有就取最后加的一条（与删除的行为一致） */
    private fun currentGuide(): Guide? = activeGuide ?: project?.guides?.lastOrNull()

    /** 当前线是否在镜像组里（Activity 据此把菜单项换成「解除」） */
    val activeGuideMirrored: Boolean get() = (currentGuide()?.group ?: 0) != 0

    /**
     * 镜像变换：把一条线的几何投射到组内某个角色。
     *
     * 线是无向的，所以关于竖直轴或水平轴反射后角度都是 -θ，区别只在锚点；
     * 对角角色等于绕中心转 180°，角度不变、两个锚点分量都取反。
     * 每个变换都是自逆的（做两次回到原样），所以「由镜像线反算原线」也用它。
     */
    private fun mirrorGeometry(angle: Float, ax: Float, ay: Float, role: Int): FloatArray {
        val a = normalizeGuideAngle(angle)
        return when (role) {
            1 -> floatArrayOf(normalizeGuideAngle(-a), -ax, ay)   // 左右镜像
            2 -> floatArrayOf(normalizeGuideAngle(-a), ax, -ay)   // 上下镜像
            3 -> floatArrayOf(a, -ax, -ay)                        // 对角镜像
            else -> floatArrayOf(a, ax, ay)
        }
    }

    /**
     * 给当前线建立镜像组：原线 + 左右 + 上下 + 对角，共四条，两两关于页面中线对称。
     * 建立之后拖或转任意一条，其余三条同步跟上。
     */
    fun addGuideMirrors(): Boolean {
        val p = project ?: return false
        val g = currentGuide() ?: return false
        activeGuide = g
        if (g.group != 0) return false
        val page = currentPage()
        val w = page?.widthMm(p) ?: 210f
        val h = page?.heightMm(p) ?: 297f
        val a0 = normalizeGuideAngle(g.angle)
        var x0 = g.axMm
        var y0 = g.ayMm
        // 线压在页面中线上时，镜像过去仍落在原处，四条会叠成一条——
        // 先挪到四分之一处，让四象限各自看得见
        if (abs(x0) < w * 0.05f) x0 = -w * 0.25f
        if (abs(y0) < h * 0.05f) y0 = -h * 0.25f
        g.angle = a0; g.axMm = x0; g.ayMm = y0

        val gid = (p.guides.maxOfOrNull { it.group } ?: 0) + 1
        g.group = gid; g.role = 0
        for (role in 1..3) {
            val v = mirrorGeometry(a0, x0, y0, role)
            p.guides.add(Guide(angle = v[0], axMm = v[1], ayMm = v[2],
                group = gid, role = role))
        }
        onGuidesChanged?.invoke()
        invalidate()
        return true
    }

    /** 解除当前线所在的镜像组：四条各自独立，之后各改各的。 */
    fun unlinkActiveMirror(): Boolean {
        val p = project ?: return false
        val g = currentGuide() ?: return false
        activeGuide = g
        if (g.group == 0) return false
        val gid = g.group
        for (q in p.guides) if (q.group == gid) { q.group = 0; q.role = 0 }
        onGuidesChanged?.invoke()
        invalidate()
        return true
    }

    /**
     * 组内一条线被拖动/旋转后，把其余成员同步到镜像位置。
     *
     * 用户拖的可能是任意一条（含镜像线），所以先按它的角色反算回原线几何，
     * 再按各自角色派生——变换自逆，反算与正算同一套。
     */
    private fun syncMirrorGroup(from: Guide) {
        if (from.group == 0) return
        val p = project ?: return
        val members = p.guides.filter { it.group == from.group }
        if (members.size < 2) {          // 只剩自己，镜像关系自动失效
            from.group = 0; from.role = 0
            return
        }
        val base = mirrorGeometry(from.angle, from.axMm, from.ayMm, from.role)
        for (q in members) {
            if (q === from) continue
            val v = mirrorGeometry(base[0], base[1], base[2], q.role)
            q.angle = v[0]; q.axMm = v[1]; q.ayMm = v[2]
        }
    }

    fun clearGuides() {
        val p = project ?: return
        if (p.guides.isEmpty()) return
        p.guides.clear()
        activeGuide = null
        onGuidesChanged?.invoke()
        invalidate()
    }

    /**
     * 元素旋转时与指示线联动：快到与某条线平行时吸附过去。
     * 矩形是 90° 周期，所以把线角折叠到 0..90 再比。
     */
    fun snapAngleWithGuides(deg: Float): Float {
        val base = snapAngle(deg)
        if (base != deg) return base
        val p = project ?: return base
        var best = base
        var bestD = Float.MAX_VALUE
        for (g in p.guides) {
            val target = ((g.angle % 90f) + 90f) % 90f
            for (k in 0 until 4) {
                val cand = target + k * 90f
                var d = abs(deg - cand) % 180f
                if (d > 90f) d = 180f - d
                if (d < bestD) { bestD = d; best = cand }
            }
        }
        return if (bestD <= 2.5f) best else base
    }

    private fun guideTouch(ev: MotionEvent): Boolean {
        val p = project ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitGuide(ev.x, ev.y)
                if (hit == null) {
                    activeGuide = null; guideDragMode = 0
                } else {
                    val changed = hit.first !== activeGuide
                    activeGuide = hit.first
                    guideDragMode = hit.second
                    lastTouch.set(ev.x, ev.y)
                    if (changed) onGuidePicked?.invoke()
                    onGuideEditStart?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val g = activeGuide ?: return true
                if (guideDragMode == 0) return true
                val center = contentCenterPx() ?: return true
                val slot = slots.getOrNull(0) ?: return true
                val page = p.pages.getOrNull(slot.index) ?: return true
                val pxPerMm = slot.rect.width() / page.widthMm(p).coerceAtLeast(1f)
                if (guideDragMode == 1) {
                    // 平移：只取法线分量——沿线的方向对一条贯穿线没有视觉意义
                    val rad = Math.toRadians(g.angle.toDouble())
                    val nx = -sin(rad).toFloat(); val ny = cos(rad).toFloat()
                    val along = (ev.x - lastTouch.x) * nx + (ev.y - lastTouch.y) * ny
                    g.axMm += nx * along / pxPerMm
                    g.ayMm += ny * along / pxPerMm
                } else {
                    // 旋转：绕锚点转
                    val cx = center.x + g.axMm * pxPerMm
                    val cy = center.y + g.ayMm * pxPerMm
                    val deg = Math.toDegrees(
                        atan2((ev.y - cy).toDouble(), (ev.x - cx).toDouble())).toFloat()
                    g.angle = snapGuideAngle(deg)
                }
                // 组内其余三条跟着做镜像，始终与这条保持对称
                syncMirrorGroup(g)
                lastTouch.set(ev.x, ev.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (guideDragMode != 0) onGuidesChanged?.invoke()
                guideDragMode = 0
                return true
            }
        }
        return true
    }

    /** 命中：handler 圆 → 旋转；线体 → 平移。后加的线在上，倒序找。 */
    private fun hitGuide(vx: Float, vy: Float): Pair<Guide, Int>? {
        val p = project ?: return null
        val center = contentCenterPx() ?: return null
        val slot = slots.getOrNull(0) ?: return null
        val page = p.pages.getOrNull(slot.index) ?: return null
        val pxPerMm = slot.rect.width() / page.widthMm(p).coerceAtLeast(1f)
        val d = resources.displayMetrics.density
        val hr = 22f * d / viewScale
        val off = 120f * d / viewScale
        val lineTol = 16f * d / viewScale
        val cx0 = center.x; val cy0 = center.y
        for (g in p.guides.reversed()) {
            if (g !== activeGuide) continue      // 手柄只在当前线上显示
            val rad = Math.toRadians(g.angle.toDouble())
            val ux = cos(rad).toFloat(); val uy = sin(rad).toFloat()
            val cx = cx0 + g.axMm * pxPerMm; val cy = cy0 + g.ayMm * pxPerMm
            if (hypot(vx - (cx + ux * off), vy - (cy + uy * off)) <= hr) return g to 2
            if (hypot(vx - (cx - ux * off), vy - (cy - uy * off)) <= hr) return g to 2
        }
        for (g in p.guides.reversed()) {
            val rad = Math.toRadians(g.angle.toDouble())
            val ux = cos(rad).toFloat(); val uy = sin(rad).toFloat()
            val cx = cx0 + g.axMm * pxPerMm; val cy = cy0 + g.ayMm * pxPerMm
            if (distToSeg(vx, vy, cx - ux * 4000f, cy - uy * 4000f,
                    cx + ux * 4000f, cy + uy * 4000f) <= lineTol) return g to 1
        }
        return null
    }

    // ---------------- 触摸 ----------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {

        // 工作区双指缩放：未选中、非绘制模式时用【原始视图坐标】直接处理。
        // 不能走 contentEvent（内容坐标）—— 其换算分母是正在变化的 viewScale，
        // 会形成反馈回路导致画面抽搐。
        if (!drawMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2 && selected == null && !reordering && !zooming) {
                        zooming = true
                        zoomInitDist = dist(ev).coerceAtLeast(1f)
                        zoomInitScale = viewScale
                        zoomInitMidX = midX(ev); zoomInitMidY = midY(ev)
                        zoomInitViewX = viewX; zoomInitViewY = viewY
                        return true
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
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (zooming && ev.pointerCount - 1 < 2) { zooming = false; return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (zooming) { zooming = false; return true }
                }
            }
        }

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
        // 笔画只落在所属页/墙内（超出边界的落点钳制到边缘）
        liveRaw.add(((vx - slot.rect.left) / slot.rect.width()).coerceIn(0f, 1f))
        liveRaw.add(((vy - slot.rect.top) / slot.rect.height()).coerceIn(0f, 1f))
        liveRaw.add(pressure)
        drawing = true
        rebuildLive()
        invalidate()
    }

    private fun extendStroke(vx: Float, vy: Float, pressure: Float) {
        val slot = drawSlot ?: return
        liveRaw.add(((vx - slot.rect.left) / slot.rect.width()).coerceIn(0f, 1f))
        liveRaw.add(((vy - slot.rect.top) / slot.rect.height()).coerceIn(0f, 1f))
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
        var x0 = minX - padX; var y0 = minY - padY
        var bw = (maxX - minX) + padX * 2f
        var bh = (maxY - minY) + padY * 2f
        // 边界框整体限制在页面/墙内（笔画不越界，也不会误触发墙外幽灵预览）
        x0 = x0.coerceAtLeast(0f)
        y0 = y0.coerceAtLeast(0f)
        bw = (x0 + bw).coerceAtMost(1f) - x0
        bh = (y0 + bh).coerceAtMost(1f) - y0
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

    private fun editTouch(ev: MotionEvent, rawX: Float, rawY: Float): Boolean {
        // 指示线模式：只操作线，不选元素、不拖元素
        if (guideMode) return guideTouch(ev)
        gesture.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchDown?.invoke()
                if (selected != null && selList.size == 1 && hitResizeHandle(ev.x, ev.y)) {
                    resizing = true
                    resetSnapState()
                    val sel = selected!!
                    resizeBaseW = sel.w
                    resizeBaseH = sel.h
                    // 照片只能按原图比例缩放（不裁切、不变形）；文字/色块/笔画仍自由缩放
                    resizeLockAspect = sel.type == Item.TYPE_PHOTO &&
                            sel.w > 0f && sel.h > 0f
                    lastTouch.set(ev.x, ev.y)
                    return true
                }
                lastTouch.set(ev.x, ev.y)
                emptyDownX = ev.x; emptyDownY = ev.y
                val hit = hitItem(ev.x, ev.y)
                hasItemAtDown = hit != null
                // 按在任一已选元素上即可整体拖动（多选时是整组移动）
                dragging = hit != null && selList.any { it === hit }
                if (dragging) beginDrag()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && selected != null && selList.size == 1) {
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
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) { handleResizeMove(ev); return true }
                if (reordering) {
                    handleReorderMove(ev)
                    updateDropTarget(rawX, rawY)
                    lastTouch.set(ev.x, ev.y); return true
                }
                if (multiActive && ev.pointerCount >= 2 && selected != null) {
                    val it = selected!!
                    val d = dist(ev)
                    // 两指落下时几乎重合 → dist ≈ 0，拿它做分母会算出 Infinity/NaN。
                    // NaN 进了模型，JSONObject 会直接拒收（Forbidden numeric value），
                    // 于是存盘、快照、撤销全部跟着崩。等手指张开到能测量的距离再起算，
                    // 以此刻的尺寸为基准，缩放不会跳。
                    if (d < minPinchPx()) return true
                    if (multiInitialDist < minPinchPx()) {
                        multiInitialDist = d
                        multiInitialAngle = angle(ev)
                        multiInitialW = it.w; multiInitialH = it.h
                        multiInitialX = it.x; multiInitialY = it.y
                        multiInitialPrintMm = it.printMm ?: 0f
                        multiInitialRot = it.rotation
                    }
                    val scale = finite(d / multiInitialDist, 1f)
                    val deltaAngle = Math.toDegrees((angle(ev) - multiInitialAngle).toDouble()).toFloat()
                    // 以图像中心为锚点缩放：保持中心不动
                    val cx0 = multiInitialX + multiInitialW / 2f
                    val cy0 = multiInitialY + multiInitialH / 2f
                    // 照片：两轴共用同一系数，钳制也按两轴共同边界，比例严格守恒
                    var sc = scale
                    if (it.type == Item.TYPE_PHOTO && multiInitialW > 0f && multiInitialH > 0f) {
                        val lo = Math.max(0.02f / multiInitialW, 0.02f / multiInitialH)
                        val hi = Math.min(4f / multiInitialW, 4f / multiInitialH)
                        sc = scale.coerceIn(Math.min(lo, hi), Math.max(lo, hi))
                    }
                    it.w = finite((multiInitialW * sc).coerceIn(0.02f, 4f), 0.02f)
                    it.h = finite((multiInitialH * sc).coerceIn(0.02f, 4f), 0.02f)
                    // 双指缩放同样跟参考位置对齐：中心不变，边去吸目标
                    val slot0 = slotOf(it)
                    val page0 = slot0?.let { s -> project?.pages?.getOrNull(s.index) }
                    if (slot0 != null && page0 != null) {
                        val r0 = ((it.rotation % 360f) + 360f) % 360f
                        if (r0 < 1f || abs(r0 - 180f) < 1f || r0 > 359f) {
                            val (wc, hx) = snapResizeAxis(cx0, it.w, tolX(slot0), true, slot0, page0, cy0)
                            val (hc, hy) = snapResizeAxis(cy0, it.h, tolY(slot0), false, slot0, page0, cx0)
                            val dxPx = if (hx != null) abs(wc - it.w) * slot0.rect.width() * viewScale
                            else Float.MAX_VALUE
                            val dyPx = if (hy != null) abs(hc - it.h) * slot0.rect.height() * viewScale
                            else Float.MAX_VALUE
                            if (dxPx <= dyPx && hx != null) {
                                it.w = finite(wc.coerceIn(0.02f, 4f), it.w)
                                if (multiInitialW > 0f) it.h = finite(it.w * (multiInitialH / multiInitialW), it.h)
                                snapV = hx; snapH = null
                            } else if (hy != null) {
                                it.h = finite(hc.coerceIn(0.02f, 4f), it.h)
                                if (multiInitialH > 0f) it.w = finite(it.h * (multiInitialW / multiInitialH), it.w)
                                snapV = null; snapH = hy
                            }
                        }
                    }
                    it.x = finite(cx0 - it.w / 2f, it.x)
                    it.y = finite(cy0 - it.h / 2f, it.y)
                    // 墙上的照片按实际尺寸等比缩放
                    if (it.printMm != null && multiInitialPrintMm > 0f)
                        it.printMm = finite(multiInitialPrintMm * scale, multiInitialPrintMm)
                    it.rotation = finite(snapAngleWithGuides(multiInitialRot + deltaAngle), it.rotation)
                    invalidate()
                } else if (dragging && selList.isNotEmpty()) {
                    val slot = slotOf(selList[0]) ?: return true
                    // 只累计手指位移，绝不把吸附结果写回基准
                    dragRawDx += (ev.x - lastTouch.x) / slot.rect.width()
                    dragRawDy += (ev.y - lastTouch.y) / slot.rect.height()
                    applyDrag(slot)
                    invalidate()
                }
                // 多选整体移动不跨页：跨页只搬一个元素，选择集就会分裂到两页
                if (dragging && selList.size == 1) updateDropTarget(rawX, rawY)
                lastTouch.set(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val isUp = ev.actionMasked == MotionEvent.ACTION_UP
                if (resizing) {
                    if (isUp) { resizing = false; onModified?.invoke() }
                    else resizing = false
                    resetSnapState()
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
                val dy = ev.y - emptyDownY
                val d = resources.displayMetrics.density
                val canFlip = isUp && !multiActive && !resizing && !reordering
                // 单指横向滑动：整个工作区都生效（落点在照片上也翻页），
                // 只有"正在拖动已选中的元素"时才让位给移动操作
                val swiped = abs(dx) > 70f * d && abs(dx) > abs(dy) * 1.4f
                // 轻点页面左右两侧的空白：按方向翻页
                val tapped = abs(dx) < 12f * d && abs(dy) < 12f * d
                if (canFlip && swiped && !dragging) {
                    onFlip?.invoke(if (dx < 0) 1 else -1)
                } else if (canFlip && tapped && !hasItemAtDown && !dragging) {
                    val pr = visiblePagesRect()
                    if (pr != null) {
                        when {
                            ev.x < pr.left -> onFlip?.invoke(-1)    // 页面左侧空白 → 上一页
                            ev.x > pr.right -> onFlip?.invoke(1)    // 页面右侧空白 → 下一页
                        }
                    }
                }
                multiActive = false
                dragging = false
                resetSnapState()
                invalidate()
            }
        }
        return true
    }

    private fun dist(ev: MotionEvent): Float =
        hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    /** 双指缩放的最小可测量间距：小于它视为两指重合，先不起算（见 MOVE 分支） */
    private fun minPinchPx(): Float = 16f * resources.displayMetrics.density

    /** 数值兜底：任何 NaN / Infinity 都换成 fallback，绝不让它进模型 */
    private fun finite(v: Float, fallback: Float): Float = if (v.isFinite()) v else fallback

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

    private fun beginDrag() {
        dragRawDx = 0f; dragRawDy = 0f
        dragOriginX.clear(); dragOriginY.clear()
        for (it in selList) { dragOriginX.add(it.x); dragOriginY.add(it.y) }
        resetSnapState()
        snapV = null; snapH = null
    }

    /**
     * 把「手指位移 + 吸附修正」写回元素。
     * 每帧都从 dragOrigin 重算，吸附不进入基准，于是继续拖就能离开被吸住的位置。
     */
    private fun applyDrag(slot: Slot) {
        val p = project ?: return
        val page = p.pages.getOrNull(slot.index) ?: return
        if (selList.isEmpty()) return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in selList.indices) {
            val x0 = dragOriginX[i] + dragRawDx
            val y0 = dragOriginY[i] + dragRawDy
            if (x0 < minX) minX = x0
            if (x0 + selList[i].w > maxX) maxX = x0 + selList[i].w
            if (y0 < minY) minY = y0
            if (y0 + selList[i].h > maxY) maxY = y0 + selList[i].h
        }
        val dx = snapAxis(left = minX, w = maxX - minX, tol = tolX(slot), vertical = true,
            slot = slot, page = page, top = minY, h = maxY - minY)
        val dy = snapAxis(left = minY, w = maxY - minY, tol = tolY(slot), vertical = false,
            slot = slot, page = page, top = minX, h = maxX - minX)
        for (i in selList.indices) {
            selList[i].x = dragOriginX[i] + dragRawDx + dx
            selList[i].y = dragOriginY[i] + dragRawDy + dy
        }
    }

    private fun tolX(slot: Slot): Float =
        (SNAP_TOL_DP * resources.displayMetrics.density) /
                (slot.rect.width() * viewScale).coerceAtLeast(1f)

    private fun tolY(slot: Slot): Float =
        (SNAP_TOL_DP * resources.displayMetrics.density) /
                (slot.rect.height() * viewScale).coerceAtLeast(1f)

    /**
     * 单轴吸附：返回该轴的修正量，并记下命中的目标用于画参考线。
     *
     * @param vertical true = 处理 x 轴（竖直参考线）
     * @param slot/page 用于取元素列表、页面尺寸与指示线坐标
     * @param top 另一轴的起点（等距判定要用"是否同一行/同一列"）
     */
    private fun snapAxis(
        left: Float, w: Float, tol: Float, vertical: Boolean,
        slot: Slot, page: Page, top: Float, h: Float
    ): Float {
        val p = project ?: return 0f
        val acc = AxisSnapper()
        // 页面：左右/上下边与中线
        acc.add(0f, SnapKind.PAGE); acc.add(0.5f, SnapKind.PAGE); acc.add(1f, SnapKind.PAGE)
        // 同页其他元素（笔画不参与）
        val others = ArrayList<Item>()
        for (o in page.items) {
            if (o.type == Item.TYPE_STROKE) continue
            if (selList.any { it === o }) continue
            others.add(o)
        }
        for (o in others) {
            if (vertical) {
                acc.add(o.x, SnapKind.ELEMENT)
                acc.add(o.x + o.w / 2f, SnapKind.ELEMENT)
                acc.add(o.x + o.w, SnapKind.ELEMENT)
            } else {
                acc.add(o.y, SnapKind.ELEMENT)
                acc.add(o.y + o.h / 2f, SnapKind.ELEMENT)
                acc.add(o.y + o.h, SnapKind.ELEMENT)
            }
        }
        // 等距：左右（或上下）最近邻居之间的正中
        run {
            var prevEnd = Float.NaN
            var nextStart = Float.NaN
            for (o in others) {
                val oS = if (vertical) o.x else o.y
                val oSz = if (vertical) o.w else o.h
                val oC = if (vertical) o.y else o.x
                val oCz = if (vertical) o.h else o.w
                // 只在另一轴有重叠的元素之间比间距（同一行 / 同一列）
                if (oC + oCz <= top + 1e-4f || oC >= top + h - 1e-4f) continue
                val oE = oS + oSz
                if (oE <= left + 1e-4f) { if (prevEnd.isNaN() || oE > prevEnd) prevEnd = oE }
                if (oS >= left + w - 1e-4f) { if (nextStart.isNaN() || oS < nextStart) nextStart = oS }
            }
            if (!prevEnd.isNaN() && !nextStart.isNaN()) {
                val gap = nextStart - prevEnd - w
                if (gap > 0f) acc.add(prevEnd + gap / 2f, SnapKind.EQUAL)
            }
        }
        // 指示线：近水平参与纵向、近垂直参与横向
        addGuideTargets(acc, vertical, slot, p, left + w / 2f, top + h / 2f)
        // 展览墙：对齐到整厘米
        if (p.type == Project.TYPE_EXHIBITION) {
            val mm = if (vertical) page.widthMm(p) else page.heightMm(p)
            val step = 10f / mm.coerceAtLeast(1f)
            acc.add(round(left / step) * step, SnapKind.GRID)
        }

        val now = android.os.SystemClock.uptimeMillis()
        val coolUntil = if (vertical) coolVUntil else coolHUntil
        if (now < coolUntil) return 0f
        val hit = acc.hit(left, w, tol) ?: run {
            if (vertical) stuckVKind = null else stuckHKind = null
            return 0f
        }
        if (shouldRelease(hit, left, tol, vertical, now)) return 0f
        if (vertical) snapV = hit else snapH = hit
        return hit.delta
    }

    /**
     * 缩放时的单轴吸附。缩放以中心为锚点、两边对称外扩，所以「哪一边先碰到目标」
     * 就按那一边反算尺寸：左边命中 → 宽 = 2×(中心−目标)，右边命中 → 宽 = 2×(目标−中心)。
     * 两边的候选都试，取位移更小的那个。
     *
     * @return 修正后的尺寸与命中的目标（后者用来画参考线）
     */
    private fun snapResizeAxis(
        center: Float, size: Float, tol: Float, vertical: Boolean,
        slot: Slot, page: Page, crossCenter: Float
    ): Pair<Float, AxisSnapper.Hit?> {
        val p = project ?: return size to null
        if (tol <= 0f) return size to null
        val acc = AxisSnapper()
        acc.add(0f, SnapKind.PAGE); acc.add(0.5f, SnapKind.PAGE); acc.add(1f, SnapKind.PAGE)
        for (o in page.items) {
            if (o.type == Item.TYPE_STROKE) continue
            if (selList.any { it === o }) continue
            if (vertical) {
                acc.add(o.x, SnapKind.ELEMENT)
                acc.add(o.x + o.w / 2f, SnapKind.ELEMENT)
                acc.add(o.x + o.w, SnapKind.ELEMENT)
            } else {
                acc.add(o.y, SnapKind.ELEMENT)
                acc.add(o.y + o.h / 2f, SnapKind.ELEMENT)
                acc.add(o.y + o.h, SnapKind.ELEMENT)
            }
        }
        addGuideTargets(acc, vertical, slot, p,
            if (vertical) center else crossCenter,
            if (vertical) crossCenter else center)
        if (p.type == Project.TYPE_EXHIBITION) {
            val mm = if (vertical) page.widthMm(p) else page.heightMm(p)
            val step = 10f / mm.coerceAtLeast(1f)
            // 网格目标取「两条边附近的整厘米线」——取中心没有意义，中心不动
            acc.add(round((center - size / 2f) / step) * step, SnapKind.GRID)
            acc.add(round((center + size / 2f) / step) * step, SnapKind.GRID)
        }

        var bestSize = size
        var bestHit: AxisSnapper.Hit? = null
        var bestScore = Float.MAX_VALUE
        val minSize = 0.02f
        for (t in acc.all) {
            val penalty = snapPenalty(t.kind) * tol
            if (t.value < center - 1e-4f) {
                val s = (center - t.value) * 2f
                if (s >= minSize) {
                    val d = abs(s - size)
                    if (d <= tol && d + penalty < bestScore) {
                        bestScore = d + penalty
                        bestSize = s
                        bestHit = AxisSnapper.Hit(t.value, t.kind, s - size, 0)
                    }
                }
            }
            if (t.value > center + 1e-4f) {
                val s = (t.value - center) * 2f
                if (s >= minSize) {
                    val d = abs(s - size)
                    if (d <= tol && d + penalty < bestScore) {
                        bestScore = d + penalty
                        bestSize = s
                        bestHit = AxisSnapper.Hit(t.value, t.kind, s - size, 2)
                    }
                }
            }
        }
        return bestSize to bestHit
    }

    /** 同一目标上手指继续远离超过 1.6 倍容差 → 放开并冷却 260ms，避免"粘住拖不走"。 */
    private fun shouldRelease(
        hit: AxisSnapper.Hit, raw: Float, tol: Float, vertical: Boolean, now: Long
    ): Boolean {
        val kind = if (vertical) stuckVKind else stuckHKind
        val value = if (vertical) stuckVValue else stuckHValue
        val rawAt = if (vertical) stuckVRaw else stuckHRaw
        if (kind == null || value != hit.value) {
            if (vertical) { stuckVKind = hit.kind; stuckVValue = hit.value; stuckVRaw = raw }
            else { stuckHKind = hit.kind; stuckHValue = hit.value; stuckHRaw = raw }
            return false
        }
        if (abs(raw - rawAt) > 1.6f * tol) {
            if (vertical) { coolVUntil = now + 260; stuckVKind = null }
            else { coolHUntil = now + 260; stuckHKind = null }
            return true
        }
        return false
    }

    private fun addGuideTargets(
        acc: AxisSnapper, vertical: Boolean, slot: Slot, p: Project,
        cxN: Float, cyN: Float
    ) {
        if (p.guides.isEmpty()) return
        val center = contentCenterPx() ?: return
        val page = p.pages.getOrNull(slot.index) ?: return
        val wmm = page.widthMm(p).coerceAtLeast(1f)
        val pxPerMm = slot.rect.width() / wmm
        for (g in p.guides) {
            val rad = Math.toRadians(g.angle.toDouble())
            val nx = -sin(rad).toFloat(); val ny = cos(rad).toFloat()
            val px = center.x + g.axMm * pxPerMm
            val py = center.y + g.ayMm * pxPerMm
            if (vertical) {
                // 接近竖直：x 为常数
                if (abs(abs(g.angle) - 90f) <= 1f)
                    acc.add((px - slot.rect.left) / slot.rect.width(), SnapKind.GUIDE)
            } else {
                // 接近水平：取元素中心 x 处的线高（允许 1° 内的轻微倾斜）
                if (abs(g.angle) <= 1f) {
                    val tx = slot.rect.left + cxN * slot.rect.width()
                    val yPx = py + tan(rad).toFloat() * (tx - px)
                    acc.add((yPx - slot.rect.top) / slot.rect.height(), SnapKind.GUIDE)
                }
            }
        }
    }

    /** 可视内容（单页或对开）的中心，单位内容像素 */
    private fun contentCenterPx(): PointF? {
        if (slots.isEmpty()) return null
        var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE
        var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in slots) {
            l = min(l, s.rect.left); r = max(r, s.rect.right)
            t = min(t, s.rect.top); b = max(b, s.rect.bottom)
        }
        return PointF((l + r) / 2f, (t + b) / 2f)
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

    // 手柄缩放起点：照片锁定原比例缩放，其余元素保持自由缩放
    private var resizeBaseW = 0f
    private var resizeBaseH = 0f
    private var resizeLockAspect = false

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
        return hypot(vx - hx, vy - hy) <= 20f * resources.displayMetrics.density
    }

    /**
     * 拖拽右下角手柄调节大小，中心保持不动：
     * - **照片：只按原图比例等比缩放**（不裁切、不变形）——取手指在两轴上更大的缩放意愿，
     *   对起始宽高等比乘同一个系数，比例天然守恒
     * - 文字 / 色块 / 笔画：宽高各自独立，自由缩放
     * 墙上的照片实际宽度跟随宽度轴缩放。
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
        val oldW = it.w
        val page = project?.pages?.getOrNull(slot.index) ?: return
        // 旋转后元素的「左右边」不再对应竖直参考线，吸附会显得乱跳；只在正立时吸
        val rot = ((it.rotation % 360f) + 360f) % 360f
        val axisAligned = rot < 1f || abs(rot - 180f) < 1f || rot > 359f
        snapV = null; snapH = null
        var w: Float
        var h: Float

        if (resizeLockAspect && resizeBaseW > 0f && resizeBaseH > 0f) {
            // 照片：以起始尺寸等比缩放，比例严格保持
            val wantW = lx * 2f / slot.rect.width()
            val wantH = ly * 2f / slot.rect.height()
            val wantScale = Math.max(wantW / resizeBaseW, wantH / resizeBaseH)
            val minScale = 0.02f / Math.min(resizeBaseW, resizeBaseH)
            val maxScale = 8f / Math.max(resizeBaseW, resizeBaseH)
            val sc = wantScale.coerceAtLeast(minScale).coerceAtMost(
                if (maxScale > minScale) maxScale else minScale)
            w = resizeBaseW * sc
            h = resizeBaseH * sc

            // 等比缩放时两轴共用一个系数：两个轴各自算一个候选系数，取离手指更近的那个
            if (axisAligned) {
                val (wc, hx) = snapResizeAxis(cxN, w, tolX(slot), true, slot, page, cyN)
                val (hc, hy) = snapResizeAxis(cyN, h, tolY(slot), false, slot, page, cxN)
                val dxPx = if (hx != null) abs(wc - w) * slot.rect.width() * viewScale
                else Float.MAX_VALUE
                val dyPx = if (hy != null) abs(hc - h) * slot.rect.height() * viewScale
                else Float.MAX_VALUE
                if (dxPx <= dyPx && hx != null) {
                    w = wc.coerceIn(0.02f, 8f)
                    h = (resizeBaseH * (w / resizeBaseW)).coerceIn(0.02f, 8f)
                    snapV = hx
                } else if (hy != null) {
                    h = hc.coerceIn(0.02f, 8f)
                    w = (resizeBaseW * (h / resizeBaseH)).coerceIn(0.02f, 8f)
                    snapH = hy
                }
            }
        } else {
            w = (lx * 2f / slot.rect.width()).coerceIn(0.02f, 8f)
            h = (ly * 2f / slot.rect.height()).coerceIn(0.02f, 8f)
            if (axisAligned) {
                val (wc, hx) = snapResizeAxis(cxN, w, tolX(slot), true, slot, page, cyN)
                val (hc, hy) = snapResizeAxis(cyN, h, tolY(slot), false, slot, page, cxN)
                w = wc.coerceIn(0.02f, 8f)
                h = hc.coerceIn(0.02f, 8f)
                snapV = hx; snapH = hy
            }
        }
        it.w = finite(w, it.w)
        it.h = finite(h, it.h)
        // 墙上照片：实际打印宽度跟随宽度轴
        if (it.printMm != null && oldW > 0f)
            it.printMm = finite(it.printMm!! * (it.w / oldW), it.printMm!!)
        // 中心保持
        it.x = finite(cxN - it.w / 2f, it.x)
        it.y = finite(cyN - it.h / 2f, it.y)
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
                item, item.w * slot.rect.width(), slot.rect.height(), context) /
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

    // ---------------- 对齐命令 ----------------

    /** 各自对齐到页面的九宫格：h 0左 1中 2右，v 0上 1中 2下（-1 = 该轴不动） */
    fun alignToPage(h: Int, v: Int): Boolean {
        if (selList.isEmpty()) return false
        for (it in selList) {
            when (h) {
                0 -> it.x = 0f
                1 -> it.x = 0.5f - it.w / 2f
                2 -> it.x = 1f - it.w
            }
            when (v) {
                0 -> it.y = 0f
                1 -> it.y = 0.5f - it.h / 2f
                2 -> it.y = 1f - it.h
            }
        }
        onModified?.invoke()
        invalidate()
        return true
    }

    /** 相对选区外框对齐：h 0左 1水平居中 2右，v 0上 1垂直居中 2下（-1 = 该轴不动） */
    fun alignToSelection(h: Int, v: Int): Boolean {
        if (selList.size < 2) return false
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (it in selList) {
            minX = min(minX, it.x); maxX = max(maxX, it.x + it.w)
            minY = min(minY, it.y); maxY = max(maxY, it.y + it.h)
        }
        for (it in selList) {
            when (h) {
                0 -> it.x = minX
                1 -> it.x = (minX + maxX) / 2f - it.w / 2f
                2 -> it.x = maxX - it.w
            }
            when (v) {
                0 -> it.y = minY
                1 -> it.y = (minY + maxY) / 2f - it.h / 2f
                2 -> it.y = maxY - it.h
            }
        }
        onModified?.invoke()
        invalidate()
        return true
    }

    /** 等距分布：首尾不动，中间按「边缘间距相等」排开 */
    fun distribute(horizontal: Boolean): Boolean {
        if (selList.size < 3) return false
        val sorted = selList.sortedBy { if (horizontal) it.x else it.y }
        val first = sorted.first(); val last = sorted.last()
        val startEdge = if (horizontal) first.x + first.w else first.y + first.h
        val endEdge = if (horizontal) last.x else last.y
        var middleSize = 0f
        for (i in 1 until sorted.size - 1)
            middleSize += if (horizontal) sorted[i].w else sorted[i].h
        val gap = (endEdge - startEdge - middleSize) / (sorted.size - 1)
        var cursor = startEdge + gap
        for (i in 1 until sorted.size - 1) {
            val it = sorted[i]
            if (horizontal) it.x = cursor else it.y = cursor
            cursor += (if (horizontal) it.w else it.h) + gap
        }
        onModified?.invoke()
        invalidate()
        return true
    }

    /** 以第一个选中项为基准同宽 / 同高。照片按比例联动，绝不拉伸变形。 */
    fun matchSize(width: Boolean): Boolean {
        if (selList.size < 2) return false
        val ref = selList[0]
        for (i in 1 until selList.size) {
            val it = selList[i]
            if (it.type == Item.TYPE_PHOTO && it.h > 0f && it.w > 0f) {
                val ar = it.w / it.h
                if (width) { it.w = ref.w; it.h = it.w / ar }
                else { it.h = ref.h; it.w = it.h * ar }
            } else {
                if (width) it.w = ref.w else it.h = ref.h
            }
        }
        onModified?.invoke()
        invalidate()
        return true
    }

    /** 展览墙：把选中项左上角对齐到整厘米 */
    fun snapSelectionToCm(): Boolean {
        val p = project ?: return false
        if (p.type != Project.TYPE_EXHIBITION) return false
        val page = selList.firstOrNull()?.let { pageOf(it) } ?: return false
        val stepX = 10f / page.widthMm(p).coerceAtLeast(1f)
        val stepY = 10f / page.heightMm(p).coerceAtLeast(1f)
        for (it in selList) {
            it.x = round(it.x / stepX) * stepX
            it.y = round(it.y / stepY) * stepY
        }
        onModified?.invoke()
        invalidate()
        return true
    }
}

package com.yussy.folior.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.model.heightMm
import com.yussy.folior.model.widthMm

/**
 * 看片台（Light Table）：
 * 全工作区平铺所有页面缩略图，替代条状预览栏做大跨度排序。
 * 参考 Keynote / PowerPoint「看片台」与 InDesign 页面缩略图网格的交互。
 *
 * 设计要点：
 * - 网格列数按可用宽度自适应（目标格宽约 80dp）：竖屏约 4 列、横屏约 9 列
 * - **统一格尺寸**：格子高度按项目基准页比例计算，各页缩略图按自身比例在格内居中 ——
 *   摄影书与展览墙混用不同比例（3:2 / 1:1 / 2:1 / 2:3）时依然整齐成行
 * - 长按拖动排序（支持跨行、跨屏，拖到边缘自动滚动），松手回调新位置
 * - 点击缩略图进入该页
 * - 缩略图 LRU 缓存 + 分帧渲染节流（每帧最多 2 张），展开动画不掉帧
 */
class LightTable @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null
) : RecyclerView(context, attrs) {

    var project: Project? = null
        set(value) {
            val sameProject = field != null && value != null && field?.id == value.id
            field = value
            if (!sameProject) {
                // 只在切换项目时清缓存：同一项目收回再展开直接命中，不再重渲染
                thumbCache.clear()
                resetRenderQueue()
            }
            tableAdapter.notifyDataSetChanged()
        }

    /** 当前页（朱红高亮） */
    var currentIndex = 0
        set(value) {
            val old = field
            field = value
            if (old != value) {
                if (old in 0 until tableAdapter.itemCount) tableAdapter.notifyItemChanged(old)
                if (value in 0 until tableAdapter.itemCount) tableAdapter.notifyItemChanged(value)
            }
        }

    /** 项目基准页宽高比（宽/高），决定统一格高 */
    var baseAspect = 1.5f

    var onPageClicked: ((Int) -> Unit)? = null
    /** 拖动排序结束：参数为拖动结束后的位置 */
    var onReordered: ((Int) -> Unit)? = null

    // ---------- 格子尺寸（px，layoutCells 计算） ----------
    private var cols = 4
    private var cellW = 0
    private var cellH = 0
    private var imgH = 0
    private var labelH = 0
    private var gap = 0

    private var dragPos = -1
    private var dragMoved = false

    private val tableAdapter = TableAdapter()

    // 缩略图缓存：访问序 LRU 60 条（看片台一屏可达 40 张）
    private val thumbCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 80
    }

    // 分帧渲染队列：避免一次性画几十张缩略图卡住展开动画
    private class RenderTask(
        val page: Page, val key: String, val w: Int, val h: Int, val iv: ImageView
    )
    private val renderQueue = ArrayDeque<RenderTask>()
    private val renderHandler = Handler(Looper.getMainLooper())
    private var renderRunning = false
    private val renderDrain = object : Runnable {
        override fun run() {
            try {
                var n = 0
                while (renderQueue.isNotEmpty() && n < 3) {
                    val t = renderQueue.removeFirst()
                    n++
                    // 身份校验：ImageView 已被复用给其他页 → 丢弃该任务（否则图会错位/覆盖）
                    if (t.iv.tag != t.page.id) continue
                    val hit = thumbCache[t.key]
                    if (hit != null) {
                        t.iv.setImageBitmap(hit)
                        continue
                    }
                    val p = project ?: continue
                    val bmp = try {
                        PageRenderer.renderThumb(context, p, t.page, t.w, t.h)
                    } catch (e: Throwable) {
                        null   // 单张失败（OOM 等）不能拖垮整个队列
                    }
                    if (bmp != null) {
                        thumbCache[t.key] = bmp
                        // 渲染期间可能已被复用：再校验一次
                        if (t.iv.tag == t.page.id) t.iv.setImageBitmap(bmp)
                    }
                }
                if (renderQueue.isNotEmpty()) {
                    renderHandler.postDelayed(this, 16)
                } else {
                    renderRunning = false
                }
            } catch (e: Throwable) {
                // 兜底自愈：任何意外都复位状态，避免"渲染永久停摆"（缩略图不再加载）
                resetRenderQueue()
            }
        }
    }

    /** 清空渲染队列并复位运行标志（project 切换 / 重算格子 / 主动刷新时调用） */
    private fun resetRenderQueue() {
        renderHandler.removeCallbacks(renderDrain)
        renderQueue.clear()
        renderRunning = false
    }

    init {
        setBackgroundColor(0xFFF4F4EE.toInt())
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // 格子尺寸由 layoutCells 统一指定，RecyclerView 自身尺寸不随内容变化
        setHasFixedSize(true)
        // 预缓存更多离屏 holder，滚动时减少重绑定
        setItemViewCacheSize(12)
        val d = resources.displayMetrics.density
        gap = (7 * d).toInt()
        labelH = (18 * d).toInt()
        setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        addItemDecoration(GridSpacing())
        adapter = tableAdapter
        layoutManager = GridLayoutManager(context, cols)

        // 长按拖动 = 调整页序（四方向均可，跨行/跨屏；拖到边缘自动滚动）
        val ith = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val pages = project?.pages ?: return false
                if (from !in pages.indices || to !in pages.indices) return false
                pages.add(to, pages.removeAt(from))
                tableAdapter.notifyItemMoved(from, to)
                dragPos = to
                dragMoved = true
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    dragPos = vh.bindingAdapterPosition
                    dragMoved = false
                    vh.itemView.alpha = 0.92f
                    vh.itemView.scaleX = 1.06f
                    vh.itemView.scaleY = 1.06f
                    vh.itemView.elevation = 12f * resources.displayMetrics.density
                }
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                vh.itemView.alpha = 1f
                vh.itemView.scaleX = 1f
                vh.itemView.scaleY = 1f
                vh.itemView.elevation = 0f
                // 只有真正发生过移动才回调，避免"长按未移动就松手"误触发排序刷新
                val pos = dragPos
                val moved = dragMoved
                dragPos = -1
                dragMoved = false
                if (moved && pos >= 0) onReordered?.invoke(pos)
            }
        })
        ith.attachToRecyclerView(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { layoutCells() }
    }

    /** 按当前宽高重算列数与格子尺寸；横竖屏切换后自动调用 */
    fun layoutCells() {
        val d = resources.displayMetrics.density
        if (width <= 0 || height <= 0) return
        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom
        if (availW <= 0 || availH <= 0) return

        // 目标格宽 80dp：竖屏约 4 列、横屏约 9 列，保证一屏装下的页数足够多
        val target = 80f * d
        val newCols = (availW / target).toInt().coerceIn(3, 11)
        // 子项宽度由 GridLayoutManager 均分；这里只算内容宽（扣掉 decoration 的左右 gap）
        val newCellW = (availW / newCols) - gap
        val aspect = baseAspect.coerceIn(0.55f, 1.9f)
        var newImgH = (newCellW / aspect).toInt()
        var newCellH = newImgH + labelH

        // 至少保证 2 行可见：若基准比例把行数压到 1 行，只缩格高（缩略图在格内按比例缩小），
        // 不缩格宽 —— 否则列会缩窄、右侧留白
        val rows = (availH + gap) / (newCellH + gap)
        if (rows < 2) {
            val maxCellH = (availH - gap) / 2 - gap
            if (maxCellH > labelH + (12 * d).toInt()) {
                newCellH = maxCellH
                newImgH = newCellH - labelH
            }
        }

        if (newCols != cols || newCellW != cellW || newCellH != cellH) {
            cols = newCols
            cellW = newCellW
            cellH = newCellH
            imgH = newImgH
            (layoutManager as? GridLayoutManager)?.spanCount = cols
            thumbCache.clear()
            resetRenderQueue()
            tableAdapter.notifyDataSetChanged()
        }
    }

    private var preloadThread: Thread? = null
    private var preloadedProjectId: String? = null

    /** 估算展开后的格子尺寸（未展开时也能算，供后台预渲染使用） */
    fun estimateCellSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val d = dm.density
        val availW = dm.widthPixels - paddingLeft - paddingRight
        if (availW <= 0) return 0 to 0
        val cols = (availW / (80f * d)).toInt().coerceIn(3, 11)
        val cellW = (availW / cols) - gap
        val imgH = (cellW / baseAspect.coerceIn(0.55f, 1.9f)).toInt()
        return cellW to imgH
    }

    /**
     * 项目打开后在后台逐页预渲染缩略图（每个项目只跑一次）。
     * 渲染结果 post 回主线程再写入 LRU——解码在后台（Store 已加锁），缓存只在主线程改。
     */
    fun preloadInBackground(p: Project, aspect: Float) {
        if (preloadedProjectId == p.id) return
        preloadedProjectId = p.id
        baseAspect = aspect
        val (w, h) = estimateCellSize()
        if (w <= 0 || h <= 0) return
        val pages = p.pages.toList()
        if (pages.isEmpty()) return
        preloadThread?.interrupt()
        preloadThread = Thread {
            for (page in pages) {
                if (Thread.currentThread().isInterrupted) return@Thread
                val key = "${page.id}_${w}x$h"
                val bmp = try {
                    PageRenderer.renderThumb(context, p, page, w, h)
                } catch (_: Throwable) {
                    null
                } ?: continue
                renderHandler.post {
                    if (thumbCache[key] == null) thumbCache[key] = bmp
                }
                // 让出 CPU：预加载不抢界面渲染的时间片
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "lighttable-preload"
            start()
        }
    }

    /** 停止后台预加载（界面销毁时调用） */
    fun stopPreload() {
        preloadThread?.interrupt()
        preloadThread = null
        preloadedProjectId = null
    }

    /** 展开后定位到当前页所在行 */
    fun scrollToCurrent() {
        post {
            val lm = layoutManager ?: return@post
            if (currentIndex in 0 until (project?.pages?.size ?: 0)) {
                lm.scrollToPosition(currentIndex)
            }
        }
    }

    fun refresh() {
        thumbCache.clear()
        resetRenderQueue()
        tableAdapter.notifyDataSetChanged()
    }

    // ---------------- 网格间隙 ----------------

    /** 网格间隙：每格左右各 gap/2（所有列一致）、底部 gap；宽度交由 GridLayoutManager 均分 */
    private inner class GridSpacing : ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: State
        ) {
            outRect.left = gap / 2
            outRect.right = gap / 2
            outRect.top = 0
            outRect.bottom = gap
        }
    }

    // ---------------- 列表 ----------------

    private inner class TableAdapter : RecyclerView.Adapter<CellVH>() {

        override fun getItemCount(): Int = project?.pages?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellVH {
            val cell = FrameLayout(context).apply {
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            cell.addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
            val badge = TextView(context).apply {
                textSize = 9.5f
                gravity = Gravity.CENTER
                setTextColor(0xFF8A8A84.toInt())
            }
            cell.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (18 * resources.displayMetrics.density).toInt())
                .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
            return CellVH(cell, iv, badge)
        }

        override fun onBindViewHolder(holder: CellVH, pos: Int) {
            val p = project ?: return
            val page = p.pages.getOrNull(pos) ?: return
            val d = resources.displayMetrics.density
            if (cellW <= 0) { post { layoutCells() }; return }

            // 统一格子：宽度由 Grid 均分（MATCH_PARENT），高度按基准比例算出的 cellH
            holder.itemView.layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cellH)
            holder.thumb.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, imgH,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL)

            // tag 用于渲染队列的身份校验：View 复用时随之改变，旧任务自动失效
            holder.thumb.tag = page.id
            val key = "${page.id}_${cellW}x${imgH}"
            val cached = thumbCache[key]
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageBitmap(null)
                // 注意：不在此清空队列 —— 清空会丢掉尚未渲染的任务且不重绑定，
                // 造成"永久空白"。失效任务在 drain 里被 tag 校验瞬时丢弃，不会积压。
                renderQueue.addLast(RenderTask(page, key, cellW, imgH, holder.thumb))
                if (!renderRunning) {
                    renderRunning = true
                    renderHandler.post(renderDrain)
                }
            }

            val selected = pos == currentIndex
            val isDragging = pos == dragPos && dragPos >= 0
            holder.thumb.background = GradientDrawable().apply {
                setColor(if (isDragging) 0x1FB5442A else 0xFFFFFFFF.toInt())
                setStroke(
                    ((if (selected || isDragging) 2f else 1f) * d).toInt(),
                    when {
                        isDragging -> 0xFFB5442A.toInt()
                        selected -> 0xFFB5442A.toInt()
                        else -> 0x1A000000
                    })
                cornerRadius = 1.5f * d
            }
            holder.badge.text = "${pos + 1}"
            holder.badge.setTextColor(
                if (selected) 0xFFB5442A.toInt() else 0xFF8A8A84.toInt())
            holder.itemView.contentDescription = "第${pos + 1}页"
            holder.itemView.setOnClickListener {
                val pp = holder.bindingAdapterPosition
                if (pp >= 0) onPageClicked?.invoke(pp)
            }
        }
    }

    private class CellVH(
        root: FrameLayout, val thumb: ImageView, val badge: TextView
    ) : RecyclerView.ViewHolder(root)
}

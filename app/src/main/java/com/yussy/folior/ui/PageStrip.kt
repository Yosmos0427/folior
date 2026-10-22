package com.yussy.folior.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yussy.folior.model.Project

/**
 * 页面预览栏（复合控件）：
 *   [ 固定「圆圈加号」按钮区 ] + [ 可滚动页面缩略图列表 ]
 * - 竖屏：横向栏，按钮区固定在左端；单元格为「缩略图在上、序号在下」
 * - 横屏：纵向栏，按钮区固定在顶端；单元格为「缩略图在左、序号在右」，更紧凑
 * 缩略图尺寸按栏内可用空间自适应，保持页面比例。
 */
class PageStrip @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var project: Project? = null
    var currentIndex: Int = 0
    var verticalMode = false
        private set

    var onPageSelected: ((Int) -> Unit)? = null
    var onPageReordered: ((Int) -> Unit)? = null
    var onPageAdded: (() -> Unit)? = null

    /** 长按拖拽页面时的回调：屏幕坐标，供外层做"拖到删除框"判定 */
    var onDragStart: ((pageId: String) -> Unit)? = null
    var onDragMove: ((x: Float, y: Float) -> Unit)? = null
    var onDragEnd: ((pageId: String, x: Float, y: Float) -> Unit)? = null

    private var draggedPageId: String? = null
    private var lastDragX = 0f
    private var lastDragY = 0f

    private val thumbCache = HashMap<String, android.graphics.Bitmap>()
    private var cellW = 0
    private var cellH = 0

    private val stripAdapter = StripAdapter()
    private val rv: RecyclerView
    private val addArea: FrameLayout
    private val addIcon: ImageView
    private val divider: View

    init {
        orientation = HORIZONTAL
        setBackgroundColor(0xFFFAFAF7.toInt())
        val d = resources.displayMetrics.density

        // ---- 固定按钮区（独立于滚动容器）----
        addArea = FrameLayout(context).apply {
            setBackground(RippleDrawable(
                ColorStateList.valueOf(0x141A1A18),
                GradientDrawable().apply { setColor(Color.TRANSPARENT) },
                null))
            contentDescription = "新增空白页"
            isClickable = true
            isFocusable = true
            setOnClickListener { onPageAdded?.invoke() }
        }
        addIcon = ImageView(context).apply {
            setImageResource(com.yussy.folior.R.drawable.ic_add_circle)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.9f
        }
        addArea.addView(addIcon, FrameLayout.LayoutParams(
            (26 * d).toInt(), (26 * d).toInt(), Gravity.CENTER))

        divider = View(context).apply { setBackgroundColor(0xFFE6E6E0.toInt()) }

        // ---- 滚动列表 ----
        rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = stripAdapter
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        addView(addArea)
        addView(divider)
        addView(rv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val ith = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(r: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (verticalMode)
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                else makeMovementFlags(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val pages = project?.pages ?: return false
                if (from !in pages.indices || to !in pages.indices) return false
                val p = pages.removeAt(from)
                pages.add(to, p)
                stripAdapter.notifyItemMoved(from, to)
                currentIndex = to
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    vh.itemView.alpha = 0.55f
                    vh.itemView.scaleX = 1.04f; vh.itemView.scaleY = 1.04f
                    draggedPageId = project?.pages?.getOrNull(vh.bindingAdapterPosition)?.id
                    val loc = IntArray(2)
                    vh.itemView.getLocationOnScreen(loc)
                    lastDragX = loc[0] + vh.itemView.width / 2f
                    lastDragY = loc[1] + vh.itemView.height / 2f
                    draggedPageId?.let { onDragStart?.invoke(it) }
                }
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                vh.itemView.alpha = 1f
                vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f
                draggedPageId?.let { onDragEnd?.invoke(it, lastDragX, lastDragY) }
                draggedPageId = null
                onPageReordered?.invoke(currentIndex)
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, r: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (isCurrentlyActive && vh.bindingAdapterPosition >= 0) {
                    val loc = IntArray(2)
                    vh.itemView.getLocationOnScreen(loc)
                    lastDragX = loc[0] + vh.itemView.width / 2f + dX
                    lastDragY = loc[1] + vh.itemView.height / 2f + dY
                    onDragMove?.invoke(lastDragX, lastDragY)
                }
                super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive)
            }
        })
        ith.attachToRecyclerView(rv)

        applyMode()
    }

    fun setVertical(vertical: Boolean) {
        if (verticalMode == vertical) { refresh(); return }
        verticalMode = vertical
        rv.layoutManager = LinearLayoutManager(
            context,
            if (vertical) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL,
            false)
        // 两种方向的单元格结构不同，必须丢弃旧 ViewHolder
        rv.recycledViewPool.clear()
        applyMode()
        refresh()
    }

    private fun applyMode() {
        val d = resources.displayMetrics.density
        orientation = if (verticalMode) VERTICAL else HORIZONTAL
        if (verticalMode) {
            // 竖栏：按钮区在顶部（紧凑 44dp）
            addArea.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt())
            divider.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, (0.7f * d).toInt()))
            (rv.layoutParams as LayoutParams).apply { width = LayoutParams.MATCH_PARENT; height = 0; weight = 1f }
            rv.setPadding((3 * d).toInt(), (2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt())
            addIcon.layoutParams = FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER)
        } else {
            // 横栏：按钮区在左端（紧凑 68dp）
            addArea.layoutParams = LayoutParams((68 * d).toInt(), LayoutParams.MATCH_PARENT)
            divider.layoutParams = LayoutParams(Math.max(1, (0.7f * d).toInt()), LayoutParams.MATCH_PARENT)
            (rv.layoutParams as LayoutParams).apply { width = 0; height = LayoutParams.MATCH_PARENT; weight = 1f }
            rv.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            addIcon.layoutParams = FrameLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt(), Gravity.CENTER)
        }
        addArea.requestLayout()
        divider.requestLayout()
        rv.requestLayout()
    }

    /** 依据可用空间计算缩略图尺寸（保持页面比例）。 */
    private fun computeCellSize() {
        val p = project ?: return
        if (rv.width == 0 || rv.height == 0) return
        val d = resources.displayMetrics.density
        val aspect = p.pageWmm / p.pageHmm
        if (!verticalMode) {
            // 横栏：高度决定尺寸，底部留序号
            val availH = rv.height - rv.paddingTop - rv.paddingBottom - (17 * d).toInt()
            var h = availH.coerceAtLeast((24 * d).toInt())
            var w = (h * aspect).toInt()
            val maxW = (54 * d).toInt()
            if (w > maxW) { w = maxW; h = (w / aspect).toInt() }
            cellW = w; cellH = h
        } else {
            // 竖栏：宽度决定尺寸，右侧留序号
            val availW = rv.width - rv.paddingLeft - rv.paddingRight - (18 * d).toInt()
            var w = availW.coerceAtMost((44 * d).toInt()).coerceAtLeast((18 * d).toInt())
            var h = (w / aspect).toInt()
            val maxH = (84 * d).toInt()
            if (h > maxH) { h = maxH; w = (h * aspect).toInt() }
            cellW = w; cellH = h
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            val oldW = cellW; val oldH = cellH
            computeCellSize()
            if (oldW != cellW || oldH != cellH) {
                thumbCache.clear()
                stripAdapter.notifyDataSetChanged()
            }
        }
    }

    fun refresh() {
        computeCellSize()
        thumbCache.clear()
        stripAdapter.notifyDataSetChanged()
        post { rv.scrollToPosition(currentIndex.coerceIn(0, (project?.pages?.size ?: 1) - 1)) }
    }

    fun setCurrent(idx: Int) {
        currentIndex = idx
        stripAdapter.notifyDataSetChanged()
    }

    // ---------------- 列表 ----------------

    inner class StripAdapter : RecyclerView.Adapter<StripVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripVH {
            val d = resources.displayMetrics.density
            val cell = LinearLayout(context).apply {
                orientation = if (verticalMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (verticalMode) setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                else setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
            cell.layoutParams = RecyclerView.LayoutParams(
                if (verticalMode) RecyclerView.LayoutParams.MATCH_PARENT
                else RecyclerView.LayoutParams.WRAP_CONTENT,
                if (verticalMode) RecyclerView.LayoutParams.WRAP_CONTENT
                else RecyclerView.LayoutParams.MATCH_PARENT)
            return StripVH(cell)
        }

        override fun getItemCount(): Int = project?.pages?.size ?: 0

        override fun getItemViewType(position: Int): Int = if (verticalMode) 1 else 0

        override fun onBindViewHolder(holder: StripVH, pos: Int) {
            val p = project ?: return
            val page = p.pages.getOrNull(pos) ?: return
            val d = resources.displayMetrics.density
            if (cellW <= 0 || cellH <= 0) computeCellSize()
            val thumbW = cellW.coerceAtLeast(8)
            val thumbH = cellH.coerceAtLeast(8)

            val lp = holder.thumb.layoutParams as LayoutParams
            lp.width = thumbW
            lp.height = thumbH
            holder.thumb.layoutParams = lp

            val key = "${page.id}_${thumbW}x$thumbH"
            val cached = thumbCache[key]
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageBitmap(null)
                holder.thumb.post {
                    if (project === p && p.pages.getOrNull(holder.bindingAdapterPosition) === page) {
                        val bmp = PageRenderer.renderThumb(context, p, page, thumbW, thumbH)
                        thumbCache[key] = bmp
                        holder.thumb.setImageBitmap(bmp)
                    }
                }
            }

            holder.thumb.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(((if (pos == currentIndex) 2f else 1f) * d).toInt(),
                    if (pos == currentIndex) 0xFFB5442A.toInt() else 0x14000000)
                cornerRadius = 1.5f * d
            }
            holder.thumb.contentDescription = "第${pos + 1}页"

            holder.badge.text = "${pos + 1}"
            holder.badge.setTextColor(
                if (pos == currentIndex) 0xFFB5442A.toInt() else 0xFF8A8A84.toInt())
            holder.badge.width = (16 * d).toInt()
            holder.badge.setPadding(
                if (verticalMode) (3 * d).toInt() else 0, 0, 0, 0)

            holder.itemView.setOnClickListener {
                currentIndex = pos
                onPageSelected?.invoke(pos)
                setCurrent(pos)
            }
        }
    }

    inner class StripVH(cell: LinearLayout) : RecyclerView.ViewHolder(cell) {
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val badge = TextView(context).apply {
            textSize = 9.5f
            gravity = if (verticalMode) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
        }

        init {
            cell.addView(thumb)
            cell.addView(badge)
        }
    }
}

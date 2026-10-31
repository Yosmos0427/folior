package com.yussy.folior.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
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
import com.yussy.folior.model.heightMm
import com.yussy.folior.model.renderSignature
import com.yussy.folior.model.widthMm

/**
 * 页面预览栏：
 *   [ 固定「圆圈加号」按钮区 ] + [ 可滚动页面缩略图列表 ]
 * - 竖屏横向栏：缩略图在上、序号在下；**上滑呼出删除按钮**
 * - 横屏纵向栏：缩略图在左、序号在右；**右滑呼出删除按钮**
 * - 长按拖动调整页序；工作区拖照片到某格可转移（pageAt/setDragTarget）
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
    /** 长按开始拖动页序时回调（外部据此记录撤销起点，整个拖动算一步） */
    var onReorderStart: (() -> Unit)? = null
    var onPageAdded: (() -> Unit)? = null

    /** 滑动呼出删除按钮后点击删除：参数为页索引 */
    var onStripDelete: ((Int) -> Unit)? = null

    /** 已呼出删除按钮的页 id（用 id 而非位置：滚动、重排、View 复用后都能正确匹配） */
    private var revealedPageId: String? = null

    /** 工作区拖照片悬停的目标格（高亮），-1 = 无 */
    private var dragTargetPos = -1

    /** 正在被长按拖动的页 id（拖动期间触摸逻辑完全让位，避免打断拖动） */
    private var draggingPageId: String? = null

    // 缩略图缓存：访问序 LRU，上限 40 条（页面很多时不再无界增长）
    private val thumbCache = object : LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>) =
            size > 40
    }
    private var availW = 0
    private var availH = 0

    private val stripAdapter = StripAdapter()
    private val rv: RecyclerView
    private val addArea: FrameLayout
    private val addIcon: ImageView
    private val divider: View

    init {
        orientation = HORIZONTAL
        setBackgroundColor(0xFFFAFAF7.toInt())
        val d = resources.displayMetrics.density

        addArea = FrameLayout(context).apply {
            setBackground(RippleDrawable(
                ColorStateList.valueOf(0x141A1A18),
                GradientDrawable().apply { setColor(Color.TRANSPARENT) },
                null))
            contentDescription = "新增"
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

        // 长按拖动 = 调整页序（滑动删除走"滑动呼出按钮"，二者不冲突）
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
                    onReorderStart?.invoke()
                    vh.itemView.alpha = 0.55f
                    vh.itemView.scaleX = 1.04f; vh.itemView.scaleY = 1.04f
                    // 注意：这里绝不能 notifyDataSetChanged —— 拖动刚启动时重排会让
                    // ItemTouchHelper 立刻丢失拖动态（表现为"震动一下就放回"）。
                    // 只清状态并就地复位该项视觉，界面在拖动结束的 clearView 里统一刷新。
                    revealedPageId = null
                    draggingPageId = (vh as? StripVH)?.let {
                        project?.pages?.getOrNull(it.bindingAdapterPosition)?.id
                    }
                    (vh as? StripVH)?.let {
                        it.deleteOverlay.visibility = View.INVISIBLE
                    }
                }
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                vh.itemView.alpha = 1f
                vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f
                draggingPageId = null
                onPageReordered?.invoke(currentIndex)
                stripAdapter.notifyDataSetChanged()
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
        rv.recycledViewPool.clear()
        revealedPageId = null
        draggingPageId = null
        dragTargetPos = -1
        applyMode()
        refresh()
    }

    private fun applyMode() {
        val d = resources.displayMetrics.density
        orientation = if (verticalMode) VERTICAL else HORIZONTAL
        if (verticalMode) {
            addArea.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt())
            divider.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, (0.7f * d).toInt()))
            (rv.layoutParams as LayoutParams).apply { width = LayoutParams.MATCH_PARENT; height = 0; weight = 1f }
            rv.setPadding((3 * d).toInt(), (2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt())
            addIcon.layoutParams = FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER)
        } else {
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

    private fun computeAvail() {
        val d = resources.displayMetrics.density
        if (rv.width == 0 || rv.height == 0) return
        if (!verticalMode) {
            availH = rv.height - rv.paddingTop - rv.paddingBottom - (17 * d).toInt()
        } else {
            availW = rv.width - rv.paddingLeft - rv.paddingRight - (22 * d).toInt()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            computeAvail()
            thumbCache.clear()
            stripAdapter.notifyDataSetChanged()
        }
    }

    /**
     * 刷新缩略图（不改变滚动位置）。
     * 不清空缓存：缓存的 key 含页面渲染指纹，内容未变的页直接命中旧位图，
     * 只有指纹变化的页才会重新渲染（改页面设置只重画本页，改项目设置才波及全部）。
     */
    fun refresh() {
        computeAvail()
        stripAdapter.notifyDataSetChanged()
    }

    /** 把预览条滚动到当前页（切页 / 新增页时显式调用；删除页时不调用，保持原位不跳动） */
    fun scrollToCurrent() {
        post {
            val n = project?.pages?.size ?: 0
            if (n > 0) rv.scrollToPosition(currentIndex.coerceIn(0, n - 1))
        }
    }

    fun setCurrent(idx: Int) {
        currentIndex = idx
        stripAdapter.notifyDataSetChanged()
    }

    fun hideReveal() {
        if (revealedPageId != null) {
            revealedPageId = null
            stripAdapter.notifyDataSetChanged()
        }
    }

    /** 同一时刻只允许一个删除层：收起除 keepPageId 之外所有可见项的覆盖层 */
    private fun collapseOtherOverlays(keepPageId: String?) {
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? StripVH ?: continue
            val pid = project?.pages?.getOrNull(holder.bindingAdapterPosition)?.id ?: continue
            if (pid != keepPageId) holder.hideOverlayNow()
        }
    }

    private fun setRevealed(pageId: String?) {
        if (revealedPageId == pageId) return
        revealedPageId = pageId
        stripAdapter.notifyDataSetChanged()
    }

    private fun revealPx(): Float {
        val d = resources.displayMetrics.density
        return if (verticalMode) 38f * d else 44f * d
    }

    private fun selectFromStrip(pos: Int) {
        revealedPageId = null       // 点其它页 / 点空白处 → 收起固定
        currentIndex = pos
        onPageSelected?.invoke(pos)
        stripAdapter.notifyDataSetChanged()
    }

    // ------------- 工作区拖放目标 -------------

    /** 屏幕坐标（raw）落在哪一页的缩略图上；不在栏内返回 -1 */
    fun pageAt(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        rv.getLocationOnScreen(loc)
        val x = rawX - loc[0]
        val y = rawY - loc[1]
        if (x < 0 || y < 0 || x > rv.width || y > rv.height) return -1
        val v = rv.findChildViewUnder(x, y) ?: return -1
        val pos = rv.getChildAdapterPosition(v)
        return if (pos in 0 until (project?.pages?.size ?: 0)) pos else -1
    }

    /** 高亮悬停目标格 */
    fun setDragTarget(idx: Int) {
        if (dragTargetPos == idx) return
        dragTargetPos = idx
        stripAdapter.notifyDataSetChanged()
    }

    // ---------------- 列表 ----------------

    inner class StripAdapter : RecyclerView.Adapter<StripVH>() {
        override fun getItemViewType(position: Int): Int = if (verticalMode) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripVH {
            val d = resources.displayMetrics.density
            val root = FrameLayout(context).apply {
                if (verticalMode) setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                else setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
            root.layoutParams = RecyclerView.LayoutParams(
                if (verticalMode) RecyclerView.LayoutParams.MATCH_PARENT
                else RecyclerView.LayoutParams.WRAP_CONTENT,
                if (verticalMode) RecyclerView.LayoutParams.WRAP_CONTENT
                else RecyclerView.LayoutParams.MATCH_PARENT)
            return StripVH(root)
        }

        override fun getItemCount(): Int = project?.pages?.size ?: 0

        override fun onBindViewHolder(holder: StripVH, pos: Int) {
            val p = project ?: return
            val page = p.pages.getOrNull(pos) ?: return
            val d = resources.displayMetrics.density
            if (availH == 0 || availW == 0) computeAvail()

            // 缩略图尺寸：按本页宽高比自适应
            val aspect = page.widthMm(p) / page.heightMm(p)
            var w: Int; var h: Int
            if (!verticalMode) {
                h = availH.coerceAtLeast((16 * d).toInt())
                w = (h * aspect).toInt()
                val maxW = (54 * d).toInt()
                if (w > maxW) { w = maxW; h = (w / aspect).toInt() }
            } else {
                w = availW.coerceAtMost((44 * d).toInt()).coerceAtLeast((14 * d).toInt())
                h = (w / aspect).toInt()
                val maxH = (84 * d).toInt()
                if (h > maxH) { h = maxH; w = (h * aspect).toInt() }
            }
            holder.thumb.layoutParams = LinearLayout.LayoutParams(
                w.coerceAtLeast(8), h.coerceAtLeast(8))

            val key = "${page.id}_${page.renderSignature(p)}_${w}x$h"
            val cached = thumbCache[key]
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageBitmap(null)
                holder.thumb.post {
                    if (project === p && p.pages.getOrNull(holder.bindingAdapterPosition) === page) {
                        val bmp = PageRenderer.renderThumb(context, p, page, w, h)
                        thumbCache[key] = bmp
                        holder.thumb.setImageBitmap(bmp)
                    }
                }
            }

            val selected = pos == currentIndex
            val hovered = pos == dragTargetPos
            holder.thumb.background = GradientDrawable().apply {
                setColor(if (hovered) 0x24B5442A else Color.TRANSPARENT)
                setStroke(((if (selected || hovered) 2f else 1f) * d).toInt(),
                    when {
                        hovered -> 0xFFB5442A.toInt()
                        selected -> 0xFFB5442A.toInt()
                        else -> 0x14000000
                    })
                cornerRadius = 1.5f * d
            }
            holder.thumb.contentDescription = "第${pos + 1}页"

            holder.badge.text = "${pos + 1}"
            holder.badge.setTextColor(
                when {
                    hovered -> 0xFFB5442A.toInt()
                    selected -> 0xFFB5442A.toInt()
                    else -> 0xFF8A8A84.toInt()
                })
            holder.badge.width = (16 * d).toInt()
            holder.badge.setPadding(if (verticalMode) (3 * d).toInt() else 0, 0, 0, 0)

            // 覆盖式删除层的状态（按页 id 匹配，滚动后不丢失）；内容本身不动
            val revealed = revealedPageId != null && revealedPageId == page.id
            holder.deleteOverlay.visibility = if (revealed) View.VISIBLE else View.INVISIBLE
            holder.deleteOverlay.translationY = 0f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class StripVH(root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val content: LinearLayout
        val thumb: ImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val badge: TextView = TextView(context).apply {
            textSize = 9.5f
            gravity = if (verticalMode) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
        }
        val deleteOverlay: FrameLayout
        private val cell: FrameLayout = root

        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var axisLocked = false
        /** 呼出轴不主导：整个手势交给 RecyclerView 滚动 */
        private var axisIgnored = false
        /** 本次手势是否已把删除层固定 */
        private var revealCommitted = false

        init {
            val d = resources.displayMetrics.density
            content = LinearLayout(context).apply {
                orientation = if (verticalMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            content.addView(thumb)
            content.addView(badge)

            // 覆盖式删除层：上滑时从下方滑入盖住缩略图；轻点删除，下滑退出
            deleteOverlay = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                visibility = View.INVISIBLE
                background = GradientDrawable().apply {
                    setColor(0xF0B5442A.toInt())
                    cornerRadius = 2f * d
                }
                contentDescription = "删除本页"
            }
            deleteOverlay.addView(ImageView(context).apply {
                setImageResource(com.yussy.folior.R.drawable.ic_trash)
                setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER))

            root.addView(content)
            root.addView(deleteOverlay)   // 覆盖在缩略图之上

            // 覆盖层自身手势：轻点=删除，下滑=收起
            var ovDownY = 0f
            var ovDownX = 0f
            var ovMoved = false
            deleteOverlay.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        ovDownX = e.x; ovDownY = e.y; ovMoved = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val mdx = Math.abs(e.x - ovDownX)
                        val mdy = Math.abs(e.y - ovDownY)
                        when {
                            // 横向滑动 = 想翻看列表：收起并交还手势给 RecyclerView
                            mdx > 6f * d && mdx > mdy -> {
                                collapseOverlay()
                                return@setOnTouchListener false
                            }
                            mdy > 6f * d -> ovMoved = true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (ovMoved) collapseOverlay()
                        else {
                            val pp = bindingAdapterPosition
                            if (pp >= 0) {
                                revealedPageId = null
                                onStripDelete?.invoke(pp)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { collapseOverlay(); true }
                    else -> false
                }
            }

            content.setOnTouchListener { _, ev ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnTouchListener false
                val page = project?.pages?.getOrNull(pos)
                    ?: return@setOnTouchListener false
                // 该页正在被长按拖动：让位给 ItemTouchHelper
                if (draggingPageId != null && draggingPageId == page.id) {
                    return@setOnTouchListener false
                }
                val h = cell.height.coerceAtLeast(1).toFloat()
                val slop = 4f * d
                val commit = 8f * d

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x; downY = ev.y
                        moved = false; axisLocked = false; axisIgnored = false
                        revealCommitted = revealedPageId == page.id
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        // 呼出轴：竖屏=纵向（上滑）、横屏=横向（右滑）
                        val axisD = if (verticalMode) dx else dy
                        val otherD = if (verticalMode) dy else dx
                        if (!axisLocked && Math.abs(axisD) > slop) {
                            axisLocked = true
                            // 呼出轴不占主导 → 整个手势让给列表滚动（不抢手势，长按也不受影响）
                            axisIgnored = Math.abs(axisD) <= Math.abs(otherD) * 1.2f
                        }
                        if (axisLocked && !axisIgnored) {
                            moved = true
                            val towardShow = if (verticalMode) axisD else -axisD
                            val p = (towardShow / h).coerceIn(0f, 1f)
                            if (p > 0f) {
                                if (deleteOverlay.visibility != View.VISIBLE) {
                                    // 本页刚开始滑出：清掉指向其它页的标记并收起它们的删除层
                                    if (revealedPageId != null && revealedPageId != page.id) {
                                        revealedPageId = null
                                    }
                                    collapseOtherOverlays(page.id)
                                    deleteOverlay.visibility = View.VISIBLE
                                }
                                deleteOverlay.translationY = h * (1f - p)
                            }
                            if (towardShow > commit) revealCommitted = true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val delta = if (verticalMode) ev.x - downX else ev.y - downY
                        val towardShow = if (verticalMode) delta else -delta
                        when {
                            !moved ->
                                if (revealCommitted) collapseOverlay() else selectFromStrip(pos)
                            revealCommitted && towardShow < -commit -> collapseOverlay()
                            revealCommitted -> settleOverlay(page.id)
                            else -> collapseOverlay()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (revealCommitted) settleOverlay(page.id) else collapseOverlay()
                        true
                    }
                    else -> false
                }
            }
        }

        /** 吸附：删除层完全盖住缩略图并保持固定（同时收起其它页的删除层） */
        private fun settleOverlay(pageId: String) {
            revealedPageId = pageId
            revealCommitted = true
            collapseOtherOverlays(pageId)
            deleteOverlay.visibility = View.VISIBLE
            deleteOverlay.animate().translationY(0f).setDuration(110).start()
        }

        /** 立即隐藏本项覆盖层（不动列表布局，供互斥收起使用） */
        fun hideOverlayNow() {
            deleteOverlay.animate().cancel()
            deleteOverlay.translationY = cell.height.coerceAtLeast(1).toFloat()
            deleteOverlay.visibility = View.INVISIBLE
        }

        /** 退出：删除层向下滑出并隐藏 */
        private fun collapseOverlay() {
            if (revealedPageId != null) revealedPageId = null
            revealCommitted = false
            deleteOverlay.animate().translationY(cell.height.coerceAtLeast(1).toFloat())
                .setDuration(140)
                .withEndAction { deleteOverlay.visibility = View.INVISIBLE }.start()
        }
    }
}

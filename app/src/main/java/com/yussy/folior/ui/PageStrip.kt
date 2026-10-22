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
    var onPageAdded: (() -> Unit)? = null

    /** 滑动呼出删除按钮后点击删除：参数为页索引 */
    var onStripDelete: ((Int) -> Unit)? = null

    private var revealedPos = -1

    /** 工作区拖照片悬停的目标格（高亮），-1 = 无 */
    private var dragTargetPos = -1

    private val thumbCache = HashMap<String, android.graphics.Bitmap>()
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
                    vh.itemView.alpha = 0.55f
                    vh.itemView.scaleX = 1.04f; vh.itemView.scaleY = 1.04f
                    if (revealedPos == vh.bindingAdapterPosition) {
                        revealedPos = -1
                        stripAdapter.notifyDataSetChanged()
                    }
                }
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                vh.itemView.alpha = 1f
                vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f
                onPageReordered?.invoke(currentIndex)
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
        revealedPos = -1
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

    fun refresh() {
        computeAvail()
        thumbCache.clear()
        stripAdapter.notifyDataSetChanged()
        post { rv.scrollToPosition(currentIndex.coerceIn(0, (project?.pages?.size ?: 1) - 1)) }
    }

    fun setCurrent(idx: Int) {
        currentIndex = idx
        stripAdapter.notifyDataSetChanged()
    }

    fun hideReveal() {
        if (revealedPos >= 0) {
            revealedPos = -1
            stripAdapter.notifyDataSetChanged()
        }
    }

    private fun revealPx(): Float {
        val d = resources.displayMetrics.density
        return if (verticalMode) 38f * d else 44f * d
    }

    private fun selectFromStrip(pos: Int) {
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

            val key = "${page.id}_${w}x$h"
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

            // 滑动呼出删除按钮的状态
            val revealed = revealedPos == pos
            val reveal = revealPx()
            holder.content.translationY = if (revealed && !verticalMode) -reveal else 0f
            holder.content.translationX = if (revealed && verticalMode) reveal else 0f
            holder.deleteBtn.visibility = if (revealed) View.VISIBLE else View.INVISIBLE
            holder.deleteBtn.setOnClickListener {
                val pp = holder.bindingAdapterPosition
                if (pp >= 0) onStripDelete?.invoke(pp)
            }
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
        val deleteBtn: ImageView

        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var axisLocked = false

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

            deleteBtn = ImageView(context).apply {
                setImageResource(com.yussy.folior.R.drawable.ic_trash)
                setColorFilter(0xFFB5442A.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x1FB5442A)
                }
                contentDescription = "删除页面"
                val side = (34 * d).toInt()
                layoutParams = FrameLayout.LayoutParams(side, side,
                    if (verticalMode) Gravity.START or Gravity.CENTER_VERTICAL
                    else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            }
            root.addView(deleteBtn)
            root.addView(content)

            content.setOnTouchListener { _, ev ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnTouchListener false
                val reveal = revealPx()
                val slop = 3f * d
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x; downY = ev.y; moved = false; axisLocked = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        // 滑动轴：横向栏=纵向滑动；纵向栏=横向滑动
                        val axisD = if (verticalMode) dx else dy
                        val otherD = if (verticalMode) dy else dx
                        if (!axisLocked && Math.abs(axisD) > slop) {
                            axisLocked = true
                            if (Math.abs(axisD) > Math.abs(otherD) * 1.2f) {
                                // 沿删除滑动轴主导：禁止父级（RecyclerView 滚动）拦截手势
                                rv.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        if (axisLocked) {
                            moved = true
                            val t = (if (verticalMode) axisD.coerceIn(0f, reveal)
                                    else axisD.coerceIn(-reveal, 0f))
                            if (verticalMode) content.translationX = t
                            else content.translationY = t
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // 即使被父级拦截，也按已滑动距离判定是否呼出
                        val dx = ev.x - downX; val dy = ev.y - downY
                        val delta = if (verticalMode) dx else dy
                        if (Math.abs(delta) > reveal * 0.35f) revealedPos = pos
                        stripAdapter.notifyDataSetChanged()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val delta = if (verticalMode) ev.x - downX else ev.y - downY
                        if (!moved) {
                            if (revealedPos == pos) hideReveal() else selectFromStrip(pos)
                        } else {
                            if (Math.abs(delta) > reveal * 0.35f) revealedPos = pos
                            stripAdapter.notifyDataSetChanged()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }
}

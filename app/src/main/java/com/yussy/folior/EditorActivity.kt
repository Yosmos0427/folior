package com.yussy.folior

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yussy.folior.model.*
import com.yussy.folior.store.Store
import com.yussy.folior.ui.*

class EditorActivity : AppCompatActivity() {

    private lateinit var project: Project
    private lateinit var canvas: PageCanvas
    private lateinit var strip: PageStrip
    private lateinit var pageLabel: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var framingPanel: FramingPanel
    private var framingOpen = false
    private lateinit var bodyRow: LinearLayout
    private lateinit var rootLayout: LinearLayout
    // 二级工具在竖屏布局中的原始序号（横屏停靠右侧后用于复位）
    private var selBarIdx = 0
    private var framingIdx = 0
    private var drawBarIdx = 0
    private lateinit var canvasWrap: android.widget.FrameLayout
    private lateinit var sideStripWrap: LinearLayout
    private lateinit var bottomStripWrap: LinearLayout
    // 横屏右侧纵向工具槽（一级工具 / 二级工具 / 装裱 / 绘制 共用同一槽位，互斥占位）
    private lateinit var rightDock: android.widget.ScrollView
    private lateinit var rightInner: LinearLayout
    private var toolsIdx = 0
    // 看片台（全工作区平铺排序）
    private lateinit var rootFrame: android.widget.FrameLayout
    private lateinit var topBar: View
    private lateinit var lightTableHost: android.widget.FrameLayout
    private lateinit var lightTable: LightTable
    private lateinit var ltHeader: LinearLayout
    private lateinit var ltInfo: TextView
    private lateinit var expandBtn: TextView
    private var lightTableOpen = false
    private var pageIndex = 0
    private var spread = false
    private lateinit var tools: LinearLayout
    private lateinit var drawBar: LinearLayout
    private var drawMode = false
    private var brushColor = "#1A1A18"
    private var eraserOn = false
    private val brushBtns = ArrayList<TextView>()
    private lateinit var eraserBtn: TextView
    private lateinit var colorChip: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { Store.save(this, project) }

    /** 系统相册选择器（Photo Picker）：多选，无需任何存储权限。
     *  上限不要写死 —— 交由框架取 MediaStore.getPickImagesMaxLimit()，
     *  各厂商（三星等）上限不同，写死会直接抛 IllegalArgumentException。 */
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (!uris.isNullOrEmpty()) handlePickedPhotos(uris) }

    companion object {
        const val REQ_PDF = 202
        const val REQ_BUNDLE = 203
        /** 展览常见展厅尺寸预设（米制） */
        val WALL_PRESETS = listOf(
            Triple("3×2m", 3000f, 2000f),
            Triple("3×3m", 3000f, 3000f),
            Triple("4×3m", 4000f, 3000f),
            Triple("6×3m", 6000f, 3000f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: finish().let { return }
        val p = Store.listProjects(this).find { it.id == id } ?: finish().let { return }
        project = p
        if (project.pages.isEmpty()) project.pages.add(Page())
        buildUi()
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        // 最外层 FrameLayout：看片台覆盖层与「展开」按钮需悬浮于整个编辑器之上
        val frame = android.widget.FrameLayout(this).apply {
            rootFrame = this
            setBackgroundColor(0xFFFAFAF7.toInt())
        }
        val root = LinearLayout(this).apply {
            rootLayout = this
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAFAF7.toInt())
        }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- 顶栏 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(6f))
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 24f
            setPadding(dp(6f), dp(4f), dp(14f), dp(4f))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = project.title
            textSize = 16f
            maxLines = 1
            setTextColor(0xFF1A1A18.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pageLabel = TextView(this).apply {
            text = "1 / 1"
            textSize = 12f
            setTextColor(0xFF6B6B66.toInt())
            setPadding(dp(10f), 0, dp(10f), 0)
        }
        top.addView(pageLabel)
        top.addView(TextView(this).apply {
            text = "菜单"
            textSize = 13f
            setTextColor(0xFFB5442A.toInt())
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { showMenu() }
        })
        root.addView(top)
        topBar = top

        // ---- 主工作区：横屏时左侧纵向页面栏 + 画布 ----
        bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        sideStripWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(88f), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        bodyRow.addView(sideStripWrap)
        canvasWrap = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        canvas = PageCanvas(this)
        canvas.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        canvasWrap.addView(canvas)
        bodyRow.addView(canvasWrap)

        // 横屏右侧纵向工具槽：内容用 ScrollView 兜底，避免极端比例下按钮被截断
        rightDock = android.widget.ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rightInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightDock.addView(rightInner, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bodyRow.addView(rightDock, LinearLayout.LayoutParams(
            dp(108f), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bodyRow)

        // ---- 选择工具栏（固定两行，全部功能直接可见） ----
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val selRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(4f), dp(4f), 0)
        }
        val selRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4f), 0, dp(4f), dp(4f))
        }
        // 按钮所在行：第一行放编辑类操作，第二行放排版类操作
        var selTarget: LinearLayout = selRow1
        fun selBtn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { action() }
            selTarget.addView(this)
        }
        fun switchRow(row: LinearLayout) { selTarget = row }
        // 删除放第一位：任何选中态都立即可见（红色 = 破坏性操作）
        selBtn("删除") {
            val it = selected() ?: return@selBtn
            AlertDialog.Builder(this).setTitle("删除该元素？")
                .setPositiveButton("删除") { _, _ ->
                    (pageOfItem(it) ?: page()).items.remove(it)
                    canvas.select(null)
                    canvas.invalidate()
                    scheduleSave()
                }.setNegativeButton("取消", null).show()
        }.setTextColor(0xFFB5442A.toInt())
        selBtn("装裱") {
            val it = selected() ?: return@selBtn
            if (it.type != Item.TYPE_PHOTO && it.type != Item.TYPE_TEXT) return@selBtn
            openFraming(it)
        }
        selBtn("尺寸") {
            val it = selected() ?: return@selBtn
            if (it.type != Item.TYPE_PHOTO) return@selBtn
            if (project.type != Project.TYPE_EXHIBITION) {
                Toast.makeText(this, "实际尺寸仅用于展览墙体", Toast.LENGTH_SHORT).show()
                return@selBtn
            }
            editPhotoPrintSize(it)
        }
        selBtn("编辑") { editSelected() }
        switchRow(selRow2)
        selBtn("复制") { duplicateSelected() }
        selBtn("置顶") { bringTop() }
        selBtn("下移") { sendBack() }
        selBtn("转90°") { canvas.rotateSelected90() }
        selBtn("跨页") {
            if (!canvas.spanSelected())
                Toast.makeText(this, "需横屏并同时显示左右两页", Toast.LENGTH_SHORT).show()
        }
        selectionBar.addView(selRow1)
        selectionBar.addView(selRow2)
        root.addView(selectionBar)

        // ---- 内联装裱面板（布局流内，不遮挡工作区；即时生效） ----
        framingPanel = FramingPanel(this).apply { visibility = View.GONE }
        framingPanel.onChanged = { canvas.invalidate(); scheduleSave() }
        framingPanel.onClosed = { closeFraming() }
        root.addView(framingPanel)

        // ---- 绘制模式工具条（进入绘制模式后替换底部工具条） ----
        drawBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        val brushDefs = arrayOf(
            Triple("细笔", 0.005f, 1f),
            Triple("中笔", 0.011f, 1f),
            Triple("粗笔", 0.02f, 1f),
            Triple("马克", 0.032f, 0.5f)
        )
        brushDefs.forEachIndexed { bi, (name, w, alpha) ->
            val btn = TextView(this).apply {
                text = name
                textSize = 13f
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                setOnClickListener {
                    canvas.brush.width = w
                    canvas.brush.alpha = alpha
                    canvas.brush.marker = name == "马克"
                    eraserOn = false
                    canvas.eraserMode = false
                    refreshDrawBar(bi)
                }
            }
            brushBtns.add(btn)
            drawBar.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5f)
            })
        }
        // 颜色块
        colorChip = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setOnClickListener {
                ColorPickerDialog.show(this@EditorActivity, "笔具颜色", brushColor) { c ->
                    brushColor = c
                    canvas.brush.color = c
                    refreshDrawBar(-1)
                }
            }
        }
        drawBar.addView(colorChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5f)
        })
        // 橡皮
        eraserBtn = TextView(this).apply {
            text = "橡皮"
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                eraserOn = !eraserOn
                canvas.eraserMode = eraserOn
                refreshDrawBar(-1)
            }
        }
        drawBar.addView(eraserBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5f)
        })
        // 撤销
        drawBar.addView(TextView(this).apply {
            text = "撤销"
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                canvas.undoDraw()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(5f)
        })
        // 完成
        drawBar.addView(TextView(this).apply {
            text = "完成"
            textSize = 13f
            setTextColor(0xFFFAFAF7.toInt())
            setBackgroundColor(0xFF1A1A18.toInt())
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            setOnClickListener { setDrawMode(false) }
        })
        root.addView(drawBar)

        // ---- 底部工具条 ----
        tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        }
        fun toolBtn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            setOnClickListener { action() }
            tools.addView(this)
        }
        toolBtn("＋照片") { pickPhotos() }
        toolBtn("＋文字") { addText() }
        toolBtn(if (project.type == Project.TYPE_EXHIBITION) "＋墙体" else "＋自定义页面") { addCustomSizePage() }
        toolBtn("绘制") { setDrawMode(!drawMode) }
        toolBtn("放映") { startActivity(Intent(this, ViewerActivity::class.java)
            .putExtra("id", project.id)
            .putExtra("page", pageIndex)) }
        // 「展开看片台」图标按钮（∧），位于「放映」右侧；展开后迁移到看片台顶栏变为（∨）
        // 注：底色已并入「菜单 - 页面设置」，不再占用工具条
        expandBtn = TextView(this).apply {
            text = "∧"
            textSize = 15f
            setTextColor(0xFF1A1A18.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            contentDescription = "展开看片台"
            setOnClickListener { if (lightTableOpen) closeLightTable() else openLightTable() }
        }
        tools.addView(expandBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tools)

        // ---- 页面条（竖屏在底部，横屏移到左侧）----
        strip = PageStrip(this)
        bottomStripWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96f))
        }
        bottomStripWrap.addView(strip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bottomStripWrap)

        // ---- 看片台覆盖层（默认隐藏；展开时从底部上滑盖住工作区）----
        lightTableHost = android.widget.FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFFF4F4EE.toInt())
        }
        // 看片台顶栏：左侧页数信息，右侧「收回」按钮（展开时从工具条迁移过来）
        ltHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), 0, dp(6f), 0)
        }
        ltInfo = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFF8A8A84.toInt())
        }
        ltHeader.addView(ltInfo, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lightTableHost.addView(ltHeader, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
        lightTableHost.addView(View(this).apply { setBackgroundColor(0xFFE6E6E0.toInt()) },
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))).apply {
                topMargin = dp(44f)
            })

        lightTable = LightTable(this)
        lightTableHost.addView(lightTable, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(44f)
        })
        frame.addView(lightTableHost, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(frame)

        canvas.project = project
        canvas.onSelectionChanged = { updateBars() }
        canvas.onModified = { scheduleSave(); strip.refresh() }
        canvas.onFlip = { dir -> flipSpread(dir) }
        canvas.onEditText = { it -> editItem(it) }
        canvas.onDragTargetAt = { x, y -> strip.pageAt(x, y) }
        canvas.onStripHighlight = { idx -> strip.setDragTarget(idx) }
        canvas.onItemDropToPage = { item, idx -> transferItemTo(item, idx) }

        strip.project = project
        strip.onPageSelected = { idx ->
            pageIndex = idx
            canvas.select(null)
            syncSpread()
            canvas.invalidateLayout()
            updateBars()
        }
        strip.onPageReordered = { idx ->
            pageIndex = idx.coerceIn(0, project.pages.size - 1)
            scheduleSave()
            syncSpread()
            canvas.invalidateLayout()
            updateBars()
        }
        strip.onPageAdded = {
            // 摄影书：普通空白页；展览：新建墙体（需输入尺寸）
            if (project.type == Project.TYPE_EXHIBITION) addCustomSizePage() else addPage()
        }
        strip.onStripDelete = { pos ->
            deletePageAt(pos)
            strip.hideReveal()
        }

        // 看片台：点击缩略图进入该页；拖动排序结束后同步页码与画布并保存
        lightTable.onPageClicked = { idx ->
            pageIndex = idx.coerceIn(0, project.pages.size - 1)
            canvas.select(null)
            closeLightTable()
            refreshAll()
        }
        lightTable.onReordered = { idx ->
            pageIndex = idx.coerceIn(0, project.pages.size - 1)
            lightTable.currentIndex = pageIndex
            scheduleSave()
            refreshAll()
        }

        // 记录工具条的竖屏位置（重挂载后可复原）
        toolsIdx = rootLayout.indexOfChild(tools)
        selBarIdx = rootLayout.indexOfChild(selectionBar)
        framingIdx = rootLayout.indexOfChild(framingPanel)
        drawBarIdx = rootLayout.indexOfChild(drawBar)

        applyOrientation()
        refreshAll()
    }

    // ---------------- 跨页 / 横竖屏 ----------------

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * 方向适配：
     * - 竖屏：预览栏在底部横排；一级工具在底部**等宽均分**铺满；二级工具回到底部原位
     * - 横屏：预览栏在左侧纵向；一级工具 / 二级工具 / 装裱 / 绘制 统一停靠**右侧纵向槽位**
     *   （同一时刻只显示一组，避免 15 个按钮同屏拥挤、也无需滚动）
     */
    private fun applyOrientation() {
        val land = isLandscape()
        spread = land && project.type == Project.TYPE_BOOK
        canvas.spreadMode = spread

        // 右侧槽位宽度：装裱面板需要更宽，工具按钮一列 108dp 足够
        val dockW = if (land && framingOpen) dp(240f) else dp(108f)
        rightDock.layoutParams = LinearLayout.LayoutParams(
            dockW, ViewGroup.LayoutParams.MATCH_PARENT)

        if (land) {
            // ---- 左：纵向预览栏 ----
            bottomStripWrap.visibility = View.GONE
            sideStripWrap.visibility = View.VISIBLE
            sideStripWrap.layoutParams = LinearLayout.LayoutParams(
                dp(88f), ViewGroup.LayoutParams.MATCH_PARENT)
            if (strip.parent !== sideStripWrap) {
                (strip.parent as? ViewGroup)?.removeView(strip)
                sideStripWrap.addView(strip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            strip.setVertical(true)

            // ---- 右：纵向工具槽 ----
            rightDock.visibility = View.VISIBLE
            if (tools.parent !== rightInner) {
                (tools.parent as? ViewGroup)?.removeView(tools)
                rightInner.addView(tools, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            layoutTools(land = true)
            dockSecondary(land = true)
        } else {
            // ---- 下：横向预览栏 ----
            sideStripWrap.visibility = View.GONE
            rightDock.visibility = View.GONE
            bottomStripWrap.visibility = View.VISIBLE
            bottomStripWrap.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96f))
            if (strip.parent !== bottomStripWrap) {
                (strip.parent as? ViewGroup)?.removeView(strip)
                bottomStripWrap.addView(strip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            strip.setVertical(false)

            // 竖屏：按固定顺序归位 top / bodyRow / 二级工具 / 工具条 / 预览栏
            restackPortrait()
            layoutTools(land = false)
            dockSecondary(land = false)
        }

        syncSpread()
        canvas.invalidateLayout()
        updateBars()
        applyExpandBtnState()
        if (lightTableOpen) {
            val lpH = lightTableHost.layoutParams as android.widget.FrameLayout.LayoutParams
            lpH.topMargin = topBar.height
            lightTableHost.layoutParams = lpH
            lightTable.post { lightTable.layoutCells() }
        }
    }

    /** 一级工具按钮布局：竖屏等宽均分铺满一行；横屏纵向单列、每项铺满栏宽 */
    private fun layoutTools(land: Boolean) {
        tools.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        tools.gravity = if (land) Gravity.TOP else Gravity.CENTER
        tools.setPadding(
            if (land) 0 else dp(6f), if (land) dp(6f) else dp(4f),
            if (land) 0 else dp(6f), if (land) dp(6f) else dp(4f))
        for (i in 0 until tools.childCount) {
            val v = tools.getChildAt(i)
            if (v === expandBtn && lightTableOpen) continue   // 已迁移到看片台顶栏
            v.layoutParams = if (land)
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            (v as? TextView)?.let {
                it.gravity = Gravity.CENTER
                // 横屏纵向一列按钮需更紧凑，否则 9 项装不下
                if (land) it.setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                else it.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            }
        }
    }

    /**
     * 竖屏归位：root 顺序固定为 top / bodyRow / selectionBar / framingPanel / drawBar / tools / 预览栏。
     * 直接按序重挂，不用索引 clamp（旋转后各元素所在容器不同，索引会错位）。
     */
    private fun restackPortrait() {
        val chain = listOf(selectionBar, framingPanel, drawBar, tools, bottomStripWrap)
        chain.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        chain.forEach {
            rootLayout.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 预览栏保持固定高度
        bottomStripWrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(96f))
    }

    /** 二级工具（选择栏 / 装裱面板 / 绘制栏）：横屏进右侧槽位；竖屏布局由 restackPortrait 负责 */
    private fun dockSecondary(land: Boolean) {
        applySelBarLayout(land)
        applyDrawBarLayout(land)
        if (!land) return

        val chain = listOf(selectionBar, framingPanel, drawBar)
        chain.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rightInner.addView(selectionBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rightInner.addView(framingPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rightInner.addView(drawBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 选择栏：竖屏两行横排（原位）；横屏拆成 9 项单列纵向排列 */
    private fun applySelBarLayout(land: Boolean) {
        selectionBar.orientation = LinearLayout.VERTICAL
        for (i in 0 until selectionBar.childCount) {
            val row = selectionBar.getChildAt(i) as? LinearLayout ?: continue
            row.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            row.gravity = if (land) Gravity.TOP else Gravity.CENTER
            when {
                land -> row.setPadding(0, 0, 0, 0)
                i == 0 -> row.setPadding(dp(4f), dp(4f), dp(4f), 0)
                else -> row.setPadding(dp(4f), 0, dp(4f), dp(4f))
            }
            for (j in 0 until row.childCount) {
                val btn = row.getChildAt(j)
                btn.layoutParams = LinearLayout.LayoutParams(
                    if (land) ViewGroup.LayoutParams.MATCH_PARENT
                    else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                (btn as? TextView)?.apply {
                    gravity = Gravity.CENTER
                    if (land) setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                    else setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                }
            }
        }
    }

    /** 绘制栏：横屏纵向单列（每项铺满栏宽）；竖屏横向一行 */
    private fun applyDrawBarLayout(land: Boolean) {
        drawBar.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        drawBar.gravity = if (land) Gravity.CENTER_VERTICAL else Gravity.CENTER
        for (i in 0 until drawBar.childCount) {
            val lp = drawBar.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            if (land) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.rightMargin = 0; lp.topMargin = dp(3f); lp.bottomMargin = dp(3f)
            } else {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.rightMargin = dp(5f); lp.topMargin = 0; lp.bottomMargin = 0
            }
            drawBar.getChildAt(i).layoutParams = lp
            (drawBar.getChildAt(i) as? TextView)?.let {
                it.gravity = Gravity.CENTER
                if (land) it.setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                else it.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    // ---------------- 看片台（Light Table） ----------------

    /** 展开看片台：抽屉从底部上滑盖住整个工作区，工具条同步上移让位。 */
    private fun openLightTable() {
        if (lightTableOpen) return
        lightTableOpen = true
        canvas.select(null)
        // 统一格高按项目基准页比例（摄影书=页尺寸，展览=墙体默认尺寸）
        lightTable.baseAspect = project.pageWmm / project.pageHmm.coerceAtLeast(1f)
        lightTable.project = project
        lightTable.currentIndex = pageIndex
        val lp = lightTableHost.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.topMargin = topBar.height
        lightTableHost.layoutParams = lp
        lightTableHost.visibility = View.VISIBLE
        lightTableHost.translationY = Math.max(1, rootFrame.height - topBar.height).toFloat()
        lightTable.post { lightTable.layoutCells() }
        ltInfo.text = "共 ${project.pages.size} 页"
        applyExpandBtnState()
        lightTableHost.animate().translationY(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { lightTable.layoutCells(); lightTable.scrollToCurrent() }
            .start()
        // 竖屏：工具条向上滑走，形成抽屉推起工作台的观感（横屏工具在右栏，无需位移）
        if (!isLandscape()) {
            tools.animate().translationY(-dp(26f).toFloat()).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /** 收回看片台：下滑退出，工具条落回原位。 */
    private fun closeLightTable() {
        if (!lightTableOpen) return
        lightTableOpen = false
        val h = Math.max(1, lightTableHost.height)
        lightTableHost.animate().translationY(h.toFloat())
            .setDuration(260)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                lightTableHost.visibility = View.GONE
                lightTableHost.translationY = 0f
            }.start()
        tools.animate().translationY(0f).setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        applyExpandBtnState()
    }

    /**
     * 「展开 / 收回」按钮：
     * - 收起态：留在底部工具条内（「放映」右侧），与其它工具钮同样式，朱红文字区分
     * - 展开态：迁移到看片台顶栏右侧，变为浅朱红胶囊的「收回」
     */
    private fun applyExpandBtnState() {
        val target: ViewGroup = if (lightTableOpen) ltHeader else tools
        // 始终显式给出布局参数：addView(child) 会复用 child 原有的 LayoutParams，
        // 曾导致从看片台顶栏（固定高度）回到工具条后高度被锁死、文字被截断
        val lp = when {
            lightTableOpen -> LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f)).apply { rightMargin = dp(4f) }
            isLandscape() -> LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else -> LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)   // 竖屏与其它工具等宽均分
        }

        if (expandBtn.parent !== target) {
            (expandBtn.parent as? ViewGroup)?.removeView(expandBtn)
            target.addView(expandBtn, lp)
        } else {
            expandBtn.layoutParams = lp
        }

        expandBtn.gravity = Gravity.CENTER
        expandBtn.setTextColor(0xFF1A1A18.toInt())
        expandBtn.background = null        // 不加特殊底色，与其它工具钮一致
        if (lightTableOpen) {
            expandBtn.text = "∨"           // 收回（向下）
            expandBtn.textSize = 15f
            expandBtn.setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
            expandBtn.contentDescription = "收回看片台"
        } else {
            expandBtn.text = "∧"           // 展开（向上）
            expandBtn.textSize = 15f
            expandBtn.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            expandBtn.contentDescription = "展开看片台"
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (lightTableOpen) { closeLightTable(); return }
        super.onBackPressed()
    }

    /** 书的跨页配对：封面单右、封底单左，中间 (2,3)(4,5)… */
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

    private fun spreadFor(idx: Int): Pair<Int, Int> {
        val list = spreads()
        list.firstOrNull { it.first == idx || it.second == idx }?.let { return it }
        return list.lastOrNull() ?: (-1 to 0)
    }

    private fun syncSpread() {
        if (pageIndex !in 0 until project.pages.size) pageIndex = 0
        if (!spread) {
            canvas.leftIndex = -1
            canvas.rightIndex = pageIndex
            canvas.activeIndex = pageIndex
        } else {
            val (l, r) = spreadFor(pageIndex)
            canvas.leftIndex = l
            canvas.rightIndex = r
            canvas.activeIndex = pageIndex
        }
    }

    private fun flipSpread(dir: Int) {
        if (!spread) {
            val ni = (pageIndex + dir).coerceIn(0, project.pages.size - 1)
            if (ni != pageIndex) {
                pageIndex = ni
                canvas.select(null)
                strip.setCurrent(pageIndex)
                syncSpread(); canvas.invalidateLayout(); updateBars()
            }
            return
        }
        val list = spreads()
        val cur = list.indexOfFirst { it.first == canvas.leftIndex && it.second == canvas.rightIndex }
            .let { if (it < 0) 0 else it }
        val t = (cur + dir).coerceIn(0, list.size - 1)
        if (t == cur) return
        val target = list[t]
        pageIndex = if (target.first >= 0) target.first else target.second
        canvas.select(null)
        strip.setCurrent(pageIndex)
        syncSpread(); canvas.invalidateLayout(); updateBars()
    }

    /** 滑动呼出删除按钮后执行：删除第 pos 页/墙。 */
    private fun deletePageAt(pos: Int) {
        if (pos !in project.pages.indices) return
        if (project.pages.size <= 1) {
            Toast.makeText(this,
                if (project.type == Project.TYPE_EXHIBITION) "至少保留一面墙" else "至少保留一页",
                Toast.LENGTH_SHORT).show()
            return
        }
        project.pages.removeAt(pos)
        pageIndex = pageIndex.coerceIn(0, project.pages.size - 1)
        canvas.select(null)
        refreshAll()
        scheduleSave()
    }

    /** 新建自定义尺寸页面（摄影书）/ 新建墙体（展览，单位米）。 */
    private fun addCustomSizePage() {
        val isExh = project.type == Project.TYPE_EXHIBITION
        if (isExh) {
            showWall(this, "新建墙体",
                project.pageWmm, project.pageHmm, presets = WALL_PRESETS) { w, h, _ ->
                project.pages.add(pageIndex + 1, Page(wmm = w, hmm = h, bg = page().bg))
                pageIndex += 1
                refreshAll()
                scheduleSave()
            }
        } else {
            PageSizeDialog.show(this, "新建自定义页面尺寸",
                project.pageWmm, project.pageHmm) { w, h, _ ->
                project.pages.add(pageIndex + 1, Page(wmm = w, hmm = h, bg = page().bg))
                pageIndex += 1
                refreshAll()
                scheduleSave()
            }
        }
    }

    /**
     * 页面设置：同一对话框内管理当前页/墙的尺寸与本页底色。
     * 展览用米制墙体对话框（含展厅预设），摄影书用毫米对话框。
     */
    private fun resizeCurrentPage() {
        val pg = page()
        val apply: (Float, Float, String?) -> Unit = { w, h, bg ->
            pg.wmm = w; pg.hmm = h
            if (bg != null) pg.bg = bg
            canvas.invalidateLayout()
            strip.refresh()
            scheduleSave()
        }
        val curBg = pg.bg ?: project.defaultBg
        if (project.type == Project.TYPE_EXHIBITION)
            showWall(this, "墙体设置", pg.widthMm(project), pg.heightMm(project),
                presets = WALL_PRESETS, bg = curBg) { w, h, bg -> apply(w, h, bg) }
        else
            PageSizeDialog.show(this, "页面设置",
                pg.widthMm(project), pg.heightMm(project), bg = curBg) { w, h, bg ->
                apply(w, h, bg)
            }
    }

    /** 本次导入中超出墙面的提示（导入结束后统一展示） */
    private val oversizeNotes = mutableListOf<String>()

    private fun fmtCm(mm: Float): String = "${(mm / 10f).toInt()}"

    /** 元素横向可用范围：框比页面大时允许负值（溢出），保证始终覆盖画面 */
    private fun clampX(w: Float, x: Float): Float =
        if (w >= 1f) x.coerceIn(1f - w, 0f) else x.coerceIn(0f, 1f - w)

    private fun clampY(h: Float, y: Float): Float =
        if (h >= 1f) y.coerceIn(1f - h, 0f) else y.coerceIn(0f, 1f - h)

    /** 展览：照片按实际尺寸摆上墙 —— 实际尺寸 = 原始像素 ÷ PPI（EXIF，缺省 300）。 */
    private fun placeOnWall(imgId: String, wall: Page, order: Int): Item {
        val (pxW, pxH, ppi) = Store.photoMeta(this, imgId)
        var printW: Float
        var printH: Float
        if (pxW > 0 && pxH > 0) {
            // 像素 / PPI = 英寸 → mm
            printW = pxW / ppi * 25.4f
            printH = pxH / ppi * 25.4f
            // 兜底：极端元数据时限制在 5cm – 5m 之间
            val longSide = Math.max(printW, printH).coerceIn(50f, 5000f)
            val k = longSide / Math.max(printW, printH)
            printW *= k; printH *= k
        } else {
            val aspect = 1.5f
            printW = if (aspect >= 1f) 400f else 400f * aspect
            printH = printW / aspect
        }
        val ww = wall.widthMm(project); val wh = wall.heightMm(project)
        // 真实尺寸，不做缩放：超出墙面的部分直接留在墙外（画布按墙边界裁切）
        val w = printW / ww
        val h = printH / wh
        if (w > 1.001f || h > 1.001f) {
            oversizeNotes.add("${fmtCm(printW)}×${fmtCm(printH)}cm（墙面 ${fmtCm(ww)}×${fmtCm(wh)}cm）")
        }
        val (cx, cy) = nextInsertSlot(order)
        val x = clampX(w, cx - w / 2f)
        val y = clampY(h, cy - h / 2f)
        return Item(type = Item.TYPE_PHOTO, imgId = imgId, printMm = printW,
            x = x, y = y, w = w, h = h)
    }

    /** 修改照片实际宽度（cm），按比例重算在墙上的占比。 */
    private fun editPhotoPrintSize(item: Item) {
        val wall = pageOfItem(item) ?: page()
        val ww = wall.widthMm(project); val wh = wall.heightMm(project)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format("%.0f", (item.printMm ?: 400f) / 10f))
            hint = "照片宽度（cm）"
            setSingleLine()
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("照片实际宽度（cm）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val cm = input.text.toString().toFloatOrNull() ?: return@setPositiveButton
                val newWmm = (cm * 10f).coerceIn(10f, 20000f)
                val imgAspect = (item.w * ww) / (item.h * wh)
                val newHmm = newWmm / imgAspect
                // 保持当前中心位置，尺寸可超出墙面
                val cxNow = item.x + item.w / 2f
                val cyNow = item.y + item.h / 2f
                item.printMm = newWmm
                item.w = (newWmm / ww).coerceAtMost(6f)
                item.h = (newHmm / wh).coerceAtMost(6f)
                item.x = clampX(item.w, cxNow - item.w / 2f)
                item.y = clampY(item.h, cyNow - item.h / 2f)
                canvas.invalidate(); scheduleSave()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把元素转移到目标页/墙（保持物理比例）。 */
    private fun transferItemTo(item: Item, targetIdx: Int) {
        val owner = pageOfItem(item) ?: return
        val target = project.pages.getOrNull(targetIdx) ?: return
        if (target === owner) return
        owner.items.remove(item)
        val ow = owner.widthMm(project); val oh = owner.heightMm(project)
        val tw = target.widthMm(project); val th = target.heightMm(project)
        if (item.type == Item.TYPE_PHOTO && item.printMm != null) {
            // 有实际尺寸：保持物理大小（允许超出目标墙）
            val physH = item.printMm!! * (item.h * oh) / (item.w * ow)
            item.w = (item.printMm!! / tw).coerceAtMost(6f)
            item.h = (physH / th).coerceAtMost(6f)
        } else {
            // 按与页面宽度的占比换算
            item.w = (item.w * ow / tw).coerceIn(0.05f, 6f)
            item.h = (item.h * oh / th).coerceIn(0.05f, 6f)
        }
        item.x = clampX(item.w, (1f - item.w) / 2f)
        item.y = clampY(item.h, (1f - item.h) / 2f)
        target.items.add(item)
        canvas.select(item)
        refreshAll()
        scheduleSave()
    }

    private fun selected(): Item? = canvas.selected

    private fun page(): Page = project.pages[pageIndex]

    private fun pageOfItem(item: Item): Page? =
        project.pages.firstOrNull { pg -> pg.items.any { it === item } }

    private fun refreshAll() {
        strip.setCurrent(pageIndex)
        strip.refresh()
        syncSpread()
        canvas.invalidateLayout()
        updateBars()
    }

    private fun updatePageLabel() {
        pageLabel.text = if (spread) {
            val l = canvas.leftIndex; val r = canvas.rightIndex
            val range = when {
                l >= 0 && r >= 0 -> "${l + 1}–${r + 1}"
                r >= 0 -> "${r + 1}"
                l >= 0 -> "${l + 1}"
                else -> "-"
            }
            "$range / ${project.pages.size}"
        } else "${pageIndex + 1} / ${project.pages.size}"
    }

    /** 进入/退出绘制模式：底部工具条与笔具条互换，画布切换触摸通道。 */
    private fun setDrawMode(on: Boolean) {
        drawMode = on
        canvas.drawMode = on
        if (on) {
            canvas.select(null)
            canvas.brush.color = brushColor
            canvas.eraserMode = eraserOn
        } else {
            canvas.eraserMode = false
            canvas.clearDrawUndo()
            scheduleSave()
        }
        tools.visibility = if (on) View.GONE else View.VISIBLE
        drawBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) refreshDrawBar(-1)
        updateBars()
    }

    /** 刷新笔具条高亮与颜色块。activeBrush：当前笔索引，-1 表示不变。 */
    private fun refreshDrawBar(activeBrush: Int) {
        // 找出当前生效的笔（按宽度匹配）
        var cur = -1
        if (!eraserOn) cur = when (canvas.brush.width) {
            0.005f -> 0; 0.011f -> 1; 0.02f -> 2; 0.032f -> 3; else -> 1
        }
        for (i in brushBtns.indices) {
            val v = brushBtns[i]
            val active = i == cur && !eraserOn
            v.setTextColor(if (active) 0xFFFAFAF7.toInt() else 0xFF1A1A18.toInt())
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(if (active) 0xFF1A1A18.toInt() else 0x00000000)
            }
        }
        eraserBtn.setTextColor(if (eraserOn) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
        eraserBtn.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            if (eraserOn) {
                setColor(0x14B5442A)
                setStroke(dp(1f), 0xFFB5442A.toInt())
            } else setColor(0x00000000)
        }
        // 颜色块：显示当前笔色
        colorChip.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(com.yussy.folior.model.parseColor(brushColor, 0xFF1A1A18.toInt()))
            setStroke(dp(1f), 0x22000000)
        }
    }

    /** 打开内联装裱面板：替换选择工具栏位置，调整即时生效。 */
    private fun openFraming(target: Item) {
        framingOpen = true
        framingPanel.bind(target)
        framingPanel.visibility = View.VISIBLE
        updateBars()
    }

    private fun closeFraming() {
        framingOpen = false
        framingPanel.visibility = View.GONE
        canvas.select(null)
        updateBars()
    }

    private fun updateBars() {
        if (canvas.selected != null && canvas.activeIndex != pageIndex) {
            pageIndex = canvas.activeIndex
            strip.setCurrent(pageIndex)
        }
        updatePageLabel()
        // 装裱面板打开时：目标被取消选中 → 自动收起
        if (framingOpen) {
            val bound = framingPanel.boundItem()
            if (canvas.selected == null || bound == null || canvas.selected !== bound) {
                framingOpen = false
                framingPanel.visibility = View.GONE
            }
        }
        framingPanel.visibility = if (framingOpen) View.VISIBLE else View.GONE
        selectionBar.visibility =
            if (!drawMode && !framingOpen && canvas.selected != null)
                View.VISIBLE else View.GONE

        // 横屏右侧是单一工具槽：选中元素 / 装裱 / 绘制时让位给对应工具组，
        // 一级工具隐藏——避免十几项按钮同屏拥挤、也不需要滚动
        val land = isLandscape()
        tools.visibility =
            if (land && (drawMode || framingOpen || canvas.selected != null)) View.GONE
            else View.VISIBLE
        if (land) {
            val dockW = if (framingOpen) dp(240f) else dp(108f)
            val lpD = rightDock.layoutParams as LinearLayout.LayoutParams
            if (lpD.width != dockW) {
                lpD.width = dockW
                rightDock.layoutParams = lpD
            }
        }
        if (canvas.selected != null) {
            val it = canvas.selected!!
            // 行1 构成：删除 / 装裱 / 尺寸 / 编辑 —— 按元素类型控制可见性
            (selectionBar.getChildAt(0) as LinearLayout).let { row ->
                val isPhoto = it.type == Item.TYPE_PHOTO
                val isExh = project.type == Project.TYPE_EXHIBITION
                (row.getChildAt(1) as TextView).visibility =   // 装裱：照片与文字
                    if (isPhoto || it.type == Item.TYPE_TEXT) View.VISIBLE else View.GONE
                (row.getChildAt(2) as TextView).visibility =   // 尺寸：展览照片
                    if (isPhoto && isExh) View.VISIBLE else View.GONE
                (row.getChildAt(3) as TextView).visibility =   // 编辑：非照片
                    if (isPhoto) View.GONE else View.VISIBLE
            }
            // 行2（复制/置顶/下移/转90°/跨页）全部常显，无需处理
        }
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 600)
    }

    override fun onPause() {
        super.onPause()
        Store.save(this, project)
    }

    // ---------- 菜单 ----------

    private fun showMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "项目设置" to { projectSettings() })
        actions.add(if (project.type == Project.TYPE_EXHIBITION)
            "墙体设置" to { resizeCurrentPage() }
        else "页面设置" to { resizeCurrentPage() })
        actions.add("导出 PDF" to { exportPdf() })
        actions.add("导出工程文件 (.folio)" to { exportBundle() })
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }.show()
    }

    private fun addPage() {
        project.pages.add(pageIndex + 1, Page(bg = page().bg))
        pageIndex += 1
        refreshAll()
        scheduleSave()
    }

    private fun projectSettings() {
        ProjectDialog.show(this, true, project.title, project.type,
            project.pageWmm, project.pageHmm, project.defaultBg) { r ->
            project.title = r.title
            project.type = r.type
            project.pageWmm = r.wmm
            project.pageHmm = r.hmm
            project.defaultBg = r.bg
            canvas.invalidateLayout()
            strip.refresh()
            scheduleSave()
        }
    }

    // ---------- 添加元素 ----------

    /** 0 = 每张照片新建一页；1 = 加入当前页面 */
    private var photoAddMode = 0

    private fun pickPhotos() {
        val items = arrayOf("每张照片新建一页", "添加到当前页面")
        AlertDialog.Builder(this)
            .setTitle("照片添加到")
            .setItems(items) { _, which ->
                photoAddMode = if (which == 0) 0 else 1
                launchPhotoPicker()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchPhotoPicker() {
        try {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开相册选择器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理相册返回：按用户选择的去向分流。 */
    private fun handlePickedPhotos(uris: List<Uri>) {
        val total = uris.size
        val progress = AlertDialog.Builder(this)
            .setTitle("正在导入")
            .setMessage("0 / $total")
            .setCancelable(false)
            .create()
        progress.show()
        Thread {
            val imgIds = ArrayList<String>()
            uris.forEachIndexed { i, uri ->
                Store.importImage(this, uri)?.let { imgIds.add(it) }
                runOnUiThread { progress.setMessage("${i + 1} / $total") }
            }
            runOnUiThread {
                progress.dismiss()
                if (imgIds.isEmpty()) {
                    Toast.makeText(this, "照片读取失败，请重试", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val isExh = project.type == Project.TYPE_EXHIBITION
                if (photoAddMode == 1) {
                    // 全部加到当前页/墙
                    imgIds.forEachIndexed { i, id ->
                        if (isExh) page().items.add(placeOnWall(id, page(), i))
                        else addImportedPhoto(id, i)
                    }
                    canvas.select(page().items.lastOrNull())
                } else if (isExh) {
                    // 展览：新建一面默认尺寸的墙，照片按实际尺寸摆上
                    val wall = Page(wmm = project.pageWmm, hmm = project.pageHmm, bg = page().bg)
                    imgIds.forEachIndexed { i, id -> wall.items.add(placeOnWall(id, wall, i)) }
                    project.pages.add(pageIndex + 1, wall)
                    pageIndex += 1
                    canvas.select(null)
                    refreshAll()
                } else {
                    // 每张照片一页，接在当前页之后
                    var insertAt = pageIndex
                    val firstNew = pageIndex + 1
                    imgIds.forEach { id ->
                        insertAt++
                        val pg = Page(bg = page().bg)
                        pg.items.add(centeredPhotoItem(id))
                        project.pages.add(insertAt, pg)
                    }
                    pageIndex = firstNew
                    canvas.select(null)
                    refreshAll()
                }
                canvas.invalidate()
                strip.refresh()
                scheduleSave()
                oversizeNotes.clear()
            }
        }.start()
    }

    /** 生成"一张一页"版式：照片等比居中，四周留白（不裁切）。 */
    private fun centeredPhotoItem(imgId: String): Item {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(Store.imageFile(this, imgId)?.absolutePath, opts)
        val aspect = if (opts.outWidth > 0 && opts.outHeight > 0)
            opts.outWidth.toFloat() / opts.outHeight else 1.5f
        // 归一化坐标下保持真实比例：w = h * aspect * (页高/页宽)
        val k = aspect * project.pageHmm / project.pageWmm
        var h = 0.82f
        var w = h * k
        val maxW = 0.86f
        if (w > maxW) { w = maxW; h = w / k }
        return Item(
            type = Item.TYPE_PHOTO, imgId = imgId,
            x = (1f - w) / 2f, y = (1f - h) / 2f, w = w, h = h
        )
    }

    private fun nextInsertSlot(i: Int): Pair<Float, Float> {
        val base = 0.5f
        val off = (i % 5) * 0.035f
        return (base - off / 2) to (base - off / 2)
    }

    private fun addImportedPhoto(imgId: String, order: Int) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val f = Store.imageFile(this, imgId)
        BitmapFactory.decodeFile(f?.absolutePath, opts)
        val aspect = if (opts.outWidth > 0 && opts.outHeight > 0)
            opts.outWidth.toFloat() / opts.outHeight else 1.5f
        val h = 0.45f
        val pg = page()
        val w = (h * aspect * (pg.heightMm(project) / pg.widthMm(project))).coerceIn(0.08f, 0.96f)
        val (cx, cy) = nextInsertSlot(order)
        val item = Item(
            type = Item.TYPE_PHOTO, imgId = imgId,
            x = (cx - w / 2).coerceIn(0f, 1f - w),
            y = (cy - h / 2).coerceIn(0f, 1f - h),
            w = w, h = h
        )
        page().items.add(item)
    }

    private fun addText() {
        TextDialog.show(this, "文字", 0.04f, "#1A1A18", false, false, 0) { text, fs, color, bold, italic, align, vertical, lineHeight ->
            val item = Item(
                type = Item.TYPE_TEXT, text = text, fontScale = fs, color = color,
                bold = bold, italic = italic, align = align,
                vertical = vertical, lineHeight = lineHeight,
                x = 0.2f, y = 0.42f, w = 0.6f, h = fs * 3f + 0.04f
            )
            page().items.add(item)
            canvas.select(item)
            canvas.fitText(item)   // 按内容自适应高度
            scheduleSave()
        }
    }

    // ---------- 选中项操作 ----------

    private fun editSelected() {
        editItem(selected() ?: return)
    }

    private fun editItem(it: Item) {
        when (it.type) {
            Item.TYPE_TEXT -> TextDialog.show(this, it.text, it.fontScale, it.color,
                it.bold, it.italic, it.align, it.vertical, it.lineHeight) { text, fs, color, bold, italic, align, vertical, lineHeight ->
                it.text = text; it.fontScale = fs; it.color = color
                it.bold = bold; it.italic = italic; it.align = align
                it.vertical = vertical; it.lineHeight = lineHeight
                canvas.fitText(it)
                canvas.invalidate(); scheduleSave()
            }
            Item.TYPE_SHAPE -> {
                AlertDialog.Builder(this)
                    .setItems(arrayOf("矩形", "横线", "圆形", "改颜色")) { _, which ->
                        when (which) {
                            0 -> it.shapeKind = "rect"
                            1 -> it.shapeKind = "line"
                            2 -> it.shapeKind = "circle"
                            3 -> ColorPickerDialog.show(this, "色块颜色", it.shapeColor) { c -> it.shapeColor = c }
                        }
                        canvas.invalidate(); scheduleSave()
                    }.show()
            }
        }
    }

    private fun duplicateSelected() {
        val it = selected() ?: return
        val copy = it.copy(id = java.util.UUID.randomUUID().toString(),
            x = (it.x + 0.04f).coerceAtMost(1f - it.w), y = (it.y + 0.04f).coerceAtMost(1f - it.h))
        (pageOfItem(it) ?: page()).items.add(copy)
        canvas.select(copy)
        canvas.invalidate()
        scheduleSave()
    }

    private fun bringTop() {
        val it = selected() ?: return
        val items = (pageOfItem(it) ?: page()).items
        items.remove(it); items.add(it)
        canvas.invalidate(); scheduleSave()
    }

    private fun sendBack() {
        val it = selected() ?: return
        val items = (pageOfItem(it) ?: page()).items
        val idx = items.indexOf(it)
        if (idx > 0) { items.removeAt(idx); items.add(idx - 1, it) }
        canvas.invalidate(); scheduleSave()
    }

    // ---------- 导出 ----------

    private var pdfOptions = PdfExporter.Options()

    private fun exportPdf() {
        val items = arrayOf(
            "单页 · 标准（逐页，适合打印）",
            "单页 + 首页空白（双页阅读对齐 · 推荐）",
            "双页合并（每页即一组对开页）"
        )
        AlertDialog.Builder(this)
            .setTitle("PDF 导出方式")
            .setItems(items) { _, which ->
                pdfOptions = when (which) {
                    1 -> PdfExporter.Options(insertBlankFirst = true)
                    2 -> PdfExporter.Options(mergeSpreads = true)
                    else -> PdfExporter.Options()
                }
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_TITLE, "${project.title}.pdf")
                    startActivityForResult(this, REQ_PDF)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportBundle() {
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${project.title}.folio")
            startActivityForResult(this, REQ_BUNDLE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PDF -> {
                val uri = data.data ?: return
                val dialog = AlertDialog.Builder(this)
                    .setTitle("正在导出 PDF…")
                    .setMessage("准备中…")
                    .setCancelable(false)
                    .create()
                dialog.show()
                try {
                    val opts = pdfOptions
                    contentResolver.openOutputStream(uri)?.let { out ->
                        PdfExporter.export(this, project, out, opts, object : PdfExporter.Progress {
                            override fun onProgress(done: Int, total: Int) {
                                dialog.setMessage("渲染页面 $done / $total")
                            }

                            override fun onStage(text: String) {
                                dialog.setMessage(text)
                            }
                            override fun onDone(ok: Boolean, error: String?) {
                                dialog.dismiss()
                                if (!ok) Toast.makeText(this@EditorActivity,
                                    "导出失败：$error", Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                } catch (e: Exception) {
                    dialog.dismiss()
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_BUNDLE -> {
                val uri = data.data ?: return
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        Store.exportBundle(this, project, it)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

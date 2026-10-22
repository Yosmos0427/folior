package com.yussy.folior

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
    private lateinit var selectionBar: HorizontalScrollView
    private lateinit var cropBar: LinearLayout
    private lateinit var bodyRow: LinearLayout
    private lateinit var canvasWrap: android.widget.FrameLayout
    private lateinit var deleteZone: LinearLayout
    private var draggingPageId: String? = null
    private var suppressReorderOnce = false
    private var zoneHot = false
    private lateinit var sideStripWrap: LinearLayout
    private lateinit var bottomStripWrap: LinearLayout
    private var pageIndex = 0
    private var spread = false

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAFAF7.toInt())
        }

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
        deleteZone = buildDeleteZone()
        canvasWrap.addView(deleteZone)
        bodyRow.addView(canvasWrap)
        root.addView(bodyRow)

        // ---- 选择工具栏 ----
        selectionBar = HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
        }
        val selRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        fun selBtn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { action() }
            selRow.addView(this)
        }
        selBtn("裁剪") { selected()?.let { if (it.type == Item.TYPE_PHOTO && it.imgId != null) canvas.enterCrop(it) } }
        selBtn("编辑") { editSelected() }
        selBtn("复制") { duplicateSelected() }
        selBtn("置顶") { bringTop() }
        selBtn("下移") { sendBack() }
        selBtn("转90°") { canvas.rotateSelected90() }
        selBtn("跨页") {
            if (!canvas.spanSelected())
                Toast.makeText(this, "需横屏并同时显示左右两页", Toast.LENGTH_SHORT).show()
        }
        selBtn("删除") { deleteSelected() }
        selectionBar.addView(selRow)
        root.addView(selectionBar)

        // ---- 裁切确认栏 ----
        cropBar = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        cropBar.addView(TextView(this).apply {
            text = "取消"
            textSize = 14f
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            setOnClickListener { canvas.exitCrop(false); updateBars() }
        })
        cropBar.addView(TextView(this).apply {
            text = "完成裁切"
            textSize = 14f
            setTextColor(0xFFFAFAF7.toInt())
            setBackgroundColor(0xFF1A1A18.toInt())
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(12f), 0, 0, 0)
            setOnClickListener { canvas.exitCrop(true); updateBars(); refreshAll() }
        })
        root.addView(cropBar)

        // ---- 底部工具条 ----
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        fun toolBtn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener { action() }
            tools.addView(this)
        }
        toolBtn("＋照片") { pickPhotos() }
        toolBtn("＋文字") { addText() }
        toolBtn("＋色块") { addShape() }
        toolBtn("底色") { pageBg() }
        toolBtn("放映") { startActivity(Intent(this, ViewerActivity::class.java).putExtra("id", project.id)) }
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

        setContentView(root)

        canvas.project = project
        canvas.onSelectionChanged = { updateBars() }
        canvas.onModified = { scheduleSave(); strip.refresh() }
        canvas.onOpenCrop = { canvas.enterCrop(it); updateBars() }
        canvas.onFlip = { dir -> flipSpread(dir) }

        strip.project = project
        strip.onPageSelected = { idx ->
            pageIndex = idx
            canvas.select(null)
            syncSpread()
            canvas.invalidateLayout()
            updateBars()
        }
        strip.onPageReordered = { idx ->
            if (suppressReorderOnce) {
                // 该页已被拖入删除框删除，忽略紧随其后的换序回调
                suppressReorderOnce = false
            } else {
                pageIndex = idx.coerceIn(0, project.pages.size - 1)
                scheduleSave()
                syncSpread()
                canvas.invalidateLayout()
                updateBars()
            }
        }
        strip.onPageAdded = { addPage() }
        strip.onDragStart = { id -> draggingPageId = id; showDeleteZone() }
        strip.onDragMove = { x, y -> handleDragMove(x, y) }
        strip.onDragEnd = { id, x, y -> handleDragEnd(id, x, y) }

        applyOrientation()
        refreshAll()
    }

    // ---------------- 跨页 / 横竖屏 ----------------

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** 横屏：页面条转为工作区左侧纵向排列；竖屏：回到底部横向排列。 */
    private fun applyOrientation() {
        val land = isLandscape()
        spread = land && project.type == Project.TYPE_BOOK
        canvas.spreadMode = spread

        if (land) {
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
        } else {
            sideStripWrap.visibility = View.GONE
            bottomStripWrap.visibility = View.VISIBLE
            bottomStripWrap.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96f))
            if (strip.parent !== bottomStripWrap) {
                (strip.parent as? ViewGroup)?.removeView(strip)
                bottomStripWrap.addView(strip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            strip.setVertical(false)
        }

        syncSpread()
        canvas.invalidateLayout()
        updateBars()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
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
        strip.setCurrent(pageIndex)
        syncSpread(); canvas.invalidateLayout(); updateBars()
    }

    /** 工作区中央的删除投放框（长按拖页时浮现）。 */
    private fun buildDeleteZone(): LinearLayout {
        val zone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            alpha = 0f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp(132f), dp(132f), Gravity.CENTER)
            background = zoneBg(false)
        }
        zone.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f))
        })
        zone.addView(TextView(this).apply {
            text = "删除页面"
            textSize = 12f
            setTextColor(0xFF6B6B66.toInt())
            setPadding(0, dp(10f), 0, 0)
        })
        zone.elevation = dp(2f).toFloat()
        return zone
    }

    private fun zoneBg(hot: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(18f).toFloat()
        setColor(if (hot) 0xFFFDEFEA.toInt() else 0xF7FAFAF7.toInt())
        setStroke(dp(if (hot) 1.6f else 1f), if (hot) 0xFFB5442A.toInt() else 0xFFD8D4C9.toInt())
    }

    private fun zoneRectOnScreen(): android.graphics.Rect {
        val loc = IntArray(2)
        deleteZone.getLocationOnScreen(loc)
        return android.graphics.Rect(loc[0], loc[1],
            loc[0] + deleteZone.width, loc[1] + deleteZone.height)
    }

    private fun showDeleteZone() {
        zoneHot = false
        deleteZone.background = zoneBg(false)
        deleteZone.visibility = View.VISIBLE
        deleteZone.scaleX = 0.92f; deleteZone.scaleY = 0.92f
        deleteZone.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130).start()
    }

    private fun hideDeleteZone() {
        deleteZone.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(110)
            .withEndAction {
                deleteZone.visibility = View.GONE
                deleteZone.background = zoneBg(false)
                zoneHot = false
            }.start()
    }

    private fun handleDragMove(x: Float, y: Float) {
        if (deleteZone.visibility != View.VISIBLE) return
        val hot = zoneRectOnScreen().contains(x.toInt(), y.toInt())
        if (hot != zoneHot) {
            zoneHot = hot
            deleteZone.background = zoneBg(hot)
            deleteZone.animate().scaleX(if (hot) 1.06f else 1f)
                .scaleY(if (hot) 1.06f else 1f).setDuration(90).start()
        }
    }

    /** 拖入删除框即删页（替代菜单删除）。 */
    private fun handleDragEnd(pageId: String, x: Float, y: Float) {
        val dropped = deleteZone.visibility == View.VISIBLE &&
            zoneRectOnScreen().contains(x.toInt(), y.toInt())
        hideDeleteZone()
        if (!dropped) return
        suppressReorderOnce = true
        deletePageById(pageId)
    }

    private fun deletePageById(pageId: String) {
        val idx = project.pages.indexOfFirst { it.id == pageId }
        if (idx < 0) return
        if (project.pages.size <= 1) {
            Toast.makeText(this, "至少保留一页", Toast.LENGTH_SHORT).show()
            return
        }
        project.pages.removeAt(idx)
        pageIndex = pageIndex.coerceIn(0, project.pages.size - 1)
        canvas.select(null)
        refreshAll()
        scheduleSave()
        Toast.makeText(this, "已删除第 ${idx + 1} 页", Toast.LENGTH_SHORT).show()
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

    private fun updateBars() {
        if (canvas.selected != null && canvas.activeIndex != pageIndex) {
            pageIndex = canvas.activeIndex
            strip.setCurrent(pageIndex)
            strip.setCurrent(pageIndex)
        }
        updatePageLabel()
        val inCrop = canvas.cropItem != null
        cropBar.visibility = if (inCrop) View.VISIBLE else View.GONE
        selectionBar.visibility = if (!inCrop && canvas.selected != null) View.VISIBLE else View.GONE
        if (canvas.selected != null) {
            val it = canvas.selected!!
            // 裁剪按钮只对照片有效；编辑对文字/色块有效 —— 动态调整第一个按钮文案
            (selectionBar.getChildAt(0) as LinearLayout).let { row ->
                (row.getChildAt(0) as TextView).visibility =
                    if (it.type == Item.TYPE_PHOTO) View.VISIBLE else View.GONE
                (row.getChildAt(1) as TextView).visibility =
                    if (it.type == Item.TYPE_PHOTO) View.GONE else View.VISIBLE
            }
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
        val options = arrayOf("项目设置", "导出 PDF", "导出工程文件 (.folio)")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> projectSettings()
                    1 -> exportPdf()
                    2 -> exportBundle()
                }
            }.show()
    }

    private fun addPage() {
        project.pages.add(pageIndex + 1, Page(bg = page().bg))
        pageIndex += 1
        refreshAll()
        scheduleSave()
    }

    private fun pageBg() {
        ColorPickerDialog.show(this, "本页底色",
            page().bg ?: project.defaultBg) { c ->
            page().bg = c
            canvas.invalidate()
            scheduleSave()
        }
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
                if (photoAddMode == 1) {
                    // 全部加到当前页，依次错位叠放
                    imgIds.forEachIndexed { i, id -> addImportedPhoto(id, i) }
                    canvas.select(page().items.lastOrNull())
                    Toast.makeText(this, "已添加 ${imgIds.size} 张到第 ${pageIndex + 1} 页",
                        Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "已添加 ${imgIds.size} 张照片 · 各占一页",
                        Toast.LENGTH_LONG).show()
                }
                canvas.invalidate()
                strip.refresh()
                scheduleSave()
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
        val w = (h * aspect * (project.pageHmm / project.pageWmm)).coerceIn(0.08f, 0.96f)
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
        TextDialog.show(this, "文字", 0.04f, "#1A1A18", false, false, 0) { text, fs, color, bold, italic, align ->
            val item = Item(
                type = Item.TYPE_TEXT, text = text, fontScale = fs, color = color,
                bold = bold, italic = italic, align = align,
                x = 0.2f, y = 0.42f, w = 0.6f, h = fs * 3f + 0.04f
            )
            page().items.add(item)
            canvas.invalidate()
            canvas.select(item)
            scheduleSave()
        }
    }

    private fun addShape() {
        val item = Item(
            type = Item.TYPE_SHAPE, shapeKind = "rect", shapeColor = "#1A1A18",
            x = 0.3f, y = 0.42f, w = 0.4f, h = 0.12f
        )
        page().items.add(item)
        canvas.invalidate()
        canvas.select(item)
        scheduleSave()
    }

    // ---------- 选中项操作 ----------

    private fun editSelected() {
        val it = selected() ?: return
        when (it.type) {
            Item.TYPE_TEXT -> TextDialog.show(this, it.text, it.fontScale, it.color, it.bold, it.italic, it.align) { text, fs, color, bold, italic, align ->
                it.text = text; it.fontScale = fs; it.color = color
                it.bold = bold; it.italic = italic; it.align = align
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

    private fun deleteSelected() {
        val it = selected() ?: return
        AlertDialog.Builder(this).setTitle("删除该元素？")
            .setPositiveButton("删除") { _, _ ->
                (pageOfItem(it) ?: page()).items.remove(it)
                canvas.select(null)
                canvas.invalidate()
                scheduleSave()
            }.setNegativeButton("取消", null).show()
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
                                val desc = when {
                                    opts.mergeSpreads -> "已按对开页合并导出"
                                    opts.insertBlankFirst -> "已插入首页空白（封面落在双页视图右侧）"
                                    else -> "已按单页导出"
                                }
                                Toast.makeText(this@EditorActivity,
                                    if (ok) "PDF 已导出 · $desc\n真实尺寸 ${project.pageWmm.toInt()}×${project.pageHmm.toInt()}mm"
                                    else "导出失败：$error",
                                    if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "工程文件已导出（含全部照片）", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

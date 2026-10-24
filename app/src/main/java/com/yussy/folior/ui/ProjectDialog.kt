package com.yussy.folior.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 项目设置：名称 / 类型（摄影书·摄影展览 二选一）/ 尺寸 / 预设 / 默认底色。
 * - 摄影书：页面尺寸，单位 mm，预设 A4 / B5 / 方册
 * - 摄影展览：墙体大小，单位 m（0.1 – 100 m），预设为常见展厅墙体
 */
object ProjectDialog {

    data class Result(
        val title: String, val type: String,
        val wmm: Float, val hmm: Float, val bg: String
    )

    private val BOOK_PRESETS = listOf(
        Triple("A4", 210f, 297f),
        Triple("B5", 176f, 250f),
        Triple("方册", 210f, 210f)
    )

    private val WALL_PRESETS = listOf(
        Triple("3×2m", 3000f, 2000f),
        Triple("3×3m", 3000f, 3000f),
        Triple("4×3m", 4000f, 3000f),
        Triple("6×3m", 6000f, 3000f)
    )

    fun show(
        ctx: Context, edit: Boolean,
        initTitle: String, initType: String, initW: Float, initH: Float, initBg: String,
        onDone: (Result) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        var type = if (initType == "exhibition") "exhibition" else "book"
        var wmm = initW
        var hmm = initH

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(8f), px(20f), 0)
        }

        // ---------- 名称 ----------
        root.addView(sectionLabel(ctx, "名称", first = true))
        val titleInput = EditText(ctx).apply {
            setText(initTitle)
            hint = "给这本摄影书 / 这场展览起个名字"
            textSize = 15f
            setSingleLine()
            setPadding(px(12f), px(11f), px(12f), px(11f))
            background = boxBg(ctx)
            setTextColor(0xFF1A1A18.toInt())
            setHintTextColor(0xFFA8A8A0.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(titleInput)

        // 尺寸区标签（类型切换时联动文案，提前创建）
        val sizeLabel = sectionLabel(ctx, "页面尺寸")
        val hintView = hint(ctx, "")

        // ---------- 类型（二选一） ----------
        root.addView(sectionLabel(ctx, "类型"))
        val typeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bookBtn = chip(ctx, "摄影书", 14f)
        val exhBtn = chip(ctx, "摄影展览", 14f)
        val gap = px(10f)
        typeRow.addView(bookBtn, LinearLayout.LayoutParams(0, px(44f), 1f).apply { rightMargin = gap / 2 })
        typeRow.addView(exhBtn, LinearLayout.LayoutParams(0, px(44f), 1f).apply { leftMargin = gap / 2 })
        root.addView(typeRow)

        // ---------- 尺寸 ----------
        root.addView(sizeLabel)
        val sizeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var syncing = false
        fun sizeInput(): EditText = EditText(ctx).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setPadding(px(6f), px(11f), px(6f), px(11f))
            background = boxBg(ctx)
            setTextColor(0xFF1A1A18.toInt())
        }
        val wIn = sizeInput()
        val hIn = sizeInput()
        val times = TextView(ctx).apply {
            text = "×"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFF9A9A93.toInt())
        }
        val unit = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFF8A8A84.toInt())
            setPadding(px(8f), 0, 0, 0)
        }
        sizeRow.addView(wIn, LinearLayout.LayoutParams(0, px(44f), 1f))
        sizeRow.addView(times, LinearLayout.LayoutParams(px(30f), px(44f)))
        sizeRow.addView(hIn, LinearLayout.LayoutParams(0, px(44f), 1f))
        sizeRow.addView(unit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sizeRow)
        root.addView(hintView)

        fun watchField(edit: EditText, isW: Boolean) {
            edit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val v = s?.toString()?.toFloatOrNull() ?: return
                    if (type == "exhibition") {
                        val mm = (v * 1000f).coerceIn(100f, 100000f)
                        if (isW) wmm = mm else hmm = mm
                    } else {
                        val mm = v.coerceIn(30f, 2000f)
                        if (isW) wmm = mm else hmm = mm
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        watchField(wIn, true)
        watchField(hIn, false)

        // ---------- 预设 ----------
        root.addView(sectionLabel(ctx, "预设"))
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(presetRow)

        fun fmt(mm: Float, meters: Boolean): String {
            if (!meters) return if (mm == mm.toInt().toFloat()) mm.toInt().toString()
            else String.format("%.1f", mm).trimEnd('0').trimEnd('.')
            val m = mm / 1000f
            return if (m == m.toInt().toFloat()) m.toInt().toString()
            else String.format("%.2f", m).trimEnd('0').trimEnd('.')
        }

        fun renderSize() {
            syncing = true
            val meters = type == "exhibition"
            unit.text = if (meters) "m" else "mm"
            wIn.setText(fmt(wmm, meters))
            hIn.setText(fmt(hmm, meters))
            sizeLabel.text = if (meters) "墙体大小" else "页面尺寸"
            hintView.text = if (meters)
                "宽 × 高，单位米（m），可分别输入；范围 0.1 – 100 m"
            else
                "宽 × 高，可分别输入；范围 30 – 2000 mm"
            // 预设重建
            presetRow.removeAllViews()
            val presets = if (meters) WALL_PRESETS else BOOK_PRESETS
            presets.forEachIndexed { i, (name, w, h) ->
                val chip = chip(ctx, name, 12.5f)
                presetRow.addView(chip, LinearLayout.LayoutParams(0, px(34f), 1f).apply {
                    if (i > 0) leftMargin = px(8f)
                })
                chip.setOnClickListener {
                    wmm = w; hmm = h
                    renderSize()
                }
            }
            syncing = false
        }

        fun renderType() {
            styleChip(bookBtn, type == "book", solid = true)
            styleChip(exhBtn, type == "exhibition", solid = true)
            renderSize()
        }
        bookBtn.setOnClickListener { type = "book"; renderType() }
        exhBtn.setOnClickListener { type = "exhibition"; renderType() }
        renderType()

        // ---------- 默认底色 ----------
        root.addView(sectionLabel(ctx, "默认底色"))
        var bg = initBg
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun swatchBg(c: String) = GradientDrawable().apply {
            setColor(parse(c)); cornerRadius = px(6f).toFloat()
            setStroke(px(1f), 0x22000000)
        }
        val swatch = TextView(ctx).apply {
            background = swatchBg(bg)
            layoutParams = LinearLayout.LayoutParams(px(34f), px(34f))
        }
        val pick = TextView(ctx).apply {
            text = "选择颜色"
            textSize = 13f
            setTextColor(0xFF8A5B4A.toInt())
            setPadding(px(12f), px(10f), px(12f), px(10f))
            setOnClickListener {
                ColorPickerDialog.show(ctx, "默认底色", bg) { c ->
                    bg = c
                    swatch.background = swatchBg(c)
                }
            }
        }
        colorRow.addView(swatch)
        colorRow.addView(pick)
        root.addView(colorRow)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (edit) "项目设置" else "新建照片集")
            .setView(scrollableDialogContent(ctx, root))
            .setPositiveButton(if (edit) "保存" else "创建", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val meters = type == "exhibition"
            val w: Float
            val h: Float
            if (meters) {
                w = ((wIn.text.toString().toFloatOrNull() ?: (wmm / 1000f)) * 1000f)
                    .coerceIn(100f, 100000f)
                h = ((hIn.text.toString().toFloatOrNull() ?: (hmm / 1000f)) * 1000f)
                    .coerceIn(100f, 100000f)
            } else {
                w = (wIn.text.toString().toFloatOrNull() ?: wmm).coerceIn(30f, 2000f)
                h = (hIn.text.toString().toFloatOrNull() ?: hmm).coerceIn(30f, 2000f)
            }
            onDone(Result(titleInput.text.toString().ifBlank { "未命名" }, type, w, h, bg))
            dialog.dismiss()
        }
    }

    // ---------------- 视图工厂 ----------------

    private fun sectionLabel(ctx: Context, text: String, first: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            letterSpacing = 0.06f
            setTextColor(0xFF8A8A84.toInt())
            val dd = ctx.resources.displayMetrics.density
            setPadding((2 * dd).toInt(),
                ((if (first) 4 else 16) * dd).toInt(), 0, (7 * dd).toInt())
        }

    private fun hint(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFA8A8A0.toInt())
            val p = (3 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, 0, 0)
        }

    private fun boxBg(c: Context): GradientDrawable {
        val d = c.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(0xFFFFFFFF.toInt())
            setStroke((1 * d).toInt(), 0xFFDDDDD6.toInt())
        }
    }

    private fun chip(ctx: Context, text: String, size: Float): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            isClickable = true
        }

    private fun styleChip(v: TextView, selected: Boolean, solid: Boolean) {
        val d = v.resources.displayMetrics.density
        v.setTextColor(if (selected) Color.WHITE else 0xFF4A4A45.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(if (selected) 0xFF1A1A18.toInt() else 0x00000000)
            if (!selected) setStroke((1 * d).toInt(), 0xFFDDDDD6.toInt())
        }
    }

    private fun parse(c: String?): Int = try {
        Color.parseColor(c ?: "#FFFFFF")
    } catch (e: Exception) { Color.WHITE }
}

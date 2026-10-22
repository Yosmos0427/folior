package com.yussy.folior.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 项目设置：名称 / 类型（摄影书·摄影展览 二选一）/ 页面尺寸（宽×高，可分别输入，单位 mm）/ 预设 / 默认底色。
 * 极简风格：细线描边、墨色选中态、统一 8dp 圆角。
 */
object ProjectDialog {

    data class Result(
        val title: String, val type: String,
        val wmm: Float, val hmm: Float, val bg: String
    )

    /** 仅保留三项预设：A4 / B5 / 方册 */
    private val PRESETS = listOf(
        Triple("A4", 210f, 297f),
        Triple("B5", 176f, 250f),
        Triple("方册", 210f, 210f)
    )

    fun show(
        ctx: Context, edit: Boolean,
        initTitle: String, initType: String, initW: Float, initH: Float, initBg: String,
        onDone: (Result) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

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
            background = boxBg(ctx, false)
            setTextColor(0xFF1A1A18.toInt())
            setHintTextColor(0xFFA8A8A0.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(titleInput)

        // ---------- 类型（二选一） ----------
        root.addView(sectionLabel(ctx, "类型"))
        var type = if (initType == "exhibition") "exhibition" else "book"
        val typeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bookBtn = typeChip(ctx, "摄影书")
        val exhBtn = typeChip(ctx, "摄影展览")
        val gap = px(10f)
        typeRow.addView(bookBtn, LinearLayout.LayoutParams(0, px(44f), 1f).apply { rightMargin = gap / 2 })
        typeRow.addView(exhBtn, LinearLayout.LayoutParams(0, px(44f), 1f).apply { leftMargin = gap / 2 })
        root.addView(typeRow)

        fun renderType() {
            styleType(bookBtn, type == "book")
            styleType(exhBtn, type == "exhibition")
        }
        bookBtn.setOnClickListener { type = "book"; renderType() }
        exhBtn.setOnClickListener { type = "exhibition"; renderType() }
        renderType()

        // ---------- 页面尺寸 ----------
        root.addView(sectionLabel(ctx, "页面尺寸"))
        val sizeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wInput = sizeInput(ctx, initW)
        val hInput = sizeInput(ctx, initH)
        val times = TextView(ctx).apply {
            text = "×"
            textSize = 15f
            setTextColor(0xFF9A9A93.toInt())
            gravity = Gravity.CENTER
        }
        val unit = TextView(ctx).apply {
            text = "mm"
            textSize = 12f
            setTextColor(0xFF8A8A84.toInt())
            setPadding(px(8f), 0, 0, 0)
        }
        sizeRow.addView(wInput, LinearLayout.LayoutParams(0, px(44f), 1f))
        sizeRow.addView(times, LinearLayout.LayoutParams(px(30f), px(44f)))
        sizeRow.addView(hInput, LinearLayout.LayoutParams(0, px(44f), 1f))
        sizeRow.addView(unit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sizeRow)
        root.addView(hint(ctx, "宽 × 高，可分别输入；范围 30 – 2000 mm"))

        // ---------- 预设 ----------
        root.addView(sectionLabel(ctx, "预设"))
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var selectedPreset = -1
        val chips = ArrayList<TextView>()
        PRESETS.forEachIndexed { i, (name, w, h) ->
            val chip = presetChip(ctx, name)
            chips.add(chip)
            val lp = LinearLayout.LayoutParams(0, px(36f), 1f)
            if (i > 0) lp.leftMargin = px(8f)
            presetRow.addView(chip, lp)
            chip.setOnClickListener {
                selectedPreset = i
                wInput.setText(fmt(w))
                hInput.setText(fmt(h))
                chips.forEachIndexed { k, c -> stylePreset(ctx, c, k == selectedPreset) }
            }
            stylePreset(ctx, chip, false)
        }
        root.addView(presetRow)
        // 初始尺寸正好命中某个预设时高亮
        PRESETS.indexOfFirst { it.second == initW && it.third == initH }.let { hit ->
            if (hit >= 0) { selectedPreset = hit; stylePreset(ctx, chips[hit], true) }
        }

        // ---------- 默认底色 ----------
        root.addView(sectionLabel(ctx, "默认底色"))
        var bg = initBg
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatch = TextView(ctx).apply {
            setBackgroundColor(parse(bg))
            layoutParams = LinearLayout.LayoutParams(px(34f), px(34f))
            background = GradientDrawable().apply {
                setColor(parse(bg)); cornerRadius = px(6f).toFloat()
                setStroke(px(1f), 0x22000000)
            }
        }
        val pick = TextView(ctx).apply {
            text = "选择颜色"
            textSize = 13f
            setTextColor(0xFF8A5B4A.toInt())
            setPadding(px(12f), px(10f), px(12f), px(10f))
            setOnClickListener {
                ColorPickerDialog.show(ctx, "默认底色", bg) { c ->
                    bg = c
                    swatch.background = GradientDrawable().apply {
                        setColor(parse(c)); cornerRadius = px(6f).toFloat()
                        setStroke(px(1f), 0x22000000)
                    }
                }
            }
        }
        colorRow.addView(swatch)
        colorRow.addView(pick)
        root.addView(colorRow)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (edit) "项目设置" else "新建照片集")
            .setView(root)
            .setPositiveButton(if (edit) "保存" else "创建", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val w = (wInput.text.toString().toFloatOrNull() ?: initW).coerceIn(30f, 2000f)
            val h = (hInput.text.toString().toFloatOrNull() ?: initH).coerceIn(30f, 2000f)
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
            setPadding((2 * ctx.resources.displayMetrics.density).toInt(),
                ((if (first) 4 else 16) * ctx.resources.displayMetrics.density).toInt(),
                0, (7 * ctx.resources.displayMetrics.density).toInt())
        }

    private fun hint(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFA8A8A0.toInt())
            val p = (3 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, 0, 0)
        }

    private fun boxBg(ctx: Context, focus: Boolean): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(0xFFFFFFFF.toInt())
            setStroke((if (focus) 1.4f else 1f * 1f).let { (it * d).toInt() },
                if (focus) 0xFFB5442A.toInt() else 0xFFDDDDD6.toInt())
        }
    }

    private fun sizeInput(ctx: Context, value: Float): EditText {
        val d = ctx.resources.displayMetrics.density
        return EditText(ctx).apply {
            setText(fmt(value))
            textSize = 15f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            background = boxBg(ctx, false)
            setTextColor(0xFF1A1A18.toInt())
        }
    }

    private fun typeChip(ctx: Context, text: String): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, 0, 0, 0)
        }
    }

    private fun styleType(v: TextView, selected: Boolean) {
        val d = v.resources.displayMetrics.density
        v.setTextColor(if (selected) Color.WHITE else 0xFF4A4A45.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(if (selected) 0xFF1A1A18.toInt() else 0x00000000)
            if (!selected) setStroke((1 * d).toInt(), 0xFFDDDDD6.toInt())
        }
    }

    private fun presetChip(ctx: Context, text: String): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
        }
    }

    private fun stylePreset(ctx: Context, v: TextView, selected: Boolean) {
        val d = ctx.resources.displayMetrics.density
        v.setTextColor(if (selected) 0xFFB5442A.toInt() else 0xFF4A4A45.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(if (selected) 0xFFFBEFEB.toInt() else 0x00000000)
            setStroke((1 * d).toInt(),
                if (selected) 0xFFB5442A.toInt() else 0xFFDDDDD6.toInt())
        }
    }

    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)

    private fun parse(c: String?): Int = try {
        Color.parseColor(c ?: "#FFFFFF")
    } catch (e: Exception) { Color.WHITE }
}

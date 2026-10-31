package com.yussy.folior.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 宽×高（mm）输入对话框：新建自定义页面 / 墙体、修改单页尺寸。 */
object PageSizeDialog {

    /**
     * 宽×高输入，可同时管理本页颜色。
     * @param projectBg 非 null 时显示颜色区（勾选框 + 色块），值用于预览「应用项目设置」
     * @param pageBg 页面自身颜色；null = 跟随项目
     * @param onDone bg 为 null 表示跟随项目设置
     */
    fun show(
        ctx: Context, title: String, initW: Float, initH: Float,
        projectBg: String? = null,
        pageBg: String? = null,
        onDone: (w: Float, h: Float, bg: String?) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(8f), px(20f), 0)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun input(v: Float) = EditText(ctx).apply {
            setText(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
            textSize = 15f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setPadding(px(6f), px(11f), px(6f), px(11f))
            setTextColor(0xFF1A1A18.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(0xFFFFFFFF.toInt())
                setStroke(px(1f), 0xFFDDDDD6.toInt())
            }
        }
        val wIn = input(initW)
        val hIn = input(initH)
        val times = TextView(ctx).apply {
            text = "×"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFF9A9A93.toInt())
        }
        val unit = TextView(ctx).apply {
            text = "mm"; textSize = 12f
            setTextColor(0xFF8A8A84.toInt())
            setPadding(px(8f), 0, 0, 0)
        }
        row.addView(wIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(times, LinearLayout.LayoutParams(px(30f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(hIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(unit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row)
        root.addView(TextView(ctx).apply {
            text = "宽 × 高，可分别输入；范围 30 – 2000 mm"
            textSize = 11f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(px(3f), px(6f), 0, 0)
        })

        var pickedBg: String? = pageBg
        if (projectBg != null) root.addView(makeBgRow(ctx, projectBg, pageBg) { pickedBg = it })

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scrollableDialogContent(ctx, root))
            .setPositiveButton("确定") { _, _ ->
                val w = (wIn.text.toString().toFloatOrNull() ?: initW).coerceIn(30f, 2000f)
                val h = (hIn.text.toString().toFloatOrNull() ?: initH).coerceIn(30f, 2000f)
                onDone(w, h, pickedBg)
            }
            .setNegativeButton("取消", null)
            .show()
    }

}

/** 展览墙体：以米为单位输入，无实际上限（0.1 – 100 m），可选预设。 */
fun showWall(
    ctx: Context, title: String, initWmm: Float, initHmm: Float,
    presets: List<Triple<String, Float, Float>> = emptyList(),
    projectBg: String? = null,
    pageBg: String? = null,
    onDone: (w: Float, h: Float, bg: String?) -> Unit
) {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Float) = (v * d).toInt()
    fun fmtM(mm: Float): String {
        val m = mm / 1000f
        return if (m == m.toInt().toFloat()) m.toInt().toString()
        else String.format("%.2f", m).trimEnd('0').trimEnd('.')
    }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20f), px(8f), px(20f), 0)
    }
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun input(v: Float) = EditText(ctx).apply {
        setText(fmtM(v))
        textSize = 15f
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine()
        setPadding(px(6f), px(11f), px(6f), px(11f))
        setTextColor(0xFF1A1A18.toInt())
        background = GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(0xFFFFFFFF.toInt())
            setStroke(px(1f), 0xFFDDDDD6.toInt())
        }
    }
    val wIn = input(initWmm)
    val hIn = input(initHmm)
    val times = TextView(ctx).apply {
        text = "×"; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(0xFF9A9A93.toInt())
    }
    val unit = TextView(ctx).apply {
        text = "m"; textSize = 12f
        setTextColor(0xFF8A8A84.toInt())
        setPadding(px(8f), 0, 0, 0)
    }
    row.addView(wIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(times, LinearLayout.LayoutParams(px(30f), ViewGroup.LayoutParams.WRAP_CONTENT))
    row.addView(hIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(unit, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(row)

    if (presets.isNotEmpty()) {
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10f), 0, 0)
        }
        presets.forEach { (name, w, h) ->
            presetRow.addView(TextView(ctx).apply {
                text = name
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(0xFF4A4A45.toInt())
                setPadding(px(12f), px(8f), px(12f), px(8f))
                background = GradientDrawable().apply {
                    cornerRadius = 8f * d
                    setStroke(px(1f), 0xFFDDDDD6.toInt())
                }
                setOnClickListener {
                    wIn.setText(fmtM(w)); hIn.setText(fmtM(h))
                    setTextColor(0xFFB5442A.toInt())
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = px(8f)
            })
        }
        root.addView(presetRow)
    }

    root.addView(TextView(ctx).apply {
        text = "宽 × 高，单位米（m），可分别输入；范围 0.1 – 100 m"
        textSize = 11f
        setTextColor(0xFFA8A8A0.toInt())
        setPadding(px(3f), px(8f), 0, 0)
    })

    var pickedBg: String? = pageBg
    if (projectBg != null) root.addView(makeBgRow(ctx, projectBg, pageBg) { pickedBg = it })

    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(scrollableDialogContent(ctx, root))
        .setPositiveButton("确定") { _, _ ->
            val w = ((wIn.text.toString().toFloatOrNull() ?: (initWmm / 1000f)) * 1000f)
                .coerceIn(100f, 100000f)
            val h = ((hIn.text.toString().toFloatOrNull() ?: (initHmm / 1000f)) * 1000f)
                .coerceIn(100f, 100000f)
            onDone(w, h, pickedBg)
        }
        .setNegativeButton("取消", null)
        .show()
}

/**
 * 「页面颜色」区：
 *  - 勾选「应用项目设置」= 跟随项目默认色（页面自身色为 null），色块置灰；
 *  - 取消勾选后可单独选色，色块可点开调色板。
 * 回调参数 null 表示跟随项目。
 */
private fun makeBgRow(
    ctx: Context, projectBg: String, pageBg: String?,
    onPick: (String?) -> Unit
): LinearLayout {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Float) = (v * d).toInt()

    var custom = pageBg            // 页面自选色（null = 尚未选过）
    var follow = pageBg == null

    fun chipDrawable(hex: String): GradientDrawable {
        val c = try {
            android.graphics.Color.parseColor(hex)
        } catch (e: Exception) {
            android.graphics.Color.WHITE
        }
        return GradientDrawable().apply {
            cornerRadius = 8f * d
            setColor(c)
            setStroke(px(1f), 0xFFDDDDD6.toInt())
        }
    }

    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // 勾选行
    val followRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(16f), 0, 0)
    }
    followRow.addView(TextView(ctx).apply {
        text = "应用项目设置"
        textSize = 13f
        setTextColor(0xFF1A1A18.toInt())
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val check = TextView(ctx).apply { textSize = 16f }
    followRow.addView(check)

    // 色块行
    val colorRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(12f), 0, 0)
    }
    colorRow.addView(TextView(ctx).apply {
        text = "页面颜色"
        textSize = 13f
        setTextColor(0xFF1A1A18.toInt())
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val chip = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(px(64f), px(34f))
    }
    colorRow.addView(chip)

    fun sync() {
        check.text = if (follow) "✓" else "　"
        check.setTextColor(if (follow) 0xFFB5442A.toInt() else 0xFFC0C0BA.toInt())
        val shown = if (follow) projectBg else (custom ?: projectBg)
        chip.background = chipDrawable(shown)
        chip.alpha = if (follow) 0.45f else 1f
        colorRow.alpha = if (follow) 0.55f else 1f
    }

    followRow.setOnClickListener {
        follow = !follow
        if (!follow && custom == null) custom = projectBg   // 取消勾选时以项目色为起点
        sync()
        onPick(if (follow) null else custom)
    }
    chip.setOnClickListener {
        if (follow) return@setOnClickListener
        ColorPickerDialog.show(ctx, "页面颜色", custom ?: projectBg) { c ->
            custom = c
            sync()
            onPick(c)
        }
    }

    box.addView(followRow)
    box.addView(colorRow)
    sync()
    return box
}

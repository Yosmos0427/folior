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

    fun show(ctx: Context, title: String, initW: Float, initH: Float, onDone: (Float, Float) -> Unit) {
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

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val w = (wIn.text.toString().toFloatOrNull() ?: initW).coerceIn(30f, 2000f)
                val h = (hIn.text.toString().toFloatOrNull() ?: initH).coerceIn(30f, 2000f)
                onDone(w, h)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}


/** 展览墙体：以米为单位输入，无实际上限（0.1 – 100 m），可选预设。 */
fun showWall(
    ctx: Context, title: String, initWmm: Float, initHmm: Float,
    presets: List<Triple<String, Float, Float>> = emptyList(),
    onDone: (Float, Float) -> Unit
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

    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(root)
        .setPositiveButton("确定") { _, _ ->
            val w = ((wIn.text.toString().toFloatOrNull() ?: (initWmm / 1000f)) * 1000f)
                .coerceIn(100f, 100000f)
            val h = ((hIn.text.toString().toFloatOrNull() ?: (initHmm / 1000f)) * 1000f)
                .coerceIn(100f, 100000f)
            onDone(w, h)
        }
        .setNegativeButton("取消", null)
        .show()
}

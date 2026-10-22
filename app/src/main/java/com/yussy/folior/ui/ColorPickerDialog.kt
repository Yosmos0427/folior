package com.yussy.folior.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 极简调色板：精选色板 + 自定义色值输入。 */
object ColorPickerDialog {

    val SWATCHES = arrayOf(
        "#FFFFFF", "#FAFAF7", "#F1EFE9", "#E8E4DC", "#D6D2C6", "#BDB8AA",
        "#8A8578", "#6B6B66", "#3A3A36", "#1A1A18", "#000000", "#2B2B33",
        "#B5442A", "#C96F4A", "#D9A566", "#E3C88F", "#A8B5A0", "#5F7A6A",
        "#3E5C76", "#6E8CA8", "#9DB4C9", "#C9D6DF", "#7A6A8A", "#A85C7A"
    )

    fun show(ctx: Context, title: String, current: String, onPick: (String) -> Unit) {
        val d = Dialog(ctx)
        d.setTitle(title)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }
        val grid = GridLayout(ctx).apply {
            columnCount = 6
            rowCount = 4
        }
        val cellSize = (52 * ctx.resources.displayMetrics.density).toInt()
        SWATCHES.forEach { hex ->
            val cell = TextView(ctx).apply {
                val c = try { Color.parseColor(hex) } catch (e: Exception) { Color.GRAY }
                setBackgroundColor(c)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSize; height = cellSize
                    val m = (6 * ctx.resources.displayMetrics.density).toInt()
                    setMargins(m, m, m, m)
                }
                if (hex.equals(current, true)) {
                    // 当前色标记
                    post {
                        this.foreground = android.graphics.drawable.GradientDrawable().apply {
                            setStroke(3, 0xFFB5442A.toInt())
                        }
                    }
                }
                setOnClickListener {
                    onPick(hex)
                    d.dismiss()
                }
            }
            grid.addView(cell)
        }
        root.addView(grid)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(ctx).apply {
            hint = "#RRGGBB"
            setText(current)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ok = TextView(ctx).apply {
            text = "确定"
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            setTextColor(0xFFB5442A.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener {
                val v = input.text.toString().trim()
                try { Color.parseColor(v); onPick(v); d.dismiss() } catch (e: Exception) {
                    Toast.makeText(ctx, "色值格式不正确", Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(input)
        row.addView(ok)
        root.addView(row)
        d.setContentView(root)
        d.show()
    }
}

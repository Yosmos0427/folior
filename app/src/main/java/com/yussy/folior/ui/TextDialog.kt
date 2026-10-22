package com.yussy.folior.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 文字编辑对话框：内容 + 字号 + 颜色 + 粗斜体 + 对齐。 */
object TextDialog {

    fun show(
        ctx: Context,
        initialText: String, initialFontScale: Float, initialColor: String,
        initialBold: Boolean, initialItalic: Boolean, initialAlign: Int,
        initialTextBg: String? = null,
        onDone: (text: String, fontScale: Float, color: String, bold: Boolean, italic: Boolean, align: Int, textBg: String?) -> Unit
    ) {
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(ctx).apply {
            setText(initialText)
            hint = "输入文字"
            minLines = 2
        }
        root.addView(input)

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun label(s: String) = TextView(ctx).apply { text = s; setPadding(0, 24, 12, 8) }
        row1.addView(label("字号"))
        val fontInput = EditText(ctx).apply {
            setText(((initialFontScale * 1000).toInt()).toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "千分页高"
        }
        row1.addView(fontInput)
        val colorBtn = TextView(ctx).apply {
            text = "颜色"
            setPadding(24, 24, 24, 8)
            setTextColor(0xFFB5442A.toInt())
            var picked = initialColor
            setOnClickListener {
                ColorPickerDialog.show(ctx, "文字颜色", picked) { c -> picked = c; tag = c }
            }
            tag = initialColor
        }
        row1.addView(colorBtn)
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var bold = initialBold; var italic = initialItalic; var align = initialAlign
        val boldBtn = toggleBtn(ctx, "粗", initialBold) { bold = it }
        val italicBtn = toggleBtn(ctx, "斜", initialItalic) { italic = it }
        val alignBtn = TextView(ctx).apply {
            text = "对齐: " + when (align) { 1 -> "中"; 2 -> "右"; else -> "左" }
            setPadding(24, 24, 24, 8)
            setTextColor(0xFF1A1A18.toInt())
            setOnClickListener {
                align = (align + 1) % 3
                text = "对齐: " + when (align) { 1 -> "中"; 2 -> "右"; else -> "左" }
            }
        }
        val bgBtn = TextView(ctx).apply {
            val names = mapOf<String?, String>(
                null to "背景: 无", "paper" to "背景: 纸张", "metal" to "背景: 金属",
                "wood" to "背景: 木板", "acrylic" to "背景: 亚克力")
            val order = listOf<String?>(null, "paper", "metal", "wood", "acrylic")
            var bg: String? = initialTextBg
            text = names[bg]
            setPadding(24, 24, 24, 8)
            setTextColor(if (bg != null) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
            setOnClickListener {
                bg = order[(order.indexOf(bg) + 1) % order.size]
                text = names[bg]
                setTextColor(if (bg != null) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
            }
            tag = bg
        }
        row2.addView(bgBtn)
        row2.addView(boldBtn); row2.addView(italicBtn); row2.addView(alignBtn)
        root.addView(row2)

        AlertDialog.Builder(ctx)
            .setTitle("文字")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val fs = (fontInput.text.toString().toFloatOrNull() ?: 40f) / 1000f
                onDone(input.text.toString(), fs.coerceIn(0.005f, 0.5f),
                    colorBtn.tag as? String ?: "#1A1A18", bold, italic, align,
                    bgBtn.tag as? String)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleBtn(ctx: Context, s: String, init: Boolean, on: (Boolean) -> Unit): TextView {
        var state = init
        return TextView(ctx).apply {
            text = s
            setPadding(24, 24, 24, 8)
            setTextColor(if (state) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
            setOnClickListener {
                state = !state
                setTextColor(if (state) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
                on(state)
            }
        }
    }
}

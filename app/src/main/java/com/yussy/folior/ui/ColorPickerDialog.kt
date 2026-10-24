package com.yussy.folior.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils

/**
 * 极简调色板（完整版）：11 个色系 × 明暗 6 档，横向滑动选择；
 * 色值输入使用 HSL（如「18 56% 44%」或 hsl(18,56%,44%)），仍兼容 #RRGGBB。
 */
object ColorPickerDialog {

    /** 色系按列排布：每列一个色系，上浅下深（横向滑动浏览）。 */
    private val FAMILIES = arrayOf(
        arrayOf("#FFFFFF", "#FAFAF7", "#F1EFE9", "#E8E4DC", "#D6D2C6", "#BDB8AA"),   // 暖白灰
        arrayOf("#A8A8A0", "#8A8A85", "#6B6B66", "#3A3A36", "#1A1A18", "#000000"),   // 灰墨
        arrayOf("#EFE3D5", "#D9C2A8", "#B99B78", "#94765A", "#6E563E", "#4A3828"),   // 棕
        arrayOf("#F2D7CD", "#E8B4A5", "#D97F66", "#C96F4A", "#B5442A", "#7A2A18"),   // 红
        arrayOf("#F7E3CE", "#EFC9A0", "#E3A76F", "#D9A566", "#B97F3E", "#8A5B2A"),   // 橙
        arrayOf("#F7EFD2", "#EFDCA8", "#E6C97F", "#E3C88F", "#C9A84A", "#8A7430"),   // 黄
        arrayOf("#E3EBDC", "#C4D4B8", "#A8B5A0", "#7F9978", "#5F7A6A", "#3D5245"),   // 绿
        arrayOf("#DCE9E7", "#B0CCC7", "#7FA8A2", "#55887F", "#3A6B62", "#24504A"),   // 青
        arrayOf("#DCE6EF", "#B4C9DC", "#9DB4C9", "#6E8CA8", "#3E5C76", "#263D52"),   // 蓝
        arrayOf("#E8E0EC", "#C9BAD4", "#A795BE", "#7A6A8A", "#5C4E6E", "#3E3450"),   // 紫
        arrayOf("#F2E0E4", "#E0BCC6", "#CE97A6", "#C17B90", "#A85C7A", "#7A3D55")    // 粉
    )

    /** 兼容旧引用：扁平化全部色值。 */
    val SWATCHES: Array<String> = FAMILIES.flatten().toTypedArray()

    /** 颜色 → HSL 文本（"18 56% 44%"）。 */
    fun toHslText(hex: String): String {
        val c = try { Color.parseColor(hex) } catch (e: Exception) { return hex }
        val hsl = FloatArray(3)
        ColorUtils.RGBToHSL(Color.red(c), Color.green(c), Color.blue(c), hsl)
        return "${hsl[0].toInt()} ${(hsl[1] * 100).toInt()}% ${(hsl[2] * 100).toInt()}%"
    }

    /** 解析 HSL 输入（"18 56% 44%" / "18,56,44" / "hsl(18,56%,44%)"），非法返回 null。 */
    fun parseHsl(v: String): Int? {
        val t = v.trim().lowercase().removePrefix("hsl").trim('(', ')', ' ')
        val parts = t.split(Regex("[,\\s]+")).map { it.trim().removeSuffix("%") }
        if (parts.size != 3) return null
        val h = parts[0].toFloatOrNull() ?: return null
        val s = (parts[1].toFloatOrNull() ?: return null) / 100f
        val l = (parts[2].toFloatOrNull() ?: return null) / 100f
        if (h !in 0f..360f || s !in 0f..1f || l !in 0f..1f) return null
        return ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
    }

    fun show(ctx: Context, title: String, current: String, onPick: (String) -> Unit) {
        val d = Dialog(ctx)
        d.setTitle(title)
        val dd = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dd).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }

        // ---- 色板：横向滑动，11 色系列 × 6 档行 ----
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val grid = GridLayout(ctx).apply {
            columnCount = 6          // 每列 6 档（上浅下深）
            rowCount = FAMILIES.size
        }
        val cellSize = (50 * dd).toInt()
        val cellMargin = (5 * dd).toInt()
        FAMILIES.forEach { family ->
            family.forEach { hex ->
                val cell = TextView(ctx).apply {
                    val c = try { Color.parseColor(hex) } catch (e: Exception) { Color.GRAY }
                    setBackgroundColor(c)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = cellSize; height = cellSize
                        setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                    }
                    if (hex.equals(current, true)) {
                        post {
                            foreground = android.graphics.drawable.GradientDrawable().apply {
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
        }
        scroll.addView(grid)
        root.addView(scroll)

        // ---- HSL 输入行 ----
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(ctx).apply {
            hint = "H S% L%（如 18 56% 44%）"
            setText(toHslText(current))
            textSize = 14f
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ok = TextView(ctx).apply {
            text = "确定"
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            setTextColor(0xFFB5442A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener {
                val v = input.text.toString().trim()
                val picked = when {
                    v.startsWith("#") -> try {
                        Color.parseColor(v); v
                    } catch (e: Exception) { null }
                    else -> parseHsl(v)?.let { c -> String.format("#%06X", c and 0xFFFFFF) }
                }
                if (picked != null) { onPick(picked); d.dismiss() }
                else Toast.makeText(ctx, "格式：H S% L%，如 18 56% 44%", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(input)
        row.addView(ok)
        root.addView(row)
        d.setContentView(scrollableDialogContent(ctx, root))
        d.show()
    }
}

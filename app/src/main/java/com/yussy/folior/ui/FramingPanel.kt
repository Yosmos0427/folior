package com.yussy.folior.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.yussy.folior.model.Item
import com.yussy.folior.model.parseColor

/**
 * 内联装裱面板：占据选择工具栏的位置（布局流内，完全不会遮挡工作区画布），
 * 所有调整即时生效并实时预览，无需"确定"按钮。
 *
 * 行1：✕ · 预设（无 / 经典 / 原木 / 悬浮）
 * 行2：衬材（无/纸/木/金/亚）+ 颜色
 * 行3：外延滑杆 + 比例（跟随/1:1/4:3/3:2/16:9）
 * 行4：边框（无/木/金/亚）+ 颜色 + 宽度滑杆
 */
class FramingPanel(context: Context) : LinearLayout(context) {

    var onClosed: (() -> Unit)? = null
    /** 每次装裱参数变化后回调（Activity 负责刷新画布与保存） */
    var onChanged: (() -> Unit)? = null

    private var item: Item? = null
    private var density = resources.displayMetrics.density

    fun bind(target: Item) {
        item = target
        rebuild()
    }

    fun boundItem(): Item? = item

    private fun px(v: Float) = (v * density).toInt()

    private fun rebuild() {
        removeAllViews()
        val it = item ?: return

        fun chip(text: String, selected: Boolean, onTap: () -> Unit): TextView =
            TextView(context).apply {
                this.text = text
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFFB5442A.toInt() else 0xFF4A4A45.toInt())
                setPadding(px(10f), px(6f), px(10f), px(6f))
                background = GradientDrawable().apply {
                    cornerRadius = px(8f).toFloat()
                    setColor(if (selected) 0xFFFBEFEB.toInt() else 0x00000000)
                    setStroke(px(1f), if (selected) 0xFFB5442A.toInt() else 0xFFDDDDD6.toInt())
                }
                setOnClickListener { onTap() }
            }

        fun row(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6f), px(2f), px(6f), px(2f))
        }

        fun addChip(row: LinearLayout, label: String, selected: Boolean, onTap: () -> Unit) {
            row.addView(chip(label, selected, onTap), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = px(4f)
            })
        }

        fun colorChip(label: String, cur: () -> String?, onPick: (String?) -> Unit): TextView {
            var picked = cur()
            return TextView(context).apply {
                textSize = 12f
                setPadding(px(10f), px(6f), px(10f), px(6f))
                fun refresh() {
                    text = if (picked == null) "$label·默认" else label
                    background = GradientDrawable().apply {
                        cornerRadius = px(8f).toFloat()
                        if (picked == null) setStroke(px(1f), 0xFFDDDDD6.toInt())
                        else setColor(parseColor(picked, Color.WHITE))
                    }
                }
                refresh()
                setOnClickListener {
                    ColorPickerDialog.show(context, label, picked ?: "#FFFFFF") { hex ->
                        picked = hex
                        refresh()
                        onPick(hex)
                        onChanged?.invoke()
                    }
                }
            }
        }

        fun slider(initPct: Int, maxPct: Int, onChangedNow: (Int) -> Unit): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val value = TextView(context).apply {
                    textSize = 11f
                    setTextColor(0xFF4A4A45.toInt())
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(px(40f), ViewGroup.LayoutParams.WRAP_CONTENT)
                    text = "$initPct%"
                }
                addView(SeekBar(context).apply {
                    max = maxPct
                    progress = initPct
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, prog: Int, fromUser: Boolean) {
                            value.text = "$prog%"
                            if (fromUser) { onChangedNow(prog); onChanged?.invoke() }
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) {}
                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    })
                })
                addView(value)
            }

        // ---- 行1：关闭 + 预设 ----
        val r1 = row()
        r1.addView(TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(0xFFB5442A.toInt())
            setPadding(px(14f), px(6f), px(10f), px(6f))
            setOnClickListener { onClosed?.invoke() }
        })
        val presets = listOf<Pair<String, () -> Unit>>(
            "无" to { it.matStyle = null; it.frameStyle = null },
            "经典" to { it.matStyle = "paper"; it.matPad = 0.12f; it.matRatio = 0f
                it.frameStyle = "wood"; it.frameColor = "#1A1A18"; it.frameW = 0.012f },
            "原木" to { it.matStyle = "wood"; it.matColor = null; it.matPad = 0.15f; it.matRatio = 0f
                it.frameStyle = "wood"; it.frameColor = "#8A5B3A"; it.frameW = 0.03f },
            "悬浮" to { it.matStyle = "acrylic"; it.matColor = null; it.matPad = 0.18f; it.matRatio = 0f
                it.frameStyle = null; it.frameColor = null }
        )
        presets.forEach { (name, action) ->
            addChip(r1, name, false) { action(); onChanged?.invoke(); rebuild() }
        }
        addView(r1)

        // ---- 行2：衬材 ----
        val r2 = row()
        r2.addView(TextView(context).apply {
            text = "衬材"; textSize = 11f
            setTextColor(0xFF8A8A84.toInt()); setPadding(0, 0, px(6f), 0)
        })
        listOf("无" to null, "纸" to "paper", "木" to "wood",
            "金" to "metal", "亚" to "acrylic").forEach { (n, v) ->
            addChip(r2, n, v == it.matStyle) { it.matStyle = v; onChanged?.invoke(); rebuild() }
        }
        r2.addView(colorChip("衬色", { it.matColor }) { hex -> it.matColor = hex },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(r2)

        // ---- 行3：外延 + 比例 ----
        val r3 = row()
        r3.addView(TextView(context).apply {
            text = "外延"; textSize = 11f
            setTextColor(0xFF8A8A84.toInt()); setPadding(0, 0, px(6f), 0)
        })
        r3.addView(slider((it.matPad * 100).toInt().coerceIn(0, 40), 40) { p ->
            it.matPad = p / 100f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = px(8f)
        })
        listOf("跟随" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:2" to 1.5f, "16:9" to 16f / 9f)
            .forEach { (n, v) ->
                addChip(r3, n, kotlin.math.abs(v - it.matRatio) < 0.005f) {
                    it.matRatio = v; onChanged?.invoke(); rebuild()
                }
            }
        addView(r3)

        // ---- 行4：边框 ----
        val r4 = row()
        r4.addView(TextView(context).apply {
            text = "边框"; textSize = 11f
            setTextColor(0xFF8A8A84.toInt()); setPadding(0, 0, px(6f), 0)
        })
        listOf("无" to null, "木" to "wood", "金" to "metal", "亚" to "acrylic").forEach { (n, v) ->
            addChip(r4, n, v == it.frameStyle) { it.frameStyle = v; onChanged?.invoke(); rebuild() }
        }
        r4.addView(colorChip("框色", { it.frameColor }) { hex -> it.frameColor = hex },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(4f) })
        r4.addView(TextView(context).apply {
            text = "宽"; textSize = 11f
            setTextColor(0xFF8A8A84.toInt()); setPadding(px(6f), 0, 0, 0)
        })
        r4.addView(slider((it.frameW * 100).toInt().coerceIn(1, 8), 8) { p ->
            it.frameW = p.coerceAtLeast(1) / 100f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(r4)
    }
}

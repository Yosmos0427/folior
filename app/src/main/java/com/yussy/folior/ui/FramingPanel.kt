package com.yussy.folior.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.yussy.folior.model.Item
import com.yussy.folior.model.parseColor

/**
 * 内联装裱面板：占据选择工具栏的位置（布局流内，不会遮挡工作区画布），
 * 采用与原弹窗一致的分区结构，即时生效、实时预览。
 *
 * 结构：
 *   ✕  快捷预设：无 / 经典 / 原木 / 悬浮
 *   ── 背景衬材 ──
 *   材质：无 / 纸张 / 木板 / 金属 / 亚克力
 *   颜色：[色块按钮]    外延：▬▬▬ 12%
 *   比例：跟随 / 1:1 / 4:3 / 3:2 / 16:9
 *   ── 边框 ──
 *   材质：无 / 木板 / 金属 / 亚克力
 *   颜色：[色块按钮]    宽度：▬▬▬ 2%
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
        orientation = VERTICAL
        val it = item ?: return
        // 内容放入内部 ScrollView：面板高度受限（横屏）时仍可滚动到达全部选项
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = px(14f)
            setPadding(pad, px(6f), pad, px(6f))
        }
        val scroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }
        scroll.addView(root)
        addView(scroll)

        fun sectionLabel(text: String) = TextView(context).apply {
            this.text = text
            textSize = 11.5f
            letterSpacing = 0.06f
            setTextColor(0xFF8A8A84.toInt())
            setPadding(px(2f), px(10f), 0, px(5f))
        }

        fun chip(text: String, selected: Boolean, onTap: () -> Unit) = TextView(context).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (selected) 0xFFB5442A.toInt() else 0xFF4A4A45.toInt())
            setPadding(px(13f), px(7f), px(13f), px(7f))
            background = GradientDrawable().apply {
                cornerRadius = px(8f).toFloat()
                setColor(if (selected) 0xFFFBEFEB.toInt() else 0x00000000)
                setStroke(px(1f), if (selected) 0xFFB5442A.toInt() else 0xFFDDDDD6.toInt())
            }
            setOnClickListener { onTap() }
        }

        /** 固定宽度行标签（右对齐灰字） */
        fun rowLabel(text: String) = TextView(context).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setTextColor(0xFF8A8A84.toInt())
            layoutParams = LinearLayout.LayoutParams(px(44f), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        fun row(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(3f), 0, px(3f))
        }

        fun addChip(row: LinearLayout, label: String, selected: Boolean, onTap: () -> Unit) {
            row.addView(chip(label, selected, onTap), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = px(6f)
            })
        }

        /** 醒目的颜色按钮：大色块 + 当前色值/默认说明，描边容器 */
        fun colorButton(title: String, cur: () -> String?, onPick: (String?) -> Unit): LinearLayout {
            var picked = cur()
            val swatch = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(px(30f), px(30f))
            }
            val text = TextView(context).apply {
                textSize = 13f
                setPadding(px(8f), 0, 0, 0)
            }
            fun refresh() {
                text.text = if (picked == null) "使用默认色" else picked
                text.setTextColor(if (picked == null) 0xFFA8A8A0.toInt() else 0xFF1A1A18.toInt())
                swatch.background = GradientDrawable().apply {
                    cornerRadius = px(6f).toFloat()
                    if (picked == null) {
                        setColor(0xFFFFFFFF.toInt())
                        setStroke(px(1.2f), 0xFFC9C9C2.toInt())
                    } else {
                        setColor(parseColor(picked, Color.WHITE))
                        setStroke(px(1f), 0x33000000)
                    }
                }
            }
            refresh()
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(8f), px(4f), px(12f), px(4f))
                background = GradientDrawable().apply {
                    cornerRadius = px(8f).toFloat()
                    setStroke(px(1f), 0xFFDDDDD6.toInt())
                }
                addView(swatch)
                addView(text)
                setOnClickListener {
                    ColorPickerDialog.show(context, title, picked ?: "#FFFFFF") { hex ->
                        picked = hex
                        refresh()
                        onPick(hex)
                        onChanged?.invoke()
                    }
                }
            }
        }

        /** 滑杆行：标签 + 滑杆 + 数值 */
        fun sliderRow(label: String, initPct: Int, maxPct: Int, onNow: (Int) -> Unit): LinearLayout {
            val r = row()
            r.addView(rowLabel(label))
            val value = TextView(context).apply {
                textSize = 12f
                setTextColor(0xFF1A1A18.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(px(46f), ViewGroup.LayoutParams.WRAP_CONTENT)
                text = "$initPct%"
            }
            r.addView(SeekBar(context).apply {
                max = maxPct
                progress = initPct
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, prog: Int, fromUser: Boolean) {
                        value.text = "$prog%"
                        if (fromUser) { onNow(prog); onChanged?.invoke() }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
            r.addView(value)
            return r
        }

        // ---- ✕ + 快捷预设 ----
        val presetRow = row()
        presetRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFFB5442A.toInt())
            setPadding(px(2f), px(6f), px(14f), px(6f))
            setOnClickListener { onClosed?.invoke() }
        })
        presetRow.addView(TextView(context).apply {
            text = "快捷预设"
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF8A8A84.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        listOf(
            "无" to { it.matStyle = null; it.frameStyle = null },
            "经典" to { it.matStyle = "paper"; it.matPad = 0.12f; it.matRatio = 0f
                it.frameStyle = "wood"; it.frameColor = "#1A1A18"; it.frameW = 0.012f },
            "原木" to { it.matStyle = "wood"; it.matColor = null; it.matPad = 0.15f; it.matRatio = 0f
                it.frameStyle = "wood"; it.frameColor = "#8A5B3A"; it.frameW = 0.03f },
            "悬浮" to { it.matStyle = "acrylic"; it.matColor = null; it.matPad = 0.18f; it.matRatio = 0f
                it.frameStyle = null; it.frameColor = null }
        ).forEach { (name, action) ->
            addChip(presetRow, name, false) { action(); onChanged?.invoke(); rebuild() }
        }
        root.addView(presetRow)

        // ---- 背景衬材 ----
        root.addView(sectionLabel("背景衬材"))
        val matRow = row()
        matRow.addView(rowLabel("材质"))
        listOf("无" to null, "纸张" to "paper", "木板" to "wood",
            "金属" to "metal", "亚克力" to "acrylic").forEach { (n, v) ->
            addChip(matRow, n, v == it.matStyle) { it.matStyle = v; onChanged?.invoke(); rebuild() }
        }
        root.addView(matRow)

        val matColorRow = row()
        matColorRow.addView(rowLabel("颜色"))
        matColorRow.addView(colorButton("衬材颜色", { it.matColor }) { hex -> it.matColor = hex })
        root.addView(matColorRow)
        root.addView(sliderRow("外延", (it.matPad * 100).toInt().coerceIn(0, 40), 40) { p ->
            it.matPad = p / 100f
        })

        val ratioRow = row()
        ratioRow.addView(rowLabel("比例"))
        listOf("跟随" to 0f, "1:1" to 1f, "4:3" to 4f / 3f,
            "3:2" to 1.5f, "16:9" to 16f / 9f).forEach { (n, v) ->
            addChip(ratioRow, n, kotlin.math.abs(v - it.matRatio) < 0.005f) {
                it.matRatio = v; onChanged?.invoke(); rebuild()
            }
        }
        root.addView(ratioRow)

        // ---- 边框 ----
        root.addView(sectionLabel("边框"))
        val frameRow = row()
        frameRow.addView(rowLabel("材质"))
        listOf("无" to null, "木板" to "wood", "金属" to "metal", "亚克力" to "acrylic")
            .forEach { (n, v) ->
                addChip(frameRow, n, v == it.frameStyle) {
                    it.frameStyle = v; onChanged?.invoke(); rebuild()
                }
            }
        root.addView(frameRow)

        val frameColorRow = row()
        frameColorRow.addView(rowLabel("颜色"))
        frameColorRow.addView(colorButton("边框颜色", { it.frameColor }) { hex -> it.frameColor = hex })
        root.addView(frameColorRow)
        root.addView(sliderRow("宽度", (it.frameW * 100).toInt().coerceIn(1, 8), 8) { p ->
            it.frameW = p.coerceAtLeast(1) / 100f
        })
    }
}

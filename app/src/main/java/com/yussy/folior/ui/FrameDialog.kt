package com.yussy.folior.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.yussy.folior.model.parseColor

/**
 * 统一装裱对话框（照片与文字共用）：
 * 快捷预设 + 背景衬材（材质/颜色/外延/比例）+ 边框（材质/颜色/宽度）。
 * 任何选择即时反映到界面（整体轻量重建）。
 */
object FrameDialog {

    class Conf(
        var matStyle: String? = null,   // paper / wood / metal / acrylic
        var matColor: String? = null,
        var matPad: Float = 0.08f,      // 衬材外延（内容 min(宽,高) 比例）
        var matRatio: Float = 0f,       // 衬材宽高比，0 = 跟随内容
        var frameStyle: String? = null, // wood / metal / acrylic
        var frameColor: String? = null,
        var frameW: Float = 0.02f       // 边框宽度（min(衬宽,衬高) 比例）
    ) {
        fun copyFrom(o: Conf) {
            matStyle = o.matStyle; matColor = o.matColor; matPad = o.matPad; matRatio = o.matRatio
            frameStyle = o.frameStyle; frameColor = o.frameColor; frameW = o.frameW
        }
    }

    fun show(ctx: Context, init: Conf, onApply: (Conf) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val c = Conf().apply { copyFrom(init) }
        var presetSel = -1

        val holder = FrameLayout(ctx)
        var content: (() -> View)? = null
        fun rebuild() {
            holder.removeAllViews()
            holder.addView(content!!.invoke())
        }

        fun buildContent(): View = ScrollView(ctx).apply {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20f), px(6f), px(20f), px(2f))
            }
            addView(root)

            fun label(text: String) = TextView(ctx).apply {
                this.text = text
                textSize = 11.5f
                letterSpacing = 0.06f
                setTextColor(0xFF8A8A84.toInt())
                setPadding(px(2f), px(12f), 0, px(6f))
            }

            fun chip(text: String, selected: Boolean, onTap: () -> Unit) = TextView(ctx).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFFB5442A.toInt() else 0xFF4A4A45.toInt())
                setPadding(px(12f), px(8f), px(12f), px(8f))
                background = GradientDrawable().apply {
                    cornerRadius = px(8f).toFloat()
                    setColor(if (selected) 0xFFFBEFEB.toInt() else 0x00000000)
                    setStroke(px(1f), if (selected) 0xFFB5442A.toInt() else 0xFFDDDDD6.toInt())
                }
                setOnClickListener { onTap() }
            }

            fun chipRow(options: List<Pair<String, String?>>, cur: String?, onSelect: (String?) -> Unit) =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    options.forEach { (name, value) ->
                        addView(chip(name, value == cur) { onSelect(value); presetSel = -1; rebuild() },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(6f) })
                    }
                }

            fun colorBtn(title: String, cur: () -> String?, onPick: (String?) -> Unit) = TextView(ctx).apply {
                var picked = cur()
                textSize = 13f
                setPadding(px(12f), px(8f), px(12f), px(8f))
                fun refresh() {
                    text = if (picked == null) "颜色·默认" else "颜色"
                    background = GradientDrawable().apply {
                        cornerRadius = px(8f).toFloat()
                        if (picked == null) setStroke(px(1f), 0xFFDDDDD6.toInt())
                        else setColor(parseColor(picked, Color.WHITE))
                    }
                }
                refresh()
                setOnClickListener {
                    ColorPickerDialog.show(ctx, title, picked ?: "#FFFFFF") { hex ->
                        picked = hex
                        refresh()
                        onPick(hex)
                    }
                }
            }

            fun sliderRow(initPct: Int, maxPct: Int, onChanged: (Int) -> Unit): LinearLayout =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val value = TextView(ctx).apply {
                        textSize = 12f
                        setTextColor(0xFF4A4A45.toInt())
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(px(52f), ViewGroup.LayoutParams.WRAP_CONTENT)
                        text = "$initPct%"
                    }
                    addView(SeekBar(ctx).apply {
                        max = maxPct
                        progress = initPct
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar: SeekBar?, prog: Int, fromUser: Boolean) {
                                value.text = "$prog%"
                                if (fromUser) onChanged(prog)
                            }
                            override fun onStartTrackingTouch(bar: SeekBar?) {}
                            override fun onStopTrackingTouch(bar: SeekBar?) {}
                        })
                    })
                    addView(value)
                }

            // ---- 快捷预设 ----
            root.addView(label("快捷预设"))
            val presets = listOf<Pair<String, () -> Unit>>(
                "无" to { c.matStyle = null; c.frameStyle = null },
                "经典" to { c.matStyle = "paper"; c.matPad = 0.12f; c.matRatio = 0f
                    c.frameStyle = "wood"; c.frameColor = "#1A1A18"; c.frameW = 0.012f },
                "原木" to { c.matStyle = "wood"; c.matColor = null; c.matPad = 0.15f; c.matRatio = 0f
                    c.frameStyle = "wood"; c.frameColor = "#8A5B3A"; c.frameW = 0.03f },
                "悬浮" to { c.matStyle = "acrylic"; c.matColor = null; c.matPad = 0.18f; c.matRatio = 0f
                    c.frameStyle = null; c.frameColor = null }
            )
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                presets.forEachIndexed { i, (name, action) ->
                    addView(chip(name, i == presetSel) { action(); presetSel = i; rebuild() },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(6f) })
                }
            })

            // ---- 背景衬材 ----
            root.addView(label("背景衬材"))
            root.addView(chipRow(listOf(
                "无" to null, "纸张" to "paper", "木板" to "wood",
                "金属" to "metal", "亚克力" to "acrylic"), c.matStyle) { c.matStyle = it })
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(colorBtn("衬材颜色", { c.matColor }) { c.matColor = it },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = px(10f)
                    })
                // 衬材比例
                listOf("跟随" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:2" to 1.5f, "16:9" to 16f / 9f)
                    .forEach { (n, v) ->
                        addView(chip(n, kotlin.math.abs(v - c.matRatio) < 0.005f) {
                            c.matRatio = v; presetSel = -1; rebuild()
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(5f) })
                    }
            })
            root.addView(label("衬材外延（内容占比）"))
            root.addView(sliderRow((c.matPad * 100).toInt().coerceIn(0, 40), 40) {
                c.matPad = it / 100f; presetSel = -1
            })

            // ---- 边框 ----
            root.addView(label("边框"))
            root.addView(chipRow(listOf(
                "无" to null, "木" to "wood", "金属" to "metal", "亚克力" to "acrylic"),
                c.frameStyle) { c.frameStyle = it })
            root.addView(colorBtn("边框颜色", { c.frameColor }) { c.frameColor = it })
            root.addView(label("边框宽度"))
            root.addView(sliderRow((c.frameW * 100).toInt().coerceIn(1, 8), 8) {
                c.frameW = it.coerceAtLeast(1) / 100f; presetSel = -1
            })
        }

        content = ::buildContent
        rebuild()
        AlertDialog.Builder(ctx)
            .setTitle("装裱")
            .setView(holder)
            .setPositiveButton("确定") { _, _ -> onApply(c) }
            .setNegativeButton("取消", null)
            .show()
    }
}

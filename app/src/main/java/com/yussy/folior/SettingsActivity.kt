package com.yussy.folior

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yussy.folior.store.Prefs
import com.yussy.folior.ui.ColorPickerDialog

/**
 * 全局设置（独立页面，非弹窗）：
 *   PDF 导出 —— 精度（轻量 / 标准 / 精细 / 自定义 dpi）与体积上限（默认无上限）。
 * 所有改动即时生效并保存，无"保存"按钮。
 */
class SettingsActivity : AppCompatActivity() {

    private var d = 1f
    private lateinit var dpiInput: EditText
    private lateinit var limitInput: EditText
    private lateinit var summary: TextView
    private lateinit var blankCheck: TextView

    /** 精度档 → 勾选标记 */
    private val dpiChecks = ArrayList<Pair<String, TextView>>()
    /** 体积行 → 勾选标记（key：MB，-1 = 自定义） */
    private val limitChecks = ArrayList<Pair<Int, TextView>>()

    private companion object {
        const val LIMIT_NONE = 0
        const val LIMIT_CUSTOM = -1
        val LIMIT_PRESETS = intArrayOf(20, 50, 100)

        const val ABOUT_P1 = "社交媒体习惯把照片切成一张张孤立的图片，但摄影师想说的话往往不在单独的一张图里，" +
                "它们的顺序、间隔与并置之间有许多被忽视的表达空间。我们鼓励一种更完整的摄影表达方式。"
        const val ABOUT_P2 = "「册页」为呈现方式而存在，快速便捷的编排工具与导出交付能够跟得上社交媒体的速度。"
        const val ABOUT_P3 = "我们提供一个把照片编辑成册的工作台，照片排序、页面设计、文字介绍、装裱设计、手绘标注，" +
                "都可以在这里一次性完成。你可以将完成的电子册页导出分享，或输出一本 PDF 电子册传播到社交平台上。"
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun dp(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAFAF7.toInt())
        }

        // ---- 顶栏 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(6f))
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 24f
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(6f), dp(4f), dp(14f), dp(4f))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "全局设置"
            textSize = 17f
            setTextColor(0xFF1A1A18.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(top)

        // ---- 内容 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(32f))
        }

        content.addView(sectionLabel("PDF 导出"))
        content.addView(hint("导出 PDF 的画质与文件大小，两项设置相互独立。"))

        content.addView(subLabel("精度"))
        content.addView(hint("72 dpi 适合屏幕与邮件分享；192 dpi 适合高质量屏幕查看与普通打印；300 dpi 为印刷级。"))

        content.addView(dpiRow(Prefs.DPI_LIGHT.toString(), "轻量", "72 dpi · 体积最小，屏幕阅读与快速分享"))
        content.addView(dpiRow(Prefs.DPI_STANDARD.toString(), "标准", "192 dpi · 清晰度与体积平衡（推荐）"))
        content.addView(dpiRow(Prefs.DPI_FINE.toString(), "精细", "300 dpi · 印刷级画质"))
        content.addView(dpiCustomBlock())

        content.addView(subLabel("体积上限"))
        content.addView(hint("默认不限制。设置上限后，导出完成后若超出，会对 PDF 内的图片重新压缩（不会重新渲染页面）。压缩到最低画质仍超出时，仍会输出文件并提示实际大小。"))

        content.addView(limitRow(LIMIT_NONE, "无上限", "完整保留所选精度的画质"))
        LIMIT_PRESETS.forEach { mb ->
            content.addView(limitRow(mb, "$mb MB", if (mb == 20) "适合邮件附件" else "适合在线传输"))
        }
        content.addView(limitCustomBlock())

        summary = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B6B66.toInt())
            setPadding(dp(2f), dp(22f), dp(2f), 0)
        }
        content.addView(summary)

        // ---- 预览图导出 ----
        content.addView(sectionLabel("预览图导出"))
        content.addView(hint("「菜单 → 导出项目预览」生成一张包含全部页面的图片，用于整体浏览或一次分享整册结构。摄影书按偶数列排列，封面之前留一格间隔，便于成对查看翻开时的左右页。"))
        content.addView(previewBgRow())
        content.addView(previewKeepBlankRow())

        // ---- 关于 ----
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply { topMargin = dp(26f) }
            setBackgroundColor(0xFFE6E6DF.toInt())
        })
        content.addView(sectionLabel("关于"))

        val aboutHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), 0)
        }
        aboutHead.addView(TextView(this).apply {
            text = "册页"
            textSize = 16f
            setTextColor(0xFF1A1A18.toInt())
        })
        aboutHead.addView(TextView(this).apply {
            text = "Folior"
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(dp(8f), dp(2f), 0, 0)
        })
        aboutHead.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        aboutHead.addView(TextView(this).apply {
            text = "v${versionName()}"
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
        })
        content.addView(aboutHead)

        content.addView(TextView(this).apply {
            text = "$ABOUT_P1\n\n$ABOUT_P2\n\n$ABOUT_P3"
            textSize = 12f
            setTextColor(0xFF6B6B66.toInt())
            setLineSpacing(dp(5f).toFloat(), 1.45f)
            setPadding(dp(2f), dp(10f), dp(2f), 0)
        })

        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // ---------------- 视图构件 ----------------

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF8A8A84.toInt())
        setPadding(dp(2f), dp(22f), 0, dp(6f))
    }

    /** 大栏内的子标题 */
    private fun subLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(0xFF6B6B66.toInt())
        setPadding(dp(2f), dp(16f), 0, dp(2f))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(0xFFA8A8A0.toInt())
        setLineSpacing(0f, 1.35f)
        setPadding(dp(2f), 0, dp(2f), dp(8f))
    }

    /** 预览图背景色：行 + 右侧色块 */
    private fun previewBgRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(13f), dp(2f), dp(13f))
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = "背景色"
            textSize = 15f
            setTextColor(0xFF1A1A18.toInt())
        })
        col.addView(TextView(this).apply {
            text = "预览图的底色，默认白色"
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(0, dp(3f), 0, 0)
        })
        row.addView(col)

        val chip = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64f), dp(34f))
            background = bgChipDrawable(Prefs.previewBg(this@SettingsActivity))
        }
        row.addView(chip)
        row.setOnClickListener { pickPreviewBg(chip) }
        return row
    }

    private fun bgChipDrawable(color: Int) = GradientDrawable().apply {
        cornerRadius = 8f * d
        setColor(color)
        setStroke(dp(1f), 0xFFDDDDD6.toInt())
    }

    private fun pickPreviewBg(chip: TextView) {
        val hex = String.format("#%06X", 0xFFFFFF and Prefs.previewBg(this))
        ColorPickerDialog.show(this, "预览图背景色", hex) { c ->
            val color = try {
                android.graphics.Color.parseColor(c)
            } catch (e: Exception) {
                return@show
            }
            Prefs.setPreviewBg(this, color)
            chip.background = bgChipDrawable(color)
        }
    }

    /** 保留空白页：行 + 右侧开/关标记，点击切换 */
    private fun previewKeepBlankRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(13f), dp(2f), dp(13f))
            isClickable = true
            setOnClickListener {
                Prefs.setPreviewKeepBlank(this@SettingsActivity, !Prefs.previewKeepBlank(this@SettingsActivity))
                refreshSelectionOnly()
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = "保留空白页"
            textSize = 15f
            setTextColor(0xFF1A1A18.toInt())
        })
        col.addView(TextView(this).apply {
            text = "未放置任何内容的页面也出现在预览图中"
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(0, dp(3f), 0, 0)
        })
        row.addView(col)

        blankCheck = TextView(this).apply {
            textSize = 16f
            setPadding(dp(12f), 0, dp(4f), 0)
        }
        row.addView(blankCheck)
        return row
    }

    /** 通用选项行：标题 + 说明 + 右侧 ✓ */
    private fun optionRow(title: String, desc: String, onTap: () -> Unit): Triple<LinearLayout, TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(13f), dp(2f), dp(13f))
            isClickable = true
            setOnClickListener { onTap() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF1A1A18.toInt())
        }
        col.addView(t)
        val descView = TextView(this).apply {
            text = desc
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(0, dp(3f), 0, 0)
        }
        col.addView(descView)
        row.addView(col)
        val check = TextView(this).apply {
            text = "✓"
            textSize = 16f
            setTextColor(0xFFB5442A.toInt())
            visibility = View.INVISIBLE
            setPadding(dp(12f), 0, dp(4f), 0)
        }
        row.addView(check)
        return Triple(row, t, check)
    }

    private fun dpiRow(mode: String, title: String, desc: String): LinearLayout {
        val (row, titleView, check) = optionRow(title, desc) {
            Prefs.setPdfDpiMode(this, mode)
            refresh()
        }
        titleView.setTextColor(0xFF1A1A18.toInt())
        dpiChecks.add(mode to check)
        return row
    }

    private fun limitRow(mb: Int, title: String, desc: String): LinearLayout {
        val (row, _, check) = optionRow(title, desc) {
            Prefs.setPdfLimitMb(this, mb)
            refresh()
        }
        limitChecks.add(mb to check)
        return row
    }

    /** 自定义精度：标题行（可选中）+ dpi 输入 */
    private fun dpiCustomBlock(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (head, _, check) = optionRow("自定义", "自设渲染精度（72 – 600 dpi）") {
            Prefs.setPdfDpiMode(this, Prefs.MODE_CUSTOM)
            refresh()
        }
        dpiChecks.add(Prefs.MODE_CUSTOM to check)
        box.addView(head)
        box.addView(numberInput(Prefs.pdfCustomDpi(this), "dpi", { v ->
            Prefs.setPdfCustomDpi(this, v)
            refresh()
        }, { dpiInput = it }, {
            if (Prefs.pdfDpiMode(this) != Prefs.MODE_CUSTOM) {
                Prefs.setPdfDpiMode(this, Prefs.MODE_CUSTOM)
                refreshSelectionOnly()
            }
        }))
        return box
    }

    /** 自定义上限：标题行（可选中）+ MB 输入 */
    private fun limitCustomBlock(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (head, _, check) = optionRow("自定义上限", "自设目标体积（1 – 2000 MB）") {
            val cur = Prefs.pdfLimitMb(this)
            Prefs.setPdfLimitMb(this, if (cur > 0 && cur !in LIMIT_PRESETS) cur else 50)
            refresh()
        }
        limitChecks.add(LIMIT_CUSTOM to check)
        box.addView(head)
        box.addView(numberInput(
            Prefs.pdfLimitMb(this).takeIf { it > 0 } ?: 50, "MB", { v ->
                Prefs.setPdfLimitMb(this, v)
                refresh()
            }, { limitInput = it }, {
            val cur = Prefs.pdfLimitMb(this)
            if (cur <= 0 || cur in LIMIT_PRESETS) Prefs.setPdfLimitMb(this, 50)
            refreshSelectionOnly()
        }))
        return box
    }

    /** 数字输入行：输入 + 单位 */
    private fun numberInput(
        init: Int, unit: String, onCommit: (Int) -> Unit, onCreate: (EditText) -> Unit,
        onEngage: () -> Unit = {}
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), dp(12f))
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            textSize = 14f
            gravity = Gravity.CENTER
            setText(init.toString())
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1f), 0xFFDDDDD6.toInt())
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onEngage()
                else {
                    val v = text.toString().trim().toIntOrNull()
                    if (v != null) onCommit(v) else setText(init.toString())
                }
            }
        }
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = unit
            textSize = 12.5f
            setTextColor(0xFF6B6B66.toInt())
            setPadding(dp(10f), 0, dp(4f), 0)
        })
        onCreate(et)
        return row
    }

    // ---------------- 状态刷新 ----------------

    /** 只更新选中标记与摘要（输入框获得焦点时用，避免打断输入） */
    private fun refreshSelectionOnly() {
        val dpiMode = Prefs.pdfDpiMode(this)
        dpiChecks.forEach { (m, check) ->
            check.visibility = if (m == dpiMode) View.VISIBLE else View.INVISIBLE
        }
        dpiInput.alpha = if (dpiMode == Prefs.MODE_CUSTOM) 1f else 0.6f

        val limitMb = Prefs.pdfLimitMb(this)
        limitChecks.forEach { (key, check) ->
            val on = when (key) {
                LIMIT_NONE -> limitMb <= 0
                LIMIT_CUSTOM -> limitMb > 0 && limitMb !in LIMIT_PRESETS
                else -> limitMb == key
            }
            check.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        limitInput.alpha = if (limitMb > 0 && limitMb !in LIMIT_PRESETS) 1f else 0.6f
        summary.text = "当前：${Prefs.pdfDpi(this)} dpi · ${Prefs.pdfLimitLabel(this)}"
        updateBlankCheck()
    }

    /** 保留空白页的开/关标记 */
    private fun updateBlankCheck() {
        val on = Prefs.previewKeepBlank(this)
        blankCheck.text = if (on) "✓" else "–"
        blankCheck.setTextColor(if (on) 0xFFB5442A.toInt() else 0xFFC0C0BA.toInt())
    }

    private fun refresh() {
        val dpiMode = Prefs.pdfDpiMode(this)
        dpiChecks.forEach { (m, check) ->
            check.visibility = if (m == dpiMode) View.VISIBLE else View.INVISIBLE
        }
        dpiInput.alpha = if (dpiMode == Prefs.MODE_CUSTOM) 1f else 0.6f

        val limitMb = Prefs.pdfLimitMb(this)
        limitChecks.forEach { (key, check) ->
            val on = when (key) {
                LIMIT_NONE -> limitMb <= 0
                LIMIT_CUSTOM -> limitMb > 0 && limitMb !in LIMIT_PRESETS
                else -> limitMb == key
            }
            check.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        val customOn = limitMb > 0 && limitMb !in LIMIT_PRESETS
        limitInput.alpha = if (customOn) 1f else 0.6f
        if (customOn && !limitInput.hasFocus()) limitInput.setText(limitMb.toString())

        summary.text = "当前：${Prefs.pdfDpi(this)} dpi · ${Prefs.pdfLimitLabel(this)}"
        updateBlankCheck()
    }
}

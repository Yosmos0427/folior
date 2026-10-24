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

/**
 * 全局设置（独立页面，非弹窗）：
 *   PDF 导出 —— 大小档位（小型 / 标准 / 完整 / 自定义）与自定义上限。
 * 所有改动即时生效并保存，无"保存"按钮。
 */
class SettingsActivity : AppCompatActivity() {

    private var d = 1f
    private lateinit var customInput: EditText
    private lateinit var customTitle: TextView

    /** 每个档位行（含自定义行）→ 行内勾选标记，用于刷新选中态 */
    private val rows = ArrayList<Pair<String, TextView>>()

    private fun dp(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        buildUi()
        refreshSelection()
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

        // ---- 内容（可滚动）----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(28f))
        }
        content.addView(sectionLabel("PDF 导出"))
        content.addView(hint("导出时按所选档位控制画质与文件大小；超过上限会自动降低精度重新导出。"))

        content.addView(optionRow(Prefs.PDF_SMALL, "小型", "约 150 dpi · 上限 20 MB"))
        content.addView(optionRow(Prefs.PDF_STANDARD, "标准", "约 225 dpi · 上限 100 MB"))
        content.addView(optionRow(Prefs.PDF_FULL, "完整", "300 dpi 全画质 · 不限大小"))
        content.addView(customBlock())

        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF8A8A84.toInt())
        setPadding(dp(2f), dp(18f), 0, dp(6f))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(0xFFA8A8A0.toInt())
        setLineSpacing(0f, 1.3f)
        setPadding(dp(2f), 0, dp(2f), dp(10f))
    }

    /** 档位行：标题 + 说明 + 右侧 ✓ */
    private fun optionRow(mode: String, title: String, desc: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(14f), dp(2f), dp(14f))
            isClickable = true
            setOnClickListener {
                Prefs.setPdfSizeMode(this@SettingsActivity, mode)
                refreshSelection()
            }
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
        col.addView(TextView(this).apply {
            text = desc
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(0, dp(3f), 0, 0)
        })
        row.addView(col)
        val check = TextView(this).apply {
            text = "✓"
            textSize = 16f
            setTextColor(0xFFB5442A.toInt())
            visibility = View.INVISIBLE
            setPadding(dp(12f), 0, dp(4f), 0)
        }
        row.addView(check)
        rows.add(mode to check)
        return row
    }

    /** 自定义档：标题行 + 上限输入 */
    private fun customBlock(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(14f), dp(2f), dp(4f))
            isClickable = true
            setOnClickListener {
                Prefs.setPdfSizeMode(this@SettingsActivity, Prefs.PDF_CUSTOM)
                refreshSelection()
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        customTitle = TextView(this).apply {
            text = "自定义"
            textSize = 15f
            setTextColor(0xFF1A1A18.toInt())
        }
        col.addView(customTitle)
        col.addView(TextView(this).apply {
            text = "自设文件大小上限"
            textSize = 11.5f
            setTextColor(0xFFA8A8A0.toInt())
            setPadding(0, dp(3f), 0, 0)
        })
        head.addView(col)
        val check = TextView(this).apply {
            text = "✓"
            textSize = 16f
            setTextColor(0xFFB5442A.toInt())
            visibility = View.INVISIBLE
            setPadding(dp(12f), 0, dp(4f), 0)
        }
        head.addView(check)
        rows.add(Prefs.PDF_CUSTOM to check)
        box.addView(head)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(2f), dp(2f), dp(14f))
        }
        inputRow.addView(TextView(this).apply {
            text = "上限"
            textSize = 12.5f
            setTextColor(0xFF6B6B66.toInt())
            setPadding(0, 0, dp(10f), 0)
        })
        customInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            textSize = 14f
            gravity = Gravity.CENTER
            setText(Prefs.pdfCustomMb(this@SettingsActivity).toString())
            setTextColor(0xFF1A1A18.toInt())
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1f), 0xFFDDDDD6.toInt())
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitCustomMb() }
        }
        inputRow.addView(customInput, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(TextView(this).apply {
            text = "MB"
            textSize = 12.5f
            setTextColor(0xFF6B6B66.toInt())
            setPadding(dp(10f), 0, dp(4f), 0)
        })
        box.addView(inputRow)
        return box
    }

    private fun commitCustomMb() {
        val v = customInput.text.toString().trim().toIntOrNull() ?: return
        Prefs.setPdfCustomMb(this, v)
        customInput.setText(Prefs.pdfCustomMb(this).toString())
    }

    /** 刷新选中标志与自定义输入可用状态 */
    private fun refreshSelection() {
        val mode = Prefs.pdfSizeMode(this)
        rows.forEach { (m, check) ->
            check.visibility = if (m == mode) View.VISIBLE else View.INVISIBLE
        }
        customTitle.setTextColor(
            if (mode == Prefs.PDF_CUSTOM) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
        val enabled = mode == Prefs.PDF_CUSTOM
        customInput.isEnabled = enabled
        customInput.alpha = if (enabled) 1f else 0.45f
    }
}

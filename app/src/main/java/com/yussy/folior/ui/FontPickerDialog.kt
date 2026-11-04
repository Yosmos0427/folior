package com.yussy.folior.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** 字体选择：每行用字体本身渲染名称与示例句，右侧标注所属分类。 */
object FontPickerDialog {

    fun show(ctx: Context, current: String, onPick: (String) -> Unit) {
        val rows = Fonts.ALL
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(pos: Int) = rows[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val d = ctx.resources.displayMetrics.density
                fun px(v: Float) = (v * d).toInt()
                val def = rows[pos]
                val tf: Typeface? = Fonts.typeface(ctx, def.id)
                val picked = def.id == current

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(20f), px(11f), px(20f), px(11f))
                }

                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(ctx).apply {
                    text = def.name
                    textSize = 16f
                    setTextColor(if (picked) 0xFFB5442A.toInt() else 0xFF1A1A18.toInt())
                    if (tf != null) typeface = tf
                })
                col.addView(TextView(ctx).apply {
                    text = def.sample
                    textSize = 13f
                    setTextColor(0xFF8A8A82.toInt())
                    if (tf != null) typeface = tf
                })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                row.addView(TextView(ctx).apply {
                    text = def.group
                    textSize = 11f
                    setTextColor(0xFF8A8A82.toInt())
                })
                return row
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("字体")
            .setAdapter(adapter) { _, which -> onPick(rows[which].id) }
            .setNegativeButton("取消", null)
            .show()
    }
}

package com.yussy.folior.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * 把对话框内容包进可滚动的 ScrollView。
 *
 * 横屏（或小屏 + 大字号）时系统对话框高度有限，内容较多的对话框
 * （页面设置含尺寸与底色、文字样式、项目设置、调色板）会被直接截断。
 * 包一层 ScrollView 后，超出部分可以上下滑动查看。
 */
fun scrollableDialogContent(ctx: Context, content: View): ScrollView =
    ScrollView(ctx).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

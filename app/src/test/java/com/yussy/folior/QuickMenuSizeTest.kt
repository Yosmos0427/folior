package com.yussy.folior

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.store.Store
import com.yussy.folior.ui.PageCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 工作区浮动菜单的按钮尺寸：固定为四个汉字的宽度，文字不许被裁。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickMenuSizeTest {

    private lateinit var act: EditorActivity

    private fun launch(): EditorActivity {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = Project(title = "t", pages = mutableListOf(Page(), Page()))
        Store.save(ctx, p)
        act = Robolectric.buildActivity(
            EditorActivity::class.java,
            android.content.Intent(ctx, EditorActivity::class.java).putExtra("id", p.id)
        ).setup().get()
        val decor = act.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1080, 2400)
        return act
    }

    private fun field(name: String): Any {
        val f = EditorActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(act)!!
    }

    private fun canvas(): PageCanvas = field("canvas") as PageCanvas

    /** 让四个按钮都显形：指示线模式用的是「完成/添加/镜像/删除」 */
    private fun showAllButtons() {
        canvas().editGuides(true)
        EditorActivity::class.java.getDeclaredMethod("updateQuickMenu")
            .apply { isAccessible = true }.invoke(act)
        val menu = field("quickActions") as LinearLayout
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
        )
        menu.layout(0, 0, menu.measuredWidth, menu.measuredHeight)
    }

    private fun buttons(): List<TextView> =
        listOf("quickA", "quickB", "quickC", "quickD")
            .map { field(it) as TextView }
            .filter { it.visibility == View.VISIBLE }

    @Test
    fun buttonsFitFourChars() {
        launch()
        showAllButtons()
        val bs = buttons()
        assertTrue("按钮数量异常: ${bs.size}", bs.size >= 3)

        val widths = bs.map { it.width }
        val textRoom = bs.map { it.width - it.paddingLeft - it.paddingRight }
        val textW = bs.map { it.paint.measureText(it.text.toString()) }
        bs.forEachIndexed { i, b ->
            println("BTN ${b.text} width=${widths[i]} room=${textRoom[i]} text=${textW[i]} pxPerChar=${b.paint.measureText("测")}")
        }

        // 内容区至少能放下当前文字（不裁）
        bs.indices.forEach { i ->
            assertTrue(
                "「${bs[i].text}」被裁：可用 ${textRoom[i]} < 文字 ${textW[i]}",
                textRoom[i] >= textW[i]
            )
        }
        // 所有按钮等宽
        assertEquals(1, widths.toSet().size)

        // 宽度确实按四个汉字定：能放下四字，且不会大到夸张
        val four = bs[0].paint.measureText("测测测测")
        val room = textRoom[0].toFloat()
        assertTrue("宽度不足四字：$room < $four", room >= four * 0.98f)
        assertTrue("宽度超出四字过多：$room vs $four", room < four * 1.35f)
    }

    @Test
    fun toggleKeepsStyle() {
        launch()
        val t = field("quickToggle") as TextView
        val lp = t.layoutParams as LinearLayout.LayoutParams
        val dp40 = (40 * act.resources.displayMetrics.density).toInt()
        println("TOGGLE w=${lp.width} h=${lp.height} text=${t.text}")
        assertEquals("菜单键尺寸应保持 40dp", dp40, lp.width)
        assertEquals(dp40, lp.height)
    }

    @Test
    fun drawWithoutClip() {
        launch()
        showAllButtons()
        (field("quickToggle") as TextView).performClick()   // 展开菜单
        val decor = act.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1080, 2400)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        java.io.FileOutputStream("/tmp/quickmenu.png").use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // 容器与按钮都不许出屏：右边缘必须落在屏幕内
        val menu = field("quickActions") as LinearLayout
        println("BOX w=${menu.width} h=${menu.height} x=${menu.x} right=${menu.x + menu.width}")
        assertTrue("菜单超出屏幕右边缘", menu.x + menu.width <= 1080)
    }
}

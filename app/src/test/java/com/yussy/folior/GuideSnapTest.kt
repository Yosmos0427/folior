package com.yussy.folior

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.yussy.folior.model.Item
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.model.heightMm
import com.yussy.folior.model.widthMm
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 指示线自身的吸附：平移吸到页面边/中线与元素的边/中心，旋转吸到元素的边方向。
 * 全部走真实触摸事件，不直接调私有方法，验证的是用户实际能摸到的路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideSnapTest {

    private lateinit var act: EditorActivity

    private fun launch(p: Project) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.save(ctx, p)
        act = Robolectric.buildActivity(
            EditorActivity::class.java, Intent(ctx, EditorActivity::class.java).putExtra("id", p.id)
        ).setup().get()
        frame()
    }

    private fun frame() {
        val decor = act.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        decor.draw(Canvas(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)))
    }

    private fun canvas(): PageCanvas =
        EditorActivity::class.java.getDeclaredField("canvas").apply { isAccessible = true }
            .get(act) as PageCanvas

    private fun project(): Project =
        EditorActivity::class.java.getDeclaredField("project").apply { isAccessible = true }
            .get(act) as Project

    /** 内容中心（画布坐标） */
    private fun centerPx(): PointF {
        val m = PageCanvas::class.java.getDeclaredMethod("contentCenterPx")
            .apply { isAccessible = true }
        return m.invoke(canvas()) as PointF
    }

    /** 1mm 对应多少像素（与画布内部一致：取首页页宽的换算比例） */
    private fun pxPerMm(): Float {
        val f = PageCanvas::class.java.getDeclaredField("slots").apply { isAccessible = true }
        val slot = (f.get(canvas()) as List<*>)[0]!!
        val rf = slot.javaClass.getDeclaredField("rect").apply { isAccessible = true }
        val rect = rf.get(slot) as RectF
        val p = project()
        return rect.width() / p.pages[0].widthMm(p).coerceAtLeast(1f)
    }

    private fun ev(action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        })
        return MotionEvent.obtain(0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun down(x: Float, y: Float) {
        canvas().dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y))
    }

    private fun move(x: Float, y: Float) {
        canvas().dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y))
    }

    private fun up(x: Float, y: Float) {
        canvas().dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x, y))
    }

    // ---------------- 1. 平移吸到页面水平中线 ----------------

    @Test
    fun snapsToPageCenterLine() {
        launch(Project(title = "t", pages = mutableListOf(Page())))
        val c = canvas()
        c.editGuides(true)
        val g = project().guides[0]
        frame()
        val ppm = pxPerMm()
        val ctr = centerPx()
        // 水平线正好压在页面水平中线上（单页时中心偏移为 0）
        assertEquals(0f, g.ayMm, 0.5f)

        down(ctr.x + 200f, ctr.y)
        move(ctr.x + 200f, ctr.y + 300f)
        val away = g.ayMm
        println("APART ay=${away}mm  (期望约 ${300f / ppm})")

        // 拖回到中线附近 20px（容差 24px）
        move(ctr.x + 200f, ctr.y + 20f)
        println("NEAR ay=${g.ayMm}mm")
        assertTrue("应吸回页面水平中线: ay=${g.ayMm}", abs(g.ayMm) < 1f)

        // 继续拖到 60px 外：吸附必须放开，不能粘住
        move(ctr.x + 200f, ctr.y + 60f)
        println("RELEASE ay=${g.ayMm}mm")
        assertTrue("离得够远应脱离吸附: ay=${g.ayMm}", abs(g.ayMm) > 5f)
        up(ctr.x + 200f, ctr.y + 60f)
        frame()
    }

    // ---------------- 2. 平移吸到元素的左边 ----------------

    @Test
    fun snapsToElementEdge() {
        val item = Item(type = Item.TYPE_PHOTO).apply {
            x = 0.1f; y = 0.2f; w = 0.3f; h = 0.2f; imgId = "nope"
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)))))
        val c = canvas()
        c.editGuides(true)
        val g = project().guides[0]
        val p = project()
        val wmm = p.pages[0].widthMm(p)
        val target = (item.x - 0.5f) * wmm          // 元素左边相对内容中心的 mm
        g.angle = 90f
        g.axMm = target + 14f                        // 先停在左边右侧 14mm
        g.ayMm = 0f
        frame()
        val ppm = pxPerMm()
        val ctr = centerPx()
        val lineX = ctr.x + g.axMm * ppm

        down(lineX, ctr.y)
        move(ctr.x + (target + 1f) * ppm, ctr.y)     // 拖到距左边 1mm（约 7px，在容差内）
        println("EDGE ax=${g.axMm}mm  target=${target}mm")
        assertEquals("应吸到元素左边", target, g.axMm, 1f)
        up(ctr.x + (target + 1f) * ppm, ctr.y)
        frame()
    }

    // ---------------- 3. 平移吸到元素的中心（斜线穿心） ----------------

    @Test
    fun snapsToElementCenter() {
        // 元素中心放在页面右侧偏中：它到 45° 法线的投影远离页面九宫格的投影值
        // （页面九宫格投影只有 0 / ±30.7 / ±74.2 / ±105 / ±179.2），避免两个目标打擂台
        val item = Item(type = Item.TYPE_PHOTO).apply {
            x = 0.686f; y = 0.35f; w = 0.2f; h = 0.3f; imgId = "nope"
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)))))
        val c = canvas()
        c.editGuides(true)
        val g = project().guides[0]
        val p = project()
        val page = p.pages[0]
        val wmm = page.widthMm(p)
        val hmm = page.heightMm(p)
        g.angle = 45f
        val rad = Math.toRadians(45.0)
        val nx = -sin(rad).toFloat(); val ny = cos(rad).toFloat()
        val ecx = (item.x + item.w / 2f - 0.5f) * wmm
        val ecy = (item.y + item.h / 2f - 0.5f) * hmm
        val expectD = ecx * nx + ecy * ny          // 线正好穿过元素中心时的法向位置
        // 先停在中心沿法线 2mm 外
        g.axMm = ecx - nx * 2f
        g.ayMm = ecy - ny * 2f
        frame()
        val ppm = pxPerMm()
        val ctr = centerPx()
        val lineX = ctr.x + g.axMm * ppm
        val lineY = ctr.y + g.ayMm * ppm

        down(lineX, lineY)
        move(lineX + nx * 2.2f * ppm, lineY + ny * 2.2f * ppm)   // 推近到差 0.2mm
        val gotD = g.axMm * nx + g.ayMm * ny
        println("CENTER d=${gotD}mm expect=${expectD}mm")
        assertEquals("线应正好穿过元素中心", expectD, gotD, 1f)
        assertTrue("这个位置本就远离页面中心，不能靠吸到中心蒙过去", abs(expectD) > 20f)
        up(lineX, lineY)
        frame()
    }

    // ---------------- 4. 旋转吸到元素的边方向 ----------------

    @Test
    fun rotationSnapsToElementAngle() {
        val item = Item(type = Item.TYPE_PHOTO).apply {
            x = 0.2f; y = 0.2f; w = 0.4f; h = 0.3f; imgId = "nope"; rotation = 20f
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)))))
        val c = canvas()
        c.editGuides(true)
        val g = project().guides[0]
        g.angle = 0f; g.axMm = 0f; g.ayMm = 0f
        frame()
        val ppm = pxPerMm()
        val ctr = centerPx()
        val ax = ctr.x + g.axMm * ppm
        val ay = ctr.y + g.ayMm * ppm
        val off = 120f * 3f / 1f          // 手柄距锚点 120dp，xxhdpi 密度 3

        // 抓右手柄，绕锚点转到 21°
        down(ax + off, ay)
        val rad = Math.toRadians(21.0)
        move(ax + (off + 40f) * cos(rad).toFloat(), ay + (off + 40f) * sin(rad).toFloat())
        println("ROTATE angle=${g.angle}  (元素 20°)")
        assertEquals("应吸到元素边方向", 20f, g.angle, 0.6f)
        up(ax + off, ay)
        frame()
    }

    // ---------------- 5. 镜像组跟着一起走 ----------------

    @Test
    fun mirrorGroupMovesTogether() {
        launch(Project(title = "t", pages = mutableListOf(Page())))
        val c = canvas()
        c.editGuides(true)
        c.addGuideMirrors()
        val p = project()
        assertEquals(4, p.guides.size)
        val g = p.guides[0]
        frame()
        val ppm = pxPerMm()
        val ctr = centerPx()

        down(ctr.x + g.axMm * ppm, ctr.y + g.ayMm * ppm)
        move(ctr.x + g.axMm * ppm, ctr.y + g.ayMm * ppm + 400f)
        up(ctr.x + g.axMm * ppm, ctr.y + g.ayMm * ppm + 400f)

        val base = p.guides[0]
        val m1 = p.guides[1]
        println("MIRROR base=(${base.axMm},${base.ayMm},${base.angle}) " +
                "m1=(${m1.axMm},${m1.ayMm},${m1.angle})")
        assertEquals("左右镜像 x 应相反", -base.axMm, m1.axMm, 0.5f)
        assertEquals("左右镜像 y 应相同", base.ayMm, m1.ayMm, 0.5f)
        frame()
    }
}

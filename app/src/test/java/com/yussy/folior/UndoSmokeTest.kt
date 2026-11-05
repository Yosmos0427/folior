package com.yussy.folior

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.yussy.folior.model.Item
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.store.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UndoSmokeTest {

    private lateinit var act: EditorActivity

    private fun launch(p: Project): EditorActivity {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.save(ctx, p)
        val intent = android.content.Intent(ctx, EditorActivity::class.java).putExtra("id", p.id)
        act = Robolectric.buildActivity(EditorActivity::class.java, intent).setup().get()
        layout()
        return act
    }

    private fun layout() {
        val decor = act.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
    }

    /** 真实画一帧：绘制期崩溃只能这样暴露 */
    private fun drawFrame() {
        layout()
        act.window.decorView.draw(Canvas(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)))
    }

    private fun pushHistory() {
        EditorActivity::class.java.getDeclaredMethod("pushHistory")
            .apply { isAccessible = true }.invoke(act)
    }

    private fun click(field: String) {
        val f = EditorActivity::class.java.getDeclaredField(field).apply { isAccessible = true }
        (f.get(act) as View).performClick()
    }

    private fun projectOf(): Project =
        EditorActivity::class.java.getDeclaredField("project").apply { isAccessible = true }
            .get(act) as Project

    private fun canvasOf(): com.yussy.folior.ui.PageCanvas =
        EditorActivity::class.java.getDeclaredField("canvas").apply { isAccessible = true }
            .get(act) as com.yussy.folior.ui.PageCanvas

    private fun live(i: Int = 0): Item = projectOf().pages[0].items[i]

    private fun event(action: Int, pts: List<Pair<Float, Float>>, index: Int = 0): MotionEvent {
        val props = Array(pts.size) {
            MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(pts.size) {
            MotionEvent.PointerCoords().apply {
                x = pts[it].first; y = pts[it].second; pressure = 1f; size = 1f
            }
        }
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        return MotionEvent.obtain(0L, 0L, a, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    // ---------------- 1. 双指重合不再产生非法数值 ----------------

    @Test
    fun pinchFromSamePointStaysFinite() {
        val item = Item(type = Item.TYPE_PHOTO).apply {
            x = 0.3f; y = 0.3f; w = 0.4f; h = 0.3f; imgId = "nope"
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)))))
        val canvas = canvasOf()
        canvas.select(live())
        layout()
        val c = canvas.width / 2f to canvas.height / 2f

        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(c)))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, listOf(c, c), 1))
        // 第一帧仍几乎重合
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(c, c)))
        // 张开到 240px
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE,
            listOf(c.first - 120f to c.second, c.first + 120f to c.second)))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_UP,
            listOf(c.first - 120f to c.second, c.first + 120f to c.second)))

        val it = live()
        println("PINCH-SAME w=${it.w} h=${it.h} x=${it.x} y=${it.y}")
        assertTrue("尺寸必须是合法有限值", it.w.isFinite() && it.h.isFinite())
        assertTrue("位置必须是合法有限值", it.x.isFinite() && it.y.isFinite())
        // 从重合张开：以张开后第一帧为基准，不该突然放大/缩小
        assertTrue("不应突变放大: w=${it.w}", it.w in 0.3f..0.5f)
        pushHistory()   // 修复前这里抛 JSONException
        drawFrame()
    }

    /** 正常双指张开：缩放仍然生效（防止修复把功能改死） */
    @Test
    fun pinchStillWorks() {
        val item = Item(type = Item.TYPE_PHOTO).apply {
            x = 0.3f; y = 0.3f; w = 0.4f; h = 0.3f; imgId = "nope"
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)))))
        val canvas = canvasOf()
        canvas.select(live())
        layout()
        val cy = canvas.height / 2f
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(400f to cy)))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, listOf(400f to cy, 600f to cy), 1))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(300f to cy, 700f to cy)))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_UP, listOf(300f to cy, 700f to cy)))
        println("PINCH w=${live().w}")
        assertTrue("双指张开 2 倍应放大: w=${live().w}", live().w > 0.6f)
    }

    // ---------------- 2. 数据层兜底：NaN 不再毁掉整条链 ----------------

    @Test
    fun nanNeverBreaksSnapshot() {
        val item = Item(type = Item.TYPE_TEXT).apply {
            x = 0.3f; y = 0.3f; w = 0.4f; h = 0.3f; text = "a"
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)))))
        live().w = Float.NaN
        live().x = Float.POSITIVE_INFINITY
        pushHistory()          // 修复前：JSONException → Activity 崩溃
        click("undoBtn")       // 修复前：同样崩
        drawFrame()
        println("NAN-SURVIVE w=${live().w} x=${live().x}")
    }

    // ---------------- 3. 撤销/重做本身 ----------------

    @Test
    fun undoRedoRoundTrip() {
        val item = Item(type = Item.TYPE_TEXT).apply {
            x = 0.2f; y = 0.2f; w = 0.3f; h = 0.1f; text = "hi"
        }
        launch(Project(title = "t", pages = mutableListOf(Page(items = mutableListOf(item)), Page())))
        pushHistory(); live().x = 0.6f; drawFrame()
        click("undoBtn"); drawFrame()
        assertEquals(0.2f, live().x, 0.001f)
        click("redoBtn"); drawFrame()
        assertEquals(0.6f, live().x, 0.001f)
    }

    @Test
    fun undoPageDelete() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page(), Page())))
        val del = EditorActivity::class.java.getDeclaredMethod("deletePageAt", Int::class.java)
            .apply { isAccessible = true }
        pushHistory()
        del.invoke(act, 1)
        click("undoBtn"); drawFrame()
        assertEquals(3, projectOf().pages.size)
    }

    // ---------------- 4. 撤销后旧引用不留痕 ----------------

    @Test
    fun guidesStillEditableAfterUndo() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        val canvas = canvasOf()
        canvas.editGuides(true)
        pushHistory()
        canvas.addGuide()
        assertEquals(2, canvas.guideCount)
        click("undoBtn")
        val c2 = canvasOf()
        assertEquals(1, c2.guideCount)
        // 修复前：activeGuide 指向旧对象，removeAll 匹配不到 → 删不掉
        assertTrue("撤销后仍能删掉指示线", c2.removeActiveGuide())
        assertEquals(0, c2.guideCount)
        drawFrame()
    }

    /** 撤销不应该把用户放大查看的视图位置弹回 1 倍 */
    @Test
    fun undoKeepsViewZoom() {
        launch(Project(title = "t", pages = mutableListOf(Page())))
        val canvas = canvasOf()
        val cy = canvas.height / 2f
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(400f to cy)))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, listOf(400f to cy, 600f to cy), 1))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(300f to cy, 700f to cy)))
        canvas.dispatchTouchEvent(event(MotionEvent.ACTION_UP, listOf(300f to cy, 700f to cy)))
        val vsField = com.yussy.folior.ui.PageCanvas::class.java.getDeclaredField("viewScale")
            .apply { isAccessible = true }
        val before = vsField.getFloat(canvas)
        pushHistory()
        click("undoBtn")
        val after = vsField.getFloat(canvasOf())
        println("VIEW before=$before after=$after")
        assertEquals(before, after, 0.001f)
    }
}

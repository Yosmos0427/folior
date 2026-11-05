package com.yussy.folior

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.yussy.folior.model.Guide
import com.yussy.folior.model.Page
import com.yussy.folior.model.Project
import com.yussy.folior.store.Store
import com.yussy.folior.ui.PageCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class GuideMirrorTest {

    private lateinit var act: EditorActivity

    private fun launch(p: Project): EditorActivity {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.save(ctx, p)
        act = Robolectric.buildActivity(EditorActivity::class.java,
            android.content.Intent(ctx, EditorActivity::class.java).putExtra("id", p.id))
            .setup().get()
        val decor = act.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        return act
    }

    private fun drawFrame() {
        act.window.decorView.draw(Canvas(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)))
    }

    private fun project(): Project =
        EditorActivity::class.java.getDeclaredField("project").apply { isAccessible = true }
            .get(act) as Project

    private fun canvas(): PageCanvas =
        EditorActivity::class.java.getDeclaredField("canvas").apply { isAccessible = true }
            .get(act) as PageCanvas

    private fun enterGuideMode() {
        val c = canvas()
        c.editGuides(true)
        // 默认那条压在中线上，先挪开一点，模拟用户摆好的位置
        val g = project().guides[0]
        g.angle = 30f; g.axMm = -40f; g.ayMm = -60f
    }

    /** 组内四条应两两关于中心对称 */
    private fun assertSymmetric(gs: List<Guide>) {
        val o = gs.first { it.role == 0 }
        val lr = gs.first { it.role == 1 }
        val ud = gs.first { it.role == 2 }
        val dg = gs.first { it.role == 3 }
        assertEquals("左右镜像角度", -o.angle, lr.angle, 0.001f)
        assertEquals("左右镜像锚点 x", -o.axMm, lr.axMm, 0.001f)
        assertEquals("左右镜像锚点 y", o.ayMm, lr.ayMm, 0.001f)
        assertEquals("上下镜像角度", -o.angle, ud.angle, 0.001f)
        assertEquals("上下镜像锚点 x", o.axMm, ud.axMm, 0.001f)
        assertEquals("上下镜像锚点 y", -o.ayMm, ud.ayMm, 0.001f)
        assertEquals("对角角度", o.angle, dg.angle, 0.001f)
        assertEquals("对角锚点 x", -o.axMm, dg.axMm, 0.001f)
        assertEquals("对角锚点 y", -o.ayMm, dg.ayMm, 0.001f)
    }

    @Test
    fun buildFourQuadrants() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        assertEquals(1, project().guides.size)
        assertTrue(canvas().addGuideMirrors())
        val gs = project().guides
        println("MIRROR count=${gs.size} roles=${gs.map { it.role }} groups=${gs.map { it.group }}")
        assertEquals(4, gs.size)
        assertEquals(setOf(0, 1, 2, 3), gs.map { it.role }.toSet())
        assertEquals(1, gs.map { it.group }.toSet().size)
        assertSymmetric(gs)
        drawFrame()
    }

    /** 浮动菜单里的「镜像」按钮确实接到了画布 */
    @Test
    fun quickMenuButtonBuildsMirror() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        refreshQuickMenu()
        val btn = EditorActivity::class.java.getDeclaredField("quickC").apply { isAccessible = true }
            .get(act) as android.widget.TextView
        assertEquals("镜像", btn.text.toString())
        btn.performClick()
        println("MENU count=${project().guides.size} mirrored=${canvas().activeGuideMirrored}")
        assertEquals(4, project().guides.size)
        refreshQuickMenu()
        assertEquals("解除", btn.text.toString())
        btn.performClick()
        assertEquals(4, project().guides.size)
        assertTrue(project().guides.all { it.group == 0 })
        drawFrame()
    }

    @Test
    fun dragOriginalSyncsMirrors() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        canvas().addGuideMirrors()
        val o = project().guides.first { it.role == 0 }
        // 模拟拖动：改原线几何后同步
        o.angle = -45f; o.axMm = 30f; o.ayMm = 20f
        syncMirror(o)
        assertSymmetric(project().guides)
        println("AFTER DRAG origin=${project().guides.filter { it.role == 0 }.size}")
        drawFrame()
    }

    @Test
    fun dragMirrorLineKeepsSymmetry() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        canvas().addGuideMirrors()
        val mirrored = project().guides.first { it.role == 1 }
        mirrored.angle = -20f; mirrored.axMm = 55f; mirrored.ayMm = -35f
        syncMirror(mirrored)
        // 拖镜像线时，原线应按它的镜像反算回去，四条仍对称
        assertSymmetric(project().guides)
        val o = project().guides.first { it.role == 0 }
        assertEquals("原线角度 = -镜像角度", 20f, o.angle, 0.001f)
        assertEquals("原线锚点 x = -镜像锚点 x", -55f, o.axMm, 0.001f)
        assertEquals("原线锚点 y = 镜像锚点 y", -35f, o.ayMm, 0.001f)
    }

    @Test
    fun unlinkMakesThemIndependent() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        val c = canvas()
        c.addGuideMirrors()
        assertTrue(c.activeGuideMirrored)
        assertTrue(c.unlinkActiveMirror())
        assertFalse(c.activeGuideMirrored)
        assertTrue(project().guides.all { it.group == 0 && it.role == 0 })
        assertEquals(4, project().guides.size)
        // 解除后改一条，其余不该动
        val before = project().guides.map { it.angle }
        project().guides[0].angle = 12f
        syncMirror(project().guides[0])
        assertEquals(before.drop(1), project().guides.drop(1).map { it.angle })
    }

    @Test
    fun deleteDownToOneUnlinks() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        val c = canvas()
        c.addGuideMirrors()
        // 逐条删除，剩两条时仍联动，剩一条自动解除
        repeat(3) { c.removeActiveGuide() }
        val left = project().guides
        println("LEFT count=${left.size} group=${left.map { it.group }}")
        assertEquals(1, left.size)
        assertEquals(0, left[0].group)
        assertEquals(0, left[0].role)
    }

    @Test
    fun mirrorSurvivesSaveAndUndo() {
        launch(Project(title = "t", pages = mutableListOf(Page(), Page())))
        enterGuideMode()
        canvas().addGuideMirrors()
        val json = project().toJson().toString()
        val back = Project.fromJson(org.json.JSONObject(json))
        println("ROUNDTRIP groups=${back.guides.map { it.group }} roles=${back.guides.map { it.role }}")
        assertEquals(project().guides.map { it.group }, back.guides.map { it.group })
        assertEquals(project().guides.map { it.role }, back.guides.map { it.role })
    }

    // ---- 工具 ----

    private fun refreshQuickMenu() {
        EditorActivity::class.java.getDeclaredMethod("updateBars")
            .apply { isAccessible = true }.invoke(act)
    }

    private fun syncMirror(g: Guide) {
        PageCanvas::class.java.getDeclaredMethod("syncMirrorGroup", Guide::class.java)
            .apply { isAccessible = true }.invoke(canvas(), g)
    }
}

package com.yussy.folior.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 数据模型。
 * 坐标体系：所有 item 的 x/y/w/h 均为相对页面的归一化值（0..1），
 * 保证任意页面尺寸下布局等比成立。
 */
data class Project(
    var id: String = UUID.randomUUID().toString(),
    var title: String = "未命名",
    var type: String = TYPE_BOOK,          // book | exhibition
    var pageWmm: Float = 210f,
    var pageHmm: Float = 297f,
    var defaultBg: String = "#FFFFFF",
    var pages: MutableList<Page> = mutableListOf(),
    /** 指示线：贯穿工作区的构图参考线，项目级共享（翻页不变） */
    var guides: MutableList<Guide> = mutableListOf()
) {
    companion object {
        const val TYPE_BOOK = "book"
        const val TYPE_EXHIBITION = "exhibition"

        fun fromJson(o: JSONObject): Project {
            val p = Project(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title", "未命名"),
                type = o.optString("type", TYPE_BOOK),
                pageWmm = o.optDouble("pageWmm", 210.0).toFloat(),
                pageHmm = o.optDouble("pageHmm", 297.0).toFloat(),
                defaultBg = o.optString("defaultBg", "#FFFFFF")
            )
            val arr = o.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until arr.length()) p.pages.add(Page.fromJson(arr.getJSONObject(i)))
            val gs = o.optJSONArray("guides")
            if (gs != null) for (i in 0 until gs.length()) {
                val g = gs.optJSONObject(i) ?: continue
                p.guides.add(Guide(
                    angle = g.optDouble("a", 0.0).toFloat(),
                    axMm = g.optDouble("x", 0.0).toFloat(),
                    ayMm = g.optDouble("y", 0.0).toFloat(),
                    group = g.optInt("g", 0),
                    role = g.optInt("r", 0)))
            }
            return p
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("title", title); o.put("type", type)
        o.put("pageWmm", num(pageWmm, 210f)); o.put("pageHmm", num(pageHmm, 297f))
        o.put("defaultBg", defaultBg)
        o.put("format", "folior-1")
        val arr = JSONArray()
        pages.forEach { arr.put(it.toJson()) }
        o.put("pages", arr)
        val gs = JSONArray()
        guides.forEach { g ->
            gs.put(JSONObject().apply {
                put("a", num(g.angle))
                put("x", num(g.axMm))
                put("y", num(g.ayMm))
                put("g", g.group)
                put("r", g.role)
            })
        }
        o.put("guides", gs)
        return o
    }

    fun pageBg(page: Page): Int = parseColor(page.bg ?: defaultBg, 0xFFFFFFFF.toInt())
}

data class Page(
    var id: String = UUID.randomUUID().toString(),
    var bg: String? = null,
    /** 页级尺寸覆盖（摄影书自定义页 / 展览墙体），null = 使用项目默认 */
    var wmm: Float? = null,
    var hmm: Float? = null,
    var items: MutableList<Item> = mutableListOf()
) {
    companion object {
        fun fromJson(o: JSONObject): Page {
            val p = Page(id = o.optString("id", UUID.randomUUID().toString()))
            p.bg = if (o.isNull("bg")) null else o.optString("bg")
            p.wmm = if (o.isNull("wmm")) null else o.optDouble("wmm").toFloat()
            p.hmm = if (o.isNull("hmm")) null else o.optDouble("hmm").toFloat()
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) p.items.add(Item.fromJson(arr.getJSONObject(i)))
            return p
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("bg", bg ?: JSONObject.NULL)
        o.put("wmm", wmm?.let { num(it) } ?: JSONObject.NULL)
        o.put("hmm", hmm?.let { num(it) } ?: JSONObject.NULL)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        o.put("items", arr)
        return o
    }
}

/**
 * 指示线：一条贯穿工作区的直线，只作构图参考，不参与 PDF / 预览输出。
 *
 * 以「角度 + 锚点」定义——旋转绕锚点，平移沿法线移动锚点。
 * 锚点用 mm 存（相对内容中心），于是缩放、换页、不同设备上位置一致。
 */
data class Guide(
    var angle: Float = 0f,     // 度，0 = 水平，归一到 (-90, 90]
    var axMm: Float = 0f,      // 锚点相对内容中心的横向偏移
    var ayMm: Float = 0f,      // 纵向偏移
    /** 镜像组 id，0 = 独立的一条线。同组四条关于页面中线两两对称。 */
    var group: Int = 0,
    /** 组内角色：0 原线 / 1 左右镜像 / 2 上下镜像 / 3 对角镜像 */
    var role: Int = 0
)

/** 页面实际宽高（mm）：页级覆盖优先，回退项目默认 */
fun Page.widthMm(project: Project): Float = wmm ?: project.pageWmm
fun Page.heightMm(project: Project): Float = hmm ?: project.pageHmm

/**
 * 页面渲染指纹：页面自身（元素、尺寸、页面色）+ 项目默认色。
 * 缩略图缓存以它为 key 的一部分 —— 只有指纹变化（全局设置影响所有页、
 * 页面设置/内容只影响本页）的页面才会重新渲染。
 */
fun Page.renderSignature(project: Project): Int {
    var h = hashCode()                       // Page 为 data class，已覆盖全部字段与 items
    h = 31 * h + project.defaultBg.hashCode()
    return h
}

/**
 * 写进 JSON 的数值一律过这一关。
 *
 * org.json 对 NaN / Infinity 会直接抛 `Forbidden numeric value`——一旦某个坐标
 * 被算成 NaN（双指缩放两指重合时除零就是这样），序列化立刻失败：快照、存盘、
 * 撤销全部连带崩掉，编辑器表现为「一点就闪退」。这里把非有限值换成兜底值，
 * 让最坏情况退化成位置/尺寸归零，而不是整份工程写不出去。
 */
private fun num(v: Float, fallback: Float = 0f): Double =
    if (v.isFinite()) v.toDouble() else fallback.toDouble()

private fun num(v: Double): Double = if (v.isFinite()) v else 0.0

/**
 * 照片是否横向越出页面边界。跨页只按几何判定——越界且那一侧有相邻页就延伸过去，
 * 不依赖任何额外的开关状态（拉回页内即恢复单页）。
 */
fun Item.crossesHorizontally(): Boolean =
    type == Item.TYPE_PHOTO && (x < -0.002f || x + w > 1.002f)

/** 本页是否有照片向右侧越出（会被接到右邻页上） */
fun Page.spillsRight(): Boolean =
    items.any { it.crossesHorizontally() && it.x + it.w > 1.002f }

/** 本页是否有照片向左侧越出（会被接到左邻页上） */
fun Page.spillsLeft(): Boolean =
    items.any { it.crossesHorizontally() && it.x < -0.002f }

/**
 * 摄影书页序：第 0 页为封面（单页，靠右）；此后奇数页在左、偶数页在右。
 * 跨页只在同一对开的两页之间成立，因此「左邻 / 右邻」按对开规则确定，
 * 而不是简单的 index ± 1。
 */
fun leftNeighborOf(index: Int): Int =
    if (index >= 2 && index % 2 == 0) index - 1 else -1

fun rightNeighborOf(index: Int, total: Int): Int =
    if (index >= 1 && index % 2 == 1 && index + 1 < total) index + 1 else -1

data class Item(
    var id: String = UUID.randomUUID().toString(),
    var type: String = TYPE_PHOTO,
    var x: Float = 0.1f, var y: Float = 0.1f,
    var w: Float = 0.5f, var h: Float = 0.5f,
    var rotation: Float = 0f,
    // photo
    var imgId: String? = null,
    var cropZoom: Float = 1f,        // 1 = cover 铺满, >1 放大裁切
    var cropCx: Float = 0.5f,        // 裁切中心（图像归一化坐标）
    var cropCy: Float = 0.5f,
    var frameStroke: Boolean = false,
    /** 边框材质：null=无 / wood 木 / metal 金属 / acrylic 亚克力（旧值 mat/frame 自动迁移） */
    var frameStyle: String? = null,
    var frameColor: String? = null,    // 边框颜色覆盖
    var frameW: Float = 0.02f,         // 边框宽度（min(衬宽,衬高) 比例）
    /** 背景衬材：null=无 / paper 纸张 / wood 木板 / metal 金属 / acrylic 亚克力 */
    var matStyle: String? = null,
    var matColor: String? = null,      // 衬材颜色覆盖
    var matPadX: Float = 0.08f,        // 衬材横向外沿（内容 min(宽,高) 比例）
    var matPadY: Float = 0.08f,        // 衬材纵向外沿
    var matRatio: Float = 0f,          // 衬材宽高比，0=跟随内容
    /** 照片实际打印宽度（mm），用于展览墙体按真实比例摆放；null = 未指定 */
    var printMm: Float? = null,
    /**
     * 旧版跨页标记，仅供旧工程读取兼容；跨页现由几何位置判定（见 [crossesHorizontally]），
     * 不再读写此字段。
     */
    var span: Boolean = false,
    // text
    var text: String = "",
    var fontScale: Float = 0.04f,    // 字号 = 页高的比例
    var color: String = "#1A1A18",
    var bold: Boolean = false,
    var italic: Boolean = false,
    var align: Int = 0,              // 0 left 1 center 2 right
    var lineHeight: Float = 1.4f,
    var vertical: Boolean = false,  // 竖排（自右向左成列）
    /** 字体 id（见 ui/Fonts.kt），空串 = 系统默认 */
    var font: String = "",
    // shape
    var shapeKind: String = "rect",  // rect | line
    var shapeColor: String = "#1A1A18",
    // stroke（手绘笔画）
    var strokeColor: String = "#1A1A18",
    var strokeW: Float = 0.012f,        // 线宽（页高比例）
    var strokeAlpha: Float = 1f,        // 马克笔约 0.5
    var strokeMarker: Boolean = false,  // 马克笔（半透明平头）
    /** 扁平点列 x,y,p（相对边界框 0..1，p = 压感 0..1） */
    var pts: MutableList<Float> = mutableListOf()
) {
    companion object {
        const val TYPE_PHOTO = "photo"
        const val TYPE_TEXT = "text"
        const val TYPE_SHAPE = "shape"
        const val TYPE_STROKE = "stroke"

        fun fromJson(o: JSONObject): Item {
            val it = Item(id = o.optString("id", UUID.randomUUID().toString()))
            it.type = o.optString("type", TYPE_PHOTO)
            it.x = o.optDouble("x", 0.1).toFloat()
            it.y = o.optDouble("y", 0.1).toFloat()
            it.w = o.optDouble("w", 0.5).toFloat()
            it.h = o.optDouble("h", 0.5).toFloat()
            it.rotation = o.optDouble("rotation", 0.0).toFloat()
            it.imgId = if (o.isNull("imgId")) null else o.optString("imgId")
            it.cropZoom = o.optDouble("cropZoom", 1.0).toFloat()
            it.cropCx = o.optDouble("cropCx", 0.5).toFloat()
            it.cropCy = o.optDouble("cropCy", 0.5).toFloat()
            it.frameStroke = o.optBoolean("frameStroke", false)
            it.frameStyle = if (o.isNull("frameStyle")) null else o.optString("frameStyle")
            it.frameColor = if (o.isNull("frameColor")) null else o.optString("frameColor")
            it.frameW = o.optDouble("frameW", 0.02).toFloat()
            it.matStyle = if (o.isNull("matStyle")) null else o.optString("matStyle")
            it.matColor = if (o.isNull("matColor")) null else o.optString("matColor")
            // 旧数据只有统一外延 matPad：两个方向都取该值
            val legacyPad = o.optDouble("matPad", 0.08).toFloat()
            it.matPadX = o.optDouble("matPadX", legacyPad.toDouble()).toFloat()
            it.matPadY = o.optDouble("matPadY", legacyPad.toDouble()).toFloat()
            it.matRatio = o.optDouble("matRatio", 0.0).toFloat()
            // ---- 旧数据迁移 ----
            if (it.frameStyle == "mat") {          // 旧卡纸装裱 → 纸张衬材
                if (it.matStyle == null) it.matStyle = "paper"
                it.frameStyle = null
            } else if (it.frameStyle == "frame") { // 旧木框 → 木边框
                it.frameStyle = "wood"
            }
            if (it.matStyle == null && !o.isNull("textBg"))  // 旧文字背景 → 衬材
                it.matStyle = o.optString("textBg")
            it.printMm = if (o.isNull("printMm")) null else o.optDouble("printMm").toFloat()
            it.span = o.optBoolean("span", false)
            it.text = o.optString("text", "")
            it.fontScale = o.optDouble("fontScale", 0.04).toFloat()
            it.color = o.optString("color", "#1A1A18")
            it.bold = o.optBoolean("bold", false)
            it.italic = o.optBoolean("italic", false)
            it.align = o.optInt("align", 0)
            it.lineHeight = o.optDouble("lineHeight", 1.4).toFloat()
            it.vertical = o.optBoolean("vertical", false)
            it.font = o.optString("font", "")
            it.shapeKind = o.optString("shapeKind", "rect")
            it.shapeColor = o.optString("shapeColor", "#1A1A18")
            it.strokeColor = o.optString("strokeColor", "#1A1A18")
            it.strokeW = o.optDouble("strokeW", 0.012).toFloat()
            it.strokeAlpha = o.optDouble("strokeAlpha", 1.0).toFloat()
            it.strokeMarker = o.optBoolean("strokeMarker", false)
            val ptsArr = o.optJSONArray("pts")
            if (ptsArr != null) for (k in 0 until ptsArr.length())
                it.pts.add(ptsArr.optDouble(k).toFloat())
            return it
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("type", type)
        o.put("x", num(x)); o.put("y", num(y))
        o.put("w", num(w, 0.5f)); o.put("h", num(h, 0.5f))
        o.put("rotation", num(rotation))
        o.put("imgId", imgId ?: JSONObject.NULL)
        o.put("cropZoom", num(cropZoom, 1f))
        o.put("cropCx", num(cropCx, 0.5f)); o.put("cropCy", num(cropCy, 0.5f))
        o.put("frameStroke", frameStroke)
        o.put("frameStyle", frameStyle ?: JSONObject.NULL)
        o.put("frameColor", frameColor ?: JSONObject.NULL)
        o.put("frameW", num(frameW, 0.02f))
        o.put("matStyle", matStyle ?: JSONObject.NULL)
        o.put("matColor", matColor ?: JSONObject.NULL)
        o.put("matPadX", num(matPadX, 0.08f))
        o.put("matPadY", num(matPadY, 0.08f))
        // 旧版本兼容字段
        o.put("matPad", num(Math.min(matPadX, matPadY), 0.08f))
        o.put("matRatio", num(matRatio))
        o.put("printMm", printMm?.let { num(it, 1f) } ?: JSONObject.NULL)
        o.put("span", span)
        o.put("text", text)
        o.put("fontScale", num(fontScale, 0.04f))
        o.put("color", color); o.put("bold", bold); o.put("italic", italic)
        o.put("align", align); o.put("lineHeight", num(lineHeight, 1.4f))
        o.put("vertical", vertical)
        o.put("font", font)
        o.put("shapeKind", shapeKind); o.put("shapeColor", shapeColor)
        o.put("strokeColor", strokeColor); o.put("strokeW", num(strokeW, 0.012f))
        o.put("strokeAlpha", num(strokeAlpha, 1f)); o.put("strokeMarker", strokeMarker)
        val ptsArr = JSONArray()
        pts.forEach { ptsArr.put(num(it)) }
        o.put("pts", ptsArr)
        return o
    }

    fun bounds(rotated: Boolean = true): FloatArray {
        // 返回 [cx, cy, hw, hh, rad] 中心 + 半宽高 + 弧度
        val rad = if (rotated) Math.toRadians(rotation.toDouble()).toFloat() else 0f
        return floatArrayOf(x + w / 2f, y + h / 2f, w / 2f, h / 2f, rad)
    }
}

fun parseColor(s: String?, fallback: Int): Int {
    if (s.isNullOrBlank()) return fallback
    return try {
        android.graphics.Color.parseColor(s)
    } catch (e: Exception) { fallback }
}

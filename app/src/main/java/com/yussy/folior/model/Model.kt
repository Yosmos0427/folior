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
    var pages: MutableList<Page> = mutableListOf()
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
            return p
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("title", title); o.put("type", type)
        o.put("pageWmm", pageWmm.toDouble()); o.put("pageHmm", pageHmm.toDouble())
        o.put("defaultBg", defaultBg)
        o.put("format", "folior-1")
        val arr = JSONArray()
        pages.forEach { arr.put(it.toJson()) }
        o.put("pages", arr)
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
        o.put("wmm", wmm ?: JSONObject.NULL); o.put("hmm", hmm ?: JSONObject.NULL)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        o.put("items", arr)
        return o
    }
}

/** 页面实际宽高（mm）：页级覆盖优先，回退项目默认 */
fun Page.widthMm(project: Project): Float = wmm ?: project.pageWmm
fun Page.heightMm(project: Project): Float = hmm ?: project.pageHmm

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
    /** 简易装裱：null=无 / "mat"=卡纸 / "frame"=木框 */
    var frameStyle: String? = null,
    // text
    var text: String = "",
    var fontScale: Float = 0.04f,    // 字号 = 页高的比例
    var color: String = "#1A1A18",
    var bold: Boolean = false,
    var italic: Boolean = false,
    var align: Int = 0,              // 0 left 1 center 2 right
    var lineHeight: Float = 1.4f,
    // shape
    var shapeKind: String = "rect",  // rect | line
    var shapeColor: String = "#1A1A18"
) {
    companion object {
        const val TYPE_PHOTO = "photo"
        const val TYPE_TEXT = "text"
        const val TYPE_SHAPE = "shape"

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
            it.text = o.optString("text", "")
            it.fontScale = o.optDouble("fontScale", 0.04).toFloat()
            it.color = o.optString("color", "#1A1A18")
            it.bold = o.optBoolean("bold", false)
            it.italic = o.optBoolean("italic", false)
            it.align = o.optInt("align", 0)
            it.lineHeight = o.optDouble("lineHeight", 1.4).toFloat()
            it.shapeKind = o.optString("shapeKind", "rect")
            it.shapeColor = o.optString("shapeColor", "#1A1A18")
            return it
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("type", type)
        o.put("x", x.toDouble()); o.put("y", y.toDouble())
        o.put("w", w.toDouble()); o.put("h", h.toDouble())
        o.put("rotation", rotation.toDouble())
        o.put("imgId", imgId ?: JSONObject.NULL)
        o.put("cropZoom", cropZoom.toDouble())
        o.put("cropCx", cropCx.toDouble()); o.put("cropCy", cropCy.toDouble())
        o.put("frameStroke", frameStroke)
        o.put("frameStyle", frameStyle ?: JSONObject.NULL)
        o.put("text", text)
        o.put("fontScale", fontScale.toDouble())
        o.put("color", color); o.put("bold", bold); o.put("italic", italic)
        o.put("align", align); o.put("lineHeight", lineHeight.toDouble())
        o.put("shapeKind", shapeKind); o.put("shapeColor", shapeColor)
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

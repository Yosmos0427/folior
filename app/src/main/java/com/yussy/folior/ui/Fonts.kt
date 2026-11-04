package com.yussy.folior.ui

import android.content.Context
import android.graphics.Typeface

/**
 * 内置字体清单。
 *
 * 全部为 SIL OFL 授权，可随应用分发。中文三种常用风格（宋 / 黑 / 楷），
 * 西文按摄影书排版惯例给出古典衬线、展示衬线、现代无衬线、几何无衬线、打字机五类。
 * id 为空串表示系统默认字体，旧工程（无 font 字段）自然落到它。
 *
 * "Futura" 一行用的是 Jost（OFL）——Futura 本身是 Bauer Types 的商业字体，
 * 不能随 MIT 项目分发；Jost 是同一套 1920 年代几何无衬线设计稿的开源复刻。
 */
object Fonts {

    class Def(
        val id: String,
        val name: String,       // 列表显示名
        val sample: String,     // 列表里的示例字串（用字体自身渲染）
        val file: String,       // assets 路径；空 = 系统默认
        val group: String,
        val boldFile: String = ""   // 真粗体；空则用合成加粗
    )

    const val DEFAULT = ""

    val ALL: List<Def> = listOf(
        Def(DEFAULT, "系统默认", "册页 Aa", "", "基础"),

        Def("sc-serif", "思源宋体", "摄影书 Aa", "fonts/NotoSerifSC.ttf", "中文",
            "fonts/NotoSerifSC-Bold.ttf"),
        Def("sc-sans", "思源黑体", "摄影书 Aa", "fonts/NotoSansSC.ttf", "中文",
            "fonts/NotoSansSC-Bold.ttf"),
        Def("sc-kai", "霞鹜文楷", "手记 Aa", "fonts/LXGWWenKai.ttf", "中文"),

        Def("garamond", "Garamond", "Photographs", "fonts/EBGaramond.ttf", "西文"),
        Def("playfair", "Playfair Display", "Photographs", "fonts/PlayfairDisplay.ttf", "西文"),
        Def("inter", "Inter", "Photographs", "fonts/Inter.ttf", "西文"),
        Def("jost", "Futura", "Photographs", "fonts/Jost.ttf", "西文",
            "fonts/Jost-Bold.ttf"),
        Def("mono", "Space Mono", "Photographs", "fonts/SpaceMono.ttf", "西文"),
    )

    /** 已下线的字体 id → 替代项。旧工程打开时静默落到同类字体，不突变版式。 */
    private val LEGACY = mapOf("sc-title" to "sc-sans")

    private val cache = HashMap<String, Typeface?>()

    fun def(id: String): Def {
        val key = LEGACY[id] ?: id
        return ALL.firstOrNull { it.id == key } ?: ALL[0]
    }

    fun displayName(id: String): String = def(id).name

    /** 该字体是否带有真正的粗体文件（有则不再叠加合成加粗）。 */
    fun hasBold(id: String): Boolean = def(id).boldFile.isNotEmpty()

    /** 取字体；id 为空或加载失败返回 null（交给系统默认）。结果常驻缓存。 */
    fun typeface(ctx: Context?, id: String, bold: Boolean = false): Typeface? {
        if (id.isEmpty() || ctx == null) return null
        val d = def(id)
        val path = if (bold && d.boldFile.isNotEmpty()) d.boldFile else d.file
        if (path.isEmpty()) return null
        val key = path
        if (cache.containsKey(key)) return cache[key]
        val tf = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, path)
        } catch (e: Throwable) {
            null
        }
        cache[key] = tf
        return tf
    }
}

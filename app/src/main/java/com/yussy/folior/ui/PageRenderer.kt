package com.yussy.folior.ui

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import com.yussy.folior.model.*
import com.yussy.folior.store.Store

/**
 * 统一页面渲染器：画布、缩略图、PDF、放映共用。
 * 拆分为「页底」与「内容」两层，便于跨页模式下把多页内容绘制在同一画布上。
 */
object PageRenderer {

    fun pageBgColor(project: Project, page: Page): Int =
        parseColor(page.bg ?: project.defaultBg, Color.WHITE)

    /** 单页完整绘制（自动裁切到页内）。 */
    fun drawPage(
        ctx: Context, project: Project, page: Page,
        canvas: android.graphics.Canvas,
        left: Float, top: Float, wPx: Float, hPx: Float,
        highQuality: Boolean = false
    ) {
        drawPageBackground(project, page, canvas, RectF(left, top, left + wPx, top + hPx))
        val save = canvas.save()
        canvas.clipRect(left, top, left + wPx, top + hPx)
        drawItems(ctx, project, page, canvas, RectF(left, top, left + wPx, top + hPx), highQuality)
        canvas.restoreToCount(save)
    }

    fun drawPageBackground(
        project: Project, page: Page,
        canvas: android.graphics.Canvas, rect: RectF
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = pageBgColor(project, page)
        canvas.drawRect(rect, paint)
    }

    /**
     * 绘制该页全部元素（不裁切，调用方决定裁切范围）。
     * pxHint：整页在目标输出中的像素宽度（PDF 导出时按 300dpi 传入），
     *         用于按实际占版面积解码图片，避免无谓的超大图解码与嵌入。
     */
    fun drawItems(
        ctx: Context, project: Project, page: Page,
        canvas: android.graphics.Canvas, rect: RectF,
        highQuality: Boolean = false, pxHint: Int? = null
    ) {
        val isExhibition = project.type == Project.TYPE_EXHIBITION
        // 笔画始终在最底层（页面之上、所有展示物之下），不遮挡照片/文字
        for (item in page.items) {
            if (item.type != Item.TYPE_STROKE) continue
            drawItem(ctx, item, canvas, rect.left, rect.top, rect.width(), rect.height(),
                isExhibition, highQuality, pxHint)
        }
        for (item in page.items) {
            if (item.type == Item.TYPE_STROKE) continue
            drawItem(ctx, item, canvas, rect.left, rect.top, rect.width(), rect.height(),
                isExhibition, highQuality, pxHint)
        }
    }

    fun drawItem(
        ctx: Context, item: Item,
        canvas: android.graphics.Canvas,
        left: Float, top: Float, wPx: Float, hPx: Float,
        isExhibition: Boolean, highQuality: Boolean = false, pxHint: Int? = null
    ) {
        val cx = left + (item.x + item.w / 2f) * wPx
        val cy = top + (item.y + item.h / 2f) * hPx
        val fw = item.w * wPx
        val fh = item.h * hPx

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.rotate(item.rotation, cx, cy)

        val fl = cx - fw / 2f
        val fT = cy - fh / 2f

        when (item.type) {
            Item.TYPE_PHOTO -> {
                val imgId = item.imgId
                if (imgId == null) { canvas.restore(); return }
                val maxDim = when {
                    // 按元素在成品上的实际宽度换算所需像素（300dpi）
                    pxHint != null -> (item.w * pxHint).toInt().coerceIn(320, 3200)
                    highQuality -> 3000
                    fw > 900 -> 2048
                    fw > 260 -> 1024
                    else -> 512
                }
                val bmp = Store.decodeFull(ctx, imgId, maxDim)
                if (bmp == null) {
                    p.color = 0xFFE8E8E2.toInt()
                    canvas.drawRect(fl, fT, fl + fw, fT + fh, p)
                    canvas.restore()
                    return
                }
                // 统一装裱：投影按装裱外沿，边框围在衬材外围（衬材可自定义比例）
                val fr = computeFraming(item, fl, fT, fw, fh)
                if (isExhibition || fr != null)
                    drawShadow(canvas, fr?.outerL ?: fl, fr?.outerT ?: fT,
                        fr?.outerW ?: fw, fr?.outerH ?: fh, 18f)
                fr?.let { drawFraming(canvas, it) }
                canvas.save()
                canvas.clipRect(fl, fT, fl + fw, fT + fh)
                drawCroppedBitmap(canvas, bmp, item, fl, fT, fw, fh, highQuality)
                canvas.restore()
                if (item.frameStroke) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = Math.max(1f, wPx * 0.0015f)
                    p.color = 0x33000000
                    canvas.drawRect(fl, fT, fl + fw, fT + fh, p)
                }
            }
            Item.TYPE_TEXT -> {
                // 装裱（与照片同一套衬材/边框系统）
                computeFraming(item, fl, fT, fw, fh)?.let { drawFraming(canvas, it) }
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG)
                tp.color = parseColor(item.color, Color.BLACK)
                tp.textSize = item.fontScale * hPx
                tp.isFakeBoldText = item.bold
                tp.textSkewX = if (item.italic) -0.25f else 0f
                val alignment = when (item.align) {
                    1 -> android.text.Layout.Alignment.ALIGN_CENTER
                    2 -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                    else -> android.text.Layout.Alignment.ALIGN_NORMAL
                }
                if (item.vertical) {
                    drawVerticalText(canvas, item, tp, fl, fT, fw, fh)
                } else {
                    val layoutW = fw.coerceAtLeast(tp.textSize)
                    val sl = android.text.StaticLayout.Builder
                        .obtain(item.text, 0, item.text.length, tp, layoutW.toInt().coerceAtLeast(1))
                        .setAlignment(alignment)
                        .setLineSpacing(0f, item.lineHeight)
                        .setIncludePad(false)
                        .build()
                    canvas.save()
                    canvas.translate(fl, fT)
                    sl.draw(canvas)
                    canvas.restore()
                }
            }
            Item.TYPE_SHAPE -> {
                p.color = parseColor(item.shapeColor, Color.BLACK)
                when (item.shapeKind) {
                    "line" -> {
                        val th = fh.coerceAtMost(2f + wPx * 0.002f)
                        canvas.drawRect(fl, cy - th / 2f, fl + fw, cy + th / 2f, p)
                    }
                    "circle" -> canvas.drawOval(fl, fT, fl + fw, fT + fh, p)
                    else -> canvas.drawRect(fl, fT, fl + fw, fT + fh, p)
                }
            }
            Item.TYPE_STROKE -> {
                if (item.pts.size >= 3) {
                    val base = (item.strokeW * hPx).coerceAtLeast(1f)
                    p.style = Paint.Style.STROKE
                    p.color = parseColor(item.strokeColor, Color.BLACK)
                    p.alpha = (item.strokeAlpha.coerceIn(0.1f, 1f) * 255f).toInt()
                    p.strokeJoin = Paint.Join.ROUND
                    p.strokeCap = if (item.strokeMarker) Paint.Cap.BUTT else Paint.Cap.ROUND
                    fun px(k: Int) = fl + item.pts[k] * fw
                    fun py(k: Int) = fT + item.pts[k + 1] * fh
                    if (item.strokeMarker) {
                        // 马克笔：整条路径一次绘制（平头半透明），避免接缝处叠加变深
                        p.strokeWidth = base
                        val path = Path()
                        path.moveTo(px(0), py(0))
                        var k = 3
                        while (k < item.pts.size) { path.lineTo(px(k), py(k)); k += 3 }
                        canvas.drawPath(path, p)
                    } else {
                        // 压感笔：按点压感分段变宽（S Pen 笔锋）
                        var ax = px(0); var ay = py(0)
                        var k = 3
                        while (k < item.pts.size) {
                            val bx = px(k); val by = py(k)
                            val press = item.pts[k + 2].coerceIn(0f, 1f)
                            p.strokeWidth = (base * (0.35f + 0.65f * press)).coerceAtLeast(1f)
                            canvas.drawLine(ax, ay, bx, by, p)
                            ax = bx; ay = by
                            k += 3
                        }
                        if (item.pts.size == 3) {
                            // 单点（点触）：实心圆点
                            p.style = Paint.Style.FILL
                            canvas.drawCircle(ax, ay,
                                base * 0.5f * (0.35f + 0.65f * item.pts[2]), p)
                        }
                    }
                }
            }
        }
        canvas.restore()
    }

    // ---------------- 文字测量与竖排 ----------------

    private fun textPaintOf(item: Item, pageHPx: Float): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            color = parseColor(item.color, Color.BLACK)
            textSize = item.fontScale * pageHPx
            isFakeBoldText = item.bold
            textSkewX = if (item.italic) -0.25f else 0f
        }

    /** 横排：给定内容宽(px)返回所需内容高(px)。 */
    fun textLayoutHeightPx(item: Item, wPx: Float, pageHPx: Float): Float {
        val tp = textPaintOf(item, pageHPx)
        val layoutW = wPx.coerceAtLeast(tp.textSize)
        val sl = android.text.StaticLayout.Builder
            .obtain(item.text, 0, item.text.length, tp, layoutW.toInt().coerceAtLeast(1))
            .setAlignment(when (item.align) {
                1 -> android.text.Layout.Alignment.ALIGN_CENTER
                2 -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                else -> android.text.Layout.Alignment.ALIGN_NORMAL
            })
            .setLineSpacing(0f, item.lineHeight)
            .setIncludePad(false)
            .build()
        return sl.height.toFloat()
    }

    /** 竖排：给定内容高(px)返回所需内容宽(px)。 */
    fun verticalTextWidthPx(item: Item, hPx: Float, pageHPx: Float): Float {
        val size = item.fontScale * pageHPx
        val lineStep = size * item.lineHeight.coerceAtLeast(1f)
        val colW = size * 1.25f
        val perCol = (hPx / lineStep).toInt().coerceAtLeast(1)
        val cols = ((item.text.length + perCol - 1) / perCol).coerceAtLeast(1)
        return cols * colW
    }

    /** 竖排绘制：字符自上而下成列，列自右向左。 */
    private fun drawVerticalText(
        canvas: android.graphics.Canvas, item: Item, tp: TextPaint,
        fl: Float, fT: Float, fw: Float, fh: Float
    ) {
        val size = tp.textSize
        val lineStep = size * item.lineHeight.coerceAtLeast(1f)
        val colW = size * 1.25f
        val perCol = (fh / lineStep).toInt().coerceAtLeast(1)
        val len = item.text.length
        val cols = ((len + perCol - 1) / perCol).coerceAtLeast(1)
        // 列区域整体居中于内容框
        val blockW = cols * colW
        val startX = fl + (fw + blockW) / 2f - colW / 2f   // 第一列（最右）中心
        tp.textAlign = Paint.Align.CENTER
        val fm = tp.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2f
        var idx = 0
        var col = 0
        while (idx < len) {
            val cx = startX - col * colW
            var row = 0
            while (idx < len && row < perCol) {
                val ch = item.text.substring(idx, idx + 1)
                canvas.drawText(ch, cx, fT + (row + 0.5f) * lineStep + baseline, tp)
                idx++; row++
            }
            col++
        }
    }

    // ---------------- 统一装裱（照片与文字共用） ----------------

    class Framing(
        val matStyle: String?, val matColor: String?,
        val frameStyle: String?, val frameColor: String?,
        val matRect: RectF, val frameRect: RectF,
        val outerL: Float, val outerT: Float, val outerW: Float, val outerH: Float
    )

    private val matDefaultColor = mapOf(
        "paper" to "#F7F4EC", "wood" to "#9A6B44",
        "metal" to "#C9CBD3", "acrylic" to "#FFFFFF"
    )

    /**
     * 计算装裱几何：衬材在内容外延 matPad（可锁定比例），
     * 边框宽度按 min(衬宽,衬高) 比例围在衬材外围。
     */
    fun computeFraming(item: Item, fl: Float, fT: Float, fw: Float, fh: Float): Framing? {
        val hasMat = item.matStyle != null
        val hasFrame = item.frameStyle != null
        if (!hasMat && !hasFrame) return null
        var l = fl; var t = fT; var r = fl + fw; var b = fT + fh
        if (hasMat) {
            val pad = item.matPad * Math.min(fw, fh)
            l -= pad; t -= pad; r += pad; b += pad
            if (item.matRatio > 0.01f) {
                // 锁定衬材比例：以覆盖内容为原则扩展（居中）
                val cw = r - l; val ch = b - t
                if (cw / ch > item.matRatio) {
                    val nh = cw / item.matRatio
                    t -= (nh - ch) / 2f; b += (nh - ch) / 2f
                } else {
                    val nw = ch * item.matRatio
                    l -= (nw - cw) / 2f; r += (nw - cw) / 2f
                }
            }
        }
        val matRect = RectF(l, t, r, b)
        val fwid = if (hasFrame) item.frameW * Math.min(r - l, b - t) else 0f
        val frameRect = RectF(l - fwid, t - fwid, r + fwid, b + fwid)
        return Framing(item.matStyle, item.matColor, item.frameStyle, item.frameColor,
            matRect, frameRect, frameRect.left, frameRect.top,
            frameRect.width(), frameRect.height())
    }

    private fun drawFraming(canvas: android.graphics.Canvas, fr: Framing) {
        val m = Math.min(fr.frameRect.width(), fr.frameRect.height())
        val radius = m * 0.03f
        if (fr.frameStyle != null)
            drawMaterialFill(canvas, fr.frameStyle, fr.frameColor, fr.frameRect, radius)
        if (fr.matStyle != null)
            drawMaterialFill(canvas, fr.matStyle, fr.matColor, fr.matRect, radius * 0.7f)
    }

    /** 材质填充：纸张 / 木板 / 金属 / 亚克力，颜色可覆盖。 */
    private fun drawMaterialFill(
        canvas: android.graphics.Canvas, style: String, colorHex: String?,
        rect: RectF, r: Float
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = parseColor(colorHex ?: (matDefaultColor[style] ?: "#FFFFFF"), Color.WHITE)
        val hairline = Math.max(1f, Math.min(rect.width(), rect.height()) * 0.005f)
        when (style) {
            "metal" -> {
                p.shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    intArrayOf(
                        androidx.core.graphics.ColorUtils.blendARGB(base, Color.WHITE, 0.30f),
                        androidx.core.graphics.ColorUtils.blendARGB(base, Color.BLACK, 0.22f),
                        androidx.core.graphics.ColorUtils.blendARGB(base, Color.WHITE, 0.18f),
                        androidx.core.graphics.ColorUtils.blendARGB(base, Color.BLACK, 0.28f)),
                    floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, r, r, p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = hairline
                p.color = 0x33000000
                canvas.drawRoundRect(rect, r, r, p)
            }
            "wood" -> {
                p.shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    androidx.core.graphics.ColorUtils.blendARGB(base, Color.WHITE, 0.18f),
                    androidx.core.graphics.ColorUtils.blendARGB(base, Color.BLACK, 0.22f),
                    Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, r, r, p)
                p.shader = null
                // 木纹：数条半透明深色水平细纹（裁切在圆角内）
                canvas.save()
                canvas.clipPath(android.graphics.Path().apply {
                    addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
                })
                val grain = Paint(Paint.ANTI_ALIAS_FLAG)
                grain.color = 0x2E000000
                grain.strokeWidth = Math.max(1f, rect.height() * 0.012f)
                var y = rect.top + rect.height() * 0.18f
                var i = 0
                while (y < rect.bottom) {
                    canvas.drawLine(rect.left, y, rect.right,
                        y + if (i % 2 == 0) rect.height() * 0.02f else -rect.height() * 0.02f, grain)
                    y += rect.height() * 0.22f; i++
                }
                canvas.restore()
            }
            "acrylic" -> {
                p.color = (base and 0x00FFFFFF) or 0x59000000
                canvas.drawRoundRect(rect, r, r, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = hairline * 1.5f
                p.color = 0x8CFFFFFF.toInt()
                canvas.drawRoundRect(rect, r, r, p)
            }
            else -> {  // paper
                p.color = base
                canvas.drawRoundRect(rect, r, r, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = hairline
                p.color = 0x22000000
                canvas.drawRoundRect(rect, r, r, p)
            }
        }
    }

    private fun drawShadow(canvas: android.graphics.Canvas, l: Float, t: Float, w: Float, h: Float, radius: Float) {
        val sh = Paint(Paint.ANTI_ALIAS_FLAG)
        sh.color = 0x33000000
        sh.maskFilter = BlurMaskFilter(radius * 0.6f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(l + radius * 0.3f, t + radius * 0.5f, l + w + radius * 0.3f, t + h + radius * 0.5f, sh)
    }

    /** 裁切绘制：cropZoom + 裁切中心映射到框中心，clamp 保证覆盖。 */
    fun drawCroppedBitmap(
        canvas: android.graphics.Canvas, bmp: Bitmap, item: Item,
        fl: Float, fT: Float, fw: Float, fh: Float, filter: Boolean = true
    ) {
        val baseScale = Math.max(fw / bmp.width, fh / bmp.height)
        val scale = baseScale * item.cropZoom
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val cxImg = item.cropCx * dw
        val cyImg = item.cropCy * dh
        var dx = fl + fw / 2f - cxImg
        var dy = fT + fh / 2f - cyImg
        dx = Math.max(fl - (dw - fw), Math.min(fl, dx))
        dy = Math.max(fT - (dh - fh), Math.min(fT, dy))
        if (dw < fw) dx = fl + (fw - dw) / 2f
        if (dh < fh) dy = fT + (fh - dh) / 2f
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), p)
    }

    /** 渲染整页到位图（缩略图用）。 */
    fun renderThumb(ctx: Context, project: Project, page: Page, wPx: Int, hPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(1), hPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.WHITE)
        drawPage(ctx, project, page, c, 0f, 0f, wPx.toFloat(), hPx.toFloat())
        return bmp
    }
}

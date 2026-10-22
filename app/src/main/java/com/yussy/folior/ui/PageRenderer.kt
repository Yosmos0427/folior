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
                val frame = item.frameStyle
                if (isExhibition || frame != null) drawShadow(canvas, fl, fT, fw, fh, 18f)
                when (frame) {
                    "mat" -> {
                        // 卡纸装裱：四周留白衬纸
                        val m = Math.min(fw, fh) * 0.055f
                        p.style = Paint.Style.FILL
                        p.color = 0xFFFDFCF8.toInt()
                        canvas.drawRect(fl - m, fT - m, fl + fw + m, fT + fh + m, p)
                    }
                    "frame" -> {
                        // 木框装裱
                        val t = Math.min(fw, fh) * 0.03f
                        p.style = Paint.Style.FILL
                        p.color = 0xFF2B2A26.toInt()
                        canvas.drawRect(fl - t, fT - t, fl + fw + t, fT + fh + t, p)
                    }
                }
                canvas.save()
                canvas.clipRect(fl, fT, fl + fw, fT + fh)
                drawCroppedBitmap(canvas, bmp, item, fl, fT, fw, fh, highQuality)
                canvas.restore()
                if (frame != null) {
                    // 装裱内侧细边线
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = Math.max(1f, Math.min(fw, fh) * 0.004f)
                    p.color = 0x2E000000
                    canvas.drawRect(fl, fT, fl + fw, fT + fh, p)
                }
                if (item.frameStroke) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = Math.max(1f, wPx * 0.0015f)
                    p.color = 0x33000000
                    canvas.drawRect(fl, fT, fl + fw, fT + fh, p)
                }
            }
            Item.TYPE_TEXT -> {
                // 背景材质（在文字之下）
                item.textBg?.let { bg -> drawTextBg(canvas, bg, fl, fT, fw, fh) }
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

    /** 文字背景材质：纸张 / 金属 / 木板 / 亚克力。 */
    private fun drawTextBg(canvas: android.graphics.Canvas, kind: String,
                           l: Float, t: Float, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Math.min(w, h) * 0.06f
        val rect = RectF(l, t, l + w, t + h)
        when (kind) {
            "paper" -> {
                p.color = 0xFFF7F4EC.toInt()
                canvas.drawRoundRect(rect, r, r, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = Math.max(1f, Math.min(w, h) * 0.006f)
                p.color = 0x26000000
                canvas.drawRoundRect(rect, r, r, p)
            }
            "metal" -> {
                p.shader = LinearGradient(
                    l, t, l, t + h,
                    intArrayOf(0xFFEDEEF2.toInt(), 0xFFC2C4CC.toInt(),
                               0xFFE4E5EA.toInt(), 0xFFAFB2BC.toInt()),
                    floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, r, r, p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = Math.max(1f, Math.min(w, h) * 0.005f)
                p.color = 0x33000000
                canvas.drawRoundRect(rect, r, r, p)
            }
            "wood" -> {
                p.shader = LinearGradient(
                    l, t, l, t + h,
                    intArrayOf(0xFF9A6B44.toInt(), 0xFF7C5232.toInt()),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, r, r, p)
                // 木纹：数条半透明深色水平细纹
                canvas.save()
                canvas.clipPath(android.graphics.Path().apply {
                    addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
                })
                val grain = Paint(Paint.ANTI_ALIAS_FLAG)
                grain.color = 0x2E000000
                grain.strokeWidth = Math.max(1f, h * 0.012f)
                var y = t + h * 0.18f
                var i = 0
                while (y < t + h) {
                    canvas.drawLine(l, y, l + w,
                        y + if (i % 2 == 0) h * 0.02f else -h * 0.02f, grain)
                    y += h * 0.22f; i++
                }
                canvas.restore()
                p.shader = null
            }
            "acrylic" -> {
                p.color = 0x59FFFFFF
                canvas.drawRoundRect(rect, r, r, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = Math.max(1f, Math.min(w, h) * 0.008f)
                p.color = 0x8CFFFFFF.toInt()
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

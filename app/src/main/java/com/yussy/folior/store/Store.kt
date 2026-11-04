package com.yussy.folior.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.yussy.folior.model.Project
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 存储层：
 * - 项目 JSON 存于 filesDir/projects/<id>.folio
 * - 图片复制进应用私有目录 filesDir/images/<imgId>.<ext>（自包含，可整体打包）
 * - .folio 导出 = zip(project.json + images/)
 */
object Store {

    fun projectsDir(c: Context): File = File(c.filesDir, "projects").apply { mkdirs() }
    fun imagesDir(c: Context): File = File(c.filesDir, "images").apply { mkdirs() }

    // ---------- 项目 CRUD ----------

    // 项目解析缓存：id → (文件mtime, Project)。mtime 未变直接复用，首页不再反复全量解析。
    private val parseCache = HashMap<String, Pair<Long, Project>>()

    fun listProjects(c: Context): MutableList<Project> {
        val list = mutableListOf<Project>()
        projectsDir(c).listFiles { f -> f.name.endsWith(".folio") }?.forEach { f ->
            val id = f.name.removeSuffix(".folio")
            val mtime = f.lastModified()
            val cached = parseCache[id]
            if (cached != null && cached.first == mtime) {
                list.add(cached.second)
                return@forEach
            }
            try {
                val p = Project.fromJson(JSONObject(f.readText()))
                parseCache[id] = mtime to p
                list.add(p)
            } catch (_: Exception) {}
        }
        list.sortByDescending { File(projectsDir(c), it.id + ".folio").lastModified() }
        return list
    }

    /** 原子写入：先写临时文件再重命名，中途崩溃不会损坏已有工程 */
    fun save(c: Context, p: Project) {
        try {
            val target = File(projectsDir(c), p.id + ".folio")
            val tmp = File(projectsDir(c), p.id + ".tmp")
            tmp.writeText(p.toJson().toString())
            if (!tmp.renameTo(target)) {
                // 个别文件系统 rename 失败：回退直写
                target.writeText(p.toJson().toString())
                tmp.delete()
            }
        } catch (_: Exception) {}
    }

    fun delete(c: Context, id: String) {
        File(projectsDir(c), "$id.folio").delete()
        parseCache.remove(id)
    }

    /**
     * 孤儿图片清理：扫描所有工程的全部引用，删除不再被任何工程引用的
     * 图片文件与 PPI 元数据（返回删除的文件数）。
     * 建议在首页启动（后台线程）与删除工程后调用；编辑器运行期间勿调。
     */
    @Synchronized
    fun gcImages(c: Context): Int {
        val referenced = HashSet<String>()
        projectsDir(c).listFiles { f -> f.name.endsWith(".folio") }?.forEach { f ->
            try {
                val p = Project.fromJson(JSONObject(f.readText()))
                p.pages.forEach { pg -> pg.items.forEach { it.imgId?.let(referenced::add) } }
            } catch (_: Exception) {}
        }
        var removed = 0
        val meta = loadMeta(c)
        var metaDirty = false
        imagesDir(c).listFiles()?.forEach { f ->
            if (f.isDirectory) return@forEach
            val name = f.name
            if (name == "meta.json") return@forEach
            val id = name.substringBeforeLast('.')
            if (id !in referenced) {
                if (f.delete()) removed++
                if (meta.remove(id) != null) metaDirty = true
            }
        }
        // 工作图（proxy）跟随原图存亡
        proxyDir(c).listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name.substringBeforeLast('.') !in referenced) {
                if (f.delete()) removed++
            }
        }
        if (metaDirty) saveMeta(c)
        return removed
    }

    // ---------- 图片 ----------

    /**
     * 把相册图片复制进私有目录，返回 imgId。
     *
     * 分辨率策略（关系到放映里 100% 缩放能看到的真实细节）：
     * - 方向正常（EXIF orientation = 1）的图片**直接按原字节复制**，不解码、不重编码，
     *   像素与画质 100% 原样保留，导出 PDF 与 100% 放映都拿到的是原始数据。
     * - 需要旋正的图片（竖拍常见）才解码：像素总数超过 [maxPixels] 时按 2 的幂减半，
     *   旋正后按 JPEG 96 重编码。OOM 时自动再减半重试，不会整张导入失败。
     * - meta 里记录的始终是**原始像素**（解码前读到的 outWidth/outHeight）与 PPI，
     *   展览模式按「像素 ÷ PPI」换算实际尺寸时不受减半影响。
     */
    fun importImage(c: Context, uri: Uri, maxPixels: Long = 24_000_000L): String? {
        return try {
            val cr = c.contentResolver
            // 1) 只读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // 2) EXIF：方向 + 分辨率（PPI）
            var orientation = ExifInterface.ORIENTATION_NORMAL
            var ppi = 300f
            try {
                cr.openInputStream(uri)?.use { ins ->
                    val exif = ExifInterface(ins)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    // X/Y_RESOLUTION 形如 "72/1"（有理数）
                    val xRes = exif.getAttribute(ExifInterface.TAG_X_RESOLUTION)
                    val unit = exif.getAttributeInt(
                        ExifInterface.TAG_RESOLUTION_UNIT, ExifInterface.RESOLUTION_UNIT_INCHES.toInt())
                    if (xRes != null) {
                        val v = xRes.split("/").let { p ->
                            p[0].toFloatOrNull()?.let { num ->
                                if (p.size > 1) p[1].toFloatOrNull()?.let { den ->
                                    if (den != 0f) num / den else null
                                } ?: num else num
                            }
                        }
                        if (v != null && v > 0f) {
                            ppi = when (unit) {
                                ExifInterface.RESOLUTION_UNIT_CENTIMETERS.toInt() -> v * 2.54f
                                else -> v   // 英寸或无单位，按英寸处理
                            }
                        }
                    }
                    // 合理性判定：
                    // 低于 100dpi（典型 72dpi）是相机固件的历史默认值，不代表真实打印分辨率 → 按 300ppi
                    // 超过 1200dpi 同样视为异常元数据
                    if (ppi < 100f || ppi > 1200f) ppi = 300f
                }
            } catch (_: Exception) {}

            val id = UUID.randomUUID().toString()
            val dir = imagesDir(c)
            // meta 里的宽高要和磁盘上那张图**一致**：旋正过的宽高互换，
            // 否则展览摆放与 100% 缩放的换算会横竖颠倒。
            val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE
            val ow = if (swapped) bounds.outHeight else bounds.outWidth
            val oh = if (swapped) bounds.outWidth else bounds.outHeight

            // 3) 无需旋正：原字节复制（保真且最快）
            val uprightBaked = orientation == ExifInterface.ORIENTATION_NORMAL ||
                    orientation == ExifInterface.ORIENTATION_UNDEFINED
            if (uprightBaked) {
                val ext = extensionOf(cr, uri)
                if (ext != null) {
                    val out = File(dir, "$id.$ext")
                    if (copyBytes(cr, uri, out)) {
                        putPhotoMeta(c, id, ow, oh, ppi)
                        ensureProxy(c, id)          // 顺手备好工作图，之后编辑/放映都不用等
                        return id
                    }
                    out.delete()
                }
            }

            // 4) 需要旋正（或原格式不认识）：解码 → 旋正 → 高质量重编码
            var budget = maxPixels
            var bmp: Bitmap? = null
            while (budget >= 1_000_000L) {
                bmp = decodeBounded(cr, uri, bounds, budget)
                if (bmp != null) break
                budget /= 4          // OOM：再减半采样重试
            }
            if (bmp == null) return null
            val upright = applyExifOrientation(bmp, orientation)
            File(dir, "$id.jpg").outputStream().use { fo ->
                upright.compress(Bitmap.CompressFormat.JPEG, 96, fo)
            }
            if (upright !== bmp) upright.recycle()
            bmp.recycle()
            putPhotoMeta(c, id, ow, oh, ppi)
            ensureProxy(c, id)
            id
        } catch (e: Exception) { null }
    }

    /** 原始字节复制；成功且非空返回 true。 */
    private fun copyBytes(
        cr: android.content.ContentResolver, uri: Uri, out: File
    ): Boolean = try {
        cr.openInputStream(uri)?.use { ins ->
            out.outputStream().use { fos -> ins.copyTo(fos) }
        }
        out.length() > 0
    } catch (_: Exception) { false }

    /** 按像素总数预算选 2 的幂采样率解码；OOM 返回 null 由调用方降档。 */
    private fun decodeBounded(
        cr: android.content.ContentResolver, uri: Uri,
        bounds: BitmapFactory.Options, maxPixels: Long
    ): Bitmap? = try {
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) *
            (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
        cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }

    /**
     * 从 mime 或路径推断可识别的扩展名；BitmapFactory 能解、且 imageFile() 会找的才返回。
     * 认不出来返回 null，交给重编码分支。
     */
    private fun extensionOf(cr: android.content.ContentResolver, uri: Uri): String? {
        val mime = try { cr.getType(uri) } catch (_: Exception) { null }
        when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> return "jpg"
            "image/png" -> return "png"
            "image/webp" -> return "webp"
            "image/heic", "image/heif" -> return "heic"
        }
        val name = uri.lastPathSegment?.lowercase() ?: return null
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "jpg"
            name.endsWith(".png") -> "png"
            name.endsWith(".webp") -> "webp"
            name.endsWith(".heic") || name.endsWith(".heif") -> "heic"
            else -> null
        }
    }

    /** 按 EXIF 方向旋转/翻转到正向，避免竖拍照片导入后躺倒。 */
    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: Exception) { src }
    }

    fun imageFile(c: Context, imgId: String): File? {
        for (ext in arrayOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
            val f = File(imagesDir(c), "$imgId.$ext")
            if (f.exists()) return f
        }
        return null
    }

    // ---------- 照片物理元数据（原始像素 × PPI） ----------

    private var metaCache: MutableMap<String, org.json.JSONObject>? = null

    private fun metaFile(c: Context): File = File(imagesDir(c), "meta.json")

    @Synchronized
    private fun loadMeta(c: Context): MutableMap<String, org.json.JSONObject> {
        metaCache?.let { return it }
        val m = mutableMapOf<String, org.json.JSONObject>()
        val f = metaFile(c)
        if (f.exists()) {
            try {
                val o = org.json.JSONObject(f.readText())
                o.keys().forEach { k -> m[k] = o.getJSONObject(k) }
            } catch (_: Exception) {}
        }
        metaCache = m
        return m
    }

    @Synchronized
    private fun saveMeta(c: Context) {
        val m = metaCache ?: return
        try {
            val o = org.json.JSONObject()
            m.forEach { (k, v) -> o.put(k, v) }
            metaFile(c).writeText(o.toString())
        } catch (_: Exception) {}
    }

    @Synchronized
    fun putPhotoMeta(c: Context, imgId: String, origW: Int, origH: Int, ppi: Float) {
        val m = loadMeta(c)
        m[imgId] = org.json.JSONObject()
            .put("w", origW).put("h", origH).put("ppi", ppi.toDouble())
        saveMeta(c)
    }

    /** 返回 (原始宽px, 原始高px, PPI)；无记录时回退为文件尺寸 + 300ppi */
    fun photoMeta(c: Context, imgId: String): Triple<Int, Int, Float> {
        loadMeta(c)[imgId]?.let {
            return Triple(it.optInt("w"), it.optInt("h"), it.optDouble("ppi", 300.0).toFloat())
        }
        val f = imageFile(c, imgId) ?: return Triple(0, 0, 300f)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        return Triple(opts.outWidth, opts.outHeight, 300f)
    }

    // ---------------- 工作图（proxy）与解码 ----------------

    /** 解码源：工作图（中等尺寸 JPEG 副本）/ 原始文件 */
    const val SRC_PROXY = 0
    const val SRC_ORIGINAL = 1

    /** 工作图长边上限。原图本身不超过这个尺寸时直接用原图，不另存副本。 */
    private const val PROXY_MAX_DIM = 1600
    private const val PROXY_QUALITY = 88

    fun proxyDir(c: Context): File = File(imagesDir(c), "proxy").apply { mkdirs() }

    fun proxyFile(c: Context, imgId: String): File = File(proxyDir(c), "$imgId.jpg")

    private val proxyLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * 生成（或复用）工作图。原图本身就不大时直接返回原图，不多存一份。
     * 生成失败也返回原图——宁可慢一点，不能没有。
     * 锁按 imgId 分开：导入时多张并行生成互不排队。
     */
    fun ensureProxy(c: Context, imgId: String): File? {
        val orig = imageFile(c, imgId) ?: return null
        val f = proxyFile(c, imgId)
        if (f.exists() && f.length() > 0L) return f
        val lock = proxyLocks.computeIfAbsent(imgId) { Any() }
        synchronized(lock) {
            if (f.exists() && f.length() > 0L) return f
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(orig.absolutePath, b)
            if (b.outWidth in 1..PROXY_MAX_DIM && b.outHeight in 1..PROXY_MAX_DIM) return orig
            val bmp = decodeSampled(orig, PROXY_MAX_DIM) ?: return orig
            return try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, PROXY_QUALITY, it) }
                if (f.length() > 0L) f else orig
            } catch (_: Exception) {
                f.delete(); orig
            } finally {
                bmp.recycle()
            }
        }
    }

    // 三份缓存分开计量：一张原图动辄 30–60MB，混在一起会把缩略图整片挤掉，
    // 于是每次刷新都重新解码，越翻越卡。
    private fun sizeOfBitmap(key: String, value: Bitmap) = value.byteCount.coerceAtLeast(1)

    // 上限按可用堆分配：large 必须能装下「一张 24MP 原图」（约 96MB），
    // 否则放进去立刻被淘汰，等于每一帧都重新解码——越放大越卡就是这么来的。
    private val heapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
    private val memThumb = object : LruCache<String, Bitmap>(
        (heapMb / 32).coerceIn(8, 16) * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = sizeOfBitmap(key, value)
    }
    private val memWork = object : LruCache<String, Bitmap>(
        (heapMb / 6).coerceIn(24, 64) * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = sizeOfBitmap(key, value)
    }
    private val memLarge = object : LruCache<String, Bitmap>(
        (heapMb / 3).coerceIn(96, 160) * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = sizeOfBitmap(key, value)
    }

    private fun cacheFor(src: Int) = if (src == SRC_ORIGINAL) memLarge else memWork

    private fun cacheKey(imgId: String, maxDim: Int, src: Int, prefix: String) =
        "$prefix$imgId@$maxDim@$src"

    /**
     * 通用下采样解码（先读尺寸算 sample，再真正解码）。OOM 返回 null 由调用方降档。
     *
     * 除了长边，还卡一道像素总量：5000 万像素的手机原图按长边算出来是 8160px，
     * 一次解码就要 200MB，会直接把进程打崩。超过 [maxPixels] 时再减半一级。
     */
    private fun decodeSampled(f: File, maxDim: Int, maxPixels: Long = 24_000_000L): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: OutOfMemoryError) { null } catch (e: Exception) { null }

    /** 工作图文件；还没生成时先用原图顶上，并把生成任务丢给后台。 */
    private fun workFileFor(c: Context, imgId: String): File? {
        val f = proxyFile(c, imgId)
        if (f.exists() && f.length() > 0L) return f
        val orig = imageFile(c, imgId) ?: return null
        if (!inFlight.add("proxy:$imgId")) return orig
        val app = c.applicationContext
        decodePool.execute {
            try { ensureProxy(app, imgId) } catch (_: Throwable) {
            } finally { inFlight.remove("proxy:$imgId") }
        }
        return orig
    }

    private fun decodeImage(
        c: Context, imgId: String, maxDim: Int, src: Int,
        cache: LruCache<String, Bitmap> = cacheFor(src), prefix: String = ""
    ): Bitmap? {
        val key = cacheKey(imgId, maxDim, src, prefix)
        cache.get(key)?.let { return it }
        val f: File = (if (src == SRC_ORIGINAL) imageFile(c, imgId) else workFileFor(c, imgId))
            ?: return null
        // 目标尺寸解不出来（多为 OOM）就逐级减半，宁可略糊也不要空块
        var dim = maxDim
        while (dim >= 256) {
            val bmp = decodeSampled(f, dim)
            if (bmp != null) {
                if (bmp.width.coerceAtLeast(bmp.height) <= maxDim) cache.put(key, bmp)
                return bmp
            }
            dim /= 2
        }
        return null
    }

    /**
     * 缓存里同一张图 ≤ maxDim 的最大的那一版，没有则 null。
     * 用 snapshot 而不是固定档位查表——渲染请求的尺寸是任意整数（按元素占版宽度算出来的）。
     */
    private fun bestCached(
        cache: LruCache<String, Bitmap>, imgId: String, maxDim: Int, src: Int, prefix: String = ""
    ): Bitmap? {
        val tag = "$prefix$imgId@"
        var best: Bitmap? = null
        var bestDim = -1
        for ((k, v) in cache.snapshot()) {
            if (!k.startsWith(tag) || !k.endsWith("@$src")) continue
            val d = k.removePrefix(tag).substringBeforeLast('@').toIntOrNull() ?: continue
            if (d <= maxDim && d > bestDim) { bestDim = d; best = v }
        }
        return best
    }

    /**
     * 取图（带缓存）。
     * - 目标尺寸已缓存 → 直接给
     * - 只有更小的版本 → **先给小的，后台补大的**（缩放时不卡，随后自动刷新一次）
     * - 什么都没有 → 同步解码，保证第一帧就是对的
     */
    /**
     * 工作图只有两档：小图 640、全图 1600。请求按档归一，否则编辑区按元素宽度算出来的
     * 1024 / 2048 会把同一张 1600 的解码结果各存一份，缓存白白翻倍。
     */
    private fun normDim(maxDim: Int, src: Int): Int =
        if (src != SRC_PROXY) maxDim
        else if (maxDim <= 640) 640 else PROXY_MAX_DIM

    fun decodeFor(c: Context, imgId: String, maxDim0: Int, src0: Int): Bitmap? {
        // 需要的像素没超过工作图时，读原图是白费力气：放映「适应屏幕」只要 800px 上下，
        // 工作图 1600 已经够了。只有放大到 1600 以上才真的动原图。
        val src = if (src0 == SRC_ORIGINAL && maxDim0 <= PROXY_MAX_DIM) SRC_PROXY else src0
        val maxDim = normDim(maxDim0, src)
        val cache = cacheFor(src)
        cache.get(cacheKey(imgId, maxDim, src, ""))?.let { return it }
        val smaller = bestCached(cache, imgId, maxDim, src)
        if (smaller != null) {
            requestDecode(c, imgId, maxDim, src)
            return smaller
        }
        // 放映要原图、但原图还没解过：先用工作图那版顶上，别让屏幕空等一下
        if (src == SRC_ORIGINAL) {
            bestCached(memWork, imgId, maxDim, SRC_PROXY)?.let {
                requestDecode(c, imgId, maxDim, src)
                return it
            }
        }
        return decodeImage(c, imgId, maxDim, src)
    }

    /** 兼容旧调用：默认走工作图。 */
    fun decodeFull(c: Context, imgId: String, maxDim: Int = 2048): Bitmap? =
        decodeFor(c, imgId, maxDim, SRC_PROXY)

    /** 缩略图（独立缓存，不被大图挤掉）。 */
    fun decodeThumb(c: Context, imgId: String, maxDim: Int = 320): Bitmap? {
        memThumb.get(cacheKey(imgId, maxDim, SRC_PROXY, "t"))?.let { return it }
        return decodeImage(c, imgId, maxDim, SRC_PROXY, memThumb, "t")
    }

    // ---- 后台解码：不占 UI 线程，完成后通知订阅者重绘 ----

    private val decodePool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val decodeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addDecodeListener(l: () -> Unit) {
        if (l !in decodeListeners) decodeListeners.add(l)
    }

    fun removeDecodeListener(l: () -> Unit) {
        decodeListeners.remove(l)
    }

    fun requestDecode(c: Context, imgId: String, maxDim0: Int, src0: Int) {
        val src = if (src0 == SRC_ORIGINAL && maxDim0 <= PROXY_MAX_DIM) SRC_PROXY else src0
        val maxDim = normDim(maxDim0, src)
        val key = cacheKey(imgId, maxDim, src, "")
        if (!inFlight.add(key)) return
        val app = c.applicationContext
        decodePool.execute {
            try {
                decodeImage(app, imgId, maxDim, src)
            } catch (_: Throwable) {
            } finally {
                inFlight.remove(key)
            }
            mainHandler.post { decodeListeners.forEach { it() } }
        }
    }

    /** 预取：翻页/放大前先把目标页的图解码好，翻过去就不用等。 */
    fun prefetch(c: Context, imgIds: Collection<String>, maxDim: Int, src: Int) {
        for (id in imgIds) requestDecode(c, id, maxDim, src)
    }

    // ---------- .folio 包（zip）导出 / 导入 ----------

    fun exportBundle(c: Context, p: Project, out: java.io.OutputStream): Boolean {
        return try {
            ZipOutputStream(out).use { zos ->
                zos.putNextEntry(ZipEntry("project.json"))
                zos.write(p.toJson().toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                val ids = p.pages.flatMap { pg -> pg.items.mapNotNull { it.imgId } }.toSet()
                for (imgId in ids) {
                    val f = imageFile(c, imgId) ?: continue
                    zos.putNextEntry(ZipEntry("images/$imgId.${f.extension}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                val meta = File(imagesDir(c), "meta.json")
                if (meta.exists()) {
                    zos.putNextEntry(ZipEntry("images/meta.json"))
                    meta.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) { false }
    }

    fun importBundle(c: Context, input: java.io.InputStream): Project? {
        return try {
            val tmpImgs = mutableListOf<Pair<File, String>>()
            var projectJson: String? = null
            var incomingMeta: JSONObject? = null
            ZipInputStream(input).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    when {
                        e.name == "project.json" -> projectJson = zis.readBytes().toString(Charsets.UTF_8)
                        e.name == "images/meta.json" ->
                            incomingMeta = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        e.name.startsWith("images/") -> {
                            val name = e.name.removePrefix("images/")
                            val f = File(imagesDir(c), name)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            tmpImgs.add(f to name)
                        }
                    }
                    e = zis.nextEntry
                }
            }
            if (projectJson == null) {
                tmpImgs.forEach { it.first.delete() }; return null
            }
            metaCache = null   // 重载元数据
            // 随包的 meta.json 要**合并**而不是覆盖——否则会把本机其他照片的原始像素/PPI 记录清空
            incomingMeta?.let { inc ->
                val m = loadMeta(c)
                inc.keys().forEach { k -> m[k] = inc.getJSONObject(k) }
                saveMeta(c)
            }
            // imgId 冲突处理：简单起见直接使用（UUID 冲突概率极低）
            Project.fromJson(JSONObject(projectJson!!)).also { save(c, it) }
        } catch (e: Exception) { null }
    }
}

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
            val name = f.name
            if (name == "meta.json") return@forEach
            val id = name.substringBeforeLast('.')
            if (id !in referenced) {
                if (f.delete()) removed++
                if (meta.remove(id) != null) metaDirty = true
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

    // 内存位图缓存：按字节计量（48MB 上限），整图与缩略图共用；
    // 此前按"张数(64)"计量，大量大图时可能堆爆。
    private val memCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount.coerceAtLeast(1)
    }

    /** 通用下采样解码（先读尺寸算 sample，再真正解码）。OOM 返回 null 由调用方降档。 */
    private fun decodeSampled(f: File, maxDim: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: OutOfMemoryError) { null } catch (e: Exception) { null }

    /** 解码整图（带缓存与下采样），用于画布渲染与 PDF 导出。 */
    fun decodeFull(c: Context, imgId: String, maxDim: Int = 2048): Bitmap? =
        decodeCached(c, imgId, maxDim)

    /** 解码缩略图（独立 key 空间），用于页面条。 */
    fun decodeThumb(c: Context, imgId: String, maxDim: Int = 320): Bitmap? =
        decodeCached(c, imgId, maxDim, prefix = "t")

    @Synchronized
    private fun decodeCached(c: Context, imgId: String, maxDim: Int, prefix: String = ""): Bitmap? {
        val key = "$prefix$imgId@$maxDim"
        memCache.get(key)?.let { return it }
        val f = imageFile(c, imgId) ?: return null
        // 目标尺寸解不出来（多为 OOM）就逐级减半，宁可略糊也不要空块
        var dim = maxDim
        while (dim >= 256) {
            val bmp = decodeSampled(f, dim)
            if (bmp != null) {
                if (bmp.width.coerceAtLeast(bmp.height) <= maxDim) memCache.put(key, bmp)
                return bmp
            }
            dim /= 2
        }
        return null
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

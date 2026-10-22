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

    fun listProjects(c: Context): MutableList<Project> {
        val list = mutableListOf<Project>()
        projectsDir(c).listFiles { f -> f.name.endsWith(".folio") }?.forEach { f ->
            try { list.add(Project.fromJson(JSONObject(f.readText()))) } catch (_: Exception) {}
        }
        list.sortByDescending { File(projectsDir(c), it.id + ".folio").lastModified() }
        return list
    }

    fun save(c: Context, p: Project) {
        File(projectsDir(c), p.id + ".folio").writeText(p.toJson().toString())
    }

    fun delete(c: Context, id: String) {
        File(projectsDir(c), "$id.folio").delete()
    }

    // ---------- 图片 ----------

    /** 把相册图片复制进私有目录，返回 imgId。带下采样防止超大原图 OOM，并按 EXIF 摆正方向。 */
    fun importImage(c: Context, uri: Uri, maxDim: Int = 4096): String? {
        return try {
            val cr = c.contentResolver
            // 1) 只读尺寸，决定下采样率
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (Math.max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxDim) sample *= 2
            // 2) 读取 EXIF 方向 + 分辨率（PPI）
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
                    // 合理性钳制（异常元数据兜底）
                    if (ppi < 1f || ppi > 4000f) ppi = 300f
                }
            } catch (_: Exception) {}
            // 3) 真正解码
            val bmp = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val upright = applyExifOrientation(bmp, orientation)
            val id = UUID.randomUUID().toString()
            File(imagesDir(c), "$id.jpg").outputStream().use { fo ->
                upright.compress(Bitmap.CompressFormat.JPEG, 92, fo)
            }
            if (upright !== bmp) upright.recycle()
            bmp.recycle()
            putPhotoMeta(c, id, opts.outWidth, opts.outHeight, ppi)
            id
        } catch (e: Exception) { null }
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
        for (ext in arrayOf("jpg", "jpeg", "png", "webp")) {
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

    // 内存缩略图缓存
    private val memCache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    /** 解码整图（带下采样），用于画布渲染与 PDF 导出。 */
    fun decodeFull(c: Context, imgId: String, maxDim: Int = 2048): Bitmap? {
        val key = "$imgId@$maxDim"
        memCache.get(key)?.let { return it }
        val f = imageFile(c, imgId) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) memCache.put(key, bmp)
            bmp
        } catch (e: Exception) { null }
    }

    /** 解码缩略图，用于页面条。 */
    fun decodeThumb(c: Context, imgId: String, maxDim: Int = 320): Bitmap? {
        val key = "t$imgId@$maxDim"
        memCache.get(key)?.let { return it }
        val f = imageFile(c, imgId) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) memCache.put(key, bmp)
            bmp
        } catch (e: Exception) { null }
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
            ZipInputStream(input).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    when {
                        e.name == "project.json" -> projectJson = zis.readBytes().toString(Charsets.UTF_8)
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
            metaCache = null   // 重载元数据（meta.json 可能随包导入）
            // imgId 冲突处理：简单起见直接使用（UUID 冲突概率极低）
            Project.fromJson(JSONObject(projectJson!!)).also { save(c, it) }
        } catch (e: Exception) { null }
    }
}

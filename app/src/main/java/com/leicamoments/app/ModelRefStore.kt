package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 模型参考图的**缩略图**与「已排除」标记。
 *
 * 真正的统计量在 [CustomStyleDef.seeds]（约 18 KB/张，进 `custom_styles.json`）；
 * 这里只存给人看的 240px 缩略图与排除位，纯 UI 资产，不进备份、丢了不影响出图。
 *
 * 目录 `filesDir/model_refs/`：
 * - `<styleId>_<index>.jpg` —— 与 `def.seeds` **同序**（第 i 张缩略图对应 seeds[i]）
 * - `<styleId>.json` —— `{"disabled":[1,3]}`，下标同上
 */
internal object ModelRefStore {

    private const val DIR = "model_refs"

    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun jpg(ctx: Context, styleId: String, index: Int): File =
        File(dir(ctx), "${styleId}_$index.jpg")

    private fun meta(ctx: Context, styleId: String): File = File(dir(ctx), "$styleId.json")

    fun saveThumb(ctx: Context, styleId: String, index: Int, jpeg: ByteArray): Boolean =
        runCatching { jpg(ctx, styleId, index).writeBytes(jpeg) }.isSuccess

    /**
     * 解码参考图 → 最长边 240px → JPEG90。
     *
     * 缩略图既给人看，也是 A 路线拟合的原料，故尺寸/质量必须与详情页重建时一致。
     * 会做整图解码，禁止在主线程调用。
     */
    fun thumbJpeg(ctx: Context, uri: Uri): ByteArray? = runCatching {
        val resolver = ctx.applicationContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 320 && bounds.outHeight / (sample * 2) >= 320) sample *= 2
        val src = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val scale = 240f / max(src.width, src.height)
        val dst = if (scale < 1f) Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true
        ) else src
        val bos = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        if (dst !== src) dst.recycle()
        src.recycle()
        bos.toByteArray()
    }.getOrNull()

    fun thumbFile(ctx: Context, styleId: String, index: Int): File? =
        jpg(ctx, styleId, index).takeIf { it.isFile }

    /** M7.1：逐图风格指纹 e_i（n×36 fp32），供候选聚类。 */
    fun embFile(ctx: Context, styleId: String): File = File(dir(ctx), "${styleId}_emb.bin")

    fun saveEmb(ctx: Context, styleId: String, e: FloatArray): Boolean = runCatching {
        val bb = java.nio.ByteBuffer.allocate(e.size * 4)
        bb.asFloatBuffer().put(e)
        embFile(ctx, styleId).writeBytes(bb.array())
    }.isSuccess

    fun loadEmb(ctx: Context, styleId: String): FloatArray? = runCatching {
        val f = embFile(ctx, styleId)
        if (!f.isFile) return@runCatching null
        val bb = java.nio.ByteBuffer.wrap(f.readBytes())
        FloatArray(bb.remaining() / 4) { bb.float }
    }.getOrNull()

    /** 已落盘的缩略图数量（下标连续，遇到缺口即停；旧模型没有缩略图时为 0） */
    fun count(ctx: Context, styleId: String): Int {
        var i = 0
        while (jpg(ctx, styleId, i).isFile) i++
        return i
    }

    /**
     * 已落盘的全部缩略图（下标 → 文件，按下标升序），供分享打包使用。
     *
     * 与 [count] 不同：M4 起给老模型加图时缩略图从高下标开始落盘（前面没有文件），
     * 必须按实际文件收集，否则会漏掉这些图。
     */
    fun allThumbs(ctx: Context, styleId: String): List<Pair<Int, File>> = runCatching {
        val prefix = "${styleId}_"
        dir(ctx).listFiles()
            ?.mapNotNull { f ->
                if (!f.isFile || !f.name.startsWith(prefix) || !f.name.endsWith(".jpg")) {
                    return@mapNotNull null
                }
                val idx = f.name.substring(prefix.length, f.name.length - 4).toIntOrNull()
                    ?: return@mapNotNull null
                if (idx < 0) null else idx to f
            }
            ?.sortedBy { it.first }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 是否**有任意**缩略图。
     *
     * M4 起给老模型加图时，新图的缩略图会落在 `[旧张数, …)` 这个高下标区，
     * 前面没有文件 → [count] 恒为 0，但网格其实是有内容可画的，故单独判断。
     */
    fun anyThumb(ctx: Context, styleId: String): Boolean = runCatching {
        dir(ctx).listFiles()?.any { it.name.startsWith("${styleId}_") && it.name.endsWith(".jpg") } == true
    }.getOrDefault(false)

    fun disabled(ctx: Context, styleId: String): Set<Int> {
        return try {
            val f = meta(ctx, styleId)
            if (!f.isFile) return emptySet()
            val arr = JSONObject(f.readText()).optJSONArray("disabled") ?: return emptySet()
            val out = HashSet<Int>()
            for (k in 0 until arr.length()) {
                val v = arr.optInt(k, -1)
                if (v >= 0) out.add(v)
            }
            out
        } catch (t: Throwable) {
            // 标记损坏就当没有排除，最多多算几张参考图，不影响正确性
            emptySet()
        }
    }

    fun setDisabled(ctx: Context, styleId: String, indexes: Set<Int>) {
        runCatching {
            val arr = JSONArray()
            indexes.sorted().forEach { arr.put(it) }
            meta(ctx, styleId).writeText(JSONObject().put("disabled", arr).toString())
        }
    }

    /**
     * 池位置变了（M4 淘汰）后重排缩略图：[survivors] = 保留下来的**旧下标**（升序）。
     *
     * 先把幸存缩略图全读进内存再整体重写，避免「新下标 < 旧下标」时后写的把先写的覆盖掉。
     * @return 重排后的排除下标
     */
    fun remap(ctx: Context, styleId: String, survivors: List<Int>, oldDisabled: Set<Int>): Set<Int> {
        val bytes = survivors.map { idx -> thumbFile(ctx, styleId, idx)?.readBytes() }
        runCatching {
            dir(ctx).listFiles()
                ?.filter { it.name.startsWith("${styleId}_") && it.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
        }
        val newDisabled = HashSet<Int>()
        survivors.forEachIndexed { newIdx, oldIdx ->
            bytes[newIdx]?.let { saveThumb(ctx, styleId, newIdx, it) }
            if (oldIdx in oldDisabled) newDisabled.add(newIdx)
        }
        setDisabled(ctx, styleId, newDisabled)
        return newDisabled
    }

    // ---- 断点续跑（M4） ----

    private fun pendingFile(ctx: Context, styleId: String): File =
        File(dir(ctx), "${styleId}_pending.json")

    /**
     * 已分析、但还没提交进池的 seed。
     *
     * 只在「加图增强」过程中写：分析一张记一张，取消/失败后重新选同一批照片，
     * 已算过的直接复用，不再重复分析（分析是这个流程里最慢的一段）。
     */
    fun loadPending(ctx: Context, styleId: String): MutableList<RefSeed> {
        val f = pendingFile(ctx, styleId)
        if (!f.isFile) return mutableListOf()
        return try {
            SeedsStore.parseSeeds(JSONObject(f.readText()).optJSONArray("seeds") ?: JSONArray())
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun appendPending(ctx: Context, styleId: String, seed: RefSeed) {
        val list = loadPending(ctx, styleId)
        list.add(seed)
        while (list.size > ModelPool.MAX_PENDING) list.removeAt(0)
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(SeedsStore.seedJson(it)) }
            pendingFile(ctx, styleId).writeText(JSONObject().put("seeds", arr).toString())
        }
    }

    fun clearPending(ctx: Context, styleId: String) {
        runCatching { pendingFile(ctx, styleId).delete() }
    }

    /** 删除某模型的全部缩略图与标记（删模型时调用） */
    fun clear(ctx: Context, styleId: String) {
        runCatching {
            dir(ctx).listFiles()
                ?.filter { it.name == "$styleId.json" || it.name.startsWith("${styleId}_") }
                ?.forEach { it.delete() }
        }
    }
}

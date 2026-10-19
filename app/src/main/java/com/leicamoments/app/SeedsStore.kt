package com.leicamoments.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 参考图指纹池的独立存储（M3）。
 *
 * 拆出去的原因：一张 seed 含 18 KB 残差网格（base64 后 ~24 KB），
 * 模型池到 120 张就是 3 MB，全塞进 `custom_styles.json` 会让启动时
 * 「读一个小 JSON」变成读几十 MB 并全部常驻内存。
 *
 * 现在：`custom_styles.json` 只留 `seedCount`（元数据，几百字节/条），
 * 池本体在 `filesDir/seeds/<styleId>.json`，由
 * [CustomStyleStore.ensureSeeds] 在真正要烘焙/导出时才读进来。
 *
 * 文件格式（与备份包内 `seeds/<styleId>.json` 一致）：
 * ```json
 * { "schema":1, "count":N, "seeds":[ {addedAt, wbGain:"b64", ...} ] }
 * ```
 */
internal object SeedsStore {

    private const val DIR = "seeds"
    private const val SCHEMA = 1

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR)

    fun fileOf(ctx: Context, styleId: String): File = File(dir(ctx), "$styleId.json")

    fun exists(ctx: Context, styleId: String): Boolean = fileOf(ctx, styleId).isFile

    /** 落盘（先写 .tmp 再 rename，避免写到一半掉电留下半截 JSON）。 */
    fun save(ctx: Context, styleId: String, seeds: List<RefSeed>): Boolean {
        return try {
            val d = dir(ctx).apply { mkdirs() }
            val arr = JSONArray()
            seeds.forEach { arr.put(seedJson(it)) }
            val root = JSONObject()
                .put("schema", SCHEMA)
                .put("count", seeds.size)
                .put("seeds", arr)
            val tmp = File(d, "$styleId.json.tmp")
            tmp.writeText(root.toString())
            val dst = File(d, "$styleId.json")
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        } catch (t: Throwable) {
            DiagLog.e("SeedsStore", "写入失败 $styleId", t)
            false
        }
    }

    /** @return null 表示文件不存在（区别于「存在但为空池」） */
    fun load(ctx: Context, styleId: String): MutableList<RefSeed>? {
        val f = fileOf(ctx, styleId)
        if (!f.isFile) return null
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("seeds") ?: JSONArray()
            parseSeeds(arr)
        } catch (t: Throwable) {
            DiagLog.e("SeedsStore", "读取失败 $styleId", t)
            // 文件损坏：返回空池而不是 null，避免调用方写出「全量重新采集」的错觉
            mutableListOf()
        }
    }

    fun delete(ctx: Context, styleId: String) {
        runCatching { fileOf(ctx, styleId).delete() }
    }

    // ---- 编解码（备份 / 分享 / 主 JSON 迁移共用一套） ----

    fun seedJson(s: RefSeed): JSONObject = JSONObject()
        .put("addedAt", s.addedAt)
        .put("wbGain", encodeFloats(s.wbGain))
        .put("toneCurve", encodeFloats(s.toneCurve))
        .put("colorMatrix", encodeFloats(s.colorMatrix))
        .put("satByHue", encodeFloats(s.satByHue))
        .put("hueShift", encodeFloats(s.hueShift))
        .put("hueShare", encodeFloats(s.hueShare))
        .put("base", encodeFloats(s.base))
        // 残差网格只在有测量时写（约 24 KB base64），旧数据不会凭空多出空串
        .apply { if (s.meas.isNotEmpty()) put("meas", encodeFloats(s.meas)) }
        // M4：感知哈希（去重/断点续跑用），0 不写，旧数据不受影响
        .apply { if (s.fingerprint != 0L) put("fp", java.lang.Long.toHexString(s.fingerprint)) }

    fun parseSeeds(arr: JSONArray): MutableList<RefSeed> {
        val out = ArrayList<RefSeed>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            out.add(
                RefSeed(
                    addedAt = s.optLong("addedAt"),
                    wbGain = decodeFloats(s.optString("wbGain")),
                    toneCurve = decodeFloats(s.optString("toneCurve")),
                    colorMatrix = decodeFloats(s.optString("colorMatrix")),
                    satByHue = decodeFloats(s.optString("satByHue")),
                    hueShift = decodeFloats(s.optString("hueShift")),
                    hueShare = decodeFloats(s.optString("hueShare")),
                    base = decodeFloats(s.optString("base")),
                    meas = decodeFloats(s.optString("meas"))
                ).also { seed ->
                    // 十六进制字符串；解析失败按「未知」处理，不参与去重
                    seed.fingerprint = s.optString("fp").takeIf { it.isNotEmpty() }
                        ?.toLongOrNull(16) ?: 0L
                }
            )
        }
        return out
    }
}

/** float 数组 → base64（小端，与历史存档字节布局一致）。 */
internal fun encodeFloats(a: FloatArray): String {
    val buf = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    a.forEach { buf.putFloat(it) }
    return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
}

internal fun decodeFloats(s: String): FloatArray {
    if (s.isEmpty()) return FloatArray(0)
    return try {
        val raw = Base64.decode(s, Base64.NO_WRAP)
        val fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    } catch (t: Throwable) {
        FloatArray(0)
    }
}

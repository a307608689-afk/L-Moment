package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * 分享码导入：把 [ColorShareCode.Payload] 变成一条真正的自定义色彩。
 *
 * 与备份导入（[ColorBackup]）的分工：备份是「整库全量 + 文件」，分享码是「单条 + 文本」，
 * 所以这里单独一套流程，但共用同一条纪律 ——
 * **LUT 一律落成新的唯一文件名**，绝不与已有色彩共用 bin（删色彩是按 lutFile 删文件的）。
 */
object ColorShareImport {

    class Result(
        val def: CustomStyleDef?,
        val thumb: Bitmap? = null,
        /** 导入失败的人话原因。 */
        val error: String? = null,
        /** 命中去重时：库里已有这条色彩。 */
        val existingId: String? = null
    ) {
        val ok: Boolean get() = def != null
    }

    // ---- 缩略图落盘（分享/导入共用，顺便修掉「重启后卡片全灰」） ----

    fun thumbDir(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "thumbs")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    fun thumbFile(ctx: Context, styleId: String): File = File(thumbDir(ctx), "$styleId.jpg")

    fun deleteThumb(ctx: Context, styleId: String) {
        try {
            thumbFile(ctx, styleId).delete()
        } catch (t: Throwable) {
            DiagLog.w("Share", "缩略图删除失败 $styleId: ${t.message}")
        }
    }

    /** 覆盖写缩略图（调用方已在后台线程且传的是 JPEG 字节）。 */
    fun saveThumb(ctx: Context, styleId: String, jpeg: ByteArray) {
        try {
            thumbFile(ctx, styleId).writeBytes(jpeg)
        } catch (t: Throwable) {
            DiagLog.w("Share", "缩略图落盘失败 $styleId: ${t.message}")
        }
    }

    fun loadThumb(ctx: Context, styleId: String): Bitmap? {
        return try {
            val f = thumbFile(ctx, styleId)
            if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (t: Throwable) {
            null
        }
    }

    // ---- 导入 ----

    fun import(ctx: Context, p: ColorShareCode.Payload): Result {
        // 1) 还原 LUT 字节：配方码重烘焙，LUT 码直接反量化
        val bins: List<ByteArray> = if (p.mode == ColorShareCode.MODE_RECIPE) {
            val chain = ColorShareCode.bakeChain(p.seeds, p.steps)
                ?: return Result(null, error = "指令内容不完整，无法还原")
            val crc = ColorShareCode.crc32(chain.last())
            if (crc != p.crc) {
                return Result(null, error = "这条指令来自其它版本的 App，无法精确还原")
            }
            chain
        } else {
            val lut = p.lut ?: return Result(null, error = "指令内容不完整")
            val bin = ColorShareCode.inflateLut(lut, p.dim)
                ?: return Result(null, error = "指令已损坏，无法还原")
            listOf(bin)
        }

        // 2) 去重：库里已有同一条链就选中它，不产生重复卡片
        val dup = findDuplicate(ctx, p.crc)
        if (dup != null) {
            return Result(dup, loadThumb(ctx, dup.id), existingId = dup.id)
        }

        // 3) 建色彩：每版一个独立 LUT 文件（回退要能逐版读取）
        val styleId = CustomStyleStore.newId()
        val now = System.currentTimeMillis()
        val versions = ArrayList<StyleVersion>(bins.size)
        val written = ArrayList<File>()
        var ordinal = 0
        for (i in bins.indices) {
            val verId = "v${now.toString(36)}$i"
            val fileName = "lut_${styleId}_$verId.bin"
            if (!CustomLutBaker.saveBytes(ctx, bins[i], fileName)) {
                written.forEach { runCatching { it.delete() } }
                return Result(null, error = "写入失败，请检查存储空间后重试")
            }
            written.add(File(com.codex.leicaapp.AlexaLut.customDir(ctx), fileName))

            val spec = p.steps.getOrNull(i)
            val strength = (spec?.strength ?: 1f).coerceIn(0f, 1f)
            val isInitial = i == 0
            val label = if (isInitial) {
                "初始"
            } else {
                ordinal++
                val percent = (strength * 100).roundToInt()
                if (percent >= 100) "增强 $ordinal" else "增强 $ordinal $percent%"
            }
            versions.add(
                StyleVersion(
                    id = verId,
                    createdAt = now + i,
                    label = label,
                    lutFile = fileName,
                    seedCount = spec?.seedCount ?: p.seeds.size,
                    source = if (isInitial) VersionSource.INITIAL else VersionSource.ENHANCE,
                    strength = strength
                )
            )
        }

        val def = CustomStyleDef(
            id = styleId,
            name = uniqueName(p.name),
            createdAt = now,
            seeds = p.seeds.toMutableList(),
            versions = versions,
            currentVersionId = versions.last().id
        )

        // 4) 缩略图：写盘 + 交给调用方直接塞内存缓存
        var thumb: Bitmap? = null
        p.thumb?.let { bytes ->
            try {
                thumbFile(ctx, styleId).writeBytes(bytes)
                thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (t: Throwable) {
                DiagLog.w("Share", "缩略图落盘失败 $styleId: ${t.message}")
            }
        }

        CustomStyleStore.put(ctx, def)
        return Result(def, thumb)
    }

    /** 当前版本的 LUT CRC 相同即视为同一条色彩。 */
    private fun findDuplicate(ctx: Context, crc: Long): CustomStyleDef? {
        if (crc == 0L) return null
        CustomStyleStore.all().forEach { def ->
            val cur = CustomStyleStore.currentVersion(def) ?: return@forEach
            val bytes = CustomLutBaker.readBytes(ctx, cur.lutFile) ?: return@forEach
            if (ColorShareCode.crc32(bytes) == crc) return def
        }
        return null
    }

    /** 重名时加序号，避免两条同名色彩在滤镜条里分不清。 */
    private fun uniqueName(base: String): String {
        val name = base.trim().ifEmpty { "分享的色彩" }
        val used = CustomStyleStore.all().map { it.name }.toHashSet()
        if (name !in used) return name
        var i = 2
        while ("$name $i" in used) i++
        return "$name $i"
    }
}

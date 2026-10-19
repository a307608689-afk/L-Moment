package com.leicamoments.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.codex.leicaapp.AlexaLut
import com.codex.leicaapp.StyleA
import com.codex.leicaapp.StyleAFit
import java.io.File

/**
 * A 路线（LMA6 v2，1596 B）参数的落盘。
 *
 * 和 LUT 一起放在 filesDir/luts/，但用 `par_` 前缀：
 * - 参数只有 1.6 KB，永不回收，所以不需要 θ 快照；
 * - [ModelLutStore] 的 LRU 只认 `lut_` 前缀，不会误删。
 */
internal object ModelParamStore {
    private const val TAG = "ModelParam"
    private const val PREFIX = "par_"
    /** LMA6 v2 固定长度：12 头 + 36 A + 12 b + 64*3*2*4 表。 */
    const val BYTES = 1596

    fun nameOf(styleId: String, versionId: String) = "$PREFIX${styleId}_$versionId.bin"

    fun fileOf(ctx: Context, name: String): File = File(AlexaLut.customDir(ctx), name)

    fun exists(ctx: Context, name: String): Boolean {
        if (name.isEmpty()) return false
        val f = fileOf(ctx, name)
        return f.isFile && f.length() == BYTES.toLong()
    }

    fun read(ctx: Context, name: String): ByteArray? {
        if (name.isEmpty()) return null
        return try {
            val f = fileOf(ctx, name)
            if (!f.isFile || f.length() != BYTES.toLong()) null else f.readBytes()
        } catch (t: Throwable) {
            Log.w(TAG, "读取参数失败 $name", t)
            null
        }
    }

    /** 原子写入，成功返回文件名；失败返回空串（调用方按"无参数"处理）。 */
    fun write(ctx: Context, styleId: String, versionId: String, bytes: ByteArray): String =
        writeNamed(ctx, nameOf(styleId, versionId), bytes)

    /**
     * 导入用：保证文件名不与磁盘上已有的冲突（冲突时加序号），
     * 避免覆盖别的色彩/版本正在用的参数。
     */
    fun uniqueName(ctx: Context, styleId: String, versionId: String): String {
        val base = "$PREFIX${styleId}_$versionId"
        val dir = AlexaLut.customDir(ctx)
        var name = "$base.bin"
        var i = 2
        while (File(dir, name).exists()) {
            name = "${base}_$i.bin"
            i++
        }
        return name
    }

    /** 按指定文件名原子写入，成功返回文件名；失败返回空串。 */
    fun writeNamed(ctx: Context, name: String, bytes: ByteArray): String {
        if (bytes.size != BYTES) {
            Log.w(TAG, "参数长度异常 ${bytes.size} != $BYTES，放弃写入")
            return ""
        }
        return try {
            val dir = AlexaLut.customDir(ctx)
            if (!dir.isDirectory && !dir.mkdirs()) return ""
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            val dst = File(dir, name)
            if (dst.exists()) dst.delete()
            if (tmp.renameTo(dst)) name else {
                tmp.delete()
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入参数失败 $name", t)
            ""
        }
    }

    fun delete(ctx: Context, name: String) {
        if (name.isEmpty()) return
        try {
            fileOf(ctx, name).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "删除参数失败 $name", t)
        }
        StyleA.invalidate(AlexaLut.fileRef(name))
    }

    /** 删除某个色彩的全部参数（删除色彩/导入覆盖时用）。 */
    fun purge(ctx: Context, styleId: String) {
        val dir = AlexaLut.customDir(ctx)
        val hit = dir.listFiles { f ->
            f.isFile && f.name.startsWith("$PREFIX${styleId}_") && f.name.endsWith(".bin")
        } ?: return
        for (f in hit) {
            f.delete()
            StyleA.invalidate(AlexaLut.fileRef(f.name))
        }
    }

    fun ref(name: String): String = AlexaLut.fileRef(name)
}

/**
 * A 路线的拟合入口：参考图 → [StyleAFit] → 参数 bin。
 *
 * 拟合原料用 [ModelRefStore] 存的 240px 参考副本（不用原图）：
 * - 六张以上参考图时每张 5 万像素左右，对"全局仿射 + 分位表"足够；
 * - 与「重新生成」同源，同一份池子拟合结果稳定；
 * - 副本缺失（M4 之前建的模型）时返回 null，调用方退回旧烘焙，行为与今天一致。
 */
internal object ModelFitter {
    private const val TAG = "ModelFit"

    fun fitFromThumbs(ctx: Context, styleId: String, indexes: List<Int>): ByteArray? {
        if (indexes.isEmpty()) {
            Log.w(TAG, "$styleId 无可用参考索引")
            return null
        }
        val frames = ArrayList<StyleAFit.Frame>(indexes.size)
        for (i in indexes) {
            val f = ModelRefStore.thumbFile(ctx, styleId, i)
            if (f == null || !f.isFile || f.length() < 64) {
                Log.w(TAG, "$styleId 缺少参考图副本 #$i，退回旧烘焙")
                return null
            }
            val bmp = try {
                BitmapFactory.decodeFile(f.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "$styleId 副本解码失败 #$i", t)
                null
            }
            if (bmp == null) return null
            val w = bmp.width
            val h = bmp.height
            if (w < 16 || h < 16) {
                bmp.recycle()
                return null
            }
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            frames.add(StyleAFit.Frame(px, w, h))
        }
        val t0 = System.currentTimeMillis()
        return try {
            val bytes = StyleAFit.fit(frames)
            Log.i(TAG, "$styleId 拟合完成 frames=${frames.size} ${bytes?.size ?: 0}B ${System.currentTimeMillis() - t0}ms")
            bytes
        } catch (t: Throwable) {
            Log.w(TAG, "$styleId 拟合异常", t)
            null
        }
    }
}

/**
 * 自定义色彩的出图入口：参数版本走 A 路线，旧版本继续查 3D LUT。
 * 两条路都返回 JPEG；失败时返回输入字节本身（与 processLut 的回退语义一致）。
 */
internal object ModelColor {
    private const val TAG = "ModelColor"

    fun isParamVersion(version: StyleVersion): Boolean = version.paramFile.isNotEmpty()

    /** 产物是否就绪（参数文件或 LUT 文件在盘上）。 */
    fun ready(ctx: Context, version: StyleVersion): Boolean =
        if (isParamVersion(version)) ModelParamStore.exists(ctx, version.paramFile)
        else ModelLutStore.has(ctx, version)

    /** 缺产物时尽量补齐：参数版本补不了（只可能被清数据），旧版本按 θ 重烘。 */
    fun ensure(ctx: Context, def: CustomStyleDef?, version: StyleVersion): Boolean {
        if (isParamVersion(version)) {
            if (ModelParamStore.exists(ctx, version.paramFile)) return true
            Log.w(TAG, "参数文件缺失 ${version.paramFile}")
            return false
        }
        return def != null && ModelLutStore.ensure(ctx, def, version)
    }

    /** 把底图按版本渲染成新位图（增强预览用，不走 JPEG 往返）。失败返回 null。 */
    fun renderBitmap(ctx: Context, src: android.graphics.Bitmap, version: StyleVersion): android.graphics.Bitmap? {
        if (isParamVersion(version)) {
            return StyleA.applyToBitmap(ctx, src, ModelParamStore.ref(version.paramFile))
        }
        if (!ModelLutStore.has(ctx, version)) return null
        return AlexaLut.applyToBitmap(ctx, src, AlexaLut.fileRef(version.lutFile))
    }

    fun render(
        ctx: Context,
        jpeg: ByteArray,
        version: StyleVersion,
        onFallback: (() -> Unit)? = null
    ): ByteArray {
        if (!isParamVersion(version)) {
            return LeicaEngine.processLut(ctx, jpeg, AlexaLut.fileRef(version.lutFile), onFallback)
        }
        val out = StyleA.process(ctx, jpeg, ModelParamStore.ref(version.paramFile))
        if (out === jpeg) onFallback?.invoke()
        return out
    }
}

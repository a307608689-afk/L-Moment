package com.leicamoments.app

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * 自定义色彩分享码（`LMC1.` 前缀的一段 Base64 文本）。
 *
 * 两条通道各有一种码：
 * - 「复制指令」只用 [encodeCompact]：17³ 量化 LUT + 差分 + gzip，稳定约 6.2 KB 字符，
 *   接收端可直接套用、能以这份 LUT 为基准继续增强，但没有指纹、无法重放版本链；
 * - 「配方码」[encodeRecipe] 只在编码侧存在（旧版本分享过、本版仍能解）：几 KB 但
 *   需要参考图指纹，接收端重烘焙。分享入口已不再生成它，因为完整版本链常到 40 KB。
 *
 * 二进制布局（小端，整体 gzip 后 Base64Url 无换行）：
 * ```
 * 0..3   "LMC1"        4      format=1|2    5  mode(0=配方 1=LUT)
 * 6      dim           7      flags(bit0 含缩略图, bit1 LUT 差分压缩)
 * 8..11  crc32(目标 LUT bin)
 * 12..13 thumbLen      14..15 nameLen      16..17 seedCount   18 stepCount  19 reserved
 * then   name UTF-8 | thumb JPEG | seeds | steps | lut(mode=1)
 * ```
 * 每个 seed 固定 200 个 float：3+16+9+72+24+72+4（与 [RefSeed] 一致）。
 */
object ColorShareCode {

    const val PREFIX = "LMC1."

    /**
     * 分享协议版本；改烘焙算法或载荷编码时递增，旧包收到新码会明确拒绝而不是给出错颜色。
     *
     * 2 = LUT 载荷可差分压缩（flags bit1）。旧版只认 1 且不认 bit1，
     * 若不升版本，它会把差分字节当原始 LUT 静默导入成错色。
     */
    const val FORMAT = 2

    /** 配方模式：接收端重烘焙。 */
    const val MODE_RECIPE = 0

    /** LUT 模式：内嵌 17³ 量化查表。 */
    const val MODE_LUT = 1

    /** 降级模式的网格边长（33³ 取偶数索引 = 17³，无损抽样）。 */
    const val LUT_DIM = 17

    private const val MAGIC = 0x4C4D4331 // "LMC1"
    private const val SEED_FLOATS = 200

    /** 单个参考图指纹的字节数：8 字节 addedAt + 200 个 float。 */
    private const val SEED_BYTES = 8 + SEED_FLOATS * 4
    private const val MAX_SEEDS = 64
    private const val MAX_STEPS = 12
    private const val MAX_NAME_LEN = 48

    /**
     * 「复制指令」按钮的字符上限。
     *
     * 17³ LUT 走「沿 R 轴差分 + gzip」后实测 6244 字符（不做差分是 8228），
     * 这里留 1.7 KB 余量给名称与文案。超限时分享面板禁用复制、只留发文件。
     */
    const val MAX_TEXT_CHARS = 8000

    /** 版本链上的一步：`bake(seeds[0, seedCount))`，非初始版再与上一步按 [strength] 混合。 */
    class Step(val seedCount: Int, val strength: Float)

    class Payload(
        val mode: Int,
        val dim: Int,
        val crc: Long,
        val name: String,
        val seeds: List<RefSeed>,
        val steps: List<Step>,
        val lut: ByteArray?,
        val thumb: ByteArray?
    )

    // ---- 生成 ----

    /**
     * 重放版本链，返回每一版的 LUT 字节（下标与 steps 对齐）。
     *
     * 返回 null 表示这条链在语义上不可重放：
     * - 首个版本不是 INITIAL（异常存档）；
     * - 首个版本强度 < 100%（「新增色彩」分叉出来的初始版，其 bin 是别处的混合结果）；
     * - 链上版本已被回收（第一个 ENHANCE 的基准不在链上，重放结果必然不同）；
     * - 烘焙失败。
     *
     * 调用方拿到结果后仍要用 CRC 兜一次底，只有完全一致才写进分享码。
     */
    fun replay(def: CustomStyleDef, target: StyleVersion): List<ByteArray>? {
        val end = def.versions.indexOfFirst { it.id == target.id }
        if (end < 0) return null
        val chain = def.versions.subList(0, end + 1)
        if (chain.size > MAX_STEPS) return null
        val head = chain.firstOrNull() ?: return null
        if (head.source != VersionSource.INITIAL) return null
        // 「新增色彩」分叉出来的初始版，bin 是上游版本混合出来的，无法由 seeds 重放
        if (head.strength < 0.999f) return null
        if (def.seeds.isEmpty()) return null
        val steps = chain.map { Step(it.seedCount, it.strength) }
        return bakeChain(def.seeds, steps)
    }

    /**
     * 按版本链重烘焙：`v0 = bake(seeds[0,c0))`，`vi = blend(v(i-1), bake(seeds[0,ci)), si)`。
     *
     * 与 [MainActivity] 的增强链严格同构，且 [CustomLutBaker.bakeBytes] 是纯函数
     * （无随机、无时间依赖），所以同参数在两台设备上必得同字节 —— 这是配方码成立的根基。
     */
    fun bakeChain(seeds: List<RefSeed>, steps: List<Step>): List<ByteArray>? {
        if (seeds.isEmpty() || steps.isEmpty()) return null
        val out = ArrayList<ByteArray>(steps.size)
        var prev: ByteArray? = null
        for (s in steps) {
            val n = s.seedCount.coerceIn(1, seeds.size)
            val baked = CustomLutBaker.bakeBytes(seeds.subList(0, n)) ?: return null
            val bin = when {
                prev == null -> baked
                s.strength >= 0.999f -> baked
                else -> CustomLutBaker.blendBytes(prev, baked, s.strength)
            }
            out.add(bin)
            prev = bin
        }
        return out
    }

    /** 配方码：seeds 只带链上用到的最长前缀。 */
    fun encodeRecipe(
        name: String,
        seeds: List<RefSeed>,
        steps: List<Step>,
        crc: Long,
        thumb: ByteArray?
    ): String? {
        val used = steps.maxOfOrNull { it.seedCount } ?: return null
        val prefix = seeds.take(used.coerceIn(1, seeds.size))
        if (prefix.isEmpty()) return null
        return pack(
            mode = MODE_RECIPE,
            dim = CustomLutBaker.DIM,
            crc = crc,
            name = name,
            seeds = prefix,
            steps = steps,
            lut = null,
            thumb = thumb
        )
    }

    /**
     * LUT 码：33³ bin 抽样成 17³ 并量化到 8 bit（色差 < 1/255，体积降 25 倍）。
     *
     * 仍然带上 seeds：对方导入后虽然拿到的是一份成品 LUT，
     * 但用自己拍的照片「增强」时基准就是这个 bin，链路完好。
     */
    fun encodeLut(name: String, bin33: ByteArray, crc: Long, seeds: List<RefSeed>, thumb: ByteArray?): String? {
        val lut = downsample(bin33) ?: return null
        return pack(
            mode = MODE_LUT,
            dim = LUT_DIM,
            crc = crc,
            name = name,
            seeds = seeds.take(MAX_SEEDS),
            steps = listOf(Step(seeds.size.coerceAtLeast(1), 1f)),
            lut = lut,
            thumb = thumb
        )
    }

    /**
     * 轻量码：只带 17³ 量化 LUT（差分 + gzip，无指纹、无缩略图）。
     *
     * 这是「复制指令」唯一使用的形态：体积恒定约 6.3 KB 字符，微信里粘贴不会卡。
     * 接收端导入后得到一条成品色彩，可直接套用、也能以这份 LUT 为基准继续增强，
     * 但没有参考图指纹，无法重放版本链 —— 想要完整能力就走「分享文件」。
     *
     * 返回 null 表示这份 bin 无法降到 17³（例如尺寸不合法）。
     */
    fun encodeCompact(name: String, bin: ByteArray): String? {
        val lut = toLut17(bin) ?: return null
        val inflate = inflateLut(lut, LUT_DIM) ?: return null
        return pack(
            mode = MODE_LUT,
            dim = LUT_DIM,
            crc = crc32(inflate),
            name = name,
            seeds = emptyList(),
            steps = listOf(Step(0, 1f)),
            lut = lut,
            thumb = null,
            deltaLut = true
        )
    }

    private fun pack(
        mode: Int,
        dim: Int,
        crc: Long,
        name: String,
        seeds: List<RefSeed>,
        steps: List<Step>,
        lut: ByteArray?,
        thumb: ByteArray?,
        deltaLut: Boolean = false
    ): String? {
        val safeName = name.take(MAX_NAME_LEN)
        val nameBytes = safeName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 0xFFFF) return null
        val safeSeeds = seeds.take(MAX_SEEDS)
        if (safeSeeds.size > 0xFFFF || steps.size > MAX_STEPS) return null
        val thumbBytes = thumb?.takeIf { it.size <= 0xFFFF }
        val flags = (if (thumbBytes != null) 1 else 0) or (if (deltaLut) 2 else 0)

        val body = ByteArrayOutputStream(1024 + safeSeeds.size * 800 + (lut?.size ?: 0))
        val out = java.io.DataOutputStream(body)
        out.writeInt(MAGIC)
        out.writeByte(FORMAT)
        out.writeByte(mode)
        out.writeByte(dim)
        out.writeByte(flags)
        out.writeInt((crc and 0xFFFFFFFFL).toInt())
        out.writeShort(thumbBytes?.size ?: 0)
        out.writeShort(nameBytes.size)
        out.writeShort(safeSeeds.size)
        out.writeByte(steps.size)
        out.writeByte(0)
        out.write(nameBytes)
        if (thumbBytes != null) out.write(thumbBytes)
        safeSeeds.forEach { writeSeed(out, it) }
        steps.forEach { s ->
            out.writeByte(s.seedCount.coerceIn(0, 255))
            out.writeFloat(s.strength.coerceIn(0f, 1f))
        }
        if (mode == MODE_LUT) {
            val l = lut ?: return null
            val expect = dim * dim * dim * 3
            if (l.size != expect) return null
            out.write(if (deltaLut) deltaEncode(l, dim) else l)
        }
        out.flush()
        val gz = gzip(body.toByteArray()) ?: return null
        val code = android.util.Base64.encodeToString(
            gz,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return PREFIX + code
    }

    // ---- 解析 ----

    /** 从任意文本里抽出指令（微信会把「复制这条消息…」的引导语一起复制过来）。 */
    fun extractPayload(text: String): String? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        var end = start + PREFIX.length
        while (end < text.length) {
            val c = text[end]
            // 只认 Base64Url 的 ASCII 字符：isLetterOrDigit 会把紧跟其后的中文也吞进来
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
            if (ok) end++ else break
        }
        if (end - start - PREFIX.length < 24) return null
        return text.substring(start + PREFIX.length, end)
    }

    /**
     * 指令指纹：同一段码只处理一次（剪贴板内容会长期留着，不能每次回前台都弹窗）。
     * 只看码本身，与引导语（展示用的标题与提示文字）无关。
     */
    fun signature(text: String): String? =
        extractPayload(text)?.let { "${it.length}:${it.hashCode()}" }

    fun decode(text: String): Payload? {
        val code = extractPayload(text) ?: return null
        val gz = try {
            android.util.Base64.decode(
                code,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        } catch (t: Throwable) {
            return null
        }
        val body = gunzip(gz) ?: return null
        // 头部与 steps 用 DataOutputStream 的大端；只有 seed 内部与 LUT 是显式小端
        val buf = ByteBuffer.wrap(body)
        if (buf.remaining() < 20) return null
        if (buf.int != MAGIC) return null
        val format = buf.get().toInt() and 0xFF
        // 2 = 当前版本（新增 LUT 差分标志位）；1 = 旧版分享码，继续可读
        if (format != FORMAT && format != 1) return null
        val mode = buf.get().toInt() and 0xFF
        val dim = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val crc = (buf.int.toLong() and 0xFFFFFFFFL)
        val thumbLen = buf.short.toInt() and 0xFFFF
        val nameLen = buf.short.toInt() and 0xFFFF
        val seedCount = buf.short.toInt() and 0xFFFF
        val stepCount = buf.get().toInt() and 0xFF
        buf.get() // reserved

        if (mode != MODE_RECIPE && mode != MODE_LUT) return null
        if (dim < 2 || dim > 64) return null
        if (seedCount > MAX_SEEDS || stepCount > MAX_STEPS) return null
        if (nameLen > 0xFFFF || thumbLen > 0xFFFF) return null

        fun take(n: Int): ByteArray? {
            if (n < 0 || n > buf.remaining()) return null
            val a = ByteArray(n)
            buf.get(a)
            return a
        }

        val nameBytes = take(nameLen) ?: return null
        val thumb = if ((flags and 1) != 0) take(thumbLen) ?: return null else null
        val seeds = ArrayList<RefSeed>(seedCount)
        for (i in 0 until seedCount) {
            val raw = take(SEED_BYTES) ?: return null
            seeds.add(readSeed(raw))
        }
        val steps = ArrayList<Step>(stepCount)
        for (i in 0 until stepCount) {
            if (buf.remaining() < 5) return null
            val n = buf.get().toInt() and 0xFF
            val s = buf.float
            steps.add(Step(n, s))
        }
        val lut = if (mode == MODE_LUT) {
            val expect = dim * dim * dim * 3
            val raw = take(expect) ?: return null
            // bit1：轻量码的差分压缩；旧码没有该位，按原样读取保持兼容
            if ((flags and 2) != 0) deltaDecode(raw, dim) else raw
        } else null

        return Payload(
            mode = mode,
            dim = dim,
            crc = crc,
            name = String(nameBytes, Charsets.UTF_8).ifEmpty { "分享的色彩" },
            seeds = seeds,
            steps = steps,
            lut = lut,
            thumb = thumb
        )
    }

    // ---- 字节工具 ----

    fun crc32(bytes: ByteArray): Long {
        val c = CRC32()
        c.update(bytes)
        return c.value
    }

    /**
     * bin → 17³ uint8（等步长采样，两端对齐）。
     *
     * 源网格按长度自适应：33³（v1 统计量映射）/ 65³（v2 相对风格拟合）。
     * 步长必须整除（32/16=2、64/16=4），否则采样点对不上格点。
     */
    fun downsample(bin: ByteArray): ByteArray? {
        val dst = LUT_DIM
        val pts = bin.size / 12
        if (pts <= 0 || pts * 12 != bin.size) return null
        val src = Math.round(Math.cbrt(pts.toDouble())).toInt()
        if (src * src * src != pts) return null
        if (src <= dst || (src - 1) % (dst - 1) != 0) return null
        val fb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val step = (src - 1) / (dst - 1)
        val out = ByteArray(dst * dst * dst * 3)
        val planeSrc = src * src * src
        var o = 0
        for (ch in 0 until 3) {
            for (bi in 0 until dst) {
                val sb = bi * step
                for (gi in 0 until dst) {
                    val sg = gi * step
                    for (ri in 0 until dst) {
                        val sr = ri * step
                        val v = fb.get(ch * planeSrc + sb * src * src + sg * src + sr)
                        out[o++] = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                    }
                }
            }
        }
        return out
    }

    /**
     * 任意支持的 bin → 17³ uint8。
     *
     * 33³/65³ 等步长抽样；已经是 17³（轻量码导入后的存档）直接量化 ——
     * 否则「导入来的色彩再分享」会因为没有可降采样空间而失败。
     */
    fun toLut17(bin: ByteArray): ByteArray? {
        val pts = bin.size / 12
        if (pts <= 0 || pts * 12 != bin.size) return null
        val src = Math.round(Math.cbrt(pts.toDouble())).toInt()
        if (src * src * src != pts) return null
        if (src != LUT_DIM) return downsample(bin)
        val fb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(LUT_DIM * LUT_DIM * LUT_DIM * 3)
        for (i in out.indices) {
            out[i] = (fb.get(i).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        return out
    }

    /** 沿 R 轴（布局里最快变化的维）差分：相邻格点数值接近，gzip 能再压掉约 24%。 */
    private fun deltaEncode(lut: ByteArray, dim: Int): ByteArray {
        val out = lut.copyOf()
        for (i in lut.indices) {
            if (i % dim != 0) out[i] = ((lut[i].toInt() - lut[i - 1].toInt()) and 0xFF).toByte()
        }
        return out
    }

    private fun deltaDecode(enc: ByteArray, dim: Int): ByteArray {
        val out = enc.copyOf()
        for (i in 1 until out.size) {
            if (i % dim != 0) out[i] = ((out[i - 1].toInt() + enc[i].toInt()) and 0xFF).toByte()
        }
        return out
    }

    /** 17³ uint8 → float32 bin（可直接喂给 [com.codex.leicaapp.AlexaLut]，它按长度反推网格）。 */
    fun inflateLut(lut: ByteArray, dim: Int): ByteArray? {
        val expect = dim * dim * dim * 3
        if (lut.size != expect) return null
        val out = ByteBuffer.allocate(expect * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (b in lut) out.putFloat((b.toInt() and 0xFF) / 255f)
        return out.array()
    }

    /** 分享指令的完整可读文本。 */
    fun wrapText(name: String, code: String): String =
        "【L-Moment·色彩】$name\n" +
            "复制这条消息后打开 L-Moment，会自动导入。\n\n" +
            code

    /** 指令体积（Base64 字符数，用于分享面板提示）。 */
    fun codeLength(code: String): Int = code.length

    private fun writeSeed(out: java.io.DataOutputStream, seed: RefSeed) {
        // 载荷是固定 200 float 的旧布局：v2 扩容后的 seed（48 桶 / 33 点）先降采样，
        // 否则会被截断成"前 24 个色相桶 + 半个曲线"，对方重烘焙出来是错色。
        val s = seed.compactedForShare()
        val buf = ByteBuffer.allocate(SEED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(s.addedAt)
        val arrays = arrayOf(s.wbGain, s.toneCurve, s.colorMatrix, s.satByHue, s.hueShift, s.hueShare, s.base)
        val lens = intArrayOf(3, 16, 9, 72, 24, 72, 4)
        for (i in arrays.indices) {
            val a = arrays[i]
            for (k in 0 until lens[i]) buf.putFloat(a.getOrElse(k) { 0f })
        }
        out.write(buf.array())
    }

    private fun readSeed(raw: ByteArray): RefSeed {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val addedAt = bb.long
        val fb = bb.asFloatBuffer()
        fun next(n: Int): FloatArray = FloatArray(n) { fb.get() }
        return RefSeed(
            addedAt = addedAt,
            wbGain = next(3),
            toneCurve = next(16),
            colorMatrix = next(9),
            satByHue = next(72),
            hueShift = next(24),
            hueShare = next(72),
            base = next(4)
        )
    }

    private fun gzip(data: ByteArray): ByteArray? = try {
        val bos = ByteArrayOutputStream(data.size / 2 + 64)
        GZIPOutputStream(bos).use { it.write(data) }
        bos.toByteArray()
    } catch (t: Throwable) {
        null
    }

    private fun gunzip(data: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }
}

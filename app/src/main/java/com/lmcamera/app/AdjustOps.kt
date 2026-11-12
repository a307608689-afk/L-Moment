package com.lmcamera.app

import android.graphics.Bitmap
import com.codex.lmapp.MadridShader

/**
 * 新增调节维度（色温 / 色调 / 黑白强度 / 颗粒 / 暗角 / LUT 强度）的统一算子层。
 *
 * 设计约束（与「专业级一致性」验收口径对应）：
 * 1. **纯像素算子**：不依赖预览尺寸、不依赖导入格式（RAW 解码后与 JPG 走同一份工作字节），
 *    因此 RAW 与 JPG 同参数必然同结果；
 * 2. **条带安全**：颗粒/暗角通过 [MadridShader.apply] 的 fullH/top 参数保证跨带连续；
 *    色温/色调/黑白是逐像素映射，天然无接缝；
 * 3. **线性域白平衡**：sRGB → 线性 → 增益 → 回 sRGB（查表实现），避免直接乘 gamma 域的
 *    假色偏与高光染色，这是「自然」的关键；
 * 4. **LUT 强度是混合语义**：base 与已套 LUT 的图做线性插值，不是重新调色，100% 与现状完全等价。
 */
object AdjustOps {

    /** 色温/色调/黑白/颗粒/暗角 —— 与八档曲线参数相互独立的一组。 */
    data class Extras(
        val temp: Int = 0,        // -10..10，正=暖
        val tint: Int = 0,        // -10..10，正=品红
        val mono: Int = 0,        // 0..100，黑白混入比例
        val grain: Int = 0,       // 0..100，叠加在风格内置颗粒之上
        val vignette: Int = 0     // 0..100，叠加在风格内置暗角之上
    ) {
        val isNeutral: Boolean get() = temp == 0 && tint == 0 && mono == 0 && grain == 0 && vignette == 0
    }

    private const val WB_STEP = 0.012f     // 每档增益 ≈1.2%，±10 档 ≈ ±12%
    private const val TINT_STEP = 0.015f

    /**
     * 依次应用：白平衡 → 黑白混入 → 颗粒/暗角。
     *
     * 全部为中性时**原样返回入参**（调用方据此判断是否需要 recycle），
     * 非中性时返回新位图或就地修改入参，调用方按 [isNeutral] 决定拷贝策略。
     */
    fun applyExtras(
        src: Bitmap,
        e: Extras,
        fullH: Int = src.height,
        top: Int = 0
    ): Bitmap {
        if (e.isNeutral) return src
        var out = src
        if (e.temp != 0 || e.tint != 0) {
            out = whiteBalance(out, e.temp, e.tint)
        }
        if (e.mono > 0) {
            monoBlend(out, e.mono)
        }
        if (e.grain > 0 || e.vignette > 0) {
            MadridShader.apply(
                out,
                e.grain / 100f,
                e.vignette / 100f,
                fullH,
                top
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 白平衡

    /**
     * 线性域白平衡：sRGB 8bit → 线性增益 → sRGB 8bit，逐通道预生成 256 项查找表。
     * 增益只作用在亮度上（保持色相走向按通道比例），不做整体曝光，因此不会改变对比。
     */
    fun whiteBalance(src: Bitmap, temp: Int, tint: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val t = temp.coerceIn(-10, 10) * WB_STEP
        val g = tint.coerceIn(-10, 10) * TINT_STEP
        // 暖：R↑ B↓；品红：G↓（同时略微抬 R/B，避免整体发绿或发灰）
        val gainR = 1f + t + g * 0.35f
        val gainG = 1f - g
        val gainB = 1f - t + g * 0.35f

        val lutR = buildChannelLut(gainR)
        val lutG = buildChannelLut(gainG)
        val lutB = buildChannelLut(gainB)

        for (i in px.indices) {
            val c = px[i]
            val r = lutR[(c shr 16) and 0xFF]
            val gg = lutG[(c shr 8) and 0xFF]
            val b = lutB[c and 0xFF]
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or b
        }
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        } catch (t2: Throwable) {
            try {
                src.setPixels(px, 0, w, 0, 0, w, h)
            } catch (e2: Throwable) {
                // 忽略：调用方会拿原图继续
            }
            src
        }
    }

    /** sRGB→线性→乘增益→回 sRGB，压实成 256 项表（含软肩，避免高光硬切）。 */
    private fun buildChannelLut(gain: Float): IntArray {
        val lut = IntArray(256)
        val g = gain.coerceIn(0.4f, 2.5f)
        for (i in 0 until 256) {
            val v = i / 255f
            val lin = srgbToLinear(v) * g
            val enc = linearToSrgb(lin)
            lut[i] = (enc * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f
        else 1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
    }

    // ---------------------------------------------------------------- 黑白强度

    /** 按比例把像素往亮度轴收（0 = 原色，100 = 完全黑白），保色相不变灰阶分层。 */
    fun monoBlend(src: Bitmap, percent: Int) {
        val s = (percent.coerceIn(0, 100)) / 100f
        if (s <= 0f) return
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            val nr = (r + (y - r) * s).toInt().coerceIn(0, 255)
            val ng = (g + (y - g) * s).toInt().coerceIn(0, 255)
            val nb = (b + (y - b) * s).toInt().coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        src.setPixels(px, 0, w, 0, 0, w, h)
    }

    // ---------------------------------------------------------------- LUT 强度

    /**
     * LUT 强度：把「已套 LUT 的图」与原图按百分比线性混合。
     *
     * 100% 与既有行为逐像素等价（直接返回 styled），0% 返回 base，
     * 中间值不会二次调色，也不会改变已有的曲线档位顺序。
     */
    fun blendLut(base: Bitmap, styled: Bitmap, percent: Int): Bitmap {
        val p = percent.coerceIn(0, 100)
        if (p >= 100) return styled
        if (p <= 0) return base
        // 尺寸不一致（如原底与风格基准来自不同降采样路径）：把 base 缩到 styled 尺寸再混合。
        // 绝不直接返回 styled——调用方会把返回值当作"自有位图"持有并回收，返回缓存本体必崩。
        if (base.width != styled.width || base.height != styled.height) {
            val scaled = Bitmap.createScaledBitmap(base, styled.width, styled.height, true)
            return try {
                blendLut(scaled, styled, p)
            } finally {
                scaled.recycle()
            }
        }
        val s = p / 100f
        val w = base.width
        val h = base.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        base.getPixels(a, 0, w, 0, 0, w, h)
        styled.getPixels(b, 0, w, 0, 0, w, h)
        for (i in a.indices) {
            val ca = a[i]
            val cb = b[i]
            val r = (((ca shr 16) and 0xFF) + ((((cb shr 16) and 0xFF) - ((ca shr 16) and 0xFF)) * s)).toInt()
            val g = (((ca shr 8) and 0xFF) + ((((cb shr 8) and 0xFF) - ((ca shr 8) and 0xFF)) * s)).toInt()
            val bl = ((ca and 0xFF) + (((cb and 0xFF) - (ca and 0xFF)) * s)).toInt()
            a[i] = (ca and 0xFF000000.toInt()) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                bl.coerceIn(0, 255)
        }
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(a, 0, w, 0, 0, w, h)
            out
        } catch (t: Throwable) {
            styled
        }
    }

    /**
     * [blendLut] 的就地变体：把混合结果直接逐行写回 mutable 的 [styled]，
     * 不产生第三张位图、不驻留整图数组——24MP 级 HiRes 导出链专用
     * （blendLut 的「双数组 + 新位图」峰值在 24MP 画布会顶穿堆上限）。
     *
     * 约束：[styled] 必须 mutable 且与 [base] 同尺寸，否则行为未定义（调用方先判）；
     * percent ≤0 时无法换底，仅 ≥1 有意义（调用方保证 1..99）。返回 styled 本体。
     */
    fun blendLutInPlace(base: Bitmap, styled: Bitmap, percent: Int): Bitmap {
        val s = (percent.coerceIn(1, 99)) / 100f
        val w = styled.width
        val h = styled.height
        val rowB = IntArray(w)
        val rowS = IntArray(w)
        for (y in 0 until h) {
            base.getPixels(rowB, 0, w, 0, y, w, 1)
            styled.getPixels(rowS, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val ca = rowB[x]
                val cb = rowS[x]
                val r = (((ca shr 16) and 0xFF) + ((((cb shr 16) and 0xFF) - ((ca shr 16) and 0xFF)) * s)).toInt()
                val g = (((ca shr 8) and 0xFF) + ((((cb shr 8) and 0xFF) - ((ca shr 8) and 0xFF)) * s)).toInt()
                val b = ((ca and 0xFF) + (((cb and 0xFF) - (ca and 0xFF)) * s)).toInt()
                rowS[x] = (ca and 0xFF000000.toInt()) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
            }
            styled.setPixels(rowS, 0, w, 0, y, w, 1)
        }
        return styled
    }
}

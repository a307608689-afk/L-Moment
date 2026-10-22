package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 出口色度清理（200MP/50MP 全像素链平坦区彩色噪点治理）。
 *
 * 病灶（2026-10-21 真机对拍 50MP 暗光样张定量）：M9 链的 TPDF 抖动对 R/B 通道
 * 独立扰动（CHROMA_SCALE 加权），链上抖 3 次 + 4:4:4 编码全保留，把平坦区
 * 色度高频 σ 从原图 Cb 0.29/Cr 0.24 推到 0.73/0.53（2.2~2.5 倍）；且处理图
 * 色噪与原图色噪空间相关仅 0.046（= 新注入的白噪，不是原图被放大）。
 *
 * 算子（与 Lm9Post.chromaDenoise 同构，LM9 链已量产验证）：Y 不动，
 * Cb/Cr 取 DS=2 网格 + 半径 CH_R 盒低频，细节保留 [CH_KEEP] 回加——
 * ≤36px 的色度中频（含抖动彩噪）被衰减到保留比例，色度层次与边缘色不受伤。
 *
 * 位置：必须在**最后一次抖动之后**（出口），否则链内两次抖动会重新注入。
 * 内存：带 16320×256 下峰值 ≈ 16MB(int[]) + 8MB(网格) + 盒滤波 tmp ≈ 42MB。
 */
internal object ChromaClean {

    private const val TAG = "CHROMAC"

    /** 色度低频网格步长（px/格） */
    private const val CH_DS = 2

    /** 网格盒滤波半径（格）→ 等效 2·(2R+1) ≈ 36px 全分辨率 */
    private const val CH_R = 9

    /** 色度细节保留比例：0.55 = 噪声匹配折中（压彩噪但保留原图 55% 色度层次） */
    private const val CH_KEEP = 0.55f

    /** @return 耗时 ms；输入不可变/异常返回 -1，绝不抛出 */
    fun apply(band: Bitmap): Long {
        if (band.isRecycled || !band.isMutable) return -1
        val w = band.width
        val h = band.height
        if (w < 16 || h < 16) return -1
        val t0 = System.currentTimeMillis()
        try {
            val sw = (w + CH_DS - 1) / CH_DS
            val sh = (h + CH_DS - 1) / CH_DS
            val px = IntArray(w * h)
            band.getPixels(px, 0, w, 0, 0, w, h)

            // 1) DS×DS 网格 Cb/Cr 均值
            val crS = FloatArray(sw * sh)
            val cbS = FloatArray(sw * sh)
            val cnt = IntArray(sw * sh)
            for (y in 0 until h) {
                val sy = y / CH_DS
                val base = sy * sw
                var x = 0
                while (x < w) {
                    val p = px[y * w + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val Y = 0.299f * r + 0.587f * g + 0.114f * b
                    val si = base + x / CH_DS
                    crS[si] += (r - Y) / 1.402f
                    cbS[si] += (b - Y) / 1.772f
                    cnt[si]++
                    x++
                }
            }
            for (i in crS.indices) {
                if (cnt[i] > 0) {
                    val n = cnt[i].toFloat()
                    crS[i] = crS[i] / n
                    cbS[i] = cbS[i] / n
                }
            }
            // 2) 两遍盒模糊（可分离）→ 色度低频
            val crL = boxBlur(boxBlur(crS, sw, sh, CH_R), sw, sh, CH_R)
            val cbL = boxBlur(boxBlur(cbS, sw, sh, CH_R), sw, sh, CH_R)

            // 3) 双线性上采样 + 保留 CH_KEEP 细节回加
            val invDs = 1f / CH_DS
            var y = 0
            while (y < h) {
                val fy = (y + 0.5f) * invDs - 0.5f
                val gy0 = floorInt(fy, sh - 1)
                val gy1 = min(gy0 + 1, sh - 1)
                val wy = max(fy - gy0, 0f)
                val r0 = gy0 * sw
                val r1 = gy1 * sw
                var x = 0
                while (x < w) {
                    val fx = (x + 0.5f) * invDs - 0.5f
                    val gx0 = floorInt(fx, sw - 1)
                    val gx1 = min(gx0 + 1, sw - 1)
                    val wx = max(fx - gx0, 0f)
                    val crLo = (1 - wx) * (1 - wy) * crL[r0 + gx0] + wx * (1 - wy) * crL[r0 + gx1] +
                            (1 - wx) * wy * crL[r1 + gx0] + wx * wy * crL[r1 + gx1]
                    val cbLo = (1 - wx) * (1 - wy) * cbL[r0 + gx0] + wx * (1 - wy) * cbL[r0 + gx1] +
                            (1 - wx) * wy * cbL[r1 + gx0] + wx * wy * cbL[r1 + gx1]

                    val p = px[y * w + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val Y = 0.299f * r + 0.587f * g + 0.114f * b
                    val Cr = (r - Y) / 1.402f
                    val Cb = (b - Y) / 1.772f
                    val cr2 = crLo + (Cr - crLo) * CH_KEEP
                    val cb2 = cbLo + (Cb - cbLo) * CH_KEEP
                    val r2 = Y + 1.402f * cr2
                    val b2 = Y + 1.772f * cb2
                    val g2 = Y - 0.7141f * cr2 - 0.3441f * cb2
                    px[y * w + x] = (p and 0xFF000000.toInt()) or
                            (clamp255(r2) shl 16) or (clamp255(g2) shl 8) or clamp255(b2)
                    x++
                }
                y++
            }
            band.setPixels(px, 0, w, 0, 0, w, h)
            return System.currentTimeMillis() - t0
        } catch (t: Throwable) {
            DiagLog.e(TAG, "色度清理失败（跳过，不影响导出）", t)
            return -1
        }
    }

    private fun floorInt(v: Float, max: Int): Int {
        val i = floor(v.toDouble()).toInt()
        return if (i < 0) 0 else min(i, max)
    }

    private fun clamp255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else min(i, 255)
    }

    /** 可分离盒模糊（边界钳位），返回新数组 */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = max(x - r, 0)
                val x1 = min(x + r, w - 1)
                var acc = 0f
                var xx = x0
                while (xx <= x1) {
                    acc += src[row + xx]
                    xx++
                }
                tmp[row + x] = acc / (x1 - x0 + 1)
            }
        }
        for (y in 0 until h) {
            val y0 = max(y - r, 0)
            val y1 = min(y + r, h - 1)
            val n = (y1 - y0 + 1).toFloat()
            for (x in 0 until w) {
                var acc = 0f
                var yy = y0
                while (yy <= y1) {
                    acc += tmp[yy * w + x]
                    yy++
                }
                out[y * w + x] = acc / n
            }
        }
        return out
    }
}

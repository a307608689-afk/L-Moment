package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 全像素条带链出口的「弱对比区高频衰减」（200MP 导出平坦区水彩纹/细噪治理）。
 *
 * 病灶（2026-10-20，PC 对拍 200MP M9 导出 vs 原图定罪）：
 * 条带链（native 暖调局部增强 + 空间可变高频增益 + 双次 TPDF 抖动 + 编码）把平滑区
 * 细噪声/中频云纹放大到原图的 ~2 倍（hpσ G 0.72→1.7），云纹与原图噪声空间相关
 * （corr 0.65~0.73、局部斜率 1.5~2.3）＝厂商 .so 的局部增强行为，不可改参数；
 * 预览链因 4× 降采样推理 + 缩屏显示把同一相对噪声平均掉，故「只有导出可见」。
 *
 * 算子：以 2px 网格亮度梯度幅值为结构检测器（真实边缘 ≥10 级/格，云纹 ≤3 级/格），
 * 平坦区向 7×7 盒均值混合，边缘权重 w = K·(1 − A/T0)² 平方衰减退到 0 —— 双边滤波式
 * 的幅度门控（噪声幅度 ≤T0 被平滑、强对比边缘被保留），无需纹理/边缘分类器。
 *
 * 参数依据（band200_retune.py，**正确门控语义**扫描）：T0=5.0 / K=0.8 →
 * 平坦 hpσ G 1.70→1.35（原图 0.72 + 出口抖动残余）、圆点边缘 Lap p95 23→19、
 * 标签小字 Lap 28→28（无损）。
 *
 * ⚠️ 历史教训（2026-10-21 真机返工）：PC 首版原型把门控误写成 `1 - (A/T0)^2`
 * （Python `**` 优先级笔误），该函数在 [0,1] 全程 0.56~1 等于没有门控，据此调出的
 * T0=3.0 移植进 Kotlin（语义正确的 (1−x)²）后 w 均值仅 0.14、降噪几乎无效。
 * 本文件常量必须以「(1−A/T0)²」语义的扫描台（retune.py）为准，勿再引用 v3c 旧值。
 *
 * ⚠️ setPixels 偏移：处理结果写在 sPx 的第 R 行起（前 R 行是 halo），写回必须
 * `offset = R*w`。初版漏了偏移 → 每 128 行 strip 错位 3 行，产生周期接缝线
 * （200MP 实测 128 周期行差能量 0.40% vs 旧包 0.05%）。
 *
 * 内存：全程条带化（128 行 + halo），峰值 ~2×(宽×134) float + 网格数组（200MP 单带 ≈20MB），
 * 不引入整带级大数组。耗时实测目标 <1s/带（24 带约 +15~20s）。
 */
internal object FlatHfClean {

    private const val TAG = "FLATHF"

    /** 结构检测网格降采样（px/格）：2 = 边缘定位精度与网格开销的折中 */
    private const val DS = 2

    /**
     * 门控阈值（亮度级/格）：网格梯度超过该值视为结构，权重退到 0。
     * 5.0 = retune.py 正确语义扫描的折中点（T0=4 降噪不足 1.49；T0=6 边缘软化到 17）。
     */
    private const val T0 = 5.0f

    /** 平坦区向局部均值的最大混合强度（0=不动，1=完全替换） */
    private const val K = 0.8f

    /** 盒模糊半径（两遍可分离 → (2R+1)² 等效支撑）：需覆盖 6~14px 云纹 */
    private const val R = 3

    /** 门控衰减指数：平方使边缘侧比线性更陡 */
    private const val POW = 2.0f

    /** 应用条带行数（读写缓冲按 行数+2R 预留 halo） */
    private const val STRIP = 128

    /**
     * 跨带衔接网格行数：本带前 [SEAM] 网格行的 A 直接沿用上一带底边同口径的值。
     * 不衔接的后果（2026-10-21 真机实测）：每带独立算的 A 场在带边界跳变 →
     * 混合权重 w 跳变 → 带边 tone 过冲（200MP 实测 y=2048 达 +1.6 级，
     * 即用户所见「带边暗条/亮条」的残余来源）。
     */
    private const val SEAM = 4

    /** 上一带底边 [SEAM] 网格行的 A（跨带衔接用；新任务必须 [reset]） */
    private var prevSeamA: FloatArray? = null

    /** 导出任务开始/结束时调用，清跨带递推状态 */
    fun reset() {
        prevSeamA = null
    }

    /** @return 耗时 ms（诊断用）；输入不可变/异常返回 -1，绝不抛出 */
    fun apply(band: Bitmap, top: Int, src: ImageUtils.StripSource?): Long {
        if (band.isRecycled || !band.isMutable) return -1
        val w = band.width
        val h = band.height
        if (w < 8 || h < 8) return -1
        val t0 = System.currentTimeMillis()
        try {
            val gw = (w + DS - 1) / DS
            val gh = (h + DS - 1) / DS
            // ---- 1) 网格亮度（DS×DS 均值）----
            val gSum = FloatArray(gw * gh)
            val gCnt = IntArray(gw * gh)
            val rowPx = IntArray(w)
            var y = 0
            while (y < h) {
                band.getPixels(rowPx, 0, w, 0, y, w, 1)
                val gy = y / DS
                val base = gy * gw
                var x = 0
                while (x < w) {
                    val p = rowPx[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    gSum[base + x / DS] += 0.2126f * r + 0.7152f * g + 0.0722f * b
                    gCnt[base + x / DS]++
                    x++
                }
                y++
            }
            val Yg = FloatArray(gw * gh)
            for (i in Yg.indices) Yg[i] = gSum[i] / maxOf(gCnt[i], 1)
            // ---- 2) 网格梯度幅值 + 3×3 平滑 → 活动场 ----
            val mag = FloatArray(gw * gh)
            for (gy in 0 until gh) {
                val row = gy * gw
                val yUp = min(gy + 1, gh - 1) * gw
                val yDn = maxOf(gy - 1, 0) * gw
                for (gx in 0 until gw) {
                    val xR = min(gx + 1, gw - 1)
                    val xL = maxOf(gx - 1, 0)
                    val dfx = (Yg[row + xR] - Yg[row + xL]) * 0.5f
                    val dfy = (Yg[yUp + gx] - Yg[yDn + gx]) * 0.5f
                    mag[row + gx] = sqrt(dfx * dfx + dfy * dfy)
                }
            }
            val A = box3(mag, gw, gh)
            // 跨带衔接：本带前 SEAM 网格行的 A 沿用上一带底边值（见 [SEAM] 注释）。
            // A 场是 3×3 网格平滑的低频场，平移 SEAM 行的误差远小于台阶本身。
            val seamRows = min(SEAM, gh / 2)
            if (top > 0 && prevSeamA != null && gh >= seamRows * 2) {
                System.arraycopy(prevSeamA!!, 0, A, 0, seamRows * gw)
            }
            if (gh >= seamRows * 2) {
                if (prevSeamA == null || prevSeamA!!.size < seamRows * gw) {
                    prevSeamA = FloatArray(seamRows * gw)
                }
                System.arraycopy(A, (gh - seamRows) * gw, prevSeamA!!, 0, seamRows * gw)
            }
            // ---- 3) 逐条带：7×7 盒均值 + 门控混合 ----
            var nBlend = 0L; var nSkip = 0L; var wSum = 0.0
            val ext = STRIP + 2 * R
            val sPx = IntArray(w * ext)
            // 整带原始副本：所有 strip 必须从同一份**未处理**数据读。
            // 若每条 strip 直接从 band 读，后一条 strip 的 halo 行会读到前一条
            // strip 已写回的处理值 → 盒均值邻域半处理半原始 → 条带边界混合不连续
            // （128 周期行差能量 0.34% vs 旧包 0.05% 的来源）。
            val orig = IntArray(w * h)
            band.getPixels(orig, 0, w, 0, 0, w, h)
            // 水平/垂直盒结果：每像素 3 通道（RGB），容量必须是 w*ext*3。
            // 2026-10-21 真机返工：初版按 w*ext 分配却按 *3 索引 → 每带
            // ArrayIndexOutOfBounds 被 catch 静默吞掉，降噪从未在真机执行过。
            val hBuf = FloatArray(w * ext * 3)
            val mBuf = FloatArray(w * ext * 3)
            // ---- 3.0) 噪声匹配：只削「处理引入的噪声」，把结果拉回源图水平 ----
            // 固定 K=0.8 在低噪场景会过削成塑料感（真机实测 σ 打到原图 1/6）。
            // σ_x = 本带（FlatHfClean 前）高频能量；σ_src = 源图同行高频能量。
            // 7×7 盒均值对相关噪声衰减约 √7≈2.6，取保守因子 3 免算 m 缓冲：
            //   K_eff = (σ_x − σ_src) / (σ_x − σ_x/3) = 3(σ_x − σ_src)/(2σ_x)
            // σ_src ≥ σ_x（源更噪）→ 0 不动；高噪场景（暖调放大 2 倍）自动接近 K 上限。
            var kEff = K
            var statSx = 0f; var statSs = 0f
            if (src != null && h >= 8) {
                val yB = FloatArray(w)
                val yS = FloatArray(w)
                val brow = IntArray(w)
                val srow = IntArray(w)
                val stepS = maxOf(1, h / 64)
                var accX = 0.0; var accS = 0.0; var nS = 0
                var yy = 0
                while (yy < h) {
                    val sb = src.read(top + yy, 1)
                    if (sb != null) {
                        try {
                            sb.getPixels(srow, 0, w, 0, 0, w, 1)
                        } finally {
                            sb.recycle()
                        }
                        System.arraycopy(orig, yy * w, brow, 0, w)
                        var xi = 0
                        while (xi < w) {
                            val pb = brow[xi]; val ps = srow[xi]
                            yB[xi] = 0.2126f * ((pb shr 16) and 0xFF) +
                                    0.7152f * ((pb shr 8) and 0xFF) +
                                    0.0722f * (pb and 0xFF)
                            yS[xi] = 0.2126f * ((ps shr 16) and 0xFF) +
                                    0.7152f * ((ps shr 8) and 0xFF) +
                                    0.0722f * (ps and 0xFF)
                            xi++
                        }
                        var ex = 0.0; var es = 0.0
                        xi = 1
                        while (xi < w - 1) {
                            ex += Math.abs(yB[xi] - 0.5f * (yB[xi - 1] + yB[xi + 1]))
                            es += Math.abs(yS[xi] - 0.5f * (yS[xi - 1] + yS[xi + 1]))
                            xi++
                        }
                        accX += ex / (w - 2); accS += es / (w - 2); nS++
                    }
                    yy += stepS
                }
                if (nS > 0) {
                    val sx = accX / nS
                    val ss = accS / nS
                    statSx = sx.toFloat(); statSs = ss.toFloat()
                    if (sx > 1e-3) {
                        kEff = (1.5 * (sx - ss) / sx).coerceIn(0.0, K.toDouble()).toFloat()
                    }
                }
            }
            var sy = 0
            while (sy < h) {
                val rows = min(STRIP, h - sy)
                val readTop = maxOf(0, sy - R)
                val readEnd = min(h, sy + rows + R)
                val readRows = readEnd - readTop
                System.arraycopy(orig, readTop * w, sPx, 0, readRows * w)
                // 水平盒（逐行，边界钳位）
                var ry = 0
                while (ry < readRows) {
                    val src = ry * w
                    val dst = ry * w
                    var x = 0
                    while (x < w) {
                        val x0 = maxOf(x - R, 0)
                        val x1 = min(x + R, w - 1)
                        var sr = 0f; var sg = 0f; var sb = 0f
                        var xx = x0
                        while (xx <= x1) {
                            val p = sPx[src + xx]
                            sr += (p shr 16) and 0xFF
                            sg += (p shr 8) and 0xFF
                            sb += p and 0xFF
                            xx++
                        }
                        val n = (x1 - x0 + 1).toFloat()
                        val o = dst + x
                        hBuf[o * 3] = sr / n
                        hBuf[o * 3 + 1] = sg / n
                        hBuf[o * 3 + 2] = sb / n
                        x++
                    }
                    ry++
                }
                // 垂直盒（列向，条带内钳位）
                ry = 0
                while (ry < readRows) {
                    val y0 = maxOf(ry - R, 0)
                    val y1 = min(ry + R, readRows - 1)
                    var x = 0
                    while (x < w) {
                        var sr = 0f; var sg = 0f; var sb = 0f
                        var yy = y0
                        while (yy <= y1) {
                            val o = (yy * w + x) * 3
                            sr += hBuf[o]; sg += hBuf[o + 1]; sb += hBuf[o + 2]
                            yy++
                        }
                        val n = (y1 - y0 + 1).toFloat()
                        val o = (ry * w + x) * 3
                        mBuf[o] = sr / n
                        mBuf[o + 1] = sg / n
                        mBuf[o + 2] = sb / n
                        x++
                    }
                    ry++
                }
                // 门控混合写回（本带中心 rows 行）
                val invDs = 1f / DS
                ry = R
                while (ry < R + rows) {
                    val imgY = sy + (ry - R)
                    val fy = (imgY + 0.5f) * invDs - 0.5f
                    val gy0 = fy.toInt().coerceIn(0, gh - 1)
                    val gy1 = min(gy0 + 1, gh - 1)
                    val wy = (fy - gy0).coerceIn(0f, 1f)
                    val r0 = gy0 * gw
                    val r1 = gy1 * gw
                    val srcRow = ry * w
                    var x = 0
                    while (x < w) {
                        val fx = (x + 0.5f) * invDs - 0.5f
                        val gx0 = fx.toInt().coerceIn(0, gw - 1)
                        val gx1 = min(gx0 + 1, gw - 1)
                        val wx = (fx - gx0).coerceIn(0f, 1f)
                        val a00 = A[r0 + gx0] * (1 - wx) + A[r0 + gx1] * wx
                        val a10 = A[r1 + gx0] * (1 - wx) + A[r1 + gx1] * wx
                        val av = a00 * (1 - wy) + a10 * wy
                        var wgt = 1f - av / T0
                        if (wgt <= 0f) { nSkip++; x++; continue }
                        if (wgt > 1f) wgt = 1f
                        wgt = kEff * wgt * wgt
                        nBlend++; wSum += wgt
                        val p = sPx[srcRow + x]
                        val o3 = (srcRow + x) * 3
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val nr = r + wgt * (mBuf[o3] - r)
                        val ng = g + wgt * (mBuf[o3 + 1] - g)
                        val nb = b + wgt * (mBuf[o3 + 2] - b)
                        sPx[srcRow + x] = (p and 0xFF000000.toInt()) or
                                (nr.roundToInt().coerceIn(0, 255) shl 16) or
                                (ng.roundToInt().coerceIn(0, 255) shl 8) or
                                nb.roundToInt().coerceIn(0, 255)
                        x++
                    }
                    ry++
                }
                // 处理结果在 sPx 第 R 行起（前 R 行是 halo），写回必须带 R*w 偏移
                band.setPixels(sPx, R * w, w, 0, sy, w, rows)
                sy += rows
            }
            val total = nBlend + nSkip
            lastStat = String.format(
                "blend=%.1f%% meanWgt=%.3f kEff=%.2f sigX=%.2f sigSrc=%.2f",
                100.0 * nBlend / maxOf(total, 1L),
                if (nBlend > 0) wSum / nBlend else 0.0,
                kEff, statSx, statSs
            )
            return System.currentTimeMillis() - t0
        } catch (t: Throwable) {
            DiagLog.e(TAG, "弱对比高频衰减失败（跳过，不影响导出）", t)
            lastStat = "FAILED"
            return -1
        }
    }

    /** 最近一次 [apply] 的诊断摘要（首带日志用；真机验证降噪是否真生效看这项） */
    @Volatile
    var lastStat: String = ""
        private set

    /** 网格 3×3 盒模糊（两遍可分离，边界钳位），原地语义返回新数组 */
    private fun box3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = maxOf(x - 1, 0)
                val x1 = min(x + 1, w - 1)
                tmp[row + x] = (src[row + x0] + src[row + x] + src[row + x1]) / 3f
            }
        }
        for (y in 0 until h) {
            val y0 = maxOf(y - 1, 0)
            val y1 = min(y + 1, h - 1)
            val r0 = y0 * w
            val r1 = y * w
            val r2 = y1 * w
            for (x in 0 until w) {
                out[x + r1] = (tmp[x + r0] + tmp[x + r1] + tmp[x + r2]) / 3f
            }
        }
        return out
    }
}

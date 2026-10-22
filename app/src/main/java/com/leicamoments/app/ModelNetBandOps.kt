package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * M6 自定义模型风格（aff/knots）的全像素条带算子。
 *
 * 风格本身逐像素可条带化；只有两处是全图量：
 *  - neutralize 的灰世界增益与 0.5/99.5 分位曝光：在 [prepare] 里用 2048 长边采样算一次；
 *  - protectWeight 的 1/8 网格 sky/skin 掩码：在 native 侧用每条带的 ext 窗口全分辨率重建。
 *
 * [apply] 收到的 band 只有 core 行，所以会再向 [ImageUtils.StripSource] 读上下 halo
 * （56px = 7 个 8px 掩码格 + 1 行 3×3 平滑邻域），拼成一个 ext 窗口交给 native；
 * native 只输出 core 行，掩码与平滑在带边界处与整图等价。
 *
 * 只支持到 50MP（52,000,000 像素），200MP 仍走原有工作版导出。
 */
class ModelNetBandOps(
    private val style: AdaptiveColorNet.Style,
    private val levels: IntArray
) : ImageUtils.StripBandOp {

    companion object {
        /** 50MP 机型常见输出为 8192×6144=50,331,648，留一点余量。 */
        const val MAX_PIXELS = 52_000_000L
        private const val HALO = 56
        private const val MASK_DIV = 8
        private const val STATS_SAMPLE = 2048

        fun supports(width: Int, height: Int): Boolean =
            width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_PIXELS

        private fun alignUp(v: Int): Int = ((v + MASK_DIV - 1) / MASK_DIV) * MASK_DIV
    }

    private var source: ImageUtils.StripSource? = null
    private var stats: AdaptiveColorNet.Stats? = null
    private var width = 0
    private var height = 0

    override fun prepare(reader: ImageUtils.StripSource): Boolean {
        width = reader.width
        height = reader.height
        if (!supports(width, height)) return false

        val sample = reader.sample(STATS_SAMPLE) ?: return false
        stats = try {
            AdaptiveColorNet.statsOf(sample)
        } finally {
            sample.recycle()
        }
        if (stats == null) {
            DiagLog.w("ModelNetBand", "全局统计失败，回退工作版")
            return false
        }
        source = reader
        return true
    }

    override fun apply(band: Bitmap, top: Int, rows: Int, fullW: Int, fullH: Int): Bitmap? {
        val st = stats ?: return null
        val src = source ?: return null
        if (fullW <= 0 || fullH <= 0 || rows <= 0) return null

        // extTop 必须落在 8px 网格上，protect mask 才能与整图 1/8 网格对齐。
        val extTop = max(0, (top - HALO) / MASK_DIV * MASK_DIV)
        val extBottom = min(fullH, alignUp(top + rows + HALO))
        val extH = extBottom - extTop
        val coreStart = top - extTop
        val bottomRows = extH - coreStart - rows
        if (extH <= 0 || coreStart < 0 || bottomRows < 0) {
            DiagLog.w("ModelNetBand", "非法窗口 top=$top rows=$rows ext=$extTop+$extH")
            return null
        }

        val px = IntArray(fullW * extH)
        try {
            // core 行用 barcode 已解码的 band，避免重复解码；上下 halo 再单独读。
            band.getPixels(px, coreStart * fullW, fullW, 0, 0, fullW, rows)
            if (coreStart > 0) {
                val topBmp = src.read(extTop, coreStart) ?: return null
                try {
                    topBmp.getPixels(px, 0, fullW, 0, 0, fullW, coreStart)
                } finally {
                    topBmp.recycle()
                }
            }
            if (bottomRows > 0) {
                val botBmp = src.read(top + rows, bottomRows) ?: return null
                try {
                    botBmp.getPixels(px, (coreStart + rows) * fullW, fullW, 0, 0, fullW, bottomRows)
                } finally {
                    botBmp.recycle()
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("ModelNetBand", "ext 窗口拼装失败：${t.message}")
            return null
        } finally {
            // 实现方返回新 Bitmap 时必须自行回收原 band（ImageUtils 的约定）。
            band.recycle()
        }

        val ok = AdaptiveColorNet.applyPrepared(
            px, fullW, extH, style, st,
            extTop, fullH, coreStart, rows,
            protect = true, guard = true, smooth = true, strength = 1f
        )
        if (!ok) return null

        return try {
            val core = Bitmap.createBitmap(fullW, rows, Bitmap.Config.ARGB_8888)
            core.setPixels(px, coreStart * fullW, fullW, 0, 0, fullW, rows)
            LevelStack.apply(core, levels)
        } catch (t: Throwable) {
            DiagLog.w("ModelNetBand", "core 输出失败：${t.message}")
            null
        }
    }

    override fun release() {
        source = null
        stats = null
    }
}

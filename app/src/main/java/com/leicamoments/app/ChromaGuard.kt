package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 出口色度护栏。
 *
 * 背景（200MP 全像素导出实测）：暖调 LUT 之后，暖黄色主体（奶油黄杯身）的蓝通道被压到 0~3，
 * 接着被「大增益饱和度/暖偏」继续推离亮度、直接削顶成 0 —— 亮部蓝通道 7.1% 像素精确为 0
 * （原图 1.3%），平坦区蓝通道横向层次从 60 级塌成 6 级，观感即「荧光色块 + 塑料感」；
 * 削顶区残留的抖动/颗粒还会被放大成可见噪声。同时逐带色调修正对最暗几级允许 -64 下压，
 * 会把整片钉死在 0 上，并让逐格修正量差异显形成块。
 *
 * 三个动作都只作用于「已经越界或即将越界」的像素/档位，正常像素零改动：
 *  1) [satGain]：饱和限幅（仅大增益后推链）。像素最小通道越低，饱和增益越向 1.0 收，
 *     不再把偏低的通道继续推离亮度 —— 削顶的直接机理。
 *  2) [knee] 的削顶型高饱和回缩：饱和度很高 **且** 最小通道已低到不可用（荧光黄/青的典型
 *     形态）时，把色差向亮度回缩。这一动作会同时把被压死的通道拉回可用范围，等于用「降一点
 *     饱和」换回「整条色阶」。正常的红花/蓝天最小通道不低，不会被碰到。
 *  3) [knee] 的抬底：仍低于 [FLOOR] 的通道做三通道等量平移（保色相、保单调、不碰真暗部）。
 *     [TONE_LOW_GUARD]/[TONE_LOW_DEV] 则给逐带色调修正的最暗档做下压限幅。
 */
internal object ChromaGuard {

    /** 饱和回缩的起点饱和度。 */
    const val SAT_MAX = 0.85f

    /** 超过 [SAT_MAX] 的部分只保留这么多（软压缩），避免整体降饱和。 */
    const val SAT_SOFT = 0.35f

    /** 最小通道低于此值才认为「通道已被压到不可用」，是饱和回缩的准入条件。 */
    const val LOW_CH = 24f

    /** 抬底目标下限（码值）。 */
    const val FLOOR = 10f

    /** 抬底强度（0~1）：把低于下限的通道抬起 (FLOOR - lo) * LIFT。 */
    const val LIFT = 0.5f

    /**
     * 明显色度判定：亮度与最小通道之差超过此值，才认为该像素是「低通道被压塌」而需要抬底。
     * 避免把真正的深暗部（RGB 都很低）整体提亮、破坏黑位。
     */
    const val CHROMA_MIN = 12f

    /** 自适应饱和增益的起点最小通道：低于此值开始让出增益。 */
    const val SAT_KNEE = 26f

    /** 色调修正表：亮度码值低于此值视为「最暗档」，负向修正幅度收紧到 [TONE_LOW_DEV]。 */
    const val TONE_LOW_GUARD = 24f

    /** 最暗档允许的最大下压量（码值）。原口径是 MAX_DEV=64，会把低值通道整片钉到 0。 */
    const val TONE_LOW_DEV = 4f

    /**
     * 自适应饱和度（给矩阵式后推用）：minCh 越小，增益越贴近 1.0；minCh ≥ [SAT_KNEE] 时等于原 sat。
     * 用 sqrt 收到低端，避免整体降饱和改变风格。
     */
    fun satGain(minCh: Float, sat: Float): Float {
        if (sat <= 1f) return sat
        val t = (minCh / SAT_KNEE).coerceIn(0f, 1f)
        return 1f + (sat - 1f) * sqrt(t)
    }

    /**
     * 出口护栏（原地）：削顶型高饱和回缩 + 抬底。
     * 逐行读写，不额外分配整图内存；最小通道够高的像素走最短路直接跳过。
     */
    fun knee(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0
        val row = IntArray(w)
        var hits = 0
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var touched = false
            for (i in 0 until w) {
                val p = row[i]
                val r0 = (p shr 16) and 0xFF
                val g0 = (p shr 8) and 0xFF
                val b0 = p and 0xFF
                val mn0 = if (r0 < g0) { if (r0 < b0) r0 else b0 } else { if (g0 < b0) g0 else b0 }
                // 快速跳过：最小通道够高时两个动作都不可能触发
                if (mn0 >= LOW_CH) continue

                var fr = r0.toFloat()
                var fg = g0.toFloat()
                var fb = b0.toFloat()
                val lum = 0.299f * fr + 0.587f * fg + 0.114f * fb
                var mx = if (fr > fg) { if (fr > fb) fr else fb } else { if (fg > fb) fg else fb }
                var mn = mn0.toFloat()

                // 1) 削顶型高饱和：通道已被压到不可用 + 饱和度很高 → 色差向亮度回缩
                if (mx > 0.5f) {
                    val s = (mx - mn) / mx
                    if (s > SAT_MAX) {
                        val scale = (SAT_MAX + (s - SAT_MAX) * SAT_SOFT) / s
                        fr = lum + (fr - lum) * scale
                        fg = lum + (fg - lum) * scale
                        fb = lum + (fb - lum) * scale
                        mx = if (fr > fg) { if (fr > fb) fr else fb } else { if (fg > fb) fg else fb }
                        mn = if (fr < fg) { if (fr < fb) fr else fb } else { if (fg < fb) fg else fb }
                    }
                }

                // 2) 抬底：仍低于下限的通道三通道等量平移（保色相、保单调、不碰真暗部）
                if (mn < FLOOR && lum - mn > CHROMA_MIN) {
                    val shift = (FLOOR - mn) * LIFT
                    if (shift >= 0.5f) {
                        fr += shift
                        fg += shift
                        fb += shift
                    }
                }

                val nr = fr.roundToInt().coerceIn(0, 255)
                val ng = fg.roundToInt().coerceIn(0, 255)
                val nb = fb.roundToInt().coerceIn(0, 255)
                if (nr != r0 || ng != g0 || nb != b0) {
                    row[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    touched = true
                    hits++
                }
            }
            if (touched) bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return hits
    }
}

package com.lmcamera.app

import com.lmcamera.app.AdaptiveColorNet.isMono

/**
 * 风格画像：把用户创建色彩的参数翻译成 AI 与用户都读得懂的标签。
 *
 * 纯函数、毫秒级、零网络——在 [MainActivity.styleCatalog] 注入路径上现算，
 * 存量旧模型 / 分享导入 / 备份恢复自动覆盖，零迁移代码。
 *
 * 数据源（优先级）：
 * 1. M6 模型（[CustomStyleDef.modelStyle]，aff 12 = 3×4 Lab 矩阵 + 偏移，
 *    knots 27 = L/a/b 三通路 × 9 控制点输出曲线，域 L:0..1、a/b:-1..1）；
 * 2. 回落 [CustomStyleDef.seeds] 聚合（旧 LUT/θ 色彩；base = satBase/contrast/gamma/blackLift）。
 *
 * 阈值都留了恒等裕量（恒等风格不出任何标签），装机后按观感微调即可。
 */
object StyleProfile {

    data class Profile(val tags: List<String>, val summary: String)

    fun of(def: CustomStyleDef): Profile {
        val tags = ArrayList<String>(4)
        val ms = def.modelStyle
        if (ms != null && ms.first.size >= 12 && ms.second.size >= 27) {
            tags.addAll(fromNet(ms.first, ms.second))
        } else if (def.seeds.isNotEmpty()) {
            tags.addAll(fromSeeds(def.seeds))
        }
        val clipped = tags.distinct().take(4)
        return Profile(clipped, clipped.joinToString(" · "))
    }

    // ---- M6：aff/knots → 标签（域见类注） ----

    private fun fromNet(aff: FloatArray, kn: FloatArray): List<String> {
        val tags = ArrayList<String>(4)
        if (AdaptiveColorNet.Style(aff, kn).isMono()) {
            tags.add("黑白")
            return tags
        }
        // 中性点（L=0.5, a=0, b=0）经矩阵与输出曲线后的 a/b 偏移 → 暖冷倾向
        val aMid = curve(aff[4] * 0.5f + aff[7], kn, 9, -1f, 1f)
        val bMid = curve(aff[8] * 0.5f + aff[11], kn, 18, -1f, 1f)
        if (bMid > 0.04f) tags.add("暖调") else if (bMid < -0.04f) tags.add("冷调")
        if (aMid > 0.06f) tags.add("品红倾向") else if (aMid < -0.06f) tags.add("青绿倾向")
        // 彩度增益：a/b 两通路的线性系数均值（恒等 = 1）
        val satGain = (aff[5] + aff[10]) / 2f
        if (satGain > 1.08f) tags.add("高饱和") else if (satGain < 0.92f) tags.add("低饱和")
        // L 曲线：两端跨度 → 对比；中点 → 明暗；黑位 → 哑光
        val kL = kn
        val span = kL[8] - kL[0]
        if (span > 1.06f) tags.add("高对比") else if (span < 0.94f) tags.add("柔和")
        val mid = (kL[3] + kL[4] + kL[5]) / 3f
        if (mid > 0.535f) tags.add("提亮") else if (mid < 0.465f) tags.add("压暗")
        if (kL[0] > 0.06f) tags.add("哑光")
        // B 曲线：阴影端压低 + 高光端抬高 → 青橙分离色调（恒等 k[20]=-0.5 / k[24]=+0.5）
        val kB = kn
        if (kB[20] < -0.55f && kB[24] > 0.55f) tags.add("青橙")
        return tags
    }

    // ---- 旧 LUT/θ：seeds 聚合 → 标签 ----

    private fun fromSeeds(seeds: List<RefSeed>): List<String> {
        val tags = ArrayList<String>(4)
        var sat = 0f; var con = 0f; var black = 0f
        var wbR = 0f; var wbB = 0f; var n = 0
        seeds.forEach { s ->
            if (s.base.size >= 4) {
                sat += s.base[0]; con += s.base[1]; black += s.base[3]; n++
            }
            if (s.wbGain.size >= 3) { wbR += s.wbGain[0]; wbB += s.wbGain[2] }
        }
        if (n == 0) return tags
        sat /= n; con /= n; black /= n
        if (wbR > 0f && wbB > 0f) {
            if (wbR > wbB * 1.04f) tags.add("暖调") else if (wbR < wbB * 0.96f) tags.add("冷调")
        }
        if (sat < 0.15f) {
            tags.add("黑白")
            return tags
        }
        if (sat > 1.08f) tags.add("高饱和") else if (sat < 0.92f) tags.add("低饱和")
        if (con > 1.08f) tags.add("高对比") else if (con < 0.92f) tags.add("柔和")
        if (black > 0.05f) tags.add("哑光")
        return tags
    }

    /** 与 [AdaptiveColorNet] 的 Kotlin 输出曲线同口径：9 控制点线性插值（域 lo..hi）。 */
    private fun curve(v: Float, k: FloatArray, off: Int, lo: Float, hi: Float): Float {
        val pos = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * 8f
        val i0 = pos.toInt().coerceIn(0, 7)
        val f = pos - i0
        return k[off + i0] + (k[off + i0 + 1] - k[off + i0]) * f
    }
}

package com.leicamoments.app

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 端侧图像统计：给「看不见图」的纯文本模型提供可推理的数值依据。
 *
 * 口径与烘焙台脚本一致（直方图分位 / 平均色度 / 色相分布 / 亮度对比），
 * 只是把浮点指标翻译成一段中文，模型按指标推参数即可。
 */
object ImageStats {

    private const val SAMPLE_DIM = 160

    fun describe(src: Bitmap): JSONObject {
        val bmp = if (maxOf(src.width, src.height) > SAMPLE_DIM) {
            val scale = SAMPLE_DIM.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            src
        }
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (bmp !== src) bmp.recycle()

        val hist = IntArray(256)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumSat = 0.0
        var sumLum2 = 0.0
        var hueWeight = DoubleArray(12)
        var satPixels = 0

        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r; sumG += g; sumB += b
            val lum = (299 * r + 587 * g + 114 * b) / 1000
            hist[lum]++
            sumLum2 += (lum.toDouble() * lum)

            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            sumSat += sat
            if (sat > 0.15 && mx > 24) {
                satPixels++
                val hue = hueOf(r, g, b, mx, mn)
                val idx = ((hue / 30f).toInt()).coerceIn(0, 11)
                hueWeight[idx] += sat
            }
        }

        val n = px.size.coerceAtLeast(1)
        val meanLum = (sumR * 0.299 + sumG * 0.587 + sumB * 0.114) / n
        val stdLum = sqrt((sumLum2 / n) - (meanLum * meanLum)).coerceAtLeast(0.0)

        val p5 = percentile(hist, n, 0.05)
        val p50 = percentile(hist, n, 0.50)
        val p95 = percentile(hist, n, 0.95)

        var dark = 0
        var bright = 0
        for (i in 0..8) dark += hist[i]
        for (i in 247..255) bright += hist[i]

        val warmth = ((sumR - sumB).toDouble() / n) / 255.0 * 100.0
        val meanSat = sumSat / n * 100.0

        val topHues = JSONArray()
        val order = (0 until 12).sortedByDescending { hueWeight[it] }
        for (i in 0 until 3) {
            val idx = order[i]
            if (hueWeight[idx] <= 0.0) continue
            val share = if (satPixels == 0) 0.0 else hueWeight[idx] / hueWeight.sum() * 100.0
            topHues.put(
                JSONObject().apply {
                    put("hue", (idx * 30 + 15) % 360)
                    put("name", hueName(idx))
                    put("share", round1(share))
                }
            )
        }

        val json = JSONObject().apply {
            put("width", src.width)
            put("height", src.height)
            put("meanLuma", round1(meanLum))
            put("lumaP5", p5)
            put("lumaP50", p50)
            put("lumaP95", p95)
            put("contrast", round1(stdLum))
            put("shadowClipPct", round1(dark * 100.0 / n))
            put("highlightClipPct", round1(bright * 100.0 / n))
            put("meanSaturation", round1(meanSat))
            put("warmth", round1(warmth))
            put("dominantHues", topHues)
        }
        json.put("summary", toText(json))
        return json
    }

    fun toText(j: JSONObject): String {
        val mean = j.optDouble("meanLuma", 0.0)
        val tone = when {
            mean < 60 -> "整体偏暗"
            mean < 95 -> "略偏暗"
            mean < 150 -> "亮度适中"
            mean < 195 -> "略偏亮"
            else -> "整体偏亮"
        }
        val contrast = j.optDouble("contrast", 0.0)
        val cText = when {
            contrast < 32 -> "对比偏低（画面发灰）"
            contrast < 48 -> "对比中等"
            else -> "对比明显"
        }
        val sat = j.optDouble("meanSaturation", 0.0)
        val sText = when {
            sat < 18 -> "色彩很淡"
            sat < 32 -> "色彩偏淡"
            sat < 50 -> "色彩适中"
            else -> "色彩浓郁"
        }
        val warm = j.optDouble("warmth", 0.0)
        val wText = when {
            warm > 5 -> "整体偏暖"
            warm < -5 -> "整体偏冷"
            else -> "色温中性"
        }
        val shadow = j.optDouble("shadowClipPct", 0.0)
        val high = j.optDouble("highlightClipPct", 0.0)
        val clip = buildString {
            if (shadow > 3) append("死黑 ${round1(shadow)}%；")
            if (high > 3) append("高光溢出 ${round1(high)}%；")
        }
        val hues = j.optJSONArray("dominantHues")
        val hueText = if (hues != null && hues.length() > 0) {
            val names = ArrayList<String>()
            for (i in 0 until hues.length()) {
                val o = hues.optJSONObject(i) ?: continue
                names.add("${o.optString("name")} ${o.optDouble("share", 0.0)}%")
            }
            names.joinToString("、")
        } else {
            "无明显主色"
        }
        return "亮度均值 ${round1(mean)}（$tone），对比 ${round1(contrast)}（$cText），" +
            "饱和度 ${round1(sat)}（$sText），$wText；" +
            (if (clip.isEmpty()) "" else "注意：$clip") +
            "主色：$hueText。"
    }

    private fun percentile(hist: IntArray, total: Int, q: Double): Int {
        val target = (total * q).toInt()
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }

    private fun hueOf(r: Int, g: Int, b: Int, mx: Int, mn: Int): Float {
        val d = (mx - mn).toFloat()
        if (d == 0f) return 0f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return if (h < 0) h + 360f else h
    }

    private fun hueName(index: Int): String = when (index) {
        0, 11 -> "红"
        1 -> "橙"
        2 -> "黄"
        3 -> "黄绿"
        4 -> "绿"
        5 -> "青绿"
        6 -> "青"
        7 -> "蓝"
        8 -> "蓝紫"
        9 -> "紫"
        else -> "品红"
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** 供「无视觉模型」时拼进 user 消息的文本块。 */
    fun promptBlock(stats: JSONObject): String {
        return "当前照片统计：" + stats.optString("summary") +
            "\n原始指标：" + stats.toString()
    }

    fun isFlat(j: JSONObject): Boolean = abs(j.optDouble("contrast", 40.0)) < 1.0
}

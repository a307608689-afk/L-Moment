package com.leicamoments.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M7.2：端侧 CIEDE2000（抽样），给候选风格的 ΔE00 门限用（3.0 可感知 / 12.0 离群）。
 *
 * 只比较同一场景下两套风格的渲染结果，输入是两张同尺寸 Bitmap；抽样 step 像素，
 * 640px 预览约 1.1 万点，毫秒级。公式按 Sharma et al. (2005) 的标准实现。
 */
object ColorMath {

    data class Lab(val l: Float, val a: Float, val b: Float)

    fun avgDeltaE00(a: Bitmap, b: Bitmap, step: Int = 6): Float {
        if (a.width != b.width || a.height != b.height || step < 1) return Float.NaN
        var sum = 0.0
        var cnt = 0
        val pa = IntArray(a.width)
        val pb = IntArray(b.width)
        var y = 0
        while (y < a.height) {
            a.getPixels(pa, 0, a.width, 0, y, a.width, 1)
            b.getPixels(pb, 0, b.width, 0, y, b.width, 1)
            var x = 0
            while (x < a.width) {
                sum += de00(lab(pa[x]), lab(pb[x]))
                cnt++
                x += step
            }
            y += step
        }
        return if (cnt == 0) Float.NaN else (sum / cnt).toFloat()
    }

    private fun lab(argb: Int): Lab {
        val r = lin(((argb shr 16) and 0xFF) / 255.0)
        val g = lin(((argb shr 8) and 0xFF) / 255.0)
        val b = lin((argb and 0xFF) / 255.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab(
            (116.0 * fy - 16.0).toFloat(),
            (500.0 * (fx - fy)).toFloat(),
            (200.0 * (fy - fz)).toFloat()
        )
    }

    private fun lin(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun f(t: Double): Double =
        if (t > 216.0 / 24389.0) Math.cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0

    private fun de00(c1: Lab, c2: Lab): Double {
        val l1 = c1.l.toDouble(); val a1 = c1.a.toDouble(); val b1 = c1.b.toDouble()
        val l2 = c2.l.toDouble(); val a2 = c2.a.toDouble(); val b2 = c2.b.toDouble()
        val c1ab = sqrt(a1 * a1 + b1 * b1)
        val c2ab = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1ab + c2ab) / 2.0
        val g = 0.5 * (1.0 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val h1p = if (b1 == 0.0 && a1p == 0.0) 0.0 else Math.toDegrees(atan2(b1, a1p)).let { if (it < 0) it + 360 else it }
        val h2p = if (b2 == 0.0 && a2p == 0.0) 0.0 else Math.toDegrees(atan2(b2, a2p)).let { if (it < 0) it + 360 else it }
        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp: Double
        if (c1p * c2p == 0.0) dhp = 0.0
        else {
            var d = h2p - h1p
            if (d > 180) d -= 360 else if (d < -180) d += 360
            dhp = d
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))
        val lBarP = (l1 + l2) / 2.0
        val cBarP = (c1p + c2p) / 2.0
        val hBarP: Double
        if (c1p * c2p == 0.0) hBarP = h1p + h2p
        else {
            val sum = h1p + h2p
            val diff = abs(h1p - h2p)
            hBarP = when {
                diff <= 180 -> sum / 2.0
                sum < 360 -> (sum + 360) / 2.0
                else -> (sum - 360) / 2.0
            }
        }
        val t = 1.0 - 0.17 * cos(Math.toRadians(hBarP - 30)) +
            0.24 * cos(Math.toRadians(2 * hBarP)) +
            0.32 * cos(Math.toRadians(3 * hBarP + 6)) -
            0.20 * cos(Math.toRadians(4 * hBarP - 63))
        val dTheta = 30.0 * exp(-((hBarP - 275) / 25.0).pow(2))
        val rC = 2.0 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
        val sL = 1.0 + 0.015 * (lBarP - 50).pow(2) / sqrt(20.0 + (lBarP - 50).pow(2))
        val sC = 1.0 + 0.045 * cBarP
        val sH = 1.0 + 0.015 * cBarP * t
        val rT = -sin(Math.toRadians(2 * dTheta)) * rC
        val tL = dLp / sL
        val tC = dCp / sC
        val tH = dHp / sH
        return sqrt((tL * tL + tC * tC + tH * tH + rT * tC * tH).coerceAtLeast(0.0))
    }
}

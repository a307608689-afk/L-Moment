package com.leicamoments.app

/**
 * DNG/TIFF 标签读取（OffLineM9 前段所需）：只解析 IFD0 + Exif IFD 的数值/ASCII 标签，
 * 纯 Kotlin 无第三方依赖（LibRaw 拿不到 ColorMatrix 的 illuminant 配对与 AsShotNeutral 原始值）。
 */
class DngTags(private val b: ByteArray) {

    private val le: Boolean
    private val valid: Boolean
    private val nums = HashMap<Int, DoubleArray>()
    private val strs = HashMap<Int, String>()

    companion object {
        // ---- 需要的标签号 ----
        const val MAKE = 0x010F
        const val MODEL = 0x0110
        const val ORIENTATION = 0x0112
        const val EXIF_IFD = 0x8769
        const val EXPOSURE_TIME = 0x829A
        const val FNUMBER = 0x829D
        const val ISO = 0x8827
        const val FOCAL_LENGTH = 0x920A
        const val FOCAL_35MM = 0xA405
        const val BASELINE_EXPOSURE = 0xC62A
        const val AS_SHOT_NEUTRAL = 0xC628
        const val COLOR_MATRIX1 = 0xC621
        const val COLOR_MATRIX2 = 0xC622
        const val CALIB_ILLUM1 = 0xC65A
        const val CALIB_ILLUM2 = 0xC65B
    }

    init {
        le = b.size >= 8 && b[0] == 0x49.toByte() && b[1] == 0x49.toByte()
        val be = b.size >= 8 && b[0] == 0x4D.toByte() && b[1] == 0x4D.toByte()
        valid = (le || be) && b.size >= 8 && u16(2).toInt() == 42
        if (valid) {
            parseIfd(u32(4).toInt())
            nums[EXIF_IFD]?.firstOrNull()?.let { parseIfd(it.toInt()) }
        }
    }

    fun ok(): Boolean = valid

    fun doubles(tag: Int): DoubleArray? = nums[tag]

    fun value(tag: Int, def: Double = 0.0): Double = nums[tag]?.firstOrNull() ?: def

    fun string(tag: Int): String = strs[tag]?.trim() ?: ""

    // ---- TIFF 基础 ----

    private fun u16(o: Int): Int {
        if (o < 0 || o + 2 > b.size) return 0
        val a = b[o].toInt() and 0xFF
        val c = b[o + 1].toInt() and 0xFF
        return if (le) a or (c shl 8) else (a shl 8) or c
    }

    private fun u32(o: Int): Long {
        if (o < 0 || o + 4 > b.size) return 0
        var v = 0L
        for (i in 0 until 4) {
            val k = if (le) o + 3 - i else o + i
            v = (v shl 8) or (b[k].toLong() and 0xFF)
        }
        return v
    }

    private fun i32(o: Int): Int = u32(o).toInt()

    private fun typeSize(t: Int): Int = when (t) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    private fun parseIfd(off: Int) {
        if (off <= 0 || off + 2 > b.size) return
        val n = u16(off)
        for (i in 0 until n) {
            val e = off + 2 + i * 12
            if (e + 12 > b.size) return
            val tag = u16(e)
            val type = u16(e + 2)
            val count = u32(e + 4).toInt()
            if (count <= 0 || count > 4096) continue
            val sz = typeSize(type)
            val dataOff = if (sz * count <= 4) e + 8 else u32(e + 8).toInt()
            if (type == 2) {
                strs[tag] = readAscii(dataOff, count)
            } else {
                val arr = DoubleArray(count)
                for (k in 0 until count) arr[k] = readValue(type, dataOff + k * sz)
                nums[tag] = arr
            }
        }
    }

    private fun readValue(type: Int, o: Int): Double = when (type) {
        1, 7 -> (if (o < b.size) b[o].toInt() and 0xFF else 0).toDouble()
        3 -> u16(o).toDouble()
        4 -> u32(o).toDouble()
        5 -> {
            val den = u32(o + 4)
            if (den == 0L) 0.0 else u32(o).toDouble() / den.toDouble()
        }
        6 -> (if (o < b.size) b[o].toByte().toInt() else 0).toDouble()
        8 -> u16(o).toShort().toDouble()
        9 -> i32(o).toDouble()
        10 -> {
            val den = i32(o + 4)
            if (den == 0) 0.0 else i32(o).toDouble() / den.toDouble()
        }
        11 -> u32(o).toFloat().toDouble()
        12 -> {
            val den = u32(o + 4).toFloat()
            if (den == 0f) 0.0 else u32(o).toFloat() / den.toDouble()
        }
        else -> 0.0
    }

    private fun readAscii(off: Int, count: Int): String {
        if (off < 0 || off >= b.size) return ""
        val end = (off + count).coerceAtMost(b.size)
        val sb = StringBuilder(end - off)
        for (i in off until end) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}

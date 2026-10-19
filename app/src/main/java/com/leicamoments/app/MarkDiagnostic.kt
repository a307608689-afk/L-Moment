package com.leicamoments.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 导出后的标记自检（用 JDK ByteBuffer 独立解析，与 LeicaMark 的写入逻辑无关）。
 */
object MarkDiagnostic {

    fun describe(jpeg: ByteArray): String {
        val sb = StringBuilder()
        var pos = 2
        var ffe4 = false
        var exifCount = 0
        var exifDesc: String? = null
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) break
            if (marker == 0xE4) {
                val seg = jpeg.copyOfRange(pos + 4, pos + 4 + len - 2)
                val magic = if (seg.size >= 16) String(seg, 0, 16, StandardCharsets.ISO_8859_1) else ""
                ffe4 = magic.startsWith("XIAOMI_CUSTOMIZE")
            }
            if (marker == 0xE1 && len >= 10 &&
                jpeg[pos + 4] == 'E'.code.toByte() && jpeg[pos + 5] == 'x'.code.toByte()) {
                exifCount++
                if (exifDesc == null) {
                    exifDesc = parseExif(jpeg.copyOfRange(pos + 4, pos + 4 + len - 2))
                }
            }
            pos += 2 + len
        }
        sb.append(exifDesc ?: "无EXIF")
        sb.append(" Exif段数=").append(exifCount)
        sb.append(" FFE4=").append(ffe4)
        return sb.toString()
    }

    private fun parseExif(d: ByteArray): String {
        if (d.size < 14) return "EXIF短"
        val order = when {
            d[6] == 'I'.code.toByte() && d[7] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            d[6] == 'M'.code.toByte() && d[7] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return "EXIF序?"
        }
        val buf = ByteBuffer.wrap(d).order(order)
        val ifd0 = buf.getInt(10).toLong() and 0xFFFFFFFFL
        val ifd0Off = ifd0 + 6
        if (ifd0Off + 2 > d.size) return "IFD0越界"
        val n = buf.getShort(ifd0Off.toInt()).toInt() and 0xFFFF
        var make = ""
        var model = ""
        var theme0 = ""
        var exifOff = -1L
        for (i in 0 until n) {
            val e = ifd0Off + 2 + i * 12
            if (e + 12 > d.size) break
            val tag = buf.getShort(e.toInt()).toInt() and 0xFFFF
            val type = buf.getShort(e.toInt() + 2).toInt() and 0xFFFF
            val cnt = buf.getInt(e.toInt() + 4).toLong() and 0xFFFFFFFFL
            when (tag) {
                0x010F -> make = readAscii(buf, d, e, type, cnt, order)
                0x0110 -> model = readAscii(buf, d, e, type, cnt, order)
                0x889D -> theme0 = readAscii(buf, d, e, type, cnt, order)
                0x8769 -> exifOff = (buf.getInt(e.toInt() + 8).toLong() and 0xFFFFFFFFL) + 6
            }
        }
        var t = ""
        var l = ""
        var m = ""
        if (exifOff in 6 until d.size.toLong()) {
            val ex = exifOff.toInt()
            val mn = buf.getShort(ex).toInt() and 0xFFFF
            for (i in 0 until mn) {
                val e = ex + 2 + i * 12
                if (e + 12 > d.size) break
                val tag = buf.getShort(e).toInt() and 0xFFFF
                val type = buf.getShort(e + 2).toInt() and 0xFFFF
                val cnt = buf.getInt(e + 4).toLong() and 0xFFFFFFFFL
                when (tag) {
                    0x889D -> t = readAscii(buf, d, e.toLong(), type, cnt, order)
                    34992 -> l = (d[e + 8].toInt() and 0xFF).toString()
                    0xA661 -> m = readAscii(buf, d, e.toLong(), type, cnt, order)
                }
            }
        }
        return "Make=$make Model=$model theme0=$theme0 theme=$t legend=$l mode=$m"
    }

    private fun readAscii(buf: ByteBuffer, d: ByteArray, e: Long, type: Int, cnt: Long, order: ByteOrder): String {
        val sz = when (type) { 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10 -> 8; else -> 1 }
        val bytes = sz * cnt
        val off = if (bytes <= 4) e + 8 else (buf.getInt((e + 8).toInt()).toLong() and 0xFFFFFFFFL) + 6
        if (off < 0 || off + cnt > d.size) return "?"
        return String(d, off.toInt(), cnt.toInt(), StandardCharsets.ISO_8859_1).trim('\u0000')
    }
}

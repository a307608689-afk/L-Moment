package com.leicamoments.app

/**
 * 从 DNG 提取厂商内嵌的全尺寸 JPEG 预览（SubIFD，厂商 ISP 渲染，色彩最准确）。
 * 对小米 Ultra RAW 等特殊 DNG，内嵌预览就是「标准答案」。
 */
object DngPreviewExtractor {

    fun extract(bytes: ByteArray): ByteArray? {
        return try {
            if (bytes.size < 16) return null
            val le = bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte()
            val mm = bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte()
            if (!le && !mm) return null
            if (u16(bytes, 2, le) != 42L) return null // TIFF magic
            val ifd0 = u32(bytes, 4, le)
            val n = u16(bytes, ifd0.toInt(), le).toInt()
            var best: ByteArray? = null
            for (i in 0 until n) {
                val e = ifd0 + 2 + i * 12L
                if (e + 12 > bytes.size) break
                if (u16(bytes, e.toInt(), le).toInt() != 0x014A) continue // SubIFDs
                val cnt = u32(bytes, (e + 8).toInt(), le)
                for (k in 0 until minOf(cnt, 8L).toInt()) {
                    val sub = u32(bytes, (e + 8 + k * 4).toInt(), le)
                    val jpeg = extractSubIfdJpeg(bytes, sub, le) ?: continue
                    if (best == null || jpeg.size > best.size) best = jpeg
                }
            }
            best
        } catch (t: Throwable) {
            null
        }
    }

    private fun extractSubIfdJpeg(b: ByteArray, sub: Long, le: Boolean): ByteArray? {
        if (sub <= 0 || sub + 2 > b.size) return null
        val n = u16(b, sub.toInt(), le).toInt()
        var off = -1L
        var cnt = -1L
        var isJpeg = false
        for (i in 0 until n) {
            val e = sub + 2 + i * 12L
            if (e + 12 > b.size) break
            when (u16(b, e.toInt(), le).toInt()) {
                0x0111 -> off = u32(b, (e + 8).toInt(), le)                       // StripOffsets
                0x0117 -> cnt = u32(b, (e + 8).toInt(), le)                       // StripByteCounts
                0x0103 -> isJpeg = u16(b, (e + 2).toInt(), le).toInt() == 7       // Compression=JPEG
            }
        }
        if (off <= 0 || cnt <= 0 || off + cnt > b.size) return null
        if (!isJpeg) return null
        val b0 = b[off.toInt()].toInt() and 0xFF
        val b1 = b[off.toInt() + 1].toInt() and 0xFF
        if (b0 != 0xFF || b1 != 0xD8) return null
        return b.copyOfRange(off.toInt(), (off + cnt).toInt())
    }

    private fun u16(b: ByteArray, o: Int, le: Boolean): Long =
        if (le) (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8)
        else ((b[o].toLong() and 0xFF) shl 8) or (b[o + 1].toLong() and 0xFF)

    private fun u32(b: ByteArray, o: Int, le: Boolean): Long {
        var v = 0L
        for (i in 0 until 4) {
            val k = if (le) 3 - i else i
            v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        }
        return v
    }
}

package com.leicamoments.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HDR（UltraHDR 增益图）保全：
 *  - 导入期从原始 JPEG 中提取增益图（MPF 第二图 + XMP hdrgm 元数据），存为 [HdrData]；
 *  - 导出期把增益图按 libultrahdr 的装配方式回嵌进处理后的 SDR JPEG：
 *    主图插 XMP APP1（GContainer + hdrgm）→ SOS 前插 MPF APP2 → 主图 EOI 后紧跟增益图。
 *
 * 字节布局逐项对齐 google/libultrahdr（generateMpf / generateXmpForPrimaryImage /
 * generateXmpForSecondaryImage / JpegR::appendGainMap），Android 14+ 平台解码器与
 * 主流相册按 UltraHDR 规范读取；不支持的设备自动退回 SDR 主图，零副作用。
 */
object HdrGainMap {

    /** 提取出的增益图及其元数据（各值为原文件 XMP 的原样字符串，Min/Max/Capacity 为 log2 域） */
    class HdrData(
        val gainmapJpeg: ByteArray,
        val minLog2: String,
        val maxLog2: String,
        val gamma: String,
        val offsetSdr: String,
        val offsetHdr: String,
        val capMinLog2: String,
        val capMaxLog2: String,
        /** 原图主图 XMP 原文（回嵌时被丢弃；留作容器路径取证与徽章继承改写的数据源） */
        val sourceXmp: String?
    )

    /** 装配自检结果：[brief] 供 toast 一行显示，[detail] 进 DiagLog 供排查 */
    class Check(val ok: Boolean, val brief: String, val detail: String)

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val DEFAULT_OFFSET = "0.015625" // 1/64，UltraHDR 规范缺省

    private class Segment(
        val src: ByteArray,
        val marker: Int,
        val segStart: Int,
        val dataStart: Int,
        val dataLength: Int
    )

    // ---- 导入：检测 + 提取 ----

    /**
     * 从原始 JPEG 提取增益图。返回 null 表示非 HDR 或提取失败（调用方维持 SDR 现状）。
     * 必须在动态照片截断/任何重编码之前、对最原始字节调用。
     */
    fun extract(originalJpeg: ByteArray): HdrData? {
        try {
            if (!isJpeg(originalJpeg)) return null
            var xmpXml: String? = null
            var mpfSeg: Segment? = null
            forEachSegment(originalJpeg) { seg ->
                when (seg.marker) {
                    0xE1 -> if (xmpXml == null && startsWith(seg, XMP_NS.toByteArray())) {
                        xmpXml = String(seg.src, seg.dataStart, seg.dataLength, Charsets.UTF_8)
                    }
                    0xE2 -> if (mpfSeg == null && startsWith(seg, "MPF\u0000".toByteArray())) mpfSeg = seg
                }
                false
            }
            val mpf = mpfSeg ?: return null
            if (mpf.dataLength < 12) return null

            // ---- 解析 MPF TIFF：定位第二图（增益图）。offset 以 MP Endian 字段（"MPF\0" 之后）为基准 ----
            val j = originalJpeg
            val be = j[mpf.dataStart] == 'M'.code.toByte()
            fun u16(off: Int) = u16At(j, off, be)
            fun u32(off: Int) = u32At(j, off, be)

            val tiffBase = mpf.dataStart + 4
            if (tiffBase + 8 > j.size) return null
            val ifdPos = tiffBase + u32(mpf.dataStart + 8)
            if (ifdPos < 0 || ifdPos + 2 > j.size) return null
            val tagCount = u16(ifdPos)
            var entryPos = -1
            var q = ifdPos + 2
            for (i in 0 until tagCount) {
                if (q + 12 > j.size) return null
                if (u16(q) == 0xB002) { entryPos = tiffBase + u32(q + 8); break }
                q += 12
            }
            if (entryPos < 0 || entryPos + 32 > j.size) return null

            val secSize = u32(entryPos + 16 + 4)
            val secOffset = u32(entryPos + 16 + 8)
            if (secSize <= 0 || secOffset <= 0) return null
            val secAbs = tiffBase + secOffset
            if (secAbs < 0 || secAbs + secSize > j.size) return null
            val sec = j.copyOfRange(secAbs, secAbs + secSize)
            if (!isJpeg(sec)) return null

            // ---- 校验增益图可解码（增益图通常为主图 1/4 分辨率，解码一次成本很低）----
            val probe = BitmapFactory.decodeByteArray(sec, 0, sec.size) ?: return null

            // ---- 方向跟随：主图若因 EXIF 方向被归一旋转，增益图需同矩阵旋转 ----
            val gainBytes: ByteArray = try {
                val exif = ExifInterface(ByteArrayInputStream(originalJpeg))
                val rot = exif.rotationDegrees
                val flip = exif.isFlipped
                if (rot == 0 && !flip) sec else rotateJpeg(probe, rot, flip)
            } catch (t: Throwable) {
                sec
            }
            probe.recycle()

            // ---- 元数据：优先主图 XMP hdrgm（log2 域），缺失时回退增益图自身 XMP，再缺失用规范缺省 ----
            if (xmpXml == null) {
                forEachSegment(sec) { s ->
                    if (s.marker == 0xE1 && startsWith(s, XMP_NS.toByteArray())) {
                        xmpXml = String(s.src, s.dataStart, s.dataLength, Charsets.UTF_8)
                        true
                    } else false
                }
            }
            val xml = xmpXml
            fun attr(name: String): String? = xml?.let { s ->
                Regex("$name\\s*=\\s*[\"']([^\"']*)[\"']").find(s)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            val maxL2 = sanitizeLog2(attr("hdrgm:GainMapMax") ?: attr("hdrgm:GainMapMAX"), "1.0")
            val minL2 = sanitizeLog2(attr("hdrgm:GainMapMin"), "0.0")
            val capMax = sanitizeLog2(attr("hdrgm:HDRCapacityMax"), maxL2)
            val capMin = sanitizeLog2(attr("hdrgm:HDRCapacityMin"), "0.0")
            val gamma = sanitizeLinear(attr("hdrgm:Gamma"), "1")
            val offSdr = sanitizeLinear(attr("hdrgm:OffsetSDR") ?: attr("hdrgm:HDROffsetSDR"), DEFAULT_OFFSET)
            val offHdr = sanitizeLinear(attr("hdrgm:OffsetHDR") ?: attr("hdrgm:HDROffsetHDR"), DEFAULT_OFFSET)

            val pSize = jpegSize(originalJpeg)
            val gSize = jpegSize(gainBytes)
            DiagLog.i("HdrGainMap", "提取增益图 ${gainBytes.size}B ${gSize.first}x${gSize.second}" +
                    " 主图 ${pSize.first}x${pSize.second} 元数据 min=$minL2 max=$maxL2 gamma=$gamma")
            // 容器路径徽章取证：原图 XMP 里有哪些命名空间/属性（导出时自造 XMP 会丢弃它们）
            DiagLog.i("HdrGainMap", "原图 XMP ${xml?.length ?: 0}字符 字段: ${xmpFieldSummary(xml)}")
            return HdrData(gainBytes, minL2, maxL2, gamma, offSdr, offHdr, capMin, capMax, xml)
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "提取异常: ${t.message}")
            return null
        }
    }

    // ---- 导出：回嵌 UltraHDR ----

    /**
     * 把增益图回嵌进处理完成的 SDR JPEG（徽章已写入的字节）。
     * 幂等：先剥掉已有的 XMP/MPF/ISO 段再装配。任何异常返回原字节（SDR 兜底）。
     */
    fun embed(primaryJpeg: ByteArray, data: HdrData): ByteArray {
        try {
            if (!isJpeg(primaryJpeg) || !isJpeg(data.gainmapJpeg)) return primaryJpeg
            val stripped = stripHdrSegments(primaryJpeg)
            // 截断到主图 EOI：保证输入为纯单图（若已含二次图/尾数据则丢弃），装配幂等
            val base = truncateAtEoi(stripped)

            // 1) 增益图：无自身 XMP 则补一份（hdrgm 元数据随二次图走）
            val sec = ensureSecondaryXmp(data.gainmapJpeg, data)

            // 2) 主图 XMP APP1（GContainer + hdrgm），插在前部 APPn 链之后
            val xmpBlock = buildApp1(primaryXmp(sec.size, data))
            var ins = 2
            var pos = 2
            while (pos + 4 <= base.size && (base[pos].toInt() and 0xFF) == 0xFF) {
                val m = base[pos + 1].toInt() and 0xFF
                if (m in 0xE0..0xEF) {
                    val len = ((base[pos + 2].toInt() and 0xFF) shl 8) or (base[pos + 3].toInt() and 0xFF)
                    if (len < 2 || pos + 2 + len > base.size) break
                    pos += 2 + len
                    ins = pos
                } else break
            }
            val withXmp = ByteArray(base.size + xmpBlock.size)
            System.arraycopy(base, 0, withXmp, 0, ins)
            System.arraycopy(xmpBlock, 0, withXmp, ins, xmpBlock.size)
            System.arraycopy(base, ins, withXmp, ins + xmpBlock.size, base.size - ins)

            // 3) MPF APP2 插在 SOS 之前（libultrahdr 装配语义），增益图紧跟主图 EOI
            val sos = findSos(withXmp)
            if (sos <= 0) return primaryJpeg
            val primaryTotal = withXmp.size + 90 // + FF E2(2) + 段长(2) + MPF 载荷(86)
            val secondaryOffset = primaryTotal - sos - 8 // 相对 MP Endian 字段（"MPF\0" 之后）
            val mpfBlock = ByteArray(90)
            mpfBlock[0] = 0xFF.toByte(); mpfBlock[1] = 0xE2.toByte()
            mpfBlock[2] = 0x00; mpfBlock[3] = 0x58 // 段长 88 = 2 + 86
            System.arraycopy(buildMpfPayload(primaryTotal, sec.size, secondaryOffset), 0, mpfBlock, 4, 86)

            val out = ByteArray(primaryTotal + sec.size)
            System.arraycopy(withXmp, 0, out, 0, sos)
            System.arraycopy(mpfBlock, 0, out, sos, 90)
            System.arraycopy(withXmp, sos, out, sos + 90, withXmp.size - sos)
            System.arraycopy(sec, 0, out, primaryTotal, sec.size)

            DiagLog.i("HdrGainMap", "UltraHDR 回嵌完成 主图 ${withXmp.size}B + 增益图 ${sec.size}B")
            return out
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "回嵌异常: ${t.message}")
            return primaryJpeg
        }
    }

    /**
     * 只保留主图：截断到主图 EOI，丢弃 EOI 之后残留的二次图（增益图 / 动态照片尾数据）。
     *
     * 用于「没有装配 UltraHDR」的导出：导入期已判定要走缩放重编码（hdr=null、不回嵌），
     * 但处理链输出仍可能带着与主图不再匹配的增益图尾数据——主图被缩小、增益图还是原尺寸，
     * 读端按 UltraHDR 渲染会发灰或高光断层。此时必须显式裁掉，保证导出是纯单图。
     */
    fun truncateToPrimary(jpeg: ByteArray): ByteArray = try {
        if (!isJpeg(jpeg)) {
            jpeg
        } else {
            val out = truncateAtEoi(jpeg)
            if (out.size != jpeg.size) {
                DiagLog.i("HdrGainMap", "剥离主图 EOI 后残留 ${jpeg.size - out.size}B" +
                        "（未装配 UltraHDR，残留增益图与缩放后主图不匹配）")
            }
            out
        }
    } catch (t: Throwable) {
        jpeg
    }

    // ---- 装配结果自检（纯字节回读，不依赖平台解码器/相册）----

    /**
     * 回读已装配的字节，报告 UltraHDR 容器结构：主图 XMP(hdrgm + Container 目录)、MPF 二次图指针、
     * 增益图存在性与几何比。判定 ok 的三要素：增益图存在、XMP 含 Container 目录、
     * 目录长度与 MPF 二次图长度均等于增益图实际字节数（不一致即读端可能取错长度）。
     */
    fun verify(jpeg: ByteArray): Check {
        try {
            if (!isJpeg(jpeg)) return Check(false, "HDR=非JPEG", "非 JPEG 字节")
            var xmp: String? = null
            var mpf: Segment? = null
            var iso = false
            forEachSegment(jpeg) { seg ->
                when {
                    seg.marker == 0xE1 && xmp == null && startsWith(seg, XMP_NS.toByteArray()) ->
                        xmp = String(seg.src, seg.dataStart, seg.dataLength, Charsets.UTF_8)
                    seg.marker == 0xE2 && mpf == null && startsWith(seg, "MPF\u0000".toByteArray()) -> mpf = seg
                    seg.marker == 0xE2 && startsWith(seg, "urn:".toByteArray()) -> iso = true
                }
                false
            }
            val eoi = mainEoi(jpeg)
            val secFrom = if (eoi > 0) eoi else jpeg.size
            val secSize = jpeg.size - secFrom
            val gSize = sizeAt(jpeg, secFrom)
            // 存在性只看 EOI 之后是否有合法 SOI，不依赖 SOF 解析成功（解析不出只影响比例显示）
            val hasSec = secSize > 4 && matchAt(jpeg, secFrom, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            val pSize = jpegSize(jpeg)
            val dirLen = xmp?.let { Regex("<Item:Length>(\\d+)<").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
            var mpfCount = 0
            var mpfSecSize = -1
            val mpfSeg = mpf
            if (mpfSeg != null && mpfSeg.dataLength >= 12) {
                val be = jpeg[mpfSeg.dataStart] == 'M'.code.toByte()
                val tiffBase = mpfSeg.dataStart + 4
                val ifdPos = tiffBase + u32At(jpeg, mpfSeg.dataStart + 8, be)
                if (ifdPos in 2 until jpeg.size) {
                    val n = u16At(jpeg, ifdPos, be)
                    var q = ifdPos + 2
                    var entry0 = -1
                    for (i in 0 until n) {
                        if (q + 12 > jpeg.size) break
                        when (u16At(jpeg, q, be)) {
                            0xB001 -> mpfCount = u32At(jpeg, q + 8, be)
                            0xB002 -> entry0 = tiffBase + u32At(jpeg, q + 8, be)
                        }
                        q += 12
                    }
                    if (entry0 in 2 until jpeg.size && entry0 + 32 <= jpeg.size) {
                        mpfSecSize = u32At(jpeg, entry0 + 16 + 4, be)
                    }
                }
            }
            val ok = hasSec && dirLen == secSize && mpfSecSize == secSize
            val brief = when {
                !hasSec -> "HDR=无增益图"
                ok -> "HDR=增益图 ok"
                else -> "HDR=增益图 有疑问"
            }
            val ratio = if (gSize.first > 0 && pSize.first > 0) pSize.first / gSize.first else 0
            val detail = "主图 ${pSize.first}x${pSize.second} 增益图 ${gSize.first}x${gSize.second}" +
                    " 比例1/${if (ratio > 0) ratio else "?"} ${secSize}B" +
                    " | XMP ${xmp?.length ?: 0}字符 hdrgm=${xmp?.contains("hdrgm:GainMapMax") == true}" +
                    " 目录=${xmp?.contains("Container:Directory") == true} 目录长=$dirLen" +
                    " | MPF=${mpfSeg != null}/$mpfCount 二次图长=$mpfSecSize ISO=$iso"
            DiagLog.i("HdrGainMap", "导出自检 $brief | $detail")
            return Check(ok, brief, detail)
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "自检异常: ${t.message}")
            return Check(false, "HDR=自检异常", "自检异常 ${t.message}")
        }
    }

    // ---- MPF 载荷（86 字节，大端，逐字段对齐 libultrahdr generateMpf）----

    private fun buildMpfPayload(primarySize: Int, secondarySize: Int, secondaryOffset: Int): ByteArray {
        val b = ByteBuffer.allocate(86).order(ByteOrder.BIG_ENDIAN)
        b.put("MPF\u0000".toByteArray())
        b.put(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)) // "MM\0*" 大端 TIFF 头
        b.putInt(8)                                 // Index IFD offset（相对 MP Endian 字段）
        b.putShort(3)                               // tag 数
        b.putShort(0xB000.toShort()); b.putShort(7); b.putInt(4); b.put("0100".toByteArray())   // MPFVersion
        b.putShort(0xB001.toShort()); b.putShort(4); b.putInt(1); b.putInt(2)                    // NumberOfImages
        b.putShort(0xB002.toShort()); b.putShort(7); b.putInt(32); b.putInt(50)                  // MPEntry 值区偏移（载荷 54 - 基准 4，对齐 libultrahdr）
        b.putInt(0)                                 // Attribute IFD offset（不写）
        // Entry 0：主图（0x030000 = Primary），offset 0
        b.putInt(0x030000); b.putInt(primarySize); b.putInt(0); b.putShort(0); b.putShort(0)
        // Entry 1：增益图
        b.putInt(0); b.putInt(secondarySize); b.putInt(secondaryOffset); b.putShort(0); b.putShort(0)
        return b.array()
    }

    // ---- XMP 模板 ----

    /** 主图 XMP：hdrgm 元数据（老规范主图承载）+ GContainer 目录（Item:Length = 增益图字节数） */
    private fun primaryXmp(secondaryLength: Int, d: HdrData): String {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\"" +
                " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
                " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
                " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"" +
                " hdrgm:Version=\"1.0\"" +
                " hdrgm:GainMapMin=\"${d.minLog2}\"" +
                " hdrgm:GainMapMax=\"${d.maxLog2}\"" +
                " hdrgm:Gamma=\"${d.gamma}\"" +
                " hdrgm:OffsetSDR=\"${d.offsetSdr}\"" +
                " hdrgm:OffsetHDR=\"${d.offsetHdr}\"" +
                " hdrgm:HDRCapacityMin=\"${d.capMinLog2}\"" +
                " hdrgm:HDRCapacityMax=\"${d.capMaxLog2}\"" +
                " hdrgm:BaseRenditionIsHDR=\"False\">" +
                "<Container:Directory><rdf:Seq>" +
                "<rdf:li rdf:parseType=\"Resource\"><Item:Semantic>Primary</Item:Semantic><Item:Mime>image/jpeg</Item:Mime></rdf:li>" +
                "<rdf:li rdf:parseType=\"Resource\"><Item:Semantic>GainMap</Item:Semantic><Item:Mime>image/jpeg</Item:Mime><Item:Length>$secondaryLength</Item:Length></rdf:li>" +
                "</rdf:Seq></Container:Directory>" +
                "</rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    /** 增益图 XMP：hdrgm 元数据（新规范二次图承载；读取端 exp2 还原 Min/Max/Capacity） */
    private fun secondaryXmp(d: HdrData): String {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\"" +
                " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"" +
                " hdrgm:Version=\"1.0\"" +
                " hdrgm:GainMapMin=\"${d.minLog2}\"" +
                " hdrgm:GainMapMax=\"${d.maxLog2}\"" +
                " hdrgm:Gamma=\"${d.gamma}\"" +
                " hdrgm:OffsetSDR=\"${d.offsetSdr}\"" +
                " hdrgm:OffsetHDR=\"${d.offsetHdr}\"" +
                " hdrgm:HDRCapacityMin=\"${d.capMinLog2}\"" +
                " hdrgm:HDRCapacityMax=\"${d.capMaxLog2}\"" +
                " hdrgm:BaseRenditionIsHDR=\"False\"/>" +
                "</rdf:RDF></x:xmpmeta>"
    }

    private fun buildApp1(xml: String): ByteArray {
        val ns = XMP_NS.toByteArray(Charsets.UTF_8)
        val body = xml.toByteArray(Charsets.UTF_8)
        val len = 2 + ns.size + body.size
        val out = ByteArray(2 + len)
        out[0] = 0xFF.toByte(); out[1] = 0xE1.toByte()
        out[2] = ((len shr 8) and 0xFF).toByte(); out[3] = (len and 0xFF).toByte()
        System.arraycopy(ns, 0, out, 4, ns.size)
        System.arraycopy(body, 0, out, 4 + ns.size, body.size)
        return out
    }

    /** 增益图若无 XMP APP1（方向旋转重编码后会丢失）则在 SOI 后补一份。 */
    private fun ensureSecondaryXmp(sec: ByteArray, d: HdrData): ByteArray {
        var hasXmp = false
        forEachSegment(sec) { s ->
            if (s.marker == 0xE1 && startsWith(s, XMP_NS.toByteArray())) { hasXmp = true; true } else false
        }
        if (hasXmp) return sec
        val block = buildApp1(secondaryXmp(d))
        val out = ByteArray(sec.size + block.size)
        System.arraycopy(sec, 0, out, 0, 2)
        System.arraycopy(block, 0, out, 2, block.size)
        System.arraycopy(sec, 2, out, 2 + block.size, sec.size - 2)
        return out
    }

    /** 删除主图里已有的 XMP APP1 / MPF APP2 / ISO 21496 APP2（幂等装配）。 */
    private fun stripHdrSegments(jpeg: ByteArray): ByteArray {
        var cur = jpeg
        while (true) {
            val next = removeOneHdrSegment(cur) ?: return cur
            cur = next
        }
    }

    private fun removeOneHdrSegment(jpeg: ByteArray): ByteArray? {
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val m = jpeg[pos + 1].toInt() and 0xFF
            if (m == 0xDA || m == 0xD9) return null
            if (m == 0xD8 || (m in 0xD0..0xD7) || m == 0x01) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) return null
            val drop = (m == 0xE1 && matchAt(jpeg, pos + 4, XMP_NS.toByteArray())) ||
                    (m == 0xE2 && (matchAt(jpeg, pos + 4, "MPF\u0000".toByteArray())
                            || matchAt(jpeg, pos + 4, "urn:".toByteArray())))
            if (drop) {
                val out = ByteArray(jpeg.size - len - 2)
                System.arraycopy(jpeg, 0, out, 0, pos)
                System.arraycopy(jpeg, pos + 2 + len, out, pos, jpeg.size - pos - 2 - len)
                return out
            }
            pos += 2 + len
        }
        return null
    }

    // ---- 通用工具 ----

    private fun u16At(b: ByteArray, off: Int, be: Boolean): Int {
        if (off < 0 || off + 2 > b.size) return 0
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        return if (be) (b0 shl 8) or b1 else (b1 shl 8) or b0
    }

    private fun u32At(b: ByteArray, off: Int, be: Boolean): Int {
        if (off < 0 || off + 4 > b.size) return 0
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        return if (be) (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        else (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    /** 从 [from]（应为 SOI）起解析 SOF 段取宽高，失败返回 (0,0)。JPEG 段内一律大端。 */
    private fun sizeAt(b: ByteArray, from: Int): Pair<Int, Int> {
        if (from < 0 || from + 4 > b.size || !matchAt(b, from, byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
            return 0 to 0
        var pos = from + 2
        while (pos + 4 <= b.size && (b[pos].toInt() and 0xFF) == 0xFF) {
            val m = b[pos + 1].toInt() and 0xFF
            if (m == 0xDA || m == 0xD9) return 0 to 0
            val isSof = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC
            if (isSof) {
                if (pos + 9 > b.size) return 0 to 0
                return u16At(b, pos + 7, true) to u16At(b, pos + 5, true) // 宽, 高
            }
            if (m == 0xD8 || (m in 0xD0..0xD7) || m == 0x01) { pos += 2; continue }
            val len = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > b.size) return 0 to 0
            pos += 2 + len
        }
        return 0 to 0
    }

    private fun jpegSize(b: ByteArray): Pair<Int, Int> = if (isJpeg(b)) sizeAt(b, 0) else 0 to 0

    /** 主图 EOI 之后的位置（= 增益图起点）；找不到返回 -1。 */
    private fun mainEoi(jpeg: ByteArray): Int {
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val m = jpeg[pos + 1].toInt() and 0xFF
            if (m == 0xD9) return pos + 2
            if (m == 0xDA) {
                var i = pos + 2
                while (i + 1 < jpeg.size) {
                    if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xD9.toByte()) return i + 2
                    i++
                }
                return -1
            }
            if (m == 0xD8 || (m in 0xD0..0xD7) || m == 0x01) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) return -1
            pos += 2 + len
        }
        return -1
    }

    /** XMP 命名空间与属性名清单（容器路径徽章取证：看原图 XMP 里有哪些字段会被我们丢弃） */
    private fun xmpFieldSummary(xml: String?): String {
        if (xml.isNullOrEmpty()) return "无"
        val fields = LinkedHashSet<String>()
        Regex("xmlns:([A-Za-z0-9_]+)\\s*=").findAll(xml).forEach { fields.add("ns:" + it.groupValues[1]) }
        Regex("([A-Za-z0-9_]+):([A-Za-z0-9_]+)\\s*=").findAll(xml).forEach {
            if (it.groupValues[1] != "xmlns") fields.add(it.groupValues[1] + ":" + it.groupValues[2])
        }
        Regex("<Item:Semantic>([^<]*)</Item:Semantic>").findAll(xml).forEach {
            fields.add("Item:" + it.groupValues[1])
        }
        val s = fields.joinToString(" ")
        return if (s.length > 600) s.take(600) + "…(截断)" else s
    }

    private fun matchAt(jpeg: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at < 0 || at + prefix.size > jpeg.size) return false
        for (i in prefix.indices) if (jpeg[at + i] != prefix[i]) return false
        return true
    }

    private fun startsWith(seg: Segment, prefix: ByteArray): Boolean =
        matchAt(seg.src, seg.dataStart, prefix)

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8

    /** 截断到主图第一个 EOI（含）。找不到 EOI 返回原字节。 */
    private fun truncateAtEoi(jpeg: ByteArray): ByteArray {
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val m = jpeg[pos + 1].toInt() and 0xFF
            if (m == 0xD9) return jpeg.copyOf(pos + 2)
            if (m == 0xDA) {
                // 扫描数据内找 EOI（跳过 marker 段，熵数据无 FF D9 之外的成对标记语义）
                var i = pos + 2
                while (i + 1 < jpeg.size) {
                    if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xD9.toByte()) return jpeg.copyOf(i + 2)
                    i++
                }
                return jpeg
            }
            if (m == 0xD8 || (m in 0xD0..0xD7) || m == 0x01) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) return jpeg
            pos += 2 + len
        }
        return jpeg
    }

    /** SOS 段的段头位置（MPF 插入点）。找不到返回 -1。 */
    private fun findSos(jpeg: ByteArray): Int {
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val m = jpeg[pos + 1].toInt() and 0xFF
            if (m == 0xDA) return pos
            if (m == 0xD8 || (m in 0xD0..0xD7) || m == 0x01) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) return -1
            pos += 2 + len
        }
        return -1
    }

    /** 遍历 JPEG 段（到 SOS/EOI 为止）；回调返回 true 立即中止。 */
    private inline fun forEachSegment(jpeg: ByteArray, onSeg: (Segment) -> Boolean) {
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val m = jpeg[pos + 1].toInt() and 0xFF
            if (m == 0xDA || m == 0xD9) return
            if (m == 0xD8 || (m in 0xD0..0xD7) || m == 0x01) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) return
            if (onSeg(Segment(jpeg, m, pos, pos + 4, len - 2))) return
            pos += 2 + len
        }
    }

    private fun rotateJpeg(src: Bitmap, degrees: Int, flip: Boolean): ByteArray {
        val m = Matrix()
        m.setRotate(degrees.toFloat())
        m.postScale(if (flip) -1f else 1f, 1f)
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        if (out !== src) out.recycle()
        return bos.toByteArray()
    }

    private fun sanitizeLog2(v: String?, fallback: String): String {
        val f = v?.toFloatOrNull() ?: return fallback
        return if (f.isNaN() || f < -10f || f > 10f) fallback else v!!
    }

    private fun sanitizeLinear(v: String?, fallback: String): String {
        val f = v?.toFloatOrNull() ?: return fallback
        return if (f.isNaN() || f < 0f || f > 1f) fallback else v!!
    }
}

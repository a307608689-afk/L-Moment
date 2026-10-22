package com.leicamoments.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
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

            // ---- 元数据：主图 XMP 优先、增益图自身 XMP 兜底；**逐个属性回退** ----
            // 平台编码器（Android 14+ / 厂商相机）常只在增益图的 XMP 里写 Min/Max/Gamma/Offset，
            // 主图 XMP 仅有 Container 目录 + hdrgm:Version，逐属性回退才能拿到真实区间。
            var secXmp: String? = null
            forEachSegment(sec) { s ->
                if (s.marker == 0xE1 && startsWith(s, XMP_NS.toByteArray())) {
                    secXmp = String(s.src, s.dataStart, s.dataLength, Charsets.UTF_8)
                    true
                } else false
            }
            if (xmpXml == null) xmpXml = secXmp
            val xml = xmpXml
            val secXml = secXmp
            fun attr(name: String): String? =
                matchXmpAttr(xml, name) ?: matchXmpAttr(secXml, name)
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

    /** 文件级装配结果（[ok] + 一行诊断，供导出日志与提示复用）。 */
    class FileOpResult(val ok: Boolean, val brief: String)

    /**
     * 文件级 UltraHDR 回嵌：**不把主图整幅读进 Java 堆**。
     *
     * 容器头（SOI..SOS 段）在内存里改写——剥旧 XMP/MPF/ISO、插新 XMP 与 MPF；
     * 主图熵编码数据与增益图按 256KiB 缓冲流式搬运，尾部残留二次图按主图 EOI 裁掉。
     * 原地替换 [primary]，任何异常保持原文件（SDR 兜底）。
     */
    fun embedFile(primary: File, data: HdrData): FileOpResult {
        try {
            if (!isJpeg(data.gainmapJpeg)) return FileOpResult(false, "HDR=增益图非法")
            val head = ImageUtils.readFileHeader(primary) ?: return FileOpResult(false, "HDR=主图头读取失败")
            if (!isJpeg(head)) return FileOpResult(false, "HDR=非JPEG")

            val base = stripHdrSegments(head) // 头部内剥离（幂等）
            val sec = ensureSecondaryXmp(data.gainmapJpeg, data)

            // 1) 主图 XMP APP1，插在前部 APPn 链之后（与字节版 embed 同一插入点语义）
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

            val sos = findSos(withXmp)
            if (sos <= 0) return FileOpResult(false, "HDR=无 SOS")

            // 2) 主图 = 改写后的头 + 原文件熵编码数据（截到主图 EOI）
            val headEnd = head.size.toLong()
            val mainEnd = findMainEoiOffset(primary, headEnd) ?: primary.length()
            val entropyLen = (mainEnd - headEnd).coerceAtLeast(0L)
            val primaryTotal = withXmp.size.toLong() + entropyLen + 90L
            if (primaryTotal > Int.MAX_VALUE - 16L) return FileOpResult(false, "HDR=主图超限")
            val secondaryOffset = (primaryTotal - sos - 8L).toInt() // 相对 MP Endian 字段
            val mpfBlock = ByteArray(90)
            mpfBlock[0] = 0xFF.toByte(); mpfBlock[1] = 0xE2.toByte()
            mpfBlock[2] = 0x00; mpfBlock[3] = 0x58 // 段长 88 = 2 + 86
            System.arraycopy(buildMpfPayload(primaryTotal.toInt(), sec.size, secondaryOffset), 0, mpfBlock, 4, 86)

            // 3) 落盘：头[..sos) + MPF + 头[sos..) + 熵数据 + 增益图
            val dir = primary.absoluteFile.parentFile
            val tmp = File(dir, primary.name + ".hdr" + System.nanoTime())
            try {
                FileOutputStream(tmp).use { os ->
                    os.write(withXmp, 0, sos)
                    os.write(mpfBlock)
                    if (withXmp.size > sos) os.write(withXmp, sos, withXmp.size - sos)
                    copyRange(primary, headEnd, entropyLen, os)
                    os.write(sec)
                }
                if (!tmp.renameTo(primary)) {
                    copyFileOver(tmp, primary)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }

            DiagLog.i("HdrGainMap", "文件级回嵌完成 主图 ${primaryTotal}B（头 ${withXmp.size}B + 熵 ${entropyLen}B）" +
                    " + 增益图 ${sec.size}B")
            return verifyFile(primary, primaryTotal.toInt(), sec.size)
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "文件级回嵌异常: ${t.message}")
            return FileOpResult(false, "HDR=回嵌异常")
        }
    }

    /** 文件级「只保留主图」：截到主图 EOI，丢弃 EOI 之后残留的二次图（无 UltraHDR 装配时用）。 */
    fun truncateToPrimaryFile(primary: File): FileOpResult {
        return try {
            val head = ImageUtils.readFileHeader(primary) ?: return FileOpResult(true, "HDR=无")
            val mainEnd = findMainEoiOffset(primary, head.size.toLong()) ?: return FileOpResult(true, "HDR=无")
            val len = primary.length()
            if (mainEnd >= len) {
                FileOpResult(true, "HDR=无")
            } else {
                RandomAccessFile(primary, "rw").use { it.setLength(mainEnd) }
                DiagLog.i("HdrGainMap", "文件级剥离残留二次图 ${len - mainEnd}B")
                FileOpResult(true, "HDR=已剥离残留")
            }
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "文件级剥离异常: ${t.message}")
            FileOpResult(true, "HDR=剥离异常")
        }
    }

    /** 回读文件检查装配结构：XMP/MPF 存在、长度吻合、增益图起点是合法 SOI。 */
    private fun verifyFile(file: File, expectedPrimary: Int, gainBytes: Int): FileOpResult {
        val head = ImageUtils.readFileHeader(file) ?: return FileOpResult(false, "HDR=回读失败")
        var hasXmp = false
        var hasMpf = false
        forEachSegment(head) { s ->
            when {
                s.marker == 0xE1 && startsWith(s, XMP_NS.toByteArray()) -> hasXmp = true
                s.marker == 0xE2 && startsWith(s, "MPF\u0000".toByteArray()) -> hasMpf = true
            }
            false
        }
        val len = file.length()
        val expected = expectedPrimary.toLong() + gainBytes
        val secSoi = readSoiAt(file, expectedPrimary.toLong())
        val ok = hasXmp && hasMpf && len == expected && secSoi
        val brief = if (ok) "HDR=增益图 ok" else "HDR=增益图 有疑问"
        DiagLog.i("HdrGainMap", "文件级回嵌自检 $brief | 主图 ${expectedPrimary}B 增益图 ${gainBytes}B " +
                "文件 ${len}B XMP=$hasXmp MPF=$hasMpf 二次图SOI=$secSoi")
        return FileOpResult(ok, brief)
    }

    private fun readSoiAt(file: File, from: Long): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (from <= 0 || from + 2 > raf.length()) return false
                raf.seek(from)
                val b = ByteArray(2)
                raf.readFully(b)
                b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 主图 EOI 之后的绝对偏移；从 [from]（熵编码起点）起逐块扫描 `FF D9`。
     * 熵数据里 0xFF 只会跟 `00` 或 RST 标记，`FF D9` 不会出现在扫描数据内部，故首个命中即主图 EOI。
     */
    private fun findMainEoiOffset(file: File, from: Long): Long? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (from >= raf.length()) return@use null
                raf.seek(from)
                val buf = ByteArray(256 * 1024)
                var pos = from
                var prevFF = false
                var found: Long? = null
                scan@ while (found == null) {
                    val n = raf.read(buf)
                    if (n <= 0) break@scan
                    for (i in 0 until n) {
                        val b = buf[i].toInt() and 0xFF
                        if (prevFF && b == 0xD9) {
                            found = pos + i + 1
                            break@scan
                        }
                        prevFF = b == 0xFF
                    }
                    pos += n
                }
                found
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun copyRange(src: File, from: Long, count: Long, os: java.io.OutputStream) {
        if (count <= 0L) return
        RandomAccessFile(src, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(256 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n <= 0) break
                os.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun copyFileOver(src: File, dst: File) {
        FileInputStream(src).use { ins ->
            FileOutputStream(dst).use { os ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                }
                os.flush()
            }
        }
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

    /** 从 XMP 字符串里取某个属性（如 hdrgm:GainMapMax）的引号内容 */
    private fun matchXmpAttr(xml: String?, name: String): String? {
        if (xml == null) return null
        return Regex("$name\\s*=\\s*[\"']([^\"']*)[\"']").find(xml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
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

    // ================= C3：增益图重标定（色调变更后重新标定 HDR 增益） =================

    /**
     * 总开关。false 时三个 rebake* 一律返回 null，调用方沿用源增益图 = C1（原图直通）语义，
     * 用于 A/B 对照与线上回退。
     */
    const val REBAKE_ENABLED = true

    /** sRGB 编码值 → 线性光（256 级查表；逐像素 pow 太贵） */
    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f
        else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    /** 增益图解码后的（log2）取值范围下限，低于它就认为元数据退化、不值得重标定 */
    private const val MIN_LOG2_SPAN = 0.02f

    /** 区间重估上限：最多把增益上限再抬 3 EV（相对原区间），避免风格压黑过度时比值失控 */
    private const val MAX_EXPAND_LOG2 = 3.0f

    /** 区间重估下限：最多把增益下限再降 2 EV（提亮型风格 HDR 相对更暗时用得上） */
    private const val MAX_SHRINK_LOG2 = 2.0f

    /**
     * 区间总跨度护栏：[outMin, outMax] 的绝对跨度不超过 5 EV（ratioMax ≤ 32）。
     * 源区间本身更宽时以源区间为准（不压缩，避免削顶）。实测样张最大 4.31 EV，
     * 护栏只防极端风格（大面积压黑）把 ratioMax 推到平台 gainmap 不合理区间。
     */
    private const val MAX_TOTAL_SPAN_LOG2 = 5.0f

    /** 需求增益直方图：[-2, +10] EV，步长 0.02 EV */
    private const val HIST_LO = -2.0f
    private const val HIST_STEP = 0.02f
    private const val HIST_BINS = 600

    /**
     * C3 重标定（字节版）。[oldSdrJpeg] = 导入时的原始 SDR 主图（增益图的分母来源），
     * [newSdrJpeg] = 套过色调的成品 SDR。返回新的 [HdrData]：几何不变，增益区间按
     * 实测需求重估（原区间不够时向外扩，见 [MAX_EXPAND_LOG2]/[MAX_SHRINK_LOG2]）；
     * 输入不可解、无增益图、范围退化或异常时返回 null，调用方应回落到旧增益图。
     */
    fun rebakeJpeg(oldSdrJpeg: ByteArray, newSdrJpeg: ByteArray, data: HdrData): HdrData? {
        if (!REBAKE_ENABLED) return null
        return try {
            val gm = decodeGainBitmap(data.gainmapJpeg) ?: return null
            val gw = gm.width
            val gh = gm.height
            val oldBmp = decodeScaledBytes(oldSdrJpeg, gw, gh)
            val newBmp = decodeScaledBytes(newSdrJpeg, gw, gh)
            if (oldBmp == null || newBmp == null) {
                DiagLog.w("HdrGainMap", "重标定跳过：SDR 解码失败 old=${oldBmp != null} new=${newBmp != null}")
                gm.recycle(); oldBmp?.recycle(); newBmp?.recycle()
                return null
            }
            val out = rebakeCore(gm, oldBmp, newBmp, data)
            gm.recycle(); oldBmp.recycle(); newBmp.recycle()
            out
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "重标定异常：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * C3 重标定（文件版）。用于全像素直通导出（源与成品都是磁盘上的大图）：
     * [srcW]/[srcH] 是源主图尺寸，用来一次算出解码降采样倍率（避免对大图解码两遍取 bounds）。
     */
    fun rebakeFiles(srcFile: File, newFile: File, data: HdrData, srcW: Int, srcH: Int): HdrData? {
        if (!REBAKE_ENABLED) return null
        return try {
            val gm = decodeGainBitmap(data.gainmapJpeg) ?: return null
            val gw = gm.width
            val gh = gm.height
            val sample = sampleSizeFor(srcW, srcH, gw, gh)
            val oldBmp = decodeScaledFile(srcFile, gw, gh, sample)
            val newBmp = decodeScaledFile(newFile, gw, gh, sample)
            if (oldBmp == null || newBmp == null) {
                DiagLog.w("HdrGainMap", "重标定跳过：文件解码失败 sample=$sample")
                gm.recycle(); oldBmp?.recycle(); newBmp?.recycle()
                return null
            }
            val out = rebakeCore(gm, oldBmp, newBmp, data)
            gm.recycle(); oldBmp.recycle(); newBmp.recycle()
            out
        } catch (t: Throwable) {
            DiagLog.w("HdrGainMap", "重标定异常：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * 逐行重算增益图。规范模型（ISO 21496-1 / Adobe hdrgm 同构）：
     *   L_hdr = (L_sdr + offSdr) * 2^g - offHdr
     * 反解新 SDR 需要的增益：g' = log2((L_hdr + offHdr) / (L_sdr_new + offSdr))，
     * 再按原 min/max 区间钳位并反算回 [0,255] 的像素值（gamma 逆变换）。
     */
    private fun rebakeCore(gain: Bitmap, oldSdr: Bitmap, newSdr: Bitmap, data: HdrData): HdrData? {
        val t0 = System.currentTimeMillis()
        val gw = gain.width
        val gh = gain.height
        val minL2 = data.minLog2.toFloatOrNull() ?: 0f
        val maxL2 = data.maxLog2.toFloatOrNull() ?: 1f
        val gamma = (data.gamma.toFloatOrNull() ?: 1f).coerceIn(0.2f, 5f)
        val offSdr = data.offsetSdr.toFloatOrNull() ?: 0.015625f
        val offHdr = data.offsetHdr.toFloatOrNull() ?: 0.015625f
        val span = maxL2 - minL2
        if (span < MIN_LOG2_SPAN) {
            DiagLog.w("HdrGainMap", "重标定跳过：区间退化 min=$minL2 max=$maxL2")
            return null
        }
        val gammaNeutral = Math.abs(gamma - 1f) < 1e-3f
        val gRow = IntArray(gw)
        val oRow = IntArray(gw)
        val nRow = IntArray(gw)
        val pRow = IntArray(gw)

        // 第一遍：统计「还原原图 HDR 所需的增益 g2」分布，用于区间重估。
        // 原图区间是为原 SDR 标定的，风格改变 SDR 影调后需求可能超出，
        // 直接沿用旧区间会把大量像素钳在上下限（信息损失，HDR 还原不到位）。
        val hist = IntArray(HIST_BINS)
        var counted = 0L
        for (y in 0 until gh) {
            gain.getPixels(gRow, 0, gw, 0, y, gw, 1)
            oldSdr.getPixels(oRow, 0, gw, 0, y, gw, 1)
            newSdr.getPixels(nRow, 0, gw, 0, y, gw, 1)
            for (x in 0 until gw) {
                val lNew = SRGB_TO_LINEAR[lumaIndex(nRow[x])]
                if (lNew <= 1e-6f) continue
                val g2 = needGainLog2(gRow[x], oRow[x], lNew, minL2, span, gamma, offSdr, offHdr, gammaNeutral)
                hist[histBin(g2)]++
                counted++
            }
        }

        var outMin = minL2
        var outMax = maxL2
        if (counted > 0) {
            val needHigh = histQuantile(hist, counted, 0.995)
            val needLow = histQuantile(hist, counted, 0.005)
            outMax = Math.max(maxL2, needHigh).coerceAtMost(maxL2 + MAX_EXPAND_LOG2)
            outMin = Math.min(minL2, needLow).coerceAtLeast(minL2 - MAX_SHRINK_LOG2)
            // 总跨度护栏：至少保留源区间，再限制额外扩张后的绝对跨度
            outMax = Math.min(outMax, Math.max(maxL2, outMin + MAX_TOTAL_SPAN_LOG2))
            if (outMax - outMin < MIN_LOG2_SPAN) {
                outMin = minL2
                outMax = maxL2
            }
        }
        val outSpan = outMax - outMin

        // 第二遍：按重估后的区间编码
        val out = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        var clippedHigh = 0L
        var clippedLow = 0L
        var dead = 0L
        for (y in 0 until gh) {
            gain.getPixels(gRow, 0, gw, 0, y, gw, 1)
            oldSdr.getPixels(oRow, 0, gw, 0, y, gw, 1)
            newSdr.getPixels(nRow, 0, gw, 0, y, gw, 1)
            for (x in 0 until gw) {
                val lNew = SRGB_TO_LINEAR[lumaIndex(nRow[x])]
                val c: Float
                if (lNew <= 1e-6f) {
                    // 新 SDR 已压成纯黑：反解无解（分母为 0）。保留原增益（比值语义），
                    // 此时还原值 ≈ offSdr*2^g-offHdr，既不会像取上限那样把 HDR 抬亮，
                    // 也不会像取最小增益那样丢掉原图暗部残留。
                    c = decodeGainLog2(gRow[x], minL2, span, gamma, gammaNeutral)
                        .coerceIn(outMin, outMax)
                    dead++
                } else {
                    val g2 = needGainLog2(gRow[x], oRow[x], lNew, minL2, span, gamma, offSdr, offHdr, gammaNeutral)
                    if (g2 > outMax) {
                        c = outMax
                        clippedHigh++
                    } else if (g2 < outMin) {
                        c = outMin
                        clippedLow++
                    } else {
                        c = g2
                    }
                }
                val norm = ((c - outMin) / outSpan).coerceIn(0f, 1f)
                val q = if (gammaNeutral) norm
                else Math.pow(norm.toDouble(), gamma.toDouble()).toFloat()
                val v = (q * 255f + 0.5f).toInt().coerceIn(0, 255)
                pRow[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            out.setPixels(pRow, 0, gw, 0, y, gw, 1)
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        out.recycle()
        val bytes = bos.toByteArray()
        val total = (gw.toLong() * gh).coerceAtLeast(1L)
        val fMin = fmtLog2(outMin)
        val fMax = fmtLog2(outMax)
        DiagLog.i(
            "HdrGainMap",
            "增益图重标定 ${gw}x${gh} ${data.gainmapJpeg.size}B→${bytes.size}B " +
                "区间[$minL2,$maxL2]→[$fMin,$fMax] 削顶 ${pct(clippedHigh, total)} " +
                "负增益钳位 ${pct(clippedLow, total)} 压黑 ${pct(dead, total)} " +
                "用时 ${System.currentTimeMillis() - t0}ms"
        )
        // 区间重估后必须同步写回 GainMapMin/Max 与 HDRCapacity，否则解码端仍按旧区间换算
        return HdrData(
            bytes, fMin, fMax, data.gamma,
            data.offsetSdr, data.offsetHdr, data.capMinLog2, fMax, data.sourceXmp
        )
    }

    /** 增益图像素 → 原区间下的增益（log2）。规范解码：去 gamma 后按 min/span 线性还原 */
    private fun decodeGainLog2(
        gp: Int, minL2: Float, span: Float, gamma: Float, gammaNeutral: Boolean
    ): Float {
        val gv = (((gp shr 16) and 0xFF) * 0.299f + ((gp shr 8) and 0xFF) * 0.587f +
                (gp and 0xFF) * 0.114f) / 255f
        // 增益图存的是「已加 gamma 的 0..1」，规范解码要开 1/gamma 次方
        val p = if (gammaNeutral) gv else Math.pow(gv.toDouble(), 1.0 / gamma).toFloat()
        return minL2 + span * p
    }

    /** 还原原图 HDR 所需的增益（log2），规范模型的逆运算 */
    private fun needGainLog2(
        gp: Int, op: Int, lNew: Float, minL2: Float, span: Float,
        gamma: Float, offSdr: Float, offHdr: Float, gammaNeutral: Boolean
    ): Float {
        val log2Gain = decodeGainLog2(gp, minL2, span, gamma, gammaNeutral)
        val lOld = SRGB_TO_LINEAR[lumaIndex(op)]
        val hdr = (lOld + offSdr) * Math.pow(2.0, log2Gain.toDouble()).toFloat() - offHdr
        return (Math.log(((hdr + offHdr).coerceAtLeast(1e-6f) / (lNew + offSdr)).toDouble()) / LN2).toFloat()
    }

    private fun histBin(g2: Float): Int =
        (((g2 - HIST_LO) / HIST_STEP).toInt()).coerceIn(0, HIST_BINS - 1)

    /** 直方图分位（返回该分位所在的 bin 中点，精度 ±HIST_STEP/2） */
    private fun histQuantile(hist: IntArray, total: Long, q: Double): Float {
        val target = (q * total).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return HIST_LO + (i + 0.5f) * HIST_STEP
        }
        return HIST_LO + (HIST_BINS - 0.5f) * HIST_STEP
    }

    private fun fmtLog2(v: Float): String =
        String.format(java.util.Locale.US, "%.5f", v)

    private const val LN2 = 0.6931472

    /** Rec.601 亮度，四舍五入的整数索引（0..255），用于查 [SRGB_TO_LINEAR] */
    private fun lumaIndex(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return ((299 * r + 587 * g + 114 * b + 500) / 1000).coerceIn(0, 255)
    }

    private fun pct(n: Long, total: Long): String =
        String.format(java.util.Locale.US, "%.3f%%", n * 100.0 / total)

    private fun decodeGainBitmap(jpeg: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    /** 按「不小于目标尺寸」的 2 的幂降采样解码，再精确缩放到目标尺寸 */
    private fun decodeScaledBytes(jpeg: ByteArray, tw: Int, th: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, tw, th)
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opt) ?: return null
        return fitTo(bmp, tw, th)
    }

    private fun decodeScaledFile(file: File, tw: Int, th: Int, sample: Int): Bitmap? {
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opt) ?: return null
        return fitTo(bmp, tw, th)
    }

    private fun fitTo(bmp: Bitmap, tw: Int, th: Int): Bitmap {
        if (bmp.width == tw && bmp.height == th) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** 最大的 2 的幂降采样倍率，使降采样后仍不小于目标尺寸（上限 8，够覆盖 200MP） */
    private fun sampleSizeFor(w: Int, h: Int, tw: Int, th: Int): Int {
        var s = 1
        while (s < 8 && w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }
}

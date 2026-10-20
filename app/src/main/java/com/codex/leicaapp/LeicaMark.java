package com.codex.leicaapp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 相机标记写入（供相册按拍摄模式归类）。
 *
 * <p>相册识别依赖两处元数据：</p>
 * <ol>
 *   <li>EXIF APP1 里的三个私有 tag（ExifIFD）：themeCustomize(0x889D)="Madrid"、
 *       legendmode(0x88B0)=1、mode(0xA661)="256"。</li>
 *   <li>FFE4 "XIAOMI_CUSTOMIZE" 段：把上述 tag 序列化成 JSON
 *       {@code {"889d":"Madrid","88b0":"1","a661":"256"}}（相册主要读这里）。</li>
 * </ol>
 *
 * <p>下方常量属于设备侧的元数据格式约定，值不可改动（改了相册不再归类）；
 * 它们不构成本项目的独创表达。</p>
 */
public final class LeicaMark {

    private static final int TAG_EXIF_IFD_POINTER = 34665; // 0x8769
    private static final int TAG_THEME = 0x889D;           // 34973, ASCII "Madrid"
    private static final int TAG_LEGENDMODE = 34992;       // 0x88B0, BYTE=1
    private static final int TAG_MODE = 0xA661;            // 42593, ASCII="256"

    private static final byte[] CUSTOMIZE_MAGIC = "XIAOMI_CUSTOMIZE\u0000\u0001\u0001"
            .getBytes(StandardCharsets.UTF_8);

    // 0x9999 XiaomiComment 的格式约定值——filterId=66048 是该模式的标识
    private static final String XIAOMI_COMMENT_LEICA =
            "{\"mirror\":false,\"sensorType\":\"rear\",\"Hdr\":\"off\",\"OpMode\":0,"
                    + "\"smallPicture\":false,\"AIScene\":0,\"filterId\":66048,"
                    + "\"zoomMultiple\":4.300000190734863}";
    // 0x889E XiaomiAuxiliaryInfo 的格式约定值
    private static final String XIAOMI_AUX_LEICA =
            "{\"buildDevice\":\"xuanyuan\",\"customize\":\"\",\"cvLensName\":\"\","
                    + "\"deviceType\":1,\"filterName\":\"??\",\"livephotoInfo\":\"\","
                    + "\"series\":\"\",\"variant\":\"\",\"version\":4,"
                    + "\"waterLogo\":\"XIAOMI\",\"waterName\":\"15 Ultra\"}";

    // XIAOMI_CUSTOMIZE JSON（14 字段，含设备标识）× 格式约定值
    private static final String CUSTOMIZE_JSON =
            "{\"8895\":\"0\",\"10f\":\"Xiaomi\",\"88a3\":\"{}\",\"version\":\"32\","
                    + "\"110\":\"Xiaomi 15 Ultra\",\"889d\":\"Madrid\",\"889a\":\"0\","
                    + "\"9a00\":\"Xiaomi 15 Ultra\",\"9aaa\":\"\",\"88b0\":\"1\","
                    + "\"889e\":\"{\\\"buildDevice\\\":\\\"xuanyuan\\\",\\\"customize\\\":\\\"\\\","
                    + "\\\"cvLensName\\\":\\\"\\\",\\\"deviceType\\\":1,\\\"filterName\\\":\\\"??\\\","
                    + "\\\"livephotoInfo\\\":\\\"\\\",\\\"series\\\":\\\"\\\",\\\"variant\\\":\\\"\\\","
                    + "\\\"version\\\":4,\\\"waterLogo\\\":\\\"XIAOMI\\\",\\\"waterName\\\":\\\"15 Ultra\\\"}\","
                    + "\"889f\":\"???\","
                    + "\"9999\":\"{\\\"mirror\\\":false,\\\"sensorType\\\":\\\"rear\\\",\\\"Hdr\\\":\\\"off\\\","
                    + "\\\"OpMode\\\":0,\\\"smallPicture\\\":false,\\\"AIScene\\\":0,"
                    + "\\\"filterId\\\":66048,\\\"zoomMultiple\\\":1}\","
                    + "\"a661\":\"256\"}";

    private LeicaMark() {
    }

    /** 对 JPEG 写入相机标记（EXIF + FFE4 两处）。任何异常返回原字节。 */
    public static byte[] mark(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4
                || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return jpeg;
        }
        try {
            // 1) 删除复合照片容器元数据（XMP Container/MPF/ISO 增益图标识）——动态照片/ HDR 相册
            //    会读这套容器里的拍摄模式而忽略主 EXIF；删掉后导出变纯静态图，走主 EXIF 判定
            // 2) 删除多余的 FFE1 Exif 段（动态照片嵌入帧空壳）
            return markCustomize(markExif(stripCompositeMeta(stripExtraExif(jpeg))));
        } catch (Throwable t) {
            return jpeg;
        }
    }

    // ---- 复合照片容器元数据移除 ----

    /** 循环删除 XMP / MPF / ISO TS 21496 段（复合照片识别依据）。 */
    private static byte[] stripCompositeMeta(byte[] jpeg) {
        while (true) {
            byte[] out = removeOneComposite(jpeg);
            if (out == jpeg) return jpeg;
            jpeg = out;
        }
    }

    private static byte[] removeOneComposite(byte[] jpeg) {
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) { pos += 2; continue; }
            int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) break;
            boolean del = false;
            if (marker == 0xE1 && len >= 16
                    && jpeg[pos + 4] == 'h' && jpeg[pos + 5] == 't' && jpeg[pos + 6] == 't' && jpeg[pos + 7] == 'p') {
                del = true; // XMP（含 Container:Directory 复合照片标记）
            } else if (marker == 0xE2 && len >= 5
                    && jpeg[pos + 4] == 'M' && jpeg[pos + 5] == 'P' && jpeg[pos + 6] == 'F') {
                del = true; // MPF 多图格式
            } else if (marker == 0xE2 && len >= 6
                    && jpeg[pos + 4] == 'u' && jpeg[pos + 5] == 'r' && jpeg[pos + 6] == 'n') {
                del = true; // urn:iso:std:iso:ts:21496 增益图标识
            }
            if (del) {
                byte[] out = new byte[jpeg.length - len - 2];
                System.arraycopy(jpeg, 0, out, 0, pos);
                System.arraycopy(jpeg, pos + 2 + len, out, pos, jpeg.length - pos - 2 - len);
                return out;
            }
            pos += 2 + len;
        }
        return jpeg;
    }

    // ---- 多余 Exif 段移除 ----

    /** 循环删除第二个及以后的 FFE1 "Exif\0\0" 段（保留第一个即主 Exif）。 */
    private static byte[] stripExtraExif(byte[] jpeg) {
        while (true) {
            byte[] out = stripOneExtraExif(jpeg);
            if (out == jpeg) return jpeg;
            jpeg = out;
        }
    }

    private static byte[] stripOneExtraExif(byte[] jpeg) {
        int pos = 2;
        boolean seen = false;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) { pos += 2; continue; }
            int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) break;
            if (marker == 0xE1 && len >= 10
                    && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x'
                    && jpeg[pos + 6] == 'i' && jpeg[pos + 7] == 'f'
                    && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                if (seen) {
                    byte[] out = new byte[jpeg.length - len - 2];
                    System.arraycopy(jpeg, 0, out, 0, pos);
                    System.arraycopy(jpeg, pos + 2 + len, out, pos, jpeg.length - pos - 2 - len);
                    return out;
                }
                seen = true;
            }
            pos += 2 + len;
        }
        return jpeg;
    }

    // ---- 旧算法段清理 ----

    /** JPEG 层删除 FFE3 ALGO_COMMON 段（设备私有算法数据，外来源图不含）。 */
    private static byte[] stripAlgoCommon(byte[] jpeg) {
        return removeSegment(jpeg, 0xE3, "ALGO_COMMON");
    }

    private static final int[] XIAOMI_EXIF_TAGS = {
            0x927C, // MakerNote（"Xiaomi\0..." 私有 IFD，相册识别本机图的指纹）
            0x9999, // XiaomiComment
            0x9A00, // XiaomiProduct
            0x889E, // XiaomiAuxiliaryInfo
            0x889F, // XiaomiCvSessionkeyType
            0x88A3, // XiaomiCamAccInfo
            0x889A, // 小米私有
            0x8895, // 小米私有
    };

    private static final int[] XIAOMI_IFD0_TAGS = {
            0x9999, 0x9A00, 0x889A, 0x88A3,
    };

    /** 就地从 IFD 表删除一个 entry（count 减 1、后续前移、next IFD 指针前移，payload 长度不变）。 */
    private static void removeEntryInPlace(byte[] d, boolean le, int ifdOff, int tag) {
        if (ifdOff < 6 || ifdOff + 2 > d.length) return;
        int count = (int) integer(d, ifdOff, 2, le);
        if (count <= 0) return;
        int idx = -1;
        for (int i = 0; i < count; i++) {
            int e = ifdOff + 2 + i * 12;
            if (e + 12 > d.length) return;
            if (integer(d, e, 2, le) == tag) { idx = i; break; }
        }
        if (idx < 0) return;
        for (int i = idx; i < count - 1; i++) {
            System.arraycopy(d, ifdOff + 2 + (i + 1) * 12, d, ifdOff + 2 + i * 12, 12);
        }
        int nextOld = ifdOff + 2 + count * 12;
        int nextNew = ifdOff + 2 + (count - 1) * 12;
        if (nextOld + 4 <= d.length) {
            System.arraycopy(d, nextOld, d, nextNew, 4);
        }
        writeInt(d, ifdOff, count - 1L, 2, le);
    }

    private static byte[] removeSegment(byte[] jpeg, int marker, String magic) {
        byte[] mb = magic.getBytes(StandardCharsets.US_ASCII);
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int m = jpeg[pos + 1] & 0xFF;
            if (m == 0xDA || m == 0xD9) break;
            if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7) || m == 0x01) { pos += 2; continue; }
            int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) break;
            if (m == marker && len - 2 >= mb.length) {
                boolean match = true;
                for (int i = 0; i < mb.length; i++) {
                    if (jpeg[pos + 4 + i] != mb[i]) { match = false; break; }
                }
                if (match) {
                    byte[] out = new byte[jpeg.length - len - 2];
                    System.arraycopy(jpeg, 0, out, 0, pos);
                    System.arraycopy(jpeg, pos + 2 + len, out, pos, jpeg.length - pos - 2 - len);
                    return out;
                }
            }
            pos += 2 + len;
        }
        return jpeg;
    }

    // ---- EXIF APP1 ----

    private static byte[] markExif(byte[] jpeg) {
        int pos = 2;
        int app1DataStart = -1;
        int app1DataLen = 0;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            if (marker == 0xD8) { pos += 2; continue; }
            if (marker >= 0xD0 && marker <= 0xD7) { pos += 2; continue; }
            if (marker == 0x01) { pos += 2; continue; }
            int segLen = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (segLen < 2 || pos + 2 + segLen > jpeg.length) break;
            if (marker == 0xE1 && segLen >= 8
                    && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x'
                    && jpeg[pos + 6] == 'i' && jpeg[pos + 7] == 'f'
                    && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                app1DataStart = pos + 4;
                app1DataLen = segLen - 2;
                break;
            }
            pos += 2 + segLen;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length + 64);
        if (app1DataStart < 0) {
            byte[] minimal = minimalExifPayload();
            int len = minimal.length + 2;
            bos.write(jpeg, 0, 2);
            bos.write(0xFF);
            bos.write(0xE1);
            bos.write((len >> 8) & 0xFF);
            bos.write(len & 0xFF);
            bos.write(minimal, 0, minimal.length);
            bos.write(jpeg, 2, jpeg.length - 2);
            return bos.toByteArray();
        }

        byte[] payload = Arrays.copyOfRange(jpeg, app1DataStart, app1DataStart + app1DataLen);
        byte[] marked = writeTags(payload);
        if (marked == null || !verifyTags(marked)) {
            // 源 EXIF 结构异常（重建后解析不出标记）→ 回退自建完整模板
            marked = minimalExifPayload();
        }
        int len = marked.length + 2;
        if (len > 0xFFFF) return jpeg;
        bos.write(jpeg, 0, app1DataStart - 4);
        bos.write(0xFF);
        bos.write(0xE1);
        bos.write((len >> 8) & 0xFF);
        bos.write(len & 0xFF);
        bos.write(marked, 0, marked.length);
        bos.write(jpeg, app1DataStart + app1DataLen,
                jpeg.length - app1DataStart - app1DataLen);
        return bos.toByteArray();
    }

    // ---- FFE4 XIAOMI_CUSTOMIZE ----

    private static byte[] markCustomize(byte[] jpeg) {
        // 构造 FFE4 段：FF E4 + len + magic + json
        byte[] jsonBytes = CUSTOMIZE_JSON.getBytes(StandardCharsets.UTF_8);
        int payloadLen = CUSTOMIZE_MAGIC.length + jsonBytes.length;
        int segLen = payloadLen + 2;
        byte[] seg = new byte[segLen + 2];
        seg[0] = (byte) 0xFF;
        seg[1] = (byte) 0xE4;
        seg[2] = (byte) (segLen >> 8);
        seg[3] = (byte) (segLen & 0xFF);
        System.arraycopy(CUSTOMIZE_MAGIC, 0, seg, 4, CUSTOMIZE_MAGIC.length);
        System.arraycopy(jsonBytes, 0, seg, 4 + CUSTOMIZE_MAGIC.length, jsonBytes.length);

        // 扫描：若已存在 FFE4 XIAOMI_CUSTOMIZE，替换；否则在 SOI 后插入
        int pos = 2;
        int found = -1;
        int foundLen = 0;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            if (marker == 0xD8) { pos += 2; continue; }
            if (marker >= 0xD0 && marker <= 0xD7) { pos += 2; continue; }
            if (marker == 0x01) { pos += 2; continue; }
            int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) break;
            if (marker == 0xE4 && len >= CUSTOMIZE_MAGIC.length) {
                boolean match = true;
                for (int i = 0; i < CUSTOMIZE_MAGIC.length; i++) {
                    if (jpeg[pos + 4 + i] != CUSTOMIZE_MAGIC[i]) { match = false; break; }
                }
                if (match) { found = pos; foundLen = len + 2; break; }
            }
            pos += 2 + len;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length + seg.length + 8);
        if (found >= 0) {
            bos.write(jpeg, 0, found);
            bos.write(seg, 0, seg.length);
            bos.write(jpeg, found + foundLen, jpeg.length - found - foundLen);
        } else {
            bos.write(jpeg, 0, 2);       // SOI
            bos.write(seg, 0, seg.length);
            bos.write(jpeg, 2, jpeg.length - 2);
        }
        return bos.toByteArray();
    }

    // ---- EXIF tag 写入 ----

    private static byte[] writeTags(byte[] data) {
        if (data.length < 14) return data;
        boolean le = data[6] == 'I' && data[7] == 'I';
        if (!le && !(data[6] == 'M' && data[7] == 'M')) return data;
        long ifd0 = integer(data, 10, 4, le) + 6L;
        if (ifd0 < 14L || ifd0 > data.length - 2L) return data;
        int count = (int) integer(data, (int) ifd0, 2, le);
        for (int i = 0; i < count; i++) {
            long p = ifd0 + 2L + i * 12L;
            if (p > data.length - 12L) break;
            int e = (int) p;
            if (integer(data, e, 2, le) == TAG_EXIF_IFD_POINTER
                    && integer(data, e + 2, 2, le) == 4L
                    && integer(data, e + 4, 4, le) == 1L) {
                int exifIfd = (int) (integer(data, e + 8, 4, le) + 6L);
                if (exifIfd < 14 || exifIfd > data.length - 4) return null;
                // 删除动态照片标记 tag（0x8897=1）：处理链只保留主 EXIF 不保留容器元数据，
                // 残留的动态标记会让相册按复合照片路径判定而忽略相机标记
                removeEntryInPlace(data, le, exifIfd, 0x8897);
                removeEntryInPlace(data, le, (int) ifd0, 0x8897);
                byte[] afterExif = writeInExifIfd(data, le, e, exifIfd);
                // 把设备拍摄模式记录（XiaomiComment/AuxiliaryInfo）替换为目标值：
                // 相册徽章判定读 EXIF 本体的 filterId，普通图的 filterId 会导致判定失败
                // （华为等外来图没有这些 tag → 无操作，走宽松判定 → 有徽章）
                afterExif = writeReplaceAscii(afterExif, le, exifIfd, 0x9999, XIAOMI_COMMENT_LEICA);
                afterExif = writeReplaceAscii(afterExif, le, exifIfd, 0x889E, XIAOMI_AUX_LEICA);
                byte[] afterIfd0 = writeInIfd0(afterExif, le);
                long ifd0b = integer(afterIfd0, 10, 4, le) + 6L;
                return writeReplaceAscii(afterIfd0, le, (int) ifd0b, 0x9999, XIAOMI_COMMENT_LEICA);
            }
        }
        return null;
    }

    /** IFD0 确保 themeCustomize="Madrid"（样张在 IFD0 与 ExifIFD 各有一处，相册可能读 IFD0 的）。 */
    private static byte[] writeInIfd0(byte[] data, boolean le) {
        if (data.length < 14) return data;
        long ifd0 = integer(data, 10, 4, le) + 6L;
        if (ifd0 < 14L || ifd0 > data.length - 2L) return data;
        int count = (int) integer(data, (int) ifd0, 2, le);
        if (count < 0 || ifd0 + 6L + (long) count * 12L > data.length) return data;
        for (int i = 0; i < count; i++) {
            int e = (int) ifd0 + 2 + i * 12;
            if (integer(data, e, 2, le) == TAG_THEME) {
                int type = (int) integer(data, e + 2, 2, le);
                if (type == 2 && integer(data, e + 4, 4, le) >= 7) {
                    int off = (int) (integer(data, e + 8, 4, le) + 6L);
                    if (off >= 6 && off + 7 <= data.length) {
                        data[off] = 'M'; data[off + 1] = 'a'; data[off + 2] = 'd';
                        data[off + 3] = 'r'; data[off + 4] = 'i'; data[off + 5] = 'd';
                        data[off + 6] = 0;
                    }
                }
                return data;
            }
        }
        // 缺失：重建 IFD0 到 payload 末尾（TIFF header 的 IFD0 offset 同步更新）
        int base = (data.length + 1) & ~1;
        int newCount = count + 1;
        long total = (long) base + 6L + (long) newCount * 12L + 8L;
        if (total > 65533L) return data;
        byte[] out = Arrays.copyOf(data, (int) total);
        int dataOff = base + 6 + newCount * 12;
        writeInt(out, 10, base - 6L, 4, le);
        writeInt(out, base, newCount, 2, le);
        int w = base + 2;
        boolean themeWritten = false;
        for (int i = 0; i < count; i++) {
            int e = (int) ifd0 + 2 + i * 12;
            int tag = (int) integer(data, e, 2, le);
            if (!themeWritten && TAG_THEME < tag) { w = writeTheme(out, w, le, dataOff); themeWritten = true; }
            System.arraycopy(data, e, out, w, 12);
            w += 12;
        }
        if (!themeWritten) { w = writeTheme(out, w, le, dataOff); }
        System.arraycopy(data, (int) ifd0 + 2 + count * 12, out, w, 4);
        return out;
    }

    private static byte[] writeInExifIfd(byte[] data, boolean le, int ifd0Entry, int exifIfd) {
        int count = (int) integer(data, exifIfd, 2, le);
        if (count < 0 || exifIfd + 6L + (long) count * 12L > data.length) return data;

        boolean hasTheme = false, hasLegend = false, hasMode = false;
        for (int i = 0; i < count; i++) {
            int e = exifIfd + 2 + i * 12;
            int tag = (int) integer(data, e, 2, le);
            int type = (int) integer(data, e + 2, 2, le);
            if (tag == TAG_THEME) {
                hasTheme = true;
                if (type == 2 && integer(data, e + 4, 4, le) >= 7) {
                    int off = (int) (integer(data, e + 8, 4, le) + 6L);
                    if (off >= 6 && off + 7 <= data.length) {
                        data[off] = 'M'; data[off + 1] = 'a'; data[off + 2] = 'd';
                        data[off + 3] = 'r'; data[off + 4] = 'i'; data[off + 5] = 'd';
                        data[off + 6] = 0;
                    }
                }
            } else if (tag == TAG_LEGENDMODE) {
                hasLegend = true;
                if (type == 1 && integer(data, e + 4, 4, le) == 1) data[e + 8] = 1;
            } else if (tag == TAG_MODE) {
                hasMode = true;
                if (type == 2) {
                    writeInt(data, e + 4, 4L, 4, le);
                    data[e + 8] = '2'; data[e + 9] = '5'; data[e + 10] = '6'; data[e + 11] = 0;
                }
            }
        }
        if (hasTheme && hasLegend && hasMode) return data;

        int missing = (hasTheme ? 0 : 1) + (hasLegend ? 0 : 1) + (hasMode ? 0 : 1);
        int base = (data.length + 1) & ~1;
        int newCount = count + missing;
        int dataSize = hasTheme ? 0 : 8;
        long total = (long) base + 6L + (long) newCount * 12L + dataSize;
        if (total > 65533L) return data;
        byte[] out = Arrays.copyOf(data, (int) total);
        writeInt(out, ifd0Entry + 8, base - 6L, 4, le);
        writeInt(out, base, newCount, 2, le);

        int dataOff = base + 6 + newCount * 12;
        int w = base + 2;
        boolean themeWritten = hasTheme, legendWritten = hasLegend, modeWritten = hasMode;
        for (int i = 0; i < count; i++) {
            int e = exifIfd + 2 + i * 12;
            int tag = (int) integer(data, e, 2, le);
            if (!themeWritten && TAG_THEME < tag) { w = writeTheme(out, w, le, dataOff); themeWritten = true; }
            if (!legendWritten && TAG_LEGENDMODE < tag) { w = writeLegend(out, w, le); legendWritten = true; }
            if (!modeWritten && TAG_MODE < tag) { w = writeMode(out, w, le); modeWritten = true; }
            System.arraycopy(data, e, out, w, 12);
            w += 12;
        }
        if (!themeWritten) { w = writeTheme(out, w, le, dataOff); }
        if (!legendWritten) { w = writeLegend(out, w, le); }
        if (!modeWritten) { w = writeMode(out, w, le); }
        System.arraycopy(data, exifIfd + 2 + count * 12, out, w, 4);
        return out;
    }

    private static int writeTheme(byte[] b, int off, boolean le, int dataOff) {
        writeInt(b, off, TAG_THEME, 2, le);
        writeInt(b, off + 2, 2L, 2, le);
        writeInt(b, off + 4, 7L, 4, le);
        writeInt(b, off + 8, dataOff - 6L, 4, le);
        b[dataOff] = 'M'; b[dataOff + 1] = 'a'; b[dataOff + 2] = 'd';
        b[dataOff + 3] = 'r'; b[dataOff + 4] = 'i'; b[dataOff + 5] = 'd';
        b[dataOff + 6] = 0;
        return off + 12;
    }

    private static int writeLegend(byte[] b, int off, boolean le) {
        writeInt(b, off, TAG_LEGENDMODE, 2, le);
        writeInt(b, off + 2, 1L, 2, le);
        writeInt(b, off + 4, 1L, 4, le);
        b[off + 8] = 1;
        return off + 12;
    }

    private static int writeMode(byte[] b, int off, boolean le) {
        writeInt(b, off, TAG_MODE, 2, le);
        writeInt(b, off + 2, 2L, 2, le);
        writeInt(b, off + 4, 4L, 4, le);
        b[off + 8] = '2'; b[off + 9] = '5'; b[off + 10] = '6'; b[off + 11] = 0;
        return off + 12;
    }

    /**
     * 自建完整相机 EXIF 模板（动态计算偏移，杜绝手算错误）：
     * IFD0{Make, Model, themeCustomize="Madrid", →ExifIFD}
     * ExifIFD{themeCustomize="Madrid", legendmode=1, mode="256"}
     */
    private static byte[] minimalExifPayload() {
        byte[] make = asciiZ("Xiaomi");
        byte[] model = asciiZ("Xiaomi 15 Ultra");
        byte[] madrid = asciiZ("Madrid");

        final int ifd0Off = 8;
        final int ifd0N = 4;
        final int exifOff = ifd0Off + 2 + ifd0N * 12 + 4;
        final int exifN = 3;
        final int dataOff = exifOff + 2 + exifN * 12 + 4;
        final int offMake = dataOff;
        final int offModel = offMake + make.length;
        final int offM0 = offModel + model.length;
        final int offM1 = offM0 + madrid.length;

        byte[] p = new byte[6 + offM1 + madrid.length];
        p[0] = 'E'; p[1] = 'x'; p[2] = 'i'; p[3] = 'f';
        p[6] = 'I'; p[7] = 'I'; p[8] = 0x2A;
        le32(p, 10, ifd0Off);

        int w = 6 + ifd0Off;
        p[w++] = (byte) ifd0N;
        w = putEntry(p, w, 0x010F, 2, make.length, offMake);
        w = putEntry(p, w, 0x0110, 2, model.length, offModel);
        w = putEntry(p, w, 0x889D, 2, madrid.length, offM0);
        w = putEntry(p, w, 0x8769, 4, 1, exifOff);
        w += 4; // next IFD = 0

        int e = 6 + exifOff;
        p[e] = (byte) exifN;
        w = e + 2;
        w = putEntry(p, w, 0x889D, 2, madrid.length, offM1);
        w = putEntry(p, w, 0x88B0, 1, 1, 1);          // inline value=1
        w = putEntry(p, w, 0xA661, 2, 4, -1);         // inline "256\0"
        p[w - 4] = '2'; p[w - 3] = '5'; p[w - 2] = '6'; p[w - 1] = 0;

        System.arraycopy(make, 0, p, 6 + offMake, make.length);
        System.arraycopy(model, 0, p, 6 + offModel, model.length);
        System.arraycopy(madrid, 0, p, 6 + offM0, madrid.length);
        System.arraycopy(madrid, 0, p, 6 + offM1, madrid.length);
        return p;
    }

    private static int putEntry(byte[] p, int w, int tag, int type, int count, long value) {
        p[w] = (byte) tag; p[w + 1] = (byte) (tag >> 8);
        p[w + 2] = (byte) type; p[w + 3] = (byte) (type >> 8);
        le32(p, w + 4, count);
        if (value >= 0) le32(p, w + 8, value);
        return w + 12;
    }

    private static void le32(byte[] p, int off, long v) {
        p[off] = (byte) v; p[off + 1] = (byte) (v >> 8);
        p[off + 2] = (byte) (v >> 16); p[off + 3] = (byte) (v >> 24);
    }

    private static byte[] asciiZ(String s) {
        byte[] b = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    /** 将 IFD 内指定 ASCII tag 的值整体替换为新字符串（新数据区追加到 payload 末尾）。tag 不存在则原样返回。 */
    private static byte[] writeReplaceAscii(byte[] data, boolean le, int ifdOff, int tag, String newValue) {
        try {
            if (ifdOff < 14 || ifdOff > data.length - 2) return data;
            int count = (int) integer(data, ifdOff, 2, le);
            if (count <= 0) return data;
            for (int i = 0; i < count; i++) {
                int e = ifdOff + 2 + i * 12;
                if (e + 12 > data.length) return data;
                if (integer(data, e, 2, le) != tag) continue;
                if (integer(data, e + 2, 2, le) != 2) return data; // 非 ASCII 不动
                byte[] nv = asciiZ(newValue);
                int base = (data.length + 1) & ~1;
                long total = (long) base + 6L + nv.length;
                if (total > 65533L) return data;
                byte[] out = Arrays.copyOf(data, (int) total);
                System.arraycopy(nv, 0, out, base + 6, nv.length);
                writeInt(out, e + 4, nv.length, 4, le);
                writeInt(out, e + 8, base - 6L, 4, le);
                return out;
            }
            return data;
        } catch (Throwable t) {
            return data;
        }
    }

    /** 校验 payload 的三处标记是否可解析（与写入侧相同的 offset 语义）。 */
    private static boolean verifyTags(byte[] payload) {
        try {
            if (payload.length < 14) return false;
            boolean le = payload[6] == 'I' && payload[7] == 'I';
            if (!le && !(payload[6] == 'M' && payload[7] == 'M')) return false;
            long ifd0 = integer(payload, 10, 4, le) + 6;
            if (ifd0 < 14 || ifd0 > payload.length - 2) return false;
            int count = (int) integer(payload, (int) ifd0, 2, le);
            boolean theme0 = false;
            long exif = -1;
            for (int i = 0; i < count; i++) {
                int e = (int) ifd0 + 2 + i * 12;
                if (e + 12 > payload.length) return false;
                int tag = (int) integer(payload, e, 2, le);
                if (tag == TAG_THEME) theme0 = true;
                if (tag == TAG_EXIF_IFD_POINTER && integer(payload, e + 2, 2, le) == 4
                        && integer(payload, e + 4, 4, le) == 1) {
                    exif = integer(payload, e + 8, 4, le) + 6;
                }
            }
            if (!theme0 || exif < 14 || exif > payload.length - 4) return false;
            int n = (int) integer(payload, (int) exif, 2, le);
            boolean t = false, l = false, m = false;
            for (int i = 0; i < n; i++) {
                int e = (int) exif + 2 + i * 12;
                if (e + 12 > payload.length) return false;
                int tag = (int) integer(payload, e, 2, le);
                if (tag == TAG_THEME) t = true;
                if (tag == TAG_LEGENDMODE) l = true;
                if (tag == TAG_MODE) m = true;
            }
            return t && l && m;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long integer(byte[] b, int off, int len, boolean le) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            int k = le ? len - i - 1 : i;
            v = (v << 8) | (b[k + off] & 0xFF);
        }
        return v;
    }

    private static void writeInt(byte[] b, int off, long v, int len, boolean le) {
        for (int i = 0; i < len; i++) {
            int k = le ? i : len - 1 - i;
            b[k + off] = (byte) (v >>> (i * 8));
        }
    }

    // ---- 文件级写入（200MP 全像素直通导出用）----

    /**
     * 对落盘 JPEG 原地写入相机标记，**不把整幅图像读进 Java 堆**。
     *
     * <p>本类的所有段改写（删 XMP/MPF/ISO、替换 EXIF APP1、追加 FFE4）都只在容器头
     * （SOI..SOS 段）里发生，熵编码数据只是原样后移。因此这里把容器头读成小数组走
     * {@link #mark(byte[])}，再把原文件 SOS 之后的字节流搬运到新文件，最后替换原文件。</p>
     *
     * @return 是否写成功（失败时原文件保持不变）
     */
    public static boolean markFile(File file) {
        return markFile(file, null);
    }

    /**
     * 同上，附带 ICC 色彩档案段（§22 P1）。
     *
     * <p>ICC 注入与徽章改写合并成<b>同一次</b>文件重写，不额外搬运一遍熵编码数据。</p>
     *
     * @param iccBlocks 源图的 APP2/ICC_PROFILE 段（含段头，可能多段）；null/空 = 不处理
     */
    public static boolean markFile(File file, byte[] iccBlocks) {
        if (file == null || !file.isFile() || file.length() < 4) return false;
        File tmp = null;
        RandomAccessFile in = null;
        FileOutputStream out = null;
        try {
            byte[] header = readHeader(file);
            if (header == null) return false;
            byte[] marked = mark(header);
            if (marked == null || marked.length < 4) return false;
            if (iccBlocks != null && iccBlocks.length > 0) {
                marked = IccProfile.embed(marked, iccBlocks);
            }
            if (marked == header) return true; // 无需改写

            tmp = new File(file.getAbsoluteFile().getParentFile(),
                    file.getName() + ".mk" + System.nanoTime());
            in = new RandomAccessFile(file, "r");
            in.seek(header.length); // 容器头之后 = 熵编码起点
            out = new FileOutputStream(tmp);
            out.write(marked, 0, marked.length);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;

            if (tmp.renameTo(file)) {
                tmp = null;
                return true;
            }
            // 同目录 rename 失败（极少见）：流式覆盖原文件
            FileInputStream fis = new FileInputStream(tmp);
            try {
                FileOutputStream fos = new FileOutputStream(file);
                try {
                    while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.flush();
                } finally {
                    fos.close();
                }
            } finally {
                fis.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp != null) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读容器头：SOI 起至 SOS 段末（含 SOS 段头，不含熵编码数据）。失败返回 null。 */
    private static byte[] readHeader(File file) {
        long total = file.length();
        if (total < 4) return null;
        int cap = (int) Math.min(total, 4L * 1024 * 1024);
        byte[] buf = new byte[cap];
        int read = 0;
        try {
            FileInputStream ins = new FileInputStream(file);
            try {
                while (read < cap) {
                    int n = ins.read(buf, read, cap - read);
                    if (n <= 0) break;
                    read += n;
                }
            } finally {
                ins.close();
            }
        } catch (Throwable t) {
            return null;
        }
        if (read < 4 || (buf[0] & 0xFF) != 0xFF || (buf[1] & 0xFF) != 0xD8) return null;
        int p = 2;
        while (p + 3 < read) {
            if ((buf[p] & 0xFF) != 0xFF) return null;
            int m = p + 1;
            while (m < read && (buf[m] & 0xFF) == 0xFF) m++;
            if (m >= read) return null;
            int marker = buf[m] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                p = m + 1;
                continue;
            }
            if (marker == 0xD9) return Arrays.copyOf(buf, m + 1);
            if (m + 2 >= read) return null;
            int segLen = ((buf[m + 1] & 0xFF) << 8) | (buf[m + 2] & 0xFF);
            if (segLen < 2) return null;
            int end = m + 1 + segLen;
            if (end > read) return null;
            if (marker == 0xDA) return Arrays.copyOf(buf, end);
            p = end;
        }
        return null;
    }
}

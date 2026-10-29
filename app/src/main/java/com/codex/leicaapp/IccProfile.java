package com.codex.leicaapp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * JPEG ICC 色彩档案迁移（§22 P1）。
 *
 * <p>背景：全链解码不转色域（{@code BitmapFactory} 原样取 Display P3 编码数值），
 * 出口重编码不写 ICC，查看器按 sRGB 解释 → 红/绿方向过饱和。本类只做「字节搬运」：
 * 把源 JPEG 的 APP2/{@code ICC_PROFILE} 段原样取出、原样嵌进输出容器头，<b>像素零改动</b>。</p>
 *
 * <p>纪律：任何异常/无 ICC 一律返回原样/null，调用方静默跳过——色彩档案写失败不能丢图。</p>
 *
 * <p><b>2026-10-29 A1 起本类没有调用点</b>：全链解码统一到 sRGB（所有解码处
 * {@code inPreferredColorSpace = SRGB}），成品不再搬运源 ICC，见
 * {@link MadridLegendary#copyExif}。若日后启用 A2 广色域路线，必须「去掉解码处的 sRGB
 * 请求 + 恢复 ICC 搬运」成套执行，只改一边会重现 sRGB 数值被贴 P3 标签的过饱和。</p>
 */
public final class IccProfile {

    /** ICC_PROFILE 段标识（APP2 payload 前 12 字节）。 */
    private static final byte[] SIG = {'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};

    /** 源文件只读前 4 MB 找容器头（真实头 < 200 KB）。 */
    private static final int PROC_HEAD = 4 * 1024 * 1024;

    private static final int MARKER_SOS = 218;
    private static final int MARKER_EOI = 217;
    private static final int MARKER_SOI = 216;
    private static final int MARKER_APP2 = 226;

    private IccProfile() {
    }

    /** 段 payload 是否以 {@code ICC_PROFILE\0} 开头。 */
    static boolean isIccPayload(byte[] buf, int off, int len) {
        if (off < 0 || len < SIG.length || off + SIG.length > buf.length) return false;
        for (int i = 0; i < SIG.length; i++) {
            if (buf[off + i] != SIG[i]) return false;
        }
        return true;
    }

    /**
     * 从容器头提取所有 {@code APP2/ICC_PROFILE} 段（含段头，大 profile 会分成多段）。
     *
     * @return 段字节序列；无 ICC 或源非法时返回 null
     */
    public static byte[] extract(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4) return null;
        if ((jpeg[0] & 255) != 255 || (jpeg[1] & 255) != MARKER_SOI) return null;
        ByteArrayOutputStream out = null;
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 255) == 255) {
            int marker = jpeg[pos + 1] & 255;
            if (marker == MARKER_SOS || marker == MARKER_EOI) break;
            if (marker == MARKER_SOI || marker == 1 || (marker >= 208 && marker <= 215)) {
                pos += 2;
                continue;
            }
            int len = ((jpeg[pos + 2] & 255) << 8) | (jpeg[pos + 3] & 255);
            if (len < 2 || pos + len + 2 > jpeg.length) break;
            if (marker == MARKER_APP2 && isIccPayload(jpeg, pos + 4, len - 2)) {
                if (out == null) out = new ByteArrayOutputStream(4096);
                out.write(jpeg, pos, len + 2);
            }
            pos += len + 2;
        }
        return out == null ? null : out.toByteArray();
    }

    /**
     * 把 ICC 段嵌到容器头 APPn 区末尾，<b>并移除已有的 ICC 段</b>（替换语义）。
     *
     * <p>必须是替换而不是追加：Android 的 JPEG 编码器（Skia）会给输出自带一段
     * 456 字节的 {@code sRGB} profile，与源图的 P3 profile 并存会让解码器整个放弃
     * 色彩档案（两段 seq 都是 1/1，拼接失败）。</p>
     *
     * @return 新数组；已就位（唯一 ICC 段且与 blocks 相同）时返回<b>同一引用</b>
     */
    public static byte[] embed(byte[] jpeg, byte[] blocks) {
        if (jpeg == null || jpeg.length < 4 || blocks == null || blocks.length == 0) return jpeg;
        if ((jpeg[0] & 255) != 255 || (jpeg[1] & 255) != MARKER_SOI) return jpeg;

        int lastAppEnd = 2; // 最后一个非 ICC 的 APPn 段末尾（blocks 插在它之后）
        int iccCount = 0;
        boolean sameIcc = false;
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 255) == 255) {
            int marker = jpeg[pos + 1] & 255;
            if (marker == MARKER_SOS || marker == MARKER_EOI) break;
            if (marker == MARKER_SOI || marker == 1 || (marker >= 208 && marker <= 215)) {
                pos += 2;
                continue;
            }
            int len = ((jpeg[pos + 2] & 255) << 8) | (jpeg[pos + 3] & 255);
            if (len < 2 || pos + len + 2 > jpeg.length) break;
            if (marker == MARKER_APP2 && isIccPayload(jpeg, pos + 4, len - 2)) {
                iccCount++;
                if (!sameIcc && len + 2 == blocks.length && regionEquals(jpeg, pos, blocks)) {
                    sameIcc = true;
                }
            } else if (marker >= 224 && marker <= 239) {
                lastAppEnd = pos + len + 2;
            }
            pos += len + 2;
        }
        int headerEnd = pos; // SOS（或首个非段字节）位置
        if (iccCount == 1 && sameIcc) return jpeg;

        ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length + blocks.length);
        bos.write(jpeg, 0, 2); // SOI
        boolean placed = false;
        int p = 2;
        while (p < headerEnd) {
            if (!placed && p >= lastAppEnd) {
                bos.write(blocks, 0, blocks.length);
                placed = true;
            }
            if ((jpeg[p] & 255) != 255 || p + 4 > headerEnd) {
                bos.write(jpeg, p, headerEnd - p);
                break;
            }
            int marker = jpeg[p + 1] & 255;
            if (marker == MARKER_SOI || marker == 1 || (marker >= 208 && marker <= 215)) {
                bos.write(jpeg, p, 2);
                p += 2;
                continue;
            }
            int len = ((jpeg[p + 2] & 255) << 8) | (jpeg[p + 3] & 255);
            if (len < 2 || p + len + 2 > headerEnd) {
                bos.write(jpeg, p, headerEnd - p);
                break;
            }
            if (!(marker == MARKER_APP2 && isIccPayload(jpeg, p + 4, len - 2))) {
                bos.write(jpeg, p, len + 2);
            }
            p += len + 2;
        }
        if (!placed) bos.write(blocks, 0, blocks.length);
        bos.write(jpeg, headerEnd, jpeg.length - headerEnd);
        return bos.toByteArray();
    }

    /** 把 jpeg 的容器头（跳过所有 ICC 段）与 SOS 之后的数据整体写进 out。 */
    public static void copyWithoutIcc(ByteArrayOutputStream out, byte[] jpeg) {
        if (out == null || jpeg == null || jpeg.length < 4) return;
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 255) == 255) {
            int marker = jpeg[pos + 1] & 255;
            if (marker == MARKER_SOS || marker == MARKER_EOI) break;
            if (marker == MARKER_SOI || marker == 1 || (marker >= 208 && marker <= 215)) {
                out.write(jpeg, pos, 2);
                pos += 2;
                continue;
            }
            int len = ((jpeg[pos + 2] & 255) << 8) | (jpeg[pos + 3] & 255);
            if (len < 2 || pos + len + 2 > jpeg.length) break;
            if (!(marker == MARKER_APP2 && isIccPayload(jpeg, pos + 4, len - 2))) {
                out.write(jpeg, pos, len + 2);
            }
            pos += len + 2;
        }
        out.write(jpeg, pos, jpeg.length - pos);
    }

    private static boolean regionEquals(byte[] a, int off, byte[] b) {
        if (off + b.length > a.length) return false;
        for (int i = 0; i < b.length; i++) {
            if (a[off + i] != b[i]) return false;
        }
        return true;
    }

    /** 只读源文件容器头取 ICC 段（不把整幅图像读进堆）。失败返回 null。 */
    public static byte[] readBlocks(File src) {
        if (src == null || !src.isFile()) return null;
        FileInputStream fis = null;
        try {
            long n = Math.min(src.length(), (long) PROC_HEAD);
            if (n < 4) return null;
            byte[] buf = new byte[(int) n];
            fis = new FileInputStream(src);
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            return extract(buf);
        } catch (Throwable t) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

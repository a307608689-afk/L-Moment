package com.codex.leicaapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 三个风格 mod 的核心引擎封装（纯离线图片处理）。
 *
 * <p>native 方法由 libmadridlegend.so 导出（JNI 符号
 * {@code Java_com_codex_leicaapp_MadridLegendary_filter / _grain}），
 * 因此类名与包名必须保持 {@code com.codex.leicaapp.MadridLegendary} 不变。</p>
 *
 * <p>处理链：JPEG 解码 → LUT 调色（filter）→ M3 黑白（grain）→ JPEG 编码 → EXIF 回填。</p>
 */
public final class MadridLegendary {

    private static boolean loaded = false;

    // ---- native (libmadridlegend.so) ----
    static native boolean filter(Bitmap bitmap, String binPath, float lux, float cct, float zoom, float extra);

    static native boolean grain(Bitmap bitmap, String monopanRunnerPath, String cachePath);

    private MadridLegendary() {
    }

    /** 加载 libmadridlegend.so。 */
    public static synchronized void load(String nativeLibraryDir) {
        if (!loaded) {
            System.load(nativeLibraryDir + "/libmadridlegend.so");
            loaded = true;
        }
    }

    /** 把源 JPEG 的 APP1(Exif) 段回填到输出 JPEG 的 SOI 之后。 */
    public static byte[] copyExif(byte[] srcJpeg, byte[] outJpeg) {
        if (srcJpeg.length >= 4 && outJpeg.length >= 4
                && (srcJpeg[0] & 255) == 255 && (srcJpeg[1] & 255) == 216) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(outJpeg.length + 8192);
            bos.write(outJpeg, 0, 2); // SOI
            int pos = 2;
            boolean copied = false;
            while (true) {
                if (pos + 4 > srcJpeg.length || (srcJpeg[pos] & 255) != 255) break;
                int marker = srcJpeg[pos + 1] & 255;
                if (marker == 218 || marker == 217) break; // SOS / EOI
                int len = ((srcJpeg[pos + 2] & 255) << 8) | (srcJpeg[pos + 3] & 255);
                if (len < 2 || pos + len + 2 > srcJpeg.length) break;
                if (!copied && marker == 225 && len >= 8
                        && srcJpeg[pos + 4] == 'E' && srcJpeg[pos + 5] == 'x'
                        && srcJpeg[pos + 6] == 'i' && srcJpeg[pos + 7] == 'f'
                        && srcJpeg[pos + 8] == 0 && srcJpeg[pos + 9] == 0) {
                    bos.write(srcJpeg, pos, len + 2); // 只回填第一个 Exif（动态照片 MVIMG 会有第二个空壳帧 EXIF，相册读到它会导致标记失效）
                    copied = true;
                }
                pos += len + 2;
            }
            bos.write(outJpeg, 2, outJpeg.length - 2);
            return bos.toByteArray();
        }
        return outJpeg;
    }

    /** 把源 JPEG 的第一个 Exif 段 IFD0 的 Orientation(0x0112) 就地改写为 1（像素已摆正后调用）。
     *  无 Exif / 无该 tag / 解析失败一律原样返回。 */
    public static byte[] patchExifOrientationToOne(byte[] jpeg) {
        try {
            if (jpeg == null || jpeg.length < 4
                    || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216) {
                return jpeg;
            }
            int pos = 2;
            while (pos + 4 <= jpeg.length && (jpeg[pos] & 255) == 255) {
                int marker = jpeg[pos + 1] & 255;
                if (marker == 218 || marker == 217) break; // SOS / EOI
                int len = ((jpeg[pos + 2] & 255) << 8) | (jpeg[pos + 3] & 255);
                if (len < 2 || pos + len + 2 > jpeg.length) break;
                if (marker == 225 && len >= 10
                        && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x'
                        && jpeg[pos + 6] == 'i' && jpeg[pos + 7] == 'f'
                        && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                    byte[] out = jpeg.clone();
                    int tiff = pos + 10;
                    if (tiff + 8 > out.length) return out;
                    boolean le = out[tiff] == 'I' && out[tiff + 1] == 'I';
                    boolean be = out[tiff] == 'M' && out[tiff + 1] == 'M';
                    if (!le && !be) return out;
                    long ifdRel = u32(out, tiff + 4, le);
                    int ifd = tiff + (int) ifdRel;
                    if (ifd < 0 || ifd + 2 > out.length) return out;
                    int count = readU16(out, ifd, le);
                    for (int i = 0; i < count; i++) {
                        int e = ifd + 2 + i * 12;
                        if (e + 12 > out.length) break;
                        int tag = readU16(out, e, le);
                        if (tag == 0x0112) {
                            // SHORT count=1 → 值内联在 entry+8（SHORT 与 LONG 内联两种布局都兼容）
                            if (le) { out[e + 8] = 1; out[e + 9] = 0; }
                            else { out[e + 8] = 0; out[e + 9] = 1; }
                            break;
                        }
                    }
                    return out;
                }
                pos += len + 2;
            }
            return jpeg;
        } catch (Throwable t) {
            return jpeg;
        }
    }

    private static int readU16(byte[] b, int off, boolean le) {
        return le ? (b[off] & 255) | ((b[off + 1] & 255) << 8)
                  : ((b[off] & 255) << 8) | (b[off + 1] & 255);
    }

    private static long u32(byte[] b, int off, boolean le) {
        return le ? (b[off] & 255L) | ((b[off + 1] & 255L) << 8)
                     | ((b[off + 2] & 255L) << 16) | ((b[off + 3] & 255L) << 24)
                  : ((b[off] & 255L) << 24) | ((b[off + 1] & 255L) << 16)
                     | ((b[off + 2] & 255L) << 8) | (b[off + 3] & 255L);
    }

    /** 从 assets/legend18 复制参数到 filesDir，返回本地路径。 */
    private static File parameters(Context ctx, String name) throws IOException {
        File dir = new File(ctx.getFilesDir(), "madrid-legend18-v1");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create parameter directory");
        }
        File f = new File(dir, name);
        if (f.isFile() && f.length() > 0) return f;

        File tmp = new File(dir, name + ".tmp");
        InputStream in = ctx.getAssets().open("legend18/" + name);
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
        if (!tmp.renameTo(f)) throw new IOException("Cannot publish parameters");
        return f;
    }

    /**
     * 对一张 JPEG 应用指定风格（"M3" / "M9" / "M10R"），返回处理后的 JPEG 字节。
     * 失败时返回原图字节。
     */
    public static byte[] process(Context ctx, byte[] jpeg, String style) {
        String bin;
        if ("M3".equals(style)) {
            bin = "leica_filter_param_m3.bin";
        } else if ("M9".equals(style)) {
            bin = "leica_filter_param_m9_snapshot.bin";
        } else {
            bin = "leica_filter_param_m10r_snapshot.bin";
        }

        try {
            load(ctx.getApplicationInfo().nativeLibraryDir);
            File binFile = parameters(ctx, bin);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0
                    || (long) opts.outWidth * opts.outHeight > 50_000_000L) {
                return jpeg;
            }
            opts.inJustDecodeBounds = false;
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return jpeg;

            // LUT 调色（lux=200, cct=5500, zoom=1 为默认值）
            boolean ok = filter(bmp, binFile.getAbsolutePath(), 200f, 5500f, 1f, 0f);
            if (!ok) {
                bmp.recycle();
                return jpeg;
            }

            // M3 黑白（monopan）
            if ("M3".equals(style)) {
                String mono = ctx.getApplicationInfo().nativeLibraryDir + "/libmonopan_runner.so";
                if (!grain(bmp, mono, ctx.getCacheDir().getAbsolutePath())) {
                    // 黑白兜底：去饱和
                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(0f);
                    Paint p = new Paint();
                    p.setColorFilter(new ColorMatrixColorFilter(cm));
                    Canvas c = new Canvas(bmp);
                    c.drawBitmap(bmp, 0f, 0f, p);
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length);
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos);
            bmp.recycle();
            return copyExif(jpeg, bos.toByteArray());
        } catch (Throwable t) {
            return jpeg;
        }
    }
}

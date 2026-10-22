package com.codex.leicaapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 完整离线处理链（不依赖相机服务）。
 *
 * <p>native 方法由 libleicamodelembed.so 导出，类名与包名必须保持
 * {@code com.codex.leicaapp.LegendaryPostProcessor} 不变。</p>
 *
 * <p>M3 = applyOriginalFilter（LUT）+ applyMonochrome（黑白）；<br>
 * M9 = applyStyle（styletrans 神经网络）+ applyWarmOriginalFilter（暖调 LUT）。</p>
 */
public final class LegendaryPostProcessor {

    // ---- native (libleicamodelembed.so) ----
    private static native boolean applyOriginalFilter(Bitmap b, String bin, float lux, float cct, float zoom, float time);

    private static native boolean applyMonochrome(Bitmap b, String runner, String cache);

    private static native boolean applyStyle(Bitmap b, String runner, String cache, String models, int rotation, int lux, int cct);

    private static native boolean applyWarmOriginalFilter(
            Bitmap b, String bin, float lux, float cct, float zoom, float time, float denoise, float ev, float warmth);

    // ---- M9+ 放大参数（真机调参改这里；M9 默认 denoise 0.6 / ev -0.95 / warmth 0.3）----
    public static final float M9P_DENOISE = 0.6f;
    public static final float M9P_EV = -1.3f;
    public static final float M9P_WARMTH = 0.5f;

    private static boolean modelsReady = false;

    /**
     * 确凿识别为非高通 SoC（如联发科天玑）时，QNN HTP DSP 通道物理不存在，
     * styletrans 主链 init 必败（白等 1~3s）。只在 API31+ 且 SOC_MANUFACTURER
     * 可读且明确不含高通标识时返回 true；读不到/unknown 一律按高通处理（fail-open，
     * 不因误判丢掉高通机型的完整链）。Build.HARDWARE 的高通平台是代号
     * （sun/kalama/taro…）不含 qcom 字样，故不可作判据。
     */
    private static boolean isNonQualcommSoC() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                String soc = android.os.Build.SOC_MANUFACTURER;
                if (soc == null || soc.isEmpty() || "unknown".equalsIgnoreCase(soc)) return false;
                String s = soc.toLowerCase(java.util.Locale.US);
                return !(s.contains("qcom") || s.contains("qualcomm") || s.contains("qti"));
            }
        } catch (Throwable t) { /* ignore */ }
        return false;
    }

    private static String m3ModelPath;
    private static String m9ModelPath;
    private static String m10rModelPath;
    private static String styleModels;
    private static String styleRunner;
    private static String monopanRunner;
    private static String cachePath;

    static {
        System.loadLibrary("leicamodelembed");
    }

    private LegendaryPostProcessor() {
    }

    // ---- 模型初始化 ----

    public static synchronized void ensureModels(Context ctx) {
        if (modelsReady) return;
        try {
            String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
            styleRunner = nativeDir + "/libstyletrans_runner.so";
            File v4 = new File(ctx.getFilesDir(), "leica-v4");
            if (!new File(v4, ".ready").isFile()) {
                copyTree(ctx, "leica-v4", v4);
                new File(v4, ".ready").createNewFile();
            }
            styleModels = v4.getAbsolutePath();
            monopanRunner = nativeDir + "/libmonopan_runner.so";
            cachePath = ctx.getCacheDir().getAbsolutePath();
            m3ModelPath = copyAsset(ctx, "leica_filter_param_m3.bin");
            m9ModelPath = copyAsset(ctx, "leica_filter_param_m9_snapshot.bin");
            m10rModelPath = copyAsset(ctx, "leica_filter_param_m10r_snapshot.bin");
            modelsReady = true;
        } catch (Throwable ignored) {
        }
    }

    private static String copyAsset(Context ctx, String name) throws IOException {
        File f = new File(ctx.getFilesDir(), name);
        if (f.exists() && f.length() > 4096L) return f.getAbsolutePath();
        InputStream in = ctx.getAssets().open(name);
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        return f.getAbsolutePath();
    }

    private static void copyTree(Context ctx, String assetPath, File dest) throws IOException {
        String[] names = ctx.getAssets().list(assetPath);
        if (names != null && names.length > 0) {
            dest.mkdirs();
            for (String n : names) {
                copyTree(ctx, assetPath + "/" + n, new File(dest, n));
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            InputStream in = ctx.getAssets().open(assetPath);
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
        }
    }

    // ---- 处理 ----

    /** M3：LUT + 黑白。失败返回 null（由调用方降级）。 */
    public static byte[] processM3(Context ctx, byte[] jpeg) {
        ensureModels(ctx);
        if (m3ModelPath == null) return null;
        try {
            Bitmap bmp = decode(jpeg);
            if (bmp == null) return null;

            float lux = 200f, cct = 5500f, zoom = 1f;
            float time = SystemClock.uptimeMillis() % 60000L;
            boolean ok = applyOriginalFilter(bmp, m3ModelPath, lux, cct, zoom, time);
            if (!ok) {
                bmp.recycle();
                return null;
            }
            applyMonochrome(bmp, monopanRunner, cachePath);

            byte[] out = encode(bmp, jpeg);
            bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** M9：styletrans + 暖调 LUT。失败返回 null（由调用方降级到纯 LUT）。 */
    public static byte[] processM9(Context ctx, byte[] jpeg) {
        ensureModels(ctx);
        if (isNonQualcommSoC()) return null;
        if (m9ModelPath == null || styleModels == null) return null;
        try {
            Bitmap bmp = decode(jpeg);
            if (bmp == null) return null;
            bmp = alignEven(bmp);

            int rotation = readOrientation(jpeg);
            int lux = 200, cct = 5500;
            float zoom = 1f;
            float time = SystemClock.uptimeMillis() % 60000L;

            // styletrans
            boolean ok = applyStyleOriented(bmp, rotation, lux, cct);
            if (!ok) {
                bmp.recycle();
                return null;
            }
            // 暖调 LUT（denoise 0.6 / ev -0.95 / warmth 0.3 为默认）
            boolean ok2 = applyWarmOriginalFilter(bmp, m9ModelPath, lux, cct, zoom, time, 0.6f, -0.95f, 0.3f);
            if (!ok2) {
                bmp.recycle();
                return null;
            }

            byte[] out = encode(bmp, jpeg);
            bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * M9+：styletrans + 放大暖调 LUT（比 M9 更激进的暖调/暗部）。
     * 与 {@link #processM9} 完全同链，仅 applyWarmOriginalFilter 的
     * denoise/ev/warmth 用放大值（M9 默认 0.6 / -0.95 / 0.3）。
     * 失败返回 null（由调用方降级）。
     */
    public static byte[] processM9Plus(Context ctx, byte[] jpeg) {
        ensureModels(ctx);
        if (isNonQualcommSoC()) return null;
        if (m9ModelPath == null || styleModels == null) return null;
        try {
            Bitmap bmp = decode(jpeg);
            if (bmp == null) return null;
            bmp = alignEven(bmp);

            int rotation = readOrientation(jpeg);
            int lux = 200, cct = 5500;
            float zoom = 1f;
            float time = SystemClock.uptimeMillis() % 60000L;

            boolean ok = applyStyleOriented(bmp, rotation, lux, cct);
            if (!ok) {
                bmp.recycle();
                return null;
            }
            boolean ok2 = applyWarmOriginalFilter(bmp, m9ModelPath, lux, cct, zoom, time,
                    M9P_DENOISE, M9P_EV, M9P_WARMTH);
            if (!ok2) {
                bmp.recycle();
                return null;
            }

            byte[] out = encode(bmp, jpeg);
            bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 对一张 JPEG 补一层暖调 LUT（bin 资产名可指定，供 ORT 降级链按模式选用
     * m9/m10r snapshot bin）。仅 applyWarmOriginalFilter，不跑 styletrans。
     * 失败返回 null。
     */
    public static byte[] applyWarmLutBin(Context ctx, byte[] jpeg, String binAsset,
                                         float denoise, float ev, float warmth) {
        ensureModels(ctx);
        if (!modelsReady) return null;
        try {
            String bin = copyAsset(ctx, binAsset);
            Bitmap bmp = decode(jpeg);
            if (bmp == null) return null;

            float lux = 200f, cct = 5500f, zoom = 1f;
            float time = SystemClock.uptimeMillis() % 60000L;
            boolean ok = applyWarmOriginalFilter(bmp, bin, lux, cct, zoom, time,
                    denoise, ev, warmth);
            if (!ok) {
                bmp.recycle();
                return null;
            }

            byte[] out = encode(bmp, jpeg);
            bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 对一张 Bitmap 补一层暖调 LUT（bin 资产名可指定，供 ORT 降级链 Bitmap 直入直出，
     * 消灭 styled JPEG 中间代）。输入若非 mutable 会先拷贝；失败返回 null。
     */
    public static Bitmap applyWarmLutBitmapBin(Context ctx, Bitmap in, String binAsset,
                                               float denoise, float ev, float warmth) {
        return applyWarmLutBitmapBinAt(ctx, in, binAsset, denoise, ev, warmth,
                SystemClock.uptimeMillis() % 60000L);
    }

    /**
     * 同 {@link #applyWarmLutBitmapBin(Context, Bitmap, String, float, float, float)}，
     * 但由调用方固定 native 的 time 参数。条带导出必须逐带传同一个 time（整图链只调用一次），
     * 否则带间可能因 time 不同产生色调跳变。
     */
    public static Bitmap applyWarmLutBitmapBinAt(Context ctx, Bitmap in, String binAsset,
                                                 float denoise, float ev, float warmth, float time) {
        ensureModels(ctx);
        if (!modelsReady) return null;
        Bitmap bmp = null;
        try {
            String bin = copyAsset(ctx, binAsset);
            // native 就地写回要求 mutable
            bmp = in.isMutable() ? in : in.copy(Bitmap.Config.ARGB_8888, true);

            float lux = 200f, cct = 5500f, zoom = 1f;
            boolean ok = applyWarmOriginalFilter(bmp, bin, lux, cct, zoom, time,
                    denoise, ev, warmth);
            if (!ok) {
                if (bmp != in) bmp.recycle();
                return null;
            }
            return bmp;
        } catch (Throwable t) {
            if (bmp != null && bmp != in) bmp.recycle();
            return null;
        }
    }

    /**
     * 对一张 JPEG 补一层 M9 暖调 LUT（供 CPU styletrans 降级输出后处理）。
     * 仅 applyWarmOriginalFilter，不跑 styletrans。失败返回 null。
     */
    public static byte[] applyWarmLut(Context ctx, byte[] jpeg, float denoise, float ev, float warmth) {
        ensureModels(ctx);
        if (m9ModelPath == null) return null;
        try {
            Bitmap bmp = decode(jpeg);
            if (bmp == null) return null;

            float lux = 200f, cct = 5500f, zoom = 1f;
            float time = SystemClock.uptimeMillis() % 60000L;
            boolean ok = applyWarmOriginalFilter(bmp, m9ModelPath, lux, cct, zoom, time,
                    denoise, ev, warmth);
            if (!ok) {
                bmp.recycle();
                return null;
            }

            byte[] out = encode(bmp, jpeg);
            bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** M10R：styletrans + m10r 暖调 LUT。失败返回 null（由调用方降级到 LUT+暗角）。 */
    public static byte[] processM10R(Context ctx, byte[] jpeg) {
        ensureModels(ctx);
        if (isNonQualcommSoC()) return null;
        if (m10rModelPath == null || styleModels == null) return null;
        try {
            Bitmap bmp = decode(jpeg);
            if (bmp == null) return null;
            bmp = alignEven(bmp);

            int rotation = readOrientation(jpeg);
            int lux = 200, cct = 5500;
            float zoom = 1f;
            float time = SystemClock.uptimeMillis() % 60000L;

            // styletrans
            boolean ok = applyStyleOriented(bmp, rotation, lux, cct);
            if (!ok) {
                bmp.recycle();
                return null;
            }
            // 暖调 LUT（m10r bin）
            boolean ok2 = applyWarmOriginalFilter(bmp, m10rModelPath, lux, cct, zoom, time, 0.6f, -0.95f, 0.3f);
            if (!ok2) {
                bmp.recycle();
                return null;
            }

            byte[] out = encode(bmp, jpeg);
            bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean applyStyleOriented(Bitmap b, int rotation, int lux, int cct) {
        if (b.getWidth() >= b.getHeight()) {
            return applyStyle(b, styleRunner, cachePath, styleModels, rotation, lux, cct);
        }
        // 竖图：旋转 -90 处理后再转回
        Matrix m = new Matrix();
        m.setRotate(-90f);
        Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
        boolean ok = applyStyle(rotated, styleRunner, cachePath, styleModels, (rotation + 90) % 360, lux, cct);
        if (!ok) {
            rotated.recycle();
            return false;
        }
        Matrix m2 = new Matrix();
        m2.setRotate(90f);
        m2.postTranslate(b.getWidth(), 0);
        Canvas c = new Canvas(b);
        c.drawBitmap(rotated, m2, null);
        rotated.recycle();
        return true;
    }

    // ---- 辅助 ----

    private static Bitmap decode(byte[] jpeg) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = true;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
    }

    /**
     * styletrans 引擎要求输入宽高为偶数（DSP 张量对齐；runner 路径注释同款约束）。
     * 相机原图恒偶数 → 空操作直通；相册裁切/编辑重编码可产出奇数尺寸 →
     * 裁掉最右列/最下一行像素后重画到可变位图（native 就地写回需 mutable）。
     */
    private static Bitmap alignEven(Bitmap bmp) {
        int w = bmp.getWidth() & ~1;
        int h = bmp.getHeight() & ~1;
        if (w == bmp.getWidth() && h == bmp.getHeight()) return bmp;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(bmp, 0f, 0f, null);
        bmp.recycle();
        return out;
    }

    private static byte[] encode(Bitmap bmp, byte[] srcJpeg) {
        return MadridLegendary.copyExif(srcJpeg, com.leicamoments.app.JpegEnc.encode(bmp));
    }

    private static int readOrientation(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new java.io.ByteArrayInputStream(jpeg));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            switch (o) {
                case 3:
                case 4:
                    return 180;
                case 5:
                case 6:
                    return 90;
                case 7:
                case 8:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }
}

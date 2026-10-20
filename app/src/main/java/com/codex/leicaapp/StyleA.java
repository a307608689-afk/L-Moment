package com.codex.leicaapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;

import com.leicamoments.app.DiagLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A 路线部署端：Lab 仿射 + 逐通道节点 LUT。
 *
 * <p>与离线导出脚本的 {@code apply_q()} 逐行等价，
 * 任何一侧改动必须同步另一侧。数学：
 * <pre>
 *   lin  = sRGB->linear(u8)                     // 256 项查表
 *   lin *= grayWorldGain                        // 全图均值，clamp 0.5..2
 *   y    = LUMA·lin                             // 注意 BGR 排布，见 LUMA_* 注释
 *   lin  = lin*scale + off                      // 0.5/99.5 百分位曝光归一，clamp 0.25..4
 *   lab  = sRGB->Lab(lin)                       // D65 + OpenCV 白点
 *   lab' = knotLut(lab @ A + b)                 // 每通道单调节点表，二分插值
 *   out  = Lab->sRGB(lab')                      // 4096 项查表 + 插值
 * </pre>
 *
 * <p>统计量分三遍扫描（均值 -> 直方图 -> 应用），不做中间浮点缓存：
 * 12MP 下三遍约等于一遍的内存带宽，换来 48MB 的峰值节省。参考实测 dE 0.5~1.0。
 */
public final class StyleA {

    private static final String TAG = "LMStyleA";
    private static final byte[] MAGIC = {'L', 'M', 'A', '6'};
    private static final int VER = 2;

    /** sRGB u8 -> 线性光。 */
    private static final double[] LIN = new double[256];
    /** 线性光 -> sRGB，4096 点表 + 线性插值。 */
    private static final double[] L2S = new double[4096];
    private static final int HIST = 65536;

    // 与 common.py 完全一致。注意 LUMA 是 BGR 排布但数值按 R/G/B 权重写的
    // （0.2126 乘 B 通道），这是 PC 台拟合时的口径，不得"修正"，
    // 否则拟合端与应用端的曝光归一不同源。
    private static final double LUMA_B = (double) 0.2126f;
    private static final double LUMA_G = (double) 0.7152f;
    private static final double LUMA_R = (double) 0.0722f;

    private static final double EPS = 1e-6;
    private static final double WB_LO = 0.5, WB_HI = 2.0;
    private static final double EXPO_LO = 0.005, EXPO_HI = 0.98;
    private static final double SCALE_LO = 0.25, SCALE_HI = 4.0;

    // sRGB D65（OpenCV 口径）
    private static final double WX = 0.950456, WZ = 1.088754;
    private static final double T_THRESH = 0.008856;
    private static final double T_INV_THRESH = 0.206893;
    private static final double M00 = 0.412453, M01 = 0.357580, M02 = 0.180423;
    private static final double M10 = 0.212671, M11 = 0.715160, M12 = 0.072169;
    private static final double M20 = 0.019334, M21 = 0.119193, M22 = 0.950227;
    private static final double N00 = 3.240479, N01 = -1.537150, N02 = -0.498535;
    private static final double N10 = -0.969256, N11 = 1.875992, N12 = 0.041556;
    private static final double N20 = 0.055648, N21 = -0.204043, N22 = 1.057311;

    static {
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            LIN[i] = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        for (int i = 0; i < L2S.length; i++) {
            double x = i / (double) (L2S.length - 1);
            L2S[i] = x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
        }
    }

    private final double[] a9 = new double[9];
    private final double[] b3 = new double[3];
    private final double[][] xs;
    private final double[][] tab;
    private final int nodes;

    private StyleA(int nodes) {
        this.nodes = nodes;
        this.xs = new double[3][nodes];
        this.tab = new double[3][nodes];
    }

    // ------------------------------------------------------------------ loading

    private static final Map<String, StyleA> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> ATTEMPTED = new ConcurrentHashMap<>();

    /** 按 assets 名（或 {@code file:} 前缀走 filesDir）加载参数，失败返回 null 且本会话不再重试。 */
    public static synchronized StyleA ensureLoaded(Context ctx, String ref) {
        StyleA c = CACHE.get(ref);
        if (c != null) return c;
        if (Boolean.TRUE.equals(ATTEMPTED.get(ref))) return null;
        ATTEMPTED.put(ref, Boolean.TRUE);
        try {
            byte[] bytes = readAll(ctx, ref);
            if (bytes == null) return null;
            StyleA p = decode(bytes);
            if (p == null) return null;
            CACHE.put(ref, p);
            DiagLog.i(TAG, "loaded " + ref + " nodes=" + p.nodes + " " + bytes.length + "B");
            return p;
        } catch (Throwable t) {
            DiagLog.e(TAG, "load failed " + ref, t);
            return null;
        }
    }

    /** 参数文件被删除/覆盖时清缓存，下次调用会重新读盘。 */
    public static synchronized void invalidate(String ref) {
        if (ref == null) return;
        CACHE.remove(ref);
        ATTEMPTED.remove(ref);
    }

    /** 已缓存的参数条数（诊断用）。 */
    public static int cacheSize() {
        return CACHE.size();
    }

    /**
     * 注册内存参数（增强预览用）：不落盘，ref 任意字符串（如 {@code "mem:enhance_par"}）。
     * 同一 ref 反复覆盖即可实现强度滑杆实时预览；用完调 {@link #invalidate(String)} 释放。
     */
    public static synchronized boolean putBytes(String ref, byte[] bytes) {
        if (ref == null || bytes == null) return false;
        StyleA p = decode(bytes);
        if (p == null) return false;
        CACHE.put(ref, p);
        ATTEMPTED.remove(ref);
        return true;
    }

    /** 对 Bitmap 应用参数（预览用，走 ARGB 像素原地改写，不做 JPEG 往返）。 */
    public static Bitmap applyToBitmap(Context ctx, Bitmap src, String ref) {
        if (src == null) return null;
        StyleA p = ensureLoaded(ctx, ref);
        if (p == null) return null;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        int w = out.getWidth(), h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        p.applyPx(px, w, h);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static byte[] readAll(Context ctx, String ref) throws Exception {
        InputStream in;
        if (ref != null && ref.startsWith(AlexaLut.FILE_PREFIX)) {
            File f = new File(AlexaLut.customDir(ctx), ref.substring(AlexaLut.FILE_PREFIX.length()));
            if (!f.isFile()) return null;
            in = new FileInputStream(f);
        } else {
            in = ctx.getAssets().open(ref);
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 12);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static StyleA decode(byte[] s) {
        if (s == null || s.length < 12) return null;
        for (int i = 0; i < 4; i++) if (s[i] != MAGIC[i]) return null;
        ByteBuffer bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN);
        int ver = bb.getInt(4);
        int nodes = bb.getInt(8);
        if (ver != VER || nodes < 2 || nodes > 4096) return null;
        int need = 12 + 36 + 12 + 12 * nodes * 2;
        if (s.length != need) {
            DiagLog.w(TAG, "bad length " + s.length + " != " + need);
            return null;
        }
        StyleA p = new StyleA(nodes);
        int o = 12;
        // 二进制里 A 是行主序 A[k][c]（PC 端 v = lab @ A，即 v_c = sum_k lab_k*A[k][c]）。
        // 这里转置存放，让 pass3 的 a9[c*3+k]*lab[k] 与 PC 同源；A 非对称，不能直接平铺。
        for (int k = 0; k < 3; k++) {
            for (int c = 0; c < 3; c++) p.a9[c * 3 + k] = bb.getFloat(o + (k * 3 + c) * 4);
        }
        o += 36;
        for (int i = 0; i < 3; i++) p.b3[i] = bb.getFloat(o + i * 4);
        o += 12;
        for (int c = 0; c < 3; c++) {
            for (int k = 0; k < nodes; k++) p.xs[c][k] = bb.getFloat(o + (c * nodes + k) * 4);
        }
        o += 12 * nodes;
        for (int c = 0; c < 3; c++) {
            for (int k = 0; k < nodes; k++) p.tab[c][k] = bb.getFloat(o + (c * nodes + k) * 4);
        }
        return p;
    }

    // ------------------------------------------------------------------ pipeline

    /** 就地改写 ARGB 像素。调用方负责 Bitmap 的重编码。 */
    public void applyPx(int[] px, int w, int h) {
        final int n = w * h;

        // pass 1: 通道均值（gray world）
        double sb = 0, sg = 0, sr = 0;
        for (int i = 0; i < n; i++) {
            int p = px[i];
            sb += LIN[p & 0xFF];
            sg += LIN[(p >>> 8) & 0xFF];
            sr += LIN[(p >>> 16) & 0xFF];
        }
        double mb = sb / n, mg = sg / n, mr = sr / n;
        double m = (mb + mg + mr) / 3.0;
        final double gb = clamp(m / (mb + EPS), WB_LO, WB_HI);
        final double gg = clamp(m / (mg + EPS), WB_LO, WB_HI);
        final double gr = clamp(m / (mr + EPS), WB_LO, WB_HI);

        // pass 2: 亮度直方图（百分位曝光）
        int[] hist = new int[HIST];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            double y = LUMA_B * LIN[p & 0xFF] * gb
                    + LUMA_G * LIN[(p >>> 8) & 0xFF] * gg
                    + LUMA_R * LIN[(p >>> 16) & 0xFF] * gr;
            int k = (int) (y * HIST);
            if (k < 0) k = 0;
            if (k >= HIST) k = HIST - 1;
            hist[k]++;
        }
        double pLo = percentile(hist, 0.005, n);
        double pHi = percentile(hist, 0.995, n);
        double span = Math.max(pHi - pLo, 1e-4);
        final double scale = clamp((EXPO_HI - EXPO_LO) / span, SCALE_LO, SCALE_HI);
        final double off = EXPO_LO - pLo * scale;

        // pass 3: 应用（全程复用 3 元暂存数组，逐像素 new 会压垮 GC）
        double[] lab = new double[3];
        double[] lin = new double[3];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            double bl = Math.min(1.0, Math.max(0.0, LIN[p & 0xFF] * gb * scale + off));
            double gl = Math.min(1.0, Math.max(0.0, LIN[(p >>> 8) & 0xFF] * gg * scale + off));
            double rl = Math.min(1.0, Math.max(0.0, LIN[(p >>> 16) & 0xFF] * gr * scale + off));

            rgb2lab(rl, gl, bl, lab);
            double v0 = a9[0] * lab[0] + a9[1] * lab[1] + a9[2] * lab[2] + b3[0];
            double v1 = a9[3] * lab[0] + a9[4] * lab[1] + a9[5] * lab[2] + b3[1];
            double v2 = a9[6] * lab[0] + a9[7] * lab[1] + a9[8] * lab[2] + b3[2];

            lab[0] = knot(0, v0);
            lab[1] = knot(1, v1);
            lab[2] = knot(2, v2);

            lab2lin(lab, lin);
            // lab2lin 输出 [R,G,B]；ARGB 整数里 R 在 bit16，B 在 bit0。
            int o = 0xFF000000;
            o |= ((int) Math.round(l2s(lin[0]) * 255.0)) << 16;
            o |= ((int) Math.round(l2s(lin[1]) * 255.0)) << 8;
            o |= (int) Math.round(l2s(lin[2]) * 255.0);
            px[i] = o;
        }
    }

    private static double percentile(int[] hist, double p, long total) {
        double want = p * (total - 1) + 0.5;
        long c = 0;
        for (int i = 0; i < hist.length; i++) {
            c += hist[i];
            if (c >= want) return i / (double) HIST;
        }
        return (HIST - 1) / (double) HIST;
    }

    private double knot(int c, double v) {
        double[] x = xs[c], t = tab[c];
        int n = nodes;
        if (v <= x[0]) return t[0];
        if (v >= x[n - 1]) return t[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (x[mid] <= v) lo = mid; else hi = mid;
        }
        double dx = x[hi] - x[lo];
        double f = dx > 1e-9 ? (v - x[lo]) / dx : 0.0;
        return t[lo] * (1.0 - f) + t[hi] * f;
    }

    private static void rgb2lab(double r, double g, double b, double[] out) {
        double x = (M00 * r + M01 * g + M02 * b) / WX;
        double y = M10 * r + M11 * g + M12 * b;
        double z = (M20 * r + M21 * g + M22 * b) / WZ;
        double fx = x > T_THRESH ? Math.cbrt(x) : 7.787 * x + 16.0 / 116.0;
        double fy = y > T_THRESH ? Math.cbrt(y) : 7.787 * y + 16.0 / 116.0;
        double fz = z > T_THRESH ? Math.cbrt(z) : 7.787 * z + 16.0 / 116.0;
        out[0] = 116.0 * fy - 16.0;
        out[1] = 500.0 * (fx - fy);
        out[2] = 200.0 * (fy - fz);
    }

    private static void lab2lin(double[] lab, double[] out) {
        double fy = (lab[0] + 16.0) / 116.0;
        double fx = fy + lab[1] / 500.0;
        double fz = fy - lab[2] / 200.0;
        double x = (fx > T_INV_THRESH ? fx * fx * fx : (fx - 16.0 / 116.0) / 7.787) * WX;
        double y = fy > T_INV_THRESH ? fy * fy * fy : (fy - 16.0 / 116.0) / 7.787;
        double z = (fz > T_INV_THRESH ? fz * fz * fz : (fz - 16.0 / 116.0) / 7.787) * WZ;
        out[0] = N00 * x + N01 * y + N02 * z;
        out[1] = N10 * x + N11 * y + N12 * z;
        out[2] = N20 * x + N21 * y + N22 * z;
    }

    private static double l2s(double v) {
        if (v <= 0.0) return 0.0;
        if (v >= 1.0) return 1.0;
        double pos = v * (L2S.length - 1);
        int i = (int) pos;
        if (i >= L2S.length - 1) i = L2S.length - 2;
        double f = pos - i;
        return L2S[i] * (1.0 - f) + L2S[i + 1] * f;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ entry

    /** 对 JPEG 应用 A 参数，返回处理后 JPEG。参数缺失/处理失败返回原字节。 */
    public static byte[] process(Context ctx, byte[] jpeg, String ref) {
        StyleA p = ensureLoaded(ctx, ref);
        if (p == null) {
            DiagLog.w(TAG, "params unavailable ref=" + ref + "（降级为原图）");
            return jpeg;
        }
        long t0 = System.currentTimeMillis();
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return jpeg;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            p.applyPx(px, w, h);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length);
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos);
            byte[] ret = MadridLegendary.copyExif(jpeg, bos.toByteArray());
            DiagLog.i(TAG, "ok ref=" + ref + " " + w + "x" + h + " "
                    + (System.currentTimeMillis() - t0) + "ms out=" + ret.length + "B");
            return ret;
        } catch (Throwable t) {
            DiagLog.e(TAG, "process failed ref=" + ref + "（降级为原图）", t);
            return jpeg;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }
}

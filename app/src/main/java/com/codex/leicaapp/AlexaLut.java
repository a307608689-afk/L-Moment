package com.codex.leicaapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * 3D LUT 通用加载器（三线性插值）。
 *
 * <p>内置 LUT（assets，均离线烘焙、逐像素点算子）：
 *  - lut_alexa.bin      : 「ARRI 2049」浓青橙电影（离线烘焙）
 *  - lut_arri709.bin    : ARRI 709 基础影视直出（离线烘焙，33³）
 *  - lut_arrireveal.bin : Reveal 通透还原低反差（同上，33³）
 *  - lut_arridune.bin   : Dune 沙漠哑光（同上，65³）
 *  - lut_arrilala.bin   : LaLaLand 黄昏绚丽（同上，65³）
 *  - lut_arrijoker.bin  : Joker 绿调阴郁（同上，33³）
 *  - lut_p9vibrant.bin  : L-P9「P9 鲜艳」色彩还原（测量式拟合）</p>
 *
 * <p>自定义 LUT（运行时由 Kotlin 侧烘焙，落在 filesDir/luts/）：以 {@link #FILE_PREFIX}
 * 开头的引用读取，如 {@code "file:lut_u1729_v3.bin"}。</p>
 *
 * <p>网格边长不再写死：由文件字节长度反推（len = 3·dim³·4）。内置实例 33³ 与 65³ 混用，
 * 自定义用 33³（431KB，6 个版本才 2.6MB），同一进程内可混用。</p>
 *
 * <p>纯 Java 三线性插值（与烘焙脚本 java_trilinear 严格对齐），无 CNN / 无融合；
 * 加载或处理失败一律返回原字节。调参只改烘焙脚本常数后重烘重装。</p>
 */
public final class AlexaLut {

    /** assets 内置：M4 浓青橙电影。 */
    public static final String LUT_ALEXA = "lut_alexa.bin";
    /** assets 内置：L-P9 鲜艳。 */
    public static final String LUT_P9 = "lut_p9vibrant.bin";
    /** assets 内置：ARRI 709 基础影视直出（33³）。 */
    public static final String LUT_ARRI709 = "lut_arri709.bin";
    /** assets 内置：Reveal 通透还原（33³）。 */
    public static final String LUT_ARRIREVEAL = "lut_arrireveal.bin";
    /** assets 内置：Dune 沙漠哑光（65³）。 */
    public static final String LUT_ARRI_DUNE = "lut_arridune.bin";
    /** assets 内置：LaLaLand 黄昏绚丽（65³）。 */
    public static final String LUT_ARRI_LALA = "lut_arrilala.bin";
    /** assets 内置：Joker 绿调阴郁（33³）。 */
    public static final String LUT_ARRI_JOKER = "lut_arrijoker.bin";

    /** 自定义 LUT 引用前缀：{@code file:<name>} → filesDir/luts/<name>。 */
    public static final String FILE_PREFIX = "file:";
    /** 自定义 LUT 子目录（相对 filesDir）。 */
    public static final String CUSTOM_DIR = "luts";

    /** 一套已加载的 LUT：数据 + 网格边长 + 派生常量。 */
    private static final class Lut {
        final float[] data;
        final int dim;
        final int dim2;        // dim * dim
        final int shift;       // dim³
        final float binsize;   // 与烘焙脚本 binsize 口径一致

        Lut(float[] data, int dim) {
            this.data = data;
            this.dim = dim;
            this.dim2 = dim * dim;
            this.shift = dim * dim * dim;
            this.binsize = 1.0001f / (dim - 1);
        }
    }

    private static final HashMap<String, Lut> LUTS = new HashMap<>();
    private static final HashMap<String, Boolean> ATTEMPTED = new HashMap<>();

    private AlexaLut() {
    }

    /** 构造自定义 LUT 引用（filesDir/luts/<fileName>）。 */
    public static String fileRef(String fileName) {
        return FILE_PREFIX + fileName;
    }

    /** 自定义 LUT 目录（不存在时创建；创建失败由调用方按"文件缺失"处理）。 */
    public static File customDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), CUSTOM_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /**
     * 丢弃某个 LUT 的缓存。自定义 LUT 文件被覆盖或删除后必须调用，
     * 否则静态缓存会一直被复用、回退/增强看不到变化。
     */
    public static synchronized void invalidate(String ref) {
        if (ref == null) return;
        LUTS.remove(ref);
        ATTEMPTED.remove(ref);
    }

    /**
     * 注册内存 LUT（增强预览用）：不落盘，ref 任意字符串（如 {@code "mem:enhance_preview"}）。
     * 同一 ref 反复覆盖即可实现滑杆实时预览；用完调 {@link #invalidate(String)} 释放。
     */
    public static synchronized boolean putBytes(String ref, byte[] bytes) {
        if (ref == null || bytes == null) return false;
        Lut lut = decode(bytes);
        if (lut == null) return false;
        LUTS.put(ref, lut);
        ATTEMPTED.put(ref, Boolean.TRUE);
        return true;
    }

    /**
     * 对 Bitmap 应用 LUT（预览用，不做 JPEG 往返）。成功返回新 Bitmap，失败返回 null；
     * 入参 src 不被修改也不被回收。
     */
    public static Bitmap applyToBitmap(Context ctx, Bitmap src, String ref) {
        if (src == null) return null;
        Lut lut = ensureLoaded(ctx, ref);
        if (lut == null) return null;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        try {
            applyLut(out, lut);
            return out;
        } catch (Throwable t) {
            out.recycle();
            return null;
        }
    }

    private static synchronized Lut ensureLoaded(Context ctx, String ref) {
        Lut cached = LUTS.get(ref);
        if (cached != null) return cached;
        if (Boolean.TRUE.equals(ATTEMPTED.get(ref))) return null;
        ATTEMPTED.put(ref, Boolean.TRUE);   // 每资产只尝试一次，失败后本会话内直接走原图降级
        try {
            byte[] bytes = readAll(ctx, ref);
            if (bytes == null) return null;
            Lut lut = decode(bytes);
            if (lut == null) return null;
            LUTS.put(ref, lut);
            return lut;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按引用读取原始字节：{@code file:} 前缀走 filesDir/luts/，否则走 assets。 */
    private static byte[] readAll(Context ctx, String ref) throws Exception {
        InputStream in;
        if (ref != null && ref.startsWith(FILE_PREFIX)) {
            File f = new File(customDir(ctx), ref.substring(FILE_PREFIX.length()));
            if (!f.isFile()) return null;
            in = new FileInputStream(f);
        } else {
            in = ctx.getAssets().open(ref);
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 解析 LUT 字节并按长度反推网格边长（len = 3·dim³·4）。非法长度返回 null。 */
    private static Lut decode(byte[] bytes) {
        if (bytes == null || bytes.length < 24 || bytes.length % 12 != 0) return null;
        long points = bytes.length / 12L;                  // 每点 3 通道 × 4 字节
        int dim = (int) Math.round(Math.cbrt((double) points));
        if (dim < 2 || dim > 256) return null;
        if ((long) dim * dim * dim != points) return null;
        float[] floats = new float[(int) points * 3];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats);
        return new Lut(floats, dim);
    }

    /** 对 JPEG 应用 M4 青橙电影 LUT（lut_alexa.bin），返回处理后 JPEG。失败返回原字节。 */
    public static byte[] process(Context ctx, byte[] jpeg) {
        return process(ctx, jpeg, LUT_ALEXA, null);
    }

    /** 对 JPEG 应用指定 LUT 引用，返回处理后 JPEG。失败返回原字节。 */
    public static byte[] process(Context ctx, byte[] jpeg, String ref) {
        return process(ctx, jpeg, ref, null);
    }

    /** LUT 前后像素级钩子（L-M9 局部算子/场景条件注入用；两钩子均可为 null）。 */
    public interface Hooks {
        void pre(int[] px, int w, int h);
        void post(int[] px, int w, int h);
    }

    /** 对 JPEG 应用指定 LUT 引用 + 前后像素钩子，返回处理后 JPEG。失败返回原字节。 */
    public static byte[] process(Context ctx, byte[] jpeg, String ref, Hooks hooks) {
        Lut lut = ensureLoaded(ctx, ref);
        if (lut == null) {
            android.util.Log.w("LMAlexaLut", "load failed ref=" + ref + "（降级为原图）");
            return jpeg;
        }
        long t0 = System.currentTimeMillis();
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return jpeg;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            int mid = (h / 2) * w + (w / 2);
            int probeIn = px[mid];
            if (hooks != null) hooks.pre(px, w, h);
            applyLutPx(px, lut);
            if (hooks != null) hooks.post(px, w, h);
            int probeOut = px[mid];
            {
                int d = lut.dim, d2 = lut.dim2, sh = lut.shift;
                int c = (d / 2) * d2 + (d / 2) * d + (d / 2);
                android.util.Log.i("LMAlexaLut", "probe px " + Integer.toHexString(probeIn)
                        + " -> " + Integer.toHexString(probeOut) + " | lutMid R=" + lut.data[c]
                        + " G=" + lut.data[sh + c] + " B=" + lut.data[2 * sh + c]);
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length);
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos);
            bmp.recycle();
            byte[] ret = MadridLegendary.copyExif(jpeg, bos.toByteArray());
            android.util.Log.i("LMAlexaLut", "ok ref=" + ref + " dim=" + lut.dim + " " + w + "x" + h
                    + " " + (System.currentTimeMillis() - t0) + "ms out=" + ret.length + "B");
            return ret;
        } catch (Throwable t) {
            android.util.Log.w("LMAlexaLut", "process failed ref=" + ref + "（降级为原图）", t);
            return jpeg;
        }
    }

    private static void applyLut(Bitmap bmp, Lut lut) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        applyLutPx(px, lut);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void applyLutPx(int[] px, Lut lut) {
        final float[] data = lut.data;
        final int dim = lut.dim;
        final int dim2 = lut.dim2;
        final int last = dim - 2;
        final float binsize = lut.binsize;
        final int rPlane = 0, gPlane = lut.shift, bPlane = lut.shift * 2;

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;

            int ri = (int) Math.floor(r / binsize);
            int gi = (int) Math.floor(g / binsize);
            int bi = (int) Math.floor(b / binsize);
            if (ri > last) ri = last;
            if (gi > last) gi = last;
            if (bi > last) bi = last;

            float rd = (r % binsize) / binsize;
            float gd = (g % binsize) / binsize;
            float bd = (b % binsize) / binsize;
            if (rd < 0f) rd = 0f; else if (rd > 1f) rd = 1f;
            if (gd < 0f) gd = 0f; else if (gd > 1f) gd = 1f;
            if (bd < 0f) bd = 0f; else if (bd > 1f) bd = 1f;

            float w000 = (1 - rd) * (1 - gd) * (1 - bd);
            float w100 = rd * (1 - gd) * (1 - bd);
            float w010 = (1 - rd) * gd * (1 - bd);
            float w110 = rd * gd * (1 - bd);
            float w001 = (1 - rd) * (1 - gd) * bd;
            float w101 = rd * (1 - gd) * bd;
            float w011 = (1 - rd) * gd * bd;
            float w111 = rd * gd * bd;

            int b00 = bi * dim2 + gi * dim + ri;

            int idx = rPlane + b00;
            float or = w000 * data[idx] + w100 * data[idx + 1]
                    + w010 * data[idx + dim] + w110 * data[idx + dim + 1]
                    + w001 * data[idx + dim2] + w101 * data[idx + dim2 + 1]
                    + w011 * data[idx + dim2 + dim] + w111 * data[idx + dim2 + dim + 1];

            idx = gPlane + b00;
            float og = w000 * data[idx] + w100 * data[idx + 1]
                    + w010 * data[idx + dim] + w110 * data[idx + dim + 1]
                    + w001 * data[idx + dim2] + w101 * data[idx + dim2 + 1]
                    + w011 * data[idx + dim2 + dim] + w111 * data[idx + dim2 + dim + 1];

            idx = bPlane + b00;
            float ob = w000 * data[idx] + w100 * data[idx + 1]
                    + w010 * data[idx + dim] + w110 * data[idx + dim + 1]
                    + w001 * data[idx + dim2] + w101 * data[idx + dim2 + 1]
                    + w011 * data[idx + dim2 + dim] + w111 * data[idx + dim2 + dim + 1];

            int ir = clamp255(or), ig = clamp255(og), ib = clamp255(ob);
            px[i] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
        }
    }

    private static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }
}

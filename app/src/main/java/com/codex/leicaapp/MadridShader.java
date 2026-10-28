package com.codex.leicaapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

/**
 * 「马德里」风格的暗角与颗粒着色器（纯 Java 实现）。
 *
 * <p>对 JPEG 施加：中心感知亮度 LUT + 径向暗角（smoothstep）+ 亮度调制的伪随机颗粒。</p>
 */
public final class MadridShader {

    private static final float[] NOISE = new float[65536];
    private static final float[] P_LUT = new float[256];
    private static boolean inited = false;

    private MadridShader() {
    }

    /** 对 Bitmap 施加暗角 + 颗粒。density 通常为 0.3。 */
    public static void apply(Bitmap bmp, float density) {
        apply(bmp, density, 1f);
    }

    /**
     * @param density          颗粒密度（0=无颗粒）
     * @param vignetteStrength 暗角强度系数（1=原有全强度，0.3≈极轻，0=无暗角）
     */
    public static void apply(Bitmap bmp, float density, float vignetteStrength) {
        apply(bmp, density, vignetteStrength, bmp.getHeight(), 0);
    }

    /**
     * 条带版：暗角与颗粒都依赖**全图绝对坐标**，按带调用时必须传整幅高度与带偏移，
     * 否则每条带各自从中心重算暗角（出现横向亮度涟漪）、颗粒图案也会在带边界错位。
     *
     * <p>bandPx 由编码器决定（200MP=320、50MP=384、12.58MP=512），只有 512 能整除
     * 颗粒周期 256，故颗粒索引用 {@code (yOffset+y) & 0xFF} 而非带内 y。</p>
     *
     * @param fullH   整幅高度（像素）
     * @param yOffset 本条带顶部在整幅中的行号
     */
    public static void apply(Bitmap bmp, float density, float vignetteStrength,
                             int fullH, int yOffset) {
        init();
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (fullH <= 0) fullH = h;
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            int gy = yOffset + y;
            float ny = (float) gy / (float) fullH;
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int p = pixels[idx];
                float r = (float) ((p >> 16) & 0xFF) / 255f;
                float g = (float) ((p >> 8) & 0xFF) / 255f;
                float b = (float) (p & 0xFF) / 255f;

                float dx = ((float) x / (float) w - 0.5f) * 0.75f + 0.5f - 0.5f;
                float dy = ny - 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                float lum = P_LUT[clamp255(0.2125f * r + 0.7154f * g + 0.0721f * b)];
                // 语义：中心 m=1（不动），边缘 m→lum（压暗）。强度必须缩放「压暗量」
                // （1 - strength*(1-v)），若直接乘 smoothstep 会破坏中心不变量、把亮部压暗。
                float vraw = smoothstep(1.4f, 0.2f, dist);
                float vignette = 1f - vignetteStrength * (1f - vraw);
                float m = (1f - lum) * vignette + lum;

                float rr = r * m;
                float gg = g * m;
                float bb = b * m;

                float grainAmt = (1f - (0.299f * rr + 0.114f * gg + 0.587f * bb)) * density;
                if (grainAmt < 0f) grainAmt = 0f;
                if (grainAmt > 1f) grainAmt = 1f;

                if (grainAmt > 0f) {
                    float n = NOISE[(gy & 0xFF) << 8 | (x & 0xFF)] * 2f;
                    rr = rr + (rr * n - rr) * grainAmt;
                    gg = gg + (gg * n - gg) * grainAmt;
                    bb = bb + (n * bb - bb) * grainAmt;
                }

                int ir = clamp255(rr);
                int ig = clamp255(gg);
                int ib = clamp255(bb);
                pixels[idx] = ib | (ir << 16) | 0xFF000000 | (ig << 8);
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    /** 解码 JPEG → apply → 重新编码（quality 96）。失败返回原字节。 */
    public static byte[] applyToJpeg(byte[] jpeg, float density) {
        return applyToJpeg(jpeg, density, 1f);
    }

    /**
     * 解码 JPEG → apply（含暗角强度）→ 重新编码（4:4:4，质量=JpegEnc 默认档）。失败返回原字节。
     */
    public static byte[] applyToJpeg(byte[] jpeg, float density, float vignetteStrength) {
        return applyToJpeg(jpeg, density, vignetteStrength,
                com.leicamoments.app.JpegEnc.DEFAULT_QUALITY);
    }

    /**
     * 质量显式版。M10R 主链（含各级降级）收尾钉 95（JpegEnc.M9_FAMILY_QUALITY），
     * 不随全局默认变化。
     */
    public static byte[] applyToJpeg(byte[] jpeg, float density, float vignetteStrength, int quality) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return jpeg;
            apply(bmp, density, vignetteStrength);
            byte[] encoded = com.leicamoments.app.JpegEnc.encode(bmp, quality);
            bmp.recycle();
            // 回填源 JPEG 的 APP1(Exif) 段：机型/拍摄参数/GPS 位置随链路整体保留。
            // 链路上游（MadridLegendary/LegendaryPostProcessor/AlexaLut/PortraitFilm）均已 copyExif 传递，
            // 此前本方法重编码后整段丢失，导致 M4/M10R/M13/LP9 及各降级路径导出无 EXIF。
            return MadridLegendary.copyExif(jpeg, encoded);
        } catch (Throwable t) {
            return jpeg;
        }
    }

    private static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }

    private static synchronized void init() {
        if (inited) return;
        for (int i = 0; i < 256; i++) {
            double x = (double) i / 255.0;
            P_LUT[i] = (float) (1.0 / Math.pow(100.0, 1.0 - x));
        }
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                float fx = (float) b / 255f;
                float fy = (float) a / 255f;
                float sum = 0f;
                float[] offs = new float[]{0.07f, 0.11f, 0.13f, 0.17f, 0.19f, 0.23f};
                for (float o : offs) {
                    sum += nrand(fx + o, o + fy);
                }
                NOISE[a << 8 | b] = sum / 6f;
            }
        }
        inited = true;
    }

    private static float nrand(float a, float b) {
        double v = Math.sin((double) a * 12.9898 + (double) b * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - t * 2f);
    }
}

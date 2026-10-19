package com.codex.leicaapp;

/**
 * L-M9 条件化解析引擎（T1）——挂在 AlexaLut LUT 前后的像素级算子。
 *
 * <p>管线：场景估计 → 色偏注入(pre, LUT 前) → 65³ LUT → 局部对比算子(post)。
 * 参数与离线拟合脚本逐字同步（分桶阈值、局部算子的网格搜索解）。</p>
 *
 * <p>设计语义（固定日光白平衡）：
 * 真机 M9 不做场景白平衡自适应——阴天片源天然发蓝、夕阳天然金调。
 * 手机直出 JPEG 已被各家自动白平衡「洗掉」色偏，故这里按场景把色偏按约 25% 回注，
 * LUT 再统一塑形。阴影档注入冷偏、夕阳档注入暖偏、日光/暗光档不注入。</p>
 *
 * <p>调参流程：改 PC 台常数重烘重装，或直接改本文件顶部 LM9_* 常数（免重烘）。</p>
 */
public final class Lm9Post implements AlexaLut.Hooks {

    private static final Lm9Post INSTANCE = new Lm9Post();

    public static Lm9Post get() {
        return INSTANCE;
    }

    private Lm9Post() {
    }

    // ---- 场景分桶阈值（02_buckets.py 同款口径，作用于 1/16 抽样）----
    private static final float DARK_MEDV = 0.34f;
    private static final float WARM_RB = 12f;        // R-B > 12 判暖
    private static final float STRONG_WARM_RB = 22f; // R-B >= 22 判夕阳
    private static final float COOL_BR = 6f;         // B-R > 6 判冷

    // ---- 色偏回注强度（gamma 域通道增益；0=关闭）----
    // v5：仅在"中性场"（meanS<0.22，阴天/灰调场景）注入——蓝天占画面的晴天场景
    // 均值 B>R 会误判 shade，注入会把天空推成电蓝（真机翻车证据），必须门控。
    static final float SHADE_CAST = 0.045f;   // 阴影/阴天：r*=(1-c), b*=(1+c)
    static final float SUNSET_CAST = 0.042f;  // 夕阳：r*=(1+c), b*=(1-c)
    static final float CAST_MAX_MEAN_S = 0.22f; // 注入门控：场景平均饱和度上限

    // ---- 局部对比算子（08_localfit.py 最优解 + v5 调整）----
    static final float CRUSH = 0.44f;   // 低频阴影压暗深度
    static final float MU = 0.18f;      // 压暗中心（luma）
    static final float SIG = 0.10f;     // 压暗宽度
    static final float HL = 0.02f;      // 高光抬升（v^6；v8: 0.04→0.02 防顶端推越 255）
    static final float DETAIL = 0.55f;  // 细节增益（v15: 0.75→0.55，全局降档治天空絮纹放大）
    private static final int DS = 8;    // base 低通抽样步长（低频量足够）

    // ---- 色度中频衰减（25_v5.py 同构；治 NR 水彩斑块被影调/饱和放大）----
    private static final int CH_DS = 2;      // 色度低频网格步长
    private static final int CH_R = 9;       // 网格盒滤波半径（≈36px 全分辨率）
    private static final float CH_KEEP = 0.3f; // 色度细节保留比例（v5: 0.5→0.3）
    static final float SKY_BAND_KEEP = 0.35f;  // 天空色度中频保留比例（P9Post 共用）

    // ---- v15 拆补丁定版（PC 台 64b_v15.py：dist 18.02→8.44）----
    // 教训：掩码系补丁（天空detail衰减/skyLowMix）各层副作用互相放大（黄蓝横纹/建筑雾）。
    // 最终：引导base（halo根除）+ 全局detail降档 + LUT输入dither（治banding），无任何掩码。
    static final float GF_EPS = 0.008f;          // 引导滤波 eps（边缘保持强度）

    @Override
    public void pre(int[] px, int w, int h) {
        chromaDenoise(px, w, h);
        // 场景估计：1/16 网格抽样统计（中央 92%，避水印条）
        int n = 0;
        long sumR = 0, sumG = 0, sumB = 0;
        double sumS = 0;
        int samples = ((w / 16) + 1) * ((h / 16) + 1);
        float[] vs = new float[samples];
        int yTop = (int) (h * 0.04f), yBot = (int) (h * 0.96f);
        int xL = (int) (w * 0.02f), xR = (int) (w * 0.98f);
        for (int y = yTop; y < yBot; y += 16) {
            for (int x = xL; x < xR; x += 16) {
                int p = px[y * w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                sumR += r;
                sumG += g;
                sumB += b;
                int mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                int mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
                vs[n++] = mx / 255f;
                sumS += mx > 0 ? (mx - mn) / (float) mx : 0;
            }
        }
        if (n == 0) return;
        float meanR = sumR / (float) n, meanB = sumB / (float) n;
        float meanS = (float) (sumS / n);
        java.util.Arrays.sort(vs, 0, n);
        float medV = vs[n / 2];
        float rb = meanR - meanB, br = meanB - meanR;

        float rGain = 1f, bGain = 1f;
        if (medV < DARK_MEDV) {
            // 暗光/室内：不注入（M9 直出该场景即为暗调）
        } else if (meanS >= CAST_MAX_MEAN_S) {
            // 高饱和场（如大蓝天）：不注入——色偏已被场景内容承载，
            // 再注入会把天空推成电蓝（v5 门控）
        } else if (rb >= STRONG_WARM_RB) {
            rGain = 1f + SUNSET_CAST;
            bGain = 1f - SUNSET_CAST;
        } else if (br > COOL_BR) {
            rGain = 1f - SHADE_CAST;
            bGain = 1f + SHADE_CAST;
        }
        if (rGain == 1f && bGain == 1f) return;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            r = (int) (r * rGain);
            b = (int) (b * bGain);
            if (r > 255) r = 255;
            if (b > 255) b = 255;
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    @Override
    public void post(int[] px, int w, int h) {
        // 低频 base：DS 步长抽样 → 自引导滤波（边缘保持）→ 双线性上采样逐像素取用
        int sw = (w + DS - 1) / DS, sh = (h + DS - 1) / DS;
        float[] small = new float[sw * sh];
        int[] cnt = new int[sw * sh];
        for (int y = 0; y < h; y++) {
            int sy = y / DS;
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                int si = sy * sw + x / DS;
                small[si] += mx;
                cnt[si]++;
            }
        }
        for (int i = 0; i < small.length; i++) {
            small[i] = cnt[i] > 0 ? small[i] / cnt[i] / 255f : 0f;
        }
        // v13：网格级自引导滤波替代双程盒滤波（边缘保持，治建筑-天空交界 halo）
        int rFull = (int) (Math.min(w, h) * 0.06f);
        int rS = Math.max(1, rFull / DS);
        float[] mI = boxBlur(small, sw, sh, rS);
        float[] corrII = new float[small.length];
        for (int i = 0; i < small.length; i++) corrII[i] = small[i] * small[i];
        corrII = boxBlur(corrII, sw, sh, rS);
        float[] aa = new float[small.length], bb = new float[small.length];
        for (int i = 0; i < small.length; i++) {
            float varI = corrII[i] - mI[i] * mI[i];
            float a = varI / (varI + GF_EPS);
            aa[i] = a;
            bb[i] = mI[i] - a * mI[i];
        }
        aa = boxBlur(aa, sw, sh, rS);
        bb = boxBlur(bb, sw, sh, rS);
        for (int i = 0; i < small.length; i++) {
            small[i] = aa[i] * small[i] + bb[i];
        }

        float invDs = 1f / DS;
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * invDs - 0.5f;
            int y0 = floorInt(fy, sh - 1);
            int y1 = Math.min(y0 + 1, sh - 1);
            float wy = fy - y0;
            if (wy < 0) wy = 0;
            int row0 = y0 * sw, row1 = y1 * sw;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * invDs - 0.5f;
                int x0 = floorInt(fx, sw - 1);
                int x1 = Math.min(x0 + 1, sw - 1);
                float wx = fx - x0;
                if (wx < 0) wx = 0;

                float b00 = small[row0 + x0], b10 = small[row0 + x1];
                float b01 = small[row1 + x0], b11 = small[row1 + x1];
                float base = (1 - wx) * (1 - wy) * b00 + wx * (1 - wy) * b10
                        + (1 - wx) * wy * b01 + wx * wy * b11;

                int p = px[y * w + x];
                int ir = (p >> 16) & 0xFF, ig = (p >> 8) & 0xFF, ib = p & 0xFF;
                int mx = ir > ig ? (ir > ib ? ir : ib) : (ig > ib ? ig : ib);
                float L = mx / 255f;
                float detail = L - base;
                float d2 = base - MU;
                float crush = CRUSH * base * (float) Math.exp(-0.5f * (d2 * d2) / (SIG * SIG));
                float c = base - crush + HL * base * base * base * base * base * base;
                if (c < 0) c = 0;
                if (c > 1) c = 1;
                float L2 = c + detail * DETAIL;
                if (L2 < 0) L2 = 0;
                if (L2 > 1) L2 = 1;
                if (L > 0.004f) {
                    float ratio = L2 / L;
                    // v9 高光安全钳 + 天空亮度帽（v14: 240→248，反光高光层次保留，软膝 LUT 自控顶端）
                    float capv = 250f;
                    if (ib >= ir && ib >= ig && L >= 0.82f && (ib - ir) >= 20) capv = 248f;
                    float cap = capv / Math.max(L * 255f, 1f);
                    if (ratio > cap) ratio = cap;
                    int nr = clamp255(ir * ratio);
                    int ng = clamp255(ig * ratio);
                    int nb = clamp255(ib * ratio);
                    px[y * w + x] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                }
            }
        }
    }
    /**
     * 色度中频衰减：Y 不动，Cb/Cr 取 DS=2 网格低频 + detail×CH_KEEP 回加。
     * 手机 NR 的水彩状色度斑块（10~40px）被影调/饱和增益放大后极显眼，
     * 在 LUT 前衰减掉（PC 台 22_v4.py 同构，midCb ×1.85 → ×1.14）。
     */
    public static void chromaDenoise(int[] px, int w, int h) {
        int sw = (w + CH_DS - 1) / CH_DS, sh = (h + CH_DS - 1) / CH_DS;
        float[] crS = new float[sw * sh], cbS = new float[sw * sh];
        int[] cnt = new int[sw * sh];
        for (int y = 0; y < h; y++) {
            int sy = y / CH_DS;
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                float r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                float Y = 0.299f * r + 0.587f * g + 0.114f * b;
                int si = sy * sw + x / CH_DS;
                crS[si] += (r - Y) / 1.402f;
                cbS[si] += (b - Y) / 1.772f;
                cnt[si]++;
            }
        }
        for (int i = 0; i < sw * sh; i++) {
            if (cnt[i] > 0) { crS[i] /= cnt[i]; cbS[i] /= cnt[i]; }
        }
        crS = boxBlur(boxBlur(crS, sw, sh, CH_R), sw, sh, CH_R);
        cbS = boxBlur(boxBlur(cbS, sw, sh, CH_R), sw, sh, CH_R);
        float invDs = 1f / CH_DS;
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * invDs - 0.5f;
            int y0 = floorInt(fy, sh - 1), y1 = Math.min(y0 + 1, sh - 1);
            float wy = Math.max(fy - y0, 0);
            int row0 = y0 * sw, row1 = y1 * sw;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * invDs - 0.5f;
                int x0 = floorInt(fx, sw - 1), x1 = Math.min(x0 + 1, sw - 1);
                float wx = Math.max(fx - x0, 0);
                float crL = (1 - wx) * (1 - wy) * crS[row0 + x0] + wx * (1 - wy) * crS[row0 + x1]
                        + (1 - wx) * wy * crS[row1 + x0] + wx * wy * crS[row1 + x1];
                float cbL = (1 - wx) * (1 - wy) * cbS[row0 + x0] + wx * (1 - wy) * cbS[row0 + x1]
                        + (1 - wx) * wy * cbS[row1 + x0] + wx * wy * cbS[row1 + x1];

                int p = px[y * w + x];
                float r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                float Y = 0.299f * r + 0.587f * g + 0.114f * b;
                float Cr = (r - Y) / 1.402f;
                float Cb = (b - Y) / 1.772f;
                float cr2 = crL + (Cr - crL) * CH_KEEP;
                float cb2 = cbL + (Cb - cbL) * CH_KEEP;
                float r2 = Y + 1.402f * cr2;
                float b2 = Y + 1.772f * cb2;
                float g2 = Y - 0.7141f * cr2 - 0.3441f * cb2;
                px[y * w + x] = 0xFF000000
                        | (clamp255(r2) << 16) | (clamp255(g2) << 8) | clamp255(b2);
            }
        }
    }

    /**
     * 天空色度中频清理（P9Post 用）：40~121px 带的 Cb/Cr 云纹在天空掩码内衰减 65%。
     * 校正场在 DS=2 网格上计算（blur15-blur60）后双线性上采样逐像素减除，
     * 内存轻量；Y 不动；建筑硬边处于高频 detail，基本不受影响。
     * PC 台 38_p9fix.py 同构验证：Cb 云纹 ×1.5 → ×0.8，场饱和保持。
     */
    public static void skyChromaMidbandClean(int[] px, int w, int h) {
        int ds = 2;
        int sw = (w + ds - 1) / ds, sh = (h + ds - 1) / ds;
        float[] cbS = new float[sw * sh], crS = new float[sw * sh], vS = new float[sw * sh];
        int[] cnt = new int[sw * sh];
        for (int y = 0; y < h; y++) {
            int sy = y / ds;
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                float r = ((p >> 16) & 0xFF) / 255f, g = ((p >> 8) & 0xFF) / 255f, b = (p & 0xFF) / 255f;
                float Y = 0.299f * r + 0.587f * g + 0.114f * b;
                int si = sy * sw + x / ds;
                crS[si] += (r - Y) / 1.402f;
                cbS[si] += (b - Y) / 1.772f;
                vS[si] += Math.max(r, Math.max(g, b));
                cnt[si]++;
            }
        }
        for (int i = 0; i < sw * sh; i++) {
            if (cnt[i] > 0) { crS[i] /= cnt[i]; cbS[i] /= cnt[i]; vS[i] /= cnt[i]; }
        }
        float[] cb31 = boxBlur(cbS, sw, sh, 15), cr31 = boxBlur(crS, sw, sh, 15);
        float[] cb121 = boxBlur(cbS, sw, sh, 60), cr121 = boxBlur(crS, sw, sh, 60);
        // 天空掩码（网格级：蓝主导 & V≥0.55）→ 膨胀羽化
        float[] msk = new float[sw * sh];
        for (int i = 0; i < sw * sh; i++) {
            float cb = cbS[i], cr = crS[i], v = vS[i];
            boolean blue = cb > 0.02f && v >= 0.55f;
            msk[i] = blue ? 1f : 0f;
        }
        msk = boxBlur(msk, sw, sh, 6);
        float invDs = 1f / ds;
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * invDs - 0.5f;
            int y0 = floorInt(fy, sh - 1), y1 = Math.min(y0 + 1, sh - 1);
            float wy = Math.max(fy - y0, 0);
            int row0 = y0 * sw, row1 = y1 * sw;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * invDs - 0.5f;
                int x0 = floorInt(fx, sw - 1), x1 = Math.min(x0 + 1, sw - 1);
                float wx = Math.max(fx - x0, 0);
                float m = (1 - wx) * (1 - wy) * msk[row0 + x0] + wx * (1 - wy) * msk[row0 + x1]
                        + (1 - wx) * wy * msk[row1 + x0] + wx * wy * msk[row1 + x1];
                if (m < 0.02f) continue;
                int p = px[y * w + x];
                float r = ((p >> 16) & 0xFF) / 255f, g = ((p >> 8) & 0xFF) / 255f, b = (p & 0xFF) / 255f;
                float Y = 0.299f * r + 0.587f * g + 0.114f * b;
                float Cr = (r - Y) / 1.402f, Cb = (b - Y) / 1.772f;
                float bandCb = (1 - wx) * (1 - wy) * (cb31[row0 + x0] - cb121[row0 + x0])
                        + wx * (1 - wy) * (cb31[row0 + x1] - cb121[row0 + x1])
                        + (1 - wx) * wy * (cb31[row1 + x0] - cb121[row1 + x0])
                        + wx * wy * (cb31[row1 + x1] - cb121[row1 + x1]);
                float bandCr = (1 - wx) * (1 - wy) * (cr31[row0 + x0] - cr121[row0 + x0])
                        + wx * (1 - wy) * (cr31[row0 + x1] - cr121[row0 + x1])
                        + (1 - wx) * wy * (cr31[row1 + x0] - cr121[row1 + x0])
                        + wx * wy * (cr31[row1 + x1] - cr121[row1 + x1]);
                float attn = (1 - SKY_BAND_KEEP) * m;
                float cr2 = Cr - bandCr * attn;
                float cb2 = Cb - bandCb * attn;
                float r2 = Y + 1.402f * cr2;
                float b2 = Y + 1.772f * cb2;
                float g2 = Y - 0.7141f * cr2 - 0.3441f * cb2;
                px[y * w + x] = 0xFF000000
                        | (clamp255(r2 * 255f) << 16) | (clamp255(g2 * 255f) << 8) | clamp255(b2 * 255f);
            }
        }
    }

    private static int floorInt(float v, int max) {
        int i = (int) Math.floor(v);
        if (i < 0) return 0;
        if (i > max) return max;
        return i;
    }

    private static float[] boxBlur(float[] src, int w, int h, int r) {
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            float acc = 0;
            for (int k = -r; k <= r; k++) acc += src[row + clampi(k, 0, w - 1)];
            for (int x = 0; x < w; x++) {
                tmp[row + x] = acc * inv;
                acc -= src[row + clampi(x - r, 0, w - 1)];
                acc += src[row + clampi(x + r + 1, 0, w - 1)];
            }
        }
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int k = -r; k <= r; k++) acc += tmp[clampi(k, 0, h - 1) * w + x];
            for (int y = 0; y < h; y++) {
                out[y * w + x] = acc * inv;
                acc -= tmp[clampi(y - r, 0, h - 1) * w + x];
                acc += tmp[clampi(y + r + 1, 0, h - 1) * w + x];
            }
        }
        return out;
    }

    private static int clampi(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp255(float v) {
        int i = (int) (v + 0.5f);
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }
}

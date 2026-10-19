package com.leicamoments.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * L-M9 v0：非学习的「统计同源」实现（轨道 A）。
 *
 * 思路不是调色，而是**统计对齐**：把输入图的逐通道均值/标准差，按所属桶的比例映射到
 * 目标风格池的域（逐通道统计对齐）：
 *      out = (x - m) * (TGT_SD / CORPUS_SD) + m * (TGT_MU / CORPUS_MU)
 * 常数由离线统计脚本对样张库实测得到。
 *
 * 定位（重要）：
 *  - 它是 L-M9 的**可装机 v0 与兜底层**——保证输出与味道池统计同源；
 *  - 但它**表达不了"连亮场景也压暗"**：A-plain 桶的目标均值(0.470)比语料基准(0.458)还亮，
 *    比例≈1.0，故对明亮场景近乎不做处理。这是统计法的固有上限，需学习式（轨道 B）补。
 *
 * 零依赖、纯 Java、无 ONNX。失败返回 null，调用方回落正常 M9 链并提醒用户。
 */
public final class Lm9Stats {

    private Lm9Stats() {}

    // ---- 语料基准（80 张普通照片实测，充当分母）----
    private static final float[] CORPUS_MU = {0.4580f, 0.4430f, 0.4310f};
    private static final float[] CORPUS_SD = {0.2830f, 0.2820f, 0.3000f};

    // ---- 逐桶目标统计（味道池块库实测）----
    private static final String[] KEYS = {
        "A-plain", "A-sky", "A-skyveg", "B-plain", "B-sky", "B-skyveg", "B-veg", "C-plain", "C-veg"};
    private static final float[][] MU = {
        {0.4703f, 0.4359f, 0.3982f}, {0.3957f, 0.4694f, 0.5461f}, {0.3556f, 0.4050f, 0.3910f},
        {0.3476f, 0.3171f, 0.2954f}, {0.2839f, 0.3552f, 0.4373f}, {0.2690f, 0.3321f, 0.3284f},
        {0.2720f, 0.3054f, 0.2361f}, {0.2154f, 0.1824f, 0.1786f}, {0.1679f, 0.2356f, 0.1661f}};
    private static final float[][] SD = {
        {0.2600f, 0.2525f, 0.2576f}, {0.2408f, 0.2368f, 0.2598f}, {0.2473f, 0.2506f, 0.3217f},
        {0.2707f, 0.2454f, 0.2452f}, {0.2319f, 0.2327f, 0.2759f}, {0.2316f, 0.2369f, 0.2934f},
        {0.2236f, 0.2226f, 0.2118f}, {0.2428f, 0.1947f, 0.1931f}, {0.1882f, 0.2060f, 0.1800f}};

    private static final int PROBE = 320;      // 统计/选桶用的探测边长（足够稳且快）

    /** @return 处理后 JPEG；任何失败返回 null（调用方回落正常 M9 + 提醒） */
    public static byte[] run(Context app, byte[] jpeg) {
        try {
            Bitmap src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (src == null) return null;

            // 1) 探测图（降采样）→ 选桶
            int pw = PROBE, ph = Math.max(1, Math.round(PROBE * src.getHeight() / (float) src.getWidth()));
            Bitmap probe = Bitmap.createScaledBitmap(src, pw, ph, true);
            int[] pp = new int[pw * ph];
            probe.getPixels(pp, 0, pw, 0, 0, pw, ph);
            probe.recycle();
            int bucket = bucketOf(pp);

            // 2) 探测图上的逐通道均值/标准差
            double[] sum = new double[3], sum2 = new double[3];
            for (int px : pp) {
                for (int c = 0; c < 3; c++) {
                    int v = (px >> (16 - c * 8)) & 0xFF;
                    sum[c] += v;
                    sum2[c] += (double) v * v;
                }
            }
            int n = pp.length;
            // 3) 修复②：**区域化相对变换**（实测配方）
            //    out_c = (x_c - m_区域,c) * rS_区域,c + m_区域,c * rL_区域,c
            //    行 0/1/2 = 天空 / 植被 / 其他；rL = 池.区域均值 / 语料.区域均值（选择性压暗），
            //    rS = 池.区域标准差 / 语料.区域标准差（轻微压对比）。
            //    关键：通道比例近乎中性（天空 R/B 0.958、植被 0.986），所以**不再产生整幅偏色**；
            //    而亮度上 sky ×0.93 / veg ×0.69 / other ×0.60 正是「一瞬」的性格。
            // 系数收敛：原 sky/veg/other = 0.93 / 0.69 / 0.58 —— "其他"那档是从味池"地面建筑"来的，
            // 拿去压白云/太阳辉光属类别错误，故上限收到 ~0.72，植被略收。
            final float[][] RL = {{0.919f, 0.953f, 0.959f},
                                  {0.700f, 0.740f, 0.700f},
                                  {0.720f, 0.740f, 0.760f}};
            final float[][] RS = {{1.062f, 1.028f, 1.000f},
                                  {0.870f, 0.953f, 0.858f},
                                  {0.892f, 0.859f, 0.864f}};
            // 先用探测图统计三个区域各自的均值（区域分类与 PC 端掩码同阈值）
            double[] rSum = new double[3], gSum = new double[3], bSum = new double[3];
            int[] rCnt = new int[3];
            for (int p : pp) {
                int r0 = (p >> 16) & 0xFF, g0 = (p >> 8) & 0xFF, b0 = p & 0xFF;
                double lm = (0.2126 * r0 + 0.7152 * g0 + 0.0722 * b0) / 255.0;
                int reg = (b0 > g0 + 10 && b0 > r0 + 20 && lm > 0.35) ? 0
                        : ((g0 > r0 + 8 && g0 > b0 + 8) ? 1 : 2);
                rSum[reg] += r0;
                gSum[reg] += g0;
                bSum[reg] += b0;
                rCnt[reg]++;
            }
            float[][] mR = new float[3][3];
            for (int r = 0; r < 3; r++) {
                int c0 = Math.max(rCnt[r], 1);
                mR[r][0] = (float) (rSum[r] / c0 / 255.0);
                mR[r][1] = (float) (gSum[r] / c0 / 255.0);
                mR[r][2] = (float) (bSum[r] / c0 / 255.0);
            }
            // 3.1) 空区域回退：某区域在探测图上占比 <1% 时均值不可估计，历史实现留下 m=(0,0,0)
            //      → 曲线退化为纯增益 v×RS（sky 变 ×1.062 提亮、veg 变 ×0.870），与 other 的
            //      ×0.72 拉开 47%，是暖白墙斑块的真正幅度来源（比 warmSky 系数差更狠）。
            //      用 other 档替代，保证同一张图内三档量级一致。逐元素复制，避免别名共享。
            for (int r = 0; r < 2; r++) {
                if (rCnt[r] < Math.max(8, pp.length / 100)) {
                    mR[r][0] = mR[2][0];
                    mR[r][1] = mR[2][1];
                    mR[r][2] = mR[2][2];
                }
            }
            // 表内保留 float（不在表内取整）：表内先取整 + 最终再取整 = 双重量化，
            // 在天空这类每像素只差 1 级的平滑渐变上表现为断层/条纹（实拍已复现）。
            float[][][] lut = new float[3][3][256];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    for (int v = 0; v < 256; v++) {
                        float o = (v / 255f - mR[r][c]) * RS[r][c] + mR[r][c] * RL[r][c];
                        lut[r][c][v] = Math.max(0f, Math.min(1f, o)) * 255f;
                    }
                }
            }
            // 4×4 有序抖动（±0.4 级）：消掉 8 位输出的残余等值线
            // 抖动幅度必须与"斜率压缩比"匹配：映射把范围压到 0.72~0.93 倍，等效量化台阶
            // 被放大到 ~1.1~1.4 级，±0.4 压不住 → 提到 ±0.8（仍远低于视觉阈值）
            final float[] dith4 = {0.20f, -0.60f, 0.70f, -0.10f,
                                   -0.50f, 0.60f, -0.30f, 0.40f,
                                   0.60f, -0.20f, 0.30f, -0.70f,
                                   -0.10f, 0.50f, -0.60f, 0.10f};
            int w = src.getWidth(), h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            src.recycle();

            // ---- 天空域单等效曲线（消"太阳辉光灰白絮状雾块"）----
            // 全在 320 探测图上决策，全图只做双线性采样，开销可忽略。
            // 门控：域面积 <5% 或域内平均 sky 权重 <0.30（掩码多半覆盖了水面/建筑）→ 不启用。
            float[][] eff = null;
            float[] dMk = null;
            int dpw = 0, dph = 0;
            int[] mx0 = null, mx1 = null;
            float[] mfx = null;
            {
                float[] mk = skyDomain(pp, pw, ph);
                if (mk != null) {
                    float[] w3 = new float[3];
                    int area = 0;
                    double wsum = 0, ssum = 0, l0 = 0, l2 = 0, lo = 0;
                    int dn = 0;
                    for (int i = 0; i < pp.length; i++) {
                        if (mk[i] <= 0.5f) continue;
                        area++;
                        int p = pp[i];
                        int r0 = (p >> 16) & 0xFF, g0 = (p >> 8) & 0xFF, b0 = p & 0xFF;
                        skyWeight(r0, g0, b0, w3);
                        float y0 = 0.2126f * lut[0][0][r0] + 0.7152f * lut[0][1][g0] + 0.0722f * lut[0][2][b0];
                        float y2 = 0.2126f * lut[2][0][r0] + 0.7152f * lut[2][1][g0] + 0.0722f * lut[2][2][b0];
                        float sw2 = Math.max(1e-6f, w3[0] + w3[2]);
                        lo += (w3[0] * y0 + w3[2] * y2) / sw2;
                        l0 += y0;
                        l2 += y2;
                        wsum += mk[i];
                        ssum += w3[0] * mk[i];
                        dn++;
                    }
                    if (dn > 0 && area >= (long) (0.05 * pp.length) && wsum > 0) {
                        float wbar = (float) (ssum / wsum);
                        if (wbar >= 0.30f) {
                            // 一阶矩守恒：换曲线会因 clamp 非线性而整体偏暗/偏亮，
                            // 这里解出使"域内平均亮度不变"的混合权重 w（对 w 单调线性，可直接解析）。
                            float A = (float) (l0 / dn), B = (float) (l2 / dn), O = (float) (lo / dn);
                            float wMix = Math.abs(A - B) < 1e-3f ? wbar : (O - B) / (A - B);
                            wMix = Math.max(0f, Math.min(1f, wMix));
                            eff = new float[3][256];
                            for (int c = 0; c < 3; c++) {
                                for (int v = 0; v < 256; v++) {
                                    eff[c][v] = wMix * lut[0][c][v] + (1f - wMix) * lut[2][c][v];
                                }
                            }
                            dMk = mk;
                            dpw = pw;
                            dph = ph;
                            // x 方向采样索引/权重（每行复用，双线性）
                            mx0 = new int[w];
                            mx1 = new int[w];
                            mfx = new float[w];
                            for (int x = 0; x < w; x++) {
                                float sx = (x + 0.5f) * dpw / w - 0.5f;
                                sx = Math.max(0f, Math.min(dpw - 1f, sx));
                                int i0 = (int) sx;
                                int i1 = Math.min(dpw - 1, i0 + 1);
                                mx0[x] = i0;
                                mx1[x] = i1;
                                mfx[x] = sx - i0;
                            }
                            DiagLog.INSTANCE.w("LM9STAT", "sky-domain on: area=" + String.format("%.3f", area / (double) pp.length)
                                    + " wbar=" + String.format("%.3f", wbar) + " wMix=" + String.format("%.3f", wMix));
                        }
                    }
                }
            }
            float errR = 0f, errG = 0f, errB = 0f;      // 误差扩散的状态（每行重置）
            // 软权重（连续映射，无硬边）+ 高光保护：
            //   · 硬阈值会让"太阳辉光/雾霾"（白色，不满足 b>g+10）落进"其他"被重压 → 交界硬边、
            //     过渡带像素反复跳变 → 点状杂色（实拍已复现）；改成连续权重后一并消失
            //   · 亮部（lum>0.8，云与辉光）权重向"天空"靠拢，从机制上保证不会被压暗
            float[] w3 = new float[3];
            int my0 = 0, my1 = 0;
            float mfy = 0f;
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                int r0 = (v >> 16) & 0xFF, g0 = (v >> 8) & 0xFF, b0 = v & 0xFF;
                int x = i % w;
                if (x == 0) {
                    errR = errG = errB = 0f;
                    if (dMk != null) {
                        int y = i / w;
                        float sy = (y + 0.5f) * dph / h - 0.5f;
                        sy = Math.max(0f, Math.min(dph - 1f, sy));
                        my0 = (int) sy;
                        my1 = Math.min(dph - 1, my0 + 1);
                        mfy = sy - my0;
                    }
                }
                // 软权重（连续映射，无硬边）+ 高光保护：
                //   · 硬阈值会让"太阳辉光/雾霾"（白色，不满足 b>g+10）落进"其他"被重压 → 交界硬边、
                //     过渡带像素反复跳变 → 点状杂色（实拍已复现）；改成连续权重后一并消失
                //   · 亮部（lum>0.8，云与辉光）权重向"天空"靠拢，从机制上保证不会被压暗
                //   · 暖色第二通道 + satGate 见 skyWeight（防暖白墙被劈成两档）
                skyWeight(r0, g0, b0, w3);
                float sky = w3[0], veg = w3[1], other = w3[2];
                float[][] cSky = lut[0], cOth = lut[2];
                if (dMk != null) {
                    // 域内：sky 权重换成"空间域掩码"（域内≈1、域外回落到原权重），
                    // 曲线换成域内单等效曲线 eff —— 权重场的空间方差被抹掉，絮状雾块消失。
                    int a0 = my0 * dpw, a1 = my1 * dpw;
                    int fx0 = mx0[x], fx1 = mx1[x];
                    float fx = mfx[x];
                    float m = ((dMk[a0 + fx0] * (1f - fx) + dMk[a0 + fx1] * fx) * (1f - mfy))
                            + ((dMk[a1 + fx0] * (1f - fx) + dMk[a1 + fx1] * fx) * mfy);
                    float sk = m + (1f - m) * sky;
                    other = Math.max(0f, 1f - sk - veg);
                    sky = sk;
                    cSky = eff;
                }
                float sw = sky + veg + other;
                // 1-D 误差扩散（每行重置）：把取整误差传给下一个像素。
                // 有序抖动在 ±0.8 幅度下会变成可见的规则竖条纹（实拍已复现），误差扩散不形成图案。
                float wantR = (sky * cSky[0][r0] + veg * lut[1][0][r0] + other * cOth[0][r0]) / sw + errR;
                float wantG = (sky * cSky[1][g0] + veg * lut[1][1][g0] + other * cOth[1][g0]) / sw + errG;
                float wantB = (sky * cSky[2][b0] + veg * lut[1][2][b0] + other * cOth[2][b0]) / sw + errB;
                int ri = (int) Math.round(wantR);
                int gi = (int) Math.round(wantG);
                int bi = (int) Math.round(wantB);
                errR = Math.max(-1f, Math.min(1f, wantR - ri));
                errG = Math.max(-1f, Math.min(1f, wantG - gi));
                errB = Math.max(-1f, Math.min(1f, wantB - bi));
                ri = Math.max(0, Math.min(255, ri));
                gi = Math.max(0, Math.min(255, gi));
                bi = Math.max(0, Math.min(255, bi));
                px[i] = (0xFF << 24) | (ri << 16) | (gi << 8) | bi;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(jpeg.length);
            out.compress(Bitmap.CompressFormat.JPEG, 98, bos);   // 95→98：减少二次编码在平滑渐变上的等值带
            out.recycle();
            DiagLog.INSTANCE.w("LM9STAT", "统计同源链完成 bucket=" + KEYS[bucket] + " " + bos.size() + "B");
            return com.codex.leicaapp.MadridLegendary.copyExif(jpeg, bos.toByteArray());
        } catch (Throwable t) {
            DiagLog.INSTANCE.e("LM9STAT", "统计同源链失败，回落正常 M9", t);
            return null;
        }
    }

    /**
     * 三区域软权重（探测图与全图共用，保证口径一致）。
     * 写出 out[0]=sky, out[1]=veg, out[2]=other。
     */
    private static void skyWeight(int r0, int g0, int b0, float[] out) {
        float rf = r0 / 255f, gf = g0 / 255f, bf = b0 / 255f;
        float lum = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf;
        float blueness = Math.min(1f, Math.max(0f, (bf - gf - 0.024f) / 0.078f));
        float greenness = Math.min(1f, Math.max(0f, (gf - Math.max(rf, bf) - 0.016f) / 0.063f));
        float chroma = Math.max(rf, Math.max(gf, bf)) - Math.min(rf, Math.min(gf, bf));
        float satGate = Math.min(1f, Math.max(0f, (chroma - 0.045f) / 0.055f));
        float warmSky = Math.min(1f, Math.max(0f, (rf - bf - 0.020f) / 0.060f))
                * Math.min(1f, Math.max(0f, (lum - 0.45f) / 0.15f))
                * satGate;
        float blueSky = blueness * Math.min(1f, Math.max(0f, (lum - 0.30f) / 0.15f));
        float sky = Math.max(blueSky, warmSky);
        float hi = Math.min(1f, Math.max(0f, (lum - 0.80f) / 0.15f));
        sky = sky * (1f - hi) + hi;
        float veg = greenness * (1f - sky);
        out[0] = sky;
        out[1] = veg;
        out[2] = Math.max(0f, 1f - sky - veg);
    }

    /**
     * 空间天空域（320 探测图上计算，再双线性放大）。
     *
     * 动机：逐像素 sky 权重由 (b-g) 的 0.078 窄斜坡驱动，在太阳辉光/薄云里该差值缓慢起伏，
     * 权重于是在 0~1 之间整段摆动；两档曲线（sky x1.062 vs other x0.72）相差 47%，
     * 权重场的等值线就被放大成大片灰白絮状雾块（实拍已复现，右区中频能量 +66%）。
     *
     * 做法：用"顶行连通的蓝色候选区"划出天空域，域内改用单一等效曲线（见 run 内 wbar），
     * 权重场的空间方差归零、域均值保留。
     *
     * @return 羽化后的域掩码（0~1），长度 pw*ph；检测失败返回 null
     */
    private static float[] skyDomain(int[] pp, int pw, int ph) {
        int n = pw * ph;
        if (n < 64) return null;
        // 1) 候选：与探测图统计同阈值的宽松版（b-g>2 级、b 不低于 r-8、lum>0.20）
        byte[] cand = new byte[n];
        int cc = 0;
        for (int i = 0; i < n; i++) {
            int p = pp[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            double lm = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
            if (b - g > 2 && b > r - 8 && lm > 0.20) {
                cand[i] = 1;
                cc++;
            }
        }
        if (cc < Math.max(16, n / 100)) return null;
        // 2) 膨胀 2 次（3x3）：替代闭运算填掉小洞，纯 Java 无依赖
        byte[] dil = dilate3(dilate3(cand, pw, ph), pw, ph);
        // 3) 只保留与顶行连通的区域（天空必然从画面顶边开始，地面/水面不接顶边的不算）
        byte[] seed = new byte[n];
        int[] stack = new int[n];
        int sp2 = 0;
        for (int x = 0; x < pw; x++) {
            int i = x;
            if (dil[i] == 1 && seed[i] == 0) {
                seed[i] = 1;
                stack[sp2++] = i;
            }
        }
        if (sp2 == 0) return null;
        while (sp2 > 0) {
            int i = stack[--sp2];
            int x = i % pw, y = i / pw;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= ph) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= pw) continue;
                    int j = ny * pw + nx;
                    if (dil[j] == 1 && seed[j] == 0) {
                        seed[j] = 1;
                        stack[sp2++] = j;
                    }
                }
            }
        }
        // 4) 羽毛化（sigma≈1.7）：域边界线性过渡，避免天空/地面出现拼缝
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = seed[i];
        return blur5(f, pw, ph, 1.7f);
    }

    private static byte[] dilate3(byte[] src, int w, int h) {
        byte[] d = new byte[src.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (src[row + x] == 0) continue;
                int y0 = Math.max(0, y - 1), y1 = Math.min(h - 1, y + 1);
                int x0 = Math.max(0, x - 1), x1 = Math.min(w - 1, x + 1);
                for (int yy = y0; yy <= y1; yy++) {
                    int r2 = yy * w;
                    for (int xx = x0; xx <= x1; xx++) d[r2 + xx] = 1;
                }
            }
        }
        return d;
    }

    /** 可分离高斯（边缘钳位），半径足够覆盖 sigma*3。 */
    private static float[] blur5(float[] src, int w, int h, float sigma) {
        int rad = Math.max(1, Math.round(sigma * 3f));
        float[] k = new float[rad * 2 + 1];
        float sum = 0f;
        for (int i = -rad; i <= rad; i++) {
            float v = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            k[i + rad] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] tmp = new float[src.length], out = new float[src.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                for (int i = -rad; i <= rad; i++) {
                    int xx = Math.max(0, Math.min(w - 1, x + i));
                    acc += src[row + xx] * k[i + rad];
                }
                tmp[row + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                for (int i = -rad; i <= rad; i++) {
                    int yy = Math.max(0, Math.min(h - 1, y + i));
                    acc += tmp[yy * w + x] * k[i + rad];
                }
                out[y * w + x] = acc;
            }
        }
        return out;
    }

    /** 选桶：亮度档（A>=0.50 / B>=0.30，输入域阈值）× 场景型（天空/植被占比）。 */
    private static int bucketOf(int[] px) {
        double lumSum = 0;
        int sky = 0, veg = 0;
        for (int p : px) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            double lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
            lumSum += lum;
            if (b > g + 10 && b > r + 20 && lum > 0.35) sky++;
            if (g > r + 8 && g > b + 8) veg++;
        }
        int n = px.length;
        double lm = lumSum / n;
        double skyF = sky / (double) n, vegF = veg / (double) n;
        String type = (skyF > 0.12 ? "sky" : "") + (vegF > 0.12 ? "veg" : "");
        if (type.isEmpty()) type = "plain";
        String tier = lm >= 0.50 ? "A" : (lm >= 0.30 ? "B" : "C");
        String key = tier + "-" + type;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 3;   // 回退 B-plain（样本最多：n=780）
    }
}

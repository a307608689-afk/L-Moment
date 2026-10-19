package com.codex.leicaapp;

/**
 * 自定义色彩（「色彩 N」）渲染期收尾——压制「高光低饱和区斑驳」。
 *
 * <p>问题（MVIMG_20261013_134102 实测）：太阳/天空高光带出现约 2× 放大的斑驳。
 * 根因不在渲染链，而在 LUT 本身——参考图聚合后的 tone curve 高光段斜率 &gt; 1
 * （末两格 1.18 / 1.98），65³ 网格在该输入域（R 232~244 / G 208~221 / B 192~206）
 * 的局部增益达 2~3×，把输入 1/255 级的微起伏放大成可见噪声。
 * PC 用设备 LUT 直套原图复现同一斑块（频带图相关 0.837），确认与 JPEG 收尾无关。</p>
 *
 * <p>修复（PC 台 A/B 定案，唯一不动影调的方案）：在高光 + 低饱和区把输出往它的
 * 引导滤波低频拉。引导图 = 亮度，p = 各通道，r ≈ 0.8% 短边（25px @3000 短边），eps=0.01。
 * 权重 wy=(Y-0.58)/0.20、ws=(0.60-sat)/0.34，乘积为混合系数。
 * 实测（设备导出的 Leica_色彩_1_1792255213162.jpg，4096×3072）：
 * 太阳区 hf 1.07→0.74、p99 3.85→2.37、该区 MAE 0.24，全图 MAE 0.25，maxdiff 8；
 * 24 张样图回归 median MAE 0.33 / 色相偏移 p90 3°（彩色高光被 ws 挡住，不动）。</p>
 *
 * <p>二轮（2026-10-18，IMG_20261018_011831 天空圈区）：ws 上界过紧是压制不足主因
 * （见 SAT_HI 注释）。sat 门限放宽后同场景 hf3/hf12 = 1.22/1.36 → 0.31/0.38，
 * 3× 高频增强下碎块消失、只剩柔和云影渐变；整图副作用（城堡尖顶、白车车漆、
 * 地面纹理）目检无变化，半径在 8~24px 内对结果几乎无影响（eps 主导）。</p>
 *
 * <p>代价：高光纹理轻微变柔（云纹/光晕细节被抹平一部分）。仅自定义色彩链生效，
 * 内置风格（M4 / L-P9 / L-M9 等）完全不受影响。</p>
 *
 * <p>性能：DS=4 网格 + 滚动盒滤波（O(N)），12.6MP 约 0.2~0.5s（后台线程）；
 * 高光低饱和占比 &lt; 1% 的图几毫秒内直接跳过。
 * 另：小于 1MP 的图（滤镜条缩略图）跳过——斑驳在缩略图上不可见，徒增耗时。</p>
 */
public final class CustomLutPost implements AlexaLut.Hooks {

    private static final CustomLutPost INSTANCE = new CustomLutPost();

    public static CustomLutPost get() {
        return INSTANCE;
    }

    private CustomLutPost() {
    }

    /** 总开关（真机 A/B：置 false 即回到加入本算子之前的行为）。 */
    public static volatile boolean ENABLED = true;

    // ---- 高光-低饱和混合权重（PC 台 skyfix 对拍口径，改这里需同步 PC 脚本）----
    /**
     * 高光起点亮度（0..1，Rec.601 luma）。
     * 注意不能取 0.72：设备导出图的太阳/天空亮部平均 luma 恰好在 0.72 附近，
     * 0.72 门槛会把整片需要压制的区域挡在门外（实测权重均值只剩 0.03）。
     * 0.58 起、0.78 到满权：实测太阳区 hf 1.07→0.74、p99 3.85→2.37、该区 MAE 0.24。
     */
    private static final float Y_LO = 0.58f;
    private static final float Y_SPAN = 0.20f;
    /**
     * 低饱和上界（HSV S）；高于它完全不处理，避免压掉彩色高光。
     * 2026-10-18 二轮定案：原 0.34/0.24 是压制不足的真因——导出图天空实测
     * sat≈0.24，ws=(0.34-0.24)/0.24=0.42，再乘亮度权 0.8 只剩 0.34，
     * 替换率不足 → 天空碎块只压掉 66%，肉眼仍见「斑块感」（用户连续两次反馈）。
     * 放宽到 0.60/0.34 后该区 ws=1，hf3 1.22→0.31（-75%）、hf12 1.36→0.38；
     * 引导滤波以亮度为引导，建筑/车漆的亮度纹理保留，只抹掉与亮度无关的色度斑。
     */
    private static final float SAT_HI = 0.60f;
    private static final float SAT_SPAN = 0.34f;

    /** 引导滤波正则（归一化域，与 PC 实验 eps=0.01 一致） */
    private static final float EPS = 0.010f;

    /** 网格步长（降采样 + 上采样双线性，DS=4 时 12.6MP → 787k 网格点） */
    private static final int DS = 4;
    /** 全分辨率滤波半径 = 短边 × R_RATIO（3000 短边 → 24px，与 PC 的 r=25 对齐） */
    private static final float R_RATIO = 0.008f;
    private static final int R_MIN = 4;
    private static final int R_MAX = 48;

    /**
     * 单通道最大改动量（级）。
     * DS=4 网格降采样会把星点/灯光/叶隙光斑这类小尺寸高光平均进周围暗背景，
     * 若不夹取，这些点会被整块替换（实测出现 181~208 级落差）。斑驳压制本身
     * 只需 1~3 级改动，夹 8 级对太阳区 hf/p99 完全无损（实测 0.74 / 1.96 不变）。
     */
    private static final float DELTA_CLAMP = 8f;

    /** 小于该像素数直接跳过（滤镜条缩略图 200px 宽；预览/导出都远大于此，行为一致） */
    private static final long MIN_PIXELS = 400000L;
    /** 抽样中「权重 ≥ 0.5」的像素占比低于该值 → 没有斑驳可治，跳过 */
    private static final float TRIGGER = 0.01f;

    /** 上一次 post 的执行摘要（供引擎侧打一行诊断日志）。 */
    public static volatile String lastNote = "";

    @Override
    public void pre(int[] px, int w, int h) {
        // 只做 LUT 之后的收尾
    }

    @Override
    public void post(int[] px, int w, int h) {
        lastNote = "";
        if (!ENABLED || px == null) return;
        // 兜底：AlexaLut.process 的外层 catch 会把任何异常变成「返回原图」，
        // 收尾算子失败绝不能连累 LUT 输出，这里自行吞掉。
        try {
            run(px, w, h);
        } catch (Throwable t) {
            lastNote = "post failed: " + t;
        }
    }

    private void run(int[] px, int w, int h) {
        long n = (long) w * h;
        if (n < MIN_PIXELS) {
            lastNote = "skip small " + w + "x" + h;
            return;
        }
        long t0 = System.currentTimeMillis();
        int minSide = Math.min(w, h);

        // ---- 1) 触发判定：1/64 抽样，太阳/天空高光带占比不到 1% 的图直接放行 ----
        int step = Math.max(8, minSide / 64);
        int hit = 0, tot = 0;
        for (int y = 0; y < h; y += step) {
            int row = y * w;
            for (int x = 0; x < w; x += step) {
                int p = px[row + x];
                tot++;
                if (weight((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF) >= 0.5f) hit++;
            }
        }
        if (tot == 0) return;
        float coverage = hit / (float) tot;
        if (coverage < TRIGGER) {
            lastNote = String.format("coverage %.2f%% skip", coverage * 100f);
            return;
        }

        // ---- 2) 降采样到网格（每格均值）----
        int sw = (w + DS - 1) / DS, sh = (h + DS - 1) / DS;
        int m = sw * sh;
        float[] sR = new float[m], sG = new float[m], sB = new float[m], sY = new float[m];
        int[] cnt = new int[m];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int gRow = (y / DS) * sw;
            for (int x = 0; x < w; x++) {
                int p = px[row + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int si = gRow + x / DS;
                sR[si] += r;
                sG[si] += g;
                sB[si] += b;
                sY[si] += 0.299f * r + 0.587f * g + 0.114f * b;
                cnt[si]++;
            }
        }
        for (int i = 0; i < m; i++) {
            if (cnt[i] > 0) {
                float k = 1f / (cnt[i] * 255f);
                sR[i] *= k;
                sG[i] *= k;
                sB[i] *= k;
                sY[i] *= k;
            }
        }

        // ---- 3) 网格引导滤波：I=亮度，p=各通道（边缘保持，硬边不在太阳区才会被拉）----
        int rFull = Math.round(minSide * R_RATIO);
        if (rFull < R_MIN) rFull = R_MIN;
        if (rFull > R_MAX) rFull = R_MAX;
        int rS = Math.max(1, rFull / DS);

        float[] mI = boxBlur(sY, sw, sh, rS);
        float[] corrII = new float[m];
        for (int i = 0; i < m; i++) corrII[i] = sY[i] * sY[i];
        corrII = boxBlur(corrII, sw, sh, rS);
        float[] varI = new float[m];
        for (int i = 0; i < m; i++) varI[i] = corrII[i] - mI[i] * mI[i];
        corrII = null;

        float[] qR = guideChannel(sY, sR, mI, varI, sw, sh, rS);
        float[] qG = guideChannel(sY, sG, mI, varI, sw, sh, rS);
        float[] qB = guideChannel(sY, sB, mI, varI, sw, sh, rS);

        // ---- 4) 全分辨率混合：out = out + w · (引导低频 - out) ----
        float invDs = 1f / DS;
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * invDs - 0.5f;
            int y0 = floorInt(fy, sh - 1), y1 = Math.min(y0 + 1, sh - 1);
            float wyG = fy - y0;
            if (wyG < 0) wyG = 0;
            int row0 = y0 * sw, row1 = y1 * sw;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = px[row + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                float wgt = weight(r, g, b);
                if (wgt <= 0.002f) continue;

                float fx = (x + 0.5f) * invDs - 0.5f;
                int x0 = floorInt(fx, sw - 1), x1 = Math.min(x0 + 1, sw - 1);
                float wxG = fx - x0;
                if (wxG < 0) wxG = 0;
                float w00 = (1 - wxG) * (1 - wyG), w10 = wxG * (1 - wyG);
                float w01 = (1 - wxG) * wyG, w11 = wxG * wyG;

                float lr = w00 * qR[row0 + x0] + w10 * qR[row0 + x1]
                        + w01 * qR[row1 + x0] + w11 * qR[row1 + x1];
                float lg = w00 * qG[row0 + x0] + w10 * qG[row0 + x1]
                        + w01 * qG[row1 + x0] + w11 * qG[row1 + x1];
                float lb = w00 * qB[row0 + x0] + w10 * qB[row0 + x1]
                        + w01 * qB[row1 + x0] + w11 * qB[row1 + x1];

                float dr = (lr * 255f - r) * wgt;
                float dg = (lg * 255f - g) * wgt;
                float db = (lb * 255f - b) * wgt;
                if (dr > DELTA_CLAMP) dr = DELTA_CLAMP; else if (dr < -DELTA_CLAMP) dr = -DELTA_CLAMP;
                if (dg > DELTA_CLAMP) dg = DELTA_CLAMP; else if (dg < -DELTA_CLAMP) dg = -DELTA_CLAMP;
                if (db > DELTA_CLAMP) db = DELTA_CLAMP; else if (db < -DELTA_CLAMP) db = -DELTA_CLAMP;
                int nr = clamp255(r + dr);
                int ng = clamp255(g + dg);
                int nb = clamp255(b + db);
                px[row + x] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
            }
        }

        lastNote = String.format("applied cov=%.1f%% r=%d %dx%d %dms",
                coverage * 100f, rFull, sw, sh, System.currentTimeMillis() - t0);
    }

    /**
     * 高光 × 低饱和混合系数：y 越大越管，s 越大越不管。
     * 两者相乘保证彩色高光（夕阳、霓虹、肤色高光）不被误压。
     */
    private static float weight(int r, int g, int b) {
        float Y = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
        float wy = (Y - Y_LO) / Y_SPAN;
        if (wy <= 0f) return 0f;
        if (wy > 1f) wy = 1f;
        int mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
        if (mx <= 0) return wy;
        int mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
        float sat = (mx - mn) / (float) mx;
        float ws = (SAT_HI - sat) / SAT_SPAN;
        if (ws <= 0f) return 0f;
        if (ws > 1f) ws = 1f;
        return wy * ws;
    }

    /** 单通道引导滤波（q = ā·I + b̄，网格点级）。 */
    private static float[] guideChannel(float[] I, float[] p, float[] mI, float[] varI,
                                        int w, int h, int r) {
        float[] mP = boxBlur(p, w, h, r);
        float[] ip = new float[I.length];
        for (int i = 0; i < I.length; i++) ip[i] = I[i] * p[i];
        ip = boxBlur(ip, w, h, r);

        float[] a = new float[I.length], b = new float[I.length];
        for (int i = 0; i < I.length; i++) {
            float cov = ip[i] - mI[i] * mP[i];
            float ai = cov / (varI[i] + EPS);
            a[i] = ai;
            b[i] = mP[i] - ai * mI[i];
        }
        a = boxBlur(a, w, h, r);
        b = boxBlur(b, w, h, r);

        float[] q = new float[I.length];
        for (int i = 0; i < I.length; i++) q[i] = a[i] * I[i] + b[i];
        return q;
    }

    private static int floorInt(float v, int max) {
        int i = (int) Math.floor(v);
        if (i < 0) return 0;
        return i > max ? max : i;
    }

    /** 滚动和盒滤波（O(N)，边界钳位），与 Lm9Post 同款实现。 */
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
        return i > 255 ? 255 : i;
    }
}

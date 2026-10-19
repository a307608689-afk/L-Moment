package com.codex.leicaapp;

import java.util.Arrays;
import java.util.List;

/**
 * A 路线参数拟合（端上）：从参考图闭式解出 (A,b,xs,tab)，编码为 LMA6 v2 bin。
 * 与离线拟合脚本同口径；浮点求和顺序不同，
 * 落到 8bit 上是 0~1 灰阶，参数再 apply 时 dE00 均值 < 0.3（见 20_fit_check.py）。
 */
public final class StyleAFit {

    public static final int FIT_SIDE = 640;
    public static final int NODES = 64;
    public static final int N_Q = 1024;
    public static final int MAX_PIXELS = 1_500_000;

    private static final byte[] MAGIC = {'L', 'M', 'A', '6'};
    private static final int VER = 2;
    private static final double EPS = 1e-6;
    private static final double WB_LO = 0.5, WB_HI = 2.0;
    private static final double EXPO_LO = 0.005, EXPO_HI = 0.98;
    private static final double SCALE_LO = 0.25, SCALE_HI = 4.0;
    private static final double LUMA_B = (double) 0.2126f;
    private static final double LUMA_G = (double) 0.7152f;
    private static final double LUMA_R = (double) 0.0722f;
    private static final double WX = 0.950456, WZ = 1.088754;
    private static final double T_THRESH = 0.008856;
    private static final double M00 = 0.412453, M01 = 0.357580, M02 = 0.180423;
    private static final double M10 = 0.212671, M11 = 0.715160, M12 = 0.072169;
    private static final double M20 = 0.019334, M21 = 0.119193, M22 = 0.950227;

    private static final double[] LIN = new double[256];
    private static final double[] L2S = new double[4096];

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

    /** 一张参考图（ARGB，来自 Bitmap.getPixels）。 */
    public static final class Frame {
        public final int[] px;
        public final int w, h;

        public Frame(int[] px, int w, int h) {
            if (px == null || w <= 0 || h <= 0 || px.length < w * h) {
                throw new IllegalArgumentException("bad frame " + w + "x" + h);
            }
            this.px = px;
            this.w = w;
            this.h = h;
        }
    }

    private StyleAFit() {}

    /** 拟合并返回 LMA6 v2 参数（1596 B）；参考图不足/退化返回 null。 */
    public static byte[] fit(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) return null;
        int total = 0;
        for (Frame f : frames) total += f.w * f.h;
        if (total < 1024) return null;
        int stride = Math.max(1, (total + MAX_PIXELS - 1) / MAX_PIXELS);

        // PC 的 C.neutralize 对单张图调用：每张参考图各自 gray-world + 各自的曝光归一
        int n = 0;
        for (Frame f : frames) n += (f.w * f.h + stride - 1) / stride;
        float[] src = new float[n * 3];
        float[] dst = new float[n * 3];
        int k = 0;
        double[] lab = new double[3];
        double pLo = 0, pHi = 0, scale = 1, off = 0;
        for (int fi = 0; fi < frames.size(); fi++) {
            Frame f = frames.get(fi);
            int cnt = f.w * f.h;
            double sb = 0, sg = 0, sr = 0;
            for (int i = 0; i < cnt; i++) {
                int p = f.px[i];
                sb += LIN[p & 0xFF];
                sg += LIN[(p >>> 8) & 0xFF];
                sr += LIN[(p >>> 16) & 0xFF];
            }
            double mb = sb / cnt, mg = sg / cnt, mr = sr / cnt;
            double m = (mb + mg + mr) / 3.0;
            double gb = clamp(m / (mb + EPS), WB_LO, WB_HI);
            double gg = clamp(m / (mg + EPS), WB_LO, WB_HI);
            double gr = clamp(m / (mr + EPS), WB_LO, WB_HI);
            int ny = (cnt + stride - 1) / stride;
            double[] yv = new double[ny];
            int yi = 0;
            for (int i = 0; i < cnt; i += stride) {
                int p = f.px[i];
                yv[yi++] = LUMA_B * LIN[p & 0xFF] * gb
                        + LUMA_G * LIN[(p >>> 8) & 0xFF] * gg
                        + LUMA_R * LIN[(p >>> 16) & 0xFF] * gr;
            }
            pLo = percentile(yv, yi, 0.005);
            pHi = percentile(yv, yi, 0.995);
            double span = Math.max(pHi - pLo, 1e-4);
            scale = clamp((EXPO_HI - EXPO_LO) / span, SCALE_LO, SCALE_HI);
            off = EXPO_LO - pLo * scale;
            for (int i = 0; i < cnt; i += stride) {
                int p = f.px[i];
                double b0 = LIN[p & 0xFF], g0 = LIN[(p >>> 8) & 0xFF], r0 = LIN[(p >>> 16) & 0xFF];
                // PC 的 neutralize 把结果写回 8bit 再做 Lab，这里必须同样往返
                int b8 = toU8(b0 * gb * scale + off);
                int g8 = toU8(g0 * gg * scale + off);
                int r8 = toU8(r0 * gr * scale + off);
                rgb2lab(LIN[r8], LIN[g8], LIN[b8], lab);
                src[k * 3] = (float) lab[0];
                src[k * 3 + 1] = (float) lab[1];
                src[k * 3 + 2] = (float) lab[2];
                rgb2lab(r0, g0, b0, lab);
                dst[k * 3] = (float) lab[0];
                dst[k * 3 + 1] = (float) lab[1];
                dst[k * 3 + 2] = (float) lab[2];
                k++;
            }
        }
        n = k;
        if (n < 512) return null;

        // 3x3 仿射：mu + cov(ddof=1) -> inv -> A = inv(Ls)^T @ Ld^T
        double[] muS = new double[3], muD = new double[3];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                muS[c] += src[i * 3 + c];
                muD[c] += dst[i * 3 + c];
            }
        }
        for (int c = 0; c < 3; c++) {
            muS[c] /= n;
            muD[c] /= n;
        }
        double[] cs = new double[9], cd = new double[9];
        for (int i = 0; i < n; i++) {
            double a0 = src[i * 3] - muS[0], a1 = src[i * 3 + 1] - muS[1], a2 = src[i * 3 + 2] - muS[2];
            double d0 = dst[i * 3] - muD[0], d1 = dst[i * 3 + 1] - muD[1], d2 = dst[i * 3 + 2] - muD[2];
            cs[0] += a0 * a0; cs[1] += a0 * a1; cs[2] += a0 * a2;
            cs[3] += a1 * a0; cs[4] += a1 * a1; cs[5] += a1 * a2;
            cs[6] += a2 * a0; cs[7] += a2 * a1; cs[8] += a2 * a2;
            cd[0] += d0 * d0; cd[1] += d0 * d1; cd[2] += d0 * d2;
            cd[3] += d1 * d0; cd[4] += d1 * d1; cd[5] += d1 * d2;
            cd[6] += d2 * d0; cd[7] += d2 * d1; cd[8] += d2 * d2;
        }
        for (int i = 0; i < 9; i++) {
            cs[i] /= (n - 1);
            cd[i] /= (n - 1);
        }
        // PC 的 _chol_psd 返回 Cholesky 因子（不是协方差逆）：
        // A = inv(Ls)^T @ Ld^T  ->  A[k][c] = sum_m invLs[m][k] * Ld[c][m]
        double[] ls = chol3(sym(cs));
        double[] ld = chol3(sym(cd));
        double[] ils = invLower3(ls);
        double[] A = new double[9];
        for (int kk = 0; kk < 3; kk++) {
            for (int c = 0; c < 3; c++) {
                double s = 0;
                for (int m = 0; m < 3; m++) s += ils[m * 3 + kk] * ld[c * 3 + m];
                A[kk * 3 + c] = s;
            }
        }
        double[] b = new double[3];
        for (int c = 0; c < 3; c++) {
            double s = 0;
            for (int kk = 0; kk < 3; kk++) s += muS[kk] * A[kk * 3 + c];
            b[c] = muD[c] - s;
        }

        // 每通道 1024 分位 -> 64 节点，单调化（1e-6 递增，与 PC 同）
        double[][] qsrc = new double[3][N_Q];
        double[][] qdst = new double[3][N_Q];
        double[] z = new double[n];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) {
                z[i] = b[c];
                for (int m = 0; m < 3; m++) z[i] += src[i * 3 + m] * A[m * 3 + c];
            }
            double[] zs = z.clone();
            Arrays.sort(zs);
            quantiles(zs, n, qsrc[c]);
            for (int i = 0; i < n; i++) {
                z[i] = dst[i * 3 + c];
            }
            Arrays.sort(z);
            quantiles(z, n, qdst[c]);
            double acc = qsrc[c][0];
            for (int i = 0; i < N_Q; i++) {
                acc = Math.max(acc, qsrc[c][i] + i * EPS);
                qsrc[c][i] = acc;
            }
            acc = qdst[c][0];
            for (int i = 0; i < N_Q; i++) {
                acc = Math.max(acc, qdst[c][i] + i * EPS);
                qdst[c][i] = acc;
            }
        }
        double[] xs = new double[3 * NODES];
        double[] tab = new double[3 * NODES];
        for (int c = 0; c < 3; c++) {
            for (int kk = 0; kk < NODES; kk++) {
                int sel = (int) Math.rint(kk * (N_Q - 1) / (double) (NODES - 1));
                xs[c * NODES + kk] = qsrc[c][sel];
                tab[c * NODES + kk] = qdst[c][sel];
            }
        }
        return encode(A, b, xs, tab);
    }

    /**
     * 力度混合：old → new 按 {@code s}（0=old，1=new）线性插值。
     *
     * <p>凸组合保持逐通道节点表的单调性，所以结果依旧是合法参数；
     * A/b 同理（仿射变换的凸组合）。份数不同或长度不合规时返回 null。
     */
    public static byte[] blend(byte[] oldBytes, byte[] newBytes, float s) {
        float[] a = unpack(oldBytes);
        float[] b = unpack(newBytes);
        if (a == null || b == null) return null;
        if (a.length != b.length) return null;
        float t = s < 0f ? 0f : (s > 1f ? 1f : s);
        int n = a.length;
        double[] A = new double[9];
        double[] bb = new double[3];
        double[] xs = new double[3 * NODES];
        double[] tab = new double[3 * NODES];
        for (int i = 0; i < n; i++) {
            double v = a[i] + (b[i] - a[i]) * (double) t;
            if (i < 9) A[i] = v;
            else if (i < 12) bb[i - 9] = v;
            else if (i < 12 + 3 * NODES) xs[i - 12] = v;
            else tab[i - 12 - 3 * NODES] = v;
        }
        return encode(A, bb, xs, tab);
    }

    /** 解出 [A(9), b(3), xs(3N), tab(3N)]；格式不合规返回 null。 */
    private static float[] unpack(byte[] bytes) {
        if (bytes == null) return null;
        int expect = 12 + 36 + 12 + 12 * NODES * 2;
        if (bytes.length != expect) return null;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            if (bb.get(i) != MAGIC[i]) return null;
        }
        if (bb.getInt(4) != VER) return null;
        if (bb.getInt(8) != NODES) return null;
        float[] out = new float[12 + 3 * NODES * 2];
        for (int i = 0; i < out.length; i++) out[i] = bb.getFloat(12 + i * 4);
        return out;
    }

    private static byte[] encode(double[] A, double[] b, double[] xs, double[] tab) {
        int size = 12 + 36 + 12 + 12 * NODES * 2;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(size)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put(MAGIC);
        bb.putInt(VER);
        bb.putInt(NODES);
        for (int i = 0; i < 9; i++) bb.putFloat((float) A[i]);
        for (int i = 0; i < 3; i++) bb.putFloat((float) b[i]);
        for (int i = 0; i < 3 * NODES; i++) bb.putFloat((float) xs[i]);
        for (int i = 0; i < 3 * NODES; i++) bb.putFloat((float) tab[i]);
        return bb.array();
    }

    /** 线性插值分位：pos = q*(n-1)（与 np.quantile 默认 linear 一致）。 */
    private static void quantiles(double[] sorted, int n, double[] out) {
        for (int i = 0; i < N_Q; i++) {
            double pos = (i / (double) (N_Q - 1)) * (n - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, n - 1);
            double f = pos - lo;
            out[i] = sorted[lo] * (1 - f) + sorted[hi] * f;
        }
    }

    private static double percentile(double[] a, int n, double p) {
        if (n <= 0) return 0;
        double[] s = Arrays.copyOf(a, n);
        Arrays.sort(s);
        double pos = p * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, n - 1);
        double f = pos - lo;
        return s[lo] * (1 - f) + s[hi] * f;
    }

    private static double[] sym(double[] c) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = 0.5 * (c[i * 3 + j] + c[j * 3 + i]);
            }
        }
        r[0] += 1e-4;
        r[4] += 1e-4;
        r[8] += 1e-4;
        return r;
    }

    /** Cholesky 下三角（入参已对称化 + 1e-4 抖动）。 */
    private static double[] chol3(double[] m) {
        double[] L = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j <= i; j++) {
                double s = m[i * 3 + j];
                for (int k = 0; k < j; k++) s -= L[i * 3 + k] * L[j * 3 + k];
                if (i == j) L[i * 3 + i] = Math.sqrt(Math.max(s, 1e-12));
                else L[i * 3 + j] = s / L[j * 3 + j];
            }
        }
        return L;
    }

    /** 下三角求逆（逐列前向替换）。 */
    private static double[] invLower3(double[] L) {
        double[] inv = new double[9];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 3; i++) {
                double s = i == c ? 1.0 : 0.0;
                for (int k = 0; k < i; k++) s -= L[i * 3 + k] * inv[k * 3 + c];
                inv[i * 3 + c] = s / L[i * 3 + i];
            }
        }
        return inv;
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

    /** 线性光 -> sRGB u8（直接公式，与 common.linear_to_srgb 一致）。 */
    private static int toU8(double lin) {
        double x = clamp(lin, 0, 1);
        double s = x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
        long v = Math.round(s * 255.0);
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

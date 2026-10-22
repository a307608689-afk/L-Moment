package com.codex.leicaapp;

import android.graphics.Bitmap;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 8bit 输出抖色（TPDF dither）——消除量化等高线（色块）。
 *
 * <p>背景：整条链的每一段终点都要把 float 结果压回 8bit。大片平坦区（天空/白墙/纯色布面）
 * 上不足 1 级的量化台阶会连成片，人眼对「长而直的等值线边界」极其敏感，于是表现为
 * 10~30px 的方块状色块。蒸馏微网把 styled 段磨得过于干净（实测 b* 高频 σ 0.07，
 * 原图 0.11），没有天然噪声可掩盖台阶，问题最明显。</p>
 *
 * <p>标准解法：量化前叠加三角分布（TPDF，两个均匀分布之和）抖动，把确定性台阶随机化成
 * 像素级细噪。这是 8bit 输出链的噪声地板，视觉上不可见（σ≈0.5 LSB），但足以打断
 * 等值线的连续性。</p>
 *
 * <p>幅度语义：ampLsb 为 TPDF 半峰幅（值域 [-amp, amp] LSB）。amp=1.0 时量化到整数后
 * 约 25% 的像素被扰动 ±1 级（σ≈0.5 LSB）；amp=1.5 时约 44%（σ≈0.6）。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>噪声表边长取 251（质数），与 JPEG 8×8 DCT 块 / 4:2:0 的 16px MCU 网格互质，
 *       即使平铺也不会形成规律性条纹。</li>
 *   <li>RGB 三通道各用独立噪声平面（不是同一张表），并对 R/B 额外加权（见 CHROMA_SCALE）：
 *       JPEG 4:2:0 的色度半平面平均实测把 b* 抖动压到亮度方向的 1/4 左右，而色块恰好
 *       出现在 b* 通道，必须单独补偿。</li>
 *   <li>按 256 行条带处理：12MP 全图一次性 getPixels 需要 50MB int[]，条带把它压到 ~4MB，
 *       避免与推理/解码的峰值内存叠加触发 OOM。</li>
 *   <li>每次调用用原子计数器错开表相位：同一条链里量化前/后各抖一次时，两次图案相互独立，
 *       不会叠加成固定花纹。</li>
 * </ul>
 */
public final class Dither {

    private Dither() {}

    /** 噪声表边长。质数，避免与 JPEG 8/16 像素块对齐产生周期性条纹。 */
    private static final int TILE = 251;
    private static final int PLANE = TILE * TILE;

    /** 条带行数（临时 int[] 上限 ≈ 256×宽×4B）。 */
    private static final int STRIP = 256;

    /**
     * 色度方向幅度加权（只放大 R/B，G 保持基准）。
     *
     * <p>实测：JPEG 4:2:0 把色度半平面平均，b* 上的抖动幅度只剩亮度方向的约 1/4
     * （合成渐变测 hp σ 0.087 vs 灰度 0.372），而投影色块恰恰出现在 b*（黄蓝）通道，
     * 故 R/B 需加权补偿。但 1.5× 在 4:4:4 全像素链上过强——平坦区彩色噪点被推到
     * 原图 2.2~2.5 倍（2026-10-21 真机对拍：Cb 0.29→0.73、Cr 0.24→0.53，且与
     * 原图色噪空间相关仅 0.046 = 纯注入）。1.1× 仍足以在 4:4:4 下压住 LUT bin
     * 色块（配合出口 ChromaClean 双保险），注入量降约 40%。</p>
     */
    private static final float CHROMA_SCALE = 1.1f;

    /** 三平面 TPDF 噪声，值域 [-1,1]（两个 [0,1) 均匀分布之和减 1）。 */
    private static final float[] NOISE;

    static {
        Random rnd = new Random(0x4C45494341L);          // 固定种子 → 输出可复现
        NOISE = new float[3 * PLANE];
        for (int i = 0; i < NOISE.length; i++) {
            NOISE[i] = rnd.nextFloat() + rnd.nextFloat() - 1f;
        }
    }

    /** 调用序号 → 表相位偏移（同链多次抖动互相独立）。 */
    private static final AtomicInteger PHASE = new AtomicInteger();

    /**
     * 对 mutable Bitmap 就地叠加 TPDF 抖动（RGB 三通道，alpha 不变）。
     *
     * @param ampLsb TPDF 半峰幅（LSB）。{@code <=0} 直接跳过；常用 1.0（轻微）~1.5（强掩蔽）。
     */
    public static void tpdf(Bitmap bmp, float ampLsb) {
        tpdf(bmp, ampLsb, 0, -1);
    }

    /**
     * 全图口径抖动（条带导出用）。
     *
     * <p>逐带调用时若沿用上面的旧口径，噪声表有两个带间不连续源：<br>
     *   1. 行索引用「带内局部 y」，相邻带各自从 0 开始，表图案在带边界错位；<br>
     *   2. 相位来自全局自增计数器，每条带每次调用都换一套图案。<br>
     * 单看不明显（σ≈0.5 LSB 的细噪），但 300 条带累积后会在每个带边界形成
     * 可测的亮度台阶（200MP 实测边界行跳变中位 0.196 / 峰 3.0 级）。</p>
     *
     * @param yBase 该位图第 0 行在整图中的行号（逐带调用传带顶），使噪声场跨带连续
     * @param phase 表相位序号（{@code >=0} 时固定；同一条链的各带必须传同一个值，
     *              不同后处理段传不同值以保持互相独立）。{@code <0} 走旧的自增口径
     */
    public static void tpdf(Bitmap bmp, float ampLsb, int yBase, int phase) {
        if (bmp == null || bmp.isRecycled() || ampLsb <= 0f) return;
        // 调用方约定传 mutable；不可变时静默跳过——绝不在这里 copy（会隐性分配 50MB）
        if (!bmp.isMutable()) return;

        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return;

        // 把 [-1,1] 的 TPDF 按当前幅度量化为整数级：表上算一次，逐像素只剩加法。
        // 三通道交错存放（idx*3+c），一个像素的三级抖动落在同一 cache line 上。
        final int[] d = new int[3 * PLANE];
        final float chroma = ampLsb * CHROMA_SCALE;
        for (int i = 0; i < PLANE; i++) {
            final int j = i * 3;
            d[j] = Math.round(NOISE[i] * chroma);                 // R
            d[j + 1] = Math.round(NOISE[PLANE + i] * ampLsb);     // G
            d[j + 2] = Math.round(NOISE[2 * PLANE + i] * chroma); // B
        }

        // 相位错开：整表二维环形平移，不改变分布（同链量化前/后各抖一次时图案独立）
        final int off = phase >= 0
                ? (phase & 0x3FFF) * 7919 % PLANE
                : (PHASE.getAndIncrement() & 0x3FFF) * 7919 % PLANE;
        final int xOff = off % TILE;
        final int yOff = (off / TILE) % TILE;
        // 行号带全图偏移（floorMod 防负数），逐带调用时噪声场在带边界处连续
        final int rowBase = Math.floorMod(yBase + yOff, TILE);

        final int[] xm = new int[w];
        for (int x = 0; x < w; x++) xm[x] = (x + xOff) % TILE;

        final int[] px = new int[w * Math.min(STRIP, h)];
        for (int y0 = 0; y0 < h; y0 += STRIP) {
            final int rows = Math.min(STRIP, h - y0);
            bmp.getPixels(px, 0, w, 0, y0, w, rows);
            int i = 0;
            for (int ry = 0; ry < rows; ry++) {
                final int base = ((rowBase + y0 + ry) % TILE) * TILE;
                for (int x = 0; x < w; x++, i++) {
                    final int j = (base + xm[x]) * 3;
                    final int a = px[i];
                    int r = ((a >>> 16) & 0xFF) + d[j];
                    int g = ((a >>> 8) & 0xFF) + d[j + 1];
                    int b = (a & 0xFF) + d[j + 2];
                    if (r < 0) r = 0; else if (r > 255) r = 255;
                    if (g < 0) g = 0; else if (g > 255) g = 255;
                    if (b < 0) b = 0; else if (b > 255) b = 255;
                    px[i] = (a & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
            bmp.setPixels(px, 0, w, 0, y0, w, rows);
        }
    }
}

package com.codex.leicaapp;

/**
 * L-P9 专用 Hooks：治"云斑显形"。
 *
 * <p>审计结论（lm9_work\37_p9audit.py）：L-P9 输出的云 vs 蓝天色度差被 LUT 饱和增益
 * 全频段放大 ×1.32~1.50（Y 结构不变），薄云在鲜艳蓝天上显形为水彩斑块。
 * 修复 = pre 域两段色度清理（LUT 放大之前先衰减）：
 *  1) chromaDenoise：≤36px 色度降噪（保留 30%）
 *  2) skyChromaMidbandClean：40~121px 天空掩码内中频衰减 65%
 * 场饱和（鲜艳深度）由低频承载，不受影响（PC 台 38_p9fix.py 验证：Cb 云纹 ×1.5→×0.8，场 sat 104 保持）。
 * 无亮度改动、无削顶风险（L-P9 链实测 B 0% 削顶）。</p>
 */
public final class P9Post implements AlexaLut.Hooks {

    private static final P9Post INSTANCE = new P9Post();

    public static P9Post get() {
        return INSTANCE;
    }

    private P9Post() {
    }

    @Override
    public void pre(int[] px, int w, int h) {
        Lm9Post.chromaDenoise(px, w, h);
        Lm9Post.skyChromaMidbandClean(px, w, h);
    }

    @Override
    public void post(int[] px, int w, int h) {
        // v2 高光安全钳：最大通道永不越 250（RAW 审计曾见 0.1~0.5% B 削顶）
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int mx = Math.max(r, Math.max(g, b));
            if (mx > 250) {
                float k = 250f / mx;
                px[i] = 0xFF000000
                        | (clamp255(r * k) << 16) | (clamp255(g * k) << 8) | clamp255(b * k);
            }
        }
    }

    private static int clamp255(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}

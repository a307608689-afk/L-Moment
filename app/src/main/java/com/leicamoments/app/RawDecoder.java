package com.leicamoments.app;

/**
 * RAW 解码（基于 LibRaw，支持 CR2/NEF/ARW/DNG/RAF 等绝大多数相机 RAW）。
 * 解码结果通过 native 返回为 ARGB 像素数组。
 */
public final class RawDecoder {

    static {
        System.loadLibrary("rawdecoder");
    }

    private RawDecoder() {
    }

    /**
     * 解码 RAW 字节。
     *
     * @return int[]：{ width, height, ARGB像素... }；解码失败返回 null
     */
    public static native int[] nativeDecode(byte[] data);

    /**
     * 带 auto_bright_thr 的解码（RAW 解码亮度档位）。
     *
     * @param autoBrightThr 取直方图最亮该比例的分位点作为白点：0.01 为 LibRaw 默认，
     *                      调大等于把白点下压、整体提亮（大光比场景更接近相册预览）
     */
    public static native int[] nativeDecodeWithThr(byte[] data, float autoBrightThr);

    /**
     * 提取 RAW 内嵌的厂商预览图（JPEG 字节，厂商 ISP 渲染，色彩最准确）。
     *
     * @return JPEG 字节；失败或无预览返回 null
     */
    public static native byte[] nativeExtractPreview(byte[] data);

    /**
     * 线性解码（OffLineM9 前段输入）：LibRaw 16bit linear RAW，相机色彩空间，等价
     * rawpy.postprocess(gamma=(1,1), output_bps=16, output_color=raw, demosaic=AHD,
     * user_wb=mul, highlight=Clip, no_auto_bright=True)。
     *
     * @param wbMul 相机通道增益 double[3]（R/G/B，G2 复用 G）
     * @param half  半尺寸去马赛克（快速预览）
     * @return direct ByteBuffer：前 8 字节为 int w、int h，随后 w*h*3 个 u16（RGB 交错，
     *         本机字节序）；失败返回 null。<b>用完必须调用 {@link #nativeFreeLinear} 释放</b>。
     */
    public static native java.nio.ByteBuffer nativeDecodeLinear(byte[] data, double[] wbMul, boolean half);

    /**
     * 线性 sRGB 解码（RAW 自适应曲线前段）：16bit 线性、sRGB、相机白平衡，
     * 关闭 LibRaw 自动亮度（提亮交给上层曲线）。
     *
     * @return direct ByteBuffer：前 8 字节为 int w、int h，随后 w*h*3 个 u16（RGB 交错，
     *         本机字节序）；失败返回 null。<b>用完必须调用 {@link #nativeFreeLinear} 释放</b>。
     */
    public static native java.nio.ByteBuffer nativeDecodeLinearSrgb(byte[] data);

    /** 释放 nativeDecodeLinear 返回的堆外缓冲。 */
    public static native void nativeFreeLinear(java.nio.ByteBuffer buffer);
}

package com.leicamoments.app

/**
 * ncnn JNI 直通层。**不要直接调用**——请走 [GpuNet]（它负责模型落盘、预热、失败闩锁）。
 *
 * 类名与包名参与 JNI 符号名（`Java_com_leicamoments_app_NcnnNet_*`），改动需同步
 * `app/src/main/cpp/NcnnNet.cpp`。
 */
internal object NcnnNet {

    /**
     * librawdecoder.so 里同时装着 RawDecoder/JpegStream/ModelColor 与 ncnn 桥。
     * 这里显式加载一次（其它 native 类各自也会加载，重复调用是幂等的）；
     * 失败只记日志不抛——GPU 是可选通道，调用方据此回退 ORT。
     */
    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("rawdecoder")
            true
        } catch (t: Throwable) {
            DiagLog.w("GPUNET", "rawdecoder 加载失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    fun isLoaded(): Boolean = loaded

    /** Vulkan 物理设备数；0 = 该机没有可用 GPU 后端。 */
    external fun nativeGpuCount(): Int

    /** 设备名/API/评分/共享显存，仅用于诊断日志。 */
    external fun nativeDeviceInfo(): String

    /**
     * @param paramPath 解包后的 .param 绝对路径
     * @param binPath   解包后的 .bin 绝对路径
     * @param cachePath .vkpipeline 绝对路径（空串 = 不启用 pipeline cache）
     * @return 句柄；0 = 失败（调用方回退 ORT）
     */
    external fun nativeCreate(
        paramPath: String,
        binPath: String,
        cachePath: String,
        threads: Int,
        useVulkan: Int,
        fp16: Int,
        fp16Arithmetic: Int
    ): Long

    /**
     * CHW float32 推理。输入必须是 direct ByteBuffer/FloatBuffer（否则 native 侧取不到地址）。
     * @return float[C*H*W]（CHW），失败返回 null
     */
    external fun nativeRun(
        handle: Long,
        inputs: Array<java.nio.Buffer>,
        shapeC: IntArray,
        shapeH: IntArray,
        shapeW: IntArray
    ): FloatArray?

    /** 用零张量按真实 tile 形状跑一次，触发 shader 编译。返回耗时 ms，失败 -1。 */
    external fun nativeWarmup(
        handle: Long,
        shapeC: IntArray,
        shapeH: IntArray,
        shapeW: IntArray
    ): Long

    /** 把 pipeline cache 落盘。返回是否成功。 */
    external fun nativeFlushCache(handle: Long): Boolean

    external fun nativeDestroy(handle: Long)

    external fun nativeLastError(): String
}

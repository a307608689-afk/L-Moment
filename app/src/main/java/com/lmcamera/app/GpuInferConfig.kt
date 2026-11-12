package com.lmcamera.app

/**
 * GPU（ncnn / Vulkan）推理通道的开关与口径，单一真源。
 *
 * 设计原则：
 *  - **默认全关**。每条链在真机对拍通过（8bit 最大差 ≤1 级、单 tile 快于 CPU 一半）之后，
 *    才把自己那一项置 true；关着的时候业务代码与改造前完全一致。
 *  - 任何一项都可以单独回退，互不影响。
 *  - [ENABLED] 是总闸：置 false 等于立刻回到纯 ORT 实现。
 *
 * 注意：这是编译期常量（与 [SuperResConfig] 同一风格），改完需要重新构建。
 * 运行期临时降级由 [GpuNet] 的失败闩锁负责，不需要改这里。
 */
internal object GpuInferConfig {

    /** 总闸。false = 任何链路都不走 GPU。 */
    const val ENABLED = true

    /** M9 完美档 / 降级重链的 ColorFix（m9fb_colorfix，29.7MB U-Net，544 分块）。 */
    const val USE_FOR_COLORFIX = true

    /** M9 完美档 / 降级重链的 StyleTrans（m9fb_styletrans_high，15.9MB）。 */
    const val USE_FOR_STYLETRANS = true

    /** 超分快速档（SPAN x2）。 */
    const val USE_FOR_SUPERRES = true

    /** M9 蒸馏微网（m9_distill）/ L-M9 微网（lm9_net）。带宽型网络，收益有限，默认不做。 */
    const val USE_FOR_DISTILL = false

    /**
     * 跨设备安全阀：每个模型在本机首次使用时做一次同块 CPU/GPU A/B，GPU 不够快就永久
     * 判给 CPU（见 GpuNet.reportProbe）。关掉它就只剩「失败即回退」这一层兜底。
     */
    const val CALIBRATE = true

    /** GPU 后端里仍留在 CPU 上的层（Resize / 数据搬运等）用的线程数。 */
    const val NUM_THREADS = 2

    /** fp16 packed + storage。Vulkan 上基本必开，否则显存占用与带宽都翻倍。 */
    const val FP16 = true

    /**
     * fp16 算术。更快，但中间累加精度下降；若对拍超差先把它关掉（默认关，
     * 因为我们的验收口径是 8bit 最大差 ≤1 级，优先保精度）。
     */
    const val FP16_ARITHMETIC = false

    /** 打点：每个模型的首块编译耗时、单块推理耗时、回退原因。 */
    const val LOG = true
}

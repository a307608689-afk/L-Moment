package com.leicamoments.app

/**
 * 超分引擎配置（A/B 开关，改常量重编译生效）
 */
object SuperResConfig {
    /** ONNX 模型 assets 文件名（int8 量化版；体积/速度均优于已移除的 fp32 版） */
    const val MODEL_ASSET = "swinir_s_x2_int8.onnx"

    /** 快速档模型（SPAN INT8，纯卷积无 mask 输入） */
    const val FAST_MODEL_ASSET = "fast_span_x2.onnx"

    /** XNNPACK 执行提供器（真机 A/B 显示疑似拖慢 2×+，默认关闭；待计时数据复核） */
    const val USE_XNNPACK = false

    /** NNAPI 执行提供器（真机实测 0% 卡死：算子编译/回退挂起，判死禁用） */
    const val USE_NNAPI = false

    /** 推理线程数（0=ORT 默认全核；ARM 大小核建议试 4） */
    const val INTRA_OP_THREADS = 4

    /** 逐 tile 耗时打点（诊断日志: SuperRes） */
    const val LOG_TILE_TIMING = true
}

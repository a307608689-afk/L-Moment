package com.leicamoments.app

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * P3：常规链终点的统一 JPEG 出口。
 *
 * 历史问题：所有出口都走 `Bitmap.compress(JPEG, 95/96)`，而它把 libjpeg 留在默认
 * 采样上（4:2:0），暗部出现色块、强边缘出现彩边 —— 与 200MP 条带链（JpegStream
 * 强制 4:4:4）口径不一致。这里统一改走 [JpegStream.encode]（4:4:4，直读 Bitmap，
 * 零 Java 堆拷贝），任何失败都静默回落到 `Bitmap.compress`，行为仍可用。
 *
 * 质量档（真机 A/B 定档，PC 台对拍验证，三张 17MP 图 ×
 * LM9/LP9/M13，基线=同一 APK 内 `--ez legacy true` 的旧 4:2:0 输出）：
 * 相对旧口径（q95/4:2:0，L-M9 是 q98/4:2:0），4:4:4 同质量体积约 +20~30% ——
 * 多道编码叠加（中间产物也是 4:4:4，末道要重新编码真实色度细节）是主因。
 * 逐档实测（最不利组合 = 双编码 + 强色度的 M13）：
 *   q95/4:4:4 +41.5% / q94 +28.9% / q93 +17.6% / q92 +8.8%，判据 ≤ +15%。
 * 而 q93 与 q92 的色度收益几乎相同（blkb8 −0.2% vs −1.9%，边缘彩边同为 +1.35×），
 * 故取 [DEFAULT_QUALITY] = 92。L-M9 单道编码，取 95（实测 +11.0~13.5%，≥ 判据内）。
 */
object JpegEnc {

    private const val TAG = "JpegEnc"

    /** 常规链出口质量（4:4:4 口径，见类注释的真机 A/B 依据）。 */
    const val DEFAULT_QUALITY = 92

    /** L-M9（Lm9Stats）出口质量：单道编码，相对其旧 q98/4:2:0 体积 +11~14%。 */
    const val LM9_QUALITY = 95

    /**
     * 仅供 A/B 的强制回落开关：true 时走旧的 `Bitmap.compress`（4:2:0），
     * 由 BatchTestActivity 的 `--ez legacy true` 置位，正常使用零影响。
     */
    @JvmStatic
    var forceLegacy420 = false

    /** 仅供 A/B 的质量覆盖：>0 时所有出口按该质量编码（BatchTest 的 `--ei q 93`）。 */
    @JvmStatic
    var qualityOverride = 0

    @JvmStatic
    fun encode(bitmap: Bitmap): ByteArray = encode(bitmap, DEFAULT_QUALITY)

    /** 4:4:4 编码；native 不可用时回落 `Bitmap.compress`（4:2:0，旧行为）。 */
    @JvmStatic
    fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val effective = if (qualityOverride > 0) qualityOverride else quality
        if (!forceLegacy420) {
            val fast = try {
                JpegStream.encode(bitmap, effective)
            } catch (t: Throwable) {
                null
            }
            if (fast != null && fast.isNotEmpty()) return fast
            Log.w(TAG, "4:4:4 encoder unavailable, falling back to Bitmap.compress q=$effective")
        }
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, effective, bos)
        return bos.toByteArray()
    }
}

package com.leicamoments.app

import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * C1 条带流式 JPEG 写入器。
 *
 * 200MP 无法整体驻留为一张 ARGB Bitmap，也无法一次性编码；本类把整幅图按
 * 「MCU 行整数倍」切成若干条带，每条带独立交给 libjpeg-turbo 编码，native 侧
 * 把各条带的熵编码数据拼成一个合法 JPEG：
 *
 *   [SOI..SOS(条带0头，SOF 高度已改为整幅，插入 DRI)] [熵0] [RST0] [熵1] ... [EOI]
 *
 * 每条带恰好是一个 restart interval，因此条带内部不产生 RST，编号在拼接处
 * 全局连续；解码器在每个 RST 重置 DC 预测，正好与独立编码的条带对齐。4:4:4
 * 采样 + 标准 Huffman 表 + 相同量化表使该方案与整图一次编码**逐比特一致**
 * （PC 端验证 MAE = 0，含 12288x16320 / 51 条带）。
 */
object JpegStream {

    private const val TAG = "JpegStream"

    init {
        System.loadLibrary("rawdecoder")
    }

    external fun nativeOpen(
        path: String,
        width: Int,
        height: Int,
        quality: Int,
        maxBandRows: Int
    ): Long

    external fun nativeBandRows(handle: Long): Int
    external fun nativeBandCount(handle: Long): Int
    external fun nativeWriteBand(handle: Long, rgba: ByteArray, rows: Int): Int

    /** 直接传直接缓冲（native 堆），省掉 jbyteArray 的两次整条带拷贝。 */
    external fun nativeWriteBandDirect(handle: Long, rgba: java.nio.ByteBuffer, rows: Int): Int
    external fun nativeClose(handle: Long): Int
    external fun nativeAbort(handle: Long)

    /**
     * 整图内存编码（强制 4:4:4，`quality` 生效）。失败返回 null。
     *
     * native 侧直接 `AndroidBitmap_lockPixels` 读 Bitmap（stride 感知，不经过
     * Java 堆），因此只接受 ARGB_8888；其它配置请走 [encode]。
     */
    external fun nativeEncodeBitmap(bitmap: Bitmap, quality: Int): ByteArray?

    /**
     * P3：整图出口编码，强制 4:4:4（`Bitmap.compress` 是 4:2:0，暗部色块/边缘彩边来源）。
     * 返回 null 表示不可用，调用方须回落到 `Bitmap.compress`。
     */
    @JvmStatic
    fun encode(bitmap: Bitmap, quality: Int): ByteArray? {
        if (bitmap.isRecycled) return null
        var temp: Bitmap? = null
        return try {
            val src = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { temp = it } ?: return null
            } else {
                bitmap
            }
            nativeEncodeBitmap(src, quality)
        } catch (t: Throwable) {
            Log.e(TAG, "native encode failed: ${t.message}")
            null
        } finally {
            temp?.recycle()
        }
    }

    /**
     * 打开一个流式写入器。[maxBandRows] 为期望的条带上限（像素行），
     * native 会在此基础上选取能整除且满足 DRI 16 位限制的条带高度。
     */
    class Writer(
        private val path: String,
        private val width: Int,
        private val height: Int,
        quality: Int,
        maxBandRows: Int = 512
    ) {
        private var handle: Long =
            nativeOpen(path, width, height, quality, maxBandRows)

        /** 复用的直接缓冲：大小 = 一条带的 RGBA，常驻 native 堆，不占 Java 堆。 */
        private var directBuf: java.nio.ByteBuffer? = null

        val isValid: Boolean get() = handle != 0L
        val bandRows: Int get() = if (handle == 0L) 0 else nativeBandRows(handle)
        val bandCount: Int get() = if (handle == 0L) 0 else nativeBandCount(handle)

        /** 写入一条带；[bitmap] 必须与整幅同宽且高度 == rows（最后一条可更少）。 */
        fun writeBand(bitmap: Bitmap, rows: Int = bitmap.height): Boolean {
            if (handle == 0L) return false
            if (bitmap.width != width || rows <= 0 || bitmap.height != rows) {
                Log.e(TAG, "band size mismatch: ${bitmap.width}x${bitmap.height} rows=$rows")
                return false
            }
            val need = width * rows * 4
            val buf: java.nio.ByteBuffer = directBuf
                ?.takeIf { it.capacity() >= need }
                ?: java.nio.ByteBuffer.allocateDirect(need).also { directBuf = it }
            buf.clear()
            // copyPixelsToBuffer copies the whole buffer including row padding,
            // so make sure the source is tightly packed for the native side.
            val src = if (bitmap.rowBytes == need) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
            }
            try {
                src.copyPixelsToBuffer(buf)
            } finally {
                if (src !== bitmap) src.recycle()
            }
            val rc = nativeWriteBandDirect(handle, buf, rows)
            if (rc != 0) Log.e(TAG, "writeBand failed rc=$rc rows=$rows")
            return rc == 0
        }

        /** 成功收尾后返回 true；失败会删除半成品文件。 */
        fun close(): Boolean {
            if (handle == 0L) return false
            val rc = nativeClose(handle)
            handle = 0L
            directBuf = null
            if (rc != 0) {
                Log.e(TAG, "close failed rc=$rc")
                runCatching { File(path).delete() }
                return false
            }
            return true
        }

        fun abort() {
            if (handle != 0L) {
                nativeAbort(handle)
                handle = 0L
            }
            directBuf = null
            runCatching { File(path).delete() }
        }
    }
}

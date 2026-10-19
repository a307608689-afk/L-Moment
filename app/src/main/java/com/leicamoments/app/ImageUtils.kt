package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ImageUtils {

    /** 从 URI 读取原始字节。 */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return input.readBytes()
        }
        throw IllegalStateException("无法读取图片")
    }

    /**
     * 解码为 Bitmap，按 [maxDim] 等比缩小。
     * 支持 JPG/PNG/WebP/HEIF/DNG（API 28+ 走 ImageDecoder，兼容部分 RAW）。
     */
    fun decode(context: Context, uri: Uri, maxDim: Int = 1600): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(context, uri, maxDim)
            } else {
                decodeWithBitmapFactory(context, uri, maxDim)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 工作字节尺寸上限：M9 系 styletrans 引擎只接受**横置**画面，硬限为 长边 ≤4096 且 短边 ≤3072。
     *
     * 为什么是长/短边而不是宽/高：引擎吃的是横置画面，而 [com.codex.leicaapp.LegendaryPostProcessor]
     * 的 applyStyleOriented 在送引擎前会把竖图旋转 -90°（竖 3072×4096 → 横 4096×3072）再转回。
     * 若此处按竖置宽高判定，竖拍图会被白白多缩一刀：
     *   · 竖 3072×4096（12.5MP 竖拍）旧口径高 4096 > 3072 → 缩到 2304×3072（丢 60% 像素）；
     *   · 横置口径长 4096 短 3072 → 合规，旋转后正好 4096×3072，零缩放零重编码。
     * 超限死法 = OpenCV copyMakeBorder 负 padding 断言，exit=134 硬 abort 无降级，故上限必须严格守住。
     */
    const val MAX_WORK_LONG = 4096
    const val MAX_WORK_SHORT = 3072

    /** 像素尺寸是否已在工作上限内（横置口径：长边/短边逐轴判定）。 */
    fun withinWorkLimit(w: Int, h: Int): Boolean =
        w >= 1 && h >= 1 && maxOf(w, h) <= MAX_WORK_LONG && minOf(w, h) <= MAX_WORK_SHORT

    /** 把 (w,h) 等比压进横置口径所需的缩放系数；已在限内返回 1f。 */
    fun workScale(w: Int, h: Int): Float {
        if (w < 1 || h < 1) return 1f
        return minOf(
            1f,
            MAX_WORK_LONG.toFloat() / maxOf(w, h),
            MAX_WORK_SHORT.toFloat() / minOf(w, h))
    }

    /** 仅读文件头拿像素尺寸（不分配像素内存），失败返回 null。这是「存储尺寸」，未考虑 EXIF 方向。 */
    fun probeJpegSize(bytes: ByteArray): IntArray? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) intArrayOf(opts.outWidth, opts.outHeight) else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * JPEG 的「方向归一后」像素尺寸（宽高已按 EXIF Orientation 互换）。
     *
     * 相机竖拍 JPG 像素是**横存**的（如 4096×3072）+ Orientation=6，[normalizeExifOrientation]
     * 会把像素转正成 3072×4096，日志与超分提示都需要这个「真实朝向」尺寸。
     * 注：工作上限已改为长/短边口径（宽高互换不变），故判定结果与存储尺寸等价，
     * 这里保留归一尺寸纯粹为了语义清晰。读取失败（无 EXIF / 非 JPEG）按原尺寸返回。
     */
    fun probeOrientedSize(bytes: ByteArray): IntArray? {
        val size = probeJpegSize(bytes) ?: return null
        return try {
            if (isSwapOrientation(readOrientation(bytes))) intArrayOf(size[1], size[0]) else size
        } catch (t: Throwable) {
            size
        }
    }

    /** EXIF Orientation 是否要求宽高互换（5/6/7/8）。 */
    fun isSwapOrientation(ori: Int): Boolean =
        ori == ExifInterface.ORIENTATION_TRANSPOSE || ori == ExifInterface.ORIENTATION_ROTATE_90 ||
            ori == ExifInterface.ORIENTATION_TRANSVERSE || ori == ExifInterface.ORIENTATION_ROTATE_270

    /** 读源容器的 EXIF Orientation（读不到按 1）。 */
    fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (t: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * 决定重编码工作字节该写什么 Orientation 值。
     *
     * 常规写 "1"：[decodeWork] 约定解码时已按 EXIF 摆正像素，沿用原值会导致查看器二次旋转。
     * 但「ImageDecoder 是否应用了 HEIF 的朝向」属实现细节——若解码结果的长宽朝向与
     * 「摆正后应有的朝向」相反，说明方向没被应用，此时必须沿用源值，否则导出横竖错。
     * 只有互换类方向（5/6/7/8）才会造成朝向差异，180° 类天然自洽。
     */
    fun resolveWorkOrientation(srcBytes: ByteArray, workW: Int, workH: Int): String {
        if (workW == workH) return "1"
        return try {
            val stored = probeJpegSize(srcBytes) ?: return "1"
            if (stored[0] == stored[1]) return "1"
            val ori = readOrientation(srcBytes)
            // 摆正之后的朝向：需互换时与存储朝向相反
            val uprightLandscape =
                if (isSwapOrientation(ori)) stored[0] < stored[1] else stored[0] > stored[1]
            if ((workW > workH) == uprightLandscape) "1" else ori.toString()
        } catch (t: Throwable) {
            "1"
        }
    }

    /**
     * 全分辨率工作位图：不做预览级降采样，仅在超过横置口径（长 ≤4096 短 ≤3072）时等比封顶。
     * 缩放在 onHeaderDecoded 里用 setTargetSize 完成——超大图（50MP/200MP 级）直接解到目标尺寸，
     * 峰值内存为目标位图本身，而非「先全解再缩」。
     * 返回位图保证两件事：①EXIF 方向已应用（像素摆正，Orientation 语义为 1）；②尺寸已在工作上限内。
     */
    fun decodeWork(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > 0 && h > 0) {
                        val s = workScale(w, h)
                        if (s < 1f) {
                            decoder.setTargetSize(
                                (w * s).toInt().coerceAtLeast(1),
                                (h * s).toInt().coerceAtLeast(1))
                        }
                    }
                }
                // info.size 在「方向归一前/后」上是实现细节，解码后再按横置口径收敛一次兜底
                clampToWorkLimit(decoded)
            } else {
                // API<28 兜底：BitmapFactory 不认 EXIF 方向，这里按方向标签摆正后再按横置口径收敛
                clampToWorkLimit(decodeOrientedWithBitmapFactory(context, uri, MAX_WORK_LONG))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 按横置口径把位图收敛到工作上限内；已在限内直接返回原对象（不复制）。 */
    private fun clampToWorkLimit(bmp: Bitmap): Bitmap {
        if (withinWorkLimit(bmp.width, bmp.height)) return bmp
        val s = workScale(bmp.width, bmp.height)
        val out = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** API<28 专用：BitmapFactory 解码（不认方向）+ 按 EXIF Orientation 把像素摆正。 */
    private fun decodeOrientedWithBitmapFactory(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bmp = decodeWithBitmapFactory(context, uri, maxDim)
        val exif = try {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } catch (t: Throwable) {
            null
        } ?: return bmp
        val ori = try {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        if (ori == ExifInterface.ORIENTATION_NORMAL || ori == ExifInterface.ORIENTATION_UNDEFINED) return bmp
        return try {
            val m = Matrix()
            m.setRotate(exif.rotationDegrees.toFloat())
            m.postScale(if (exif.isFlipped) -1f else 1f, 1f)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            out
        } catch (t: Throwable) {
            bmp
        }
    }

    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val sample = maxOf(1, maxOf(w, h) / maxDim)
            decoder.setTargetSampleSize(sample)
        }
    }

    private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("decode failed")
    }

    /** Bitmap → JPEG 字节。 */
    fun toJpeg(bitmap: Bitmap, quality: Int = 92): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    /** JPEG 字节 → Bitmap。 */
    fun fromJpeg(bytes: ByteArray): Bitmap? {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * EXIF 方向归一：Orientation≠1 的 JPEG（如 RAW 伴生 JPG 像素横存、靠 Orientation=6 竖显）
     * 按标签旋转/镜像像素，重编码后回填原 EXIF 并把 Orientation 就地改写为 1。
     * Orientation 缺省或为 1（正常拍摄 JPG）原字节原样返回，零重编码零损失；任何失败返回原字节。
     */
    fun normalizeExifOrientation(bytes: ByteArray): ByteArray {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            if (ori == ExifInterface.ORIENTATION_NORMAL
                || ori == ExifInterface.ORIENTATION_UNDEFINED) return bytes
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
            // 官方文档同款矩阵：先按 rotationDegrees 旋转，镜像再水平翻转
            val m = Matrix()
            m.setRotate(exif.rotationDegrees.toFloat())
            m.postScale(if (exif.isFlipped) -1f else 1f, 1f)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            val bos = ByteArrayOutputStream(bytes.size)
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            out.recycle()
            MadridLegendary.patchExifOrientationToOne(
                MadridLegendary.copyExif(bytes, bos.toByteArray()))
        } catch (t: Throwable) {
            bytes
        }
    }
}

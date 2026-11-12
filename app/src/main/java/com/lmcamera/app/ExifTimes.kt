package com.lmcamera.app

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出时间的 EXIF 读写工具（配合 SettingsStore 的 exportTimeMode）。
 *
 * - 读：从导出字节/文件解析「拍摄时间」（优先 DateTimeOriginal，缺则回退 DateTime），
 *   供 MediaStore `DATE_TAKEN` 使用，使相册「拍摄时间」显示与排序落到原图时间。
 * - 写（「按当下时间」模式）：把 DateTime / DateTimeOriginal / DateTimeDigitized
 *   三个 tag 重写为导出时刻——只动这三个时间 tag，机型/拍摄参数/GPS 等其余 EXIF 不受影响。
 *
 * 实现约束：androidx ExifInterface 的 `saveAttributes()` **只支持文件路径构造**
 * （流式构造调用会抛异常），故字节版走 cacheDir 临时文件中转。
 *
 * 所有失败路径（无 EXIF、解析失败、IO 异常）均安全兜底：
 * 读返回 null（调用方回退当下）、写返回输入原样（不阻塞导出主链）。
 */
object ExifTimes {

    /** EXIF 时间字段格式（冒号分隔日期是 EXIF 规范写法） */
    private const val EXIF_TIME_FMT = "yyyy:MM:dd HH:mm:ss"

    /** 解析 EXIF 时间串为 epoch 毫秒，null = 无法解析 */
    private fun parseExifTime(value: String?): Long? {
        val s = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return try {
            val fmt = SimpleDateFormat(EXIF_TIME_FMT, Locale.US)
            fmt.isLenient = false
            val pos = ParsePosition(0)
            val d: Date? = fmt.parse(s, pos)
            // 必须消费完整串（防 "2026:01:01 12:00" 之类半截串混过）
            if (d != null && pos.index == s.length) d.time else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 对文件路径上的 ExifInterface 写入三个时间 tag 并就地保存；false = 失败 */
    private fun writeNowInPlace(exif: ExifInterface): Boolean = try {
        val now = SimpleDateFormat(EXIF_TIME_FMT, Locale.US).format(Date())
        exif.setAttribute(ExifInterface.TAG_DATETIME, now)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now)
        exif.saveAttributes()
        true
    } catch (t: Throwable) {
        false
    }

    /** 从 ExifInterface 读拍摄时间，优先 DateTimeOriginal，缺则回退 DateTime */
    private fun readTakenAt(exif: ExifInterface): Long? =
        parseExifTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: parseExifTime(exif.getAttribute(ExifInterface.TAG_DATETIME))

    // ---- 字节版（MainActivity.saveNamed / SuperResTask.saveNamed 用）----

    /**
     * 从 JPEG 字节读拍摄时间（epoch 毫秒）。
     * 无 EXIF / 解析失败返回 null（调用方回退当下）。
     * ExifInterface 只扫描 JPEG 头部段，不开全图，开销极小。
     */
    fun readTakenAtEpoch(bytes: ByteArray): Long? = try {
        readTakenAt(ExifInterface(ByteArrayInputStream(bytes)))
    } catch (t: Throwable) {
        null
    }

    /**
     * 把字节内 EXIF 的三个时间 tag 重写为当前时刻。
     * 实现走 cacheDir 临时文件（流式构造不支持 saveAttributes）：
     * 无 EXIF 输入会**新建** APP1 段；任何失败原样返回输入字节，不阻塞导出主链。
     * 24MP 一次性峰值内存约几十 MB（临时文件读写 + 重序列化），可接受。
     */
    fun rewriteToNow(context: Context, bytes: ByteArray): ByteArray = try {
        val tmp = File.createTempFile("exiftimes_", ".jpg", context.cacheDir)
        try {
            tmp.writeBytes(bytes)
            val exif = ExifInterface(tmp.absolutePath)
            if (writeNowInPlace(exif)) tmp.readBytes() else bytes
        } finally {
            tmp.delete()
        }
    } catch (t: Throwable) {
        bytes
    }

    // ---- 文件版（MainActivity.saveNamedFile 走路径，免 200MP 整读进堆）----

    /** 从文件读拍摄时间（epoch 毫秒），null = 无 EXIF / 解析失败 */
    fun readTakenAtEpoch(file: File): Long? = try {
        readTakenAt(ExifInterface(file.absolutePath))
    } catch (t: Throwable) {
        null
    }

    /**
     * 就地把文件内 EXIF 三个时间 tag 重写为当前时刻（ExifInterface 按路径就地重写）。
     * 失败保持文件原样并返回 false，不阻塞导出。
     */
    fun rewriteToNowInPlace(file: File): Boolean = try {
        writeNowInPlace(ExifInterface(file.absolutePath))
    } catch (t: Throwable) {
        false
    }
}

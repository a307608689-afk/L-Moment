package com.leicamoments.app

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File

/**
 * EXIF 抄写：把源容器（HEIF 等）的 EXIF 标签逐条写进重编码后的 JPEG 工作字节。
 *
 * 为什么需要：Bitmap.compress 不写 EXIF，HEIF 导入的工作字节从生成那一刻起就没有
 * 机型/光圈/快门/ISO/日期/GPS；下游所有 copyExif 传递的源都是这串空元数据 → 导出丢拍摄参数。
 * （JPG 导入走原字节直通、天然带 EXIF；RAW 属另一档，本类只服务重编码入径。）
 *
 * 保真度：标准 IFD0 / ExifIFD / GPS IFD 全覆盖；**不含 MakerNote 与未知私有 tag**
 * （androidx ExifInterface 不暴露），IFD1 缩略图被丢弃。读取失败或任何异常一律返回原 JPEG（零副作用）。
 *
 * 实现约束（androidx exifinterface 1.3.7 实测 API）：
 * ① 没有 `getAttributes()`，无法枚举标签，故只能按下面的白名单逐项 `getAttribute`；
 * ② 只有无参 `saveAttributes()`（原地回写，要求实例由"文件路径"构造），没有 OutputStream 版本，
 *    所以先把目标字节落一个临时文件，抄完再读回来。
 */
object ExifTransplant {

    /**
     * 待抄写的 EXIF 标签白名单（仅包含 1.3.7 里真实存在的公开常量）。
     *
     * 刻意不收：Orientation（单独强制写 1）、ImageWidth/Length、PixelXDimension/PixelYDimension
     * （处理/缩放后与实际像素不符，严格查看器会显示旧尺寸）、Thumbnail 与各类 offset（指向文件内部结构）。
     */
    private val TAGS = arrayOf(
        // ---- IFD0：机型与描述 ----
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        // ---- ExifIFD：时间 ----
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // ---- ExifIFD：曝光/镜头（"拍摄参数"主体）----
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_FILE_SOURCE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_CFA_PATTERN,
        ExifInterface.TAG_SPECTRAL_SENSITIVITY,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
        // ---- ExifIFD：感光度 ----
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY,
        ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ,
        // ---- ExifIFD：机身/镜头标识 ----
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_USER_COMMENT,
        // ---- ExifIFD：版本/色彩 ----
        ExifInterface.TAG_EXIF_VERSION,
        ExifInterface.TAG_FLASHPIX_VERSION,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_COMPONENTS_CONFIGURATION,
        ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
        // ---- GPS IFD（整段保留，缩略图内不重挖）----
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
    )

    /**
     * 把 [srcBytes]（HEIF/JPEG 等 ExifInterface 可解析的容器）的 EXIF 写进 [dstJpeg]。
     *
     * @param orientation 写入的 Orientation 值。调用方经 [ImageUtils.resolveWorkOrientation]
     *   判定：像素已被摆正时写 "1"（否则查看器二次旋转），方向未被应用时沿用源值。
     * @return 带 EXIF 的新 JPEG 字节；源无 EXIF 或任何失败时返回 [dstJpeg] 原样。
     */
    fun copy(context: Context, srcBytes: ByteArray, dstJpeg: ByteArray, orientation: String = "1"): ByteArray {
        val picked = try {
            val src = ExifInterface(ByteArrayInputStream(srcBytes))
            val m = LinkedHashMap<String, String>()
            for (tag in TAGS) {
                val v = try {
                    src.getAttribute(tag)
                } catch (t: Throwable) {
                    null
                }
                if (!v.isNullOrEmpty()) m[tag] = v
            }
            m
        } catch (t: Throwable) {
            DiagLog.i("Exif", "源 EXIF 不可读（${t.javaClass.simpleName}），跳过抄写")
            return dstJpeg
        }
        if (picked.isEmpty()) {
            DiagLog.i("Exif", "源容器无可抄 EXIF 字段，跳过抄写")
            return dstJpeg
        }

        // 1.3.7 只有原地 saveAttributes()，必须落临时文件；JPEG 主图像数据是逐字节搬运，不重编码
        val tmp = File(context.cacheDir, "exif_work_${System.nanoTime()}.jpg")
        return try {
            tmp.writeBytes(dstJpeg)
            val dst = ExifInterface(tmp.absolutePath)
            for ((tag, value) in picked) dst.setAttribute(tag, value)
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            dst.saveAttributes()
            val out = tmp.readBytes()
            DiagLog.i("Exif", "抄写 ${picked.size} 项 ${dstJpeg.size}B→${out.size}B " +
                "ori=$orientation model=${picked[ExifInterface.TAG_MODEL] ?: "-"}")
            out
        } catch (t: Throwable) {
            DiagLog.w("Exif", "EXIF 抄写失败，保留原字节：${t.message}")
            dstJpeg
        } finally {
            tmp.delete()
        }
    }
}

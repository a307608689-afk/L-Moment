package com.lmcamera.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 导出文件名统一生成器（设置页「导出文件名格式」四档预设，2026-11-11）。
 *
 * 收编此前散落在 MainActivity / SuperResTask 共 7 处的硬编码文件名：
 *
 * - [FMT_LEGACY]（默认）：`LM_色彩_毫秒时间戳`，与历史版本逐字节一致（毫秒戳天然防重）；
 * - [FMT_MODEL_TIME_STYLE]：`机型_时间_色彩`（机型跟随「设备标识」设置，与 EXIF 同源）；
 * - [FMT_TIME_STYLE]：`时间_色彩`；
 * - [FMT_ORIGNAME_STYLE]：`原图名_色彩`（源图无文件名时回落 [FMT_TIME_STYLE] 行为）。
 *
 * 防重名并进各档：非 legacy 档末尾自带序号 `_2.._99`（MediaStore 同目录查询 +
 * 会话内内存账本双保险——后者覆盖 IS_PENDING 窗口内自查不到自己刚插入文件的场景）；
 * 序号耗尽或查询异常回退毫秒时间戳，宁可名字丑不可与已有文件撞名。
 *
 * 时间 token 跟随「导出时间模式」（original=拍摄时间 / now=导出当下），
 * 由调用方把 [takenAt] 算好传入（与 [ExportSave] 共用同一次 EXIF 读取）。
 * `full`/`perfect` 等模式标记由调用方经 [modeTag] 传入，固定追加在末尾。
 */
object ExportNamer {

    const val FMT_LEGACY = "legacy"
    const val FMT_MODEL_TIME_STYLE = "model_time_style"
    const val FMT_TIME_STYLE = "time_style"
    const val FMT_ORIGNAME_STYLE = "origname_style"

    /** 变长段长度上限（保证最长组合含后缀仍在 ~64 字符内，且日期/模式标记不被截断） */
    private const val CAP_MODEL = 20
    private const val CAP_STYLE = 24
    private const val CAP_ORIG = 24

    /** 序号上限：_2.._99，耗尽回退毫秒戳 */
    private const val SEQ_MAX = 99

    /** 会话内已用名账本（小写「目录|文件名」，覆盖 MediaStore 查询在 IS_PENDING 窗口的盲区） */
    private val usedNames: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // ---- 段净化 ----

    /**
     * 文件名片段净化：去掉文件系统不接受的分隔符，空白压成下划线
     * （语义与历史 MainActivity.sanitizeFileTag 一致，后者已委托到这里）。
     */
    fun sanitizeTag(raw: String?): String {
        val cleaned = (raw ?: "").trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { "custom" }
    }

    /** 去掉文件扩展名（原图名 token 用），无扩展名原样返回 */
    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun cap(s: String, max: Int): String = if (s.length <= max) s else s.take(max)

    // ---- 主入口 ----

    /**
     * 生成最终文件名（含扩展名）。
     *
     * @param format    四档格式常量之一（未知值按 [FMT_LEGACY] 处理）
     * @param styleTag  色彩段（自定义色彩=显示名、内置=styleId，由调用方定口径）
     * @param takenAt   拍摄/导出时间（epoch 毫秒；legacy 档不使用它，毫秒戳取当下）
     * @param origName  源图文件名（[FMT_ORIGNAME_STYLE] 用；null/空回落时间+色彩）
     * @param deviceModel 机型段（[FMT_MODEL_TIME_STYLE] 用；null/空回落时间+色彩）
     * @param modeTag   模式标记（"full"/"perfect"/null），固定追加在末尾
     * @param reserve   true=把生成的名字记入会话账本（正式导出）；false=纯预览（设置页示例）
     */
    fun buildName(
        context: Context,
        format: String,
        styleTag: String,
        takenAt: Long,
        origName: String? = null,
        deviceModel: String? = null,
        modeTag: String? = null,
        reserve: Boolean = true,
    ): String {
        val style = cap(sanitizeTag(styleTag), CAP_STYLE)
        val mode = modeTag?.let { sanitizeTag(it) }?.takeUnless { it.isEmpty() }
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(takenAt))

        // legacy：与历史各站逐字节一致——LM_色彩[模式]_毫秒戳
        if (format != FMT_MODEL_TIME_STYLE && format != FMT_TIME_STYLE && format != FMT_ORIGNAME_STYLE) {
            val modePart = mode?.let { "_${it}" } ?: ""
            return "LM_${style}${modePart}_${System.currentTimeMillis()}.jpg"
        }

        // 变长段按格式就位；缺料（无机型/无原图名）统一回落「时间_色彩」
        var modelSeg: String? = null
        var origSeg: String? = null
        when (format) {
            FMT_MODEL_TIME_STYLE -> {
                val m = deviceModel?.let { sanitizeTag(it) }
                if (!m.isNullOrEmpty() && m != "custom") modelSeg = cap(m, CAP_MODEL)
            }
            FMT_ORIGNAME_STYLE -> {
                val o = origName?.let { sanitizeTag(stripExtension(it)) }
                if (!o.isNullOrEmpty() && o != "custom") origSeg = cap(o, CAP_ORIG)
            }
        }

        var base = join(modelSeg, date, origSeg, style, mode)
        // 超长兜底：先砍原图段，再砍色彩段，日期/模式标记永不动
        if (base.length > 60 && origSeg != null) {
            base = join(modelSeg, date, null, style, mode)
        }
        if (base.length > 60) {
            base = join(modelSeg, date, null, cap(style, 12), mode)
        }

        val name = if (reserve) nextAvailable(context, base) else "$base.jpg"
        if (reserve) usedNames.add(ledgerKey(context, name))
        return name
    }

    /** 设置页示例：假数据渲染当前格式效果，不进账本 */
    fun previewSample(context: Context, format: String): String = try {
        buildName(
            context, format,
            styleTag = "L-M9",
            takenAt = System.currentTimeMillis(),
            origName = "IMG_20261111_1530",
            deviceModel = Build.MODEL,
            modeTag = null,
            reserve = false,
        )
    } catch (t: Throwable) {
        "LM_色彩_时间戳.jpg"
    }

    private fun join(vararg segs: String?): String =
        segs.mapNotNull { it?.trim()?.takeUnless(String::isEmpty) }
            .joinToString("_")
            .ifEmpty { "IMG" }

    // ---- 防重名 ----

    private fun ledgerKey(context: Context, displayName: String): String =
        "${SettingsStore.saveRelativePath(context).lowercase()}|$displayName"

    /**
     * 同目录撞名检测：MediaStore 查询 + 会话账本双保险。
     * 查询失败返回 null（视为未知，仅靠账本）；SDK<29 无 RELATIVE_PATH，直接视为未知
     * （老系统 MediaProvider 对同名插入会自动改名，不覆盖，风险仅是名字与预想不符）。
     */
    private fun queryDirNames(context: Context, relPath: String): Set<String>? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val names = HashSet<String>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf(relPath),
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) names.add(c.getString(idx)?.lowercase() ?: "")
            }
            names
        } catch (t: Throwable) {
            null
        }
    }

    private fun isFree(name: String, existing: Set<String>?, key: String): Boolean {
        if (name.lowercase() in usedNames) return false
        if (key in usedNames) return false
        if (existing != null && name.lowercase() in existing) return false
        return true
    }

    private fun nextAvailable(context: Context, base: String): String {
        val rel = SettingsStore.saveRelativePath(context)
        val existing = queryDirNames(context, rel)
        val first = "$base.jpg"
        if (isFree(first, existing, ledgerKey(context, first))) return first
        for (i in 2..SEQ_MAX) {
            val n = "${base}_$i.jpg"
            if (isFree(n, existing, ledgerKey(context, n))) return n
        }
        // 序号耗尽（或查询异常连账本都在漂）：回退毫秒戳，永不撞名
        return "${base}_${System.currentTimeMillis()}.jpg"
    }

    /** 测试/换号场景清理账本（当前无调用方，留作入口） */
    fun clearLedger() {
        usedNames.clear()
    }
}

/**
 * 导出入库统一出口：收编 MainActivity.saveNamed / saveNamedFile 与 SuperResTask 私有副本
 * 三份重复的 MediaStore 写入逻辑。
 *
 * takenAt 与 timeNow 由调用方上提计算（与 [ExportNamer] 共用同一次 EXIF 读取，避免双读）：
 * - timeNow=false（按原图时间）：payload 原样，DATE_TAKEN=takenAt（调用方已兜底当下）；
 * - timeNow=true（按当下）：字节版先重写 EXIF 三时间 tag、文件版就地重写，DATE_TAKEN=当下。
 */
object ExportSave {

    /** 字节版入库（常规导出/完美成片/超分自动导出） */
    fun save(context: Context, bytes: ByteArray, name: String, takenAt: Long, timeNow: Boolean): Uri? {
        return try {
            val payload = if (timeNow) ExifTimes.rewriteToNow(context, bytes) else bytes
            insertBytes(context, payload, name, takenAt)
        } catch (e: Exception) {
            DiagLog.w("Export", "入库异常：${e.message}")
            null
        }
    }

    /** 文件版入库：流式拷贝，不把整幅字节读进堆（200MP 直通导出用） */
    fun saveFile(context: Context, file: File, name: String, takenAt: Long, timeNow: Boolean): Uri? {
        return try {
            if (timeNow && !ExifTimes.rewriteToNowInPlace(file)) {
                DiagLog.w("FullRes", "EXIF 时间重写为当下失败（文件原样保留）")
            }
            insertFile(context, file, name, takenAt)
        } catch (e: Exception) {
            DiagLog.w("FullRes", "入库异常：${e.message}")
            null
        }
    }

    private fun baseValues(context: Context, name: String, takenAt: Long): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, SettingsStore.saveRelativePath(context))
            put(MediaStore.Images.Media.IS_PENDING, 1)
            // 相册「拍摄时间」显示与排序的依据（date_added/date_modified 由系统强制写为入库时刻）
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, takenAt)
        }
    }

    private fun insertBytes(context: Context, payload: ByteArray, name: String, takenAt: Long): Uri? {
        val values = baseValues(context, name, takenAt)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { os -> os.write(payload) }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }
        return uri
    }

    private fun insertFile(context: Context, file: File, name: String, takenAt: Long): Uri? {
        val values = baseValues(context, name, takenAt)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            DiagLog.w("FullRes", "MediaStore insert 返回 null（无相册权限？）")
            return null
        }
        resolver.openOutputStream(uri)?.use { os ->
            file.inputStream().use { ins -> ins.copyTo(os, 256 * 1024) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}

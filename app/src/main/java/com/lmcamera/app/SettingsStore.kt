package com.lmcamera.app

import android.content.Context
import android.os.Build
import org.json.JSONArray

/**
 * 轻量设置存储（SharedPreferences）。
 *
 * - `default_category`：all / lm / arri / ccd / custom / last
 * - `last_category`：最后一次**实际所处**的分类，切分类与冷启动确定分类后写入
 * - `coach_shown`：首次启动的分类引导气泡是否已展示
 * - `disclaimer_shown`：免责声明是否已确认（仅弹一次）
 * - `pinned_ids`：置顶项 id 列表（保序 JSON 数组，最近置顶的在最前）
 * - `pinned_cross_category`：置顶跨分类显示（默认关：置顶只出现在 L-Moment 与该色彩所属分类）
 * - `full_res_export`：全像素导出开关（C4，默认开；低端机由像素预算分档强制回落）
 * - `save_relative_path`：保存位置（MediaStore RELATIVE_PATH，默认 Pictures/L-Moment）
 * - `export_time_mode`：导出时间模式（original=按图片原本时间〔默认〕/ now=按当下时间）
 * - `raw_thr_level:<MODEL>`：RAW 解码亮度档位（按机型记忆，不进备份）
 */
object SettingsStore {

    private const val FILE = "lm_settings"
    private const val KEY_DEFAULT = "default_category"
    private const val KEY_LAST = "last_category"
    private const val KEY_COACH = "coach_shown"
    private const val KEY_DISCLAIMER = "disclaimer_shown"
    private const val KEY_PINNED = "pinned_ids"
    private const val KEY_PINNED_CROSS = "pinned_cross_category"
    private const val KEY_SHARE_SEEN = "share_seen_sig"
    private const val KEY_RAW_THR = "raw_thr_level"
    private const val KEY_RAW_HINT = "raw_hint_shown"
    private const val KEY_FULL_RES = "full_res_export"
    private const val KEY_M9_HIRES = "m9_hires_export"
    private const val KEY_DEVICE_INFO = "device_info_mode"
    private const val KEY_PSEUDO_LINEAR = "pseudo_linear_work"
    private const val KEY_HDR_BOOST = "hdr_boost_level"
    private const val KEY_HDR_HINT = "hdr_hint_shown"
    private const val KEY_HDR_DEFAULT_ON = "hdr_default_on"
    private const val KEY_SAVE_REL_PATH = "save_relative_path"
    private const val KEY_EXPORT_TIME_MODE = "export_time_mode"
    private const val KEY_GPU_ACCEL = "gpu_accel"

    /** 「记忆上次」在 default_category 里的取值 */
    const val VALUE_LAST = "last"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 设置页当前选择（可能是 [VALUE_LAST]），用于回显 */
    fun defaultSetting(c: Context): String =
        prefs(c).getString(KEY_DEFAULT, null) ?: FilterCategory.ALL.id

    fun setDefaultSetting(c: Context, value: String) {
        prefs(c).edit().putString(KEY_DEFAULT, value).apply()
    }

    fun lastCategory(c: Context): FilterCategory? =
        FilterCategory.fromId(prefs(c).getString(KEY_LAST, null))

    fun setLastCategory(c: Context, category: FilterCategory) {
        prefs(c).edit().putString(KEY_LAST, category.id).apply()
    }

    /** 冷启动进入的分类：固定值直接用，「记忆上次」取 last，无记录回落 L-Moment */
    fun startCategory(c: Context): FilterCategory {
        val def = defaultSetting(c)
        if (def == VALUE_LAST) return lastCategory(c) ?: FilterCategory.ALL
        return FilterCategory.fromId(def) ?: FilterCategory.ALL
    }

    fun isCoachShown(c: Context): Boolean = prefs(c).getBoolean(KEY_COACH, false)

    fun markCoachShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_COACH, true).apply()
    }

    // ---- 免责声明（仅弹一次）----

    fun isDisclaimerShown(c: Context): Boolean = prefs(c).getBoolean(KEY_DISCLAIMER, false)

    fun markDisclaimerShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_DISCLAIMER, true).apply()
    }

    // ---- 置顶（内置与自定义共用一套 id 空间）----

    /**
     * 置顶项 id，最近置顶的在最前。
     * 必须用有序数组：StringSet 无序，多置顶时顺序会在重启后乱掉。
     */
    fun pinnedIds(c: Context): List<String> {
        val raw = prefs(c).getString(KEY_PINNED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            ArrayList<String>(arr.length()).apply {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun setPinnedIds(c: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs(c).edit().putString(KEY_PINNED, arr.toString()).apply()
    }

    fun isPinned(c: Context, id: String): Boolean = pinnedIds(c).contains(id)

    /** 切换置顶状态，返回切换后是否处于置顶。 */
    fun togglePinned(c: Context, id: String): Boolean {
        val cur = pinnedIds(c)
        return if (cur.contains(id)) {
            setPinnedIds(c, cur.filterNot { it == id })
            false
        } else {
            setPinnedIds(c, listOf(id) + cur)
            true
        }
    }

    /** 删除自定义色彩时同步清掉它的置顶标记 */
    fun removePinned(c: Context, id: String) {
        val cur = pinnedIds(c)
        if (cur.contains(id)) setPinnedIds(c, cur.filterNot { it == id })
    }

    /**
     * 置顶跨分类显示（2026-10-22 起**默认关**）。
     *
     * 关 = 新口径：置顶区只出现在 L-Moment 与该色彩**所属**的分类里
     * （内置色彩查 [FilterCategory.styles] 归属；自定义/模型色彩只在「我的色彩」）；
     * 开 = 旧口径：置顶区不参与分类过滤，任何分类下都排最前。
     *
     * 只影响滤镜条显示，置顶记录（[pinnedIds]）本身不随开关变化。
     */
    fun pinnedCrossCategory(c: Context): Boolean = prefs(c).getBoolean(KEY_PINNED_CROSS, false)

    fun setPinnedCrossCategory(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_PINNED_CROSS, enabled).apply()
    }

    // ---- 分享指令 ----

    /**
     * 最近一次已导入（或自己刚复制、或已损坏提示过）的指令指纹。
     *
     * 剪贴板内容会长期留着，没有这条记录的话每次切回前台都会弹一次导入确认。
     */
    fun shareSeenSignature(c: Context): String? = prefs(c).getString(KEY_SHARE_SEEN, null)

    fun markShareSeen(c: Context, sig: String) {
        prefs(c).edit().putString(KEY_SHARE_SEEN, sig).apply()
    }

    // ---- RAW 解码亮度（按机型记忆）----

    /**
     * 0 = 标准（auto_bright_thr 0.01，与旧版本一致）
     * 1 = 提亮（0.03）——默认档（2026-10-22 改定，旧版默认 0）
     * 2 = 强力提亮（0.04）
     *
     * key 带机型：同一台机器的观感偏好换机后不沿用；也**不进 .lmc 备份**，
     * 因为该值与机型的 RAW 高光余量、预览合成策略强相关，跨机恢复只会帮倒忙。
     */
    fun rawThrLevel(c: Context): Int = prefs(c).getInt(rawThrKey(), 1).coerceIn(0, 2)

    fun setRawThrLevel(c: Context, level: Int) {
        prefs(c).edit().putInt(rawThrKey(), level.coerceIn(0, 2)).apply()
    }

    /** 档位 → LibRaw 的 auto_bright_thr */
    fun rawThr(c: Context): Float = when (rawThrLevel(c)) {
        1 -> 0.03f
        2 -> 0.04f
        else -> 0.01f
    }

    private fun rawThrKey(): String {
        val model = (Build.MODEL ?: "").ifBlank { "unknown" }
        return "$KEY_RAW_THR:$model"
    }

    /** 首次导入 RAW 的提示是否已展示（全局一次，与机型无关） */
    fun isRawHintShown(c: Context): Boolean = prefs(c).getBoolean(KEY_RAW_HINT, false)

    fun markRawHintShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_RAW_HINT, true).apply()
    }

    /**
     * HDR 说明弹窗是否已展示过（全局一次，跨重启有效）。
     *
     * 语义是「**展示即标记**」：无论用户点开启还是取消，只要弹窗出现过就不再重复，
     * 与 [isRawHintShown] / 引导页同款（不区分用户是否确认）。
     * 之后点击 HDR 按钮直接进入开启态，不再被长文案拦一次。
     */
    fun isHdrHintShown(c: Context): Boolean = prefs(c).getBoolean(KEY_HDR_HINT, false)

    fun markHdrHintShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_HDR_HINT, true).apply()
    }

    /**
     * HDR 开关的**记忆值**（跨重启 + 换图都保持），默认关（零回归）。
     *
     * 语义边界：这是「用户上次手动选择的状态」，只在用户点按钮时写入。
     * 导入一张**自带 HDR 增益图**的图时不得写它（那类图按钮本就隐藏、本次强制关闭），
     * 否则「开过一次 → 导一张徕卡直出 → 再导普通图」就会把用户的「开」冲掉。
     */
    fun hdrDefaultOn(c: Context): Boolean = prefs(c).getBoolean(KEY_HDR_DEFAULT_ON, false)

    fun setHdrDefaultOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_HDR_DEFAULT_ON, on).apply()
    }

    // ---- 全像素导出（C4）----

    /**
     * 全像素导出开关。
     *
     * **默认开**：C1 已验收的行为就是「200MP 原图档点导出即全像素」，默认关会让这条路径
     * 悄悄退回 4096×3072，属于功能倒退。开关的语义是「我宁可快一点、不要 8~20 秒」，
     * 而不是「要不要启用这个功能」。
     *
     * 真正的安全阀在 [ImageUtils.decideFullRes] 的低端回落：总内存 <6GB 时即使这里为 true 也不启用。
     */
    fun fullResEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_FULL_RES, true)

    fun setFullResEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_FULL_RES, enabled).apply()
    }

    /**
     * M9 / M9D 超高分辨率档（8192 长边 = 50MP 级，实验）。
     *
     * 默认**关**：8192 档的 native 暖调在 50MP 输入上的稳定性尚未量产验证，
     * 默认开等于把未知风险推给所有用户。开启后 50MP 源零降采样、200MP 出 50MP，
     * 失败自动回落 24MP 档（5632）。
     *
     * 依赖：[fullResEnabled] 为 true 才可选（设置页对 UI 做灰态）；
     * 低端机即使这里为 true 也会被 [ImageUtils.lowEndReason] 的回落拦住。
     */
    fun m9HiResEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_M9_HIRES, false)

    fun setM9HiResEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_M9_HIRES, enabled).apply()
    }

    // ---- 徽章 ----

    /**
     * 徽章开关：**恒为关**（历史上有开关与设置项，现统一收敛为无徽章导出）。
     *
     * 关 = 不写徽章，并剥离源图自带的徽章元数据
     * （含 15 Ultra 徕卡模式直出 JPG 原生携带的那份），导出即「无徽章」普通照片。
     * 复合容器清理（XMP/MPF）与 EXIF 保留（机型/拍摄参数/GPS）不随开关变化。
     *
     * 保留函数签名以复用既有导出站调用点（MainActivity / SuperResTask）；
     * 不再读 prefs —— 历史持久化的「开」值天然失效。
     */
    fun badgeEnabled(c: Context): Boolean = false

    // ---- 导出设备标识（机型字段来源）----

    const val DEVICE_INFO_SOURCE = "source"
    const val DEVICE_INFO_LOCAL = "local"

    /**
     * 导出写入的机型标识来源（**默认跟随原图**）。
     *
     * - `source`：沿用导入图 EXIF 的真实 Make/Model；
     * - `local`：写当前手机型号。
     *
     * 历史值 `fixed`（固定写 Xiaomi 15 Ultra）已下架，读取时归一到 `source`。
     * 只影响 FFE4/0x889E 里的展示型设备字段，拍摄参数与 GPS 不受影响。
     */
    fun deviceInfoMode(c: Context): String =
        when (prefs(c).getString(KEY_DEVICE_INFO, null)) {
            DEVICE_INFO_LOCAL -> DEVICE_INFO_LOCAL
            else -> DEVICE_INFO_SOURCE
        }

    fun setDeviceInfoMode(c: Context, value: String) {
        prefs(c).edit().putString(KEY_DEVICE_INFO, value).apply()
    }

    /** 映射到 [com.codex.lmapp.ExportMark] 的写入档位。 */
    fun deviceInfoModeCode(c: Context): Int = when (deviceInfoMode(c)) {
        DEVICE_INFO_LOCAL -> com.codex.lmapp.ExportMark.MODE_LOCAL
        else -> com.codex.lmapp.ExportMark.MODE_SOURCE
    }

    // ---- 高位深工作域（伪 RAW，JPG/HEIF）----

    /**
     * 高位深工作域开关（**默认关**，零回归）。
     *
     * 开 = 非 RAW 输入导入时建立 16-bit 线性基准，七档调节在 16 位域运算（预览与导出同源）；
     * 关 = 完全维持既有 8-bit 链路。
     *
     * 语义边界：
     * - 只是「允许」，实际仅在七档/柔光非零时启用；全零仍走原链路（JPG 保持原字节直通）。
     * - 只影响**之后导入**的图；导入时快照，导出途中改设置不影响当前图。
     * - 不影响真 RAW（它本身就有 16-bit 线性解码，走 [rawThrLevel]）。
     * - 不影响 EXIF：机型/拍摄参数/GPS 由各重编码站的 copyExif 保证，与开关无关。
     */
    fun pseudoLinearEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_PSEUDO_LINEAR, false)

    fun setPseudoLinearEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_PSEUDO_LINEAR, enabled).apply()
    }

    /**
     * HDR 输出强度（只影响导出时合成增益图的幅度）：
     * 0=轻 1.5EV（默认）/ 1=标准 2.0EV / 2=强 2.5EV。
     *
     * 2026-10-28 用户拍板默认改「轻」：默认观感更克制、更不易察觉。
     * 迁移语义：SharedPreferences 仅在键不存在时返回默认值，故 V1/V2 期间手动选过档位的
     * 用户继续读其存储值，只有从未操作过的用户落到「轻」，无需迁移代码。
     *
     * 与「徕卡徽章」开关正交：徽章管元数据，这里管增益图幅度；
     * 两者都不改变主图像素与不支持 HDR 设备的显示。
     */
    fun hdrBoostLevel(c: Context): Int = prefs(c).getInt(KEY_HDR_BOOST, 0)

    fun setHdrBoostLevel(c: Context, level: Int) {
        prefs(c).edit().putInt(KEY_HDR_BOOST, level.coerceIn(0, 2)).apply()
    }

    /** 档位显示名（主界面弹窗与设置页共用，避免两处各写一份 when 后漂移） */
    fun hdrBoostLevelName(level: Int): String = when (level) {
        0 -> "轻"
        2 -> "强"
        else -> "标准"
    }

    /**
     * RAW 的档位是**强度系数**而非绝对上限：真实增益由线性数据决定
     * （大光比场景可能到 3~4EV），档位只整体缩放，既保持用户可控、
     * 又不靠人为上限压制真实动态范围。
     */
    fun rawHdrStrength(level: Int): Float = when (level) {
        0 -> 0.6f
        2 -> 1.4f
        else -> 1.0f
    }

    // ---- 保存位置（MediaStore RELATIVE_PATH）----

    /** 默认保存位置（硬编码路径） */
    const val DEFAULT_SAVE_REL_PATH = "Pictures/L-Moment"

    /**
     * 保存位置（MediaStore `RELATIVE_PATH`，**默认 `Pictures/L-Moment`**）。
     *
     * 合法形态（图片类媒体集合目录约束）：顶层必须为 `Pictures` 或 `DCIM`，
     * 之后跟任意一层或多层子目录（系统自动创建）：
     * - `Pictures` / `DCIM` / `DCIM/Camera`（预设档，可直达集合根）
     * - `Pictures/<自定义名>`（自定义档，UI 输入子目录名后拼前缀）
     * - 也接受 `Pictures/`、`DCIM/` 开头的完整写法
     *
     * 读取时同样校验：历史值若不合法（如旧版本写坏）回落 [DEFAULT_SAVE_REL_PATH]。
     */
    fun saveRelativePath(c: Context): String =
        sanitizeRelPath(prefs(c).getString(KEY_SAVE_REL_PATH, null)) ?: DEFAULT_SAVE_REL_PATH

    /** 非法输入拒绝写（保留原值），由 UI 层 toast 提示 */
    fun setSaveRelativePath(c: Context, path: String): Boolean {
        val clean = sanitizeRelPath(path) ?: return false
        prefs(c).edit().putString(KEY_SAVE_REL_PATH, clean).apply()
        return true
    }

    /** 恢复默认保存位置 */
    fun resetSaveRelativePath(c: Context) {
        prefs(c).edit().putString(KEY_SAVE_REL_PATH, DEFAULT_SAVE_REL_PATH).apply()
    }

    /**
     * 保存位置清洗与校验：
     * 1) trim、去首尾 `/`、合并连续 `/`；
     * 2) 拒绝空串、`..` 段与路径穿越；
     * 3) 拒绝非法文件名字符（`\ : * ? " < > |` 与控制符）；
     * 4) 顶层必须是 `Pictures` 或 `DCIM`（大小写不敏感，统一转首字母大写）。
     *
     * 校验通过返回规范化路径（如 `Pictures/我的徕卡`），否则返回 null。
     */
    fun sanitizeRelPath(raw: String?): String? {
        var s = (raw ?: "").trim().replace('\\', '/').trim('/')
        if (s.isEmpty()) return null
        // 合并连续斜杠
        while (s.contains("//")) s = s.replace("//", "/")
        val segments = s.split('/')
        if (segments.any { it == "." || it == ".." }) return null
        if (segments.any { it.isEmpty() }) return null
        if (segments.any { seg ->
                seg.any { it.code < 0x20 || "\\:*?\"<>|".contains(it) }
            }) return null
        val top = segments[0].lowercase()
        val normTop = when (top) {
            "pictures" -> "Pictures"
            "dcim" -> "DCIM"
            else -> return null
        }
        return (listOf(normTop) + segments.drop(1)).joinToString("/")
    }

    // ---- 导出时间 ----

    /** 导出时间模式 key 取值：原图时间 / 当下时间 */
    const val EXPORT_TIME_ORIGINAL = "original"
    const val EXPORT_TIME_NOW = "now"

    /**
     * 导出时间模式（**默认按图片原本时间**）。
     *
     * - `original`：EXIF 拍摄时间保持现状（copyExif 被动保留），并把源图
     *   `DateTimeOriginal` 写入 MediaStore `DATE_TAKEN`，相册「拍摄时间」
     *   显示与排序落到原图时间；源图无时间信息回退当下。
     * - `now`：重写 EXIF `DateTime`/`DateTimeOriginal`/`DateTimeDigitized`
     *   三个 tag 为导出时刻，`DATE_TAKEN` = 导出时刻，效果如刚拍的照片。
     *
     * 边界（设置页 hint 同步言明）：文件属性时间（date_added/date_modified）
     * 由系统强制写为入库时刻、应用不可改；已导出的历史文件不受影响；
     * GPS/机型/拍摄参数 EXIF 不受影响。
     */
    fun exportTimeMode(c: Context): String =
        prefs(c).getString(KEY_EXPORT_TIME_MODE, EXPORT_TIME_ORIGINAL) ?: EXPORT_TIME_ORIGINAL

    /** true = 按图片原本时间（默认） */
    fun exportTimeOriginal(c: Context): Boolean = exportTimeMode(c) == EXPORT_TIME_ORIGINAL

    fun setExportTimeMode(c: Context, mode: String) {
        val v = if (mode == EXPORT_TIME_NOW) EXPORT_TIME_NOW else EXPORT_TIME_ORIGINAL
        prefs(c).edit().putString(KEY_EXPORT_TIME_MODE, v).apply()
    }

    fun setExportTimeOriginal(c: Context, original: Boolean) {
        setExportTimeMode(c, if (original) EXPORT_TIME_ORIGINAL else EXPORT_TIME_NOW)
    }

    // ---- 导出文件名格式 ----

    private const val KEY_EXPORT_NAME_FORMAT = "export_name_format"

    /**
     * 导出文件名四档预设（档位常量与起名逻辑在 [ExportNamer]）：
     * legacy=LM_色彩_时间戳（默认，与历史逐字节一致）；
     * model_time_style=机型_时间_色彩；time_style=时间_色彩；origname_style=原图名_色彩。
     * 历史值/非法值一律回落 legacy（照 deviceInfoMode 范式，读侧归一）。
     */
    fun exportNameFormat(c: Context): String =
        when (prefs(c).getString(KEY_EXPORT_NAME_FORMAT, null)) {
            ExportNamer.FMT_MODEL_TIME_STYLE -> ExportNamer.FMT_MODEL_TIME_STYLE
            ExportNamer.FMT_TIME_STYLE -> ExportNamer.FMT_TIME_STYLE
            ExportNamer.FMT_ORIGNAME_STYLE -> ExportNamer.FMT_ORIGNAME_STYLE
            else -> ExportNamer.FMT_LEGACY
        }

    fun setExportNameFormat(c: Context, value: String) {
        prefs(c).edit().putString(KEY_EXPORT_NAME_FORMAT, value).apply()
    }

    /** 档位显示名（设置页弹窗与副标题共用，避免两处各写一份 when 后漂移） */
    fun exportNameFormatLabel(format: String): String = when (format) {
        ExportNamer.FMT_MODEL_TIME_STYLE -> "机型 · 时间 · 色彩"
        ExportNamer.FMT_TIME_STYLE -> "时间 · 色彩"
        ExportNamer.FMT_ORIGNAME_STYLE -> "原图名 · 色彩"
        else -> "默认（LM · 色彩 · 时间戳）"
    }

    // ---- GPU 加速（实验，默认关）----

    /**
     * GPU（ncnn/Vulkan）加速开关，**默认关 = 零回归**。
     *
     * 关：不建会话、不加载模型、不初始化 Vulkan、不占显存，导出路径与改造前完全一致。
     * 开：只有完美档（风格迁移 + 调色修正）与超分快速档走 GPU，其余链路不受影响。
     *
     * 另有两条自动关闭路径（由 [GpuNet] 写入，用户可见）：
     * - 首次使用的同块 A/B 判定「GPU 不比 CPU 快 20%」；
     * - 某模型连续两次推理失败。
     */
    fun gpuAccelEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_GPU_ACCEL, false)

    fun setGpuAccelEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_GPU_ACCEL, enabled).apply()
    }
}

package com.leicamoments.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一张参考图的风格指纹（约 2 KB）。参考图原图不保存，只留统计量。
 *
 * v2 数组尺寸（v1 旧数据是 16/72/24，读取时由 [resampledForV2] 重采样）：
 */
data class RefSeed(
    val addedAt: Long,
    val wbGain: FloatArray,       // 3    白平衡增益（v2 恒 1）
    val toneCurve: FloatArray,    // 33   V 曲线控制点
    val colorMatrix: FloatArray,  // 9    v2：三段分色调（阴影/中间/高光 × RGB）
    val satByHue: FloatArray,     // 144  48 色相 × 3 饱和档增益
    val hueShift: FloatArray,     // 48   色相旋转（度）
    val hueShare: FloatArray,     // 72   色相像素占比（聚合权重）；v2 前两位是饱和-亮度耦合
    val base: FloatArray,         // 4    satBase / contrast / gamma / blackLift
    /**
     * 联合残差网格（约 18 KB，[CustomLutBaker.RES_SIZE] 长度，每 4 个 float 一格：
     * dV / dH(度) / dS(比例) / 置信度 w）。
     *
     * 这是「伪原片直接建表」的测量证据：参数骨架表达不了的交叉项误差留在这里，
     * 烘焙时按覆盖度叠加。空数组 = 旧存档或未测量，完全退回参数骨架（向后兼容）。
     * 分享码协议是固定 200 float 的旧格式，[compactedForShare] 会清空本字段。
     */
    val meas: FloatArray = FloatArray(0)
) {
    /**
     * 64 位感知哈希（9×8 灰度梯度），M4 起用于「同一张图」去重与断点续跑。
     * 0 = 旧数据/未知（不参与去重，避免把两张老图误判成同一张）。
     */
    var fingerprint: Long = 0L
}

/**
 * 把任意来源的 [RefSeed]（旧存档 24×3 桶 / 16 点曲线、旧分享码）重采样到当前 v2 常量。
 *
 * v2 扩容后桶数与曲线点数都变了：旧 seed 若直接按新下标读取，`satByHue` 后半段会
 * 全部落到默认值 1f —— 等于静默丢掉一半饱和信息。统一在聚合入口重采样一次；
 * 尺寸已匹配时原样返回（零拷贝）。
 */
internal fun RefSeed.resampledForV2(): RefSeed {
    val hueDst = CustomLutBaker.HUE_BINS_V2
    val curveDst = CustomLutBaker.CURVE_POINTS_V2
    val bands = CustomLutBaker.SAT_BANDS
    val srcBins = if (bands > 0) satByHue.size / bands else 0
    if (srcBins == hueDst && hueShift.size == hueDst && toneCurve.size == curveDst) return this
    return RefSeed(
        addedAt = addedAt,
        wbGain = wbGain,
        toneCurve = resampleCurve(toneCurve, curveDst),
        colorMatrix = colorMatrix,
        satByHue = if (srcBins == hueDst) satByHue else resampleSatHue(satByHue, srcBins, hueDst),
        hueShift = if (hueShift.size == hueDst) hueShift else resampleHue(hueShift, hueDst),
        hueShare = hueShare,
        base = base,
        // 残差网格与新常量强绑定（下标由 HUE_BINS_V2/SAT_BANDS 决定），
        // 尺寸不符说明是别的版本产的，宁可丢弃回骨架也不要错位叠加。
        meas = if (meas.size == CustomLutBaker.RES_SIZE) meas else FloatArray(0)
    ).also { it.fingerprint = fingerprint }
}

/**
 * 分享码里的 seed 载荷是固定 200 float 的旧协议（3+16+9+72+24+72+4）。
 * 新版 seed（48 桶 / 33 点）写码前先降采样回旧尺寸，保证字节布局不变。
 */
internal fun RefSeed.compactedForShare(): RefSeed {
    val bands = CustomLutBaker.SAT_BANDS
    val srcBins = if (bands > 0) satByHue.size / bands else 0
    if (toneCurve.size == 16 && srcBins == 24 && hueShift.size == 24) return this
    return RefSeed(
        addedAt = addedAt,
        wbGain = wbGain,
        toneCurve = resampleCurve(toneCurve, 16),
        colorMatrix = colorMatrix,
        satByHue = resampleSatHue(satByHue, srcBins, 24),
        hueShift = resampleHue(hueShift, 24),
        hueShare = hueShare,
        base = base
    )
}

/** 曲线重采样（两端锁死，内部线性插值）。 */
internal fun resampleCurve(src: FloatArray, dst: Int): FloatArray {
    if (src.size == dst) return src
    if (src.size < 2) return FloatArray(dst) { if (src.isEmpty()) it / (dst - 1f) else src[0] }
    val out = FloatArray(dst)
    val last = src.size - 1
    for (k in 0 until dst) {
        val pos = k / (dst - 1f) * last
        val i = pos.toInt().coerceIn(0, last - 1)
        val f = pos - i
        out[k] = src[i] + (src[i + 1] - src[i]) * f
    }
    return out
}

/** 分色相 × 饱和档增益重采样（色相维环绕插值，饱和档不变）。 */
internal fun resampleSatHue(src: FloatArray, srcBins: Int, dstBins: Int): FloatArray {
    val bands = CustomLutBaker.SAT_BANDS
    val out = FloatArray(dstBins * bands) { 1f }
    if (srcBins <= 0) return out
    for (b in 0 until dstBins) {
        val pos = (b + 0.5f) / dstBins * srcBins - 0.5f
        val lo = kotlin.math.floor(pos).toInt()
        val f = pos - lo
        val i0 = ((lo % srcBins) + srcBins) % srcBins
        val i1 = (((lo + 1) % srcBins) + srcBins) % srcBins
        for (band in 0 until bands) {
            val v0 = src[(i0 * bands + band).coerceIn(0, src.size - 1)]
            val v1 = src[(i1 * bands + band).coerceIn(0, src.size - 1)]
            out[b * bands + band] = v0 + (v1 - v0) * f
        }
    }
    return out
}

/** 单值色相序列（旋转量）重采样，同样环绕插值。 */
internal fun resampleHue(src: FloatArray, dstBins: Int): FloatArray {
    val out = FloatArray(dstBins)
    if (src.isEmpty()) return out
    val srcBins = src.size
    for (b in 0 until dstBins) {
        val pos = (b + 0.5f) / dstBins * srcBins - 0.5f
        val lo = kotlin.math.floor(pos).toInt()
        val f = pos - lo
        val i0 = ((lo % srcBins) + srcBins) % srcBins
        val i1 = (((lo + 1) % srcBins) + srcBins) % srcBins
        out[b] = src[i0] + (src[i1] - src[i0]) * f
    }
    return out
}

enum class VersionSource { INITIAL, ENHANCE, SPLIT }

/**
 * 自定义色彩的两条产品线。
 *
 * [QUICK] = 「我的色彩」（1~10 张快速生成，分类列表内）；
 * [MODEL] = 「模型色彩」（大量参考图蒸馏，独立界面管理；列表里只出现在
 * L-Moment 与「我的色彩」两个分类，且按创建时间正常排序，不自动置顶）。
 *
 * 旧存档没有该字段时按 [QUICK] 读，保证零迁移。
 */
enum class StyleKind { QUICK, MODEL }

/**
 * 自定义色彩的一个版本快照；[seedCount] 指向 seeds 池的前缀长度。
 *
 * [strength] 只用于历史面板展示（`强度 70%`）：应用时强度已经烘进 LUT bin，
 * 运行时仍按单 LUT 查表，不再读取该字段。
 *
 * [thetaFile] 是聚合快照（θ）的文件名，M5「游标懒烘」的原料：
 * LUT 文件会被 LRU 回收，需要时按 θ 重烘，结果与当初一致。
 * 空串 = 没有快照（旧存档 / 导入包），只能按池前缀重聚合（近似）。
 *
 * [paramFile] 是 A 路线参数（LMA6 v2，1596 B）的文件名，和 LUT 互斥：
 * - 非空 → 该版本走 [com.codex.leicaapp.StyleA]，[lutFile] 为空；
 * - 空串 → 旧版本，仍按 [lutFile] 查表。
 * 参数文件不会被 LRU 回收（体积可忽略），所以不需要 θ 快照。
 */
data class StyleVersion(
    val id: String,
    val createdAt: Long,
    val label: String,
    val lutFile: String,
    val seedCount: Int,
    val source: VersionSource,
    val strength: Float = 1f,
    val thetaFile: String = "",
    val paramFile: String = ""
)

/**
 * 自定义色彩本体：seeds 池只增不删，versions 用 seedCount 记录前缀。
 *
 * M3 起池本体不再常驻：`[seedsLoaded]=false` 表示 [seeds] 还没从
 * `filesDir/seeds/<id>.json` 读进来，此时**只能**读 [seedCount]（UI 展示、上限判断），
 * 要拿池子做烘焙/导出必须先 `CustomStyleStore.ensureSeeds(def)`。
 *
 * 两个字段都有默认值：构造时传了非空 [seeds] 就自动视为「已加载」，
 * 新建色彩/导入备份这类代码不用额外标记，也就不会把池子写空。
 */
data class CustomStyleDef(
    val id: String,
    var name: String,
    val createdAt: Long,
    val seeds: MutableList<RefSeed> = mutableListOf(),
    val versions: MutableList<StyleVersion> = mutableListOf(),
    var currentVersionId: String = "",
    /** 产品线；旧存档缺省 QUICK（见 [StyleKind]） */
    val kind: StyleKind = StyleKind.QUICK,
    /** 池里一共多少张（未加载时唯一可信值）；加载后与 `seeds.size` 同源 */
    var seedCount: Int = seeds.size,
    /** [seeds] 是否已在内存 */
    var seedsLoaded: Boolean = seeds.isNotEmpty()
)

/**
 * 自定义色彩的 JSON 持久化（filesDir/custom_styles.json）。
 *
 * 进程内常驻一份内存副本：滤镜条每次重建都要读名称，不能每次都碰磁盘。
 * 使用前必须 [init] 一次（`MainActivity.onCreate`）。
 *
 * 结构（schema 2，M3 起指纹池外置到 [SeedsStore]）：
 * ```
 * { "schema":2, "styles":[ { id,name,createdAt,currentVersionId,kind,seedCount,
 *     versions:[{id,createdAt,label,lutFile,seedCount,source}] } ] }
 * ```
 * schema 1（池内嵌在 `seeds:[]`）在 [init] 时自动迁移：先把池写进
 * `filesDir/seeds/<id>.json`，成功后才按 schema 2 重写主文件。
 */
object CustomStyleStore {

    private const val FILE_NAME = "custom_styles.json"
    private const val SCHEMA = 2

    private val styles = LinkedHashMap<String, CustomStyleDef>()
    private var loaded = false

    /** 供懒加载用的 application context（[init] 时存入） */
    private var appCtx: Context? = null

    /**
     * 内容代次：每次增删改自增。
     *
     * 设置页导入备份后，MainActivity 还在栈下不会重建滤镜条，靠它对比决定是否重建。
     */
    @Volatile
    var revision = 0
        private set

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        var migrated = false
        try {
            val f = File(ctx.applicationContext.filesDir, FILE_NAME)
            if (!f.isFile) return
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("styles") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val def = parseStyle(o) ?: continue
                // schema 1：池还内嵌在主文件里 → 搬到独立文件。写失败就保持内嵌，
                // 下次 save 会再试；绝不能先删内嵌再写，否则一次 IO 抖动就是永久丢池。
                if (o.has("seeds") && def.seedsLoaded) {
                    if (SeedsStore.save(ctx, def.id, def.seeds)) {
                        migrated = true
                    } else {
                        DiagLog.w("Store", "seeds 迁移失败，仍保留内嵌：${def.id}")
                    }
                }
                styles[def.id] = def
            }
        } catch (t: Throwable) {
            // 损坏的存档不能拖垮启动：保持空列表，下次写入自然覆盖
            styles.clear()
            return
        }
        if (migrated) save(ctx)
    }

    fun all(): List<CustomStyleDef> = styles.values.toList()

    fun count(): Int = styles.size

    /** 按产品线取（「我的色彩」列表与数量统计都只看 QUICK） */
    fun allOf(kind: StyleKind): List<CustomStyleDef> = styles.values.filter { it.kind == kind }

    fun countOf(kind: StyleKind): Int = styles.values.count { it.kind == kind }

    fun byId(id: String?): CustomStyleDef? = if (id == null) null else styles[id]

    /** 池里多少张：未加载时读 `seedCount`，加载后用 `seeds.size`（两者本应一致）。 */
    fun refCount(def: CustomStyleDef): Int =
        if (def.seedsLoaded) def.seeds.size else def.seedCount

    /**
     * 确保 [CustomStyleDef.seeds] 已读进内存（幂等，同步 IO）。
     *
     * 只在真正要**烘焙 / 导出 / 改池**的路径调用；纯展示请用 [refCount]，
     * 否则「滤镜条重建顺手读一遍所有池」会把 M3 省下的内存又吃回去。
     *
     * @return 池是否可用（文件缺失时按空池放行，并留一条 warn 便于排查）
     */
    fun ensureSeeds(def: CustomStyleDef): Boolean {
        if (def.seedsLoaded) return true
        val ctx = appCtx ?: return false
        val loaded = SeedsStore.load(ctx, def.id)
        if (loaded == null && def.seedCount > 0) {
            DiagLog.w("Store", "池文件缺失 ${def.id}（记录 ${def.seedCount} 张）")
        }
        def.seeds.clear()
        loaded?.let { def.seeds.addAll(it) }
        def.seedCount = def.seeds.size
        def.seedsLoaded = true
        return true
    }

    /** 新增或整条替换（S2 只新增；S3 的回退/删除走同一入口）。@return 是否成功落盘。 */
    fun put(ctx: Context, def: CustomStyleDef): Boolean {
        styles[def.id] = def
        revision++
        return save(ctx)
    }

    /**
     * 批量写入（导入备份用）：一次落盘，避免 N 个色彩产生 N 次全量 JSON 改写。
     *
     * 入参里的对象若已是内存副本（合并场景），这里只是重新索引，语义等价于 put。
     */
    fun putAll(ctx: Context, defs: List<CustomStyleDef>) {
        if (defs.isEmpty()) return
        defs.forEach { styles[it.id] = it }
        revision++
        save(ctx)
    }

    /**
     * 删除一个自定义色彩：内存副本移除 + 立即重写 `custom_styles.json`。
     *
     * 只负责元数据；LUT 文件、缩略图缓存、置顶记录由调用方清理
     * （见 MainActivity 的删除流程），避免存储层反向依赖 UI 状态。
     */
    fun remove(ctx: Context, id: String): Boolean {
        // 池文件先删：它只被这条色彩引用，元数据没了就是孤儿
        SeedsStore.delete(ctx, id)
        if (styles.remove(id) == null) return false
        revision++
        save(ctx)
        return true
    }

    /** 生成不冲突的自定义色彩 id（u_ 前缀 + 36 进制时间戳）。 */
    fun newId(): String {
        val base = "u_" + System.currentTimeMillis().toString(36)
        if (!styles.containsKey(base)) return base
        var i = 2
        while (styles.containsKey("${base}_$i")) i++
        return "${base}_$i"
    }

    /** 滤镜条卡片显示名；色彩已被删除时给占位文案而不是空字符串。 */
    fun nameOf(id: String): String = styles[id]?.name ?: "已删除"

    fun currentVersion(def: CustomStyleDef): StyleVersion? =
        def.versions.firstOrNull { it.id == def.currentVersionId } ?: def.versions.lastOrNull()

    /** @return 是否成功落盘。调用方一般不关心，但需要「先确认写成功再删旧文件」的场景要判。 */
    fun save(ctx: Context): Boolean {
        return try {
            // 只写「已加载」的池：未加载时 seeds 是空的，写了就是把磁盘上的池清空
            var seedsOk = true
            styles.values.forEach { d ->
                if (d.seedsLoaded) {
                    d.seedCount = d.seeds.size
                    if (!SeedsStore.save(ctx, d.id, d.seeds)) seedsOk = false
                }
            }
            val arr = JSONArray()
            styles.values.forEach { arr.put(styleJson(it)) }
            val root = JSONObject().put("schema", SCHEMA).put("styles", arr)
            File(ctx.applicationContext.filesDir, FILE_NAME).writeText(root.toString())
            seedsOk
        } catch (t: Throwable) {
            // 写盘失败只影响下次启动的持久化，不打断当前会话
            false
        }
    }

    // ---- JSON ----

    /** 单条色彩的 JSON 序列化（分享文件时导出只含一条色彩的容器用）。 */
    internal fun styleJsonOf(def: CustomStyleDef): JSONObject = styleJson(def)

    /**
     * 单条分享文件用：池随元数据内嵌（schema 1 形态），对方旧版本也能导入。
     * 全量备份不走这里 —— 它把池作为独立 zip 条目装，避免主 JSON 重新变胖。
     */
    internal fun styleJsonWithSeeds(def: CustomStyleDef): JSONObject = styleJson(def, withSeeds = true)

    private fun styleJson(def: CustomStyleDef, withSeeds: Boolean = false): JSONObject {
        val versions = JSONArray()
        def.versions.forEach { v ->
            versions.put(
                JSONObject()
                    .put("id", v.id)
                    .put("createdAt", v.createdAt)
                    .put("label", v.label)
                    .put("lutFile", v.lutFile)
                    .put("seedCount", v.seedCount)
                    .put("source", v.source.name)
                    .put("strength", v.strength.toDouble())
                    // 空串不写，旧版本读到未知键会忽略
                    .put("thetaFile", v.thetaFile)
                    .apply { if (v.paramFile.isNotEmpty()) put("paramFile", v.paramFile) }
            )
        }
        val o = JSONObject()
            .put("id", def.id)
            .put("name", def.name)
            .put("createdAt", def.createdAt)
            .put("currentVersionId", def.currentVersionId)
            // 旧档没有该字段；显式写 QUICK 也保持向后兼容（旧版本读到自己不认识的键会忽略）
            .put("kind", def.kind.name)
            // schema 2：只记张数，池本体在 SeedsStore（避免启动时读几十 MB）
            .put("seedCount", if (def.seedsLoaded) def.seeds.size else def.seedCount)
            .put("versions", versions)
        if (withSeeds) {
            val arr = JSONArray()
            def.seeds.forEach { arr.put(SeedsStore.seedJson(it)) }
            o.put("seeds", arr)
        }
        return o
    }

    /**
     * 解析单条色彩（备份导入复用同一套字段容错，不落到内存也不写盘）。
     *
     * [StyleVersion.thetaFile] 保留原名：M5 之后备份包会带 `theta/` 条目，
     * 导入端要靠这个名字把快照搬到本机；包里没有快照时由 [ColorBackup.merge]
     * 把 thetaFile 清空，不会留下指向不存在文件的引用。
     */
    internal fun parseStyleJson(o: JSONObject): CustomStyleDef? = parseStyle(o)

    private fun parseStyle(o: JSONObject): CustomStyleDef? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        // schema 1：内嵌 seeds 数组；schema 2：只有 seedCount，池在 SeedsStore。
        // 备份包/分享包解析也走这里，所以两种形态必须都能读。
        val seedArr = o.optJSONArray("seeds")
        val seeds = if (seedArr != null) SeedsStore.parseSeeds(seedArr) else mutableListOf()
        val seedCount = o.optInt("seedCount", seeds.size)
        val verArr = o.optJSONArray("versions") ?: JSONArray()
        val versions = ArrayList<StyleVersion>(verArr.length())
        for (i in 0 until verArr.length()) {
            val v = verArr.optJSONObject(i) ?: continue
            versions.add(
                StyleVersion(
                    id = v.optString("id"),
                    createdAt = v.optLong("createdAt"),
                    label = v.optString("label"),
                    lutFile = v.optString("lutFile"),
                    seedCount = v.optInt("seedCount"),
                    source = runCatching { VersionSource.valueOf(v.optString("source")) }
                        .getOrDefault(VersionSource.INITIAL),
                    strength = v.optDouble("strength", 1.0).toFloat().coerceIn(0f, 1f),
                    thetaFile = v.optString("thetaFile"),
                    paramFile = v.optString("paramFile")
                )
            )
        }
        return CustomStyleDef(
            id = id,
            name = o.optString("name").ifEmpty { "色彩" },
            createdAt = o.optLong("createdAt"),
            seeds = seeds,
            versions = versions,
            currentVersionId = o.optString("currentVersionId"),
            // 缺字段/非法值都回落到 QUICK，旧存档与旧分享包无需迁移
            kind = runCatching { StyleKind.valueOf(o.optString("kind", "QUICK")) }
                .getOrDefault(StyleKind.QUICK),
            seedCount = seedCount,
            // 有 seeds 键 = 池随元数据一起来的（schema 1 / 备份包），已加载
            seedsLoaded = seedArr != null
        )
    }

}

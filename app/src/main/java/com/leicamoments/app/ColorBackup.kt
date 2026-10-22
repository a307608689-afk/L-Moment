package com.leicamoments.app

import android.content.Context
import com.codex.leicaapp.AlexaLut
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 「我的色彩」备份 / 导入。
 *
 * 备份集只有两块：`filesDir/custom_styles.json`（元数据 + 参考图指纹）与
 * `filesDir/luts/` 下的 bin（每个版本一个 33³ LUT）。卡片缩略图是纯内存缓存，
 * 导入后由 MainActivity 自动重算，不进备份。
 *
 * 文件格式是普通 zip（扩展名 `.lmc`）：
 * ```
 * manifest.json          schema / 导出时间 / bin 清单（含缺失项，便于诊断）
 * custom_styles.json     原样拷贝
 * luts/<lutFile>         仅元数据引用到的 bin
 * ```
 *
 * 导入语义（已与用户确认）：
 * - **合并**：绝不删除或覆盖已有色彩，只往库里加。
 * - 同 id 且参考图序列兼容 → 视为同一色彩，按版本 id 增量合并（已有的版本跳过）。
 * - 同 id 但参考图序列分叉（两台设备各自增强过）→ 另存为一条新色彩，
 *   避免把别人的 `seedCount` 指到自己不同的 seeds 前缀上。
 * - 所有新落的 bin 一律用新文件名：现有删除逻辑按 `versions[].lutFile` 删文件，
 *   共用同一个 bin 会让删一条色彩连带打死另一条。
 * - 尺寸不是 33³×3×4 的版本直接跳过（截断/他版网格都会让查表越界）。
 */
object ColorBackup {

    /** 备份格式版本，只升不降：高于本值的备份拒绝导入。 */
    private const val SCHEMA = 1

    private const val MANIFEST = "manifest.json"
    private const val STYLES = "custom_styles.json"
    private const val LUT_PREFIX = "luts/"

    /** M3：参考图指纹池的独立条目（与 `filesDir/seeds/` 同名同格式） */
    private const val SEEDS_PREFIX = "seeds/"

    /** M5：θ 聚合快照（`filesDir/theta/`），被 LRU 回收的版本靠它重烘 */
    private const val THETA_PREFIX = "theta/"

    /** A 路线参数（1596 B，永不回收）。和 LUT 互斥，跟着版本一起走 */
    private const val PARAM_PREFIX = "params/"

    /**
     * 模型色彩的参考图缩略图（240px JPEG，`filesDir/model_refs/`），条目名
     * `refs/<styleId>/<index>.jpg`，下标与参考图池同序。
     *
     * 它既是给人看的图，也是 A 路线重新拟合的原料：不带上，对方的「设置」页
     * 只有灰格，加图增强也烘不出新版本。
     */
    private const val REFS_PREFIX = "refs/"

    /** 单张缩略图上限（240px JPEG90 正常 20~40 KB，留足余量） */
    private const val MAX_REF_BYTES = 512L * 1024

    /**
     * 合法 LUT 字节数白名单（点 × 3 通道 × 4 字节）：
     * - 17³ = 58956（轻量指令导入后的存档）
     * - 33³ = 431244（v1 统计量映射，旧数据）
     * - 65³ = 3295500（v2 相对风格拟合）
     * 尺寸不在白名单的版本直接跳过（截断/他版网格都会让查表越界）。
     */
    private val LUT_BYTES = longArrayOf(
        17L * 17L * 17L * 3L * 4L,
        33L * 33L * 33L * 3L * 4L,
        65L * 65L * 65L * 3L * 4L
    )

    private fun isLutSize(n: Long): Boolean = LUT_BYTES.contains(n)

    // 解包上限：防损坏文件 / zip bomb 把内存和磁盘拖死
    private const val MAX_ENTRIES = 5000
    private const val MAX_META = 8L * 1024 * 1024
    private const val MAX_ENTRY = 4L * 1024 * 1024
    private const val MAX_TOTAL = 1024L * 1024 * 1024

    data class Result(
        val styles: Int,     // 新增的完整色彩数
        val merged: Int,     // 合并进已有色彩的色彩数
        val versions: Int,   // 实际新增的版本总数
        val skipped: Int     // 跳过的无效版本数
    )

    /** 默认文件名：LeicaColors_20261017_1530.lmc */
    fun defaultFileName(): String =
        "LeicaColors_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".lmc"

    // ---- 导出 ----

    /**
     * 打包全部「我的色彩」到 [out]。返回色彩条数。
     *
     * IO 过程由调用方放在后台线程；失败抛异常，由调用方提示。
     */
    fun export(ctx: Context, out: OutputStream): Int {
        val styles = CustomStyleStore.all()
        if (styles.isEmpty()) throw IllegalStateException("还没有自定义色彩")

        // 进程内副本可能比磁盘新（正常流程 put 已落盘），导出前再落一次保证一致
        CustomStyleStore.save(ctx)
        val dir = AlexaLut.customDir(ctx)

        val names = LinkedHashSet<String>()
        styles.forEach { def -> def.versions.forEach { v -> if (v.lutFile.isNotEmpty()) names.add(v.lutFile) } }

        val present = ArrayList<Pair<String, Long>>()
        val missing = ArrayList<String>()
        names.forEach { name ->
            val f = File(dir, name)
            if (f.isFile) present.add(name to f.length()) else missing.add(name)
        }

        val lutArr = JSONArray()
        present.forEach { (name, size) ->
            lutArr.put(JSONObject().put("name", name).put("size", size))
        }
        val missingArr = JSONArray()
        missing.forEach { missingArr.put(it) }

        // M3：池已挪到独立文件。这里**直接拷文件**而不 ensure（把几十 MB 读进内存只为打包）。
        // 已加载的池在上一行 save() 时同步落盘，所以文件总是比内存新或相等。
        val seedEntries = ArrayList<Pair<String, File>>()
        styles.forEach { d ->
            val f = SeedsStore.fileOf(ctx, d.id)
            if (f.isFile) seedEntries.add("${d.id}.json" to f)
        }
        val seedArr = JSONArray()
        seedEntries.forEach { (name, f) -> seedArr.put(JSONObject().put("name", name).put("size", f.length())) }

        // M5：θ 快照每个约 19 KB，必须跟着版本走 —— LUT 被 LRU 回收后，
        // 新设备/本机靠它把版本逐字节重烘回来，否则备份会静默少掉这些版本。
        val thetaEntries = ArrayList<Pair<String, File>>()
        val thetaSeen = HashSet<String>()
        styles.forEach { d ->
            d.versions.forEach { v ->
                if (v.thetaFile.isEmpty() || !thetaSeen.add(v.thetaFile)) return@forEach
                val f = ModelLutStore.thetaFileOf(ctx, v.thetaFile)
                if (f.isFile) thetaEntries.add(v.thetaFile to f)
            }
        }
        val thetaArr = JSONArray()
        thetaEntries.forEach { (name, f) -> thetaArr.put(JSONObject().put("name", name).put("size", f.length())) }

        // A 路线参数：1.6 KB/版本，必须跟着版本走（不进 luts/，长度也不在白名单里）
        val paramEntries = ArrayList<Pair<String, File>>()
        val paramSeen = HashSet<String>()
        styles.forEach { d ->
            d.versions.forEach { v ->
                if (v.paramFile.isEmpty() || !paramSeen.add(v.paramFile)) return@forEach
                val f = ModelParamStore.fileOf(ctx, v.paramFile)
                if (f.isFile && f.length() == ModelParamStore.BYTES.toLong()) paramEntries.add(v.paramFile to f)
            }
        }
        val paramArr = JSONArray()
        paramEntries.forEach { (name, f) -> paramArr.put(JSONObject().put("name", name).put("size", f.length())) }

        val manifest = JSONObject()
            .put("schema", SCHEMA)
            .put("app", "LeicaMoments")
            .put("exportedAt", System.currentTimeMillis())
            .put("styles", styles.size)
            .put("luts", lutArr)
            .put("thetas", thetaArr)
            .put("params", paramArr)
            .put("seeds", seedArr)
            .put("missing", missingArr)

        val metaFile = File(ctx.filesDir, STYLES)
        if (!metaFile.isFile) throw IllegalStateException("元数据文件读取失败")

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            putEntry(zip, MANIFEST, manifest.toString().toByteArray(Charsets.UTF_8))
            putEntry(zip, STYLES, metaFile.readBytes())
            present.forEach { (name, _) ->
                zip.putNextEntry(ZipEntry(LUT_PREFIX + name))
                File(dir, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            seedEntries.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(SEEDS_PREFIX + name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            thetaEntries.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(THETA_PREFIX + name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            paramEntries.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(PARAM_PREFIX + name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        }
        return styles.size
    }

    /**
     * 导出单条色彩（分享用）：只装当前版本 + 全部参考图指纹（模型色彩再带参考图缩略图）。
     *
     * 和备份的区别是**不装历史版本**：接收方要的是这条色彩的成品，3.3 MB 已经是上限，
     * 再带上每个历史版本的 65³ bin 会变成几十 MB。版本元数据改写为「初始版」，
     * 这样对方导入后是常规色彩：可直接套用，也能以这份 LUT 为基准继续增强。
     *
     * 模型色彩（kind=MODEL）额外带上 `refs/<id>/<i>.jpg`（240px 缩略图）并保留 kind：
     * 对方导入后仍是模型，进「设置」能看到参考图、能继续加图并重新拟合。容器结构与备份
     * 完全一致，接收端仍走「设置 → 导入」；旧客户端忽略未知条目，不影响导入。
     */
    fun exportOne(ctx: Context, out: OutputStream, def: CustomStyleDef, version: StyleVersion) {
        // A 路线参数版本：1.6 KB，永不回收，用不着重烘
        val paramBytes = if (version.paramFile.isNotEmpty()) ModelParamStore.read(ctx, version.paramFile) else null
        val isParam = paramBytes != null
        // M6：aff/knots 直接放在 JSON 里，没有 LUT/θ/param 文件可打包
        val isNet = def.hasModelNet
        // M5：旧 LUT 版本可能已被 LRU 回收，先按 θ 快照重烘
        if (!isNet && !isParam && !ModelLutStore.ensure(ctx, def, version)) {
            throw IllegalStateException("这个版本的色彩文件已丢失")
        }
        val dir = AlexaLut.customDir(ctx)
        val binFile = File(dir, version.lutFile)
        if (!isNet && !isParam) {
            if (version.lutFile.isEmpty() || !binFile.isFile) {
                throw IllegalStateException("这个版本的色彩文件已丢失")
            }
            if (!isLutSize(binFile.length())) {
                throw IllegalStateException("色彩文件格式不支持分享")
            }
        }

        // 分享文件要把池一起装走（对方靠它继续增强），M3 后池不常驻，先补上
        CustomStyleStore.ensureSeeds(def)
        // 模型色彩的参考图缩略图：对方「设置」页展示 + A 路线重新拟合的原料
        val refThumbs = if (def.kind == StyleKind.MODEL) ModelRefStore.allThumbs(ctx, def.id) else emptyList()
        val singleVersion = version.copy(
            createdAt = System.currentTimeMillis(),
            label = "初始",
            seedCount = CustomStyleStore.refCount(def),
            source = VersionSource.INITIAL,
            strength = 1f,
            lutFile = if (isNet || isParam) "" else version.lutFile,
            thetaFile = if (isNet || isParam) "" else version.thetaFile,
            // 版本级优先；旧数据只有 def 级时用它补齐，保证分享包仍带 aff/knots
            modelAff = if (version.modelAff.size == 12) version.modelAff else def.modelAff,
            modelKnots = if (version.modelKnots.size == 27) version.modelKnots else def.modelKnots
        )
        val single = CustomStyleDef(
            id = def.id,
            name = def.name,
            createdAt = def.createdAt,
            seeds = ArrayList(def.seeds),
            versions = arrayListOf(singleVersion),
            currentVersionId = singleVersion.id,
            kind = def.kind,
            modelAff = def.modelAff,
            modelKnots = def.modelKnots
        )
        val root = JSONObject()
            .put("schema", 1)
            // 单条分享仍用「池内嵌」的老容器格式：对方旧版本也能导入
            .put("styles", JSONArray().put(CustomStyleStore.styleJsonWithSeeds(single)))
        val manifest = JSONObject()
            .put("schema", SCHEMA)
            .put("app", "LeicaMoments")
            .put("exportedAt", System.currentTimeMillis())
            .put("styles", 1)
            .put(
                "luts",
                if (isNet || isParam) JSONArray()
                else JSONArray().put(JSONObject().put("name", version.lutFile).put("size", binFile.length()))
            )
            .put(
                "params",
                if (isParam) JSONArray().put(JSONObject().put("name", version.paramFile).put("size", paramBytes!!.size))
                else JSONArray()
            )
            .put("missing", JSONArray())
            .put(
                "refs",
                JSONArray().apply {
                    refThumbs.forEach { (idx, f) ->
                        put(JSONObject().put("name", "$idx.jpg").put("size", f.length()))
                    }
                }
            )

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            putEntry(zip, MANIFEST, manifest.toString().toByteArray(Charsets.UTF_8))
            putEntry(zip, STYLES, root.toString().toByteArray(Charsets.UTF_8))
            when {
                isNet -> Unit                       // JSON 里的 aff/knots 就是全部
                isParam -> putEntry(zip, PARAM_PREFIX + version.paramFile, paramBytes!!)
                else -> {
                    zip.putNextEntry(ZipEntry(LUT_PREFIX + version.lutFile))
                    binFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            refThumbs.forEach { (idx, f) ->
                zip.putNextEntry(ZipEntry("$REFS_PREFIX${def.id}/$idx.jpg"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    /**
     * 只读元数据，返回容器里的色彩名（供导入前确认「导入这个色彩？」）。
     *
     * 只解 customs_styles.json 一个条目，不碰 LUT，几毫秒内返回。
     * 结构不认识时返回空列表，交给 [import] 去报真正的格式错误。
     */
    fun peekStyleNames(file: File): List<String> {
        var raw: ByteArray? = null
        ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    if (++entries > MAX_ENTRIES) break
                    if (entry.name == STYLES) {
                        raw = readLimited(zip, MAX_META)
                        break
                    }
                }
                zip.closeEntry()
            }
        }
        val bytes = raw ?: return emptyList()
        val arr = try {
            JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("styles")
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotBlank()) names.add(name)
        }
        return names
    }

    // ---- 导入 ----

    /**
     * 从 [input] 合并导入。返回统计；没有任何变化时返回全 0（不算失败）。
     *
     * IO 过程由调用方放在后台线程；格式非法抛异常。
     */
    fun import(ctx: Context, input: InputStream): Result {
        // 先整包落到 cacheDir，再按元数据搬运：zip 条目顺序不可控，
        // 不能假设 custom_styles.json 一定在 bin 之前。
        val tmpDir = File(ctx.cacheDir, "lut_import")
        runCatching { tmpDir.deleteRecursively() }
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) {
            throw IllegalStateException("临时目录创建失败")
        }
        try {
            var manifest: JSONObject? = null
            var styleBytes: ByteArray? = null
            val tmpLuts = HashMap<String, File>()
            // M3：池作为独立条目带上（styleId → json 字节）；旧备份没有这些条目，
            // 它们的池仍内嵌在 custom_styles.json 里，由 parseStyle 读取
            val tmpSeeds = HashMap<String, ByteArray>()
            // M5：θ 快照（条目名 → 字节）。体积小，直接读进内存再写回 filesDir/theta
            val tmpThetas = HashMap<String, ByteArray>()
            // A 路线参数（条目名 → 1596 B）。同样 1.6 KB 级，直接进内存
            val tmpParams = HashMap<String, ByteArray>()
            // 模型色彩的参考图缩略图（条目名 `<styleId>/<index>.jpg` → 临时文件）
            val tmpRefs = HashMap<String, File>()
            var total = 0L

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        if (++entries > MAX_ENTRIES) throw IllegalStateException("备份文件异常")
                        when {
                            entry.name == MANIFEST ->
                                manifest = JSONObject(String(readLimited(zip, MAX_META), Charsets.UTF_8))

                            entry.name == STYLES ->
                                styleBytes = readLimited(zip, MAX_META)

                            entry.name.startsWith(LUT_PREFIX) -> {
                                val base = entry.name.substring(LUT_PREFIX.length)
                                if (!isSafeName(base)) throw IllegalStateException("备份文件异常")
                                val f = File(tmpDir, base)
                                total += copyLimited(zip, f, MAX_ENTRY)
                                if (total > MAX_TOTAL) throw IllegalStateException("备份文件过大")
                                if (f.isFile) tmpLuts[base] = f
                            }
                            entry.name.startsWith(SEEDS_PREFIX) -> {
                                val base = entry.name.substring(SEEDS_PREFIX.length)
                                if (!isSafeName(base)) throw IllegalStateException("备份文件异常")
                                tmpSeeds[base.removeSuffix(".json")] = readLimited(zip, MAX_META)
                            }
                            entry.name.startsWith(THETA_PREFIX) -> {
                                val base = entry.name.substring(THETA_PREFIX.length)
                                if (!isSafeName(base)) throw IllegalStateException("备份文件异常")
                                tmpThetas[base] = readLimited(zip, MAX_META)
                            }
                            entry.name.startsWith(PARAM_PREFIX) -> {
                                val base = entry.name.substring(PARAM_PREFIX.length)
                                if (!isSafeName(base)) throw IllegalStateException("备份文件异常")
                                tmpParams[base] = readLimited(zip, ModelParamStore.BYTES.toLong())
                            }
                            entry.name.startsWith(REFS_PREFIX) -> {
                                // 条目名 `<styleId>/<index>.jpg`：id 走安全名校验，下标必须是数字
                                val base = entry.name.substring(REFS_PREFIX.length)
                                val slash = base.indexOf('/')
                                val sid = if (slash > 0) base.substring(0, slash) else ""
                                val idx = if (slash > 0) {
                                    base.substring(slash + 1).removeSuffix(".jpg").toIntOrNull()
                                } else null
                                if (sid.isNotEmpty() && isSafeName(sid) && idx != null && idx >= 0) {
                                    val f = File(tmpDir, "ref_${sid}_$idx.jpg")
                                    total += copyLimited(zip, f, MAX_REF_BYTES)
                                    if (total > MAX_TOTAL) throw IllegalStateException("备份文件过大")
                                    if (f.isFile) tmpRefs[base] = f
                                }
                                // 不认识的 refs 条目直接忽略（向前兼容）
                            }
                            // 未知条目忽略：向前兼容将来加的附属文件
                        }
                    }
                    zip.closeEntry()
                }
            }

            manifest?.let { mf ->
                val schema = mf.optInt("schema", SCHEMA)
                if (schema > SCHEMA) throw IllegalStateException("备份来自更新版本，请先升级应用")
            }
            val bytes = styleBytes ?: throw IllegalStateException("不是有效的备份文件")
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = root.optJSONArray("styles") ?: JSONArray()

            return merge(ctx, arr, tmpLuts, tmpSeeds, tmpThetas, tmpParams, tmpRefs)
        } finally {
            runCatching { tmpDir.deleteRecursively() }
        }
    }

    /**
     * 把备份里的色彩合并进内存库并落盘。
     *
     * 只构造/修改对象，文件搬运失败只影响该版本（跳过并计数）。
     */
    private fun merge(
        ctx: Context,
        arr: JSONArray,
        tmpLuts: Map<String, File>,
        tmpSeeds: Map<String, ByteArray>,
        tmpThetas: Map<String, ByteArray>,
        tmpParams: Map<String, ByteArray>,
        tmpRefs: Map<String, File>
    ): Result {
        val dir = AlexaLut.customDir(ctx)
        val takenFiles = HashSet<String>()
        val takenNames = CustomStyleStore.all().map { it.name }.toMutableSet()
        val changed = ArrayList<CustomStyleDef>()

        /**
         * 把包里的 θ 快照落到本机，返回本机文件名（包内没有快照返回空串）。
         * 有快照但没 LUT 的版本照样保留：首次套用时按快照重烘。
         */
        fun restoreTheta(styleId: String, v: StyleVersion): String {
            val raw = tmpThetas[v.thetaFile] ?: return ""
            return ModelLutStore.writeTheta(ctx, styleId, v.id, raw)
        }

        /**
         * 把包里的 A 路线参数落到本机，返回本机文件名（包内没有返回空串）。
         * 一律用新文件名：本机可能与别的版本重名，覆盖会打坏正在用的色彩。
         */
        fun restoreParam(styleId: String, v: StyleVersion): String {
            if (v.paramFile.isEmpty()) return ""
            val raw = tmpParams[v.paramFile] ?: return ""
            if (raw.size != ModelParamStore.BYTES) return ""
            return ModelParamStore.writeNamed(ctx, ModelParamStore.uniqueName(ctx, styleId, v.id), raw)
        }

        /**
         * 把包里的参考图缩略图落到本机（下标与参考图池同序）。
         *
         * 只补缺失的位：本地已有同名缩略图时保留本机文件，绝不覆盖。
         */
        fun restoreRefs(srcId: String, dstId: String) {
            if (tmpRefs.isEmpty()) return
            for ((key, f) in tmpRefs) {
                val slash = key.indexOf('/')
                if (slash <= 0 || key.substring(0, slash) != srcId) continue
                val idx = key.substring(slash + 1).removeSuffix(".jpg").toIntOrNull() ?: continue
                if (idx < 0 || ModelRefStore.thumbFile(ctx, dstId, idx)?.isFile == true) continue
                val raw = runCatching { f.readBytes() }.getOrNull() ?: continue
                runCatching { ModelRefStore.saveThumb(ctx, dstId, idx, raw) }
            }
        }

        var addedStyles = 0
        var mergedStyles = 0
        var addedVersions = 0
        var skipped = 0

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val src = CustomStyleStore.parseStyleJson(obj) ?: continue
            // schema 2 的备份池不在主 JSON 里：先把 seeds/ 条目补进 src，
            // 后面 putAll 时 save() 才会把池写成独立文件
            hydratePool(src, tmpSeeds)
            val local = CustomStyleStore.byId(src.id)

            if (local != null && CustomStyleStore.ensureSeeds(local) && seedsCompatible(local, src)) {
                // 同一色彩：按版本 id 增量合并，本地的名称 / 当前版本一概不动
                val known = local.versions.map { it.id }.toHashSet()
                var added = 0
                src.versions.forEach { v ->
                    if (v.id.isEmpty() || v.id in known) return@forEach
                    val tmp = tmpLuts[v.lutFile]
                    val hasLut = tmp != null && isLutSize(tmp.length())
                    val thetaName = restoreTheta(local.id, v)
                    val paramName = restoreParam(local.id, v)
                    if (!hasLut && thetaName.isEmpty() && paramName.isEmpty()) {
                        skipped++
                        return@forEach
                    }
                    val target = if (hasLut || thetaName.isNotEmpty()) {
                        uniqueLutFile(dir, local.id, v.id, takenFiles)
                    } else null
                    if (hasLut && target != null && !copyFile(tmp!!, target)) {
                        skipped++
                        return@forEach
                    }
                    if (!hasLut && paramName.isEmpty()) {
                        DiagLog.i("Custom", "版本 ${v.id} 仅带快照导入，首次套用时重烘")
                    }
                    // 本地 seeds 是备份前缀时先补齐（seedCount 指向的位置才一致）
                    if (local.seeds.size < src.seeds.size) {
                        for (idx in local.seeds.size until src.seeds.size) local.seeds.add(src.seeds[idx])
                    }
                    local.versions.add(
                        v.copy(
                            lutFile = target?.name ?: "",
                            thetaFile = thetaName,
                            paramFile = paramName
                        )
                    )
                    if (local.currentVersionId.isEmpty()) local.currentVersionId = v.id
                    known.add(v.id)
                    added++
                    addedVersions++
                }
                if (added > 0) {
                    // 同 id 合并：只补本地缺失的缩略图，已有的一律保留
                    restoreRefs(src.id, local.id)
                    mergedStyles++
                    changed.add(local)
                }
                continue
            }

            // 新 id，或同 id 但参考图序列分叉 → 整条作为新色彩导入
            val def = if (local == null) {
                src
            } else {
                CustomStyleDef(
                    id = CustomStyleStore.newId(),
                    name = src.name,
                    createdAt = src.createdAt,
                    seeds = src.seeds,
                    versions = mutableListOf(),
                    currentVersionId = "",
                    // 分叉也要保留模型身份，否则对方的「设置」入口会退化成普通色彩菜单
                    kind = src.kind,
                    modelAff = src.modelAff,
                    modelKnots = src.modelKnots
                )
            }
            def.name = uniqueStyleName(src.name, takenNames)
            takenNames.add(def.name)

            val keep = ArrayList<StyleVersion>()
            src.versions.forEach { v ->
                val tmp = tmpLuts[v.lutFile]
                val hasLut = tmp != null && isLutSize(tmp.length())
                val thetaName = restoreTheta(def.id, v)
                val paramName = restoreParam(def.id, v)
                if (!hasLut && thetaName.isEmpty() && paramName.isEmpty()) {
                    skipped++
                    return@forEach
                }
                val target = if (hasLut || thetaName.isNotEmpty()) {
                    uniqueLutFile(dir, def.id, v.id, takenFiles)
                } else null
                if (hasLut && target != null && !copyFile(tmp!!, target)) {
                    skipped++
                    return@forEach
                }
                if (!hasLut && paramName.isEmpty()) {
                    DiagLog.i("Custom", "版本 ${v.id} 仅带快照导入，首次套用时重烘")
                }
                keep.add(
                    v.copy(
                        lutFile = target?.name ?: "",
                        thetaFile = thetaName,
                        paramFile = paramName
                    )
                )
            }
            if (keep.isEmpty()) continue

            // 新色彩：缩略图写到最终 id 下（同 id 分叉时 id 已换成新生成的）
            restoreRefs(src.id, def.id)
            def.versions.clear()
            def.versions.addAll(keep)
            def.currentVersionId = keep.firstOrNull { it.id == src.currentVersionId }?.id
                ?: keep.last().id
            addedVersions += keep.size
            addedStyles++
            changed.add(def)
        }

        if (changed.isNotEmpty()) CustomStyleStore.putAll(ctx, changed)
        return Result(addedStyles, mergedStyles, addedVersions, skipped)
    }

    // ---- 小工具 ----

    /**
     * 把备份条目里的池补进 [src]（M3：schema 2 备份的池在 `seeds/<id>.json`）。
     *
     * 旧备份（schema 1）池本就内嵌在元数据里，`parseStyle` 已填好且 seedsLoaded=true。
     * 找不到条目时保持未加载 —— 库里若已有池文件则按需读，绝不用空池覆盖。
     */
    private fun hydratePool(src: CustomStyleDef, tmpSeeds: Map<String, ByteArray>) {
        if (src.seedsLoaded) return
        val bytes = tmpSeeds[src.id] ?: return
        val arr = runCatching {
            JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("seeds")
        }.getOrNull() ?: return
        src.seeds.clear()
        src.seeds.addAll(SeedsStore.parseSeeds(arr))
        src.seedCount = src.seeds.size
        src.seedsLoaded = true
    }

    /** 两个 seeds 池是否前缀关系（比较到较短的那个为止）。 */
    private fun seedsCompatible(a: CustomStyleDef, b: CustomStyleDef): Boolean {
        val n = minOf(a.seeds.size, b.seeds.size)
        for (i in 0 until n) {
            if (!sameSeed(a.seeds[i], b.seeds[i])) return false
        }
        return true
    }

    /** RefSeed 含 FloatArray，data class 的 equals 是引用比较，必须逐数组比。 */
    private fun sameSeed(a: RefSeed, b: RefSeed): Boolean =
        a.addedAt == b.addedAt &&
            a.wbGain.contentEquals(b.wbGain) &&
            a.toneCurve.contentEquals(b.toneCurve) &&
            a.colorMatrix.contentEquals(b.colorMatrix) &&
            a.satByHue.contentEquals(b.satByHue) &&
            a.hueShift.contentEquals(b.hueShift) &&
            a.hueShare.contentEquals(b.hueShare) &&
            a.base.contentEquals(b.base)

    /** 卡片可重名会让人分不清，导入时自动加序号。 */
    private fun uniqueStyleName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    /**
     * 生成一个不会被现有文件/本次导入占用的 LUT 文件名。
     *
     * 绝不沿用备份里的原名：名称相同就会和已有色彩共用同一个 bin，
     * 而删除逻辑是按 lutFile 删文件的。
     */
    private fun uniqueLutFile(
        dir: File,
        styleId: String,
        versionId: String,
        taken: MutableSet<String>
    ): File {
        val sid = sanitize(styleId)
        val vid = sanitize(versionId).ifEmpty { System.currentTimeMillis().toString(36) }
        val base = "lut_${sid}_$vid"
        var name = "$base.bin"
        var i = 2
        while (name in taken || File(dir, name).exists()) {
            name = "${base}_$i.bin"
            i++
        }
        taken.add(name)
        return File(dir, name)
    }

    /** id 来自备份 JSON，可能被改坏：只留安全字符并限长。 */
    private fun sanitize(raw: String): String =
        raw.map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
            .joinToString("")
            .take(40)

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') &&
            name != "." && name != ".." && !name.contains("..")

    private fun copyFile(src: File, dst: File): Boolean = try {
        src.inputStream().use { ins -> dst.outputStream().use { out -> ins.copyTo(out) } }
        true
    } catch (t: Throwable) {
        runCatching { dst.delete() }
        DiagLog.w("Custom", "LUT 导入落盘失败 ${dst.name}: ${t.message}")
        false
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readLimited(zip: ZipInputStream, limit: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) throw IllegalStateException("备份文件异常（条目过大）")
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun copyLimited(zip: ZipInputStream, dst: File, limit: Long): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        dst.outputStream().use { out ->
            while (true) {
                val n = zip.read(buf)
                if (n <= 0) break
                total += n
                if (total > limit) throw IllegalStateException("备份文件异常（条目过大）")
                out.write(buf, 0, n)
            }
        }
        return total
    }
}

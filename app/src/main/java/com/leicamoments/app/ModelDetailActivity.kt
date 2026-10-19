package com.leicamoments.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 模型详情（M2）：参考图网格 + 排除 + 加图增强 + 版本回退。
 *
 * 入口 = [ModelActivity] 管理模式点卡片；本页只管「已有模型」的维护，
 * 新建与选中返回仍在 [ModelActivity]。
 */
class ModelDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STYLE_ID = "extra_style_id"

        private const val BG = 0xFF0E0E0F.toInt()
        private const val CARD_BG = 0xFF1C1C1E.toInt()
        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val TEXT_DIM = 0xFF8E8E93.toInt()
        private const val ACCENT = 0xFFFF453A.toInt()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var styleId: String = ""

    /** 详情页根容器：数据变了整页重建（内容量小，比局部刷新省事） */
    private lateinit var host: LinearLayout

    @Volatile
    private var busy = false
    @Volatile
    private var canceled = false

    private val pickRefs =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) enhance(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleId = intent?.getStringExtra(EXTRA_STYLE_ID).orEmpty()
        if (styleId.isEmpty() || CustomStyleStore.byId(styleId) == null) {
            finish()
            return
        }
        window.statusBarColor = BG
        host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        setContentView(host)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) rebuild()
    }

    override fun onDestroy() {
        canceled = true
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---- 页面 ----

    private fun rebuild() {
        val def = CustomStyleStore.byId(styleId) ?: run { finish(); return }
        // 详情页要看池、还要按池加图/重烘，进来就加载（幂等）
        CustomStyleStore.ensureSeeds(def)
        host.removeAllViews()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(this).apply {
            text = def.name
            textSize = 17f
            setTextColor(TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "改名"
            textSize = 14f
            setTextColor(TEXT_DIM)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { askRename(def) }
        })
        host.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        scroll.addView(content)

        // 概览
        val disabled = ModelRefStore.disabled(this, styleId)
        val cur = CustomStyleStore.currentVersion(def)
        content.addView(TextView(this).apply {
            text = "参考 ${CustomStyleStore.refCount(def)} 张 · 已排除 ${disabled.size} 张 · ${def.versions.size} 个版本"
            textSize = 12f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(2), 0, dp(12))
        })

        // 参考图网格
        content.addView(sectionTitle("参考图"))
        content.addView(TextView(this).apply {
            text = "点一张标记/取消排除；排除只在下次「重新生成」时生效，不会动已有版本。"
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(0, 0, 0, dp(8))
        })
        // 网格按**池张数**铺，不按缩略图张数：M4 淘汰/老模型补图后缩略图可能有缺口，
        // 缺的那格画灰底（下标照旧可点，排除标记仍按池下标走）
        val poolCount = CustomStyleStore.refCount(def)
        val hasThumb = ModelRefStore.anyThumb(this, styleId)
        if (poolCount == 0) {
            content.addView(TextView(this).apply {
                text = "这个模型没有可用的参考图池（统计量仍在，出图不受影响）。"
                textSize = 12f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(4), 0, dp(12))
            })
        } else if (!hasThumb) {
            content.addView(TextView(this).apply {
                text = "这个模型创建于旧版本，没有保存参考图缩略图（统计量仍在，出图不受影响）。"
                textSize = 12f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(4), 0, dp(12))
            })
        } else {
            val grid = GridLayout(this).apply {
                columnCount = 4
                useDefaultMargins = false
            }
            for (i in 0 until poolCount) {
                grid.addView(refCell(i, disabled.contains(i)), refCellParams())
            }
            content.addView(grid)
        }

        // 操作
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(6))
        }
        actions.addView(actionButton("＋ 添加参考图") { askAddRefs(def) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        actions.addView(actionButton("按当前参考重新生成") { confirmRebake(def) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        content.addView(actions)

        // 版本
        content.addView(sectionTitle("版本"))
        def.versions.asReversed().forEach { v ->
            content.addView(versionRow(v, v.id == cur?.id), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { bottomMargin = dp(6) })
        }

        host.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(TEXT)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun refCellParams(): GridLayout.LayoutParams {
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        )
        lp.width = 0
        lp.height = dp(84)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        return lp
    }

    private fun refCell(index: Int, excluded: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD_BG, dp(8).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(4))
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 圆角与格子同心：外圆 dp(8) 减去内缩 dp(3)
            background = roundedRect(0xFF232326.toInt(), dp(5).toFloat())
            clipToOutline = true
            setImageBitmap(
                ModelRefStore.thumbFile(this@ModelDetailActivity, styleId, index)
                    ?.absolutePath
                    ?.let { BitmapFactory.decodeFile(it) }
            )
            alpha = if (excluded) 0.3f else 1f
        }
        cell.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cell.addView(TextView(this).apply {
            text = if (excluded) "已排除" else "#${index + 1}"
            textSize = 10f
            setTextColor(if (excluded) ACCENT else TEXT_DIM)
            gravity = Gravity.CENTER
        })
        cell.setOnClickListener { toggleExclude(index) }
        return cell
    }

    private fun versionRow(v: StyleVersion, current: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(if (current) 0xFF2A1A1B.toInt() else CARD_BG, dp(10).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            setOnClickListener { rollback(v) }
        }
        row.addView(TextView(this).apply {
            text = "${if (current) "● " else "○ "}${v.label}"
            textSize = 13f
            setTextColor(if (current) ACCENT else TEXT)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            // M5：LUT 被回收的旧版本标「待重烘」，第一次点它会有几秒等待；
            // A 路线参数版本（1.6 KB 常驻不回收）恒为就绪，不会出现该标记
            text = "${fmtTime(v.createdAt)} · ${v.seedCount} 张" +
                if (ModelColor.ready(this@ModelDetailActivity, v)) "" else " · 待重烘"
            textSize = 11f
            setTextColor(TEXT_DIM)
        })
        return row
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        background = roundedRect(0xFF2C2C2E.toInt(), dp(10).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---- 排除 / 回退 ----

    private fun toggleExclude(index: Int) {
        val set = ModelRefStore.disabled(this, styleId).toMutableSet()
        if (!set.remove(index)) set.add(index)
        ModelRefStore.setDisabled(this, styleId, set)
        rebuild()
    }

    private fun rollback(v: StyleVersion) {
        val def = CustomStyleStore.byId(styleId) ?: return
        if (def.currentVersionId == v.id) return
        def.currentVersionId = v.id
        CustomStyleStore.put(this, def)
        toast(
            if (ModelColor.ready(this, v)) "已切到「${v.label}」，主界面下次进入即生效"
            else "已切到「${v.label}」，下次套用时按快照重建（几秒）"
        )
        rebuild()
    }

    // ---- 加图增强 ----

    /**
     * 把池读进内存，并确认「记录的张数」与「读到的张数」对得上。
     *
     * [CustomStyleStore.ensureSeeds] 在池文件缺失时会按空池放行（宁可不崩），
     * 但改池的路径绝不能拿空池去烘焙——那会把新版本做成「只剩这一批」的另一个风格。
     */
    private fun loadPool(def: CustomStyleDef): Boolean {
        val expected = CustomStyleStore.refCount(def)
        if (!CustomStyleStore.ensureSeeds(def)) return false
        if (expected > 0 && def.seeds.isEmpty()) {
            DiagLog.w("ModelDetail", "池文件缺失，拒绝改池 ${def.id}（记录 $expected 张）")
            return false
        }
        return true
    }

    private fun askAddRefs(def: CustomStyleDef) {
        if (busy) { toast("正在生成，请稍候"); return }
        val cur = CustomStyleStore.refCount(def)
        val msg = buildString {
            append("当前 $cur 张（上限 ${ModelPool.MAX_TOTAL}），一次最多选 ${ModelPool.MAX_BATCH} 张。\n")
            if (cur >= ModelPool.MAX_TOTAL) {
                append("池已满：新增会先淘汰池内「和别的参考最像」的那几张，保证多样性。\n")
            }
            append("和已有参考重复的会自动跳过。\n")
            append("新参考图会和旧统计量一起重新烘焙，生成一个新版本（旧版本随时可回退，近期没用到的会自动重烘）。\n")
            append("参考图本身不保存，只留统计量与缩略图。")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("添加参考图")
            .setMessage(msg)
            .setPositiveButton("选择参考图") { _, _ -> pickRefs.launch("image/*") }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 加图增强（M4）：指纹去重 → 复用断点续跑 → 逐张分析 → 超上限淘汰 → 烘焙 → 提交。
     *
     * 提交必须「一次语义」：池、缩略图、排除标记、每个版本的 `seedCount` 同时改，
     * 否则旧版本记的前缀会指到别的图上（见 [ModelPool]）。
     */
    private fun enhance(uris: List<Uri>) {
        val def = CustomStyleStore.byId(styleId) ?: return
        if (busy) return
        if (!loadPool(def)) { toast("参考图池读取失败，请重试"); return }
        val picked = if (uris.size > ModelPool.MAX_BATCH) uris.take(ModelPool.MAX_BATCH) else uris
        if (uris.size > picked.size) toast("本次只取前 ${picked.size} 张")

        busy = true
        canceled = false
        val dialog = progressDialog("分析参考图…")
        val tv = dialog.first
        val dlg = dialog.second
        dlg.show()

        executor.execute {
            val oldCount = def.seeds.size
            // 已知指纹 = 池内 + 本批已收；判重与「复用上次分析结果」共用这一份
            val usedFps = ArrayList<Long>(oldCount + picked.size)
            def.seeds.forEach { usedFps.add(it.fingerprint) }
            val pendingByFp = HashMap<Long, RefSeed>()
            ModelRefStore.loadPending(this, styleId).forEach {
                if (it.fingerprint != 0L) pendingByFp[it.fingerprint] = it
            }

            val seeds = ArrayList<RefSeed>(picked.size)
            // 与 seeds 同序：只有真正进池的新图才有缩略图，下标才能对齐
            val okUris = ArrayList<Uri>(picked.size)
            var failed = 0
            var dup = 0
            var reused = 0
            for ((i, uri) in picked.withIndex()) {
                if (canceled) break
                mainHandler.post { tv.text = "分析参考图 ${i + 1}/${picked.size}" }
                val fp = try {
                    RefAnalyzer.fingerprintOf(this, uri)
                } catch (t: Throwable) {
                    0L
                }
                if (ModelPool.isDuplicate(fp, usedFps)) { dup++; continue }
                // 断点续跑：上一轮取消/失败前已经算过同一张，直接用（分析是最慢的一段）
                val cached = if (fp != 0L) pendingByFp.remove(fp) else null
                if (cached != null) {
                    seeds.add(cached)
                    okUris.add(uri)
                    usedFps.add(fp)
                    reused++
                    continue
                }
                val seed = try {
                    RefAnalyzer.analyze(this, uri)
                } catch (t: Throwable) {
                    DiagLog.e("ModelDetail", "参考图分析异常 $uri", t)
                    null
                }
                if (seed == null) { failed++; continue }
                seeds.add(seed)
                okUris.add(uri)
                usedFps.add(seed.fingerprint)
                // 逐张落 pending：中途取消/进程被杀后，重选同一批照片不必重新分析
                if (seed.fingerprint != 0L) ModelRefStore.appendPending(this, styleId, seed)
            }
            if (canceled) { mainHandler.post { dlg.dismiss(); busy = false }; return@execute }
            if (seeds.isEmpty()) {
                mainHandler.post {
                    dlg.dismiss(); busy = false
                    toast(emptyReason(dup, failed))
                }
                return@execute
            }

            // 超上限：按「与池内其它图最相似」剔除，新图同样可能被剔（说明它确实冗余）
            val combined = ArrayList<RefSeed>(oldCount + seeds.size)
            combined.addAll(def.seeds)
            combined.addAll(seeds)
            val victims = ModelPool.evictPlan(combined, (combined.size - ModelPool.MAX_TOTAL).coerceAtLeast(0))
            val survivors = ArrayList<Int>(combined.size - victims.size)
            var vi = 0
            for (idx in combined.indices) {
                if (vi < victims.size && victims[vi] == idx) { vi++; continue }
                survivors.add(idx)
            }
            val finalSeeds = survivors.map { combined[it] }
            val oldSurvivors = survivors.filter { it < oldCount }
            val evictedOld = victims.count { it < oldCount }
            val added = survivors.size - oldSurvivors.size

            if (added == 0) {
                // 新图全部被判冗余：池与版本都不动，省掉一次烘焙
                mainHandler.post {
                    dlg.dismiss(); busy = false
                    toast("这 ${seeds.size} 张和池内参考高度相似，参考池保持不变")
                }
                return@execute
            }

            mainHandler.post { tv.text = "总共 ${finalSeeds.size} 张参考，开始拟合…" }
            val vid = "v_" + System.currentTimeMillis().toString(36)
            val t0 = System.currentTimeMillis()
            // 新图副本先按「合并池下标」落盘：拟合按 finalSeeds 顺序读全部参考。
            // 此时池还没提交，只会往高下标区新增文件，不会覆盖旧图（失败也就多几个孤儿副本）
            survivors.forEachIndexed { _, oldIdx ->
                if (oldIdx >= oldCount) saveRefThumb(oldIdx, okUris[oldIdx - oldCount])
            }
            var paramFile = ""
            val paramBytes = ModelFitter.fitFromThumbs(this, styleId, survivors)
            if (paramBytes != null) paramFile = ModelParamStore.write(this, styleId, vid, paramBytes)
            var lutFile = ""
            var thetaFile = ""
            var ok = paramFile.isNotEmpty()
            if (ok) {
                DiagLog.i(
                    "ModelDetail",
                    "增强拟合成功 $paramFile ${System.currentTimeMillis() - t0}ms " +
                        "参考 ${finalSeeds.size} 张（新增 $added，淘汰 $evictedOld，跳过重复 $dup，复用 $reused）"
                )
            } else {
                // 缺参考副本（M4 之前建的模型）或拟合退化：退回 M5 烘焙，行为与今天一致
                lutFile = "lut_${styleId}_${vid}.bin"
                ok = CustomLutBaker.bake(this, finalSeeds, lutFile)
                DiagLog.i(
                    "ModelDetail",
                    "增强烘焙${if (ok) "成功" else "失败"} $lutFile ${System.currentTimeMillis() - t0}ms " +
                        "参考 ${finalSeeds.size} 张（新增 $added，淘汰 $evictedOld，跳过重复 $dup，复用 $reused）"
                )
                // M5：θ 快照（LUT 被 LRU 回收后按它逐字节重烘）
                if (ok) thetaFile = saveTheta(styleId, vid, finalSeeds)
            }

            if (ok && evictedOld > 0) {
                // 缩略图与池同序：整体重排旧图会删掉全文副本，新图必须按**最终下标**重写一遍。
                // 没有淘汰时下标不变，拟合前已按同一位置写过，不必再编码一次。
                ModelRefStore.remap(this, styleId, oldSurvivors, ModelRefStore.disabled(this, styleId))
                survivors.forEachIndexed { newIdx, oldIdx ->
                    if (oldIdx >= oldCount) saveRefThumb(newIdx, okUris[oldIdx - oldCount])
                }
            }

            mainHandler.post {
                dlg.dismiss()
                busy = false
                if (!ok) {
                    toast("生成失败，请重试（已分析的参考下次会直接复用）")
                    return@post
                }
                val now = System.currentTimeMillis()
                def.seeds.clear()
                def.seeds.addAll(finalSeeds)
                def.seedCount = def.seeds.size
                if (evictedOld > 0) {
                    // 版本记的是前缀张数：淘汰后要把每版的前缀长度映射到新下标空间
                    for (k in def.versions.indices) {
                        val v = def.versions[k]
                        val keep = survivors.count { it < v.seedCount.coerceIn(0, oldCount) }
                        def.versions[k] = v.copy(seedCount = keep)
                    }
                }
                def.versions.add(
                    StyleVersion(
                        id = vid,
                        createdAt = now,
                        label = "增强 +$added",
                        lutFile = lutFile,
                        thetaFile = thetaFile,
                        paramFile = paramFile,
                        seedCount = def.seeds.size,
                        source = VersionSource.ENHANCE
                    )
                )
                def.currentVersionId = vid
                CustomStyleStore.put(this, def)
                ModelRefStore.clearPending(this, styleId)
                // M5 回收放到新版本入库之后：保留集只在本线程读版本表，避开并发遍历
                val keepLuts = HashSet<String>()
                if (lutFile.isNotEmpty()) keepLuts.add(lutFile)
                def.versions.firstOrNull { it.id == def.currentVersionId }
                    ?.let { if (it.lutFile.isNotEmpty()) keepLuts.add(it.lutFile) }
                def.versions.forEach {
                    if (it.thetaFile.isEmpty() && it.lutFile.isNotEmpty()) keepLuts.add(it.lutFile)
                }
                executor.execute { ModelLutStore.evict(this@ModelDetailActivity, def.id, keepLuts) }
                toast(enhanceResult(added, evictedOld, dup, reused, failed))
                rebuild()
            }
        }
    }

    private fun emptyReason(dup: Int, failed: Int): String = when {
        dup > 0 && failed == 0 -> "这 $dup 张和已有参考图重复，未添加"
        dup > 0 -> "$dup 张与已有参考重复，其余 $failed 张无法解析"
        else -> RefAnalyzer.lastReject ?: "参考图无法解析，换几张试试"
    }

    private fun enhanceResult(added: Int, evictedOld: Int, dup: Int, reused: Int, failed: Int): String {
        val sb = StringBuilder("已新增版本（+$added 张")
        if (evictedOld > 0) sb.append("，替换 $evictedOld 张旧参考")
        if (dup > 0) sb.append("，跳过 $dup 张重复")
        if (reused > 0) sb.append("，复用 $reused 张已分析")
        if (failed > 0) sb.append("，$failed 张未采用")
        sb.append("）")
        return sb.toString()
    }

    // ---- 重新生成（让排除生效） ----

    private fun confirmRebake(def: CustomStyleDef) {
        if (busy) { toast("正在生成，请稍候"); return }
        if (!loadPool(def)) { toast("参考图池读取失败，请重试"); return }
        val disabled = ModelRefStore.disabled(this, styleId)
        if (disabled.isEmpty()) { toast("没有排除任何参考图"); return }
        val enabled = def.seeds.indices.filter { !disabled.contains(it) }
        if (enabled.isEmpty()) { toast("全部参考图都被排除了"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("重新生成")
            .setMessage("用剩余 ${enabled.size} 张参考图重新烘焙一个新版本（${disabled.size} 张已排除，旧版本仍可回退）。")
            .setPositiveButton("生成") { _, _ -> rebake(def, enabled) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rebake(def: CustomStyleDef, indexes: List<Int>) {
        val seeds = indexes.map { def.seeds[it] }
        busy = true
        canceled = false
        val (_, dlg) = progressDialog("重新烘焙（${seeds.size} 张参考）…")
        dlg.show()
        executor.execute {
            val vid = "v_" + System.currentTimeMillis().toString(36)
            val t0 = System.currentTimeMillis()
            var paramFile = ""
            val paramBytes = ModelFitter.fitFromThumbs(this, styleId, indexes)
            if (paramBytes != null) paramFile = ModelParamStore.write(this, styleId, vid, paramBytes)
            var lutFile = ""
            var thetaFile = ""
            var ok = paramFile.isNotEmpty()
            if (ok) {
                DiagLog.i("ModelDetail", "重烘拟合成功 $paramFile ${System.currentTimeMillis() - t0}ms")
            } else {
                // 缺参考副本或拟合退化：退回 M5 烘焙
                lutFile = "lut_${styleId}_${vid}.bin"
                ok = CustomLutBaker.bake(this, seeds, lutFile)
                DiagLog.i("ModelDetail", "重烘${if (ok) "成功" else "失败"} $lutFile ${System.currentTimeMillis() - t0}ms")
                if (ok) thetaFile = saveTheta(styleId, vid, seeds)
            }
            mainHandler.post {
                dlg.dismiss()
                busy = false
                if (!ok) { toast("生成失败，请重试"); return@post }
                def.versions.add(
                    StyleVersion(
                        id = vid,
                        createdAt = System.currentTimeMillis(),
                        label = "重新生成",
                        lutFile = lutFile,
                        thetaFile = thetaFile,
                        paramFile = paramFile,
                        seedCount = seeds.size,
                        source = VersionSource.ENHANCE
                    )
                )
                def.currentVersionId = vid
                CustomStyleStore.put(this, def)
                val keepLuts = HashSet<String>()
                if (lutFile.isNotEmpty()) keepLuts.add(lutFile)
                def.versions.firstOrNull { it.id == def.currentVersionId }
                    ?.let { if (it.lutFile.isNotEmpty()) keepLuts.add(it.lutFile) }
                def.versions.forEach {
                    if (it.thetaFile.isEmpty() && it.lutFile.isNotEmpty()) keepLuts.add(it.lutFile)
                }
                executor.execute { ModelLutStore.evict(this@ModelDetailActivity, def.id, keepLuts) }
                toast("已按 ${seeds.size} 张参考重新生成")
                rebuild()
            }
        }
    }

    /**
     * M5：把这次烘焙的汇总 θ 存成快照，返回快照文件名（失败空串）。
     * 只能后台线程调用（要聚合整池）。
     */
    private fun saveTheta(styleId: String, versionId: String, seeds: List<RefSeed>): String {
        val snap = CustomLutBaker.snapshotBytes(seeds) ?: return ""
        val name = ModelLutStore.writeTheta(this, styleId, versionId, snap)
        if (name.isEmpty()) DiagLog.w("ModelDetail", "θ 快照未落盘 $styleId/$versionId")
        return name
    }

    // ---- 改名 / 缩略图 ----

    private fun askRename(def: CustomStyleDef) {
        val input = EditText(this).apply {
            setText(def.name)
            setSelection(def.name.length)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) { toast("名称不能为空"); return@setPositiveButton }
                def.name = name.take(12)
                CustomStyleStore.put(this, def)
                rebuild()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 参考图缩略图：解码 → 缩到 240px → JPEG，落 [ModelRefStore] */
    private fun saveRefThumb(index: Int, uri: Uri) {
        try {
            val jpeg = ModelRefStore.thumbJpeg(this, uri)
            if (jpeg == null) {
                DiagLog.w("ModelDetail", "参考图缩略图解码失败 $index")
                return
            }
            ModelRefStore.saveThumb(this, styleId, index, jpeg)
        } catch (t: Throwable) {
            DiagLog.w("ModelDetail", "参考图缩略图失败 $index: ${t.message}")
        }
    }

    // ---- 工具 ----

    private fun progressDialog(tip: String): Pair<TextView, androidx.appcompat.app.AlertDialog> {
        val tv = TextView(this).apply {
            text = tip
            textSize = 13f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(12), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(
                ProgressBar(this@ModelDetailActivity, null, android.R.attr.progressBarStyleHorizontal)
                    .apply { isIndeterminate = true },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
            )
            addView(tv)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("生成中")
            .setView(box)
            .setNegativeButton("取消") { _, _ -> canceled = true }
            .setCancelable(false)
            .create()
        return tv to dlg
    }

    private fun fmtTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun roundedRect(color: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

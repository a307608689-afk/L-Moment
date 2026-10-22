package com.leicamoments.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import com.codex.leicaapp.AlexaLut
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 模型色彩：独立界面（库 / 选择 / 新建 / 管理）。
 *
 * 与「我的色彩」的关系见 `docs/模型色彩方案.md`：
 * 模型不进 [FilterCategory]，只在主界面留一张「当前模型」临时卡；本界面负责挑选与落盘。
 *
 * 三种进入方式：
 * - 无 [EXTRA_MANAGE_ID]：**选择模式**，点卡片 → `RESULT_OK` + [EXTRA_PICKED_ID] → 主界面套用；
 * - 带 [EXTRA_MANAGE_ID]：**管理模式**（主界面长按模型卡进来），点卡片只弹管理菜单；
 * - 选择模式 + [EXTRA_AUTO_CREATE]（「我的色彩」新建卡进来）：落地即弹「新建」对话框，
 *   建完走选择模式的自动回传，取消则留在库里。
 */
class ModelActivity : AppCompatActivity() {

    companion object {
        /** 管理模式：进来后直接聚焦这个模型 */
        const val EXTRA_MANAGE_ID = "extra_manage_id"
        /** 选择模式回传：选中的模型 id */
        const val EXTRA_PICKED_ID = "extra_picked_id"
        /** 进来即弹「新建」对话框：主界面「我的色彩」新建卡直达 */
        const val EXTRA_AUTO_CREATE = "extra_auto_create"

        private const val BG = 0xFF0E0E0F.toInt()
        private const val CARD_BG = 0xFF1C1C1E.toInt()
        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val TEXT_DIM = 0xFF8E8E93.toInt()
        private const val ACCENT = 0xFFFF453A.toInt()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var grid: GridLayout
    private lateinit var emptyHint: TextView

    /** 管理模式携带的目标 id；null = 选择模式 */
    private var manageId: String? = null

    /** 正在新建：挡掉重复入口 */
    @Volatile
    private var creating = false
    @Volatile
    private var createCanceled = false

    private val pickRefs = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) createModel(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manageId = intent?.getStringExtra(EXTRA_MANAGE_ID)
        window.statusBarColor = BG
        setContentView(buildRoot())
        rebuild()
        // 「我的色彩」新建卡直达：落地即弹新建（选择模式下建完自动选中返回，见 createModel）
        if (intent?.getBooleanExtra(EXTRA_AUTO_CREATE, false) == true) askCreate()
    }

    override fun onDestroy() {
        createCanceled = true
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!creating) rebuild()
    }

    // ---- UI ----

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(this).apply {
            text = "模型色彩"
            textSize = 17f
            setTextColor(TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "＋ 新建"
            textSize = 14f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { askCreate() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        emptyHint = TextView(this).apply {
            text = "还没有模型色彩。\n点右上角「＋ 新建」，选 1~${ModelPool.MAX_BATCH} 张喜欢的成片，\n会蒸馏成一套可反复套用的固定色彩。"
            textSize = 13f
            setTextColor(TEXT_DIM)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(40))
            visibility = View.GONE
        }
        root.addView(emptyHint, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        grid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            setPadding(dp(8), dp(4), dp(8), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(grid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun rebuild() {
        grid.removeAllViews()
        val models = CustomStyleStore.allOf(StyleKind.MODEL).sortedByDescending { it.createdAt }
        emptyHint.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        models.forEach { grid.addView(modelCard(it), cellParams()) }
        if (models.isEmpty()) return
        // 管理模式：把目标卡滚到可见位置（列表短，直接提示即可）
        if (manageId != null && models.none { it.id == manageId }) toast("该模型已不存在")
    }

    private fun cellParams(): GridLayout.LayoutParams {
        // 注意参数顺序是 (rowSpec, columnSpec)：权重只给列，行仍按内容高度
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        )
        lp.width = 0
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.setMargins(dp(6), dp(6), dp(6), dp(6))
        return lp
    }

    private fun modelCard(def: CustomStyleDef): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(CARD_BG, dp(12).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(10))
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 圆角与卡片同心：外圆 dp(12) 减去内缩 dp(6)。背景本身带圆角，clipToOutline
            // 才能把 CENTER_CROP 的图一起裁圆；用 ColorDrawable（setBackgroundColor）
            // 的 outline 是直角，裁了也没效果。
            background = roundedRect(0xFF232326.toInt(), dp(6).toFloat())
            clipToOutline = true
            setImageBitmap(ColorShareImport.loadThumb(this@ModelActivity, def.id))
        }
        card.addView(
            img,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
        )
        card.addView(TextView(this).apply {
            text = def.name
            textSize = 14f
            setTextColor(TEXT)
            isSingleLine = true
            setPadding(0, dp(8), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "${CustomStyleStore.refCount(def)} 张 · ${def.versions.size} 版"
            textSize = 11f
            setTextColor(TEXT_DIM)
            isSingleLine = true
            setPadding(0, dp(2), 0, 0)
        })
        card.setOnClickListener {
            if (manageId != null) openDetail(def.id) else pickAndReturn(def.id)
        }
        card.setOnLongClickListener {
            showManage(def.id)
            true
        }
        return card
    }

    // ---- 选择 ----

    private fun pickAndReturn(id: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICKED_ID, id))
        finish()
    }

    /** 管理模式点卡片 → 详情（参考图 / 版本 / 加图增强） */
    private fun openDetail(id: String) {
        startActivity(
            Intent(this, ModelDetailActivity::class.java)
                .putExtra(ModelDetailActivity.EXTRA_STYLE_ID, id)
        )
    }

    // ---- 新建 ----

    private fun askCreate() {
        if (creating) { toast("正在生成模型，请稍候"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建模型色彩")
            .setMessage(
                "选择 1~${ModelPool.MAX_BATCH} 张你喜欢的成片作为参考。会先中和每张图的光源色温与曝光，" +
                    "再拟合它相对原片的色彩倾向，蒸馏成一套固定色彩。\n\n" +
                    "重复的照片会自动跳过；参考图本身不会被保存，只留统计量与 240px 缩略图（用于之后重新生成）；" +
                    "同样的模型可以反复套用、随时增强（累计上限 ${ModelPool.MAX_TOTAL} 张）。"
            )
            .setPositiveButton("选择参考图") { _, _ -> pickRefs.launch("image/*") }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createModel(uris: List<Uri>) {
        if (creating) return
        val picked = if (uris.size > ModelPool.MAX_BATCH) uris.take(ModelPool.MAX_BATCH) else uris
        if (uris.size > ModelPool.MAX_BATCH) {
            toast("单批最多 ${ModelPool.MAX_BATCH} 张，已取前 ${ModelPool.MAX_BATCH} 张")
        }
        creating = true
        createCanceled = false

        val tvProgress = TextView(this).apply {
            text = "正在分析参考图…"
            textSize = 13f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(12), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(
                ProgressBar(this@ModelActivity, null, android.R.attr.progressBarStyleHorizontal)
                    .apply { isIndeterminate = true },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
            )
            addView(tvProgress)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("生成模型")
            .setView(box)
            .setNegativeButton("取消") { _, _ -> createCanceled = true }
            .setCancelable(false)
            .create()
        dialog.show()

        executor.execute {
            val seeds = ArrayList<RefSeed>()
            // 与 seeds 同序：只有分析成功的图才进池，缩略图下标才能与 seeds 对齐
            val okUris = ArrayList<Uri>()
            val usedFps = ArrayList<Long>(picked.size)
            var failed = 0
            var dup = 0
            var rejectMsg: String? = null
            for ((i, uri) in picked.withIndex()) {
                if (createCanceled) break
                mainHandler.post { tvProgress.text = "分析参考图 ${i + 1}/${picked.size}" }
                val fp = try {
                    RefAnalyzer.fingerprintOf(this, uri)
                } catch (t: Throwable) {
                    0L
                }
                // 同一张照片选了两遍（或连拍几乎相同）只留一份，免得它把平均色彩带偏
                if (ModelPool.isDuplicate(fp, usedFps)) { dup++; continue }
                val seed = try {
                    RefAnalyzer.analyze(this, uri)
                } catch (t: Throwable) {
                    DiagLog.e("Model", "参考图分析异常 $uri", t)
                    null
                }
                if (seed != null) {
                    seeds.add(seed)
                    okUris.add(uri)
                    usedFps.add(seed.fingerprint)
                } else {
                    failed++
                    if (rejectMsg == null) rejectMsg = RefAnalyzer.lastReject
                }
            }

            if (createCanceled) {
                mainHandler.post { dialog.dismiss(); creating = false }
                return@execute
            }
            if (seeds.isEmpty()) {
                mainHandler.post {
                    dialog.dismiss()
                    creating = false
                    val why = rejectMsg
                    toast(
                        when {
                            dup > 0 && failed == 0 -> "这 $dup 张都是重复的同一张，再选几张不同的吧"
                            dup > 0 -> "$dup 张重复已跳过，其余 $failed 张无法解析"
                            else -> why ?: "参考图无法解析，换几张试试"
                        }
                    )
                }
                return@execute
            }

            val id = CustomStyleStore.newId()
            // 参考图副本先落盘：A 路线按副本拟合，顺序必须先于拟合
            okUris.forEachIndexed { k, u -> saveRefThumb(id, k, u) }

            // M6 影子数据：用刚落盘的 240px 缩略图编码 aff/knots；旧 LUT 仍照常生成，
            // 等应用侧接好新链再切换，避免这一版改坏出图。
            val refBitmaps = ModelRefStore.allThumbs(this, id)
                .sortedBy { it.first }
                .mapNotNull { (_, f) -> BitmapFactory.decodeFile(f.absolutePath) }
            // M7.1：逐图指纹 e_i 落盘（n×36 fp32），候选聚类与增量增强共用
            val embFlat = FloatArray(refBitmaps.size * 36)
            var embOk = true
            refBitmaps.forEachIndexed { i, bmp ->
                val e = AdaptiveColorNet.encodeStats(this, listOf(bmp))
                if (e != null && e.size >= 36) {
                    System.arraycopy(e, 0, embFlat, i * 36, 36)
                } else {
                    embOk = false
                }
            }
            if (embOk && refBitmaps.isNotEmpty()) {
                ModelRefStore.saveEmb(this, id, embFlat)
            }
            val netStyle0 = AdaptiveColorNet.encodeStyle(this, refBitmaps)
            // 黑白参考 → 单色风格（stage2 未学过黑白参考，需显式清零 a/b 通路）
            val netStyle = netStyle0?.let { AdaptiveColorNet.monoIfGray(refBitmaps, it).first }
            val netMono = netStyle0 != null && netStyle != netStyle0
            refBitmaps.forEach { it.recycle() }
            DiagLog.i("Model", "M6 编码${if (netStyle != null) "成功" else "不可用"} 参考 ${refBitmaps.size} 张${if (netMono) "（黑白参考→单色）" else ""}")

            mainHandler.post { tvProgress.text = "正在拟合模型（${seeds.size} 张参考）…" }
            val versionId = "v_" + System.currentTimeMillis().toString(36)
            val t0 = System.currentTimeMillis()
            var paramFile = ""
            val params = ModelFitter.fitFromThumbs(this, id, seeds.indices.toList())
            if (params != null) paramFile = ModelParamStore.write(this, id, versionId, params)
            DiagLog.i(
                "Model",
                "A 路线拟合${if (paramFile.isNotEmpty()) "成功" else "不可用，退回旧烘焙"} " +
                    "耗时 ${System.currentTimeMillis() - t0}ms 参考 ${seeds.size} 张"
            )

            var lutFile = ""
            var thetaFile = ""
            val ok: Boolean
            if (paramFile.isNotEmpty()) {
                ok = true
            } else {
                // 没有参考副本（M4 之前的老流程）或拟合退化：沿用 M5 烘焙，行为与今天一致
                lutFile = "lut_${id}_${versionId}.bin"
                ok = CustomLutBaker.bake(this, seeds, lutFile)
                DiagLog.i("Model", "旧烘焙${if (ok) "成功" else "失败"} $lutFile")
                // θ 快照是 LUT 被 LRU 回收后唯一的重建依据，必须与版本一同落盘
                if (ok) {
                    val snap = CustomLutBaker.snapshotBytes(seeds)
                    if (snap != null) thetaFile = ModelLutStore.writeTheta(this, id, versionId, snap)
                }
                if (!ok) ModelParamStore.purge(this, id)
            }

            mainHandler.post {
                dialog.dismiss()
                creating = false
                if (!ok) { toast("模型生成失败，请重试"); return@post }
                val now = System.currentTimeMillis()
                val def = CustomStyleDef(
                    id = id,
                    name = nextModelName(),
                    createdAt = now,
                    seeds = seeds,
                    versions = mutableListOf(
                        StyleVersion(
                            id = versionId,
                            createdAt = now,
                            label = "初始",
                            lutFile = lutFile,
                            thetaFile = thetaFile,
                            paramFile = paramFile,
                            seedCount = seeds.size,
                            source = VersionSource.INITIAL,
                            modelAff = netStyle?.aff ?: FloatArray(0),
                            modelKnots = netStyle?.knots ?: FloatArray(0)
                        )
                    ),
                    currentVersionId = versionId,
                    kind = StyleKind.MODEL,
                    modelAff = netStyle?.aff ?: FloatArray(0),
                    modelKnots = netStyle?.knots ?: FloatArray(0)
                )
                CustomStyleStore.put(this, def)
                rebuild()
                toast(
                    if (failed > 0 || dup > 0) {
                        buildString {
                            append("已创建「${def.name}」")
                            val extras = buildList {
                                if (netMono) add("黑白参考，输出为黑白")
                                if (dup > 0) add("跳过 $dup 张重复")
                                if (failed > 0) add("$failed 张未采用")
                            }
                            if (extras.isNotEmpty()) {
                                append("（").append(extras.joinToString("，")).append("）")
                            }
                        }
                    } else {
                        "已创建「${def.name}」"
                    }
                )
                // 选择模式下新建完直接选中返回，省掉「建完还要再点一次」
                if (manageId == null) pickAndReturn(id)
            }
        }
    }

    /** 默认名「模型 N」：与「我的色彩」的「色彩 N」互不干扰 */
    private fun nextModelName(): String {
        val used = CustomStyleStore.allOf(StyleKind.MODEL).mapTo(HashSet()) { it.name }
        var i = 1
        while (used.contains("模型 $i")) i++
        return "模型 $i"
    }

    /**
     * 参考图缩略图，一次两份：
     * - 第 0 张写 [ColorShareImport]（模型卡缩略图，模型没有「当前工作图」）
     * - 每张都写 [ModelRefStore]（详情页参考图网格，下标与 seeds 同序）
     */
    private fun saveRefThumb(styleId: String, index: Int, uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320 && bounds.outHeight / (sample * 2) >= 320) {
                sample *= 2
            }
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return
            val scale = 240f / max(src.width, src.height)
            val dst = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).roundToInt().coerceAtLeast(1),
                    (src.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            } else {
                src
            }
            val bos = ByteArrayOutputStream()
            dst.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            val jpeg = bos.toByteArray()
            if (index == 0) ColorShareImport.saveThumb(this, styleId, jpeg)
            ModelRefStore.saveThumb(this, styleId, index, jpeg)
            if (dst !== src) dst.recycle()
            src.recycle()
        } catch (t: Throwable) {
            DiagLog.w("Model", "参考图缩略图失败 $styleId: ${t.message}")
        }
    }

    // ---- 管理 ----

    private fun showManage(styleId: String) {
        val def = CustomStyleStore.byId(styleId) ?: run { toast("该模型已不存在"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(def.name)
            .setItems(arrayOf("查看参考图 / 版本", "重命名", "删除模型")) { _, which ->
                when (which) {
                    0 -> openDetail(def.id)
                    1 -> askRename(def)
                    2 -> askDelete(def)
                }
            }
            .show()
    }

    private fun askRename(def: CustomStyleDef) {
        val input = EditText(this).apply {
            setText(def.name)
            setSelection(def.name.length)
            inputType = InputType.TYPE_CLASS_TEXT
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

    private fun askDelete(def: CustomStyleDef) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${def.name}」？")
            .setMessage("会同时删除它的 LUT、颜色快照、A 路线参数与参考图统计量，已导出的照片不受影响。")
            .setPositiveButton("删除") { _, _ ->
            val dir = AlexaLut.customDir(this)
            def.versions.forEach { v ->
                if (v.lutFile.isNotEmpty()) {
                    AlexaLut.invalidate(AlexaLut.fileRef(v.lutFile))
                    runCatching { File(dir, v.lutFile).delete() }
                }
            }
            // 回收按前缀兜住「M5 LRU 留下/导入改名」的文件，并清掉 θ 快照
            ModelLutStore.purge(this, def.id)
            ModelParamStore.purge(this, def.id)
                CustomStyleStore.remove(this, def.id)
                ColorShareImport.deleteThumb(this, def.id)
                ModelRefStore.clear(this, def.id)
                SettingsStore.removePinned(this, def.id)
                toast("已删除「${def.name}」")
                if (manageId == def.id) manageId = null
                rebuild()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 小工具 ----

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

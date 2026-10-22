package com.leicamoments.app

import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.io.File

/**
 * 设置页：启动默认界面 + 「我的色彩」备份/导入。
 *
 * 修改后**不改变当前界面已选的分类**（避免打断正在看的图），仅影响下次冷启动。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var rowDefault: LinearLayout
    private lateinit var txtDefaultValue: TextView
    private lateinit var rowBackup: LinearLayout
    private lateinit var rowImport: LinearLayout
    private lateinit var rowShareImport: LinearLayout
    private lateinit var txtBackupSummary: TextView
    private lateinit var rowPinnedCross: LinearLayout
    private lateinit var txtPinnedCrossValue: TextView
    private lateinit var rowRaw: LinearLayout
    private lateinit var txtRawValue: TextView
    private lateinit var rowFullRes: LinearLayout
    private lateinit var txtFullResValue: TextView
    private lateinit var txtFullResHint: TextView
    private lateinit var rowM9Hires: LinearLayout
    private lateinit var txtM9HiresValue: TextView
    private lateinit var txtM9HiresHint: TextView
    private lateinit var rowLeicaBadge: LinearLayout
    private lateinit var txtLeicaBadgeValue: TextView
    private lateinit var txtLeicaBadgeHint: TextView

    /**
     * RAW 解码亮度档位（id, 标题, 副标题）。id 与 [SettingsStore.rawThrLevel] 的存储值一一对应。
     * 三档离散而非滑杆：解码档位一变就要重新解码，连续调节没有交互预算。
     */
    private val rawLevels = listOf(
        Triple("0", "标准", "追求质感偏暗"),
        Triple("1", "提亮", "白天更准"),
        Triple("2", "强力提亮", "夜景或偏亮")
    )

    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    // SAF：导出走「新建文档」，导入走「打开文档」，都不需要存储权限
    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) doExport(uri) }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doImport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CustomStyleStore.init(this)      // 「我的」分类的副标题要读自定义数量
        setContentView(R.layout.activity_settings)
        applyImmersiveBars()
        rowDefault = findViewById(R.id.rowDefault)
        txtDefaultValue = findViewById(R.id.txtDefaultValue)
        rowBackup = findViewById(R.id.rowBackup)
        rowImport = findViewById(R.id.rowImport)
        rowShareImport = findViewById(R.id.rowShareImport)
        txtBackupSummary = findViewById(R.id.txtBackupSummary)
        rowPinnedCross = findViewById(R.id.rowPinnedCross)
        txtPinnedCrossValue = findViewById(R.id.txtPinnedCrossValue)
        rowRaw = findViewById(R.id.rowRaw)
        txtRawValue = findViewById(R.id.txtRawValue)
        rowFullRes = findViewById(R.id.rowFullRes)
        txtFullResValue = findViewById(R.id.txtFullResValue)
        txtFullResHint = findViewById(R.id.txtFullResHint)
        rowM9Hires = findViewById(R.id.rowM9Hires)
        txtM9HiresValue = findViewById(R.id.txtM9HiresValue)
        txtM9HiresHint = findViewById(R.id.txtM9HiresHint)
        rowLeicaBadge = findViewById(R.id.rowLeicaBadge)
        txtLeicaBadgeValue = findViewById(R.id.txtLeicaBadgeValue)
        txtLeicaBadgeHint = findViewById(R.id.txtLeicaBadgeHint)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        rowDefault.setOnClickListener { showDefaultPicker() }
        rowRaw.setOnClickListener { showRawPicker() }
        rowFullRes.setOnClickListener { showFullResPicker() }
        rowM9Hires.setOnClickListener { showM9HiresPicker() }
        rowLeicaBadge.setOnClickListener { showLeicaBadgePicker() }
        rowBackup.setOnClickListener { startBackup() }
        rowImport.setOnClickListener { startImport() }
        rowShareImport.setOnClickListener { startShareImport() }
        rowPinnedCross.setOnClickListener { showPinnedCrossPicker() }
        renderValue()
        renderRawValue()
        renderFullRes()
        renderLeicaBadge()
        renderPinnedCross()
        renderBackupSummary()
    }

    /**
     * 置顶跨分类显示（默认关）。只影响滤镜条显示，不改置顶记录；
     * 回到主界面由 MainActivity.onResume 检测变化并重建滤镜条。
     */
    private fun renderPinnedCross() {
        txtPinnedCrossValue.text = if (SettingsStore.pinnedCrossCategory(this)) "开" else "关"
    }

    private fun showPinnedCrossPicker() {
        CategoryPopup.show(
            anchor = rowPinnedCross,
            items = listOf(
                CategoryPopup.Item("off", "关闭（默认）", "仅 L-Moment 与所属分类"),
                CategoryPopup.Item("on", "开启", "所有分类都排最前")
            ),
            selectedId = if (SettingsStore.pinnedCrossCategory(this)) "on" else "off",
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setPinnedCrossCategory(this, item.id == "on")
            renderPinnedCross()
        }
    }

    private fun renderValue() {
        val value = SettingsStore.defaultSetting(this)
        txtDefaultValue.text = if (value == SettingsStore.VALUE_LAST) {
            "记忆上次"
        } else {
            FilterCategory.fromId(value)?.title ?: FilterCategory.ALL.title
        }
    }

    private fun showDefaultPicker() {
        CategoryPopup.show(
            anchor = rowDefault,
            // 与滤镜条副标题同口径：L-Moment 与「我的色彩」都含模型色彩
            items = CategoryPopup.settingItems(CustomStyleStore.count()),
            selectedId = SettingsStore.defaultSetting(this),
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setDefaultSetting(this, item.id)
            renderValue()
        }
    }

    /** RAW 解码亮度：只影响之后导入的 RAW（解码发生在导入瞬间，不重解当前图）。 */
    private fun renderRawValue() {
        val level = SettingsStore.rawThrLevel(this).toString()
        txtRawValue.text = rawLevels.firstOrNull { it.first == level }?.second ?: "标准"
    }

    private fun showRawPicker() {
        CategoryPopup.show(
            anchor = rowRaw,
            items = rawLevels.map { CategoryPopup.Item(it.first, it.second, it.third) },
            selectedId = SettingsStore.rawThrLevel(this).toString(),
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setRawThrLevel(this, item.id.toIntOrNull() ?: 0)
            renderRawValue()
        }
    }

    /** 全像素导出（C4）：只影响之后导入的 50MP/200MP 源（导入瞬间决定是否保留全分辨率字节）。 */
    private fun renderFullRes() {
        val lowEnd = ImageUtils.lowEndReason(this)
        if (lowEnd != null) {
            // 低端机不给开关：像素预算分档会强制回落，留着可点的开关只会误导
            txtFullResValue.text = "自动关闭"
            rowFullRes.isEnabled = false
            rowFullRes.alpha = 0.55f
            txtFullResHint.text = "$lowEnd，统一按 4096×3072 工作版导出"
        } else {
            rowFullRes.isEnabled = true
            rowFullRes.alpha = 1f
            txtFullResValue.text = if (SettingsStore.fullResEnabled(this)) "开" else "关"
            txtFullResHint.text = "50MP / 200MP 图的最高导出像素：\n" +
                    "· 原图（不套滤镜）、LUT 色彩、M9+ / M10-R → 原始像素\n" +
                    "· 模型色彩 → 原始像素（50MP 源；200MP 源按 1280 万）\n" +
                    "· M9、M9D → 约 2400 万（4240×5632，整图链无条带伪影）\n" +
                    "· SA、LP9、自定义 LUT → 1280 万（4096×3072）\n" +
                    "开关只对之后导入的图片生效；开启后耗时与内存更高"
        }
        // 超高分辨率档依赖全像素开关与内存档位，随之联动灰态
        renderM9HiRes()
    }

    /**
     * M9 超高分辨率档（8192 / 50MP，实验）：仅在全像素导出开启且非低端机时可选。
     * 默认关——50MP 位图上的 native 暖调稳定性未经量产验证，失败会自动回落 24MP 档。
     */
    private fun renderM9HiRes() {
        val lowEnd = ImageUtils.lowEndReason(this)
        val fullOn = SettingsStore.fullResEnabled(this)
        if (lowEnd != null) {
            txtM9HiresValue.text = "不可用"
            rowM9Hires.isEnabled = false
            rowM9Hires.alpha = 0.55f
            txtM9HiresHint.text = "$lowEnd，统一按 4096×3072 工作版导出"
        } else if (!fullOn) {
            txtM9HiresValue.text = "不可用"
            rowM9Hires.isEnabled = false
            rowM9Hires.alpha = 0.55f
            txtM9HiresHint.text = "需先开启「全像素导出」；开启后仅 M9 / M9D 生效"
        } else {
            rowM9Hires.isEnabled = true
            rowM9Hires.alpha = 1f
            txtM9HiresValue.text = if (SettingsStore.m9HiResEnabled(this)) "开" else "关"
            txtM9HiresHint.text = "仅 M9 / M9D 生效：50MP 源零降采样、200MP 出 50MP" +
                    "（8192×6144），耗时约 20s；失败自动回落 24MP 档"
        }
    }

    private fun showM9HiresPicker() {
        if (ImageUtils.lowEndReason(this) != null) {
            toast("本机内存较低，已自动关闭全像素导出")
            return
        }
        if (!SettingsStore.fullResEnabled(this)) {
            toast("请先开启「全像素导出」")
            return
        }
        CategoryPopup.show(
            anchor = rowM9Hires,
            items = listOf(
                CategoryPopup.Item("on", "开启（实验）", "50MP 满血 · 约 20s"),
                CategoryPopup.Item("off", "关闭（推荐）", "24MP · 约 12s")
            ),
            selectedId = if (SettingsStore.m9HiResEnabled(this)) "on" else "off",
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setM9HiResEnabled(this, item.id == "on")
            renderM9HiRes()
        }
    }

    private fun showFullResPicker() {
        if (ImageUtils.lowEndReason(this) != null) {
            toast("本机内存较低，已自动关闭全像素导出")
            return
        }
        CategoryPopup.show(
            anchor = rowFullRes,
            items = listOf(
                CategoryPopup.Item("on", "开启（推荐）", "保全部像素"),
                CategoryPopup.Item("off", "关闭", "省时省内存")
            ),
            selectedId = if (SettingsStore.fullResEnabled(this)) "on" else "off",
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setFullResEnabled(this, item.id == "on")
            renderFullRes()
        }
    }

    /**
     * 徕卡徽章（默认开）：导出是否写入徕卡识别元数据（EXIF 三私有 tag + FFE4，见 LeicaMark）。
     * 关闭时导出不写徽章，并剥离源图自带的徽章元数据（含徕卡模式直出 JPG 原生那份）；
     * 只影响之后导出的文件，已导出的不受影响。
     */
    private fun renderLeicaBadge() {
        val on = SettingsStore.leicaBadgeEnabled(this)
        txtLeicaBadgeValue.text = if (on) "开" else "关"
        txtLeicaBadgeHint.text = if (on) {
            "导出写入徕卡识别元数据，相册显示「徕卡」徽章"
        } else {
            "导出无徽章，并清除源图自带的徽章元数据\n机型、拍摄参数、GPS 等 EXIF 不受影响"
        }
    }

    private fun showLeicaBadgePicker() {
        CategoryPopup.show(
            anchor = rowLeicaBadge,
            items = listOf(
                CategoryPopup.Item("on", "开启（默认）", "导出带徕卡徽章"),
                CategoryPopup.Item("off", "关闭", "导出无徽章，清除徽章元数据")
            ),
            selectedId = if (SettingsStore.leicaBadgeEnabled(this)) "on" else "off",
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setLeicaBadgeEnabled(this, item.id == "on")
            renderLeicaBadge()
        }
    }

    // ---- 我的色彩 备份 / 导入 ----

    /** 备份行的小字跟着库里实际内容走，用户点之前就能知道会导出多少。 */
    private fun renderBackupSummary() {
        val styles = CustomStyleStore.all()
        txtBackupSummary.text = if (styles.isEmpty()) {
            "当前没有自定义色彩。备份会导出全部色彩及其全部版本"
        } else {
            val versions = styles.fold(0) { acc, def -> acc + def.versions.size }
            "当前 ${styles.size} 个色彩 / $versions 个版本，导出为单个 .lmc 文件"
        }
    }

    private fun startBackup() {
        if (busy) {
            toast("正在处理，请稍候")
            return
        }
        if (CustomStyleStore.count() == 0) {
            toast("还没有自定义色彩，无需备份")
            return
        }
        try {
            createBackup.launch(ColorBackup.defaultFileName())
        } catch (t: Throwable) {
            toast("无法打开保存位置")
        }
    }

    private fun startImport() {
        if (busy) {
            toast("正在处理，请稍候")
            return
        }
        try {
            // 不限 MIME：.lmc 在各家文件管理器里被识别成什么类型不确定
            openBackup.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            toast("无法打开文件选择器")
        }
    }

    private fun doExport(uri: Uri) {
        busy = true
        toast("正在备份…")
        Thread {
            val msg = try {
                val n = contentResolver.openOutputStream(uri)?.use { out ->
                    ColorBackup.export(this, out)
                } ?: throw IllegalStateException("无法写入所选位置")
                "已备份 $n 个色彩"
            } catch (t: Throwable) {
                DiagLog.w("Custom", "备份失败: ${t.message}")
                "备份失败：" + (t.message ?: "未知错误")
            }
            ui.post {
                busy = false
                toast(msg)
            }
        }.start()
    }

    /**
     * 导入文件。先落一份临时副本再读容器里的色彩名：
     * - 1 条（别人分享来的成品）→ 先问「导入这个色彩？」，避免点错文件就改了库；
     * - 多条（自己的备份）→ 沿用一键恢复。
     * 副本还让「读元数据」和「真导入」都能各自开一次 zip，不必把流缓存进内存。
     */
    private fun doImport(uri: Uri) {
        busy = true
        toast("正在读取文件…")
        Thread {
            val tmp = File(cacheDir, "import_" + System.currentTimeMillis() + ".lmc")
            val names = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("无法读取该文件")
                ColorBackup.peekStyleNames(tmp)
            } catch (t: Throwable) {
                DiagLog.w("Custom", "读取待导入文件失败: ${t.message}")
                null
            }
            ui.post {
                if (names == null) {
                    busy = false
                    tmp.delete()
                    toast("导入失败：无法读取该文件")
                    return@post
                }
                if (names.size == 1) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("导入这个色彩？")
                        .setMessage("「${names[0]}」\n\n只新增这一条色彩，不改动你现有的色彩库。")
                        .setPositiveButton("导入") { _, _ -> runImportFile(tmp) }
                        .setNegativeButton("取消") { _, _ ->
                            busy = false
                            tmp.delete()
                        }
                        .setOnCancelListener {
                            busy = false
                            tmp.delete()
                        }
                        .show()
                } else {
                    runImportFile(tmp)
                }
            }
        }.start()
    }

    private fun runImportFile(file: File) {
        toast("正在导入…")
        Thread {
            var changed = false
            val msg = try {
                val result = file.inputStream().use { input -> ColorBackup.import(this, input) }
                changed = result.styles > 0 || result.versions > 0
                describe(result)
            } catch (t: Throwable) {
                DiagLog.w("Custom", "导入失败: ${t.message}")
                "导入失败：" + (t.message ?: "未知错误")
            }
            file.delete()
            ui.post {
                busy = false
                if (changed) renderBackupSummary()
                toast(msg)
            }
        }.start()
    }

    private fun describe(result: ColorBackup.Result): String {
        val parts = ArrayList<String>(4)
        if (result.styles > 0) parts.add("新增 ${result.styles} 个色彩")
        if (result.versions > 0) parts.add("新增 ${result.versions} 个版本")
        if (result.merged > 0) parts.add("合并到 ${result.merged} 个已有色彩")
        if (result.skipped > 0) parts.add("跳过 ${result.skipped} 个无效版本")
        if (parts.isEmpty()) return "备份里没有可导入的色彩"
        return "已导入：" + parts.joinToString("，")
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    // ---- 色彩分享指令 ----

    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /** 手动入口：自动识别被系统/输入法挡掉时，用户可以从设置页主动粘贴。 */
    private fun startShareImport() {
        if (busy) {
            toast("正在处理，请稍候")
            return
        }
        val text = readClipboard()
        if (text == null || !text.contains(ColorShareCode.PREFIX)) {
            toast("剪贴板里没有分享指令")
            return
        }
        val payload = ColorShareCode.decode(text)
        val sig = ColorShareCode.signature(text)
        if (payload == null || sig == null) {
            toast("这条分享指令已损坏")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导入分享色彩")
            .setMessage(
                "「${payload.name}」\n" +
                    "${payload.steps.size} 个版本 · ${payload.seeds.size} 张参考图\n\n" +
                    "只新增这条色彩，不改动你现有的色彩库。"
            )
            .setPositiveButton("导入") { _, _ -> doShareImport(payload, sig) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doShareImport(payload: ColorShareCode.Payload, sig: String) {
        busy = true
        toast("正在导入…")
        Thread {
            val result = try {
                ColorShareImport.import(this, payload)
            } catch (t: Throwable) {
                DiagLog.w("Share", "分享指令导入失败: ${t.message}")
                null
            }
            ui.post {
                busy = false
                val def = result?.def
                if (def == null) {
                    toast(result?.error ?: "导入失败，请重试")
                    return@post
                }
                // 记下来，避免回到主界面又被剪贴板自动识别成「待导入」
                SettingsStore.markShareSeen(this, sig)
                renderBackupSummary()
                val existed = result.existingId != null
                toast(if (existed) "「${def.name}」已在你的色彩库里" else "已导入「${def.name}」")
            }
        }.start()
    }

    /** 状态栏/导航栏与 App 黑底融为一体（与 MainActivity 一致） */
    private fun applyImmersiveBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

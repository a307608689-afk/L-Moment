package com.lmcamera.app

import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private lateinit var rowSavePath: LinearLayout
    private lateinit var txtSavePathValue: TextView
    private lateinit var txtSavePathHint: TextView
    private lateinit var rowExportTime: LinearLayout
    private lateinit var txtExportTimeValue: TextView
    private lateinit var txtExportTimeHint: TextView
    private lateinit var rowExportName: LinearLayout
    private lateinit var txtExportNameValue: TextView
    private lateinit var txtExportNameHint: TextView
    private lateinit var rowDeviceInfo: LinearLayout
    private lateinit var txtDeviceInfoValue: TextView
    private lateinit var txtDeviceInfoHint: TextView
    private lateinit var rowPseudoLinear: LinearLayout
    private lateinit var txtPseudoLinearValue: TextView
    private lateinit var txtPseudoLinearHint: TextView
    private lateinit var rowGpu: LinearLayout
    private lateinit var txtGpuValue: TextView
    private lateinit var txtGpuHint: TextView
    private lateinit var rowHdrBoost: LinearLayout
    private lateinit var txtHdrBoostValue: TextView
    private lateinit var txtHdrBoostHint: TextView
    private lateinit var aiServiceBox: LinearLayout
    private var aiAdmin: AiServiceAdmin? = null

    // ---- 页内页面栈（设置首屏 + 5 个三级页）----
    private lateinit var txtTitle: TextView
    private lateinit var pageHub: View
    private lateinit var pageDisplay: View
    private lateinit var pageQuality: View
    private lateinit var pageExport: View
    private lateinit var pageAi: View
    private lateinit var pageColors: View
    private lateinit var rowNavDisplay: LinearLayout
    private lateinit var txtNavDisplay: TextView
    private lateinit var rowNavQuality: LinearLayout
    private lateinit var txtNavQuality: TextView
    private lateinit var rowNavExport: LinearLayout
    private lateinit var txtNavExport: TextView
    private lateinit var rowNavAi: LinearLayout
    private lateinit var txtNavAi: TextView
    private lateinit var rowNavColors: LinearLayout
    private lateinit var txtNavColors: TextView

    /** 设置页内的三级页：首屏 [HUB] + 五个分类页（标题即顶栏文字） */
    private enum class SettingPage(val title: String) {
        HUB("设置"),
        DISPLAY("打开与显示"),
        QUALITY("画质与性能"),
        EXPORT("导出与元数据"),
        AI("AI 服务"),
        COLORS("我的色彩")
    }

    private var currentPage = SettingPage.HUB

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
        // 平板/折叠宽屏档：页面栈（hub+五分页）整列限宽 520dp 居中，行样式不动
        DialogWidth.clampColumn(findViewById(R.id.settingsPages))
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
        rowSavePath = findViewById(R.id.rowSavePath)
        txtSavePathValue = findViewById(R.id.txtSavePathValue)
        txtSavePathHint = findViewById(R.id.txtSavePathHint)
        rowExportTime = findViewById(R.id.rowExportTime)
        txtExportTimeValue = findViewById(R.id.txtExportTimeValue)
        txtExportTimeHint = findViewById(R.id.txtExportTimeHint)
        rowExportName = findViewById(R.id.rowExportName)
        txtExportNameValue = findViewById(R.id.txtExportNameValue)
        txtExportNameHint = findViewById(R.id.txtExportNameHint)
        rowDeviceInfo = findViewById(R.id.rowDeviceInfo)
        txtDeviceInfoValue = findViewById(R.id.txtDeviceInfoValue)
        txtDeviceInfoHint = findViewById(R.id.txtDeviceInfoHint)
        rowPseudoLinear = findViewById(R.id.rowPseudoLinear)
        txtPseudoLinearValue = findViewById(R.id.txtPseudoLinearValue)
        txtPseudoLinearHint = findViewById(R.id.txtPseudoLinearHint)
        rowGpu = findViewById(R.id.rowGpu)
        txtGpuValue = findViewById(R.id.txtGpuValue)
        txtGpuHint = findViewById(R.id.txtGpuHint)
        rowHdrBoost = findViewById(R.id.rowHdrBoost)
        txtHdrBoostValue = findViewById(R.id.txtHdrBoostValue)
        txtHdrBoostHint = findViewById(R.id.txtHdrBoostHint)
        aiServiceBox = findViewById(R.id.aiServiceBox)
        txtTitle = findViewById(R.id.txtTitle)
        pageHub = findViewById(R.id.pageHub)
        pageDisplay = findViewById(R.id.pageDisplay)
        pageQuality = findViewById(R.id.pageQuality)
        pageExport = findViewById(R.id.pageExport)
        pageAi = findViewById(R.id.pageAi)
        pageColors = findViewById(R.id.pageColors)
        rowNavDisplay = findViewById(R.id.rowNavDisplay)
        txtNavDisplay = findViewById(R.id.txtNavDisplay)
        rowNavQuality = findViewById(R.id.rowNavQuality)
        txtNavQuality = findViewById(R.id.txtNavQuality)
        rowNavExport = findViewById(R.id.rowNavExport)
        txtNavExport = findViewById(R.id.txtNavExport)
        rowNavAi = findViewById(R.id.rowNavAi)
        txtNavAi = findViewById(R.id.txtNavAi)
        rowNavColors = findViewById(R.id.rowNavColors)
        txtNavColors = findViewById(R.id.txtNavColors)
        rowNavDisplay.setOnClickListener { showPage(SettingPage.DISPLAY) }
        rowNavQuality.setOnClickListener { showPage(SettingPage.QUALITY) }
        rowNavExport.setOnClickListener { showPage(SettingPage.EXPORT) }
        rowNavAi.setOnClickListener { showPage(SettingPage.AI) }
        rowNavColors.setOnClickListener { showPage(SettingPage.COLORS) }
        // ← 钮与系统返回同语义：三级页先回首屏，首屏再退出设置
        findViewById<TextView>(R.id.btnBack).setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        rowDefault.setOnClickListener { showDefaultPicker() }
        rowRaw.setOnClickListener { showRawPicker() }
        rowFullRes.setOnClickListener { showFullResPicker() }
        rowM9Hires.setOnClickListener { showM9HiresPicker() }
        rowSavePath.setOnClickListener { showSavePathPicker() }
        rowExportTime.setOnClickListener { showExportTimePicker() }
        rowExportName.setOnClickListener { showExportNamePicker() }
        rowDeviceInfo.setOnClickListener { showDeviceInfoPicker() }
        rowPseudoLinear.setOnClickListener { showPseudoLinearPicker() }
        rowGpu.setOnClickListener { showGpuPicker() }
        rowHdrBoost.setOnClickListener { showHdrBoostPicker() }
        rowBackup.setOnClickListener { startBackup() }
        rowImport.setOnClickListener { startImport() }
        rowShareImport.setOnClickListener { startShareImport() }
        rowPinnedCross.setOnClickListener { showPinnedCrossPicker() }
        // 模型服务管理直接内嵌 AI 页（不再有中间弹层级）
        aiAdmin = AiServiceAdmin(this, aiServiceBox) { renderHub() }
        aiAdmin?.render()
        renderValue()
        renderRawValue()
        renderFullRes()
        renderSavePath()
        renderExportTime()
        renderExportName()
        renderDeviceInfo()
        renderPseudoLinear()
        renderGpu()
        renderHdrBoost()
        renderPinnedCross()
        renderBackupSummary()
        renderHub()
        // 旋转/重建后回到原三级页（否则一转屏就跳回首屏）
        savedInstanceState?.getString(KEY_PAGE)?.let { name ->
            SettingPage.values().firstOrNull { it.name == name }
                ?.takeIf { it != SettingPage.HUB }
                ?.let { showPage(it, animate = false) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, currentPage.name)
    }

    override fun onResume() {
        super.onResume()
        // 设置页外的操作（AI 面板换服务 / 备份导入）结束后回来刷新摘要
        if (this::aiServiceBox.isInitialized) aiAdmin?.render()
        if (this::txtNavDisplay.isInitialized) renderHub()
    }

    // ---- 页内页面栈：首屏 → 三级页 ----

    /** 返回：三级页先回首屏，首屏再退出设置（← 钮与系统返回/手势同语义）。 */
    private fun goBack() {
        if (currentPage == SettingPage.HUB) finish() else showPage(SettingPage.HUB)
    }

    private fun pageView(page: SettingPage): View = when (page) {
        SettingPage.HUB -> pageHub
        SettingPage.DISPLAY -> pageDisplay
        SettingPage.QUALITY -> pageQuality
        SettingPage.EXPORT -> pageExport
        SettingPage.AI -> pageAi
        SettingPage.COLORS -> pageColors
    }

    /**
     * 切页。旧页立即让位（避免两页同时在动互抢状态），新页淡入 + 24dp 横移进场，
     * 动效语言与 [CategoryPopup] 的 130ms 一致。回首屏时重算摘要（三级页里可能刚改过）。
     */
    private fun showPage(page: SettingPage, animate: Boolean = true) {
        if (page == currentPage) return
        val from = pageView(currentPage)
        val to = pageView(page)
        val forward = page != SettingPage.HUB
        val dx = 24 * resources.displayMetrics.density
        currentPage = page
        txtTitle.text = page.title
        from.visibility = View.GONE
        from.alpha = 1f
        from.translationX = 0f
        to.visibility = View.VISIBLE
        if (animate) {
            to.alpha = 0f
            to.translationX = if (forward) dx else -dx
            to.animate().alpha(1f).translationX(0f).setDuration(180).start()
        } else {
            to.alpha = 1f
            to.translationX = 0f
        }
        if (page == SettingPage.HUB) renderHub()
    }

    /**
     * 首屏摘要（只读）：把每个分类的关键状态露在入口上，
     * 否则设置搬进三级页后必须点进去才知道当前值。
     */
    private fun renderHub() {
        val def = SettingsStore.defaultSetting(this)
        txtNavDisplay.text = if (def == SettingsStore.VALUE_LAST) {
            "记忆上次"
        } else {
            FilterCategory.fromId(def)?.title ?: FilterCategory.ALL.title
        }

        txtNavQuality.text = "高位深 ${onOff(SettingsStore.pseudoLinearEnabled(this))}" +
                " · GPU ${onOff(SettingsStore.gpuAccelEnabled(this))}"

        val path = SettingsStore.saveRelativePath(this)
        val pathLabel = if (path == SettingsStore.DEFAULT_SAVE_REL_PATH) "默认目录" else path
        txtNavExport.text = "导出位置 $pathLabel"

        val provider = AiProviderStore.currentProvider(this)
        txtNavAi.text = when {
            provider == null -> "未配置"
            provider.model.isBlank() -> provider.name
            else -> provider.model
        }

        val styles = CustomStyleStore.all()
        txtNavColors.text = if (styles.isEmpty()) {
            "备份与分享"
        } else {
            val versions = styles.fold(0) { acc, s -> acc + s.versions.size }
            "${styles.size} 个色彩 / $versions 个版本"
        }
    }

    private fun onOff(on: Boolean) = if (on) "开" else "关"

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

    // ---- 保存位置 ----

    /**
     * 保存位置（MediaStore RELATIVE_PATH，默认 Pictures/L-Moment）。
     *
     * 四个预设档 + 「自定义目录…」（输入子目录名拼 Pictures/ 前缀，或直接给
     * Pictures/、DCIM/ 开头的完整写法）+ 「恢复默认」。
     * 顶层仅限 Pictures/DCIM 是 MediaStore 媒体集合目录的硬约束
     * （SD 卡任意目录需 SAF，未做）。
     */
    private fun renderSavePath() {
        val path = SettingsStore.saveRelativePath(this)
        val isDefault = path == SettingsStore.DEFAULT_SAVE_REL_PATH
        txtSavePathValue.text = if (isDefault) "默认" else path
        txtSavePathHint.text = if (isDefault) {
            "导出到相册 Pictures/L-Moment 目录（默认）\n子目录不存在会自动创建；只影响之后导出的文件"
        } else {
            "导出到相册 $path 目录\n子目录不存在会自动创建；只影响之后导出的文件"
        }
    }

    private fun showSavePathPicker() {
        CategoryPopup.show(
            anchor = rowSavePath,
            items = listOf(
                CategoryPopup.Item("default", "默认（Pictures/L-Moment）", "与历史版本一致的落点"),
                CategoryPopup.Item("pictures", "Pictures 根目录", "与系统截图等混在一起"),
                CategoryPopup.Item("dcim", "DCIM", "相册相机集合根目录"),
                CategoryPopup.Item("dcim_camera", "DCIM/Camera", "直接混入相机照片目录"),
                CategoryPopup.Item("custom", "自定义目录…", "Pictures 下的自定义子目录"),
                CategoryPopup.Item("reset", "恢复默认", "回到 Pictures/L-Moment")
            ),
            selectedId = when (SettingsStore.saveRelativePath(this)) {
                "Pictures/L-Moment" -> "default"
                "Pictures" -> "pictures"
                "DCIM" -> "dcim"
                "DCIM/Camera" -> "dcim_camera"
                else -> "custom"
            },
            centerOnAnchor = false
        ) { item ->
            when (item.id) {
                "default" -> {
                    SettingsStore.resetSaveRelativePath(this)
                    renderSavePath()
                }
                "pictures" -> {
                    SettingsStore.setSaveRelativePath(this, "Pictures")
                    renderSavePath()
                }
                "dcim" -> {
                    SettingsStore.setSaveRelativePath(this, "DCIM")
                    renderSavePath()
                }
                "dcim_camera" -> {
                    SettingsStore.setSaveRelativePath(this, "DCIM/Camera")
                    renderSavePath()
                }
                "custom" -> promptCustomSavePath()
                "reset" -> {
                    SettingsStore.resetSaveRelativePath(this)
                    renderSavePath()
                    Toast.makeText(this, "已恢复默认保存位置", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 「自定义目录…」输入弹窗：输入子目录名拼 `Pictures/<名>`，
     * 也接受 `Pictures/`、`DCIM/` 开头的完整写法。经 [SettingsStore.setSaveRelativePath]
     * 校验，非法输入拒绝保存并提示。
     */
    private fun promptCustomSavePath() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "如：LM，或 Pictures/LM、DCIM/LM"
            setText(SettingsStore.saveRelativePath(this@SettingsActivity))
            setSingleLine(true)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义保存目录")
            .setMessage("输入 Pictures 下的子目录名（自动创建），\n或 Pictures/、DCIM/ 开头的完整路径。")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (SettingsStore.setSaveRelativePath(this, value)) {
                    renderSavePath()
                    Toast.makeText(this, "保存位置：${SettingsStore.saveRelativePath(this)}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "路径不合法：顶层须为 Pictures 或 DCIM，且不含特殊字符", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 导出时间 ----

    /**
     * 导出时间（默认按图片原本时间）。
     * original：相册按源图拍摄时间显示与排序（DATE_TAKEN 取原图 EXIF 时间）；
     * now：EXIF 三个时间 tag 重写为导出时刻，如刚拍的照片。
     * 文件属性时间（date_added）始终为导出时刻——系统强制写、应用不可改。
     */
    private fun renderExportTime() {
        val original = SettingsStore.exportTimeOriginal(this)
        txtExportTimeValue.text = if (original) "按图片原本时间" else "按当下时间"
        txtExportTimeHint.text = if (original) {
            "相册按源图拍摄时间显示与排序\n文件属性时间始终为导出时刻（系统机制）\nGPS、机型、拍摄参数不受影响"
        } else {
            "导出的照片在相册里如刚拍摄（EXIF 时间=导出时刻）\n文件属性时间始终为导出时刻（系统机制）\nGPS、机型、拍摄参数不受影响"
        }
    }

    private fun showExportTimePicker() {
        CategoryPopup.show(
            anchor = rowExportTime,
            items = listOf(
                CategoryPopup.Item("original", "按图片原本时间导出", "相册按源图拍摄时间显示与排序"),
                CategoryPopup.Item("now", "按当下时间信息导出", "导出的照片在相册里如刚拍摄")
            ),
            selectedId = if (SettingsStore.exportTimeOriginal(this)) "original" else "now",
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setExportTimeMode(this, item.id)
            renderExportTime()
        }
    }

    /**
     * 导出文件名四档预设（档位/起名逻辑在 ExportNamer）。
     * 副标题实时渲染示例文件名，选完档立刻能预览效果；
     * 时间跟随「导出时间」档、机型跟随「设备标识」档，这两个设置变化后回来本页自动刷新。
     */
    private fun renderExportName() {
        val fmt = SettingsStore.exportNameFormat(this)
        txtExportNameValue.text = SettingsStore.exportNameFormatLabel(fmt)
        txtExportNameHint.text = "示例：${ExportNamer.previewSample(this, fmt)}\n" +
                "非默认档重名自动加序号（_2、_3…）；时间跟随「导出时间」档，机型跟随「设备标识」档\n" +
                "只影响之后导出的文件；已导出的不受影响"
    }

    private fun showExportNamePicker() {
        CategoryPopup.show(
            anchor = rowExportName,
            items = listOf(
                CategoryPopup.Item(
                    ExportNamer.FMT_LEGACY,
                    "默认（LM · 色彩 · 时间戳）",
                    "LM_色彩_毫秒时间戳，与历史版本一致"
                ),
                CategoryPopup.Item(
                    ExportNamer.FMT_MODEL_TIME_STYLE,
                    "机型 · 时间 · 色彩",
                    "机型跟随「设备标识」档，时间跟随「导出时间」档"
                ),
                CategoryPopup.Item(
                    ExportNamer.FMT_TIME_STYLE,
                    "时间 · 色彩",
                    "按拍摄/导出时间命名，简洁可读"
                ),
                CategoryPopup.Item(
                    ExportNamer.FMT_ORIGNAME_STYLE,
                    "原图名 · 色彩",
                    "保留源图文件名，便于追溯原图"
                )
            ),
            selectedId = SettingsStore.exportNameFormat(this),
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setExportNameFormat(this, item.id)
            renderExportName()
        }
    }

    /**
     * 导出设备标识（默认跟随原图）。
     *
     * 历史版本把徽章元数据里的机型字段写死成 Xiaomi 15 Ultra，导致任何手机拍的图
     * 导出后都显示该机型；本档位让机型跟随源图 EXIF，另留旧行为与本机型号两个回退档。
     */
    private fun renderDeviceInfo() {
        val mode = SettingsStore.deviceInfoMode(this)
        txtDeviceInfoValue.text = when (mode) {
            SettingsStore.DEVICE_INFO_LOCAL -> "本机型号"
            else -> "跟随原图"
        }
        txtDeviceInfoHint.text = when (mode) {
            SettingsStore.DEVICE_INFO_LOCAL ->
                "写入当前手机型号（Build.MODEL）\n只影响设备标识，拍摄参数与 GPS 不受影响"
            else ->
                "导出图片的机型取自原图 EXIF；原图无 EXIF 时使用本机型号\n只影响设备标识，拍摄参数与 GPS 不受影响"
        }
    }

    private fun showDeviceInfoPicker() {
        CategoryPopup.show(
            anchor = rowDeviceInfo,
            items = listOf(
                CategoryPopup.Item(
                    SettingsStore.DEVICE_INFO_SOURCE,
                    "跟随原图（默认）",
                    "写入拍摄设备的真实机型"
                ),
                CategoryPopup.Item(
                    SettingsStore.DEVICE_INFO_LOCAL,
                    "本机型号",
                    "使用当前手机型号"
                )
            ),
            selectedId = SettingsStore.deviceInfoMode(this),
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setDeviceInfoMode(this, item.id)
            renderDeviceInfo()
        }
    }

    /**
     * 高位深工作域（伪 RAW，JPG/HEIF，**默认关**）：
     * 开 = 导入时建立 16-bit 线性基准，七档影调在 16 位域运算（预览与导出同源）；
     * 关 = 维持既有 8-bit 链路。只影响之后导入的图，不影响真 RAW 与 EXIF。
     */
    private fun renderPseudoLinear() {
        val on = SettingsStore.pseudoLinearEnabled(this)
        txtPseudoLinearValue.text = if (on) "开" else "关"
        txtPseudoLinearHint.text = if (on) {
            "JPG / HEIF 的影调在 16 位域运算，暗部过渡更连续\n" +
                "仅在常规导出（长边 ≤4096）生效；全像素直通与 M9 超高分辨率走各自链路\n" +
                "不影响 RAW、色彩风格与 EXIF（机型/参数/GPS 均保留）"
        } else {
            "关闭时 JPG / HEIF 走 8 位链路，与原版一致\n" +
                "真 RAW、全像素与 M9 超高分辨率导出均不受本开关影响"
        }
    }

    private fun showPseudoLinearPicker() {
        CategoryPopup.show(
            anchor = rowPseudoLinear,
            items = listOf(
                CategoryPopup.Item("off", "关闭（默认）", "8 位链路，与改前完全一致"),
                CategoryPopup.Item("on", "开启", "七档在 16 位域运算，暗部更连续")
            ),
            selectedId = if (SettingsStore.pseudoLinearEnabled(this)) "on" else "off",
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setPseudoLinearEnabled(this, item.id == "on")
            renderPseudoLinear()
        }
    }

    /**
     * GPU 加速（实验，默认关）。
     *
     * 关闭时完全不加载 GPU 相关资源，导出与改前一致；开启后只有完美档与超分快速档走 GPU。
     * 判负（本机 GPU 更慢 / 连续失败）时 [GpuNet] 会把开关自动关掉，这里刷新即可看到。
     */
    private fun renderGpu() {
        val on = SettingsStore.gpuAccelEnabled(this)
        txtGpuValue.text = if (on) "开" else "关"
        txtGpuHint.text = if (on) {
            "已开启：完美档与超分快速档走 GPU\n" +
                "若本机 GPU 反而更慢，会自动改用 CPU 并把本开关关掉"
        } else {
            "关闭（默认）：不加载 GPU，导出与改前完全一致\n" +
                "开启后只影响完美档与超分快速档；首次导出会多等几秒做一次本机对比"
        }
    }

    private fun showGpuPicker() {
        CategoryPopup.show(
            anchor = rowGpu,
            items = listOf(
                CategoryPopup.Item("off", "关闭（默认）", "不加载 GPU，与改前完全一致"),
                CategoryPopup.Item("on", "开启（实验）", "完美档与超分快速档加速；更慢会自动关掉")
            ),
            selectedId = if (SettingsStore.gpuAccelEnabled(this)) "on" else "off",
            centerOnAnchor = false
        ) { item ->
            val on = item.id == "on"
            SettingsStore.setGpuAccelEnabled(this, on)
            // 关掉时立刻递归回收会话与显存；开着的会话在下次导出开始时才建立
            if (!on) GpuNet.release()
            renderGpu()
        }
    }

    /**
     * HDR 输出强度：只影响导出时合成增益图的幅度，不改主图与色彩影调。
     * 与「徕卡徽章」正交；是否写 HDR 由主界面「HDR」按钮决定，这里只调幅度。
     */
    private fun renderHdrBoost() {
        val level = SettingsStore.hdrBoostLevel(this)
        txtHdrBoostValue.text = SettingsStore.hdrBoostLevelName(level)
        txtHdrBoostHint.text = hdrBoostLevelHint(level) +
                "\n需在主界面开启「HDR」按钮才会写入；不支持 HDR 的设备显示不变"
    }

    private fun hdrBoostLevelHint(level: Int): String = when (level) {
        0 -> "轻（默认）：1.0EV，全画幅轻微提亮，最保守"
        2 -> "强：2.5EV，幅度最大，可能超出设备峰值亮度"
        else -> "标准：1.6EV，对齐相机直出的 HDR 量级"
    }

    private fun showHdrBoostPicker() {
        CategoryPopup.show(
            anchor = rowHdrBoost,
            items = listOf(
                CategoryPopup.Item("0", "轻（默认）", "1.0EV · 最保守"),
                CategoryPopup.Item("1", "标准", "1.6EV · 对齐相机直出"),
                CategoryPopup.Item("2", "强", "2.5EV · 幅度最大")
            ),
            selectedId = SettingsStore.hdrBoostLevel(this).toString(),
            centerOnAnchor = false
        ) { item ->
            SettingsStore.setHdrBoostLevel(this, item.id.toIntOrNull() ?: 0)
            renderHdrBoost()
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

    private companion object {
        /** 旋转/重建时恢复所在三级页 */
        const val KEY_PAGE = "settings_page"
    }
}

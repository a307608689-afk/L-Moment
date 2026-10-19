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
    private lateinit var rowRaw: LinearLayout
    private lateinit var txtRawValue: TextView

    /**
     * RAW 解码亮度档位（id, 标题, 副标题）。id 与 [SettingsStore.rawThrLevel] 的存储值一一对应。
     * 三档离散而非滑杆：解码档位一变就要重新解码，连续调节没有交互预算。
     */
    private val rawLevels = listOf(
        Triple("0", "标准（推荐）", "与当前一致"),
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
        rowRaw = findViewById(R.id.rowRaw)
        txtRawValue = findViewById(R.id.txtRawValue)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        rowDefault.setOnClickListener { showDefaultPicker() }
        rowRaw.setOnClickListener { showRawPicker() }
        rowBackup.setOnClickListener { startBackup() }
        rowImport.setOnClickListener { startImport() }
        rowShareImport.setOnClickListener { startShareImport() }
        renderValue()
        renderRawValue()
        renderBackupSummary()
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

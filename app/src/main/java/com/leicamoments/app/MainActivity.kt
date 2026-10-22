package com.leicamoments.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import java.io.ByteArrayOutputStream
import java.io.File
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.codex.leicaapp.AlexaLut
import com.codex.leicaapp.IccProfile
import com.codex.leicaapp.LeicaMark
import com.codex.leicaapp.StyleA
import com.codex.leicaapp.StyleAFit
import com.leicamoments.app.AdaptiveColorNet.toMono
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private companion object {
        /** 增强预览注册的内存产物（不落盘），弹窗关闭时释放。 */
        const val MEM_PARAM_REF = "mem:enhance_par"
        const val MEM_LUT_REF = "mem:enhance_lut"

        /** ≥1 亿像素（2 亿级）才走「耗时确认 + 前台服务 + 用时弹窗」，50MP 不打扰 */
        const val BIG_EXPORT_PIXELS = 100_000_000L
    }

    private lateinit var imgPreview: ZoomableImageView
    private lateinit var txtEmpty: TextView
    private lateinit var txtStyleHint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var filterContainer: LinearLayout
    private lateinit var btnImport: TextView
    private lateinit var btnExport: TextView
    private lateinit var btnSuperRes: TextView
    private lateinit var btnSuperResFast: TextView
    private lateinit var btnPerfect: TextView
    private lateinit var btnAdjust: TextView
    private lateinit var btnCategory: View
    private lateinit var txtTitle: TextView
    private lateinit var imgTitleArrow: ImageView
    private lateinit var btnSettings: TextView
    private lateinit var txtCoach: TextView
    private lateinit var filterScroll: HorizontalScrollView
    private var sourceUri: Uri? = null                 // 导入源（完美档 RAW 完整管线需重读 DNG）
    private var sourceIsRaw = false
    private lateinit var adjPanel: LinearLayout
    // P3：七档调节滑杆（进度 0..20 ↔ 档位 -10..+10；拖动调档，双击标签复位到 0）
    private lateinit var sbBright: SeekBar
    private lateinit var sbLum: SeekBar
    private lateinit var sbHigh: SeekBar
    private lateinit var sbShadow: SeekBar
    private lateinit var sbBlack: SeekBar
    private lateinit var sbSat: SeekBar
    private lateinit var sbCon: SeekBar
    private lateinit var sbGlow: SeekBar
    private lateinit var txtBrightVal: TextView
    private lateinit var txtLumVal: TextView
    private lateinit var txtHighVal: TextView
    private lateinit var txtShadowVal: TextView
    private lateinit var txtBlackVal: TextView
    private lateinit var txtSatVal: TextView
    private lateinit var txtConVal: TextView
    private lateinit var txtGlowVal: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 调档预览专用队列：与共享 executor（导出烘焙/风格渲染等长任务）隔离，
    // 调 +/− 档时预览重算不再排在长任务后面
    private val adjExecutor = Executors.newSingleThreadExecutor()

    // P2：RAW 提亮/强力提亮档导入时的 16-bit 线性预览基准（曲线映射后、未量化）。
    // 调五档时若仍在原图卡片（无风格结果），直接在它上面应用 ToneCurve——
    // RAW 的 10/12/14-bit 数据一路保真，调阴影/高光不再有 8-bit 源量化色带
    private var rawLin16: RawToneMap.Lin16? = null
    private var rawLin16Level = 0

    // P2：导入时的 DNG 内嵌预览字节（导出重放 16-bit 五档时分位匹配必须用同一份，观感才一致）
    private var rawPreviewJpeg: ByteArray? = null

    // 状态
    private var originalBytes: ByteArray? = null      // 工作 JPEG 字节（导出用）
    private var workWidth = 0                         // 工作字节真实像素尺寸（超分提示按它估算）
    private var workHeight = 0
    private var originalBitmap: Bitmap? = null        // 预览尺寸原图（对比用）
    private var resultBitmap: Bitmap? = null          // 当前处理结果
    private var currentStyle: LeicaEngine.Style? = null // null = 原图
    private var currentCustomId: String? = null       // 当前选中的自定义色彩 id（与 currentStyle 互斥）
    private var processingGeneration = 0              // 代次，防竞态
    private var srBusy = false                        // 超分进行中（与滤镜/导出互斥）
    @Volatile
    private var exporting = false                     // 全像素大图导出进行中（前台服务保护，期间禁止超分/完美）
    @Volatile
    private var pendingHdr: HdrGainMap.HdrData? = null // 导入图自带的 HDR 增益图（导出时回嵌 UltraHDR）
    // C1：200MP 级全像素原图直通源（方向=1 的静态 JPEG 主图）。只服务「原图」档导出，
    // 不参与滤镜链路（引擎硬上限 4096×3072），因此与 originalBytes 并存。
    // 源以 cache 文件形式驻留磁盘（50MB 级字节不进 Java 堆），EXIF 只需头部几十 KB 副本。
    private var fullResFile: File? = null
    /**
     * 一次性兜底标志：M9 高分辨率导出（[exportDistillHiRes]）失败时置 true 并重入 [export]，
     * 让本次跳过全像素分支、直接走工作版原链路。成功后即被消费清零，不跨任务残留。
     */
    private var skipFullResOnce = false
    /** M6 全像素条带失败后回落工作版：仅用于把 toast 文案改成「已按工作版导出」。 */
    private var fullResFallbackOnce = false
    private var fullResExifHead: ByteArray? = null
    private var fullResWidth = 0
    private var fullResHeight = 0
    private var fullResHdr: HdrGainMap.HdrData? = null
    private var brightLevel = 0               // 亮度档（±10，0=不调；仅 RAW 导入显示步进器）
    private var exposureLevel = 0             // 曝光档（±10，0=不调；仅 RAW 导入显示步进器）
    private var highlightLevel = 0            // 高光档（±10，0=不调；仅 RAW 导入显示步进器）
    private var shadowLevel = 0               // 阴影档（±10，0=不调；仅 RAW 导入显示步进器）
    private var blackLevel = 0                // 黑色阶档（±10，0=不调；仅 RAW 导入显示步进器）
    private var satLevel = 0                  // 饱和度档（±10，0=不调）
    private var contrastLevel = 0             // 对比度档（±10，0=不调）
    private var glowLevel = 0                 // 柔光档（±10，0=不调；正=Orton 柔雾，负=清晰化）
    private var liftedOriginal: Bitmap? = null  // 原图调整显示版（曝光/阴影任一≠0 时存在）
    private var liftedResult: Bitmap? = null    // 滤镜结果调整显示版（同上）
    private var liftGen = 0                     // 调整重算代次，防与切滤镜/换图竞态

    // 缩略图按 style.id 键控：分类过滤后卡片下标会变，不能再按下标回填
    private val imgByStyle = mutableMapOf<String, ImageView>()
    private val cardViews = mutableListOf<View>()
    private val cardLabels = mutableListOf<TextView>()
    private val cardEntries = mutableListOf<FilterEntry>()   // 与 cardViews 一一对应（原图/自定义/内置统一）
    private val thumbCache = mutableMapOf<String, Bitmap>()       // 已生成缩略图，切回分类秒显
    private val origThumbKey = FilterEntry.ORIG_ID                // 「原图」卡在缓存/映射里的键
    /** 色彩卡选中框圆角（外圈） */
    private val cardCorner by lazy { dp(12).toFloat() }
    /** 卡片左右内边距：选中框与缩略图之间的间隙，也是同心圆角的内外差 */
    private val cardInsetH by lazy { dp(6) }
    /** 缩略图圆角（内圈）= 外圈 − 间隙，两圆心重合才是同心圆角 */
    private val thumbCorner by lazy { cardCorner - cardInsetH.toFloat() }
    private var currentCategory = FilterCategory.ALL
    private var categoryPopup: PopupWindow? = null
    private var coachVisible = false
    /** 上次同步的色彩库代次：设置页导入备份后回到这里，据此决定要不要重建滤镜条 */
    private var seenRevision = -1
    /** 上次同步的「置顶跨分类」开关：设置页改了开关回这里，按新口径重建滤镜条 */
    private var seenPinnedCross: Boolean? = null

    /** 分享码自动识别：本次会话里已被用户点「取消」的指令，不再重复打扰 */
    private var declinedShareSig: String? = null
    /** 正在导入的指令：确认框关闭会立刻触发一次焦点回调，用它挡住重复弹框 */
    private var importingShareSig: String? = null
    private var sharePromptShowing = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    // 增强（S4）：在游标版本的基础上追加参考图，先预览强度再落盘
    private val pickEnhanceImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val styleId = pendingEnhanceId
        pendingEnhanceId = null
        if (styleId != null && !uris.isNullOrEmpty()) enhanceFromRefs(styleId, uris)
    }
    // 模型色彩：独立界面（ModelActivity）选中后回传 styleId
    private val pickModel = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            r.data?.getStringExtra(ModelActivity.EXTRA_PICKED_ID)?.let { onModelPicked(it) }
        }
    }
    /** 分类弹窗里「模型色彩」这一行的 id：它是跨界面跳转，不是分类 */
    private val modelEntryId = "model_entry"

    private var pendingEnhanceId: String? = null      // 选参考图期间记住目标色彩
    private var enhancing = false                     // 增强生成中（挡重复入口）
    private var splitting = false                     // 复制版本成新色彩中（挡重复入口）
    @Volatile
    private var enhanceCanceled = false
    private val maxSeedPool = 50                      // 单个色彩的参考图池上限（跨版本累计）
    private val maxVersions = 6                       // 初始版 + 5 次增强，初始版永不删

    private val maxRefs = 10                          // 单次增强最多新增的参考图数
    private val customThumbPending = mutableSetOf<String>()  // 自定义卡缩略图在途，防空转

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashBox()
        DiagLog.init(packageManager.getPackageInfo(packageName, 0).versionName ?: "?")
        CustomStyleStore.init(this)      // 滤镜条要读自定义色彩名称，必须先加载
        cleanupPendingLuts()             // 清掉上次增强中断留下的预览临时文件
        setContentView(R.layout.activity_main)
        applyImmersiveBars()
        bindViews()
        setupCategoryEntry()
        btnImport.setOnClickListener { requestMediaLocationThen { pickImage.launch("image/*") } }
        btnExport.setOnClickListener { export() }
        imgPreview.onCompareEnter = { if (hasColor()) originalBitmap?.let { imgPreview.setImageBitmap(it) } }
        imgPreview.onCompareExit = { if (hasColor()) refreshPreviewImage() }
        btnSuperRes.setOnClickListener { confirmSuperRes(false) }
        btnSuperResFast.setOnClickListener { confirmSuperRes(true) }
        btnPerfect.setOnClickListener { onPerfectClicked() }
        setupSlider(sbBright, txtBrightVal, { brightLevel }, { applyBrightChange(it) })
        setupSlider(sbLum, txtLumVal, { exposureLevel }, { applyExposureChange(it) })
        setupSlider(sbHigh, txtHighVal, { highlightLevel }, { applyHighChange(it) })
        setupSlider(sbShadow, txtShadowVal, { shadowLevel }, { applyShadowChange(it) })
        setupSlider(sbBlack, txtBlackVal, { blackLevel }, { applyBlackChange(it) })
        setupSlider(sbSat, txtSatVal, { satLevel }, { applySatChange(it) })
        setupSlider(sbCon, txtConVal, { contrastLevel }, { applyContrastChange(it) })
        setupSlider(sbGlow, txtGlowVal, { glowLevel }, { applyGlowChange(it) })
        btnAdjust.setOnClickListener { toggleAdjPanel() }
        SuperResTask.addListener(srListener)
        // 分享冷启动：仅首次创建处理（系统重建/配置变化不重复导入）
        if (savedInstanceState == null) handleSharedIntent(intent)
        if (savedInstanceState == null) applyDebugAutoImport()
        if (!SettingsStore.isCoachShown(this)) showCoach()
    }

    /** debug：从 Intent 直接喂一张工作图并选中模型，绕过相册（release 不触发）。 */
    private fun applyDebugAutoImport() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val path = intent.getStringExtra("autoImage") ?: return
        mainHandler.post {
            val bytes = try {
                File(path).readBytes()
            } catch (t: Throwable) {
                toast("自动导入失败：读不到 $path")
                return@post
            }
            val bmp = decodePreview(bytes, 2048) ?: run {
                toast("自动导入失败：解码失败")
                return@post
            }
            originalBytes = bytes
            originalBitmap?.recycle()
            originalBitmap = bmp
            resultBitmap?.recycle()
            resultBitmap = null
            val sz = ImageUtils.probeJpegSize(bytes)
            workWidth = sz?.getOrNull(0) ?: bmp.width
            workHeight = sz?.getOrNull(1) ?: bmp.height
            imgPreview.setImageBitmap(bmp)
            generateThumbnails()
            val id = intent.getStringExtra("autoModel") ?: return@post
            val def = CustomStyleStore.byId(id)
            if (def == null) {
                toast("自动选中的模型不存在")
            } else {
                toast("自动应用 ${def.name}（${if (def.hasModelNet) "M6" else "旧 LUT"}）")
                onCustomSelected(FilterEntry.Custom(id))
            }
        }
    }

    /** 黑匣子：崩溃写盘；下次启动若存在崩溃记录则弹窗展示（无 ADB 环境的排障通道） */
    private fun installCrashBox() {
        val file = File(filesDir, "crash_last.txt")
        val prev = try { if (file.isFile) file.readText() else null } catch (t: Throwable) { null }
        if (!prev.isNullOrBlank()) {
            DiagLog.recordCrash(prev)
            file.delete()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("上次异常退出")
                .setMessage("已记录到诊断日志。若需要反馈问题，可在「查看日志」里复制全文发给开发者。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("查看日志") { _, _ -> showLogDialog() }
                .show()
        }
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                File(filesDir, "crash_last.txt").writeText(
                    "thread=${t.name}\n${sw}\nM9CPU=${M9CpuFallback.lastStatus}"
                )
                DiagLog.e("CRASH", "线程 ${t.name} 未捕获异常", e)
            } catch (ignored: Throwable) { }
            systemHandler?.uncaughtException(t, e)
        }
    }

    /** 状态栏/导航栏（小白条）与 App 黑色背景融为一体：黑底 + 浅色图标。 */
    private fun applyImmersiveBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false      // 浅色（白）图标，适配黑底
            isAppearanceLightNavigationBars = false
        }
    }

    /** 首次启动一次性引导：气泡一出现即记为已展示（中途退出也不再重复打扰） */
    private fun showCoach() {
        txtCoach.visible()
        txtCoach.alpha = 0f
        txtCoach.animate().alpha(1f).setDuration(180).start()
        coachVisible = true
        SettingsStore.markCoachShown(this)
        mainHandler.postDelayed(coachAutoHide, 5000)
    }

    private val coachAutoHide = Runnable { hideCoach() }

    private fun hideCoach() {
        if (!coachVisible) return
        coachVisible = false
        mainHandler.removeCallbacks(coachAutoHide)
        txtCoach.animate().alpha(0f).setDuration(160)
            .withEndAction { txtCoach.gone() }.start()
    }

    /** 气泡可见时，点屏幕任意处只关气泡（消费本次按下，避免误触到下面的控件） */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (coachVisible && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            hideCoach()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // 设置页里导入备份后，MainActivity 还在栈下不会重建滤镜条：
        // 只有色彩库真的变了才重建一次（首次 resume 只记录基线，避免重复构建）。
        var rebuild = false
        val revision = CustomStyleStore.revision
        if (seenRevision < 0) {
            seenRevision = revision
        } else if (revision != seenRevision) {
            seenRevision = revision
            // 模型可能在独立界面被删除：选中悬空就回原图，避免留下一张幽灵高亮
            val cid = currentCustomId
            if (cid != null && CustomStyleStore.byId(cid) == null) {
                currentCustomId = null
                txtStyleHint.text = "原图"
            }
            rebuild = true
        }
        // 设置页改了「置顶跨分类显示」开关：滤镜条要按新口径重建（首次只记基线）
        val cross = SettingsStore.pinnedCrossCategory(this)
        if (seenPinnedCross == null) {
            seenPinnedCross = cross
        } else if (cross != seenPinnedCross) {
            seenPinnedCross = cross
            rebuild = true
        }
        if (rebuild) buildFilterBar(currentCategory)
        // 微信里复制分享指令后切回本应用：等窗口拿到焦点再读剪贴板
        // （Android 10+ 只允许有焦点的应用读剪贴板，onResume 当下还读不到）
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) checkShareClipboard()
        }, 400)
    }

    /**
     * 窗口恢复焦点时再补一次剪贴板识别。
     *
     * 从微信切回本应用时 `onResume` 往往还读不到剪贴板（系统要求窗口有焦点），
     * 只靠上面的 400ms 延迟在部分机型上会抢在焦点之前；这里以焦点事件为准。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkShareClipboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(coachAutoHide)
        SuperResTask.removeListener(srListener)
        // 全像素导出线程随 Activity 线程池关闭：让前台服务拿到结局通知并收尾（免得常驻 30 分钟）
        if (exporting) ExportProgress.end(false, "已退出应用，导出中断")
        executor.shutdownNow()
        // 不在 onDestroy 回收位图：可能仍被 ImageView 持有，销毁到 detach 间的一帧绘制
        // 会触发 "Canvas: trying to use a recycled bitmap" 崩溃；交给 GC 兜底（预览尺寸无收益）
        // 200MP 直通源是 cache 文件，进程退出不再需要，主动删掉不占用户存储
        runCatching { fullResFile?.delete() }
    }

    private fun bindViews() {
        imgPreview = findViewById(R.id.imgPreview)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtStyleHint = findViewById(R.id.txtStyleHint)
        progress = findViewById(R.id.progress)
        filterContainer = findViewById(R.id.filterContainer)
        btnImport = findViewById(R.id.btnImport)
        btnExport = findViewById(R.id.btnExport)
        btnSuperRes = findViewById(R.id.btnSuperRes)
        btnSuperResFast = findViewById(R.id.btnSuperResFast)
        btnPerfect = findViewById(R.id.btnPerfect)
        btnAdjust = findViewById(R.id.btnAdjust)
        btnCategory = findViewById(R.id.btnCategory)
        txtTitle = findViewById(R.id.txtTitle)
        imgTitleArrow = findViewById(R.id.imgTitleArrow)
        btnSettings = findViewById(R.id.btnSettings)
        txtCoach = findViewById(R.id.txtCoach)
        filterScroll = findViewById(R.id.filterScroll)
        adjPanel = findViewById(R.id.adjPanel)
        sbBright = findViewById(R.id.sbBright)
        sbLum = findViewById(R.id.sbLum)
        sbHigh = findViewById(R.id.sbHigh)
        sbShadow = findViewById(R.id.sbShadow)
        sbBlack = findViewById(R.id.sbBlack)
        sbSat = findViewById(R.id.sbSat)
        sbCon = findViewById(R.id.sbCon)
        sbGlow = findViewById(R.id.sbGlow)
        txtBrightVal = findViewById(R.id.txtBrightVal)
        txtLumVal = findViewById(R.id.txtLumVal)
        txtHighVal = findViewById(R.id.txtHighVal)
        txtShadowVal = findViewById(R.id.txtShadowVal)
        txtBlackVal = findViewById(R.id.txtBlackVal)
        txtSatVal = findViewById(R.id.txtSatVal)
        txtConVal = findViewById(R.id.txtConVal)
        txtGlowVal = findViewById(R.id.txtGlowVal)
    }

    // ---- 滤镜条 ----

    /**
     * 分类入口：标题（文字 + 小三角）点击弹出分类列表，长按仍打开诊断日志。
     * 冷启动按设置决定进入哪个分类。
     */
    private fun setupCategoryEntry() {
        // 「我的色彩」即使一个色彩都没有也常驻：它是唯一的新建入口，
        // 色彩被删光后回落 L-Moment 反而会让用户找不到「新建」，故不回落。
        currentCategory = SettingsStore.startCategory(this)
        SettingsStore.setLastCategory(this, currentCategory)
        btnCategory.setOnClickListener { showCategoryPicker() }
        btnCategory.setOnLongClickListener {
            showLogDialog()
            true
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        buildFilterBar(currentCategory)
    }

    /**
     * 按分类重建滤镜条：原图恒在首位，其后是置顶区，再给该分类的内置色彩。
     * 重建不触碰图片/工作字节；跨分类时当前风格可能不在列表内（此时无卡片高亮）。
     */
    private fun buildFilterBar(category: FilterCategory) {
        currentCategory = category
        txtTitle.text = category.title
        imgTitleArrow.rotation = 0f
        filterContainer.removeAllViews()
        imgByStyle.clear()
        cardViews.clear()
        cardLabels.clear()
        cardEntries.clear()
        orderedEntries(category).forEach { addFilterCard(it) }
        filterScroll.scrollTo(0, 0)
        selectCardByEntry(currentEntry())
    }

    /**
     * 条目顺序：原图 → 置顶区 → 创建卡（仅「我的色彩」）→ 分类内其余。
     *
     * L-Moment（全部）＝ 全部内置 + 全部自定义（自定义排在末尾，含模型色彩），但**不放新建卡**；
     * 「新建」入口只属于 [FilterCategory.CUSTOM]，避免在 L-Moment 里误建。
     *
     * 自定义色彩（含模型色彩）只属于 L-Moment 与「我的色彩」两个分类，其它分类不插卡；
     * 排序按创建时间倒序，与普通卡片一样排队，**不做「当前模型自动置顶」**——
     * 置顶只由用户手动置顶（[SettingsStore.pinnedIds]）产生。
     *
     * 置顶区的分类口径由设置页「置顶跨分类显示」决定（默认关）：
     * - 关（默认）：置顶区只含属于当前分类的条目——L-Moment 恒全量；内置色彩在其
     *   所属分类（[FilterCategory.styles]）；自定义/模型色彩只在「我的色彩」。
     *   被置顶的 CCD 色彩**不会**再出现在 ARRI 等无关分类里。
     * - 开（旧口径）：置顶区不参与分类过滤，任何分类下都排最前。
     * 顺序取 SettingsStore 的有序数组（最近置顶在前）。
     */
    private fun orderedEntries(category: FilterCategory): List<FilterEntry> {
        // 模型色彩与普通自定义一样允许手动置顶（创建时不自动置顶，见上方说明）；
        // 已隐藏的内置色彩（Style A）要过滤，否则历史置顶记录会把它带回列表
        val cross = SettingsStore.pinnedCrossCategory(this)
        val pinned = SettingsStore.pinnedIds(this).mapNotNull { resolveEntry(it) }
            .filter { !isHiddenEntry(it) }
            .filter { cross || belongsToCategory(it, category) }
        val pinnedIds = pinned.mapTo(HashSet()) { it.id }
        val normal = when (category) {
            // L-Moment 也要能看到自己新建的色彩，否则建完只能回「我的色彩」里看；
            // 内置部分走 category.styles（= Style.UI_VISIBLE），隐藏项自然不出现
            FilterCategory.ALL -> category.styles.map { FilterEntry.BuiltIn(it) } +
                customEntries()
            FilterCategory.CUSTOM -> customEntries()
            else -> category.styles.map { FilterEntry.BuiltIn(it) }
        }
        return buildList {
            add(FilterEntry.Orig)
            addAll(pinned)
            if (category.hostsCustom) add(FilterEntry.Create)
            addAll(normal.filter { it.id !in pinnedIds })
        }
    }

    /** L-Moment /「我的色彩」里的自定义色彩：QUICK 与模型色彩混排，新建的排前面 */
    private fun customEntries(): List<FilterEntry> = CustomStyleStore.all()
        .sortedByDescending { it.createdAt }
        .map { FilterEntry.Custom(it.id) }

    /**
     * 已隐藏的内置色彩（[LeicaEngine.Style.UI_VISIBLE] 之外，目前只有 Style A）。
     * 枚举仍在，分享码/批量测试按 id 依然可用，这里只负责不让它进卡片列表。
     */
    private fun isHiddenEntry(entry: FilterEntry): Boolean {
        val style = (entry as? FilterEntry.BuiltIn)?.style ?: return false
        return style !in LeicaEngine.Style.UI_VISIBLE
    }

    /**
     * 非跨分类模式下置顶项的归属判定（与分类正常区同口径）：
     * L-Moment 全量可见；自定义/模型色彩只在「我的色彩」；内置色彩在其所属分类。
     */
    private fun belongsToCategory(entry: FilterEntry, category: FilterCategory): Boolean {
        return when (category) {
            FilterCategory.ALL -> true
            FilterCategory.CUSTOM -> entry is FilterEntry.Custom
            else -> (entry as? FilterEntry.BuiltIn)?.style in category.styles
        }
    }

    /** 持久化的 id → 条目；色彩已被删除时返回 null（残留的置顶记录自动跳过） */
    private fun resolveEntry(id: String): FilterEntry? {
        if (id == FilterEntry.ORIG_ID) return FilterEntry.Orig
        LeicaEngine.Style.values().firstOrNull { it.id == id }?.let { return FilterEntry.BuiltIn(it) }
        return CustomStyleStore.byId(id)?.let { FilterEntry.Custom(it.id) }
    }

    /** 分类弹窗条目：全部列出，「我的色彩」即使为空也常驻（新建入口在那儿） */
    private fun pickerCategories(): List<FilterCategory> = FilterCategory.values().toList()

    /** 切换分类（同一分类不重建，保留滚动位置与选中态） */
    private fun switchCategory(category: FilterCategory) {
        if (category == currentCategory) return
        SettingsStore.setLastCategory(this, category)
        buildFilterBar(category)
    }

    /** 按风格恢复选中态：不在当前分类内则全部不高亮 */
    private fun selectCardByStyle(style: LeicaEngine.Style?) {
        selectCardByEntry(if (style == null) FilterEntry.Orig else FilterEntry.BuiltIn(style))
    }

    /** 按条目恢复选中态（自定义色彩/原图/内置统一走这里） */
    private fun selectCardByEntry(entry: FilterEntry) {
        val index = cardEntries.indexOfFirst { it.id == entry.id }
        selectCard(if (index >= 0) index else -1)
    }

    /** 当前选中的条目（原图 / 内置风格 / 自定义色彩） */
    private fun currentEntry(): FilterEntry = when {
        currentCustomId != null -> FilterEntry.Custom(currentCustomId!!)
        currentStyle != null -> FilterEntry.BuiltIn(currentStyle!!)
        else -> FilterEntry.Orig
    }

    /** 是否有任何色彩生效（内置或自定义） */
    private fun hasColor(): Boolean = currentStyle != null || currentCustomId != null

    private fun showCategoryPicker() {
        if (categoryPopup != null) return
        imgTitleArrow.animate().rotation(180f).setDuration(120).start()
        // 计数口径跟着列表走：L-Moment 与「我的色彩」都含模型色彩
        val customCount = CustomStyleStore.count()
        categoryPopup = CategoryPopup.show(
            anchor = btnCategory,
            items = pickerCategories().map {
                CategoryPopup.Item(it.id, it.title, it.subtitle(customCount))
            },
            selectedId = currentCategory.id,
            onDismiss = {
                categoryPopup = null
                imgTitleArrow.animate().rotation(0f).setDuration(120).start()
            },
            // 分隔线之后的独立入口：语义是打开另一个界面，不是切分类
            footer = listOf(CategoryPopup.Item(modelEntryId, "模型色彩", ""))
        ) { item ->
            if (item.id == modelEntryId) {
                openModelGallery()
            } else {
                FilterCategory.fromId(item.id)?.let { switchCategory(it) }
            }
        }
    }

    // ---- 模型色彩（独立界面 ModelActivity） ----

    /** 分类弹窗底部独立入口 → 模型库（选择模式） */
    private fun openModelGallery() {
        pickModel.launch(Intent(this, ModelActivity::class.java))
    }

    /**
     * 「我的色彩」新建卡 → 直达模型色彩新建：落地即弹「新建模型色彩」对话框。
     * 选择模式下建完自动选中返回（用户不用再点一次）；取消则留在模型库，
     * 可顺手挑一个现有模型，或退出不动。
     */
    private fun openModelCreate() {
        pickModel.launch(
            Intent(this, ModelActivity::class.java)
                .putExtra(ModelActivity.EXTRA_AUTO_CREATE, true)
        )
    }

    /** 主界面长按模型卡 →「设置」：直接进该模型的色彩模式页 */
    private fun openModelSettings(styleId: String) {
        startActivity(
            Intent(this, ModelDetailActivity::class.java)
                .putExtra(ModelDetailActivity.EXTRA_STYLE_ID, styleId)
        )
    }

    /** 模型库里选中一个模型：套用 + 插入临时卡 */
    private fun onModelPicked(styleId: String) {
        val def = CustomStyleStore.byId(styleId) ?: return
        // 拦截语义与点击色彩卡完全一致：宁可不切，也不要把状态切一半
        if (srBusy) { toast("处理中，请稍候"); return }
        if (perfectApplied) {
            toast("当前图为完美成片，请先关闭「完美」再切换色彩")
            return
        }
        srBakedStyle?.let { baked ->
            toast("当前图已含「${baked.label}」色彩，换色请重新导入原图")
            return
        }
        currentStyle = null
        currentCustomId = styleId
        // 只有 L-Moment /「我的色彩」会列出模型卡，其它分类重建后不高亮属预期
        buildFilterBar(currentCategory)
        onCustomSelected(FilterEntry.Custom(styleId))
        // onCustomSelected 内部按「自定义色彩」写提示，这里改回模型语义
        if (currentCustomId == styleId) txtStyleHint.text = "${def.name} · 模型色彩"
    }

    private fun addFilterCard(entry: FilterEntry) {
        val index = cardViews.size
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(cardInsetH, dp(8), cardInsetH, dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tv = TextView(this).apply {
            text = if (entry is FilterEntry.Create) "新建" else entry.label
            textSize = 11f
            // 卡片宽 72dp：不锁单行的话长名字会换行把卡片撑高，同排高度就参差了
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }

        if (entry is FilterEntry.Create) {
            card.addView(TextView(this).apply {
                text = "+"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#8E8E93"))
                background = roundedRect(0xFF232326.toInt(), thumbCorner)
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            })
        } else {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // 内圈圆角 = 选中框外圈 − 卡片内边距，与选中框同心（同半径会显得角部外扩）
                background = roundedRect(0xFF232326.toInt(), thumbCorner)
                // background 只画了圆角底色，CENTER_CROP 的位图仍按矩形铺满会盖住四角，
                // 靠 outline 裁切让预览画面本身也是圆角（圆角与 background 同为 thumbCorner）
                clipToOutline = true
            }
            // 用 FrameLayout 包一层只为叠「模型」角标，几何仍是 60×60
            val holder = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            }
            holder.addView(img, FrameLayout.LayoutParams(dp(60), dp(60)))
            if ((entry as? FilterEntry.Custom)?.let {
                    CustomStyleStore.byId(it.styleId)?.kind
                } == StyleKind.MODEL) {
                // 右下角 8dp 白点：不占文字宽度、不改卡片几何
                holder.addView(View(this).apply {
                    background = roundedRect(0xFFFFFFFF.toInt(), dp(4).toFloat())
                    layoutParams = FrameLayout.LayoutParams(
                        dp(8), dp(8), Gravity.END or Gravity.BOTTOM
                    ).apply {
                        rightMargin = dp(4)
                        bottomMargin = dp(4)
                    }
                })
            }
            card.addView(holder)
            imgByStyle[entry.id] = img
            thumbCache[entry.id]?.let { img.setImageBitmap(it) }   // 切回已生成过的分类秒显
            if (entry is FilterEntry.Custom && thumbCache[entry.id] == null) {
                // 跨会话时内存缓存是空的：先试落盘的缩略图（几十 KB，主线程解码可忽略），
                // 没有才回落到「用当前图重算」。分享/导入的缩略图也走这条路径。
                val disk = ColorShareImport.loadThumb(this, entry.styleId)
                if (disk != null) {
                    thumbCache[entry.id] = disk
                    img.setImageBitmap(disk)
                } else {
                    ensureCustomThumb(entry.styleId)
                }
            }
        }

        card.addView(tv)
        card.setOnClickListener {
            selectCard(index)
            when (entry) {
                is FilterEntry.BuiltIn -> onFilterSelected(entry)
                is FilterEntry.Custom -> onFilterSelected(entry)
                is FilterEntry.Orig -> onFilterSelected(entry)
                // 新建入口统一走模型色彩（QUICK 快速生成已退役，管线见 git 历史）
                is FilterEntry.Create -> openModelCreate()
            }
        }
        card.setOnLongClickListener { v ->
            // 原图/创建卡不响应；必须返回 true，否则松手会顺带触发一次点击
            if (entry is FilterEntry.BuiltIn || entry is FilterEntry.Custom) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // 模型色彩与普通自定义共用同一套菜单，差异在 showFilterActions 内部处理
                showFilterActions(v, entry)
                true
            } else {
                false
            }
        }
        filterContainer.addView(card)
        cardViews.add(card)
        cardLabels.add(tv)
        cardEntries.add(entry)
    }

    /**
     * 长按菜单：置顶对内置/自定义都开放，重命名与删除只属于自定义色彩。
     *
     * 模型色彩与普通「我的色彩」共用这套菜单，只有两处差异：
     * - 「增强」换成「设置」：进该模型的色彩模式页（参考图池/排除/加图增强/版本回退）；
     * - 不出现「回退」：模型的历史版本在「设置」页里管，菜单里留一个入口就够。
     */
    private fun showFilterActions(anchor: View, entry: FilterEntry) {
        val pinned = SettingsStore.isPinned(this, entry.id)
        val customId = (entry as? FilterEntry.Custom)?.styleId
        val def = customId?.let { CustomStyleStore.byId(it) }
        val isModel = def?.kind == StyleKind.MODEL
        // 只有一个版本时没有可回退的历史，菜单不出现该入口
        val versionCount = def?.versions?.size ?: 0
        val actions = buildList {
            add(FilterActionPopup.Action("pin", if (pinned) "取消置顶" else "置顶"))
            if (customId != null) {
                if (isModel) {
                    add(FilterActionPopup.Action("settings", "设置"))
                } else {
                    add(FilterActionPopup.Action("enhance", "增强"))
                    if (versionCount > 1) add(FilterActionPopup.Action("history", "回退"))
                }
                add(FilterActionPopup.Action("share", "分享"))
                add(FilterActionPopup.Action("rename", "重命名"))
                add(FilterActionPopup.Action("delete", "删除", danger = true))
            }
        }
        FilterActionPopup.show(anchor, actions) { action ->
            when (action.id) {
                "pin" -> togglePin(entry)
                "settings" -> if (customId != null) openModelSettings(customId)
                "enhance" -> if (customId != null) startEnhance(customId)
                "history" -> if (customId != null) openVersionHistory(customId)
                "share" -> if (customId != null) shareCustomStyle(customId)
                "rename" -> renameCustomStyle(entry.id)
                "delete" -> confirmDeleteCustomStyle(entry.id)
            }
        }
    }

    private fun togglePin(entry: FilterEntry) {
        val pinned = SettingsStore.togglePinned(this, entry.id)
        buildFilterBar(currentCategory)
        // 重建会把列表滚回 0，重排后滚到卡片新位置，否则用户找不到刚操作的那一项
        val idx = cardEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            val target = cardViews[idx]
            filterScroll.post { filterScroll.smoothScrollTo(target.left - dp(6), 0) }
        }
        toast(if (pinned) "已置顶" else "已取消置顶")
    }

    // ---- 自定义色彩：分享（S6）----

    /**
     * 分享一条色彩，两个出口：
     * - 分享文件：单条 `.lmc`（成品 LUT/参数 + 全部参考图指纹），几 MB，对方可继续增强；
     * - 复制指令：17³ LUT 文本码（约 6 KB），后台生成，对方只能直接套用。
     *
     * 模型色彩只有文件一个出口：它的成品是 A 路线参数、没有 LUT bin，指令通道必然失败，
     * 所以面板里直接隐藏（`withTextChannel=false`），不要等用户点了才看到报错。
     *
     * 面板先弹、轻量指令后台生成；生成不出来或体积超限时复制按钮保持灰色禁用，
     * 用户不会拿到半截字符串，也不会误以为有两条路可走。
     */
    private fun shareCustomStyle(styleId: String) {
        if (enhancing || splitting) {
            toast("正在生成色彩，请稍候")
            return
        }
        val def = CustomStyleStore.byId(styleId) ?: run {
            toast("该色彩已不可用")
            buildFilterBar(currentCategory)
            return
        }
        val version = CustomStyleStore.currentVersion(def) ?: run {
            toast("该色彩已不可用")
            return
        }
        // 缩略图优先从内存缓存拿；冷启动/跨会话时回落到落盘的缩略图
        val thumb = thumbCache[styleId]
            ?: ColorShareImport.loadThumb(this, styleId)?.also { thumbCache[styleId] = it }
        val isModel = def.kind == StyleKind.MODEL
        val handle = ColorShareSheet.show(
            this,
            def,
            thumb,
            onShareFile = { shareStyleFile(def, version) },
            withTextChannel = !isModel,
            onCopyText = { text ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("L-Moment 色彩", text))
                // 自己刚写进去的指令不要在自己切回前台时又被识别成「导入」
                ColorShareCode.signature(text)?.let { SettingsStore.markShareSeen(this, it) }
                toast("指令已复制，发给朋友即可")
            }
        )
        // 模型色彩只剩文件分享，后台的指令生成链整条跳过
        if (isModel) return
        executor.execute {
            // 轻量指令只发 17³ 量化 LUT（差分 + gzip，约 6.2 KB 字符）。
            // 不走配方码是因为完整版本链（常见 6 张参考图）要 40 KB 文字，微信里粘贴会卡；
            // 代价是收件方无法再增强 —— 面板里已写明，想要完整能力就走分享文件。
            // M5：版本被回收时先重烘，再生成轻量指令
            val bin = if (ModelLutStore.ensure(this, def, version)) {
                CustomLutBaker.readBytes(this, version.lutFile)
            } else null
            val text = bin?.let {
                ColorShareCode.encodeCompact(def.name, it)?.let { code ->
                    ColorShareCode.wrapText(def.name, code)
                }
            }
            mainHandler.post {
                if (handle.isDismissed()) return@post
                when {
                    bin == null -> handle.fail("色彩文件已丢失，请重新生成这条色彩")
                    text == null -> handle.fail("这份色彩无法生成轻量指令，请用「分享文件」")
                    else -> handle.ready(text)
                }
            }
        }
    }

    /** 生成中的防重入标记（大文件压缩几百毫秒，连点会出现两个分享面板）。 */
    @Volatile
    private var sharingStyleFile = false

    /** 把单条色彩写成 `.lmc` 放缓存目录，再交给系统分享面板（微信「发送文件」）。 */
    private fun shareStyleFile(def: CustomStyleDef, version: StyleVersion) {
        if (sharingStyleFile) {
            toast("正在准备文件，请稍候")
            return
        }
        sharingStyleFile = true
        toast("正在准备色彩文件…")
        executor.execute {
            val file = runCatching {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val safeName = def.name
                    .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                    .take(24)
                    .ifBlank { "color" }
                val out = File(dir, "LeicaMoments_$safeName.lmc")
                out.outputStream().use { ColorBackup.exportOne(this, it, def, version) }
                out
            }.getOrNull()
            mainHandler.post {
                sharingStyleFile = false
                if (file == null) {
                    toast("色彩文件生成失败")
                    return@post
                }
                runCatching {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, def.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "分享「${def.name}」"))
                }.onFailure { toast("没有可用的分享应用") }
            }
        }
    }

    /**
     * 剪贴板自动识别：从微信复制指令后回到本应用即弹确认框。
     *
     * 只在窗口有焦点时调用（[onResume] 延迟一次兜底），并做三重去重：
     * 持久化的已处理指纹、本次会话里被取消的指纹、正在显示的确认框。
     */
    private fun checkShareClipboard() {
        if (sharePromptShowing || isFinishing || isDestroyed) return
        val text = try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            clip.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (t: Throwable) {
            // 个别系统在无焦点时读剪贴板会抛 SecurityException，静默跳过
            return
        } ?: return
        if (!text.contains(ColorShareCode.PREFIX)) return
        val sig = ColorShareCode.signature(text) ?: return
        if (sig == declinedShareSig) return
        if (sig == importingShareSig) return
        if (sig == SettingsStore.shareSeenSignature(this)) return

        val payload = ColorShareCode.decode(text)
        if (payload == null) {
            SettingsStore.markShareSeen(this, sig)
            toast("这条分享指令已损坏")
            return
        }
        confirmShareImport(payload, sig)
    }

    /** 导入前确认：一次性说清会新增几条、会不会重复。 */
    private fun confirmShareImport(payload: ColorShareCode.Payload, sig: String) {
        sharePromptShowing = true
        val modeNote = if (payload.mode == ColorShareCode.MODE_RECIPE) "配方码" else "LUT 码"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导入分享色彩")
            .setMessage(
                "「${payload.name}」\n" +
                    "${payload.steps.size} 个版本 · ${payload.seeds.size} 张参考图（$modeNote）\n\n" +
                    "只新增这条色彩，不改动你现有的色彩库。"
            )
            .setPositiveButton("导入") { _, _ ->
                importSharePayload(payload, sig)
            }
            .setNegativeButton("取消") { _, _ ->
                // 本次会话不再打扰；下次启动（或换一条指令）仍可导入
                declinedShareSig = sig
            }
            .setOnDismissListener { sharePromptShowing = false }
            .show()
    }

    /** 导入落库：后台重烘焙/写文件，完成后重建滤镜条并选中新卡片。 */
    private fun importSharePayload(payload: ColorShareCode.Payload, sig: String) {
        if (enhancing || splitting) {
            toast("正在生成色彩，请稍候")
            return
        }
        progress.visible()
        // 确认框一关窗口就重新拿到焦点，这条标记挡住那次「又弹一次确认框」
        importingShareSig = sig
        executor.execute {
            val result = try {
                ColorShareImport.import(this, payload)
            } catch (t: Throwable) {
                DiagLog.e("Share", "分享码导入失败", t)
                null
            }
            mainHandler.post {
                if (importingShareSig == sig) importingShareSig = null
                if (isFinishing || isDestroyed) return@post
                progress.gone()
                if (result == null) {
                    toast("导入失败，请重试")
                    return@post
                }
                val def = result.def
                if (def == null) {
                    toast(result.error ?: "导入失败")
                    return@post
                }
                SettingsStore.markShareSeen(this, sig)
                val existed = result.existingId != null
                // 导入自带的缩略图直接进内存缓存，卡片不用等重算
                result.thumb?.let { thumbCache[def.id] = it }
                // 当前分类看不到自定义色彩时切到「我的色彩」，否则用户找不到刚导入的卡片
                if (!currentCategory.hostsCustom && currentCategory != FilterCategory.ALL) {
                    SettingsStore.setLastCategory(this, FilterCategory.CUSTOM)
                    buildFilterBar(FilterCategory.CUSTOM)
                } else {
                    buildFilterBar(currentCategory)
                }
                seenRevision = CustomStyleStore.revision
                if (!existed) ensureCustomThumb(def.id)
                toast(
                    if (existed) "「${def.name}」已在你的色彩库里"
                    else "已导入「${def.name}」"
                )
                // 有图就顺手应用，导入即可见效果；无图/完美态/合成态只落选中态
                if (originalBytes != null && !perfectApplied && srBakedStyle == null) {
                    onCustomSelected(FilterEntry.Custom(def.id))
                } else {
                    currentStyle = null
                    currentCustomId = def.id
                    txtStyleHint.text = "${def.name} · 自定义色彩"
                    selectCardByEntry(FilterEntry.Custom(def.id))
                }
            }
        }
    }

    // ---- 自定义色彩：重命名 / 删除（S3）----

    /** 名称上限 8 字：卡片宽 72dp，输入期就限住比事后省略友好 */
    private val maxStyleNameLen = 8

    /**
     * 重命名：只改元数据与界面文案，色彩内容与缩略图都不动。
     * 当前提示要同步，否则上方还显示旧名字。
     */
    private fun renameCustomStyle(styleId: String) {
        val def = CustomStyleStore.byId(styleId) ?: run {
            toast("该色彩已不可用")
            buildFilterBar(currentCategory)
            return
        }
        val edit = EditText(this).apply {
            setText(def.name)
            setSelection(def.name.length)
            isSingleLine = true
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxStyleNameLen))
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E8E93"))
            hint = "色彩名称"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(0xFF232326.toInt(), dp(10).toFloat())
        }
        val counter = TextView(this).apply {
            text = "${def.name.length}/$maxStyleNameLen"
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.END
            setPadding(0, dp(6), dp(4), 0)
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                counter.text = "${s?.length ?: 0}/$maxStyleNameLen"
            }
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
            addView(edit)
            addView(counter)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("重命名色彩")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        // 正按钮不能走默认回调：空名/重名要拦住，不能让对话框先关掉
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val name = edit.text.toString().trim()
                    if (name.isEmpty()) {
                        toast("名称不能为空")
                        return@setOnClickListener
                    }
                    if (CustomStyleStore.all().any { it.id != def.id && it.name == name }) {
                        toast("已有同名色彩")
                        return@setOnClickListener
                    }
                    def.name = name
                    CustomStyleStore.put(this, def)
                    val idx = cardEntries.indexOfFirst { it.id == def.id }
                    if (idx >= 0) cardLabels[idx].text = name
                    if (currentCustomId == def.id) txtStyleHint.text = "$name · 自定义色彩"
                    dialog.dismiss()
                    toast("已重命名为「$name」")
                }
        }
        dialog.show()
        edit.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** 删除前二次确认（不可恢复） */
    private fun confirmDeleteCustomStyle(styleId: String) {
        val def = CustomStyleStore.byId(styleId) ?: run {
            toast("该色彩已不可用")
            buildFilterBar(currentCategory)
            return
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("删除色彩")
            .setMessage("「${def.name}」及其全部 LUT 文件将被永久删除，删除后不可恢复。")
            .setPositiveButton("删除", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Color.parseColor("#FF453A"))
                setOnClickListener {
                    dialog.dismiss()
                    deleteCustomStyle(styleId)
                }
            }
        }
        dialog.show()
    }

    /**
     * 删除自定义色彩：按方案文档的 7 项清理逐条执行，少一项就留残渣。
     *
     * 1/3 元数据与 JSON（[CustomStyleStore.remove]）· 2 LUT 文件 · 4 缩略图缓存
     * · 5 ImageView 映射 · 6 置顶记录 · 7 当前选中回退原图。
     * 另外必须 [AlexaLut.invalidate]，否则静态缓存会继续用已删文件的数据。
     */
    private fun deleteCustomStyle(styleId: String) {
        if (srBusy) {
            toast("处理中，请稍候")
            return
        }
        val def = CustomStyleStore.byId(styleId) ?: return
        val wasSelected = currentCustomId == styleId
        // 后台可能正有该色彩的 LUT 处理在跑，回来会覆盖新预览 → 作废代次
        ++processingGeneration
        progress.gone()

        val dir = AlexaLut.customDir(this)
        def.versions.forEach { v ->
            AlexaLut.invalidate(AlexaLut.fileRef(v.lutFile))
            runCatching { File(dir, v.lutFile).delete() }
        }
        // 按前缀补漏（M5 懒烘/导入改名的文件）+ 清 θ 快照 + A 路线参数
        ModelLutStore.purge(this, styleId)
        ModelParamStore.purge(this, styleId)
        CustomStyleStore.remove(this, styleId)
        customThumbPending.remove(styleId)
        thumbCache.remove(styleId)
        imgByStyle.remove(styleId)
        ColorShareImport.deleteThumb(this, styleId)
        SettingsStore.removePinned(this, styleId)

        if (wasSelected) {
            // 7：选中的色彩被删 → 回原图（结果位图作废，工作字节不动）
            currentCustomId = null
            currentStyle = null
            resultBitmap?.recycle()
            resultBitmap = null
            liftedResult?.recycle()
            liftedResult = null
            refreshPreviewImage()
            txtStyleHint.text = "原图"
        }
        buildFilterBar(currentCategory)
        toast("已删除「${def.name}」")
    }

    private fun selectCard(index: Int) {
        cardViews.forEachIndexed { i, card ->
            card.background = if (i == index) {
                roundedRect(0x33FF3B30.toInt(), cardCorner)
            } else {
                null
            }
            cardLabels[i].setTextColor(if (i == index) Color.WHITE else Color.parseColor("#8E8E93"))
        }
    }

    // ---- 分享入口（外部 ACTION_SEND 进来的图片走与「导入」完全相同的链路）----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)                 // 让 getIntent() 与最新一次分享一致
        handleSharedIntent(intent)
    }

    /** 分享导入：解析 uri 后交给既有导入链；画质任务在途时拦截（长任务完成会接管预览） */
    private fun handleSharedIntent(intent: Intent?) {
        val uri = extractSharedUri(intent) ?: run {
            DiagLog.w("Import", "分享未携带可用图片 type=${intent?.type}")
            return
        }
        DiagLog.i("Import", "来自分享 type=${intent?.type} uri=$uri")
        // 超分/完美是分钟级长任务，且完美分支不带换图身份校验，在途导入新图会被成片接管预览
        if (srBusy) {
            DiagLog.w("Import", "分享被拦截：画质任务在途")
            toast("画质处理中，请稍候再分享")
            return
        }
        onImagePicked(uri)
    }

    /** 取分享图片 uri：EXTRA_STREAM(单个) → EXTRA_STREAM(列表取首) → data → clipData 首项 */
    private fun extractSharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { return it }
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.firstOrNull()?.let { return it }
        intent.data?.let { return it }
        intent.clipData?.let { cd -> if (cd.itemCount > 0) return cd.getItemAt(0).uri }
        return null
    }

    // ---- 导入 ----

    private fun onImagePicked(uri: Uri) {
        progress.visible()
        executor.execute {
            try {
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = ImageUtils.readBytes(this, uri)
                val displayName = queryDisplayName(uri)?.lowercase()
                val isRaw = displayName != null && RAW_EXTS.any { displayName.endsWith(it) }
                val isJpeg = !isRaw && (mime.contains("jpeg") || mime.contains("jpg") || isJpegBytes(bytes))
                DiagLog.i("Import", "读取 ${bytes.size}B mime=$mime raw=$isRaw jpeg=$isJpeg")

                // 动态照片（MVIMG/MotionPhoto）截断为纯静态 JPEG：丢掉主图 EOI 之后的视频数据，
                // 避免相册按复合照片路径读取容器元数据导致相机标记失效
                val trimmed = if (isJpeg) trimToStaticJpeg(bytes) else null
                if (trimmed != null && trimmed.size != bytes.size) {
                    DiagLog.i("Import", "动态照片截断 ${bytes.size}B→${trimmed.size}B")
                }
                // JPG 像素尺寸（仅读头）：rawSize = 存储尺寸（日志用），oriSize = 方向归一后的实际像素尺寸
                val rawSize = if (trimmed != null) ImageUtils.probeJpegSize(bytes) else null
                // 直通判据按引擎的横置口径（长 ≤4096 且 短 ≤3072）：竖拍图送引擎前会被旋转 -90°，
                // 竖 3072×4096 旋转后正好 4096×3072 合规，故竖拍 12.5MP 也能原字节直通（旧宽/高口径会误缩）
                val oriSize = if (trimmed != null) ImageUtils.probeOrientedSize(bytes) else null
                val jpegDirect = trimmed != null && oriSize != null &&
                    ImageUtils.withinWorkLimit(oriSize[0], oriSize[1])

                // C4：像素预算分档（单点判定 = 50MP/200MP 档 + 低端回落 + 用户开关）。
                // 约束见 ImageUtils.decideFullRes（像素带 / Orientation=1 / 总内存 ≥6GB / 堆 ≥384MiB / 设置）。
                val srcOri = if (trimmed != null) ImageUtils.readOrientation(bytes) else 1
                val fullResDecision = if (trimmed != null && rawSize != null) {
                    ImageUtils.decideFullRes(
                        this, rawSize[0], rawSize[1], srcOri,
                        SettingsStore.fullResEnabled(this)
                    )
                } else null
                val fullResDirect = fullResDecision?.enabled == true
                // 源落 cache 文件：50MB 级字节不驻留 Java 堆，条带解码直接从文件读
                val fullResFileLocal = if (fullResDirect) {
                    writeCacheBytes(trimmed!!, "leica_fullres_src")
                } else null
                // EXIF 只需容器头（SOI..SOS，几十 KB），尾部回填用它即可
                val fullResHeadLocal = if (fullResFileLocal != null) {
                    ImageUtils.extractExifHeader(trimmed!!)
                } else null
                if (fullResFileLocal != null && fullResDecision != null && rawSize != null) {
                    DiagLog.i("Import",
                        "全像素直通可用 ${fullResDecision.tier} ${rawSize[0]}x${rawSize[1]} " +
                            "${fullResDecision.reason} " +
                            "源文件 ${fullResFileLocal.length()}B exifHead=${fullResHeadLocal?.size ?: 0}B " +
                            "堆 ${ImageUtils.heapUsageLabel()}")
                } else if (fullResDecision != null) {
                    // 分档未通过也留痕（门限/方向/低端/堆/设置各有一句原因），真机验收直接看这句
                    DiagLog.i("Import", "全像素直通未启用：${fullResDecision.reason} · 堆 ${ImageUtils.heapUsageLabel()}")
                }

                // HDR 增益图提取：必须在任何重编码之前对最原始字节做；且只在「JPG 原字节直通」时可用
                // ——工作字节一旦被缩放重编码，原尺寸的增益图无法与主图对应
                // 全像素直通的主图几何 = 原图几何，增益图天然对齐，可安全回嵌
                val hdr = if (jpegDirect || fullResDirect) HdrGainMap.extract(bytes) else null
                if (isJpeg && rawSize != null && !jpegDirect && !fullResDirect) {
                    DiagLog.i("Import",
                        "JPG 存储 ${rawSize[0]}x${rawSize[1]} 归一后 ${oriSize?.get(0)}x${oriSize?.get(1)} " +
                        "超引擎上限（横置口径 长≤4096 短≤3072）将缩放，跳过 HDR 增益图回嵌")
                }
                if (hdr != null) {
                    DiagLog.i("Import", "检测到 HDR 增益图 ${hdr.gainmapJpeg.size}B，导出将回嵌 UltraHDR")
                }

                val preview: Bitmap
                val workBytes: ByteArray
                // 新导入作废旧 RAW 16-bit 基准（RAW 提亮档成功后于 decodeRaw 内重登记）
                rawLin16 = null
                rawPreviewJpeg = null
                if (isRaw) {
                    val r = decodeRaw(bytes)
                    if (r == null) {
                        DiagLog.e("Import", "RAW 解码失败")
                        mainHandler.post { progress.gone(); toast("RAW 解码失败") }
                        return@execute
                    }
                    preview = r.first
                    workBytes = r.second
                    DiagLog.i("Import", "RAW 解码 OK ${preview.width}x${preview.height} 工作字节 ${workBytes.size}B")
                } else {
                    // JPG 原字节直通（零重编码 + 原生 EXIF）的前提是像素尺寸已在 M9 系引擎上限内；
                    // 超出 4096×3072 的 JPG 必须先缩，否则 styletrans 硬崩（exit=134，无降级）
                    val directBytes = if (jpegDirect) trimmed else null
                    if (directBytes != null) {
                        val p = ImageUtils.decode(this, uri, 1600)
                        if (p == null) {
                            DiagLog.e("Import", "图片解码失败")
                            mainHandler.post { progress.gone(); toast("无法解码该图片") }
                            return@execute
                        }
                        preview = p
                        workBytes = directBytes
                        val sz = oriSize!!
                        DiagLog.i("Import", "JPG 原字节直通 ${sz[0]}x${sz[1]} ${directBytes.size}B")
                    } else {
                        // HEIF/PNG/WebP 或超限 JPG：全分辨率工作字节（上限封顶）作为逐像素链路的真源
                        // （此前压到 1600px，等于让引擎在 1.9MP 上算完再放大，损失 6.6× 像素）
                        val work = ImageUtils.decodeWork(this, uri)
                        if (work == null) {
                            DiagLog.e("Import", "图片解码失败")
                            mainHandler.post { progress.gone(); toast("无法解码该图片") }
                            return@execute
                        }
                        val pv = scaleBitmap(work, 1600)
                        preview = pv
                        // Bitmap.compress 不写 EXIF：从原始容器把拍摄参数抄回工作字节；
                        // Orientation 经朝向核对决定（正常 1，个别容器方向未被应用时沿用源值防横竖错）
                        val wOri = ImageUtils.resolveWorkOrientation(bytes, work.width, work.height)
                        workBytes = ExifTransplant.copy(
                            this, bytes, ImageUtils.toJpeg(work, 95), wOri)
                        DiagLog.i("Import",
                            "全分辨率工作字节 ${work.width}x${work.height} ${workBytes.size}B" +
                            (if (rawSize != null) "（源 ${rawSize[0]}x${rawSize[1]} 超限）" else "（$mime）"))
                        // 小图（长边 ≤1600）scaleBitmap 直接返回 work 自身，此时它就是 preview，不能回收
                        if (pv !== work) work.recycle()
                    }
                }

                // 导入期一次性 EXIF 方向归一（伴生 JPG 横存竖拍），后续每次切模式处理不再重复旋转
                val normalizedBytes = ImageUtils.normalizeExifOrientation(workBytes)
                // 工作字节的真实像素尺寸（超分提示按它算，不再用预览尺寸 ×2 低估）
                val workSize = ImageUtils.probeJpegSize(normalizedBytes)
                    ?: intArrayOf(preview.width, preview.height)
                DiagLog.i("Import",
                    "导入完成 ${normalizedBytes.size}B 工作 ${workSize[0]}x${workSize[1]} " +
                    "预览 ${preview.width}x${preview.height}" +
                    (if (normalizedBytes !== workBytes && normalizedBytes.size != workBytes.size)
                        " (方向归一 ${workBytes.size}→${normalizedBytes.size}B)" else ""))

                mainHandler.post {
                    originalBytes = normalizedBytes
                    workWidth = workSize[0]
                    workHeight = workSize[1]
                    pendingHdr = if (jpegDirect) hdr else null
                    // C1：全像素直通源（仅「原图」档导出可用；滤镜仍走 normalizedBytes）
                    clearFullRes()
                    fullResFile = fullResFileLocal
                    fullResExifHead = fullResHeadLocal
                    fullResWidth = if (fullResFileLocal != null) rawSize!![0] else 0
                    fullResHeight = if (fullResFileLocal != null) rawSize!![1] else 0
                    fullResHdr = if (fullResFileLocal != null) hdr else null
                    srBakedStyle = null
                    brightLevel = 0
                    exposureLevel = 0
                    highlightLevel = 0
                    shadowLevel = 0
                    blackLevel = 0
                    satLevel = 0
                    contrastLevel = 0
                    glowLevel = 0
                    updateLumBarUi()
                    // 作废在途调整任务：防止旧图的调整版完成于新导入之后盖在预览上
                    liftGen++
                    // 调节入口对所有导入开放（RAW/JPG/HEIF）；管线作用在工作字节上，与输入格式无关
                    btnAdjust.visible()
                    adjPanel.gone()
                    originalBitmap = preview
                    resultBitmap = null
                    currentStyle = null
                    currentCustomId = null
                    liftedOriginal?.recycle(); liftedOriginal = null
                    liftedResult?.recycle(); liftedResult = null
                    txtEmpty.gone()
                    imgPreview.setImageBitmap(preview)
                    txtStyleHint.visible()
                    btnSuperRes.visible()
                    btnSuperResFast.visible()
                    // 完美档完整管线需要原始 DNG：这里只记源，用时再重读（不常驻大字节）
                    sourceUri = uri
                    sourceIsRaw = isRaw
                    clearPerfectState()
                    txtStyleHint.text = "原图"
                    selectCard(0)
                    progress.gone()
                    generateThumbnails()
                    val rawTag = when (SettingsStore.rawThrLevel(this)) {
                        1 -> " · 提亮"
                        2 -> " · 强力提亮"
                        else -> ""
                    }
                    if (fullResFileLocal != null) {
                        // C1：200MP 直通可用性显式告知（滤镜档仍按工作字节 3072 级导出）
                        txtStyleHint.text = "原图 · 全像素直通"
                        toast(
                            "200MP 已导入 · 原图档可全像素导出 " +
                                "${fullResWidth}x${fullResHeight}"
                        )
                    } else {
                        toast(
                            if (isRaw) "RAW 已解码：${preview.width}x${preview.height}$rawTag"
                            else "已导入"
                        )
                    }
                    if (isRaw) showRawHintIfNeeded()
                }
            } catch (e: Exception) {
                DiagLog.e("Import", "导入异常", e)
                mainHandler.post {
                    progress.gone()
                    toast("读取图片失败，请换一张或重试（详情见日志）")
                }
            }
        }
    }

    private val RAW_EXTS = listOf(
        ".cr2", ".cr3", ".nef", ".nrw", ".arw", ".dng", ".raf",
        ".orf", ".rw2", ".pef", ".srw", ".sr2", ".3fr", ".x3f", ".iiq"
    )

    /**
     * RAW 解码：LibRaw 全解码（真 14-bit）+ 白平衡修复（改良灰世界，校正小米 Ultra RAW
     * 的 AnalogBalance 过度增益）+ 色彩增强链（自动曝光/阴影提升/对比度/饱和度/高光压缩）。
     * 返回 (预览Bitmap, 处理输入JPEG)，失败返回 null。
     */
    private fun decodeRaw(bytes: ByteArray): Triple<Bitmap, ByteArray, RawToneMap.Lin16?>? {
        // 解码亮度档位：按机型记忆；默认「提亮」(level 1，2026-10-22 改定，旧版默认标准)。
        // 提亮/强力提亮改走「线性解码 + 自适应软肩曲线」（同亮度下削顶从 2.24% 降到 0.34%），
        // 标准档仍走原 thr 路径逐字节不变；新路径失败时回落原路径保证可用。
        val level = SettingsStore.rawThrLevel(this)
        var lin16: RawToneMap.Lin16? = null
        val decoded = if (RawToneMap.isEnabled(level)) {
            val r = decodeLinearTone(bytes, level)
            lin16 = r?.second
            r?.first ?: RawDecoder.nativeDecodeWithThr(bytes, SettingsStore.rawThr(this))
        } else {
            RawDecoder.nativeDecodeWithThr(bytes, SettingsStore.rawThr(this))
        } ?: return null
        if (decoded.size < 3) return null
        val w = decoded[0]
        val h = decoded[1]
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(decoded, 2, w, 0, 0, w, h)
        // 引擎上限=横置口径（长 ≤4096 且 短 ≤3072）：常规 12.5MP Binned RAW（4096×3072）原样通过零变化；
        // 超出者（如 50MP RAW、200MP RAW）先缩到限内，否则 styletrans 直接 exit=134 硬崩
        var base = full
        if (!ImageUtils.withinWorkLimit(w, h)) {
            val s = ImageUtils.workScale(w, h)
            val nw = (w * s).toInt().coerceAtLeast(1)
            val nh = (h * s).toInt().coerceAtLeast(1)
            base = Bitmap.createScaledBitmap(full, nw, nh, true)
            if (base !== full) full.recycle()
            DiagLog.w("Import", "RAW ${w}x${h} 超引擎上限（横置口径 长≤4096 短≤3072），工作字节缩到 ${nw}x${nh}")
        }
        val p = scaleBitmap(base, 1600)
        val work = ImageUtils.toJpeg(base, 95)
        if (p !== base) base.recycle()
        // P2：登记 16-bit 基准、解码档位与内嵌预览（导出重放校验用）；非提亮档为 null
        rawLin16 = lin16
        rawLin16Level = level
        return Triple(p, work, lin16)
    }

    /**
     * 提亮档专用解码：LibRaw 线性 sRGB 16bit → 自适应软肩曲线 → ARGB。
     * 失败（native 解码异常 / 曲线异常）返回 null，由 [decodeRaw] 回落原 thr 路径。
     */
    private fun decodeLinearTone(bytes: ByteArray, level: Int): Pair<IntArray, RawToneMap.Lin16?>? {
        val buf = RawDecoder.nativeDecodeLinearSrgb(bytes) ?: return null
        return try {
            val t0 = System.currentTimeMillis()
            // DNG 内嵌厂商预览 = 原生观感基准，用于分位匹配；取不到就回落软肩曲线
            val preview = try {
                RawDecoder.nativeExtractPreview(bytes)
            } catch (t: Throwable) {
                DiagLog.w("Import", "RAW 内嵌预览提取失败：${t.message}")
                null
            }
            val t1 = System.currentTimeMillis()
            // P2：一次解码出 ARGB + 1600px 16-bit 线性基准（调五档时免 8-bit 量化损失）；
            // 顺带缓存内嵌预览字节（导出 16-bit 重放的分位匹配基准）
            rawPreviewJpeg = preview
            val out = RawToneMap.renderWithBase(buf, level, preview, null, 1600)
            DiagLog.w("Import", "RAW 线性曲线 level=$level ${out?.first?.get(0)}x${out?.first?.get(1)} " +
                    "preview=${preview?.size ?: 0}B 提取${t1 - t0}ms 映射${System.currentTimeMillis() - t1}ms")
            out
        } catch (t: Throwable) {
            DiagLog.e("Import", "RAW 线性曲线失败（level=$level），回落 thr 解码", t)
            null
        } finally {
            RawDecoder.nativeFreeLinear(buf)
        }
    }

    /**
     * P2：RAW 16-bit 五档导出重放——重读源 DNG → 线性解码 → RawToneMap 曲线 +
     * ToneCurve 七档（同一条曲线、同一份内嵌预览引导，与导入/预览完全同源）→ 编码 JPEG。
     * 曲线七档在 16-bit 线性域完成（RAW 高位深保真）；柔光不进曲线域，在降 8-bit 的
     * 位图上叠 SoftGlow（8-bit 域足够，见 SoftGlow 头注）。风格引擎消费其输出。
     * 任一步失败返回 null，调用方回落「风格 → applyStackToJpeg」原链路。
     */
    private fun raw16TonedWorkBytes(br: Int, ev: Int, hl: Int, sh: Int, bl: Int,
                                    sa: Int, co: Int, gl: Int = 0): ByteArray? {
        val level = rawLin16Level
        val preview = rawPreviewJpeg
        val uri = sourceUri ?: return null
        if (level == 0 || !RawToneMap.isEnabled(level) || preview == null) return null
        return try {
            val src = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val buf = RawDecoder.nativeDecodeLinearSrgb(src) ?: return null
            try {
                val argb = RawToneMap.renderWithTone(
                    buf, level, preview, ToneCurve.of(br, ev, hl, sh, bl, sa, co)) ?: return null
                val w = argb[0]
                val h = argb[1]
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(argb, 2, w, 0, 0, w, h)
                if (gl != 0) SoftGlow.applyInPlace(bmp, gl)
                try { ImageUtils.toJpeg(bmp, 95) } finally { bmp.recycle() }
            } finally {
                RawDecoder.nativeFreeLinear(buf)
            }
        } catch (t: Throwable) {
            DiagLog.e("Export", "RAW 16bit 七档重放失败，回落 8bit 链", t)
            null
        }
    }

    /**
     * 首次导入 RAW 的一次性提示：相册预览通常经过多帧合成，RAW 直出在白天大光比场景
     * 会明显偏暗。指引到设置里的「RAW 解码亮度」，弹一次即标记，不重复打扰。
     */
    private fun showRawHintIfNeeded() {
        if (SettingsStore.isRawHintShown(this)) return
        SettingsStore.markRawHintShown(this)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("RAW 可能比相册偏暗")
            .setMessage(
                "相册预览通常经过多帧合成，RAW 直出在白天大光比场景会暗一些。\n\n" +
                    "默认已选「提亮」；如仍偏暗，可在 设置 → RAW 解码亮度 选「强力提亮」，追求质感偏暗可选「标准」。"
            )
            .setPositiveButton("去设置", null)
            .setNegativeButton("知道了", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    dialog.dismiss()
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
        }
        dialog.show()
    }

    private fun isRawFile(uri: Uri): Boolean {
        val name = queryDisplayName(uri)?.lowercase() ?: return false
        return RAW_EXTS.any { name.endsWith(it) }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isJpegBytes(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8
    }

    /** 截断到主 JPEG 的 EOI（动态照片尾部视频在 EOI 之后，静态图截断后与原文件等价）。 */
    private fun trimToStaticJpeg(bytes: ByteArray): ByteArray {
        var pos = 2
        var sosEnd = -1
        while (pos + 4 <= bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) {
            val m = bytes[pos + 1].toInt() and 0xFF
            if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { pos += 2; continue }
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > bytes.size) return bytes
            if (m == 0xDA) { sosEnd = pos + 2 + len; break }
            pos += 2 + len
        }
        if (sosEnd < 0 || sosEnd >= bytes.size) return bytes
        var i = sosEnd
        while (i + 1 < bytes.size) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
                return if (i + 2 == bytes.size) bytes else bytes.copyOf(i + 2)
            }
            i++
        }
        return bytes
    }

    /** 检测 JPEG 是否含 APP1 Exif 段（导入诊断用）。 */
    private fun hasExifSegment(bytes: ByteArray): Boolean {
        var pos = 2
        while (pos + 4 <= bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) {
            val m = bytes[pos + 1].toInt() and 0xFF
            if (m == 0xDA || m == 0xD9) break
            if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { pos += 2; continue }
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > bytes.size) break
            if (m == 0xE1 && len >= 10 && bytes[pos + 4] == 'E'.code.toByte() &&
                bytes[pos + 5] == 'x'.code.toByte()) return true
            pos += 2 + len
        }
        return false
    }

    private fun generateThumbnails() {
        val src = originalBitmap ?: return
        val thumb = scaleBitmap(src, 200)
        thumbCache.clear()                                  // 换图后旧缩略图全部作废
        thumbCache[origThumbKey] = thumb
        imgByStyle[origThumbKey]?.setImageBitmap(thumb)
        // 隐藏项（Style A）不生成缩略图：既不展示，白跑一次处理链没有意义
        LeicaEngine.Style.UI_VISIBLE.forEach { style ->
            executor.execute {
                try {
                    // 缩略图不触发 CPU 降级链（导入期连发 4 个子进程压力大且无意义，用 LUT 近似即可）
                    val out = LeicaEngine.process(this, ImageUtils.toJpeg(thumb, 90), style,
                        allowCpuFallback = false)
                    val bmp = ImageUtils.fromJpeg(out)
                    // 按下标回填会因分类过滤而贴错卡：统一按 style.id 键控，重建时再从缓存取
                    mainHandler.post {
                        if (bmp != null) {
                            thumbCache[style.id] = bmp
                            imgByStyle[style.id]?.setImageBitmap(bmp)
                        }
                    }
                } catch (t: Throwable) {
                    DiagLog.e("Engine", "缩略图生成失败 $style", t)
                }
            }
        }
        // 自定义色彩只是查表，跟着一起出图（同一张 200px 缩略图，几十毫秒）
        CustomStyleStore.all().forEach { def -> ensureCustomThumb(def.id, thumb) }
    }

    /**
     * 自定义色彩的卡片缩略图（按 id 键控，与内置风格同池）。
     * 同一 id 同时只有一条在途任务，避免分类来回切时重复起任务。
     */
    private fun ensureCustomThumb(styleId: String, source: Bitmap? = null) {
        if (thumbCache.containsKey(styleId)) return
        val def = CustomStyleStore.byId(styleId) ?: return
        val version = CustomStyleStore.currentVersion(def) ?: return
        val src = source ?: originalBitmap ?: return
        if (!customThumbPending.add(styleId)) return
        val thumb = if (src.width <= 240) src else scaleBitmap(src, 200)
        executor.execute {
            try {
                // M5：缩略图也会碰到被回收的版本，需要时先重烘（A 路线参数常驻不回收）
                if (!ModelColor.ensure(this, def, version)) {
                    DiagLog.w("Custom", "缩略图跳过（色彩文件缺失）${def.id}/${version.id}")
                    mainHandler.post { customThumbPending.remove(styleId) }
                    return@execute
                }
                val out = ModelColor.render(this, ImageUtils.toJpeg(thumb, 90), version)
                val bmp = ImageUtils.fromJpeg(out)
                // 落盘一份：重启后滤镜条不再全灰，分享时也拿得到缩略图
                ColorShareImport.saveThumb(this, styleId, out)
                mainHandler.post {
                    customThumbPending.remove(styleId)
                    // 烘焙期间该色彩可能已被删除：别再写回缓存留孤儿位图
                    if (CustomStyleStore.byId(styleId) == null) {
                        bmp?.recycle()
                        return@post
                    }
                    if (bmp != null) {
                        thumbCache[styleId] = bmp
                        imgByStyle[styleId]?.setImageBitmap(bmp)
                    }
                }
            } catch (t: Throwable) {
                DiagLog.e("Custom", "自定义缩略图失败 ${def.name}", t)
                mainHandler.post { customThumbPending.remove(styleId) }
            }
        }
    }

    // ---- 滤镜切换 ----

    private fun onFilterSelected(entry: FilterEntry) {
        if (entry is FilterEntry.Custom) {
            onCustomSelected(entry)
            return
        }
        val style = (entry as? FilterEntry.BuiltIn)?.style
        if (srBusy) {
            toast("处理中，请稍候")
            return
        }
        // 完美态：成片自带色彩，须先关闭「完美」才能换风格（再按一下「完美」即关闭）
        if (perfectApplied) {
            if (style != null && style.id == perfectStyle?.id) {
                txtStyleHint.text = "${style.label} · 完美成片"
                return
            }
            toast("当前图为完美成片，请先关闭「完美」再切换风格")
            selectCardByStyle(perfectStyle)
            return
        }
        // 完美按钮仅 M9 / M9+ / M10-R 三档出现
        updatePerfectVisibility(style)
        // 合成态：色彩已烙在图中，禁止换色/去色（防止二次套色）
        srBakedStyle?.let { baked ->
            if (style == baked) {
                txtStyleHint.text = "${baked.label} · 已合成"
                return
            }
            toast("当前图已含「${baked.label}」色彩，换色请重新导入原图")
            selectCardByStyle(baked)
            return
        }
        val gen = ++processingGeneration
        currentStyle = style
        currentCustomId = null
        if (style == null) {
            // 原图（档位≠0 时显示曝光版）
            refreshPreviewImage()
            txtStyleHint.text = "原图"
            return
        }
        val work = originalBytes ?: return
        txtStyleHint.text = "${style.label} · ${style.desc}"
        progress.visible()
        executor.execute {
            try {
                val t0 = System.currentTimeMillis()
                DiagLog.i("Engine", "${style.label} 处理开始 ${work.size}B")
                val out = LeicaEngine.process(this, work, style, onFallback = { st ->
                    DiagLog.w("Engine", "${st.label} 触发降级")
                    mainHandler.post {
                        if (LeicaEngine.Style.isCcd(st)) {
                            showDiagDialog(fallbackMsg(st), PortraitFilm.lastError ?: "无详情")
                        } else {
                            toast(fallbackMsg(st))
                        }
                    }
                })
                DiagLog.i("Engine", "${style.label} 处理完成 ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
                val bmp = ImageUtils.fromJpeg(out)
                mainHandler.post {
                    if (gen == processingGeneration) {
                        progress.gone()
                        if (bmp != null) {
                            resultBitmap?.recycle()
                            resultBitmap = bmp
                            if (exposureLevel != 0) {
                                // 先显示未调曝光基图，异步重算曝光版后静默替换
                                refreshPreviewImage()
                                recomputeAdjAsync()
                            } else {
                                imgPreview.setImageBitmap(bmp)
                            }
                        }
                        // M3 语义对齐其它风格：兜底接管/异常才 toast，monopan 正常保持安静（诊断全量在诊断日志）
                        if (style == LeicaEngine.Style.M3) {
                            val info = M3Mono.lastInfo
                            if (info != null && (info.startsWith("converted") || info.startsWith("exception"))) {
                                toast("M3 · monopan 失效，CPU 黑白兜底已接管（详情见日志）")
                            }
                        }
                    } else if (bmp != null) {
                        bmp.recycle()
                    }
                }
            } catch (t: Throwable) {
                DiagLog.e("Engine", "${style.label} 处理异常", t)
                mainHandler.post {
                    progress.gone()
                    toast("处理失败，请重试（详情见日志）")
                }
            }
        }
    }

    /**
     * 自定义色彩选中：直接对工作字节应用运行时 LUT。
     *
     * 与内置风格的三点差异：不加颗粒/暗角（用户给的是参考图不是预设）、
     * 不走降级链（LUT 是纯查表，失败即输出原图）、不支持完美/超分合成（S3 再并链）。
     */
    private fun onCustomSelected(entry: FilterEntry.Custom) {
        if (srBusy) {
            toast("处理中，请稍候")
            return
        }
        val def = CustomStyleStore.byId(entry.styleId)
        val version = def?.let { CustomStyleStore.currentVersion(it) }
        if (def == null || version == null) {
            toast("该色彩已不可用")
            buildFilterBar(currentCategory)
            return
        }
        // 完美态：成片自带色彩，须先关闭「完美」才能换色彩
        if (perfectApplied) {
            toast("当前图为完美成片，请先关闭「完美」再切换色彩")
            selectCardByEntry(currentEntry())
            return
        }
        // 合成态：色彩已烙在图中，禁止换色（防止二次套色）
        srBakedStyle?.let { baked ->
            toast("当前图已含「${baked.label}」色彩，换色请重新导入原图")
            selectCardByStyle(baked)
            return
        }
        val gen = ++processingGeneration
        val prevCustomId = currentCustomId
        val prevStyle = currentStyle
        val prevHint = txtStyleHint.text
        currentStyle = null
        currentCustomId = entry.styleId
        updatePerfectVisibility(null)
        txtStyleHint.text = "${def.name} · 自定义色彩"
        val work = originalBytes ?: return
        progress.visible()
        executor.execute {
            try {
                // M6：带 aff/knots 的模型走 native 新链，没有才回旧 LUT
                val useNet = def.hasModelNet
                // M5：被 LRU 回收的旧版本在「首次应用」时按 θ 快照重烘（一次性成本）
                if (!useNet && !ModelColor.ensure(this, def, version)) {
                    DiagLog.w("Engine", "色彩文件缺失且无法重建 ${def.id}/${version.id}")
                    mainHandler.post {
                        if (gen == processingGeneration) {
                            progress.gone()
                            currentStyle = prevStyle
                            currentCustomId = prevCustomId
                            txtStyleHint.text = prevHint
                            buildFilterBar(currentCategory)
                            toast("「${def.name}」色彩文件缺失，无法恢复此版本")
                        }
                    }
                    return@execute
                }
                val t0 = System.currentTimeMillis()
                DiagLog.i("Engine", "${def.name} 自定义色彩处理开始 ${work.size}B")
                val warn: () -> Unit = {
                    mainHandler.post { toast("「${def.name}」色彩文件不可用，已输出原图") }
                    Unit
                }
                // M7.2：参考集有多个风格簇时，先给 640px 候选预览，选中后再渲染全图
                val cands = if (useNet) buildCandidateStyles(work, def, intent.getBooleanExtra("debugCandAll", false)) else null
                if (cands != null && cands.size > 1) {
                    DiagLog.i("Engine", "${def.name} 候选 ${cands.size} 个 耗时 ${System.currentTimeMillis() - t0}ms")
                    mainHandler.post {
                        if (gen != processingGeneration) {
                            cands.forEach { it.preview.recycle() }
                            return@post
                        }
                        progress.gone()
                        txtStyleHint.text = "${def.name} · 多风格"
                        StyleCandidateSheet.show(
                            this,
                            cands.map { StyleCandidateSheet.Item(it.label, it.preview, it.deltaE) },
                            onPick = { idx ->
                                cands.forEach { it.preview.recycle() }
                                applyCandidate(def, work, cands[idx], gen)
                            },
                            onCancel = {
                                cands.forEach { it.preview.recycle() }
                                applyCandidate(def, work, cands[0], gen)
                            }
                        )
                    }
                    return@execute
                }
                val out = if (useNet) {
                    cands?.firstOrNull()?.let { renderWithStyle(work, it.style) }
                        ?: renderModelNet(work, def) ?: ModelColor.render(this, work, version, warn)
                } else {
                    ModelColor.render(this, work, version, warn)
                }
                DiagLog.i("Engine", "${def.name} 处理完成 ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
                val bmp = ImageUtils.fromJpeg(out)
                mainHandler.post {
                    if (gen == processingGeneration) {
                        progress.gone()
                        if (bmp != null) {
                            resultBitmap?.recycle()
                            resultBitmap = bmp
                            if (exposureLevel != 0) {
                                refreshPreviewImage()
                                recomputeAdjAsync()
                            } else {
                                imgPreview.setImageBitmap(bmp)
                            }
                        }
                    } else if (bmp != null) {
                        bmp.recycle()
                    }
                }
            } catch (t: Throwable) {
                DiagLog.e("Engine", "${def.name} 自定义色彩处理异常", t)
                mainHandler.post {
                    progress.gone()
                    toast("处理失败，请重试（详情见日志）")
                }
            }
        }
    }

    /**
     * M6：用 aff/knots 渲染工作图。native apply 约 1.6 s / 12 MP；失败返回 null，
     * 调用方回退旧 LUT。
     */
    private fun renderModelNet(work: ByteArray, def: CustomStyleDef): ByteArray? {
        val style = def.modelStyle ?: return null
        return renderWithStyle(work, AdaptiveColorNet.Style(style.first, style.second))
    }

    private fun renderWithStyle(work: ByteArray, style: AdaptiveColorNet.Style): ByteArray? {
        val src = ImageUtils.fromJpeg(work) ?: return null
        val styled = try {
            AdaptiveColorNet.apply(src, style, smooth = true)
        } finally {
            src.recycle()
        }
        if (styled == null) return null
        return try {
            val bos = ByteArrayOutputStream()
            styled.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            bos.toByteArray()
        } finally {
            styled.recycle()
        }
    }

    private data class Candidate(
        val label: String,
        val style: AdaptiveColorNet.Style,
        val preview: Bitmap,
        val deltaE: Float
    )

    /**
     * M7.2：读 emb_seeds → 候选簇 → 每簇 aggregate/fuse → 640px 预览 + ΔE00。
     * 返回 null 表示没有 emb 或单候选（调用方走原路径）。
     *
     * 候选级 ΔE00 裁剪（3.0~12.0）：<3 视为与候选 0 重复，>12 视为离群簇；
     * 与 PC 版 22_m7_candidates.py 的 delta_e_fn 同语义，端侧用真实渲染替代代理。
     */
    private fun buildCandidateStyles(
        work: ByteArray,
        def: CustomStyleDef,
        skipDeGate: Boolean = false
    ): List<Candidate>? {
        val emb = ModelRefStore.loadEmb(this, def.id) ?: return null
        val d = 36
        val n = emb.size / d
        if (n < 4) return null
        val res = StyleCandidates.candidates(emb, n, d)
        if (res.single) return null
        // 黑白参考 → 候选簇同样转单色，否则用户切到任何候选都会回到彩色
        val refThumbs = ModelRefStore.allThumbs(this, def.id).sortedBy { it.first }
            .mapNotNull { (_, f) -> BitmapFactory.decodeFile(f.absolutePath) }
        val monoRefs = refThumbs.isNotEmpty() &&
            AdaptiveColorNet.refsMeanChroma(refThumbs) < AdaptiveColorNet.MONO_MEAN_CHROMA
        refThumbs.forEach { it.recycle() }
        val src = ImageUtils.fromJpeg(work) ?: return null
        val scale = 640f / max(src.width, src.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true
        ) else src
        val out = ArrayList<Candidate>()
        try {
            for ((i, p) in res.picks.withIndex()) {
                val sub = FloatArray(p.members.size * d)
                p.members.forEachIndexed { j, m -> System.arraycopy(emb, m * d, sub, j * d, d) }
                val r = AdaptiveColorNet.aggregate(sub, p.members.size)
                val style = (AdaptiveColorNet.fuse(this, r) ?: continue).let {
                    if (monoRefs) it.toMono() else it
                }
                val styled = AdaptiveColorNet.apply(small, style, smooth = true) ?: continue
                val de = if (out.isEmpty()) 0f else ColorMath.avgDeltaE00(out[0].preview, styled)
                out.add(Candidate(if (i == 0) "原风格" else "风格 ${i + 1}", style, styled, de))
            }
        } finally {
            if (small !== src) small.recycle()
            src.recycle()
        }
        if (out.size < 2) {
            out.forEach { it.preview.recycle() }
            return null
        }
        val kept = if (skipDeGate) out
        else out.filterIndexed { i, c -> i == 0 || c.deltaE.isNaN() || (c.deltaE in 3f..12f) }
        out.forEach { if (it !in kept) it.preview.recycle() }
        return if (kept.size < 2) {
            kept.forEach { it.preview.recycle() }
            null
        } else kept
    }

    /** 候选选中后：全图渲染并回到预览（与 onCustomSelected 的收尾同构）。 */
    private fun applyCandidate(def: CustomStyleDef, work: ByteArray, cand: Candidate, gen: Int) {
        progress.visible()
        executor.execute {
            try {
                val t0 = System.currentTimeMillis()
                val version = CustomStyleStore.currentVersion(def) ?: return@execute
                val out = renderWithStyle(work, cand.style)
                    ?: ModelColor.render(this, work, version) {
                        mainHandler.post { toast("「${def.name}」色彩不可用，已输出原图") }
                    }
                DiagLog.i("Engine", "${def.name} 候选渲染 ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
                val bmp = ImageUtils.fromJpeg(out)
                mainHandler.post {
                    if (gen == processingGeneration) {
                        progress.gone()
                        if (bmp != null) {
                            resultBitmap?.recycle()
                            resultBitmap = bmp
                            if (exposureLevel != 0) {
                                refreshPreviewImage()
                                recomputeAdjAsync()
                            } else {
                                imgPreview.setImageBitmap(bmp)
                            }
                        }
                    } else if (bmp != null) {
                        bmp.recycle()
                    }
                }
            } catch (t: Throwable) {
                DiagLog.e("Engine", "候选渲染失败", t)
                mainHandler.post {
                    progress.gone()
                    toast("处理失败，请重试（详情见日志）")
                }
            }
        }
    }

    // ---- 增强（S4）：游标版本 + 新参考图 → 新版本 ----

    /**
     * 增强入口：先讲清「在哪个版本上继续、还能加几张」，再进多选。
     *
     * 额度按**有效种子**算（`version.seedCount`）：回退到早期版本后，游标之后被分叉覆盖
     * 的种子会重新变成可用额度，与提交时「先截断再追加」的语义一致。
     */
    private fun startEnhance(styleId: String) {
        if (enhancing || splitting) { toast("正在生成色彩，请稍候"); return }
        if (srBusy) { toast("处理中，请稍候"); return }
        val def = CustomStyleStore.byId(styleId)
        val version = def?.let { CustomStyleStore.currentVersion(it) }
        if (def == null || version == null) { toast("该色彩已不可用"); return }
        // 纯额度计算：用 refCount，不为了问一句「还能加几张」把整池读进内存
        val used = version.seedCount.coerceIn(0, CustomStyleStore.refCount(def))
        val room = (maxSeedPool - used).coerceAtMost(maxRefs)
        if (room <= 0) {
            toast("参考图已达上限（$maxSeedPool 张），请先回退到较早版本")
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("增强「${def.name}」")
            .setMessage(
                "当前版本已用 $used 张参考图。再选 1~$room 张你喜欢的成片，" +
                    "会在当前版本的基础上继续往它们的影调与色彩靠拢。\n\n" +
                    "选完可以先拖动强度看效果，点「应用」才生成新版本；" +
                    "最多保留 $maxVersions 个版本，最早的初始版会一直留着。"
            )
            .setPositiveButton("选择参考图") { _, _ ->
                pendingEnhanceId = styleId
                pickEnhanceImages.launch("image/*")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 参考图 → 逐张指纹 →（有效种子 + 新增）重新烘焙到临时文件 → 预览强度。
     *
     * 临时文件 `pending_<id>_<ts>.bin` 只服务预览与「应用」；取消/失败/进程被杀都不碰
     * 元数据，留下的孤儿文件由 [cleanupPendingLuts] 在下次启动清掉。
     */
    private fun enhanceFromRefs(styleId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val def = CustomStyleStore.byId(styleId)
        val version = def?.let { CustomStyleStore.currentVersion(it) }
        if (def == null || version == null) { toast("该色彩已不可用"); return }
        // 马上要真的拿池子切片烘焙，这里才加载
        if (!CustomStyleStore.ensureSeeds(def)) { toast("参考图池读取失败，请重试"); return }
        val effective = def.seeds.take(version.seedCount.coerceIn(0, def.seeds.size))
        val room = (maxSeedPool - effective.size).coerceAtLeast(0)
        if (room <= 0) {
            toast("参考图已达上限（$maxSeedPool 张），请先回退到较早版本")
            return
        }
        val limit = minOf(maxRefs, room)
        val picked = if (uris.size > limit) uris.take(limit) else uris
        if (uris.size > limit) toast("本次最多再加 $limit 张参考图，已取前 $limit 张")
        enhancing = true
        enhanceCanceled = false

        val tvProgress = TextView(this).apply {
            text = "正在分析参考图…"
            setTextColor(Color.parseColor("#8A8A8E"))
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(
                ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
            )
            addView(tvProgress)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("增强色彩")
            .setView(box)
            .setNegativeButton("取消") { _, _ -> enhanceCanceled = true }
            .setCancelable(false)
            .create()
        dialog.show()

        executor.execute {
            val added = ArrayList<RefSeed>()
            val addedUris = ArrayList<Uri>()
            var failed = 0
            var rejectMsg: String? = null
            var warnMsg: String? = null
            for ((i, uri) in picked.withIndex()) {
                if (enhanceCanceled) break
                mainHandler.post { tvProgress.text = "分析参考图 ${i + 1}/${picked.size}" }
                val seed = try {
                    RefAnalyzer.analyze(this, uri)
                } catch (t: Throwable) {
                    DiagLog.e("Custom", "增强参考图分析异常 $uri", t)
                    null
                }
                if (seed != null) {
                    added.add(seed)
                    addedUris.add(uri)
                    val w = RefAnalyzer.lastWarning
                    if (w != null) warnMsg = if (warnMsg == null) w else warnMsg + " " + w
                } else {
                    failed++
                    if (rejectMsg == null) rejectMsg = RefAnalyzer.lastReject
                }
            }
            if (enhanceCanceled) {
                mainHandler.post { dialog.dismiss(); enhancing = false }
                return@execute
            }
            if (added.isEmpty()) {
                mainHandler.post {
                    dialog.dismiss(); enhancing = false
                    val why = rejectMsg
                    if (why != null) showRefNotice("参考图无法使用", why)
                    else toast("参考图无法解析，换几张试试")
                }
                return@execute
            }
            mainHandler.post {
                tvProgress.text = "正在生成新版本（${effective.size + added.size} 张参考）…"
            }

            // 新增参考图的 240px 副本：既给详情页看池子，也是 A 路线拟合原料
            val baseCount = effective.size
            addedUris.forEachIndexed { k, uri ->
                ModelRefStore.thumbJpeg(this, uri)?.let {
                    ModelRefStore.saveThumb(this, styleId, baseCount + k, it)
                }
            }
            val t0 = System.currentTimeMillis()
            val indexes = ArrayList<Int>(baseCount + added.size)
            for (i in 0 until baseCount) indexes.add(i)
            for (i in 0 until added.size) indexes.add(baseCount + i)
            // M6 模型：用全部 240px 缩略图重新编码 aff/knots，不碰 LUT/param
            val isNet = def.hasModelNet
            var netStyle: AdaptiveColorNet.Style? = null
            if (isNet) {
                val bitmaps = ModelRefStore.allThumbs(this, styleId)
                    .sortedBy { it.first }
                    .mapNotNull { (_, f) -> BitmapFactory.decodeFile(f.absolutePath) }
                netStyle = AdaptiveColorNet.encodeStyle(this, bitmaps)?.let {
                    // 黑白参考 → 单色风格（须在 recycle 前测色度）
                    AdaptiveColorNet.monoIfGray(bitmaps, it).first
                }
                bitmaps.forEach { it.recycle() }
                DiagLog.i("Custom", "M6 增强编码${if (netStyle != null) "成功" else "失败"} 参考 ${indexes.size} 张")
            }
            val paramBytes = if (isNet) null else ModelFitter.fitFromThumbs(this, styleId, indexes)
            // A 路线升级：旧版是参数才能按强度插值；旧版是 LUT 时滑块隐藏、按 100% 应用
            val oldParams = if (version.paramFile.isNotEmpty()) {
                ModelParamStore.read(this, version.paramFile)
            } else {
                null
            }
            DiagLog.i(
                "Custom",
                "增强拟合${if (paramBytes != null) "成功" else "不可用，退回旧烘焙"} " +
                    "耗时 ${System.currentTimeMillis() - t0}ms 参考 ${indexes.size} 张"
            )

            // 拟合不可用（老模型缺参考副本）时退回 M5 烘焙，行为与今天一致
            var pendingFile = ""
            var oldBytes: ByteArray? = null
            var newBytes: ByteArray? = null
            if (!isNet && paramBytes == null) {
                pendingFile = "pending_${styleId}_${System.currentTimeMillis().toString(36)}.bin"
                val t1 = System.currentTimeMillis()
                val ok = CustomLutBaker.bake(this, effective + added, pendingFile)
                oldBytes = CustomLutBaker.readBytes(this, version.lutFile)
                newBytes = if (ok) CustomLutBaker.readBytes(this, pendingFile) else null
                DiagLog.i(
                    "Custom", "增强烘焙${if (newBytes != null) "成功" else "失败"} $pendingFile " +
                        "耗时 ${System.currentTimeMillis() - t1}ms"
                )
            }

            mainHandler.post {
                dialog.dismiss()
                enhancing = false
                if (!isNet && paramBytes == null && (oldBytes == null || newBytes == null)) {
                    if (pendingFile.isNotEmpty()) deletePendingLut(pendingFile)
                    toast(if (oldBytes == null) "当前版本的色彩文件已丢失" else "色彩生成失败，请重试")
                    return@post
                }
                if (!isNet && paramBytes != null && !ModelColor.ready(this, version)) {
                    toast("当前版本的色彩文件已丢失")
                    return@post
                }
                // 预览底图：有工作图用工作图，否则解码第一张参考图（弹窗内部缩到 ≤1100px）
                val base = originalBitmap ?: ImageUtils.decode(this, picked.first(), 1200)
                if (base == null) {
                    if (pendingFile.isNotEmpty()) deletePendingLut(pendingFile)
                    toast("没有可用的预览底图，增强已取消")
                    return@post
                }
                val wm = warnMsg
                if (wm != null) showRefNotice("参考图提醒", wm)

                // 新效果渲染器：M6 走 native、参数版本走 StyleA（带强度插值），旧 LUT 走查表
                val ns = netStyle
                val renderNew: (android.graphics.Bitmap, Float) -> android.graphics.Bitmap? =
                    if (ns != null) {
                        { bmp, s -> AdaptiveColorNet.apply(bmp, ns, strength = s) }
                    } else if (paramBytes != null) {
                        { bmp, s ->
                            val bytes = if (oldParams != null && s < 0.999f) {
                                StyleAFit.blend(oldParams, paramBytes, s) ?: paramBytes
                            } else {
                                paramBytes
                            }
                            if (StyleA.putBytes(MEM_PARAM_REF, bytes)) {
                                StyleA.applyToBitmap(this, bmp, MEM_PARAM_REF)
                            } else {
                                null
                            }
                        }
                    } else {
                        { bmp, s ->
                            val bytes = if (s >= 0.999f) newBytes!!
                            else CustomLutBaker.blendBytes(oldBytes!!, newBytes!!, s)
                            if (AlexaLut.putBytes(MEM_LUT_REF, bytes)) {
                                AlexaLut.applyToBitmap(this, bmp, MEM_LUT_REF)
                            } else {
                                null
                            }
                        }
                    }

                EnhancePreviewDialog.show(
                    this, base, added.size,
                    effective.size + added.size,
                    allowStrength = isNet || paramBytes == null || oldParams != null,
                    renderOld = { bmp ->
                        val os = def.modelStyle
                        if (os != null) {
                            AdaptiveColorNet.apply(bmp, AdaptiveColorNet.Style(os.first, os.second))
                        } else {
                            ModelColor.renderBitmap(this, bmp, version)
                        }
                    },
                    renderNew = renderNew,
                    onApply = { strength ->
                        applyEnhance(
                            styleId, version.id, pendingFile, paramBytes,
                            oldParams != null, added, strength, ns
                        )
                    },
                    onCancel = {
                        if (pendingFile.isNotEmpty()) deletePendingLut(pendingFile)
                        if (failed > 0) toast("$failed 张参考图未能采用，已取消增强")
                    },
                    onCleanup = {
                        StyleA.invalidate(MEM_PARAM_REF)
                        AlexaLut.invalidate(MEM_LUT_REF)
                    }
                )
            }
        }
    }

    /**
     * 应用增强：混合落盘在后台，元数据提交回主线程（顺序见文档 §十 第 5 条）。
     *
     * A 路线：参数按强度线性插值后直接落 1596 B 文件，不经过临时文件；
     * [paramBytes] 为空说明走了旧烘焙（缺参考副本的老模型），[pendingFile] 才有内容。
     */
    private fun applyEnhance(
        styleId: String,
        baseVersionId: String,
        pendingFile: String,
        paramBytes: ByteArray?,
        blendable: Boolean,
        added: List<RefSeed>,
        strength: Float,
        netStyle: AdaptiveColorNet.Style? = null
    ) {
        val s = strength.coerceIn(0f, 1f)
        executor.execute {
            val def = CustomStyleStore.byId(styleId)
            val base = def?.versions?.firstOrNull { it.id == baseVersionId }
            if (def == null || base == null) {
                if (pendingFile.isNotEmpty()) deletePendingLut(pendingFile)
                mainHandler.post { toast("该色彩已不可用") }
                return@execute
            }
            val versionId = "v_" + System.currentTimeMillis().toString(36)

            // M6：没有 LUT/param 文件，直接把新 aff/knots 写进新版本
            if (netStyle != null) {
                mainHandler.post { commitEnhance(def, base, added, versionId, "", "", s, netStyle) }
                return@execute
            }

            if (paramBytes != null) {
                val oldParams = if (blendable) ModelParamStore.read(this, base.paramFile) else null
                val bytes = if (oldParams != null && s < 0.999f) {
                    StyleAFit.blend(oldParams, paramBytes, s) ?: paramBytes
                } else {
                    paramBytes
                }
                val paramFile = ModelParamStore.write(this, styleId, versionId, bytes)
                if (paramFile.isEmpty()) {
                    mainHandler.post { toast("增强失败：写入参数出错") }
                    return@execute
                }
                mainHandler.post { commitEnhance(def, base, added, versionId, "", paramFile, s) }
                return@execute
            }

            if (!ModelLutStore.ensure(this, def, base)) {
                deletePendingLut(pendingFile)
                mainHandler.post { toast("增强失败：基准色彩文件缺失") }
                return@execute
            }
            val oldBytes = CustomLutBaker.readBytes(this, base.lutFile)
            val newBytes = CustomLutBaker.readBytes(this, pendingFile)
            if (oldBytes == null || newBytes == null) {
                deletePendingLut(pendingFile)
                mainHandler.post { toast("增强失败：色彩文件不可读") }
                return@execute
            }
            val lutFile = "lut_${styleId}_${versionId}.bin"
            // 强度 ≥99.9% 直接用新烘焙字节，省一次混合（结果完全等价）
            val bytes = if (s >= 0.999f) newBytes else CustomLutBaker.blendBytes(oldBytes, newBytes, s)
            val ok = CustomLutBaker.saveBytes(this, bytes, lutFile)
            deletePendingLut(pendingFile)
            if (!ok) {
                mainHandler.post { toast("增强失败：写入色彩文件出错") }
                return@execute
            }
            mainHandler.post { commitEnhance(def, base, added, versionId, lutFile, "", s) }
        }
    }

    /**
     * 提交新版本：截断游标之后的版本与文件 → seeds 截到 seedCount 再追加 → 追加版本
     * → 游标前移 → 回收最老的增强版（初始版永不删）→ 持久化 → 重出缩略图 → 必要时重跑预览。
     */
    private fun commitEnhance(
        def: CustomStyleDef,
        base: StyleVersion,
        added: List<RefSeed>,
        versionId: String,
        lutFile: String,
        paramFile: String,
        strength: Float,
        netStyle: AdaptiveColorNet.Style? = null
    ) {
        // 池在 enhanceFromRefs 里已加载；这里兜底，宁可这次不提交也不能把池写成空
        if (!CustomStyleStore.ensureSeeds(def)) {
            toast("参考图池读取失败，本次未提交")
            return
        }
        val dir = AlexaLut.customDir(this)
        val cut = def.versions.indexOf(base)
        if (cut >= 0) {
            while (def.versions.size > cut + 1) {
                val dropped = def.versions.removeAt(def.versions.size - 1)
                dropVersionArtifacts(dropped, dir)
            }
        }
        // seeds 是只增池：游标之后的种子属于被分叉覆盖的历史，先截掉再追加
        while (def.seeds.size > base.seedCount) def.seeds.removeAt(def.seeds.size - 1)
        def.seeds.addAll(added)

        val percent = Math.round(strength * 100)
        val ordinal = def.versions.count { it.source == VersionSource.ENHANCE } + 1
        val version = StyleVersion(
            id = versionId,
            createdAt = System.currentTimeMillis(),
            label = if (percent >= 100) "增强 $ordinal" else "增强 $ordinal $percent%",
            lutFile = lutFile,
            seedCount = def.seeds.size,
            source = VersionSource.ENHANCE,
            strength = strength,
            paramFile = paramFile,
            modelAff = netStyle?.aff ?: FloatArray(0),
            modelKnots = netStyle?.knots ?: FloatArray(0)
        )
        def.versions.add(version)
        def.currentVersionId = versionId

        while (def.versions.size > maxVersions) {
            val idx = def.versions.indexOfFirst {
                it.source != VersionSource.INITIAL && it.id != versionId
            }
            if (idx < 0) break
            dropVersionArtifacts(def.versions.removeAt(idx), dir)
        }
        CustomStyleStore.put(this, def)

        // 卡片缩略图必须按新 LUT 重出，否则卡片还显示旧色彩
        thumbCache.remove(def.id)
        ensureCustomThumb(def.id)

        // 该色彩正被选中时刷新主预览（完美成片/超分合成态由 onCustomSelected 自己拦）
        if (currentCustomId == def.id && originalBytes != null && !perfectApplied && srBakedStyle == null) {
            onCustomSelected(FilterEntry.Custom(def.id))
        }
        toast("已生成「${def.name}」${version.label}")
    }

    /** 删掉一个版本的全部产物（LUT / θ 快照 / A 路线参数）。 */
    private fun dropVersionArtifacts(version: StyleVersion, dir: File) {
        if (version.lutFile.isNotEmpty()) {
            AlexaLut.invalidate(AlexaLut.fileRef(version.lutFile))
            runCatching { File(dir, version.lutFile).delete() }
        }
        if (version.thetaFile.isNotEmpty()) {
            runCatching { ModelLutStore.thetaFileOf(this, version.thetaFile).delete() }
        }
        if (version.paramFile.isNotEmpty()) ModelParamStore.delete(this, version.paramFile)
    }

    /** 删掉预览用的临时 LUT（取消/失败/应用完成都要走一遍）。 */
    private fun deletePendingLut(fileName: String) {
        if (!fileName.startsWith("pending_")) return
        runCatching { File(AlexaLut.customDir(this), fileName).delete() }
    }

    /** 启动时清掉上次异常退出留下的 pending 文件（只服务预览，元数据不引用）。 */
    private fun cleanupPendingLuts() {
        runCatching {
            AlexaLut.customDir(this).listFiles()
                ?.filter { it.isFile && it.name.startsWith("pending_") }
                ?.forEach { it.delete() }
        }
    }

    // ---- 版本回退（S5）：游标左移 / 把某版本复制成新色彩 ----

    /** 历史版本面板入口；只有一个版本时没有历史可看，直接提示。 */
    private fun openVersionHistory(styleId: String) {
        if (enhancing || splitting) { toast("正在生成色彩，请稍候"); return }
        if (srBusy) { toast("处理中，请稍候"); return }
        val def = CustomStyleStore.byId(styleId) ?: run { toast("该色彩已不可用"); return }
        if (def.versions.size <= 1) { toast("这个色彩只有一个版本"); return }
        VersionHistorySheet.show(
            this, def,
            onRollback = { v -> rollbackToVersion(def.id, v) },
            onSplit = { v -> splitFromVersion(def.id, v) }
        )
    }

    /**
     * 回退：只移动游标，不动任何文件。有效参考图数随 `seedCount` 自动收窄，
     * 版本内容本身还在，之后再增强从这条链继续（游标之后的版本会被覆盖）。
     */
    private fun rollbackToVersion(styleId: String, version: StyleVersion) {
        if (enhancing || splitting) { toast("正在生成色彩，请稍候"); return }
        val def = CustomStyleStore.byId(styleId) ?: run { toast("该色彩已不可用"); return }
        if (def.currentVersionId == version.id) return
        // 参数版本查 1.6 KB 的 par_ 文件，旧版本仍查 LUT（语义与改前一致）
        if (!ModelColor.ready(this, version)) {
            toast("该版本的色彩文件已丢失，无法回退")
            return
        }
        // 用户明确换色彩时，在途的旧滤镜结果不该再落屏
        ++processingGeneration
        progress.gone()
        def.currentVersionId = version.id
        CustomStyleStore.put(this, def)

        // 卡片缩略图跟着当前版本换，否则卡片显示的还是新版本的色彩
        thumbCache.remove(styleId)
        ensureCustomThumb(styleId)

        if (currentCustomId == styleId && originalBytes != null && !perfectApplied && srBakedStyle == null) {
            onCustomSelected(FilterEntry.Custom(styleId))
        }
        toast("已回退到「${version.label}」")
    }

    /**
     * 新增色彩：把选中版本的 LUT 字节复制成独立文件 + 独立元数据，原色彩一个字段都不改。
     * 新色彩初始版本沿用该版本的参考图前缀与强度，source 记 INITIAL（它是新色彩的起点，不参与回收）。
     */
    private fun splitFromVersion(styleId: String, version: StyleVersion) {
        if (enhancing || splitting) { toast("正在处理，请稍候"); return }
        val def = CustomStyleStore.byId(styleId) ?: run { toast("该色彩已不可用"); return }
        splitting = true
        executor.execute {
            // 复制要带走有效种子，池未加载时先补（JSON 解析重，放后台线程）
            if (!CustomStyleStore.ensureSeeds(def)) {
                splitting = false
                mainHandler.post { toast("参考图池读取失败，请重试") }
                return@execute
            }
            val bytes = CustomLutBaker.readBytes(this, version.lutFile)
            if (bytes == null) {
                splitting = false
                mainHandler.post { toast("该版本的色彩文件已丢失，无法新增") }
                return@execute
            }
            val newId = CustomStyleStore.newId()
            val newFile = "lut_${newId}_v_${System.currentTimeMillis().toString(36)}.bin"
            val ok = CustomLutBaker.saveBytes(this, bytes, newFile)
            mainHandler.post {
                splitting = false
                if (!ok) {
                    toast("新增色彩失败：写入出错")
                    return@post
                }
                val seeds = def.seeds
                    .take(version.seedCount.coerceIn(0, def.seeds.size))
                    .toMutableList()
                val initial = StyleVersion(
                    id = "v_" + System.currentTimeMillis().toString(36),
                    createdAt = System.currentTimeMillis(),
                    label = "初始",
                    lutFile = newFile,
                    seedCount = seeds.size,
                    source = VersionSource.INITIAL,
                    strength = version.strength
                )
                val copy = CustomStyleDef(
                    id = newId,
                    name = uniqueCopyName(def.name),
                    createdAt = System.currentTimeMillis(),
                    seeds = seeds,
                    versions = mutableListOf(initial),
                    currentVersionId = initial.id
                )
                CustomStyleStore.put(this, copy)
                buildFilterBar(currentCategory)
                // 复制出的新色彩自动选中（完美/合成态下换色会被拦，交给用户先解除）
                if (originalBytes != null && !perfectApplied && srBakedStyle == null) {
                    onCustomSelected(FilterEntry.Custom(newId))
                }
                val idx = cardEntries.indexOfFirst { it.id == newId }
                if (idx >= 0) {
                    val target = cardViews[idx]
                    filterScroll.post { filterScroll.smoothScrollTo(target.left - dp(6), 0) }
                }
                toast("已新增「${copy.name}」")
            }
        }
    }

    /** 副本名「X 副本」：重名加序号、超 8 字都在这里收敛 */
    private fun uniqueCopyName(base: String): String {
        val used = CustomStyleStore.all().mapTo(HashSet()) { it.name }
        var i = 1
        var name: String
        do {
            val suffix = if (i == 1) " 副本" else " 副本$i"
            name = base.take((maxStyleNameLen - suffix.length).coerceAtLeast(1)) + suffix
            i++
        } while (used.contains(name))
        return name.take(maxStyleNameLen)
    }

    // ---- 曝光步进器 ----

    /** 当前应显示的基图（未调曝光）：滤镜生效且结果就绪时为结果，否则为原图 */
    private fun currentBaseBitmap(): Bitmap? =
        if (hasColor() && resultBitmap != null) resultBitmap else originalBitmap

    /** 按档位状态刷新预览显示源（曝光版优先，无曝光版回退基图） */
    private fun refreshPreviewImage() {
        val bmp = if (hasColor() && resultBitmap != null) {
            liftedResult ?: resultBitmap
        } else {
            liftedOriginal ?: originalBitmap
        }
        if (bmp != null) imgPreview.setImageBitmap(bmp)
    }

    /**
     * P3：五档滑杆（进度 0..20 ↔ 档位 -10..+10）。拖动实时调档——预览走 recomputeAdjAsync
     * 既有的 1920 降采样基准 + 专用队列 + 代次过期秒退，连拖只算最新一档，全程不卡顿；
     * valueText 为行尾固定宽数值列（"0"/"+n"/"-n"）。双击复位面=滑杆所在的整行
     * （sb.parent：子滑杆自消费触摸，行级只收到名称/数值/空白区的点按），复位到 0
     * 后由 apply 链路的 updateLumBarUi 把滑杆进度回写归中。
     */
    private fun setupSlider(sb: SeekBar, valueText: TextView, get: () -> Int, set: (Int) -> Unit) {
        sb.max = 20
        sb.progress = get() + 10
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progressLevel: Int, fromUser: Boolean) {
                if (fromUser) set(progressLevel - 10)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!srBusy) set(0)
                return true
            }
        })
        val surface = sb.parent as View
        surface.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun applyBrightChange(newLevel: Int) {
        val level = Brightness.clamp(newLevel)
        if (level == brightLevel) return
        brightLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applyExposureChange(newLevel: Int) {
        val level = Exposure.clamp(newLevel)
        if (level == exposureLevel) return
        exposureLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applyHighChange(newLevel: Int) {
        val level = Highlights.clamp(newLevel)
        if (level == highlightLevel) return
        highlightLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applyShadowChange(newLevel: Int) {
        val level = Shadows.clamp(newLevel)
        if (level == shadowLevel) return
        shadowLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applyBlackChange(newLevel: Int) {
        val level = BlackLevel.clamp(newLevel)
        if (level == blackLevel) return
        blackLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applySatChange(newLevel: Int) {
        val level = newLevel.coerceIn(ToneCurve.MIN_LEVEL, ToneCurve.MAX_LEVEL)
        if (level == satLevel) return
        satLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applyContrastChange(newLevel: Int) {
        val level = newLevel.coerceIn(ToneCurve.MIN_LEVEL, ToneCurve.MAX_LEVEL)
        if (level == contrastLevel) return
        contrastLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    private fun applyGlowChange(newLevel: Int) {
        val level = newLevel.coerceIn(SoftGlow.MIN_LEVEL, SoftGlow.MAX_LEVEL)
        if (level == glowLevel) return
        glowLevel = level
        updateLumBarUi()
        recomputeAdjAsync()
    }

    // 调档预览基准（短边≤1920 降采样缓存，仅 adjExecutor 线程访问）：
    // 调档只跑小图（12MP→~2.8MP，快 4-8 倍）；导出烘焙仍走全分辨率工作字节，
    // 预览与导出是同一 ToneCurve 曲线，仅分辨率差异
    private var adjBaseO: Bitmap? = null
    private var adjBaseR: Bitmap? = null
    private var adjSrcO: Bitmap? = null
    private var adjSrcR: Bitmap? = null

    /**
     * 调档预览基准（仅 adjExecutor 线程调用）。基图替换/回收后（引用不匹配或已回收）
     * 自动重建；基图本身不大则直接复用基图对象（不额外占内存）。
     */
    private fun ensureAdjBase(src: Bitmap?, isOrig: Boolean): Bitmap? {
        val oldBase = if (isOrig) adjBaseO else adjBaseR
        val oldSrc = if (isOrig) adjSrcO else adjSrcR
        if (src == null || src.isRecycled) {
            if (oldBase != null && oldBase !== oldSrc && !oldBase.isRecycled) oldBase.recycle()
            if (isOrig) { adjBaseO = null; adjSrcO = null } else { adjBaseR = null; adjSrcR = null }
            return null
        }
        if (oldBase != null && !oldBase.isRecycled && oldSrc === src) return oldBase
        // 基图已换：重建基准（旧基准若是独立位图则回收；与基图同对象则交给基图生命周期）
        if (oldBase != null && oldBase !== oldSrc && !oldBase.isRecycled && oldBase !== src) {
            oldBase.recycle()
        }
        val next = scaleBitmap(src, 1920)
        if (isOrig) { adjBaseO = next; adjSrcO = src } else { adjBaseR = next; adjSrcR = src }
        return next
    }

    /**
     * 依据当前基图与五档调整重算调整显示版（主线程调用，重活走 adjExecutor 专用队列）。
     * 五档均为 0 时切回基图并回收调整版；否则后台重算（亮度→曝光→高光→阴影→黑色阶，与烘焙同序），
     * 完成后先换显示再回收旧版。
     *
     * 响应性三件套：专用队列（不排在导出/风格长任务后）＋任务开头过期秒退（连档只跑最新一档）
     * ＋降采样预览基准（全尺寸基图不再全图重算）。
     */
    private fun recomputeAdjAsync() {
        val gen = ++liftGen
        val br = brightLevel
        val ev = exposureLevel
        val hl = highlightLevel
        val sh = shadowLevel
        val bl = blackLevel
        val sa = satLevel
        val co = contrastLevel
        val gl = glowLevel
        if (br == 0 && ev == 0 && hl == 0 && sh == 0 && bl == 0 && sa == 0 && co == 0 && gl == 0) {
            // 先置空调整字段再换显示：refreshPreviewImage 才会取基图，
            // 避免把即将回收的调整版重新 setImageBitmap 上屏（否则下一帧画已回收位图崩溃）
            val oldO = liftedOriginal
            val oldR = liftedResult
            liftedOriginal = null
            liftedResult = null
            refreshPreviewImage()
            oldO?.recycle()
            oldR?.recycle()
            return
        }
        val origBase = originalBitmap
        val resultBase = resultBitmap
        adjExecutor.execute {
            // 排队期间已过期（连点加减档/换图）：直接放弃，不做全量重算
            if (gen != liftGen) return@execute
            var lo: Bitmap? = null
            var lr: Bitmap? = null
            // P2：RAW 提亮/强力提亮档 + 原图卡片（无风格结果）→ 五档直接作用于
            // 导入时缓存的 16-bit 线性基准：RAW 高位深保真，调阴影不出量化色带
            val lin = rawLin16
            if (lin != null && resultBase == null) {
                val params = ToneCurve.of(br, ev, hl, sh, bl, sa, co)
                try {
                    val px = IntArray(lin.w * lin.h)
                    ToneCurve.applyLinear16ToArgb(lin.data, lin.w, lin.h, params, px)
                    lo = Bitmap.createBitmap(lin.w, lin.h, Bitmap.Config.ARGB_8888)
                    lo.setPixels(px, 0, lin.w, 0, 0, lin.w, lin.h)
                    // 柔光是空间算子，不进 16-bit 曲线域——降 8-bit 后叠加（与导出链同口径）
                    if (gl != 0) SoftGlow.applyInPlace(lo, gl)
                } catch (t: Throwable) {
                    DiagLog.e("Lum", "RAW 16bit 调整失败，回落 8bit", t)
                    lo?.recycle(); lo = null
                }
            }
            if (lo == null) {
                // 8-bit 路径：基图可能在排队期间被主线程回收，scaleBitmap 有竞态窗口，兜一层
                val bO = try { ensureAdjBase(origBase, true) } catch (t: Throwable) {
                    DiagLog.e("Lum", "原图基准准备失败", t); null
                } ?: return@execute
                val bR = try { ensureAdjBase(resultBase, false) } catch (t: Throwable) {
                    DiagLog.e("Lum", "结果基准准备失败", t); null
                }
                val params = ToneCurve.of(br, ev, hl, sh, bl, sa, co)
                lo = try {
                    ToneCurve.applyToBitmap(bO, params)
                } catch (t: Throwable) {
                    DiagLog.e("Lum", "原图调整失败", t); null
                }
                lr = try {
                    bR?.let { ToneCurve.applyToBitmap(it, params) }
                } catch (t: Throwable) {
                    DiagLog.e("Lum", "结果调整失败", t); null
                }
                // 柔光：曲线之后叠空间算子（1920 预览基准下半径 ~min/100，代次过期秒退兜住耗时）。
                // 坑（真机闪退定罪）：isNeutral(p) 时 applyToBitmap 原样返回输入——七档全 0 只拖柔光时
                // lo===bO / lr===bR 同实例，原地 glow 会烘进原图本体，且落地段 recycle(lo) 会把
                // 全局 bO 回收 → 下一帧绘制已回收位图崩溃。必须先抄独立副本再做柔光。
                if (gl != 0) {
                    if (lo !== null && lo === bO) lo = lo.copy(Bitmap.Config.ARGB_8888, true)
                    if (lr !== null && bR !== null && lr === bR) lr = lr.copy(Bitmap.Config.ARGB_8888, true)
                    lo?.let { SoftGlow.applyInPlace(it, gl) }
                    lr?.let { SoftGlow.applyInPlace(it, gl) }
                }
            }
            mainHandler.post {
                if (gen != liftGen) {
                    lo?.recycle(); lr?.recycle()
                    return@post
                }
                val oldO = liftedOriginal
                val oldR = liftedResult
                liftedOriginal = lo
                liftedResult = lr
                refreshPreviewImage()
                oldO?.recycle()
                oldR?.recycle()
            }
        }
    }

    private fun updateLumBarUi() {
        updateAdjustEntryText()
        // 亮度组（滑杆进度由本函数统一回写；程序性 setProgress 触发的
        // onProgressChanged(fromUser=false) 在 setupSlider 中被忽略，无回环）
        val v = brightLevel
        txtBrightVal.text = if (v > 0) "+$v" else "$v"
        sbBright.progress = v + 10
        sbBright.isEnabled = !srBusy
        sbBright.alpha = if (srBusy) 0.35f else 1f
        // 曝光组
        val e = exposureLevel
        txtLumVal.text = if (e > 0) "+$e" else "$e"
        sbLum.progress = e + 10
        sbLum.isEnabled = !srBusy
        sbLum.alpha = if (srBusy) 0.35f else 1f
        // 高光组
        val hl = highlightLevel
        txtHighVal.text = if (hl > 0) "+$hl" else "$hl"
        sbHigh.progress = hl + 10
        sbHigh.isEnabled = !srBusy
        sbHigh.alpha = if (srBusy) 0.35f else 1f
        // 阴影组
        val s = shadowLevel
        txtShadowVal.text = if (s > 0) "+$s" else "$s"
        sbShadow.progress = s + 10
        sbShadow.isEnabled = !srBusy
        sbShadow.alpha = if (srBusy) 0.35f else 1f
        // 黑色阶组
        val b = blackLevel
        txtBlackVal.text = if (b > 0) "+$b" else "$b"
        sbBlack.progress = b + 10
        sbBlack.isEnabled = !srBusy
        sbBlack.alpha = if (srBusy) 0.35f else 1f
        // 饱和度组
        val sa = satLevel
        txtSatVal.text = if (sa > 0) "+$sa" else "$sa"
        sbSat.progress = sa + 10
        sbSat.isEnabled = !srBusy
        sbSat.alpha = if (srBusy) 0.35f else 1f
        // 对比度组
        val co = contrastLevel
        txtConVal.text = if (co > 0) "+$co" else "$co"
        sbCon.progress = co + 10
        sbCon.isEnabled = !srBusy
        sbCon.alpha = if (srBusy) 0.35f else 1f
        // 柔光组
        val gl = glowLevel
        txtGlowVal.text = if (gl > 0) "+$gl" else "$gl"
        sbGlow.progress = gl + 10
        sbGlow.isEnabled = !srBusy
        sbGlow.alpha = if (srBusy) 0.35f else 1f
    }

    /** 调节浮层开合：点击「调节」在预览上向上展开三个胶囊调节项，再点收起 */
    private fun toggleAdjPanel() {
        adjPanel.visibility = if (adjPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 入口胶囊文字：无调整时「调节」，有调整时附带生效项数 */
    private fun updateAdjustEntryText() {
        val n = listOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel,
            satLevel, contrastLevel, glowLevel).count { it != 0 }
        btnAdjust.text = if (n == 0) "调节" else "调节 · $n"
    }

    // ---- 超分 ----

    private var srStartedBytes: ByteArray? = null   // 任务启动时的输入快照，Done 时校验身份
    private var srStartedStyle: LeicaEngine.Style? = null // 超分前选中的色彩，完成后保持重应用
    private var srBakedStyle: LeicaEngine.Style? = null   // 已合成进当前图的色彩（导出跳过二次套色）
    private var srStartedFast: Boolean = false             // 本次超分是否快速模型
    private var srStartedPerfect: Boolean = false          // 本次画质任务是否为完美档
    private var perfectBytes: ByteArray? = null            // 完美成片字节（非空 = 完美已应用）
    private var perfectStyle: LeicaEngine.Style? = null    // 完美成片所用风格
    private var perfectApplied = false                     // 完美档已应用（按钮高亮）

    private val srListener: (SuperResTask.State) -> Unit = { st ->
        when (st) {
            is SuperResTask.State.Idle -> {
                srBusy = false
                resetQualityButtons()
                setPerfectButton(perfectApplied)
                updateLumBarUi()
            }
            is SuperResTask.State.Running -> {
                srBusy = true
                btnSuperRes.isEnabled = false
                btnSuperResFast.isEnabled = false
                btnPerfect.isEnabled = false
                if (srStartedPerfect) {
                    btnPerfect.text = "完美处理中 ${st.progress}%"
                    DiagLog.i("Perfect", "完美档启动 ${st.progress}%")
                } else {
                    DiagLog.i("SuperRes", "${if (srStartedFast) "快速" else "光学"}超分启动 ${st.progress}%")
                    if (srStartedFast) btnSuperResFast.text = "快速超分中 ${st.progress}%"
                    else btnSuperRes.text = "光学超分中 ${st.progress}%"
                }
                updateLumBarUi()
            }
            is SuperResTask.State.Done -> {
                srBusy = false
                resetQualityButtons()
                updateLumBarUi()
                if (srStartedPerfect) {
                    // 完美成片：不改工作字节（保证「再按一下可关闭」）、不自动导出
                    DiagLog.i("Perfect", "完成 ${st.width}x${st.height} 输出 ${st.jpeg.size}B（未自动导出）")
                    perfectBytes = st.jpeg
                    perfectStyle = srStartedStyle
                    perfectApplied = true
                    // 完美成片走 perfectBytes 导出：全像素直通源与之无关，作废防错配
                    clearFullRes()
                    setPerfectButton(true)
                    resultBitmap?.recycle()
                    resultBitmap = decodePreview(st.jpeg, 2048)
                    liftedOriginal?.recycle(); liftedOriginal = null
                    liftedResult?.recycle(); liftedResult = null
                    resultBitmap?.let { imgPreview.setImageBitmap(it) }
                    val ps = srStartedStyle
                    txtStyleHint.text = if (ps != null) "${ps.label} · 完美成片" else "完美成片"
                    generateThumbnails()
                    val sec = st.elapsedMs / 1000
                    val dur = if (sec >= 60) "${sec / 60}分${sec % 60}秒" else "${sec}秒"
                    toast("完美成片已生成 · 用时 $dur · 请手动导出")
                    srStartedPerfect = false
                } else if (originalBytes === srStartedBytes) {
                    // 用户未换图：预览/工作字节升级为高清版
                    originalBytes = st.jpeg
                    // 工作字节已被超分成片替换：200MP 全像素直通源与之不再对应，作废
                    clearFullRes()
                    // 图已被替换为超分成片，完美态随之失效（避免重复套色/高亮错位）
                    perfectApplied = false
                    perfectBytes = null
                    perfectStyle = null
                    setPerfectButton(false)
                    originalBitmap?.recycle()
                    originalBitmap = decodePreview(st.jpeg, 2048)
                    // 源已是超分 JPEG，RAW 16-bit 窗口作废
                    rawLin16 = null
                    rawPreviewJpeg = null
                    resultBitmap?.recycle()
                    resultBitmap = null
                    // 旧曝光版基于已回收/已替换的基图，随基图升级一并作废（同帧内换图，安全）
                    liftedOriginal?.recycle(); liftedOriginal = null
                    liftedResult?.recycle(); liftedResult = null
                    val hadStyle = srStartedStyle
                    if (hadStyle != null) {
                        // 色彩已在超分前合成进图：卡片保持该色彩选中（合成态），导出不再二次套色
                        srBakedStyle = hadStyle
                        currentStyle = hadStyle
                        selectCardByStyle(hadStyle)
                        txtStyleHint.text = "${hadStyle.label} · 已合成"
                    } else {
                        srBakedStyle = null
                        currentStyle = null
                        selectCard(0)
                        txtStyleHint.text = "原图 · 已放大"
                    }
                    originalBitmap?.let { imgPreview.setImageBitmap(it) }
                    generateThumbnails()
                    // 档位≠0 时基于新高清基图重算曝光显示版（自动导出已带档位烘焙）
                    recomputeAdjAsync()
                    DiagLog.i("SuperRes", "完成 ${st.width}x${st.height} 输出 ${st.jpeg.size}B 已自动导出")
                    val sec = st.elapsedMs / 1000
                    val dur = if (sec >= 60) "${sec / 60}分${sec % 60}秒" else "${sec}秒"
                    toast("已放大至 ${st.width}×${st.height} · 用时 $dur · 已自动导出图库")
                } else {
                    toast("超分完成，已保存至图库")
                }
                SuperResTask.consume()
            }
            is SuperResTask.State.Failed -> {
                srBusy = false
                DiagLog.e("SuperRes", "失败: ${st.message.takeLast(300)}")
                resetQualityButtons()
                setPerfectButton(perfectApplied)
                updateLumBarUi()
                toast(if (srStartedPerfect) "完美处理失败，请重试（详情见日志）"
                else "超分失败，请重试（详情见日志）")
                srStartedPerfect = false
                SuperResTask.consume()
            }
        }
    }

    /** 大图降采样解码预览（避免整图解码 48MP 占内存） */
    private fun decodePreview(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    // ---- 完美档（OffLineM9 后段全量；开关语义；不自动导出）----

    /** 完美按钮仅在 M9 / M9+ / M10-R 三档出现（其余含原图一律隐藏）。 */
    private fun updatePerfectVisibility(style: LeicaEngine.Style?) {
        val show = style == LeicaEngine.Style.M9 || style == LeicaEngine.Style.M9P ||
                style == LeicaEngine.Style.M10R
        if (show) btnPerfect.visible() else btnPerfect.gone()
    }

    /** 画质三钮复位（启用 + 常态文案）。 */
    private fun resetQualityButtons() {
        btnSuperRes.isEnabled = true
        btnSuperRes.text = "光学超分"
        btnSuperResFast.isEnabled = true
        btnSuperResFast.text = "快速超分"
        btnPerfect.isEnabled = true
    }

    /** 完美按钮外观：已应用 = 亮底黑字 + ✓；未应用 = 常态玻璃底白字。 */
    private fun setPerfectButton(applied: Boolean) {
        if (applied) {
            btnPerfect.setBackgroundResource(R.drawable.bg_pill_active)
            btnPerfect.setTextColor(Color.BLACK)
            btnPerfect.text = "完美 ✓"
        } else {
            btnPerfect.setBackgroundResource(R.drawable.bg_pill)
            btnPerfect.setTextColor(Color.WHITE)
            btnPerfect.text = "完美"
        }
    }

    private fun onPerfectClicked() {
        if (exporting) { toast("全像素导出进行中，请稍候"); return }
        if (srBusy) { toast("处理中，请稍候"); return }
        if (perfectApplied) { clearPerfectState(); return }   // 再按一下 = 关闭
        if (originalBytes == null) { toast("请先导入图片"); return }
        if (srBakedStyle != null) { toast("当前图为超分合成图，请重新导入原图后再用「完美」"); return }
        if (currentCustomId != null) { toast("「完美」需要内置色彩，自定义色彩暂不支持"); return }
        if (currentStyle == null) { toast("请先选择 M9 / M9+ / M10-R"); return }
        confirmPerfect()
    }

    private fun confirmPerfect() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("完美处理")
            .setMessage(buildPerfectMessage())
            .setPositiveButton("处理") { _, _ -> launchPerfect() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 完美确认文案：首行红色耗时 + 「不自动导出」提示 */
    private fun buildPerfectMessage(): CharSequence {
        val red = "生成约 3 分钟（视机型而定），处理过程会伴随发热；完成后不自动导出，需手动保存\n"
        val rest = "使用 OffLineM9 离线管线（StyleTrans + ColorFix）重建画面，色彩已包含在成片内，不会二次套色。\n" +
            "处理长边上限 4096，超过该尺寸的原图会先等比缩至该长边。\n" +
            "转为后台处理，可回到桌面，通知栏显示进度。\n" +
            "完成后可手动导出，或继续做超分放大；再按一下「完美」可关闭。"
        val ss = android.text.SpannableString(red + rest)
        ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    private fun launchPerfect() {
        val src = originalBytes ?: run { toast("请先导入图片"); return }
        srStartedBytes = src
        srStartedStyle = currentStyle
        srStartedFast = false
        srStartedPerfect = true
        SuperResTask.pendingInput = src
        SuperResTask.pendingStyle = null                 // 完美档不套色（OffLineM9 自带色彩）
        SuperResTask.pendingPerfect = true
        SuperResTask.pendingPerfectStyle = currentStyle
        SuperResTask.pendingPerfectDngUri = if (sourceIsRaw) sourceUri else null
        SuperResTask.pendingFast = false
        SuperResTask.pendingHdr = null                   // HDR 增益图在导出时回嵌
        SuperResTask.pendingBrightLevel = brightLevel
        SuperResTask.pendingExposure = exposureLevel
        SuperResTask.pendingHighlightLevel = highlightLevel
        SuperResTask.pendingShadowLevel = shadowLevel
        SuperResTask.pendingBlackLevel = blackLevel
        SuperResTask.pendingSatLevel = satLevel
        SuperResTask.pendingContrastLevel = contrastLevel
        SuperResTask.pendingGlowLevel = glowLevel
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        ContextCompat.startForegroundService(this, Intent(this, SuperResService::class.java))
    }

    /** 关闭完美：丢成片 + 去高亮；当前若有选中风格则按其重算预览（回到未应用完美的样子）。 */
    private fun clearPerfectState() {
        perfectBytes = null
        perfectStyle = null
        perfectApplied = false
        setPerfectButton(false)
        if (hasColor()) onFilterSelected(currentEntry()) else refreshPreviewImage()
    }

    private fun confirmSuperRes(fast: Boolean) {
        // 完美成片可直接继续超分：源用成片、超分阶段不再套色
        val src = (if (perfectApplied) perfectBytes else originalBytes)
            ?: run { toast("请先导入图片"); return }
        if (exporting) { toast("全像素导出进行中，请稍候"); return }
        if (srBusy) return
        // 按工作字节真实尺寸估算：超分输入 = 工作字节（宽/高已受 4096/3072 逐轴封顶），输出 ×2
        val w = (if (workWidth > 0) workWidth else originalBitmap?.width ?: 0) * 2
        val h = (if (workHeight > 0) workHeight else originalBitmap?.height ?: 0) * 2
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (fast) "快速超分 ×2" else "光学超分 ×2")
            .setMessage(
                if (fast) buildFastMessage(w, h)
                else buildSlowMessage(w, h)
            )
            .setPositiveButton("放大") { _, _ -> launchSuperRes(src, fast) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 快速超分确认文案：标题下第一行为红色耗时提示 */
    private fun buildFastMessage(w: Int, h: Int): CharSequence {
        val red = "生成约2分钟时间，超分过程会伴随发热，完成后会自动导出\n"
        val rest = "将放大到约 ${w}×${h}（${w.toLong() * h / 1000000}MP）。\n" +
            "转为后台处理，可回到桌面，通知栏/超级岛显示进度。\n" +
            (if (currentStyle != null) "已选色彩将先合成进原图，超分完成后成图自带该色彩。\n" else "") +
            "使用快速模型（速度优先）。\n" +
            "仅增强细节，不改变原有色彩。"
        val ss = android.text.SpannableString(red + rest)
        ss.setSpan(
            android.text.style.ForegroundColorSpan(Color.RED),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return ss
    }

    /** 光学超分确认文案：标题下第一行为红色耗时提示 */
    private fun buildSlowMessage(w: Int, h: Int): CharSequence {
        val red = "生成约10分钟时间，超分过程会伴随发热，完成后会自动导出\n"
        val rest = "将放大到约 ${w}×${h}（${w.toLong() * h / 1000000}MP）。\n" +
            "转为后台处理，可回到桌面，通知栏/超级岛显示进度。\n" +
            (if (currentStyle != null) "已选色彩将先合成进原图，超分完成后成图自带该色彩。\n" else "") +
            "仅增强细节，不改变原有色彩。"
        val ss = android.text.SpannableString(red + rest)
        ss.setSpan(
            android.text.style.ForegroundColorSpan(Color.RED),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return ss
    }

    private fun launchSuperRes(src: ByteArray, fast: Boolean) {
        // 自定义色彩走运行时 LUT，暂不并进超分合成链（防成图彩色却没套上色）
        if (currentCustomId != null) {
            toast("自定义色彩暂不支持超分合成，请先切换内置色彩或原图")
            return
        }
        // 完美态下：成片已自带色彩，超分只做放大（不套色），完成后按超分语义自动导出
        val style = if (perfectApplied) null else currentStyle
        srStartedBytes = src
        srStartedStyle = style
        srStartedFast = fast
        srStartedPerfect = false
        SuperResTask.pendingInput = src
        SuperResTask.pendingStyle = style
        SuperResTask.pendingPerfect = false
        SuperResTask.pendingPerfectStyle = null
        SuperResTask.pendingFast = fast
        SuperResTask.pendingHdr = pendingHdr
        SuperResTask.pendingBrightLevel = brightLevel
        SuperResTask.pendingExposure = exposureLevel
        SuperResTask.pendingHighlightLevel = highlightLevel
        SuperResTask.pendingShadowLevel = shadowLevel
        SuperResTask.pendingBlackLevel = blackLevel
        SuperResTask.pendingSatLevel = satLevel
        SuperResTask.pendingContrastLevel = contrastLevel
        SuperResTask.pendingGlowLevel = glowLevel
        // Android 13+ 通知权限：拒绝不影响超分执行，仅无岛/通知展示
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        ContextCompat.startForegroundService(this, Intent(this, SuperResService::class.java))
    }

    // 预览图缩放/平移/双击/长按对比：全部内聚在 ZoomableImageView，本类只挂对比回调

    // ---- 导出 ----

    private fun export() {
        if (exporting) { toast("全像素导出进行中，请稍候"); return }
        if (srBusy) { toast("处理中，请稍候"); return }
        val bytes = originalBytes ?: run { toast("请先导入图片"); return }
        // 完美态：成片自带色彩，直接落盘（跳过滤镜链，避免二次套色）
        if (perfectApplied && perfectBytes != null) { exportPerfect(); return }
        // 合成态：超分完成时已自动导出（带徽章），手动导出会产生重复文件
        if (srBakedStyle != null) { toast("超分完成后已自动导出至图库"); return }
        // C1/C4：全像素导出。原图档直通；甲档（LUT 家族）与乙档（模型链 M9/M9+/M10R/M9D）
        // 带滤镜逐带处理，同为全像素。SA 与 LP9（P9Post 依赖全图空间场）仍按工作字节导出。
        // 兜底重入：M9 高分辨率导出失败时会置位并重调本方法，让这次跳过全像素分支。
        val fullSrc = if (skipFullResOnce) { skipFullResOnce = false; null } else fullResFile
        if (fullSrc != null) {
            if (!hasColor()) { exportFullRes(fullSrc); return }
            val fCustomDef = currentCustomId?.let { CustomStyleStore.byId(it) }
            val fCustomVer = fCustomDef?.let { CustomStyleStore.currentVersion(it) }
            if (currentCustomId != null && (fCustomDef == null || fCustomVer == null)) {
                toast("该色彩的 LUT 不可用，请重新选择")
                return
            }
            val lutRef = if (fCustomVer != null && !ModelColor.isParamVersion(fCustomVer)) {
                AlexaLut.fileRef(fCustomVer.lutFile)
            } else null
            if (LutBandOps.supports(currentStyle, lutRef)) {
                if (fCustomDef != null && fCustomVer != null &&
                    !ModelColor.ensure(this, fCustomDef, fCustomVer)
                ) {
                    toast("「${fCustomDef.name}」色彩文件缺失，无法导出")
                    return
                }
                val op = LutBandOps.create(
                    this, currentStyle, lutRef,
                    intArrayOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel, satLevel, contrastLevel)
                )
                if (op != null) {
                    val tag = fCustomDef?.name?.let { sanitizeFileTag(it) }
                        ?: (currentStyle?.id ?: "orig")
                    val sid = fCustomDef?.id ?: currentStyle?.id ?: "orig"
                    exportFullRes(fullSrc, op, tag, sid)
                    return
                }
            }
            // 丙档：M6 自定义模型风格（aff/knots 逐像素，可条带；最高 50MP）
            if (fCustomDef?.hasModelNet == true && AdaptiveColorNet.isNativeAvailable() &&
                ModelNetBandOps.supports(fullResWidth, fullResHeight)
            ) {
                val modelStyle = fCustomDef.modelStyle
                if (modelStyle != null) {
                    val op = ModelNetBandOps(
                        AdaptiveColorNet.Style(modelStyle.first, modelStyle.second),
                        intArrayOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel, satLevel, contrastLevel)
                    )
                    val tag = sanitizeFileTag(fCustomDef.name)
                    DiagLog.i("FullRes", "M6 模型风格全像素条带：${fCustomDef.name} " +
                        "${fullResWidth}×${fullResHeight}")
                    exportFullRes(fullSrc, op, tag, fCustomDef.id, onFailure = {
                        DiagLog.w("FullRes", "M6 全像素条带失败 → 回落工作版")
                        fullResFallbackOnce = true
                        skipFullResOnce = true
                        export()
                    })
                    return
                }
            }
            // 乙档：模型链（M9 / M9+ / M10-R / M9D）
            if (DistillBandOps.supports(currentStyle)) {
                val sid = currentStyle?.id ?: "orig"
                // B 方案（2026-10-21 用户确认）：M9 / M9D 放弃全像素条带链，改走
                // 「高分辨率工作链」——整图链在 24MP 级画布上出图，暖调**全图一次调用**，
                // 结构上不存在带边收敛/条带台阶/周期纹（条带链的全部问题的根因）。
                // 失败（预算/超时/native 异常）自动回落工作版（[exportWork] 口径），绝不崩。
                // M9P / M10R 带额外 boost/LUT 后处理，暂保持条带链（零改动）。
                if (sid == "M9" || sid == "M9D") {
                    // RAW 源不会进到这里（口径 A，2026-10-22 复核确认）：fullResFile 只在
                    // 导入期为 JPEG 源置位（fullResDecision 要求 trimmed!=null），RAW 恒为
                    // null → 上方 fullSrc 门已把 RAW 落到工作版 12.5MP（P2 的 RAW 16-bit
                    // 五档重放也在工作版内生效）。24MP 链仅服务 ≥45MP 的 JPG 源。
                    exportDistillHiRes(fullSrc, bytes, sid, sid)
                    return
                }
                // M9P / M10R：保持原有条带链全像素口径
                val op = DistillBandOps.create(
                    this, currentStyle,
                    intArrayOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel, satLevel, contrastLevel)
                )
                if (op != null) {
                    exportFullRes(fullSrc, op, sid, sid)
                    return
                }
                DiagLog.i("FullRes", "模型链条带算子创建失败 → 按工作版导出")
            }
            if (lutRef != null) {
                DiagLog.i("FullRes", "自定义 LUT 走 CustomLutPost 全图引导滤波，无法按带等价复现 → 按工作版导出")
            }
        }
        if (!hasColor()) { toast("请先选择一个滤镜"); return }
        val customDef = currentCustomId?.let { CustomStyleStore.byId(it) }
        val customVer = customDef?.let { CustomStyleStore.currentVersion(it) }
        if (currentCustomId != null && (customDef == null || customVer == null)) {
            toast("该色彩的 LUT 不可用，请重新选择")
            return
        }
        val styleId = customDef?.id ?: currentStyle?.id ?: "orig"
        // 文件名跟名字走（重命名后导出即新名），id 只用于日志
        val nameTag = customDef?.name?.let { sanitizeFileTag(it) } ?: styleId
        progress.visible()
        val gen = ++processingGeneration
        executor.execute {
            val t0 = System.currentTimeMillis()
            val brL = brightLevel
            val evL = exposureLevel
            val hlL = highlightLevel
            val shL = shadowLevel
            val blL = blackLevel
            val saL = satLevel
            val coL = contrastLevel
            val glL = glowLevel
            // M5：被回收的版本先按 θ 快照重烘；失败就明确报错，不能导出原图冒充成片
            // M6 模型没有 LUT/param，跳过 ensure，走 renderModelNet
            if (customDef != null && customVer != null && !customDef.hasModelNet &&
                !ModelColor.ensure(this, customDef, customVer)) {
                DiagLog.w("Export", "${customDef.name} 色彩文件缺失且无法重建")
                mainHandler.post {
                    progress.gone()
                    if (gen == processingGeneration) toast("「${customDef.name}」色彩文件缺失，无法导出")
                }
                return@execute
            }
            // P2：RAW 提亮/强力提亮档 + 七档非零 → 调整前移到 16-bit 线性段重放
            // （RAW 高位深保真，调阴影无 8-bit 源量化色带），风格引擎消费调整后的 JPEG。
            // 与预览口径一致：预览的七档同样作用于无风格数据。重放失败回落原 8-bit 链。
            var rawToned: ByteArray? = null
            val toneRequested = brL != 0 || evL != 0 || hlL != 0 || shL != 0 || blL != 0 ||
                saL != 0 || coL != 0
            if (toneRequested && rawLin16 != null && rawLin16Level != 0) {
                val t16 = System.currentTimeMillis()
                rawToned = raw16TonedWorkBytes(brL, evL, hlL, shL, blL, saL, coL, glL)
                DiagLog.i("Export", "RAW 16bit 七档重放 ${if (rawToned != null) "成功" else "失败→回落8bit"} " +
                        "耗时 ${System.currentTimeMillis() - t16}ms")
            }
            var out = if (rawToned != null) {
                LeicaEngine.process(this, rawToned, currentStyle!!, onFallback = { st ->
                    DiagLog.w("Export", "${st.label} 处理触发降级")
                    mainHandler.post { toast(fallbackMsg(st)) }
                })
            } else if (customDef != null && customVer != null) {
                if (customDef.hasModelNet) {
                    // 导出必须与预览走同一条 native 链，否则导出会丢风格
                    renderModelNet(bytes, customDef) ?: ModelColor.render(this, bytes, customVer) {
                        DiagLog.w("Export", "${customDef.name} 色彩文件不可用，导出原图")
                        mainHandler.post { toast("「${customDef.name}」色彩文件不可用，已导出原图") }
                    }
                } else {
                    ModelColor.render(this, bytes, customVer) {
                        DiagLog.w("Export", "${customDef.name} 色彩文件不可用，导出原图")
                        mainHandler.post { toast("「${customDef.name}」色彩文件不可用，已导出原图") }
                    }
                }
            } else {
                LeicaEngine.process(this, bytes, currentStyle!!, onFallback = { st ->
                    DiagLog.w("Export", "${st.label} 处理触发降级")
                    mainHandler.post { toast(fallbackMsg(st)) }
                })
            }
            // 亮度+曝光+高光+阴影+黑色阶组合烘焙（滤镜之后、徽章之前；单次 decode/encode，EXIF 回填）
            // P2：16-bit 重放成功时七档已烘焙，跳过 8-bit 二次应用。
            // P3：柔光条件并入（glow 可单独成立；16-bit 成功时柔光已随重放烘焙）。
            if ((toneRequested || glL != 0) && rawToned == null) {
                val tL = System.currentTimeMillis()
                out = Shadows.applyStackToJpeg(out, brL, evL, hlL, shL, blL, saL, coL, glL)
                DiagLog.i("Export", "亮度 ${brL}/曝光 ${evL}/高光 ${hlL}/阴影 ${shL}/黑色阶 ${blL}/饱和度 ${saL}/对比度 ${coL}/柔光 ${glL} 已烘焙 耗时 ${System.currentTimeMillis() - tL}ms")
            }
            DiagLog.i("Export", "${styleId} 处理完成 ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
            // C3 增益图重标定：套过色调后 SDR 亮度分布已变，旧增益图的分母（原 SDR）失配
            // → 用「原 SDR + 新 SDR」重算 g' = L_hdr / L_sdr_new，失败则沿用旧增益图
            val hdrForExport = if (pendingHdr != null) {
                HdrGainMap.rebakeJpeg(bytes, out, pendingHdr!!) ?: pendingHdr
            } else null
            mainHandler.post {
                progress.gone()
                if (gen != processingGeneration) return@post
                if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                    requestWritePermission { export() }
                    return@post
                }
                val badgeOn = SettingsStore.leicaBadgeEnabled(this)
                val marked = LeicaMark.mark(out, badgeOn)
                if (marked === out) DiagLog.w("Export",
                    if (badgeOn) "徽章写入失败（返回原字节）" else "徽章关闭：源图无徽章元数据（原样返回）")
                // HDR 回嵌：徽章之后装配（徽章链会移除 XMP/MPF，不能放它前面）；
                // 未回嵌时裁掉 EOI 之后的残留二次图，避免「主图已缩放、增益图仍是原尺寸」的错配
                val hdrSrc = hdrForExport
                val finalBytes = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                    else HdrGainMap.truncateToPrimary(marked)
                // 装配结果自检：报告最终字节里的增益图/容器结构（detail 已进 DiagLog）
                val hdrChk = HdrGainMap.verify(finalBytes)
                val diag = MarkDiagnostic.describe(marked) + " " + hdrChk.brief
                val uri = saveNamed(finalBytes, "Leica_${nameTag}_${System.currentTimeMillis()}.jpg")
                DiagLog.i("Export", "保存${if (uri != null) "成功" else "失败"} · $diag")
                toast(
                    when {
                        uri == null -> {
                            fullResFallbackOnce = false
                            "导出失败，请重试（详情见日志）"
                        }
                        // M6 全像素条带失败后的自动回落：必须明确告知本次不是全像素
                        fullResFallbackOnce -> {
                            fullResFallbackOnce = false
                            val hdrTail = if (hdrSrc != null && !hdrChk.ok)
                                " · HDR 数据未完整保留" else ""
                            "已保存到相册（${workWidth}×${workHeight}）· 全像素处理失败，已按工作版导出$hdrTail"
                        }
                        hdrSrc != null && !hdrChk.ok -> "已保存到相册 · HDR 数据未完整保留"
                        // 200MP 源选了滤镜：本次按引擎工作字节导出，提示全像素入口
                        fullResFile != null ->
                            "已保存到相册（${workWidth}×${workHeight}）· 原图档可全像素导出"
                        else -> "已保存到相册"
                    }
                )
            }
        }
    }

    /**
     * M9 / M9D 高分辨率导出（B 方案，2026-10-21 用户确认）。
     *
     * 链路：200MP/50MP 源文件 → [ImageUtils.decodeWorkForLongEdge] 按 24MP 目标解码
     *      （setTargetSize 直接解到目标尺寸，峰值=目标位图，不做「先全解 200MP 再缩」）→
     *      小 JPEG → [M9Distill.run]（longEdge=[ImageUtils.HIRES_M9_LONG]）→
     *      五滑杆烘焙 → HDR 重标定 → 徽章/HDR 装配 → 保存。
     *
     * 与条带链（[DistillBandOps]）的本质差异：整图链的暖调是**全图一次调用**，
     * 结构上不存在「逐带归一化 → 带边收敛」，用户报的中间暗条/周期横纹/带边界过冲
     * 因此根本不会出现；也不再需要任何出口补偿算子。代价是画布受堆上限约束（24MP 级）。
     *
     * 失败（预算不足 / 超时 / native 异常 / OOM）→ 置 [skipFullResOnce] 重入 [export]，
     * 自动回落工作版原链路（12.5MP），绝不崩溃、绝不产出半成品。
     *
     * 收尾段（滑杆/HDR/徽章/保存/toast）与 [export] 的工作版尾部同口径——
     * 任一侧改动必须同步另一侧。
     */
    private fun exportDistillHiRes(srcFile: File, srcBytes: ByteArray,
                                   nameTag: String, styleId: String) {
        progress.visible()
        val gen = ++processingGeneration
        executor.execute {
            val t0 = System.currentTimeMillis()
            val brL = brightLevel
            val evL = exposureLevel
            val hlL = highlightLevel
            val shL = shadowLevel
            val blL = blackLevel
            val saL = satLevel
            val coL = contrastLevel
            val glL = glowLevel

            // 单档执行：按目标长边解码 → 小 JPEG（回填源 EXIF）→ 整图链。
            // toJpeg 从 Bitmap 重编码**不写 EXIF**；而 M9Distill 内部的 copyExif 与
            // LeicaMark.mark（在已有 APP1 段上追加 theme/legend/mode）都依赖输入带 EXIF。
            // 不回填 → 导出丢 Make/Model/拍摄参数与徕卡徽章（2026-10-21 真机实测：
            // MarkDiagnostic 显示 Make=/Model=/legend= 全空，FFE4 却在——段不依赖 EXIF）。
            var hiSize = ""
            fun tryHi(edge: Int): ByteArray? {
                val hi = ImageUtils.decodeWorkForLongEdge(
                    this, Uri.fromFile(srcFile), edge) ?: return null
                hiSize = "${hi.width}×${hi.height}"
                val jpeg = ImageUtils.toJpeg(hi, 95)
                val wOri = ImageUtils.resolveWorkOrientation(srcBytes, hi.width, hi.height)
                val jpegExif = ExifTransplant.copy(this, srcBytes, jpeg, wOri)
                hi.recycle()
                DiagLog.i("FullRes", "M9 高分辨率导出 · ${edge}档 画布 $hiSize 源JPEG ${jpegExif.size}B")
                return M9Distill.run(this, jpegExif, longEdge = edge)
            }
            // 超高分辨率档（8192，实验）需设置里手动开启；失败回落 24MP 档，再失败回落工作版。
            val xl = SettingsStore.m9HiResEnabled(this) && ImageUtils.lowEndReason(this) == null
            var usedEdge = if (xl) ImageUtils.HIRES_M9_LONG_XL else ImageUtils.HIRES_M9_LONG
            var out = tryHi(usedEdge)
            if (out == null && xl) {
                DiagLog.w("FullRes", "M9 超高分辨率档失败(${M9Distill.lastFail}) → 回落 24MP 档")
                usedEdge = ImageUtils.HIRES_M9_LONG
                out = tryHi(usedEdge)
            }
            DiagLog.i("FullRes", "M9 高分辨率导出 · ${usedEdge}档" +
                    (if (out != null) "成功 ${out.size}B" else "失败(${M9Distill.lastFail})") +
                    " 耗时 ${System.currentTimeMillis() - t0}ms")
            if (out == null) {
                // 兜底：回落工作版原链路（12.5MP）。skipFullResOnce 让这次 export() 跳过全像素分支。
                mainHandler.post {
                    progress.gone()
                    if (gen != processingGeneration) return@post
                    DiagLog.w("FullRes", "M9 高分辨率导出不可用 → 回落工作版原链路")
                    skipFullResOnce = true
                    export()
                }
                return@execute
            }
            // 3) 八滑杆（与工作版口径一致：滤镜之后、徽章之前）
            if (brL != 0 || evL != 0 || hlL != 0 || shL != 0 || blL != 0 || saL != 0 || coL != 0 || glL != 0) {
                val tL = System.currentTimeMillis()
                out = Shadows.applyStackToJpeg(out, brL, evL, hlL, shL, blL, saL, coL, glL)
                DiagLog.i("Export", "亮度 ${brL}/曝光 ${evL}/高光 ${hlL}/阴影 ${shL}/黑色阶 ${blL}/饱和度 ${saL}/对比度 ${coL}/柔光 ${glL} 已烘焙 耗时 ${System.currentTimeMillis() - tL}ms")
            }
            // 4) HDR 增益图重标定。
            // 增益图来源：全像素直通场景存在 fullResHdr（导入期 `pendingHdr` 只在 jpegDirect
            // 时赋值，200MP/50MP 直通一律为 null——曾导致 HiRes 导出静默丢 HDR，2026-10-21 修）。
            // 套了滤镜必须 rebake：增益图的分母是「源 SDR」，成品 SDR 亮度分布已变；
            // rebakeJpeg 内部把新旧 SDR 都缩到增益图尺寸，与输出主图尺寸无关（几何安全）。
            val outFinal = out
            val hdrData = fullResHdr ?: pendingHdr
            val hdrForExport = if (hdrData != null) {
                HdrGainMap.rebakeJpeg(srcBytes, outFinal, hdrData) ?: hdrData
            } else null
            mainHandler.post {
                progress.gone()
                if (gen != processingGeneration) return@post
                if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                    // 老设备写权限兜底：授权后回落工作版重跑（避免 HiRes 再失败一次）
                    requestWritePermission { skipFullResOnce = true; export() }
                    return@post
                }
                val badgeOn = SettingsStore.leicaBadgeEnabled(this)
                val marked = LeicaMark.mark(outFinal, badgeOn)
                if (marked === outFinal) DiagLog.w("Export",
                    if (badgeOn) "徽章写入失败（返回原字节）" else "徽章关闭：源图无徽章元数据（原样返回）")
                val hdrSrc = hdrForExport
                val finalBytes = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                    else HdrGainMap.truncateToPrimary(marked)
                val hdrChk = HdrGainMap.verify(finalBytes)
                val diag = MarkDiagnostic.describe(marked) + " " + hdrChk.brief
                val uri = saveNamed(finalBytes, "Leica_${nameTag}_${System.currentTimeMillis()}.jpg")
                DiagLog.i("Export", "保存${if (uri != null) "成功" else "失败"} · $diag")
                toast(
                    when {
                        uri == null -> "导出失败，请重试（详情见日志）"
                        hdrSrc != null && !hdrChk.ok -> "已保存到相册 · HDR 数据未完整保留"
                        else -> "已保存到相册（高分辨率 $hiSize）"
                    }
                )
            }
        }
    }

    /**
     * C1：200MP 全像素「原图直通」导出（**全链路文件化**）。
     *
     * 链路：cache 源文件 → 条带解码/编码（常驻一条带）→ EXIF 原地回填 → 相机徽章
     *      （只重写容器头）→ UltraHDR 文件级回嵌（头改写 + 尾部流式搬运）→ 流式入库。
     *
     * 内存：全程不驻留整图；Java 堆里只有容器头（几百 KB）、增益图与 256KiB 拷贝缓冲。
     *      200MP 的 50~80MB 源字节以 cache 文件形式存在磁盘，不参与堆分配。
     *
     * @param op 甲：逐带套色算子（LUT 家族）。null = 原图直通；非 null 时走 strict，
     *           失败宁可报错也不产出「尺寸对但没套色」的图。
     */
    private fun exportFullRes(
        srcFile: File,
        op: ImageUtils.StripBandOp? = null,
        nameTag: String = "orig",
        styleId: String = "orig",
        confirmed: Boolean = false,
        onFailure: (() -> Unit)? = null
    ) {
        val w0 = fullResWidth
        val h0 = fullResHeight
        val px = w0.toLong() * h0
        // 2 亿像素级（≥1 亿像素）：条带要跑数分钟，先讲清耗时与后台限制再开工
        val big = px >= BIG_EXPORT_PIXELS
        if (big && !confirmed) {
            confirmBigExport(px, w0, h0, op != null) {
                exportFullRes(
                    srcFile, op, nameTag, styleId,
                    confirmed = true, onFailure = onFailure
                )
            }
            return
        }
        progress.visible()
        if (big) {
            exporting = true
            startExportForeground()
        }
        val gen = ++processingGeneration
        val hdrSrc = fullResHdr
        val exifHead = fullResExifHead
        val w = fullResWidth
        val h = fullResHeight
        executor.execute {
            val t0 = System.currentTimeMillis()
            var succeeded = false
            var exportSummary = ""
            if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                exporting = false
                ExportProgress.end(false, "缺少存储权限")
                mainHandler.post { progress.gone(); requestWritePermission { export() } }
                return@execute
            }
            val tmp = File(cacheDir, "leica_fullres_${System.currentTimeMillis()}.jpg")
            try {
                DiagLog.i(
                    "FullRes",
                    "全像素导出开始 ${w}x$h 源 ${srcFile.length()}B op=${op?.javaClass?.simpleName ?: "none"} " +
                        "style=$styleId 堆 ${ImageUtils.heapUsageLabel()}"
                )
                val res = ImageUtils.encodeStripFromFile(
                    srcFile, tmp,
                    quality = 95, maxBandRows = 512,
                    onProgress = { done, total -> ExportProgress.update(done, total) },
                    op = op, strict = op != null
                )
                DiagLog.i("FullRes",
                    "条带编码 ok=${res.ok} fallback=${res.fallbackCopy} " +
                        "${res.width}x${res.height} band=${res.bandRows}×${res.bandCount} " +
                        "${res.outBytes}B 耗时 ${res.elapsedMs}ms 堆 ${ImageUtils.heapUsageLabel()}")
                if (!res.ok && !res.fallbackCopy) {
                    DiagLog.e("FullRes", "条带编码失败（strict，未写回落副本）")
                    // onFailure 会重入 export()。exporting 要到本 runnable 的 finally（后台线程）
                    // 才清零，主线程先跑 post 的话 export() 入口闸门会把它当「导出进行中」吞掉。
                    // 这里先解除（finally 再写一次无害），且写在 post 之前，借 post 的
                    // happens-before 保证主线程可见。权限分支已有同款先例。
                    exporting = false
                    mainHandler.post {
                        progress.gone()
                        if (gen != processingGeneration || isFinishing || isDestroyed) return@post
                        if (onFailure != null) onFailure()
                        else toast(
                            if (op != null) "全像素滤镜导出失败，请重试（详情见日志）"
                            else "全像素导出失败，请重试（详情见日志）"
                        )
                    }
                    return@execute
                }
                // 全像素直通只接受 Orientation=1（见 ImageUtils.decideFullRes），故固定写 1
                val exifOk = exifHead != null && ExifTransplant.copyInPlace(this, exifHead, tmp, "1")
                DiagLog.i("FullRes", "EXIF 回填${if (exifOk) "完成" else "跳过"} 堆 ${ImageUtils.heapUsageLabel()}")
                // §22 P1：源图 ICC 色彩档案随导出走（纯字节搬运，不改像素；读失败静默跳过）
                val iccBlocks = IccProfile.readBlocks(srcFile)
                val badgeOn = SettingsStore.leicaBadgeEnabled(this)
                val badgeOk = LeicaMark.markFile(tmp, iccBlocks, badgeOn)
                val badgeMsg = if (badgeOn) "徽章写入${if (badgeOk) "完成" else "失败（保留原样）"}"
                else "徽章关闭（剥离徽章元数据）${if (badgeOk) "完成" else "失败（保留原样）"}"
                DiagLog.i("FullRes",
                    "$badgeMsg · " +
                        "ICC ${if (iccBlocks != null) "${iccBlocks.size}B" else "源图无"} " +
                        "堆 ${ImageUtils.heapUsageLabel()}")
                // HDR 回嵌：徽章链会移除 XMP/MPF，必须在其后；无增益图时裁掉残留二次图防错配
                // C3：全像素源若自带增益图且本次套了色（op != null），增益图按新 SDR 重标定
                val hdrForExport = if (hdrSrc != null && op != null) {
                    HdrGainMap.rebakeFiles(srcFile, tmp, hdrSrc, w, h) ?: hdrSrc
                } else hdrSrc
                val hdrChk = if (hdrForExport != null) HdrGainMap.embedFile(tmp, hdrForExport)
                    else HdrGainMap.truncateToPrimaryFile(tmp)
                val diag = fileHeaderDiag(tmp) + " " + hdrChk.brief
                val name = "Leica_${nameTag}_full_${System.currentTimeMillis()}.jpg"
                val uri = saveNamedFile(tmp, name)
                val elapsed = System.currentTimeMillis() - t0
                DiagLog.i("FullRes",
                    "保存${if (uri != null) "成功" else "失败"} ${tmp.length()}B " +
                        "总耗时 ${elapsed}ms 堆 ${ImageUtils.heapUsageLabel()} · $diag")
                succeeded = uri != null
                exportSummary = "${w}×${h} · 用时 ${fmtDuration(elapsed)}"
                mainHandler.post {
                    progress.gone()
                    if (gen != processingGeneration || isFinishing || isDestroyed) return@post
                    // 柔光是空间算子不进条带链（半径 > 重叠带必带边不连续），全像素条带导出时明示
                    val glowNote = if (op != null && glowLevel != 0) " · 柔光未参与" else ""
                    val tail = (if (op != null) "· ${nameTag} 已套色" else "") + glowNote
                    val hdrShort = hdrForExport != null && !hdrChk.ok
                    when {
                        uri == null -> toast("全像素导出失败，请重试（详情见日志）")
                        big -> showBigExportDoneDialog(w, h, elapsed, hdrShort, tail)
                        hdrShort -> toast("已保存到相册（${w}×$h）· HDR 数据未完整保留$tail")
                        else -> toast("已保存到相册（${w}×$h）$tail")
                    }
                }
            } catch (t: Throwable) {
                DiagLog.e("FullRes", "全像素导出异常：${t.message}", t)
                // 同上：onFailure 回落要重入 export()，必须先解除 exporting 保护
                exporting = false
                mainHandler.post {
                    progress.gone()
                    if (gen == processingGeneration && !isFinishing && !isDestroyed) {
                        if (onFailure != null) onFailure()
                        else toast("全像素导出失败：${t.javaClass.simpleName}")
                    }
                }
            } finally {
                runCatching { tmp.delete() }
                ExportProgress.end(succeeded, exportSummary)
                exporting = false
            }
        }
    }

    // ---- 大图（≥1 亿像素）全像素导出：开工确认 / 用时弹窗 ----

    /** 开工前确认：耗时与「后台无限制」都要讲清楚，确认后才真正起前台服务 */
    private fun confirmBigExport(px: Long, w: Int, h: Int, withColor: Boolean, onStart: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("全像素导出")
            .setMessage(buildBigExportMessage(px, w, h, withColor))
            .setPositiveButton("开始导出") { _, _ -> onStart() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 确认文案：首行红字耗时 + 末段红字「后台无限制」指引 */
    private fun buildBigExportMessage(px: Long, w: Int, h: Int, withColor: Boolean): CharSequence {
        val red = "预计 ${estimateBandExportText(px, withColor)}，期间手机会持续发热\n"
        val rest = "全像素导出 ${w}×$h（${fmtPixels(px)}）：逐条带重编码，内存占用很低，但需要时间。\n" +
            "已挂前台服务并从通知栏显示进度，可以退回桌面等待；请勿在多任务里划掉本应用。\n" +
            "锁屏会降低系统调度、明显变慢，建议保持亮屏或直接切到桌面。\n\n"
        val warn = "若系统限制后台，长任务可能被冻结或中断 —— 请到 系统设置 → 应用管理 → L-Moment → 省电策略，改为「无限制」。"
        val ss = android.text.SpannableString(red + rest + warn)
        ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val warnStart = (red + rest).length
        ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED),
            warnStart, ss.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    /** 完成弹窗：数分钟的等待必须被明确交代，而不是一条会自己消失的 toast */
    private fun showBigExportDoneDialog(w: Int, h: Int, elapsedMs: Long, hdrShort: Boolean, tail: String) {
        if (isFinishing || isDestroyed) return
        val red = "用时 ${fmtDuration(elapsedMs)}\n"
        val rest = "已保存全像素成片 ${w}×$h 到相册$tail。\n" +
            (if (hdrShort) "本次 HDR 增益图未完整保留（成片可用）。\n" else "") + "\n"
        val warn = "若等待期间系统提示过「应用在后台耗电」或任务被打断过，请把 L-Moment 的后台权限设为「无限制」，下次导出更稳。"
        val ss = android.text.SpannableString(red + rest + warn)
        ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val warnStart = (red + rest).length
        ss.setSpan(android.text.style.ForegroundColorSpan(Color.RED),
            warnStart, ss.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("全像素导出完成")
            .setMessage(ss)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 条带耗时估算：200MP 直通实测约 256s（≈1.28 s/MP），套色算子再乘 1.6，给区间更诚实 */
    private fun estimateBandExportText(px: Long, withColor: Boolean): String {
        var sec = (px / 1_000_000.0 * 1.28).toInt()
        if (withColor) sec = (sec * 1.6).toInt()
        if (sec < 30) sec = 30
        val lo = (sec * 0.8 / 60.0).toInt().coerceAtLeast(1)
        val hi = (sec * 1.6 / 60.0).toInt().coerceAtLeast(lo + 1)
        return "约 $lo~$hi 分钟"
    }

    private fun fmtPixels(px: Long): String {
        if (px >= 100_000_000L) {
            val yi = (px / 10_000_000L) / 10.0
            return "$yi 亿像素"
        }
        return "${px / 10_000} 万像素"
    }

    private fun fmtDuration(ms: Long): String {
        val total = (ms / 1000).toInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return if (m > 0) "$m 分 $s 秒" else "$s 秒"
    }

    /** 大图导出起前台服务（+通知权限）：切后台/息屏后条带编码继续跑 */
    private fun startExportForeground() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        ExportProgress.begin()
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SuperResService::class.java).setAction(SuperResService.ACTION_EXPORT)
            )
        } catch (t: Throwable) {
            DiagLog.w("FullRes", "前台服务启动失败（转无保护导出）：${t.message}")
        }
    }

    /** 容器头诊断（只读 SOI..SOS 段，不碰熵编码数据） */
    private fun fileHeaderDiag(file: File): String = try {
        val head = ImageUtils.readFileHeader(file)
        if (head == null) "头=不可解析" else MarkDiagnostic.describe(head)
    } catch (t: Throwable) {
        "头=解析异常"
    }

    /** 把源字节落到 cache 文件（200MP 源不进 Java 堆常驻） */
    private fun writeCacheBytes(bytes: ByteArray, prefix: String): File? = try {
        val f = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { it.write(bytes) }
        f
    } catch (t: Throwable) {
        DiagLog.w("FullRes", "写 cache 源失败：${t.message}")
        null
    }

    /** 作废 200MP 全像素直通源（删 cache 文件 + 清字段） */
    private fun clearFullRes() {
        runCatching { fullResFile?.delete() }
        fullResFile = null
        fullResExifHead = null
        fullResWidth = 0
        fullResHeight = 0
        fullResHdr = null
    }

    /** 文件名片段净化：去掉文件系统不接受的分隔符，空白压成下划线 */
    private fun sanitizeFileTag(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { "custom" }
    }

    /** 完美成片导出：档位烘焙（如需要）→ 相机徽章 → HDR 回嵌 → 入库（命名带 _perfect）。 */
    private fun exportPerfect() {
        val src = perfectBytes ?: return
        progress.visible()
        val gen = ++processingGeneration
        val styleId = perfectStyle?.id ?: "M9"
        executor.execute {
            val t0 = System.currentTimeMillis()
            val brL = brightLevel
            val evL = exposureLevel
            val hlL = highlightLevel
            val shL = shadowLevel
            val blL = blackLevel
            val saL = satLevel
            val coL = contrastLevel
            val glL = glowLevel
            var out = src
            if (brL != 0 || evL != 0 || hlL != 0 || shL != 0 || blL != 0 || saL != 0 || coL != 0 || glL != 0) {
                out = Shadows.applyStackToJpeg(out, brL, evL, hlL, shL, blL, saL, coL, glL)
            }
            DiagLog.i("Export", "完美成片导出 $styleId ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
            // C3：完美档同样是「套了色调的 SDR」，增益图需按新 SDR 重标定
            val hdrForExport = if (pendingHdr != null && originalBytes != null) {
                HdrGainMap.rebakeJpeg(originalBytes!!, out, pendingHdr!!) ?: pendingHdr
            } else null
            mainHandler.post {
                progress.gone()
                if (gen != processingGeneration) return@post
                if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                    requestWritePermission { exportPerfect() }
                    return@post
                }
                val marked = LeicaMark.mark(out, SettingsStore.leicaBadgeEnabled(this))
                // HDR 回嵌：徽章之后装配（徽章链会移除 XMP/MPF）；未回嵌则裁掉残留增益图尾数据
                val hdrSrc = hdrForExport
                val finalBytes = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                    else HdrGainMap.truncateToPrimary(marked)
                val hdrChk = HdrGainMap.verify(finalBytes)
                val uri = saveNamed(finalBytes,
                    "Leica_${styleId}_perfect_${System.currentTimeMillis()}.jpg")
                DiagLog.i("Export", "完美成片保存${if (uri != null) "成功" else "失败"} · ${hdrChk.brief}")
                toast(
                    when {
                        uri == null -> "导出失败，请重试（详情见日志）"
                        hdrSrc != null && !hdrChk.ok -> "完美成片已保存 · HDR 数据未完整保留"
                        else -> "完美成片已保存"
                    }
                )
            }
        }
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestWritePermission(onGranted: () -> Unit) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            1001
        )
        // 授权结果在 onRequestPermissionsResult 里处理
        permissionRetry = onGranted
    }

    private var permissionRetry: (() -> Unit)? = null

    /**
     * 导入前补齐「媒体位置」权限（Android 10+）：系统按它决定返回的图带不带 EXIF GPS。
     * 只请求一次；拒绝/永久拒绝/低版本都直接继续导入，仅导出无定位，不阻塞主流程。
     */
    private fun requestMediaLocationThen(go: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            go()
            return
        }
        locationRetry = go
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION),
            1003
        )
    }

    private var locationRetry: (() -> Unit)? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) permissionRetry?.invoke() else toast("未授予存储权限，无法导出")
            permissionRetry = null
        }
        if (requestCode == 1003) {
            // 授权 → 本次及以后读到带 GPS 原件；拒绝 → 照常导入，导出无定位
            locationRetry?.invoke()
            locationRetry = null
        }
    }

    private fun saveToGallery(bytes: ByteArray, style: LeicaEngine.Style): Uri? {
        return saveNamed(bytes, "Leica_${style.id}_${System.currentTimeMillis()}.jpg")
    }

    private fun saveNamed(bytes: ByteArray, name: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaMoments")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { os -> os.write(bytes) }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
            uri
        } catch (e: Exception) {
            null
        }
    }

    // ---- 工具 ----

    /** 文件版入库：流式拷贝，不把整幅字节读进堆（200MP 直通导出用）。 */
    private fun saveNamedFile(file: File, name: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaMoments")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
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
            uri
        } catch (e: Exception) {
            DiagLog.w("FullRes", "入库异常：${e.message}")
            null
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val scale = maxDim.toFloat() / maxOf(w, h)
        if (scale >= 1f) return src
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fallbackMsg(style: LeicaEngine.Style): String =
        if (style == LeicaEngine.Style.LM9) "「${style.label}」模型不可用，已切换为 M9"
        else if (LeicaEngine.Style.isLutOnly(style)) "「${style.label}」LUT 加载失败，已输出原图"
        else if (LeicaEngine.Style.isCcd(style)) "「${style.label}」模型不可用，已降级为 LUT 模拟（详情见日志）"
        else "「${style.label}」完整链路不可用，已降级为 LUT 模拟"

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 参考图门槛提示：硬拒绝与质量提醒都走这里。
     *
     * 不用 toast 是因为这些句子要讲清「哪一项不合格 + 该怎么换图」，
     * 长度超过 toast 的两行会被截断，用户只会看到半句话。
     */
    private fun showRefNotice(title: String, msg: String) {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 临时诊断弹窗：完整错误 + 复制按钮（M13 排查用） */
    private fun showDiagDialog(title: String, detail: String) {
        val tv = TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(8))
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("portra_error", detail))
                toast("已复制完整错误")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 诊断日志对话框：顶栏标题长按触发，可复制/清空（排障用） */
    private fun showLogDialog() {
        val tv = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("诊断日志（仅内存，不上传）")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("lmoment_log", DiagLog.snapshot()))
                toast("已复制全部日志")
            }
            .setNeutralButton("清空") { _, _ ->
                DiagLog.clear()
                tv.text = DiagLog.snapshot()
            }
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            tv.text = DiagLog.snapshot()
            // 打开即定位到最新日志（底部）
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.75f).toInt()
        )
    }

    private fun View.visible() {
        visibility = View.VISIBLE
    }

    private fun View.gone() {
        visibility = View.GONE
    }
}

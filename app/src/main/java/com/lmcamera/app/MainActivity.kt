package com.lmcamera.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.codex.lmapp.AlexaLut
import com.codex.lmapp.ExportMark
import com.codex.lmapp.MadridLegendary
import com.codex.lmapp.StyleA
import com.codex.lmapp.StyleAFit
import com.lmcamera.app.AdaptiveColorNet.toMono
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), AiUiHost {

    private companion object {
        /** 增强预览注册的内存产物（不落盘），弹窗关闭时释放。 */
        const val MEM_PARAM_REF = "mem:enhance_par"
        const val MEM_LUT_REF = "mem:enhance_lut"

        /**
         * 全像素「长任务通道」下限：与 ImageUtils.FULLRES_MIN_PIXELS（45MP）同口径。
         *
         * 只要走了全像素直通（50MP / 200MP），一律「耗时确认 + 前台服务 + 百分比浮层
         * + 完成弹窗」。2026-10-29 用户拍板：50MP 不再静默处理——原门槛 1 亿像素时，
         * 50MP 只有普通转圈、无前台服务保护，切后台不保证继续。
         */
        const val BIG_EXPORT_PIXELS = 45_000_000L

        /**
         * 弹窗警示文字色：Material 暗色主题官方 error 红（#CF6679）。
         * 纯 Color.RED（#FF0000）在黑底上饱和度过高、过于刺眼，导出/超分/完美
         * 各确认与结论弹窗的首行警示与末段警告统一用此色。
         */
        const val WARN_TEXT_COLOR = 0xFFCF6679.toInt()

        /**
         * RAW 线性亮度基准的长边上限（V2 真 HDR 的分子）。
         *
         * 2026-10-28 由 1024 提到 2048，与增益图的 1/2 分辨率对齐 —— 两者必须同尺寸
         * （[HdrGainMap.synthFromLinear] 按 luma 尺寸缩 SDR 并校验几何，不一致会直接退回
         * V1 猜测式）。约 3.1MP / 6.3MB。
         */
        private const val RAW_LUMA_MAX_DIM = 2048

        /**
         * 单批导入上限。
         *
         * 不是系统限制（Photo Picker 允许更多），而是内存/磁盘预算：热数据 LRU 3 之外
         * 的图只留 uri，但 50MP/200MP 的直通源是 cache 文件（20~90MB/张），30 张已接近
         * 1~2GB，再多就该提示用户分两批了。
         */
        const val MAX_BATCH = 30
    }

    private lateinit var imgPreview: ZoomableImageView
    private lateinit var txtEmpty: TextView
    private lateinit var txtStyleHint: TextView
    /** 左下角胶囊行（调节/完美/HDR）：实测其宽度供右下角小字限宽，防三段长文案重叠 */
    private lateinit var rowBottomPills: LinearLayout
    /** 小字原始文案（未折行）：限宽变化（旋转/按钮显隐）时据此重算语义折行 */
    private var hintRaw: String = ""
    /** 内部回写折行文本时置位，防 TextWatcher 把折行结果误记为原始文案（无限循环） */
    private var hintInternalSet = false
    private lateinit var progress: ProgressBar
    // 大图（≥1 亿像素）导出浮层：环形进度 + 百分比，替代普通转圈（仅 exportFullRes 大图分支）
    private lateinit var bigExportOverlay: View
    private lateinit var bigExportRing: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var bigExportPercent: TextView
    private lateinit var bigExportHint: TextView
    private lateinit var filterContainer: LinearLayout
    private lateinit var btnImport: TextView
    private lateinit var btnExport: TextView
    private lateinit var btnSuperRes: TextView
    private lateinit var btnSuperResFast: TextView
    private lateinit var btnPerfect: TextView
    private lateinit var btnHdr: TextView
    private lateinit var btnAdjust: TextView
    private lateinit var btnCategory: View
    private lateinit var txtTitle: TextView
    private lateinit var imgTitleArrow: ImageView
    private lateinit var btnSettings: TextView
    private lateinit var txtCoach: TextView
    // 批量切换 UI（单张导入时全部 gone）
    private lateinit var btnPrev: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var txtBatchIndex: TextView
    // 批量导出进度浮层（底板复用大图导出浮层）
    private lateinit var batchOverlay: View
    private lateinit var batchRing: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var batchMain: TextView
    private lateinit var batchSub: TextView
    private lateinit var btnBatchStop: TextView
    private lateinit var filterScroll: HorizontalScrollView
    private var sourceUri: Uri? = null                 // 导入源（完美档 RAW 完整管线需重读 DNG）
    private var sourceIsRaw = false
    /**
     * 伪 RAW（JPG/HEIF 高位深工作域）开关快照。
     *
     * 导入时定死：开关只影响「之后导入的图」，导出途中去设置里改开关不会让当前图语义漂移。
     * 真正是否进 16-bit 域还要再看 [rawLin16Level]（构建失败时为 0 → 自动回落 8-bit 链）。
     */
    private var pseudoLinearAllowed = false

    /**
     * 当前图的伪线性缓冲是否来自 HEIF 直通解码（而非工作字节反编码）。
     *
     * true 时导出会用 [PseudoRawLinear.fromHeif] 重新解码源容器：HEIF 常为 10-bit，
     * 这是伪 RAW 唯一能拿到真实位深的路径；解码失败自动退回工作字节线性化。
     */
    private var pseudoLinearFromHeif = false
    private lateinit var adjPanel: LinearLayout
    // ---- 平板双栏右栏（FormFactor 档位驱动，控件搬家复用同一批实例）----
    private lateinit var tabletRail: LinearLayout
    private lateinit var tabletFilterScroll: ScrollView
    private lateinit var tabletCol0: LinearLayout
    private lateinit var tabletCol1: LinearLayout
    private lateinit var tabletCol2: LinearLayout
    /** 悬浮层舞台：包住预览区+右栏，adjPanel 运行时挂这里按档位换角（横屏右下/竖屏左下） */
    private lateinit var floatStage: FrameLayout
    private var currentForm: Form = Form.PHONE
    /** 调节面板开合状态按档位分记（默认收起，三档一致） */
    private val adjOpenState = mapOf(Form.PHONE to false, Form.TABLET_LAND to false, Form.TABLET_PORT to false).toMutableMap()
    // P3：七档调节滑杆（进度 0..20 ↔ 档位 -10..+10；拖动调档，双击标签复位到 0）
    private lateinit var sbBright: SeekBar
    private lateinit var sbLum: SeekBar
    private lateinit var sbHigh: SeekBar
    private lateinit var sbShadow: SeekBar
    private lateinit var sbBlack: SeekBar
    private lateinit var sbSat: SeekBar
    private lateinit var sbCon: SeekBar
    private lateinit var sbGlow: SeekBar
    private lateinit var sbTemp: SeekBar
    private lateinit var sbTint: SeekBar
    private lateinit var sbLutStr: SeekBar
    private lateinit var sbMono: SeekBar
    private lateinit var sbGrain: SeekBar
    private lateinit var sbVignette: SeekBar
    private lateinit var txtTempVal: TextView
    private lateinit var txtTintVal: TextView
    private lateinit var txtLutStrVal: TextView
    private lateinit var txtMonoVal: TextView
    private lateinit var txtGrainVal: TextView
    private lateinit var txtVignetteVal: TextView
    private lateinit var txtBrightVal: TextView
    private lateinit var txtLumVal: TextView
    private lateinit var txtHighVal: TextView
    private lateinit var txtShadowVal: TextView
    private lateinit var txtBlackVal: TextView
    private lateinit var txtSatVal: TextView
    private lateinit var txtConVal: TextView
    private lateinit var txtGlowVal: TextView

    /** 调节面板打开时的全屏透明遮罩：点击面板外任意区域 = 收起面板 */
    private var adjScrim: View? = null

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

    // V2：RAW 真 HDR 的分子——**未映射的原始线性亮度**低分辨率基准（1024 长边，约 1.5MB）。
    // 与 rawLin16 同源但用途不同：rawLin16 是曲线映射后的调档预览基准；
    // 本字段保持原始线性值，导出末尾与最终 SDR 相除得到真实增益 g = log2(L_lin/L_sdr)。
    private var rawLinearLuma: RawToneMap.Luma16? = null

    // P2：导入时的 DNG 内嵌预览字节（导出重放 16-bit 五档时分位匹配必须用同一份，观感才一致）
    private var rawPreviewJpeg: ByteArray? = null

    // 状态
    private var originalBytes: ByteArray? = null      // 工作 JPEG 字节（导出用）
    private var workWidth = 0                         // 工作字节真实像素尺寸（超分提示按它估算）
    private var workHeight = 0
    private var originalBitmap: Bitmap? = null        // 预览尺寸原图（对比用）
    private var resultBitmap: Bitmap? = null          // 当前处理结果
    private var currentStyle: ColorEngine.Style? = null // null = 原图
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
    // 第二批调节维度（2026-11-03）：色温/色调 ±10；黑白/颗粒/暗角/色彩强度 0..100
    private var wbTemp = 0
    private var wbTint = 0
    private var monoLevel = 0
    private var grainLevel = 0
    private var vignetteLevel = 0
    private var lutStrength = 100
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

    // ---- AI 调色助手（会话级，不落任何用户设置） ----
    private var aiSheet: AiSheetDialog? = null
    /** 参考图拟合出的临时色彩：只在本次编辑内有效，用户点保存才入库 */
    private var aiRefStyle: AdaptiveColorNet.Style? = null
    private var aiRefPreview: Bitmap? = null
    /** 结果胶囊文案（如「AI · 3 项」）：AI 调过参数后显示在入口卡上，关面板也不丢成果 */
    private var aiResultHint: String? = null
    /** 一键重置前的快照：5 秒内可撤销 */
    private var adjUndoSnapshot: JSONObject? = null
    /** 标题行撤销组的 5 秒隐藏任务 */
    private var undoHideRunnable: Runnable? = null
    // ---- 调节响应性（A：拖动低清代理 + D：节流） ----
    /** 任一滑杆正在拖动：拖动中走 1/3 低清代理，松手后全尺寸精修 */
    @Volatile private var sliderDragging = false
    private var lastFastAt = 0L
    private var fastPending = false
    private var fastProxyO: Bitmap? = null
    private var fastProxyOKey: Bitmap? = null
    private var fastProxyR: Bitmap? = null
    private var fastProxyRKey: Bitmap? = null
    /** AI 整体力度（10..100，默认 100=原样）：AI 抽屉滑杆写入，applyParams 按此打折 */
    var aiStrengthPct = 100
    // ---- AI 接管锁定：用户手调过的维度，AI 默认跳过（applyParams 内生效） ----
    private val dimUserTouched = BooleanArray(14)
    private var dimIndexById: Map<Int, Int> = emptyMap()
    /** 调节面板当前页（0=影调 1=色彩 2=质感），会话内记忆 */
    private var adjPageIdx = 0
    private val adjRowsTone = ArrayList<View>()
    private val adjRowsColor = ArrayList<View>()
    private val adjRowsTexture = ArrayList<View>()
    private var adjTabViews: List<TextView> = emptyList()
    /** 上滑手势的按下点是否落在色彩卡区域 */
    private var aiSwipeArmed = false
    private var aiSwipeStartY = 0f
    private var aiSwipeStartX = 0f
    private var aiSwipeTriggered = false
    /** 下滑手势（预览区下滑唤出分类列表）：与 AI 上滑共用同一套判向，方向互斥 */
    private var catSwipeArmed = false
    private var catSwipeTriggered = false

    /** 分享码自动识别：本次会话里已被用户点「取消」的指令，不再重复打扰 */
    private var declinedShareSig: String? = null
    /** 正在导入的指令：确认框关闭会立刻触发一次焦点回调，用它挡住重复弹框 */
    private var importingShareSig: String? = null
    private var sharePromptShowing = false

    // ---- 批量（多图）编辑会话 ----
    /**
     * 本批导入的图。单张导入时 size == 1，多选时 2..[MAX_BATCH]。
     * 只有 [batchIndex] 指向的那张是「装进当前字段」的活动图，其余只保 uri + 设置。
     */
    private val batchItems = mutableListOf<EditItem>()
    /** 当前活动项下标；-1 = 没有批次 */
    private var batchIndex = -1
    /** 切图代次：连点箭头会排队多次加载，落地时校验，防旧请求覆盖新图 */
    private var switchSeq = 0
    /** 批量导出进行中（比 exporting 更强的互斥：禁止切图/导入/超分/完美/调节） */
    private var batchExportRunning = false
    /** 批量导出被用户点「停止」：当前这张导完后收尾 */
    private var batchCancel = false
    /** 导出完成回调：非批量时为 null（空操作），单张导出路径零行为变化 */
    private var exportDoneHook: ((Boolean) -> Unit)? = null
    /** 本批「配方」= 用户最近一次手动设置；未单独调整的图切过去时自动套用（预览即导出结果） */
    private val batchRecipe = Recipe()
    /**
     * 程序性装载期间为 true（切图恢复 / 静默导出装载）。
     *
     * 这类路径也会走 onFilterSelected / 滑杆回写，但那**不是**用户调整：不能把图标记成
     * touched，否则每切一次就"污染"一张，继承语义全废。
     */
    private var applyingSession = false

    /**
     * 导入入口：系统相册多选（上限 [MAX_BATCH]）。
     *
     * Android 13+ 走系统 Photo Picker（原生多选）；低版本由 contract 自动回落
     * ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE，长按多选、单选照旧。
     * 只选 1 张时走原来的单图路径（清批次 + 普通导入）——行为与改前完全一致。
     */
    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)
    ) { uris ->
        when {
            uris.isNullOrEmpty() -> Unit
            uris.size == 1 -> {
                clearBatch()
                onImagePicked(uris[0])
            }
            else -> beginBatch(uris)
        }
    }

    // 增强（S4）：在游标版本的基础上追加参考图，先预览强度再落盘
    private val pickEnhanceImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val styleId = pendingEnhanceId
        pendingEnhanceId = null
        if (styleId != null && !uris.isNullOrEmpty()) enhanceFromRefs(styleId, uris)
    }
    // AI 面板参考图附件：系统相册多选（上限 [RefAttachmentCache.MAX_COUNT]，超出自动截断）
    private val pickAiRefs = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) ingestAiRefAttachments(uris)
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
        maybeShowDisclaimer()
        bindViews()
        setupCategoryEntry()
        btnImport.setOnClickListener {
            // 位置权限与导入解耦：拒绝也照常导入，只是导出无 GPS（沿用既有口径）
            requestMediaLocationThen {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        btnExport.setOnClickListener { onExportClicked() }
        // 批量切图：首尾循环（与相册左右滑动同语义，不给出"点不动"的死角）
        btnPrev.setOnClickListener { switchTo(stepBatchIndex(-1)) }
        btnNext.setOnClickListener { switchTo(stepBatchIndex(1)) }
        btnBatchStop.setOnClickListener {
            batchCancel = true
            batchSub.text = "正在停止 · 当前这张完成后结束"
        }
        // 批量导出中按返回：退到桌面继续跑（不能 finish —— onDestroy 会关掉导出线程池，
        // 这正是"切后台还在、一按返回就断"的根源）
        onBackPressedDispatcher.addCallback(this) {
            if (batchExportRunning) {
                toast("导出继续在后台进行，完成后会通知你")
                moveTaskToBack(true)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        // 长按对比：选了色彩、或只是调过参数（AI 常停在「原图」卡上只调八档）都应生效
        imgPreview.onCompareEnter = {
            if (hasColor() || hasAdjustments()) originalBitmap?.let { imgPreview.setImageBitmap(it) }
        }
        imgPreview.onCompareExit = {
            if (hasColor() || hasAdjustments()) refreshPreviewImage()
        }
        btnSuperRes.setOnClickListener { confirmSuperRes(false) }
        btnSuperResFast.setOnClickListener { confirmSuperRes(true) }
        btnPerfect.setOnClickListener { onPerfectClicked() }
        btnHdr.setOnClickListener { onHdrClicked() }
        setupSlider(sbBright, txtBrightVal, { brightLevel }, { applyBrightChange(it) })
        setupSlider(sbLum, txtLumVal, { exposureLevel }, { applyExposureChange(it) })
        setupSlider(sbHigh, txtHighVal, { highlightLevel }, { applyHighChange(it) })
        setupSlider(sbShadow, txtShadowVal, { shadowLevel }, { applyShadowChange(it) })
        setupSlider(sbBlack, txtBlackVal, { blackLevel }, { applyBlackChange(it) })
        setupSlider(sbSat, txtSatVal, { satLevel }, { applySatChange(it) })
        setupSlider(sbCon, txtConVal, { contrastLevel }, { applyContrastChange(it) })
        setupSlider(sbGlow, txtGlowVal, { glowLevel }, { applyGlowChange(it) })
        setupSlider(sbTemp, txtTempVal, { wbTemp }, { wbTemp = it; applyExtraChange() })
        setupSlider(sbTint, txtTintVal, { wbTint }, { wbTint = it; applyExtraChange() })
        setupPercentSlider(sbLutStr, txtLutStrVal, { lutStrength }, 100, { lutStrength = it; applyExtraChange() })
        setupPercentSlider(sbMono, txtMonoVal, { monoLevel }, 0, { monoLevel = it; applyExtraChange() })
        setupPercentSlider(sbGrain, txtGrainVal, { grainLevel }, 0, { grainLevel = it; applyExtraChange() })
        setupPercentSlider(sbVignette, txtVignetteVal, { vignetteLevel }, 0, { vignetteLevel = it; applyExtraChange() })
        setupAdjPanelChrome()
        btnAdjust.setOnClickListener { toggleAdjPanel() }
        SuperResTask.addListener(srListener)
        applyFormFactor()
        // 分享冷启动：仅首次创建处理（系统重建/配置变化不重复导入）
        if (savedInstanceState == null) handleSharedIntent(intent)
        if (savedInstanceState == null) applyDebugAutoImport()
        if (!SettingsStore.isCoachShown(this)) showCoach()
    }

    // ---- 平板/折叠形态适配（FormFactor 驱动，控件搬家复用，全程幂等）----

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFormFactor()
    }

    /** 档位应用：手机/回落档 = 现状布局；双栏 = 右栏可见（全高滤镜网格）+ 卡片搬三列 + 浮层换右下角 */
    private fun applyFormFactor() {
        if (!this::adjPanel.isInitialized || !this::tabletRail.isInitialized) return
        val form = FormFactor.current(this)
        val changed = form != currentForm
        currentForm = form
        val wDp = resources.configuration.screenWidthDp

        if (form == Form.TABLET_LAND) {
            tabletRail.visibility = View.VISIBLE
            (tabletRail.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(FormFactor.railWidthDp(wDp))
            }
            tabletRail.requestLayout()
            moveCardsToGrid()
            filterScroll.visibility = View.GONE
        } else {
            tabletRail.visibility = View.GONE
            moveCardsToBar()
            filterScroll.visibility = View.VISIBLE
        }
        // 调节浮层三档统一悬浮在舞台层，只换停靠角与宽度（272/320dp）
        positionAdjPanel()

        // 开合状态按档位恢复（三档默认收起）
        val open = adjOpenState.getOrDefault(form, false)
        adjPanel.visibility = if (open) View.VISIBLE else View.GONE
        adjScrim?.visibility = if (open) View.VISIBLE else View.GONE
        // 档位切换伴随窗口重排：清掉旧窗口下的双指缩放变换，onSizeChanged 会重建 fitCenter 基准
        if (changed) imgPreview.resetZoom()
    }

    /** 调节浮层停靠：手机/回落档 = 左下角（现状）；平板横屏 = 右下角（悬浮在滤镜网格上方） */
    private fun positionAdjPanel() {
        val lp = adjPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        if (currentForm == Form.TABLET_LAND) {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            lp.leftMargin = 0
            lp.rightMargin = dp(16)
            lp.width = dp(FormFactor.PHONE_PANEL_DP)
        } else {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            lp.leftMargin = dp(16)
            lp.rightMargin = 0
            lp.width = dp(FormFactor.panelWidthDp(currentForm))
        }
        adjPanel.layoutParams = lp
    }

    /** 卡片所在列：双栏三列按序交替（i%3），阅读顺序与横滑条一致（左1右2左3…） */
    private fun cardColumnFor(index: Int): LinearLayout = when (index % 3) {
        1 -> tabletCol1
        2 -> tabletCol2
        else -> tabletCol0
    }

    private fun thumbSizeDp(): Int = when (currentForm) {
        Form.TABLET_PORT -> 76
        Form.TABLET_LAND -> 84
        else -> 60
    }

    /** 双栏档：卡片搬进三列（幂等）；空列表或已在列中时跳过 */
    private fun moveCardsToGrid() {
        val first = cardViews.firstOrNull()?.parent ?: return
        if (first === tabletCol0 || first === tabletCol1 || first === tabletCol2) return
        cardViews.forEachIndexed { i, card ->
            (card.parent as? ViewGroup)?.removeView(card)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            cardColumnFor(i).addView(card, lp)
        }
    }

    /** 手机/回落档：卡片搬回横滑条（幂等），宽度按档位（72/96dp） */
    private fun moveCardsToBar() {
        if (cardViews.firstOrNull()?.parent === filterContainer) return
        val w = FormFactor.cardWidthDp(currentForm)
        cardViews.forEach { card ->
            (card.parent as? ViewGroup)?.removeView(card)
            filterContainer.addView(
                card,
                LinearLayout.LayoutParams(dp(w), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    /**
     * AI 上滑手势命中区（用户拍板 2026-11-11）：
     * - 手机/回落档：底部滤镜条（现状）；
     * - 平板横屏双栏：只认预览区——右栏滤镜列表的滑动是纯滚动浏览，永不触发 AI 面板。
     */
    private fun isInFilterBar(rawX: Float, rawY: Float): Boolean {
        if (!this::filterScroll.isInitialized) return false
        val target: View = if (currentForm == Form.TABLET_LAND) {
            if (!this::imgPreview.isInitialized) return false
            imgPreview.parent as? View ?: return false      // 预览区（横跨预览的 FrameLayout）
        } else {
            filterScroll
        }
        return isWithinOnScreen(target, rawX, rawY)
    }

    /** 分类下滑手势命中区：三档统一 = 预览区（用户拍板 A，2026-11-12） */
    private fun isInPreviewArea(rawX: Float, rawY: Float): Boolean {
        if (!this::imgPreview.isInitialized) return false
        val target = imgPreview.parent as? View ?: return false
        return isWithinOnScreen(target, rawX, rawY)
    }

    private fun isWithinOnScreen(target: View, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        return try {
            target.getLocationOnScreen(loc)
            val left = loc[0]
            val top = loc[1]
            rawX >= left && rawX <= left + target.width &&
                rawY >= top && rawY <= top + target.height
        } catch (t: Throwable) {
            false
        }
    }

    private fun openAiSheet() {
        if (aiSheet == null) aiSheet = AiSheetDialog(this)
        aiSheet?.show()
    }

    /** 更新到本版本后第一次载入图片时弹一次引导（生命周期内只弹一次）。 */
    private fun maybeShowAiOnboarding() {
        if (AiProviderStore.onboardingShown(this)) return
        AiProviderStore.setOnboardingShown(this)
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            AiOnboardingDialog.show(this) { openAiSheet() }
        }, 600)
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
            val bmp = decodePreview(bytes, previewMaxDim()) ?: run {
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
    /**
     * 全局触摸旁听：①新手引导点一下消失；②色彩卡区域上滑打开 AI 抽屉；③预览区下滑唤出分类列表。
     *
     * 手势在 Activity 层旁听，而不是给 filterScroll 挂 OnTouchListener——卡片子 View
     * 会消费自己的点击/横滑，父容器监听收不到。这里**只看不消费**（始终交给
     * super.dispatchTouchEvent），因此对既有横滑浏览、点按选色零影响。
     * 上滑=AI、下滑=分类，方向天然互斥，同一次按下先到阈值者胜出。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (coachVisible && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            hideCoach()
            return true
        }
        trackVerticalGestures(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 竖向手势判定（共享一套按下原点，方向互斥）：
     * - 上滑 → AI 抽屉：按下点在色彩卡区（手机/回落档=底部滤镜条，双栏档=预览区）；
     * - 下滑 → 分类列表：按下点在预览区（三档统一，用户拍板 A）且**图片未放大**——
     *   放大态下单指拖动是平移图片，不能兼作手势（避免拖图同时弹出列表）。
     */
    private fun trackVerticalGestures(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                aiSwipeStartX = ev.rawX
                aiSwipeStartY = ev.rawY
                aiSwipeTriggered = false
                catSwipeTriggered = false
                aiSwipeArmed = isInFilterBar(ev.rawX, ev.rawY)
                // 双栏档两个手势都落在预览区，靠上下滑方向互斥，不做 !aiSwipeArmed 排除
                catSwipeArmed = isInPreviewArea(ev.rawX, ev.rawY) &&
                    this::imgPreview.isInitialized && imgPreview.isAtFitScale
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - aiSwipeStartY
                val dx = ev.rawX - aiSwipeStartX
                val vertical = kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.4f
                if (aiSwipeArmed && !aiSwipeTriggered && dy < -dp(80) && vertical) {
                    aiSwipeTriggered = true
                }
                if (catSwipeArmed && !catSwipeTriggered && !aiSwipeTriggered &&
                    dy > dp(80) && vertical
                ) {
                    catSwipeTriggered = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (aiSwipeArmed && aiSwipeTriggered) openAiSheet()
                if (catSwipeArmed && catSwipeTriggered) showCategoryPicker()
                aiSwipeArmed = false
                aiSwipeTriggered = false
                catSwipeArmed = false
                catSwipeTriggered = false
            }
            MotionEvent.ACTION_CANCEL -> {
                aiSwipeArmed = false
                aiSwipeTriggered = false
                catSwipeArmed = false
                catSwipeTriggered = false
            }
        }
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
        mainHandler.removeCallbacks(exportOverlayTick)
        SuperResTask.removeListener(srListener)
        // Vulkan 设备与已加载的 ncnn 权重是进程级常驻资源，页面销毁时主动还回去
        GpuNet.release()
        aiSheet?.release()               // AI 在途请求随页面销毁一起停止
        // 全像素导出线程随 Activity 线程池关闭：让前台服务拿到结局通知并收尾（免得常驻 30 分钟）
        if (exporting) ExportProgress.end(false, "已退出应用，导出中断")
        // 批量：进程真要走了也要给通知栏一个结论，不能一直挂在"进行中"
        if (batchExportRunning) ExportProgress.end(false, "应用被关闭，批量导出已中断")
        // 停掉看门狗与推进链，避免销毁后仍有 postDelayed 回调
        batchExportRunning = false
        batchCancel = true
        cancelBatchWatchdog()
        executor.shutdownNow()
        // 不在 onDestroy 回收位图：可能仍被 ImageView 持有，销毁到 detach 间的一帧绘制
        // 会触发 "Canvas: trying to use a recycled bitmap" 崩溃；交给 GC 兜底（预览尺寸无收益）
        // 200MP 直通源是 cache 文件，进程退出不再需要，主动删掉不占用户存储
        runCatching { fullResFile?.delete() }
        // 批量：逐张的直通源与超分成片同样是 cache，一并清掉（30 张可能上 GB）
        batchItems.forEach { it.releaseDisk() }
        batchItems.clear()
    }

    private fun bindViews() {
        imgPreview = findViewById(R.id.imgPreview)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtStyleHint = findViewById(R.id.txtStyleHint)
        progress = findViewById(R.id.progress)
        bigExportOverlay = findViewById(R.id.bigExportOverlay)
        bigExportRing = findViewById(R.id.bigExportRing)
        bigExportPercent = findViewById(R.id.bigExportPercent)
        bigExportHint = findViewById(R.id.bigExportHint)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        txtBatchIndex = findViewById(R.id.txtBatchIndex)
        batchOverlay = findViewById(R.id.batchOverlay)
        batchRing = findViewById(R.id.batchRing)
        batchMain = findViewById(R.id.batchMain)
        batchSub = findViewById(R.id.batchSub)
        btnBatchStop = findViewById(R.id.btnBatchStop)
        filterContainer = findViewById(R.id.filterContainer)
        btnImport = findViewById(R.id.btnImport)
        btnExport = findViewById(R.id.btnExport)
        btnSuperRes = findViewById(R.id.btnSuperRes)
        btnSuperResFast = findViewById(R.id.btnSuperResFast)
        btnPerfect = findViewById(R.id.btnPerfect)
        btnHdr = findViewById(R.id.btnHdr)
        btnAdjust = findViewById(R.id.btnAdjust)
        btnCategory = findViewById(R.id.btnCategory)
        txtTitle = findViewById(R.id.txtTitle)
        imgTitleArrow = findViewById(R.id.imgTitleArrow)
        btnSettings = findViewById(R.id.btnSettings)
        txtCoach = findViewById(R.id.txtCoach)
        filterScroll = findViewById(R.id.filterScroll)
        adjPanel = findViewById(R.id.adjPanel)
        tabletRail = findViewById(R.id.tabletToolsColumn)
        tabletFilterScroll = findViewById(R.id.tabletFilterScroll)
        tabletCol0 = findViewById(R.id.tabletCol0)
        tabletCol1 = findViewById(R.id.tabletCol1)
        tabletCol2 = findViewById(R.id.tabletCol2)
        floatStage = findViewById(R.id.floatStage)
        rowBottomPills = findViewById(R.id.rowBottomPills)
        // 右下角色彩小字限宽（防三段长文案压住左下按钮行）：
        // 左按钮行宽度变化（完美/HDR 显隐）与舞台尺寸变化（旋转/换档）时自动重算
        rowBottomPills.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyHintMaxWidth() }
        floatStage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyHintMaxWidth() }
        // 记录原始文案并在限宽变化后重算语义折行（内部回写走 hintInternalSet 旁路）
        txtStyleHint.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (hintInternalSet) return
                hintRaw = s?.toString() ?: ""
                refreshHintWrap()
            }
        })
        // 调节浮层统一挂到舞台层（预览区+右栏之上）：手机档视觉与原预览区内浮层一致，
        // 平板横屏才能悬浮在右栏右下角（XML 里 adjPanel 的宿主是预览区，够不到右栏）
        (adjPanel.parent as? ViewGroup)?.removeView(adjPanel)
        floatStage.addView(adjPanel)
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
        sbTemp = findViewById(R.id.sbTemp)
        sbTint = findViewById(R.id.sbTint)
        sbLutStr = findViewById(R.id.sbLutStr)
        sbMono = findViewById(R.id.sbMono)
        sbGrain = findViewById(R.id.sbGrain)
        sbVignette = findViewById(R.id.sbVignette)
        txtTempVal = findViewById(R.id.txtTempVal)
        txtTintVal = findViewById(R.id.txtTintVal)
        txtLutStrVal = findViewById(R.id.txtLutStrVal)
        txtMonoVal = findViewById(R.id.txtMonoVal)
        txtGrainVal = findViewById(R.id.txtGrainVal)
        txtVignetteVal = findViewById(R.id.txtVignetteVal)
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
    // ===================== AI 调色助手（AiUiHost 实现） =====================
    //
    // 安全边界（与计划一致）：这一整块只做「读风格目录 / 读统计 / 改当前会话八档 /
    // 出预览 / 搜参考图 / 拟合临时色彩」。**没有任何写 SettingsStore 的路径**，
    // 参考色彩只有用户点「保存」才会新增一条 CustomStyleDef（只增不改）。

    private fun currentDisplayBitmap(): Bitmap? =
        liftedResult ?: resultBitmap ?: liftedOriginal ?: originalBitmap

    /** 主线程同步执行（AI 线程调用工具时用），超时返回 null，绝不阻塞过久。 */
    private fun <T> onMainSync(timeoutMs: Long = 6000, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var out: T? = null
        mainHandler.post {
            try {
                out = block()
            } catch (t: Throwable) {
                DiagLog.w("AiHost", "主线程工具执行失败：${t.message}")
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) null else out
        } catch (t: Throwable) {
            null
        }
    }

    override fun hasImage(): Boolean = originalBitmap != null

    override fun styleCatalog(): JSONArray {
        val arr = JSONArray()
        ColorEngine.Style.UI_VISIBLE.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                // AI 品牌锚定（aiDesc 空回落 desc）：去品牌化后 UI 文案无品牌词，
                // 「彩色经典=徕卡一瞬」「P9 鲜艳=华为 P9 徕卡」只有 LLM 经此通道可知
                put("desc", s.aiDesc.ifBlank { s.desc })
                put("kind", "builtin")
                // 分类锚定：category 携「中性名 · 品牌全称」（如「L-色彩 · 徕卡 Leica 相机色彩」），
                // 用户说「徕卡/柯达/富士」等品牌词即可定位家族档位
                put("category", familyOf(s)?.let { "${it.title} · ${it.aiName}" } ?: "")
            })
        }
        CustomStyleStore.all().sortedByDescending { it.createdAt }.take(40).forEach { d ->
            // 自动标注（StyleProfile 现算，毫秒级）：AI 凭标签把「我那张青橙的模型」匹配到具体色彩
            val profile = try { StyleProfile.of(d) } catch (t: Throwable) {
                DiagLog.w("AiHost", "风格标注失败 ${d.id}: ${t.message}")
                StyleProfile.Profile(emptyList(), "")
            }
            val kindDesc = if (d.hasModelNet) "模型色彩" else "我的色彩"
            val desc = if (profile.summary.isBlank()) kindDesc
                else "$kindDesc · ${profile.summary}"
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("label", d.name)
                put("desc", desc)
                put("kind", "custom")
                put("tags", JSONArray(profile.tags))
            })
        }
        return arr
    }

    /** 内置色彩 → 所属家族分类（AI 目录锚定用，取 title + aiName）；SA 隐藏项天然无归属返回 null。 */
    private fun familyOf(style: ColorEngine.Style): FilterCategory? =
        FilterCategory.values().firstOrNull {
            it != FilterCategory.ALL && it != FilterCategory.CUSTOM && style in it.styles
        }

    /**
     * 风格签名（assets/ai_style_signatures.json，离线烘焙）：该档对多样化图集的
     * 典型作用量（luma/contrast/sat/warmth，与 [imageStats] 同字段同口径）。
     * 懒加载一次缓存；资产缺失/损坏返回 null（模型链档无签名是正常路径）。
     */
    private var styleSignatures: org.json.JSONObject? = null
    private var styleSignaturesTried = false

    private fun signaturesOf(styleId: String): org.json.JSONObject? {
        if (!styleSignaturesTried) {
            styleSignaturesTried = true
            styleSignatures = try {
                assets.open("ai_style_signatures.json").bufferedReader().use { it.readText() }
                    .let { org.json.JSONObject(it).optJSONObject("styles") }
            } catch (t: Throwable) {
                DiagLog.w("AiHost", "签名表不可用：${t.message}")
                null
            }
        }
        return styleSignatures?.optJSONObject(styleId)
    }

    override fun imageStats(): JSONObject? {
        // 统计基准必须固定为原图：套风格后 resultBitmap 是处理后的，多轮对话里模型
        // 会失去「原图长什么样」的参照，无法做贴合微调。画布效果由 previewBase64 负责。
        val bmp = onMainSync { originalBitmap ?: currentDisplayBitmap() } ?: return null
        return try {
            if (bmp.isRecycled) null else ImageStats.describe(bmp)
        } catch (t: Throwable) {
            DiagLog.w("AiHost", "统计失败：${t.message}")
            null
        }
    }

    override fun applyParams(args: JSONObject): JSONObject {
        val res = onMainSync {
            if (originalBitmap == null) {
                JSONObject().apply { put("ok", false); put("error", "当前没有照片") }
            } else {
                // 接管锁定：用户手调过的维度 AI 跳过（不覆盖用户意志）
                val skipped = ArrayList<String>()
                val names = arrayOf(
                    "亮度", "曝光", "高光", "阴影", "黑位", "饱和度", "对比度", "柔光",
                    "色温", "色调", "黑白强度", "颗粒", "暗角", "风格强度"
                )
                fun put(idx: Int, key: String, cur: Int, lo: Int, hi: Int): Int {
                    if (dimUserTouched[idx]) { skipped.add(names[idx]); return cur }
                    return args.optInt(key, cur).coerceIn(lo, hi)
                }
                // 整体力度：AI 方案按 aiStrengthPct 打折（50% = 每项只偏离默认值一半）
                val s = (aiStrengthPct / 100.0).coerceIn(0.1, 1.0)
                val tgt = intArrayOf(
                    put(0, "brightness", brightLevel, -10, 10),
                    put(1, "exposure", exposureLevel, -10, 10),
                    put(2, "highlights", highlightLevel, -10, 10),
                    put(3, "shadows", shadowLevel, -10, 10),
                    put(4, "black", blackLevel, -10, 10),
                    put(5, "saturation", satLevel, -10, 10),
                    put(6, "contrast", contrastLevel, -10, 10),
                    put(7, "glow", glowLevel, -10, 10),
                    put(8, "temp", wbTemp, -10, 10),
                    put(9, "tint", wbTint, -10, 10),
                    put(10, "mono", monoLevel, 0, 100),
                    put(11, "grain", grainLevel, 0, 100),
                    put(12, "vignette", vignetteLevel, 0, 100),
                    put(13, "lutStrength", lutStrength, 0, 100)
                )
                val cur = intArrayOf(
                    brightLevel, exposureLevel, highlightLevel, shadowLevel,
                    blackLevel, satLevel, contrastLevel, glowLevel,
                    wbTemp, wbTint, monoLevel, grainLevel, vignetteLevel, lutStrength
                )
                val applied = IntArray(14) { i ->
                    val def = if (i == 13) 100 else 0
                    val lo0 = if (i >= 10) 0 else -10
                    val hi0 = if (i >= 10) 100 else 10
                    if (dimUserTouched[i]) cur[i]
                    else (def + Math.round((tgt[i] - def) * s)).toInt().coerceIn(lo0, hi0)
                }
                brightLevel = applied[0]; exposureLevel = applied[1]
                highlightLevel = applied[2]; shadowLevel = applied[3]
                blackLevel = applied[4]; satLevel = applied[5]
                contrastLevel = applied[6]; glowLevel = applied[7]
                wbTemp = applied[8]; wbTint = applied[9]
                monoLevel = applied[10]; grainLevel = applied[11]
                vignetteLevel = applied[12]; lutStrength = applied[13]
                val changed = applied.foldIndexed(0) { i, acc, v ->
                    acc + if (v != (if (i == 13) 100 else 0)) 1 else 0
                }
                aiResultHint = if (changed == 0) null else "AI · $changed 项"
                updateLumBarUi()
                recomputeAdjAsync()
                JSONObject().apply {
                    put("ok", true)
                    put("applied", currentParamsJson())
                    put("strength", aiStrengthPct)
                    if (skipped.isNotEmpty()) {
                        put("skipped", skipped.joinToString("、"))
                        put("note", "已按力度 $aiStrengthPct% 应用（跳过你手动调过的：${skipped.joinToString("、")}）")
                    } else if (aiStrengthPct < 100) {
                        put("note", "已按力度 $aiStrengthPct% 应用；预览在数百毫秒内更新")
                    } else {
                        put("note", "参数已应用，预览在数百毫秒内更新")
                    }
                }
            }
        }
        return res ?: JSONObject().apply { put("ok", false); put("error", "主线程无响应") }
    }

    override fun applyStyle(styleId: String): JSONObject {
        val res = onMainSync {
            val builtIn = ColorEngine.Style.values()
                .firstOrNull { it.id == styleId && it in ColorEngine.Style.UI_VISIBLE }
            val custom = CustomStyleStore.byId(styleId)
            when {
                builtIn != null -> {
                    onFilterSelected(FilterEntry.BuiltIn(builtIn))
                    applyStyleStrengthFromAi()
                    JSONObject().apply {
                        put("ok", true)
                        put("style", builtIn.label)
                        put("note", "已切换「${builtIn.label}」" +
                            (if (aiStrengthPct < 100) "（按力度 $aiStrengthPct% 应用）" else "") +
                            "，渲染约 1~2 秒后生效")
                        // 风格签名：该档对多样化图集的典型作用量（与原图统计同口径）。
                        // 模型据此做「本图现状 × 作用量 → 过冲预判」的贴合微调；
                        // 模型链档（M9 系等）无签名，缺省靠语义 + 统计微调。
                        signaturesOf(builtIn.id)?.let { put("signature", it) }
                    }
                }
                custom != null -> {
                    onFilterSelected(FilterEntry.Custom(custom.id))
                    applyStyleStrengthFromAi()
                    JSONObject().apply {
                        put("ok", true)
                        put("style", custom.name)
                        put("note", "已切换「${custom.name}」" +
                            (if (aiStrengthPct < 100) "（按力度 $aiStrengthPct% 应用）" else "") +
                            "，渲染约 1~2 秒后生效")
                    }
                }
                else -> JSONObject().apply {
                    put("ok", false)
                    put("error", "没有这个色彩：$styleId")
                }
            }
        }
        return res ?: JSONObject().apply { put("ok", false); put("error", "主线程无响应") }
    }

    override fun previewBase64(maxDim: Int): String? {
        val copy = onMainSync {
            val bmp = currentDisplayBitmap() ?: return@onMainSync null
            if (bmp.isRecycled) return@onMainSync null
            val m = maxOf(bmp.width, bmp.height)
            if (m <= maxDim) bmp.copy(Bitmap.Config.ARGB_8888, false) else {
                val s = maxDim.toFloat() / m
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * s).toInt().coerceAtLeast(1),
                    (bmp.height * s).toInt().coerceAtLeast(1),
                    true
                )
            }
        } ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            copy.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            copy.recycle()
            android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            try { copy.recycle() } catch (e: Throwable) { }
            null
        }
    }

    override fun currentSummary(): String {
        val styleName = when {
            currentCustomId != null -> CustomStyleStore.nameOf(currentCustomId!!)
            currentStyle != null -> currentStyle!!.label
            else -> "原图"
        }
        return "色彩=$styleName；" + paramsSummary()
    }

    /** AI 面板图库按钮入口：拉起系统相册多选（结果落 [RefAttachmentCache]）。 */
    fun launchAiRefPicker() {
        pickAiRefs.launch("image/*")
    }

    /** 选图即落盘私有缓存（后台线程），完成后刷新 AI 面板附件条。 */
    private fun ingestAiRefAttachments(uris: List<Uri>) {
        executor.execute {
            val items = RefAttachmentCache.save(this, uris)
            mainHandler.post {
                if (items.size < uris.size) {
                    toast("参考图一次最多 ${RefAttachmentCache.MAX_COUNT} 张，已取前 ${RefAttachmentCache.MAX_COUNT} 张")
                }
                aiSheet?.refreshRefAttachments()
            }
        }
    }

    /**
     * 用附件缩略拟合参考色彩并应用到预览（**后台线程调用**）。
     *
     * [RefAttachmentCache] 的 240px 缩略就是 M6 编码链的既有输入规格（与模型增强路径
     * 同源），拟合质量与逐张喂原图的旧链一致。返回 {ok, name, count} 或 {ok:false, error}。
     */
    fun fitReferenceFromThumbs(thumbs: List<File>): JSONObject {
        val fail = { msg: String ->
            JSONObject().apply { put("ok", false); put("error", msg) }
        }
        if (originalBitmap == null) return fail("当前没有照片")
        val refs = ArrayList<Bitmap>(thumbs.size)
        for (f in thumbs) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) refs.add(b)
        }
        if (refs.isEmpty()) return fail("参考图无法读取")
        val count = refs.size
        val style = try {
            AdaptiveColorNet.encodeStyle(this, refs)
        } catch (t: Throwable) {
            DiagLog.w("AiHost", "拟合异常：${t.message}")
            null
        } ?: run {
            refs.forEach { it.recycle() }
            return fail("参考图拟合失败")
        }
        val finalStyle = try {
            AdaptiveColorNet.monoIfGray(refs, style).first
        } catch (t: Throwable) {
            style
        }
        refs.forEach { it.recycle() }

        // 拷一份底图：预览链在另一条线程会 recycle 旧 lifted 位图，直接引用可能踩到已回收对象
        val base = onMainSync {
            val b = currentDisplayBitmap()
            if (b == null || b.isRecycled) null else b.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return fail("没有可预览的底图")
        val rawPreview = try {
            AdaptiveColorNet.apply(base, finalStyle)
        } catch (t: Throwable) {
            null
        } ?: return fail("渲染失败")
        // AI 力度 <100：参考拟合结果按力度往底图方向拉回（线性混合，与风格强度同语义）
        val preview = if (aiStrengthPct < 100) {
            try { AdjustOps.blendLut(base, rawPreview, aiStrengthPct.coerceIn(10, 100)) } catch (t: Throwable) { rawPreview }
        } else rawPreview
        if (preview !== rawPreview) rawPreview.recycle()
        base.recycle()

        aiRefStyle = finalStyle
        aiRefPreview = preview
        val name = uniqueAiStyleName()
        onMainSync {
            imgPreview.setImageBitmap(preview)
            txtStyleHint.text = "AI 参考 · 待保存" +
                (if (aiStrengthPct < 100) " · 力度 $aiStrengthPct%" else "")
        }
        return JSONObject().apply {
            put("ok", true)
            put("name", name)
            put("count", count)
        }
    }

    /** 当前新维度 extras（导出三站与预览同源） */
    private fun currentExtras(): AdjustOps.Extras =
        AdjustOps.Extras(wbTemp, wbTint, monoLevel, grainLevel, vignetteLevel)

    /** AI 力度 <100：切色彩后把风格强度同步为力度（用户接管过风格强度则尊重用户值） */
    private fun applyStyleStrengthFromAi() {
        if (aiStrengthPct < 100 && !dimUserTouched[13]) {
            lutStrength = aiStrengthPct.coerceIn(10, 100)
            updateLumBarUi()
        }
    }

    private fun paramsSummary(): String =
        "亮度=$brightLevel 曝光=$exposureLevel 高光=$highlightLevel 阴影=$shadowLevel " +
            "黑位=$blackLevel 饱和=$satLevel 对比=$contrastLevel 柔光=$glowLevel " +
            "色温=$wbTemp 色调=$wbTint 黑白=$monoLevel 颗粒=$grainLevel " +
            "暗角=$vignetteLevel 风格强度=$lutStrength"

    private fun currentParamsJson(): JSONObject = JSONObject().apply {
        put("brightness", brightLevel)
        put("exposure", exposureLevel)
        put("highlights", highlightLevel)
        put("shadows", shadowLevel)
        put("black", blackLevel)
        put("saturation", satLevel)
        put("contrast", contrastLevel)
        put("glow", glowLevel)
        put("temp", wbTemp)
        put("tint", wbTint)
        put("mono", monoLevel)
        put("grain", grainLevel)
        put("vignette", vignetteLevel)
        put("lutStrength", lutStrength)
    }

    /**
     * AI 撤销快照：14 档参数 + 当前色彩（style_id/custom_id，空=原图）+ 接管标记。
     * 撤销 = 整组还原（色彩变了走 applyStyle 异步切回），不只是参数。
     */
    override fun snapshotParams(): JSONObject = currentParamsJson().apply {
        put("style_id", currentStyle?.id ?: "")
        put("custom_id", currentCustomId ?: "")
        put("touched", JSONArray(dimUserTouched.toList()))
    }

    override fun restoreParams(snapshot: JSONObject) {
        onMainSync {
            // 完整撤销·色彩部分（先于参数恢复：applyStyle 同步侧的力度覆盖会被后面的
            // 参数还原覆盖回快照值）。旧快照无该键（调节面板重置撤销）则跳过，行为不变。
            // 切色彩是异步渲染：落地回调按 exposureLevel/lutStrength 决定是否重算参数预览，
            // 与撤销目标态天然自洽（撤销到全零参数时切完即目标画面）。
            if (snapshot.has("style_id") || snapshot.has("custom_id")) {
                val sid = snapshot.optString("style_id", "")
                val cid = snapshot.optString("custom_id", "")
                if (sid != (currentStyle?.id ?: "") || cid != (currentCustomId ?: "")) {
                    when {
                        sid.isNotEmpty() -> applyStyle(sid)
                        cid.isNotEmpty() -> applyStyle(cid)
                        else -> {
                            // 撤回「原图」：清色彩引用，预览回原图/原图调整版
                            resultBitmap?.recycle()
                            resultBitmap = null
                            currentStyle = null
                            currentCustomId = null
                            refreshPreviewImage()
                            txtStyleHint.text = "原图"
                        }
                    }
                }
            }
            brightLevel = snapshot.optInt("brightness", brightLevel).coerceIn(-10, 10)
            exposureLevel = snapshot.optInt("exposure", exposureLevel).coerceIn(-10, 10)
            highlightLevel = snapshot.optInt("highlights", highlightLevel).coerceIn(-10, 10)
            shadowLevel = snapshot.optInt("shadows", shadowLevel).coerceIn(-10, 10)
            blackLevel = snapshot.optInt("black", blackLevel).coerceIn(-10, 10)
            satLevel = snapshot.optInt("saturation", satLevel).coerceIn(-10, 10)
            contrastLevel = snapshot.optInt("contrast", contrastLevel).coerceIn(-10, 10)
            glowLevel = snapshot.optInt("glow", glowLevel).coerceIn(-10, 10)
            wbTemp = snapshot.optInt("temp", wbTemp).coerceIn(-10, 10)
            wbTint = snapshot.optInt("tint", wbTint).coerceIn(-10, 10)
            monoLevel = snapshot.optInt("mono", monoLevel).coerceIn(0, 100)
            grainLevel = snapshot.optInt("grain", grainLevel).coerceIn(0, 100)
            vignetteLevel = snapshot.optInt("vignette", vignetteLevel).coerceIn(0, 100)
            lutStrength = snapshot.optInt("lutStrength", lutStrength).coerceIn(0, 100)
            // 接管标记随撤销回滚：否则撤销后 AI 被幽灵锁定绑住，后续微调莫名跳档
            // （旧快照无该键则保持现状，兼容调节面板重置撤销）
            snapshot.optJSONArray("touched")?.let { arr ->
                for (i in 0 until minOf(14, arr.length())) {
                    dimUserTouched[i] = arr.optBoolean(i, false)
                }
            }
            updateLumBarUi()
            recomputeAdjAsync()
        }
    }

    override fun hasPendingReferenceStyle(): Boolean = aiRefStyle != null

    override fun pendingReferenceDefaultName(): String = uniqueAiStyleName()

    /** AI 参考色彩入库：只新增，不触碰任何已有条目（重名自动加序号）。 */
    override fun savePendingReferenceStyle(name: String): Boolean {
        val style = aiRefStyle ?: return false
        val finalName = uniqueAiStyleName(name)
        val id = CustomStyleStore.newId()
        val now = System.currentTimeMillis()
        val verId = "v" + now.toString(36)

        // 卡片缩略图：拿当前底图渲一张 240px 的成品图（失败不影响入库）
        try {
            val base = originalBitmap
            if (base != null) {
                val small = scaleBitmap(base, 240)
                val applied = AdaptiveColorNet.apply(small, style) ?: small
                val bos = ByteArrayOutputStream()
                applied.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                ColorShareImport.saveThumb(this, id, bos.toByteArray())
                thumbCache[id] = applied
                if (applied !== small) small.recycle()
            }
        } catch (t: Throwable) {
            DiagLog.w("AiHost", "缩略图生成失败：${t.message}")
        }

        val version = StyleVersion(
            id = verId,
            createdAt = now,
            label = "初始",
            lutFile = "",
            seedCount = 0,
            source = VersionSource.INITIAL,
            strength = 1f,
            thetaFile = "",
            paramFile = "",
            modelAff = style.aff,
            modelKnots = style.knots
        )
        val def = CustomStyleDef(
            id = id,
            name = finalName,
            createdAt = now,
            seeds = mutableListOf(),
            versions = mutableListOf(version),
            currentVersionId = verId,
            kind = StyleKind.MODEL,
            seedCount = 0,
            seedsLoaded = false,
            modelAff = style.aff,
            modelKnots = style.knots
        )
        val ok = try {
            CustomStyleStore.put(this, def)
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            aiRefStyle = null
            aiRefPreview = null
            seenRevision = CustomStyleStore.revision
            onModelPicked(id)
            toast("已保存「$finalName」到我的色彩")
        }
        return ok
    }

    override fun clearPendingReferenceStyle() {
        aiRefStyle = null
        aiRefPreview = null
    }

    override fun onAiSheetClosed() {
        // 预览保留（AI 的调整属于当前编辑会话，不因关面板而丢弃）；
        // 有调整结果时把入口卡文案换成「AI · N 项」，让成果在滤镜条上可见
        if (aiResultHint != null) buildFilterBar(currentCategory)
    }

    /** 「AI 色彩」命名去重：只加序号，绝不覆盖已有同名条目。 */
    private fun uniqueAiStyleName(base: String = "AI 色彩"): String {
        val baseName = base.trim().ifEmpty { "AI 色彩" }
        val names = CustomStyleStore.all().map { it.name }.toHashSet()
        if (baseName !in names) return baseName
        var i = 2
        while ("$baseName $i" in names) i++
        return "$baseName $i"
    }

    private fun buildFilterBar(category: FilterCategory) {
        currentCategory = category
        txtTitle.text = category.title
        imgTitleArrow.rotation = 0f
        filterContainer.removeAllViews()
        tabletCol0.removeAllViews(); tabletCol1.removeAllViews(); tabletCol2.removeAllViews()
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
            // AI 入口常驻末尾：既是上滑手势的可见兜底，也不参与分类归属/置顶
            add(FilterEntry.Ai)
        }
    }

    /** L-Moment /「我的色彩」里的自定义色彩：QUICK 与模型色彩混排，新建的排前面 */
    private fun customEntries(): List<FilterEntry> = CustomStyleStore.all()
        .sortedByDescending { it.createdAt }
        .map { FilterEntry.Custom(it.id) }

    /**
     * 已隐藏的内置色彩（[ColorEngine.Style.UI_VISIBLE] 之外，目前只有 Style A）。
     * 枚举仍在，分享码/批量测试按 id 依然可用，这里只负责不让它进卡片列表。
     */
    private fun isHiddenEntry(entry: FilterEntry): Boolean {
        val style = (entry as? FilterEntry.BuiltIn)?.style ?: return false
        return style !in ColorEngine.Style.UI_VISIBLE
    }

    /**
     * 非跨分类模式下置顶项的归属判定（与分类正常区同口径）：
     * L-Moment 全量可见；自定义/模型色彩只在「我的色彩」；内置色彩在其所属分类。
     */
    private fun belongsToCategory(entry: FilterEntry, category: FilterCategory): Boolean {
        if (entry is FilterEntry.Ai) return true      // AI 入口常驻所有分类末尾
        return when (category) {
            FilterCategory.ALL -> true
            FilterCategory.CUSTOM -> entry is FilterEntry.Custom
            else -> (entry as? FilterEntry.BuiltIn)?.style in category.styles
        }
    }

    /** 持久化的 id → 条目；色彩已被删除时返回 null（残留的置顶记录自动跳过） */
    private fun resolveEntry(id: String): FilterEntry? {
        if (id == FilterEntry.ORIG_ID) return FilterEntry.Orig
        if (id == FilterEntry.AI_ID) return FilterEntry.Ai
        ColorEngine.Style.values().firstOrNull { it.id == id }?.let { return FilterEntry.BuiltIn(it) }
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
    private fun selectCardByStyle(style: ColorEngine.Style?) {
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

    /**
     * 是否有任意一档参数被调整过。
     *
     * 用于长按对比：AI 常在没有选色彩（原图卡）的情况下只调八档，
     * 若对比只看 [hasColor]，这种场景就看不到调整前后差异。
     */
    private fun hasAdjustments(): Boolean =
        brightLevel != 0 || exposureLevel != 0 || highlightLevel != 0 || shadowLevel != 0 ||
            blackLevel != 0 || satLevel != 0 || contrastLevel != 0 || glowLevel != 0 ||
            wbTemp != 0 || wbTint != 0 || monoLevel != 0 || grainLevel != 0 ||
            vignetteLevel != 0 || lutStrength != 100

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
        // 卡宽/缩略图按档位取值：手机 72/60（现状）· 回落档 96/76 · 双栏三列 MATCH_PARENT/84
        val cardW = FormFactor.cardWidthDp(currentForm)
        val tdp = thumbSizeDp()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(cardInsetH, dp(8), cardInsetH, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                if (cardW == 0) LinearLayout.LayoutParams.MATCH_PARENT else dp(cardW),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (cardW == 0) bottomMargin = dp(8) }   // 三列竖排的卡间距
        }
        val tv = TextView(this).apply {
            text = when {
                entry is FilterEntry.Create -> "新建"
                entry is FilterEntry.Ai -> aiResultHint ?: "AI"
                else -> entry.label
            }
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
                layoutParams = LinearLayout.LayoutParams(dp(tdp), dp(tdp))
            })
        } else if (entry is FilterEntry.Ai) {
            // AI 入口卡：描边 + ✨，与色彩卡区分（"选现成的" vs "让 AI 来"）
            card.addView(TextView(this).apply {
                text = "✦"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#F2F2F2"))
                background = GradientDrawable().apply {
                    cornerRadius = thumbCorner.toFloat()
                    setColor(Color.parseColor("#1A1A1D"))
                    setStroke(dp(1), Color.parseColor("#59FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(tdp), dp(tdp))
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
            // 用 FrameLayout 包一层只为叠「模型」角标，几何随档位缩略图尺寸
            val holder = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(tdp), dp(tdp))
            }
            holder.addView(img, FrameLayout.LayoutParams(dp(tdp), dp(tdp)))
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
            // AI 卡不改选中态：它不是一个色彩，点它只是打开抽屉
            if (entry is FilterEntry.Ai) {
                openAiSheet()
                return@setOnClickListener
            }
            selectCard(index)
            when (entry) {
                is FilterEntry.BuiltIn -> onFilterSelected(entry)
                is FilterEntry.Custom -> onFilterSelected(entry)
                is FilterEntry.Orig -> onFilterSelected(entry)
                // 新建入口统一走模型色彩（QUICK 快速生成已退役，管线见 git 历史）
                is FilterEntry.Create -> openModelCreate()
                is FilterEntry.Ai -> openAiSheet()      // 上面已提前返回，这里只为密封 when 完整
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
        // 挂载点按档位：手机/回落档 → 底部横滑条；双栏 → 三列网格（按序交替分配 i%3）
        if (currentForm == Form.TABLET_LAND) {
            cardColumnFor(index).addView(card)
        } else {
            filterContainer.addView(card)
        }
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
                val out = File(dir, "LMColors_$safeName.lmc")
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
        // 分享是单图入口：与「导入单张」同口径，先收摊已有批次，避免批次序号指向旧图
        clearBatch()
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

    /**
     * 导入一张图并（可选）绑定到批量条目。
     *
     * @param item 非空时把加载结果绑进该条目（切图冷加载 / 批量首张）
     * @param silent 批量导出用的静默装载：只写字段、不碰任何 View（不闪预览、不重建缩略图、
     *               不弹 toast），落地后直接把字段对齐该条目保存的设置
     * @param onResult 加载结果回调（主线程，恰好一次）：批量首张失败顺延、批量导出跳过坏图都靠它
     */
    private fun onImagePicked(uri: Uri, item: EditItem? = null, silent: Boolean = false,
                              onResult: ((Boolean) -> Unit)? = null) {
        if (!silent) progress.visible()
        executor.execute {
            try {
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = ImageUtils.readBytes(this, uri)
                val displayName = queryDisplayName(uri)?.lowercase()
                val isRaw = displayName != null && RAW_EXTS.any { displayName.endsWith(it) }
                val isJpeg = !isRaw && (mime.contains("jpeg") || mime.contains("jpg") || isJpegBytes(bytes))
                // 伪 RAW 开关快照（真 RAW 不走这条路：它本身就有 16-bit 线性解码）
                pseudoLinearAllowed = !isRaw && SettingsStore.pseudoLinearEnabled(this)
                pseudoLinearFromHeif = false
                DiagLog.i("Import", "读取 ${bytes.size}B mime=$mime raw=$isRaw jpeg=$isJpeg " +
                    "pseudo=${pseudoLinearAllowed}")

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
                    writeCacheBytes(trimmed!!, "lm_fullres_src")
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
                rawLin16Level = 0          // 档位必须一起清：否则上一张伪 RAW 的 3 会残留到本张
                rawPreviewJpeg = null
                rawLinearLuma = null
                if (isRaw) {
                    val r = decodeRaw(bytes)
                    if (r == null) {
                        DiagLog.e("Import", "RAW 解码失败")
                        mainHandler.post {
                            progress.gone()
                            toast("RAW 解码失败")
                            onResult?.invoke(false)
                        }
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
                            mainHandler.post {
                            progress.gone()
                            toast("无法解码该图片")
                            onResult?.invoke(false)
                        }
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
                            mainHandler.post {
                            progress.gone()
                            toast("无法解码该图片")
                            onResult?.invoke(false)
                        }
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

                // 伪 RAW（JPG/HEIF 高位深工作域）：复用刚得到的预览位图建 16-bit 线性基准。
                // 只登记基准，**不改 workBytes**——JPG 仍原字节直通（零重编码、原生 EXIF），
                // HEIF 仍走既有重编码入径；导出是否真进 16-bit 域由「七档是否非零」再判一次，
                // 全零时仍走原链（零性能损失、JPG 不会白掉一代编码）。
                if (pseudoLinearAllowed) {
                    // HEIF/HEIC：优先走 10-bit 直通（线性扩展现色域 → RGBA_F16 → u16）。
                    // 厂商解码器不支持时内部自动退回 8-bit 线性化；整条 HEIF 路径失败再用预览位图。
                    val isHeif = mime.contains("heif") || mime.contains("heic") ||
                        displayName?.endsWith(".heif") == true || displayName?.endsWith(".heic") == true
                    var base = if (isHeif) PseudoRawLinear.previewBaseFromHeif(this, uri) else null
                    if (base != null) {
                        pseudoLinearFromHeif = true
                        DiagLog.i("PSEUDO", "基准来源=HEIF 直通解码")
                    } else {
                        base = PseudoRawLinear.buildPreviewBase(preview)
                    }
                    if (base != null) {
                        rawLin16 = base
                        rawLin16Level = RawToneMap.PSEUDO_LEVEL
                        // 刻意不写 rawPreviewJpeg：伪 RAW 与「内嵌预览」同源，分位匹配恒等，无意义
                        // 刻意不写 rawLinearLuma：伪线性算不出物理增益，HDR 仍走 V1 猜测式/源图增益图
                        DiagLog.i("PSEUDO", "基准就绪 ${base.w}x${base.h} level=$rawLin16Level")
                    } else {
                        DiagLog.w("PSEUDO", "基准构建失败 → 本次按 8bit 链处理（无感降级）")
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
                    // 换图：旧预览/结果位图必须主动回收，否则每切一张泄漏约 8MB（批量切换会迅速堆满堆）。
                    // 顺序约定：先记旧引用 → 字段指向新对象 → 视图换图 → 最后回收旧图，
                    // 与 recomputeAdjAsync 里「先换显示再回收」同款，避免画到已回收位图。
                    // 但批量场景下旧图可能已被 snapshot 进某个 EditItem.hot（归属已转移），
                    // 那种情况绝不能回收——否则切回去就是一张已回收位图。
                    val prevOriginal = originalBitmap
                    val prevResult = resultBitmap
                    if (!silent) dropAdjCache()

                    // ---- 字段段：静默装载（批量导出）同样执行，保证导出读到的就是这张图 ----
                    originalBytes = normalizedBytes
                    workWidth = workSize[0]
                    workHeight = workSize[1]
                    pendingHdr = if (jpegDirect) hdr else null
                    // C1：全像素直通源（仅「原图」档导出可用；滤镜仍走 normalizedBytes）
                    // 批量切图时上一张的 cache 文件归属已转移到 EditItem，
                    // 调用方会先 detachFullResFields()，这里的删除才是空操作。
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
                    wbTemp = 0
                    wbTint = 0
                    monoLevel = 0
                    grainLevel = 0
                    vignetteLevel = 0
                    lutStrength = 100
                    java.util.Arrays.fill(dimUserTouched, false)   // 换图：接管锁定一并解除
                    // HDR 开关是记忆项：取持久化默认值，但源图自带增益图时本次强制关闭。
                    // 这里**只读不写**记忆——否则导一张徕卡直出（自带 HDR）就会把用户的「开」冲掉。
                    val hasSourceHdr = pendingHdr != null || fullResHdr != null
                    hdrBoost = SettingsStore.hdrDefaultOn(this) && !hasSourceHdr
                    // 作废在途调整任务：防止旧图的调整版完成于新导入之后盖在预览上
                    liftGen++
                    originalBitmap = preview
                    resultBitmap = null
                    currentStyle = null
                    currentCustomId = null
                    // 换图：AI 的临时参考色彩与入口卡文案属于上一张图，一并作废
                    aiRefStyle = null
                    aiRefPreview = null
                    aiResultHint = null
                    liftedOriginal?.recycle(); liftedOriginal = null
                    liftedResult?.recycle(); liftedResult = null
                    // 完美档完整管线需要原始 DNG：这里只记源，用时再重读（不常驻大字节）
                    sourceUri = uri
                    sourceIsRaw = isRaw
                    perfectBytes = null
                    perfectStyle = null
                    perfectApplied = false
                    if (item != null) {
                        // 批量：把本次加载的热数据/中状态绑进条目。**轻状态（色彩/档位）保持条目原值**，
                        // 随后由 applySessionFields 原样装回，冷加载才不会把该图的设置清成原图。
                        snapshotHotInto(item)
                    }

                    if (!silent) {
                        // ---- UI 段 ----
                        if (item != null) applySessionFields(item)
                        setHdrButton(hdrBoost)
                        updateLumBarUi()
                        // 调节入口对所有导入开放（RAW/JPG/HEIF）；管线作用在工作字节上，与输入格式无关
                        btnAdjust.visible()
                        adjPanel.gone()
                        adjOpenState[currentForm] = false
                        adjScrim?.visibility = View.GONE
                        hideUndoInHeader()
                        // AI 对话跨图保留（用户要求）：只提示上下文切换，不清历史
                        aiSheet?.onImageChanged()
                        aiSheet?.dismiss()
                        txtEmpty.gone()
                        imgPreview.setImageBitmap(originalBitmap ?: preview)
                        // 视图已换到新图，才可以回收旧图；归属仍在批量条目里的不回收（切回去还要用）
                        if (prevOriginal !== preview && !isCachedByBatch(prevOriginal)) prevOriginal?.recycle()
                        if (!isCachedByBatch(prevResult)) prevResult?.recycle()
                        txtStyleHint.visible()
                        btnSuperRes.visible()
                        btnSuperResFast.visible()
                        // 此时 pendingHdr / fullResHdr 已赋值，仅按「源图自带 HDR」决定是否隐藏
                        updateHdrVisibility()
                        setPerfectButton(perfectApplied)
                        updatePerfectVisibility(currentStyle)
                        txtStyleHint.text = "原图"
                        selectCard(0)
                        progress.gone()
                        generateThumbnails()
                        maybeShowAiOnboarding()
                        // 批量：按该图保存的色彩/档位刷新预览（新条目为空 → 仍是原图）；
                        // 缩略图刚由 generateThumbnails 重建，不再重复
                        if (item != null) refreshAfterSwitch(rebuildThumbs = false)
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
                    } else if (item != null) {
                        // 静默装载（批量导出）：不碰任何 View，只保证字段与该条目一致
                        applySessionFields(item)
                    }
                    onResult?.invoke(true)
                }
            } catch (e: Exception) {
                DiagLog.e("Import", "导入异常", e)
                mainHandler.post {
                    progress.gone()
                    toast("读取图片失败，请换一张或重试（详情见日志）")
                    onResult?.invoke(false)
                }
            }
        }
    }

    // ---- 批量会话：字段冻结 / 装载 ----
    //
    // 为什么是「快照 + 装载」而不是把状态抽成对象：导出/渲染/调节共 5000 行全部直接读写成
    // 员字段，改造成本与回归面都不可接受。这里只在切图这一个时机做整体打包/还原，单图逻辑
    // 一行不改。代价是必须**穷举**字段（漏一项 = 参数串图），故下面两函数严格成对维护。

    /**
     * 把当前活动图的字段整体打包进 [item]。
     *
     * 调用时机：切图前、批量导出前、退到后台/销毁前。热数据（工作字节+预览位图）一并转移
     * 给条目持有，之后**不得**再被 onImagePicked 的换图回收逻辑回收（见 isCachedByBatch）。
     */
    private fun snapshotActiveInto(item: EditItem) {
        snapshotLightInto(item)
        // 完美成片 / 超分合成成果属于「这张图的产物」，只在切走时回收登记；
        // 装载路径绝不能覆盖它们（否则再导出一张完美图就退化成普通链）
        item.perfectBytes = perfectBytes
        item.perfectStyle = perfectStyle
        item.srBakedStyle = srBakedStyle
        snapshotHotInto(item)
    }

    /**
     * 只写轻状态（色彩 / 八档 / HDR）。
     *
     * 滑杆连续拖动会每帧触发一次，走全量快照（含 thumbCache 拷贝）纯属制造 GC 压力，
     * 故与 [snapshotActiveInto] 拆开。
     */
    private fun snapshotLightInto(item: EditItem) {
        item.styleId = currentStyle?.id
        item.customId = currentCustomId
        val lv = item.levels
        lv[EditItem.LV_BRIGHT] = brightLevel
        lv[EditItem.LV_EXPOSURE] = exposureLevel
        lv[EditItem.LV_HIGHLIGHT] = highlightLevel
        lv[EditItem.LV_SHADOW] = shadowLevel
        lv[EditItem.LV_BLACK] = blackLevel
        lv[EditItem.LV_SAT] = satLevel
        lv[EditItem.LV_CONTRAST] = contrastLevel
        lv[EditItem.LV_GLOW] = glowLevel
        lv[EditItem.LV_TEMP] = wbTemp
        lv[EditItem.LV_TINT] = wbTint
        lv[EditItem.LV_MONO] = monoLevel
        lv[EditItem.LV_GRAIN] = grainLevel
        lv[EditItem.LV_VIGNETTE] = vignetteLevel
        lv[EditItem.LV_LUT_STRENGTH] = lutStrength
        item.hdrBoost = hdrBoost
    }

    /**
     * 只打包「图本身」的数据（中状态 + 热状态），**不动色彩/档位**。
     *
     * 冷加载落地时用这个：此时字段里是刚导入的默认值（原图/0 档），而条目里存着这张图
     * 之前保存的设置；若用 [snapshotActiveInto] 会把设置一起冲掉。
     */
    private fun snapshotHotInto(item: EditItem) {
        // 中状态
        item.pendingHdr = pendingHdr
        item.fullResFile = fullResFile
        item.fullResHead = fullResExifHead
        item.fullResW = fullResWidth
        item.fullResH = fullResHeight
        item.fullResHdr = fullResHdr
        item.sourceUri = sourceUri
        item.sourceIsRaw = sourceIsRaw
        // 热状态（没有工作字节/预览就保持旧值，避免把条目打成"空壳"）
        val work = originalBytes
        val preview = originalBitmap
        if (work != null && preview != null && !preview.isRecycled) {
            item.hot = EditItem.Hot(
                workBytes = work,
                workW = workWidth,
                workH = workHeight,
                preview = preview,
                result = resultBitmap,
                thumbs = HashMap(thumbCache),
                rawLin16 = rawLin16,
                rawLin16Level = rawLin16Level,
                rawPreviewJpeg = rawPreviewJpeg,
                rawLinearLuma = rawLinearLuma,
                pseudoLinear = pseudoLinearAllowed,
                pseudoFromHeif = pseudoLinearFromHeif
            )
        }
    }

    /**
     * 把 [item] 的字段装回 MainActivity（**不碰任何 View**）。
     *
     * 静默装载（批量导出）与切图后的 UI 恢复共用这一段，避免两份字段逻辑漂移。
     *
     * @return false = 该条目还没有热数据，调用方需要先沿导入链把它加载出来
     */
    private fun applySessionFields(item: EditItem): Boolean {
        val hot = item.hot ?: return false
        applyingSession = true
        // 轻状态
        currentStyle = item.styleId?.let { id -> ColorEngine.Style.values().firstOrNull { it.id == id } }
        currentCustomId = item.customId
        val lv = item.levels
        brightLevel = lv[EditItem.LV_BRIGHT]
        exposureLevel = lv[EditItem.LV_EXPOSURE]
        highlightLevel = lv[EditItem.LV_HIGHLIGHT]
        shadowLevel = lv[EditItem.LV_SHADOW]
        blackLevel = lv[EditItem.LV_BLACK]
        satLevel = lv[EditItem.LV_SAT]
        contrastLevel = lv[EditItem.LV_CONTRAST]
        glowLevel = lv[EditItem.LV_GLOW]
        wbTemp = lv[EditItem.LV_TEMP]
        wbTint = lv[EditItem.LV_TINT]
        monoLevel = lv[EditItem.LV_MONO]
        grainLevel = lv[EditItem.LV_GRAIN]
        vignetteLevel = lv[EditItem.LV_VIGNETTE]
        lutStrength = lv[EditItem.LV_LUT_STRENGTH]
        // HDR：与导入期同一条规则——源图自带增益图时开关不起作用（只压制生效值，不改用户选择）
        val hasSourceHdr = item.pendingHdr != null || item.fullResHdr != null
        hdrBoost = item.hdrBoost && !hasSourceHdr
        // 中状态
        pendingHdr = item.pendingHdr
        fullResFile = item.fullResFile
        fullResExifHead = item.fullResHead
        fullResWidth = item.fullResW
        fullResHeight = item.fullResH
        fullResHdr = item.fullResHdr
        perfectBytes = item.perfectBytes
        perfectStyle = item.perfectStyle
        perfectApplied = item.perfectBytes != null
        srBakedStyle = item.srBakedStyle
        sourceUri = item.sourceUri
        sourceIsRaw = item.sourceIsRaw
        // 热状态
        originalBytes = hot.workBytes
        workWidth = hot.workW
        workHeight = hot.workH
        originalBitmap = hot.preview
        resultBitmap = hot.result
        thumbCache.clear()
        thumbCache.putAll(hot.thumbs)
        rawLin16 = hot.rawLin16
        rawLin16Level = hot.rawLin16Level
        rawPreviewJpeg = hot.rawPreviewJpeg
        rawLinearLuma = hot.rawLinearLuma
        pseudoLinearAllowed = hot.pseudoLinear
        pseudoLinearFromHeif = hot.pseudoFromHeif
        // 逐张级别的瞬时标志：绝不能跨图残留（上一张的回落会让下一张直接跳过全像素）
        skipFullResOnce = false
        fullResFallbackOnce = false
        applyingSession = false
        return true
    }

    /**
     * 该位图是否仍被批量条目的热数据引用。
     *
     * 换图回收位图时必须问一句：这张是不是还挂在某个 EditItem.hot 里？是则归属已转移，
     * 回收它等于把用户切回去的那张图打成废图（Canvas: trying to use a recycled bitmap）。
     */
    private fun isCachedByBatch(bmp: Bitmap?): Boolean {
        if (bmp == null) return true
        return batchItems.any { it.hot?.preview === bmp || it.hot?.result === bmp }
    }

    /**
     * 释放调档预览基准。
     *
     * 与 [ensureAdjBase] 同一套所有权规则：基准与基图是同一对象时（小图直接复用）不能回收；
     * 只有独立副本才回收。换图/切图前必须清空，否则会把上一张的 1920 基准一直钉在堆里。
     */
    private fun dropAdjCache() {
        val baseO = adjBaseO
        val baseR = adjBaseR
        if (baseO != null && baseO !== adjSrcO && !baseO.isRecycled) baseO.recycle()
        if (baseR != null && baseR !== adjSrcR && !baseR.isRecycled) baseR.recycle()
        adjBaseO = null
        adjSrcO = null
        adjBaseR = null
        adjSrcR = null
    }

    /**
     * 只清全像素直通字段，**不删磁盘文件**。
     *
     * 批量切图时旧图的 cache 文件归属已经转移到对应 EditItem，删掉会让那张图永远失去
     * 全像素导出的能力；真正该删的时机是清批次 / 退出应用（见 releaseBatchDisk）。
     */
    private fun detachFullResFields() {
        fullResFile = null
        fullResExifHead = null
        fullResWidth = 0
        fullResHeight = 0
        fullResHdr = null
    }

    // ---- 批量会话：导入 / 切换 ----

    /**
     * 多选导入：只在第一张上跑完整导入链，其余保 uri 懒加载。
     *
     * 不预先解码所有图：30 张各解一次 = 几十秒等待 + 几百 MB 峰值，而用户多数只精修一两张。
     */
    private fun beginBatch(uris: List<Uri>) {
        clearBatch()
        val taken = if (uris.size > MAX_BATCH) uris.take(MAX_BATCH) else uris
        taken.forEachIndexed { i, u -> batchItems.add(EditItem(i.toLong(), u)) }
        if (uris.size > MAX_BATCH) toast("单批最多 ${MAX_BATCH} 张，已取前 ${MAX_BATCH} 张")
        batchIndex = 0
        refreshBatchNavUi()
        loadBatchItemAt(0, firstLoad = true)
    }

    /** 清空批次（不动屏上位图：它们可能仍是当前预览，随字段/视图交接后由 GC 回收） */
    private fun clearBatch() {
        if (batchItems.isEmpty()) return
        batchItems.forEach { it.releaseDisk() }
        batchItems.clear()
        batchIndex = -1
        batchRecipe.clear()      // 换批次不能继承上一批的风格
        ++switchSeq
        refreshBatchNavUi()
    }

    /**
     * 加载批内第 [index] 张（复用既有导入链，落地时把该图保存的设置装回）。
     *
     * @param firstLoad 首张：失败顺延下一张 —— 多选里第一张是坏 RAW/坏文件时不能让整批空转
     */
    private fun loadBatchItemAt(index: Int, firstLoad: Boolean = false) {
        val item = batchItems.getOrNull(index) ?: return
        batchIndex = index
        refreshBatchNavUi()
        // 先把「继承的配方」写进条目：落地时 applySessionFields 会直接读它
        applyRecipeIfUntouched(item)
        val seq = ++switchSeq
        // 超分成片：字节才是最终工作图（重读 uri 会退回超分前），走落盘文件恢复
        if (item.srBakedStyle != null && item.srResultFile != null) {
            loadSrResult(item, seq)
            return
        }
        onImagePicked(item.uri, item, silent = false, onResult = { ok ->
            if (seq != switchSeq) return@onImagePicked
            if (!ok && firstLoad && index + 1 < batchItems.size) {
                DiagLog.w("Batch", "第 ${index + 1} 张导入失败，顺延下一张")
                loadBatchItemAt(index + 1, firstLoad = true)
            }
        })
    }

    /**
     * 切到批内第 [index] 张。首尾循环由调用方算好。
     *
     * 互斥：超分/导出/批量导出期间禁止切图 —— 超分用 originalBytes 身份比对识别「用户换图」，
     * 切走会让成片在回来后落到 else 分支被丢弃；导出则直接读字段，切图就是串数据。
     */
    private fun switchTo(index: Int) {
        if (batchItems.size <= 1 || index !in batchItems.indices || index == batchIndex) return
        if (srBusy) { toast("画质处理中，请稍候再切换"); return }
        if (exporting || batchExportRunning) { toast("导出中，请稍候再切换"); return }
        // 当前图整体打包（轻状态 + 热数据）；全像素字段先摘掉，文件归属已转移到条目
        batchItems.getOrNull(batchIndex)?.let { snapshotActiveInto(it) }
        detachFullResFields()
        // 作废在途渲染/调整：旧图结果绝不能盖到新图；派生位图随视图换图交给 GC
        ++processingGeneration
        ++liftGen
        dropAdjCache()
        liftedOriginal = null
        liftedResult = null
        val item = batchItems[index]
        batchIndex = index
        if (item.hot != null) {
            applyRecipeIfUntouched(item)
            applySessionFields(item)
            refreshAfterSwitch(rebuildThumbs = true)
            return
        }
        loadBatchItemAt(index)
    }

    /**
     * 从落盘的超分成片恢复会话。
     *
     * 超分完成后 originalBytes 被替换为成片（2× 且已重新编码）；若该图被驱逐后重读 uri，
     * 拿到的是超分**前**的字节，等于白做一次超分。故超分完成时同步落一份 cache（见 srListener）。
     */
    private fun loadSrResult(item: EditItem, seq: Int) {
        val file = item.srResultFile ?: return
        progress.visible()
        executor.execute {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            val preview = bytes?.let { decodePreview(it, previewMaxDim()) }
            val size = bytes?.let { ImageUtils.probeJpegSize(it) }
            mainHandler.post {
                progress.gone()
                if (seq != switchSeq) return@post
                if (bytes == null || preview == null || size == null) {
                    DiagLog.e("Batch", "超分成片读取失败 ${file.length()}B")
                    toast("超分成片读取失败，请重新导入该图")
                    return@post
                }
                // 成片即最终工作图：不再走导入链（否则会被引擎上限二次缩放，白丢超分收益）
                pendingHdr = null
                detachFullResFields()
                perfectBytes = item.perfectBytes
                perfectStyle = item.perfectStyle
                perfectApplied = item.perfectBytes != null
                srBakedStyle = item.srBakedStyle
                rawLin16 = null
                rawLin16Level = 0
                rawPreviewJpeg = null
                rawLinearLuma = null
                pseudoLinearAllowed = false
                pseudoLinearFromHeif = false
                originalBytes = bytes
                workWidth = size[0]
                workHeight = size[1]
                originalBitmap = preview
                resultBitmap = null
                currentStyle = null
                currentCustomId = null
                sourceUri = item.uri
                sourceIsRaw = item.sourceIsRaw
                snapshotHotInto(item)
                applySessionFields(item)
                refreshAfterSwitch(rebuildThumbs = true)
            }
        }
    }

    /**
     * 切图/冷加载落地后的 UI 刷新：把控件状态对齐当前字段。
     *
     * @param rebuildThumbs true = 缩略图池不属于本图，需要重建；false = 调用方刚跑过
     *                      [generateThumbnails]（导入落地路径），再用缓存回填一次即可
     */
    private fun refreshAfterSwitch(rebuildThumbs: Boolean) {
        txtEmpty.gone()
        btnAdjust.visible()
        adjPanel.gone()
        adjOpenState[currentForm] = false
        adjScrim?.visibility = View.GONE
        hideUndoInHeader()
        txtStyleHint.visible()
        btnSuperRes.visible()
        btnSuperResFast.visible()
        setHdrButton(hdrBoost)
        updateHdrVisibility()
        updateLumBarUi()
        updatePerfectVisibility(currentStyle)
        setPerfectButton(perfectApplied)
        if (rebuildThumbs) {
            if (thumbCache.size <= 1) {
                generateThumbnails()
            } else {
                imgByStyle.forEach { (id, iv) -> thumbCache[id]?.let { iv.setImageBitmap(it) } }
            }
        }
        // 下面是程序性还原：渲染链里的 onFilterSelected 不能把这张图误标成「用户调整过」
        applyingSession = true
        selectCardByEntry(currentEntry())
        when {
            // 完美成片：预览取成片字节（工作字节仍是原图，不能直接上屏）
            perfectBytes != null -> {
                resultBitmap = decodePreview(perfectBytes!!, previewMaxDim())
                resultBitmap?.let { imgPreview.setImageBitmap(it) }
                txtStyleHint.text = perfectStyle?.let { "${it.label} · 完美成片" } ?: "完美成片"
            }
            // 超分合成图：成片自带色彩，不再二次套色
            srBakedStyle != null -> {
                imgPreview.setImageBitmap(originalBitmap)
                txtStyleHint.text = "${srBakedStyle!!.label} · 已合成"
                recomputeAdjAsync()
            }
            // 有色彩：复用既有一致渲染链（自带 progress 与降级提示）
            hasColor() -> {
                imgPreview.setImageBitmap(originalBitmap)
                onFilterSelected(currentEntry())
            }
            else -> {
                imgPreview.setImageBitmap(originalBitmap)
                txtStyleHint.text = "原图"
                recomputeAdjAsync()
            }
        }
        applyingSession = false
        refreshBatchNavUi()
    }

    /**
     * 用户手动改了当前图的设置。
     *
     * 两件事：①该图标为「已单独调整」，此后不再跟随配方；②它成为本批的新配方，
     * 之后切到任何未调整的图都会立即套用 —— 这样「未调整的继承当前」是可见的，
     * 而不是导出瞬间才偷偷换色。
     */
    private fun onUserSettingsChanged() {
        if (applyingSession) return
        val item = batchItems.getOrNull(batchIndex) ?: return
        item.touched = true
        snapshotLightInto(item)          // 只写轻状态：滑杆拖动每帧都会走到这里
        batchRecipe.copyFrom(item)
    }

    /** 切到一张从未被单独调整过的图：自动套用本批配方（色彩 + 八档 + HDR） */
    private fun applyRecipeIfUntouched(item: EditItem) {
        if (item.touched || batchRecipe.isEmpty) return
        item.applyRecipe(batchRecipe)
    }

    /** 通知里的色彩名（批量进度「第 3/12 张 · L-M9」用） */
    private fun labelOf(item: EditItem): String =
        item.customId?.let { CustomStyleStore.byId(it)?.name }
            ?: item.styleId?.let { id ->
                ColorEngine.Style.values().firstOrNull { it.id == id }?.label
            }
            ?: "原图"

    /** 首尾循环取上一张/下一张（n<=1 时返回当前，调用方不会再触发切图） */
    private fun stepBatchIndex(delta: Int): Int {
        val n = batchItems.size
        if (n <= 1) return batchIndex.coerceAtLeast(0)
        val cur = if (batchIndex in batchItems.indices) batchIndex else 0
        return ((cur + delta) % n + n) % n
    }

    /**
     * 切换 UI 的**唯一**显隐入口。
     *
     * 三处状态必须同时表达：张数（单张全隐藏 → 像素级零变化）、序号、可用性
     * （超分/导出/批量导出期间置 0.35 透明度，与滑杆禁用同款口径）。
     */
    private fun refreshBatchNavUi() {
        val n = batchItems.size
        if (n <= 1) {
            btnPrev.gone()
            btnNext.gone()
            txtBatchIndex.gone()
            return
        }
        btnPrev.visible()
        btnNext.visible()
        txtBatchIndex.visible()
        txtBatchIndex.text = "${batchIndex + 1} / $n"
        val alpha = if (srBusy || exporting || batchExportRunning) 0.35f else 1f
        btnPrev.alpha = alpha
        btnNext.alpha = alpha
    }

    /**
     * 超分成片落盘（仅批量会话需要）。
     *
     * 单张场景下成片就在内存里，切走即丢、也不需要恢复；批量场景切走再切回必须还原，
     * 而 uri 指向的是超分前的原图，只能靠这份文件。
     */
    private fun persistSrResultForBatch(bytes: ByteArray) {
        val item = batchItems.getOrNull(batchIndex) ?: return
        executor.execute {
            val f = writeCacheBytes(bytes, "lm_sr")
            mainHandler.post {
                if (f == null) return@post
                val old = item.srResultFile
                item.srResultFile = f
                if (old != null && old != f) runCatching { old.delete() }
                DiagLog.i("Batch", "超分成片已落盘 ${f.length()}B（冷加载真相源）")
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
        // EXIF：RAW 工作字节从 Bitmap 重编码，天然没有 APP1；必须从源 DNG 逐 tag 抄一份。
        // 不回填的后果（2026-10-29 核实）：本工作字节后续所有 copyExif 的源头都是空元数据 →
        // 导出丢机型/拍摄参数/GPS，且 ExportMark 因找不到 APP1 连徽章 tag 都无处追加。
        // Orientation 强制 1：像素已由 LibRaw 摆正，沿用源值会让 normalizeExifOrientation 二次旋转。
        val work = ExifTransplant.copy(this, bytes, ImageUtils.toJpeg(base, 95), "1")
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
            // V2：抽线性亮度基准（必须在 renderWithBase 之前——它会原地改写线性缓冲）
            rawLinearLuma = RawToneMap.renderLinearLuma(buf, RAW_LUMA_MAX_DIM)
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
                                    sa: Int, co: Int, gl: Int = 0,
                                    extras: AdjustOps.Extras = AdjustOps.Extras()): ByteArray? {
        val level = rawLin16Level
        if (level == 0) return null
        // PSEUDO_LEVEL（JPG/HEIF 伪 RAW）：paramsOf(3)==null → mapInPlace 首行直接返回 false，
        // 不做 RAW 提亮曲线、也不做内嵌预览分位匹配（对已成片两者都无意义），
        // 只在 16-bit 线性域套七档 —— 这正是伪 RAW 想要的部分。
        val pseudo = level == RawToneMap.PSEUDO_LEVEL
        if (!pseudo && (!RawToneMap.isEnabled(level) || rawPreviewJpeg == null)) return null
        return try {
            // 源的取法按来源分叉：
            // · 伪 RAW：用内存里的 originalBytes（已方向归一 + 带 GPS）。**禁止重读 sourceUri**
            //   ——会拿到未归一方向的字节（伴生 JPG 横存竖拍直接横竖错），且裸 openInputStream
            //   在 Android 10+ 可能绕过 setRequireOriginal 拿到系统脱敏副本，回填时丢 GPS。
            // · 真 RAW：DNG 必须由 LibRaw 原生重解，工作字节里没有线性原始数据。
            val src: ByteArray
            val buf: java.nio.ByteBuffer
            val preview: ByteArray?
            if (pseudo) {
                src = originalBytes ?: return null
                // HEIF 源：重解码一次拿真实位深（省掉「源→JPEG→再解码」的一代损失与 8-bit 量化）。
                // 失败（厂商解码器差异 / 内存不足）退回工作字节线性化；两条都失败才整体回落 8-bit 链。
                val heifBuf = if (pseudoLinearFromHeif) {
                    sourceUri?.let { PseudoRawLinear.fromHeif(this, it) }
                } else null
                buf = heifBuf ?: PseudoRawLinear.fromWorkBytes(src) ?: return null
                DiagLog.i("PSEUDO",
                    "导出缓冲来源=${if (heifBuf != null) "HEIF 直通" else "工作字节线性化"}")
                preview = null
            } else {
                src = ImageUtils.readBytes(this, sourceUri ?: return null)
                buf = RawDecoder.nativeDecodeLinearSrgb(src) ?: return null
                preview = rawPreviewJpeg
            }
            try {
                // V2 真 HDR 增益只对真 RAW：伪线性与成品 SDR 的比值恒等 1，写进去只会让
                // synthFromLinear 白跑一遍再返回 null（等于把「无增益」当「解码失败」）。
                if (!pseudo) {
                    rawLinearLuma = RawToneMap.renderLinearLuma(buf, RAW_LUMA_MAX_DIM)
                }
                val argb = RawToneMap.renderWithTone(
                    buf, level, preview, ToneCurve.of(br, ev, hl, sh, bl, sa, co)) ?: return null
                val w = argb[0]
                val h = argb[1]
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(argb, 2, w, 0, 0, w, h)
                if (gl != 0) SoftGlow.applyInPlace(bmp, gl)
                // 伪 RAW 输出重建：M4 detail 重建 → M6 色度去振铃 → M3 蓝噪声抖动。
                // 只对伪 RAW 生效（真 RAW 有 16-bit 数据，套这些补偿算子属于画蛇添足）；
                // 预览链 recomputeAdjAsync 调用同一函数与同一组参数，保证预览与成片同源。
                if (pseudo) {
                    PseudoRawPostProcess.apply(
                        bmp, PseudoRawPostProcess.estimateGain(br, ev, sh)
                    )
                }
                // 新维度：与预览链同序（柔光/伪 RAW 重建之后），单次 encode 前落地
                if (!extras.isNeutral) {
                    val out2 = AdjustOps.applyExtras(bmp, extras)
                    if (out2 !== bmp) bmp.recycle()
                    // 后续编码改用 out2；为保持「finally 回收」单出口，这里把像素写回 bmp
                    if (out2 !== bmp) {
                        val px = IntArray(bmp.width * bmp.height)
                        out2.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        out2.recycle()
                    }
                }
                val jpeg = try { ImageUtils.toJpeg(bmp, 95) } finally { bmp.recycle() }
                // EXIF：toJpeg 从 Bitmap 重编码不写 APP1；不回填则下游 copyExif 的源头是空元数据，
                // 导出丢机型/拍摄参数/GPS，且 ExportMark 找不到 APP1 连徽章 tag 也无处追加。
                // 源优先用 originalBytes（已方向归一 + 带 GPS），真 RAW 兜底用重读的 DNG 字节；
                // Orientation 强制 1（像素已摆正）。
                MadridLegendary.patchExifOrientationToOne(
                    MadridLegendary.copyExif(originalBytes ?: src, jpeg))
            } finally {
                // 只有 LibRaw 的线性缓冲需要显式释放；伪 RAW 的 direct buffer 交给 GC
                if (!pseudo) RawDecoder.nativeFreeLinear(buf)
            }
        } catch (t: Throwable) {
            DiagLog.e("Export",
                (if (pseudo) "伪 RAW 16bit 七档重放失败，回落 8bit 链"
                else "RAW 16bit 七档重放失败，回落 8bit 链"), t)
            null
        }
    }

    /**
     * 首次进入的一次性免责声明：非商业学习用途、算法与模型均为社区公开资料整合
     * （开发者非作者）、异议即删。不可取消绕过，点「我已知晓」落盘后永不再弹
     * （复刻 [showRawHintIfNeeded] 的一次性标记先例）。
     */
    private fun maybeShowDisclaimer() {
        if (SettingsStore.isDisclaimerShown(this)) return
        SettingsStore.markDisclaimerShown(this)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("使用须知")
            .setMessage(
                "本应用由个人开发者免费制作与分享，仅供个人学习、交流与非商业使用；" +
                    "请勿有偿转发、捆绑销售或用于任何商业用途。\n\n" +
                    "应用内的色彩风格、处理算法与神经网络模型均整理自社区公开资料，" +
                    "开发者仅作整合与调用，并非其作者，亦不持有相关权利，权利归原作者所有；" +
                    "对其效果不作任何承诺。请勿将处理结果用于冒充特定机型的原始直出照片。\n\n" +
                    "本应用仅供学习研究，如内容涉及您的权益，请联系我移除。" +
                    "请仅处理您拥有合法权益的图片；AI 增强可能生成原图中不存在的细节，" +
                    "重要用途请自行甄别，使用本应用产生的结果由您自行承担。"
            )
            .setCancelable(false)
            .setPositiveButton("我已知晓", null)
            .show()
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
        ColorEngine.Style.UI_VISIBLE.forEach { style ->
            executor.execute {
                try {
                    // 缩略图不触发 CPU 降级链（导入期连发 4 个子进程压力大且无意义，用 LUT 近似即可）
                    val out = ColorEngine.process(this, ImageUtils.toJpeg(thumb, 90), style,
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
        onUserSettingsChanged()   // 批量：本张已单独调整，并成为新配方（程序性还原时自动跳过）
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
                val out = ColorEngine.process(this, work, style, onFallback = { st ->
                    DiagLog.w("Engine", "${st.label} 触发降级")
                    mainHandler.post {
                        if (ColorEngine.Style.isCcd(st)) {
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
                            } else if (lutStrength != 100) {
                                // 风格强度 <100（AI 力度可能已下调）：渲染完成后按强度混合
                                refreshPreviewImage()
                                recomputeAdjAsync()
                            } else {
                                imgPreview.setImageBitmap(bmp)
                            }
                        }
                        // M3 语义对齐其它风格：兜底接管/异常才 toast，monopan 正常保持安静（诊断全量在诊断日志）
                        if (style == ColorEngine.Style.M3) {
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
        onUserSettingsChanged()   // 批量：本张已单独调整（程序性还原时自动跳过）
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
                            } else if (lutStrength != 100) {
                                // 风格强度 <100（AI 力度可能已下调）：渲染完成后按强度混合
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
                            } else if (lutStrength != 100) {
                                // 风格强度 <100（AI 力度可能已下调）：渲染完成后按强度混合
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
    /** 0..100 百分比滑杆（色彩强度/黑白/颗粒/暗角）：无 ±10 偏移，数值直接显示。 */
    private fun setupPercentSlider(
        sb: SeekBar,
        valueText: TextView,
        get: () -> Int,
        def: Int,
        set: (Int) -> Unit
    ) {
        sb.max = 100
        sb.progress = get().coerceIn(0, 100)
        applySliderSkin(sb)
        valueText.paint.fontFeatureSettings = "tnum"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                set(progress)
                valueText.text = "$progress"
                styleVal(valueText, progress, def)
                dimIndexById[s.id]?.let { dimUserTouched[it] = true }
                onUserSettingsChanged()   // 批量：这张被单独调整，同时成为本批新配方（与 setupSlider 同口径）
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                sliderDragging = true   // 拖动中：后续重算走低清快速通道
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                sliderDragging = false
                fastPending = false
                recomputeAdjAsync()     // 松手：全尺寸精修
            }
        })
        valueText.text = "${get()}"
        styleVal(valueText, get(), def)
    }

    /** 新维度统一收口：字段由调用方写入，这里只做 UI 回写 + 单次重算（不连发 14 次）。 */
    private fun applyExtraChange() {
        updateLumBarUi()
        updateAdjustEntryText()
        recomputeAdjAsync()
    }

    /** 拖动快速代理：基准 1/3 尺寸缓存副本（基准位图不变时不重缩放） */
    private fun fastProxyOf(src: Bitmap?, isOrig: Boolean): Bitmap? {
        if (src == null) return null
        val w = (src.width / 3).coerceAtLeast(1)
        val h = (src.height / 3).coerceAtLeast(1)
        val key = if (isOrig) fastProxyOKey else fastProxyRKey
        var cached = if (isOrig) fastProxyO else fastProxyR
        if (cached == null || cached.isRecycled || key !== src || cached.width != w || cached.height != h) {
            cached?.recycle()
            cached = Bitmap.createScaledBitmap(src, w, h, true)
            if (isOrig) { fastProxyO = cached; fastProxyOKey = src }
            else { fastProxyR = cached; fastProxyRKey = src }
        }
        return cached
    }

    /**
     * 一键重置：把 14 个调节维度的字段一次性清零，再做**一次**重算。
     * 不连调 14 次 applyXxxChange——那会触发 14 次重算，界面会闪。
     */
    private fun resetAllAdjustments() {
        // 重置会解除全部接管标记：撤销快照连带存一份，撤销时才能还原
        adjUndoSnapshot = currentParamsJson().apply {
            put("touched", JSONArray(dimUserTouched.toList()))
        }
        brightLevel = 0; exposureLevel = 0; highlightLevel = 0; shadowLevel = 0
        blackLevel = 0; satLevel = 0; contrastLevel = 0; glowLevel = 0
        wbTemp = 0; wbTint = 0; monoLevel = 0; grainLevel = 0; vignetteLevel = 0
        lutStrength = 100
        java.util.Arrays.fill(dimUserTouched, false)   // 全部重置 = 解除全部接管锁定
        aiResultHint = null
        updateLumBarUi()
        updateAdjustEntryText()
        recomputeAdjAsync()
        showUndoInHeader()
    }

    /** 面板 chrome：重置/收起接线 + 三分段页签（影调|色彩|质感，运行时按滑杆 id 归组）。 */
    private fun setupAdjPanelChrome() {
        if (!this::adjPanel.isInitialized) return
        findViewById<TextView>(R.id.btnAdjReset)?.setOnClickListener { resetAllAdjustments() }
        findViewById<ImageView>(R.id.btnAdjClose)?.setOnClickListener { toggleAdjPanel() }
        // 点击面板外收起：透明遮罩盖住预览区（z 序在面板之下），随面板显隐
        val scrim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnClickListener { if (adjPanel.visibility == View.VISIBLE) toggleAdjPanel() }
            visibility = View.GONE
        }
        adjScrim = scrim
        (adjPanel.parent as? ViewGroup)?.addView(
            scrim, (adjPanel.parent as ViewGroup).indexOfChild(adjPanel)
        )
        // 滑杆 id → 维度索引：用户手调时打接管标记（AI 默认跳过被接管的维度）
        dimIndexById = mapOf(
            R.id.sbBright to 0, R.id.sbLum to 1, R.id.sbHigh to 2, R.id.sbShadow to 3,
            R.id.sbBlack to 4, R.id.sbSat to 5, R.id.sbCon to 6, R.id.sbGlow to 7,
            R.id.sbTemp to 8, R.id.sbTint to 9, R.id.sbMono to 10,
            R.id.sbGrain to 11, R.id.sbVignette to 12, R.id.sbLutStr to 13
        )

        val pages = findViewById<ViewGroup>(R.id.adjPages) ?: return
        // 行归组：找不到滑杆 id 的行（如撤销条）忽略
        fun seekId(v: View): Int {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val id = seekId(v.getChildAt(i))
                    if (id != View.NO_ID) return id
                }
            }
            return if (v is SeekBar) v.id else View.NO_ID
        }
        for (i in 0 until pages.childCount) {
            val row = pages.getChildAt(i)
            when (seekId(row)) {
                R.id.sbBright, R.id.sbLum, R.id.sbHigh, R.id.sbShadow, R.id.sbBlack, R.id.sbCon ->
                    adjRowsTone.add(row)
                R.id.sbSat, R.id.sbLutStr, R.id.sbTemp, R.id.sbTint ->
                    adjRowsColor.add(row)
                R.id.sbGlow, R.id.sbGrain, R.id.sbVignette, R.id.sbMono ->
                    adjRowsTexture.add(row)
            }
        }

        // 页签条：插在标题行与内容区之间
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }
        val defs = listOf("影调" to 0, "色彩" to 1, "质感" to 2)
        val tabs = ArrayList<TextView>()
        defs.forEach { (label, idx) ->
            val tv = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { rightMargin = dp(6) }
                setOnClickListener { selectAdjPage(idx) }
            }
            bar.addView(tv)
            tabs.add(tv)
        }
        adjPanel.addView(bar, 1)
        adjTabViews = tabs
        selectAdjPage(adjPageIdx)
    }

    /** 切页：行显隐 + 页签选中态（胶囊底 + 加粗白字），记住上次停留页。 */
    private fun selectAdjPage(idx: Int) {
        adjPageIdx = idx
        if (adjTabViews.isEmpty()) return
        adjTabViews.forEachIndexed { i, tv ->
            val sel = i == idx
            tv.setTextColor(Color.parseColor(if (sel) "#F2F2F2" else "#8E8E93"))
            tv.typeface = Typeface.create(Typeface.DEFAULT, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.background =
                if (sel) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.adj_tab_selected)
                else null
        }
        listOf(adjRowsTone, adjRowsColor, adjRowsTexture).forEachIndexed { gi, rows ->
            rows.forEach { it.visibility = if (gi == idx) View.VISIBLE else View.GONE }
        }
        // 切页后内容高度变了：重新钳制滚动区，避免底部留白
        findViewById<android.widget.ScrollView>(R.id.adjScroll)?.post { clampAdjScroll() }
    }

    /** 滚动区高度 = min(内容高, 档位上限屏高比)：内容不满时面板贴合内容，不留空腔 */
    private fun clampAdjScroll() {
        if (adjPanel.visibility != View.VISIBLE) return
        val sc = findViewById<android.widget.ScrollView>(R.id.adjScroll) ?: return
        val pages = findViewById<ViewGroup>(R.id.adjPages) ?: return
        val maxH = (resources.displayMetrics.heightPixels * FormFactor.panelMaxFraction(currentForm)).toInt()
        val contentH = pages.height
        val target = if (contentH in 1..maxH) contentH else maxH
        if (sc.layoutParams.height != target) {
            sc.layoutParams = sc.layoutParams.apply { height = target }
            sc.requestLayout()
        }
    }

    /** 重置后的 5 秒撤销：显示在标题行「调节」右侧（撤销与重置物理隔离防误触），超时自动消失。 */
    private fun showUndoInHeader() {
        val msg = findViewById<TextView>(R.id.adjUndoMsg) ?: return
        val btn = findViewById<TextView>(R.id.adjUndoBtn) ?: return
        undoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        msg.visibility = View.VISIBLE
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            hideUndoInHeader()
            adjUndoSnapshot?.let { restoreParams(it) }
            adjUndoSnapshot = null
        }
        undoHideRunnable = Runnable {
            msg.visibility = View.GONE
            btn.visibility = View.GONE
        }
        mainHandler.postDelayed(undoHideRunnable!!, 5000)
    }

    private fun hideUndoInHeader() {
        undoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        undoHideRunnable = null
        findViewById<TextView>(R.id.adjUndoMsg)?.visibility = View.GONE
        findViewById<TextView>(R.id.adjUndoBtn)?.visibility = View.GONE
    }

    /** 自绘滑杆皮肤：3dp 细轨 + 白圆点拇指（替代系统粗轨，观感与预览胶囊一致） */
    private fun applySliderSkin(sb: SeekBar) {
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.adj_track)?.let {
            sb.progressDrawable = it
        }
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.adj_thumb)?.let {
            sb.thumb = it
        }
        sb.splitTrack = false
    }

    /** 数值列状态色：偏离默认值时点亮成白，默认值时退灰——一眼看出哪些动过 */
    private fun styleVal(tv: TextView, v: Int, def: Int) {
        tv.setTextColor(Color.parseColor(if (v != def) "#F2F2F2" else "#8E8E93"))
    }

    private fun setupSlider(sb: SeekBar, valueText: TextView, get: () -> Int, set: (Int) -> Unit) {
        sb.max = 20
        sb.progress = get() + 10
        applySliderSkin(sb)
        valueText.paint.fontFeatureSettings = "tnum"   // 等宽数字：数值列不跳动
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progressLevel: Int, fromUser: Boolean) {
                if (fromUser) {
                    set(progressLevel - 10)
                    dimIndexById[s.id]?.let { dimUserTouched[it] = true }   // 接管标记：AI 不再覆盖
                    onUserSettingsChanged()   // 批量：这张被单独调整，同时成为本批新配方
                }
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                sliderDragging = true   // 拖动中：后续重算走低清快速通道
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                sliderDragging = false
                fastPending = false
                recomputeAdjAsync()     // 松手：全尺寸精修
            }
        })
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!srBusy) {
                    set(0)
                    onUserSettingsChanged()   // 双击复位也是一次用户调整
                }
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
        // 拖动中：低清快速通道 + 33ms 节流；松手/AI/重置：全尺寸精修
        val isFast = sliderDragging
        if (isFast) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFastAt < 33L) { fastPending = true; return }
            lastFastAt = now
        }
        val gen = ++liftGen
        val br = brightLevel
        val ev = exposureLevel
        val hl = highlightLevel
        val sh = shadowLevel
        val bl = blackLevel
        val sa = satLevel
        val co = contrastLevel
        val gl = glowLevel
        val extras = AdjustOps.Extras(wbTemp, wbTint, monoLevel, grainLevel, vignetteLevel)
        if (br == 0 && ev == 0 && hl == 0 && sh == 0 && bl == 0 && sa == 0 && co == 0 &&
            gl == 0 && extras.isNeutral && lutStrength == 100
        ) {
            // 先置空调整字段再换显示：refreshPreviewImage 才会取基图，
            // 避免把即将回收的调整版重新 setImageBitmap 上屏（否则下一帧画已回收位图崩溃）
            val oldO = liftedOriginal
            val oldR = liftedResult
            liftedOriginal = null
            liftedResult = null
            refreshPreviewImage()
            fun safeRecycleEarly(b: Bitmap?) {
                if (b == null || b.isRecycled) return
                if (b === adjBaseO || b === adjBaseR || b === fastProxyO || b === fastProxyR) return
                if (b === resultBitmap || b === originalBitmap) return
                b.recycle()
            }
            safeRecycleEarly(oldO)
            safeRecycleEarly(oldR)
            return
        }
        val origBase = originalBitmap
        val resultBase = resultBitmap
        adjExecutor.execute {
            // 排队期间已过期（连点加减档/换图）：直接放弃，不做全量重算
            if (gen != liftGen) return@execute
            val t0 = android.os.SystemClock.uptimeMillis()
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
                    // 与导出链同源：伪 RAW 时套同一套输出重建（M4→M6→M3）。
                    // 不同步会出现「拖动滑杆时预览一个样、导出后另一个样」，这是最容易被当成 bug 的形态。
                    val cur = lo
                    if (cur != null && rawLin16Level == RawToneMap.PSEUDO_LEVEL) {
                        PseudoRawPostProcess.apply(
                            cur, PseudoRawPostProcess.estimateGain(br, ev, sh)
                        )
                    }
                    // RAW 16bit 路径同样叠新维度（与 8bit 同序同语义），保证 RAW/JPG 一致
                    lo = lo?.let { old ->
                        AdjustOps.applyExtras(old, extras).also { if (it !== old) old.recycle() }
                    }
                } catch (t: Throwable) {
                    DiagLog.e("Lum", "RAW 16bit 调整失败，回落 8bit", t)
                    lo?.recycle(); lo = null
                }
            }
            if (lo == null) {
                // 8-bit 路径：基图可能在排队期间被主线程回收，scaleBitmap 有竞态窗口，兜一层
                val bFullO = try { ensureAdjBase(origBase, true) } catch (t: Throwable) {
                    DiagLog.e("Lum", "原图基准准备失败", t); null
                } ?: return@execute
                val bFullR = try { ensureAdjBase(resultBase, false) } catch (t: Throwable) {
                    DiagLog.e("Lum", "结果基准准备失败", t); null
                }
                // 快速通道：基准再降 1/3（带缓存，基准不变时不重复缩放）
                val bO = if (isFast) fastProxyOf(bFullO, true) ?: bFullO else bFullO
                val bR = if (isFast) fastProxyOf(bFullR, false) else bFullR
                val params = ToneCurve.of(br, ev, hl, sh, bl, sa, co)
                // LUT 强度 <100：把「风格基准」往「原图」方向线性拉回，再进曲线域
                // （等价于 LUT 以该强度施加，曲线档位不受影响）
                val bR2 = if (lutStrength < 100 && bR != null) {
                    try { AdjustOps.blendLut(bO, bR, lutStrength) } catch (t: Throwable) { bR }
                } else bR
                lo = try {
                    ToneCurve.applyToBitmap(bO, params)
                } catch (t: Throwable) {
                    DiagLog.e("Lum", "原图调整失败", t); null
                }
                lr = try {
                    bR2?.let { ToneCurve.applyToBitmap(it, params) }
                } catch (t: Throwable) {
                    DiagLog.e("Lum", "结果调整失败", t); null
                }
                // lutStrength<100 且曲线档全 0 时 lo 会是基准本体（或快速代理）：
                // liftedOriginal 会持有它并被下一轮 recycle——必须复制成独立位图
                if (lutStrength < 100 && lo != null && lo === bO) {
                    lo = lo.copy(Bitmap.Config.ARGB_8888, true)
                }
                // 混合产物用完即弃（lr 与其同实例时保留，那是当前工作位图）
                if (bR2 !== bR && lr !== bR2) bR2?.recycle()
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
                // 新维度（色温/色调/黑白/颗粒/暗角）：曲线与柔光之后统一叠加。
                // 若 lo/lr 仍是基准位图本体（八档全 0），先抄副本再原地改，避免烘进全局基准。
                if (!extras.isNeutral) {
                    if (lo != null && lo === bO) lo = lo.copy(Bitmap.Config.ARGB_8888, true)
                    if (lr != null && bR != null && lr === bR) lr = lr.copy(Bitmap.Config.ARGB_8888, true)
                    lo = lo?.let { old -> AdjustOps.applyExtras(old, extras).also { if (it !== old) old.recycle() } }
                    lr = lr?.let { old -> AdjustOps.applyExtras(old, extras).also { if (it !== old) old.recycle() } }
                }
            }
            DiagLog.w("AdjPerf", "fast=$isFast total=${android.os.SystemClock.uptimeMillis() - t0}ms")
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
                // 防御性回收：缓存基准/代理/当前工作位图绝不能回收（异常路径漏拷时交给缓存自愈）
                fun safeRecycle(b: Bitmap?) {
                    if (b == null || b.isRecycled) return
                    if (b === adjBaseO || b === adjBaseR || b === fastProxyO || b === fastProxyR) return
                    if (b === resultBitmap || b === originalBitmap) return
                    b.recycle()
                }
                safeRecycle(oldO)
                safeRecycle(oldR)
                // 节流期间被合并的拖动事件：补一次快速重算，直到拖动结束触发全尺寸精修
                if (fastPending && sliderDragging && isFast) {
                    fastPending = false
                    mainHandler.postDelayed({ recomputeAdjAsync() }, 34L)
                }
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

        // 色温 / 色调（±10 档，与八档同规格）
        if (this::sbTemp.isInitialized) {
            txtTempVal.text = if (wbTemp > 0) "+$wbTemp" else "$wbTemp"
            sbTemp.progress = wbTemp + 10
            sbTemp.isEnabled = !srBusy
            sbTemp.alpha = if (srBusy) 0.35f else 1f

            txtTintVal.text = if (wbTint > 0) "+$wbTint" else "$wbTint"
            sbTint.progress = wbTint + 10
            sbTint.isEnabled = !srBusy
            sbTint.alpha = if (srBusy) 0.35f else 1f

            // 百分比四项
            txtLutStrVal.text = "$lutStrength"
            sbLutStr.progress = lutStrength
            sbLutStr.isEnabled = !srBusy
            sbLutStr.alpha = if (srBusy) 0.35f else 1f

            txtMonoVal.text = "$monoLevel"
            sbMono.progress = monoLevel
            sbMono.isEnabled = !srBusy
            sbMono.alpha = if (srBusy) 0.35f else 1f

            txtGrainVal.text = "$grainLevel"
            sbGrain.progress = grainLevel
            sbGrain.isEnabled = !srBusy
            sbGrain.alpha = if (srBusy) 0.35f else 1f

            txtVignetteVal.text = "$vignetteLevel"
            sbVignette.progress = vignetteLevel
            sbVignette.isEnabled = !srBusy
            sbVignette.alpha = if (srBusy) 0.35f else 1f

            // 数值状态色：动过的白、未动的灰（14 维统一口径）
            styleVal(txtBrightVal, brightLevel, 0)
            styleVal(txtLumVal, exposureLevel, 0)
            styleVal(txtHighVal, highlightLevel, 0)
            styleVal(txtShadowVal, shadowLevel, 0)
            styleVal(txtBlackVal, blackLevel, 0)
            styleVal(txtSatVal, satLevel, 0)
            styleVal(txtConVal, contrastLevel, 0)
            styleVal(txtGlowVal, glowLevel, 0)
            styleVal(txtTempVal, wbTemp, 0)
            styleVal(txtTintVal, wbTint, 0)
            styleVal(txtMonoVal, monoLevel, 0)
            styleVal(txtGrainVal, grainLevel, 0)
            styleVal(txtVignetteVal, vignetteLevel, 0)
            styleVal(txtLutStrVal, lutStrength, 100)

            // 重置按钮只在有调整时出现：空面板上少一个灰按钮，头部更干净
            findViewById<TextView>(R.id.btnAdjReset)?.visibility =
                if (hasAdjustments()) View.VISIBLE else View.GONE
        }
    }

    /** 调节面板开合：三档统一浮层行为（遮罩 + 220ms 上滑淡入），高度上限按档位系数 */
    private fun toggleAdjPanel() {
        val showing = adjPanel.visibility != View.VISIBLE
        adjOpenState[currentForm] = showing
        adjPanel.visibility = if (showing) View.VISIBLE else View.GONE
        adjScrim?.visibility = if (showing) View.VISIBLE else View.GONE
        if (!showing) hideUndoInHeader()
        val sc = findViewById<android.widget.ScrollView>(R.id.adjScroll)
        if (sc != null) {
            sc.layoutParams = sc.layoutParams.apply {
                height = if (showing) (resources.displayMetrics.heightPixels * FormFactor.panelMaxFraction(currentForm)).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            if (showing) {
                sc.scrollTo(0, 0)
                // 内容不足上限时收缩到内容高度，消除面板底部大片留白
                sc.post { clampAdjScroll() }
            }
        }
        if (showing) {
            adjPanel.translationY = 48f
            adjPanel.alpha = 0f
            adjPanel.animate().translationY(0f).alpha(1f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /** 入口胶囊文字：无调整时「调节」，有调整时附带生效项数（14 维口径，强度 100 算默认） */
    private fun updateAdjustEntryText() {
        var n = listOf(
            brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel,
            satLevel, contrastLevel, glowLevel, wbTemp, wbTint, monoLevel,
            grainLevel, vignetteLevel
        ).count { it != 0 }
        if (lutStrength != 100) n++
        btnAdjust.text = if (n == 0) "调节" else "调节 · $n"
    }

    // ---- 超分 ----

    private var srStartedBytes: ByteArray? = null   // 任务启动时的输入快照，Done 时校验身份
    private var srStartedStyle: ColorEngine.Style? = null // 超分前选中的色彩，完成后保持重应用
    private var srBakedStyle: ColorEngine.Style? = null   // 已合成进当前图的色彩（导出跳过二次套色）
    private var srStartedFast: Boolean = false             // 本次超分是否快速模型
    private var srStartedPerfect: Boolean = false          // 本次画质任务是否为完美档
    private var perfectBytes: ByteArray? = null            // 完美成片字节（非空 = 完美已应用）
    private var perfectStyle: ColorEngine.Style? = null    // 完美成片所用风格
    private var perfectApplied = false                     // 完美档已应用（按钮高亮）
    private var hdrBoost = false                           // HDR 输出开关：导出时合成 UltraHDR 增益图

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
                btnHdr.isEnabled = false
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
                    resultBitmap = decodePreview(st.jpeg, previewMaxDim())
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
                    // 批量：成片落一份 cache 作为「冷加载真相源」——切走再切回时若重读 uri，
                    // 拿到的是超分前的字节，等于白做一次超分
                    persistSrResultForBatch(st.jpeg)
                    // 工作字节已被超分成片替换：200MP 全像素直通源与之不再对应，作废
                    clearFullRes()
                    // 源增益图是原图尺寸，超分成片是 2× 且重新编码（不再有 HDR 容器），
                    // rebake 已关闭无法重标定 → 保留会得到「主图放大、增益图原尺寸」的错配
                    // （读端按 UltraHDR 渲染会发灰/高光断层）。直接作废，需要 HDR 时由按钮重新合成。
                    pendingHdr = null
                    updateHdrVisibility()
                    // 图已被替换为超分成片，完美态随之失效（避免重复套色/高亮错位）
                    perfectApplied = false
                    perfectBytes = null
                    perfectStyle = null
                    setPerfectButton(false)
                    originalBitmap?.recycle()
                    originalBitmap = decodePreview(st.jpeg, previewMaxDim())
                    // 源已是超分 JPEG，RAW 16-bit 窗口与线性亮度基准一并作废
                    rawLin16 = null
                    rawLin16Level = 0
                    rawPreviewJpeg = null
                    rawLinearLuma = null
                    // 成片与源容器已无尺寸对应，禁止导出时再按 HEIF 源重解码
                    pseudoLinearFromHeif = false
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

    /**
     * 预览解码长边上限：按窗口对角线动态取值（平板大屏 + 双指放大不显糊）。
     * 手机档自然落在下限 2048（现状档位）；平板横屏 ~2600。
     * 注意 decodePreview 的 inSampleSize 是 2 的幂，多数源图实际解码尺寸不受影响，
     * 仅中间尺寸源（约 4000~6400px）会解码到更高档，largeHeap 下内存可控。
     */
    private fun previewMaxDim(): Int {
        val dm = resources.displayMetrics
        val diag = kotlin.math.hypot(dm.widthPixels.toDouble(), dm.heightPixels.toDouble())
        return (diag * 1.05).toInt().coerceIn(2048, 2600)
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
    private fun updatePerfectVisibility(style: ColorEngine.Style?) {
        val show = style == ColorEngine.Style.M9 || style == ColorEngine.Style.M9P ||
                style == ColorEngine.Style.M10R
        if (show) btnPerfect.visible() else btnPerfect.gone()
    }

    /** 画质钮与 HDR 开关复位（启用 + 常态文案）。 */
    private fun resetQualityButtons() {
        btnSuperRes.isEnabled = true
        btnSuperRes.text = "光学超分"
        btnSuperResFast.isEnabled = true
        btnSuperResFast.text = "快速超分"
        btnPerfect.isEnabled = true
        btnHdr.isEnabled = true
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

    // ---- HDR 输出（导出时合成 UltraHDR 增益图；纯算法，无模型）----

    /**
     * V2：取 RAW 线性亮度基准（必要时触发一次额外解码）。
     *
     * 提亮/强力档（level 1/2）在导入与 16bit 重放时已顺手抽过，直接复用；
     * 标准档（level 0）走老 `nativeDecodeWithThr` 路径、完全没有线性数据，
     * 这里重读源 DNG 解一次，**仅用于算增益，不改主图**（约 0.8~1.2s / 12.5MP）。
     *
     * 任何异常返回 null，调用方退回 V1 猜测式或纯 SDR —— 绝不影响导出本身。
     */
    private fun ensureRawLinearLuma(need: Boolean): RawToneMap.Luma16? {
        if (!need || !sourceIsRaw) return null
        rawLinearLuma?.let { return it }
        val uri = sourceUri ?: return null
        val t0 = System.currentTimeMillis()
        return try {
            val src = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val buf = RawDecoder.nativeDecodeLinearSrgb(src) ?: return null
            try {
                val luma = RawToneMap.renderLinearLuma(buf, RAW_LUMA_MAX_DIM)
                rawLinearLuma = luma
                DiagLog.i("HDR", "标准档补偿解码 ${luma?.w}x${luma?.h} 耗时 ${System.currentTimeMillis() - t0}ms")
                luma
            } finally {
                RawDecoder.nativeFreeLinear(buf)
            }
        } catch (t: Throwable) {
            DiagLog.w("HDR", "标准档补偿解码失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * 导出收尾的 HDR 数据解析（工作版三站共用）：
     * 源图自带增益图优先（按成品 SDR 重标定 C3）> HDR 开关（从成品 SDR 合成）> 无。
     *
     * 必须在工作线程内、对**最终 SDR 主图**调用：任何中途生成都会因后续色调变化而失配，
     * 这正是 C3 重标定翻车的根因。HdrGainMap 内部吞异常返回 null，
     * 调用方据此走 truncateToPrimary 退回纯 SDR（不新增任何失败模式）。
     *
     * @param srcHdr 源图自带增益图（pendingHdr / fullResHdr）
     * @param srcSdr 源 SDR 工作字节，仅 rebake 需要（作增益图的分母来源）
     * @param outSdr 成品 SDR（套色与滑杆之后）
     * @param hdrOn 开关快照：由调用方在进线程前取，避免导出途中改 UI 导致半途变语义
     * @param rawLuma RAW 线性亮度基准（V2）；非空时走真增益，null 走 V1 猜测式
     */
    private fun resolveHdrForExport(
        srcHdr: HdrGainMap.HdrData?, srcSdr: ByteArray?, outSdr: ByteArray,
        hdrOn: Boolean, rawLuma: RawToneMap.Luma16? = null
    ): HdrGainMap.HdrData? {
        if (srcHdr != null) {
            return if (srcSdr != null) HdrGainMap.rebakeJpeg(srcSdr, outSdr, srcHdr) ?: srcHdr
            else srcHdr
        }
        if (!hdrOn) return null
        // V2 优先：RAW 有线性亮度基准时走真增益（实测压缩量），失败再退回 V1 猜测式
        if (rawLuma != null) {
            val lvl = SettingsStore.hdrBoostLevel(this)
            val d = HdrGainMap.synthFromLinear(rawLuma, outSdr, SettingsStore.rawHdrStrength(lvl))
            DiagLog.i(
                "Export",
                if (d != null) "HDR 合成(线性)成功 增益图 ${d.gainmapJpeg.size}B 区间[${d.minLog2},${d.maxLog2}]"
                else "HDR 线性合成跳过（有效高光不足/几何不匹配）→ 退回 V1 猜测式"
            )
            if (d != null) return d
        }
        val d = HdrGainMap.synth(outSdr, SettingsStore.hdrBoostLevel(this))
        DiagLog.i(
            "Export",
            if (d != null) "HDR 合成成功 增益图 ${d.gainmapJpeg.size}B 区间[${d.minLog2},${d.maxLog2}]"
            else "HDR 合成跳过（高光不足或异常）→ 按纯 SDR 导出"
        )
        return d
    }

    /**
     * HDR 按钮可见性：**只有源图自带 HDR 容器时隐藏**。
     *
     * 自带 HDR 时导出链已自动回嵌原增益图，再合成一张属于语义重复。
     * RAW 自 V2 起放开：虽然工作字节是 8bit SDR，但导入期已抽好 16bit 线性亮度基准，
     * 导出时用 `g = log2(L_lin/L_sdr)` 得到**真实**压缩量，而不是 V1 的直方图猜测。
     */
    private fun updateHdrVisibility() {
        val hasSourceHdr = pendingHdr != null || fullResHdr != null
        if (hasSourceHdr) btnHdr.gone() else btnHdr.visible()
    }

    /** HDR 按钮外观：已开启 = 亮底黑字 + ✓；未开启 = 常态玻璃底白字。 */
    private fun setHdrButton(applied: Boolean) {
        if (applied) {
            btnHdr.setBackgroundResource(R.drawable.bg_pill_active)
            btnHdr.setTextColor(Color.BLACK)
            btnHdr.text = "HDR ✓"
        } else {
            btnHdr.setBackgroundResource(R.drawable.bg_pill)
            btnHdr.setTextColor(Color.WHITE)
            btnHdr.text = "HDR"
        }
    }

    /**
     * 点击 HDR：已开启 → 直接关闭；未开启 → **首次**弹说明，之后直接开启（不再打扰）。
     * 「是否已看过说明」由 [SettingsStore.isHdrHintShown] 持久化，跨重启有效。
     * 关闭是自己刚做的动作，任何时候都不弹窗。
     */
    private fun onHdrClicked() {
        if (exporting) { toast("全像素导出进行中，请稍候"); return }
        if (srBusy) { toast("处理中，请稍候"); return }
        if (hdrBoost) {
            hdrBoost = false
            setHdrButton(false)
            SettingsStore.setHdrDefaultOn(this, false)   // 记忆本次关闭
            onUserSettingsChanged()                      // 批量：HDR 开关属于本张的独立设置
            toast("HDR 输出已关闭")
            return
        }
        if (SettingsStore.isHdrHintShown(this)) enableHdr() else confirmHdr()
    }

    /** 开启 HDR 的唯一出口：弹窗正按钮与「已看过说明」的快捷路径共用，避免两处状态漂移 */
    private fun enableHdr() {
        hdrBoost = true
        setHdrButton(true)
        SettingsStore.setHdrDefaultOn(this, true)        // 记忆本次开启，下次导入自动带 HDR
        onUserSettingsChanged()                          // 批量：HDR 开关属于本张的独立设置
        toast("已开启：导出时写入 HDR 数据")
    }

    /**
     * HDR 说明弹窗：标题 + 首行红色提示 + 正文 + 「开启 / 取消」，
     * 与 [confirmSuperRes] 完全同构（MaterialAlertDialogBuilder + SpannableString 染色）。
     *
     * 展示即标记已读（点「取消」也算）：用户要的是「只首次弹出」，
     * 反复用同一段长文案拦人比缺少第二次说明更糟。
     */
    private fun confirmHdr() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("HDR 输出")
            .setMessage(buildHdrMessage())
            .setPositiveButton("开启") { _, _ -> enableHdr() }
            .setNegativeButton("取消", null)
            .show()
        SettingsStore.markHdrHintShown(this)
    }

    /** HDR 说明正文：首行红色警示（超分那里是耗时警告，这里是「非对称预期」警告） */
    private fun buildHdrMessage(): CharSequence {
        val red = "仅支持的设备与相册能显示 HDR 效果，其他设备显示为普通照片\n"
        val levelName = SettingsStore.hdrBoostLevelName(SettingsStore.hdrBoostLevel(this))
        val rest = (if (sourceIsRaw) {
            "源为 RAW：增益来自传感器线性数据，是真实还原，而非从成片估算。\n\n"
        } else "") +
            "导出时会在 JPG 里额外写入一张增益图（UltraHDR 标准）。\n" +
            "支持的设备会按 HDR 显示：高光更通透、亮部层次更完整。\n\n" +
            "主图与现在的导出完全一致，不改变色彩与影调。\n" +
            "当前强度：$levelName（在「设置 → 导出 → HDR 强度」修改）\n" +
            (if (sourceIsRaw) "RAW 的档位是对真实增益的整体缩放系数。\n" else "") +
            "\n只对本次导出生效，换图后需重新开启。"
        val ss = android.text.SpannableString(red + rest)
        ss.setSpan(
            android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return ss
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
        ss.setSpan(android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
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
        SuperResTask.pendingHdrBoost = false             // 完美档不自动导出，HDR 由 exportPerfect 处理
        SuperResTask.pendingBrightLevel = brightLevel
        SuperResTask.pendingExposure = exposureLevel
        SuperResTask.pendingHighlightLevel = highlightLevel
        SuperResTask.pendingShadowLevel = shadowLevel
        SuperResTask.pendingBlackLevel = blackLevel
        SuperResTask.pendingSatLevel = satLevel
        SuperResTask.pendingContrastLevel = contrastLevel
        SuperResTask.pendingGlowLevel = glowLevel
        SuperResTask.pendingExtras = currentExtras()
        SuperResTask.pendingSourceName = sourceUri?.let { queryDisplayName(it) }
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
            android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
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
            android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
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
        // 源图已有增益图时按钮本就隐藏；再与合成互斥，避免叠加两张增益图
        SuperResTask.pendingHdrBoost = hdrBoost && pendingHdr == null
        SuperResTask.pendingBrightLevel = brightLevel
        SuperResTask.pendingExposure = exposureLevel
        SuperResTask.pendingHighlightLevel = highlightLevel
        SuperResTask.pendingShadowLevel = shadowLevel
        SuperResTask.pendingBlackLevel = blackLevel
        SuperResTask.pendingSatLevel = satLevel
        SuperResTask.pendingContrastLevel = contrastLevel
        SuperResTask.pendingGlowLevel = glowLevel
        SuperResTask.pendingExtras = currentExtras()
        SuperResTask.pendingSourceName = sourceUri?.let { queryDisplayName(it) }
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

    /**
     * 导出收尾回调：4 条落盘出口 + 提前返回分支各调用**恰好一次**。
     *
     * 非批量时 [exportDoneHook] 为 null → 空操作，单张导出与改前完全一致。
     * 漏调会让批量导出停在那一张（看门狗兜底但会中止整批），多调会让同一张被计两次。
     */
    private fun notifyExportDone(ok: Boolean) {
        val cb = exportDoneHook ?: return
        exportDoneHook = null
        cb.invoke(ok)
    }

    /**
     * 导出入口分流：单张直接导；批量时先问「只导当前这张，还是全部」。
     *
     * 文案沿用 [buildBigExportMessage] 的样式：首行红色耗时提示 + 分行说明。
     */
    private fun onExportClicked() {
        if (batchItems.size <= 1) { export(); return }
        if (batchExportRunning) { toast("批量导出进行中"); return }
        val n = batchItems.size
        val untouched = batchItems.count { !it.touched }
        val styleLabel = currentStyle?.label
            ?: currentCustomId?.let { CustomStyleStore.byId(it)?.name }
            ?: "原图"
        val adj = listOf(brightLevel, exposureLevel, highlightLevel, shadowLevel,
            blackLevel, satLevel, contrastLevel, glowLevel).count { it != 0 }
        val red = "全部导出要逐张跑完整导出链（含高分辨率/全像素），耗时较长\n"
        val rest = "当前：第 ${batchIndex + 1} / $n 张 · $styleLabel" +
            (if (adj > 0) " · 调节 $adj 项" else "") + "\n" +
            "全部：$n 张，均按各自画面上的设置导出" +
            (if (untouched > 0) "（其中 $untouched 张未单独调整，会套用当前色彩）" else "") + "\n" +
            "过程中会显示进度，可随时停止；已完成的部分会保留在图库。"
        val msg = android.text.SpannableString(red + rest)
        msg.setSpan(
            android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("导出当前 1 张，还是全部 $n 张？")
            .setMessage(msg)
            .setPositiveButton("全部导出") { _, _ -> startBatchExport() }
            .setNegativeButton("仅当前张") { _, _ -> export() }
            .setNeutralButton("取消", null)
            .show()
    }

    // ---- 批量导出：逐张复用现有导出链 ----

    /** 单张导出结果（汇总用） */
    private class BatchExportResult(val index: Int, val ok: Boolean, val note: String)

    private val batchExportPlan = mutableListOf<Int>()
    private var batchExportCursor = -1
    private val batchExportResults = mutableListOf<BatchExportResult>()
    /** 批量结束后要回到的那张（用户点导出时正在看的那张） */
    private var batchRestoreIndex = 0
    private var batchWatchdog: Runnable? = null
    /** 批量开始时间（完成通知/汇总里的用时） */
    private var batchStartMs = 0L

    private fun startBatchExport() {
        if (batchExportRunning) return
        if (exporting || srBusy) { toast("处理中，请稍候"); return }
        if (batchItems.size <= 1) { export(); return }
        // 配方兜底：未单独调整的图跟随当前设置（切图时已写过一次，这里覆盖「从未显示过」的图）
        batchItems.getOrNull(batchIndex)?.let { snapshotActiveInto(it) }
        val recipe = Recipe()
        val cur = batchItems.getOrNull(batchIndex)
        if (cur != null && cur.hasSettings) recipe.copyFrom(cur)
        else if (!batchRecipe.isEmpty) recipe.copyFrom(batchRecipe)
        if (recipe.isEmpty) { toast("请先选择一个色彩再全部导出"); return }
        batchRecipe.copyFrom(recipe)
        batchItems.forEach { it.applyRecipe(recipe) }

        batchRestoreIndex = batchIndex
        batchExportPlan.clear()
        for (i in batchItems.indices) {
            batchExportPlan.add((batchIndex + i) % batchItems.size)   // 从当前张开始环形
        }
        batchExportResults.clear()
        batchExportCursor = -1
        batchCancel = false
        batchExportRunning = true
        batchStartMs = System.currentTimeMillis()
        batchOverlay.visible()
        batchRing.max = 100
        batchRing.setProgress(0)
        batchMain.text = "正在导出 1 / ${batchExportPlan.size}"
        batchSub.text = "准备中 · 可停止"
        // 整批挂一次前台服务：切后台/锁屏继续跑，通知栏显示整批进度（与超分同一套机制）
        startExportForeground("批量导出", "准备中", longTask = true)
        refreshBatchNavUi()
        DiagLog.i("Batch", "批量导出开始 ${batchExportPlan.size} 张 起始=$batchIndex")
        stepBatchExport()
    }

    private fun stepBatchExport() {
        if (!batchExportRunning) return
        if (batchCancel) { finishBatchExport("已停止"); return }
        val next = batchExportCursor + 1
        if (next >= batchExportPlan.size) { finishBatchExport(null); return }
        batchExportCursor = next
        val idx = batchExportPlan[next]
        val item = batchItems.getOrNull(idx)
        if (item == null) { mainHandler.post { stepBatchExport() }; return }
        // 通知栏前进到这一张（整批百分比 + 「第 3/12 张 · L-M9」）
        ExportProgress.batchItem(next, batchExportPlan.size, labelOf(item))
        // 超分合成图：成片在超分完成时已自动导出，再导一次只会产生重复文件
        if (item.srBakedStyle != null) {
            batchExportResults.add(BatchExportResult(idx, true, "超分已自动导出"))
            mainHandler.post { stepBatchExport() }
            return
        }
        batchMain.text = "正在导出 ${next + 1} / ${batchExportPlan.size}"
        batchSub.text = "准备中 · 可停止"
        ensureItemLoadedForExport(item) { ok ->
            if (!batchExportRunning) return@ensureItemLoadedForExport
            if (!ok) {
                DiagLog.w("Batch", "第 ${next + 1} 张读取失败，跳过")
                batchExportResults.add(BatchExportResult(idx, false, "读取失败"))
                mainHandler.post { stepBatchExport() }
                return@ensureItemLoadedForExport
            }
            // 让上一张的收尾（exporting=false / finally 清理）先落地，避免被 export() 入口闸门吞掉
            mainHandler.postDelayed({
                if (!batchExportRunning) return@postDelayed
                if (batchCancel) { finishBatchExport("已停止"); return@postDelayed }
                exportDoneHook = { ok2 -> onBatchItemFinished(idx, ok2) }
                armBatchWatchdog(item)
                export()
            }, 120L)
        }
    }

    /**
     * 把某张图装进字段，供导出链读取。
     *
     * 与切图不同：这里走**静默装载**（不闪预览、不重建缩略图），并且先把手上的字段
     * 快照回它自己的条目 —— 否则上一张的 200MP 直通源会被 clearFullRes 删掉。
     */
    private fun ensureItemLoadedForExport(item: EditItem, onReady: (Boolean) -> Unit) {
        val prev = batchItems.getOrNull(batchIndex)
        if (prev != null && prev !== item) snapshotActiveInto(prev)
        detachFullResFields()
        batchIndex = batchItems.indexOf(item)
        if (item.hot != null) {
            applySessionFields(item)
            refreshBatchNavUi()
            onReady(true)
            return
        }
        onImagePicked(item.uri, item, silent = true) { ok -> onReady(ok) }
    }

    private fun onBatchItemFinished(index: Int, ok: Boolean) {
        cancelBatchWatchdog()
        if (!batchExportRunning) return
        batchExportResults.add(BatchExportResult(index, ok, if (ok) "" else "导出失败"))
        val done = batchExportCursor + 1
        batchRing.setProgress((done * 100) / batchExportPlan.size.coerceAtLeast(1))
        batchSub.text = if (ok) "已完成 $done / ${batchExportPlan.size}"
        else "第 $done 张失败，继续下一张"
        DiagLog.i("Batch", "第 $done 张${if (ok) "完成" else "失败"}")
        mainHandler.postDelayed({
            if (batchExportRunning) stepBatchExport()
        }, 80L)
    }

    /**
     * 单张看门狗：漏回调或引擎卡死时不能让整批永远停在浮层上。
     *
     * 超时即中止整批（不继续下一张）：此时字段可能仍被在途任务占用，继续会有串数据风险。
     */
    private fun armBatchWatchdog(item: EditItem) {
        cancelBatchWatchdog()
        val px = item.fullResW.toLong() * item.fullResH
        val timeout = if (item.fullResFile != null && px >= BIG_EXPORT_PIXELS) 480_000L else 180_000L
        val r = Runnable {
            if (!batchExportRunning) return@Runnable
            DiagLog.w("Batch", "第 ${batchExportCursor + 1} 张导出超时 ${timeout}ms")
            exportDoneHook = null          // 迟到回调直接丢弃
            batchCancel = true
            finishBatchExport("第 ${batchExportCursor + 1} 张超时，已停止")
        }
        batchWatchdog = r
        mainHandler.postDelayed(r, timeout)
    }

    private fun cancelBatchWatchdog() {
        batchWatchdog?.let { mainHandler.removeCallbacks(it) }
        batchWatchdog = null
    }

    private fun finishBatchExport(reason: String?) {
        cancelBatchWatchdog()
        exportDoneHook = null
        batchExportRunning = false
        batchCancel = false
        batchOverlay.gone()
        // 完成通知：张数 + 失败数 + 停止原因 + 用时（前台服务据此发"批量导出完成"）
        val total = batchExportPlan.size
        val okCount = batchExportResults.count { it.ok }
        val failCount = batchExportResults.size - okCount
        val elapsed = if (batchStartMs > 0) System.currentTimeMillis() - batchStartMs else 0L
        val summary = buildString {
            append("已导出 $okCount / $total 张")
            if (failCount > 0) append(" · 失败 $failCount 张")
            if (reason != null) append(" · $reason")
            if (elapsed > 0) append(" · 用时 ${fmtDuration(elapsed)}")
        }
        ExportProgress.end(failCount == 0 && batchExportResults.size == total, summary)
        refreshBatchNavUi()
        // 导出期间字段被借给每一张，收尾必须回到用户原本看的那张
        val back = batchItems.getOrNull(batchRestoreIndex)
        if (back != null) {
            batchItems.getOrNull(batchIndex)?.let { snapshotActiveInto(it) }
            detachFullResFields()
            batchIndex = batchRestoreIndex
            if (back.hot != null) {
                applySessionFields(back)
                refreshAfterSwitch(rebuildThumbs = true)
            } else {
                loadBatchItemAt(batchRestoreIndex)
            }
        }
        showBatchExportSummary(reason)
    }

    /** 汇总弹窗：数分钟等待必须有明确交代（与 [showBigExportDoneDialog] 同款口径） */
    private fun showBatchExportSummary(reason: String?) {
        if (isFinishing || isDestroyed) return
        val total = batchExportPlan.size
        val okCount = batchExportResults.count { it.ok }
        val failed = batchExportResults.filter { !it.ok }
        val skipped = batchExportResults.count { it.ok && it.note.isNotEmpty() }
        val head = if (failed.isNotEmpty()) "成功 $okCount / $total 张" else "已导出 $okCount / $total 张"
        val sb = StringBuilder(head)
        if (skipped > 0) sb.append("（其中 $skipped 张为超分成片，此前已自动导出）")
        if (failed.isNotEmpty()) {
            sb.append("\n失败 ${failed.size} 张：")
            failed.take(5).forEach { sb.append("\n· 第 ${it.index + 1} 张（${it.note}）") }
            if (failed.size > 5) sb.append("\n· 等")
        }
        if (reason != null) sb.append("\n\n$reason")
        sb.append("\n\n成片已保存到相册 Pictures/L-Moment。")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("批量导出结束")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun export() {
        // 每条出口都必须恰好回调一次 notifyExportDone，否则批量导出会卡在这张上（看门狗兜底）
        if (exporting) { toast("全像素导出进行中，请稍候"); notifyExportDone(false); return }
        if (srBusy) { toast("处理中，请稍候"); notifyExportDone(false); return }
        val bytes = originalBytes ?: run { toast("请先导入图片"); notifyExportDone(false); return }
        // 完美态：成片自带色彩，直接落盘（跳过滤镜链，避免二次套色）
        if (perfectApplied && perfectBytes != null) { exportPerfect(); return }
        // 合成态：超分完成时已自动导出（带徽章），手动导出会产生重复文件
        if (srBakedStyle != null) {
            toast("超分完成后已自动导出至图库")
            notifyExportDone(true)      // 已导出过，批量里按成功计
            return
        }
        // C1/C4：全像素导出。原图档直通；甲档（LUT 家族）与乙档（模型链 M9/M9+/M10R/M9D）
        // 带滤镜逐带处理，同为全像素。SA 与 LP9（P9Post 依赖全图空间场）仍按工作字节导出。
        // 兜底重入：M9 高分辨率导出失败时会置位并重调本方法，让这次跳过全像素分支。
        val fullSrc = if (skipFullResOnce) { skipFullResOnce = false; null } else fullResFile
        if (fullSrc != null) {
            // 原图直通仅当「无色彩且无任何调节」——有调节时必须走完整链，否则调节会被丢掉
            if (!hasColor() && !hasAdjustments()) { exportFullRes(fullSrc); return }
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
                    intArrayOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel, satLevel, contrastLevel),
                    currentExtras(), lutStrength
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
                        intArrayOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel, satLevel, contrastLevel),
                        currentExtras(), lutStrength
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
                    intArrayOf(brightLevel, exposureLevel, highlightLevel, shadowLevel, blackLevel, satLevel, contrastLevel),
                    lutStrength
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
        // 原图卡（无色彩）也允许导出「原图 + 调节」；两者皆无 → 原样直通导出
        // （JPG=导入原字节零重编码，RAW=导入期显影的全尺寸字节，见 [exportPassthrough]）
        if (!hasColor() && !hasAdjustments()) {
            exportPassthrough(bytes)
            return
        }
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
        // HDR 开关快照：导出期间用户仍可能点按钮，进线程前定死本次语义
        val hdrOn = hdrBoost
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
            // 风格强度快照：仅在有色彩时消费（无色彩=100 原样，与预览口径一致）
            val lutPctL = if (hasColor()) lutStrength else 100
            // V2：RAW 线性亮度基准（标准档不产线性数据，这里会额外解码一次，仅用于算增益）
            val rawLuma = ensureRawLinearLuma(hdrOn)
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
                rawToned = raw16TonedWorkBytes(brL, evL, hlL, shL, blL, saL, coL, glL, currentExtras())
                DiagLog.i("Export", "RAW 16bit 七档重放 ${if (rawToned != null) "成功" else "失败→回落8bit"} " +
                        "耗时 ${System.currentTimeMillis() - t16}ms")
            }
            var out = if (rawToned != null) {
                ColorEngine.process(this, rawToned, currentStyle!!, onFallback = { st ->
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
                ColorEngine.process(this, bytes, currentStyle!!, onFallback = { st ->
                    DiagLog.w("Export", "${st.label} 处理触发降级")
                    mainHandler.post { toast(fallbackMsg(st)) }
                })
            }
            // 亮度+曝光+高光+阴影+黑色阶组合烘焙（滤镜之后、徽章之前；单次 decode/encode，EXIF 回填）
            // P2：16-bit 重放成功时七档已烘焙，跳过 8-bit 二次应用。
            // P3：柔光条件并入（glow 可单独成立；16-bit 成功时柔光已随重放烘焙）。
            // 风格强度 <100：styled 往「风格前基图」拉回（与预览 blendLut 同语义，曲线前混合）。
            val needsStack = toneRequested || glL != 0 || !currentExtras().isNeutral
            if (rawToned == null) {
                if (needsStack || lutPctL != 100) {
                    val tL = System.currentTimeMillis()
                    out = Shadows.applyStackToJpeg(out, brL, evL, hlL, shL, blL, saL, coL, glL,
                        currentExtras(), baseJpeg = bytes, lutPct = lutPctL)
                    DiagLog.i("Export", "亮度 ${brL}/曝光 ${evL}/高光 ${hlL}/阴影 ${shL}/黑色阶 ${blL}/饱和度 ${saL}/对比度 ${coL}/柔光 ${glL} 已烘焙 风格强度=$lutPctL 耗时 ${System.currentTimeMillis() - tL}ms")
                }
            } else if (lutPctL != 100) {
                // 16-bit 重放路径：七档已烘焙，这里只做强度混合；base = 风格前基图（重放产物）
                val tL = System.currentTimeMillis()
                out = Shadows.applyStackToJpeg(out, 0, 0, 0, 0, 0, 0, 0, 0,
                    AdjustOps.Extras(), baseJpeg = rawToned, lutPct = lutPctL)
                DiagLog.i("Export", "风格强度 $lutPctL 已混合（16bit 重放路径）耗时 ${System.currentTimeMillis() - tL}ms")
            }
            DiagLog.i("Export", "${styleId} 处理完成 ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
            // HDR 数据：源图自带增益图（C3 按新 SDR 重标定）> RAW 真增益 > 猜测式合成 > 无
            val hdrForExport = resolveHdrForExport(pendingHdr, bytes, out, hdrOn, rawLuma)
            mainHandler.post {
                progress.gone()
                if (gen != processingGeneration) { notifyExportDone(false); return@post }
                if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                    requestWritePermission { export() }
                    return@post
                }
                val badgeOn = SettingsStore.badgeEnabled(this)
                val deviceMode = SettingsStore.deviceInfoModeCode(this)
                val marked = ExportMark.mark(out, badgeOn, deviceMode)
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
                val uri = saveWithFormat(finalBytes, nameTag)
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
                notifyExportDone(uri != null)
            }
        }
    }

    /**
     * 原样直通导出（无色彩 + 无任何调节）：跳过解码与中性重编码，工作字节直接落盘。
     *
     * 字节来源按导入路径分档：
     * - JPG 直通导入（引擎上限内）：originalBytes 即导入原字节（仅做过 EXIF 方向归一 /
     *   动态照片截断），像素逐字节无损、EXIF 天然完整；
     * - RAW 源：originalBytes 为导入期显影的全尺寸 JPEG95（LibRaw 解码 + 白平衡/曲线
     *   与预览同源，已从源 DNG 回填 EXIF），跳过旧链的 8-bit 中性重放；
     * - 超限 JPG / HEIF / PNG 等：导入期工作字节（全尺寸 JPEG95 + EXIF 回填），
     *   比旧中性链少一代编码。
     *
     * 元数据语义与常规导出完全一致：ExportMark（徽章/设备标识，badge 关=剥源徽章）
     * → HDR 装配（源自带增益图原样回嵌 / HDR 开关合成增益图）→ 保存。全程不触碰 SDR 像素。
     * 收尾段（权限/徽章/HDR/保存/toast）与 [export] 的工作版尾部同口径——
     * 任一侧改动必须同步另一侧。
     */
    private fun exportPassthrough(srcJpeg: ByteArray) {
        progress.visible()
        val gen = ++processingGeneration
        // HDR 开关快照（同 export：进线程前定死本次语义）
        val hdrOn = hdrBoost
        executor.execute {
            val t0 = System.currentTimeMillis()
            try {
                // HDR 数据解析与常规链同口径。srcSdr 传 null：无色彩无调节时输出==源 SDR，
                // 源自带增益图无需重标定，原样回嵌即可（免一次双图解码）。
                val rawLuma = ensureRawLinearLuma(hdrOn)
                val hdrForExport = resolveHdrForExport(pendingHdr, null, srcJpeg, hdrOn, rawLuma)
                mainHandler.post {
                    progress.gone()
                    if (gen != processingGeneration) { notifyExportDone(false); return@post }
                    if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                        requestWritePermission { export() }
                        return@post
                    }
                    val badgeOn = SettingsStore.badgeEnabled(this)
                    val deviceMode = SettingsStore.deviceInfoModeCode(this)
                    val marked = ExportMark.mark(srcJpeg, badgeOn, deviceMode)
                    if (marked === srcJpeg) DiagLog.w("Export",
                        if (badgeOn) "徽章写入失败（返回原字节）" else "徽章关闭：源图无徽章元数据（原样返回）")
                    // HDR 回嵌：徽章之后装配（徽章链会移除 XMP/MPF，不能放它前面）
                    val hdrSrc = hdrForExport
                    val finalBytes = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                        else HdrGainMap.truncateToPrimary(marked)
                    val hdrChk = HdrGainMap.verify(finalBytes)
                    val uri = saveWithFormat(finalBytes, "orig")
                    DiagLog.i("Export", "原样直通导出 ${finalBytes.size}B " +
                        (if (sourceIsRaw) "(RAW显影字节) " else "") +
                        "HDR=${hdrSrc != null} 耗时 ${System.currentTimeMillis() - t0}ms · " +
                        "保存${if (uri != null) "成功" else "失败"}")
                    toast(
                        when {
                            uri == null -> "导出失败，请重试（详情见日志）"
                            hdrSrc != null && !hdrChk.ok -> "已保存到相册 · HDR 数据未完整保留"
                            else -> "已保存到相册"
                        }
                    )
                    notifyExportDone(uri != null)
                }
            } catch (t: Throwable) {
                DiagLog.e("Export", "原样导出异常", t)
                mainHandler.post {
                    progress.gone()
                    if (gen == processingGeneration) toast("导出失败，请重试（详情见日志）")
                    notifyExportDone(false)
                }
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
        // HDR 开关快照（同 export：进线程前定死本次语义）
        val hdrOn = hdrBoost
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
            // 风格强度快照（本链只服务 M9/M9D，色彩必在）
            val lutPctL = lutStrength

            // 单档执行：按目标长边解码 → 小 JPEG（回填源 EXIF）→ 整图链。
            // toJpeg 从 Bitmap 重编码**不写 EXIF**；而 M9Distill 内部的 copyExif 与
            // ExportMark.mark（在已有 APP1 段上追加 theme/legend/mode）都依赖输入带 EXIF。
            // 不回填 → 导出丢 Make/Model/拍摄参数与徕卡徽章（2026-10-21 真机实测：
            // MarkDiagnostic 显示 Make=/Model=/legend= 全空，FFE4 却在——段不依赖 EXIF）。
            var hiSize = ""
            // 风格强度混合的基图：风格前的高分辨率画布 JPEG（与 out 同画布）
            var hiBaseJpeg: ByteArray? = null
            fun tryHi(edge: Int): ByteArray? {
                val hi = ImageUtils.decodeWorkForLongEdge(
                    this, Uri.fromFile(srcFile), edge) ?: return null
                hiSize = "${hi.width}×${hi.height}"
                val jpeg = ImageUtils.toJpeg(hi, 95)
                val wOri = ImageUtils.resolveWorkOrientation(srcBytes, hi.width, hi.height)
                val jpegExif = ExifTransplant.copy(this, srcBytes, jpeg, wOri)
                hi.recycle()
                DiagLog.i("FullRes", "M9 高分辨率导出 · ${edge}档 画布 $hiSize 源JPEG ${jpegExif.size}B")
                hiBaseJpeg = jpegExif
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
            // 3) 八滑杆（与工作版口径一致：滤镜之后、徽章之前）+ 风格强度混合
            if (brL != 0 || evL != 0 || hlL != 0 || shL != 0 || blL != 0 || saL != 0 || coL != 0 || glL != 0 ||
                !currentExtras().isNeutral || (lutPctL != 100 && hiBaseJpeg != null)
            ) {
                val tL = System.currentTimeMillis()
                out = Shadows.applyStackToJpeg(out, brL, evL, hlL, shL, blL, saL, coL, glL,
                    currentExtras(), baseJpeg = hiBaseJpeg, lutPct = lutPctL)
                DiagLog.i("Export", "亮度 ${brL}/曝光 ${evL}/高光 ${hlL}/阴影 ${shL}/黑色阶 ${blL}/饱和度 ${saL}/对比度 ${coL}/柔光 ${glL} 已烘焙 风格强度=$lutPctL 耗时 ${System.currentTimeMillis() - tL}ms")
            }
            // 4) HDR 增益图重标定。
            // 增益图来源：全像素直通场景存在 fullResHdr（导入期 `pendingHdr` 只在 jpegDirect
            // 时赋值，200MP/50MP 直通一律为 null——曾导致 HiRes 导出静默丢 HDR，2026-10-21 修）。
            // 套了滤镜必须 rebake：增益图的分母是「源 SDR」，成品 SDR 亮度分布已变；
            // rebakeJpeg 内部把新旧 SDR 都缩到增益图尺寸，与输出主图尺寸无关（几何安全）。
            val outFinal = out
            // 增益图来源：全像素直通有 fullResHdr（200MP 直通时 pendingHdr 恒 null），
            // 否则按 HDR 开关从成品 SDR 合成
            val hdrForExport = resolveHdrForExport(
                fullResHdr ?: pendingHdr, srcBytes, outFinal, hdrOn, rawLinearLuma)
            mainHandler.post {
                progress.gone()
                if (gen != processingGeneration) { notifyExportDone(false); return@post }
                if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                    // 老设备写权限兜底：授权后回落工作版重跑（避免 HiRes 再失败一次）
                    requestWritePermission { skipFullResOnce = true; export() }
                    return@post
                }
                val badgeOn = SettingsStore.badgeEnabled(this)
                val deviceMode = SettingsStore.deviceInfoModeCode(this)
                val marked = ExportMark.mark(outFinal, badgeOn, deviceMode)
                if (marked === outFinal) DiagLog.w("Export",
                    if (badgeOn) "徽章写入失败（返回原字节）" else "徽章关闭：源图无徽章元数据（原样返回）")
                val hdrSrc = hdrForExport
                val finalBytes = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                    else HdrGainMap.truncateToPrimary(marked)
                val hdrChk = HdrGainMap.verify(finalBytes)
                val diag = MarkDiagnostic.describe(marked) + " " + hdrChk.brief
                val uri = saveWithFormat(finalBytes, nameTag)
                DiagLog.i("Export", "保存${if (uri != null) "成功" else "失败"} · $diag")
                toast(
                    when {
                        uri == null -> "导出失败，请重试（详情见日志）"
                        hdrSrc != null && !hdrChk.ok -> "已保存到相册 · HDR 数据未完整保留"
                        else -> "已保存到相册（高分辨率 $hiSize）"
                    }
                )
                notifyExportDone(uri != null)
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
        // 全像素（≥45MP）：条带要跑半分钟以上，先讲清耗时与后台限制再开工
        val big = px >= BIG_EXPORT_PIXELS
        // 批量导出：确认弹窗在批量入口已统一讲清，逐张再弹会打断整批
        if (big && !confirmed && !batchExportRunning) {
            confirmBigExport(px, w0, h0) {
                exportFullRes(
                    srcFile, op, nameTag, styleId,
                    confirmed = true, onFailure = onFailure
                )
            }
            return
        }
        if (big) {
            exporting = true
            // 批量：整批已挂前台服务，且进度由批量浮层统一表达，不再叠一层大图浮层
            if (!batchExportRunning) {
                startExportForeground()
                // 长任务通道用「环形进度 + 百分比」浮层替代普通转圈（与通知栏同源 ExportProgress）
                showBigExportOverlay()
            }
        } else {
            if (!batchExportRunning) progress.visible()
        }
        val gen = ++processingGeneration
        val hdrSrc = fullResHdr
        // HDR 开关快照：源图自带增益图时按钮本就隐藏，这里只可能是「无源 HDR + 开关开」
        val hdrOn = hdrBoost && hdrSrc == null
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
                mainHandler.post {
                    progress.gone()
                    hideBigExportOverlay()
                    requestWritePermission { export() }
                }
                return@execute
            }
            val tmp = File(cacheDir, "lm_fullres_${System.currentTimeMillis()}.jpg")
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
                        hideBigExportOverlay()
                        if (gen == processingGeneration && !isFinishing && !isDestroyed) {
                            if (onFailure != null) onFailure()
                            else toast(
                                if (op != null) "全像素滤镜导出失败，请重试（详情见日志）"
                                else "全像素导出失败，请重试（详情见日志）"
                            )
                        }
                        // onFailure 会重入 export()，由内层回调；没有 onFailure 才自己收尾
                        if (onFailure == null) notifyExportDone(false)
                    }
                    return@execute
                }
                // 全像素直通只接受 Orientation=1（见 ImageUtils.decideFullRes），故固定写 1
                val exifOk = exifHead != null && ExifTransplant.copyInPlace(this, exifHead, tmp, "1")
                DiagLog.i("FullRes", "EXIF 回填${if (exifOk) "完成" else "跳过"} 堆 ${ImageUtils.heapUsageLabel()}")
                // A1（2026-10-29）：源图 ICC 不再随导出走。全链解码都请求 sRGB
                // （inPreferredColorSpace = SRGB），像素已是 sRGB 数值；再贴源的 Display P3
                // 档案等于「sRGB 数值 + P3 标签」，色彩管理的查看器会外扩 → 整体偏艳偏蓝
                // （v1.4.x 相比 v1.3.0 的「M9 更饱和失真」根因）。无 ICC 标签 = sRGB 约定。
                val badgeOn = SettingsStore.badgeEnabled(this)
                val badgeOk = ExportMark.markFile(
                    tmp, null, badgeOn, SettingsStore.deviceInfoModeCode(this)
                )
                val badgeMsg = if (badgeOn) "徽章写入${if (badgeOk) "完成" else "失败（保留原样）"}"
                else "徽章关闭（剥离徽章元数据）${if (badgeOk) "完成" else "失败（保留原样）"}"
                DiagLog.i("FullRes",
                    "$badgeMsg · ICC 不搬运(A1 sRGB) " +
                        "堆 ${ImageUtils.heapUsageLabel()}")
                // HDR 回嵌：徽章链会移除 XMP/MPF，必须在其后；无增益图时裁掉残留二次图防错配
                // C3：全像素源若自带增益图且本次套了色（op != null），增益图按新 SDR 重标定
                val hdrForExport = when {
                    hdrSrc != null && op != null ->
                        HdrGainMap.rebakeFiles(srcFile, tmp, hdrSrc, w, h) ?: hdrSrc
                    hdrSrc != null -> hdrSrc
                    // 无源增益图 + 开关开：对成品主图算增益（按 inSampleSize 解码，
                    // 200MP 源也绝不整图入堆）。RAW 优先走真增益，失败退回猜测式。
                    hdrOn -> {
                        val lvl = SettingsStore.hdrBoostLevel(this)
                        val linear = rawLinearLuma?.let {
                            HdrGainMap.synthFromLinearFile(it, tmp, SettingsStore.rawHdrStrength(lvl))
                        }
                        val d = linear ?: HdrGainMap.synthFile(tmp, lvl)
                        DiagLog.i(
                            "FullRes",
                            if (d != null) "HDR 合成${if (linear != null) "（线性）" else ""}成功 " +
                                "增益图 ${d.gainmapJpeg.size}B 区间[${d.minLog2},${d.maxLog2}]"
                            else "HDR 合成跳过（高光不足或异常）→ 按纯 SDR 导出"
                        )
                        d
                    }
                    else -> null
                }
                val hdrChk = if (hdrForExport != null) HdrGainMap.embedFile(tmp, hdrForExport)
                    else HdrGainMap.truncateToPrimaryFile(tmp)
                val diag = fileHeaderDiag(tmp) + " " + hdrChk.brief
                val uri = saveFileWithFormat(tmp, nameTag, "full")
                val elapsed = System.currentTimeMillis() - t0
                DiagLog.i("FullRes",
                    "保存${if (uri != null) "成功" else "失败"} ${tmp.length()}B " +
                        "总耗时 ${elapsed}ms 堆 ${ImageUtils.heapUsageLabel()} · $diag")
                succeeded = uri != null
                exportSummary = "${w}×${h} · 用时 ${fmtDuration(elapsed)}"
                mainHandler.post {
                    progress.gone()
                    hideBigExportOverlay()
                    if (gen == processingGeneration && !isFinishing && !isDestroyed) {
                        // 柔光是空间算子不进条带链（半径 > 重叠带必带边不连续），全像素条带导出时明示
                        val glowNote = if (op != null && glowLevel != 0) " · 柔光未参与" else ""
                        val tail = (if (op != null) "· ${nameTag} 已套色" else "") + glowNote
                        val hdrShort = hdrForExport != null && !hdrChk.ok
                        when {
                            uri == null -> toast("全像素导出失败，请重试（详情见日志）")
                            // 批量：逐张弹「导出完成」会打断整批（且 toast 已被重定向到浮层）
                            big && batchExportRunning -> toast("已保存到相册（${w}×$h）$tail")
                            big -> showBigExportDoneDialog(w, h, elapsed, hdrShort, tail)
                            hdrShort -> toast("已保存到相册（${w}×$h）· HDR 数据未完整保留$tail")
                            else -> toast("已保存到相册（${w}×$h）$tail")
                        }
                    }
                    // 无论 UI 是否被新任务顶掉，导出本身已落盘 → 批量必须收到回调
                    notifyExportDone(uri != null)
                }
            } catch (t: Throwable) {
                DiagLog.e("FullRes", "全像素导出异常：${t.message}", t)
                // 同上：onFailure 回落要重入 export()，必须先解除 exporting 保护
                exporting = false
                mainHandler.post {
                    progress.gone()
                    hideBigExportOverlay()
                    if (gen == processingGeneration && !isFinishing && !isDestroyed) {
                        if (onFailure != null) onFailure()
                        else toast("全像素导出失败：${t.javaClass.simpleName}")
                    }
                    if (onFailure == null) notifyExportDone(false)
                }
            } finally {
                runCatching { tmp.delete() }
                // 批量：通知栏由整批统一收尾，逐张 end 会让通知一闪一闪
                if (!batchExportRunning) ExportProgress.end(succeeded, exportSummary)
                exporting = false
            }
        }
    }

    // ---- 全像素（≥45MP）导出：进度浮层 / 开工确认 / 用时弹窗 ----

    /**
     * 大图导出进度浮层：替代普通转圈，环形进度 + 百分比。数据源与通知栏一致
     * （ExportProgress 单例，由条带 onProgress 写入），主线程 400ms 一拍轮询。
     */
    private fun showBigExportOverlay() {
        progress.gone()
        bigExportRing.isIndeterminate = true
        bigExportPercent.text = "准备中"
        bigExportHint.text = "全像素导出中 · 可退回桌面等待"
        bigExportOverlay.visible()
        mainHandler.removeCallbacks(exportOverlayTick)
        mainHandler.post(exportOverlayTick)
    }

    /** 收浮层并停轮询：成功/失败/异常/权限回落各出口显式调用，轮询自身另有 !exporting 兜底 */
    private fun hideBigExportOverlay() {
        mainHandler.removeCallbacks(exportOverlayTick)
        bigExportOverlay.gone()
    }

    /** 首拍立即执行：indeterminate 阶段转圈 +「准备中」，条带回调到达后切 determinate 显示百分比 */
    private val exportOverlayTick = object : Runnable {
        override fun run() {
            if (!exporting) {
                hideBigExportOverlay()
                return
            }
            if (ExportProgress.indeterminate) {
                if (!bigExportRing.isIndeterminate) bigExportRing.isIndeterminate = true
                if (bigExportPercent.text.toString() != "准备中") bigExportPercent.text = "准备中"
            } else {
                if (bigExportRing.isIndeterminate) {
                    bigExportRing.isIndeterminate = false
                    bigExportRing.max = 100
                }
                val p = ExportProgress.percent
                bigExportRing.setProgress(p)
                bigExportPercent.text = "$p%"
            }
            mainHandler.postDelayed(this, 400)
        }
    }

    /** 开工前确认：耗时与「后台无限制」都要讲清楚，确认后才真正起前台服务 */
    private fun confirmBigExport(px: Long, w: Int, h: Int, onStart: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("全像素导出")
            .setMessage(buildBigExportMessage(px, w, h))
            .setPositiveButton("开始导出") { _, _ -> onStart() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 确认文案：首行红字耗时 + 末段红字「后台无限制」指引 */
    private fun buildBigExportMessage(px: Long, w: Int, h: Int): CharSequence {
        val red = "预计 ${estimateBandExportText(px)}，期间手机会持续发热\n"
        val rest = "全像素导出 ${w}×$h（${fmtPixels(px)}）：逐条带重编码，内存占用很低，但需要时间。\n" +
            "已挂前台服务并从通知栏显示进度，可以退回桌面等待；请勿在多任务里划掉本应用。\n" +
            "锁屏会降低系统调度、明显变慢，建议保持亮屏或直接切到桌面。\n\n"
        val warn = "若系统限制后台，长任务可能被冻结或中断 —— 请到 系统设置 → 应用管理 → L-Moment → 省电策略，改为「无限制」。"
        val ss = android.text.SpannableString(red + rest + warn)
        ss.setSpan(android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val warnStart = (red + rest).length
        ss.setSpan(android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
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
        ss.setSpan(android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
            0, red.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val warnStart = (red + rest).length
        ss.setSpan(android.text.style.ForegroundColorSpan(WARN_TEXT_COLOR),
            warnStart, ss.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("全像素导出完成")
            .setMessage(ss)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 条带耗时口径（分档，不按像素线性外推）：
     * - 50MP（8192×6144，512 行一带共 12 带）约 1 分钟级；
     * - 200MP（直通/套色实测）落 1~5 分钟。
     * 只影响确认弹窗首行文案，不参与任何预算/超时判定。
     */
    private fun estimateBandExportText(px: Long): String =
        if (px >= 100_000_000L) "1~5 分钟" else "约 1 分钟"

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
    /**
     * 起前台服务（+通知权限）并初始化进度单例。
     *
     * @param title    通知标题：单张「全像素导出」/ 批量「批量导出」
     * @param stage    初始阶段文案（空 = 条带默认的「处理中…」）
     * @param longTask 批量：服务把唤醒锁从 30 分钟放宽到 120 分钟
     */
    private fun startExportForeground(
        title: String = "全像素导出",
        stage: String = "",
        longTask: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        ExportProgress.begin(title, stage, longTask)
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

    /** 文件名片段净化：语义与历史一致，实现委托到 ExportNamer 单点（两处防漂移） */
    private fun sanitizeFileTag(raw: String): String = ExportNamer.sanitizeTag(raw)

    /** 完美成片导出：档位烘焙（如需要）→ 相机徽章 → HDR 回嵌 → 入库（命名带 _perfect）。 */
    private fun exportPerfect() {
        val src = perfectBytes ?: return
        progress.visible()
        val gen = ++processingGeneration
        val styleId = perfectStyle?.id ?: "M9"
        // 文件名跟名字走（2026-11-11 口径统一：自定义色彩用显示名，与 export() 一致；
        // 内置色彩 byId 查不到 → 回落 styleId，与历史一致）
        val nameTag = perfectStyle?.id?.let { CustomStyleStore.byId(it)?.name }
            ?.let { sanitizeFileTag(it) } ?: styleId
        // HDR 开关快照（完美成片导出与工作版同口径）
        val hdrOn = hdrBoost
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
            if (brL != 0 || evL != 0 || hlL != 0 || shL != 0 || blL != 0 || saL != 0 || coL != 0 || glL != 0 ||
                !currentExtras().isNeutral
            ) {
                out = Shadows.applyStackToJpeg(out, brL, evL, hlL, shL, blL, saL, coL, glL, currentExtras())
            }
            DiagLog.i("Export", "完美成片导出 $styleId ${out.size}B 耗时 ${System.currentTimeMillis() - t0}ms")
            // 完美档同样是「套了色调的 SDR」：增益图按新 SDR 重标定，无源图则按开关合成
            val hdrForExport = resolveHdrForExport(
                pendingHdr, originalBytes, out, hdrOn, rawLinearLuma)
            mainHandler.post {
                progress.gone()
                if (gen != processingGeneration) { notifyExportDone(false); return@post }
                if (Build.VERSION.SDK_INT <= 28 && !hasWritePermission()) {
                    requestWritePermission { exportPerfect() }
                    return@post
                }
                val marked = ExportMark.mark(
                    out,
                    SettingsStore.badgeEnabled(this),
                    SettingsStore.deviceInfoModeCode(this)
                )
                // HDR 回嵌：徽章之后装配（徽章链会移除 XMP/MPF）；未回嵌则裁掉残留增益图尾数据
                val hdrSrc = hdrForExport
                val finalBytes = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                    else HdrGainMap.truncateToPrimary(marked)
                val hdrChk = HdrGainMap.verify(finalBytes)
                val uri = saveWithFormat(finalBytes, nameTag, "perfect")
                DiagLog.i("Export", "完美成片保存${if (uri != null) "成功" else "失败"} · ${hdrChk.brief}")
                toast(
                    when {
                        uri == null -> "导出失败，请重试（详情见日志）"
                        hdrSrc != null && !hdrChk.ok -> "完美成片已保存 · HDR 数据未完整保留"
                        else -> "完美成片已保存"
                    }
                )
                notifyExportDone(uri != null)
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

    private fun saveToGallery(bytes: ByteArray, style: ColorEngine.Style): Uri? {
        return saveWithFormat(bytes, style.id)
    }

    /**
     * 统一导出入口（字节版）：文件名格式/时间/机型全部跟随设置页口径，见 [ExportNamer]。
     * takenAt 在这里一次读取，同时喂起名与入库（避免双读 EXIF）；
     * legacy 默认档不起名前的额外读取，保持与历史完全等价的最小开销。
     */
    private fun saveWithFormat(bytes: ByteArray, styleTag: String, modeTag: String? = null): Uri? {
        val now = System.currentTimeMillis()
        val timeNow = !SettingsStore.exportTimeOriginal(this)
        val takenAt = if (timeNow) now else ExifTimes.readTakenAtEpoch(bytes) ?: now
        val fmt = SettingsStore.exportNameFormat(this)
        val name = if (fmt == ExportNamer.FMT_LEGACY) {
            ExportNamer.buildName(this, fmt, styleTag = styleTag, takenAt = now, modeTag = modeTag)
        } else {
            ExportNamer.buildName(
                this, fmt,
                styleTag = styleTag,
                takenAt = takenAt,
                origName = sourceUri?.let { queryDisplayName(it) },
                deviceModel = ExportMark.resolveDeviceModel(bytes, SettingsStore.deviceInfoModeCode(this)),
                modeTag = modeTag,
            )
        }
        return ExportSave.save(this, bytes, name, takenAt, timeNow)
    }

    /** 统一导出入口（文件版，200MP 直通）：文件不整读进堆，机型 token 走路径流式读 */
    private fun saveFileWithFormat(file: File, styleTag: String, modeTag: String? = null): Uri? {
        val now = System.currentTimeMillis()
        val timeNow = !SettingsStore.exportTimeOriginal(this)
        val takenAt = if (timeNow) now else ExifTimes.readTakenAtEpoch(file) ?: now
        val fmt = SettingsStore.exportNameFormat(this)
        val name = if (fmt == ExportNamer.FMT_LEGACY) {
            ExportNamer.buildName(this, fmt, styleTag = styleTag, takenAt = now, modeTag = modeTag)
        } else {
            ExportNamer.buildName(
                this, fmt,
                styleTag = styleTag,
                takenAt = takenAt,
                origName = sourceUri?.let { queryDisplayName(it) },
                deviceModel = ExportMark.resolveDeviceModel(file, SettingsStore.deviceInfoModeCode(this)),
                modeTag = modeTag,
            )
        }
        return ExportSave.saveFile(this, file, name, takenAt, timeNow)
    }

    // saveNamed / saveNamedFile 已收编到 ExportSave（ExportNamer.kt），
    // 由此处的 saveWithFormat / saveFileWithFormat 统一入口调用。

    // ---- 工具 ----

    // saveNamedFile 同上，文件版逻辑在 ExportSave.saveFile。

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

    /**
     * 右下角小字（txtStyleHint）限宽：右缘不得超过左下按钮行（rowBottomPills）的左缘。
     * 三段式长文案（如「ARRI 709 · 基础影视 · 干净中性」）超宽时自动折两行、右对齐，
     * 结构上杜绝与「调节/完美/HDR」重叠。舞台未布局或按钮行尚未测量时直接跳过
     * （监听器会在其布局完成后再次触发）。下限 140dp 防极端窄屏下小字退化。
     */
    private fun applyHintMaxWidth() {
        val stageW = floatStage.width
        if (stageW <= 0) return
        val avail = stageW - dp(16) * 2 - dp(8) - rowBottomPills.width
        val w = maxOf(avail, dp(140))
        if (txtStyleHint.maxWidth != w) txtStyleHint.maxWidth = w
        refreshHintWrap()
    }

    /**
     * 小字语义折行（方案 A）：单行放不下时不在字中间硬断（会产生孤字成行），
     * 而是按「 · 」分段贪心装箱——每行尽量多装完整语义段，断点永远落在分隔符上：
     * ```
     * ARRI 709 · 基础影视
     * 干净中性
     * ```
     * 无「 · 」或未超宽时保持原文；限宽变化（旋转/完美/HDR 显隐）后按 hintRaw 重算。
     */
    private fun refreshHintWrap() {
        val raw = hintRaw
        val maxW = txtStyleHint.maxWidth
        if (raw.isEmpty() || maxW <= 0 || !raw.contains(" · ") ||
            txtStyleHint.paint.measureText(raw) <= maxW
        ) {
            setHintTextInternal(raw)
            return
        }
        val segs = raw.split(" · ")
        val lines = ArrayList<String>(segs.size)
        var cur = segs[0]
        for (i in 1 until segs.size) {
            val cand = "$cur · ${segs[i]}"
            if (txtStyleHint.paint.measureText(cand) <= maxW) cur = cand else {
                lines.add(cur); cur = segs[i]
            }
        }
        lines.add(cur)
        setHintTextInternal(lines.joinToString("\n"))
    }

    /** 回写折行文本（旁路 hintInternalSet 防 TextWatcher 回环）；内容相同不写避免无谓重排 */
    private fun setHintTextInternal(t: String) {
        if (txtStyleHint.text?.toString() == t) return
        hintInternalSet = true
        txtStyleHint.text = t
        hintInternalSet = false
    }

    private fun fallbackMsg(style: ColorEngine.Style): String =
        if (style == ColorEngine.Style.LM9) "「${style.label}」模型不可用，已切换为 M9"
        else if (ColorEngine.Style.isLutOnly(style)) "「${style.label}」LUT 加载失败，已输出原图"
        else if (ColorEngine.Style.isCcd(style)) "「${style.label}」模型不可用，已降级为 LUT 模拟（详情见日志）"
        else "「${style.label}」完整链路不可用，已降级为 LUT 模拟"

    private fun toast(msg: String) {
        // 批量导出期间每张一条 toast 会刷屏（12 张就是 12 条排队)：改投浮层次行，
        // 一次性结论由结束时的汇总弹窗交代，逐张细节在 DiagLog 里
        if (batchExportRunning && ::batchSub.isInitialized) {
            batchSub.text = msg
            return
        }
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

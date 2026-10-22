package com.leicamoments.app

import android.content.ContentValues
import android.content.Context
import com.codex.leicaapp.LeicaMark
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 超分任务单例：脱离 Activity 生命周期，由前台服务驱动，UI 通过监听器观察。
 * 状态机 Idle / Running(progress) / Done / Failed；Done/Failed 需 UI consume() 归位。
 */
object SuperResTask {

    sealed class State {
        object Idle : State()
        data class Running(val progress: Int) : State()
        data class Done(val width: Int, val height: Int, val jpeg: ByteArray, val elapsedMs: Long) : State()
        data class Failed(val message: String) : State()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    var state: State = State.Idle
        private set

    /** 待处理输入（Activity 写入、服务读取，避免 Intent 携带大字节超 Binder 限制） */
    @Volatile
    var pendingInput: ByteArray? = null

    /** 超分前先合成的色彩（原分辨率套用，避免 SR 后在 48MP 上跑原生滤镜链崩溃） */
    @Volatile
    var pendingStyle: LeicaEngine.Style? = null

    /** true=快速模型（SPAN INT8） */
    @Volatile
    var pendingFast: Boolean = false

    /** true=「完美」档：OffLineM9 后段全量（不套色、不放大、不自动导出） */
    @Volatile
    var pendingPerfect: Boolean = false

    /** 完美档所用风格（决定暖调 bin / M9+ 激进后推 / M10R 暗角） */
    @Volatile
    var pendingPerfectStyle: LeicaEngine.Style? = null

    /** 完美档 RAW 源（非空则走 OffLineM9 完整管线；由任务线程重读，避免常驻大字节） */
    @Volatile
    var pendingPerfectDngUri: android.net.Uri? = null

    /** 导入图自带的 HDR 增益图（导出时回嵌 UltraHDR） */
    @Volatile
    var pendingHdr: HdrGainMap.HdrData? = null

    /** 亮度档（±10，0=不调） */
    @Volatile
    var pendingBrightLevel: Int = 0

    /** 曝光档（±10，0=不调）：在原分辨率、套色之后烘焙，超分输入即带调整，48MP 端零额外开销 */
    @Volatile
    var pendingExposure: Int = 0

    /** 高光档（±10，0=不调） */
    @Volatile
    var pendingHighlightLevel: Int = 0

    /** 阴影档（±10，0=不调）：与曝光/黑色阶组合一次烘焙（先曝光后阴影再黑色阶） */
    @Volatile
    var pendingShadowLevel: Int = 0

    /** 黑色阶档（±10，0=不调） */
    @Volatile
    var pendingBlackLevel: Int = 0

    /** 饱和度档（±10，0=不调） */
    @Volatile
    var pendingSatLevel: Int = 0

    /** 对比度档（±10，0=不调） */
    @Volatile
    var pendingContrastLevel: Int = 0

    /** 柔光档（±10，0=不调；正=Orton 柔雾，负=清晰化） */
    @Volatile
    var pendingGlowLevel: Int = 0

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        l(state) // 立即回放当前态（Activity 重建/回前台恢复）
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun setState(s: State) {
        state = s
        mainHandler.post { listeners.forEach { it(s) } }
    }

    fun start(context: Context) {
        val bytes = pendingInput ?: return
        if (state is State.Running) return
        setState(State.Running(0))
        val app = context.applicationContext
        val t0 = android.os.SystemClock.elapsedRealtime()
        executor.execute {
            var hi: Bitmap? = null
            try {
                // ---- 完美档：OffLineM9 后段全量（不套色、不放大、不自动导出）----
                if (pendingPerfect) {
                    var pwork = bytes
                    val brL0 = pendingBrightLevel
                    val evL0 = pendingExposure
                    val hlL0 = pendingHighlightLevel
                    val shL0 = pendingShadowLevel
                    val blL0 = pendingBlackLevel
                    val saL0 = pendingSatLevel
                    val coL0 = pendingContrastLevel
                    val glL0 = pendingGlowLevel
                    if (brL0 != 0 || evL0 != 0 || hlL0 != 0 || shL0 != 0 || blL0 != 0 ||
                        saL0 != 0 || coL0 != 0 || glL0 != 0
                    ) {
                        val tL = android.os.SystemClock.elapsedRealtime()
                        pwork = Shadows.applyStackToJpeg(pwork, brL0, evL0, hlL0, shL0, blL0, saL0, coL0, glL0)
                        DiagLog.i("Perfect", "亮度 ${brL0}/曝光 ${evL0}/高光 ${hlL0}/阴影 ${shL0}/黑色阶 ${blL0}/饱和度 ${saL0}/对比度 ${coL0}/柔光 ${glL0} 已烘焙 耗时 ${android.os.SystemClock.elapsedRealtime() - tL}ms")
                    }
                    val style = pendingPerfectStyle ?: LeicaEngine.Style.M9
                    val tP = android.os.SystemClock.elapsedRealtime()
                    // RAW 源：此处重读 DNG（不常驻内存），走 OffLineM9 完整管线；读失败自动回落后段
                    val dng = pendingPerfectDngUri?.let { u ->
                        try {
                            app.contentResolver.openInputStream(u)?.use { it.readBytes() }
                        } catch (t: Throwable) {
                            DiagLog.e("Perfect", "DNG 重读失败，回落后段", t)
                            null
                        }
                    }
                    if (dng != null) DiagLog.i("Perfect", "完整管线输入 DNG ${dng.size / 1048576}MB")
                    val out = LeicaEngine.processPerfect(app, pwork, style, dng) { p -> setState(State.Running(p)) }
                        ?: throw IllegalStateException("完美档处理失败")
                    DiagLog.i("Perfect", "完美档输出 ${style.label} ${out.size}B 耗时 ${android.os.SystemClock.elapsedRealtime() - tP}ms")
                    // 不落盘、不打徽章：完美档完成后由用户手动导出（导出时才打徽章/回嵌 HDR）
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(out, 0, out.size, b)
                    setState(State.Done(b.outWidth, b.outHeight, out,
                        android.os.SystemClock.elapsedRealtime() - t0))
                    return@execute
                }

                // 先在原分辨率套用所选色彩（原生滤镜链的安全输入量级），SR 处理已着色图
                var work = bytes
                pendingStyle?.let { style ->
                    work = LeicaEngine.process(app, bytes, style)
                }
                // 亮度+曝光+高光+阴影+黑色阶组合烘焙（原分辨率，套色之后、超分之前；单次 decode/encode，EXIF 回填）
                val brL = pendingBrightLevel
                val evL = pendingExposure
                val hlL = pendingHighlightLevel
                val shL = pendingShadowLevel
                val blL = pendingBlackLevel
                val saL = pendingSatLevel
                val coL = pendingContrastLevel
                val glL = pendingGlowLevel
                if (brL != 0 || evL != 0 || hlL != 0 || shL != 0 || blL != 0 ||
                    saL != 0 || coL != 0 || glL != 0
                ) {
                    val tL = android.os.SystemClock.elapsedRealtime()
                    work = Shadows.applyStackToJpeg(work, brL, evL, hlL, shL, blL, saL, coL, glL)
                    DiagLog.i("SuperRes", "亮度 ${brL}/曝光 ${evL}/高光 ${hlL}/阴影 ${shL}/黑色阶 ${blL}/饱和度 ${saL}/对比度 ${coL}/柔光 ${glL} 已烘焙 耗时 ${android.os.SystemClock.elapsedRealtime() - tL}ms")
                }

                val decoded = ImageUtils.fromJpeg(work) ?: throw IllegalStateException("解码失败")
                val input = if (maxOf(decoded.width, decoded.height) > SuperRes.MAX_INPUT_EDGE) {
                    val scaled = scaleBitmap(decoded, SuperRes.MAX_INPUT_EDGE)
                    if (scaled !== decoded) decoded.recycle()
                    scaled
                } else decoded

                val result = SuperRes.upscale(app, input, pendingFast) { p -> setState(State.Running(p)) }
                input.recycle()
                hi = result

                // 回填源 EXIF（机型/拍摄参数/GPS）：toJpeg 从 Bitmap 重编码会整段丢失
                val workBytes = com.codex.leicaapp.MadridLegendary.copyExif(
                    work, ImageUtils.toJpeg(result, 95))
                // 自动导出：写入相机标记并按导出命名规则入库（与手动导出产物一致）
                val marked = try {
                    LeicaMark.mark(workBytes, SettingsStore.leicaBadgeEnabled(app))
                } catch (t: Throwable) {
                    DiagLog.e("SuperRes", "mark failed, export unmarked", t)
                    workBytes
                }
                // HDR 回嵌：徽章之后装配（徽章链会移除 XMP/MPF，不能放它前面）；
                // 未回嵌则裁掉 EOI 之后的残留二次图（增益图与超分后的主图尺寸必然不匹配）
                // C3 增益图重标定：套色后的 SDR 已不是原图，旧增益图的分母失配 → 按新 SDR 重算
                // （用超分前的工作版 SDR 作为新 SDR：色调与成品一致，尺寸小、解码便宜）
                val hdrSrc = if (pendingHdr != null && work !== bytes) {
                    HdrGainMap.rebakeJpeg(bytes, work, pendingHdr!!) ?: pendingHdr
                } else pendingHdr
                val exported = if (hdrSrc != null) HdrGainMap.embed(marked, hdrSrc)
                    else HdrGainMap.truncateToPrimary(marked)
                // 装配结果自检（detail 里的比例/容器结构进 DiagLog）
                HdrGainMap.verify(exported)
                val styleId = pendingStyle?.id ?: "orig"
                saveNamed(app, exported, "Leica_${styleId}_${System.currentTimeMillis()}.jpg")
                setState(State.Done(result.width, result.height, exported, android.os.SystemClock.elapsedRealtime() - t0))
            } catch (e: Throwable) {
                hi?.recycle()
                DiagLog.e("SuperRes", "SR failed", e)
                try {
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    java.io.File(app.cacheDir, "sr_error.txt").writeText(sw.toString())
                } catch (_: Throwable) {}
                setState(State.Failed(e.message?.takeLast(140) ?: (e.javaClass.simpleName + " 内存不足")))
            }
        }
    }

    /** UI 消费完 Done/Failed 后归位 */
    fun consume() {
        if (state is State.Done || state is State.Failed) setState(State.Idle)
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun saveNamed(context: Context, bytes: ByteArray, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaMoments")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            DiagLog.e("SuperRes", "save failed", e)
        }
    }
}

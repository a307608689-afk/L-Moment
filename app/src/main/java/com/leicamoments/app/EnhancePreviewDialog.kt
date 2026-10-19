package com.leicamoments.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 增强预览：旧效果与新效果按强度**在像素域交叉淡化**（先各渲一张 ≤1100px 的图，
 * 再用 alpha 叠一层）。
 *
 * 为什么不是 bin 层混合：旧版本可能是 3D LUT、新版本是 A 路线参数，两种格式的
 * 字节不可混算；像素域混合对两条管线都成立，也顺带让"LUT → 参数"的升级路径
 * 可以预览。同一格式时结果与旧实现等价（三线性插值对 bin 值是线性的）。
 *
 * 旧图只渲一次并缓存，拖动强度时只重渲新图 + 一次 alpha 合成。
 * 点「应用」才回调强度，由调用方把结果固化成一个新版本文件；取消则无残留。
 */
class EnhancePreviewDialog private constructor(
    private val activity: Activity,
    base: Bitmap,
    private val renderOld: (Bitmap) -> Bitmap?,
    private val renderNew: (Bitmap, Float) -> Bitmap?,
    addedCount: Int,
    totalCount: Int,
    private val allowStrength: Boolean,
    private val onApply: (Float) -> Unit,
    private val onCancel: () -> Unit,
    private val onCleanup: () -> Unit
) {

    companion object {
        private const val PREVIEW_MAX = 1100
        private const val THROTTLE_MS = 80L

        /**
         * @param allowStrength 旧/新产物格式不同（旧 LUT → 新参数）时传 false：
         *                      无法按强度插值，滑块隐藏、按 100% 应用。
         * @param renderOld 把底图渲染成"旧版本效果"；只在首次调用，内部会缓存。
         * @param renderNew 把底图渲染成"新版本效果"；每次强度变化都会调用。
         * @param onCleanup 弹窗关闭时调用，调用方在这里清自己注册的内存引用。
         */
        fun show(
            activity: Activity,
            base: Bitmap,
            addedCount: Int,
            totalCount: Int,
            allowStrength: Boolean,
            renderOld: (Bitmap) -> Bitmap?,
            renderNew: (Bitmap, Float) -> Bitmap?,
            onApply: (Float) -> Unit,
            onCancel: () -> Unit,
            onCleanup: () -> Unit = {}
        ): EnhancePreviewDialog {
            val d = EnhancePreviewDialog(
                activity, base, renderOld, renderNew, addedCount, totalCount,
                allowStrength, onApply, onCancel, onCleanup
            )
            d.dialog.show()
            return d
        }
    }

    private val dialog = Dialog(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val ivPreview = ImageView(activity)
    private val tvStrength = TextView(activity)
    private var tvApply: TextView? = null

    /** 预览底图（必要时是缩小副本，dialog 自己回收）。 */
    private var baseSmall: Bitmap? = null
    private var ownsBase = false

    /** 旧效果渲染结果，只算一次。 */
    private var oldPreview: Bitmap? = null

    private var previewBitmap: Bitmap? = null
    private var strengthPercent = if (allowStrength) 70 else 100
    private var pendingStrength = strengthPercent / 100f
    private var generation = 0
    private var applied = false
    private var destroyed = false

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)

        val scaled = if (maxDim(base) > PREVIEW_MAX) scaleDown(base, PREVIEW_MAX) else base
        baseSmall = scaled
        ownsBase = scaled !== base

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFF232326.toInt())   // 与历史版本面板同色，避免两套深灰
                cornerRadius = dp(20).toFloat()
            }
        }

        root.addView(label("增强预览", 17f, 0xFFFFFFFF.toInt(), true))
        val tvInfo = label("参考图共 $totalCount 张（本次新增 $addedCount 张）", 12f, 0xFF8E8E93.toInt(), false)
        tvInfo.setPadding(0, dp(4), 0, dp(12))
        root.addView(tvInfo)

        ivPreview.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            previewHeight()
        )
        ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
        ivPreview.setBackgroundColor(0xFF000000.toInt())
        ivPreview.clipToOutline = true
        ivPreview.background = GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            cornerRadius = dp(12).toFloat()
        }
        root.addView(ivPreview)

        if (allowStrength) {
            tvStrength.text = "强度 $strengthPercent%"
            tvStrength.setTextColor(0xFFFFFFFF.toInt())
            tvStrength.textSize = 13f
            tvStrength.setPadding(0, dp(14), 0, dp(6))
            root.addView(tvStrength)

            val seek = SeekBar(activity).apply {
                max = 100
                progress = strengthPercent
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    strengthPercent = progress
                    tvStrength.text = "强度 $progress%"
                    requestPreview(progress / 100f)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    requestPreview(strengthPercent / 100f, immediate = true)
                }
            })
            root.addView(seek)
        }

        val hint = if (allowStrength) "拖动强度看效果 · 应用后才写入新版本"
        else "新版本为模型参数，无法与旧版 LUT 按强度混合 · 按 100% 应用"
        val tvHint = label(hint, 11f, 0xFF6E6E73.toInt(), false)
        tvHint.setPadding(0, dp(2), 0, dp(14))
        root.addView(tvHint)

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = button("取消", 0xFF2C2C2E.toInt(), 0xFFC7C7CC.toInt())
        val apply = button("应用", 0xFFC8102E.toInt(), Color.WHITE)
        tvApply = apply
        cancel.setOnClickListener { dialog.dismiss() }
        apply.setOnClickListener {
            applied = true
            val s = if (allowStrength) strengthPercent / 100f else 1f
            dialog.dismiss()
            onApply(s)
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            rightMargin = dp(6)
        })
        buttons.addView(apply, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(6)
        })
        root.addView(buttons)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
                .coerceAtMost(dp(560))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        dialog.setOnDismissListener {
            destroyed = true
            generation++
            main.removeCallbacksAndMessages(null)
            worker.shutdownNow()
            runCatching { onCleanup() }
            previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            previewBitmap = null
            oldPreview?.let { if (!it.isRecycled) it.recycle() }
            oldPreview = null
            if (ownsBase) baseSmall?.let { if (!it.isRecycled) it.recycle() }
            baseSmall = null
            if (!applied) onCancel()
        }

        requestPreview(pendingStrength, immediate = true)
    }

    // ---- 预览 ----

    private val previewTask = Runnable {
        val gen = ++generation
        val strength = pendingStrength
        val base = baseSmall ?: return@Runnable
        try {
            worker.execute {
                val old = oldPreview ?: renderOld(base)?.also { oldPreview = it }
                val fresh = renderNew(base, strength)
                val bmp = compose(old, fresh, strength)
                main.post {
                    if (destroyed || gen != generation) {
                        bmp?.recycle()
                        return@post
                    }
                    if (bmp == null) return@post
                    val prev = previewBitmap
                    previewBitmap = bmp
                    ivPreview.setImageBitmap(bmp)
                    if (prev !== bmp) prev?.recycle()
                }
            }
        } catch (t: Throwable) {
            // 弹窗已关闭（worker 已 shutdown）时忽略
        }
    }

    /** 旧→新按 s 交叉淡化；返回的新位图归调用方所有（旧图缓存不动）。 */
    private fun compose(old: Bitmap?, fresh: Bitmap?, s: Float): Bitmap? {
        val t = s.coerceIn(0f, 1f)
        if (old == null || old.isRecycled) return fresh
        if (fresh == null || fresh.isRecycled) return old.copy(Bitmap.Config.ARGB_8888, false)
        if (t >= 0.999f) {
            return if (fresh === old) old.copy(Bitmap.Config.ARGB_8888, false) else fresh
        }
        val b = if (fresh.width == old.width && fresh.height == old.height) fresh
        else Bitmap.createScaledBitmap(fresh, old.width, old.height, true).also {
            if (fresh !== it) fresh.recycle()
        }
        val out = Bitmap.createBitmap(old.width, old.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(old, 0f, 0f, null)
        canvas.drawBitmap(b, 0f, 0f, Paint().apply { alpha = (t * 255).toInt() })
        if (b !== old && b !== out) b.recycle()
        return out
    }

    private fun requestPreview(strength: Float, immediate: Boolean = false) {
        if (destroyed) return
        pendingStrength = strength
        main.removeCallbacks(previewTask)
        main.postDelayed(previewTask, if (immediate) 0L else THROTTLE_MS)
    }

    // ---- 视图 ----

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, bg: Int, fg: Int): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(22).toFloat()
            }
        }

    private fun previewHeight(): Int {
        val screen = activity.resources.displayMetrics.heightPixels
        return (screen * 0.42f).toInt().coerceIn(dp(180), dp(380))
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun maxDim(bmp: Bitmap): Int = maxOf(bmp.width, bmp.height)

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val m = maxDim(src)
        if (m <= maxDim) return src
        val ratio = maxDim.toFloat() / m
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}

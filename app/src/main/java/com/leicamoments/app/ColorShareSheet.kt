package com.leicamoments.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

/**
 * 分享面板：缩略图 + 名字 + 两条通道的取舍说明。
 *
 * 两个出口：
 * - 「分享色彩文件」= 单条 `.lmc`（成品 LUT/参数 + 全部参考图指纹），完整能力；
 * - 「复制轻量指令」= 17³ LUT 文本码（约 6 KB），对方只能直接套用。
 *
 * 模型色彩只发文件：它的成品是 A 路线参数、没有 LUT 文件，指令通道必然失败，
 * 故调用方传 `withTextChannel=false`，面板不渲染指令按钮与状态行。
 *
 * 轻量指令在后台生成，所以面板先开、状态后到：按钮在 [Handle.ready] 之前禁用，
 * 用户不会复制到半截字符串。超出 [ColorShareCode.MAX_TEXT_CHARS] 或这份色彩
 * 根本烘不出 LUT 时，复制按钮保持在灰色禁用态，并在下方小字说明原因。
 */
object ColorShareSheet {

    private const val BG = 0xFF232326.toInt()
    private const val CARD = 0xFF2C2C2F.toInt()
    private const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    private const val TEXT_SECONDARY = 0xFF8E8E93.toInt()
    private const val BTN_SECONDARY = 0xFF3A3A3C.toInt()
    private const val ACCENT = 0xFFFF453A.toInt()
    private const val DANGER = 0xFFFF6B63.toInt()

    /** 面板句柄：后台生成完成后由调用方回填。 */
    class Handle internal constructor(
        private val activity: Activity,
        private val dialog: BottomSheetDialog,
        private val status: TextView,
        private val btnText: TextView
    ) {
        private var code: String? = null
        private var dismissed = false

        /** 轻量指令生成完毕；超出长度上限时自行降级为禁用态。 */
        fun ready(text: String) {
            if (dismissed) return
            if (text.length > ColorShareCode.MAX_TEXT_CHARS) {
                refuse("内容较大（" + sizeOf(text) + "），请用「分享文件」")
                return
            }
            code = text
            status.setTextColor(TEXT_SECONDARY)
            status.text = "轻量指令 " + sizeOf(text) + " · 复制后到微信粘贴发送"
            btnText.isEnabled = true
            btnText.alpha = 1f
        }

        /** 生成失败 / 这份色彩无法降级成轻量指令：只剩发文件一条路。 */
        fun fail(message: String) {
            refuse(message)
        }

        private fun refuse(message: String) {
            if (dismissed) return
            code = null
            status.setTextColor(DANGER)
            status.text = message
            btnText.isEnabled = false
            btnText.alpha = 0.4f
        }

        fun currentCode(): String? = code

        fun isDismissed(): Boolean = dismissed

        internal fun markDismissed() {
            dismissed = true
        }

        internal fun context(): Activity = activity

        private fun sizeOf(text: String): String {
            val n = ColorShareCode.extractPayload(text)?.length ?: text.length
            return String.format(Locale.US, "%.1f KB", (n / 1024f).coerceAtLeast(0.1f))
        }
    }

    /**
     * @param thumb          缩略图（可为 null，此时显示占位色块）
     * @param onShareFile    用户点「分享色彩文件」时回调，由调用方生成 .lmc 并唤起分享
     * @param withTextChannel 是否渲染「复制轻量指令」通道；模型色彩传 false（只有文件出口）
     * @param onCopyText     用户点「复制轻量指令」时回调完整文本，由调用方写剪贴板并提示
     */
    fun show(
        activity: Activity,
        def: CustomStyleDef,
        thumb: Bitmap?,
        onShareFile: () -> Unit,
        withTextChannel: Boolean = true,
        onCopyText: (String) -> Unit
    ): Handle {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(BG, dp(20))
            setPadding(dp(20), dp(6), dp(20), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(16) + bottom)
            insets
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        titleRow.addView(TextView(activity).apply {
            text = "分享色彩"
            textSize = 17f
            setTextColor(TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val btnClose = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(TEXT_SECONDARY)
            val s = dp(28)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        titleRow.addView(btnClose)

        // 缩略图 + 名称 + 版本/参考图数量
        val headRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }
        val iv = ImageView(activity).apply {
            val s = dp(64)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(CARD, dp(12))
            clipToOutline = true
            if (thumb != null) setImageBitmap(thumb)
        }
        headRow.addView(iv)
        val infoCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(activity).apply {
            text = def.name
            textSize = 16f
            isSingleLine = true
            setTextColor(TEXT_PRIMARY)
        })
        infoCol.addView(TextView(activity).apply {
            text = "${def.versions.size} 个版本 · ${CustomStyleStore.refCount(def)} 张参考图"
            textSize = 12f
            isSingleLine = true
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(4), 0, 0)
        })
        headRow.addView(infoCol)

        val desc = TextView(activity).apply {
            text = if (withTextChannel) {
                "发文件：完整画质，对方导入后可继续套用和增强。\n" +
                    "复制指令：约 6 KB 文字，对方只能直接套用，不能再增强。"
            } else {
                // 模型色彩：成品 + 全部参考图缩略图，对方导入后仍是模型，可在「设置」里继续加图
                "发文件：成品色彩 + 全部参考图，对方导入后可在「设置」里继续加图增强。"
            }
            textSize = 12.5f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        }

        val btnFile = TextView(activity).apply {
            text = "分享色彩文件"
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, dp(23))
        }
        val hintFile = TextView(activity).apply {
            text = "完整画质 · 对方存下后从「设置 → 导入备份」导入"
            textSize = 11.5f
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(14))
        }

        val btnText = TextView(activity).apply {
            text = "复制轻量指令"
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            setTextColor(Color.WHITE)
            background = rounded(BTN_SECONDARY, dp(23))
            isEnabled = false
            alpha = 0.4f
        }
        val status = TextView(activity).apply {
            text = "正在生成轻量指令…"
            textSize = 11.5f
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(6), 0, 0)
        }

        root.addView(titleRow)
        root.addView(headRow)
        root.addView(desc)
        root.addView(btnFile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(hintFile)
        if (withTextChannel) {
            root.addView(btnText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            root.addView(status)
        }

        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.navigationBarColor = BG
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    w.isNavigationBarContrastEnforced = false
                }
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightNavigationBars = false
                    isAppearanceLightStatusBars = false
                }
            }
        }
        val handle = Handle(activity, dialog, status, btnText)
        dialog.setOnDismissListener { handle.markDismissed() }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnFile.setOnClickListener { onShareFile() }
        btnText.setOnClickListener {
            if (!btnText.isEnabled) return@setOnClickListener
            val text = handle.currentCode() ?: return@setOnClickListener
            onCopyText(text)
            btnText.text = "已复制"
            btnText.background = rounded(BTN_SECONDARY, dp(23))
            btnText.isEnabled = false
            btnText.alpha = 0.4f
        }

        dialog.show()
        return handle
    }

    private fun rounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    private fun topRounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        val r = radiusPx.toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
}

package com.lmcamera.app

import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 弹窗限宽工具 —— 平板/折叠适配的弹窗统一出口。
 *
 * 手机档（窗口 <600dp）一律原样返回零影响；宽屏档把内容限到 [maxWidthDp] 并水平居中，
 * 避免列表行/卡片从屏幕左缘拉到右缘的「拉满」观感。所有弹窗改宽只动这里一处语义。
 */
object DialogWidth {

    /** 限宽的默认值：列表/卡片类弹窗 560dp */
    const val SHEET_MAX_DP = 560

    /** AI 面板类（大内容）720dp */
    const val AI_MAX_DP = 720

    /** 设置侧内容列限宽 520dp */
    const val CONTENT_MAX_DP = 520

    /** 窗口两侧最小留白（dp） */
    private const val EDGE_DP = 16

    /** 限宽目标像素：min(maxDp, 窗口宽 − 两侧留白) */
    private fun maxPx(ctx: android.content.Context, maxDp: Int): Int {
        val dm = ctx.resources.displayMetrics
        val cap = (maxDp * dm.density).toInt()
        val edge = (2 * EDGE_DP * dm.density).toInt()
        return minOf(cap, dm.widthPixels - edge)
    }

    /** 窗口宽是否达到宽屏档（≥600dp）；手机档调用方应直接跳过限宽 */
    fun isWide(ctx: android.content.Context): Boolean =
        ctx.resources.configuration.screenWidthDp >= FormFactor.WIDE_MIN_DP

    /**
     * 普通 Dialog（android.app.Dialog / AppCompatDialog）限宽居中。
     * 宽屏时 window.setLayout(width, height) —— height 保持调用方既有语义（WRAP 或比例高）。
     */
    fun clamp(dialog: Dialog, maxWidthDp: Int = SHEET_MAX_DP, heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        try {
            if (!isWide(dialog.context)) return
            val w = dialog.window ?: return
            w.setLayout(maxPx(dialog.context, maxWidthDp), heightPx)
            w.setGravity(Gravity.CENTER)
        } catch (_: Throwable) {
            // 限宽失败不阻塞弹窗功能
        }
    }

    /**
     * BottomSheetDialog 限宽：宽屏时把 bottom sheet 内容视图限到 [maxWidthDp] 并水平居中，
     * 滑入交互/手势下滑关闭等行为不变（仍是 BottomSheet，只是不再横向拉满）。
     */
    fun clampSheet(dialog: BottomSheetDialog, maxWidthDp: Int = SHEET_MAX_DP) {
        try {
            if (!isWide(dialog.context)) return
            val sheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return
            val lp = sheet.layoutParams
            lp.width = maxPx(dialog.context, maxWidthDp)
            if (lp is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                lp.gravity = Gravity.CENTER_HORIZONTAL
            }
            sheet.layoutParams = lp
        } catch (_: Throwable) {
            // 限宽失败不阻塞弹窗功能
        }
    }

    /**
     * 设置侧内容列限宽（≥600dp 限到 [maxDp] 居中，手机档原样）。
     * 视图必须已挂到父容器（layoutParams 就位）；行样式/行高/边距全部不动，
     * 依赖既有圆角卡片底在两侧纯黑背景上自然成列。
     */
    fun clampColumn(view: android.view.View, maxDp: Int = CONTENT_MAX_DP) {
        try {
            if (!isWide(view.context)) return
            val lp = view.layoutParams ?: return
            lp.width = maxPx(view.context, maxDp)
            when (lp) {
                is LinearLayout.LayoutParams -> lp.gravity = Gravity.CENTER_HORIZONTAL
                is FrameLayout.LayoutParams -> lp.gravity = Gravity.CENTER_HORIZONTAL
            }
            view.layoutParams = lp
        } catch (_: Throwable) {
            // 限宽失败不阻塞页面功能
        }
    }
}

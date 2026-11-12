package com.lmcamera.app

import android.content.Context
import android.content.res.Resources

/**
 * 窗口形态档位 —— 平板/折叠适配的唯一真源。
 *
 * 三档语义（全部只看窗口尺寸，绝不看设备型号）：
 * - [Form.PHONE]      窗口宽 <600dp：现状布局，像素级不动；
 * - [Form.TABLET_LAND] ≥600dp 且横屏（w>h）：双栏右栏（滤镜三列网格 + 固定调节区）；
 * - [Form.TABLET_PORT] ≥600dp 且竖屏：回落档（大号手机：底部横滑条保留、卡片 96dp、浮层 320dp）。
 *
 * 折叠屏折叠↔展开 = configuration 变化，[current] 自动翻档，无需任何设备判定。
 * 滞回：600dp 边界 ±16dp，防折叠过渡过程中在临界值附近反复翻档抖动。
 * 护栏：双栏档下预览区实际宽度 <[MIN_PREVIEW_DP] 时强制回落手机档。
 *
 * 判定为 O(1)，只在 Activity 创建与 onConfigurationChanged 时调用，无需缓存。
 */
enum class Form { PHONE, TABLET_LAND, TABLET_PORT }

object FormFactor {

    /** 宽屏档最低窗口宽度（dp） */
    const val WIDE_MIN_DP = 600

    /** 600dp 边界的滞回带（dp）：进宽屏要 ≥616，退回手机要 <584 */
    const val HYSTERESIS_DP = 16

    /** 双栏档预览区最小宽度（dp），低于此值强制回落手机档 */
    const val MIN_PREVIEW_DP = 400

    /** 右栏宽度上限（dp）与占窗口宽度比例 */
    const val RAIL_MAX_DP = 340
    const val RAIL_FRACTION = 0.38f

    /** 回落档（竖屏大屏）参数：滤镜卡 96dp、调节浮层 320dp、高度上限系数 */
    const val PORT_CARD_DP = 96
    const val PORT_PANEL_DP = 320
    const val PORT_PANEL_MAX_FRACTION = 0.60f

    /** 手机档基准参数（现状值，集中在此防散落字面量漂移） */
    const val PHONE_CARD_DP = 72
    const val PHONE_PANEL_DP = 272
    const val PHONE_PANEL_MAX_FRACTION = 0.42f

    /** 双栏档参数：三列卡列间距合计 48dp（列间距 8dp×2 + 网格左右 padding 8dp×2≈32，此处含余量） */
    const val LAND_GRID_INSET_DP = 48

    @Volatile private var lastForm: Form? = null
    @Volatile private var lastWidthDp = 0

    fun current(ctx: Context): Form = current(ctx.resources)

    fun current(res: Resources): Form {
        val cfg = res.configuration
        val w = cfg.screenWidthDp
        val h = cfg.screenHeightDp
        val f = compute(w, h)
        lastForm = f
        lastWidthDp = w
        return f
    }

    private fun compute(w: Int, h: Int): Form {
        val prev = lastForm
        val wide: Boolean = when (prev) {
            null -> w >= WIDE_MIN_DP
            Form.PHONE -> w >= WIDE_MIN_DP + HYSTERESIS_DP          // 手机档：越过 616 才进宽屏
            else -> w >= WIDE_MIN_DP - HYSTERESIS_DP                // 宽屏档：跌破 584 才回手机
        }
        if (!wide) return Form.PHONE
        // 护栏：双栏下预览剩余宽度不足则回落手机档（仅横屏双栏需要）
        if (w > h && w - railWidthDp(w) < MIN_PREVIEW_DP) return Form.PHONE
        return if (w > h) Form.TABLET_LAND else Form.TABLET_PORT
    }

    /** 双栏右栏宽度（dp）：min(340, 窗口宽×38%) */
    fun railWidthDp(windowWidthDp: Int): Int =
        minOf(RAIL_MAX_DP, (windowWidthDp * RAIL_FRACTION).toInt())

    /** 调节浮层宽度（dp）：手机 272 / 回落档 320（双栏档不使用浮层） */
    fun panelWidthDp(form: Form): Int = when (form) {
        Form.TABLET_PORT -> PORT_PANEL_DP
        else -> PHONE_PANEL_DP
    }

    /** 调节浮层高度上限系数：手机 0.42（现状）/ 回落档 0.60（双栏档不使用浮层） */
    fun panelMaxFraction(form: Form): Float = when (form) {
        Form.TABLET_PORT -> PORT_PANEL_MAX_FRACTION
        else -> PHONE_PANEL_MAX_FRACTION
    }

    /** 滤镜卡宽度（dp）：手机 72 / 回落档 96 / 双栏档返回 0 表示「跟随列宽 MATCH_PARENT」 */
    fun cardWidthDp(form: Form): Int = when (form) {
        Form.TABLET_PORT -> PORT_CARD_DP
        Form.TABLET_LAND -> 0
        else -> PHONE_CARD_DP
    }
}

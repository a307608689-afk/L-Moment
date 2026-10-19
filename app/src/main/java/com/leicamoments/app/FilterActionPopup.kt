package com.leicamoments.app

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 滤镜卡片长按菜单（自绘，深色）。
 *
 * 与 [CategoryPopup] 同一套视觉 token（`bg_popup` / `bg_popup_item` / 44dp 行高），
 * 但更窄且无副标题、无勾选。不直接用系统 PopupMenu：浅色模式下会变白底。
 */
object FilterActionPopup {

    /** 一行菜单项；[danger] = 删除这类不可逆操作，文字用强调红 */
    data class Action(val id: String, val title: String, val danger: Boolean = false)

    private const val PANEL_WIDTH_DP = 168
    private const val ROW_HEIGHT_DP = 44
    private const val EDGE_MARGIN_DP = 8
    private const val GAP_DP = 6

    fun show(anchor: View, actions: List<Action>, onPick: (Action) -> Unit): PopupWindow {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup)
            val p = dp(6)
            setPadding(p, p, p, p)
        }

        var popup: PopupWindow? = null

        actions.forEach { action ->
            val row = TextView(ctx).apply {
                text = action.title
                textSize = 15f
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(
                    Color.parseColor(if (action.danger) "#FF453A" else "#C7C7CC")
                )
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup_item)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup?.dismiss()          // 先关面板：回调里可能整条重建滤镜栏
                    onPick(action)
                }
            }
            panel.addView(row)
        }

        val width = dp(PANEL_WIDTH_DP)
        popup = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
        }

        // 居中于卡片，并钳制在屏幕内：卡片靠右时长按菜单不能溢出
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val margin = dp(EDGE_MARGIN_DP)
        val targetLeft = (loc[0] + (anchor.width - width) / 2)
            .coerceIn(margin, (screenW - width - margin).coerceAtLeast(margin))
        val dx = targetLeft - loc[0]

        // 上下选边必须自己算：滤镜条贴着屏幕底部，交给 showAsDropDown 自动翻转时
        // 它会把窗口压进剩余的几像素里，菜单被裁成一条背景板（内容全不可见）。
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val panelH = panel.measuredHeight
        val gap = dp(GAP_DP)
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val spaceBelow = frame.bottom - (loc[1] + anchor.height + gap)
        val dy = if (spaceBelow >= panelH) gap else -(anchor.height + panelH + gap)

        panel.alpha = 0f
        panel.translationY = -dp(6).toFloat()
        popup.showAsDropDown(anchor, dx, dy)
        panel.animate().alpha(1f).translationY(0f).setDuration(130).start()
        return popup
    }
}

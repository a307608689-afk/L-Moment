package com.lmcamera.app

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * 分类下拉面板（自绘，深色）。
 *
 * 主界面标题、设置页各开关、AI 服务商选择共用同一套样式。
 * 不用系统 PopupMenu：系统浅色模式下菜单会变白底，与本 App 纯黑风格冲突。
 *
 * 文字排布（2026-11 重做）：面板宽度按实际文字量自适应，一行装得下就保持单行；
 * 装不下（设置页的长标题/长说明）就整体改成「标题在上、说明在下」的两行，
 * 不再把文字截断成「关闭（默…」。整体高于屏幕可用空间时套滚动容器。
 */
object CategoryPopup {

    /** 一行数据：分类 id / 主标题 / 右侧副标题（两行排布时作为标题下方的说明） */
    data class Item(val id: String, val title: String, val subtitle: String)

    private const val PANEL_MIN_WIDTH_DP = 220
    private const val PANEL_MAX_WIDTH_DP = 288
    /** 面板与屏幕左右边缘的最小留白 */
    private const val PANEL_SCREEN_MARGIN_DP = 16
    private const val PANEL_PADDING_DP = 6
    private const val ROW_MIN_HEIGHT_DP = 44
    private const val CHECK_WIDTH_DP = 22
    private const val ROW_PADDING_START_DP = 10
    private const val ROW_PADDING_END_DP = 12
    private const val TITLE_SUBTITLE_GAP_DP = 8
    private const val TITLE_SIZE_SP = 15f
    private const val SUBTITLE_SIZE_SP = 12f

    private val COLOR_CHECK = Color.parseColor("#FF453A")
    private val COLOR_TITLE = Color.parseColor("#C7C7CC")
    private val COLOR_SUBTITLE = Color.parseColor("#8E8E93")
    private val COLOR_DIVIDER = Color.parseColor("#2C2C2E")

    /**
     * 在 [anchor] 下方弹出列表。
     * @param selectedId 当前选中项 id（点勾显示）
     * @param centerOnAnchor true=面板与 anchor 水平居中对齐；false=左对齐
     * @param onDismiss 面板关闭时回调（调用方据此复位标题箭头）
     * @param footer 分隔线之后的独立入口（如「模型色彩」）。与 [items] 语义不同：
     *        它是跨界面跳转而非分类切换，故不画勾选位、右侧给箭头。
     */
    fun show(
        anchor: View,
        items: List<Item>,
        selectedId: String?,
        centerOnAnchor: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        footer: List<Item> = emptyList(),
        /** true=面板右缘与 anchor 右缘对齐（贴右边缘的钮用它，否则会被屏幕夹到左边） */
        alignEnd: Boolean = false,
        onPick: (Item) -> Unit
    ): PopupWindow {
        val ctx = anchor.context
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val panelPad = dp(PANEL_PADDING_DP)
        val rowPadStart = dp(ROW_PADDING_START_DP)
        val rowPadEnd = dp(ROW_PADDING_END_DP)
        val checkWidth = dp(CHECK_WIDTH_DP)
        val gap = dp(TITLE_SUBTITLE_GAP_DP)
        val rowMinHeight = dp(ROW_MIN_HEIGHT_DP)

        var popup: PopupWindow? = null

        // ---- 1) 先量文字：决定面板宽度，以及是否改用上下两行 ----
        fun measureWidth(label: String, sizeSp: Float): Int {
            val probe = TextView(ctx).apply {
                text = label
                textSize = sizeSp
            }
            probe.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return probe.measuredWidth
        }

        val rowFixed = panelPad * 2 + rowPadStart + rowPadEnd + checkWidth
        var desiredWidth = dp(PANEL_MIN_WIDTH_DP)
        for (item in items) {
            var w = rowFixed + measureWidth(item.title, TITLE_SIZE_SP)
            if (item.subtitle.isNotEmpty()) {
                w += gap + measureWidth(item.subtitle, SUBTITLE_SIZE_SP)
            }
            desiredWidth = max(desiredWidth, w)
        }
        desiredWidth += dp(6) // 测量与真实排布的舍入余量
        val maxWidth = min(
            dp(PANEL_MAX_WIDTH_DP),
            dm.widthPixels - dp(PANEL_SCREEN_MARGIN_DP * 2)
        )
        val width = min(desiredWidth, maxWidth)
        // 一行排不下 → 整个面板统一改两行（不混排，保持行高节奏一致）
        val stacked = width < desiredWidth

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(panelPad, panelPad, panelPad, panelPad)
        }

        fun newRow(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup_item)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (stacked) ViewGroup.LayoutParams.WRAP_CONTENT else rowMinHeight
            )
            minimumHeight = rowMinHeight
            if (stacked) {
                setPadding(rowPadStart, dp(8), rowPadEnd, dp(8))
            } else {
                setPadding(rowPadStart, 0, rowPadEnd, 0)
            }
            isClickable = true
            isFocusable = true
        }

        fun itemRow(item: Item, selected: Boolean): LinearLayout {
            val row = newRow()
            row.addView(TextView(ctx).apply {
                text = "✓"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (selected) COLOR_CHECK else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    checkWidth, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            val titleColor = if (selected) Color.WHITE else COLOR_TITLE
            if (stacked) {
                // 两行：标题在上（最多 2 行），说明在下（自由换行，不截断）
                val column = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                column.addView(TextView(ctx).apply {
                    text = item.title
                    textSize = TITLE_SIZE_SP
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(titleColor)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
                if (item.subtitle.isNotEmpty()) {
                    column.addView(TextView(ctx).apply {
                        text = item.subtitle
                        textSize = SUBTITLE_SIZE_SP
                        setTextColor(COLOR_SUBTITLE)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(2) }
                    })
                }
                row.addView(column)
            } else {
                // 单行（原样式）：宽度已按文字量留足，不再用 maxWidth 硬裁副标题
                row.addView(TextView(ctx).apply {
                    text = item.title
                    textSize = TITLE_SIZE_SP
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(titleColor)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                if (item.subtitle.isNotEmpty()) {
                    row.addView(TextView(ctx).apply {
                        text = item.subtitle
                        textSize = SUBTITLE_SIZE_SP
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(COLOR_SUBTITLE)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = gap }
                    })
                }
            }
            row.setOnClickListener {
                onPick(item)
                popup?.dismiss()
            }
            return row
        }

        fun footerRow(item: Item): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup_item)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowMinHeight
                )
                isClickable = true
                isFocusable = true
                setPadding(rowPadStart, 0, rowPadEnd, 0)
            }
            // 与分类项的文字左缘对齐：留出勾选位同宽空白
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(checkWidth, 1)
            })
            row.addView(TextView(ctx).apply {
                text = item.title
                textSize = TITLE_SIZE_SP
                isSingleLine = true
                setTextColor(COLOR_TITLE)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(TextView(ctx).apply {
                text = "▸"
                textSize = 13f
                setTextColor(COLOR_SUBTITLE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            row.setOnClickListener {
                onPick(item)
                popup?.dismiss()
            }
            return row
        }

        items.forEach { panel.addView(itemRow(it, it.id == selectedId)) }

        if (footer.isNotEmpty()) {
            panel.addView(View(ctx).apply {
                setBackgroundColor(COLOR_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            })
            footer.forEach { panel.addView(footerRow(it)) }
        }

        // ---- 2) 高度兜底：锚点靠下、列表又长时套滚动容器，避免下半截被系统裁掉 ----
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val panelHeight = panel.measuredHeight
        val anchorPos = IntArray(2)
        anchor.getLocationOnScreen(anchorPos)
        val visible = Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        val spaceBelow = visible.bottom - (anchorPos[1] + anchor.height) - dp(16)
        val spaceAbove = anchorPos[1] - visible.top - dp(16)
        val maxHeight = max(spaceBelow, spaceAbove)

        val panelBg = ContextCompat.getDrawable(ctx, R.drawable.bg_popup)
        val scrollable = maxHeight > 0 && panelHeight > maxHeight
        val root: View
        if (scrollable) {
            root = ScrollView(ctx).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                background = panelBg
                clipToOutline = true
                addView(
                    panel,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        } else {
            panel.background = panelBg
            root = panel
        }
        root.alpha = 0f
        root.translationY = -dp(6).toFloat()

        popup = PopupWindow(
            root,
            width,
            if (scrollable) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // 外部点击可关闭
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            setOnDismissListener { onDismiss?.invoke() }
        }

        // 右对齐：面板右缘贴 anchor 右缘（anchor 通常比面板窄，dx 为负才不会被屏幕边缘夹偏）
        val dx = when {
            alignEnd -> anchor.width - width
            centerOnAnchor -> (anchor.width - width) / 2
            else -> 0
        }
        popup.showAsDropDown(anchor, dx, dp(4))
        root.animate().alpha(1f).translationY(0f).setDuration(130).start()
        return popup
    }

    /**
     * 设置页直接复用：分类若干项 + 「记忆上次」。
     * 分类在设置里也全部列出（「我的色彩」为空时常驻），[customCount] 只影响其副标题。
     */
    fun settingItems(customCount: Int = 0): List<Item> =
        FilterCategory.values().map { Item(it.id, it.title, it.subtitle(customCount)) } +
            Item(SettingsStore.VALUE_LAST, "记忆上次", "上次所在分类")
}

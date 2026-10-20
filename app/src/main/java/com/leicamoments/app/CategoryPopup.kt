package com.leicamoments.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 分类下拉面板（自绘，深色）。
 *
 * 主界面标题与设置页「默认进入界面」共用同一套样式。
 * 不用系统 PopupMenu：系统浅色模式下菜单会变白底，与本 App 纯黑风格冲突。
 */
object CategoryPopup {

    /** 一行数据：分类 id / 主标题 / 右侧副标题 */
    data class Item(val id: String, val title: String, val subtitle: String)

    private const val PANEL_WIDTH_DP = 220
    private const val ROW_HEIGHT_DP = 44

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
        onPick: (Item) -> Unit
    ): PopupWindow {
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

        items.forEach { item ->
            val selected = item.id == selectedId
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup_item)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
                )
                isClickable = true
                isFocusable = true
                setPadding(dp(10), 0, dp(12), 0)
            }

            row.addView(TextView(ctx).apply {
                text = "✓"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.parseColor("#FF453A") else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            row.addView(TextView(ctx).apply {
                text = item.title
                textSize = 15f
                isSingleLine = true
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#C7C7CC"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            row.addView(TextView(ctx).apply {
                text = item.subtitle
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                // 副标题过长会把标题挤没（曾出现「开启（推荐）原图档保留全像素」叠在一起）
                maxWidth = dp(104)
                setTextColor(Color.parseColor("#8E8E93"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })

            row.setOnClickListener {
                onPick(item)
                popup?.dismiss()
            }
            panel.addView(row)
        }

        if (footer.isNotEmpty()) {
            panel.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#2C2C2E"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            })
            footer.forEach { item ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup_item)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
                    )
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(10), 0, dp(12), 0)
                }
                // 与分类项的文字左缘对齐：留出勾选位同宽空白
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(22), 1)
                })
                row.addView(TextView(ctx).apply {
                    text = item.title
                    textSize = 15f
                    isSingleLine = true
                    setTextColor(Color.parseColor("#C7C7CC"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = "▸"
                    textSize = 13f
                    setTextColor(Color.parseColor("#8E8E93"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
                row.setOnClickListener {
                    onPick(item)
                    popup?.dismiss()
                }
                panel.addView(row)
            }
        }

        val width = dp(PANEL_WIDTH_DP)
        popup = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // 外部点击可关闭
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            setOnDismissListener { onDismiss?.invoke() }
        }

        panel.alpha = 0f
        panel.translationY = -dp(6).toFloat()
        val dx = if (centerOnAnchor) (anchor.width - width) / 2 else 0
        popup.showAsDropDown(anchor, dx, dp(4))
        panel.animate().alpha(1f).translationY(0f).setDuration(130).start()
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

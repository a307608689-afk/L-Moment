package com.leicamoments.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史版本面板（S5）：单选一行，底部「回退」/「新增色彩」。
 *
 * 全自绘深色（#232326 + 上圆角 20dp），不吃系统浅色主题——App 是 M3 DayNight，
 * 默认 BottomSheet 在浅色系统下会是白底。列表新的在上（最近一次增强最先看到）。
 *
 * 只有「选中项 ≠ 当前版本」时两个动作才可用：
 * 回退 = 游标左移（时光倒流）；新增色彩 = 把该版本复制成独立新色彩（分叉保留），原色彩不动。
 */
object VersionHistorySheet {

    private const val BG = 0xFF232326.toInt()
    private const val ROW_SELECTED = 0xFF2C2C2F.toInt()
    private const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    private const val TEXT_ROW = 0xFFC7C7CC.toInt()
    private const val TEXT_SECONDARY = 0xFF8E8E93.toInt()
    private const val BTN_SECONDARY = 0xFF3A3A3C.toInt()
    private const val ACCENT = 0xFFFF453A.toInt()

    fun show(
        activity: Activity,
        def: CustomStyleDef,
        onRollback: (StyleVersion) -> Unit,
        onSplit: (StyleVersion) -> Unit
    ) {
        val current = CustomStyleStore.currentVersion(def) ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val versions = def.versions.reversed()
        var selected: StyleVersion = current
        // 行点击与 refresh 互相依赖：先占位，行建好后回填（局部函数需先声明后使用）
        var onRowClick: (StyleVersion) -> Unit = {}

        fun row(v: StyleVersion): View {
            val isCurrent = v.id == current.id
            val isSelected = v.id == selected.id
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(11), dp(10), dp(11))
                background = if (isSelected) rounded(ROW_SELECTED, dp(12)) else null
                isClickable = true
                setOnClickListener { onRowClick(v) }
            }
            r.addView(TextView(activity).apply {
                text = if (isCurrent) "●" else "○"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (isCurrent) ACCENT else TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(activity).apply {
                text = v.label
                textSize = 15f
                isSingleLine = true
                setTextColor(if (isSelected) TEXT_PRIMARY else TEXT_ROW)
            })
            col.addView(TextView(activity).apply {
                text = subtitle(v)
                textSize = 12f
                isSingleLine = true
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(2), 0, 0)
            })
            r.addView(col)
            if (isCurrent) {
                r.addView(TextView(activity).apply {
                    text = "✓"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(ACCENT)
                    layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            return r
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(BG, dp(20))
            setPadding(dp(20), dp(6), dp(20), dp(16))
        }
        // 全屏手势/三键导航下把按钮抬到导航条之上（decorFits 模式下 insets 为 0，不影响）
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
            text = "历史版本"
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

        val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun actionButton(label: String, primary: Boolean) = TextView(activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            setTextColor(if (primary) Color.WHITE else TEXT_ROW)
            background = rounded(if (primary) ACCENT else BTN_SECONDARY, dp(22))
        }

        val btnSplit = actionButton("新增色彩", false)
        val btnRollback = actionButton("回退", true)
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
            addView(
                btnSplit,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(10) }
            )
            addView(btnRollback, LinearLayout.LayoutParams(0, dp(44), 1f))
        }

        root.addView(titleRow)
        root.addView(listBox)
        root.addView(btnRow)

        fun refresh() {
            listBox.removeAllViews()
            versions.forEach { v -> listBox.addView(row(v)) }
            val changed = selected.id != current.id
            listOf(btnRollback, btnSplit).forEach {
                it.isEnabled = changed
                it.alpha = if (changed) 1f else 0.35f
            }
        }

        onRowClick = { v ->
            if (selected.id != v.id) {
                selected = v
                refresh()
            }
        }

        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            // M3 会给 sheet 容器上 colorSurface（浅色系统下是白的），必须清掉露出自绘圆角
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.window?.let { w ->
                // 底部手势条区域：默认跟系统浅色主题，且 Android 10+ 还会给透明导航栏叠一层
                // "对比度遮罩"（浅色系统下就是那条扎眼的白）。这里统一钉成面板色并关掉遮罩，
                // 系统栏图标同时改浅色，深底上才看得见。
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

        btnClose.setOnClickListener { dialog.dismiss() }
        btnRollback.setOnClickListener {
            dialog.dismiss()
            onRollback(selected)
        }
        btnSplit.setOnClickListener {
            dialog.dismiss()
            onSplit(selected)
        }

        refresh()
        dialog.show()
    }

    private fun subtitle(v: StyleVersion): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(v.createdAt))
        val pct = Math.round(v.strength * 100)
        val strength = if (pct in 1..99) " · 强度 $pct%" else ""
        return "${v.seedCount} 张参考图 · $time$strength"
    }

    private fun rounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    /** 只圆上面两角：面板贴屏幕底部，下沿要贴合 */
    private fun topRounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        val r = radiusPx.toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
}

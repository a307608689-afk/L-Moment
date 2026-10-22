package com.leicamoments.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * M7.2 候选风格面板：把 k-medoids 挑出的几套「参考集内的不同风格」横向摆开，
 * 每张卡是当前工作图的 640px 预览，点选后应用到全分辨率。
 *
 * 候选 0 是稳健中心（推荐/默认），其余按与中心的距离排序。面板取消 = 用候选 0。
 */
object StyleCandidateSheet {

    data class Item(val label: String, val preview: Bitmap, val deltaE: Float)

    fun show(activity: Activity, items: List<Item>, onPick: (Int) -> Unit, onCancel: () -> Unit) {
        if (items.isEmpty()) {
            onCancel()
            return
        }
        var picked = false
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
        }
        root.addView(TextView(activity).apply {
            text = "选择色彩倾向"
            setTextColor(Color.parseColor("#F5F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(activity).apply {
            text = "参考图里有 ${items.size} 种不同的色彩倾向，选一个最接近你想要的"
            setTextColor(Color.parseColor("#9A9AA0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(activity, 4), 0, dp(activity, 14))
        })

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cards = ArrayList<LinearLayout>()
        items.forEachIndexed { i, item ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 12).toFloat()
                    setColor(Color.parseColor("#1C1C1E"))
                    setStroke(dp(activity, 2), Color.TRANSPARENT)
                }
                setOnClickListener {
                    picked = true
                    dialog.dismiss()
                    onPick(i)
                }
            }
            val img = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(item.preview)
            }
            card.addView(img, LinearLayout.LayoutParams(dp(activity, 136), dp(activity, 136)))
            card.addView(TextView(activity).apply {
                text = item.label
                setTextColor(Color.parseColor("#F5F5F7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 6), 0, 0)
            })
            if (!item.deltaE.isNaN()) {
                card.addView(TextView(activity).apply {
                    text = if (i == 0) "参考均值" else "ΔE ${"%.1f".format(item.deltaE)}"
                    setTextColor(Color.parseColor("#8E8E93"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                })
            }
            val lp = LinearLayout.LayoutParams(dp(activity, 128), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(activity, 8)
            row.addView(card, lp)
            cards.add(card)
        }
        val hs = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(row)
        root.addView(hs)

        fun select(i: Int) {
            cards.forEachIndexed { k, c ->
                (c.background as? GradientDrawable)?.setStroke(
                    dp(activity, 2),
                    if (k == i) Color.parseColor("#0A84FF") else Color.TRANSPARENT
                )
            }
        }
        select(0)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (!picked) onCancel()
        }
        dialog.show()
    }

    private fun dp(activity: Activity, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()
}

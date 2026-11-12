package com.lmcamera.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 首次更新引导弹窗：只弹一次（[AiProviderStore.onboardingShown]）。
 *
 * 目的有三个：①宣布 AI 调色来了；②教会「色彩卡区域上滑」这个隐藏手势；
 * ③提前管理预期——AI 需要网络与已配置的服务，没配时首次使用会引导去设置。
 */
object AiOnboardingDialog {

    fun show(activity: Activity, onTry: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#141416"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
        }

        card.addView(TextView(activity).apply {
            text = "✨"
            textSize = 26f
            gravity = Gravity.START
        })
        card.addView(TextView(activity).apply {
            text = "AI 调色来了"
            textSize = 18f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        card.addView(TextView(activity).apply {
            text = "在色彩卡区域向上轻扫，告诉 AI 你想要的感觉。"
            textSize = 13.5f
            setTextColor(Color.parseColor("#B8B8BC"))
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        // 手势示意块：不用图片资产，用排版模拟「栏 → 上滑」
        card.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1C1C1F"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            addView(TextView(activity).apply {
                text = "↑"
                textSize = 22f
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
            })
            addView(TextView(activity).apply {
                text = "色彩卡区域上滑"
                textSize = 12f
                setTextColor(Color.parseColor("#8E8E93"))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        })

        card.addView(TextView(activity).apply {
            text = "它优先复用现成色彩并自动调参；目录里没有的风格，可以在 AI 面板上传参考图" +
                "（一次最多 5 张）让助手学习拟合并微调，保存与否由你决定。"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        card.addView(TextView(activity).apply {
            text = "需要网络。设置 → AI 服务 里有推荐预设（小米 MiMo / DeepSeek / 智谱 GLM），注册一次拿到 Key 粘贴即可。"
            textSize = 12f
            setTextColor(Color.parseColor("#5A5A5E"))
            setPadding(0, dp(10), 0, dp(4))
        })

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.END
        }
        val dismiss = TextView(activity).apply {
            text = "知道了"
            textSize = 14f
            setTextColor(Color.parseColor("#E8E8EA"))
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#59FFFFFF"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        val tryBtn = TextView(activity).apply {
            text = "立即试试 →"
            textSize = 14f
            setTextColor(Color.parseColor("#000000"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            setOnClickListener {
                dialog.dismiss()
                onTry()
            }
        }
        buttons.addView(dismiss)
        buttons.addView(tryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
        card.addView(buttons)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // 宽屏档限宽 560dp 居中（手机档仍为窗口宽-40dp 原样）
            val w = (activity.resources.displayMetrics.widthPixels - dp(40))
                .coerceAtMost(dp(560))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setCanceledOnTouchOutside(true)

        // 沉浸：卡片本身避让状态栏/小白条（对话框窗口默认不 edge-to-edge，这里主动开门再垫边距）
        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(card) { v: View, insets: WindowInsetsCompat ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(
                    top = dp(24) + bars.top,
                    bottom = dp(18) + bars.bottom
                )
                insets
            }
            ViewCompat.requestApplyInsets(card)
        }
        dialog.show()
    }
}

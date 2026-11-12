package com.lmcamera.app

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

/**
 * AI 面板宿主扩展：除了 [AiHost] 的工具能力，还需要几个只服务于 UI 的能力
 * （快照撤销、待保存的参考色彩）。
 *
 * 对比不再由本面板提供——预览长按对比是 App 既有手势，避免两套入口。
 */
interface AiUiHost : AiHost {
    fun snapshotParams(): JSONObject
    fun restoreParams(snapshot: JSONObject)
    fun hasPendingReferenceStyle(): Boolean
    fun pendingReferenceDefaultName(): String
    fun savePendingReferenceStyle(name: String): Boolean
    fun clearPendingReferenceStyle()
    fun onAiSheetClosed()
}

/**
 * AI 调色抽屉（BottomSheet）。
 *
 * UI 约定（与整体设计一致）：
 * - 纯黑底 + 白字 + 细描边，主按钮白底黑字，次按钮描边；
 * - 时间线在上、输入框常驻底部、CTA 只在有结果时出现；
 * - **元素不重叠**：内容区 ScrollView 独占剩余高度，输入区固定底部，二者永不互相覆盖；
 * - **沉浸**：状态栏与小白条区域由 inset 撑开，抽屉底色一直铺到屏幕边，把手不顶进状态栏。
 */
class AiSheetDialog(private val activity: MainActivity) {

    private val host: AiUiHost = activity
    private val dialog = BottomSheetDialog(activity)
    private val session = AiSession(activity, host)

    private lateinit var root: LinearLayout
    private lateinit var headerBox: LinearLayout
    private lateinit var bottomBox: LinearLayout
    private lateinit var timeline: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    /** AI 力度：整套方案打几折（100=原样）。改的是 activity.aiStrengthPct，下一条指令生效 */
    private var aiStrength = 100
    private lateinit var strBar: SeekBar
    private lateinit var strLabel: TextView
    private lateinit var busyChip: TextView
    private lateinit var chip: TextView
    private lateinit var status: TextView
    private lateinit var ctaRow: LinearLayout
    private lateinit var undoBtn: TextView
    /** 参考图附件条（输入框上方）：选图即挂载、可逐个移除，发送时才学习拟合 */
    private lateinit var attachRow: LinearLayout
    /** 参考图拟合进行中（毫秒级端侧推理，但挡重复发送） */
    @Volatile
    private var fitting = false

    private var provider: AiProvider? = null
    private val undoStack = ArrayDeque<JSONObject>()
    private var shown = false
    /** 导航栏（小白条）高度：键盘收起时输入区的默认让位 */
    private var navBottom = 0
    /** 键盘是否弹起（由可视区高度差测得） */
    private var kbVisible = false

    private val density: Float get() = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    // ---------------- 对外 ----------------

    fun show() {
        provider = AiProviderStore.currentProvider(activity)
        buildUi()
        bindSession()
        dialog.setOnDismissListener {
            // 只是收起界面：AI 继续在后台跑（用户常滑下去看预览），
            // 真正停止只发生在换图（[onImageChanged]）与页面销毁（[release]）。
            host.onAiSheetClosed()
            shown = false
        }
        dialog.show()
        // 宽屏档限宽 720dp 居中（保持 BottomSheet 交互：滑入/下滑关闭不变，只是不再横向拉满）
        DialogWidth.clampSheet(dialog, DialogWidth.AI_MAX_DP)
        shown = true

        // 高度：先从色彩栏位置展开到 85% 屏高（不是全屏——主预览要一直看得见）
        val dm = activity.resources.displayMetrics
        dialog.behavior.peekHeight = (dm.heightPixels * 0.85f).toInt()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        refreshProviderChip()
        refreshStatus()
        // 后台可能还在跑（用户滑下去看预览又上来）：恢复忙碌提示与待保存 CTA
        if (session.isBusy) applyBusy(true)
        if (host.hasPendingReferenceStyle()) showCta(host.pendingReferenceDefaultName())
        // 历史恢复优先：关掉抽屉看预览、或换了一张图再打开，对话都还在
        renderHistory()
        // 未发送的参考图附件恢复（进程被杀重进场景，缓存文件还在就原样挂回）
        refreshRefAttachments()
        // 首帧即分析：端侧算统计（不发网络请求）。
        // 「新对话」= 空历史，或历史最后一行是换图分隔线（换图后第一次打开要重新给判断）
        val history = session.history()
        val freshImage = history.isEmpty() || history.last().text.startsWith("—— 新照片")
        if (freshImage && host.hasImage()) {
            val stats = host.imageStats()
            if (stats != null) {
                addLine("这张图：${stats.optString("summary")}", muted = true)
            }
            addLine("说一句想要的感觉，或点「自动优化」直接出结果。")
        }
    }

    /**
     * 换图时由 MainActivity 调用：**这是唯一会中止在途请求的用户动作**。
     *
     * 语义（2026-11-02 用户拍板）：清空模型上下文（旧图的统计/结论对新图是噪声），
     * 但保留时间线记录供回看，并在换图处插一条分隔提示。
     */
    fun onImageChanged() {
        session.cancel()
        session.resetForNewImage()
        // 撤销栈是上一张图的参数快照：不清的话，换图后点「撤销」会把旧档位套到新图上
        synchronized(undoStack) { undoStack.clear() }
        refreshUndoBtn()
        if (dialog.isShowing) {
            appendRow("—— 新照片 · 上下文已重置 ——", muted = true)
            applyBusy(false)
            refreshStatus()
        }
    }

    /** 页面销毁时释放（连同在途请求）。 */
    fun release() {
        session.cancel()
        if (dialog.isShowing) dialog.dismiss()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    val isShowing: Boolean get() = dialog.isShowing

    // ---------------- UI 构建 ----------------

    private fun buildUi() {
        root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#0E0E10"))
            }
            // 明确 85% 屏高：MATCH_PARENT 会被 BottomSheet 撑到全屏，主预览就看不见了
            val h = (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }

        // 拖拽把手
        root.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#59FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        })

        // 标题行：AI 调色 + 撤销/对比 + 模型 chip
        headerBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(10))
        }
        headerBox.addView(TextView(activity).apply {
            text = "AI 调色"
            textSize = 16f
            setTextColor(Color.parseColor("#F2F2F2"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        // 忙碌指示放在标题旁：内容再多也不会被滚出视野（之前放在滚动区顶部，一多就看不到）
        busyChip = TextView(activity).apply {
            text = "处理中"
            textSize = 10.5f
            setTextColor(Color.parseColor("#C8C8CC"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#1C1C1F"))
                setStroke(dp(1), Color.parseColor("#2EFFFFFF"))
            }
            visibility = View.GONE
        }
        headerBox.addView(busyChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        headerBox.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        undoBtn = actionChip("↩ 撤销").apply {
            setOnClickListener {
                val snap = synchronized(undoStack) { undoStack.removeLastOrNull() }
                    ?: return@setOnClickListener
                host.restoreParams(snap)
                addLine("已撤销上一步调整")
                refreshUndoBtn()
            }
        }
        headerBox.addView(undoBtn)
        refreshUndoBtn()
        // 不再放「对比」按钮：预览长按对比是 App 既有手势，重复入口反而挤占标题行
        chip = actionChip("未配置").apply {
            // 限宽 + 省略：模型名过长时不能把左侧「AI 调色」标题挤没
            maxWidth = dp(150)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showProviderPicker() }
        }
        headerBox.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        root.addView(headerBox)

        // 内容区（唯一可滚动区）。
        // 用 NestedScrollView 而不是 ScrollView：嵌套滚动会让「滚历史」与「下拉收起抽屉」按
        // 手势方向/边界自然分工——内容没滚到顶时下拉是滚历史，到顶后继续下拉才收抽屉。
        scroll = NestedScrollView(activity).apply {
            isFillViewport = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        status = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(status)

        timeline = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(timeline)

        ctaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(4))
            visibility = View.GONE
        }
        content.addView(ctaRow)

        scroll.addView(content)
        root.addView(scroll)

        // 底部固定区：快捷 chip + 输入框（避让小白条，永不与内容区重叠）
        bottomBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E0E10"))
            }
        }

        val chips = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "✨ 自动优化" to AUTO,
            "更胶片" to "让画面更胶片感，影调柔一点",
            "天空更蓝" to "天空更蓝、更通透一些",
            "日落更暖" to "整体更暖、高光更柔和",
            "人像柔和" to "人像肤色更柔和自然"
        ).forEach { (label, text) ->
            chipRow.addView(actionChip(label).apply {
                setOnClickListener { submit(text) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        chips.addView(chipRow)
        bottomBox.addView(chips)

        // AI 力度：整套方案打几折（10~100，默认 100）。拖动即改，下一条指令生效
        val strengthRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        strLabel = TextView(activity).apply {
            text = "力度 $aiStrength%"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        strengthRow.addView(strLabel)
        strBar = SeekBar(activity).apply {
            max = 100
            progress = aiStrength
            androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.adj_track)?.let {
                progressDrawable = it
            }
            androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.adj_thumb)?.let {
                thumb = it
            }
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p.coerceAtLeast(10)
                    if (p < 10) s.progress = 10
                    aiStrength = v
                    strLabel.text = "力度 $v%"
                    activity.aiStrengthPct = v
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        strengthRow.addView(strBar)
        bottomBox.addView(strengthRow)

        // 参考图附件条：挂在输入框上方（选图 ≠ 发送；可逐个移除；发送时统一学习拟合）
        attachRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        bottomBox.addView(attachRow)

        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        // 图库按钮：自绘扁平图标（画框 + 太阳 + 山形，单色白描边，与面板黑白语言一致；
        // 原 emoji 会被系统渲染成彩色图，与纯黑 UI 不搭）。位于输入框与发送键之间。
        val galleryBtn: View = object : View(activity) {
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = Color.parseColor("#F2F2F2")
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#F2F2F2")
            }
            private val path = Path()

            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // 与发送键同高（40dp）：三元素单行精确等高（输入框 minHeight 同值）
                setMeasuredDimension(dp(40), dp(40))
            }

            override fun onDraw(canvas: Canvas) {
                val s = width.toFloat()
                // 外框：留出与按钮背景一致的呼吸边
                val l = s * 0.22f
                val t = s * 0.24f
                val r = s * 0.78f
                val b = s * 0.76f
                val bw = r - l
                val bh = b - t
                // 圆角画框
                canvas.drawRoundRect(l, t, r, b, s * 0.07f, s * 0.07f, line)
                // 太阳（右上实心圆）
                canvas.drawCircle(l + bw * 0.68f, t + bh * 0.30f, s * 0.045f, fill)
                // 山形：主峰 + 次峰，底边贴框内沿
                path.reset()
                path.moveTo(l + bw * 0.08f, b - s * 0.045f)
                path.lineTo(l + bw * 0.42f, t + bh * 0.38f)
                path.lineTo(l + bw * 0.66f, b - s * 0.045f)
                path.moveTo(l + bw * 0.52f, b - s * 0.045f)
                path.lineTo(l + bw * 0.74f, t + bh * 0.55f)
                path.lineTo(r - bw * 0.08f, b - s * 0.045f)
                canvas.drawPath(path, line)
            }
        }.apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1C1C1F"))
                setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
            }
            setOnClickListener { activity.launchAiRefPicker() }
        }
        input = EditText(activity).apply {
            hint = "描述你想要的感觉…"
            setHintTextColor(Color.parseColor("#5A5A5E"))
            setTextColor(Color.parseColor("#F2F2F2"))
            textSize = 13.5f
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1C1C1F"))
                setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 3
            // 单行态与两颗钮精确等高（40dp）；wrap 保留——多行时输入框仍会正常长高
            minHeight = dp(40)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(input)
        // 图库钮：输入框与发送键之间（左右各 8dp，与发送键同节奏）
        inputRow.addView(galleryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        sendBtn = TextView(activity).apply {
            text = "发送"
            textSize = 13.5f
            setTextColor(Color.parseColor("#000000"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(true)
            setOnClickListener {
                if (session.isBusy) {
                    // 处理中：同一颗键变「停止」，用户随时可以中断（不留半成品状态）
                    session.cancel()
                    applyBusy(false)
                    addLine("已停止本次处理", muted = true)
                } else {
                    submit(input.text?.toString().orEmpty())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { leftMargin = dp(8) }
        }
        inputRow.addView(sendBtn)
        bottomBox.addView(inputRow)
        root.addView(bottomBox)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // ADJUST_NOTHING：窗口不随键盘 resize（否则 ime inset 不会派发到抽屉），
        // 键盘高度由下面手动测出来再抬输入区——这是 BottomSheet + 键盘的可靠组合。
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            WindowCompat.setDecorFitsSystemWindows(window, false)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, insets: WindowInsetsCompat ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                navBottom = bars.bottom
                headerBox.updatePadding(top = dp(14) + bars.top)
                if (!kbVisible) bottomBox.updatePadding(bottom = dp(10) + bars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(root)

            // 键盘双通道：①窗口 ime inset（Android 11+ 准确）；②可视区高度差（老 ROM 兜底）。
            // 任一通道报出键盘高度就抬输入区，避免不同 ROM 的度量差异导致输入框被挡住。
            window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                if (!dialog.isShowing) return@addOnGlobalLayoutListener
                val ime = ViewCompat.getRootWindowInsets(window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val r = android.graphics.Rect()
                window.decorView.getWindowVisibleDisplayFrame(r)
                val diff = (window.decorView.rootView.height - r.bottom).coerceAtLeast(0)
                val kb = maxOf(ime, if (diff > dp(120)) diff else 0)
                kbVisible = kb > dp(120)
                val target = dp(10) + maxOf(kb, navBottom)
                if (bottomBox.paddingBottom != target) bottomBox.updatePadding(bottom = target)
            }
        }
    }

    /** 主/次按钮底：实心白（发送）与描边（停止）。 */
    private fun pill(filled: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        if (filled) {
            setColor(Color.parseColor("#FFFFFF"))
        } else {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.parseColor("#59FFFFFF"))
        }
    }

    private fun actionChip(label: String): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#E8E8EA"))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#14FFFFFF"))
            setStroke(dp(1), Color.parseColor("#2EFFFFFF"))
        }
    }

    // ---------------- 交互 ----------------

    private fun submit(raw: String) {
        val text = raw.trim()
        val refs = if (this::attachRow.isInitialized) RefAttachmentCache.restore(activity) else emptyList()
        if (text.isEmpty() && refs.isEmpty()) return
        if (session.isBusy) {
            addLine("上一条还在处理，稍等", muted = true)
            return
        }
        if (fitting) {
            addLine("正在学习参考图，稍等", muted = true)
            return
        }
        val p = provider ?: run {
            addLine("还没有配置 AI 服务，请到「设置 → AI 服务」添加", error = true)
            return
        }
        if (!host.hasImage()) {
            addLine("先导入一张照片再使用 AI 调色", error = true)
            return
        }
        pushUndo()
        input.setText("")
        if (refs.isEmpty()) {
            addLine("你：$text", muted = true)
            if (text == AUTO) session.autoOptimize(p) else session.send(p, text)
            return
        }
        // 带附件：选图 ≠ 发送，点发送才「学习拟合 → 装配 → 进入 AI 微调环」
        addLine(if (text.isEmpty()) "你：（上传 ${refs.size} 张参考图）" else "你：$text", muted = true)
        addLine("正在学习 ${refs.size} 张参考图…", muted = true)
        fitting = true
        Thread {
            val res = activity.fitReferenceFromThumbs(refs.map { it.thumbFile })
            activity.runOnUiThread {
                fitting = false
                if (!res.optBoolean("ok")) {
                    addLine("参考图学习失败：${res.optString("error")}", error = true)
                    // 附件保留：用户可直接再点发送重试；有文字则降级为普通消息，不阻塞对话
                    if (text.isNotEmpty()) {
                        addLine("已按普通消息继续", muted = true)
                        session.send(p, text)
                    }
                    return@runOnUiThread
                }
                RefAttachmentCache.clear(activity)
                refreshRefAttachments()
                showCta(res.optString("name"))
                addLine("已学习 ${refs.size} 张参考图并应用到预览（待保存）", muted = true)
                val note = if (text.isEmpty() || text == AUTO) {
                    "请结合参考图的风格特征，对拟合出的色彩做最合适的微调"
                } else text
                val msg = note + "\n\n【参考拟合】系统已把用户上传的 ${refs.size} 张参考图学习成一套新色彩" +
                    "并应用到画布（端侧编码产物，你没有生成它的工具）。请先看预览与统计，" +
                    "再用 apply_params 做针对性微调（粗到细分轮），最后用一句话总结。"
                session.send(p, msg)
            }
        }.start()
    }

    private fun pushUndo() {
        val snap = host.snapshotParams()
        synchronized(undoStack) {
            // 相邻去重：消息起点快照与本轮首个工具落地前快照天然相同（逐步撤销时跳过）
            if (undoStack.isNotEmpty() && undoStack.last().toString() == snap.toString()) return
            undoStack.addLast(snap)
            while (undoStack.size > 12) undoStack.removeFirst()
        }
        refreshUndoBtn()
    }

    /** 撤销按钮单点刷新：常驻可见，栈空=置灰不可点（替代旧版忽隐忽现）。 */
    private fun refreshUndoBtn() {
        if (!this::undoBtn.isInitialized) return
        val has = synchronized(undoStack) { undoStack.isNotEmpty() }
        undoBtn.visibility = View.VISIBLE
        undoBtn.isEnabled = has
        undoBtn.alpha = if (has) 1f else 0.35f
    }

    private fun showProviderPicker() {
        val list = AiProviderStore.providers(activity)
        if (list.isEmpty()) {
            addLine("去「设置 → AI 服务」选一个推荐预设（MiMo / DeepSeek / GLM），粘贴自己的 Key 即可", error = false)
            chip.text = "去配置"
            return
        }
        CategoryPopup.show(
            anchor = chip,
            items = list.map { CategoryPopup.Item(it.id, it.name, it.model) },
            selectedId = AiProviderStore.currentProvider(activity)?.id,
            centerOnAnchor = false,
            alignEnd = true          // 芯片贴右边缘：弹窗与它右缘对齐，别再被屏幕夹到左边
        ) { item ->
            AiProviderStore.setCurrentProvider(activity, item.id)
            provider = AiProviderStore.currentProvider(activity)
            refreshProviderChip()
            refreshStatus()
        }
    }

    private fun refreshProviderChip() {
        provider = AiProviderStore.currentProvider(activity)
        chip.text = provider?.let {
            it.model.ifBlank { it.name }
        } ?: "去配置"
    }

    private fun refreshStatus() {
        status.text = when {
            provider == null -> "未配置 AI 服务。到「设置 → AI 服务」选推荐预设（MiMo/DeepSeek/GLM）、粘贴 Key 后就能对话调色。"
            !host.hasImage() -> "先导入一张照片。"
            else -> "说一句你想要的感觉；也可以点「自动优化」直接出结果。"
        }
    }

    private fun bindSession() {
        // 逐步撤销：AI 每轮 apply_params / apply_style 落地前压一个快照
        session.onUndoPoint = { pushUndo() }
        session.listener = object : AiSession.Listener {
            override fun onBusy(busy: Boolean) {
                applyBusy(busy)
                // 面板收起期间跑完：把结果回写到滤镜条上的 AI 卡（「AI · N 项」）
                if (!busy && !dialog.isShowing) host.onAiSheetClosed()
            }

            override fun onText(text: String) {
                addLine(text)
            }

            override fun onError(msg: String) {
                addLine("⚠ $msg", error = true)
            }

            override fun onPreviewChanged() {
                undoBtn.visibility = View.VISIBLE
            }
        }
    }

    /** 处理中状态：标题旁徽标 + 发送键变「停止」+ 输入禁用（内容再多提示也不会被滚走）。 */
    private fun applyBusy(busy: Boolean) {
        if (!this::busyChip.isInitialized || !this::sendBtn.isInitialized) return
        busyChip.visibility = if (busy) View.VISIBLE else View.GONE
        sendBtn.text = if (busy) "停止" else "发送"
        sendBtn.setTextColor(Color.parseColor(if (busy) "#FF6B5E" else "#000000"))
        sendBtn.background = pill(!busy)
        input.isEnabled = !busy
        status.text = if (busy) "正在处理…（可点「停止」中止）" else statusDefault()
    }

    private fun statusDefault(): String = when {
        provider == null -> "未配置 AI 服务。到「设置 → AI 服务」选推荐预设（MiMo/DeepSeek/GLM）、粘贴 Key 后就能对话调色。"
        !host.hasImage() -> "先导入一张照片。"
        else -> "继续描述或微调，例如「再暗一点」「天空更蓝」。"
    }

    // ---------------- 时间线 ----------------

    private fun addLine(text: String, error: Boolean = false, muted: Boolean = false) {
        session.logLine(text, error, muted)
        appendRow(text, error, muted)
    }

    /** 把会话里已有的历史行原样画出来（不含日志记录，避免重复）。 */
    private fun renderHistory() {
        session.history().forEach { appendRow(it.text, it.error, it.muted) }
    }

    private fun appendRow(text: String, error: Boolean = false, muted: Boolean = false) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val dot = TextView(activity).apply {
            this.text = if (error) "!" else "·"
            textSize = 12f
            setTextColor(
                Color.parseColor(
                    when {
                        error -> "#FF6B5E"
                        muted -> "#5A5A5E"
                        else -> "#7BC96F"
                    }
                )
            )
            setPadding(0, dp(1), dp(8), 0)
        }
        val body = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor(if (muted) "#8E8E93" else "#E8E8EA"))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        row.addView(dot)
        row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.alpha = 0f
        timeline.addView(row)
        row.animate().alpha(1f).setDuration(180).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 附件条：已挂参考图的缩略 + 逐个移除 + 拟合提示；无附件时整行隐藏。
     * 缩略是 240px JPEG，主线程逐张解码仅毫秒级，可接受。
     */
    fun refreshRefAttachments() {
        if (!this::attachRow.isInitialized) return
        val refs = RefAttachmentCache.restore(activity)
        attachRow.removeAllViews()
        if (refs.isEmpty()) {
            attachRow.visibility = View.GONE
            return
        }
        attachRow.visibility = View.VISIBLE
        attachRow.addView(TextView(activity).apply {
            text = "参考图"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })
        refs.forEach { p ->
            val holder = FrameLayout(activity)
            holder.addView(ImageView(activity).apply {
                BitmapFactory.decodeFile(p.thumbFile.absolutePath)?.let { setImageBitmap(it) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#232326"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40))
            })
            holder.addView(TextView(activity).apply {
                text = "×"
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(Color.parseColor("#99000000"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.END or Gravity.TOP)
                setOnClickListener {
                    RefAttachmentCache.remove(activity, p.id)
                    refreshRefAttachments()
                }
            })
            attachRow.addView(holder, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        attachRow.addView(TextView(activity).apply {
            text = "发送时学习拟合"
            textSize = 11f
            setTextColor(Color.parseColor("#5A5A5E"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })
    }

    private fun showCta(name: String) {
        ctaRow.removeAllViews()
        ctaRow.visibility = View.VISIBLE
        ctaRow.addView(TextView(activity).apply {
            text = "保存这套色彩"
            textSize = 13.5f
            setTextColor(Color.parseColor("#000000"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            setOnClickListener { askSaveName(name) }
        })
        ctaRow.addView(TextView(activity).apply {
            text = "仅本次使用"
            textSize = 13.5f
            setTextColor(Color.parseColor("#E8E8EA"))
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#59FFFFFF"))
            }
            setOnClickListener {
                host.clearPendingReferenceStyle()
                ctaRow.visibility = View.GONE
                addLine("已保留在本次编辑中（未入库）", muted = true)
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
    }

    private fun askSaveName(suggested: String) {
        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#141416"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
        }
        dialogView.addView(TextView(activity).apply {
            text = "保存这套色彩"
            textSize = 16f
            setTextColor(Color.parseColor("#F2F2F2"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        dialogView.addView(TextView(activity).apply {
            text = "由参考图拟合，会作为一套新的「我的色彩」加入滤镜条；不会修改任何已有色彩。"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(8), 0, dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        val edit = EditText(activity).apply {
            setText(host.pendingReferenceDefaultName().ifBlank { suggested })
            setSelection(text.length)
            setTextColor(Color.parseColor("#F2F2F2"))
            setHintTextColor(Color.parseColor("#5A5A5E"))
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1C1C1F"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        dialogView.addView(edit)

        val d = android.app.Dialog(activity)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        row.addView(TextView(activity).apply {
            text = "取消"
            textSize = 13.5f
            setTextColor(Color.parseColor("#E8E8EA"))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#59FFFFFF"))
            }
            setOnClickListener { d.dismiss() }
        })
        row.addView(TextView(activity).apply {
            text = "保存"
            textSize = 13.5f
            setTextColor(Color.parseColor("#000000"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            setOnClickListener {
                val name = edit.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    edit.error = "给它起个名字"
                    return@setOnClickListener
                }
                val ok = host.savePendingReferenceStyle(name)
                d.dismiss()
                if (ok) {
                    ctaRow.visibility = View.GONE
                    addLine("已保存「$name」到我的色彩")
                } else {
                    addLine("保存失败，请检查存储空间", error = true)
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
        dialogView.addView(row)

        d.setContentView(dialogView)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        d.show()
    }

    companion object {
        private const val AUTO = "__auto__"
    }
}

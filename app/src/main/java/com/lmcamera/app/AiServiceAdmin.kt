package com.lmcamera.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 「模型服务（OpenAI 兼容）」管理，**直接内嵌在设置中心 AI 页里**（不再有中间底部弹层级）。
 *
 * 由设置中心 AI 页容器直接承载：推荐预设 / 已添加服务列表 / 添加入口一页铺开，
 * 编辑表单保留弹层形态（合理的最后一层）。
 *
 * 只操作 [AiProviderStore]（独立 prefs + Keystore 密文），与用户既有的
 * 图片/色彩设置完全隔离。
 */
class AiServiceAdmin(
    private val activity: Activity,
    private val container: LinearLayout,
    private val onChanged: () -> Unit
) {

    private val density: Float get() = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    /** 推荐预设：点一下自动填好 baseURL/模型/视觉标记，用户只需要粘自己的 Key。 */
    private data class Preset(
        val label: String,
        val base: String,
        val model: String,
        /** 该平台的 API Key 页面（每家的控制台不同，所以不做「一个总入口」） */
        val keyUrl: String,
        val vision: Boolean = true
    )

    private companion object {
        /**
         * 三档推荐预设（2026-11 现状，均国内直连、OpenAI 兼容、原生多模态）：
         * - 小米 MiMo：全模态高效推理，按量付费或 Token Plan 订阅（订阅版 baseURL 换
         *   token-plan-cn.xiaomimimo.com/v1，用户在编辑框自行改）；
         * - DeepSeek：官方口径模型名用 deepseek-flash（已升级为 V4.1-Flash-1017，
         *   调用名不变，不写死版本号）；
         * - 智谱：GLM-5.3-Flash 超低价 Flash 档，回归预设列表。
         */
        val PRESETS = listOf(
            Preset(
                "小米 MiMo · V2.6-Flash",
                "https://api.xiaomimimo.com/v1",
                "mimo-v2.6-flash",
                "https://mimo.mi.com/"
            ),
            Preset(
                "DeepSeek · V4.1-Flash",
                "https://api.deepseek.com/v1",
                "deepseek-flash",
                "https://platform.deepseek.com/api_keys"
            ),
            Preset(
                "智谱 · GLM-5.3-Flash",
                "https://open.bigmodel.cn/api/paas/v4",
                "glm-5.3-flash",
                "https://open.bigmodel.cn/usercenter/proj-mgmt/apikeys"
            )
        )
    }

    /** 渲染整个服务管理区（容器由宿主提供，增删改后重调即可）。 */
    fun render() {
        container.removeAllViews()
        container.addView(sectionTitle("推荐预设（点选后只填 Key）"))
        PRESETS.forEach { preset ->
            val row = rowContainer()
            val left = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = preset.label
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#E8E8EA"))
                })
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // 每家的 Key 页面不同：各自一个入口，别再用「一条总指智谱」的引导
            row.addView(TextView(activity).apply {
                text = "获取 Key ›"
                textSize = 12f
                setTextColor(Color.parseColor("#7BC96F"))
                setPadding(dp(8), dp(6), dp(6), dp(6))
                setOnClickListener { openUrl(preset.keyUrl) }
            })
            row.addView(TextView(activity).apply {
                text = "使用"
                textSize = 12f
                setTextColor(Color.parseColor("#E8E8EA"))
                setPadding(dp(8), dp(6), dp(6), dp(6))
            })
            row.setOnClickListener { showProviderEditor(null, preset) }
            container.addView(row)
        }

        val list = AiProviderStore.providers(activity)
        val current = AiProviderStore.currentProvider(activity)
        container.addView(sectionTitle("已添加"))
        if (list.isEmpty()) {
            container.addView(hint("还没有添加服务。可以点上面的推荐预设，或「添加模型服务」手动填写。"))
        }
        list.forEach { p ->
            val row = rowContainer()
            val left = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = p.name.ifBlank { p.model }
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#E8E8EA"))
                })
                addView(TextView(activity).apply {
                    text = buildString {
                        append(p.model)
                        append(" · ")
                        append(if (p.apiKey.isEmpty()) "未填 Key" else "Key 已保存")
                        if (p.vision) append(" · 视觉")
                        if (p.thinking != AiProvider.THINK_DEFAULT) {
                            append(" · 思考")
                            append(AiProvider.thinkingName(p.thinking))
                        }
                        if (current?.id == p.id) append(" · 当前")
                    }
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#8E8E93"))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = if (current?.id == p.id) "使用中" else "选用"
                textSize = 12f
                setTextColor(Color.parseColor(if (current?.id == p.id) "#7BC96F" else "#E8E8EA"))
                setPadding(dp(10), dp(6), dp(6), dp(6))
                setOnClickListener {
                    AiProviderStore.setCurrentProvider(activity, p.id)
                    render()
                    onChanged()
                }
            })
            row.setOnClickListener { showProviderEditor(p) }
            container.addView(row)
        }
        container.addView(rowContainer().apply {
            addView(TextView(activity).apply {
                text = "＋ 添加模型服务"
                textSize = 13.5f
                setTextColor(Color.parseColor("#F2F2F2"))
            })
            setOnClickListener { showProviderEditor(null) }
        })
        container.addView(hint(
            "Key 保存在系统密钥库加密后的本地文件里，不会明文落盘；所有 AI 配置都与 App 其它设置隔离。"
        ))
    }

    private fun showProviderEditor(existing: AiProvider?, preset: Preset? = null) {
        var vision = existing?.vision ?: preset?.vision ?: false
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#141416"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
        }
        box.addView(TextView(activity).apply {
            text = when {
                existing != null -> "编辑模型服务"
                preset != null -> "添加「${preset.label}」"
                else -> "添加模型服务"
            }
            textSize = 16f
            setTextColor(Color.parseColor("#F2F2F2"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })

        val name = editField(box, "名称（如 DeepSeek）", existing?.name ?: preset?.label ?: "")
        val base = editField(box, "baseURL（如 https://api.deepseek.com/v1）", existing?.baseUrl ?: preset?.base ?: "")
        val model = editField(box, "模型名（如 deepseek-chat）", existing?.model ?: preset?.model ?: "")
        val key = editField(box, "API Key", existing?.apiKey ?: "", password = true)

        val visionBox = CheckBox(activity).apply {
            text = "该模型支持看图（多模态）"
            isChecked = vision
            setTextColor(Color.parseColor("#C8C8CC"))
            textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            setOnCheckedChangeListener { _, checked -> vision = checked }
        }
        box.addView(visionBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        box.addView(hint("不勾选时 AI 只能读端侧统计（直方图/饱和度/色温），精度略低但可用。"))

        // 思考强度：新模型基本关不掉，统一默认「最低档」，按服务商方言自动注入
        var thinkingLevel = existing?.thinking ?: AiProvider.THINK_LOW
        val thinkLabel = TextView(activity).apply {
            text = "深度思考：${AiProvider.thinkingName(thinkingLevel)} ▾"
            textSize = 13f
            setTextColor(Color.parseColor("#E8E8EA"))
            setPadding(0, dp(12), 0, dp(2))
            setOnClickListener {
                CategoryPopup.show(
                    anchor = this,
                    items = listOf(
                        CategoryPopup.Item("1", "最低档（推荐）", "省 token、响应更快"),
                        CategoryPopup.Item("0", "服务默认", "不发思考参数，由平台决定"),
                        CategoryPopup.Item("2", "关闭", "仅部分服务支持")
                    ),
                    selectedId = thinkingLevel.toString(),
                    centerOnAnchor = false
                ) { item ->
                    thinkingLevel = item.id.toIntOrNull() ?: AiProvider.THINK_LOW
                    text = "深度思考：${AiProvider.thinkingName(thinkingLevel)} ▾"
                }
            }
        }
        box.addView(thinkLabel)
        box.addView(hint("默认最低档：服务不认该参数时会自动去掉重试一次，不会报错。"))

        if (preset != null) {
            // 预设进来的场景：直接给该平台的 Key 页面（各家控制台不同，不做统一跳转）
            box.addView(TextView(activity).apply {
                text = "还没有 Key？去「${preset.label.substringBefore(" · ")}」获取 ›"
                textSize = 12.5f
                setTextColor(Color.parseColor("#7BC96F"))
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { openUrl(preset.keyUrl) }
            })
        }

        val d = Dialog(activity)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        // 模型名不用猜：直接拉 /models 让用户点选（各家的模型 ID 常带版本后缀）
        actions.addView(TextView(activity).apply {
            text = "模型列表"
            textSize = 13f
            setTextColor(Color.parseColor("#E8E8EA"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#59FFFFFF"))
            }
            setOnClickListener {
                val p = providerFrom(existing, preset, name, base, model, key, vision, thinkingLevel)
                if (p.baseUrl.isEmpty() || p.apiKey.isEmpty()) {
                    Toast.makeText(activity, "先填 baseURL 和 Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                fetchModels(p) { picked ->
                    model.setText(picked)
                    model.setSelection(picked.length)
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })
        actions.addView(TextView(activity).apply {
            text = "测试"
            textSize = 13f
            setTextColor(Color.parseColor("#E8E8EA"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#59FFFFFF"))
            }
            setOnClickListener {
                val p = providerFrom(existing, preset, name, base, model, key, vision, thinkingLevel)
                testProvider(p)
            }
        })
        if (existing != null) {
            actions.addView(TextView(activity).apply {
                text = "删除"
                textSize = 13f
                setTextColor(Color.parseColor("#FF6B5E"))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    AiProviderStore.deleteProvider(activity, existing.id)
                    d.dismiss()
                    render()
                    onChanged()
                    Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                }
            })
        }
        actions.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        actions.addView(TextView(activity).apply {
            text = "保存"
            textSize = 13f
            setTextColor(Color.parseColor("#000000"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            setOnClickListener {
                val b = base.text.toString().trim()
                val m = model.text.toString().trim()
                if (b.isEmpty() || m.isEmpty()) {
                    Toast.makeText(activity, "baseURL 和模型名不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val p = providerFrom(existing, preset, name, base, model, key, vision, thinkingLevel)
                    .copy(name = name.text.toString().trim().ifEmpty { m }, baseUrl = b, model = m)
                AiProviderStore.saveProvider(activity, p)
                if (AiProviderStore.currentProvider(activity) == null) {
                    AiProviderStore.setCurrentProvider(activity, p.id)
                }
                d.dismiss()
                render()
                onChanged()
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        box.addView(actions)

        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    /** 从编辑框当前值组装 Provider（id/预填值来自 existing/preset）。 */
    private fun providerFrom(
        existing: AiProvider?,
        preset: Preset?,
        name: EditText,
        base: EditText,
        model: EditText,
        key: EditText,
        vision: Boolean,
        thinking: Int
    ): AiProvider = AiProvider(
        id = existing?.id ?: AiProviderStore.newProviderId(),
        name = name.text.toString().trim().ifEmpty { preset?.label ?: "" },
        baseUrl = base.text.toString().trim().ifEmpty { preset?.base ?: "" },
        model = model.text.toString().trim().ifEmpty { preset?.model ?: "" },
        apiKey = key.text.toString().trim(),
        vision = vision,
        thinking = thinking
    )

    private fun testProvider(p: AiProvider) {
        Toast.makeText(activity, "正在测试…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = AiClient().ping(p)
            activity.runOnUiThread {
                if (err == null) {
                    Toast.makeText(activity, "连通正常", Toast.LENGTH_SHORT).show()
                } else {
                    // 不再用 3 秒 toast 糊过去：把 HTTP 码与返回体原文摆出来，用户能直接截图
                    showInfoDialog("测试失败", buildString {
                        append(err)
                        append("\n\n")
                        append(
                            when {
                                err.contains("人数过多") || err.contains("429") ->
                                    "免费模型排队/限流：稍等再试，或换一个模型/服务。"
                                err.contains("401") || err.contains("403") ->
                                    "Key 无效或无权限：到控制台确认 Key 有效，且已开通该模型。"
                                err.contains("404") || err.contains("模型") ->
                                    "模型名可能不对：点「模型列表」重新选。"
                                else -> "连接失败：检查网络（或该服务是否需要代理）。"
                            }
                        )
                        append("\n\n请求地址：")
                        append(p.baseUrl)
                        append("\n模型：")
                        append(p.model)
                    })
                }
            }
        }.start()
    }

    /** 拉取 /models 让用户点选，避免模型 ID 带版本后缀时反复猜。 */
    private fun fetchModels(p: AiProvider, onPick: (String) -> Unit) {
        Toast.makeText(activity, "正在获取模型列表…", Toast.LENGTH_SHORT).show()
        Thread {
            val models = AiClient().listModels(p)
            activity.runOnUiThread {
                if (models.isEmpty()) {
                    showInfoDialog(
                        "没取到模型列表",
                        "该服务可能不支持 /models 接口。请到控制台复制模型 ID 手动填入（注意大小写与版本后缀）。"
                    )
                } else {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("选择模型（${models.size}）")
                        .setItems(models.toTypedArray()) { _, which -> onPick(models[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    /** 通用信息弹窗（错误原文、提示），文字可长按复制。 */
    private fun showInfoDialog(title: String, message: String) {
        val bodyView = TextView(activity).apply {
            this.text = message
            textSize = 12.5f
            setTextColor(Color.parseColor("#C8C8CC"))
            setPadding(dp(2), dp(6), dp(2), dp(2))
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val scroll = ScrollView(activity).apply { addView(bodyView) }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#141416"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
        }
        box.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#F2F2F2"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val d = Dialog(activity)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val okBtn = TextView(activity).apply {
            text = "知道了"
            textSize = 13f
            setTextColor(Color.parseColor("#000000"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = dp(12)
            }
            setOnClickListener { d.dismiss() }
        }
        box.addView(okBtn)
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        d.show()
    }

    // ---------------- 小工具 ----------------

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(activity, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Color.parseColor("#8E8E93"))
        setPadding(dp(4), dp(18), dp(4), dp(6))
    }

    private fun hint(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Color.parseColor("#5A5A5E"))
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun rowContainer(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#14FFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        isClickable = true
    }

    private fun editField(parent: LinearLayout, label: String, value: String, password: Boolean = false): EditText {
        parent.addView(TextView(activity).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(10), 0, dp(4))
        })
        val edit = EditText(activity).apply {
            setText(value)
            setSelection(text.length)
            textSize = 13.5f
            setTextColor(Color.parseColor("#F2F2F2"))
            setHintTextColor(Color.parseColor("#5A5A5E"))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1C1C1F"))
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }
        parent.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return edit
    }
}

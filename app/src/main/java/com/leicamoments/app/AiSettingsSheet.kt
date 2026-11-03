package com.leicamoments.app

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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 「AI 服务」管理面板：模型服务（OpenAI 兼容）与参考图搜索源。
 *
 * 只操作 [AiProviderStore]（独立 prefs + Keystore 密文），与用户既有的
 * 图片/色彩设置完全隔离。
 */
class AiSettingsSheet(private val activity: Activity) {

    private val dialog = BottomSheetDialog(activity)
    private lateinit var providerBox: LinearLayout
    private lateinit var searchBox: LinearLayout
    private lateinit var bottomBox: LinearLayout

    private val density: Float get() = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    /** 免费/低价预设：点一下自动填好 baseURL/模型/视觉标记，用户只需要粘自己的 Key。 */
    private data class Preset(
        val label: String,
        val subtitle: String,
        val base: String,
        val model: String,
        /** 该平台的 API Key 页面（每家的控制台不同，所以不做「一个总入口」） */
        val keyUrl: String,
        val vision: Boolean = true
    )

    private companion object {
        /**
         * 三档预设（2026-11 现状，均国内直连、OpenAI 兼容、支持视觉与函数调用）：
         * - 百炼：新用户按模型赠送 token，稳定不排队，做主力；
         * - 方舟：实名后每模型送额度，多模态强，做备选；
         * - 硅基流动：一个 Key 调 200+ 模型、注册送额度永久有效，做兜底。
         * 智谱免费档因长期"人数过多"已从预设中移除（需要仍可手动添加）。
         */
        val PRESETS = listOf(
            Preset(
                "阿里云百炼 · Qwen3-VL-Plus",
                "主力 · 国内直连 · 新用户每模型送 100 万 tokens",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3-vl-plus",
                "https://bailian.console.aliyun.com/"
            ),
            Preset(
                "火山方舟 · Doubao-Seed-2.0-lite",
                "备选 · 实名后每模型送 50 万 tokens · 模型 ID 以控制台为准",
                "https://ark.cn-beijing.volces.com/api/v3",
                "doubao-seed-2-0-lite-260215",
                "https://console.volcengine.com/ark/region:ark+cn-beijing/apikey"
            ),
            Preset(
                "硅基流动 · 一个 Key 多模型",
                "兜底 · 注册送额度永久有效 · 模型请点「模型列表」选带 VL 的",
                "https://api.siliconflow.cn/v1",
                "",
                "https://cloud.siliconflow.cn/account/ak"
            )
        )
    }

    fun show() {
        buildUi()
        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            WindowCompat.setDecorFitsSystemWindows(window, false)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            val root = window.decorView.findViewById<View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, insets: WindowInsetsCompat ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                if (this::bottomBox.isInitialized) {
                    bottomBox.updatePadding(bottom = dp(14) + bars.bottom)
                }
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }
        dialog.show()
        val dm = activity.resources.displayMetrics
        dialog.behavior.peekHeight = (dm.heightPixels * 0.82f).toInt()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    private fun buildUi() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#0E0E10"))
            }
        }
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
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(6))
            addView(TextView(activity).apply {
                text = "AI 服务"
                textSize = 16f
                setTextColor(Color.parseColor("#F2F2F2"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(TextView(activity).apply {
                text = "关闭"
                textSize = 13f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(dp(10), dp(6), dp(6), dp(6))
                setOnClickListener { dialog.dismiss() }
            })
        })

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(0), dp(16), dp(16))
        }

        content.addView(sectionTitle("模型服务（OpenAI 兼容）"))
        providerBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(providerBox)
        content.addView(hint("Key 保存在系统密钥库加密后的本地文件里，不会明文落盘；所有 AI 配置都与 App 其它设置隔离。"))

        content.addView(sectionTitle("参考图搜索"))
        searchBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(searchBox)
        content.addView(hint("按顺序自动顺延：Google → Brave → Openverse → Pixabay → Unsplash → Pexels → 必应/DDG（实验）。免 Key 的源默认开启；总预算 15 秒。"))

        scroll.addView(content)
        root.addView(scroll)
        dialog.setContentView(root)

        bottomBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        root.addView(bottomBox)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        renderProviders()
        renderSearch()
    }

    // ---------------- 模型服务 ----------------

    private fun renderProviders() {
        providerBox.removeAllViews()
        val list = AiProviderStore.providers(activity)
        val current = AiProviderStore.currentProvider(activity)

        // 免费预设：点一下把 baseURL/模型/视觉标记填好，用户只需要粘自己的 Key
        providerBox.addView(sectionTitle("免费预设（点选后只填 Key）"))
        PRESETS.forEach { preset ->
            val row = rowContainer()
            val left = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = preset.label
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#E8E8EA"))
                })
                addView(TextView(activity).apply {
                    text = preset.subtitle
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#8E8E93"))
                    setPadding(0, dp(3), 0, 0)
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
            providerBox.addView(row)
        }

        providerBox.addView(sectionTitle("已添加"))
        if (list.isEmpty()) {
            providerBox.addView(hint("还没有添加服务。可以点上面的免费预设，或「添加模型服务」手动填写。"))
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
                    renderProviders()
                }
            })
            row.setOnClickListener { showProviderEditor(p) }
            providerBox.addView(row)
        }
        providerBox.addView(rowContainer().apply {
            addView(TextView(activity).apply {
                text = "＋ 添加模型服务"
                textSize = 13.5f
                setTextColor(Color.parseColor("#F2F2F2"))
            })
            setOnClickListener { showProviderEditor(null) }
        })
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
                val p = AiProvider(
                    id = existing?.id ?: AiProviderStore.newProviderId(),
                    name = name.text.toString().trim(),
                    baseUrl = base.text.toString().trim(),
                    model = model.text.toString().trim(),
                    apiKey = key.text.toString().trim(),
                    vision = vision
                )
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
                val p = AiProvider(
                    id = existing?.id ?: AiProviderStore.newProviderId(),
                    name = name.text.toString().trim(),
                    baseUrl = base.text.toString().trim(),
                    model = model.text.toString().trim(),
                    apiKey = key.text.toString().trim(),
                    vision = vision
                )
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
                    renderProviders()
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
                val p = AiProvider(
                    id = existing?.id ?: AiProviderStore.newProviderId(),
                    name = name.text.toString().trim().ifEmpty { m },
                    baseUrl = b,
                    model = m,
                    apiKey = key.text.toString().trim(),
                    vision = vision
                )
                AiProviderStore.saveProvider(activity, p)
                if (AiProviderStore.currentProvider(activity) == null) {
                    AiProviderStore.setCurrentProvider(activity, p.id)
                }
                d.dismiss()
                renderProviders()
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

    // ---------------- 搜索源 ----------------

    private fun renderSearch() {
        searchBox.removeAllViews()
        val sources = AiProviderStore.searchSources(activity)
        sources.forEach { s ->
            val meta = AiProviderStore.SOURCE_META.firstOrNull { it.first == s.id }
            val label = meta?.second ?: s.id
            val needsKey = meta?.third ?: false
            val row = rowContainer()
            val left = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = label
                    textSize = 14.5f
                    setTextColor(Color.parseColor(if (s.enabled) "#E8E8EA" else "#8E8E93"))
                })
                addView(TextView(activity).apply {
                    text = buildString {
                        append(if (s.enabled) "已启用" else "未启用")
                        if (needsKey) append(if (s.apiKey.isEmpty()) " · 缺 Key" else " · Key 已保存")
                        else append(" · 免 Key")
                    }
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#8E8E93"))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = if (s.enabled) "开" else "关"
                textSize = 12f
                setTextColor(Color.parseColor(if (s.enabled) "#7BC96F" else "#5A5A5E"))
                setPadding(dp(12), dp(6), dp(6), dp(6))
                setOnClickListener {
                    AiProviderStore.saveSearchSource(activity, s.copy(enabled = !s.enabled))
                    renderSearch()
                }
            })
            row.setOnClickListener { showSearchEditor(s, label, needsKey) }
            searchBox.addView(row)
        }
    }

    private fun showSearchEditor(s: AiSearchSource, label: String, needsKey: Boolean) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#141416"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
        }
        box.addView(TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(Color.parseColor("#F2F2F2"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        box.addView(hint(searchHint(s.id)))

        val key = if (needsKey) editField(box, "API Key", s.apiKey, password = true) else null
        val extraLabel = when (s.id) {
            AiProviderStore.SRC_GOOGLE_CSE -> "cx（搜索引擎 ID）或「中转地址|cx」"
            AiProviderStore.SRC_BRAVE -> "自定义中转地址（可留空）"
            AiProviderStore.SRC_OPENVERSE -> "自定义地址（可留空，默认 api.openverse.org）"
            AiProviderStore.SRC_PIXABAY -> "自定义地址（可留空）"
            AiProviderStore.SRC_UNSPLASH -> "自定义地址（可留空）"
            AiProviderStore.SRC_PEXELS -> "自定义地址（可留空）"
            else -> null
        }
        val extra = extraLabel?.let { editField(box, it, s.extra) }

        val d = Dialog(activity)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(TextView(activity).apply {
            text = if (s.enabled) "停用" else "启用"
            textSize = 13f
            setTextColor(Color.parseColor("#E8E8EA"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#59FFFFFF"))
            }
            setOnClickListener {
                AiProviderStore.saveSearchSource(activity, s.copy(enabled = !s.enabled))
                d.dismiss()
                renderSearch()
            }
        })
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
                AiProviderStore.saveSearchSource(
                    activity,
                    s.copy(
                        apiKey = key?.text?.toString()?.trim() ?: s.apiKey,
                        extra = extra?.text?.toString()?.trim() ?: s.extra
                    )
                )
                d.dismiss()
                renderSearch()
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
        d.show()
    }

    private fun searchHint(id: String): String = when (id) {
        AiProviderStore.SRC_GOOGLE_CSE -> "免费 100 次/天。国内直连不通时，把「中转地址|cx」填成你自己的反代地址（如 https://your.host/cse|cx值）。"
        AiProviderStore.SRC_BRAVE -> "每月 $5 赠送额度（约 1000 次），需要绑卡。"
        AiProviderStore.SRC_OPENVERSE -> "可免 Key 使用（匿名 200 次/天）；注册免费 Key 后额度 1 万次/天。"
        AiProviderStore.SRC_PIXABAY -> "免费 Key 登录即得，额度约 5000 次/小时，国内可直连。"
        AiProviderStore.SRC_UNSPLASH -> "免费额度 50 次/小时（演示档）。"
        AiProviderStore.SRC_PEXELS -> "免费 200 次/小时。"
        AiProviderStore.SRC_BING_CN -> "免 Key，网页解析，可能随官方改版失效——永远排在正规接口之后。"
        AiProviderStore.SRC_DUCKDUCKGO -> "免 Key，非官方接口，不稳定；仅作最后兜底。"
        else -> ""
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

package com.leicamoments.app

import android.content.Context
import android.os.Build
import org.json.JSONArray

/**
 * 轻量设置存储（SharedPreferences）。
 *
 * - `default_category`：all / leica / arri / ccd / custom / last
 * - `last_category`：最后一次**实际所处**的分类，切分类与冷启动确定分类后写入
 * - `coach_shown`：首次启动的分类引导气泡是否已展示
 * - `pinned_ids`：置顶项 id 列表（保序 JSON 数组，最近置顶的在最前）
 * - `raw_thr_level:<MODEL>`：RAW 解码亮度档位（按机型记忆，不进备份）
 */
object SettingsStore {

    private const val FILE = "lm_settings"
    private const val KEY_DEFAULT = "default_category"
    private const val KEY_LAST = "last_category"
    private const val KEY_COACH = "coach_shown"
    private const val KEY_PINNED = "pinned_ids"
    private const val KEY_SHARE_SEEN = "share_seen_sig"
    private const val KEY_RAW_THR = "raw_thr_level"
    private const val KEY_RAW_HINT = "raw_hint_shown"

    /** 「记忆上次」在 default_category 里的取值 */
    const val VALUE_LAST = "last"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 设置页当前选择（可能是 [VALUE_LAST]），用于回显 */
    fun defaultSetting(c: Context): String =
        prefs(c).getString(KEY_DEFAULT, FilterCategory.ALL.id) ?: FilterCategory.ALL.id

    fun setDefaultSetting(c: Context, value: String) {
        prefs(c).edit().putString(KEY_DEFAULT, value).apply()
    }

    fun lastCategory(c: Context): FilterCategory? =
        FilterCategory.fromId(prefs(c).getString(KEY_LAST, null))

    fun setLastCategory(c: Context, category: FilterCategory) {
        prefs(c).edit().putString(KEY_LAST, category.id).apply()
    }

    /** 冷启动进入的分类：固定值直接用，「记忆上次」取 last，无记录回落 L-Moment */
    fun startCategory(c: Context): FilterCategory {
        val def = defaultSetting(c)
        if (def == VALUE_LAST) return lastCategory(c) ?: FilterCategory.ALL
        return FilterCategory.fromId(def) ?: FilterCategory.ALL
    }

    fun isCoachShown(c: Context): Boolean = prefs(c).getBoolean(KEY_COACH, false)

    fun markCoachShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_COACH, true).apply()
    }

    // ---- 置顶（跨分类，内置与自定义共用一套 id 空间）----

    /**
     * 置顶项 id，最近置顶的在最前。
     * 必须用有序数组：StringSet 无序，多置顶时顺序会在重启后乱掉。
     */
    fun pinnedIds(c: Context): List<String> {
        val raw = prefs(c).getString(KEY_PINNED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            ArrayList<String>(arr.length()).apply {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun setPinnedIds(c: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs(c).edit().putString(KEY_PINNED, arr.toString()).apply()
    }

    fun isPinned(c: Context, id: String): Boolean = pinnedIds(c).contains(id)

    /** 切换置顶状态，返回切换后是否处于置顶。 */
    fun togglePinned(c: Context, id: String): Boolean {
        val cur = pinnedIds(c)
        return if (cur.contains(id)) {
            setPinnedIds(c, cur.filterNot { it == id })
            false
        } else {
            setPinnedIds(c, listOf(id) + cur)
            true
        }
    }

    /** 删除自定义色彩时同步清掉它的置顶标记 */
    fun removePinned(c: Context, id: String) {
        val cur = pinnedIds(c)
        if (cur.contains(id)) setPinnedIds(c, cur.filterNot { it == id })
    }

    // ---- 分享指令 ----

    /**
     * 最近一次已导入（或自己刚复制、或已损坏提示过）的指令指纹。
     *
     * 剪贴板内容会长期留着，没有这条记录的话每次切回前台都会弹一次导入确认。
     */
    fun shareSeenSignature(c: Context): String? = prefs(c).getString(KEY_SHARE_SEEN, null)

    fun markShareSeen(c: Context, sig: String) {
        prefs(c).edit().putString(KEY_SHARE_SEEN, sig).apply()
    }

    // ---- RAW 解码亮度（按机型记忆）----

    /**
     * 0 = 标准（auto_bright_thr 0.01，与旧版本一致）
     * 1 = 提亮（0.03）
     * 2 = 强力提亮（0.04）
     *
     * key 带机型：同一台机器的观感偏好换机后不沿用；也**不进 .lmc 备份**，
     * 因为该值与机型的 RAW 高光余量、预览合成策略强相关，跨机恢复只会帮倒忙。
     */
    fun rawThrLevel(c: Context): Int = prefs(c).getInt(rawThrKey(), 0).coerceIn(0, 2)

    fun setRawThrLevel(c: Context, level: Int) {
        prefs(c).edit().putInt(rawThrKey(), level.coerceIn(0, 2)).apply()
    }

    /** 档位 → LibRaw 的 auto_bright_thr */
    fun rawThr(c: Context): Float = when (rawThrLevel(c)) {
        1 -> 0.03f
        2 -> 0.04f
        else -> 0.01f
    }

    private fun rawThrKey(): String {
        val model = (Build.MODEL ?: "").ifBlank { "unknown" }
        return "$KEY_RAW_THR:$model"
    }

    /** 首次导入 RAW 的提示是否已展示（全局一次，与机型无关） */
    fun isRawHintShown(c: Context): Boolean = prefs(c).getBoolean(KEY_RAW_HINT, false)

    fun markRawHintShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_RAW_HINT, true).apply()
    }
}

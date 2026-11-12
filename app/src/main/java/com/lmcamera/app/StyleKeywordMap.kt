package com.lmcamera.app

/**
 * 第八层兜底：风格关键词 → 现有色彩 + 参数起点。
 *
 * 断网、无 API Key、搜索全失败时，AI 仍然可以靠这张表给出一版「不空手」的结果；
 * 同时它也约束模型别在明显黑白/胶片类需求上乱选彩色风格。
 */
object StyleKeywordMap {

    data class Preset(
        val styleId: String,
        val styleLabel: String,
        val reason: String,
        val saturation: Int = 0,
        val contrast: Int = 0,
        val shadows: Int = 0,
        val highlights: Int = 0,
        val black: Int = 0,
        val exposure: Int = 0,
        val glow: Int = 0
    )

    private data class Rule(val keys: List<String>, val preset: Preset)

    private val rules: List<Rule> = listOf(
        Rule(
            listOf("黑白", "单色", "灰阶", "mono", "bw"),
            Preset("M3", "M3", "黑白经典", saturation = -10, contrast = 1, black = -1)
        ),
        // 品牌词（特异性最高，必须排在「人像/鲜艳/奶油」等泛词之前，否则被截走）：
        // 用户说「徕卡/柯达/富士/理光」时直接命中对应家族主档
        Rule(
            listOf("徕卡味"),
            Preset("LP9", "L-P9", "徕卡鲜艳 · 相机光学味", saturation = 2, contrast = 1)
        ),
        // 「徕卡」笼统词的断网兜底给 LM9（用户口径：一瞬/光学味/相机直出味 = L-M9）；
        // 正常路径由 SYSTEM_PROMPT 双轨制让模型自主判断
        Rule(
            listOf("徕卡", "leica", "一瞬", "光学味", "相机味"),
            Preset("LM9", "L-M9", "徕卡一瞬 · 相机直出味", saturation = 1, contrast = 1)
        ),
        Rule(
            listOf("柯达暖"),
            Preset("CCDW", "CCD·暖", "暖黄胶片（柯达暖调）", saturation = 1)
        ),
        Rule(
            listOf("柯达", "kodak"),
            Preset("KP400", "K400", "柯达人像奶油 · 暖柔", exposure = 1, highlights = -1)
        ),
        Rule(
            listOf("富士", "fuji", "fujifilm"),
            Preset("FCHROME", "经典铬", "富士经典铬 · 哑光纪实", contrast = 2, saturation = -1)
        ),
        Rule(
            listOf("理光街拍", "理光扫街"),
            Preset("RSTREET", "STREET", "街拍 · 纪实硬调", contrast = 2, saturation = -1)
        ),
        Rule(
            listOf("理光", "ricoh"),
            Preset("RPOS", "Positive", "理光正片 · 浓彩反转", saturation = 2, contrast = 1)
        ),
        Rule(
            listOf("细腻黑白", "灰阶细腻", "颗粒黑白"),
            Preset("FACROS", "细腻黑白", "深黑细粒 · 平滑灰阶", saturation = -10, contrast = 1)
        ),
        Rule(
            listOf("电影", "青橙", "暗调", "cinematic", "teal"),
            Preset("M4", "ARRI 2049", "浓青橙 · 电影暗调", contrast = 1, shadows = -1)
        ),
        Rule(
            listOf("哈苏蓝"),
            Preset("HB", "H·蓝", "哈苏蓝 · 天空深青", saturation = 1, contrast = 1)
        ),
        Rule(
            listOf("哈苏", "自然", "通透自然", "hasselblad", "natural"),
            Preset("HN", "H·自然", "自然还原 · 通透")
        ),
        Rule(
            listOf("植被", "绿意", "森林", "橄榄绿"),
            Preset("HG", "H·绿", "橄榄绿植 · 不荧光", saturation = 1)
        ),
        Rule(
            listOf("人像", "肤色", "portrait", "skin"),
            Preset("HP", "H·人像", "肤色金黄 · 柔调", exposure = 1)
        ),
        Rule(
            listOf("鲜艳", "浓郁", "通透鲜艳", "punchy"),
            Preset("LP9", "L-P9", "P9 鲜艳", saturation = 2, contrast = 1)
        ),
        Rule(
            listOf("冷白", "ccd", "白皙", "冷调白"),
            Preset("M13", "CCD·冷白", "冷白胶片", saturation = 1)
        ),
        Rule(
            listOf("粉白", "粉调", "少女"),
            Preset("CCDP", "CCD·粉", "粉白通透", saturation = 1, highlights = 1)
        ),
        Rule(
            listOf("暖黄", "暖色"),
            Preset("CCDW", "CCD·暖", "柯达暖黄", saturation = 1)
        ),
        Rule(
            listOf("日系", "清新", "通透", "干净", "小清新"),
            Preset("REVEAL", "Reveal", "通透还原 · 柔和低反差", exposure = 1, highlights = -1)
        ),
        Rule(
            listOf("胶片", "复古", "怀旧", "褪色", "film"),
            Preset("FNN", "怀旧负片", "琥珀暖调 · 柔和褪色", saturation = -1, contrast = -1)
        ),
        Rule(
            listOf("高对比", "硬朗", "纪实", "低调"),
            Preset("FCHROME", "经典铬", "哑光纪实 · 低饱和硬调", contrast = 2, saturation = -1)
        ),
        Rule(
            listOf("负片", "青影", "冷暖对比"),
            Preset("FCNEG", "经典负片", "高对比 · 青影暖高", contrast = 2)
        ),
        Rule(
            listOf("奶油", "人像", "柔和肤色", "暖柔"),
            Preset("KP400", "K400", "人像奶油 · 暖柔", exposure = 1, highlights = -1)
        ),
        Rule(
            listOf("金黄", "怀旧蓝天", "夕阳"),
            Preset("KG200", "K200", "金黄怀旧 · 蓝天压暗", contrast = 1)
        ),
        Rule(
            listOf("风光", "锐利", "浓彩", "风景"),
            Preset("KE100", "K100", "浓彩风光 · 锐利", contrast = 1, saturation = 1)
        ),
        Rule(
            listOf("反转", "实黑", "浓红"),
            Preset("KC64", "K64", "浓红反转 · 实黑", contrast = 2, black = -1)
        ),
        Rule(
            listOf("黄昏", "霓虹", "绚丽", "晚霞"),
            Preset("LALALAND", "LaLaLand", "黄昏绚丽 · 霓虹饱和", saturation = 2, highlights = -1)
        ),
        Rule(
            listOf("沙漠", "哑光", "冷蓝砂橙", "沙色"),
            Preset("DUNE", "Dune", "沙漠哑光 · 冷蓝砂橙", saturation = -1, contrast = -1)
        ),
        Rule(
            listOf("阴郁", "绿调", "压抑"),
            Preset("JOKER", "Joker", "绿调阴郁 · 低饱和", saturation = -2, exposure = -1)
        ),
        Rule(
            listOf("颗粒", "暗角", "氛围"),
            Preset("M10R", "M10-R", "暗角颗粒", contrast = 1)
        ),
        Rule(
            listOf("柔和", "柔光", "朦胧", "梦幻"),
            Preset("FPNH", "人像负片·高", "人像高对比 · 肤色优先", glow = 3, contrast = -1)
        ),
        // R 色彩家族：只收与既有规则不冲突的关键词（"反转"已归 K64、"复古/褪色"已归 FNN、
        // "黑白"已归 M3、"青影"已归 FCNEG——含这些词的复合词同样会被截走，一律不收；
        // RRETRO/RHBW/RCROSS2 不设兜底词，交给模型自选）
        Rule(
            listOf("正片", "幻灯片", "positive"),
            Preset("RPOS", "Positive", "正片 · 浓彩反转", saturation = 2, contrast = 1)
        ),
        Rule(
            listOf("漂白", "银灰", "bleach"),
            Preset("RBLEACH", "Bleach", "漂白 · 银灰硬调", saturation = -2, contrast = 2)
        ),
        Rule(
            listOf("交叉冲洗", "cross process"),
            Preset("RCROSS", "Cross", "交叉冲洗 · 青影黄绿", contrast = 2)
        ),
        // R 色彩第二批：只收与既有规则不冲突的词（"负片/青影"归 FCNEG、"灰阶"归 M3、
        // "纪实"归 FCHROME；含这些词的复合词同样会被前面规则截走，一律不收；
        // RCROSS2 无自然中文词不设，交模型读目录）
        Rule(
            listOf("直出", "基准色"),
            Preset("RSTD", "Standard", "标准 · 直出基准")
        ),
        Rule(
            listOf("亮调灰", "柔灰", "雾灰"),
            Preset("RSOFT", "SOFT-BW", "柔调黑白 · 亮调灰阶", contrast = -2, shadows = 1)
        ),
        Rule(
            listOf("色罩", "negative film"),
            Preset("RNEG", "Negative", "负片 · 色罩感柔调", contrast = -1, shadows = 1)
        ),
        Rule(
            listOf("街拍", "扫街", "street"),
            Preset("RSTREET", "STREET", "街拍 · 纪实硬调", contrast = 2, saturation = -1)
        )
    )

    /** 命中第一条规则即返回；全不命中返回 null（交给模型自己决定）。 */
    fun suggest(text: String): Preset? {
        val t = text.lowercase()
        rules.forEach { rule ->
            if (rule.keys.any { t.contains(it) }) return rule.preset
        }
        return null
    }

    /** 给模型的离线建议文本（无网络时拼进 prompt）；未命中返回空串（不注入噪音）。 */
    fun hintText(text: String): String {
        val p = suggest(text) ?: return ""
        return "本地经验匹配：${p.styleLabel}（${p.reason}），建议起点 饱和${sign(p.saturation)} 对比${sign(p.contrast)} 阴影${sign(p.shadows)} 高光${sign(p.highlights)} 黑位${sign(p.black)} 曝光${sign(p.exposure)} 柔光${sign(p.glow)}"
    }

    private fun sign(v: Int): String = if (v > 0) "+$v" else "$v"
}

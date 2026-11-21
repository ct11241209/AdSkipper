package com.adskip.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则引擎：从 assets/rules.json 读规则。
 *
 * 三类规则，优先级从高到低：
 *  1. skipPatterns —— 高置信度，文字明确是「跳过」类，任何时候都点；
 *  2. closePatterns —— 中置信度，只在 App 刚进前台的 12 秒内点，且必须是可点控件；
 *  3. aggressiveClosePatterns —— 低置信度（关闭/×/我知道了），只有用户手动开「激进模式」才用。
 */
class RuleEngine(private val ctx: Context) {

    var version: Int = 0
        private set

    /** 窗口出现后多久内允许点「关闭」类按钮（毫秒），避免误关正常弹窗 */
    var launchWindowMs: Long = 12000L
        private set

    private val skipRegex = ArrayList<Regex>()
    private val closeRegex = ArrayList<Regex>()
    private val aggressiveRegex = ArrayList<Regex>()
    private val skipIds = ArrayList<String>()
    private val appSkip = HashMap<String, List<Regex>>()
    private val appClose = HashMap<String, List<Regex>>()
    private val appSplashOnlyMs = HashMap<String, Long>()

    fun load() {
        skipRegex.clear()
        closeRegex.clear()
        aggressiveRegex.clear()
        skipIds.clear()
        appSkip.clear()
        appClose.clear()
        appSplashOnlyMs.clear()
        val text = try {
            ctx.assets.open("rules.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            LogStore.log("RULE", "读取 rules.json 失败: " + t.message)
            return
        }
        try {
            val root = JSONObject(text)
            version = root.optInt("version", 1)
            launchWindowMs = root.optLong("launchWindowMs", 12000L)
            skipRegex.addAll(compile(root.optJSONArray("skipPatterns")))
            closeRegex.addAll(compile(root.optJSONArray("closePatterns")))
            aggressiveRegex.addAll(compile(root.optJSONArray("aggressiveClosePatterns")))
            val ids = root.optJSONArray("skipIds")
            if (ids != null) {
                for (i in 0 until ids.length()) {
                    val s = ids.optString(i, "")
                    if (s.isNotEmpty()) skipIds.add(s.lowercase())
                }
            }
            val apps = root.optJSONObject("apps")
            if (apps != null) {
                val keys = apps.keys()
                while (keys.hasNext()) {
                    val pkg = keys.next()
                    val o = apps.optJSONObject(pkg) ?: continue
                    appSkip[pkg] = compile(o.optJSONArray("skipPatterns"))
                    appClose[pkg] = compile(o.optJSONArray("closePatterns"))
                    val only = o.optLong("splashOnlyMs", 0L)
                    if (only > 0L) appSplashOnlyMs[pkg] = only
                }
            }
            LogStore.log(
                "RULE",
                "规则已加载 v" + version + "：跳过 " + skipRegex.size +
                    " 条，关闭 " + closeRegex.size + " 条，激进 " + aggressiveRegex.size + " 条"
            )
        } catch (t: Throwable) {
            LogStore.log("RULE", "解析 rules.json 失败: " + t.message)
        }
    }

    private fun compile(arr: JSONArray?): List<Regex> {
        val out = ArrayList<Regex>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val p = arr.optString(i, "")
            if (p.isEmpty()) continue
            try {
                out.add(Regex(p))
            } catch (t: Throwable) {
                LogStore.log("RULE", "无效正则: " + p)
            }
        }
        return out
    }

    private fun matchAny(list: List<Regex>, text: String?, maxLen: Int): Boolean {
        if (text == null) return false
        val s = text.trim()
        if (s.isEmpty() || s.length > maxLen) return false
        for (r in list) if (r.containsMatchIn(s)) return true
        return false
    }

    fun isSkipText(text: String?, pkg: String): Boolean {
        if (matchAny(skipRegex, text, 16)) return true
        val extra = appSkip[pkg] ?: return false
        return matchAny(extra, text, 16)
    }

    /**
     * 某个 App 是否只在「刚启动的一段窗口内」工作（毫秒），0 = 全程工作。
     *
     * 贴吧这类 App：开屏广告只在冷启动时出现，之后整屏都是信息流正文，
     * 全时段自动点「跳过/关闭」很容易点到帖子和卡片，所以用这个限定时间窗。
     */
    fun splashOnlyMs(pkg: String): Long = appSplashOnlyMs[pkg] ?: 0L

    /**
     * @param aggressive 是否启用了「激进模式」
     */
    fun isCloseText(text: String?, pkg: String, aggressive: Boolean): Boolean {
        if (matchAny(closeRegex, text, 16)) return true
        val extra = appClose[pkg]
        if (extra != null && matchAny(extra, text, 16)) return true
        if (aggressive) return matchAny(aggressiveRegex, text, 16)
        return false
    }

    /** 控件 id 里带 skip / ad_skip 之类，属于高置信度 */
    fun isSkipId(viewId: String?): Boolean {
        if (viewId == null) return false
        val id = viewId.lowercase()
        for (k in skipIds) if (id.contains(k)) return true
        return false
    }
}

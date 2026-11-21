package com.adskip.app

import android.content.Context

/**
 * 广告域名黑名单，来自 assets/blocklist.txt，一行一个域名。
 * 只写「精确的广告/统计域名」，不要写 qq.com 这种大域，否则会把正常 App 干掉。
 */
class Blocklist(ctx: Context) {

    private val domains = HashSet<String>()

    init {
        load(ctx)
    }

    val size: Int
        get() = domains.size

    private fun load(ctx: Context) {
        try {
            ctx.assets.open("blocklist.txt").bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    var line = raw.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("#")) continue
                    // 兼容 hosts 格式： 0.0.0.0 ads.example.com
                    val sp = line.indexOf(' ')
                    if (sp > 0) line = line.substring(sp + 1).trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("*.")) line = line.substring(2)
                    domains.add(line.lowercase().trimEnd('.'))
                }
            }
        } catch (t: Throwable) {
            LogStore.log("BL", "读取 blocklist.txt 失败: " + t.message)
        }
        LogStore.log("BL", "已加载 " + domains.size + " 条拦截域名")
    }

    /** 命中自身或任意上级域 */
    fun isBlocked(domain: String): Boolean {
        var d = domain.lowercase().trimEnd('.')
        var guard = 0
        while (d.isNotEmpty() && guard < 6) {
            if (domains.contains(d)) return true
            val idx = d.indexOf('.')
            if (idx < 0) return false
            d = d.substring(idx + 1)
            guard++
        }
        return false
    }
}

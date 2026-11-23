package com.adskip.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 所有配置都存 SharedPreferences，读写都很轻，没有 IO 压力。
 */
object Prefs {

    private const val FILE = "adskip_prefs"
    private var cached: SharedPreferences? = null

    private fun sp(ctx: Context): SharedPreferences {
        val c = cached
        if (c != null) return c
        val s = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        cached = s
        return s
    }

    /** 总开关：关掉后无障碍服务完全不动作 */
    fun masterEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("master", true)

    fun setMasterEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("master", v).apply()
    }

    /** DNS 拦截层开关 */
    fun dnsEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("dns", false)

    fun setDnsEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("dns", v).apply()
    }

    /** 广告通知清理开关 */
    fun notiEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("noti", false)

    fun setNotiEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("noti", v).apply()
    }

    /** 要清理通知的包名 */
    fun notiPackages(ctx: Context): Set<String> =
        sp(ctx).getStringSet("noti_pkgs", emptySet()) ?: emptySet()

    fun setNotiPackages(ctx: Context, v: Set<String>) {
        sp(ctx).edit().putStringSet("noti_pkgs", HashSet(v)).apply()
    }

    /** 用户自定义：不处理（不自动点击）的包名 */
    fun blacklist(ctx: Context): Set<String> =
        sp(ctx).getStringSet("blacklist", emptySet()) ?: emptySet()

    fun setBlacklist(ctx: Context, v: Set<String>) {
        sp(ctx).edit().putStringSet("blacklist", HashSet(v)).apply()
    }

    /** 激进模式：额外尝试点「关闭 / × / 我知道了」这类通用按钮（更有效，但误点风险更高） */
    fun aggressive(ctx: Context): Boolean = sp(ctx).getBoolean("aggressive", false)

    fun setAggressive(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("aggressive", v).apply()
    }

    /** 累计跳过次数 */
    fun count(ctx: Context): Int = sp(ctx).getInt("count", 0)

    fun bumpCount(ctx: Context) {
        synchronized(this) {
            sp(ctx).edit().putInt("count", count(ctx) + 1).apply()
        }
    }

    fun resetCount(ctx: Context) {
        sp(ctx).edit().putInt("count", 0).apply()
    }
}

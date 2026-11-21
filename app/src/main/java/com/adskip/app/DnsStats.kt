package com.adskip.app

import java.util.concurrent.atomic.AtomicLong

/**
 * DNS 层的真实运行统计。
 *
 * 为什么需要它：这一层用的是「只路由 DNS 的 VPN」，靠系统把查询送给 VPN 的 DNS 服务器。
 * 这个套路在部分 ROM / 系统版本上收不到包（表现为 tun 里一条查询都没有）。
 * 所以判断标准只有一个：**tun 里到底有没有收到 DNS 查询**，收到几条、拦了几条。
 * 用 InetAddress 在本机做解析是测不出来的（本进程被 addDisallowedApplication 排除在外了）。
 */
object DnsStats {

    private val queryCount = AtomicLong(0)
    private val blockedCount = AtomicLong(0)

    fun onQuery() {
        queryCount.incrementAndGet()
    }

    fun onBlocked() {
        blockedCount.incrementAndGet()
    }

    fun queries(): Long = queryCount.get()

    fun blocked(): Long = blockedCount.get()

    fun reset() {
        queryCount.set(0)
        blockedCount.set(0)
    }
}

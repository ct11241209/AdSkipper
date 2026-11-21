package com.adskip.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 极简本地 DNS 代理（只处理 UDP 53）。
 *
 * 原理：VPN 只把 DNS 流量指到本机 10.111.222.3，[DnsProxy] 读 tun 里的 DNS 包：
 *  - 命中黑名单  -> 直接回 NXDOMAIN，广告 SDK 拿不到 IP，广告加载失败；
 *  - 没命中      -> 转发给阿里/腾讯/百度的公共 DNS，原样回传。
 * 除了 DNS 之外一个字节都不经过这里，所以耗电极低（不代理网页流量）。
 */
class DnsProxy(
    private val input: InputStream,
    private val output: OutputStream,
    private val blocklist: Blocklist
) {

    companion object {
        private const val MY_IP = "10.111.222.3"
        private const val UPSTREAM_TIMEOUT_MS = 2500
        private val UPSTREAMS = arrayOf("223.5.5.5", "119.29.29.29", "180.76.76.76")
        private const val MAX_PACKET = 32768
    }

    @Volatile
    private var stopped = false

    private var blockedCount = 0
    private var logWindowStart = 0L

    fun stop() {
        // 只置标志。fd 的所有权归 ParcelFileDescriptor，由 AdDnsVpnService 统一关闭，
        // 避免同一个 fd 被 3 个 owner 重复关闭。
        stopped = true
    }

    fun loop() {
        val buf = ByteArray(MAX_PACKET)
        while (!stopped) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                break
            }
            if (n <= 0) continue
            try {
                handle(buf, n)
            } catch (t: Throwable) {
                LogStore.log("DNS", "处理异常: " + t.message)
            }
        }
    }

    private fun handle(pkt: ByteArray, len: Int) {
        if (len < 28) return
        val vihl = pkt[0].toInt() and 0xFF
        if ((vihl shr 4) != 4) return
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return
        if ((pkt[9].toInt() and 0xFF) != 17) return

        val totalLen = u16(pkt, 2)
        if (totalLen < ihl + 8 || totalLen > len) return

        val srcIp = pkt.copyOfRange(12, 16)
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        if (dstPort != 53) return

        val udpLen = u16(pkt, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > totalLen) return
        val query = pkt.copyOfRange(ihl + 8, ihl + udpLen)
        if (query.size < 12) return
        if ((query[2].toInt() and 0x80) != 0) return

        val name = parseName(query) ?: return
        DnsStats.onQuery()
        val blocked = blocklist.isBlocked(name)
        val response: ByteArray = if (blocked) {
            DnsStats.onBlocked()
            noteBlocked(name)
            makeError(query, 3)
        } else {
            forward(query) ?: makeError(query, 2)
        }
        sendReply(srcIp, srcPort, response)
    }

    private fun noteBlocked(name: String) {
        blockedCount++
        val now = System.currentTimeMillis()
        if (logWindowStart == 0L || now - logWindowStart > 60000L) {
            if (logWindowStart != 0L) {
                LogStore.log("DNS", "近一分钟拦截 " + blockedCount + " 次")
            }
            logWindowStart = now
            blockedCount = 0
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    /** 解析第一个 question 的域名，失败返回 null */
    private fun parseName(msg: ByteArray): String? {
        if (msg.size < 13) return null
        var pos = 12
        val sb = StringBuilder()
        var guard = 0
        while (guard < 128) {
            guard++
            if (pos >= msg.size) return null
            val l = msg[pos].toInt() and 0xFF
            if (l == 0) {
                pos++
                break
            }
            if ((l and 0xC0) == 0xC0) return null
            pos++
            if (pos + l > msg.size) return null
            if (sb.length > 0) sb.append('.')
            for (i in 0 until l) {
                val c = msg[pos + i].toInt() and 0xFF
                sb.append(if (c in 33..126) c.toChar() else '?')
            }
            pos += l
        }
        return if (sb.length == 0) null else sb.toString().lowercase()
    }

    /** question 段结束位置（含） */
    private fun questionEnd(msg: ByteArray): Int {
        var pos = 12
        var guard = 0
        while (pos < msg.size && guard < 128) {
            guard++
            val l = msg[pos].toInt() and 0xFF
            if (l == 0) {
                pos++
                return (pos + 4).coerceAtMost(msg.size)
            }
            if ((l and 0xC0) == 0xC0) return (pos + 2 + 4).coerceAtMost(msg.size)
            pos += 1 + l
        }
        return msg.size
    }

    /** 构造错误应答（rcode: 3=NXDOMAIN, 2=SERVFAIL） */
    private fun makeError(query: ByteArray, rcode: Int): ByteArray {
        val end = questionEnd(query)
        val resp = ByteArray(end)
        System.arraycopy(query, 0, resp, 0, end)
        resp[2] = 0x81.toByte() // QR=1 OPCODE=0 AA=0 TC=0 RD=1
        resp[3] = (0x80 or (rcode and 0x0F)).toByte() // RA=1, RCODE
        resp[4] = 0
        resp[5] = 1 // qdcount
        for (i in 6 until 12) resp[i] = 0
        return resp
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (up in UPSTREAMS) {
            val socket: DatagramSocket = try {
                DatagramSocket()
            } catch (t: Throwable) {
                null
            } ?: continue
            try {
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val addr = InetAddress.getByName(up)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val rbuf = ByteArray(4096)
                val rp = DatagramPacket(rbuf, rbuf.size)
                socket.receive(rp)
                if (rp.length > 0) return rbuf.copyOf(rp.length)
            } catch (t: Throwable) {
                // 换下一个上游
            } finally {
                try {
                    socket.close()
                } catch (e: Throwable) {
                }
            }
        }
        return null
    }

    private fun sendReply(clientIp: ByteArray, clientPort: Int, payload: ByteArray) {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        p[1] = 0
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[4] = 0
        p[5] = 0
        p[6] = 0x40
        p[7] = 0
        p[8] = 64
        p[9] = 17
        p[10] = 0
        p[11] = 0
        val myIp = InetAddress.getByName(MY_IP).address
        System.arraycopy(myIp, 0, p, 12, 4)
        System.arraycopy(clientIp, 0, p, 16, 4)

        p[20] = 0
        p[21] = 53
        p[22] = (clientPort shr 8).toByte()
        p[23] = clientPort.toByte()
        p[24] = (udpLen shr 8).toByte()
        p[25] = udpLen.toByte()
        p[26] = 0
        p[27] = 0
        System.arraycopy(payload, 0, p, 28, payload.size)

        val ipCk = fold(addSum(0L, p, 0, 20))
        p[10] = (ipCk shr 8).toByte()
        p[11] = ipCk.toByte()

        val pseudo = ByteArray(12)
        System.arraycopy(myIp, 0, pseudo, 0, 4)
        System.arraycopy(clientIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 17
        pseudo[10] = (udpLen shr 8).toByte()
        pseudo[11] = udpLen.toByte()
        var sum = addSum(0L, pseudo, 0, 12)
        sum = addSum(sum, p, 20, udpLen)
        var udpCk = fold(sum)
        if (udpCk == 0) udpCk = 0xFFFF
        p[26] = (udpCk shr 8).toByte()
        p[27] = udpCk.toByte()

        try {
            output.write(p)
            output.flush()
        } catch (e: IOException) {
            LogStore.log("DNS", "写回 tun 失败: " + e.message)
        }
    }

    private fun addSum(sum0: Long, data: ByteArray, off: Int, len: Int): Long {
        var sum = sum0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        return sum
    }

    private fun fold(sum0: Long): Int {
        var sum = sum0
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

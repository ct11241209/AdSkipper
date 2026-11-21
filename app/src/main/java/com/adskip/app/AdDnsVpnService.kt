package com.adskip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * DNS 拦截层的容器服务。只接管 DNS，不代理任何网页流量。
 * 如果系统拒绝建立 VPN / 建立失败，会立刻 stopSelf，保证不会把手机网络弄断。
 */
class AdDnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.adskip.app.vpn.START"
        const val ACTION_STOP = "com.adskip.app.vpn.STOP"
        private const val CHANNEL_ID = "adskip_dns"
        private const val NOTI_ID = 1001

        @Volatile
        var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var proxy: DnsProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // 系统在进程被杀后重启服务。只有用户上次确实开着才恢复，不擅自替用户开 VPN。
            if (!Prefs.dnsEnabled(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startFilter()
        return START_STICKY
    }

    private fun startFilter() {
        createChannel()
        // 只要被 startForegroundService() 叫起来，就必须在 5 秒内 startForeground()，
        // 否则系统会以 ForegroundServiceDidNotStartInTimeException 杀掉进程。
        // 所以这次调用放在 running 判断之前，重复启动也要补一次。
        try {
            startForeground(NOTI_ID, buildNotification())
        } catch (t: Throwable) {
            LogStore.log("VPN", "前台通知失败: " + t.message)
            stopSelf()
            return
        }
        if (running) return

        val builder = Builder()
        builder.setSession("跳过广告 · DNS 过滤")
        builder.addAddress("10.111.222.1", 24)
        builder.addDnsServer("10.111.222.3")
        builder.addRoute("10.111.222.3", 32)
        builder.setMtu(1500)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (t: Throwable) {
        }

        val pfd = try {
            builder.establish()
        } catch (t: Throwable) {
            LogStore.log("VPN", "建立失败: " + t.message)
            null
        }
        if (pfd == null) {
            LogStore.log("VPN", "VPN 未能建立，已自动停止（网络不受影响）")
            stopSelf()
            return
        }
        tun = pfd

        val bl = Blocklist(this)
        val p = DnsProxy(
            FileInputStream(pfd.fileDescriptor),
            FileOutputStream(pfd.fileDescriptor),
            bl
        )
        proxy = p
        running = true
        Prefs.setDnsEnabled(this, true)

        val t = Thread({
            try {
                p.loop()
            } catch (e: Throwable) {
                LogStore.log("VPN", "代理线程异常: " + e.message)
            } finally {
                running = false
                LogStore.log("VPN", "DNS 代理已停止")
            }
        }, "adskip-dns")
        t.isDaemon = true
        worker = t
        t.start()
        LogStore.log("VPN", "DNS 过滤已启动，拦截域名 " + bl.size + " 条")
    }

    override fun onRevoke() {
        LogStore.log("VPN", "系统回收了 VPN，自动停止")
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        val wasRunning = running
        running = false
        try {
            proxy?.stop()
        } catch (t: Throwable) {
        }
        proxy = null
        // 只在这里关 fd 一次：read 会因此返回错误，代理线程自然退出（不阻塞主线程等它）
        try {
            tun?.close()
        } catch (t: Throwable) {
        }
        tun = null
        worker = null
        Prefs.setDnsEnabled(this, false)
        try {
            stopForeground(true)
        } catch (t: Throwable) {
        }
        if (wasRunning) LogStore.log("VPN", "已关闭")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "DNS 过滤", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.description = "广告域名拦截运行时显示"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setContentTitle("跳过广告 · DNS 过滤运行中")
            .setContentText("只拦截广告域名，不代理其他流量")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pi)
        return builder.build()
    }
}

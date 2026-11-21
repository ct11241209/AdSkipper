package com.adskip.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 广告通知清理：只清理用户在白名单里手动指定的包名，默认不动作，
 * 避免误删正常消息。通知是事件回调，零轮询、零耗电。
 */
class NotiCleanService : NotificationListenerService() {

    companion object {
        @Volatile
        var connected = false
    }

    override fun onListenerConnected() {
        connected = true
        LogStore.log("NOTI", "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!Prefs.notiEnabled(this)) return
        val pkg = n.packageName ?: return
        if (!Prefs.notiPackages(this).contains(pkg)) return
        if (n.isOngoing) return
        try {
            cancelNotification(n.key)
            LogStore.log("NOTI", "已清理广告通知: " + pkg)
        } catch (t: Throwable) {
            LogStore.log("NOTI", "清理失败: " + t.message)
        }
    }
}

package com.adskip.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 核心：无障碍自动跳广告。
 *
 * 省电设计（重点）：
 *  1. 只订阅「窗口切换」和「窗口内容变化」两类事件，不轮询；
 *  2. 只有窗口刚出现的一段时间内才处理内容变化事件，之后彻底闲着；
 *  3. 扫描跑在独立后台线程，不占主线程，也不会常驻唤醒 CPU；
 *  4. 每个窗口最多点 8 次，点完就收手。
 *
 * 防误点策略：
 *  - 「跳过」类（高置信度）任何时候都点；
 *  - 「关闭广告/残忍离开/以后再说」类只在 App 刚进前台 12 秒内点，且必须是可点控件，
 *    绝不对手势兜底（避免点到广告的"下载"按钮）；
 *  - 「关闭 / × / 我知道了」这类通用文案，只有用户手动开「激进模式」才点。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 40
        private const val MIN_CLICK_INTERVAL_MS = 320L
        private const val MAX_CLICKS_PER_WINDOW = 8
        private const val CONTENT_SCAN_WINDOW_MS = 25000L
        private const val SCAN_MIN_GAP_MS = 130L

        @Volatile
        var connected = false

        @Volatile
        var instance: AdSkipService? = null

        /** 系统界面 / 输入法 / 桌面，绝对不碰 */
        private val STATIC_EXCLUDE = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.inputmethod.latin",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard",
            "com.samsung.android.app.cocktailbarservice",
            "com.adskip.app"
        )

        /** 看到这些包名，说明用户离开了目标 App；再切回去要重新算「刚进前台」 */
        private val LEAVE_HINTS = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.adskip.app"
        )
    }

    private class Cand(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val label: String,
        /** 找不到可点控件时，是否允许用坐标手势兜底 */
        val gestureOk: Boolean
    )

    private class ScanResult(val cand: Cand?, val visited: Int)

    private var rules: RuleEngine? = null
    private var thread: HandlerThread? = null
    private var scanHandler: Handler? = null
    private val busy = AtomicBoolean(false)

    private var currentPkg: String? = null
    private var windowStart = 0L
    private var appSessionStart = 0L
    private var leftApp = false
    private var lastScanAt = 0L
    private var clicksInWindow = 0
    private var lastClickAt = 0L

    @Volatile
    private var lastSummary: String = ""

    private val scanTask = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val r = RuleEngine(this)
        r.load()
        rules = r
        val t = HandlerThread("adskip-scan")
        t.start()
        thread = t
        scanHandler = Handler(t.looper)
        instance = this
        connected = true
        LogStore.log("SVC", "无障碍服务已连接，规则 v" + r.version)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        instance = null
        stopWorker()
        LogStore.log("SVC", "无障碍服务被关闭")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        instance = null
        stopWorker()
        super.onDestroy()
    }

    private fun stopWorker() {
        scanHandler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        scanHandler = null
        thread = null
    }

    override fun onInterrupt() {
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!Prefs.masterEnabled(this)) return
        val now = SystemClock.elapsedRealtime()
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                if (LEAVE_HINTS.contains(pkg)) {
                    // 用户切到了桌面/本 App，说明离开了原来的应用
                    leftApp = true
                    return
                }
                if (isExcluded(pkg)) return
                if (pkg != currentPkg || leftApp) {
                    // 换了 App，或从后台重新回到这个 App：重置「关闭类」的安全时间窗和点击配额
                    currentPkg = pkg
                    appSessionStart = now
                    leftApp = false
                }
                windowStart = now
                clicksInWindow = 0
                lastScanAt = 0L
                scheduleScan(50L)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (currentPkg == null) return
                if (now - windowStart > CONTENT_SCAN_WINDOW_MS) return
                scheduleScan(SCAN_MIN_GAP_MS)
            }
            else -> return
        }
    }

    private fun scheduleScan(delay: Long) {
        val h = scanHandler ?: return
        h.removeCallbacks(scanTask)
        h.postDelayed(scanTask, delay)
    }

    private fun scan() {
        if (!busy.compareAndSet(false, true)) return
        try {
            doScan()
        } catch (t: Throwable) {
            LogStore.log("SVC", "扫描异常: " + t.message)
        } finally {
            busy.set(false)
        }
    }

    private fun doScan() {
        if (!Prefs.masterEnabled(this)) return
        val r = rules ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - windowStart > CONTENT_SCAN_WINDOW_MS) return
        if (lastScanAt != 0L && now - lastScanAt < SCAN_MIN_GAP_MS) return
        if (clicksInWindow >= MAX_CLICKS_PER_WINDOW) return
        if (now - lastClickAt < MIN_CLICK_INTERVAL_MS) return
        lastScanAt = now

        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        val pkg = root.packageName?.toString() ?: return
        if (isExcluded(pkg)) return

        val result = findBest(r, root, pkg, now - appSessionStart)
        val cand = result.cand
        if (cand == null) {
            lastSummary = pkg + "：扫描 " + result.visited + " 个节点，没找到可点的跳过/关闭按钮"
            return
        }

        val ok = clickNode(cand)
        if (ok) {
            lastClickAt = SystemClock.elapsedRealtime()
            clicksInWindow++
            Prefs.bumpCount(this)
            lastSummary = pkg + "：命中【" + cand.label + "】score=" + cand.score
            LogStore.log("CLICK", pkg + "  " + cand.label + "  score=" + cand.score)
        } else {
            lastSummary = pkg + "：找到【" + cand.label + "】但点不动"
            LogStore.log("MISS", pkg + "  " + cand.label + "  找到了但点不动")
        }
    }

    /**
     * 广度优先遍历当前窗口，找出最像「跳过/关闭广告」的那个控件。
     */
    private fun findBest(
        r: RuleEngine,
        root: AccessibilityNodeInfo,
        pkg: String,
        appAge: Long
    ): ScanResult {
        val screen = Rect()
        try {
            root.getBoundsInScreen(screen)
        } catch (t: Throwable) {
        }
        val sw = if (screen.width() > 0) screen.width() else 1080
        val sh = if (screen.height() > 0) screen.height() else 2340
        val screenArea = sw.toLong() * sh.toLong()
        // 只有 App 刚进前台的 12 秒内，才允许点「关闭类」
        val allowClose = appAge < r.launchWindowMs
        val aggressive = Prefs.aggressive(this)

        var best: Cand? = null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val item = queue.poll() ?: break
            val node = item.first
            val depth = item.second
            visited++
            if (depth > MAX_DEPTH) continue

            val childCount = try {
                node.childCount
            } catch (t: Throwable) {
                0
            }
            for (i in 0 until childCount) {
                val c = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                }
                if (c != null) queue.add(c to (depth + 1))
            }

            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (t: Throwable) {
                continue
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (area > screenArea * 85L / 100L) continue

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val viewId = node.viewIdResourceName

            var score = 0
            var label = ""
            var gestureOk = true

            if ((text != null && r.isSkipText(text, pkg)) || (desc != null && r.isSkipText(desc, pkg))) {
                score = 100
                label = "跳过 " + (if (!text.isNullOrEmpty()) text else desc)
            } else if (r.isSkipId(viewId)) {
                score = 92
                label = "跳过id " + viewId
            } else if (allowClose && textOrDesc(text, desc, r, pkg, aggressive)) {
                score = 55
                label = "关闭类 " + (if (!text.isNullOrEmpty()) text else desc)
                // 「关闭类」绝不用手势兜底：不可点就不点
                gestureOk = false
            }
            if (score == 0) continue

            // 跳过按钮绝大多数在右上角，位置加分
            if (bounds.centerX() > sw / 2) score += 6
            if (bounds.centerY() < sh * 2 / 5) score += 6
            // 面积越大越不像按钮，减分
            score -= (area / 20000L).toInt().coerceAtMost(12)
            // 层级越深越可能是文字节点而非按钮，轻微减分
            score -= depth / 5
            if (!node.isClickable) score -= 3

            if (best == null || score > best.score) {
                best = Cand(node, score, label, gestureOk)
            }
        }
        return ScanResult(best, visited)
    }

    private fun textOrDesc(
        text: String?,
        desc: String?,
        r: RuleEngine,
        pkg: String,
        aggressive: Boolean
    ): Boolean {
        if (text != null && r.isCloseText(text, pkg, aggressive)) return true
        if (desc != null && r.isCloseText(desc, pkg, aggressive)) return true
        return false
    }

    private fun clickNode(cand: Cand): Boolean {
        val node = cand.node
        if (node.isClickable && safePerform(node)) return true

        // 往上找 3 层可点的父控件（很多跳过的文字在不可点的 TextView 里）
        var p = try {
            node.parent
        } catch (t: Throwable) {
            null
        }
        var i = 0
        while (p != null && i < 3) {
            if (p.isClickable && safePerform(p)) return true
            p = try {
                p.parent
            } catch (t: Throwable) {
                null
            }
            i++
        }

        if (!cand.gestureOk) return false

        // 兜底：按坐标手势点击中心点
        val b = Rect()
        try {
            node.getBoundsInScreen(b)
        } catch (t: Throwable) {
            return false
        }
        if (b.width() <= 0 || b.height() <= 0) return false

        // 兜底手势只在「小而靠边」的控件上做，避免点到整块广告的下载按钮
        val dm = resources.displayMetrics
        val sw = if (dm.widthPixels > 0) dm.widthPixels else 1080
        val sh = if (dm.heightPixels > 0) dm.heightPixels else 2340
        val small = b.width().toLong() * b.height().toLong() < sw.toLong() * sh.toLong() / 6L
        val cornerish = b.centerY() < sh * 2 / 5 || b.centerX() > sw * 2 / 3
        if (!small || !cornerish) return false

        val path = Path()
        path.moveTo(b.exactCenterX(), b.exactCenterY())
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 30L))
            .build()
        return try {
            dispatchGesture(gd, null, null)
        } catch (t: Throwable) {
            false
        }
    }

    private fun safePerform(n: AccessibilityNodeInfo): Boolean = try {
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (t: Throwable) {
        false
    }

    private fun isExcluded(pkg: String): Boolean {
        if (STATIC_EXCLUDE.contains(pkg)) return true
        if (Prefs.blacklist(this).contains(pkg)) return true
        return false
    }

    /**
     * 主界面「上次扫描结果」按钮用：返回最近一次扫描的结论。
     * 不在主线程做控件树遍历，也不需要用户"切回来"时还停在广告页。
     */
    fun lastScanReport(): String {
        val s = lastSummary
        if (s.isEmpty()) {
            return "还没有扫描记录。\n\n做法：打开一个有开屏广告的 App，等广告出来，再切回本 App 点这个按钮。"
        }
        return s
    }
}

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
import kotlin.math.abs

/**
 * 核心：无障碍自动跳广告。
 *
 * 省电设计（重点）：
 *  1. 只订阅「窗口切换」和「窗口内容变化」两类事件，不轮询；
 *  2. 只有窗口刚出现的一段时间内才处理内容变化事件，之后彻底闲着；
 *  3. 扫描跑在独立后台线程，不占主线程，也不会常驻唤醒 CPU；
 *  4. 每个窗口最多点 4 次，点完就收手。
 *
 * 防误点策略（v3 加强，重点针对贴吧这类信息流 App）：
 *  1. 「跳过」类（高置信度）任何时候都点；「关闭类」只在 App 刚进前台 12 秒内点；
 *  2. 只点「按钮尺寸」的控件：超过屏幕 6% 面积 / 70% 宽 / 25% 高的，一律不点；
 *  3. 控件在列表容器（RecyclerView / ListView / ViewPager…）里，一律当信息流内容放过；
 *  4. 点「跳过」时优先点它自己（自己可点就点，不可点在它中心戳一下），
 *     绝不为了点它而爬到整块广告的父容器上——那等于点了广告；
 *  5. 同一位置 6 秒内不重复点；
 *  6. rules.json 里可以用 splashOnlyMs 给某个 App 限定「只在前 N 秒干活」。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 40
        private const val MIN_CLICK_INTERVAL_MS = 320L
        private const val MAX_CLICKS_PER_WINDOW = 4
        private const val CONTENT_SCAN_WINDOW_MS = 25000L
        private const val SCAN_MIN_GAP_MS = 130L

        /** 同一个位置刚点过就别再点，防止在一块内容上连点 */
        private const val SAME_SPOT_COOLDOWN_MS = 6000L

        /** 点击目标必须是「按钮尺寸」，超过这些比例的一律不点（单位：%） */
        private const val MAX_TARGET_AREA_PCT = 6
        private const val MAX_TARGET_WIDTH_PCT = 70
        private const val MAX_TARGET_HEIGHT_PCT = 25

        /**
         * 这些容器里的文字是信息流 / 列表内容，不是广告按钮。
         * 刻意不含 ViewPager：不少开屏广告用 ViewPager 做多素材轮播，把「跳过」放在里面，
         * 把它一起拦掉会误伤开屏广告本身。
         */
        private val LIST_CLASS_HINTS = arrayOf(
            "RecyclerView", "ListView", "GridView", "AbsListView"
        )

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

    private class ScanResult(
        val cand: Cand?,
        val visited: Int,
        /** 命中了、但因为护栏没点（用于在「上次扫描结果」里解释原因） */
        val guardNote: String? = null
    )

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
    private var lastClickBounds: Rect? = null
    private var lastGuardNote: String? = null

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
                lastClickBounds = null
                lastGuardNote = null
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

        val appAge = now - appSessionStart

        // 有些 App（贴吧就是典型）只在启动那一会儿有开屏广告，之后整屏都是信息流。
        // 这类 App 用 rules.json 的 splashOnlyMs 限定「只在前 N 秒干活」，彻底免掉刷帖时的误点。
        val splashOnly = r.splashOnlyMs(pkg)
        if (splashOnly > 0 && appAge > splashOnly) return

        val dm = resources.displayMetrics
        val sw = if (dm.widthPixels > 0) dm.widthPixels else 1080
        val sh = if (dm.heightPixels > 0) dm.heightPixels else 2340

        val result = findBest(r, root, pkg, appAge, sw, sh)
        val cand = result.cand

        if (cand == null) {
            val note = result.guardNote
            lastSummary = if (note != null) {
                pkg + "：扫描 " + result.visited + " 个节点，命中过按钮但" + note + "\n" +
                    "→ 已主动放过（防误点），这是正常的，不是你设置错了。"
            } else {
                pkg + "：扫描 " + result.visited + " 个节点，没找到可点的跳过/关闭按钮"
            }
            if (note != null && note != lastGuardNote) {
                lastGuardNote = note
                LogStore.log("GUARD", pkg + "  " + note + "  已放过")
            }
            return
        }

        // 同一位置 6 秒内不重复点：误点也不会连点好几下
        val cb = boundsOf(cand.node)
        val prev = lastClickBounds
        if (cb != null && prev != null &&
            now - lastClickAt < SAME_SPOT_COOLDOWN_MS &&
            abs(cb.centerX() - prev.centerX()) < 24 &&
            abs(cb.centerY() - prev.centerY()) < 24
        ) {
            return
        }

        val ok = clickNode(cand, sw, sh)
        if (ok) {
            lastClickAt = SystemClock.elapsedRealtime()
            clicksInWindow++
            lastClickBounds = cb?.let { Rect(it) }
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
     *
     * 注意：inList() / targetOk() 这类父链检查只在「已经命中」的节点上做，
     * 不会对 2000 个节点全做一遍（那要走几万次跨进程调用，太慢）。
     */
    private fun findBest(
        r: RuleEngine,
        root: AccessibilityNodeInfo,
        pkg: String,
        appAge: Long,
        sw: Int,
        sh: Int
    ): ScanResult {
        val screenArea = sw.toLong() * sh.toLong()
        // 只有 App 刚进前台的 12 秒内，才允许点「关闭类」
        val allowClose = appAge < r.launchWindowMs
        val aggressive = Prefs.aggressive(this)

        var best: Cand? = null
        var guardNote: String? = null
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

            val bounds = boundsOf(node) ?: continue
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

            // 护栏 1：尺寸不像按钮 → 多半是整块广告、卡片或标记牌，点了就打开内容
            if (!targetOk(bounds, sw, sh)) {
                if (guardNote == null) {
                    guardNote = "命中的【" + label + "】尺寸不像按钮（占屏 " + (area * 100 / screenArea) + "%）"
                }
                continue
            }

            // 护栏 2：在列表容器里 → 这是信息流正文（标题/楼层/广告卡标签），点了就进内容
            if (inList(node, sw, sh)) {
                if (guardNote == null) {
                    guardNote = "命中的【" + label + "】在列表容器里（像信息流内容）"
                }
                continue
            }

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
        return ScanResult(best, visited, guardNote)
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

    /**
     * 真正执行一次点击。顺序刻意设计成「先点按钮自己，再考虑父控件」：
     *
     * 很多开屏广告把「跳过」文字和整块广告图片放在同一个可点容器里，
     * 一旦往上爬到那个父容器去点，就等于点了广告本身，会跳进广告落地页
     * ——用户的感觉就是「广告没跳过，反而点进去了」。
     */
    private fun clickNode(cand: Cand, sw: Int, sh: Int): Boolean {
        val node = cand.node

        // 1) 自己就是可点的按钮
        if (node.isClickable && safePerform(node)) return true

        // 2) 「跳过」类：直接在它自己的中心戳一下（视觉上那个按钮就在那儿）
        if (cand.gestureOk) {
            val b = boundsOf(node)
            if (b != null && targetOk(b, sw, sh) && tapCenter(b)) return true
        }

        // 3) 再往上找 3 层可点的父控件，但父控件同样必须像按钮，且不能在列表里
        var p = try {
            node.parent
        } catch (t: Throwable) {
            null
        }
        var i = 0
        while (p != null && i < 3) {
            val pb = boundsOf(p)
            if (pb != null && targetOk(pb, sw, sh) && !inList(p, sw, sh) && p.isClickable) {
                return safePerform(p)
            }
            p = try {
                p.parent
            } catch (t: Throwable) {
                null
            }
            i++
        }
        return false
    }

    private fun tapCenter(b: Rect): Boolean {
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

    private fun boundsOf(n: AccessibilityNodeInfo): Rect? {
        val r = Rect()
        return try {
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) r else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 点击目标必须是「按钮尺寸」：整块广告 / 卡片 / 半个屏幕的容器都过不了这一关 */
    private fun targetOk(b: Rect, sw: Int, sh: Int): Boolean {
        val area = b.width().toLong() * b.height().toLong()
        val screen = sw.toLong() * sh.toLong()
        if (area <= 0L) return false
        if (area > screen * MAX_TARGET_AREA_PCT / 100L) return false
        if (b.width() > sw * MAX_TARGET_WIDTH_PCT / 100) return false
        if (b.height() > sh * MAX_TARGET_HEIGHT_PCT / 100) return false
        return true
    }

    /**
     * 这个控件是不是在「列表容器」里（信息流、帖子列表、广告卡片列表）。
     *
     * 判断两件事：
     *  1. 祖先里有 RecyclerView / ListView / GridView 这类类名；
     *  2. 祖先自己 isScrollable、铺满大半屏、挂着 3 个以上条目（自定义列表的兜底判断）。
     *
     * 刻意不把 ViewPager 算进来：开屏广告常用 ViewPager 轮播素材，「跳过」就在里面。
     */
    private fun inList(start: AccessibilityNodeInfo, sw: Int, sh: Int): Boolean {
        var n: AccessibilityNodeInfo? = start
        var i = 0
        while (n != null && i < 8) {
            val cls = try {
                n.className?.toString() ?: ""
            } catch (t: Throwable) {
                ""
            }
            for (h in LIST_CLASS_HINTS) {
                if (cls.contains(h, ignoreCase = true)) return true
            }
            val b = boundsOf(n)
            val scrollable = try {
                n.isScrollable
            } catch (t: Throwable) {
                false
            }
            val childCount = try {
                n.childCount
            } catch (t: Throwable) {
                0
            }
            // 兜底：铺满大半屏、能滚、里面挂着一堆条目的容器，就是信息流列表（自定义列表也能认出来）
            if (scrollable && b != null && childCount >= 3 &&
                b.width() > sw * 85 / 100 && b.height() > sh * 50 / 100
            ) {
                return true
            }
            n = try {
                n.parent
            } catch (t: Throwable) {
                null
            }
            i++
        }
        return false
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

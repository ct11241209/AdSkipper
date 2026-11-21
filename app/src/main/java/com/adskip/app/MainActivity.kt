package com.adskip.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_POST_NOTI = 1002
    }

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var aggressiveSwitch: Switch
    private lateinit var dnsSwitch: Switch
    private lateinit var notiSwitch: Switch
    private lateinit var notiPkgEdit: EditText
    private lateinit var blacklistEdit: EditText

    private var refreshing = false
    private var pendingDnsEnable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        LogStore.log("UI", "主界面已打开")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun heading(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        v.setPadding(0, 0, 0, dp(6))
        return v
    }

    private fun note(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        v.setPadding(0, dp(4), 0, dp(4))
        return v
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(16), dp(18), dp(30))
        scroll.addView(root)

        root.addView(heading("跳过广告 · AdSkipper"))
        root.addView(note("免 root。两层：① 无障碍自动点「跳过/关闭」② 本地 DNS 拦截广告域名。全部在本机完成，App 自己不联网、不上传任何数据。"))

        statusView = TextView(this)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        statusView.setPadding(0, dp(14), 0, dp(6))
        root.addView(statusView)

        root.addView(button("① 去开启「无障碍服务」") { openA11ySettings() })

        masterSwitch = Switch(this)
        masterSwitch.text = "启用自动跳过（总开关）"
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (refreshing) return@setOnCheckedChangeListener
            Prefs.setMasterEnabled(this, checked)
            toast(if (checked) "已开启自动跳过" else "已关闭自动跳过")
        }
        root.addView(masterSwitch)

        aggressiveSwitch = Switch(this)
        aggressiveSwitch.text = "激进模式（额外点「关闭 / × / 我知道了」）"
        aggressiveSwitch.setOnCheckedChangeListener { _, checked ->
            if (refreshing) return@setOnCheckedChangeListener
            Prefs.setAggressive(this, checked)
            toast(
                if (checked) "已开启激进模式：广告关得更干净，但误点风险更高"
                else "已关闭激进模式：只点高置信度的广告关闭按钮"
            )
        }
        root.addView(aggressiveSwitch)

        statsView = TextView(this)
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        statsView.setPadding(0, dp(6), 0, dp(10))
        root.addView(statsView)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(button("查看日志") { showLog() }, weight1())
        row1.addView(button("上次扫描结果") { showLastScan() }, weight1())
        root.addView(row1)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(button("忽略电池优化") { requestIgnoreBattery() }, weight1())
        row2.addView(button("统计清零") { Prefs.resetCount(this); refresh(); toast("已清零") }, weight1())
        root.addView(row2)

        // ---------------- DNS 层
        val gap = View(this)
        gap.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
        root.addView(gap)

        root.addView(heading("DNS 拦截（可选）"))
        dnsSwitch = Switch(this)
        dnsSwitch.text = "开启 DNS 拦截"
        dnsSwitch.setOnCheckedChangeListener { _, checked ->
            if (refreshing) return@setOnCheckedChangeListener
            if (checked) requestDns() else stopDns()
        }
        root.addView(dnsSwitch)
        root.addView(button("查看 DNS 拦截统计（判断有没有真的生效）") { showDnsStats() })
        root.addView(
            note(
                "只接管 DNS 查询，不代理网页流量，耗电极低。\n" +
                    "注意：会占用系统 VPN 通道（和公司的 VPN / 梯子 冲突，二选一）；\n" +
                    "开启前必须把 设置→连接→更多连接设置→私人 DNS 改成「关闭」，否则拦截不生效。\n" +
                    "开完用几分钟手机，回来点上面的「统计」按钮：如果「收到查询」一直是 0，说明这层没打通，直接关掉它。\n" +
                    "如果开了之后某些 App 图片加载不出来，就是这层拦多了，先关掉它。"
            )
        )

        // ---------------- 通知清理
        val gap2 = View(this)
        gap2.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
        root.addView(gap2)

        root.addView(heading("广告通知清理（可选）"))
        notiSwitch = Switch(this)
        notiSwitch.text = "启用通知清理"
        notiSwitch.setOnCheckedChangeListener { _, checked ->
            if (refreshing) return@setOnCheckedChangeListener
            Prefs.setNotiEnabled(this, checked)
            if (checked) {
                toast("记得给通知使用权")
                openNotiSettings()
            }
        }
        root.addView(notiSwitch)
        root.addView(button("② 打开「通知使用权」设置") { openNotiSettings() })
        root.addView(note("只清理下面这些包名的通知，其它一律不动。多个用逗号隔开（例如 com.xingin.xhs）："))
        notiPkgEdit = EditText(this)
        notiPkgEdit.hint = "com.example.app,com.example.app2"
        notiPkgEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        notiPkgEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        root.addView(notiPkgEdit)
        root.addView(button("保存通知清理名单") {
            Prefs.setNotiPackages(this, parsePackages(notiPkgEdit.text.toString()))
            toast("已保存")
        })

        // ---------------- 黑名单
        val gap3 = View(this)
        gap3.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
        root.addView(gap3)

        root.addView(heading("不处理的应用（黑名单）"))
        root.addView(note("这些包名不会被自动点击，防止误操作（银行、支付类建议加进来）。多个用逗号隔开："))
        blacklistEdit = EditText(this)
        blacklistEdit.hint = "com.example.bank,com.tencent.mm"
        blacklistEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        blacklistEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        root.addView(blacklistEdit)
        root.addView(button("保存黑名单") {
            Prefs.setBlacklist(this, parsePackages(blacklistEdit.text.toString()))
            toast("已保存")
        })

        root.addView(note("原理说明：只用无障碍读出当前屏幕上的按钮文字，匹配「跳过 / 关闭 / ×」这类规则后模拟一次点击。不注入代码、不修改别人的 App、不抓包解密。"))

        return scroll
    }

    private fun weight1(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        p.setMargins(dp(2), dp(2), dp(2), dp(2))
        return p
    }

    private fun parsePackages(raw: String): Set<String> {
        val out = HashSet<String>()
        val parts = raw.split(",", "，", ";", "；", " ", "\n")
        for (p in parts) {
            val s = p.trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 状态

    private fun isA11yOn(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (info in list) {
            val pkg = try {
                info.resolveInfo?.serviceInfo?.packageName
            } catch (t: Throwable) {
                null
            }
            if (pkg == packageName) return true
        }
        return false
    }

    private fun refresh() {
        refreshing = true
        try {
            val on = isA11yOn()
            statusView.text = if (on) {
                "无障碍服务：已开启 ✓"
            } else {
                "无障碍服务：未开启 ✗  点下面第①个按钮，找到「跳过广告」并打开"
            }
            masterSwitch.isChecked = Prefs.masterEnabled(this)
            aggressiveSwitch.isChecked = Prefs.aggressive(this)
            dnsSwitch.isChecked = Prefs.dnsEnabled(this) || AdDnsVpnService.running
            notiSwitch.isChecked = Prefs.notiEnabled(this)
            statsView.text = "累计自动跳过：" + Prefs.count(this) + " 次"
            // 用户正在编辑时不要覆盖输入框内容
            if (!notiPkgEdit.hasFocus()) {
                notiPkgEdit.setText(Prefs.notiPackages(this).joinToString(","))
            }
            if (!blacklistEdit.hasFocus()) {
                blacklistEdit.setText(Prefs.blacklist(this).joinToString(","))
            }
        } finally {
            refreshing = false
        }
    }

    // ---------------------------------------------------------------- 跳转

    private fun openA11ySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("在「已安装的服务」里找到「跳过广告」并开启")
        } catch (t: Throwable) {
            toast("打不开无障碍设置，请手动去 设置→辅助功能")
        }
    }

    private fun openNotiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (t: Throwable) {
            toast("打不开通知使用权设置")
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已经在「不受电池限制」名单里了")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("请手动到 设置→电池→后台使用限制 里加白名单")
            }
        }
    }

    // ---------------------------------------------------------------- DNS

    private fun requestDns() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            pendingDnsEnable = true
            try {
                startActivityForResult(prep, REQ_VPN)
            } catch (t: Throwable) {
                pendingDnsEnable = false
                toast("无法请求 VPN 授权")
                refresh()
            }
            return
        }
        afterVpnAuthorized()
    }

    private fun afterVpnAuthorized() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDnsEnable = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTI)
            return
        }
        startDns()
    }

    private fun startDns() {
        val i = Intent(this, AdDnsVpnService::class.java)
        i.action = AdDnsVpnService.ACTION_START
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Prefs.setDnsEnabled(this, true)
            toast("DNS 过滤启动中…")
        } catch (t: Throwable) {
            toast("启动失败：" + t.message)
            Prefs.setDnsEnabled(this, false)
        }
        statusView.postDelayed({ refresh() }, 900L)
    }

    private fun stopDns() {
        try {
            stopService(Intent(this, AdDnsVpnService::class.java))
        } catch (t: Throwable) {
        }
        Prefs.setDnsEnabled(this, false)
        toast("DNS 过滤已关闭")
        statusView.postDelayed({ refresh() }, 300L)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                afterVpnAuthorized()
            } else {
                pendingDnsEnable = false
                toast("没有授权 VPN，DNS 拦截无法开启")
                refresh()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTI) {
            // 通知权限只影响前台通知看不看得见，不影响功能，所以无论结果都继续
            if (pendingDnsEnable) {
                pendingDnsEnable = false
                startDns()
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun showLog() {
        val lines = LogStore.snapshot()
        val text = if (lines.isEmpty()) {
            "还没有日志。\n\n去打开一个带开屏广告的 App，再回来点这里。"
        } else {
            lines.joinToString("\n")
        }
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("运行日志（" + lines.size + " 条）")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制全部") { _, _ -> copyToClipboard(text) }
            .setNegativeButton("清空") { _, _ ->
                LogStore.clear()
                toast("已清空")
            }
            .show()
    }

    private fun showLastScan() {
        val svc = AdSkipService.instance
        if (svc == null || !AdSkipService.connected) {
            toast("无障碍服务还没连上，先点第①个按钮开启")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("上次扫描结果")
            .setMessage(svc.lastScanReport())
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * DNS 层唯一诚实的判据：看 tun 里到底有没有收到 DNS 查询。
     * （不能用本机 InetAddress 解析来测——本进程被 addDisallowedApplication 排在 VPN 之外）
     */
    private fun showDnsStats() {
        if (!AdDnsVpnService.running) {
            toast("DNS 拦截没在运行，先把开关打开")
            return
        }
        val q = DnsStats.queries()
        val b = DnsStats.blocked()
        val verdict = when {
            q == 0L ->
                "tun 里一条 DNS 查询都没收到。\n\n" +
                    "→ 这套「只路由 DNS」的 VPN 在你机器上没打通（常见现象，不是 bug）。\n" +
                    "建议把 DNS 开关关掉，只用无障碍层，跳开屏广告照样工作。"
            b == 0L ->
                "收到了 " + q + " 条查询，但没命中黑名单。\n\n" +
                    "→ 拦截层是通的，只是这几分钟访问的域名不在名单里。\n" +
                    "正常用手机刷几个 App，过几分钟再来看，命中数应该会涨。"
            else ->
                "工作正常 ✓\n\n收到 " + q + " 条查询，拦截了 " + b + " 条广告域名。"
        }
        AlertDialog.Builder(this)
            .setTitle("DNS 拦截统计")
            .setMessage("tun 收到 DNS 查询：" + q + " 条\n命中黑名单：" + b + " 条\n\n" + verdict)
            .setPositiveButton("关闭", null)
            .setNegativeButton("清零重计") { _, _ ->
                DnsStats.reset()
                toast("已清零，用几分钟再回来看")
            }
            .show()
    }

    private fun copyToClipboard(s: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(ClipData.newPlainText("adskip-log", s))
            toast("已复制到剪贴板")
        } catch (t: Throwable) {
            toast("复制失败")
        }
    }
}

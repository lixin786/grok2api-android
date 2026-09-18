package com.grok2api.gateway

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 产号页（独立 Activity）：上半屏控制面板，下半屏注册 WebView。
 *
 * 布局原则（v1.6.1，按真机实测反馈重做）：
 * - WebView 是主工作区：占屏约 55%，面板可整体收起（一键全屏 WebView / 恢复）；
 * - 运行日志可折叠，默认只显示摘要行（批次进度 + 当前步骤），展开才占空间——
 *   v1.6.0 的教训：日志无限增长把 WebView 挤到看不见，人工兜底 Turnstile 时找不到窗口；
 * - 批次运行中保持亮屏 + 面板常驻关键状态（池容量、间隔倒计时、当前邮箱）。
 */
class FarmActivity : Activity(), FarmEngine.Listener {

    private lateinit var engine: FarmEngine
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView     // 折叠态摘要：进度 + 当前步骤
    private lateinit var logView: TextView         // 展开态完整日志（限 40 行）
    private lateinit var logScroll: android.widget.ScrollView   // 日志滚动容器（自动滚底）
    private lateinit var copyLogButton: Button     // 复制日志
    private lateinit var ledgerView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var backfillButton: Button    // 补登：把「注册成功但没入池」的号捞回来
    private lateinit var diagButton: Button        // 诊断：导出本地错误总结
    private var pendingDiagExport: String? = null
    private lateinit var countEdit: EditText
    private lateinit var gapEdit: EditText
    private lateinit var panelRoot: LinearLayout   // 控制面板整体（全屏切换时收起）
    private lateinit var webContainer: LinearLayout
    private lateinit var togglePanelButton: Button
    private lateinit var toggleLogButton: Button
    private lateinit var poolText: TextView        // 号池容量行
    private lateinit var gapCountdownText: TextView // 间隔倒计时
    private lateinit var providerText: TextView     // 当前邮源
    private lateinit var bubbleButton: Button       // 悬浮球开关
    private lateinit var rootContainer: LinearLayout // 根容器（透明层 detach 回挂目标）
    private var overlayOn = false
    private lateinit var web: WebView
    private val logLines = ArrayDeque<String>()
    private var logExpanded = false
    @Volatile private var gapDeadline = 0L         // 号间隔截止时刻（ms），0 = 无等待

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = FarmEngine(this)
        engine.listener = this
        setContentView(buildUi())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 错误报告落在哪：出问题时按这条路径就能把文件取出来发过来（也可点「诊断」导出到 Download）
        engine.note("[*] 错误报告目录：${DiagReporter.locationHint(this)}")
        // 定时补号自检：每小时 AlarmManager 检查池可用率，低于阈值发通知（farm_auto_batch=1 时提示自动开批）
        FarmAutoTopUp.scheduleNext(this)
        // 倒计时刷新（1s 一次，仅 UI）
        android.os.Handler(mainLooper).postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                updateGapCountdown()
                android.os.Handler(mainLooper).postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onDestroy() {
        engine.stop()
        if (overlayOn) FarmOverlay.detachRender(this, webContainer)
        FarmOverlay.hideBubble(this)
        if (this::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F172A.toInt())
        }
        rootContainer = root

        // ---- 控制面板（可整体收起）----
        panelRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        root.addView(panelRoot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.45f))

        // 标题行 + 全屏切换
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(title("⚡ 产号", 17), LinearLayout.LayoutParams(0, dp(32), 1f))
        bubbleButton = button("悬浮球") { toggleBubble() }
        bubbleButton.textSize = 11f
        bar.addView(bubbleButton)
        togglePanelButton = button("后台续跑") { toggleOverlay() }
        togglePanelButton.textSize = 11f
        bar.addView(togglePanelButton)
        panelRoot.addView(bar)

        // 状态行：当前步骤（常驻，不折叠）
        statusText = TextView(this).apply {
            setTextColor(0xFFE2E8F0.toInt()); textSize = 13f; setPadding(0, dp(4), 0, 0)
            text = "就绪"
        }
        panelRoot.addView(statusText)

        // 号池容量 + 间隔倒计时（一行两块）
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        poolText = statChip(); gapCountdownText = statChip()
        statsRow.addView(poolText, LinearLayout.LayoutParams(0, dp(26), 1f))
        statsRow.addView(gapCountdownText, LinearLayout.LayoutParams(0, dp(26), 1f))
        panelRoot.addView(statsRow)

        // 摘要行（日志折叠态显示）
        summaryText = TextView(this).apply {
            setTextColor(0xFF94A3B8.toInt()); textSize = 12f; setPadding(0, dp(2), 0, 0)
        }
        panelRoot.addView(summaryText)

        // 邮源选择行（当前源 + 切换；各源密钥进设置页）
        providerText = TextView(this).apply {
            setTextColor(0xFF94A3B8.toInt()); textSize = 11f; setPadding(0, dp(6), 0, 0)
            text = "邮源：${NativeStore.get(this@FarmActivity).getSettings().optString("farm_provider", "mailtm")} ⚙ 点击配置"
            setOnClickListener { startActivity(Intent(this@FarmActivity, FarmSettingsActivity::class.java)) }
        }
        panelRoot.addView(providerText)

        // 参数行
        val params = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val countHolder = field("数量", "1")
        val gapHolder = field("号间隔(秒)", "300")
        params.addView(countHolder.field); params.addView(gapHolder.field)
        panelRoot.addView(params)
        countEdit = countHolder.edit; gapEdit = gapHolder.edit

        // 按钮
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        startButton = button("开始批次") { onStartBatch() }
        stopButton = button("停止") { engine.stop() }
        backfillButton = button("补登") { onBackfill() }
        stopButton.isEnabled = false
        backfillButton.isEnabled = false   // 有待补登的号时 refreshLedger() 才点亮
        buttons.addView(startButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(42), 0.7f).apply { rightMargin = dp(8) })
        buttons.addView(backfillButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        panelRoot.addView(buttons)

        // 日志折叠切换 + 复制按钮（并排）
        val logBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        toggleLogButton = button("展开日志") { toggleLog() }
        toggleLogButton.textSize = 12f
        copyLogButton = button("复制日志") { copyLog() }
        copyLogButton.textSize = 12f
        diagButton = button("诊断") { onExportDiag() }
        diagButton.textSize = 12f
        logBtns.addView(toggleLogButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(6) })
        logBtns.addView(copyLogButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(6) })
        logBtns.addView(diagButton, LinearLayout.LayoutParams(0, dp(30), 1f))
        panelRoot.addView(logBtns)
        // 日志放入限高滚动容器（自动滚底）
        logScroll = android.widget.ScrollView(this).apply {
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0xFF0F172A.toInt()); cornerRadius = dp(8).toFloat()
            }
        }
        logView = TextView(this).apply {
            setTextColor(0xFFCBD5E1.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        logScroll.addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panelRoot.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(2) })

        // 台账（折叠态常驻，2 行高度）
        ledgerView = TextView(this).apply {
            setTextColor(0xFF64748B.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0); maxLines = 3
        }
        panelRoot.addView(ledgerView)
        refreshLedger()

        // ---- 注册 WebView（主工作区）----
        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF334155.toInt())
        }
        val webLabel = TextView(this).apply {
            text = "  注册窗口 · Turnstile 需手点时在这里操作"; setTextColor(0xFFCBD5E1.toInt()); textSize = 11f
            setBackgroundColor(0xFF1E293B.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        webContainer.addView(webLabel)
        web = WebView(this).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = FarmWebClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        webContainer.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(webContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.55f))
        return root
    }

    /** 悬浮球开关：常驻小窗显示批次进度，Turnstile 需人工时红闪。 */
    private fun toggleBubble() {
        if (FarmOverlay.isBubbleShowing()) {
            FarmOverlay.hideBubble(this)
            bubbleButton.text = "悬浮球"
        } else {
            if (!FarmOverlay.hasPermission(this)) {
                runCatching { startActivity(FarmOverlay.permissionIntent(this)) }
                toast("请授予悬浮窗权限（切后台续跑必需）")
                return
            }
            val ok = FarmOverlay.showBubble(this,
                onClick = { runOnUiThread { runCatching { startActivity(Intent(this, FarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } } },
                onLongClick = { runOnUiThread { FarmOverlay.hideBubble(this); runOnUiThread { bubbleButton.text = "悬浮球" } } })
            if (ok) bubbleButton.text = "收悬浮球"
            else toast("悬浮球创建失败")
        }
    }

    /** 后台续跑：把注册 WebView 挂到系统透明层（Chromium 可见不节流 JS），配悬浮球用。 */
    private fun toggleOverlay() {
        if (overlayOn) {
            if (FarmOverlay.detachRender(this, webContainer)) {
                overlayOn = false
                togglePanelButton.text = "后台续跑"
                toast("已回到窗口内运行")
            }
            return
        }
        if (!FarmOverlay.hasPermission(this)) {
            runCatching { startActivity(FarmOverlay.permissionIntent(this)) }
            toast("需要悬浮窗权限：授权后批次可切后台续跑")
            return
        }
        if (FarmOverlay.attachRender(this, webContainer, rootContainer)) {
            overlayOn = true
            togglePanelButton.text = "回到窗口内"
            toast("WebView 已挂透明层——可切去别的应用，批次继续跑")
        } else {
            toast("挂载失败")
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 全屏切换：收起面板 = WebView 占满。 */
    private fun togglePanel() {
        if (panelRoot.visibility == View.VISIBLE) {
            panelRoot.visibility = View.GONE
            togglePanelButton.text = "显示面板"
        } else {
            panelRoot.visibility = View.VISIBLE
            togglePanelButton.text = "全屏注册窗口"
        }
    }

    private fun toggleLog() {
        logExpanded = !logExpanded
        logScroll.visibility = if (logExpanded) View.VISIBLE else View.GONE
        if (logExpanded) logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        toggleLogButton.text = if (logExpanded) "收起日志" else "展开日志"
    }

    private fun copyLog() {
        val text = logLines.joinToString("\n")
        if (text.isEmpty()) { android.widget.Toast.makeText(this, "暂无日志", android.widget.Toast.LENGTH_SHORT).show(); return }
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("产号日志", text))
        android.widget.Toast.makeText(this, "已复制 ${logLines.size} 行日志", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** xAI 域内跳转留在窗内（含 cloudflare.com 的挑战页），其余甩系统浏览器。 */
    private inner class FarmWebClient : android.webkit.WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull().orEmpty().lowercase()
            // 注册流程一切导航都留在 WebView 内，绝不弹系统浏览器。
            // 非弹窗型跳转直接拦下（返回 true 不加载），避免 Cloudflare/x.ai 跳外链打断流程。
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data") {
                return false   // WebView 自己加载
            }
            return true        // mailto/tel/intent 等协议一律吞掉，不外甩
        }
    }

    private fun title(text: String, size: Int) = TextView(this).apply {
        this.text = text; setTextColor(0xFFF1F5F9.toInt()); textSize = size.toFloat(); setTypeface(typeface, Typeface.BOLD)
    }

    private fun statChip() = TextView(this).apply {
        setTextColor(0xFF94A3B8.toInt()); textSize = 12f
    }

    private fun field(label: String, initial: String): FieldHolder {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        val lbl = TextView(this).apply { text = label; setTextColor(0xFF64748B.toInt()); textSize = 11f }
        box.addView(lbl)
        val edit = EditText(this).apply {
            setText(initial); setTextColor(0xFFF1F5F9.toInt())
            textSize = 13f; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setBackgroundColor(0xFF1E293B.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        box.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        return FieldHolder(box, edit)
    }

    private class FieldHolder(val field: View, val edit: EditText)

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- 交互

    private fun onStartBatch() {
        if (engine.running) return
        val count = countEdit.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 1
        val gap = gapEdit.text.toString().toLongOrNull()?.coerceAtLeast(60L) ?: 300L
        appendLog("[*] 批次开始：$count 个号，间隔 ${gap}s")
        refreshLedger()
        startButton.isEnabled = false; stopButton.isEnabled = true
        engine.startBatch(count, gap) { web }
    }

    /**
     * 补登：把 mint 失败（注册成功但没换到 token）的号重新走一遍设备流授权送进池子。
     *
     * 复用注册 WebView：先在窗内种回留档的 sso，sso 过期则用留档的邮箱密码登录，再授权。
     * 与批次互斥（共用 engine.running），所以运行中直接挡掉。
     */
    private fun onBackfill() = startBackfill(null)

    /**
     * @param ids 非 null = 只补这些 rowId（批次收尾自动补，仅本批 mint 失败的号）；
     *            null = 补全量待补登（面板按钮手动触发，含历史遗留）
     */
    private fun startBackfill(ids: List<Long>?) {
        if (engine.running) {
            if (ids == null) toast("任务正在运行，请先停止")
            return
        }
        val store = NativeStore.get(this)
        val n = if (ids == null) store.farmPendingCount() else ids.size
        if (n == 0) {
            if (ids == null) toast("没有待补登的号")
            return
        }
        // 间隔沿用「号间隔」设置：连续授权同样会被上游风控盯上，别图快
        val gap = gapEdit.text.toString().toLongOrNull()?.coerceAtLeast(30L) ?: 60L
        appendLog(if (ids == null) "[*] 补登开始：$n 个号待补（含历史失败），间隔 ${gap}s"
                  else "[*] 批次收尾自动补登：$n 个未入池的号，间隔 ${gap}s")
        refreshLedger()
        startButton.isEnabled = false; backfillButton.isEnabled = false; stopButton.isEnabled = true
        engine.backfillPending({ web }, gap, ids)
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logLines.addLast(line)
            while (logLines.size > 40) logLines.removeFirst()
            renderLog()
            summaryText.text = logLines.lastOrNull()?.substringAfter(']')?.trim().orEmpty()
        }
    }

    /** 终端风分级着色（taixu TermLog 配色思路）：[*]青 [+]绿 [x]红 [!]黄 [d]灰。 */
    private fun renderLog() {
        val span = android.text.SpannableStringBuilder()
        for (line in logLines) {
            val color = when {
                line.startsWith("[+]") -> 0xFF4ADE80.toInt()
                line.startsWith("[x]") -> 0xFFF87171.toInt()
                line.startsWith("[!]") -> 0xFFFBBF24.toInt()
                line.startsWith("[d") -> 0xFF64748B.toInt()
                line.startsWith("[*]") -> 0xFF38BDF8.toInt()
                else -> 0xFFCBD5E1.toInt()
            }
            val start = span.length
            span.append(line).append("\n")
            span.setSpan(android.text.style.ForegroundColorSpan(color), start, span.length - 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = span
        // 自动滚到底部（用户正在查看时也滚——产号日志是"跟随最新"语义，taixu 同款）
        if (logExpanded) logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /**
     * 诊断：把本机的错误总结导出到用户选定的位置（一般选 Download），方便发出来排查。
     *
     * 报告**只在本机生成、不联网**；内容已过 [Redact] 脱敏（邮箱本地部分、验证码、
     * cookie/token/JWT 都已隐去），可以直接发给别人看。
     */
    private fun onExportDiag() {
        val reports = DiagReporter.list(this)
        if (reports.isEmpty()) {
            toast("暂无错误报告（还没出现过失败）。报告目录：${DiagReporter.locationHint(this)}")
            return
        }
        val text = DiagReporter.exportText(this)
        if (text.isBlank()) { toast("报告读取失败，请重试"); return }
        pendingDiagExport = text
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, DiagReporter.exportFileName())
            },
            REQ_DIAG_EXPORT
        )
    }

    @Deprecated("Legacy callback retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DIAG_EXPORT) return
        val payload = pendingDiagExport
        pendingDiagExport = null
        if (resultCode != RESULT_OK || payload.isNullOrBlank()) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                ?: error("无法打开目标文件")
        }.fold(
            onSuccess = { toast("诊断报告已导出（已脱敏，可直接发出）") },
            onFailure = { toast("导出失败：${it.message ?: "写入被拒绝"}") }
        )
    }

    private fun refreshLedger() {
        val store = NativeStore.get(this)
        val ledger = store.listFarmLedger(20)
        val stats = store.farmLedgerStats()
        val pooled = stats.optInt("pooled", 0)
        val stopped = stats.optInt("stopped", 0)
        // 待补登的号既不是成功也不是失败——单独算，否则「失败」数会虚高，让人误判批次质量
        val pending = store.farmPendingCount()
        val failed = stats.length() - pooled - pending - stopped
        ledgerView.text = when {
            ledger.length() == 0 -> "台账：暂无记录"
            else -> {
                val last = ledger.optJSONObject(0)
                val pendingPart = if (pending > 0) " · 待补登 $pending" else ""
                "台账：累计入池 $pooled$pendingPart · 失败 $failed · 最近 ${last?.optString("status").orEmpty()} ${last?.optString("email").orEmpty()}"
            }
        }
        backfillButton.text = if (pending > 0) "补登 ($pending)" else "补登"
        backfillButton.isEnabled = pending > 0 && !engine.running
        val reports = DiagReporter.list(this).size
        diagButton.text = if (reports > 0) "诊断 ($reports)" else "诊断"
    }

    private fun updatePoolChip() {
        val status = runCatching { NativeCore.farmStatus(this) }.getOrNull()
        val pool = status?.optInt("pool_count") ?: -1
        val max = status?.optInt("max_accounts") ?: -1
        val store = NativeStore.get(this)
        val stats = store.farmLedgerStats()
        var ok = 0; var bad = 0
        for (i in 0 until stats.length()) {
            val k = stats.names()?.optString(i).orEmpty()
            // 待补登不算失败：号还在（凭据已留档），补一次就能救回来，混进 ❌ 会让人误判批次质量
            if (k == "pooled") ok = stats.optInt(k)
            else if (k != "stopped" && k !in NativeStore.FARM_PENDING_STATUSES) bad += stats.optInt(k)
        }
        val pending = store.farmPendingCount()
        val pendingPart = if (pending > 0) " ⏳$pending" else ""
        poolText.text = if (pool >= 0) "号池 $pool/$max  ✅$ok$pendingPart  ❌$bad" else "号池 —"
    }

    private fun updateGapCountdown() {
        gapCountdownText.text = if (gapDeadline > System.currentTimeMillis()) {
            val left = (gapDeadline - System.currentTimeMillis()) / 1000
            "下号倒计时 ${left}s"
        } else ""
    }

    private fun fmtTs(sec: Double): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date((sec * 1000).toLong()))

    // ------------------------------------------------------------- FarmEngine.Listener

    private fun stageMark(stage: String): String = when {
        stage.contains("造邮箱") -> "✉"
        stage.contains("打开注册页") -> "🌐"
        stage.contains("提交邮箱") -> "✉➜"
        stage.contains("等验证码") -> "⏳"
        stage.contains("资料") -> "📝"
        stage.contains("Turnstile") -> "🛡"
        stage.contains("mint") || stage.contains("授权") -> "🔑"
        stage.contains("入池") -> "✓"
        else -> "▸"
    }

    override fun onStage(lane: Int, email: String, stage: String) {
        runOnUiThread {
            statusText.text = "${stageMark(stage)} $stage ${if (email.isNotBlank()) "· ${email.substringBefore('@')}" else ""}"
            updatePoolChip()
        }
    }

    override fun onLog(line: String) = appendLog(line)

    /** 号间隔等待：把截止时刻交给 UI 做倒计时。 */
    fun onGapWait(seconds: Long) { gapDeadline = System.currentTimeMillis() + seconds * 1000 }

    override fun onAccountPooled(email: String, nickname: String) {
        runOnUiThread {
            Toast.makeText(this, "已入池：$nickname", Toast.LENGTH_SHORT).show()
            refreshLedger()
        }
    }

    override fun onBatchFinished(summary: String) {
        runOnUiThread {
            appendLog("[*] $summary")
            startButton.isEnabled = true; stopButton.isEnabled = false
            statusText.text = summary
            gapDeadline = 0
            refreshLedger(); updatePoolChip()
            // 批次收尾自动补一场：mint 失败的号凭据已经留档，趁 WebView 还在、登录态还有效
            // 尽早捞回来最省事。只补本批失败的号（takeBatchFailedLedgerIds 取一次即清），
            // 历史遗留走「补登」按钮；补登结束走 onBackfillFinished，不会再触发放大循环。
            val failedIds = engine.takeBatchFailedLedgerIds()
            if (failedIds.isNotEmpty()) startBackfill(failedIds)
        }
    }

    override fun onBackfillFinished(summary: String) {
        runOnUiThread {
            appendLog("[*] $summary")
            statusText.text = summary
            startButton.isEnabled = true; stopButton.isEnabled = false
            refreshLedger(); updatePoolChip()
        }
    }

    override fun onNeedHumanAction(reason: String) {
        runOnUiThread {
            appendLog("[!] $reason")
            statusText.text = "⚠ 需要人工：请在下方注册窗口手点人机验证"
            toggleLog() // 收起日志把空间让给 WebView
            if (logExpanded) toggleLog()
            runCatching {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26 &&
                    nm.getNotificationChannel(FARM_CHANNEL) == null) {
                    nm.createNotificationChannel(android.app.NotificationChannel(
                        FARM_CHANNEL, "产号提醒", android.app.NotificationManager.IMPORTANCE_HIGH))
                }
                nm.notify(2001, android.app.Notification.Builder(this, FARM_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("产号需要人工")
                    .setContentText(reason)
                    .setAutoCancel(true)
                    .build())
            }
        }
    }

    override fun onHumanActionDone() {
        runOnUiThread { statusText.text = "Turnstile 已通过，继续流程" }
    }

    companion object {
        private const val FARM_CHANNEL = "grok_farm_alerts"
        private const val REQ_DIAG_EXPORT = 0x2001
    }
}

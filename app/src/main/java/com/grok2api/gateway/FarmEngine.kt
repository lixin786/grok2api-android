package com.grok2api.gateway

import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 产号引擎：单泳道状态机（造邮箱 → WebView 注册 → mint → 入池）。
 *
 * 注册流程照抄 com.taixu.grokreg 的 RegistrationFlow 状态机（state 字符串驱动）：
 * openSignupPage → waitPageReady → clickEmailSignupButton → fillEmailAndSubmit
 * → fillCodeAndSubmit → fillProfileAndSubmit（含 Turnstile 人工兜底）→ waitForSsoCookie。
 * JS 一律走 RegistrationDriver.runJs（JSONTokener 解包、永不 null、IIFE+try/catch）。
 *
 * 纪律（与 PC 端 farm.py 对齐）：
 * - canary 停批：首号失败即停整批；
 * - 号间隔 ≥300s；
 * - mint 复用现有设备流（beginOAuth + OAuthWebActivity），注册后 WebView 已登录态自动通过；
 * - 入池走 saveAccount（uid 稳定，复用好池轮询/冷却/健康检查全套逻辑）。
 */
class FarmEngine(private val activity: Activity) {

    interface Listener {
        fun onStage(lane: Int, email: String, stage: String)
        fun onLog(line: String)
        fun onAccountPooled(email: String, nickname: String)
        fun onBatchFinished(summary: String)
        fun onBackfillFinished(summary: String)  // 与 onBatchFinished 分开：补登不该动「开始批次」按钮状态
        fun onNeedHumanAction(reason: String)   // Turnstile 卡住等人工
        fun onHumanActionDone()
    }

    var listener: Listener? = null

    private fun fileLogCaller(line: String) {
        fileLog(line)
        listener?.onLog(line)
    }

    /** 文件日志：所有 onLog 同步追加到 files/farm.log，供 run-as 离线排查（UI 日志会被滚动吞掉）。 */
    private val logFile = java.io.File(activity.filesDir, "farm.log")
    private fun fileLog(line: String) {
        runCatching {
            synchronized(logFile) {
                logFile.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line\n")
                if (logFile.length() > 200_000) logFile.writeText(logFile.readText().takeLast(100_000))
            }
        }
    }

    @Volatile var running = false
        private set
    @Volatile private var stopRequested = false
    private val rng = SecureRandom()
    private var lastFailure = ""
    private var lastError = ""

    /**
     * 本批 mint 失败留下的台账 rowId（批次收尾自动补登用）。
     *
     * 只记本批的：全量自动补登会让一个反复失败的号在每批末尾白耗一次授权超时，
     * 历史遗留交给面板的「补登」按钮手动补。
     */
    private val batchFailedLedgerIds = java.util.Collections.synchronizedList(mutableListOf<Long>())

    /** 取走并清空本批失败的 rowId（UI 在批次收尾时调用，取一次即清）。 */
    fun takeBatchFailedLedgerIds(): List<Long> = synchronized(batchFailedLedgerIds) {
        val out = batchFailedLedgerIds.toList()
        batchFailedLedgerIds.clear()
        out
    }

    /** 最近一次上报的阶段（=卡在哪一步）。失败汇总报告要用它定位问题。 */
    @Volatile private var lastStage = ""

    /**
     * 本批失败汇总，**按失败类型去重**。
     *
     * 5 个号里 3 个卡在同一处（比如都收不到码），有用的信息是"卡在哪一步、什么错"，
     * 不是同一行重复三遍——所以用 LinkedHashMap 以 kind 为键去重，首条胜出。
     */
    private val batchFailures = LinkedHashMap<String, DiagReporter.Failure>()

    /** 上报阶段并记住（所有 stage 都走这里，保证 lastStage 与 UI 显示一致）。 */
    private fun reportStage(lane: Int, email: String, stage: String) {
        lastStage = stage
        listener?.onStage(lane, email, stage)
    }

    /** 把当前失败记进汇总（`stopped` 是用户主动停，不算 bug，不记）。 */
    private fun recordFailure(kind: String = lastFailure, stage: String = lastStage, detail: String = lastError) {
        if (kind.isBlank() || kind == "stopped") return
        batchFailures.putIfAbsent(kind, DiagReporter.Failure(stage.ifBlank { "未知阶段" }, kind, detail))
    }

    /** 供 UI 显示最近一次失败原因。 */
    fun lastFailureReason(): Pair<String, String> = lastFailure to lastError

    // ------------------------------------------------------------- 批次控制

    /**
     * 跑一批。串行单泳道（MVP），count 个号，号间隔 gapSec。
     * canary：第 1 个号失败（非 import 类失败）即停批返回。
     */
    fun startBatch(count: Int, gapSec: Long, webProvider: () -> WebView) {
        if (running) return
        running = true
        stopRequested = false
        batchFailedLedgerIds.clear()
        batchFailures.clear()
        Thread {
            var pooled = 0; var failed = 0
            try {
                for (i in 1..count) {
                    if (stopRequested) { fileLogCaller("[!] 已手动停止"); break }
                    fileLogCaller("[*] 第 $i/$count 个号开始")
                    val ok = runOne(i, webProvider)
                    if (ok) pooled++ else { failed++; recordFailure() }
                    if (i == 1 && !ok && lastFailure !in setOf("import_failed", "flagged_skip")) {
                        fileLogCaller("[x] canary：首号注册失败，停批。原因：$lastFailure")
                        break
                    }
                    if (i < count && !stopRequested) {
                        fileLogCaller("[*] 等待 ${gapSec}s（号间隔）…")
                        (listener as? FarmActivity)?.onGapWait(gapSec)
                        waitWithStop(gapSec * 1000)
                    }
                }
            } finally {
                running = false
                // 批量注册→批量入池闭环：批次结束自动全池风控体检（与 /admin/risk/scan 同一份逻辑）
                var scanNote = ""
                if (pooled > 0) {
                    runCatching {
                        val scan = NativeCore.riskScan(activity, disableFlagged = null)
                        scanNote = "；风控扫描 ${scan.optInt("total", 0)} 个，标记 ${scan.optInt("flagged", 0)} 个"
                    }
                }
                // 错误总结：本批有失败就落一份本地诊断报告（只存本地、不联网），
                // 卡在哪一步 + 什么错 + 已脱敏的日志尾部，用户能直接把文件发过来
                var diagNote = ""
                if (batchFailures.isNotEmpty()) {
                    val rep = DiagReporter.reportFlowFailures(
                        activity,
                        "产号批次（共 $count 个号，入池 $pooled，失败 $failed）",
                        batchFailures.values.toList()
                    )
                    if (rep != null) {
                        diagNote = "；错误总结已存 ${rep.name}"
                        fileLogCaller("[!] 错误总结已保存：${rep.absolutePath}")
                    }
                }
                listener?.onBatchFinished("批次结束：入池 $pooled，失败 $failed$scanNote$diagNote")
            }
        }.apply { name = "farm-batch" }.start()
    }

    fun stop() { stopRequested = true }

    /**
     * 让外部（UI）往日志里写一条：走同一条出口，因此**既进 UI 也进 `files/farm.log`**。
     * UI 自己的 `appendLog` 只进 UI，落盘排查时看不到。
     */
    fun note(line: String) = fileLogCaller(line)

    private fun waitWithStop(ms: Long) {
        var left = ms
        while (left > 0 && !stopRequested) {
            val step = minOf(1000L, left)
            Thread.sleep(step); left -= step
        }
    }

    private fun sleep(ms: Long) {
        val steps = (ms / 200).coerceAtLeast(1)
        for (i in 0 until steps) {
            if (stopRequested) return
            Thread.sleep(200)
        }
    }

    // ------------------------------------------------------------- 单号流程

    /** @return true = 已入池 */
    private fun runOne(lane: Int, webProvider: () -> WebView): Boolean {
        var email = ""
        try {
            reportStage(lane, "", "造邮箱")
            val mailbox = FarmMail.createMailbox(activity)
            email = mailbox.address
            fileLogCaller("[+] 邮箱：$email")
            // 密码在 runOne 生成并往下传：注册成功那一刻要连密码一起留档，
            // 否则 mint 失败后连"用邮箱密码重新登录"这条路都断了。
            val password = randomPassword()

            reportStage(lane, email, "打开注册页")
            val web = webProvider()
            val sso = registerInWebView(web, mailbox, password)
            if (sso.isNullOrBlank()) {
                lastFailure = lastFailure.ifBlank { "register_failed" }
                FarmMail.appendLedger(activity, email, lane, lastFailure, lastError)
                fileLogCaller("[x] 注册失败：$lastError")
                return false
            }
            // 凭据立刻落盘再 mint：mint 要几十秒到两分钟，期间进程被杀 / 断网 / 轮询超时
            // 都会让号卡在「已注册但未入池」——凭据在手，补登才能把它捞回来。
            val ledgerId = FarmMail.appendRegisteredLedger(activity, email, lane, sso, password)
            fileLogCaller("[+] SSO 已取得（${sso.take(24)}…），凭据已留档")

            reportStage(lane, email, "设备流 mint")
            val minted = mintWithExistingSession(web, email)
            if (minted == null) {
                lastFailure = "mint_failed"
                NativeCore.store(activity).updateFarmLedger(ledgerId, "mint_failed", error = lastError)
                batchFailedLedgerIds.add(ledgerId)   // 批次收尾自动补登用
                fileLogCaller("[x] mint 失败（凭据已留档，可补登）：$lastError")
                return false
            }
            NativeCore.store(activity).updateFarmLedger(ledgerId, "pooled", uid = minted.optString("uid"))
            fileLogCaller("[+] 已入池：$email")
            lastFailure = ""
            return true
        } catch (e: Exception) {
            lastFailure = "exception"
            lastError = e.message ?: e.javaClass.simpleName
            if (email.isNotBlank()) FarmMail.appendLedger(activity, email, lane, "exception", lastError)
            fileLogCaller("[x] 异常：${e.message}")
            return false
        }
    }

    // ------------------------------------------------------------- WebView 注册（照抄 taixu RegistrationFlow）

    private fun registerInWebView(web: WebView, mailbox: FarmMail.Mailbox, password: String): String? {
        lastError = ""
        web.post {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            web.loadUrl(RegistrationDriver.SIGNUP_URL)
        }
        waitPageReady(web, 30000L)
        if (!clickEmailSignupButton(web, 20000L)) return null
        if (!fillEmailAndSubmit(web, mailbox.address, 60000L)) {
            if (lastError.isBlank()) lastError = "填邮箱并提交失败"
            return null
        }
        reportStage(0, mailbox.address, "等验证码")
        val code = try { FarmMail.waitCode(activity, mailbox, timeoutSec = 120) }
        catch (e: Exception) { lastError = "收码失败：${e.message}"; lastFailure = "code_failed"; return null }
        fileLogCaller("[+] 验证码：$code")
        if (!fillCodeAndSubmit(web, code, 90000L)) {
            if (lastError.isBlank()) lastError = "填验证码失败"
            return null
        }
        if (!fillProfileAndSubmit(web, password, 300000L)) {
            if (lastError.isBlank()) lastError = "资料/Turnstile 未通过"
            return null
        }
        val sso = waitForSsoCookie(web, 60000L)
        if (sso.isBlank()) { lastError = "注册后未取得 SSO cookie"; return null }
        return sso
    }

    private fun waitPageReady(web: WebView, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return
            val st = runCatching {
                RegistrationDriver.runJs(web, "(function(){return JSON.stringify({state:document.readyState,len:document.body?document.body.innerHTML.length:0});})()", 8000L)
            }.getOrNull()
            if (st != null && st.optString("state") == "complete" && st.optInt("len") > 200) return
            sleep(600)
        }
        fileLogCaller("[d] 页面加载等待超时，继续尝试")
    }

    private fun clickEmailSignupButton(web: WebView, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return false
            if (RegistrationDriver.runJs(web, RegistrationDriver.PAGE_STATE).optBoolean("hasEmail")) {
                fileLogCaller("[*] 邮箱输入框已就绪")
                return true
            }
            val r = RegistrationDriver.runJs(web, RegistrationDriver.CLICK_EMAIL_SIGNUP)
            if (r.optString("state") == "clicked") {
                fileLogCaller("[*] 已点击「使用邮箱注册」: ${r.optString("text")}")
                sleep(2000)
                return true
            }
            sleep(1000)
        }
        val page = RegistrationDriver.runJs(web, RegistrationDriver.PAGE_STATE)
        lastError = "未找到「使用邮箱注册」按钮，当前页: ${page.optString("url")} / ${page.optString("title")}"
        return false
    }

    private fun fillEmailAndSubmit(web: WebView, email: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastReclick = 0L
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return false
            val filled = RegistrationDriver.runJs(web, RegistrationDriver.fillEmail(email))
            when (val st = filled.optString("state")) {
                "filled" -> {
                    sleep(900)
                    val submitted = RegistrationDriver.runJs(web, RegistrationDriver.SUBMIT_EMAIL)
                    val s2 = submitted.optString("state")
                    if (s2 == "clicked" || s2 == "form-submit" || s2 == "enter") {
                        fileLogCaller("[*] 已填写邮箱并提交: $email ($s2)")
                        sleep(1500)
                        return true
                    }
                    fileLogCaller("[d] 邮箱提交未成功: $s2")
                    sleep(700)
                }
                "not-ready" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastReclick >= 3000) {
                        lastReclick = now
                        if (RegistrationDriver.state(web, RegistrationDriver.CLICK_EMAIL_SIGNUP) == "clicked") {
                            fileLogCaller("[d] 邮箱输入框未出现，已再次触发入口")
                        }
                    }
                    fileLogCaller("[d] 等待邮箱输入框")
                    sleep(600)
                }
                else -> {
                    fileLogCaller("[d] 邮箱写入失败: $st")
                    sleep(700)
                }
            }
        }
        lastError = "未找到邮箱输入框或注册按钮"
        return false
    }

    private fun fillCodeAndSubmit(web: WebView, code: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastProbe = ""
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return false
            val probe = RegistrationDriver.runJs(web, RegistrationDriver.probeOtp(code))
            val pt = probe.optString("state")
            if (pt == "aggregate" || pt == "boxes") {
                val filled = RegistrationDriver.runJs(web, RegistrationDriver.fillOtp(code))
                val fs = filled.optString("state")
                // 只有真正填进去（filled-aggregate / filled-boxes）才提交，否则继续重试填值
                if (fs == "filled-aggregate" || fs == "filled-boxes") {
                    RegistrationDriver.runJs(web, RegistrationDriver.SUBMIT_OTP)
                    sleep(3000)
                    // 点击后页面可能立即跳转，submit 返回值不可靠——以资料页出现作成功判据
                    if (RegistrationDriver.runJs(web, RegistrationDriver.PAGE_STATE).optBoolean("hasProfile")) {
                        fileLogCaller("[*] 验证码已提交，进入资料页")
                        return true
                    }
                } else {
                    if (lastProbe != fs) { lastProbe = fs; fileLogCaller("[d] OTP($pt) 填值: $fs") }
                }
            } else {
                if (lastProbe != pt) { lastProbe = pt; fileLogCaller("[d] 等待验证码输入框 ($pt, boxes=${probe.optInt("boxes")})") }
            }
            sleep(800)
        }
        lastError = "验证码填值失败（undistilled=1 或 OTP 形态未匹配）"
        return false
    }

    private fun fillProfileAndSubmit(web: WebView, pwd: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val first = randomName(); val last = randomName()
        var profileFilled = false
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return false
            if (!profileFilled) {
                val r = RegistrationDriver.runJs(web, RegistrationDriver.fillProfile(first, last, pwd))
                when (val st = r.optString("state")) {
                    "ready-to-submit" -> profileFilled = true
                    "filled-no-submit" -> profileFilled = true
                    "wait-cloudflare" -> handleTurnstile(web)
                    else -> sleep(800)
                }
            }
            if (profileFilled) {
                reportStage(0, "", "过 Turnstile / 提交")
                val sub = RegistrationDriver.runJs(web, RegistrationDriver.SUBMIT_FINAL)
                when (val st = sub.optString("state")) {
                    "final-page-clicked-submit" -> { fileLogCaller("[*] 已提交注册"); sleep(3000); return true }
                    "final-page-wait-cf" -> handleTurnstile(web)
                    "not-final-page" -> sleep(800)
                    "final-page-no-submit" -> { lastError = "找不到「完成注册」按钮"; return false }
                    else -> sleep(800)
                }
            }
        }
        if (lastError.isBlank()) lastError = "资料/Turnstile 超时"
        return false
    }

    /**
     * Turnstile 自动点击：SCROLL_TO_TURNSTILE 返回控件页面坐标，
     * 换算屏幕坐标后注入真实 MotionEvent（DOM click 过不了 Cloudflare，
     * 真机实测真实触摸坐标点击能过——与 PC 端 register.py 同一原理）。
     * 自动点击也失败才升级人工提示。
     */
    private fun handleTurnstile(web: WebView) {
        val scrolled = runCatching { RegistrationDriver.runJs(web, RegistrationDriver.SCROLL_TO_TURNSTILE) }.getOrNull()
        val (px, py) = if (scrolled?.optString("state") == "scrolled") {
            val wx = scrolled.optInt("x"); val wy = scrolled.optInt("y")
            val ww = scrolled.optInt("w").coerceAtLeast(1); val wh = scrolled.optInt("h").coerceAtLeast(1)
            val density = activity.resources.displayMetrics.density
            val loc = IntArray(2); runCatching { web.getLocationOnScreen(loc) }
            val sx = (loc[0] + (wx + 27 + ww * 0.05) * density).toInt()
            val sy = (loc[1] + (wy + wh / 2.0) * density).toInt()
            sx to sy
        } else 0 to 0
        if (px > 0) {
            fileLogCaller("[d] Turnstile 坐标点击 ($px,$py)")
            tapScreen(px, py)
            sleep(3500)
            val probe = runCatching { RegistrationDriver.runJs(web, RegistrationDriver.PAGE_STATE) }.getOrNull()
            if (probe != null && probe.optInt("tokenLen") >= 80) {
                listener?.onHumanActionDone()
                return
            }
        }
        listener?.onNeedHumanAction("Turnstile 自动点击未通过，请在注册窗口手点")
    }

    /** 主线程注入真实触摸事件（等效手指，Cloudflare 认这种）。 */
    private fun tapScreen(x: Int, y: Int) {
        activity.runOnUiThread {
            runCatching {
                val downTime = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                activity.dispatchTouchEvent(down)
                down.recycle()
                val up = android.view.MotionEvent.obtain(downTime, downTime + 120, android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                activity.dispatchTouchEvent(up)
                up.recycle()
            }
        }
    }

    private fun waitForSsoCookie(web: WebView, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return ""
            val (sso, _) = RegistrationDriver.extractSso(web)
            if (sso.isNotBlank()) return sso
            sleep(2000)
        }
        return ""
    }

    // ------------------------------------------------------------- mint

    /**
     * 授权页推进器：申请设备码 → WebView 走 device/consent/登录页 → 并行 HTTP pollOAuth 换票。
     *
     * 两条调用路径共用它：
     *  - mint（刚注册完）：WebView 还在登录态，直接就能点过授权页，传 password = "" 即可；
     *  - 补登（历史号）：sso cookie 可能已过期，授权页会退化成 sign-in，此时靠 [password] 登回去。
     *
     * 关键约束（照抄 taixu OAuthFlow.mint）：WebView 推进与 HTTP pollOAuth 必须**并行**——
     * pollOAuth 每次阻塞 intervalMs（~5s），串行时 WebView 几乎没机会点「继续/允许」，实测会轮询超时。
     *
     * @param password 空串表示不做登录兜底（注册态 WebView 已登录）
     * @return 成功返回含 uid 的 JSONObject；失败返回 null 并写好 [lastError]
     */
    private fun authorizeDeviceFlow(web: WebView, email: String, password: String, timeoutMs: Long): JSONObject? {
        lastError = ""
        return try {
            val session = NativeCore.beginOAuth(AccountRegion.XAI)
            fileLogCaller("[*] 设备码 ${session.userCode}，在 WebView 内授权…")
            web.post { web.loadUrl(session.authUrl) }

            // WebView 授权推进线程：探测 phase → device 填码 / consent 提表单 / 登录页用邮箱密码登录
            val stopDriver = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread {
                var lastPhase = ""
                var lastAction = 0L
                while (!stopDriver.get() && !stopRequested) {
                    val st = runCatching { RegistrationDriver.runJs(web, RegistrationDriver.OAUTH_STATE, 8000L) }.getOrNull()
                    val phase = st?.optString("phase").orEmpty()
                    if (phase != lastPhase) { lastPhase = phase; fileLogCaller("[d] OAuth phase: $phase url=${st?.optString("url")?.take(90)} txt=${st?.optString("text")?.take(50)}") }
                    val now = System.currentTimeMillis()
                    when (phase) {
                        "device" -> if (now - lastAction > 4000) {
                            lastAction = now
                            runCatching { RegistrationDriver.runJs(web, RegistrationDriver.oauthUserCode(session.userCode), 8000L) }
                        }
                        "consent" -> if (now - lastAction > 4000) {
                            lastAction = now
                            val r = runCatching { RegistrationDriver.runJs(web, RegistrationDriver.OAUTH_ALLOW_FORM, 8000L) }.getOrNull()
                            fileLogCaller("[d] ALLOW_FORM -> ${r ?: "null"}")
                        }
                        // sso 已失效 → 授权页退化成登录页；用留档的邮箱密码登回去（注册态不传密码，跳过）
                        "password", "email", "signin" -> if (password.isNotBlank() && now - lastAction > 4000) {
                            lastAction = now
                            val r = runCatching { RegistrationDriver.runJs(web, RegistrationDriver.oauthSignIn(email, password), 8000L) }.getOrNull()
                            fileLogCaller("[d] 登录兜底 -> ${r?.optString("state")}")
                        }
                        "device-done" -> { /* 授权完成，等 pollOAuth 收尾 */ }
                    }
                    Thread.sleep(1500)
                }
            }.apply { name = "farm-oauth-driver"; isDaemon = true }.start()

            try {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (stopRequested) { lastError = "已停止"; return null }
                    when (val poll = NativeCore.pollOAuth(activity, session)) {
                        is NativeCore.OAuthPoll.Success -> {
                            val acc = poll.account
                            listener?.onAccountPooled(acc.nickname, acc.nickname)
                            lastFailure = ""
                            return JSONObject().put("_saved_direct", true).put("uid", acc.uid)
                        }
                        NativeCore.OAuthPoll.Pending -> Thread.sleep(2000)
                        NativeCore.OAuthPoll.SlowDown -> Thread.sleep(3000)
                        is NativeCore.OAuthPoll.Failed -> { lastError = poll.message; return null }
                    }
                }
                lastError = "授权轮询超时（${timeoutMs / 1000}s）"
                null
            } finally {
                stopDriver.set(true)
            }
        } catch (e: Exception) {
            lastError = "授权异常：${e.message}"
            null
        }
    }

    /**
     * mint：注册刚完成、WebView 仍在登录态，复用同一个 WebView 走设备流把号送进池子。
     *
     * 台账由 runOne 回写（它持有 rowId），这里不再插行——否则一次注册会留下
     * 「registered + pooled」两行，补登时无法区分哪行才是待补的。
     */
    private fun mintWithExistingSession(web: WebView, regEmail: String): JSONObject? =
        authorizeDeviceFlow(web, regEmail, "", 150_000L)

    // ------------------------------------------------------------- 补登

    /**
     * 补登：把「注册成功但没进号池」的号捞回来，重新走一遍设备流授权入池。
     *
     * 这类号的来源是 mint 失败（网络断、轮询超时、进程被杀、consent 页卡住）。注册本身已经成功，
     * xAI 那边账号真实存在，只是本地没换到 token —— 重新授权就能救回，代价是几十秒，
     * 而不是浪费一个新邮箱重新注册一遍。
     *
     * 与批次共用 running / stopRequested，所以「补登」和「开始批次」互斥，UI 侧两个按钮要一起禁用。
     *
     * @param ids 非 null 时只补这些 rowId（批次收尾自动补用，只覆盖本批失败的号）；
     *            null = 补全量待补登（面板按钮手动触发）
     */
    fun backfillPending(webProvider: () -> WebView, gapSec: Long = 60, ids: List<Long>? = null) {
        if (running) return
        running = true
        stopRequested = false
        Thread {
            var pooled = 0; var failed = 0; var attempted = 0
            // 补登失败汇总（按类型去重，与批次同一套「总结不堆日志」原则）
            val bfFailures = LinkedHashMap<String, DiagReporter.Failure>()
            try {
                val store = NativeCore.store(activity)
                val pending = if (ids == null) store.listFarmPending() else store.listFarmPendingByIds(ids)
                if (pending.isEmpty()) {
                    fileLogCaller("[*] 没有待补登的号")
                } else {
                    fileLogCaller("[*] 待补登 ${pending.size} 个号（注册成功但未入池），间隔 ${gapSec}s")
                    for (p in pending) {
                        if (stopRequested) { fileLogCaller("[!] 已手动停止补登"); break }
                        attempted++
                        if (p.sso.isBlank() || p.password.isBlank()) {
                            // 凭据解不开（Keystore 密钥丢失 / 备份恢复到新机）：留档已无意义，
                            // 标死避免每轮补登都白试一次。
                            store.updateFarmLedger(p.id, "backfill_failed", error = "凭据无法解密，只能重新注册")
                            failed++
                            bfFailures.putIfAbsent("credential_lost",
                                DiagReporter.Failure("补登取凭据", "credential_lost", "留档凭据解密为空（Keystore 密钥变更或恢复自旧备份）"))
                            fileLogCaller("[x] ${p.email} 凭据无法解密，跳过")
                        } else {
                            fileLogCaller("[*] 补登 $attempted/${pending.size}：${p.email}")
                            reportStage(0, p.email, "补登授权")
                            val res = try {
                                backfillOne(p, webProvider)
                            } catch (e: Exception) {
                                lastError = e.message ?: e.javaClass.simpleName
                                null
                            }
                            if (res != null) {
                                pooled++
                                store.updateFarmLedger(p.id, "pooled", uid = res.optString("uid"), error = "")
                                fileLogCaller("[+] 补登成功入池：${p.email}")
                            } else {
                                failed++
                                store.updateFarmLedger(p.id, "backfill_failed", error = lastError)
                                bfFailures.putIfAbsent("backfill_failed",
                                    DiagReporter.Failure(lastStage.ifBlank { "补登授权" }, "backfill_failed", lastError))
                                fileLogCaller("[x] 补登失败：${p.email} —— $lastError")
                            }
                            if (!stopRequested && attempted < pending.size) {
                                fileLogCaller("[*] 等待 ${gapSec}s（补登间隔，避免连续授权触发风控）…")
                                waitWithStop(gapSec * 1000)
                            }
                        }
                    }
                }
            } finally {
                running = false
                listener?.onStage(0, "", "idle")  // idle 不记 lastStage，保留直接转发
                var diagNote = ""
                if (bfFailures.isNotEmpty()) {
                    val rep = DiagReporter.reportFlowFailures(
                        activity,
                        "补登（尝试 $attempted，入池 $pooled，失败 $failed）",
                        bfFailures.values.toList()
                    )
                    if (rep != null) {
                        diagNote = "；错误总结已存 ${rep.name}"
                        fileLogCaller("[!] 错误总结已保存：${rep.absolutePath}")
                    }
                }
                listener?.onBackfillFinished("补登结束：尝试 $attempted，入池 $pooled，失败 $failed$diagNote")
            }
        }.apply { name = "farm-backfill" }.start()
    }

    /**
     * 补登单个号：把留档的 sso 种回 CookieManager 恢复登录态 → 设备流授权 → 入池。
     *
     * 不造新邮箱、不重新注册。sso 若已过期，[authorizeDeviceFlow] 会走进登录页分支，
     * 用留档的邮箱密码登回去再授权。
     *
     * @return 成功返回含 uid 的 JSONObject；失败返回 null 并写好 [lastError]
     */
    private fun backfillOne(p: NativeStore.PendingFarm, webProvider: () -> WebView): JSONObject? {
        lastError = ""
        val web = webProvider()
        // 种 sso 必须连 sso-rw 一起种：x.ai 两个 cookie 都要，只给一个等于未登录
        web.post {
            val cm = CookieManager.getInstance()
            for (domain in listOf("https://accounts.x.ai/", "https://grok.com/")) {
                cm.setCookie(domain, "sso=${p.sso}; path=/")
                cm.setCookie(domain, "sso-rw=${p.sso}; path=/")
            }
            cm.flush()
        }
        Thread.sleep(600)
        val res = authorizeDeviceFlow(web, p.email, p.password, 150_000L)
        if (res == null && lastError.isBlank()) lastError = "补登授权未完成"
        return res
    }

    // ------------------------------------------------------------- 小工具

    private fun randomName(): String {
        val pool = "abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        repeat(5) { sb.append(pool[rng.nextInt(pool.length)]) }
        return sb.replace(0, 1, sb.substring(0, 1).uppercase()).toString()
    }

    private fun randomPassword(): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"; val lower = "abcdefghijkmnpqrstuvwxyz"
        val digit = "23456789"; val special = "!@#$%"
        val sb = StringBuilder()
        sb.append(upper[rng.nextInt(upper.length)]).append(lower[rng.nextInt(lower.length)])
            .append(digit[rng.nextInt(digit.length)]).append(special[rng.nextInt(special.length)])
        val all = upper + lower + digit + special
        repeat(10) { sb.append(all[rng.nextInt(all.length)]) }
        return sb.toString()
    }
}
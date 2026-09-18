package com.grok2api.gateway

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 邮箱源聚合：照抄 com.taixu.grokreg.MailService 的四 provider + 域名轮换 + 验证码提取。
 *
 * provider 一览（与 taixu 对齐，配置走 farm 设置项）：
 * - mailtm    mail.tm（免配置，默认；临时邮箱老牌，偶发抖动有重试）
 * - duckmail  api.duckmail.sbs（可选 API Key；Hydra 列表格式）
 * - yyds      maliapi.215.im/v1（需 JWT 或 X-API-Key）
 * - cloudflare 自建 Cloudflare 邮箱服务（配置 api_base + path_accounts + 收件域名 CSV 轮换）
 * - cloudmail 自建 Cloud Mail（配置 api_base + public_token + 收件域名 CSV 轮换；token 即 "cloudmail:<address>"）
 *
 * 验证码提取照抄 taixu extractVerificationCode：
 * subject 专攻（"XXX-XXX xAI" 开头 / "confirmation code: XXX-XXX"）→ 正文宽匹配 → 数字码兜底。
 */
object FarmMail {

    private const val MAILTM = "https://api.mail.tm"
    private const val DUCKMAIL = "https://api.duckmail.sbs"
    private const val YYDS = "https://maliapi.215.im/v1"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val rng = SecureRandom()
    private val cfDomainIndex = AtomicInteger(0)
    private val cloudmailDomainIndex = AtomicInteger(0)

    data class Mailbox(val address: String, val token: String)

    class MailException(message: String, val transient: Boolean = false) : Exception(message)

    // ------------------------------------------------------------- Provider 路由

    fun createMailbox(context: Context): Mailbox {
        val provider = setting(context, "farm_provider", "mailtm")
        return when (provider) {
            "duckmail" -> duckmailCreate(context)
            "yyds" -> yydsCreate(context)
            "cloudflare" -> cloudflareCreate(context)
            "cloudmail" -> cloudmailCreate(context)
            else -> mailtmCreate()
        }
    }

    private fun setting(context: Context, key: String, def: String): String =
        NativeCore.store(context).getSettings().optString(key, def).ifBlank { def }

    private fun pickDomain(context: Context, key: String, counter: AtomicInteger): String {
        val csv = setting(context, key, "")
        val domains = csv.split(",").map { it.trim().trimStart('@') }.filter { it.isNotEmpty() }
        if (domains.isEmpty()) throw MailException("$key 未配置收件域名（逗号分隔）")
        return domains[counter.getAndIncrement().mod(domains.size)]
    }

    private fun randomUsername(n: Int): String {
        val pool = "abcdefghijkmnpqrstuvwxyz23456789"
        val sb = StringBuilder(n)
        repeat(n) { sb.append(pool[rng.nextInt(pool.length)]) }
        return sb.toString()
    }

    private fun randomPassword(): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"; val lower = "abcdefghijkmnpqrstuvwxyz"
        val digit = "23456789"; val special = "!@#$%"
        val all = upper + lower + digit + special
        val chars = StringBuilder()
            .append(upper[rng.nextInt(upper.length)]).append(lower[rng.nextInt(lower.length)])
            .append(digit[rng.nextInt(digit.length)]).append(special[rng.nextInt(special.length)])
        repeat(10) { chars.append(all[rng.nextInt(all.length)]) }
        return chars.toString()
    }

    // ------------------------------------------------------------- mail.tm（默认，免配置）

    private fun mailtmCreate(): Mailbox {
        val domain = runCatching { mailtmDomains().getJSONObject(0).getString("domain") }
            .getOrElse { runCatching { mailtmDomainsHydra() }.getOrElse { throw MailException("mail.tm 无可用域名") } }
        val address = "xai" + randomUsername(8) + "@$domain"
        val pw = randomPassword()
        postJson("$MAILTM/accounts", JSONObject().put("address", address).put("password", pw), 201)
        val token = postJson("$MAILTM/token", JSONObject().put("address", address).put("password", pw), 200)
            .optString("token")
        if (token.isBlank()) throw MailException("mail.tm token 接口未返回 token")
        return Mailbox(address, token)
    }

    private fun mailtmDomains(): JSONArray = try { JSONArray(getText("$MAILTM/domains")) } catch (e: Exception) { JSONArray() }
    private fun mailtmDomainsHydra(): String {
        val text = getText("$MAILTM/domains")
        Regex("<domain>([^<]+)</domain>").find(text)?.let { return it.groupValues[1] }
        JSONObject(text).optJSONArray("hydra:member")?.let { if (it.length() > 0) return it.getJSONObject(0).getString("domain") }
        throw MailException("no domain")
    }

    // ------------------------------------------------------------- DuckMail

    private fun duckmailCreate(context: Context): Mailbox {
        val apiKey = setting(context, "duckmail_api_key", "")
        val headers = if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
        val domain = runCatching {
            val res = getJson("$DUCKMAIL/domains", headers)
            res.optJSONArray("hydra:member")?.getJSONObject(0)?.getString("domain")
                ?: res.optJSONArray("member")?.getJSONObject(0)?.getString("domain")
                ?: res.getJSONArray("member").getJSONObject(0).getString("domain")
        }.getOrElse { throw MailException("DuckMail 无已验证域名可用") }
        val address = randomUsername(10) + "@$domain"
        val pw = randomPassword().map { it }.joinToString("")
        postJson("$DUCKMAIL/accounts", JSONObject()
            .put("address", address).put("password", pw).put("expiresIn", 0), 200, headers)
        val token = postJson("$DUCKMAIL/token", JSONObject().put("address", address).put("password", pw), 200)
            .optString("token")
        if (token.isBlank()) throw MailException("DuckMail 获取 token 失败")
        return Mailbox(address, token)
    }

    // ------------------------------------------------------------- YYDS

    private fun yydsHeaders(context: Context, contentType: Boolean): Map<String, String> {
        val h = HashMap<String, String>()
        if (contentType) h["Content-Type"] = "application/json"
        val jwt = setting(context, "yyds_jwt", "")
        val apiKey = setting(context, "yyds_api_key", "")
        if (jwt.isNotBlank()) h["Authorization"] = "Bearer $jwt"
        else if (apiKey.isNotBlank()) h["X-API-Key"] = apiKey
        else throw MailException("YYDS 未配置 JWT 或 API Key")
        return h
    }

    private fun yydsCreate(context: Context): Mailbox {
        val h = yydsHeaders(context, true)
        val domain = runCatching {
            val res = getJson("$YYDS/domains", yydsHeaders(context, false))
            res.optJSONArray("hydra:member")?.getJSONObject(0)?.getString("domain")
                ?: res.optJSONArray("domains")?.getJSONObject(0)?.optString("domain")
                ?: res.optJSONArray("data")?.getJSONObject(0)?.optString("domain")
                ?: throw MailException("YYDS 无已验证域名可用")
        }.getOrElse { throw it as? MailException ?: MailException("YYDS 域名接口失败") }
        val address = randomUsername(10) + "@$domain"
        val pw = randomPassword()
        val res = postJson("$YYDS/accounts", JSONObject().put("address", address).put("password", pw), 200, h)
        val token = res.optString("token").ifBlank {
            runCatching { postJson("$YYDS/token", JSONObject().put("address", address).put("password", pw), 200, h).optString("token") }.getOrDefault("")
        }
        if (token.isBlank()) throw MailException("YYDS 获取 token 失败")
        return Mailbox(address, token)
    }

    // ------------------------------------------------------------- Cloudflare（自建）

    private fun cloudflareCreate(context: Context): Mailbox {
        val apiBase = setting(context, "cloudflare_api_base", "").trimEnd('/')
        if (apiBase.isBlank()) throw MailException("Cloudflare API Base 未配置")
        val path = setting(context, "cloudflare_path_accounts", "/admin/new_address")
        val domain = pickDomain(context, "cloudflare_domains", cfDomainIndex)
        val apiKey = setting(context, "cloudflare_api_key", "")
        val isAdminCreate = path.trimEnd('/').lowercase() == "/admin/new_address"
        val payload = JSONObject()
        if (isAdminCreate) payload.put("name", randomUsername(10)).put("enablePrefix", true)
        if (domain.isNotBlank()) payload.put("domain", domain)
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        if (apiKey.isNotBlank()) h["x-admin-auth"] = apiKey
        val res = postJson(apiBase + path + "?limit=1&offset=0", payload, 200, h)
        val address = res.optString("address").ifBlank {
            res.optJSONObject("data")?.optString("address").orEmpty()
        }
        if (address.isBlank()) throw MailException("Cloudflare 建箱未返回地址")
        return Mailbox(address, "cf:$address")
    }

    // ------------------------------------------------------------- CloudMail（自建）

    private fun cloudmailCreate(context: Context): Mailbox {
        val apiBase = setting(context, "cloudmail_api_base", "").trim()
        if (apiBase.isBlank()) throw MailException("Cloud Mail API Base 未配置")
        if (setting(context, "cloudmail_public_token", "").isBlank()) throw MailException("Cloud Mail Public Token 未配置")
        val domain = pickDomain(context, "cloudmail_domains", cloudmailDomainIndex)
        val address = randomUsername(12) + "@$domain"
        return Mailbox(address, "cloudmail:$address")
    }

    private fun cloudmailList(context: Context, address: String): JSONArray {
        val apiBase = setting(context, "cloudmail_api_base", "").trim().trimEnd('/')
        val token = setting(context, "cloudmail_public_token", "")
        val path = setting(context, "cloudmail_path_messages", "/api/mail/list").let { if (it.startsWith("/")) it else "/$it" }
        val payload = JSONObject().put("toEmail", address).put("type", 0).put("isDel", 0)
            .put("timeSort", "desc").put("num", 1).put("size", 20)
        val res = postJson(apiBase + path, payload, 200, mapOf("Authorization" to token))
        val code = res.opt("code")
        if (code != null && code.toString() != "200") throw MailException("Cloud Mail 接口失败 code=$code")
        return res.optJSONArray("data") ?: JSONArray()
    }

    // ------------------------------------------------------------- 收码（统一入口 + taixu 提取链）

    /**
     * 轮询收验证码。返回**去横线后的纯码**（OTP 框不接受横线）。
     * provider 差异：mailtm/duckmail/yyds 走 token API；cloudflare/cloudmail 走自建接口。
     */
    fun waitCode(context: Context, mailbox: Mailbox, timeoutSec: Int = 120, pollEverySec: Long = 5): String {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        val seen = HashSet<String>()
        while (System.currentTimeMillis() < deadline) {
            val messages = try { listMessages(context, mailbox) } catch (e: MailException) { if (!e.transient) throw e; JSONArray() }
            for (j in 0 until messages.length()) {
                val m = messages.optJSONObject(j) ?: continue
                val mid = m.optString("id").ifBlank { m.optString("_id", j.toString()) }
                if (mid.isBlank() || !seen.add(mid)) continue
                if (!recipientMatches(m, mailbox.address)) continue
                val subject = m.optString("subject").orEmpty()
                val body = normalizeMailBody(m)
                val code = extractVerificationCode(body, subject)
                if (code != null) return code.replace("-", "")
            }
            Thread.sleep(pollEverySec * 1000)
        }
        throw MailException("等待验证码超时（${timeoutSec}s）")
    }

    private fun listMessages(context: Context, mailbox: Mailbox): JSONArray = when {
        mailbox.token.startsWith("cloudmail:") -> cloudmailList(context, mailbox.address)
        else -> {
            val base = if (mailbox.address.endsWith(".sbs") || setting(context, "farm_provider", "mailtm") == "duckmail") DUCKMAIL else MAILTM
            tokenList(base, mailbox.token)
        }
    }

    private fun tokenList(base: String, token: String): JSONArray {
        val request = Request.Builder().url("$base/messages")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .get().build()
        return execute(request) { status, text ->
            if (status != 200) throw MailException("messages HTTP $status", transient = status >= 500)
            runCatching { JSONArray(text) }.getOrElse {
                JSONObject(text).optJSONArray("hydra:member")
                    ?: JSONObject(text).optJSONArray("member") ?: JSONArray()
            }
        }
    }

    /** taixu recipientMatches：to 数组或 toEmail 字段匹配收件人。 */
    private fun recipientMatches(msg: JSONObject, address: String): Boolean {
        val target = address.lowercase()
        val toArr = msg.optJSONArray("to")
        if (toArr != null && toArr.length() > 0) {
            for (i in 0 until toArr.length()) {
                if (toArr.optJSONObject(i)?.optString("address", "").orEmpty().lowercase() == target) return true
            }
            return false
        }
        val toEmail = msg.optString("toEmail", "").ifBlank { msg.optString("to_email", "") }
        if (toEmail.isBlank()) return true
        return toEmail.lowercase() == target
    }

    /** taixu normalizeMailBody：多字段聚合 + html 去标签。 */
    private fun normalizeMailBody(msg: JSONObject): String {
        val parts = ArrayList<String>()
        for (key in listOf("text", "raw", "content", "intro", "body", "snippet")) {
            when (val v = msg.opt(key)) {
                is String -> if (v.isNotBlank()) parts.add(v)
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? String)?.let { if (it.isNotBlank()) parts.add(it) }
            }
        }
        when (val html = msg.opt("html")) {
            is String -> if (html.isNotBlank()) parts.add(Regex("<[^>]+>").replace(html, " "))
            is JSONArray -> for (i in 0 until html.length()) (html.opt(i) as? String)?.let {
                if (it.isNotBlank()) parts.add(Regex("<[^>]+>").replace(it, " "))
            }
        }
        return parts.joinToString("\n")
    }

    /** taixu extractVerificationCode：subject 专攻 → 正文宽匹配 → 数字码兜底。 */
    fun extractVerificationCode(text: String, subject: String): String? {
        if (subject.isNotEmpty()) {
            Regex("^([A-Z0-9]{3}-[A-Z0-9]{3})\\s+xAI\\b", RegexOption.IGNORE_CASE).find(subject)?.let { return it.groupValues[1] }
            Regex("\\b(?:confirmation|verification)\\s+code\\s*:\\s*([A-Z0-9]{3}-[A-Z0-9]{3})\\b", RegexOption.IGNORE_CASE).find(subject)?.let { return it.groupValues[1] }
        }
        Regex("\\b([A-Z0-9]{3}-[A-Z0-9]{3})\\b", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1] }
        for (p in listOf(
            Regex("verification\\s+code[:\\s]+(\\d{4,8})", RegexOption.IGNORE_CASE),
            Regex("your\\s+code[:\\s]+(\\d{4,8})", RegexOption.IGNORE_CASE),
            Regex("confirm(?:ation)?\\s+code[:\\s]+(\\d{4,8})", RegexOption.IGNORE_CASE)
        )) {
            p.find(text)?.let { return it.groupValues[1] }
        }
        return null
    }

    // ------------------------------------------------------------- 台账

    fun appendLedger(context: Context, email: String, lane: Int, status: String, error: String = "", uid: String = "") {
        NativeCore.store(context).appendFarmLedger(email, lane, status, error, uid)
    }

    /**
     * 注册成功、拿到 sso 的那一刻就把凭据落盘，返回台账 rowId。
     *
     * 这一步必须在 mint 之前：mint 要走设备流（几十秒到两分钟），期间进程被杀 / 网络断
     * 都会让号卡在「已注册但未入池」——只有凭据已经落盘，补登才有东西可用。
     */
    fun appendRegisteredLedger(context: Context, email: String, lane: Int, sso: String, password: String): Long =
        NativeCore.store(context).appendFarmLedger(email, lane, "registered", sso = sso, password = password)

    // ------------------------------------------------------------- HTTP 基建

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.get().build()) { status, text ->
            if (status != 200) throw MailException("$url HTTP $status", transient = status >= 500)
            runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }

    private fun postJson(url: String, body: JSONObject, expectStatus: Int, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.post(body.toString().toRequestBody(jsonType)).build()) { status, text ->
            if (status != expectStatus && status != 201) {
                throw MailException("$url HTTP $status：${text.take(160)}", transient = status >= 500 || status == 429)
            }
            runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return execute(request) { status, text ->
            if (status != 200) throw MailException("$url HTTP $status", transient = status >= 500)
            text
        }
    }

    private fun <T> execute(request: Request, handler: (Int, String) -> T): T {
        var lastError: Exception? = null
        for (attempt in 1..4) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    return handler(response.code, text)
                }
            } catch (e: MailException) {
                if (!e.transient) throw e
                lastError = e
            } catch (e: java.io.IOException) {
                lastError = MailException("网络错误：${e.message ?: e.javaClass.simpleName}", transient = true)
            }
            if (attempt < 4) {
                val backoff = minOf(8L, 1L shl (attempt - 1)) * 1000L + rng.nextInt(400)
                Thread.sleep(backoff)
            }
        }
        throw lastError ?: MailException("请求失败")
    }
}
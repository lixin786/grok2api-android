package com.grok2api.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * xAI Grok 网关的账号与上游通信核心。
 *
 * 与 WorkBuddy 版的结构性差异：
 *  1. **认证**：xAI 走 RFC 8628 设备流 OAuth（device_code + 轮询），不再有 state/authUrl 模式；
 *  2. **推理端点**：`POST /responses`（Responses 协议），而不是腾讯的 `/v2/chat/completions`，
 *     因此请求体要经 [XaiProtocol.chatToResponses] 转换，响应流要经 [XaiStreamTranslator] 转回；
 *  3. **额度**：xAI 无签到，改为查 `/billing?format=credits` + `/user?include=subscription`；
 *  4. **模型目录**：`GET /models` 返回 `{"data":[{id,...}]}`，需要按 hidden 过滤 + 本地能力表补全。
 *
 * 账号的持久化结构沿用原版（auth.accessToken / auth.refreshToken / account.uid），
 * 这样 [NativeStore] 的表结构与加密逻辑完全不用动。
 */
object NativeCore {
    const val BACKEND = "https://cli-chat-proxy.grok.com/v1"
    const val INTERNATIONAL_BACKEND = BACKEND
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 推理用的 HTTP 客户端。
     *
     * `readTimeout` 是**两次读到数据之间的最大间隔**（不是整个响应的总时长），流式下模型会持续吐 token，
     * 间隙很短，所以 120 秒足够宽松。之所以从 300 秒收紧：一旦上游不再发数据，
     * 阻塞在这个读取上的 worker 会被占住整整 5 分钟；几个这样的请求就能把线程池耗干。
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()

    /**
     * 控制面请求（/models、/billing）用的短超时客户端：不能让界面等太久。
     *
     * 显式打开 OkHttp 的透明 gzip：它会在发送时自动加 Accept-Encoding: gzip，
     * 并在收到响应时自动解压。**绝不能自己手写 Accept-Encoding 头**——那样 OkHttp
     * 认为调用方要自行处理压缩，会把压缩字节原样交给上层，导致 JSON 解析失败（表现为乱码）。
     */
    private val controlHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 模型健康探测用的客户端：探测要「快」，不能像正常推理那样慢等。
     * readTimeout 25 秒——上游流式下首个事件通常 1-3 秒就到，25 秒足够区分
     * 「模型可用但慢」与「模型不可用/冻结」。
     */
    private val probeHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 进程级稳定身份，对应上游 `x-grok-agent-id`。
     * 官方 CLI 用持久化的机器标识；这里用进程级 UUID 代替——同一进程内必须稳定，
     * 每请求换新会破坏上游侧的会话亲和。
     */
    val agentId: String by lazy { UUID.randomUUID().toString() }

    /**
     * 进程内模型目录缓存。请求路径（GET /v1/models）只读它，绝不发起网络请求——
     * 历史问题：modelsForRegion 在缓存过期时同步拉上游，客户端（Cherry Studio 等）拉一次模型
     * 要干等几十秒。现在过期数据也立刻返回（stale-while-revalidate），刷新交给后台线程。
     */
    private class ModelSnapshot(val payload: String, val expiresAt: Long)
    private val modelSnapshots = ConcurrentHashMap<String, ModelSnapshot>()
    private val modelRefreshing = ConcurrentHashMap<String, AtomicBoolean>()
    private val modelRefreshPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "model-refresh").apply { isDaemon = true } }
    private val oauthPool = Executors.newSingleThreadExecutor { r -> Thread(r, "oauth").apply { isDaemon = true } }

    /** 上游模型目录不可用时的静态兜底。 */
    private val FALLBACK_MODELS = listOf("grok-4.6", "grok-4.5", "grok-4.3", "grok-composer-2.5-fast")

    private fun snapshotModels(region: AccountRegion): JSONArray? =
        modelSnapshots[region.id]?.let { JSONArray(it.payload) }

    private fun rememberModels(region: AccountRegion, models: JSONArray, expiresAt: Long) {
        modelSnapshots[region.id] = ModelSnapshot(models.toString(), expiresAt)
    }

    /** 后台刷新（single-flight）：同一 region 同时最多一次上游拉取，绝不阻塞调用方。 */
    private fun scheduleModelRefresh(context: Context, region: AccountRegion) {
        val flag = modelRefreshing.computeIfAbsent(region.id) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        val app = context.applicationContext
        runCatching {
            modelRefreshPool.execute {
                try { refreshModels(app, region) }
                catch (e: Exception) { Log.w(TAG, "background model refresh failed: ${region.id}", e) }
                finally { flag.set(false) }
            }
        }.onFailure { flag.set(false) }
    }

    /** 维护循环调用：缓存缺失或已过期时后台预热，保证请求路径永不触发网络。 */
    fun prewarmModels(context: Context, region: AccountRegion) {
        val nowSec = System.currentTimeMillis() / 1000
        val fresh = modelSnapshots[region.id]?.let { it.expiresAt > nowSec }
            ?: (store(context).getModelCache(allowExpired = false, region = region.id) != null)
        if (!fresh) scheduleModelRefresh(context, region)
    }

    // ------------------------------------------------------------------ 账号模型

    /**
     * 账号包装。`uid` 取 xAI 的 user_id（JWT 的 sub），`nickname` 取邮箱——
     * 这样 UI、应用 Key 的归属、用量记录的 account_uid 全部沿用原字段。
     */
    data class Account(val root: JSONObject) {
        val auth: JSONObject get() = root.optJSONObject("auth") ?: root
        val profile: JSONObject get() = root.optJSONObject("account") ?: auth.optJSONObject("account") ?: JSONObject()
        val uid: String get() = profile.optString("uid").ifBlank { profile.optString("user_id") }
        val nickname: String get() = profile.optString("nickname").ifBlank {
            profile.optString("email").ifBlank { displayName("", uid) }
        }
        val email: String get() = profile.optString("email")
        val region: AccountRegion get() = AccountRegion.XAI
        val key: String get() = "${region.id}:$uid"
        val backend: String get() = region.backend
    }

    data class UpstreamCall(val request: Request, val account: Account)

    fun store(context: Context): NativeStore = NativeStore.get(context)

    fun loadAccount(context: Context): Account? {
        migrateLegacy(context)
        val selected = store(context).selectAccount(markUsed = false) ?: return null
        return store(context).getAccountRoot(selected.optString("account_key"))?.let(::Account)
    }

    fun loadAccount(context: Context, accountKey: String): Account? =
        store(context).getAccountRoot(accountKey)?.let(::Account)

    fun saveAccount(context: Context, root: JSONObject, requestedRegion: AccountRegion? = null): Account {
        root.put("region", AccountRegion.XAI.id)
        val account = Account(root)
        // 官方 grok2api 允许「只给 refresh_token」的导入（首次使用时换票）。
        // 因此这里只要求二者至少有一个，不再强制 accessToken。
        require(
            account.auth.optString("accessToken").isNotBlank() ||
                account.auth.optString("refreshToken").isNotBlank()
        ) { "auth 文件缺少 accessToken（至少需要 accessToken 或 refreshToken）" }
        require(account.uid.isNotBlank()) { "auth 文件缺少 account.uid（或 user_id）" }
        store(context).upsertAccount(root, AccountRegion.XAI.id)
        context.getSharedPreferences("native", Context.MODE_PRIVATE).edit().remove("account").apply()
        return account
    }

    fun importAccounts(context: Context, documents: JSONArray): JSONObject {
        val imported = JSONArray(); val rejected = JSONArray()
        for (i in 0 until documents.length()) {
            val raw = when (val item = documents.opt(i)) {
                is JSONObject -> item
                is String -> runCatching { JSONObject(item) }.getOrNull()
                else -> null
            }
            if (raw == null) { rejected.put(JSONObject().put("index", i).put("error", "无效 JSON")); continue }
            runCatching { saveAccount(context, normalizeImportedAccount(raw)) }
                .onSuccess { imported.put(it.uid) }
                .onFailure { rejected.put(JSONObject().put("index", i).put("error", it.message ?: "导入失败")) }
        }
        return JSONObject().put("imported", imported).put("rejected", rejected)
    }

    /**
     * 归一化导入的账号数据，尽量让用户能直接粘贴。
     *
     * 兼容三种形态：
     *  1. 本 App 自己的导出格式（含 auth/account 包装）；
     *  2. 扁平对象 `{access_token, refresh_token, user_id, email, client_id}`（grok2api 的凭据种子）；
     *  3. 上述对象组成的数组（由调用方拆开后逐个传入）。
     */
    internal fun normalizeImportedAccount(raw: JSONObject): JSONObject {
        if (raw.has("auth")) {
            // 已是本 App 格式：只补齐可能缺失的 uid / email
            val auth = raw.optJSONObject("auth") ?: JSONObject()
            val profile = raw.optJSONObject("account") ?: JSONObject()
            if (profile.optString("uid").isBlank()) {
                profile.put("uid", firstNonBlank(
                    profile.optString("user_id"), auth.optString("userId"), subjectFromJwt(auth.optString("accessToken"))
                ))
            }
            if (profile.optString("email").isBlank()) {
                profile.put("email", emailFromJwt(auth.optString("accessToken")))
            }
            raw.put("account", profile)
            return raw
        }
        val access = firstNonBlank(
            raw.optString("access_token"), raw.optString("accessToken"),
            raw.optString("token"), raw.optString("key")
        )
        val refresh = firstNonBlank(raw.optString("refresh_token"), raw.optString("refreshToken"))
        val token = JSONObject().put("accessToken", access)
        if (refresh.isNotBlank()) token.put("refreshToken", refresh)
        token.put("clientId", firstNonBlank(
            raw.optString("client_id"), raw.optString("clientId"), XaiProtocol.OAUTH_CLIENT_ID
        ))
        token.put("domain", firstNonBlank(raw.optString("domain"), AccountRegion.XAI.defaultDomain))
        // 过期时间三来源（对齐 Go 版）：显式 expires_in → RFC3339 expires_at → JWT exp claim
        val expires = raw.optLong("expires_in", 0L)
        if (expires > 0) {
            token.put("expiresAt", System.currentTimeMillis() + expires * 1000L)
        } else {
            raw.optString("expires_at").takeIf { it.isNotBlank() }?.let { txt ->
                runCatching { java.time.Instant.parse(txt).toEpochMilli() }.getOrNull()
                    ?.let { token.put("expiresAt", it) }
            }
            if (token.optLong("expiresAt", 0L) == 0L) {
                jwtClaims(access)?.optLong("exp", 0L)?.takeIf { it > 0 }
                    ?.let { token.put("expiresAt", it * 1000L) }
            }
        }
        val idToken = firstNonBlank(raw.optString("id_token"), raw.optString("idToken"))
        if (idToken.isNotBlank()) token.put("idToken", idToken)
        val userId = firstNonBlank(
            raw.optString("user_id"), raw.optString("userId"), raw.optString("sub"),
            raw.optString("principal_id"), subjectFromJwt(access), subjectFromJwt(idToken)
        )
        val email = firstNonBlank(
            raw.optString("email"), emailFromJwt(idToken), emailFromJwt(access)
        )
        // 只有 refresh_token 的导入：accessToken 为空，必须让首次调用先换票。
        // 选号逻辑只在「已知过期时间且已到期」时才刷新，所以这里显式写一个过去的时间戳
        // （1 会被 normalizedExpiryMillis 当作秒 → 1970 年），否则会拿空 token 去打上游得 401。
        if (access.isBlank() && refresh.isNotBlank()) token.put("expiresAt", 1L)
        // 纯文本路径只有 refresh_token：uid 缺失时用它派生稳定标识（哈希），保证可入库可轮换
        val effectiveUid = if (userId.isBlank() && refresh.isNotBlank()) "rt-" + sha256Short(refresh) else userId
        return JSONObject()
            .put("region", AccountRegion.XAI.id)
            .put("auth", token)
            .put("account", JSONObject().put("uid", effectiveUid)
                .put("user_id", effectiveUid)
                .put("email", email)
                .put("nickname", displayName(email, effectiveUid)))
    }

    /** 短哈希（12 位 hex）：给只有 refresh_token 的导入生成稳定 uid。 */
    private fun sha256Short(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    /**
     * 一键导入：把任意常见格式的凭据文本解析成账号数组。
     *
     * 兼容格式（与官方 grok2api Go 版对齐）：
     *  1. 本 App 导出的 JSON（单个对象或数组，含 auth/account 包装）；
     *  2. 扁平 JSON（单个对象或数组；键名兼容 snake_case 与 camelCase，
     *     含 provider/name/client_id/access_token/refresh_token/id_token/token_type/
     *     scope/expires_at(RFC3339)/expires_in/email/sub/user_id/principal_id/team_id）；
     *  3. Go 版批量文档 `{"accounts":[ {...}, ... ]}`；
     *  4. 纯文本：每行一个 refresh_token（`rt=` / `refresh_token=` 前缀可有可无）。
     *
     * 解析原则：从最严格到最宽松逐个尝试；每个账号独立解析、独立报错，
     * 部分失败不影响其余账号导入。
     */
    fun parseImportText(text: String): Pair<JSONArray, JSONArray> {
        val accepted = JSONArray(); val rejected = JSONArray()
        fun reject(index: Int, error: String) {
            rejected.put(JSONObject().put("index", index).put("error", error))
        }
        val trimmed = text.replace("\uFEFF", "").trim()

        val entries = mutableListOf<JSONObject>()
        // ---- 形态判定与解析（JSON → 纯文本逐级放宽）----
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching { normalizeJsonEntries(trimmed) }
                .getOrElse { e ->
                    rejected.put(JSONObject().put("index", 0).put("error", "JSON 解析失败：${e.message}"))
                    null
                }
            if (parsed != null) entries.addAll(parsed)
        } else {
            // 非 JSON：按纯文本 refresh_token 行解析
            var lineNo = 0
            for (line in trimmed.lines()) {
                lineNo++
                val token = line.trim()
                    .removePrefix("rt=").removePrefix("refresh_token=")
                    .trim()
                if (token.isEmpty()) continue
                if (token.length < 20 || !Regex("[A-Za-z0-9_-]+").matches(token)) {
                    reject(lineNo - 1, "第 $lineNo 行不是有效的 refresh_token")
                    continue
                }
                entries.add(JSONObject().put("refresh_token", token))
            }
            if (entries.isEmpty() && rejected.length() == 0) {
                reject(0, "无法识别的文件内容：既不是 JSON 也不是每行一个 refresh_token")
            }
        }

        // ---- 逐账号归一化导入 ----
        for ((index, entry) in entries.withIndex()) {
            runCatching { normalizeImportedAccount(entry) }
                .onSuccess { accepted.put(it) }
                .onFailure { reject(index, it.message ?: "解析失败") }
        }
        return accepted to rejected
    }

    /** 把 JSON 文本拆成"账号条目"数组：支持单对象、数组、`{"accounts":[...]}` 文档三种。 */
    private fun normalizeJsonEntries(text: String): List<JSONObject> {
        val root = runCatching { JSONObject(text) }.getOrElse {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        // Go 版批量文档：{"accounts":[...]}
        root.optJSONArray("accounts")?.let { arr ->
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        return listOf(root)
    }

    /**
     * 账号列表。这里必须先跑一次遗留数据迁移：概览页会先调 listAccounts 再调 loadAccount，
     * 而迁移原本只在 loadAccount 里触发，导致首次渲染时账号数显示为 0（数据其实已导入）。
     */
    fun listAccounts(context: Context): JSONArray {
        migrateLegacy(context)
        return store(context).listAccounts()
    }

    /**
     * 导出单个账号。输出格式与「导入 auth 文件」完全对称：导出的 JSON 直接导回即可用，
     * 因此换机迁移、备份都可以靠「导出 → 导入」完成，无需重新授权。
     * 注意返回值含 accessToken / refreshToken 等凭证，调用方应提示用户妥善保管。
     */
    fun exportAccount(context: Context, accountKey: String): String? {
        val region = accountKey.substringBefore(':', "")
        val root = store(context).getAccountRoot(accountKey) ?: return null
        if (region.isNotBlank() && !root.has("region")) root.put("region", region)
        return root.toString(2)
    }

    fun exportAllAccounts(context: Context): String {
        val keys = store(context).accountKeys()
        if (keys.size == 1) return exportAccount(context, keys[0]) ?: "{}"
        val array = JSONArray()
        keys.forEach { key ->
            runCatching { JSONObject(exportAccount(context, key).orEmpty()) }
                .getOrNull()?.let { array.put(it) }
        }
        return array.toString(2)
    }

    fun setAccountEnabled(context: Context, accountKey: String, enabled: Boolean): Boolean =
        store(context).setAccountEnabled(accountKey, enabled)
    fun setAccountPriority(context: Context, accountKey: String, priority: Int): Boolean =
        store(context).setAccountPriority(accountKey, priority)
    fun deleteAccount(context: Context, accountKey: String): Boolean = store(context).deleteAccount(accountKey)
    fun selectAccount(context: Context): JSONObject? = store(context).selectAccount()

    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences("native", Context.MODE_PRIVATE)
        val legacy = prefs.getString("api_key", null)
        val app = store(context).ensureDefaultApp(legacy)
        val key = app.optString("key")
        if (key.isNotBlank()) prefs.edit().remove("api_key").apply()
        return key
    }

    fun createApp(context: Context, name: String, note: String = "", region: AccountRegion, customKey: String? = null): JSONObject =
        store(context).createApp(name, note, region.id, customKey)
    fun updateApp(context: Context, appId: Long, name: String, note: String, region: AccountRegion, replacementKey: String? = null): JSONObject =
        store(context).updateApp(appId, name, note, region.id, replacementKey)
    fun listApps(context: Context): JSONArray {
        apiKey(context)
        return store(context).listApps()
    }
    fun getAppKey(context: Context, appId: Long): String? = store(context).getAppKey(appId)
    fun setAppEnabled(context: Context, appId: Long, enabled: Boolean): Boolean = store(context).setAppEnabled(appId, enabled)
    fun toggleApp(context: Context, appId: Long): Boolean? = store(context).toggleApp(appId)
    fun deleteApp(context: Context, appId: Long): Boolean = store(context).deleteApp(appId)
    fun authenticateApp(context: Context, key: String): JSONObject? = store(context).authenticateApp(key)

    // ------------------------------------------------------------------ 请求头

    @Synchronized
    fun headers(context: Context): Map<String, String> = accountForRequest(context).let { headersFor(it) }

    @Synchronized
    private fun accountForRequest(context: Context, region: AccountRegion? = null): Account {
        migrateLegacy(context)
        val selected = store(context).selectAccount(region = region?.id)
            ?: run {
                // 区分「根本没号」与「有号但都不可用」：后者是真实的额度耗尽/冷却信号，
                // 给客户端的提示要能帮用户判断是不是该补号。
                val hasAny = listAccounts(context).length() > 0
                throw IOException(if (hasAny) "所有 Grok 账号均不可用（额度用尽或冷却中）" else "尚未导入可用的 Grok 账号")
            }
        var account = loadAccount(context, selected.getString("account_key"))
            ?: throw IOException("账号认证数据缺失")
        val expiresAt = normalizedExpiryMillis(account.auth)
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 120_000L) {
            account = refreshToken(context, account)
        }
        return account
    }

    /** 控制面头（/models、/billing、/user）：不带 trace 系列，只带身份头。 */
    private fun headersFor(account: Account): MutableMap<String, String> {
        val auth = account.auth
        val out = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Bearer ${auth.optString("accessToken")}",
            "X-XAI-Token-Auth" to XaiProtocol.TOKEN_AUTH,
            "x-grok-client-version" to XaiProtocol.CLIENT_VERSION,
            "x-grok-client-identifier" to XaiProtocol.CLIENT_IDENTIFIER,
            "x-grok-client-mode" to "headless",
            "User-Agent" to XaiProtocol.USER_AGENT
        )
        account.uid.takeIf { it.isNotBlank() }?.let { out["x-userid"] = it }
        account.email.takeIf { it.isNotBlank() }?.let { out["x-email"] = it }
        return out
    }

    /**
     * 推理头：在基础头之上补 trace 系列。
     *
     * 三个硬要求：
     *  - `x-grok-agent-id` 进程内稳定、`x-grok-req-id` 每请求唯一；
     *  - `x-grok-session-id` / `x-grok-conv-id` 必须是**稳定会话 ID**——宁可不发也不能随机生成，
     *    随机值会破坏上游会话亲和，让 cached_tokens 恒为 0；
     *  - 流式请求 `Accept-Encoding` 必须是 identity，让上游原样发 SSE。
     */
    private fun inferenceHeaders(account: Account, model: String, sessionKey: String?): MutableMap<String, String> {
        val out = headersFor(account)
        out["Accept"] = "text/event-stream"
        out["Accept-Encoding"] = "identity"
        out["x-authenticateresponse"] = "authenticate-response"
        out["x-grok-agent-id"] = agentId
        out["x-grok-req-id"] = UUID.randomUUID().toString()
        sessionId(sessionKey)?.let { session ->
            out["x-grok-session-id"] = session
            out["x-grok-conv-id"] = session
        }
        account.uid.takeIf { it.isNotBlank() }?.let { out["x-grok-user-id"] = it }
        out["traceparent"] = "00-${randomHex(16)}-${randomHex(8)}-01"
        if (model.isNotBlank()) out["x-grok-model-override"] = model
        return out
    }

    /**
     * 把客户端给的缓存键派生成稳定的会话 UUID（version 8，与上游网关算法一致）。
     * 空键返回 null —— 调用方据此决定不发 session 头，而不是随便造一个。
     */
    internal fun sessionId(key: String?): String? {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        runCatching { UUID.fromString(trimmed) }.getOrNull()?.let { return it.toString() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(UUID_NAME_SPACE_URL)
        digest.update("grok2api:session:$trimmed".toByteArray(Charsets.UTF_8))
        val bytes = digest.digest().copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x80).toByte() // version 8
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant
        return formatUuid(bytes)
    }

    private fun formatUuid(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun randomHex(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------- Token 刷新

    fun refreshToken(context: Context, accountKey: String): Account {
        val account = loadAccount(context, accountKey) ?: throw IOException("账号不存在：$accountKey")
        return refreshToken(context, account)
    }

    /** 用 refresh_token 换新 access_token。xAI 会轮换 refresh_token，必须回写新值。 */
    private fun refreshToken(context: Context, account: Account): Account {
        val refresh = account.auth.optString("refreshToken")
        if (refresh.isBlank()) throw IOException("账号 ${account.uid} 缺少 refreshToken，需要重新授权")
        val clientId = account.auth.optString("clientId").ifBlank { XaiProtocol.OAUTH_CLIENT_ID }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refresh)
            .build()
        val request = Request.Builder().url(XaiProtocol.TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .post(form).build()
        return try {
            controlHttp.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("刷新登录失败 HTTP ${response.code}：${friendlyOAuthError(text)}")
                val payload = runCatching { JSONObject(text) }
                    .getOrElse { throw IOException("刷新登录返回非 JSON：${text.take(160)}") }
                val access = payload.optString("access_token")
                if (access.isBlank()) throw IOException("刷新登录响应缺少 access_token")
                val fresh = JSONObject().put("accessToken", access)
                // xAI 会轮换 refresh_token：返回值非空就用新的，否则保留旧的
                fresh.put("refreshToken", payload.optString("refresh_token").ifBlank { refresh })
                fresh.put("clientId", clientId)
                fresh.put("domain", account.auth.optString("domain", AccountRegion.XAI.defaultDomain))
                val expiresIn = payload.optLong("expires_in", 3600L).let { if (it > 0) it else 3600L }
                fresh.put("expiresAt", System.currentTimeMillis() + expiresIn * 1000L)
                // 刷新时上游常会一并返回 id_token：留着它，历史账号才能补上邮箱
                payload.optString("id_token").takeIf { it.isNotBlank() }?.let { fresh.put("idToken", it) }
                account.root.put("auth", fresh)
                // 早期导入/登录的账号可能没存到邮箱，这里用新拿到的 id_token 回填，
                // 否则账号列表会一直显示成 UUID。
                backfillIdentity(account.root, fresh)
                store(context).clearAccountCooldown(account.key)
                saveAccount(context, account.root, AccountRegion.XAI)
            }
        } catch (e: Exception) {
            store(context).markAccountFailure(account.key, 60,
                reason = "auth", failureCode = "token_refresh_failed")
            throw e
        }
    }

    /**
     * xAI 授权失败时返回 `{"error":"invalid_grant","error_description":"..."}`，
     * 直接把原始 JSON 抛给用户没有可读性，这里翻成人话。
     */
    private fun friendlyOAuthError(text: String): String {
        val payload = runCatching { JSONObject(text) }.getOrNull()
        val code = payload?.optString("error").orEmpty()
        val description = payload?.optString("error_description").orEmpty()
        val base = when (code) {
            "invalid_grant" -> "授权已失效，需要重新登录授权"
            "invalid_client" -> "客户端标识无效"
            "authorization_pending" -> "授权尚未完成"
            "slow_down" -> "轮询过快"
            "expired_token" -> "授权码已过期，请重新发起授权"
            "access_denied" -> "用户拒绝了授权"
            else -> code.ifBlank { "未知错误" }
        }
        return if (description.isBlank()) base else "$base（$description）"
    }

    // ------------------------------------------------------------------ 模型目录

    fun models(context: Context): JSONArray {
        val merged = linkedMapOf<String, JSONObject>()
        var lastError: Throwable? = null
        for (region in enabledRegions(context)) {
            runCatching { models(context, region) }
                .onSuccess { mergeModels(merged, it, region) }
                .onFailure { lastError = it }
        }
        if (merged.isEmpty()) {
            if (listAccounts(context).length() == 0) throw IOException("尚未导入可用的 Grok 账号")
            throw lastError ?: IOException("模型目录为空")
        }
        return JSONArray().apply { merged.values.forEach(::put) }
    }

    fun refreshAllModels(context: Context): JSONObject {
        val regions = enabledRegions(context)
        if (regions.isEmpty()) throw IOException("尚未导入可用的 Grok 账号")
        val merged = linkedMapOf<String, JSONObject>()
        val failures = JSONArray()
        for (region in regions) {
            runCatching { refreshModels(context, region) }
                .onSuccess { mergeModels(merged, it, region) }
                .onFailure {
                    failures.put(JSONObject().put("region", region.id).put("region_label", region.label)
                        .put("message", it.message ?: "刷新失败"))
                    Log.w(TAG, "refresh models failed: ${region.id}", it)
                }
        }
        if (merged.isEmpty()) {
            val message = (0 until failures.length()).joinToString("；") {
                val item = failures.getJSONObject(it); "${item.optString("region_label")}：${item.optString("message")}"
            }
            throw IOException(message.ifBlank { "模型目录为空" })
        }
        return JSONObject().put("models", JSONArray().apply { merged.values.forEach(::put) }).put("failures", failures)
    }

    /**
     * 已启用的区域。改造前从账号表推断国内/国际版；现在上游只有一个区域，
     * 只要存在任一启用账号就返回 [AccountRegion.XAI]。
     */
    private fun enabledRegions(context: Context): List<AccountRegion> {
        val accounts = listAccounts(context)
        val anyEnabled = (0 until accounts.length()).any {
            accounts.optJSONObject(it)?.optBoolean("enabled", true) == true
        }
        return if (anyEnabled) listOf(AccountRegion.XAI) else emptyList()
    }

    private fun mergeModels(target: LinkedHashMap<String, JSONObject>, models: JSONArray, region: AccountRegion) {
        for (i in 0 until models.length()) {
            val model = JSONObject(models.getJSONObject(i).toString())
            val id = model.optString("id")
            if (id.isBlank()) continue
            val existing = target[id]
            if (existing == null) {
                model.put("regions", JSONArray().put(region.id)); target[id] = model
            } else {
                val regions = existing.optJSONArray("regions") ?: JSONArray().also { existing.put("regions", it) }
                if ((0 until regions.length()).none { regions.optString(it) == region.id }) regions.put(region.id)
            }
        }
    }

    fun models(context: Context, region: AccountRegion): JSONArray = modelsForRegion(context, region)

    private fun modelsForRegion(context: Context, region: AccountRegion): JSONArray {
        val nowSec = System.currentTimeMillis() / 1000
        // 1) 内存缓存：命中即返回（毫秒级）。过期也先返回旧数据，再后台刷新，绝不阻塞客户端。
        modelSnapshots[region.id]?.let { snapshot ->
            if (snapshot.expiresAt <= nowSec) scheduleModelRefresh(context, region)
            return JSONArray(snapshot.payload)
        }
        // 2) DB 缓存：无论是否过期都直接返回（stale-while-revalidate），刷新交给后台。
        val cached = store(context).getModelCache(allowExpired = true, region = region.id)
        val stored = cached?.optJSONArray("models")
        if (stored != null && stored.length() > 0) {
            val expiresAt = cached.optDouble("expires_at", 0.0).toLong()
            rememberModels(region, stored, expiresAt)
            if (expiresAt <= nowSec) scheduleModelRefresh(context, region)
            return stored
        }
        // 3) 全新安装（无任何缓存）：先给静态兜底目录，避免客户端干等；真实目录交给后台拉。
        scheduleModelRefresh(context, region)
        return fallbackModels(context, region)
    }

    /** 无缓存时的兜底目录：静态能力表 + 后台刷新，保证客户端立刻拿到可用模型。 */
    private fun fallbackModels(context: Context, region: AccountRegion): JSONArray {
        val models = XaiProtocol.withEffortAliases(FALLBACK_MODELS.map { XaiProtocol.modelEntry(it) })
        val ttl = store(context).getSettings().optLong("model_ttl_min", 60).coerceIn(1, 1440) * 60
        store(context).saveModelCache(models, "bundled", ttl, region.id)
        rememberModels(region, models, System.currentTimeMillis() / 1000 + ttl)
        return models
    }

    fun modelsCached(context: Context): JSONArray {
        val merged = JSONArray()
        AccountRegion.entries.forEach { region ->
            (snapshotModels(region) ?: store(context).getModelCache(true, region.id)?.optJSONArray("models"))?.let { models ->
                for (i in 0 until models.length()) merged.put(models.getJSONObject(i))
            }
        }
        return merged
    }

    /**
     * 从上游 `GET /models` 拉真实目录。
     *
     * 上游返回 `{"data":[{id | model | modelId, hidden, _meta:{...}}]}`，`hidden` 为真要过滤掉。
     * 拿到 id 后再用本地能力表补上下文窗口、视觉、推理档位——上游不返回这些。
     */
    fun refreshModels(context: Context, region: AccountRegion): JSONArray {
        val selected = accountForRequest(context, region)
        val payload = executeJson(request("${region.backend}/models", "GET", null, headersFor(selected)))
        val data = payload.optJSONArray("data") ?: payload.optJSONArray("models")
            ?: throw IOException("模型接口响应缺少 data 字段：${payload.keys().asSequence().joinToString()}")
        val ordered = linkedMapOf<String, JSONObject>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            if (entry.optBoolean("hidden")) continue
            val meta = entry.optJSONObject("_meta")
            if (meta?.optBoolean("hidden") == true) continue
            val id = firstNonBlank(
                entry.optString("id"), entry.optString("model"), entry.optString("modelId"),
                meta?.optString("model").orEmpty(), meta?.optString("modelId").orEmpty()
            )
            if (id.isBlank()) continue
            val display = firstNonBlank(entry.optString("name"), meta?.optString("name").orEmpty())
            ordered[id] = XaiProtocol.modelEntry(id, display)
        }
        if (ordered.isEmpty()) throw IOException("模型接口未返回可用模型（原始条目 ${data.length()}）")
        // Build OAuth 账号恒有 composer，上游目录偶尔不列它，缺了就补上
        if (!ordered.containsKey("grok-composer-2.5-fast")) {
            ordered["grok-composer-2.5-fast"] = XaiProtocol.modelEntry("grok-composer-2.5-fast")
        }
        // **合并而非覆盖**：上游目录按账号/档位/灰度不同（chenyme 的 README 有实证），
        // 早先用"当次选中账号"的目录直接覆盖全量缓存，刷到稀疏目录就会让模型"消失"，
        // 客户端表现成"模型忽然失效"。这里与现有缓存取并集，只增不减。
        runCatching {
            val prev = store(context).getModelCache(allowExpired = true, region = region.id)
                ?.optJSONArray("models")
            if (prev != null) {
                for (i in 0 until prev.length()) {
                    val item = prev.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isNotBlank() && !ordered.containsKey(id)) ordered[id] = item
                }
            }
        }
        val models = XaiProtocol.withEffortAliases(ordered.values.toList())
        val ttl = store(context).getSettings().optLong("model_ttl_min", 60).coerceIn(1, 1440) * 60
        store(context).saveModelCache(models, "dynamic", ttl, region.id)
        rememberModels(region, models, System.currentTimeMillis() / 1000 + ttl)
        return models
    }

    // --------------------------------------------------------------- 额度（billing）

    /**
     * 查询账号额度。
     *
     * xAI 的 `/billing?format=credits` 把数值包在 `{"val": N}` 里（不是裸数字），
     * 周额度窗口在 `config.currentPeriod`。订阅档位另有 `/user?include=subscription`。
     * 两者都拿不到时回退到 JWT 的 tier claim，保证至少能显示套餐名。
     */
    fun refreshCredits(context: Context, accountKey: String): JSONObject {
        val account = loadAccount(context, accountKey) ?: throw IOException("账号不存在：$accountKey")
        return fetchCredits(context, account)
    }

    private fun fetchCredits(context: Context, source: Account): JSONObject {
        val account = headersForFresh(context, source)
        val headers = account
        val payload = executeJson(
            request("${source.backend}/billing?format=credits", "GET", null, headers), allowHttpError = true
        )
        val parsed = parseBilling(payload)
        // 订阅档位单独一次请求：失败不影响额度展示
        val tier = runCatching {
            val user = executeJson(request("${source.backend}/user?include=subscription", "GET", null, headers), true)
            parseSubscriptionTier(user)
        }.getOrNull().orEmpty().ifBlank { subscriptionTierFromJwt(source.auth.optString("accessToken")) }
        if (tier.isNotBlank()) parsed.put("plan_name", tier)

        val hasQuota = parsed.optBoolean("has_quota", false)
        val limit = parsed.optDouble("limit", 0.0)
        val used = parsed.optDouble("used", 0.0)
        val remain = if (limit > 0) (limit - used).coerceAtLeast(0.0) else 0.0
        val expire = parsed.optString("period_end").takeIf { it.isNotBlank() }?.let { epochOrNull(it)?.toDouble() }
        if (hasQuota) {
            store(context).setAccountCredits(source.key, remain, limit, expire, parsed.optJSONArray("packages") ?: JSONArray())
        } else {
            // 上游没给额度数字（免费档常见）：清空旧的额度值并标空，UI 显示"尚未刷新"而非 0/0。
            store(context).clearAccountCredits(source.key)
        }
        return JSONObject()
            .put("uid", source.uid).put("account_key", source.key)
            .put("region", source.region.id).put("region_label", source.region.label)
            .put("nickname", source.nickname)
            .put("remain", remain).put("total", limit).put("used", used)
            .put("used_percent", parsed.optDouble("used_percent", 0.0))
            .put("plan_name", tier)
            .put("period_type", parsed.optString("period_type"))
            .put("period_end", parsed.optString("period_end"))
            .put("expire_at", expire ?: JSONObject.NULL)
            .put("packages", parsed.optJSONArray("packages") ?: JSONArray())
    }

    /** 解析 billing 响应。数值可能是裸数字、字符串，或 `{"val":N}` 包装。 */
    internal fun parseBilling(payload: JSONObject): JSONObject {
        val root = payload.optJSONObject("config") ?: payload
        val out = JSONObject()
        val usedPercent = numberValue(firstValue(root, "creditUsagePercent", "credit_usage_percent"))
            ?: numberValue(firstValue(payload, "creditUsagePercent", "credit_usage_percent")) ?: 0.0
        val monthlyLimit = numberValue(firstValue(root, "monthlyLimit", "monthly_limit")) ?: 0.0
        val used = numberValue(firstValue(root, "used", "totalUsed", "includedUsed")) ?: 0.0
        val onDemandCap = numberValue(firstValue(root, "onDemandCap", "on_demand_cap", "maxAmountPerMonth")) ?: 0.0
        val onDemandUsed = numberValue(firstValue(root, "onDemandUsed", "on_demand_used")) ?: 0.0
        val prepaid = numberValue(firstValue(root, "prepaidBalance", "prepaid_balance")) ?: 0.0

        out.put("used_percent", usedPercent)
        out.put("monthly_limit", monthlyLimit).put("used_raw", used)
        out.put("on_demand_cap", onDemandCap).put("on_demand_used", onDemandUsed)
        out.put("prepaid_balance", prepaid)
        out.put("plan_code", firstNonBlank(
            root.optString("planCode"), root.optString("plan_code"), root.optString("tier"),
            payload.optString("planCode"), payload.optString("subscriptionTier"), payload.optString("tier")
        ))

        // 额度口径：优先月额度，其次按需额度，最后用百分比（周额度）。
        // 关键：只有账单里真的出现 creditUsagePercent / monthlyLimit / onDemandCap / prepaid
        // 任一指标时才算"有额度数据"。免费档的 /billing 响应只有 currentPeriod 而没有这些数字，
        // 此时若强行映射成 100/100 会把"未知"误报成"满额"，必须明确标记为无数据。
        val hasQuota = (usedPercent > 0 || monthlyLimit > 0 || onDemandCap > 0 || prepaid > 0 ||
            firstValue(root, "creditUsagePercent", "credit_usage_percent") != null ||
            firstValue(payload, "creditUsagePercent", "credit_usage_percent") != null ||
            firstValue(root, "monthlyLimit", "monthly_limit", "onDemandCap", "on_demand_cap", "prepaidBalance", "prepaid_balance") != null)
        val limit: Double
        val usedValue: Double
        if (monthlyLimit > 0) { limit = monthlyLimit; usedValue = used }
        else if (onDemandCap > 0) { limit = onDemandCap; usedValue = onDemandUsed }
        else if (usedPercent > 0) { limit = 100.0; usedValue = usedPercent }
        else { limit = 0.0; usedValue = 0.0 }
        out.put("limit", limit).put("used", usedValue).put("has_quota", hasQuota)

        val period = root.optJSONObject("currentPeriod") ?: payload.optJSONObject("currentPeriod")
        if (period != null) {
            out.put("period_type", period.optString("type"))
            out.put("period_start", period.optString("start"))
            out.put("period_end", period.optString("end"))
        } else {
            out.put("period_type", if (usedPercent > 0 || monthlyLimit == 0.0) "USAGE_PERIOD_TYPE_WEEKLY" else "")
            out.put("period_start", firstNonBlank(root.optString("billingPeriodStart"), payload.optString("billingPeriodStart")))
            out.put("period_end", firstNonBlank(root.optString("billingPeriodEnd"), payload.optString("billingPeriodEnd")))
        }

        // 打包成统一的 packages 结构，供 UI 的额度卡片展示
        val packages = JSONArray()
        if (usedPercent > 0 || limit > 0) {
            packages.put(JSONObject()
                .put("name", describePeriod(out.optString("period_type")))
                .put("remaining", (limit - usedValue).coerceAtLeast(0.0))
                .put("total", limit).put("used", usedValue)
                .put("used_percent", usedPercent))
        }
        if (onDemandCap > 0) {
            packages.put(JSONObject().put("name", "按需付费")
                .put("remaining", (onDemandCap - onDemandUsed).coerceAtLeast(0.0))
                .put("total", onDemandCap).put("used", onDemandUsed).put("used_percent", 0.0))
        }
        out.put("packages", packages)
        return out
    }

    private fun describePeriod(type: String): String = when {
        type.contains("WEEKLY", true) -> "周额度"
        type.contains("MONTHLY", true) -> "月额度"
        type.contains("DAILY", true) -> "日额度"
        else -> "额度"
    }

    internal fun parseSubscriptionTier(payload: JSONObject): String = firstNonBlank(
        payload.optString("subscriptionTier"), payload.optString("subscription_tier"),
        payload.optJSONObject("user")?.optString("subscriptionTier").orEmpty(),
        payload.optJSONObject("user")?.optString("subscription_tier").orEmpty()
    )

    /** 无网络时的兜底：直接读 JWT 里的 tier 数值 claim。 */
    internal fun subscriptionTierFromJwt(token: String): String {
        val raw = jwtClaims(token)?.opt("tier") ?: return ""
        val tier = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: return ""
            else -> return ""
        }
        return when (tier) {
            0 -> "free"
            1 -> "supergrok"
            2 -> "x_basic"
            3 -> "x_premium"
            4 -> "x_premium_plus"
            5 -> "supergrok_heavy"
            6 -> "supergrok_lite"
            else -> ""
        }
    }

    private fun jwtClaims(token: String): JSONObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * 账号显示名：优先邮箱；拿不到邮箱时退化成 "Grok 账号 <短ID>"，
     * 而不是把一长串裸 UUID 直接摆到界面上（可读性差，用户也认不出是哪个号）。
     */
    internal fun displayName(email: String, uid: String): String = when {
        email.isNotBlank() -> email
        uid.isBlank() -> "Grok 账号"
        else -> "Grok 账号 " + uid.replace("-", "").take(8)
    }

    /**
     * 用最新一次登录/刷新拿到的 id_token 回填账号身份信息。
     *
     * 早期版本只从 access_token 找邮箱，导致老账号的 nickname 是 UUID。每次刷新 token 时
     * 顺带修正一次，用户无需删除重登就能看到正确邮箱。
     */
    private fun backfillIdentity(root: JSONObject, auth: JSONObject) {
        val idToken = auth.optString("idToken")
        val profile = root.optJSONObject("account") ?: JSONObject().also { root.put("account", it) }
        val uid = firstNonBlank(profile.optString("uid"), profile.optString("user_id"), subjectFromJwt(idToken))
        if (profile.optString("uid").isBlank() && uid.isNotBlank()) profile.put("uid", uid)
        val email = firstNonBlank(
            profile.optString("email"), emailFromJwt(idToken), emailFromJwt(auth.optString("accessToken"))
        )
        if (email.isNotBlank()) {
            profile.put("email", email)
            // 只有当昵称缺失或仍是自动生成的占位名时才覆盖，避免抹掉用户手动改过的名字
            val current = profile.optString("nickname")
            if (current.isBlank() || current.startsWith("Grok 账号")) profile.put("nickname", email)
        }
    }

    private fun subjectFromJwt(token: String): String = jwtClaims(token)?.optString("sub").orEmpty()

    private fun emailFromJwt(token: String): String = jwtClaims(token)?.optString("email").orEmpty()

    // ------------------------------------------------------------- 号池风控 / 农场

    /**
     * 读 access_token 里的 xAI 风控标记。
     *
     * xAI 把 `bot_flag_source`（部分版本叫 `bfs`）写进 JWT claim，**数值 1 = 该账号已被风控标记**。
     * 被标记的号接口仍可能返回 200，但媒体生成等能力会被降级为随机内容，且更容易中途失效，
     * 因此入池与体检都要把它当坏号。社区实证：请求量过大时连付费号也会被标记
     * （Wei-Shaw/sub2api#4578）；chenyme/grok2api 亦专门解析该字段做路由降级。
     */
    internal fun botFlagSource(token: String): Int = RiskLogic.botFlagSource(token)

    /**
     * 上游失败分类（参照 chenyme/grok2api 的 failure.go：把 401/402/403/429/5xx 与
     * "内容安全拒绝""请求级策略拒绝"彻底分开，避免把安全拒绝误判成账号被封）。
     *
     * kind 决定处置：
     *  - `auth`     凭据失效/被拒        → 该号出池待重授权
     *  - `blocked`  账号被上游封锁        → 出池
     *  - `quota`    额度耗尽/付费要求     → 长冷却 + 排恢复探测（不弃号）
     *  - `policy`   内容/请求级策略拒绝   → **软失败**：不冷却、不换号语义、不降健康（只是这次请求不合规）
     *  - `rate`     限流                  → 长冷却（风控类）
     *  - `server`   5xx                   → 短冷却（基础设施抖动）
     *  - `transport`超时/连接失败/空响应   → 短冷却
     *  - `unknown`  其余                  → 短冷却，只计一次软失败
     */
    /** 上游失败分类：纯逻辑在 [RiskLogic]，这里只转发（便于 JVM 单测覆盖）。 */
    fun classifyUpstreamFailure(status: Int, body: String): RiskLogic.UpstreamFailure =
        RiskLogic.classifyUpstreamFailure(status, body)

    /** 分类结果 → 冷却秒数（短冷却=基础设施抖动，长冷却=风控类）。 */
    private fun cooldownSecondsFor(kind: String, settings: JSONObject): Long =
        RiskLogic.cooldownSecondsFor(
            kind,
            settings.optString("cooldown_network_sec", "90").toLongOrNull() ?: 90L,
            settings.optString("cooldown_risk_sec", "1800").toLongOrNull() ?: 1800L,
        )

    /**
     * 按分类结果落库：写冷却（分级）、失败码，并把结论回写 risk_level。
     * kind=policy 直接返回（软失败不改状态）。
     */
    fun applyUpstreamFailure(context: Context, accountKey: String, status: Int, body: String): RiskLogic.UpstreamFailure {
        val f = classifyUpstreamFailure(status, body)
        if (f.kind == "policy") return f
        val settings = store(context).getSettings()
        val secs = cooldownSecondsFor(f.kind, settings)
        val level = when (f.kind) {
            "blocked", "auth" -> "dead"
            "quota", "rate" -> "warning"
            else -> "warning"
        }
        val reason = when (f.kind) {
            "blocked" -> "上游封锁账号（${f.code}）"
            "auth" -> "凭据被拒（${f.code}），需重新授权"
            "quota" -> "额度耗尽（${f.code}），等待恢复探测"
            "rate" -> "上游限流（${f.code}）"
            "server" -> "上游 5xx（${f.code}）"
            "transport" -> "传输异常（${f.code}）"
            else -> "未知失败（${f.code}）"
        }
        store(context).setAccountRisk(accountKey, level, reason, f.code,
            cooldownReason = f.kind,
            nextProbeAt = if (f.kind == "quota") System.currentTimeMillis() / 1000.0 + secs else 0.0)
        if (secs > 0) {
            store(context).setAccountCooldown(accountKey, System.currentTimeMillis() / 1000.0 + secs, f.kind, f.code)
        }
        return f
    }

    /**
     * 额度恢复探测：把"冷却已到期且是额度/限流类"的号重新打一次上游，
     * 成功即清冷却回池（参照 chenyme 的 quota recovery：不靠时间估算硬冻，靠真实探测恢复）。
     */
    fun probeRecoverableAccounts(context: Context, limit: Int = 5): JSONObject {
        val now = System.currentTimeMillis() / 1000.0
        val recovered = JSONArray(); val stillOut = JSONArray()
        var probed = 0
        val accounts = listAccounts(context)
        for (i in 0 until accounts.length()) {
            if (probed >= limit) break
            val meta = accounts.optJSONObject(i) ?: continue
            val key = meta.optString("account_key")
            val reason = meta.optString("cooldown_reason")
            if (reason != "quota" && reason != "rate") continue
            val until = meta.optDouble("cooldown_until", 0.0)
            val nextProbe = meta.optDouble("next_probe_at", 0.0)
            if (until > now) continue                       // 还没到点
            if (nextProbe > 0 && nextProbe > now) continue  // 还没到探测时间
            probed++
            runCatching { refreshCredits(context, key) }.fold(
                onSuccess = {
                    store(context).setAccountCooldown(key, 0.0, "", "")
                    store(context).setAccountRisk(key, "healthy", "额度已恢复（探测通过）")
                    recovered.put(key)
                },
                onFailure = { err ->
                    val f = classifyUpstreamFailure(0, err.message ?: "")
                    val settings = store(context).getSettings()
                    val backoff = (cooldownSecondsFor(f.kind, settings) / 4).coerceAtLeast(60L)
                    store(context).setAccountRisk(key, "warning", "恢复探测失败：${f.code}",
                        f.code, "quota", now + backoff)
                    stillOut.put(JSONObject().put("account_key", key).put("error", f.code))
                }
            )
        }
        return JSONObject().put("ok", true).put("probed", probed)
            .put("recovered", recovered).put("still_out", stillOut)
    }

    /** access_token 是否是可解析的 JWT。解析不了说明凭据已损坏，调度时也拿不到 uid/标记。 */
    internal fun jwtParsable(token: String): Boolean = RiskLogic.jwtParsable(token)

    /** 单账号风控体检（只报告，不改状态）。 */
    private fun accountRiskOf(root: JSONObject, enabled: Boolean, meta: JSONObject): JSONObject {
        val auth = root.optJSONObject("auth") ?: root
        val profile = root.optJSONObject("account") ?: JSONObject()
        val flag = botFlagSource(auth.optString("accessToken"))
        val cooldownUntil = meta.optDouble("cooldown_until", 0.0)
        return JSONObject()
            .put("uid", profile.optString("uid"))
            .put("email", profile.optString("email").ifBlank { profile.optString("nickname") })
            .put("enabled", enabled)
            .put("bot_flag_source", flag)
            .put("flagged", flag == 1)
            .put("has_refresh_token", auth.optString("refreshToken").isNotBlank())
            .put("token_parsable", jwtParsable(auth.optString("accessToken")))
            .put("cooldown_remaining_sec", maxOf(0.0, cooldownUntil - System.currentTimeMillis() / 1000.0))
            .put("failure_count", meta.optInt("failure_count", 0))
    }

    /**
     * 全池风控体检：逐个解 JWT 找 bot_flag_source，并按设置自动停用被标记的号。
     *
     * 这是"号池卫生"的核心动作——被标记的号留在池里只会白吃调度、拉低成功率，还可能因反复失败
     * 触发更多风控。**停用而非删除**，凭据保留便于人工复核。
     */
    fun riskScan(context: Context, disableFlagged: Boolean? = null): JSONObject {
        val settings = store(context).getSettings()
        val autoDisable = disableFlagged
            ?: (settings.optString("farm_disable_on_bot_flag", "1") == "1")
        val accounts = listAccounts(context)
        val results = JSONArray()
        var flagged = 0; var disabled = 0; var noRefresh = 0
        for (i in 0 until accounts.length()) {
            val meta = accounts.optJSONObject(i) ?: continue
            val key = meta.optString("account_key")
            val root = store(context).getAccountRoot(key) ?: continue
            val enabled = meta.optInt("enabled", 1) == 1
            val risk = accountRiskOf(root, enabled, meta)
            if (risk.optBoolean("flagged")) {
                flagged++
                if (autoDisable && enabled) {
                    store(context).setAccountEnabled(key, false)
                    risk.put("action", "disabled")
                    disabled++
                } else {
                    risk.put("action", "report_only")
                }
            }
            if (!risk.optBoolean("has_refresh_token")) noRefresh++
            results.put(risk)
        }
        return JSONObject()
            .put("ok", true)
            .put("total", accounts.length())
            .put("flagged", flagged)
            .put("disabled_now", disabled)
            .put("without_refresh_token", noRefresh)
            .put("auto_disable", autoDisable)
            .put("results", results)
    }

    /**
     * 账号健康检查（号池卫生）。
     *
     * 两级信号：
     *  - **本地信号**（永远查，秒级）：JWT 的 bot_flag_source、refresh_token 是否缺失、
     *    是否被停用、冷却剩余、连续失败次数。
     *  - **上游探测**（`deep=true` 时逐个打上游）：验证凭据是否有效、是否被上游封禁、
     *    还是只是额度耗尽。失败文案按社区口径分类：
     *      · blocked-user / user is blocked → 账号已被封（必须出池）
     *      · 401/403 且无 CF 特征           → 凭据失效（需重新授权）
     *      · 纯 Cloudflare 拦截页           → IP 被盯（换出口即可，**不是号的问题**）
     *      · tokens/limit 之类额度文案       → 额度耗尽（等窗口重置）
     *
     * 严重度：`dead`（必须清理）> `warning`（观察）> `healthy`。
     * 「必须清理」= 被封 / 少了 refresh_token / 带 bot_flag —— 这类号留在池里只会白吃调度、
     * 拉低成功率，还可能因反复失败招来更多风控。
     */
    fun accountHealth(context: Context, deep: Boolean = false): JSONObject {
        val settings = store(context).getSettings()
        val botFlagAction = settings.optString("risk_bot_flag_action", "warn")
        val accounts = listAccounts(context)
        val results = JSONArray()
        var healthy = 0; var warning = 0; var dead = 0
        val deadKeys = JSONArray()
        for (i in 0 until accounts.length()) {
            val meta = accounts.optJSONObject(i) ?: continue
            val key = meta.optString("account_key")
            val root = store(context).getAccountRoot(key) ?: continue
            val risk = accountRiskOf(root, meta.optInt("enabled", 1) == 1, meta)
            val reasons = JSONArray()
            var severity = "healthy"

            fun mark(level: String, reason: String) {
                reasons.put(reason)
                if (level == "dead") severity = "dead"
                else if (level == "warning" && severity != "dead") severity = "warning"
            }

            if (!risk.optBoolean("token_parsable")) mark("dead", "access_token 非法（无法解析 JWT，凭据已损坏）")
            // bot_flag 处置策略（默认 warn）：lij 已证 grok.com 的 botFlagSource 不可靠、
            // chenyme 也只拿它选路由而非弃号 —— 所以默认只标记不判死，避免误杀好号。
            if (risk.optBoolean("flagged")) {
                when (botFlagAction) {
                    "disable" -> mark("dead", "被 xAI 风控标记（bot_flag_source=1，按设置停用）")
                    "ignore" -> Unit
                    else -> mark("warning", "带风控标记（bot_flag_source=1）：建议降级使用，不必然失效")
                }
            }
            if (!risk.optBoolean("has_refresh_token")) mark("dead", "缺少 refresh_token，凭据无法自动续期")
            if (!risk.optBoolean("enabled")) mark("warning", "当前处于停用状态")
            if (risk.optDouble("cooldown_remaining_sec", 0.0) > 0) {
                // 带上原因与失败码：只说"冷却中"用户无法判断该等还是该换号
                val why = listOfNotNull(
                    meta.optString("cooldown_reason").takeIf { it.isNotBlank() }?.let { "原因 $it" },
                    meta.optString("last_failure_code").takeIf { it.isNotBlank() },
                ).joinToString(" / ")
                mark("warning", "冷却中，剩余 ${risk.optLong("cooldown_remaining_sec")}s" +
                    if (why.isNotBlank()) "（$why）" else "（原因未知：早期版本未记录）")
            }
            val failures = risk.optInt("failure_count", 0)
            if (failures >= 3) mark("warning", "连续失败 $failures 次")

            if (deep && meta.optInt("enabled", 1) == 1) {
                runCatching { refreshCredits(context, key) }.fold(
                    onSuccess = { info ->
                        val remain = info.optDouble("remain", 0.0)
                        val total = info.optDouble("total", 0.0)
                        if (total > 0 && remain <= 0) mark("warning", "额度已用尽（等窗口重置）")
                    },
                    onFailure = { err ->
                        // 用统一分类器判定，避免"内容安全拒绝"之类软失败被误判成 dead
                        val status = RiskLogic.statusFromMessage(err.message)
                        val f = classifyUpstreamFailure(status, err.message ?: "")
                        val text = (err.message ?: "").lowercase()
                        if (text.contains("cloudflare") || text.contains("attention required")) {
                            mark("warning", "Cloudflare 拦截：疑似出口 IP 被盯，先换节点再判断")
                        } else when (f.kind) {
                            "policy" -> mark("warning", "上游策略拒绝（${f.code}）：号可用，本次请求不合规")
                            "blocked" -> mark("dead", "上游封锁账号（${f.code}）")
                            "auth" -> mark("dead", "凭据被拒（${f.code}），需重新授权")
                            "quota", "rate" -> mark("warning", "额度/限流（${f.code}），等待恢复探测")
                            else -> mark("warning", "探测失败：${f.code}（${(err.message ?: "").take(60)}）")
                        }
                    }
                )
            }

            if (severity == "dead") { dead++; deadKeys.put(key) }
            else if (severity == "warning") warning++
            else healthy++

            val reasonText = (0 until reasons.length()).joinToString("；") { reasons.optString(it) }
            store(context).setAccountRisk(key, severity, reasonText,
                failureCode = risk.optString("last_failure_code"))
            results.put(risk.put("severity", severity).put("reasons", reasons).put("account_key", key))
        }
        return JSONObject()
            .put("ok", true)
            .put("checked", accounts.length())
            .put("deep", deep)
            .put("healthy", healthy).put("warning", warning).put("dead", dead)
            .put("need_cleanup", dead > 0)
            .put("cleanup_keys", deadKeys)
            .put("results", results)
    }

    /**
     * 降智探测（参照 lij768423-svg/grok-register-panel 的 quality_probe）。
     *
     * 免费档被风控后常见表现不是报错，而是**"降智"**：回答变短、不再走推理。
     * 所以判定质量要看"真实短对话里有没有 reasoning token"，而不是看 JWT 标记
     * （后者已被证明不可靠）。四档结论：
     *   healthy 有推理且正常返回 / risk 无推理（疑似降智）/ error 请求失败 / unknown 判不了
     */
    fun qualityProbe(context: Context, accountKey: String): JSONObject {
        val model = store(context).getSettings().optString("quality_probe_model", "grok-4.6")
        val started = System.currentTimeMillis()
        return try {
            val payload = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("max_tokens", 64)
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with exactly: pong")))
            // 复用现有上游调用原语：loadAccount → headersForFresh（含到期自动刷新）→ request → executeJson
            val account = loadAccount(context, accountKey)
                ?: throw IOException("账号不存在：$accountKey")
            val headers = headersForFresh(context, account)
            val raw = executeJson(request("${account.backend}/chat/completions", "POST", payload, headers))
            val usage = raw.optJSONObject("usage") ?: JSONObject()
            val details = usage.optJSONObject("completion_tokens_details") ?: JSONObject()
            val reasoning = details.optInt("reasoning_tokens", 0)
            val content = raw.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val level = if (reasoning > 0) "healthy" else "risk"
            store(context).setAccountRisk(accountKey, level,
                if (reasoning > 0) "降智探测：有推理（${reasoning} reasoning tokens）"
                else "降智探测：无推理输出，疑似降智")
            JSONObject().put("ok", true).put("account_key", accountKey)
                .put("verdict", if (reasoning > 0) "healthy" else "risk")
                .put("reasoning_tokens", reasoning)
                .put("content_head", content.take(60))
                .put("elapsed_ms", System.currentTimeMillis() - started)
        } catch (e: Exception) {
            // 探测失败也走统一分类器：否则额度耗尽会被笼统记成"探测失败"，
            // 拿不到恢复探测排期（真机实测踩到：429 free-usage-exhausted 被记成未知失败）。
            val status = RiskLogic.statusFromMessage(e.message)
            val f = RiskLogic.classifyUpstreamFailure(status, e.message ?: "")
            val level = if (f.isAccountLevel) "dead" else "warning"
            val now = System.currentTimeMillis() / 1000.0
            store(context).setAccountRisk(
                accountKey, level, "推理探测失败：${f.code}",
                failureCode = f.code,
                cooldownReason = f.kind,
                nextProbeAt = if (f.isQuotaLike) now + 1800 else 0.0,
            )
            JSONObject().put("ok", false).put("account_key", accountKey)
                .put("verdict", "error").put("code", f.code).put("kind", f.kind)
                .put("error", e.message?.take(200))
                .put("elapsed_ms", System.currentTimeMillis() - started)
        }
    }

    /** 对号池批量降智探测（最多 limit 个，优先未体检的）。 */
    fun qualityProbeAll(context: Context, limit: Int = 5): JSONObject {
        val out = JSONArray()
        val accounts = listAccounts(context)
        var n = 0
        for (i in 0 until accounts.length()) {
            if (n >= limit) break
            val meta = accounts.optJSONObject(i) ?: continue
            if (meta.optInt("enabled", 1) != 1) continue
            val key = meta.optString("account_key")
            out.put(qualityProbe(context, key))
            n++
        }
        return JSONObject().put("ok", true).put("probed", n).put("results", out)
    }

    /** 号池概览（面板顶部卡片）：各状态计数 + 冷却/探测/额度汇总。 */
    fun poolSummary(context: Context): JSONObject {
        val accounts = listAccounts(context)
        var healthy = 0; var warning = 0; var dead = 0; var unchecked = 0
        var disabled = 0; var cooling = 0; var quotaOut = 0; var flagged = 0
        val now = System.currentTimeMillis() / 1000.0
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            when (a.optString("risk_level")) {
                "dead" -> dead++; "warning" -> warning++; "healthy" -> healthy++; else -> unchecked++
            }
            if (a.optInt("enabled", 1) != 1) disabled++
            if (a.optDouble("cooldown_until", 0.0) > now) cooling++
            if (a.optString("cooldown_reason") == "quota") quotaOut++
            if (a.optInt("bot_risk", 0) == 1) flagged++
        }
        val total = accounts.length()
        // 可用率口径：可调度（启用且不在冷却）占启用号的比例——面板只看这个数就能判断池子健康
        var usable = 0
        for (i in 0 until total) {
            val a = accounts.optJSONObject(i) ?: continue
            if (a.optInt("enabled", 1) == 1 && a.optDouble("cooldown_until", 0.0) <= now) usable++
        }
        val quota = quotaOverview(context)
        var used = 0L; var limit = 0L
        for (i in 0 until quota.length()) {
            val q = quota.optJSONObject(i) ?: continue
            if (q.optBoolean("enabled", true)) {
                used += q.optLong("used"); limit += q.optLong("limit")
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("total", total).put("healthy", healthy).put("warning", warning)
            .put("dead", dead).put("unchecked", unchecked)
            .put("disabled", disabled).put("cooling", cooling)
            .put("quota_exhausted", quotaOut).put("bot_flagged", flagged)
            .put("usable", usable)
            .put("usable_ratio", if (total > 0) usable.toDouble() / total else 0.0)
            .put("tokens_used", used).put("tokens_limit", limit)
            .put("tokens_remaining", (limit - used).coerceAtLeast(0))
    }

    /** 批量删除账号（健康检查后清理用）。返回真正删掉的 account_key。 */
    fun deleteAccounts(context: Context, keys: List<String>): JSONArray {
        val removed = JSONArray()
        keys.forEach { if (store(context).deleteAccount(it)) removed.put(it) }
        return removed
    }

    /**
     * 清除冷却（把号立刻放回池子）。
     *
     * 用途有二：① 误冷却的补救（例如客户端错误被误判成账号故障）；
     * ② 确认上游已恢复时手动提前放行。**只清冷却与风控标注，不动凭据**。
     */
    fun clearCooldowns(context: Context, keys: List<String>): Int {
        var n = 0
        val targets = if (keys.isEmpty()) store(context).accountKeys() else keys
        targets.forEach { key ->
            if (store(context).clearAccountCooldown(key)) {
                store(context).setAccountRisk(key, "", "")
                n++
            }
        }
        return n
    }

    /** 批量停用/启用账号，返回影响条数。 */
    fun setAccountsEnabled(context: Context, keys: List<String>, enabled: Boolean): Int {
        var n = 0
        keys.forEach { if (store(context).setAccountEnabled(it, enabled)) n++ }
        return n
    }

    /** 农场运行状态：容量、节流余量、累计导入。供 PC 侧管道与界面展示。 */
    fun farmStatus(context: Context): JSONObject {
        val settings = store(context).getSettings()
        val pool = listAccounts(context).length()
        val maxAccounts = settings.optString("farm_max_accounts", "60").toIntOrNull() ?: 60
        val gap = settings.optString("farm_import_min_gap_sec", "300").toLongOrNull() ?: 300L
        val lastImport = settings.optString("farm_last_import_ts", "0").toDoubleOrNull() ?: 0.0
        val nowSec = System.currentTimeMillis() / 1000.0
        val sinceLast = if (lastImport > 0) nowSec - lastImport else -1.0
        val remaining = if (lastImport > 0) maxOf(0.0, gap - sinceLast) else 0.0
        return JSONObject()
            .put("ok", true)
            .put("pool_count", pool)
            .put("max_accounts", maxAccounts)
            .put("capacity_left", maxOf(0, maxAccounts - pool))
            .put("import_gap_sec", gap)
            .put("seconds_since_last_import", if (sinceLast < 0) JSONObject.NULL else sinceLast.toLong())
            .put("throttle_remaining_sec", remaining.toLong())
            .put("can_import_now", remaining <= 0.0 && pool < maxAccounts)
            .put("imported_total", settings.optString("farm_imported_total", "0").toIntOrNull() ?: 0)
            // 可用 = 已启用且不在冷却期（与概览页号池健康、补号自检同口径）
            .put("available", run {
                var n = 0
                val now = System.currentTimeMillis() / 1000.0
                for (i in 0 until listAccounts(context).length()) {
                    val a = listAccounts(context).getJSONObject(i)
                    if (a.optBoolean("enabled", true) && a.optDouble("cooldown_until", 0.0) <= now) n++
                }
                n
            })
            .put("reject_bot_flag", settings.optString("farm_reject_bot_flag", "1") == "1")
            .put("auto_disable_on_bot_flag", settings.optString("farm_disable_on_bot_flag", "1") == "1")
    }

    /**
     * PC 侧自动注册管道专用的导入守卫：容量上限 → 节流间隔 → 逐个查 bot_flag → 落库。
     *
     * 相比裸 [importAccounts] 就多这三道闸。触发节流时返回 `reason=throttled` 与还需等待秒数，
     * 让调用方（farm.py）自行 sleep 重试，而不是当成失败退出。
     */
    fun importAccountsGuarded(context: Context, documents: JSONArray): JSONObject {
        val settings = store(context).getSettings()
        val pool = listAccounts(context).length()
        val maxAccounts = settings.optString("farm_max_accounts", "60").toIntOrNull() ?: 60
        if (pool >= maxAccounts) {
            return JSONObject().put("ok", false).put("reason", "pool_full")
                .put("pool_count", pool).put("max_accounts", maxAccounts)
                .put("imported", JSONArray()).put("rejected", JSONArray())
        }
        val gap = settings.optString("farm_import_min_gap_sec", "300").toLongOrNull() ?: 300L
        val lastImport = settings.optString("farm_last_import_ts", "0").toDoubleOrNull() ?: 0.0
        val nowSec = System.currentTimeMillis() / 1000.0
        val since = if (lastImport > 0) nowSec - lastImport else Double.MAX_VALUE
        if (since < gap) {
            return JSONObject().put("ok", false).put("reason", "throttled")
                .put("retry_after_sec", (gap - since).toLong())
                .put("import_gap_sec", gap)
                .put("imported", JSONArray()).put("rejected", JSONArray())
        }

        val rejectFlagged = settings.optString("farm_reject_bot_flag", "1") == "1"
        val cleaned = JSONArray(); val preRejected = JSONArray()
        for (i in 0 until documents.length()) {
            val raw = when (val item = documents.opt(i)) {
                is JSONObject -> item
                is String -> runCatching { JSONObject(item) }.getOrNull()
                else -> null
            }
            if (raw == null) {
                preRejected.put(JSONObject().put("index", i).put("error", "无效 JSON")); continue
            }
            val normalized = runCatching { normalizeImportedAccount(raw) }.getOrNull()
            val token = normalized?.optJSONObject("auth")?.optString("accessToken").orEmpty()
            if (rejectFlagged && botFlagSource(token) == 1) {
                preRejected.put(JSONObject().put("index", i)
                    .put("error", "bot_flag_source=1（已被 xAI 风控标记，弃号不入池）"))
                continue
            }
            cleaned.put(raw)
        }
        val result = importAccounts(context, cleaned)
        val imported = result.optJSONArray("imported") ?: JSONArray()
        val rejected = result.optJSONArray("rejected") ?: JSONArray()
        for (i in 0 until preRejected.length()) rejected.put(preRejected.getJSONObject(i))
        if (imported.length() > 0) {
            val total = (settings.optString("farm_imported_total", "0").toIntOrNull() ?: 0) + imported.length()
            store(context).saveSettings(JSONObject()
                .put("farm_last_import_ts", (System.currentTimeMillis() / 1000).toString())
                .put("farm_imported_total", total.toString()))
        }
        return JSONObject()
            .put("ok", imported.length() > 0)
            .put("reason", if (imported.length() > 0) "imported" else "nothing_imported")
            .put("imported", imported).put("rejected", rejected)
            .put("skipped_flagged", preRejected.length())
            .put("pool_count", listAccounts(context).length())
    }

    private fun epochOrNull(text: String): Long? {
        runCatching { java.time.Instant.parse(text).toEpochMilli() / 1000 }.onSuccess { return it }
        return text.toLongOrNull()
    }

    fun creditsSummary(context: Context): JSONObject {
        val accounts = listAccounts(context)
        var remain = 0.0; var total = 0.0; var known = 0
        for (i in 0 until accounts.length()) {
            val item = accounts.getJSONObject(i)
            if (!item.isNull("credits_remaining")) {
                remain += item.optDouble("credits_remaining")
                total += item.optDouble("credits_total")
                known++
            }
        }
        return JSONObject().put("accounts", accounts.length()).put("known", known)
            .put("remain", remain).put("total", total)
    }

    fun refreshAllCredits(context: Context): JSONObject {
        val accounts = listAccounts(context)
        if (accounts.length() == 0) throw IOException("尚未导入 Grok 账号")
        val results = JSONArray()
        var succeeded = 0
        for (i in 0 until accounts.length()) {
            val item = accounts.getJSONObject(i)
            val uid = item.getString("uid")
            val accountKey = item.optString("account_key", uid)
            val nickname = item.optString("nickname", uid)
            runCatching { refreshCredits(context, accountKey) }
                .onSuccess { results.put(JSONObject(it.toString()).put("nickname", nickname).put("ok", true)); succeeded++ }
                .onFailure {
                    results.put(JSONObject().put("uid", uid).put("account_key", accountKey)
                        .put("region", item.optString("region")).put("region_label", item.optString("region_label"))
                        .put("nickname", nickname).put("ok", false).put("message", it.message ?: "刷新失败"))
                }
        }
        if (succeeded == 0) {
            val errors = (0 until results.length()).joinToString("；") {
                val item = results.getJSONObject(it); "${item.optString("nickname")}：${item.optString("message")}"
            }
            throw IOException(errors.ifBlank { "全部账号额度刷新失败" })
        }
        // 顺带做一次"额度恢复探测"：把冷却已到期、且属于额度/限流类的号试着拉回池子。
        // 参照 chenyme 的 quota recovery —— 不靠时间估算硬冻，靠真实探测恢复。
        val recovery = runCatching { probeRecoverableAccounts(context, limit = 5) }.getOrNull()

        return JSONObject().put("results", results).put("recovery", recovery ?: JSONObject()).put("summary", creditsSummary(context))
    }

    /**
     * xAI 没有签到。保留该方法签名，统一返回「不支持签到 + 已刷新额度」，
     * 这样 UI 与定时任务都能原样复用，只是语义变成"刷新额度"。
     */
    fun checkIn(context: Context, accountKey: String): JSONObject {
        val account = loadAccount(context, accountKey) ?: throw IOException("账号不存在：$accountKey")
        val credits = refreshCredits(context, accountKey)
        return JSONObject(credits.toString())
            .put("uid", account.uid).put("account_key", account.key)
            .put("region", account.region.id).put("region_label", account.region.label)
            .put("nickname", account.nickname)
            .put("ok", true).put("already", false).put("checkin_supported", false)
            .put("credits_refreshed", true)
            .put("message", "Grok 额度由上游滚动发放，已刷新当前用量")
    }

    fun checkInAll(context: Context): JSONObject {
        val accounts = listAccounts(context)
        if (accounts.length() == 0) throw IOException("尚未导入 Grok 账号")
        val results = JSONArray()
        for (i in 0 until accounts.length()) {
            val item = accounts.getJSONObject(i)
            val uid = item.getString("uid")
            val accountKey = item.optString("account_key", uid)
            val nickname = item.optString("nickname", uid)
            if (!item.optBoolean("enabled", true)) {
                results.put(JSONObject().put("uid", uid).put("account_key", accountKey)
                    .put("region", item.optString("region")).put("region_label", item.optString("region_label"))
                    .put("nickname", nickname).put("ok", false).put("skipped", true)
                    .put("checkin_supported", false).put("message", "账号已停用，已跳过"))
                continue
            }
            runCatching { checkIn(context, accountKey) }
                .onSuccess { results.put(it) }
                .onFailure {
                    results.put(JSONObject().put("uid", uid).put("account_key", accountKey)
                        .put("region", item.optString("region")).put("region_label", item.optString("region_label"))
                        .put("nickname", nickname).put("ok", false).put("checkin_supported", false)
                        .put("message", it.message ?: "额度刷新失败"))
                }
        }
        return JSONObject().put("results", results)
    }

    // ------------------------------------------------------------- OAuth 设备流

    /**
     * 第一步：申请设备码。
     *
     * 返回的 [OAuthSession.state] 承载 device_code（沿用原字段名，避免改动调用方轮询逻辑），
     * [OAuthSession.authUrl] 是用户要打开的授权页（已带 user_code，可直接打开）。
     */
    fun beginOAuth(region: AccountRegion): OAuthSession {
        val form = FormBody.Builder()
            .add("client_id", XaiProtocol.OAUTH_CLIENT_ID)
            .add("scope", XaiProtocol.OAUTH_SCOPE)
            .add("referrer", "grok-build")
            .build()
        val request = Request.Builder().url(XaiProtocol.DEVICE_CODE_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("x-grok-client-version", XaiProtocol.CLIENT_VERSION)
            .header("x-grok-client-surface", "ui")
            .post(form).build()
        return controlHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("申请设备码失败 HTTP ${response.code}：${text.take(200)}")
            val payload = runCatching { JSONObject(text) }
                .getOrElse { throw IOException("设备码接口返回非 JSON：${text.take(160)}") }
            val deviceCode = payload.optString("device_code")
            val userCode = payload.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) throw IOException("设备码接口返回字段不完整")
            val verify = payload.optString("verification_uri").ifBlank { XaiProtocol.DEVICE_VERIFY_URL }
            val complete = payload.optString("verification_uri_complete")
                .ifBlank { "$verify?user_code=${java.net.URLEncoder.encode(userCode, "UTF-8")}" }
            val interval = payload.optLong("interval", 5L).let { if (it > 0) it else 5L } * 1000L
            val expiresIn = payload.optLong("expires_in", 1800L).let { if (it > 0) it else 1800L }
            OAuthSession(
                region = AccountRegion.XAI, state = deviceCode, authUrl = complete, userCode = userCode,
                intervalMs = interval, expiresAt = System.currentTimeMillis() + expiresIn * 1000L
            )
        }
    }

    /** 轮询结果三态：拿到账号 / 还需继续等 / 拉长间隔 / 终结。 */
    sealed class OAuthPoll {
        data class Success(val account: Account) : OAuthPoll()
        object Pending : OAuthPoll()
        object SlowDown : OAuthPoll()
        data class Failed(val message: String) : OAuthPoll()
    }

    /**
     * 第二步：轮询换取 token。
     *
     * RFC 8628 约定：未授权时返回 `authorization_pending`（继续等）、
     * 轮询过快返回 `slow_down`（拉长间隔）、`expired_token` / `access_denied` 则终结。
     * 旧实现用 null 表示"还没好"，这里显式区分状态，避免把致命错误当成继续等待。
     */
    fun pollOAuth(context: Context, session: OAuthSession): OAuthPoll {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", XaiProtocol.OAUTH_CLIENT_ID)
            .add("device_code", session.state)
            .build()
        val request = Request.Builder().url(XaiProtocol.TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("x-grok-client-version", XaiProtocol.CLIENT_VERSION)
            .header("x-grok-client-surface", "ui")
            .post(form).build()
        val (status, text) = controlHttp.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        val payload = runCatching { JSONObject(text) }.getOrNull()
        if (status in 200..299) {
            val access = payload?.optString("access_token").orEmpty()
            if (access.isBlank()) return OAuthPoll.Failed("授权成功但响应缺少 access_token")
            val expiresIn = payload?.optLong("expires_in", 3600L)?.let { if (it > 0) it else 3600L } ?: 3600L
            val idToken = payload?.optString("id_token").orEmpty()
            val token = JSONObject()
                .put("accessToken", access)
                .put("refreshToken", payload?.optString("refresh_token").orEmpty())
                .put("clientId", XaiProtocol.OAUTH_CLIENT_ID)
                .put("domain", AccountRegion.XAI.defaultDomain)
                .put("expiresAt", System.currentTimeMillis() + expiresIn * 1000L)
            if (idToken.isNotBlank()) token.put("idToken", idToken)
            val userId = firstNonBlank(
                payload?.optString("user_id").orEmpty(), subjectFromJwt(access), subjectFromJwt(idToken)
            )
            // 邮箱在 id_token（OIDC 标准位置）里，access_token 通常不带 email claim——
            // 只查 access_token 会让昵称退化成裸 UUID。
            val email = firstNonBlank(
                payload?.optString("email").orEmpty(), emailFromJwt(idToken), emailFromJwt(access)
            )
            if (userId.isBlank()) return OAuthPoll.Failed("授权成功但无法识别账号标识")
            val root = JSONObject()
                .put("region", AccountRegion.XAI.id)
                .put("auth", token)
                .put("account", JSONObject().put("uid", userId).put("user_id", userId)
                    .put("email", email).put("nickname", displayName(email, userId)))
            return OAuthPoll.Success(saveAccount(context, root, AccountRegion.XAI))
        }
        return when (payload?.optString("error").orEmpty()) {
            "authorization_pending" -> OAuthPoll.Pending
            "slow_down" -> OAuthPoll.SlowDown
            "expired_token" -> OAuthPoll.Failed("授权码已过期，请重新发起授权")
            "access_denied" -> OAuthPoll.Failed("授权被拒绝")
            "invalid_grant", "invalid_request" -> OAuthPoll.Failed("授权已失效，请重新发起")
            else -> {
                // 网络中间设备可能返回 HTML/空响应；5xx 与网络类错误按待定处理，其余终结
                if (status in 500..599 || status == 0) OAuthPoll.Pending
                else OAuthPoll.Failed(friendlyOAuthError(text).ifBlank { "HTTP $status" })
            }
        }
    }

    /** 后台线程池入口，供 UI 以 fire-and-forget 方式发起轮询。 */
    fun submitOAuth(task: Runnable) {
        runCatching { oauthPool.execute(task) }
    }

    // --------------------------------------------------------- 上游推理请求构造

    /**
     * 把内部的 OpenAI Chat 请求体转成上游 `/responses` 请求。
     *
     * 这是"选号 → 刷新 token → 组请求"的唯一入口：调用方（ApiHostService）
     * 只需拿到 [UpstreamCall] 直接 execute，不需要关心上游协议差异。
     */
    /**
     * 客户端错误守卫：请求的模型不在已知目录里时直接判客户端错误。
     *
     * 为什么必须前置拦：实测发现**请求一个不存在的模型名**时，上游对每个账号都返回
     * `personal-team-blocked:spending-limit`，而网关把它当成账号额度耗尽 →
     * 一次请求顺着重试链把 8 个号全打进 6h 冷却（可用率 94%→44%）。
     * 模型名错是客户端问题，不该由账号买单，所以这里先校验、直接 400 返回。
     */
    fun rejectUnknownModel(context: Context, requested: String): String? {
        if (requested.isBlank()) return null
        val known = runCatching { modelsCached(context) }.getOrNull() ?: return null
        if (known.length() == 0) return null          // 目录还没就绪：不拦，交给上游判
        for (i in 0 until known.length()) {
            val id = known.optJSONObject(i)?.optString("id").orEmpty()
            if (id == requested) return null
        }
        // 常见别名/前缀（如 grok-4.6-low、-high、-medium、-xhigh）在目录里已展开；
        // 仍不匹配则视为未知模型
        return "未知模型 \"$requested\"：不在当前可用目录中（未消耗任何账号额度）"
    }

    fun upstreamRequest(context: Context, body: JSONObject, requiredRegion: AccountRegion, cacheKey: String? = null): UpstreamCall {
        val account = accountForRequest(context, requiredRegion)
        val converted = XaiProtocol.chatToResponses(body)
        val model = converted.optString("model")
        // 会话键优先用客户端显式给的 prompt_cache_key / user，缺失时不发 session 头
        val sessionKey = firstNonBlank(
            body.optString("prompt_cache_key"), body.optString("user"),
            body.optJSONObject("metadata")?.optString("session_id").orEmpty(), cacheKey.orEmpty()
        )
        val headers = inferenceHeaders(account, model, sessionKey.ifBlank { null })
        // 稳定会话键随请求下发，让上游按同一会话做 prompt cache
        if (sessionKey.isNotBlank()) converted.put("prompt_cache_key", sessionKey)
        return UpstreamCall(request("${account.backend}/responses", "POST", converted, headers), account)
    }

    fun request(url: String, method: String, body: JSONObject?, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (v.isNotEmpty()) builder.header(k, v) }
        return if (method.equals("GET", true)) builder.get().build()
        else builder.method(method.uppercase(), (body ?: JSONObject()).toString().toRequestBody(JSON)).build()
    }

    fun executeJson(request: Request, allowHttpError: Boolean = false): JSONObject {
        controlHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!allowHttpError && !response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(240)}")
            return runCatching { JSONObject(text) }
                .getOrElse { throw IOException("服务器返回非 JSON：${text.take(160)}") }
        }
    }

    // ------------------------------------------------------------------ 工具方法

    private fun headersForFresh(context: Context, account: Account): MutableMap<String, String> {
        val expiry = normalizedExpiryMillis(account.auth)
        return headersFor(if (expiry > 0 && System.currentTimeMillis() >= expiry - 120_000L) refreshToken(context, account) else account)
    }

    /** expiresAt 兼容秒/毫秒两种口径：小于 1e10 视为秒。 */
    private fun normalizedExpiryMillis(auth: JSONObject): Long {
        val raw = auth.optLong("expiresAt", 0L)
        return if (raw in 1..9_999_999_999L) raw * 1000L else raw
    }

    private fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences("native", Context.MODE_PRIVATE)
        val raw = prefs.getString("account", null) ?: return
        runCatching { saveAccount(context, JSONObject(raw)) }
    }

    private fun firstValue(root: JSONObject, vararg keys: String): Any? {
        for (key in keys) if (root.has(key) && !root.isNull(key)) return root.opt(key)
        return null
    }

    /** xAI 的数值字段常有 `{"val": N}` 包装，也接受裸数字与数字字符串。 */
    internal fun numberValue(value: Any?): Double? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        is JSONObject -> numberValue(value.opt("val"))
        else -> null
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    // ------------------------------------------------------------------ 存储转发

    fun logUsage(context: Context, record: JSONObject): Long = store(context).logUsage(record)
    fun usageSummary(context: Context): JSONObject = store(context).usageSummary()
    fun usageRecent(context: Context, page: Int = 1, pageSize: Int = 20, protocol: String? = null, model: String? = null, appName: String? = null, status: String? = null, light: Boolean = true): JSONObject =
        store(context).usageRecent(page, pageSize, protocol, model, appName, status, light)
    fun usageDetail(context: Context, id: Long): JSONObject? = store(context).getUsage(id)
    fun usageFilters(context: Context): JSONObject = store(context).usageFilters()
    fun usageTimeseries(context: Context, granularity: String = "hour", points: Int = 24, model: String? = null): JSONArray =
        store(context).usageTimeseries(granularity, points, model)
    fun trimUsage(context: Context, inputLimit: Int = 4000, outputLimit: Int = 8000, reasoningLimit: Int = 8000): Int =
        store(context).trimUsageContent(inputLimit, outputLimit, reasoningLimit)
    fun cleanupUsage(context: Context, retentionDays: Int): Int = store(context).cleanupUsage(retentionDays)
    fun storageInfo(context: Context): JSONObject = store(context).storageInfo()
    fun vacuum(context: Context) = store(context).vacuum()

    fun dirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    // ------------------------------------------------------------ 额度耗尽与余额

    /**
     * 上游额度耗尽/拒绝的分类结果。
     *
     * [retryable] 为 true 表示"换一个账号再试有希望成功"：
     * 免费额度耗尽、付费限额阻断、以及 429/5xx 都属于此类；参数错误等则不重试。
     */
    data class Exhaustion(
        val kind: Kind,
        val retryable: Boolean,
        val cooldownSeconds: Long,
        val usedTokens: Long = -1,
        val limitTokens: Long = -1,
        val message: String = ""
    ) {
        enum class Kind { NONE, FREE_QUOTA, MODEL_QUOTA, SPENDING_LIMIT, RATE_LIMIT, SERVER, AUTH, BLOCKED }

        companion object {
        /** 冷却原因（写库给面板看）。与 RiskLogic 的 kind 口径保持一致，便于统一分析。 */
        fun reasonOf(kind: Kind): String = when (kind) {
            Kind.FREE_QUOTA, Kind.MODEL_QUOTA, Kind.SPENDING_LIMIT -> "quota"
            Kind.RATE_LIMIT -> "rate"
            Kind.SERVER -> "server"
            Kind.AUTH -> "auth"
            Kind.BLOCKED -> "blocked"
            Kind.NONE -> "unknown"
        }

        /** 失败机器码（面板上显示的"为什么不可用"）。 */
        fun codeOf(kind: Kind): String = when (kind) {
            Kind.FREE_QUOTA -> "free_usage_exhausted"
            Kind.MODEL_QUOTA -> "model_quota_exhausted"
            Kind.SPENDING_LIMIT -> "spending_limit"
            Kind.RATE_LIMIT -> "upstream_rate_limited"
            Kind.SERVER -> "upstream_server_error"
            Kind.AUTH -> "upstream_unauthorized"
            Kind.BLOCKED -> "account_blocked"
            Kind.NONE -> "unknown"
        }
        }
    }

    /**
     * 判定上游响应是否属于"额度耗尽/该换号"。
     *
     * 识别串取自 grok2api（Go 版）的实测文案，例如：
     *  - `subscription:free-usage-exhausted`
     *  - `You've used all the included free usage for model grok-4.5-build-free for now.
     *     Usage resets over a rolling 24-hour window — tokens (actual/limit): 537365/500000.`
     *  - `personal-team-blocked:spending-limit`
     *
     * 命中时顺带解析出**上游确认的真实 used/limit** 与重置间隔——
     * 这是免费档唯一能拿到的权威额度数字，用于覆盖本地估算。
     */
    fun classifyExhaustion(rawBody: String, status: Int, retryAfterHeader: String? = null): Exhaustion {
        val text = rawBody.lowercase()
        val message = runCatching { JSONObject(rawBody) }.getOrNull()?.let { payload ->
            payload.optString("error").ifBlank {
                payload.optJSONObject("error")?.optString("message").orEmpty()
            }.ifBlank { payload.optString("message") }
        }.orEmpty().ifBlank { rawBody.take(240) }

        val freeQuota = text.contains("subscription:free-usage-exhausted")
        val modelQuota = text.contains("used all the included free usage for model")
        val spendingLimit = text.contains("personal-team-blocked:spending-limit")
        // 账号级封禁（官方 grok2api 同款判定）：blocked-user / user is blocked。
        // 与普通 403 不同——这是账号被上游封了，换号重试才有意义，且该号要长冷却出池。
        val blocked = text.contains("blocked-user") || text.contains("user is blocked")

        // 真实用量：`tokens (actual/limit): 537365/500000`
        var used = -1L
        var limit = -1L
        TOKEN_PAIR.find(rawBody)?.let { match ->
            used = match.groupValues[1].toLongOrNull() ?: -1L
            limit = match.groupValues[2].toLongOrNull() ?: -1L
        }

        val retryAfter = parseRetryAfter(retryAfterHeader)
            ?: parseResetWindow(rawBody)

        return when {
            blocked -> Exhaustion(
                Exhaustion.Kind.BLOCKED, true, 24 * 3600L, used, limit, message
            )
            spendingLimit -> Exhaustion(
                Exhaustion.Kind.SPENDING_LIMIT, true,
                retryAfter ?: 6 * 3600L, used, limit, message
            )
            freeQuota || modelQuota -> Exhaustion(
                if (modelQuota && !freeQuota) Exhaustion.Kind.MODEL_QUOTA else Exhaustion.Kind.FREE_QUOTA,
                true,
                // 免费额度是 24h 滚动窗口；拿不到 resets in 就按剩余窗口保守估计
                retryAfter ?: DEFAULT_FREE_WINDOW_SECONDS, used, limit, message
            )
            status == 429 -> Exhaustion(
                Exhaustion.Kind.RATE_LIMIT, true, retryAfter ?: 60L, used, limit, message
            )
            status in 500..599 -> Exhaustion(
                Exhaustion.Kind.SERVER, true, 15L, used, limit, message
            )
            status == 401 || status == 403 -> Exhaustion(
                Exhaustion.Kind.AUTH, false, 0L, used, limit, message
            )
            else -> Exhaustion(Exhaustion.Kind.NONE, false, 0L, used, limit, message)
        }
    }

    /** 解析 `Retry-After` 响应头（秒数或 HTTP 日期）。 */
    private fun parseRetryAfter(header: String?): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { if (it > 0) return it }
        return runCatching {
            val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            (at.toEpochSecond() - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** 解析 `resets in: 1d2h3m4s` 形式的重置间隔。 */
    private fun parseResetWindow(body: String): Long? {
        val match = RESET_WINDOW.find(body) ?: return null
        var total = 0L
        val groups = listOf("d" to 86400L, "h" to 3600L, "m" to 60L, "s" to 1L)
        groups.forEachIndexed { index, (_, multiplier) ->
            val raw = match.groupValues[index + 1]
            if (raw.isNotEmpty()) total += (raw.toLongOrNull() ?: 0L) * multiplier
        }
        return total.takeIf { it > 0 }
    }

    /**
     * 计算某账号当前的余额视图。
     *
     * **已用量始终取本地实时统计**（滚动窗口内 usage_logs 的 token 累加），因为这是唯一会随每次
     * 调用变化、且不受历史污染的数字。上游确认值（`quota_confirmed_used`）只作为并列参考项输出，
     * 不再接管 `used` ——早先让它接管会导致一个账号被拒绝过一次之后，页面数字**永远冻结**在那个
     * 确认值上（用户观感："额度一直不变"）。
     *
     * 上限优先取上游确认过的 `quota_limit_tokens`（这是稳定的、值得信任的），
     * 其次取设置里的 `free_token_limit`（默认 500000，与官方 Go 版 estimatedFreeTokenLimit 一致）。
     */
    fun quotaView(context: Context, row: JSONObject, windowSeconds: Long = DEFAULT_FREE_WINDOW_SECONDS): JSONObject {
        val uid = row.optString("uid")
        val defaultLimit = settings(context).optString("free_token_limit", "500000")
            .toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_FREE_TOKEN_LIMIT
        val confirmedUsed = if (row.isNull("quota_confirmed_used")) -1L else row.optLong("quota_confirmed_used", -1L)
        val storedLimit = if (row.isNull("quota_limit_tokens")) -1L else row.optLong("quota_limit_tokens", -1L)
        val windowTokens = row.optLong("window_tokens", 0L)

        val limit = if (storedLimit > 0) storedLimit else defaultLimit
        // 实时值优先：本地统计每次都随新调用增长，页面才会动。
        val used = windowTokens
        val remaining = (limit - used).coerceAtLeast(0L)
        val percent = if (limit > 0) (used.toDouble() / limit * 100).coerceIn(0.0, 100.0) else 0.0
        // 上游确认值只作参考：它比本地统计更权威（含未经过网关的用量），但不会自我更新。
        val confirmed = confirmedUsed >= 0

        // 重置时刻：滚动窗口 = 窗口内最早一条记录 + 窗口长度
        val firstTs = if (row.isNull("first_ts")) 0.0 else row.optDouble("first_ts", 0.0)
        val resetAt = if (firstTs > 0) firstTs + windowSeconds else 0.0
        val cooldownUntil = row.optDouble("cooldown_until", 0.0)
        val now = System.currentTimeMillis() / 1000.0
        val cooling = cooldownUntil > now

        return JSONObject()
            .put("uid", uid)
            .put("nickname", row.optString("nickname").ifBlank { displayName("", uid) })
            .put("used", used).put("limit", limit).put("remaining", remaining)
            .put("percent", percent)
            .put("confirmed_used", if (confirmed) confirmedUsed else JSONObject.NULL)
            .put("confirmed", confirmed)
            .put("exhausted", remaining <= 0L)
            .put("cooling", cooling)
            .put("cooldown_until", cooldownUntil)
            .put("resets_at", if (resetAt > 0) resetAt else JSONObject.NULL)
            .put("window_tokens", windowTokens)
            .put("requests", row.optLong("requests", 0L))
            .put("window_hours", windowSeconds / 3600)
    }

    /** 全部账号的余额视图。 */
    fun quotaOverview(context: Context): JSONArray {
        migrateLegacy(context)
        val out = JSONArray()
        val usage = store(context).accountTokenUsage(DEFAULT_FREE_WINDOW_SECONDS.toDouble())
        for (i in 0 until usage.length()) {
            val row = usage.optJSONObject(i) ?: continue
            out.put(quotaView(context, row))
        }
        return out
    }

    // ---------------------------------------------------------------- 模型健康探测

    /**
     * 探测某模型当前是否可用。
     *
     * 做法：发一个 `max_tokens=8` 的极短流式请求，等到 `response.completed`（成功）或
     * `response.failed` / `error`（失败）即返回。用短超时客户端（25s），超过即判为
     * 「无响应/冻结」。
     *
     * 只**手动触发**、不自动：免费档额度宝贵，自动定时探测会白白烧 token。
     * 一次探测的输入侧也会计费（约 200 token），UI 上要如实提示。
     *
     * 返回 `{ok, latency_ms, error}`；`ok` 为 true 表示该模型对当前账号现在能正常出结果。
     */
    fun probeModel(context: Context, modelId: String): JSONObject {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start
        fun result(ok: Boolean, error: String) = JSONObject()
            .put("ok", ok).put("latency_ms", elapsed()).put("error", error)

        val body = JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            .put("max_tokens", 8)
        return try {
            val up = upstreamRequest(context, body, AccountRegion.XAI)
            probeHttp.newCall(up.request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val failure = classifyExhaustion(raw, response.code, response.header("Retry-After"))
                    val message = if (failure.kind != Exhaustion.Kind.NONE) failure.message
                        else "HTTP ${response.code} ${raw.take(120)}"
                    return result(false, message)
                }
                val source = response.body?.source() ?: return result(false, "上游空响应")
                var completed = false
                var failMsg: String? = null
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    // 只关心以 data: 开头的行，跳过 event: / 空行
                    val idx = line.indexOf("data:")
                    if (idx < 0) continue
                    val data = line.substring(idx + 5).trim()
                    if (data == "[DONE]") { completed = true; break }
                    val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (ev.optString("type")) {
                        // completed = 正常完成；incomplete = 模型正常响应但被 max_tokens 截断——
                        // 两者都说明模型「能用」，只有 failed / error / 无终止事件才判为不健康。
                        "response.completed", "response.incomplete" -> { completed = true; break }
                        "response.failed" -> {
                            failMsg = ev.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                                ?: ev.optString("code").ifBlank { "response.failed" }
                            break
                        }
                        "error" -> { failMsg = ev.optString("message", ev.optString("code")); break }
                    }
                }
                if (completed) result(true, "") else result(false, failMsg ?: "未收到终止事件")
            }
        } catch (e: IOException) {
            result(false, e.message ?: "连接失败")
        } catch (e: Exception) {
            result(false, e.message ?: "探测异常")
        }
    }

    /** 记录一次成功的调用：衰减失败计数、解除冷却、清掉上游确认值（说明额度已恢复）。 */
    fun markInferenceSuccess(context: Context, accountKey: String) {
        runCatching {
            store(context).markAccountSuccess(accountKey)
            store(context).clearConfirmedQuota(accountKey)
        }
    }

    /**
     * 记录一次可归因到账号的失败：设冷却并累计失败次数。
     * 命中额度耗尽时同时写入上游确认的真实 used/limit。
     */
    fun markInferenceFailure(context: Context, accountKey: String, exhaustion: Exhaustion) {
        runCatching {
            if (exhaustion.usedTokens >= 0 || exhaustion.limitTokens > 0) {
                store(context).setConfirmedQuota(accountKey, exhaustion.usedTokens, exhaustion.limitTokens)
            }
            // 指数退避：同一账号反复失败时冷却翻倍（官方同款），上限 7 天。
            // BLOCKED（封号）是终局判定，不参与翻倍——一次就按 24h 冷却出池。
            val base = exhaustion.cooldownSeconds.coerceAtLeast(1L)
            val priorFailures = store(context).getAccount(accountKey)?.optInt("failure_count", 0) ?: 0
            val cooldown = if (exhaustion.kind == Exhaustion.Kind.BLOCKED) base
                else (base shl priorFailures.coerceIn(0, 8)).coerceAtMost(7L * 24 * 3600)
            // 冷却原因与失败码一起写：否则面板只能显示"冷却中"，说不出为什么（真实踩到）。
            val nextProbe = if (exhaustion.kind == Exhaustion.Kind.FREE_QUOTA ||
                exhaustion.kind == Exhaustion.Kind.MODEL_QUOTA ||
                exhaustion.kind == Exhaustion.Kind.SPENDING_LIMIT ||
                exhaustion.kind == Exhaustion.Kind.RATE_LIMIT
            ) System.currentTimeMillis() / 1000.0 + cooldown.coerceAtMost(1800L) else 0.0
            store(context).markAccountFailure(
                accountKey, cooldown,
                reason = Exhaustion.reasonOf(exhaustion.kind),
                failureCode = Exhaustion.codeOf(exhaustion.kind),
                nextProbeAt = nextProbe,
            )
        }
    }

    fun storageBreakdown(context: Context): JSONObject {
        val dataDir = context.dataDir
        fun size(path: String) = dirSize(java.io.File(dataDir, path))
        val dbFile = context.getDatabasePath(NativeStore.DATABASE_NAME)
        return JSONObject()
            .put("databases", size("databases") + dirSize(java.io.File(dbFile.path + "-wal")) + dirSize(java.io.File(dbFile.path + "-shm")))
            .put("cache", dirSize(context.cacheDir))
            .put("webview", size("app_webview"))
            .put("files", size("files"))
            .put("shared_prefs", size("shared_prefs"))
            .put("code_cache", dirSize(context.codeCacheDir))
            .put("no_backup", dirSize(context.noBackupFilesDir))
            .put("external_cache", runCatching { dirSize(context.externalCacheDir) }.getOrDefault(0L))
            .put("external_files", runCatching { dirSize(context.getExternalFilesDir(null)) }.getOrDefault(0L))
            .put("data_total", dirSize(dataDir))
    }

    fun clearWebViewCache(context: Context) {
        runCatching {
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
        runCatching {
            val web = android.webkit.WebView(context)
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
            web.destroy()
        }
    }

    fun deleteUsage(context: Context, id: Long): Boolean = store(context).deleteUsage(id)
    fun clearUsage(context: Context, vacuum: Boolean = true): Int = store(context).clearUsage(vacuum)
    fun settings(context: Context): JSONObject = store(context).getSettings()
    fun saveSettings(context: Context, values: JSONObject): JSONObject = store(context).saveSettings(values)

    private const val TAG = "NativeCore"

    /** 免费档履约窗口：xAI 按 24 小时滚动窗口发放（与官方 Go 版 freeUsageWindow 一致）。 */
    const val DEFAULT_FREE_WINDOW_SECONDS = 24 * 3600L

    /** 免费档 token 上限兜底值（官方 Go 版 estimatedFreeTokenLimit）。 */
    const val DEFAULT_FREE_TOKEN_LIMIT = 500_000L

    /** 解析错误文案里的 `tokens (actual/limit): 537365/500000`。 */
    private val TOKEN_PAIR = Regex("""tokens\s*\(actual/limit\)\s*:\s*(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** 解析 `resets in: 1d2h3m4s`。各分组可缺省。 */
    private val RESET_WINDOW = Regex("""resets?\s+in\s*:?\s*(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""", RegexOption.IGNORE_CASE)

    /** UUID 命名空间 URL 的字节表示（RFC 4122 附录 C）。 */
    private val UUID_NAME_SPACE_URL: ByteArray = byteArrayOf(
        0x6b, 0xa7.toByte(), 0xb8.toByte(), 0x11, 0x9d.toByte(), 0xad.toByte(), 0x11, 0xd1.toByte(),
        0x80.toByte(), 0xb4.toByte(), 0x00, 0xc0.toByte(), 0x4f, 0xd4.toByte(), 0x30, 0xc8.toByte()
    )
}

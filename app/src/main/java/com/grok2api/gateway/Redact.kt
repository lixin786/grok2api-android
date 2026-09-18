package com.grok2api.gateway

/**
 * 日志脱敏：把要写进「诊断报告」的文本里的凭据材料打掉。
 *
 * 为什么要脱敏：诊断报告是**准备给别人看的**（用户发 issue、或发给作者排查），而产号日志天然含
 * 验证码、邮箱地址、SSO cookie 前缀与上游响应体片段（`FarmEngine.kt:208` / `:166` / `FarmMail.kt:339`
 * 实测都会写进去）。不脱敏就等于把别人的凭据材料收集到自己手上。
 *
 * 为什么在源头做而不是导出时过滤：报告要落盘、要被复制、可能被转发，中途谁忘了过滤就泄露了；
 * 收敛成这一处纯函数，落盘前统一过一遍。
 *
 * 注意：本机 `files/farm.log` **保持原始内容**（自己 `run-as` 排查时要看真值），只有诊断报告这一路走脱敏。
 *
 * 纯逻辑、无 Android 依赖，便于单测（与 `RiskLogic` 同样的理由）。
 */
object Redact {

    /** JWT（access/id token）——三段点分 base64url。必须最先跑，否则会被后面的长串规则切碎。 */
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]*")

    /** 明文请求头里的凭据。 */
    private val HEADER_SECRET = Regex(
        "\\b(authorization|x-api-key|api-key|x-admin-auth|cookie)\\s*:\\s*\\S+",
        RegexOption.IGNORE_CASE
    )

    /** URL query 里的凭据（自建邮箱后端有 query-key 鉴权模式，将来接入也兜住）。 */
    private val QUERY_SECRET = Regex(
        "([?&](?:key|api_key|apikey|token|access_token|refresh_token|id_token|jwt|password|pwd|secret|code|otp|email_code)=)[^&\\s\"'<>]+",
        RegexOption.IGNORE_CASE
    )

    /** cookie 赋值里的值。 */
    private val COOKIE_SECRET = Regex(
        "\\b(sso|sso-rw|cf_clearance|__cf_bm|csrf|session|sid)\\s*=\\s*[^;\\s,]+",
        RegexOption.IGNORE_CASE
    )

    /**
     * 裸 `key=value` 形态的凭据（cookie 规则之外的兜底）。
     *
     * [QUERY_SECRET] 只认 URL 里 `?`/`&` 引入的 query；异常消息里常会有不带 `?`/`&` 的
     * `token=xxx` / `api_key=xxx` 原样拼在文本里（真机实测：`token=supersecretkey123` 会漏）。
     * 值要求 ≥6 个非空白字符——既覆盖真正的 token/api key，又不误伤 `key=abc` 之类的短值。
     * `\b` 防止命中 `status_code=` / `oauth_token_refresh=`（下划线不是边界）这类看起来像键的词。
     */
    private val KEYVAL_SECRET = Regex(
        "(?i)\\b(key|token|api_key|apikey|access_token|refresh_token|id_token|jwt|password|pwd|secret|otp|auth)\\s*=\\s*[A-Za-z0-9._~:/+?%-]{6,}"
    )

    /** 「code: 123456」「验证码：1234」这类带标签的口令。`\b` 防止命中 status_code 之类。 */
    private val CODE_KEYED = Regex("(?i)\\b((?:code|验证码|校验码|otp|口令)\\s*[:：=]?\\s*)([0-9]{3,8})")

    /**
     * 裸的 `NNN-NNN` 形态（xAI 邮件验证码就是这个格式）。
     *
     * 前后加否定环视排除 `redirect=grok-com`、URL 路径段 `accounts.x.ai/xai-com`——
     * 这些是合法参数不是验证码，误脱敏会把关键排查信息抹掉（`gro`-`com` 恰好也满足 3-3 形态）。
     */
    private val CODE_DASHED = Regex("(?<![=/.:A-Za-z0-9_-])[A-Z0-9]{3}-[A-Z0-9]{3}(?![A-Za-z0-9-])")

    /** 邮箱：保留域名（能看出用的哪个临时邮箱服务，对排查有用），本地部分隐去。 */
    private val EMAIL = Regex("([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")

    /** 兜底：任何 32 位以上的无分隔长串（临时邮箱 token、session id、base64 片段）。 */
    private val LONG_BLOB = Regex("[A-Za-z0-9_-]{32,}")

    /**
     * 全量脱敏。幂等（已脱敏的文本再跑一遍结果不变），可安全重复调用。
     */
    fun text(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var t = raw
        t = JWT.replace(t, "<jwt已隐去>")
        t = HEADER_SECRET.replace(t) { m -> "${m.groupValues[1]}: <已隐去>" }
        t = QUERY_SECRET.replace(t) { m -> "${m.groupValues[1]}<已隐去>" }
        t = COOKIE_SECRET.replace(t) { m -> "${m.groupValues[1]}=<已隐去>" }
        t = KEYVAL_SECRET.replace(t) { m -> "${m.groupValues[1]}=<已隐去>" }
        t = CODE_KEYED.replace(t) { m -> "${m.groupValues[1]}<已隐去>" }
        t = CODE_DASHED.replace(t, "<验证码已隐去>")
        t = EMAIL.replace(t) { m -> "***@${m.groupValues[2]}" }
        t = LONG_BLOB.replace(t, "<长串已隐去>")
        return t
    }

    /** 邮箱单独脱敏（台账/UI 里只显示 `***@域名`，比整串打掉更能反映用的是哪个邮源）。 */
    fun email(raw: String?): String = EMAIL.replace(raw.orEmpty()) { m -> "***@${m.groupValues[2]}" }

    /** 只回布尔/长度的场景用：不泄露值，但能说明"取到了"。 */
    fun describe(raw: String?): String {
        val s = raw.orEmpty()
        return if (s.isEmpty()) "空" else "长度 ${s.length}"
    }
}
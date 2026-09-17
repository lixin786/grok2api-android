package com.grok2api.gateway

import org.json.JSONObject
import java.util.Base64

/**
 * 号池风控的**纯逻辑**（无 Android 依赖，可在 JVM 单测里直接跑）。
 *
 * 之所以从 NativeCore 抽出来：风控判定是"改一处容易碰坏另一处"的高频改动区，
 * 必须能被测试覆盖。凡是不需要 Context/数据库的判断都放这里，NativeCore 只做 IO 与落库。
 */
object RiskLogic {

    /** 上游失败分类结果。kind 决定处置（冷却时长、是否改账号状态）。 */
    data class UpstreamFailure(val code: String, val kind: String) {
        /** 软失败（策略拒绝）不该改账号状态：号是好的，只是这次请求/内容不合规。 */
        val isSoft: Boolean get() = kind == "policy"
        /** 需要账号出池的失败（封锁 / 凭据被拒）。 */
        val isAccountLevel: Boolean get() = kind == "blocked" || kind == "auth"
        /** 额度类失败：长冷却 + 排恢复探测，不弃号。 */
        val isQuotaLike: Boolean get() = kind == "quota" || kind == "rate"
    }

    /**
     * 上游失败分类（对照 chenyme/grok2api 的 failure.go）。
     *
     * 核心是把三类东西彻底分开，历史上混在一起导致误判：
     *  1. **内容/请求级策略拒绝**（号是好的）  ≠  2. **账号被封锁**（号没了）  ≠  3. 基础设施抖动（换时间即可）
     * 判定顺序即优先级：账号级封锁 > 策略拒绝 > 额度 > 凭据 > 限流 > 传输 > 服务端。
     */
    fun classifyUpstreamFailure(status: Int, body: String?): UpstreamFailure {
        val t = (body ?: "").lowercase()
        fun has(vararg keys: String) = keys.any { t.contains(it) }
        return when {
            has("blocked-user", "user is blocked", "account suspended", "banned") ->
                UpstreamFailure("account_blocked", "blocked")

            has("content policy", "safety", "moderation", "content_filter", "policy violation") ->
                UpstreamFailure("content_policy_refusal", "policy")
            has("request policy", "policy=deny", "not allowed for this request") ->
                UpstreamFailure("request_policy_denied", "policy")

            has("tokens (actual/limit)", "quota", "insufficient", "credits", "payment") ->
                UpstreamFailure("quota_exhausted", "quota")

            status == 401 || has("invalid token", "unauthorized", "invalid_grant", "token expired") ->
                UpstreamFailure("upstream_unauthorized", "auth")
            status == 402 -> UpstreamFailure("upstream_payment_required", "quota")
            status == 403 -> UpstreamFailure("upstream_forbidden", "auth")

            status == 429 || has("rate limit", "too many requests", "slow_down") ->
                UpstreamFailure("upstream_rate_limited", "rate")

            has("timeout", "timed out", "connect", "unreachable", "connection reset", "eof") ->
                UpstreamFailure("transport_error", "transport")
            has("empty", "no content") -> UpstreamFailure("upstream_response_empty", "transport")
            status in 500..599 -> UpstreamFailure("upstream_server_error", "server")
            else -> UpstreamFailure("unknown_$status", "unknown")
        }
    }

    /**
     * 分类 → 冷却秒数。
     *
     * 分级而不是一刀切：基础设施抖动（5xx/超时）短冷却即可，风控类（限流/封锁/凭据）
     * 必须长冷却；策略拒绝不冷却（下一次请求可能完全正常）。
     */
    fun cooldownSecondsFor(kind: String, networkSec: Long = 90, riskSec: Long = 1800): Long =
        when (kind) {
            "policy" -> 0L
            "blocked", "auth", "rate", "quota" -> riskSec
            else -> networkSec
        }

    /** 由分类结果推导账号风险等级（null 表示不改状态）。 */
    fun severityFor(kind: String): String? = when (kind) {
        "policy" -> null
        "blocked", "auth" -> "dead"
        else -> "warning"
    }

    /** 风险等级 → 面板排序权重（越小越靠前 = 越需要处理）。 */
    fun severityOrder(level: String?): Int = when (level) {
        "dead" -> 0
        "warning" -> 1
        "" , null -> 2          // 未体检
        else -> 3               // healthy
    }

    // ------------------------------------------------------------------ JWT

    /** 解 JWT payload（失败返回 null）。用 java.util.Base64，避免依赖 Android。 */
    fun jwtClaims(token: String?): JSONObject? {
        val parts = (token ?: "").split('.')
        if (parts.size < 2) return null
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(parts[1])
            JSONObject(String(decoded, Charsets.UTF_8))
        }.getOrNull()
    }

    /** access_token 是否是可解析的 JWT —— 解析不了说明凭据已损坏，不能当"无标记=健康"。 */
    fun jwtParsable(token: String?): Boolean = jwtClaims(token) != null

    /**
     * 读 xAI 的 bot 风控标记（`bot_flag_source` / `bfs`）。
     *
     *  该字段**不可作为弃号依据**：lij768423-svg/grok-register-panel 已实测 grok.com 的
     * botFlagSource 不可靠（改用降智探测），chenyme/grok2api 也只拿它选路由。
     * 本函数只返回原始值，由调用方按 `risk_bot_flag_action` 决定 warn/disable/ignore。
     */
    fun botFlagSource(token: String?): Int {
        val claims = jwtClaims(token) ?: return 0
        val raw: Any? = when {
            claims.has("bot_flag_source") -> claims.opt("bot_flag_source")
            claims.has("bfs") -> claims.opt("bfs")
            else -> return 0
        }
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 0
            is Boolean -> if (raw) 1 else 0
            else -> 0
        }
    }

    /**
     * 单号健康判定（纯计算，便于单测覆盖所有分支）。
     *
     * @param tokenParsable access_token 能否解析
     * @param botFlag       bot_flag_source 原始值
     * @param botFlagAction warn / disable / ignore
     * @param hasRefresh   是否有 refresh_token
     * @param enabled      是否启用
     * @param cooldownLeft 冷却剩余秒数
     * @param failureCount 连续失败次数
     * @param upstreamKind deep 探测得到的失败类型（null = 未探测/成功）
     * @return (severity, reasons)
     */
    fun evaluateAccount(
        tokenParsable: Boolean,
        botFlag: Int,
        botFlagAction: String,
        hasRefresh: Boolean,
        enabled: Boolean,
        cooldownLeft: Long,
        failureCount: Int,
        upstreamKind: String? = null,
        quotaExhausted: Boolean = false,
    ): Pair<String, List<String>> {
        var severity = "healthy"
        val reasons = mutableListOf<String>()

        fun mark(level: String, reason: String) {
            reasons += reason
            if (level == "dead") severity = "dead"
            else if (level == "warning" && severity != "dead") severity = "warning"
        }

        if (!tokenParsable) mark("dead", "access_token 非法（无法解析 JWT，凭据已损坏）")
        if (!hasRefresh) mark("dead", "缺少 refresh_token，凭据无法自动续期")

        if (botFlag == 1 && botFlagAction != "ignore") {
            if (botFlagAction == "disable") mark("dead", "被 xAI 风控标记（bot_flag_source=1，按设置停用）")
            else mark("warning", "带风控标记（bot_flag_source=1）：建议降级使用，不必然失效")
        }

        if (!enabled) mark("warning", "当前处于停用状态")
        if (cooldownLeft > 0) mark("warning", "冷却中，剩余 ${cooldownLeft}s")
        if (failureCount >= 3) mark("warning", "连续失败 $failureCount 次")

        when (upstreamKind) {
            null -> Unit
            "policy" -> mark("warning", "上游策略拒绝（content_policy_refusal）：号可用，本次请求不合规")
            "blocked" -> mark("dead", "上游封锁账号（account_blocked）")
            "auth" -> mark("dead", "凭据被拒（upstream_unauthorized），需重新授权")
            "quota", "rate" -> mark("warning", "额度/限流（quota_exhausted），等待恢复探测")
            else -> mark("warning", "探测失败：$upstreamKind")
        }
        if (quotaExhausted) mark("warning", "额度已用尽（等窗口重置）")

        return severity to reasons
    }

    /** 从错误文案里扒出 HTTP 状态码（OkHttp 的异常消息形如 "HTTP 403: ..."）。 */
    fun statusFromMessage(message: String?): Int =
        Regex("HTTP ([0-9]{3})").find(message ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
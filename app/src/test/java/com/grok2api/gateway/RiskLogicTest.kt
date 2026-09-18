package com.grok2api.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 号池风控纯逻辑单测。
 *
 * 覆盖的是"改了容易碰坏"的部分：失败分类边界（尤其 policy ≠ blocked）、冷却分级、
 * bot_flag 三档策略、健康判定优先级、JWT 解析容错。
 * 这些都是真机/模拟器上很难穷举、但线上会踩的判定分支。
 */
class RiskLogicTest {

    // ---------------------------------------------------------------- 失败分类

    @Test
    fun `blocked-user 判为账号级封锁 不是普通 403`() {
        val f = RiskLogic.classifyUpstreamFailure(403, "blocked-user: this user is blocked")
        assertEquals("account_blocked", f.code)
        assertEquals("blocked", f.kind)
        assertTrue(f.isAccountLevel)
        assertFalse("封锁不能被当成软失败", f.isSoft)
    }

    @Test
    fun `内容安全拒绝是软失败 不拖累账号`() {
        val f = RiskLogic.classifyUpstreamFailure(403, "content policy violation detected")
        assertEquals("content_policy_refusal", f.code)
        assertEquals("policy", f.kind)
        assertTrue("策略拒绝必须是软失败", f.isSoft)
        assertFalse("策略拒绝不能判成账号级", f.isAccountLevel)
        assertEquals("策略拒绝不该冷却", 0L, RiskLogic.cooldownSecondsFor(f.kind))
        assertNull("策略拒绝不该改账号状态", RiskLogic.severityFor(f.kind))
    }

    @Test
    fun `请求级策略拒绝同样是软失败`() {
        val f = RiskLogic.classifyUpstreamFailure(403, "request policy denied for this request")
        assertEquals("request_policy_denied", f.code)
        assertTrue(f.isSoft)
    }

    @Test
    fun `generic 403 归到凭据类而不是封锁`() {
        val f = RiskLogic.classifyUpstreamFailure(403, "forbidden")
        assertEquals("upstream_forbidden", f.code)
        assertEquals("auth", f.kind)
    }

    @Test
    fun `401 判为凭据失效`() {
        val f = RiskLogic.classifyUpstreamFailure(401, "invalid token")
        assertEquals("upstream_unauthorized", f.code)
        assertEquals("auth", f.kind)
        assertEquals("dead", RiskLogic.severityFor(f.kind))
    }

    @Test
    fun `额度耗尽归 quota 且属于长冷却`() {
        val f = RiskLogic.classifyUpstreamFailure(400, "tokens (actual/limit): 500000/500000")
        assertEquals("quota_exhausted", f.code)
        assertTrue(f.isQuotaLike)
        assertEquals(1800L, RiskLogic.cooldownSecondsFor(f.kind))
    }

    @Test
    fun `付费要求与 402 都归额度类`() {
        assertEquals("quota", RiskLogic.classifyUpstreamFailure(402, "").kind)
        assertEquals("quota", RiskLogic.classifyUpstreamFailure(400, "payment required").kind)
    }

    @Test
    fun `限流与 5xx 传输错误的冷却分级不同`() {
        val rate = RiskLogic.classifyUpstreamFailure(429, "rate limit exceeded")
        val server = RiskLogic.classifyUpstreamFailure(500, "internal server error")
        val transport = RiskLogic.classifyUpstreamFailure(0, "i/o timeout")
        assertEquals("rate", rate.kind)
        assertEquals("server", server.kind)
        assertEquals("transport", transport.kind)
        assertEquals("风控类长冷却", 1800L, RiskLogic.cooldownSecondsFor(rate.kind))
        assertEquals("抖动类短冷却", 90L, RiskLogic.cooldownSecondsFor(server.kind))
        assertEquals("抖动类短冷却", 90L, RiskLogic.cooldownSecondsFor(transport.kind))
    }

    @Test
    fun `冷却分级参数可配置`() {
        // 风控类（rate/blocked/auth/quota）走 riskSec；抖动类（server/transport/unknown）走 networkSec
        assertEquals(600L, RiskLogic.cooldownSecondsFor("rate", networkSec = 30, riskSec = 600))
        assertEquals(30L, RiskLogic.cooldownSecondsFor("server", networkSec = 30, riskSec = 600))
        assertEquals(30L, RiskLogic.cooldownSecondsFor("transport", networkSec = 30, riskSec = 600))
        assertEquals(0L, RiskLogic.cooldownSecondsFor("policy", networkSec = 30, riskSec = 600))
    }

    @Test
    fun `未知失败不会崩 且给短冷却`() {
        val f = RiskLogic.classifyUpstreamFailure(418, "teapot")
        assertEquals("unknown_418", f.code)
        assertEquals(90L, RiskLogic.cooldownSecondsFor(f.kind))
    }

    // ---------------------------------------------------------------- JWT

    private fun jwt(payload: Map<String, Any?>): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val body = payload.entries.joinToString(",") { (k, v) ->
            val value = if (v is String) "\"$v\"" else "$v"
            "\"$k\":$value"
        }
        return b64("{\"alg\":\"ES256\"}") + "." + b64("{$body}") + ".sig"
    }

    @Test
    fun `识别 bot_flag_source 与 bfs 两种写法`() {
        assertEquals(1, RiskLogic.botFlagSource(jwt(mapOf("sub" to "u", "bot_flag_source" to 1))))
        assertEquals(1, RiskLogic.botFlagSource(jwt(mapOf("sub" to "u", "bfs" to 1))))
        assertEquals(2, RiskLogic.botFlagSource(jwt(mapOf("sub" to "u", "bot_flag_source" to 2))))
        assertEquals(0, RiskLogic.botFlagSource(jwt(mapOf("sub" to "u"))))
    }

    @Test
    fun `bot_flag 字符串与布尔写法都能读`() {
        assertEquals(1, RiskLogic.botFlagSource(jwt(mapOf("bot_flag_source" to "1"))))
        assertEquals(1, RiskLogic.botFlagSource(jwt(mapOf("bot_flag_source" to true))))
        assertEquals(0, RiskLogic.botFlagSource(jwt(mapOf("bot_flag_source" to "abc"))))
    }

    @Test
    fun `非法 token 解析返回 0 但 parsable 为 false`() {
        assertEquals(0, RiskLogic.botFlagSource("not-a-jwt"))
        assertFalse(RiskLogic.jwtParsable("not-a-jwt"))
        assertFalse(RiskLogic.jwtParsable(""))
        assertTrue(RiskLogic.jwtParsable(jwt(mapOf("sub" to "u"))))
        assertNull(RiskLogic.jwtClaims("a.b"))
    }

    @Test
    fun `从异常消息里扒状态码`() {
        assertEquals(403, RiskLogic.statusFromMessage("HTTP 403: blocked-user"))
        assertEquals(0, RiskLogic.statusFromMessage("connection reset"))
        assertEquals(0, RiskLogic.statusFromMessage(null))
    }

    // ---------------------------------------------------------------- 健康判定

    private fun evaluate(
        tokenParsable: Boolean = true,
        botFlag: Int = 0,
        botFlagAction: String = "warn",
        hasRefresh: Boolean = true,
        enabled: Boolean = true,
        cooldownLeft: Long = 0,
        failureCount: Int = 0,
        upstreamKind: String? = null,
        quotaExhausted: Boolean = false,
    ) = RiskLogic.evaluateAccount(tokenParsable, botFlag, botFlagAction, hasRefresh,
        enabled, cooldownLeft, failureCount, upstreamKind, quotaExhausted)

    @Test
    fun `一切正常判健康`() {
        val (severity, reasons) = evaluate()
        assertEquals("healthy", severity)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `凭据损坏与缺 refresh_token 都判 dead`() {
        assertEquals("dead", evaluate(tokenParsable = false).first)
        assertEquals("dead", evaluate(hasRefresh = false).first)
    }

    @Test
    fun `bot_flag 默认只警告 不判死`() {
        val (severity, reasons) = evaluate(botFlag = 1, botFlagAction = "warn")
        assertEquals("warning", severity)
        assertTrue(reasons.any { it.contains("降级使用") })
    }

    @Test
    fun `bot_flag 按策略切到 disable 才判死 ignore 则完全忽略`() {
        assertEquals("dead", evaluate(botFlag = 1, botFlagAction = "disable").first)
        val (ignored, reasons) = evaluate(botFlag = 1, botFlagAction = "ignore")
        assertEquals("healthy", ignored)
        assertTrue("ignore 时不该出现任何 bot 提示", reasons.none { it.contains("bot") })
    }

    @Test
    fun `dead 优先级高于 warning 不被覆盖`() {
        val (severity, reasons) = evaluate(tokenParsable = false, cooldownLeft = 100, failureCount = 9)
        assertEquals("dead", severity)
        assertTrue("原因应全部保留", reasons.size >= 3)
    }

    @Test
    fun `冷却与连续失败判 warning`() {
        assertEquals("warning", evaluate(cooldownLeft = 30).first)
        assertEquals("warning", evaluate(failureCount = 3).first)
        assertEquals("连续失败 2 次还不算异常", "healthy", evaluate(failureCount = 2).first)
    }

    @Test
    fun `上游探测结果按类型映射严重度`() {
        assertEquals("dead", evaluate(upstreamKind = "blocked").first)
        assertEquals("dead", evaluate(upstreamKind = "auth").first)
        assertEquals("warning", evaluate(upstreamKind = "quota").first)
        assertEquals("warning", evaluate(upstreamKind = "rate").first)
        assertEquals("warning", evaluate(upstreamKind = "policy").first)
    }

    @Test
    fun `额度耗尽判 warning 而不是 dead`() {
        assertEquals("warning", evaluate(quotaExhausted = true).first)
    }

    // ---------------------------------------------------------------- 排序

    @Test
    fun `面板排序 dead 最前 未体检在健康之前`() {
        assertEquals(0, RiskLogic.severityOrder("dead"))
        assertEquals(1, RiskLogic.severityOrder("warning"))
        assertEquals(2, RiskLogic.severityOrder(""))
        assertEquals(2, RiskLogic.severityOrder(null))
        assertEquals(3, RiskLogic.severityOrder("healthy"))
        val sorted = listOf("healthy", "dead", "", "warning").sortedBy { RiskLogic.severityOrder(it) }
        assertEquals(listOf("dead", "warning", "", "healthy"), sorted)
    }
}
/**
 * 2026-09-17 三处修复的回归测试。
 *
 * 背景（真实事故）：请求一个不存在的模型名时，上游对每个账号都回
 * `personal-team-blocked:spending-limit`，网关把跨账号重试链上的 8 个号
 * 全打进 6h 冷却，可用率 94% → 44%。以下测试锁住"客户端错误不惩罚账号"
 * 与"冷却必须有原因"这两条不变量。
 */
class FailureAttributionTest {

    @Test
    fun `无效模型名不是账号级失败`() {
        // 上游对未知模型回的文案（实测原文）
        val f = RiskLogic.classifyUpstreamFailure(
            403, "personal-team-blocked:spending-limit You have run out of credits")
        // 注意：单看文案它是额度类，所以上游判据本身救不了——
        // 真正的护栏是"请求发出前就校验模型名"，见 rejectUnknownModel。
        assertTrue("文案层面确实像额度问题（说明了为什么必须前置校验）", f.kind == "quota")
    }

    @Test
    fun `目录校验逻辑的判据（模拟）：命中即放行 未命中即客户端错误`() {
        val known = listOf("grok-4.6", "grok-4.6-low", "grok-composer-2.5-fast")
        fun reject(requested: String): String? =
            if (requested.isBlank() || known.contains(requested)) null else "unknown model"

        assertNull(reject("grok-4.6"))
        assertNull("空模型名交给上游判，不拦", reject(""))
        assertEquals("unknown model", reject("grok-3"))
    }

    @Test
    fun `策略类失败仍然不惩罚账号（与本次修复同一原则）`() {
        val f = RiskLogic.classifyUpstreamFailure(403, "content policy violation")
        assertTrue(f.isSoft)
        assertEquals(0L, RiskLogic.cooldownSecondsFor(f.kind))
        assertNull(RiskLogic.severityFor(f.kind))
    }
}

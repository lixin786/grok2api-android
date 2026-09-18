package com.grok2api.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断报告脱敏单测。
 *
 * 为什么必须测：脱敏有**两个方向**的失败，都很难在真机上发现——
 * 1. **漏脱**（真凭据被写进报告）：报告是准备发给别人的，漏一次就是把别人的验证码/SSO 交出去；
 * 2. **误伤**（合法排查信息被抹掉）：比如 `redirect=grok-com` 恰好满足 `ABC-123` 形态，
 *    被当成验证码隐去后，报告里就看不出走的哪条注册入口了。
 * 所以这里两个方向都锁住。
 */
class RedactTest {

    // ---------------------------------------------------------------- 必须脱敏

    @Test
    fun `JWT 整体隐去`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NSIsImJvdF9mbGFnX3NvdXJjZSI6MX0.abcdefghijklmnop"
        val out = Redact.text("access_token=$jwt")
        assertFalse("JWT 不应原样留下", out.contains("eyJzdWIi"))
        assertTrue(out.contains("<jwt已隐去>"))
    }

    @Test
    fun `cookie 里的 sso 与 cf_clearance 隐去`() {
        val out = Redact.text("sso=abc123XYZ; sso-rw=abc123XYZ; cf_clearance=deadbeef")
        assertFalse("sso 值不应原样留下", out.contains("abc123XYZ"))
        assertFalse("cf_clearance 值不应原样留下", out.contains("deadbeef"))
        assertTrue(out.contains("sso=<已隐去>"))
        assertTrue(out.contains("cf_clearance=<已隐去>"))
    }

    @Test
    fun `带 cookie 头的整行也不漏（头规则先兜一层`() {
        // 真实场景里可能整行出现（异常消息带上了请求头）；此时头规则会把值整段吃掉，
        // 断言只看"秘密不残留"，不限定具体是哪个规则生效。
        val out = Redact.text("cookie: sso=abc123XYZ; cf_clearance=deadbeef")
        assertFalse(out.contains("abc123XYZ"))
        assertFalse(out.contains("deadbeef"))
        assertTrue(out.contains("<已隐去>"))
    }

    @Test
    fun `邮箱保留域名 隐去本地部分`() {
        val out = Redact.text("[+] 邮箱：xaiabcd1234@duckmail.sbs")
        assertFalse(out.contains("xaiabcd1234"))
        assertTrue("域名要留着，能看出用的哪个邮源", out.contains("***@duckmail.sbs"))
    }

    @Test
    fun `带标签的验证码隐去`() {
        assertTrue(Redact.text("[+] 验证码：123456").contains("<已隐去>"))
        assertTrue(Redact.text("[+] 验证码: 123456").contains("<已隐去>"))
        assertTrue(Redact.text("verification code: 1234").contains("<已隐去>"))
    }

    @Test
    fun `裸的 NNN-NNN 验证码隐去`() {
        val out = Redact.text("[*] 已填写验证码并提交: AB3-9KX")
        assertFalse(out.contains("AB3-9KX"))
        assertTrue(out.contains("<验证码已隐去>"))
    }

    @Test
    fun `URL query 里的凭据隐去`() {
        val out = Redact.text("https://mail.example.com/api/mails?key=supersecret&address=a@b.com")
        assertFalse(out.contains("supersecret"))
        assertTrue(out.contains("key=<已隐去>"))
    }

    @Test
    fun `裸的 token 赋值也隐去（真机实测漏点`() {
        // 异常消息里常见 `token=xxx` 不带 ?/& 前缀，此前这条规则漏了——上真机才撞出来
        val out = Redact.text("异常：token=supersecretkey123 校验失败")
        assertFalse(out.contains("supersecretkey123"))
        assertTrue(out.contains("token=<已隐去>"))
    }

    @Test
    fun `短值与相似词不误伤`() {
        val short = Redact.text("key=abc flag=ok")
        assertTrue("短值不属于凭据形态，应保留", short.contains("key=abc"))
        val notKey = Redact.text("status_code=200 oauth_token_refresh=abcdef1234567890")
        assertTrue("下划线连接的复合词边界不该断开", notKey.contains("status_code=200"))
    }

    @Test
    fun `请求头里的凭据隐去`() {
        val out = Redact.text("X-API-Key: sk-live-abcdef123456")
        assertFalse(out.contains("sk-live-abcdef123456"))
        assertTrue(out.contains("<已隐去>"))
    }

    @Test
    fun `超长无分隔串隐去（临时邮箱 token）`() {
        // 裸长串（不带 token= 前缀，专门验证 LONG_BLOB 这条兜底规则）
        val token = "a".repeat(40)
        val out = Redact.text("看到一段：$token 结尾")
        assertFalse(out.contains(token))
        assertTrue(out.contains("<长串已隐去>"))
    }

    // ---------------------------------------------------------------- 不许误伤

    @Test
    fun `redirect 参数不被当成验证码`() {
        // grok-com / cloud-console 都是 `XXX-XXX` 形态，误脱敏会把注册入口这条关键信息抹掉
        val out = Redact.text("https://accounts.x.ai/sign-up?redirect=grok-com")
        assertTrue("redirect 值必须保留，否则排查时看不出走的哪条入口", out.contains("grok-com"))

        val out2 = Redact.text("https://accounts.x.ai/sign-up?redirect=cloud-console")
        assertTrue(out2.contains("cloud-console"))
    }

    @Test
    fun `URL 路径段不被当成验证码`() {
        val out = Redact.text("https://accounts.x.ai/xai-com/step")
        assertTrue(out.contains("xai-com"))
    }

    @Test
    fun `HTTP 状态码与阶段名不被误脱`() {
        val out = Redact.text("[x] DuckMail 获取 token 失败 HTTP 429：too many requests")
        assertTrue("429 不是验证码", out.contains("429"))
        assertTrue(out.contains("too many requests"))
    }

    @Test
    fun `正常日志行原样保留`() {
        val line = "[*] 设备码 ABCD-1234，在 WebView 内授权…"
        // 设备码是 4-4 形态，不属于 3-3，且这里没有 code 标签 → 应保持可读
        val out = Redact.text(line)
        assertTrue(out.contains("在 WebView 内授权"))
    }

    @Test
    fun `幂等 重复脱敏结果不变`() {
        val once = Redact.text("邮箱 xaiabcd@duckmail.sbs 验证码：123456 sso=abc")
        assertEquals(once, Redact.text(once))
    }

    @Test
    fun `空值安全`() {
        assertEquals("", Redact.text(null))
        assertEquals("", Redact.text(""))
    }

    // ---------------------------------------------------------------- 配套小函数

    @Test
    fun `describe 只回长度不回值`() {
        assertEquals("空", Redact.describe(""))
        assertEquals("长度 8", Redact.describe("abcd1234"))
    }

    @Test
    fun `email 单独脱敏`() {
        assertEquals("***@maliapi.215.im", Redact.email("someuser@maliapi.215.im"))
    }
}
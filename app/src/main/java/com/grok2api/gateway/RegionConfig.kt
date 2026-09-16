package com.grok2api.gateway

/**
 * 上游后端配置。
 *
 * 改造前这里区分「国内版 copilot.tencent.com / 国际版 codebuddy.ai」两套端点；
 * 移植到 xAI 后上游只有一套（cli-chat-proxy.grok.com），因此收敛为单枚举。
 *
 * 保留 [from] / [fromStrict] / [infer] 三个入口与原有签名：数据库里 apps.region 列、
 * accounts.region 列仍存在（避免改表结构），只是取值恒为 [XAI]。对任何历史值都返回
 * [XAI] 而不是抛异常，这样从 WorkBuddy 版迁移过来的备份文件也能直接导入。
 */
enum class AccountRegion(
    val id: String,
    val label: String,
    val backend: String,
    val defaultDomain: String
) {
    XAI("xai", "xAI", "https://cli-chat-proxy.grok.com/v1", "grok.com");

    companion object {
        /** 兜底区域：任何未知/历史取值都归到这里。 */
        val DEFAULT: AccountRegion = XAI

        fun from(value: String?): AccountRegion = XAI

        fun fromStrict(value: String?): AccountRegion = XAI

        fun infer(explicit: String?, domain: String?): AccountRegion = XAI
    }
}

/**
 * 一次 xAI 设备流授权会话。
 *
 * 原 WorkBuddy 版是「state + authUrl 轮询」，xAI 用 RFC 8628 设备流：
 * 先取 device_code / user_code，用户到 verification_uri 授权，再轮询 token。
 * [state] 字段继续承载轮询凭据（xAI 下即 device_code），以免改动调用方。
 */
data class OAuthSession(
    val region: AccountRegion,
    val state: String,
    val authUrl: String,
    val userCode: String = "",
    val intervalMs: Long = 5_000L,
    val expiresAt: Long = 0L
)

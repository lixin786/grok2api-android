package com.grok2api.gateway

/**
 * 授权成功后的上游侧收尾同步。
 *
 * 原 WorkBuddy 版需要在这里补发一次"邀请码绑定"，因为腾讯的上游状态只在网页端补齐；
 * xAI 走标准 OAuth 设备流，token 拿到即代表授权完成，**没有任何需要补发的动作**。
 *
 * 因此本对象退化为空实现：保留类名与 [afterAuthorize] 签名，是为了让 MainActivity
 * 的授权成功回调无需分支判断——这是移植时刻意的"留壳"处理。
 */
internal object AccountSync {
    /** 空操作：xAI 授权后无需任何收尾请求。 */
    fun afterAuthorize(domain: String) = Unit
}

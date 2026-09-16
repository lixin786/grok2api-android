# Grok2API · Android 原生版

> 把 **xAI Grok 免费额度**（Grok Build / CLI 通道），变成手机本地一个标准的 **OpenAI / Anthropic 兼容 API**。
> 纯原生 Android 实现（Kotlin），**无需 Python、无需 Docker、无需电脑**——装到手机上就能跑。

> 架构源自 [WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)（MIT），
> 上游从腾讯 WorkBuddy 换成了 xAI Grok（`cli-chat-proxy.grok.com` Responses 协议）。

---

## 这是什么

一个跑在 Android 手机上的本地 API 网关。启动后，你的电脑、平板或其它 App 可以通过
adb 端口转发连接手机，用标准 OpenAI / Anthropic 协议调用 Grok 的免费模型额度。

```
你的电脑 / ZCode / Cherry Studio / Claude Code
        │  http://127.0.0.1:8789/v1/...（adb forward tcp:8789 tcp:8789）
        ▼
┌─────────────────────────────────┐
│   手机 (Grok2API Native)         │
│  原生 HTTP 服务 (ServerSocket)   │
│  ├─ 三协议 → 统一 → xAI Responses│
│  ├─ 账号轮询 / 冷却 / 换号重试    │
│  └─ SQLite 记录用量与余额        │
└─────────────────────────────────┘
        │  https://cli-chat-proxy.grok.com/v1
        ▼
     xAI Grok 上游
```

---

## 特性

- 🔌 **三协议兼容** — OpenAI Chat (`/v1/chat/completions`)、Anthropic Messages (`/v1/messages`)、OpenAI Responses (`/v1/responses`)，均为**流式 + 非流式**
- 📱 **纯原生，零依赖** — 不打包 Python 运行时；所有逻辑用 Kotlin 原生实现
- 🔄 **账号轮询 + 换号重试** — 严格轮询（最久未用优先）保证额度均匀消耗；推理失败（额度耗尽/限流/5xx）自动换号重试（最多 8 次）；指数退避冷却
- 🚫 **封号与风控识别** — 识别上游 `blocked-user` 封禁（24h 出池）与 JWT `bot_flag_source` bot 风险标记（自动排到轮询末位 + UI 警告）
- 🩺 **模型健康探测** — 手动触发（不自动烧额度），逐模型探测可用性与延迟
- 📊 **余额与用量** — 按 24h 滚动窗口实时统计每账号 token 用量；上游拒绝时取回真实用量数字标注「已确认」
- 🧠 **思维链适配** — `reasoning_content` 多轮回填与 effort 档位别名（`grok-4.6-low/-high/-xhigh`）
- 🔑 **应用 Key 管理** — 创建 / 启停 / 删除 API Key，加密存储（AES-GCM）
- 🛡 **保活加固** — 前台服务 + WakeLock + 开机自启 + 精确闹钟兜底重启；worker 池 16 并发，断连自愈不僵死

## 与上游 WorkBuddy 版的差异

| | WorkBuddy2API（原版） | Grok2API Native（本项目） |
|---|---|---|
| 上游 | 腾讯 WorkBuddy/CodeBuddy | xAI Grok（Build 免费档） |
| 额度数据 | 上游返回真实积分 | 上游无额度接口，本地按 24h 窗口统计估算 |
| 签到 | 有（积分靠签到） | 无（免费额度滚动发放） |
| 选号 | 加权随机 | 严格轮询 + 换号重试 + 指数退避 |
| 端口 | 8788 | 8789（可共存，不同时运行） |

## 快速开始

### 0. 获取 APK

到 [Releases](../../releases) 页面下载最新 APK 直接安装，或者按下面自行编译。

### 1. 编译

```bash
# JDK 17+，Android SDK (compileSdk 36)
echo "sdk.dir=/你的/Android/SDK路径" > local.properties
gradle assembleRelease   # 已配置 debug-keystore 签名，可直接安装
```

### 2. 安装并启动

安装后在应用内点击「启动服务」，然后电脑上：

```bash
adb forward tcp:8789 tcp:8789
curl http://127.0.0.1:8789/health
```

> ⚠️ 本机 ROM（Flyme/Android 16）拦截局域网入站，WiFi 直连不可用，adb 转发是唯一稳定通道。
> USB 拔插会丢转发，重跑一次即可（一条 `adb forward tcp:8789 tcp:8789`）。

### 3. 添加账号

点「登录 Grok 账号」走 xAI 设备授权（内置窗口，不跳浏览器）。
**尽量少重新登录**：凭据自动续期（refresh_token 长期有效），频繁换号登录才是风控高危动作。

### 4. 接入客户端

- Base URL：`http://127.0.0.1:8789/v1`
- API Key：App「应用」页查看
- 模型：`grok-4.6`（默认高推理，慢）/ `grok-4.6-low`（快速，推荐日常）/ `grok-composer-2.5-fast` 等

## 设置项

| 设置 | 默认 | 说明 |
|---|---|---|
| 额度自动刷新时段 | `9,21` | 定时向 xAI 探测账号限额状态 |
| 免费额度上限 | `500000` | 单账号 24h 窗口 token 上限（余额计算基准） |
| 使用记录保留天数 | `30` | 过期自动清理并 VACUUM |
| 保活开关 | 开 | 前台服务保活 |

## 风控说明

- 上游风控主要盯**登录行为**：同一设备频繁走设备流登录多账号是最高危动作。
- 账号被上游拒绝时自动冷却（指数退避，上限 7 天），冷却结束自动回到轮询。
- JWT 带 `bot_flag_source`/`bfs` 标记的账号会被识别并在 UI 上警告、轮询降权。
- 内容安全类拒答不换号重试（避免放大异常流量）。

## 已知限制

- xAI 免费档上游**响应较慢**（grok-4.6 默认档可达 50-110s），建议用 `-low` 档位
- 上游非流式 `/responses` 不可靠，网关内部始终用流式再自行汇总
- 上游无额度查询接口，余额为本地估算（被上游拒绝过一次后才有「已确认」的真实值）

## 风险提示与免责声明

- 本项目通过非官方通道调用 xAI Grok，**存在违反 xAI 服务条款导致账号受限/封禁的风险**，请自行评估，后果自负。
- 仅用于个人学习研究，请勿用于商业用途或大规模滥用。
- 本项目与 xAI 无任何关联，不为账号安全或额度变化作任何担保。

## License

MIT（继承自 [WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)）

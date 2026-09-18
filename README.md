# Grok2API Farm · Android 原生版

> **手机上一个 App 跑通 Grok 号池全流程**：WebView 自动注册产号 → 号池轮询调度 → OpenAI / Anthropic 兼容网关。
> 纯原生 Android（Kotlin），**无需 Python、无需 Docker、无需电脑常驻**——装到手机上就是一个自给自足的 Grok 代理农场。

> 架构源自 [WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)（MIT），上游从腾讯 WorkBuddy 换成了 xAI Grok（`cli-chat-proxy.grok.com` Responses 协议）；
> 产号链路（状态机 / JS 驱动 / 悬浮层）参照 Android 版 grok-register 的实现思路重写。

---

## 三件事，一个 App

```
你的电脑 / ZCode / Cherry Studio / Claude Code
        │  http://127.0.0.1:8789/v1/...（adb forward tcp:8789 tcp:8789）
        ▼
┌──────────────────────────────────────────────────────┐
│  手机 (Grok2API Farm)                                 │
│                                                      │
│  ① 产号农场                ② 号池                     │
│  临时邮箱 → 注册页 WebView   严格轮询 / 分级冷却         │
│  → 收码 → 填资料 → Turnstile 换号重试 / 额度恢复探测     │
│  → SSO 加密留档 → mint → 入池 体检 / 降智探测            │
│              │                  │                    │
│              └────► SQLite ◄────┘                    │
│                                                      │
│  ③ 协议网关                                           │
│  OpenAI Chat / Anthropic / Responses ⇄ xAI Responses  │
│  思维链适配 · 工具调用 · 应用 Key · 用量统计             │
└──────────────────────────────────────────────────────┘
        │  https://cli-chat-proxy.grok.com/v1
        ▼
     xAI Grok 上游
```

## 特性

### ① 产号农场（`-farm` 版新增）

- 🏭 **WebView 全自动注册** — 手机本地跑完整链路：造临时邮箱 → 注册页 → 邮箱注册 → 收验证码 → 填资料 → 过 Turnstile → 拿 SSO → 设备流授权（mint）→ 入池。产出直接进号池，与手动导入复用同一套 `saveAccount` / 轮询 / 冷却 / 体检
- 🖐 **Turnstile 真触摸点击** — 注入真实 `MotionEvent` 坐标点击（Cloudflare 认这个，DOM `click()` 过不了），自动点失败才升级为通知 + 悬浮球红闪提示人工
- 🩹 **失败可补登（v1.8.0）** — 注册成功拿到 sso 的**当下**就把 `sso` / `password` 加密留档，再走 mint；`mint` 失败（网络断/轮询超时/进程被杀）不再让号永久失联 —— 面板「补登」按钮或批次收尾自动重跑设备流授权即可救回，`sso` 过期还会用留档的邮箱密码重新登录。不浪费新邮箱重新注册
- 📮 **五种邮箱源** — mail.tm（默认免配置）/ DuckMail / YYDS / 自建 Cloudflare 邮箱 / 自建 Cloud Mail，带域名轮换与多级验证码提取（主题专攻 → 正文宽匹配 → 数字码兜底）
- 🪟 **切后台续跑** — 注册 WebView 挂到系统级透明悬浮窗（alpha 0.02，系统判定"可见"），Chromium 才不节流 JS 定时器，Cloudflare 验证才过得去；另配常驻悬浮球显示进度、需人工介入时闪红
- ⚖ **节奏纪律** — canary 停批（首号失败即停）、号间隔可配、补登同样带间隔，避免连续注册/授权触发上游风控
- ⏰ **定时补号** — 每小时自检池可用率，低于阈值（默认 70%）发通知；`farm_auto_batch=1` 时才允许自动开批
- 📋 **产号台账** — 每批次留档（时间 / 邮箱 / 泳道 / 状态 / 错误 / uid），凭据列 AES-GCM 加密

### ② 号池与风控

- 🔄 **账号轮询 + 换号重试** — 严格轮询（最久未用优先）保证额度均匀消耗；推理失败自动换号重试（最多 8 次）
- 🗂 **失败分类冷却** — 封锁/凭据失效出池、额度类长冷却+恢复探测、策略拒绝软失败不冷却、5xx/网络抖动短冷却，七类分开处置不再误伤
- 🚫 **bot 风控标记** — 解析 JWT `bot_flag_source`（数值型 1/2 = 被标记），入池守卫自动弃号、调度降权、面板警示
- 🩺 **两级号池体检** — 本地信号（秒级：JWT 标记/RT 缺失/冷却/连败）+ 上游探测（deep 模式），按 dead/warning/healthy 分级，异常号一键清理（停用不删除）
- 🧪 **降智探测** — 真实短对话看有无 reasoning token，作为 bot_flag 之外最终的账号质量判定
- 📥 **一键批量导入** — 兼容官方 grok2api（Go 版）的多种凭据格式：本 App 导出 JSON / 扁平 JSON（snake/camelCase）/ `{"accounts":[...]}` 批量文档 / 纯文本 refresh_token 行，逐账号独立解析
- 🔐 **PC 自动化管道端点** — `/v0/admin/*` 系列端点（应用 Key 鉴权）：把新账号直接推进号池（带容量/节流/bot_flag 守卫）、上报上游失败走统一分类器、拉取面板概览
- 🔁 **refresh_token 轮换防护** — 续期写回前先比对当前值，防止把已轮换的旧 RT 覆盖回去导致 refresh 断链

### ③ 协议网关

- 🔌 **三协议兼容** — OpenAI Chat (`/v1/chat/completions`)、Anthropic Messages (`/v1/messages`)、OpenAI Responses (`/v1/responses`)，均为**流式 + 非流式**
- 🧠 **思维链适配** — `reasoning_content` 多轮回填与 effort 档位别名（`grok-4.6-low/-high/-xhigh`）
- 🔑 **应用 Key 管理** — 创建 / 启停 / 删除 API Key，加密存储（AES-GCM）
- 📊 **余额与用量** — 24h 滚动窗口实时统计 + 上游确认值并列参考

### 工程化

- 🛡 **保活加固** — 前台服务 + WakeLock + 开机自启 + 精确闹钟兜底重启；worker 池 16 并发，断连自愈不僵死
- 🩹 **崩溃与失败自动留档** — 全局崩溃捕获 + 批次失败总结，**只落本地、不联网**，导出前统一脱敏（见下文）
- ✅ **JVM 单测 + GitHub Actions CI** — 风控分类与日志脱敏抽成无 Android 依赖的纯函数并覆盖单测；CI 先跑单测再验证 release 打包

## 快速开始

### 0. 获取 APK

到 [Releases](../../releases) 下载最新 APK 直接安装（`-farm` 为含产号农场的完整版）。

### 1. 自己构建

```bash
# 依赖：JDK 17、Android SDK（compileSdk 36）；Gradle 用仓库自带 wrapper
echo "sdk.dir=你的Android/SDK路径" > local.properties
./gradlew assembleRelease     # 已配置 debug-keystore 签名，出包即可安装
```

仓库自带 [CI 工作流](.github/workflows/ci.yml)（单测 → assembleRelease），push 到 main 自动触发，也可在 Actions 页手动 `workflow_dispatch`。

### 2. 启动网关

安装后在应用内点击「启动服务」，然后电脑上：

```bash
adb forward tcp:8789 tcp:8789
curl http://127.0.0.1:8789/health
```

> ⚠️ 部分 ROM（如 Android 16 定制系统）会拦截局域网入站，WiFi 直连可能不可用；adb 转发是最稳定通道。USB 拔插会丢转发，重跑一次即可。

### 3. 凑号（三条路，可混用）

| 方式 | 怎么做 | 适用 |
|---|---|---|
| **App 内产号** | 「产号」页 → 选邮箱源 → 开批 | 全自动，手机本地搞定 |
| **批量导入** | 「账号」页 → 一键导入，贴 JSON 或每行一个 refresh_token | 已有现成凭据 |
| **PC 管道推号** | `POST /v0/admin/accounts`（应用 Key 鉴权） | 电脑侧已有自动化脚本 |

产号前建议先在「产号设置」页确认三件事：

- **邮箱源** — 默认 `mail.tm` 免配置即可用；量大建议自建 Cloudflare 邮箱 / Cloud Mail（自有域名，不易被上游拉黑）
- **号间隔** — 默认 300s，**别调太小**，连续注册是最容易触发风控的动作
- **池容量与节流** — `farm_max_accounts`（默认 60）、`farm_import_min_gap_sec`（默认 300s）

> 也可以先在手机上点「登录 Grok 账号」走一次手动设备授权熟悉流程。**尽量少重新登录**——凭据自动续期，频繁登录才是风控高危动作。

### 4. 接入客户端

- Base URL：`http://127.0.0.1:8789/v1`
- API Key：App「应用」页查看
- 模型：`grok-4.6`（默认高推理，慢）/ `grok-4.6-low`（快速，推荐日常）/ `grok-composer-2.5-fast` 等

---

## 出错了怎么反馈（错误日志渠道）

**遇到问题请到 [Issues](../../issues/new/choose) 提交，用「🐞 Bug 报告」表单**——它会把定位问题需要的信息一次问全，省得来回追问。想聊用法和思路可以去 [Discussions](../../discussions)。

日志怎么拿：

1. App「产号」页点 **「诊断」** → 选择保存位置（一般选 Download）
2. 导出的 `grok2api-diag-*.txt` 就是**已脱敏**的失败总结（按失败种类去重 + 附日志尾部）
3. 在 issue 表单里粘贴内容，或把文件直接拖进 issue 输入框作为附件

**关于脱敏，可以放心**：诊断报告在落盘前统一过一遍 [Redact](app/src/main/java/com/grok2api/gateway/Redact.kt)——邮箱本地部分、验证码、cookie / token / JWT 全部隐去。报告**只在本机生成，App 不会自动上传任何东西**：本仓库是公开仓库，自动建 issue 等于把用户日志公开到互联网，所以刻意不做上报。

> 本机 `files/farm.log` 保持原始内容（自己 `run-as` 排查要看真值）。**要发出去请只用「诊断」导出的那份**。

## 设置项

| 设置 | 默认 | 说明 |
|---|---|---|
| 额度自动刷新时段 | `9,21` | 定时向 xAI 探测账号限额状态 |
| 免费额度上限 | `500000` | 单账号 24h 窗口 token 上限（余额计算基准） |
| 使用记录保留天数 | `30` | 过期自动清理并 VACUUM |
| 保活开关 | 开 | 前台服务保活 |
| 产号邮箱源 | `mailtm` | `mailtm` / `duckmail` / `yyds` / `cloudflare` / `cloudmail` |
| 号间隔 | `300` | 两次注册/补登之间的最小间隔秒数 |
| 池容量上限 | `60` | 超过后拒绝新号入库 |
| 定时补号阈值 | `70` | 可用率低于该值发通知（配合 `farm_auto_batch`） |
| bot_flag 处置 | `warn` | `warn` 仅标记降权（默认）/ `disable` 停用 / `ignore` 忽略 |
| 网络类冷却 | `90` | 超时 / 5xx / 连接失败 |
| 风控类冷却 | `1800` | 限流 / 封锁 / 凭据被拒 |

## 风控说明（设计原则）

- 上游风控主要盯**登录与注册行为**：同设备频繁设备流授权、连续注册多账号是最高危动作。产号链路自带 canary 停批 + 号间隔，**别关掉**。
- **失败分类决定处置**：内容安全拒绝是软失败（号是好的，不冷却）；额度耗尽长冷却+恢复探测（不弃号）；只有真封锁/凭据损坏才出池。
- `bot_flag_source` 标记的号**降权不停用**（该字段已证明不可靠），最终质量以降智探测为准。
- 上游响应里的 Cloudflare 拦截页判定为 **IP 问题不是号问题**，只提示换出口。
- 所有风控判定收敛在 [RiskLogic.kt](app/src/main/java/com/grok2api/gateway/RiskLogic.kt)（纯函数，可单测），处置动作在 NativeCore。

## 已知限制

- xAI 免费档上游**响应较慢**（grok-4.6 默认档可达 50-110s），建议用 `-low` 档位
- 上游非流式 `/responses` 不可靠，网关内部始终用流式再自行汇总
- 上游无额度查询接口，余额为本地估算（被上游拒绝过一次后才有「已确认」的真实值）
- 静默后台全自动注册受系统约束（注册需要 WebView 保持"可见"），`farm_auto_batch` 只在通知提醒后才开批

## 风险提示与免责声明

- 本项目通过非官方通道调用 xAI Grok，**注册与调用均存在违反 xAI 服务条款导致账号受限/封禁的风险**，请自行评估，后果自负。
- **产号功能请仅用于你自有设备、自有账号的个人研究**；请勿用于批量注册倒卖、代他人养号、规避平台限制牟利等用途。
- 请遵守当地法律法规与平台服务条款，**不要用于任何违法用途**。
- 本项目与 xAI 无任何关联，不为账号安全、额度变化或服务可用性作任何担保；使用风险由使用者自行承担。

## License

MIT（继承自 [WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)）
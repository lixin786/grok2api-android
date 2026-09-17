# Grok2API · Android 原生版

> 把 **xAI Grok 免费额度**（Grok Build / CLI 通道），变成手机本地一个标准的 **OpenAI / Anthropic 兼容 API**。
> 纯原生 Android 实现（Kotlin），**无需 Python、无需 Docker、无需电脑常驻**——装到手机上就是一个可以挂号池、带风控体检的 Grok 代理网关。

> 架构源自 [WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)（MIT），
> 上游从腾讯 WorkBuddy 换成了 xAI Grok（`cli-chat-proxy.grok.com` Responses 协议）。

---

## 这是什么

一个跑在 Android 手机上的本地 API 网关 + **号池管理器**。启动后，你的电脑、平板或其它 App 可以通过
adb 端口转发连接手机，用标准 OpenAI / Anthropic 协议调用 Grok 的免费模型额度；号池侧内置账号轮询、
失败分类冷却、bot 风控标记检测、降智探测等一整套"号池卫生"机制。

```
你的电脑 / ZCode / Cherry Studio / Claude Code
        │  http://127.0.0.1:8789/v1/...（adb forward tcp:8789 tcp:8789）
        ▼
┌──────────────────────────────────────┐
│   手机 (Grok2API Native)              │
│  原生 HTTP 服务 (ServerSocket)        │
│  ├─ 三协议 → 统一 → xAI Responses     │
│  ├─ 号池：轮询 / 分级冷却 / 换号重试   │
│  ├─ 风控：失败分类 / bot_flag / 体检   │
│  └─ SQLite 记录用量与余额             │
└──────────────────────────────────────┘
        │  https://cli-chat-proxy.grok.com/v1
        ▼
     xAI Grok 上游
```

## 特性

**协议网关**

- 🔌 **三协议兼容** — OpenAI Chat (`/v1/chat/completions`)、Anthropic Messages (`/v1/messages`)、OpenAI Responses (`/v1/responses`)，均为**流式 + 非流式**
- 🧠 **思维链适配** — `reasoning_content` 多轮回填与 effort 档位别名（`grok-4.6-low/-high/-xhigh`）
- 🔑 **应用 Key 管理** — 创建 / 启停 / 删除 API Key，加密存储（AES-GCM）

**号池与风控**（v1.5 系列重点）

- 🔄 **账号轮询 + 换号重试** — 严格轮询（最久未用优先）保证额度均匀消耗；推理失败自动换号重试（最多 8 次）
- 🗂 **失败分类冷却** — 封锁/凭据失效出池、额度类长冷却+恢复探测、策略拒绝软失败不冷却、5xx/网络抖动短冷却，七类分开处置不再误伤
- 🚫 **bot 风控标记** — 解析 JWT `bot_flag_source`（数值型 1/2 = 被标记），入池守卫自动弃号、调度降权、面板警示
- 🩺 **两级号池体检** — 本地信号（秒级：JWT 标记/RT 缺失/冷却/连败）+ 上游探测（deep 模式），按 dead/warning/healthy 分级，异常号一键清理（停用不删除）
- 🧪 **降智探测** — 真实短对话看有无 reasoning token，作为 bot_flag 之外最终的账号质量判定
- 📥 **一键批量导入** — 兼容官方 grok2api（Go 版）的多种凭据格式：本 App 导出 JSON / 扁平 JSON（snake/camelCase）/ `{"accounts":[...]}` 批量文档 / 纯文本 refresh_token 行，逐账号独立解析
- 🔐 **PC 自动化管道端点** — `/v0/admin/*` 系列端点（应用 Key 鉴权）：注册管道可把新账号直接推进号池、上报上游失败走统一分类器、拉取面板概览，可配合自动注册工具实现全自动养号
- 🔁 **refresh_token 轮换防护** — 续期写回前先比对当前值，防止把已轮换的旧 RT 覆盖回去导致 refresh 链断裂

**工程化**

- 🛡 **保活加固** — 前台服务 + WakeLock + 开机自启 + 精确闹钟兜底重启；worker 池 16 并发，断连自愈不僵死
- ✅ **JVM 单测 + GitHub Actions CI** — 风控纯逻辑抽成无 Android 依赖的 `RiskLogic`，单测覆盖失败分类/冷却分级/bot 策略；CI 先跑单测再编译 release

## 快速开始

### 0. 获取 APK

到 [Releases](../../releases) 下载最新 APK 直接安装（`-test` 后缀为开发快照，核心功能均已真机验证）。

### 1. 自己构建

**本机构建**（Windows，命令行，无 Android Studio）：

```bash
# 依赖：JDK 17、Android SDK（compileSdk 36）、Gradle 8.14+（或直接用仓库里的 wrapper）
echo "sdk.dir=你的Android/SDK路径" > local.properties
./gradlew assembleRelease     # 已配置 debug-keystore 签名，出包即可安装
```

**GitHub Actions 云构建**：仓库自带 [CI 工作流](.github/workflows/ci.yml)（单测 → assembleRelease），
push 到 main 自动触发，也可在 Actions 页手动 `workflow_dispatch`。

### 2. 安装并启动

安装后在应用内点击「启动服务」，然后电脑上：

```bash
adb forward tcp:8789 tcp:8789
curl http://127.0.0.1:8789/health
```

> ⚠️ 部分 ROM（如 Android 16 定制系统）会拦截局域网入站，WiFi 直连可能不可用；adb 转发是最稳定通道。USB 拔插会丢转发，重跑一次即可。

### 3. 添加账号（两种方式）

- **手机上登录**：点「登录 Grok 账号」走 xAI 设备授权（内置 WebView，不跳浏览器）。**尽量少重新登录**——凭据自动续期，频繁登录才是风控高危动作。
- **电脑批量导入**：把各处搞到的 refresh_token 整理成文本（每行一个，`rt=` 前缀可有可无）或任意兼容 JSON，App「一键导入」自动识别格式逐号入池；或由 PC 注册管道直接 POST 到 `/v0/admin/accounts` 推号（带容量上限/节流/bot_flag 守卫）。

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

## 风控说明（设计原则）

- 上游风控主要盯**登录行为**：同设备频繁设备流登录多账号是最高危动作，导入 refresh_token 不算登录。
- **失败分类决定处置**：内容安全拒绝是软失败（号是好的，不冷却）；额度耗尽长冷却+恢复探测（不弃号）；只有真封锁/凭据损坏才出池。
- `bot_flag_source` 标记的号**降权不停用**（该字段已证明不可靠），最终质量以降智探测为准。
- 上游响应里的 Cloudflare 拦截页判定为 **IP 问题不是号问题**，只提示换出口。
- 所有风控判定收敛在 [RiskLogic.kt](app/src/main/java/com/grok2api/gateway/RiskLogic.kt)（纯函数，可单测），处置动作在 NativeCore。

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

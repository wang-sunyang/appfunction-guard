# AppFunction Guard

**Android 16 让 AI Agent 直接调用你 App 里的函数。Guard 是那套机制忘了给你的锁、门铃和访客登记簿。**

[English](README.md) · **简体中文** · [错误码协议](docs/ERROR_CODES.md) · [变更日志](CHANGELOG.md)

---

## 问题

Android 16 引入了 **AppFunctions**：Agent 可以绕过你的 UI，直接调用 App 内部函数。平台给了三样东西——

- 注册函数的方式
- 把函数描述给模型的方式
- 执行函数的方式

平台**没给**的：

| 缺失 | 后果 |
|---|---|
| 函数级授权 | 能调 `getNote` 的 Agent，也能调 `deleteAllNotes` |
| 参数校验 | 模型编出 `limit: -1`，你的代码照着用 |
| 限流 | 一个重试循环一分钟打你数据库四千次 |
| 审计 | 用户问"昨晚助手对我数据做了什么"，你答不上来 |
| 同意交互 | 用户完全不知道有 Agent 正在翻他的相册 |
| 结构化错误 | Agent 拿到一个 `IllegalArgumentException`，然后永远重试 |

这些不是假想。已有的 AppFunctions 安全研究列出了三类实打实的风险：**隐私泄露**（工具返回值）、**隔离突破**（函数暴露过宽）、**LLM 完整性攻击**（恶意工具描述）。三类都不是"缺 API"，是"缺策略"。

Guard 就是那层策略。

---

## 它做什么

平台与业务代码之间的唯一拦截点：

```
agent
  │
  ▼
AppFunctionService.onExecuteFunction()
  │
  ├─ 1. 调用方白名单     谁在调？
  ├─ 2. 策略判定         这个函数让不让调？
  ├─ 3. 令牌桶限流       按函数 × 调用方分桶
  ├─ 4. 参数校验         编译期由 KSP 生成
  ├─ 5. 用户确认         只在策略要求时出现
  ├─ 6. 执行业务         你的代码，原样不动
  ├─ 7. 脱敏             在返回值流向模型之前
  └─ 8. 审计             每次调用一条，永不记录参数原文
```

全部在业务代码之前执行。不是装饰器，不会漏加。

---

## 安装

> **Maven 坐标（2026-11）：** groupId 已迁移到 `io.github.wang-sunyang`（GitHub 账号改名所致）。只有 groupId 变了，artifactId 不变。
>
> `0.1.0-alpha5` **尚未发布到 Maven Central**。在此之前发布的版本（截至 `0.1.0-alpha4`）位于已冻结的旧 groupId 下，仅为历史留档，不会再更新。完整坐标变更记录见 [CHANGELOG.md](CHANGELOG.md)，切换清单见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。

```kotlin
dependencies {
    implementation("io.github.wang-sunyang:guard-core:0.1.0-alpha5")
    ksp("io.github.wang-sunyang:guard-ksp:0.1.0-alpha5")

    // 可选：现成的确认弹窗
    implementation("io.github.wang-sunyang:guard-ui:0.1.0-alpha5")
    // 可选：不带 Android 依赖的审计记录
    implementation("io.github.wang-sunyang:guard-audit:0.1.0-alpha5")
}
```

已在 Maven Central 发布，`repositories` 里有 `mavenCentral()` 就能解析，不需要 JitPack，也不需要 `includeBuild`。

---

## 快速接入

```kotlin
// 1. Application.onCreate
GuardConfig.init(this)
GuardConfig.confirmationHandler = MyConfirmDialog()   // 宿主自己实现，几行

// 2. 你的 AppFunctionService
override fun onExecuteFunction(functionId: String, args: Bundle): Bundle =
    BundleCodec.toBundle(
        AfGuard.intercept(functionId, args) { call ->
            when (call.functionId) {
                "listNotes"  -> repo.recent(call.intOr("limit", 20))
                "deleteNote" -> repo.delete(call.requireString("id"))
                else         -> null
            }
        }
    )

// 3. 在函数上声明策略
@Guard(policy = Policy.CONFIRM_EACH, risk = Risk.HIGH)
@RateLimit(calls = 5, perSeconds = 60)
@AppFunction
fun deleteNote(@TextLimit(max = 64) id: String): Boolean = repo.delete(id)
```

注解在编译期由 KSP 读取，热路径上没有反射。

---

## 模块

| 模块 | 类型 | 内容 |
|---|---|---|
| `guard-audit` | 纯 JVM | `AuditRecord`、`DecisionKind`、`AuditSink` + CSV / JSONL 导出。不依赖 Android，能在 CI 和纯 JVM 工具里跑 |
| `guard-core` | Android AAR | 拦截链、策略引擎、限流器、脱敏器、审计出口 |
| `guard-ksp` | KSP 处理器 | 编译期生成参数校验器与函数元数据表 |
| `guard-ui` | Android AAR | `DialogConfirmationHandler`：跟随生命周期、超时安全、主线程调用直接拒绝而不是死锁 |
| `guard-cli` | JVM 应用 | 从 JSONL 审计文件导出 / 汇总，给需要把日志拿出 App 的人 |
| `guard-mcp` | JVM 应用 | stdio MCP server，把策略引擎以 `guard_check_function` 工具暴露给任意 MCP 客户端 |

前四个是发布坐标，`guard-cli` 是可运行程序，不作为库依赖。

---

## 离线看审计日志

库只写"形状"：决策、函数名、调用方、参数指纹。导出来就是一份能交给审查者的文件：

```bash
gradle :guard-cli:installDist
./guard-cli/build/install/guard-cli/bin/guard-cli summary --in audit.jsonl
./guard-cli/build/install/guard-cli/bin/guard-cli export --in audit.jsonl --out audit.csv --format csv
```

解析不了的行会报出来，不会静默丢弃——被截断的审计日志比吵闹的日志危险得多。

---

## MCP server（`guard-mcp`）

如果你的 Agent 运行时说的是 [MCP](https://modelcontextprotocol.io) 而不是 AppFunctions，`guard-mcp` 用 stdio 把**同一套**策略引擎暴露出去。判定逻辑没有第二份实现：该模块直接编译 Guard 自己的 `PolicyEngine` / `RateLimiter` 源码，所以 MCP 侧的决定与 Android 侧的决定出自同一段代码。

```bash
gradle :guard-mcp:installDist
./guard-mcp/build/install/guard-mcp/bin/guard-mcp      # stdin/stdout 上跑 JSON-RPC 2.0
```

工具 `guard_check_function` 是一个**策略询问器**：回答“这次调用会不会被放行”，不执行业务函数。

| 入参 | 类型 | 含义 |
|---|---|---|
| `function_id` | string | 被守卫的函数，如 `deleteNote` |
| `caller_id` | string | `<包名>@<uid>`，如 `com.example.agent@10123` |
| `policy_context` | object | 可选：`policy`、`risk`、`rate_limit`、`consent` |

| 出参 | 类型 | 含义 |
|---|---|---|
| `allowed` | boolean | 是否放行 |
| `code` | string | `ALLOW` / `CONFIRMATION_REQUIRED` / 或某个 `GuardErrorCode` |
| `retry_after_ms` | integer \| null | 仅 `RATE_LIMITED` 时有值 |

机器可读元数据（YAML，含 input/output schema）见英文版 README 的 MCP 章节，两份内容一致。

`ALWAYS_DENY` 永不返回 `allowed: true`，`CONFIRM_ONCE` 对 `Risk.CRITICAL` 永不自动放行 —— 与 Android 库是同两条规则。`CONFIRM_*` 在 MCP 侧返回 `allowed: false` + `code: CONFIRMATION_REQUIRED`，因为 MCP server 没有 UI，不能替用户点头。

---

## 错误协议

即使你最终不用这个库，这部分也值得抄走。

```json
{
  "afg_error": {
    "code": "RATE_LIMITED",
    "function": "deleteNote",
    "message": "Rate limit exceeded for 'deleteNote'",
    "retryAfterMs": 12000,
    "retryable": true,
    "hint": "Too many calls. Wait for retryAfterMs, then retry."
  }
}
```

`retryable` 是最关键的字段。模型的默认行为是"失败就再试一次"，`retryable: false` 是唯一能可靠掐断这个循环的信号。

| code | retryable | 含义 |
|---|---|---|
| `INVALID_ARGUMENT` | 是 | 按 `expected` 修正参数后重试 |
| `MISSING_ARGUMENT` | 是 | 补上 `field` |
| `RATE_LIMITED` | 是 | 等 `retryAfterMs` |
| `USER_DENIED` | **否** | 人拒绝了。停。 |
| `NOT_AUTHORIZED` | **否** | 调用方不被允许 |
| `FUNCTION_BLOCKED` | **否** | 永久关闭 |
| `UPSTREAM_ERROR` | **否** | 你的代码坏了，别再打 |

完整说明：[docs/ERROR_CODES.md](docs/ERROR_CODES.md)

---

## 注解一览

| 注解 | 作用位置 | 用途 |
|---|---|---|
| `@Guard(policy, risk, redactOutput, idempotent)` | 函数 | 授权策略 |
| `@RateLimit(calls, perSeconds, scope, burst)` | 函数 | 令牌桶限流 |
| `@TextLimit(min, max)` | String 参数 | 长度边界 |
| `@IntRange(min, max)` | Int/Long 参数 | 数值边界 |
| `@Pattern(regex)` | String 参数 | 格式校验 |
| `@OneOfVal(values)` | String 参数 | 白名单取值 |
| `@Sensitive(kind)` | 参数 | 标记 PII，供确认弹窗展示 |

四种策略：

- `ALWAYS_DENY` — Agent 永远碰不到这个函数
- `CONFIRM_EACH` — 每次都要用户点头
- `CONFIRM_ONCE` — 同意后记住 `consentTtlMillis`（默认 7 天）；**对 `Risk.CRITICAL` 不生效**
- `ALLOW` — 放行，但仍然限流、仍然审计

---

## 几个刻意的设计取舍

**库不 import 任何 `android.app.appfunctions.*`。**

那套 API 还在 alpha，alpha08 到 alpha10 之间确实删过组件。如果 Guard 依赖那些类型，平台一动库就废。所以 Guard 只操作 `String functionId` + `Bundle args` —— 这两个东西跨版本都在。平台怎么变，只改你自己 Service 里那一行。

**审计永不记录参数值。**

只记参数**结构**的 SHA-256 指纹，和脱敏命中数。一个安全库如果把用户手机号写进日志，它自己就是新漏洞。

**`CONFIRM_ONCE` 不对 `CRITICAL` 生效。**

便利性不该有能力把破坏性操作变成永久的。

**配置缺失时失败关闭。**

函数需要确认但没装 `ConfirmationHandler` → 拒绝。不是放行，不是忽略，是拒绝。

**降级要留痕。**

KSP 生成的表缺失时（构建配置错了），校验被跳过，但每条审计记录标 `degraded = true`。静默失败比崩溃更糟。

---

## 环境要求

- `compileSdk 37`（AGP 9.1+，Gradle 9.3.1+）
- `minSdk 26` —— 库本身只碰 `Bundle` + `Binder`，低版本也能跑；**AppFunctions 本身要求设备是 API 36**
- Kotlin 2.x
- KSP

```kotlin
// 坐标与上面「安装」一致
implementation("io.github.wang-sunyang:guard-core:0.1.0-alpha5")
ksp("io.github.wang-sunyang:guard-ksp:0.1.0-alpha5")
```

库模块已发布到 Maven Central，直接从 `https://repo1.maven.org/maven2/` 解析，不用 JitPack，也不用本地构建。`0.1.0-alpha5`（新 groupId 的首个版本）尚未发布；发版流程与坐标切换清单见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。

---

## 当前状态

**alpha，不可用于生产。**

诚实清单：

- [x] 核心拦截链、策略引擎、限流器、脱敏器、审计、错误协议
- [x] KSP 处理器（生成校验器 + 元数据表）
- [x] `guard-ui` 确认弹窗（`DialogConfirmationHandler`），sample 自己就在用
- [x] `guard-cli` 审计导出（CSV / JSONL + summary），CI 有冒烟
- [x] Maven Central —— `guard-core` / `guard-ksp` / `guard-audit` / `guard-ui` 的 `0.1.0-alpha4` 已发布，可直接从 `repo1.maven.org` 下载（GPG 签名，带 sources 与 javadoc）。该版本早于 groupId 迁移；`0.1.0-alpha5` 才是 `io.github.wang-sunyang` 下的首个版本。流程见 [docs/PUBLISHING.md](docs/PUBLISHING.md)
- [x] **真机端到端验证（阶段 B）** —— 2026-11-16 在 Android 17（API 37）真机上跑通：系统收录四个函数、`listNotes` 放行返回数据、`deleteNote` / `exportNotes` 首次 `USER_DENIED` 二次确认后执行、`wipeAllNotes` 返回 `FUNCTION_BLOCKED`。确认链路的拒绝与允许两个分支都验过。剩余手工项（弹窗外观与点击、超时后弹窗是否收起、杀进程审计清空、关弹窗权限降级、后台调用、关闭 AppFunction 开关）见 [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md)

早期阶段，最有价值的是对**注解设计**的反馈，不是 bug 报告。

---

## 同类项目核查

截至 2026-11-24（本次复核；上次核查为 2026 年 11 月上旬），没有找到给 AppFunctions 做函数级授权 / 校验 / 限流 / 审计的现成库。GitHub 上 `appfunctions` 大约 72 个仓库，基本是个位数 star 的样例；唯一沾边的是 `anyfn`（UI 自动化），解决的是另一个问题——它驱动界面，不守卫 API 面。官方样例几百 star，无策略层。

如果你知道有先行者，开 issue 告诉我，我挂链接。这比假装第一更有价值。

---

## 路线

| 周 | 目标 |
|---|---|
| W1 | 真机跑通端到端，写清楚到底哪里炸了 —— 阶段 B 已通过（2026-11-16），阶段 A 手工项待补 |
| W2 | `guard-ui` 确认面板 + 审计查看器 |
| W3 | `guard-cli` 导出、策略 lint（`afg check`） |
| W4 | Maven Central，v0.1.0 —— `0.1.0-alpha4` 已发布（首个真机验证版），`v0.1.0` 等剩余手工项 |

---

## 许可

按模块分别授权，不是整仓一个许可：

| 模块 | 许可 | 全文 |
|---|---|---|
| `guard-core`、`guard-ksp`、`guard-ui`、`guard-mcp`、`sample/` | AGPL-3.0-only | [LICENSE.AGPLv3](LICENSE.AGPLv3) |
| `guard-audit`、`guard-cli` | Apache-2.0 | [LICENSE.Apache-2.0](LICENSE.Apache-2.0) |

`guard-mcp` 与 `sample/` 归入 AGPL，原因是两者**链接**了防护层，而不是风格偏好：`guard-mcp` 把 `guard-core` 的 `PolicyEngine` / `RateLimiter` 源码编进自己的 jar，`sample/` 依赖 `guard-core` / `guard-ui` / `guard-ksp`。因此由这两个模块构建出的任何产物整体都受 AGPL-3.0 约束。注意第 13 条：把 `guard-mcp` 作为网络服务对外提供时，该服务的使用者有权获取对应源码——"闭源集成需买商业许可"的说法在这里并不适用。

为什么这么切：执行层（`guard-core` + 它的 KSP 处理器 + 确认弹窗）保持 copyleft，策略引擎的分叉也必须开源；不带执行层的可嵌入部件（审计模型 / 导出器、CLI）保持宽松，这样别人读 Guard 的审计格式不必继承 copyleft。宽松清单就是这些：只有下游消费者不编译、不链接 `guard-core` 才能入选；`guard-mcp` 与 `sample/` 都要编译或链接它，所以不入选。

**其他许可安排。** 如果上面的条款不适合你的产品（闭源、应用商店条件、公司内部禁用 AGPL），可以来谈：开一个标题为 `commercial license` 的 issue，或用 [AUTHORS.md](AUTHORS.md) 里的联系方式。是否提供替代方案取决于版权人的意愿，而非事先承诺的条款。许可为何这样划分、什么条件下会变，记录在 [docs/LICENSING.md](docs/LICENSING.md)。

**拆分之前的版本。** 截至 `0.1.0-alpha4` 的所有发布版本都是 MIT，已获取的人继续按 MIT 使用。按模块拆分从 `0.1.0-alpha5` 起生效。

---

*本项目与 Google 无关。AppFunctions 是 Android 平台 API，这是一个独立社区库。*

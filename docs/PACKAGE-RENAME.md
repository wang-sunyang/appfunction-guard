# 代码包名迁移（遗留待办 · C 项）

> **状态：暂缓，本文件只记录方案，未执行。** 执行前需先确定新包名（见文末建议）。

## 现状

Maven groupId 已于 2026-11 迁移到 `io.github.wang-sunyang`，但**代码里的 Kotlin 包名**仍是旧账号残留：

- 当前 namespace / 包名：`io.github.yangyang2857.afguard`
- 涉及模块：`guard-core`、`guard-audit`、`guard-cli`、`guard-ksp`、`guard-ui`、`sample`

## 为什么没和 groupId 一起改

groupId 与包名是两回事，且 **Java/Kotlin 包名不能含连字符**，无法直接复用 `io.github.wang-sunyang`。

groupId 含连字符（`io.github.wang-sunyang`）而包名去连字符（`io.github.wangsunyang.afguard`），两者不一致是行业常态（例如 `commons-io` ↔ `org.apache.commons.io`），不影响发布与使用。

## 迁移范围（执行前先全局搜一遍）

| 类型 | 位置 | 说明 |
|---|---|---|
| namespace | 各模块 `build.gradle.kts` 的 `namespace` | 如 `guard-core/build.gradle.kts` |
| mainClass | `guard-cli/build.gradle.kts` | `io.github.yangyang2857.afguard.cli.MainKt` |
| 源码 package | 各模块 `src/main/kotlin/io/github/yangyang2857/afguard/...` | 需同步移动目录 |
| 测试 package/import | 各模块 `src/test/...` | 同上 |
| KSP 常量 | `guard-ksp/.../GuardSymbolProcessor.kt` 的 `PKG` | 决定生成类的包名 |
| 生成类名常量 | `guard-core/.../ValidatorRegistry.kt` 的 `GENERATED_CLASS` | `...generated.AfGuardGeneratedValidators`，反射依赖 |
| 文档/示例 | `docs/DEVICE_TEST.md`、`docs/blog/w1-field-notes.md` | adb 命令、`Class.forName` |

## 新包名建议（待确认）

| 项目 | 旧 | 建议新 |
|---|---|---|
| namespace / 包名 | `io.github.yangyang2857.afguard` | `io.github.wangsunyang.afguard` |

## 执行步骤

1. 全局替换 `io.github.yangyang2857.afguard` → 新包名（含目录名）
2. 同步 `namespace`、`mainClass`、KSP `PKG`、`GENERATED_CLASS` 常量
3. 全量构建 + 测试（`./gradlew build` 或 CI）
4. 真机确认 KSP 生成的 `AfGuardGeneratedValidators` 类名与运行时反射一致

## 风险与时机

- 这是**二进制兼容性破坏**：任何引用过 `io.github.yangyang2857.afguard.*` 的代码都要改 import。
- 但项目仍处 alpha、几乎无外部用户 → **越早改越好**，拖到 1.0 代价更大。
- 建议与 `0.1.0-alpha5`（新坐标首版）**一并完成**，一次切干净，避免出现「groupId 是新的、包名还是旧的」的中间态。

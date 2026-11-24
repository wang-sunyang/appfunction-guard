// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard

/**
 * Agent 可读的错误码。
 *
 * 设计目标：让调用方 LLM 拿到失败结果时，能明确知道
 * "是要改参数、要等、还是要问人"，而不是只能盲目重试。
 *
 * [retryable] 是这里最关键的一个字段：
 * 明确的 false 能直接掐掉 Agent 的死循环重试。
 *
 * 单独成文件的原因：guard-mcp 是纯 JVM 模块，靠复用 guard-core 的源码共享判定逻辑，
 * 而同一目录下 GuardResult.kt 的 toJson() 依赖 Android 平台自带的 JSONObject。
 * 把纯数据枚举拎出来，纯 JVM 侧只编本文件，不必为一个 enum 引入 JSON 实现。
 */

enum class GuardErrorCode(
    val retryable: Boolean,
    val defaultHint: String
) {
    INVALID_ARGUMENT(
        retryable = true,
        defaultHint = "One or more arguments violate the declared constraints. Fix them and retry."
    ),
    MISSING_ARGUMENT(
        retryable = true,
        defaultHint = "A required argument is missing. Supply it and retry."
    ),
    RATE_LIMITED(
        retryable = true,
        defaultHint = "Too many calls. Wait for retryAfterMs, then retry."
    ),
    USER_DENIED(
        retryable = false,
        defaultHint = "The user explicitly denied this call. Do not retry. Ask the user first."
    ),
    NOT_AUTHORIZED(
        retryable = false,
        defaultHint = "This caller is not allowed to invoke this function. Do not retry."
    ),
    FUNCTION_BLOCKED(
        retryable = false,
        defaultHint = "This function is permanently closed to agents. Do not retry."
    ),
    FUNCTION_NOT_FOUND(
        retryable = false,
        defaultHint = "No such function is exposed by this app. Check the available function list."
    ),
    CONFIRMATION_TIMEOUT(
        retryable = true,
        defaultHint = "The user did not respond to the confirmation in time. You may retry once later."
    ),
    UPSTREAM_ERROR(
        retryable = false,
        defaultHint = "The app's own logic failed. Do not retry blindly; report it."
    )
}

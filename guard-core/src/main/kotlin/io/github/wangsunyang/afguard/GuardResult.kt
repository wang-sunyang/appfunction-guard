// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard

import org.json.JSONObject

/**
 * 结构化错误。
 *
 * 序列化后形如：
 * ```json
 * {
 *   "afg_error": {
 *     "code": "INVALID_ARGUMENT",
 *     "function": "deleteNote",
 *     "field": "id",
 *     "expected": "[A-Za-z0-9_-]+, length 1..64",
 *     "message": "id contains illegal character '/'",
 *     "retryable": true,
 *     "hint": "Fix the argument and retry."
 *   }
 * }
 * ```
 */
data class GuardError(
    val code: GuardErrorCode,
    val function: String,
    val message: String,
    val field: String? = null,
    val expected: String? = null,
    val retryAfterMs: Long? = null,
    val retryable: Boolean = code.retryable,
    val hint: String = code.defaultHint
) {
    /** 序列化为便于 LLM 解析的 JSON。不包含堆栈、不包含参数原文。 */
    fun toJson(): String {
        val body = JSONObject().apply {
            put("code", code.name)
            put("function", function)
            put("message", message)
            put("retryable", retryable)
            put("hint", hint)
            field?.let { put("field", it) }
            expected?.let { put("expected", it) }
            retryAfterMs?.let { put("retryAfterMs", it) }
        }
        return JSONObject().put("afg_error", body).toString()
    }

    companion object {
        fun invalidArgument(function: String, field: String, message: String, expected: String? = null) =
            GuardError(GuardErrorCode.INVALID_ARGUMENT, function, message, field = field, expected = expected)

        fun missing(function: String, field: String) =
            GuardError(
                GuardErrorCode.MISSING_ARGUMENT, function,
                message = "Required argument '$field' is missing", field = field
            )

        fun rateLimited(function: String, retryAfterMs: Long) =
            GuardError(
                GuardErrorCode.RATE_LIMITED, function,
                message = "Rate limit exceeded for '$function'",
                retryAfterMs = retryAfterMs
            )

        fun userDenied(function: String) =
            GuardError(GuardErrorCode.USER_DENIED, function, "User denied the call to '$function'")

        fun blocked(function: String) =
            GuardError(GuardErrorCode.FUNCTION_BLOCKED, function, "'$function' is not available to agents")

        fun notFound(function: String) =
            GuardError(GuardErrorCode.FUNCTION_NOT_FOUND, function, "Unknown function '$function'")

        fun upstream(function: String, cause: Throwable) =
            GuardError(
                GuardErrorCode.UPSTREAM_ERROR, function,
                "Function '$function' failed: ${cause.javaClass.simpleName}"
            )
    }
}

/** 拦截链的统一返回。 */
sealed class GuardResult {
    /** 业务执行成功。[value] 可能已脱敏。 */
    data class Ok(val value: Any?, val redacted: Boolean = false) : GuardResult()

    /** 被拦截或业务失败。 */
    data class Err(val error: GuardError) : GuardResult()

    companion object {
        fun ok(value: Any?): GuardResult = Ok(value)
        fun err(error: GuardError): GuardResult = Err(error)
    }
}

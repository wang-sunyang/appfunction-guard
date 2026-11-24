// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.mcp

import io.github.wangsunyang.afguard.CallerIdentity
import io.github.wangsunyang.afguard.GuardErrorCode
import io.github.wangsunyang.afguard.Policy
import io.github.wangsunyang.afguard.RateLimit
import io.github.wangsunyang.afguard.RateScope
import io.github.wangsunyang.afguard.Risk
import io.github.wangsunyang.afguard.internal.Decision
import io.github.wangsunyang.afguard.internal.InMemoryConsentStore
import io.github.wangsunyang.afguard.internal.PolicyEngine
import io.github.wangsunyang.afguard.internal.RateLimiter
import org.json.JSONArray
import org.json.JSONObject

/**
 * `guard_check_function` 的判定核心。
 *
 * 它是一个策略询问器：回答"这次调用会不会被放行"，**不执行业务函数**。
 * 判定顺序与 Android 侧 `AfGuard.intercept` 保持一致的部分是：
 * 先看策略（ALWAYS_DENY 直接拒），再消耗限流令牌，最后才谈确认。
 * 顺序反了就会出现"被永久拒绝的调用照样打满令牌桶"这种假限流。
 *
 * 与 Android 侧唯一刻意的差异：`CONFIRM_*` 不返回 allowed，因为 MCP server
 * 没有 UI，不能替用户点头。它返回 `CONFIRMATION_REQUIRED`，让宿主 Agent 去问人。
 */
class GuardCheckTool(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val consentStore = InMemoryConsentStore()
    private val policyEngine = PolicyEngine(consentStore, clock)
    private val rateLimiter = RateLimiter(clock)

    /** 一次判定的结果。`retryAfterMs` 只在限流时有值。 */
    data class Outcome(
        val allowed: Boolean,
        val code: String,
        val retryAfterMs: Long?,
        val retryable: Boolean,
        val hint: String,
        val policy: Policy,
        val risk: Risk
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("allowed", allowed)
            put("code", code)
            put("retry_after_ms", retryAfterMs ?: JSONObject.NULL)
            put("retryable", retryable)
            put("hint", hint)
            put("policy", policy.name)
            put("risk", risk.name)
        }

        companion object {
            internal fun allow(policy: Policy, risk: Risk) = Outcome(
                allowed = true,
                code = "ALLOW",
                retryAfterMs = null,
                retryable = false,
                hint = "The call would be allowed.",
                policy = policy,
                risk = risk
            )

            internal fun deny(code: GuardErrorCode, policy: Policy, risk: Risk) = Outcome(
                allowed = false,
                code = code.name,
                retryAfterMs = null,
                retryable = code.retryable,
                hint = code.defaultHint,
                policy = policy,
                risk = risk
            )

            internal fun rateLimited(retryAfterMs: Long, policy: Policy, risk: Risk) = Outcome(
                allowed = false,
                code = GuardErrorCode.RATE_LIMITED.name,
                retryAfterMs = retryAfterMs,
                retryable = true,
                hint = "Wait ${retryAfterMs}ms, then retry.",
                policy = policy,
                risk = risk
            )

            internal fun needsConfirmation(policy: Policy, risk: Risk) = Outcome(
                allowed = false,
                code = GuardCheckTool.CODE_CONFIRMATION_REQUIRED,
                retryAfterMs = null,
                retryable = false,
                hint = "A human must approve this call; an MCP server cannot grant consent on the user's behalf.",
                policy = policy,
                risk = risk
            )
        }
    }

    /** MCP `tools/list` 里的一项。字段与 README 的 YAML 元数据一一对应。 */
    fun descriptor(): JSONObject = JSONObject().apply {
        put("name", TOOL_NAME)
        put("description",
            "Policy oracle for AppFunctions-style tool calls. Returns whether a function call " +
                "would be allowed, denied, rate-limited or require user confirmation, without executing it.")
        put("inputSchema", inputSchema())
    }

    private fun inputSchema(): JSONObject {
        val props = JSONObject().apply {
            put("function_id", JSONObject().apply {
                put("type", "string")
                put("description", "Function identifier as declared by the app, e.g. deleteNote")
            })
            put("caller_id", JSONObject().apply {
                put("type", "string")
                put("description", "Caller identity as <packageName>@<uid>")
                put("pattern", CALLER_PATTERN.pattern)
            })
            put("policy_context", JSONObject().apply {
                put("type", "object")
                put("description", "Optional policy override; defaults match Guard's @Guard default (CONFIRM_ONCE / MEDIUM)")
                put("properties", JSONObject().apply {
                    put("policy", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("ALWAYS_DENY", "CONFIRM_EACH", "CONFIRM_ONCE", "ALLOW")))
                    })
                    put("risk", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")))
                    })
                    put("rate_limit", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("calls", JSONObject().put("type", "integer").put("default", 10))
                            put("per_seconds", JSONObject().put("type", "integer").put("default", 60))
                            put("burst", JSONObject().put("type", "integer").put("default", 3))
                            put("scope", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray(listOf("PER_CALLER", "GLOBAL")))
                            })
                        })
                    })
                    put("consent", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("granted", JSONObject().put("type", "boolean"))
                            put("expires_at_ms", JSONObject().put("type", "integer"))
                        })
                    })
                })
            })
        }
        return JSONObject().apply {
            put("type", "object")
            put("required", JSONArray(listOf("function_id", "caller_id")))
            put("properties", props)
        }
    }

    /**
     * 处理一次 `tools/call`。[arguments] 为 MCP params.arguments。
     * 返回值已经是 MCP 的 tool result 结构。
     */
    fun call(arguments: JSONObject?): JSONObject {
        val outcome = evaluate(arguments ?: JSONObject())
        return toolResult(outcome.toJson().toString(), isError = false)
    }

    /** 纯判定，不包 MCP 外壳。测试直接打这里。 */
    fun evaluate(arguments: JSONObject): Outcome {
        val functionId = arguments.optString("function_id", "").trim()
        if (functionId.isEmpty()) {
            return Outcome.deny(GuardErrorCode.MISSING_ARGUMENT, Policy.CONFIRM_ONCE, Risk.MEDIUM)
        }

        val callerId = arguments.optString("caller_id", "").trim()
        if (callerId.isEmpty()) {
            return Outcome.deny(GuardErrorCode.MISSING_ARGUMENT, Policy.CONFIRM_ONCE, Risk.MEDIUM)
        }
        if (!CALLER_PATTERN.matches(callerId)) {
            return Outcome.deny(GuardErrorCode.INVALID_ARGUMENT, Policy.CONFIRM_ONCE, Risk.MEDIUM)
        }

        val ctx = arguments.optJSONObject("policy_context") ?: JSONObject()
        val policy = parseEnum(Policy.entries, ctx.optString("policy", Policy.CONFIRM_ONCE.name))
            ?: return Outcome.deny(GuardErrorCode.INVALID_ARGUMENT, Policy.CONFIRM_ONCE, Risk.MEDIUM)
        val risk = parseEnum(Risk.entries, ctx.optString("risk", Risk.MEDIUM.name))
            ?: return Outcome.deny(GuardErrorCode.INVALID_ARGUMENT, policy, Risk.MEDIUM)

        val caller = parseCaller(callerId)
        applyConsent(functionId, caller, ctx)

        // 1. 策略。ALWAYS_DENY 在这里就结束，不消耗限流令牌。
        val decision = policyEngine.evaluate(functionId, policy, risk, caller)
        when (decision) {
            Decision.Allow -> Unit
            is Decision.Deny -> return Outcome.deny(decision.code, policy, risk)
            Decision.NeedsConfirmation -> return Outcome.needsConfirmation(policy, risk)
        }

        // 2. 限流。只有策略放行的调用才消耗令牌。
        val spec = ctx.optJSONObject("rate_limit")?.let { parseRateLimit(it) }
        if (spec is RateSpec.Invalid) return Outcome.deny(GuardErrorCode.INVALID_ARGUMENT, policy, risk)
        if (spec is RateSpec.Spec) {
            val wait = rateLimiter.tryAcquire(functionId, spec.limit, caller.bucketKey)
            if (wait != null) return Outcome.rateLimited(wait, policy, risk)
        }

        return Outcome.allow(policy, risk)
    }

    private fun applyConsent(functionId: String, caller: CallerIdentity, ctx: JSONObject) {
        val consent = ctx.optJSONObject("consent") ?: return
        if (!consent.optBoolean("granted", false)) return
        val expiresAt = consent.optLong("expires_at_ms", clock() + DEFAULT_CONSENT_TTL_MS)
        consentStore.grant(functionId, caller.packageName, expiresAt)
    }

    private fun parseCaller(raw: String): CallerIdentity {
        val at = raw.lastIndexOf('@')
        val pkg = raw.substring(0, at)
        val uid = raw.substring(at + 1).toInt()
        return CallerIdentity(packageName = pkg, uid = uid)
    }

    private sealed class RateSpec {
        data class Spec(val limit: RateLimit) : RateSpec()
        data object Invalid : RateSpec()
    }

    private fun parseRateLimit(raw: JSONObject): RateSpec {
        val calls = raw.optInt("calls", 10)
        val perSeconds = raw.optInt("per_seconds", 60)
        val burst = raw.optInt("burst", 3)
        if (calls < 1 || perSeconds < 1 || burst < 1) return RateSpec.Invalid
        val scope = parseEnum(RateScope.entries, raw.optString("scope", RateScope.PER_CALLER.name))
            ?: return RateSpec.Invalid
        return RateSpec.Spec(RateLimit(calls = calls, perSeconds = perSeconds, scope = scope, burst = burst))
    }

    private fun <T : Enum<T>> parseEnum(values: List<T>, raw: String): T? =
        values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    companion object {
        const val TOOL_NAME = "guard_check_function"
        const val CODE_CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
        private const val DEFAULT_CONSENT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private val CALLER_PATTERN = Regex("^[A-Za-z0-9_.]+@[0-9]+$")

        /** MCP tool result：content 数组里放一段 JSON 文本。 */
        fun toolResult(text: String, isError: Boolean): JSONObject = JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            }))
            put("isError", isError)
        }
    }
}

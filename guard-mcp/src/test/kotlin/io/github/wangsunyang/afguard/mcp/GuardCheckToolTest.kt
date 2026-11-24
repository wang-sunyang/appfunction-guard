// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardCheckToolTest {

    private var now = 1_700_000_000_000L

    private fun tool() = GuardCheckTool { now }

    private fun args(json: String) = JSONObject(json)

    private fun basic(
        functionId: String = "deleteNote",
        policy: String? = null,
        risk: String? = null,
        caller: String = "com.example.agent@10123"
    ): JSONObject {
        val ctx = JSONObject()
        if (policy != null) ctx.put("policy", policy)
        if (risk != null) ctx.put("risk", risk)
        return JSONObject()
            .put("function_id", functionId)
            .put("caller_id", caller)
            .put("policy_context", ctx)
    }

    @Test
    fun `always deny never allows`() {
        val out = tool().evaluate(basic(policy = "ALWAYS_DENY"))
        assertFalse(out.allowed)
        assertEquals("FUNCTION_BLOCKED", out.code)
        assertFalse(out.retryable)
    }

    @Test
    fun `always deny stays denied even with consent`() {
        val t = tool()
        val out = t.evaluate(
            args("""{"function_id":"deleteNote","caller_id":"com.example.agent@1",
                |"policy_context":{"policy":"ALWAYS_DENY","consent":{"granted":true}}}""".trimMargin())
        )
        assertEquals("FUNCTION_BLOCKED", out.code)
    }

    @Test
    fun `allow policy allows`() {
        val out = tool().evaluate(basic(policy = "ALLOW"))
        assertTrue(out.allowed)
        assertEquals("ALLOW", out.code)
        assertNull(out.retryAfterMs)
    }

    @Test
    fun `confirm once without consent asks the user`() {
        val out = tool().evaluate(basic(policy = "CONFIRM_ONCE", risk = "MEDIUM"))
        assertFalse(out.allowed)
        assertEquals(GuardCheckTool.CODE_CONFIRMATION_REQUIRED, out.code)
    }

    @Test
    fun `confirm once with consent allows`() {
        val t = tool()
        val out = t.evaluate(
            args("""{"function_id":"deleteNote","caller_id":"com.example.agent@1",
                |"policy_context":{"policy":"CONFIRM_ONCE","risk":"MEDIUM",
                |"consent":{"granted":true,"expires_at_ms":${now + 60_000}}}}""".trimMargin())
        )
        assertTrue(out.allowed)
    }

    @Test
    fun `confirm once never auto allows critical risk`() {
        val t = tool()
        val out = t.evaluate(
            args("""{"function_id":"wipeVault","caller_id":"com.example.agent@1",
                |"policy_context":{"policy":"CONFIRM_ONCE","risk":"CRITICAL",
                |"consent":{"granted":true,"expires_at_ms":${now + 60_000}}}}""".trimMargin())
        )
        assertFalse(out.allowed)
        assertEquals(GuardCheckTool.CODE_CONFIRMATION_REQUIRED, out.code)
    }

    @Test
    fun `confirm each always asks even with consent`() {
        val t = tool()
        val out = t.evaluate(
            args("""{"function_id":"deleteNote","caller_id":"com.example.agent@1",
                |"policy_context":{"policy":"CONFIRM_EACH","consent":{"granted":true}}}""".trimMargin())
        )
        assertEquals(GuardCheckTool.CODE_CONFIRMATION_REQUIRED, out.code)
    }

    @Test
    fun `expired consent is not honoured`() {
        val t = tool()
        val out = t.evaluate(
            args("""{"function_id":"deleteNote","caller_id":"com.example.agent@1",
                |"policy_context":{"policy":"CONFIRM_ONCE","risk":"LOW",
                |"consent":{"granted":true,"expires_at_ms":${now - 1}}}}""".trimMargin())
        )
        assertEquals(GuardCheckTool.CODE_CONFIRMATION_REQUIRED, out.code)
    }

    @Test
    fun `rate limit kicks in after the bucket is drained`() {
        val t = tool()
        val payload = args("""{"function_id":"sendSms","caller_id":"com.example.agent@1",
            |"policy_context":{"policy":"ALLOW","rate_limit":{"calls":2,"per_seconds":60,"burst":2}}}""".trimMargin())

        assertTrue(t.evaluate(payload).allowed)
        assertTrue(t.evaluate(payload).allowed)

        val third = t.evaluate(payload)
        assertFalse(third.allowed)
        assertEquals("RATE_LIMITED", third.code)
        assertNotNull(third.retryAfterMs)
        assertTrue(third.retryAfterMs!! > 0)
        assertTrue(third.retryable)
    }

    @Test
    fun `tokens refill over time`() {
        val t = tool()
        val payload = args("""{"function_id":"sendSms","caller_id":"com.example.agent@1",
            |"policy_context":{"policy":"ALLOW","rate_limit":{"calls":2,"per_seconds":60,"burst":2}}}""".trimMargin())
        repeat(2) { assertTrue(t.evaluate(payload).allowed) }
        assertEquals("RATE_LIMITED", t.evaluate(payload).code)

        now += 30_000
        assertTrue(t.evaluate(payload).allowed)
    }

    @Test
    fun `denied calls do not drain the bucket`() {
        val t = tool()
        val denied = args("""{"function_id":"mix","caller_id":"com.example.agent@1",
            |"policy_context":{"policy":"ALWAYS_DENY","rate_limit":{"calls":1,"per_seconds":60,"burst":1}}}""".trimMargin())
        repeat(5) { assertEquals("FUNCTION_BLOCKED", t.evaluate(denied).code) }

        val allowed = args("""{"function_id":"mix","caller_id":"com.example.agent@1",
            |"policy_context":{"policy":"ALLOW","rate_limit":{"calls":1,"per_seconds":60,"burst":1}}}""".trimMargin())
        assertTrue(t.evaluate(allowed).allowed)
    }

    @Test
    fun `bad caller id is rejected`() {
        val out = tool().evaluate(
            args("""{"function_id":"deleteNote","caller_id":"com.example.agent"}""")
        )
        assertFalse(out.allowed)
        assertEquals("INVALID_ARGUMENT", out.code)
    }

    @Test
    fun `missing function id is rejected`() {
        val out = tool().evaluate(args("""{"caller_id":"com.example.agent@1"}"""))
        assertEquals("MISSING_ARGUMENT", out.code)
    }

    @Test
    fun `unknown policy name is rejected`() {
        val out = tool().evaluate(basic(policy = "SOMETIMES"))
        assertEquals("INVALID_ARGUMENT", out.code)
    }

    @Test
    fun `call wraps the outcome in a tool result`() {
        val t = tool()
        val result = t.call(basic(policy = "ALLOW"))
        assertFalse(result.getBoolean("isError"))
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        val body = JSONObject(text)
        assertTrue(body.getBoolean("allowed"))
        assertEquals("ALLOW", body.getString("code"))
    }
}

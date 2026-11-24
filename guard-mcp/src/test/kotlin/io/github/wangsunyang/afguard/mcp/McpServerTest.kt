// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class McpServerTest {

    private val server = McpServer()

    private fun body(response: String?) = JSONObject(response!!)

    @Test
    fun `initialize reports protocol version and server info`() {
        val res = body(server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""))
        val result = res.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertEquals("guard-mcp", result.getJSONObject("serverInfo").getString("name"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
    }

    @Test
    fun `tools list exposes guard_check_function`() {
        val res = body(server.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
        val tools = res.getJSONObject("result").getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("guard_check_function", tool.getString("name"))
        val schema = tool.getJSONObject("inputSchema")
        val required = schema.getJSONArray("required")
        assertEquals("function_id", required.getString(0))
        assertEquals("caller_id", required.getString(1))
    }

    @Test
    fun `tools call returns the decision as text content`() {
        val res = body(server.handle("""
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"guard_check_function",
            "arguments":{"function_id":"deleteNote","caller_id":"com.example.agent@7",
            "policy_context":{"policy":"ALWAYS_DENY"}}}}
        """.trimIndent().replace("\n", "")))
        val result = res.getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("FUNCTION_BLOCKED", JSONObject(text).getString("code"))
    }

    @Test
    fun `unknown tool is reported as a tool error not a protocol error`() {
        val res = body(server.handle("""
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}
        """.trimIndent().replace("\n", "")))
        assertTrue(res.getJSONObject("result").getBoolean("isError"))
        assertFalse(res.has("error"))
    }

    @Test
    fun `unknown method returns method not found`() {
        val res = body(server.handle("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}"""))
        assertEquals(-32601, res.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `notifications are not answered`() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `malformed json returns parse error`() {
        val res = body(server.handle("{not json"))
        assertEquals(-32700, res.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `stdin loop answers each request in order`() {
        val input = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","method":"exit"}"""
        ).joinToString("\n")

        val out = ByteArrayOutputStream()
        McpServer().run(
            ByteArrayInputStream(input.toByteArray()).bufferedReader(),
            PrintStream(out, true, "UTF-8")
        )
        val lines = out.toString("UTF-8").trim().lines().filter { it.isNotBlank() }
        // initialize + tools/list 有应答，通知不发包，exit 只终止循环
        assertEquals(2, lines.size)
        assertEquals(1, JSONObject(lines[0]).getInt("id"))
        assertEquals(2, JSONObject(lines[1]).getInt("id"))
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.mcp

import java.io.BufferedReader
import java.io.PrintStream

/**
 * 极简 MCP server：JSON-RPC 2.0，一行一条消息，走 stdin/stdout。
 *
 * 只实现本模块真正需要的东西：`initialize` / `tools/list` / `tools/call` / `ping` /
 * `shutdown`。没有 SSE、没有 HTTP、没有会话恢复 —— 少一层就少一层出错的地方。
 * stdout 只允许出现协议消息，日志一律不许打到这里，否则客户端会解析失败。
 */
class McpServer(
    private val tool: GuardCheckTool = GuardCheckTool(),
    private val serverVersion: String = "0.1.0"
) {
    private class ExitSignal : RuntimeException()

    fun run(input: BufferedReader, output: PrintStream) {
        try {
            while (true) {
                val line = input.readLine() ?: return
                if (line.isBlank()) continue
                val response = handle(line) ?: continue
                output.println(response)
                output.flush()
            }
        } catch (_: ExitSignal) {
            return
        }
    }

    /** 处理一行输入，返回要写回的 JSON；返回 null 表示这条消息不需要应答。 */
    fun handle(line: String): String? {
        val request = try {
            JSONObject(line)
        } catch (_: Exception) {
            return errorResponse(null, -32700, "Parse error")
        }

        val hasId = request.has("id") && !request.isNull("id")
        val id = if (hasId) request.get("id") else null
        val method = request.optString("method", "")

        // 通知（无 id）一律不应答
        val isNotification = !hasId

        return when (method) {
            "initialize" -> {
                if (isNotification) null else ok(id, initializeResult(request.optJSONObject("params")))
            }

            "notifications/initialized", "notifications/cancelled" -> null

            "ping" -> if (isNotification) null else ok(id, JSONObject())

            "tools/list" -> {
                if (isNotification) null
                else ok(id, JSONObject().put("tools", JSONArray().put(tool.descriptor())))
            }

            "tools/call" -> {
                if (isNotification) null
                else {
                    val params = request.optJSONObject("params")
                    val name = params?.optString("name", "") ?: ""
                    if (name != GuardCheckTool.TOOL_NAME) {
                        ok(id, GuardCheckTool.toolResult(
                            JSONObject().put("error", "unknown tool: $name").toString(), isError = true
                        ))
                    } else {
                        ok(id, tool.call(params?.optJSONObject("arguments")))
                    }
                }
            }

            "shutdown" -> if (isNotification) null else ok(id, JSONObject())

            "exit" -> throw ExitSignal()

            "" -> errorResponse(id, -32600, "Invalid Request: missing method")

            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion", "")?.takeIf { it.isNotBlank() }
        return JSONObject().apply {
            put("protocolVersion", requested ?: PROTOCOL_VERSION)
            put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            put("serverInfo", JSONObject().apply {
                put("name", "guard-mcp")
                put("version", serverVersion)
            })
        }
    }

    private fun ok(id: Any?, result: JSONObject): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }.toString()

    private fun errorResponse(id: Any?, code: Int, message: String): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}

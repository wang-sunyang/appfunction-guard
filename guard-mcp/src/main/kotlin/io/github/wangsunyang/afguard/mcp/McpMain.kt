// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.mcp

/**
 * `guard-mcp` 的进程入口。启动后就是一条 stdin/stdout 的 JSON-RPC 管道，
 * 直到 stdin 关闭或客户端发 `exit`。
 *
 * 标准错误只用来在启动失败时留一行原因，不参与协议。
 */
fun main() {
    val server = McpServer()
    server.run(System.`in`.bufferedReader(Charsets.UTF_8), System.out)
}

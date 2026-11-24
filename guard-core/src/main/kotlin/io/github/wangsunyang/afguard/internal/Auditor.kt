// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.internal

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 审计模型已下沉到纯 JVM 模块 `:guard-audit`，这样 guard-cli 能在没有 Android SDK 的
 * 机器上解析、导出审计数据。
 *
 * 这里只留类型别名，保证 core / sample 里所有 `internal.AuditRecord` 之类的 import 与
 * 写法全部继续有效，对使用方零感知。
 */
typealias AuditRecord = io.github.wangsunyang.afguard.audit.AuditRecord
typealias AuditSink = io.github.wangsunyang.afguard.audit.AuditSink
typealias DecisionKind = io.github.wangsunyang.afguard.audit.DecisionKind
typealias RingBufferAuditSink = io.github.wangsunyang.afguard.audit.RingBufferAuditSink
typealias CompositeAuditSink = io.github.wangsunyang.afguard.audit.CompositeAuditSink

/**
 * 空实现，用于彻底关闭审计。
 *
 * 没有做成 typealias：Kotlin 的 typealias 指向 object 时不能在表达式位置当值使用，
 * 而 `GuardConfig.goSilent()` 里要写 `auditSink = NoopAuditSink`。
 */
object NoopAuditSink : AuditSink {
    override fun record(record: AuditRecord) = Unit
}

/** 进程内审计总线。 */
internal object AuditLog {
    private val sinks = CopyOnWriteArrayList<AuditSink>()

    fun install(sink: AuditSink) {
        sinks.clear()
        sinks.add(sink)
    }

    fun emit(record: AuditRecord) {
        sinks.forEach { runCatching { it.record(record) } }
    }
}

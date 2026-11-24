// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Wang Sunyang

package io.github.wangsunyang.afguard.mcp

/**
 * 极简 JSON：解析 + 序列化，只服务 guard-mcp 的协议层。
 *
 * 为什么不引第三方：guard-mcp 是本项目唯一的纯 JVM 产物，多一个运行时依赖就多一份
 * CVE 面和多一份分发负担。协议层实际只需要"从一行 JSON 里取几个字段、再拼几个字段"，
 * JDK 自带类型足够，不值得为此引一个通用库。
 *
 * 支持：object / array / string（含 `\uXXXX` 转义）/ number / true / false / null。
 * 不支持：重复键（后值覆盖前值）、超出 Long 范围的整数（降级为 Double）、注释、
 * 以及序列化 Flat 结构以外的自定义类型（`toString()` 兜底为字符串）。
 */

class JSONObject {

    private val map = LinkedHashMap<String, Any?>()

    constructor()

    constructor(source: String) {
        val parsed = JsonParser(source).parseDocument()
        if (parsed !is JSONObject) {
            throw IllegalArgumentException("JSON text is not an object: $source")
        }
        map.putAll(parsed.map)
    }

    fun put(key: String, value: Any?): JSONObject {
        map[key] = normalize(value)
        return this
    }

    fun has(key: String): Boolean = map.containsKey(key)

    /** 键不存在或值为 JSON null 时为 true。 */
    fun isNull(key: String): Boolean {
        if (!map.containsKey(key)) return true
        val value = map[key]
        return value == null || value === NULL
    }

    fun get(key: String): Any? {
        if (!map.containsKey(key)) throw IllegalArgumentException("no such key: $key")
        return map[key]
    }

    fun getString(key: String): String =
        get(key) as? String ?: throw IllegalArgumentException("value is not a string: $key")

    fun getInt(key: String): Int =
        (get(key) as? Number)?.toInt() ?: throw IllegalArgumentException("value is not a number: $key")

    fun getLong(key: String): Long =
        (get(key) as? Number)?.toLong() ?: throw IllegalArgumentException("value is not a number: $key")

    fun getBoolean(key: String): Boolean =
        get(key) as? Boolean ?: throw IllegalArgumentException("value is not a boolean: $key")

    fun getJSONObject(key: String): JSONObject =
        get(key) as? JSONObject ?: throw IllegalArgumentException("value is not an object: $key")

    fun getJSONArray(key: String): JSONArray =
        get(key) as? JSONArray ?: throw IllegalArgumentException("value is not an array: $key")

    fun optString(key: String, fallback: String = ""): String = map[key] as? String ?: fallback

    fun optInt(key: String, fallback: Int): Int = (map[key] as? Number)?.toInt() ?: fallback

    fun optLong(key: String, fallback: Long): Long = (map[key] as? Number)?.toLong() ?: fallback

    fun optBoolean(key: String, fallback: Boolean): Boolean = map[key] as? Boolean ?: fallback

    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject

    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    fun length(): Int = map.size

    fun keySet(): Set<String> = map.keys.toSet()

    override fun toString(): String {
        val out = StringBuilder()
        writeTo(out)
        return out.toString()
    }

    internal fun writeTo(out: StringBuilder) {
        out.append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) out.append(',')
            first = false
            writeJsonString(out, key)
            out.append(':')
            writeJsonValue(out, value)
        }
        out.append('}')
    }

    companion object {
        /** 可以被放进 [put] 的显式 JSON null。 */
        val NULL: Any = JsonNull

        private object JsonNull {
            override fun toString(): String = "null"
        }
    }
}

class JSONArray {

    private val values = ArrayList<Any?>()

    constructor()

    constructor(source: Collection<*>) {
        for (value in source) values.add(normalize(value))
    }

    constructor(source: String) {
        val parsed = JsonParser(source).parseDocument()
        if (parsed !is JSONArray) {
            throw IllegalArgumentException("JSON text is not an array: $source")
        }
        values.addAll(parsed.values)
    }

    fun put(value: Any?): JSONArray {
        values.add(normalize(value))
        return this
    }

    fun length(): Int = values.size

    fun get(index: Int): Any? = values[index]

    fun getString(index: Int): String =
        values[index] as? String ?: throw IllegalArgumentException("element $index is not a string")

    fun getInt(index: Int): Int =
        (values[index] as? Number)?.toInt()
            ?: throw IllegalArgumentException("element $index is not a number")

    fun getJSONObject(index: Int): JSONObject =
        values[index] as? JSONObject
            ?: throw IllegalArgumentException("element $index is not an object")

    override fun toString(): String {
        val out = StringBuilder()
        writeTo(out)
        return out.toString()
    }

    internal fun writeTo(out: StringBuilder) {
        out.append('[')
        var first = true
        for (value in values) {
            if (!first) out.append(',')
            first = false
            writeJsonValue(out, value)
        }
        out.append(']')
    }
}

private fun normalize(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Number -> value
    is Collection<*> -> JSONArray(value)
    is Map<*, *> -> {
        val obj = JSONObject()
        for ((key, item) in value) obj.put(key.toString(), item)
        obj
    }
    else -> value.toString()
}

private fun writeJsonValue(out: StringBuilder, value: Any?) {
    if (value == null || value === JSONObject.NULL) {
        out.append("null")
        return
    }
    when (value) {
        is String -> writeJsonString(out, value)
        is Boolean -> out.append(if (value) "true" else "false")
        is Byte, is Short, is Int, is Long -> out.append(value.toString())
        is Float -> writeJsonDouble(out, value.toDouble())
        is Double -> writeJsonDouble(out, value)
        is JSONObject -> value.writeTo(out)
        is JSONArray -> value.writeTo(out)
        else -> writeJsonString(out, value.toString())
    }
}

private fun writeJsonDouble(out: StringBuilder, value: Double) {
    if (value.isNaN() || value.isInfinite()) {
        out.append("null")
        return
    }
    val asLong = value.toLong()
    if (asLong.toDouble() == value) out.append(asLong.toString()) else out.append(value.toString())
}

private fun writeJsonString(out: StringBuilder, value: String) {
    out.append('"')
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '\b' -> out.append("\\b")
            ch.code == 12 -> out.append("\\f")
            ch.code < 0x20 -> out.append("\\u").append(String.format("%04x", ch.code))
            else -> out.append(ch)
        }
        index++
    }
    out.append('"')
}

private class JsonParser(private val text: String) {

    private var pos = 0

    fun parseDocument(): Any? {
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) {
            throw IllegalArgumentException("unexpected trailing content at index $pos")
        }
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (pos >= text.length) throw IllegalArgumentException("unexpected end of input")
        return when (val ch = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> {
                expectLiteral("true")
                true
            }
            'f' -> {
                expectLiteral("false")
                false
            }
            'n' -> {
                expectLiteral("null")
                null
            }
            else -> {
                if (ch == '-' || ch in '0'..'9') {
                    readNumber()
                } else {
                    throw IllegalArgumentException("unexpected character '$ch' at index $pos")
                }
            }
        }
    }

    private fun readObject(): JSONObject {
        pos++ // '{'
        val obj = JSONObject()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return obj
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw IllegalArgumentException("expected object key at index $pos")
            val key = readString()
            skipWhitespace()
            if (peek() != ':') throw IllegalArgumentException("expected ':' at index $pos")
            pos++
            obj.put(key, readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return obj
                }
                else -> throw IllegalArgumentException("expected ',' or '}' at index $pos")
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun readArray(): JSONArray {
        pos++ // '['
        val array = JSONArray()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return array
        }
        while (true) {
            array.put(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return array
                }
                else -> throw IllegalArgumentException("expected ',' or ']' at index $pos")
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun readString(): String {
        if (peek() != '"') throw IllegalArgumentException("expected string at index $pos")
        pos++
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos]
            pos++
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) throw IllegalArgumentException("unterminated escape")
                    val escape = text[pos]
                    pos++
                    when (escape) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append(Char(12))
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) {
                                throw IllegalArgumentException("truncated unicode escape")
                            }
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16)
                                ?: throw IllegalArgumentException("bad unicode escape: $hex")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$escape'")
                    }
                }
                else -> out.append(ch)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun readNumber(): Any {
        val start = pos
        if (pos < text.length && text[pos] == '-') pos++
        while (pos < text.length && text[pos] in '0'..'9') pos++
        var floating = false
        if (pos < text.length && text[pos] == '.') {
            floating = true
            pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            floating = true
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        val raw = text.substring(start, pos)
        if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("bad number at index $start")
        if (floating) {
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number: $raw")
        }
        return raw.toLongOrNull() ?: (raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number: $raw"))
    }

    private fun expectLiteral(literal: String) {
        if (!text.startsWith(literal, pos)) {
            throw IllegalArgumentException("expected '$literal' at index $pos")
        }
        pos += literal.length
    }

    private fun peek(): Char {
        if (pos >= text.length) throw IllegalArgumentException("unexpected end of input")
        return text[pos]
    }

    private fun skipWhitespace() {
        while (pos < text.length) {
            val ch = text[pos]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') pos++ else return
        }
    }
}

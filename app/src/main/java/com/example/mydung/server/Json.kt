package com.example.mydung.server

/**
 * JSON tối giản thuần Kotlin (object/array/string/number/boolean/null).
 *
 * Vì sao không dùng org.json: org.json nằm trong framework Android nên
 * unit test cục bộ (JVM thuần) và unit test trên CI đều không gọi được
 * (stub ném lỗi). Viết tay parser cho schema cố định của profile thì test
 * được ở cả 2 nơi, không cần thêm dependency Gradle. Định dạng dây vẫn
 * là JSON chuẩn nên mọi công cụ JSON đều đọc được file export.
 */
sealed class JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val list: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
}

/** Truy cập nhanh có kiểm tra kiểu (sai kiểu -> null). */
fun JsonValue.obj(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.map
fun JsonValue.arr(): List<JsonValue>? = (this as? JsonValue.Arr)?.list
fun JsonValue.str(): String? = (this as? JsonValue.Str)?.value
fun JsonValue.num(): Double? = (this as? JsonValue.Num)?.value
fun JsonValue.bool(): Boolean? = (this as? JsonValue.Bool)?.value
fun JsonValue.int(): Int? = num()?.takeIf { it.isFinite() }?.toInt()

object Json {

    /** Parse JSON. Lỗi cú pháp -> null. */
    fun parse(text: String): JsonValue? {
        val p = Parser(text)
        val v = p.value() ?: return null
        p.skipWs()
        if (!p.atEnd()) return null // còn rác sau giá trị
        return v
    }

    /** Ghi JSON 1 dòng. */
    fun stringify(v: JsonValue): String {
        val sb = StringBuilder()
        write(v, sb)
        return sb.toString()
    }

    private fun write(v: JsonValue, sb: StringBuilder) {
        when (v) {
            is JsonValue.Obj -> {
                sb.append('{')
                v.map.entries.forEachIndexed { i, (k, value) ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is JsonValue.Arr -> {
                sb.append('[')
                v.list.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(item, sb)
                }
                sb.append(']')
            }
            is JsonValue.Str -> writeString(v.value, sb)
            is JsonValue.Num -> {
                // Số nguyên ghi dạng nguyên (17 thay vì 17.0) cho file dễ đọc.
                val d = v.value
                if (d.isFinite() && d == kotlin.math.floor(d) && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) {
                    sb.append(d.toLong())
                } else {
                    sb.append(d)
                }
            }
            is JsonValue.Bool -> sb.append(v.value)
            JsonValue.Null -> sb.append("null")
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        fun value(): JsonValue? {
            skipWs()
            if (atEnd()) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()?.let { JsonValue.Str(it) }
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                '-', in '0'..'9' -> number()
                else -> null
            }
        }

        private fun literal(word: String, v: JsonValue): JsonValue? {
            if (!s.startsWith(word, i)) return null
            i += word.length
            return v
        }

        private fun obj(): JsonValue? {
            i++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (!atEnd() && s[i] == '}') {
                i++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWs()
                val key = string() ?: return null
                skipWs()
                if (atEnd() || s[i] != ':') return null
                i++
                val v = value() ?: return null
                map[key] = v // khóa trùng: lấy cái sau
                skipWs()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(map)
                    }
                    else -> return null
                }
            }
        }

        private fun arr(): JsonValue? {
            i++ // [
            val list = ArrayList<JsonValue>()
            skipWs()
            if (!atEnd() && s[i] == ']') {
                i++
                return JsonValue.Arr(list)
            }
            while (true) {
                val v = value() ?: return null
                list.add(v)
                skipWs()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(list)
                    }
                    else -> return null
                }
            }
        }

        private fun string(): String? {
            if (atEnd() || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) return null
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> {
                        if (c.code < 0x20) return null // ký tự điều khiển thô
                        sb.append(c)
                    }
                }
            }
            return null // chuỗi không đóng
        }

        private fun number(): JsonValue? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) return null
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                return null
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') return null
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') return null
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val d = s.substring(start, i).toDoubleOrNull() ?: return null
            if (!d.isFinite()) return null
            return JsonValue.Num(d)
        }
    }
}

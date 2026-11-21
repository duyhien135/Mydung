package com.example.mydung.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun primitives() {
        assertEquals(JsonValue.Str("abc"), Json.parse("\"abc\""))
        assertEquals(JsonValue.Num(17.0), Json.parse("17"))
        assertEquals(JsonValue.Num(-2.5), Json.parse("-2.5"))
        assertEquals(JsonValue.Num(1.0e3), Json.parse("1e3"))
        assertEquals(JsonValue.Bool(true), Json.parse("true"))
        assertEquals(JsonValue.Bool(false), Json.parse("false"))
        assertEquals(JsonValue.Null, Json.parse("null"))
    }

    @Test
    fun objectAndArray() {
        val v = Json.parse("""{"a": 1, "b": [1, 2, {"c": "x"}], "d": null}""")
        val obj = v?.obj()
        assertEquals(1.0, obj?.get("a")?.num())
        assertEquals(3, obj?.get("b")?.arr()?.size)
        assertEquals("x", obj?.get("b")?.arr()?.get(2)?.obj()?.get("c")?.str())
        assertEquals(JsonValue.Null, obj?.get("d"))
        assertEquals(emptyMap<String, JsonValue>(), Json.parse("{}")?.obj())
        assertEquals(emptyList<JsonValue>(), Json.parse("[]")?.arr())
    }

    @Test
    fun stringEscapes() {
        assertEquals("a\nb\"c\\d\te", Json.parse("\"a\\nb\\\"c\\\\d\\te\"")?.str())
        assertEquals("A", Json.parse("\"\\u0041\"")?.str())
        assertEquals("/", Json.parse("\"\\/\"")?.str())
    }

    @Test
    fun whitespaceIgnored() {
        assertEquals(JsonValue.Num(1.0), Json.parse("  \n\t 1 \r\n "))
        assertEquals(1.0, Json.parse("{ \"a\" : 1 }")?.obj()?.get("a")?.num())
    }

    @Test
    fun invalidJson_returnsNull() {
        assertNull(Json.parse(""))
        assertNull(Json.parse("   "))
        assertNull(Json.parse("{"))
        assertNull(Json.parse("[1, 2"))
        assertNull(Json.parse("{\"a\": }"))
        assertNull(Json.parse("{\"a\": 1,}")) // dấu phẩy thừa
        assertNull(Json.parse("[1 2]"))
        assertNull(Json.parse("\"chưa đóng"))
        assertNull(Json.parse("\"x\" rác")) // rác sau giá trị
        assertNull(Json.parse("01")) // số 0 đứng đầu
        assertNull(Json.parse("1."))
        assertNull(Json.parse("tru"))
        assertNull(Json.parse("\"a\\q\"")) // escape lạ
        assertNull(Json.parse("{\"a\": 1} {\"b\": 2}")) // 2 giá trị
    }

    @Test
    fun stringify_roundTrip() {
        val text = """{"name":"Free Fire","n":17,"f":1.5,"b":true,"z":null,"arr":[1,"x"],"o":{"k":"v"}}"""
        val parsed = Json.parse(text)
        assertEquals(text, Json.stringify(parsed!!))
    }

    @Test
    fun stringify_escapesStrings() {
        assertEquals("\"a\\n\\\"b\\\\\"", Json.stringify(JsonValue.Str("a\n\"b\\")))
        assertEquals("\"\\u0001\"", Json.stringify(JsonValue.Str("\u0001")))
    }

    @Test
    fun stringify_integersWithoutDecimal() {
        assertEquals("17", Json.stringify(JsonValue.Num(17.0)))
        assertEquals("-3", Json.stringify(JsonValue.Num(-3.0)))
        assertEquals("1.5", Json.stringify(JsonValue.Num(1.5)))
    }

    @Test
    fun helpers_wrongTypeReturnsNull() {
        val v = Json.parse("{\"a\": \"x\"}")!!
        assertNull(v.arr())
        assertNull(v.str())
        assertNull(v.obj()?.get("a")?.num())
        assertEquals("x", v.obj()?.get("a")?.str())
        assertEquals(17, JsonValue.Num(17.9).int()) // toInt cắt phần thập phân
    }
}

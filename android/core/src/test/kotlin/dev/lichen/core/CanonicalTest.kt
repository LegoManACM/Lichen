package dev.lichen.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CanonicalTest {
    private fun canon(text: String) = Canonical.encode(parseJson(text)!!).toString(Charsets.US_ASCII)

    @Test fun `matches every Python canonical vector`() {
        for (v in Vectors.arr("canonical")) {
            assertEquals(v.o()["canonical"].str(), canon(v.o()["json"].str()), v.o()["json"].str().take(120))
        }
    }

    @Test fun `escapes like ensure_ascii`() {
        assertEquals("\"\\u007f\\u00e9\\ud83d\\ude00\\n\\\"\\\\/\"", canon("\"\u007f\u00e9\uD83D\uDE00\\n\\\"\\\\/\""))
        assertEquals("{\"a\":1,\"b\":[true,false,null]}", canon("{ \"b\": [true, false, null], \"a\": 1 }"))
    }

    @Test fun `keys sort by code point not UTF-16 unit`() {
        // U+FFFF (one unit, 0xFFFF) sorts before U+1F600 (surrogates 0xD83D..) in Python, after it in UTF-16 order.
        assertEquals("{\"\\uffff\":1,\"\\ud83d\\ude00\":2}", canon("{\"\uD83D\uDE00\":2,\"\uFFFF\":1}"))
    }

    @Test fun `number literals survive a parse and re-encode`() {
        val text = "{\"a\":1.0,\"b\":1e-05,\"c\":1e+16,\"d\":1728750000123456,\"e\":-0.0}"
        assertEquals(text, canon(text))
        assertEquals(text, dumps(parseJson(text)!!))
    }

    @Test fun `pyFloat matches repr for known values`() {
        val cases = mapOf(1.0 to "1.0", 0.1 to "0.1", 1e-5 to "1e-05", 1e16 to "1e+16", 1e15 to "1000000000000000.0",
            1728750000.123456 to "1728750000.123456", 0.0001 to "0.0001", 0.0 to "0.0", -0.0 to "-0.0", 5e-324 to "5e-324",
            1.7976931348623157e308 to "1.7976931348623157e+308", 123456789012345680.0 to "1.2345678901234568e+17", -2.5 to "-2.5",
            100.0 to "100.0", 1e22 to "1e+22", 0.30000000000000004 to "0.30000000000000004", 1.5e-7 to "1.5e-07")
        for ((d, s) in cases) assertEquals(s, Canonical.pyFloat(d).content, s)
    }

    @Test fun `pyFloat matches the Python repr vectors`() {
        for (v in Vectors.arr("floats")) assertEquals(v.str(), Canonical.pyFloat(v.str().toDouble()).content)
    }

    @Test fun `now is a float literal`() {
        val n = Canonical.now().content
        assertNotNull(n.toDoubleOrNull()); assert('.' in n || 'e' in n)
    }

    @Test fun `parseObject only accepts objects`() {
        assertNotNull(parseObject("{}")); assertNull(parseObject("[]")); assertNull(parseObject("nope")); assertNull(parseObject(""))
    }

    // --- Kotlin -> Python -----------------------------------------------------------------------------

    private fun randomString(r: Random): String {
        val sb = StringBuilder()
        repeat(r.nextInt(12)) {
            val cp = when (r.nextInt(5)) {
                0 -> r.nextInt(0x80)
                1 -> r.nextInt(0x800)
                2 -> 0xE000 + r.nextInt(0x1FFF)  // BMP above surrogates
                3 -> 0x10000 + r.nextInt(0x10000)
                else -> 0x20 + r.nextInt(0x5f)
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun randomJson(r: Random, depth: Int = 0): JsonElement = when (r.nextInt(if (depth > 3) 6 else 8)) {
        0 -> JsonNull
        1 -> JsonPrimitive(r.nextBoolean())
        2 -> JsonPrimitive(r.nextLong() shr r.nextInt(60))
        3 -> Canonical.pyFloat(Double.fromBits(r.nextLong()).takeIf { it.isFinite() } ?: 1.5)
        4, 5 -> JsonPrimitive(randomString(r))
        6 -> JsonArray(List(r.nextInt(4)) { randomJson(r, depth + 1) })
        else -> JsonObject(List(r.nextInt(5)) { randomString(r) to randomJson(r, depth + 1) }.toMap())
    }

    @Test fun `Python canonicalizes random Kotlin objects to the same bytes`() {
        Py.require()
        val r = Random(7)
        val cases = (1..300).map { randomJson(r) } + Vectors.arr("canonical").map { parseJson(it.o()["json"].str())!! }
        val input = JsonArray(cases.map { JsonObject(mapOf("text" to JsonPrimitive(dumps(it)), "canon" to JsonPrimitive(Canonical.encode(it).toString(Charsets.US_ASCII)))) })
        val out = Py.run(
            """
from lichen.identity import canonical
bad = [c for c in json.load(sys.stdin) if canonical(json.loads(c["text"])).decode() != c["canon"]]
print(json.dumps(bad[:3]), len(bad))
""", dumps(input))
        assertEquals("[] 0", out.trim(), out)
    }

    @Test fun `pyFloat equals Python repr for many random doubles`() {
        Py.require()
        val r = Random(11)
        val doubles = ArrayList<Double>()
        repeat(20000) { Double.fromBits(r.nextLong()).takeIf { it.isFinite() }?.let(doubles::add) }
        repeat(10000) { doubles.add(1.6e9 + r.nextDouble() * 3e8) }
        repeat(10000) { doubles.add(Math.round(r.nextDouble() * 1e7) / Math.pow(10.0, r.nextInt(8).toDouble())) }
        repeat(10000) { doubles.add(r.nextDouble() * Math.pow(10.0, r.nextInt(50) - 25.0)) }
        repeat(2000) { doubles.add(r.nextInt(100000).toDouble() * Math.pow(10.0, r.nextInt(40) - 10.0)) }
        val mine = doubles.map { Canonical.pyFloat(it).content }
        val out = Py.run(
            """
import struct
for line in sys.stdin.read().split():
    print(repr(struct.unpack(">d", bytes.fromhex(line))[0]))
""", doubles.joinToString("\n") { "%016x".format(it.toRawBits()) }).trim().lines().map { it.trim() }
        assertEquals(mine.size, out.size)
        for (i in mine.indices) assertEquals(out[i], mine[i], "double ${doubles[i]}")
    }
}

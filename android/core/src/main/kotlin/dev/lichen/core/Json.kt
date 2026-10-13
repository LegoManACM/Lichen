package dev.lichen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// Everything that gets signed is hashed as canonical JSON, so these bytes must equal Python's
//   json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()
// exactly (identity.canonical). See PORTING.md.

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object Canonical {
    /** Deterministic encoding of [e]: sorted keys, no spaces, ASCII only (lowercase \u escapes). */
    fun encode(e: JsonElement): ByteArray = StringBuilder().also { write(e, it) }.toString().toByteArray(Charsets.US_ASCII)

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            // Numbers and booleans keep their literal text: a number parsed off the wire was written by
            // Python's repr already, so reusing it is what makes re-verification byte-exact.
            is JsonPrimitive -> if (e.isString) string(e.content, out) else out.append(e.content)
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                var first = true
                for (k in e.keys.sortedWith(CODE_POINT_ORDER)) {
                    if (!first) out.append(',')
                    first = false
                    string(k, out); out.append(':'); write(e.getValue(k), out)
                }
                out.append('}')
            }
        }
    }

    /** ensure_ascii: everything outside 0x20..0x7E becomes \uXXXX per UTF-16 unit (emoji -> surrogate pair). */
    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c in ' '..'~' -> out.append(c)
            else -> out.append("\\u").append(HEX[c.code shr 12 and 15]).append(HEX[c.code shr 8 and 15])
                .append(HEX[c.code shr 4 and 15]).append(HEX[c.code and 15])
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"

    /** Python repr(float) as an unquoted JSON number literal: shortest round-trip digits, `1.0`, `1e-05`, `1e+16`. */
    fun pyFloat(d: Double): JsonPrimitive {
        require(d.isFinite()) { "NaN/Infinity aren't valid JSON" }
        return JsonUnquotedLiteral(reprFloat(d))
    }

    /** time.time() as a canonical float. */
    fun now(): JsonPrimitive = pyFloat(nowSeconds())

    fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    internal fun reprFloat(d: Double): String {
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        // Shortest digit string that parses back to d (Java's Double.toString isn't shortest everywhere,
        // notably on older Android runtimes), rounding the exact value like Python's dtoa does.
        val exact = BigDecimal(Math.abs(d))
        var digits = ""
        var decpt = 0
        for (p in 1..17) {
            val r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (r.toDouble() == Math.abs(d) || p == 17) {
                val unscaled = r.unscaledValue().toString().trimEnd('0').ifEmpty { "0" }
                digits = unscaled
                decpt = r.precision() - r.scale()  // value = 0.DIGITS x 10^decpt
                break
            }
        }
        val sign = if (d < 0) "-" else ""
        val n = digits.length
        val body = if (decpt > 16 || decpt < -3) {
            val e = decpt - 1
            val mant = if (n == 1) digits else digits[0] + "." + digits.substring(1)
            mant + "e" + (if (e < 0) "-" else "+") + Math.abs(e).toString().padStart(2, '0')
        } else if (decpt <= 0) {
            "0." + "0".repeat(-decpt) + digits
        } else if (decpt >= n) {
            digits + "0".repeat(decpt - n) + ".0"
        } else digits.substring(0, decpt) + "." + digits.substring(decpt)
        return sign + body
    }
}

/** Python's default string ordering (by code point, not UTF-16 unit). */
val CODE_POINT_ORDER = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return@Comparator x.compareTo(y)
        i += Character.charCount(x); j += Character.charCount(y)
    }
    (a.length - i).compareTo(b.length - j)
}

/** Parse [text] as a JSON object; null if it isn't valid JSON or isn't an object. */
fun parseObject(text: String): JsonObject? =
    try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }

/** Parse any JSON value; null if invalid. */
fun parseJson(text: String): JsonElement? =
    try { Json.parseToJsonElement(text) } catch (e: Exception) { null }

/** Normal (non-canonical) wire JSON. Number literals are kept as they were. */
fun dumps(e: JsonElement): String = e.toString()

// --- small accessors, so callers don't repeat `(x as? JsonPrimitive)?.takeIf { ... }` ---------------

/** The text of a JSON string, or null if this isn't one. */
fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number (not a numeric string) as Double, else null. */
fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()

/** A JSON integer (or integral float like 3.0, as Python's int() would accept) as Long, else null. */
fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.let {
    it.toLongOrNull() ?: it.toDoubleOrNull()?.takeIf { d -> d.isFinite() }?.toLong()
}

fun stringArray(items: Iterable<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

inline fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

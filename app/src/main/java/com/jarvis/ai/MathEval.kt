package com.jarvis.ai

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Offline calculator for spoken math like "what is 12 times 8 plus 3". */
object MathEval {

    fun tryAnswer(text: String): String? {
        var s = text.lowercase(Locale.US)
            .replace(Regex("^(what is|what's|whats|calculate|compute|how much is|solve|evaluate)\\s+"), "")
            .trim()

        Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:%|percent) of (\\d+(?:\\.\\d+)?)$").find(s)?.let {
            val r = it.groupValues[1].toDouble() / 100.0 * it.groupValues[2].toDouble()
            return "${it.groupValues[1]} percent of ${it.groupValues[2]} is ${fmt(r)}."
        }
        Regex("^(?:the )?square root of (\\d+(?:\\.\\d+)?)$").find(s)?.let {
            return "The square root of ${it.groupValues[1]} is ${fmt(sqrt(it.groupValues[1].toDouble()))}."
        }

        s = s.replace(Regex("(\\d),(\\d{3})"), "$1$2")
            .replace("multiplied by", "*").replace("times", "*").replace(" x ", " * ").replace("×", "*")
            .replace("divided by", "/").replace(" over ", " / ").replace("÷", "/")
            .replace("plus", "+").replace("minus", "-")
            .replace("to the power of", "^").replace("power", "^")
            .replace("squared", "^2").replace("cubed", "^3")
            .trim()

        if (!s.matches(Regex("[0-9+\\-*/^(). ]+"))) return null
        if (!s.contains(Regex("\\d")) || !s.contains(Regex("[+\\-*/^]"))) return null
        return try {
            val v = Parser(s.replace(" ", "")).parse()
            if (v.isNaN() || v.isInfinite()) "That doesn't have a real answer." else "That's ${fmt(v)}."
        } catch (_: Exception) {
            null
        }
    }

    fun fmt(v: Double): String =
        if (v == floor(v) && abs(v) < 1e15) v.toLong().toString()
        else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("bad input")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        fun factor(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return base.pow(factor())
            }
            return base
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("number expected")
            return s.substring(start, i).toDouble()
        }
    }
}

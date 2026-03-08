package com.jsonviewer

object TextProcessor {

    /**
     * Count consecutive backslashes ending at position [pos - 1].
     * Returns true if the character at [pos] is escaped (odd number of preceding backslashes).
     */
    private fun isEscaped(input: String, pos: Int): Boolean {
        var count = 0
        var i = pos - 1
        while (i >= 0 && input[i] == '\\') {
            count++
            i--
        }
        return count % 2 != 0
    }

    /** Pretty-print. Works on any text — character-level state machine. */
    fun format(text: String): String {
        val input = text.replace("\r", "").replace("\n", " ")
        val out = StringBuilder(input.length + input.length / 4) // pre-size
        var depth = 0
        var inString: Char? = null

        for (i in input.indices) {
            val ch = input[i]
            when {
                inString != null && ch == inString && !isEscaped(input, i) -> {
                    inString = null; out.append(ch)
                }
                inString != null -> out.append(ch)
                ch == '"' || ch == '\'' -> { inString = ch; out.append(ch) }
                ch == ' ' || ch == '\t' -> { /* skip */ }
                ch == ':' -> out.append(": ")
                ch == ',' -> { out.append(",\n"); out.append("  ".repeat(depth)) }
                ch == '[' || ch == '{' -> { depth++; out.append(ch).append("\n").append("  ".repeat(depth)) }
                ch == ']' || ch == '}' -> { depth = maxOf(0, depth - 1); out.append("\n").append("  ".repeat(depth)).append(ch) }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** Strip all whitespace outside quoted strings. */
    fun removeWhiteSpace(text: String): String {
        val input = text.replace("\r", "").replace("\n", " ")
        val out = StringBuilder(input.length)
        var inString: Char? = null

        for (i in input.indices) {
            val ch = input[i]
            when {
                inString != null && ch == inString && !isEscaped(input, i) -> {
                    inString = null; out.append(ch)
                }
                inString != null -> out.append(ch)
                ch == '"' || ch == '\'' -> { inString = ch; out.append(ch) }
                ch == ' ' || ch == '\t' -> { /* skip */ }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

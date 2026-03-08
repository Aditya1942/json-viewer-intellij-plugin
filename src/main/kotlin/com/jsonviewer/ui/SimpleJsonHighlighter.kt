package com.jsonviewer.ui

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

// ──────────────────────────────────────────────────────────────────────────────
// Lightweight JSON syntax highlighter — no dependency on the JSON plugin.
//
// Used as a fallback when `com.intellij.json.JsonFileType` is not available
// (e.g. Android Studio, which does not bundle the JSON language plugin).
// Provides proper color-coded highlighting that respects the IDE's theme by
// mapping to DefaultLanguageHighlighterColors keys.
// ──────────────────────────────────────────────────────────────────────────────

// ── Token types ──────────────────────────────────────────────────────────────

private object JsonTokenTypes {
    val STRING      = IElementType("JSON_STRING", null)
    val PROPERTY_KEY = IElementType("JSON_PROPERTY_KEY", null)
    val NUMBER      = IElementType("JSON_NUMBER", null)
    val BOOLEAN     = IElementType("JSON_BOOLEAN", null)
    val NULL        = IElementType("JSON_NULL", null)
    val LBRACE      = IElementType("JSON_LBRACE", null)
    val RBRACE      = IElementType("JSON_RBRACE", null)
    val LBRACKET    = IElementType("JSON_LBRACKET", null)
    val RBRACKET    = IElementType("JSON_RBRACKET", null)
    val COLON       = IElementType("JSON_COLON", null)
    val COMMA       = IElementType("JSON_COMMA", null)
    val WHITESPACE  = IElementType("JSON_WHITESPACE", null)
    val BAD_CHAR    = IElementType("JSON_BAD_CHAR", null)
}

// ── Lexer ────────────────────────────────────────────────────────────────────

/**
 * Simple state-machine lexer for JSON text.
 * Tokenizes strings, numbers, booleans, null, structural characters, and whitespace.
 * Distinguishes property keys (strings followed by `:`) from string values.
 */
internal class SimpleJsonLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            return
        }

        val ch = buffer[tokenStart]

        when {
            ch.isWhitespace() -> lexWhitespace()
            ch == '"' -> lexString()
            ch == '{' -> singleChar(JsonTokenTypes.LBRACE)
            ch == '}' -> singleChar(JsonTokenTypes.RBRACE)
            ch == '[' -> singleChar(JsonTokenTypes.LBRACKET)
            ch == ']' -> singleChar(JsonTokenTypes.RBRACKET)
            ch == ':' -> singleChar(JsonTokenTypes.COLON)
            ch == ',' -> singleChar(JsonTokenTypes.COMMA)
            ch == '-' || ch.isDigit() -> lexNumber()
            ch == 't' || ch == 'f' -> lexBooleanOrBadChar()
            ch == 'n' -> lexNullOrBadChar()
            else -> singleChar(JsonTokenTypes.BAD_CHAR)
        }
    }

    private fun singleChar(type: IElementType) {
        tokenEnd = tokenStart + 1
        tokenType = type
    }

    private fun lexWhitespace() {
        var pos = tokenStart
        while (pos < endOffset && buffer[pos].isWhitespace()) pos++
        tokenEnd = pos
        tokenType = JsonTokenTypes.WHITESPACE
    }

    private fun lexString() {
        // Consume the opening quote and everything until the closing quote
        var pos = tokenStart + 1
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\\') {
                pos = minOf(pos + 2, endOffset)  // skip escaped char, clamp to bounds
                continue
            }
            if (c == '"') {
                pos++  // consume closing quote
                break
            }
            pos++
        }
        tokenEnd = pos

        // Look ahead past whitespace to see if next non-whitespace char is ':'
        // If so, this is a property key, not a string value
        var look = tokenEnd
        while (look < endOffset && buffer[look].isWhitespace()) look++
        tokenType = if (look < endOffset && buffer[look] == ':') {
            JsonTokenTypes.PROPERTY_KEY
        } else {
            JsonTokenTypes.STRING
        }
    }

    private fun lexNumber() {
        var pos = tokenStart
        // Optional leading minus
        if (pos < endOffset && buffer[pos] == '-') pos++
        // Integer part
        while (pos < endOffset && buffer[pos].isDigit()) pos++
        // Fractional part
        if (pos < endOffset && buffer[pos] == '.') {
            pos++
            while (pos < endOffset && buffer[pos].isDigit()) pos++
        }
        // Exponent part
        if (pos < endOffset && (buffer[pos] == 'e' || buffer[pos] == 'E')) {
            pos++
            if (pos < endOffset && (buffer[pos] == '+' || buffer[pos] == '-')) pos++
            while (pos < endOffset && buffer[pos].isDigit()) pos++
        }
        tokenEnd = pos
        tokenType = JsonTokenTypes.NUMBER
    }

    private fun lexBooleanOrBadChar() {
        if (matches("true")) {
            tokenEnd = tokenStart + 4
            tokenType = JsonTokenTypes.BOOLEAN
        } else if (matches("false")) {
            tokenEnd = tokenStart + 5
            tokenType = JsonTokenTypes.BOOLEAN
        } else {
            singleChar(JsonTokenTypes.BAD_CHAR)
        }
    }

    private fun lexNullOrBadChar() {
        if (matches("null")) {
            tokenEnd = tokenStart + 4
            tokenType = JsonTokenTypes.NULL
        } else {
            singleChar(JsonTokenTypes.BAD_CHAR)
        }
    }

    private fun matches(keyword: String): Boolean {
        if (tokenStart + keyword.length > endOffset) return false
        for (i in keyword.indices) {
            if (buffer[tokenStart + i] != keyword[i]) return false
        }
        return true
    }
}

// ── Syntax Highlighter ───────────────────────────────────────────────────────

/**
 * JSON syntax highlighter that maps token types to IDE theme-aware colors.
 *
 * Uses [DefaultLanguageHighlighterColors] so highlighting automatically
 * adapts to the user's color scheme (light, dark, Darcula, custom, etc.).
 */
class SimpleJsonSyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        // Text attributes keys — reuse the IDE's standard semantic colors
        private val KEY_PROPERTY_KEY = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_PROPERTY_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD
            )
        )
        private val KEY_STRING = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_STRING", DefaultLanguageHighlighterColors.STRING
            )
        )
        private val KEY_NUMBER = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_NUMBER", DefaultLanguageHighlighterColors.NUMBER
            )
        )
        private val KEY_KEYWORD = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD
            )
        )
        private val KEY_BRACES = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_BRACES", DefaultLanguageHighlighterColors.BRACES
            )
        )
        private val KEY_BRACKETS = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS
            )
        )
        private val KEY_COMMA = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_COMMA", DefaultLanguageHighlighterColors.COMMA
            )
        )
        private val KEY_COLON = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "SIMPLE_JSON_COLON", DefaultLanguageHighlighterColors.OPERATION_SIGN
            )
        )
        private val EMPTY = emptyArray<TextAttributesKey>()
    }

    override fun getHighlightingLexer(): Lexer = SimpleJsonLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return when (tokenType) {
            JsonTokenTypes.PROPERTY_KEY -> KEY_PROPERTY_KEY
            JsonTokenTypes.STRING       -> KEY_STRING
            JsonTokenTypes.NUMBER       -> KEY_NUMBER
            JsonTokenTypes.BOOLEAN,
            JsonTokenTypes.NULL         -> KEY_KEYWORD
            JsonTokenTypes.LBRACE,
            JsonTokenTypes.RBRACE       -> KEY_BRACES
            JsonTokenTypes.LBRACKET,
            JsonTokenTypes.RBRACKET     -> KEY_BRACKETS
            JsonTokenTypes.COMMA        -> KEY_COMMA
            JsonTokenTypes.COLON        -> KEY_COLON
            else                        -> EMPTY
        }
    }
}

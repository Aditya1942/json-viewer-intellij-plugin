package com.jsonviewer.ui

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import java.lang.Character

// ──────────────────────────────────────────────────────────────────────────────
// Plain-text keyword highlighter for non-JSON notes — no dependency on language
// plugins. Curated union of common keywords from JS/TS, Java, Kotlin, Python,
// SQL, shell, YAML, etc.; lexer covers strings, // # /* */ comments, numbers.
// ──────────────────────────────────────────────────────────────────────────────

private object PlainTextTokenTypes {
    val WHITESPACE = IElementType("PT_WHITESPACE", null)
    val LINE_COMMENT = IElementType("PT_LINE_COMMENT", null)
    val BLOCK_COMMENT = IElementType("PT_BLOCK_COMMENT", null)
    val STRING = IElementType("PT_STRING", null)
    val NUMBER = IElementType("PT_NUMBER", null)
    val KEYWORD = IElementType("PT_KEYWORD", null)
    val CLASS_LIKE = IElementType("PT_CLASS_LIKE", null)
    val IDENTIFIER = IElementType("PT_IDENTIFIER", null)
    val BAD_CHAR = IElementType("PT_BAD_CHAR", null)
}

/**
 * Union of frequently used reserved words / builtins across popular languages.
 * Case-sensitive: lowercase keywords + a small set of capitalized types/names.
 */
internal object PlainTextKeywordSets {
    val KEYWORDS: Set<String> = buildSet {
        // JavaScript / TypeScript
        addAll(
            listOf(
                "break", "case", "catch", "class", "const", "continue", "debugger", "default",
                "delete", "do", "else", "export", "extends", "false", "finally", "for",
                "function", "if", "import", "in", "instanceof", "let", "new", "null",
                "return", "super", "switch", "this", "throw", "true", "try", "typeof",
                "var", "void", "while", "with", "yield", "await", "async", "enum",
                "implements", "interface", "package", "private", "protected", "public",
                "static", "abstract", "boolean", "byte", "char", "double", "float",
                "goto", "int", "long", "native", "short", "synchronized", "throws",
                "transient", "volatile", "readonly", "declare", "namespace", "module",
                "require", "from", "as", "is", "keyof", "never", "unknown", "any",
                "undefined", "Infinity", "NaN", "of", "get", "set", "type", "infer",
                "satisfies", "using", "asserts", "out", "override",
            ),
        )
        // Java / JVM
        addAll(
            listOf(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                "import", "instanceof", "int", "interface", "long", "native", "new", "null",
                "package", "private", "protected", "public", "return", "short", "static",
                "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "true", "false", "var",
                "record", "sealed", "permits", "non-sealed", "yield", "when",
            ),
        )
        // Kotlin
        addAll(
            listOf(
                "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
                "if", "in", "interface", "is", "null", "object", "package", "return",
                "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
                "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
                "field", "file", "get", "import", "init", "param", "property", "receiver",
                "set", "setparam", "where", "actual", "abstract", "annotation", "companion",
                "const", "crossinline", "data", "enum", "expect", "external", "final",
                "infix", "inline", "inner", "internal", "lateinit", "noinline", "open",
                "operator", "out", "override", "private", "protected", "public", "reified",
                "sealed", "suspend", "tailrec", "vararg", "value",
            ),
        )
        // Python
        addAll(
            listOf(
                "and", "as", "assert", "async", "await", "break", "class", "continue",
                "def", "del", "elif", "else", "except", "False", "finally", "for", "from",
                "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
                "or", "pass", "raise", "return", "True", "try", "while", "with", "yield",
                "match", "case", "type",
            ),
        )
        // SQL-ish
        addAll(
            listOf(
                "select", "from", "where", "join", "inner", "left", "right", "outer",
                "on", "group", "by", "order", "having", "limit", "offset", "insert",
                "into", "values", "update", "set", "delete", "create", "table", "index",
                "drop", "alter", "and", "or", "not", "null", "like", "between", "in",
                "is", "distinct", "as", "case", "when", "then", "else", "end", "union",
                "all", "exists", "with", "recursive", "primary", "key", "foreign",
                "references", "constraint", "default", "check", "unique", "cascade",
            ),
        )
        // Go
        addAll(
            listOf(
                "break", "case", "chan", "const", "continue", "default", "defer", "else",
                "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
                "map", "package", "range", "return", "select", "struct", "switch", "type",
                "var",
            ),
        )
        // Rust
        addAll(
            listOf(
                "as", "async", "await", "break", "const", "continue", "crate", "dyn",
                "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let",
                "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self",
                "Self", "static", "struct", "super", "trait", "true", "type", "unsafe",
                "use", "where", "while", "abstract", "become", "box", "do", "final", "macro",
                "override", "priv", "try", "typeof", "unsized", "virtual", "yield",
            ),
        )
        // Shell / general
        addAll(
            listOf(
                "echo", "export", "fi", "then", "elif", "esac", "done", "exit", "read",
                "shift", "source", "alias", "cd", "pwd", "mkdir", "rm", "cp", "mv",
                "grep", "sed", "awk", "sudo", "chmod", "chown", "exec", "eval",
            ),
        )
        // YAML / config
        addAll(
            listOf(
                "true", "false", "null", "yes", "no", "on", "off",
            ),
        )
    }

    /** Common capitalized types / builtins (second color tier). */
    val CLASS_LIKE: Set<String> = setOf(
        "String", "Object", "Array", "Promise", "Map", "Set", "Date", "RegExp", "Error",
        "Number", "Boolean", "Symbol", "BigInt", "Uint8Array", "JSON", "Math", "console",
        "window", "document", "process", "Buffer", "require", "module", "exports",
        "Integer", "Long", "Double", "Float", "Byte", "Short", "Character", "Void",
        "Optional", "List", "Iterable", "Stream", "Record", "Enum", "Throwable", "Exception",
        "RuntimeException", "StringBuilder", "System", "Thread", "Runnable",
        "Int", "Float", "Double", "Boolean", "Char", "Byte", "Short", "Long", "Unit",
        "Nothing", "Any", "Array", "List", "Map", "Set", "Sequence", "Coroutine",
    )
}

internal class PlainTextKeywordsLexer : LexerBase() {

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
            ch == '/' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '/' -> lexLineComment()
            ch == '/' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '*' -> lexBlockComment()
            ch == '#' && isHashLineCommentStart(tokenStart) -> lexLineCommentFromHash()
            ch == '"' || ch == '\'' -> lexString(ch)
            ch == '.' && tokenStart + 1 < endOffset && buffer[tokenStart + 1].isDigit() -> lexNumber()
            ch.isDigit() || (ch == '-' && tokenStart + 1 < endOffset && buffer[tokenStart + 1].isDigit()) -> lexNumber()
            Character.isJavaIdentifierStart(ch.code) || ch == '$' -> lexIdentifierOrKeyword()
            else -> {
                tokenEnd = tokenStart + 1
                tokenType = PlainTextTokenTypes.BAD_CHAR
            }
        }
    }

    private fun isHashLineCommentStart(pos: Int): Boolean {
        var i = pos - 1
        while (i >= startOffset) {
            val c = buffer[i]
            if (c == '\n' || c == '\r') return true
            if (!c.isWhitespace()) return false
            i--
        }
        return true
    }

    private fun lexWhitespace() {
        var pos = tokenStart
        while (pos < endOffset && buffer[pos].isWhitespace()) pos++
        tokenEnd = pos
        tokenType = PlainTextTokenTypes.WHITESPACE
    }

    private fun lexLineComment() {
        var pos = tokenStart + 2
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\n' || c == '\r') break
            pos++
        }
        tokenEnd = pos
        tokenType = PlainTextTokenTypes.LINE_COMMENT
    }

    private fun lexLineCommentFromHash() {
        var pos = tokenStart + 1
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\n' || c == '\r') break
            pos++
        }
        tokenEnd = pos
        tokenType = PlainTextTokenTypes.LINE_COMMENT
    }

    private fun lexBlockComment() {
        var pos = tokenStart + 2
        while (pos + 1 < endOffset) {
            if (buffer[pos] == '*' && buffer[pos + 1] == '/') {
                tokenEnd = pos + 2
                tokenType = PlainTextTokenTypes.BLOCK_COMMENT
                return
            }
            pos++
        }
        tokenEnd = endOffset
        tokenType = PlainTextTokenTypes.BLOCK_COMMENT
    }

    private fun lexString(quote: Char) {
        var pos = tokenStart + 1
        var escape = false
        while (pos < endOffset) {
            val c = buffer[pos]
            if (escape) {
                escape = false
                pos++
                continue
            }
            if (c == '\\') {
                escape = true
                pos++
                continue
            }
            if (c == quote) {
                tokenEnd = pos + 1
                tokenType = PlainTextTokenTypes.STRING
                return
            }
            pos++
        }
        tokenEnd = endOffset
        tokenType = PlainTextTokenTypes.STRING
    }

    private fun lexNumber() {
        var pos = tokenStart
        if (buffer[pos] == '-') pos++
        if (pos < endOffset && buffer[pos] == '0' && pos + 1 < endOffset &&
            (buffer[pos + 1] == 'x' || buffer[pos + 1] == 'X')
        ) {
            pos += 2
            while (pos < endOffset && buffer[pos].isHexDigit()) pos++
            tokenEnd = pos
            tokenType = PlainTextTokenTypes.NUMBER
            return
        }
        while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '_')) pos++
        if (pos < endOffset && buffer[pos] == '.') {
            pos++
            while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '_')) pos++
        }
        if (pos < endOffset && (buffer[pos] == 'e' || buffer[pos] == 'E')) {
            pos++
            if (pos < endOffset && (buffer[pos] == '+' || buffer[pos] == '-')) pos++
            while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '_')) pos++
        }
        if (pos < endOffset && (buffer[pos] == 'f' || buffer[pos] == 'F' ||
                buffer[pos] == 'd' || buffer[pos] == 'D' || buffer[pos] == 'l' || buffer[pos] == 'L')
        ) {
            pos++
        }
        tokenEnd = pos
        tokenType = PlainTextTokenTypes.NUMBER
    }

    private fun lexIdentifierOrKeyword() {
        var pos = tokenStart
        while (pos < endOffset && (Character.isJavaIdentifierPart(buffer[pos].code) || buffer[pos] == '$')) pos++
        tokenEnd = pos
        val text = buffer.subSequence(tokenStart, tokenEnd).toString()
        tokenType = when {
            text in PlainTextKeywordSets.KEYWORDS -> PlainTextTokenTypes.KEYWORD
            text in PlainTextKeywordSets.CLASS_LIKE -> PlainTextTokenTypes.CLASS_LIKE
            else -> PlainTextTokenTypes.IDENTIFIER
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this.isDigit() || this in 'a'..'f' || this in 'A'..'F'
}

/**
 * Plain-text syntax highlighter using IDE theme colors.
 */
class PlainTextKeywordsSyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        private val KEY_KEYWORD = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD,
            ),
        )
        private val KEY_CLASS = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_CLASS", DefaultLanguageHighlighterColors.CLASS_NAME,
            ),
        )
        private val KEY_STRING = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_STRING", DefaultLanguageHighlighterColors.STRING,
            ),
        )
        private val KEY_NUMBER = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_NUMBER", DefaultLanguageHighlighterColors.NUMBER,
            ),
        )
        private val KEY_LINE_COMMENT = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT,
            ),
        )
        private val KEY_BLOCK_COMMENT = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT,
            ),
        )
        private val KEY_IDENTIFIER = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "PT_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER,
            ),
        )
        private val EMPTY = emptyArray<TextAttributesKey>()
    }

    override fun getHighlightingLexer(): Lexer = PlainTextKeywordsLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        when (tokenType) {
            PlainTextTokenTypes.KEYWORD -> KEY_KEYWORD
            PlainTextTokenTypes.CLASS_LIKE -> KEY_CLASS
            PlainTextTokenTypes.STRING -> KEY_STRING
            PlainTextTokenTypes.NUMBER -> KEY_NUMBER
            PlainTextTokenTypes.LINE_COMMENT -> KEY_LINE_COMMENT
            PlainTextTokenTypes.BLOCK_COMMENT -> KEY_BLOCK_COMMENT
            PlainTextTokenTypes.IDENTIFIER -> KEY_IDENTIFIER
            else -> EMPTY
        }
}

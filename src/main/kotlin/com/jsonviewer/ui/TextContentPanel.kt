package com.jsonviewer.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.Timer

// ──────────────────────────────────────────────────────────────────────────────
// Text content panel — IntelliJ Editor with auto-detected syntax highlighting
//
// Replaces Swing JTextArea for massive performance improvements:
// - Virtual scrolling (only visible lines are rendered)
// - Auto-detect JSON: when content is valid JSON, applies full IDE features
//   (syntax highlighting, code folding, color scheme) automatically
// - Plain text mode for non-JSON content — no forced highlighting
// - Native IntelliJ editing experience
// ──────────────────────────────────────────────────────────────────────────────

class TextContentPanel(
    private val onTextChanged: (String) -> Unit = {}
) : JPanel(BorderLayout()), Searchable, Disposable {

    companion object {
        private val LOG = Logger.getInstance(TextContentPanel::class.java)

        private val HIGHLIGHT_YELLOW = TextAttributes().apply {
            backgroundColor = JBColor(Color(0xFF, 0xFF, 0x00, 0x90), Color(0xBB, 0xBB, 0x00, 0x90))
        }
        private val HIGHLIGHT_ORANGE = TextAttributes().apply {
            backgroundColor = JBColor(Color(0xFF, 0xA5, 0x00, 0xC0), Color(0xFF, 0x8C, 0x00, 0xC0))
        }
    }

    private val document = EditorFactory.getInstance().createDocument("")
    private val editor: Editor
    private var debounceTimer: Timer? = null
    private var suppressEvents = false

    // ── Auto-detect JSON mode ──
    private var isJsonMode = false
    private var detectionTimer: Timer? = null
    private var foldTimer: Timer? = null

    // ── Search state ──
    private var searchMatchRanges: List<Pair<Int, Int>> = emptyList()
    private var currentMatchIndex = -1

    init {
        editor = EditorFactory.getInstance().createEditor(document).also { ed ->
            // Configure editor settings — starts as plain text, JSON features
            // are applied dynamically when valid JSON content is detected
            ed.settings.apply {
                isLineNumbersShown = true
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = true
                isUseSoftWraps = true
                additionalLinesCount = 2
                isAdditionalPageAtBottom = false
                setTabSize(2)
            }
        }

        // Add document change listener with debounce
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!suppressEvents) {
                    scheduleNotify()
                    scheduleDetection()
                }
            }
        })

        add(editor.component, BorderLayout.CENTER)
    }

    fun getText(): String = document.text

    /** Set text and fire onTextChanged. */
    fun setText(text: String) {
        // Cancel any pending detection — we detect immediately below
        detectionTimer?.stop()
        if (document.text != text) {
            WriteCommandAction.runWriteCommandAction(null) {
                document.setText(text)
            }
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
        detectAndApplyMode(text)
    }

    /** Set text without firing the storage callback (used when loading tab data). */
    fun setTextSilently(text: String) {
        suppressEvents = true
        // Cancel any pending detection — we detect immediately below
        detectionTimer?.stop()
        if (document.text != text) {
            WriteCommandAction.runWriteCommandAction(null) {
                document.setText(text)
            }
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
        suppressEvents = false
        detectAndApplyMode(text)
    }

    // ── Searchable implementation ──

    override fun applySearch(query: String) {
        clearSearchHighlights()
        searchMatchRanges = emptyList()
        currentMatchIndex = -1

        if (query.isBlank()) return

        val text = document.text
        val matches = mutableListOf<Pair<Int, Int>>()
        var idx = 0
        while (idx < text.length) {
            val found = text.indexOf(query, idx, ignoreCase = true)
            if (found < 0) break
            matches.add(found to found + query.length)
            idx = found + 1
        }

        searchMatchRanges = matches
        val markupModel = editor.markupModel

        // Add yellow highlights for all matches
        for ((start, end) in matches) {
            markupModel.addRangeHighlighter(
                start, end,
                HighlighterLayer.SELECTION - 1,
                HIGHLIGHT_YELLOW,
                HighlighterTargetArea.EXACT_RANGE
            )
        }

        if (matches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightCurrentMatch()
            scrollToCurrentMatch()
        }
    }

    override fun clearSearch() {
        clearSearchHighlights()
        searchMatchRanges = emptyList()
        currentMatchIndex = -1
    }

    override fun navigateSearch(delta: Int): Pair<Int, Int> {
        if (searchMatchRanges.isEmpty()) return 0 to 0
        currentMatchIndex = (currentMatchIndex + delta + searchMatchRanges.size) % searchMatchRanges.size
        reapplyHighlights()
        highlightCurrentMatch()
        scrollToCurrentMatch()
        return (currentMatchIndex + 1) to searchMatchRanges.size
    }

    override fun getMatchStatus(): Pair<Int, Int> {
        if (searchMatchRanges.isEmpty()) return 0 to 0
        return (currentMatchIndex + 1) to searchMatchRanges.size
    }

    // ── Internal highlight helpers ──

    private fun clearSearchHighlights() {
        editor.markupModel.removeAllHighlighters()
    }

    private fun reapplyHighlights() {
        clearSearchHighlights()
        val markupModel = editor.markupModel
        for ((start, end) in searchMatchRanges) {
            markupModel.addRangeHighlighter(
                start, end,
                HighlighterLayer.SELECTION - 1,
                HIGHLIGHT_YELLOW,
                HighlighterTargetArea.EXACT_RANGE
            )
        }
    }

    private fun highlightCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatchRanges.size) return
        val (start, end) = searchMatchRanges[currentMatchIndex]
        editor.markupModel.addRangeHighlighter(
            start, end,
            HighlighterLayer.SELECTION,
            HIGHLIGHT_ORANGE,
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    private fun scrollToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatchRanges.size) return
        val (start, _) = searchMatchRanges[currentMatchIndex]
        editor.caretModel.moveToOffset(start)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }

    /** Stop all debounce timers. Call when the panel is being disposed. */
    fun stopTimers() {
        debounceTimer?.stop()
        debounceTimer = null
        detectionTimer?.stop()
        detectionTimer = null
        foldTimer?.stop()
        foldTimer = null
    }

    private fun scheduleNotify() {
        if (suppressEvents) return
        debounceTimer?.stop()
        debounceTimer = Timer(400) { onTextChanged(document.text) }.also {
            it.isRepeats = false
            it.start()
        }
    }

    // ── Auto-detect JSON mode ────────────────────────────────────────────────

    /**
     * Debounced JSON detection — schedules a check 500ms after the last keystroke.
     * Called from the document change listener during user typing/pasting.
     * Note: javax.swing.Timer already fires on the EDT, no need for SwingUtilities.invokeLater.
     */
    private fun scheduleDetection() {
        detectionTimer?.stop()
        detectionTimer = Timer(500) {
            detectAndApplyMode(document.text)
        }.also {
            it.isRepeats = false
            it.start()
        }
    }

    /**
     * Debounced fold region update — schedules a refresh 1s after the last change.
     * Fold computation iterates every character, so we use a longer debounce than
     * the detection timer to avoid O(n) work on every keystroke while typing.
     */
    private fun scheduleFoldUpdate() {
        foldTimer?.stop()
        foldTimer = Timer(1000) {
            updateFoldRegions()
        }.also {
            it.isRepeats = false
            it.start()
        }
    }

    /**
     * Immediately detect whether [text] is valid JSON and switch the editor mode.
     * Called directly from setText/setTextSilently for instant feedback on paste/load,
     * and from the debounced timer during user typing.
     */
    private fun detectAndApplyMode(text: String) {
        if (isLikelyJson(text)) {
            applyJsonMode()
        } else {
            applyPlainTextMode()
        }
    }

    /**
     * Lightweight check to determine if text looks like a JSON object or array.
     *
     * Uses index scanning (no [String.trim] allocation) and a simple bracket-balance
     * check instead of a full Gson parse. This keeps detection O(n) in the worst case
     * but avoids allocating the entire parsed DOM tree, which matters for multi-MB texts
     * that are checked on every keystroke (debounced).
     *
     * Only objects `{…}` and arrays `[…]` trigger JSON mode — bare primitives
     * like `"hello"` or `42` stay in plain text since they don't benefit from
     * folding or structure highlighting.
     */
    private fun isLikelyJson(text: String): Boolean {
        if (text.length < 2) return false

        // Find first and last non-whitespace characters without allocating a trimmed copy
        var first = 0
        while (first < text.length && text[first].isWhitespace()) first++
        if (first >= text.length) return false

        var last = text.length - 1
        while (last > first && text[last].isWhitespace()) last--

        val openChar = text[first]
        val closeChar = text[last]

        // Must start with { or [ and end with matching bracket
        if (openChar == '{' && closeChar != '}') return false
        if (openChar == '[' && closeChar != ']') return false
        if (openChar != '{' && openChar != '[') return false

        // For small texts (< 4KB), do a quick bracket-balance check for accuracy
        val contentLength = last - first + 1
        if (contentLength <= 4096) {
            return isBracketsBalanced(text, first, last)
        }

        // For larger texts, the structural pre-check above is sufficient —
        // starts with {/[ and ends with }/] is a strong enough signal
        return true
    }

    /**
     * Quick bracket-balance check for a region of text.
     * Verifies that `{`, `}`, `[`, `]` are properly nested (ignoring content inside strings).
     * Returns false if brackets are clearly unbalanced.
     */
    private fun isBracketsBalanced(text: String, start: Int, end: Int): Boolean {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start..end) {
            val ch = text[i]
            if (escape) { escape = false; continue }
            if (ch == '\\' && inString) { escape = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            when (ch) {
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth < 0) return false  // more closes than opens
                }
            }
        }
        return depth == 0
    }

    /**
     * Switch to JSON mode — apply JSON syntax highlighting and compute fold regions.
     * No-op if already in JSON mode.
     *
     * Tries the bundled IntelliJ JSON plugin first (richer experience).
     * If unavailable (e.g. Android Studio), falls back to our own
     * [SimpleJsonSyntaxHighlighter] which uses only Platform core APIs.
     */
    private fun applyJsonMode() {
        if (isJsonMode) {
            // Already in JSON mode, but content changed — debounce fold region refresh
            // to avoid O(n) recomputation on every keystroke
            scheduleFoldUpdate()
            return
        }
        isJsonMode = true
        val edEx = editor as? EditorEx ?: return
        val scheme = EditorColorsManager.getInstance().globalScheme

        // Strategy 1: Use the bundled JSON plugin's FileType (IntelliJ, WebStorm, etc.)
        var applied = false
        try {
            val jsonFileType = Class.forName("com.intellij.json.JsonFileType")
                .getDeclaredField("INSTANCE").get(null) as FileType
            edEx.highlighter = EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(jsonFileType, scheme, null)
            applied = true
        } catch (_: Exception) {
            LOG.debug("JSON plugin not available, falling back to built-in highlighter")
        }

        // Strategy 2: Fall back to our custom lightweight JSON highlighter
        if (!applied) {
            try {
                edEx.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(SimpleJsonSyntaxHighlighter(), scheme)
                applied = true
            } catch (e: Exception) {
                LOG.warn("Could not apply any JSON highlighter", e)
            }
        }

        if (!applied) {
            isJsonMode = false
            return
        }
        updateFoldRegions()
    }

    /**
     * Switch to plain text mode — remove JSON highlighting and clear fold regions.
     * No-op if already in plain text mode.
     */
    private fun applyPlainTextMode() {
        if (!isJsonMode) return
        isJsonMode = false
        val edEx = editor as? EditorEx ?: return
        try {
            val plainFileType = Class.forName("com.intellij.openapi.fileTypes.PlainTextFileType")
                .getDeclaredField("INSTANCE").get(null) as FileType
            edEx.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                plainFileType,
                EditorColorsManager.getInstance().globalScheme,
                null
            )
        } catch (_: Exception) {
            // Fallback: create a bare highlighter with a null SyntaxHighlighter
            try {
                val nullHighlighter: com.intellij.openapi.fileTypes.SyntaxHighlighter? = null
                edEx.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(nullHighlighter, EditorColorsManager.getInstance().globalScheme)
            } catch (_: Exception) {
                LOG.debug("Could not reset to plain text highlighter")
            }
        }
        clearFoldRegions()
    }

    // ── Fold regions ─────────────────────────────────────────────────────────

    /**
     * Compute fold regions from the JSON structure and apply them to the editor.
     * Only multi-line `{…}` and `[…]` blocks get fold regions.
     */
    private fun updateFoldRegions() {
        editor.foldingModel.runBatchFoldingOperation {
            // Clear existing fold regions
            for (region in editor.foldingModel.allFoldRegions) {
                editor.foldingModel.removeFoldRegion(region)
            }
            val text = document.text
            if (text.isBlank()) return@runBatchFoldingOperation

            val foldRanges = computeJsonFoldRanges(text)
            for ((start, end, placeholder) in foldRanges) {
                val startLine = document.getLineNumber(start)
                val endLine = document.getLineNumber(end)
                // Only create fold regions for blocks that span multiple lines
                if (endLine > startLine && start + 1 < end) {
                    editor.foldingModel.addFoldRegion(start + 1, end, placeholder)?.apply {
                        isExpanded = true  // Start expanded so user sees full content
                    }
                }
            }
        }
    }

    /**
     * Stack-based bracket matching to find `{…}` and `[…]` pairs in JSON text.
     * Properly handles string escaping so brackets inside strings are ignored.
     *
     * @return list of (startOffset, endOffset, placeholder) triples
     */
    private fun computeJsonFoldRanges(text: String): List<Triple<Int, Int, String>> {
        val ranges = mutableListOf<Triple<Int, Int, String>>()
        val stack = ArrayDeque<Pair<Int, Char>>() // (offset, opening bracket)
        var inString = false
        var escape = false

        for (i in text.indices) {
            val ch = text[i]
            if (escape) { escape = false; continue }
            if (ch == '\\' && inString) { escape = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue

            when (ch) {
                '{', '[' -> stack.addLast(i to ch)
                '}' -> {
                    if (stack.isNotEmpty() && stack.last().second == '{') {
                        val (start, _) = stack.removeLast()
                        ranges.add(Triple(start, i, "{...}"))
                    }
                }
                ']' -> {
                    if (stack.isNotEmpty() && stack.last().second == '[') {
                        val (start, _) = stack.removeLast()
                        ranges.add(Triple(start, i, "[...]"))
                    }
                }
            }
        }
        return ranges
    }

    /** Remove all fold regions from the editor. */
    private fun clearFoldRegions() {
        editor.foldingModel.runBatchFoldingOperation {
            for (region in editor.foldingModel.allFoldRegions) {
                editor.foldingModel.removeFoldRegion(region)
            }
        }
    }

    override fun dispose() {
        stopTimers()
        EditorFactory.getInstance().releaseEditor(editor)
    }
}

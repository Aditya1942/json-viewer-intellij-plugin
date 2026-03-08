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
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel
import javax.swing.Timer

// ──────────────────────────────────────────────────────────────────────────────
// Text content panel — IntelliJ Editor with JSON syntax highlighting
//
// Replaces Swing JTextArea for massive performance improvements:
// - Virtual scrolling (only visible lines are rendered)
// - JSON syntax highlighting
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

    // ── Search state ──
    private var searchMatchRanges: List<Pair<Int, Int>> = emptyList()
    private var currentMatchIndex = -1

    init {
        editor = EditorFactory.getInstance().createEditor(document).also { ed ->
            val edEx = ed as? EditorEx
            // Configure editor settings
            ed.settings.apply {
                isLineNumbersShown = true
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = true
                isUseSoftWraps = true
                additionalLinesCount = 2
                isAdditionalPageAtBottom = false
                setTabSize(2)
            }
            // Apply JSON syntax highlighting when the IntelliJ JSON plugin is available
            // (optional: not present in Android Studio and some other IDEs)
            edEx?.let { ex ->
                try {
                    val jsonFileType = Class.forName("com.intellij.json.JsonFileType")
                        .getDeclaredField("INSTANCE").get(null) as FileType
                    val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                        jsonFileType,
                        EditorColorsManager.getInstance().globalScheme,
                        null
                    )
                    ex.highlighter = highlighter
                } catch (e: ClassNotFoundException) {
                    LOG.debug("JSON plugin not available, using plain text highlighting", e)
                } catch (e: Exception) {
                    LOG.debug("Could not apply JSON highlighter", e)
                }
            }
        }

        // Add document change listener with debounce
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!suppressEvents) scheduleNotify()
            }
        })

        add(editor.component, BorderLayout.CENTER)
    }

    fun getText(): String = document.text

    /** Set text and fire onTextChanged. */
    fun setText(text: String) {
        if (document.text != text) {
            WriteCommandAction.runWriteCommandAction(null) {
                document.setText(text)
            }
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
    }

    /** Set text without firing the storage callback (used when loading tab data). */
    fun setTextSilently(text: String) {
        suppressEvents = true
        if (document.text != text) {
            WriteCommandAction.runWriteCommandAction(null) {
                document.setText(text)
            }
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
        suppressEvents = false
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

    /** Stop the debounce timer. Call when the panel is being disposed. */
    fun stopTimers() {
        debounceTimer?.stop()
        debounceTimer = null
    }

    private fun scheduleNotify() {
        if (suppressEvents) return
        debounceTimer?.stop()
        debounceTimer = Timer(400) { onTextChanged(document.text) }.also {
            it.isRepeats = false
            it.start()
        }
    }

    override fun dispose() {
        stopTimers()
        EditorFactory.getInstance().releaseEditor(editor)
    }
}

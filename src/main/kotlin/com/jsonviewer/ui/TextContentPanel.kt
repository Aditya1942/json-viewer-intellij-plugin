package com.jsonviewer.ui

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.jsonviewer.JsonViewerUiSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Component
import javax.swing.JButton
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

// ──────────────────────────────────────────────────────────────────────────────
// Text content panel — IntelliJ Editor with configurable syntax highlighting
// (Plain / Auto / explicit FileType). Plain preserves legacy JSON auto-detect.
// ──────────────────────────────────────────────────────────────────────────────

class TextContentPanel(
    private val project: Project,
    private val onTextChanged: (String) -> Unit = {},
    /** When non-null (main JSON Notes editor tab), must be [com.intellij.openapi.fileEditor.FileDocumentManager]'s document for that file so Undo/Redo matches the IDE file stack. */
    private val sharedEditorDocument: Document? = null
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

    private val document: Document
    /** Exposed so [com.jsonviewer.JsonViewerPanel] can supply [com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR] for Undo/Redo in the main editor. */
    internal val editor: Editor
    private var debounceTimer: Timer? = null
    private var suppressEvents = false

    /** User-selected mode; default matches legacy behavior. */
    private var noteHighlightMode: NoteHighlightMode = NoteHighlightMode.Plain

    /** True when JSON lexer + JSON folding path is active (Plain/Auto JSON, or Explicit JSON + likely JSON). */
    private var isJsonStructuralHighlight = false
    private var detectionTimer: Timer? = null
    private var foldTimer: Timer? = null

    // ── Search state ──
    private var searchMatchRanges: List<Pair<Int, Int>> = emptyList()
    private var currentMatchIndex = -1

    /** Transparent icon-only control; top-right over the editor. */
    private val syntaxHighlightButton = JButton(NoteSyntaxHighlightUi.iconForMode(NoteHighlightMode.Plain)).apply {
        isOpaque = false
        setContentAreaFilled(false)
        border = JBUI.Borders.empty(4, 6)
        margin = JBUI.emptyInsets()
        isFocusPainted = false
        toolTipText = NoteSyntaxHighlightUi.tooltipForMode(NoteHighlightMode.Plain)
    }

    private var syntaxHighlightMenuHandler: ((Component) -> Unit)? = null

    private val editorLayerHost = JLayeredPane()

    private val lafListener = object : LafManagerListener {
        override fun lookAndFeelChanged(lafManager: LafManager) {
            SwingUtilities.invokeLater {
                syntaxHighlightButton.repaint()
                editorLayerHost.repaint()
                updateSyntaxHighlightPresentation(noteHighlightMode)
            }
        }
    }

    init {
        val factory = EditorFactory.getInstance()
        val created = ReadAction.compute<Pair<Document, Editor>, RuntimeException> {
            val doc = sharedEditorDocument ?: factory.createDocument("")
            val ed = factory.createEditor(doc).also { ed ->
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
            doc to ed
        }
        document = created.first
        editor = created.second

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!suppressEvents) {
                    scheduleNotify()
                    scheduleDetection()
                }
            }
        }, this)

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { reapplyHighlightingForCurrentMode() }
        )

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            lafListener
        )

        syntaxHighlightButton.installIconButtonHover {
            isOpaque = false
            background = null
            repaint()
        }
        syntaxHighlightButton.addActionListener {
            syntaxHighlightMenuHandler?.invoke(syntaxHighlightButton)
        }

        editorLayerHost.layout = null
        val edComp = editor.component
        editorLayerHost.add(edComp, Integer.valueOf(JLayeredPane.DEFAULT_LAYER))
        editorLayerHost.add(syntaxHighlightButton, Integer.valueOf(JLayeredPane.PALETTE_LAYER))
        editorLayerHost.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                layoutSyntaxHighlightOverlay()
            }
        })
        editorLayerHost.isOpaque = false

        add(editorLayerHost, BorderLayout.CENTER)
        layoutSyntaxHighlightOverlay()
    }

    fun setSyntaxHighlightMenuHandler(handler: (Component) -> Unit) {
        syntaxHighlightMenuHandler = handler
    }

    fun updateSyntaxHighlightPresentation(mode: NoteHighlightMode) {
        syntaxHighlightButton.icon = NoteSyntaxHighlightUi.iconForMode(mode)
        syntaxHighlightButton.toolTipText = NoteSyntaxHighlightUi.tooltipForMode(mode)
    }

    private fun layoutSyntaxHighlightOverlay() {
        val w = editorLayerHost.width
        val h = editorLayerHost.height
        if (w <= 0 || h <= 0) return
        val edComp = editor.component
        edComp.setBounds(0, 0, w, h)
        val btnW = JBUI.scale(28).coerceAtLeast(syntaxHighlightButton.preferredSize.width)
        val btnH = JBUI.scale(26).coerceAtLeast(syntaxHighlightButton.preferredSize.height)
        syntaxHighlightButton.setBounds(w - btnW - JBUI.scale(6), JBUI.scale(4), btnW, btnH)
    }

    override fun doLayout() {
        super.doLayout()
        layoutSyntaxHighlightOverlay()
    }

    fun getHighlightMode(): NoteHighlightMode = noteHighlightMode

    fun setHighlightMode(mode: NoteHighlightMode) {
        noteHighlightMode = mode
        reapplyHighlightingForCurrentMode()
    }

    /**
     * Updates the editor's font family and size (plain / bold / italic variants) without changing colors.
     * Uses a clone of the current color scheme so the main IDE editor scheme is untouched.
     */
    fun applyFontSettings(family: String, size: Int) {
        val edEx = editor as? EditorEx ?: return
        val sz = size.coerceIn(JsonViewerUiSettings.MIN_FONT_SIZE, JsonViewerUiSettings.MAX_FONT_SIZE)
        val name = family.ifBlank { PluginFonts.defaultFamilyName() }
        val globals = EditorColorsManager.getInstance().globalScheme
        val scheme = (edEx.colorsScheme.clone() as? EditorColorsScheme)
            ?: (globals.clone() as? EditorColorsScheme)
            ?: return
        scheme.setEditorFontName(name)
        scheme.setEditorFontSize(sz)
        edEx.colorsScheme = scheme
        editor.contentComponent.revalidate()
        editor.contentComponent.repaint()
    }

    fun getText(): String = document.text

    private fun moveCaretToOffsetAndScroll(offset: Int, scrollType: ScrollType) {
        WriteIntentReadAction.run(Runnable {
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(scrollType)
        })
    }

    /** Set text and fire onTextChanged. */
    fun setText(text: String) {
        detectionTimer?.stop()
        if (document.text != text) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(text)
            }
            moveCaretToOffsetAndScroll(0, ScrollType.MAKE_VISIBLE)
        }
        reapplyHighlightingForCurrentMode()
    }

    /** Set text without firing the storage callback (used when loading tab data). */
    fun setTextSilently(text: String) {
        suppressEvents = true
        detectionTimer?.stop()
        if (document.text != text) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(text)
            }
            moveCaretToOffsetAndScroll(0, ScrollType.MAKE_VISIBLE)
        }
        suppressEvents = false
        reapplyHighlightingForCurrentMode()
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
        moveCaretToOffsetAndScroll(start, ScrollType.CENTER)
    }

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

    private fun scheduleDetection() {
        detectionTimer?.stop()
        detectionTimer = Timer(500) {
            when (val m = noteHighlightMode) {
                is NoteHighlightMode.Plain, is NoteHighlightMode.Auto -> reapplyHighlightingForCurrentMode()
                is NoteHighlightMode.Explicit -> {
                    if (isJsonPluginFileType(m.fileType) && isLikelyJson(document.text)) {
                        scheduleFoldUpdate()
                    }
                }
            }
        }.also {
            it.isRepeats = false
            it.start()
        }
    }

    private fun scheduleFoldUpdate() {
        foldTimer?.stop()
        foldTimer = Timer(1000) {
            updateFoldRegions()
        }.also {
            it.isRepeats = false
            it.start()
        }
    }

    private fun reapplyHighlightingForCurrentMode() {
        val text = document.text
        when (val m = noteHighlightMode) {
            is NoteHighlightMode.Plain -> applyPlainLikeMode(text)
            is NoteHighlightMode.Auto -> applyAutoMode(text)
            is NoteHighlightMode.Explicit -> applyExplicitMode(m.fileType, text)
        }
    }

    /** Legacy Plain: JSON lexer + folds when likely JSON; else plain. */
    private fun applyPlainLikeMode(text: String) {
        if (isLikelyJson(text)) {
            if (applyJsonHighlighter()) {
                scheduleFoldUpdate()
            } else {
                leaveJsonStructuralHighlight()
                applyPlainHighlighter()
                clearFoldRegions()
            }
        } else {
            leaveJsonStructuralHighlight()
            applyPlainHighlighter()
            clearFoldRegions()
        }
    }

    private fun applyAutoMode(text: String) {
        if (isLikelyJson(text)) {
            if (applyJsonHighlighter()) {
                scheduleFoldUpdate()
            } else {
                leaveJsonStructuralHighlight()
                applyPlainHighlighter()
                clearFoldRegions()
            }
        } else {
            leaveJsonStructuralHighlight()
            val inferred = NoteContentFileTypeInference.inferNonJson(text)
            if (inferred != null && applyFileTypeHighlighter(inferred)) {
                clearFoldRegions()
            } else {
                applyPlainHighlighter()
                clearFoldRegions()
            }
        }
    }

    private fun applyExplicitMode(fileType: FileType, text: String) {
        leaveJsonStructuralHighlight()
        if (!applyFileTypeHighlighter(fileType)) {
            applyPlainHighlighter()
            clearFoldRegions()
            return
        }
        if (isJsonPluginFileType(fileType) && isLikelyJson(text)) {
            isJsonStructuralHighlight = true
            scheduleFoldUpdate()
        } else {
            clearFoldRegions()
        }
    }

    /** @return false if no JSON highlighter could be installed. */
    private fun applyJsonHighlighter(): Boolean {
        val edEx = editor as? EditorEx ?: return false
        val scheme = EditorColorsManager.getInstance().globalScheme
        var applied = false
        try {
            val jsonFileType = NoteHighlightFileTypeResolver.jsonFileType()
            if (jsonFileType != null) {
                edEx.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(jsonFileType, scheme, null)
                applied = true
            }
        } catch (e: Exception) {
            LOG.debug("JSON FileType highlighter failed", e)
        }
        if (!applied) {
            try {
                edEx.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(SimpleJsonSyntaxHighlighter(), scheme)
                applied = true
            } catch (e: Exception) {
                LOG.warn("Could not apply built-in JSON highlighter", e)
            }
        }
        if (applied) {
            isJsonStructuralHighlight = true
        }
        return applied
    }

    private fun leaveJsonStructuralHighlight() {
        isJsonStructuralHighlight = false
    }

    private fun applyPlainHighlighter(): Boolean {
        val edEx = editor as? EditorEx ?: return false
        val scheme = EditorColorsManager.getInstance().globalScheme
        return try {
            edEx.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                NoteHighlightFileTypeResolver.plainTextFileType(),
                scheme,
                null
            )
            true
        } catch (e: Exception) {
            LOG.debug("Plain highlighter failed", e)
            try {
                edEx.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(null as com.intellij.openapi.fileTypes.SyntaxHighlighter?, scheme)
                true
            } catch (e2: Exception) {
                LOG.warn("Could not apply plain highlighter", e2)
                false
            }
        }
    }

    private fun applyFileTypeHighlighter(fileType: FileType): Boolean {
        val edEx = editor as? EditorEx ?: return false
        val scheme = EditorColorsManager.getInstance().globalScheme
        return try {
            edEx.highlighter = EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(fileType, scheme, null)
            true
        } catch (e: Exception) {
            LOG.debug("Highlighter failed for ${fileType.name}", e)
            false
        }
    }

    private fun isJsonPluginFileType(fileType: FileType): Boolean {
        val j = NoteHighlightFileTypeResolver.jsonFileType() ?: return false
        return fileType.name == j.name
    }

    private fun isLikelyJson(text: String): Boolean {
        if (text.length < 2) return false
        var first = 0
        while (first < text.length && text[first].isWhitespace()) first++
        if (first >= text.length) return false
        var last = text.length - 1
        while (last > first && text[last].isWhitespace()) last--
        val openChar = text[first]
        val closeChar = text[last]
        if (openChar == '{' && closeChar != '}') return false
        if (openChar == '[' && closeChar != ']') return false
        if (openChar != '{' && openChar != '[') return false
        val contentLength = last - first + 1
        if (contentLength <= 4096) {
            return isBracketsBalanced(text, first, last)
        }
        return true
    }

    private fun isBracketsBalanced(text: String, start: Int, end: Int): Boolean {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start..end) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inString) {
                escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    private fun updateFoldRegions() {
        if (!isJsonStructuralHighlight) return
        WriteIntentReadAction.run(Runnable {
            editor.foldingModel.runBatchFoldingOperation {
                for (region in editor.foldingModel.allFoldRegions) {
                    editor.foldingModel.removeFoldRegion(region)
                }
                val text = document.text
                if (text.isBlank()) return@runBatchFoldingOperation
                val foldRanges = computeJsonFoldRanges(text)
                for ((start, end, placeholder) in foldRanges) {
                    val startLine = document.getLineNumber(start)
                    val endLine = document.getLineNumber(end)
                    if (endLine > startLine && start + 1 < end) {
                        editor.foldingModel.addFoldRegion(start + 1, end, placeholder)?.apply {
                            isExpanded = true
                        }
                    }
                }
            }
        })
    }

    private fun computeJsonFoldRanges(text: String): List<Triple<Int, Int, String>> {
        val ranges = mutableListOf<Triple<Int, Int, String>>()
        val stack = ArrayDeque<Pair<Int, Char>>()
        var inString = false
        var escape = false
        for (i in text.indices) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inString) {
                escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
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

    private fun clearFoldRegions() {
        WriteIntentReadAction.run(Runnable {
            editor.foldingModel.runBatchFoldingOperation {
                for (region in editor.foldingModel.allFoldRegions) {
                    editor.foldingModel.removeFoldRegion(region)
                }
            }
        })
    }

    override fun dispose() {
        stopTimers()
        EditorFactory.getInstance().releaseEditor(editor)
    }
}

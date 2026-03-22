package com.jsonviewer.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*

// ──────────────────────────────────────────────────────────────────────────────
// Searchable interface — implemented by both content panels
// ──────────────────────────────────────────────────────────────────────────────

interface Searchable {
    /** Run a search with the given query. Empty query clears results. */
    fun applySearch(query: String)
    /** Clear all search highlights and state. */
    fun clearSearch()
    /** Navigate to next (+1) or previous (-1) match. Returns (current, total). */
    fun navigateSearch(delta: Int): Pair<Int, Int>
    /** Returns (currentIndex 1-based, totalMatches). (0,0) means no search active. */
    fun getMatchStatus(): Pair<Int, Int>
}

// ──────────────────────────────────────────────────────────────────────────────
// Search panel (bottom bar)
// ──────────────────────────────────────────────────────────────────────────────

class SearchPanel : JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 3)) {

    private val searchField = JBTextField()
    private val statusLabel = JBLabel("")
    private var debounceTimer: Timer? = null

    var onSearch: (String) -> Unit = {}
    var onNext: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onClose: () -> Unit = {}

    init {
        border = JBUI.Borders.customLine(ideSeparatorColor(), 1, 0, 0, 0)

        add(JBLabel("Search:"))
        searchField.preferredSize = Dimension(200, 26)
        searchField.addActionListener { onNext() }
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = debounce()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = debounce()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = debounce()
        })

        // Escape → close, Shift+Enter → previous
        searchField.getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "prev")
        }
        searchField.actionMap.apply {
            put("close", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { onClose() } })
            put("prev", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { onPrevious() } })
        }

        add(searchField)
        add(statusLabel)

        add(navBtn("▼ Next") { onNext() })
        add(navBtn("▲ Prev") { onPrevious() })
        add(navBtn("✕") { onClose() })
    }

    fun getQuery(): String = searchField.text

    fun setQuery(query: String) {
        if (searchField.text != query) {
            searchField.text = query
        }
    }

    fun focusInput() {
        searchField.requestFocusInWindow()
        searchField.selectAll()
    }

    fun updateStatus(current: Int, total: Int) {
        statusLabel.text = when {
            searchField.text.isBlank() -> ""
            total == 0 -> "Not found"
            else -> "$current of $total"
        }
        statusLabel.foreground = if (total == 0 && searchField.text.isNotBlank()) JBColor.RED else JBColor.foreground()
    }

    /** Stop the debounce timer. Call when the panel is being disposed. */
    fun stopTimers() {
        debounceTimer?.stop()
        debounceTimer = null
    }

    private fun debounce() {
        debounceTimer?.stop()
        debounceTimer = Timer(150) { onSearch(searchField.text) }.also {
            it.isRepeats = false; it.start()
        }
    }

    private fun navBtn(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2, 6)
            font = font.deriveFont(Font.PLAIN, 11f)
            installIconButtonHover()
            addActionListener { action() }
        }
    }
}

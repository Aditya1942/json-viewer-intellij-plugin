package com.jsonviewer

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.text.DefaultHighlighter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeCellEditor

// ──────────────────────────────────────────────────────────────────────────────
// Factory
// ──────────────────────────────────────────────────────────────────────────────

class JsonViewerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JsonViewerPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Data models
// ──────────────────────────────────────────────────────────────────────────────

enum class JsonNodeType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

data class JsonTreeNodeData(
    val key: String,
    val value: String?,
    val type: JsonNodeType,
    val childCount: Int = 0,
    val element: JsonElement? = null,
)

// ──────────────────────────────────────────────────────────────────────────────
// Root panel — tab bar on top, header below:
//
//  ┌──────────────────────────────────────────────────────┐
//  │  <tab title>                        [‹][›][+][✕]     │ ← tab bar (icon nav)
//  ├──────────────────────────────────────────────────────┤
//  │ [📄][🌲] error?     [📋][📑][◫][▣]                  │ ← header (icons: Text/Viewer, Paste/Copy/Format/Minify)
//  ├──────────────────────────────────────────────────────┤
//  │                                                      │
//  │  content (text editor  OR  tree viewer)              │
//  │                                                      │
//  └──────────────────────────────────────────────────────┘
// ──────────────────────────────────────────────────────────────────────────────

class JsonViewerPanel : JPanel(BorderLayout()) {

    // ── View mode ──
    private enum class ViewMode { TEXT, VIEWER }
    private var viewMode = ViewMode.TEXT

    // ── Tab storage ──
    private val storageService = TabStorageService.getInstance()
    private val tabs = mutableListOf<SavedTab>()
    private var activeTabId: String = ""

    // ── Content panels ──
    private val textContent = TextContentPanel(onTextChanged = { text -> onActiveTabTextChanged(text) })
    private val viewerContent = ViewerContentPanel()
    private val contentStack = JPanel(CardLayout())

    // ── Shared search bar ──
    private val searchPanel = SearchPanel()

    // ── Header widgets (icons with tooltips) ──
    private val textBtn = headerToggleIconButton(AllIcons.FileTypes.Text, "Text")
    private val viewerBtn = headerToggleIconButton(AllIcons.Json.Object, "Viewer")
    private val errorLabel = JBLabel("").apply {
        foreground = JBColor.RED
        border = JBUI.Borders.emptyLeft(6)
    }

    // ── Tab bar widgets ──
    private val tabTitleLabel = JBLabel("").apply {
        border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val prevTabBtn = tabNavIconButton(AllIcons.Actions.Back, "Previous tab")
    private val nextTabBtn = tabNavIconButton(AllIcons.Actions.Forward, "Next tab")
    private val newTabBtn = tabNavIconButton(AllIcons.Actions.AddFile, "New tab")
    private val deleteTabBtn = tabNavIconButton(deleteIcon(), "Delete tab")

    init {
        // ── Header (no title; responsive wrap) ──────────────────────────────
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            minimumSize = Dimension(0, JBUI.scale(28))
        }

        // Left: Text/Viewer icon toggles + error
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(6))
        }
        headerLeft.add(textBtn)
        headerLeft.add(viewerBtn)
        headerLeft.add(errorLabel)

        // Right: action icon buttons (Paste, Copy, Format, Minify)
        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), JBUI.scale(2)).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(6))
        }
        headerRight.add(actionIconButton(AllIcons.Actions.Menu_paste, "Paste") { pasteFromClipboard() })
        headerRight.add(actionIconButton(AllIcons.Actions.Copy, "Copy") { copyToClipboard() })
        headerRight.add(actionIconButton(AllIcons.Diff.MagicResolveToolbar, "Format") { formatText() })
        headerRight.add(actionIconButton(minifyIcon(), "Minify") { minifyText() })

        header.add(headerLeft, BorderLayout.WEST)
        header.add(headerRight, BorderLayout.EAST)

        // ── Tab bar: tab title + nav icon buttons ─────────────────────────────
        val tabBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            minimumSize = Dimension(0, JBUI.scale(28))
        }

        val tabBarLeft = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(6))
        }
        tabTitleLabel.verticalAlignment = SwingConstants.CENTER
        tabTitleLabel.horizontalAlignment = SwingConstants.LEFT
        tabBarLeft.add(tabTitleLabel, BorderLayout.CENTER)

        val tabBarRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(6))
        }
        tabBarRight.add(prevTabBtn)
        tabBarRight.add(nextTabBtn)
        tabBarRight.add(newTabBtn)
        tabBarRight.add(deleteTabBtn)

        tabBar.add(tabBarLeft, BorderLayout.WEST)
        tabBar.add(tabBarRight, BorderLayout.EAST)

        // ── Top chrome (tab bar on top, header below) ────────────────────────
        val topChrome = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(tabBar)
            add(header)
        }

        // ── Content stack ────────────────────────────────────────────────────
        contentStack.add(textContent, "text")
        contentStack.add(viewerContent, "viewer")

        // ── Shared search bar (hidden by default) ─────────────────────────
        searchPanel.isVisible = false
        searchPanel.onSearch = { query -> dispatchSearch(query) }
        searchPanel.onNext = { dispatchNavigate(+1) }
        searchPanel.onPrevious = { dispatchNavigate(-1) }
        searchPanel.onClose = { closeSearch() }

        // ── Root ────────────────────────────────────────────────────────────
        add(topChrome, BorderLayout.NORTH)
        add(contentStack, BorderLayout.CENTER)
        add(searchPanel, BorderLayout.SOUTH)

        // ── Wire buttons ────────────────────────────────────────────────────
        textBtn.addActionListener { switchViewMode(ViewMode.TEXT) }
        viewerBtn.addActionListener { switchViewMode(ViewMode.VIEWER) }
        prevTabBtn.addActionListener { navigateTab(-1) }
        nextTabBtn.addActionListener { navigateTab(+1) }
        newTabBtn.addActionListener { newTab() }
        deleteTabBtn.addActionListener { deleteTab() }

        // ── Cmd+F at root level → open shared search bar ─────────────────
        val im = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "openSearch")
        actionMap.put("openSearch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { openSearch() }
        })

        // ── Load tabs ────────────────────────────────────────────────────────
        loadTabs()

        // ── Cross-IDE sync listener ──────────────────────────────────────────
        storageService.addListener { updatedTabs, updatedActiveId ->
            SwingUtilities.invokeLater { applyTabs(updatedTabs, updatedActiveId) }
        }
    }

    // ── Tab loading / sync ───────────────────────────────────────────────────

    private fun loadTabs() {
        applyTabs(storageService.getTabs(), storageService.getActiveTabId())
    }

    private fun applyTabs(newTabs: List<SavedTab>, newActiveId: String?) {
        tabs.clear()
        tabs.addAll(newTabs)
        activeTabId = newActiveId ?: tabs.firstOrNull()?.id ?: ""
        refreshActiveTabContent()
        refreshTabBarState()
    }

    private fun activeTab(): SavedTab? = tabs.find { it.id == activeTabId }

    // ── View mode ────────────────────────────────────────────────────────────

    private fun switchViewMode(mode: ViewMode) {
        if (mode == ViewMode.VIEWER) {
            val text = textContent.getText().trim()
            if (text.isEmpty()) {
                showError("Enter JSON in Text mode first.")
                return
            }
            try {
                val parsed = JsonParser.parseString(text)
                viewerContent.loadJson(parsed)
                clearError()
            } catch (_: Exception) {
                showError("Invalid JSON — fix it first.")
                return
            }
        } else {
            clearError()
        }
        // Clear highlights on the panel we're leaving
        activeSearchable().clearSearch()
        viewMode = mode
        showCard(if (mode == ViewMode.TEXT) "text" else "viewer")
        updateToggleState()
        // Re-apply the current search query on the new panel
        reapplySearch()
    }

    private fun showCard(card: String) {
        (contentStack.layout as CardLayout).show(contentStack, card)
    }

    private fun updateToggleState() {
        val isText = viewMode == ViewMode.TEXT
        textBtn.isEnabled = !isText
        viewerBtn.isEnabled = isText
        textBtn.putClientProperty("selected", isText)
        viewerBtn.putClientProperty("selected", !isText)
        styleToggleButton(textBtn, isText)
        styleToggleButton(viewerBtn, !isText)
    }

    // ── Tab operations ────────────────────────────────────────────────────────

    private fun navigateTab(delta: Int) {
        val idx = tabs.indexOfFirst { it.id == activeTabId }
        val newIdx = (idx + delta).coerceIn(0, tabs.lastIndex)
        if (newIdx != idx) {
            activeTabId = tabs[newIdx].id
            storageService.setActiveTab(activeTabId)
            switchToTextMode()
            refreshActiveTabContent()
            refreshTabBarState()
            reapplySearch()
        }
    }

    private fun newTab() {
        val name = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("MMM dd, yyyy h:mm a", Locale.US)
        )
        val saved = storageService.addTab(name = name)
        tabs.add(saved)
        activeTabId = saved.id
        switchToTextMode()
        refreshActiveTabContent()
        refreshTabBarState()
        reapplySearch()
    }

    private fun deleteTab() {
        if (tabs.size <= 1) {
            // Clear the last tab instead of deleting
            val name = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("MMM dd, yyyy h:mm a", Locale.US)
            )
            textContent.setText("")
            storageService.updateTab(activeTabId, name = name, jsonText = "")
            activeTab()?.let { it.name = name; it.jsonText = "" }
            refreshTabBarState()
            reapplySearch()
            return
        }
        val idx = tabs.indexOfFirst { it.id == activeTabId }
        storageService.removeTab(activeTabId)
        tabs.removeAt(idx)
        val newIdx = idx.coerceAtMost(tabs.lastIndex)
        activeTabId = tabs[newIdx].id
        storageService.setActiveTab(activeTabId)
        switchToTextMode()
        refreshActiveTabContent()
        refreshTabBarState()
        reapplySearch()
    }

    private fun switchToTextMode() {
        // Clear highlights on the panel we're leaving
        activeSearchable().clearSearch()
        viewMode = ViewMode.TEXT
        showCard("text")
        updateToggleState()
        clearError()
    }

    private fun refreshActiveTabContent() {
        val tab = activeTab()
        textContent.setTextSilently(tab?.jsonText ?: "")
    }

    private fun refreshTabBarState() {
        val tab = activeTab()
        val idx = tabs.indexOfFirst { it.id == activeTabId }
        val current = if (idx >= 0) idx + 1 else 0
        val total = tabs.size
        val name = tab?.name ?: ""
        tabTitleLabel.text = if (total > 0) "[$current/$total] $name" else name
        prevTabBtn.isEnabled = idx > 0
        nextTabBtn.isEnabled = idx < tabs.lastIndex
        deleteTabBtn.isEnabled = tabs.size > 1
    }

    private fun onActiveTabTextChanged(text: String) {
        storageService.updateTab(activeTabId, jsonText = text)
        activeTab()?.jsonText = text
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────

    private fun pasteFromClipboard() {
        try {
            val clip = Toolkit.getDefaultToolkit().systemClipboard
            val data = clip.getData(DataFlavor.stringFlavor) as? String ?: return
            textContent.setText(data)
            clearError()
        } catch (_: Exception) {}
    }

    private fun copyToClipboard() {
        val text = textContent.getText()
        if (text.isNotEmpty()) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    private fun formatText() {
        try {
            textContent.setText(TextProcessor.format(textContent.getText()))
            clearError()
        } catch (_: Exception) {
            showError("Invalid JSON — cannot format.")
        }
    }

    private fun minifyText() {
        try {
            textContent.setText(TextProcessor.removeWhiteSpace(textContent.getText()))
            clearError()
        } catch (_: Exception) {
            showError("Invalid JSON — cannot minify.")
        }
    }

    // ── Error label ───────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        errorLabel.text = msg
        errorLabel.isVisible = true
    }

    private fun clearError() {
        errorLabel.text = ""
        errorLabel.isVisible = false
    }

    // ── Shared search management ──────────────────────────────────────────────

    private fun activeSearchable(): Searchable =
        if (viewMode == ViewMode.TEXT) textContent else viewerContent

    private fun openSearch() {
        searchPanel.isVisible = true
        searchPanel.focusInput()
        revalidate()
    }

    private fun closeSearch() {
        searchPanel.isVisible = false
        textContent.clearSearch()
        viewerContent.clearSearch()
        revalidate()
    }

    private fun dispatchSearch(query: String) {
        val target = activeSearchable()
        target.applySearch(query)
        val (cur, total) = target.getMatchStatus()
        searchPanel.updateStatus(cur, total)
    }

    private fun dispatchNavigate(delta: Int) {
        val (cur, total) = activeSearchable().navigateSearch(delta)
        searchPanel.updateStatus(cur, total)
    }

    /** Re-run the current search query on the active panel (after mode/tab switch). */
    private fun reapplySearch() {
        val query = searchPanel.getQuery()
        if (searchPanel.isVisible && query.isNotBlank()) {
            dispatchSearch(query)
        }
    }

    // ── Button factories ──────────────────────────────────────────────────────

    private fun headerToggleIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(JBUI.scale(2), JBUI.scale(4))
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            minimumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun styleToggleButton(btn: JButton, active: Boolean) {
        btn.isOpaque = active
        btn.background = if (active) JBColor(Color(0xE0, 0xE0, 0xE0), Color(0x45, 0x45, 0x45))
        else null
    }

    private fun deleteIcon(): Icon =
        IconLoader.getIcon("/icons/delete/delete.svg", javaClass)

    private fun minifyIcon(): Icon =
        IconLoader.getIcon("/icons/minify/changes.svg", javaClass)

    private fun actionIconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(JBUI.scale(2), JBUI.scale(4))
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            minimumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }

    private fun tabNavIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            minimumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            margin = JBUI.insets(0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    // ── Init: apply initial toggle state ─────────────────────────────────────

    init {
        updateToggleState()
    }
}

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
// Text content panel — just the textarea, no toolbar (toolbar is in header)
// ──────────────────────────────────────────────────────────────────────────────

class TextContentPanel(
    private val onTextChanged: (String) -> Unit = {}
) : JPanel(BorderLayout()), Searchable {

    private val textArea = JTextArea()
    private var debounceTimer: Timer? = null
    private var suppressEvents = false

    // ── Search state ──
    private val highlightPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(Color(0xFF, 0xFF, 0x00, 0x90), Color(0xBB, 0xBB, 0x00, 0x90))  // Yellow
    )
    private val currentMatchPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(Color(0xFF, 0xA5, 0x00, 0xC0), Color(0xFF, 0x8C, 0x00, 0xC0))  // Orange for current
    )
    private var searchMatchRanges: List<Pair<Int, Int>> = emptyList()
    private var currentMatchIndex = -1

    init {
        textArea.font = Font("JetBrains Mono", Font.PLAIN, 13).let { f ->
            if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
        textArea.tabSize = 2
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.margin = JBUI.insets(8)

        textArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleNotify()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleNotify()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleNotify()
        })

        add(JBScrollPane(textArea), BorderLayout.CENTER)
    }

    fun getText(): String = textArea.text

    /** Set text and fire onTextChanged. */
    fun setText(text: String) {
        if (textArea.text != text) {
            textArea.text = text
            textArea.caretPosition = 0
        }
    }

    /** Set text without firing the storage callback (used when loading tab data). */
    fun setTextSilently(text: String) {
        suppressEvents = true
        if (textArea.text != text) {
            textArea.text = text
            textArea.caretPosition = 0
        }
        suppressEvents = false
    }

    // ── Searchable implementation ──

    override fun applySearch(query: String) {
        textArea.highlighter.removeAllHighlights()
        searchMatchRanges = emptyList()
        currentMatchIndex = -1

        if (query.isBlank()) return

        val text = textArea.text
        val upperQuery = query.uppercase()
        val upperText = text.uppercase()
        val matches = mutableListOf<Pair<Int, Int>>()
        var idx = 0
        while (idx < upperText.length) {
            val found = upperText.indexOf(upperQuery, idx)
            if (found < 0) break
            matches.add(found to found + query.length)
            idx = found + 1
        }

        searchMatchRanges = matches
        for ((start, end) in matches) {
            textArea.highlighter.addHighlight(start, end, highlightPainter)
        }

        if (matches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightCurrentMatch()
            scrollToCurrentMatch()
        }
    }

    override fun clearSearch() {
        textArea.highlighter.removeAllHighlights()
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

    private fun reapplyHighlights() {
        textArea.highlighter.removeAllHighlights()
        for ((start, end) in searchMatchRanges) {
            textArea.highlighter.addHighlight(start, end, highlightPainter)
        }
    }

    private fun highlightCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatchRanges.size) return
        val (start, end) = searchMatchRanges[currentMatchIndex]
        textArea.highlighter.addHighlight(start, end, currentMatchPainter)
    }

    private fun scrollToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatchRanges.size) return
        val (start, _) = searchMatchRanges[currentMatchIndex]
        textArea.caretPosition = start
        try {
            val rect = textArea.modelToView2D(start)?.bounds
            if (rect != null) textArea.scrollRectToVisible(rect)
        } catch (_: Exception) {}
    }

    private fun scheduleNotify() {
        if (suppressEvents) return
        debounceTimer?.stop()
        debounceTimer = Timer(400) { onTextChanged(textArea.text) }.also {
            it.isRepeats = false
            it.start()
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Viewer content panel — tree + property grid + search bar
// ──────────────────────────────────────────────────────────────────────────────

class ViewerContentPanel : JPanel(BorderLayout()), Searchable {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("(empty)"))
    private val tree = Tree(treeModel)
    private val tableModel = DefaultTableModel(arrayOf("Name", "Value"), 0)
    private val table = JBTable(tableModel)
    private val cellRenderer = JsonTreeCellRenderer()
    private var currentRoot: DefaultMutableTreeNode? = null
    private var searchMatches: List<DefaultMutableTreeNode> = emptyList()
    private var searchMatchSet: Set<DefaultMutableTreeNode> = emptySet()
    private var currentMatchIndex = -1

    init {
        // ── Tree ──
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.cellRenderer = cellRenderer
        tree.isEditable = true
        tree.cellEditor = JsonTreeCellEditor(cellRenderer)
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            showProperties(node)
            val path = tree.selectionPath ?: return@addTreeSelectionListener
            tree.startEditingAtPath(path)
        }

        // ── Tree context menu ──
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? JsonTreeNodeData ?: return
                val expandable = data.type == JsonNodeType.OBJECT || data.type == JsonNodeType.ARRAY
                JPopupMenu().apply {
                    add(JMenuItem("Copy").apply { addActionListener { copyNodeValue(node) } })
                    if (expandable) {
                        addSeparator()
                        add(JMenuItem("Expand").apply { addActionListener { tree.expandPath(path) } })
                        add(JMenuItem("Collapse").apply { addActionListener { tree.collapsePath(path) } })
                        add(JMenuItem("Expand All").apply { addActionListener { expandAllFrom(node) } })
                        add(JMenuItem("Collapse All").apply { addActionListener { collapseAllFrom(node) } })
                    }
                }.show(tree, e.x, e.y)
            }
        })

        // ── Table ──
        table.setShowGrid(true)
        table.tableHeader.reorderingAllowed = false
        table.columnModel.getColumn(0).preferredWidth = 120
        table.columnModel.getColumn(1).preferredWidth = 300
        val selectableEditor = DefaultCellEditor(JTextField().apply {
            isEditable = false
            border = JBUI.Borders.empty(2, 2)
        })
        table.setDefaultEditor(Any::class.java, selectableEditor)

        // ── Right panel: expand/collapse toolbar + table ──
        val rightPanel = JPanel(BorderLayout())
        val viewerToolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 2)
        }
        viewerToolbar.add(viewerToolbarButton(AllIcons.Toolbar.Expand, "Expand") { doExpand() })
        viewerToolbar.add(viewerToolbarButton(AllIcons.Toolbar.Collapse, "Collapse") { doCollapse() })
        viewerToolbar.add(viewerToolbarButton(AllIcons.Actions.Expandall, "Expand All") { doExpandAll() })
        viewerToolbar.add(viewerToolbarButton(AllIcons.Actions.Collapseall, "Collapse All") { doCollapseAll() })
        rightPanel.add(viewerToolbar, BorderLayout.NORTH)
        rightPanel.add(JBScrollPane(table), BorderLayout.CENTER)

        // ── Splitter ──
        val splitter = JBSplitter(false, 0.65f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = rightPanel
        }

        add(splitter, BorderLayout.CENTER)
    }

    private fun viewerToolbarButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(JBUI.scale(2), JBUI.scale(4))
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            minimumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            addActionListener { action() }
        }
    }

    /** Selected node or root if none selected; null when no tree content. */
    private fun targetNodeOrRoot(): DefaultMutableTreeNode? {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return selected ?: currentRoot
    }

    private fun doExpand() {
        val node = targetNodeOrRoot() ?: return
        tree.expandPath(TreePath(node.path))
    }

    private fun doCollapse() {
        val node = targetNodeOrRoot() ?: return
        tree.collapsePath(TreePath(node.path))
    }

    private fun doExpandAll() {
        val node = targetNodeOrRoot() ?: return
        expandAllFrom(node)
    }

    private fun doCollapseAll() {
        val node = targetNodeOrRoot() ?: return
        collapseAllFrom(node)
    }

    fun loadJson(element: JsonElement) {
        val root = buildTreeNode("JSON", element)
        currentRoot = root
        treeModel.setRoot(root)
        treeModel.reload()

        // Expand root + first level
        tree.expandRow(0)
        val rootPath = TreePath(root.path)
        for (i in 0 until root.childCount) {
            tree.expandPath(rootPath.pathByAddingChild(root.getChildAt(i)))
        }

        tableModel.rowCount = 0
        clearSearchState()
    }

    // ── Searchable implementation ──

    override fun applySearch(query: String) {
        clearSearchState()

        if (query.isBlank() || currentRoot == null) {
            tree.repaint()
            return
        }

        val matches = mutableListOf<DefaultMutableTreeNode>()
        collectMatches(currentRoot!!, query.uppercase(), matches)
        searchMatches = matches
        searchMatchSet = matches.toSet()
        currentMatchIndex = if (matches.isNotEmpty()) 0 else -1

        // Update renderer with search query and match set for yellow highlights
        cellRenderer.setSearchHighlight(query, searchMatchSet)
        tree.repaint()

        // Expand tree to reveal all matching nodes
        for (match in matches) {
            tree.expandPath(TreePath(match.path).parentPath ?: continue)
        }

        if (matches.isNotEmpty()) selectAndScrollTo(matches[0])
    }

    override fun clearSearch() {
        clearSearchState()
        tree.repaint()
    }

    override fun navigateSearch(delta: Int): Pair<Int, Int> {
        if (searchMatches.isEmpty()) return 0 to 0
        currentMatchIndex = (currentMatchIndex + delta + searchMatches.size) % searchMatches.size
        selectAndScrollTo(searchMatches[currentMatchIndex])
        return (currentMatchIndex + 1) to searchMatches.size
    }

    override fun getMatchStatus(): Pair<Int, Int> {
        if (searchMatches.isEmpty()) return 0 to 0
        return (currentMatchIndex + 1) to searchMatches.size
    }

    // ── Tree building ──────────────────────────────────────────────────────

    private fun buildTreeNode(key: String, element: JsonElement): DefaultMutableTreeNode {
        return when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                DefaultMutableTreeNode(JsonTreeNodeData(key, null, JsonNodeType.OBJECT, obj.size(), element)).also { node ->
                    for ((k, v) in obj.entrySet()) node.add(buildTreeNode(k, v))
                }
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                DefaultMutableTreeNode(JsonTreeNodeData(key, null, JsonNodeType.ARRAY, arr.size(), element)).also { node ->
                    arr.forEachIndexed { i, item -> node.add(buildTreeNode("[$i]", item)) }
                }
            }
            element.isJsonNull ->
                DefaultMutableTreeNode(JsonTreeNodeData(key, "null", JsonNodeType.NULL, 0, element))
            element.isJsonPrimitive -> {
                val p = element.asJsonPrimitive
                val (value, type) = when {
                    p.isBoolean -> p.asBoolean.toString() to JsonNodeType.BOOLEAN
                    p.isNumber -> p.asNumber.toString() to JsonNodeType.NUMBER
                    else -> "\"${p.asString}\"" to JsonNodeType.STRING
                }
                DefaultMutableTreeNode(JsonTreeNodeData(key, value, type, 0, element))
            }
            else -> DefaultMutableTreeNode(JsonTreeNodeData(key, element.toString(), JsonNodeType.STRING, 0, element))
        }
    }

    // ── Property grid ──────────────────────────────────────────────────────

    private fun showProperties(node: DefaultMutableTreeNode) {
        tableModel.rowCount = 0
        val data = node.userObject as? JsonTreeNodeData ?: return
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                val cd = child.userObject as? JsonTreeNodeData ?: continue
                val display = cd.value ?: when (cd.type) {
                    JsonNodeType.OBJECT -> "{${cd.childCount}}"
                    JsonNodeType.ARRAY -> "[${cd.childCount}]"
                    else -> ""
                }
                tableModel.addRow(arrayOf(cd.key, display))
            }
        } else {
            tableModel.addRow(arrayOf(data.key, data.value ?: ""))
        }
    }

    private fun copyNodeValue(node: DefaultMutableTreeNode) {
        val data = node.userObject as? JsonTreeNodeData ?: return
        val text = data.element?.toString() ?: data.value ?: ""
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun expandAllFrom(node: DefaultMutableTreeNode) {
        tree.expandPath(TreePath(node.path))
        for (i in 0 until node.childCount) {
            expandAllFrom(node.getChildAt(i) as DefaultMutableTreeNode)
        }
    }

    private fun collapseAllFrom(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            collapseAllFrom(node.getChildAt(i) as DefaultMutableTreeNode)
        }
        tree.collapsePath(TreePath(node.path))
    }

    // ── Internal search helpers ──

    private fun collectMatches(node: DefaultMutableTreeNode, upperQuery: String, out: MutableList<DefaultMutableTreeNode>) {
        val data = node.userObject as? JsonTreeNodeData
        if (data != null) {
            val text = if (data.value != null) "${data.key} : ${data.value}" else data.key
            if (text.uppercase().contains(upperQuery)) out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectMatches(node.getChildAt(i) as DefaultMutableTreeNode, upperQuery, out)
        }
    }

    private fun selectAndScrollTo(node: DefaultMutableTreeNode) {
        val path = TreePath(node.path)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun clearSearchState() {
        searchMatches = emptyList()
        searchMatchSet = emptySet()
        currentMatchIndex = -1
        cellRenderer.clearSearchHighlight()
    }

}

// ──────────────────────────────────────────────────────────────────────────────
// Tree cell renderer — colored by type
// ──────────────────────────────────────────────────────────────────────────────

class JsonTreeCellRenderer : DefaultTreeCellRenderer() {

    companion object {
        val COLOR_STRING = JBColor(Color(0x00, 0x7A, 0xCC), Color(0x61, 0xAF, 0xEF))
        val COLOR_NUMBER = JBColor(Color(0x09, 0x86, 0x58), Color(0x98, 0xC3, 0x79))
        val COLOR_BOOLEAN = JBColor(Color(0xAF, 0x6E, 0x0A), Color(0xE5, 0xC0, 0x7B))
        val COLOR_NULL = JBColor(Color(0xCA, 0x39, 0x36), Color(0xE0, 0x6C, 0x75))
        val COLOR_KEY = JBColor(Color(0x2E, 0x2E, 0x2E), Color(0xAB, 0xB2, 0xBF))
        val COLOR_COUNT = JBColor(Color(0x88, 0x88, 0x88), Color(0x63, 0x6D, 0x83))
        val COLOR_HIGHLIGHT_BG = JBColor(Color(0xFF, 0xFF, 0x00), Color(0xBB, 0xBB, 0x00))  // Yellow
    }

    private var searchQuery: String = ""
    private var matchedNodes: Set<DefaultMutableTreeNode> = emptySet()

    fun setSearchHighlight(query: String, matches: Set<DefaultMutableTreeNode>) {
        searchQuery = query
        matchedNodes = matches
    }

    fun clearSearchHighlight() {
        searchQuery = ""
        matchedNodes = emptySet()
    }

    override fun getTreeCellRendererComponent(
        tree: javax.swing.JTree, value: Any?, sel: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        val node = value as? DefaultMutableTreeNode ?: return this
        val data = node.userObject as? JsonTreeNodeData ?: run {
            text = node.userObject?.toString() ?: ""; return this
        }
        val isMatch = matchedNodes.contains(node)

        if (sel) {
            // Plain text when selected (selection bg makes colors hard to read)
            text = when {
                data.value != null -> "${data.key} : ${data.value}"
                data.type == JsonNodeType.OBJECT -> "${data.key} {${data.childCount}}"
                data.type == JsonNodeType.ARRAY -> "${data.key} [${data.childCount}]"
                else -> data.key
            }
        } else {
            val sb = StringBuilder("<html>")
            sb.append("<span style='color:${hex(COLOR_KEY)}'>${highlightText(esc(data.key), isMatch)}</span>")
            when (data.type) {
                JsonNodeType.OBJECT -> {
                    icon = AllIcons.Json.Object
                    sb.append(" <span style='color:${hex(COLOR_COUNT)}'>{${data.childCount}}</span>")
                }
                JsonNodeType.ARRAY -> {
                    icon = AllIcons.Json.Array
                    sb.append(" <span style='color:${hex(COLOR_COUNT)}'>[${data.childCount}]</span>")
                }
                JsonNodeType.STRING -> {
                    icon = AllIcons.Nodes.Variable
                    sb.append(" : <span style='color:${hex(COLOR_STRING)}'>${highlightText(esc(data.value ?: ""), isMatch)}</span>")
                }
                JsonNodeType.NUMBER -> {
                    icon = AllIcons.Nodes.Variable
                    sb.append(" : <span style='color:${hex(COLOR_NUMBER)}'>${highlightText(esc(data.value ?: ""), isMatch)}</span>")
                }
                JsonNodeType.BOOLEAN -> {
                    icon = AllIcons.Nodes.Variable
                    sb.append(" : <span style='color:${hex(COLOR_BOOLEAN)}'>${highlightText(esc(data.value ?: ""), isMatch)}</span>")
                }
                JsonNodeType.NULL -> {
                    icon = AllIcons.Nodes.Variable
                    sb.append(" : <span style='color:${hex(COLOR_NULL)}'>${highlightText("null", isMatch)}</span>")
                }
            }
            sb.append("</html>")
            text = sb.toString()
        }
        return this
    }

    /**
     * Wraps matching portions of [text] in a yellow-background span.
     * [text] should already be HTML-escaped.
     * Only highlights if this node is a match and a search query is active.
     */
    private fun highlightText(text: String, isMatch: Boolean): String {
        if (!isMatch || searchQuery.isBlank()) return text
        val escapedQuery = esc(searchQuery)
        val upperText = text.uppercase()
        val upperQuery = escapedQuery.uppercase()
        if (!upperText.contains(upperQuery)) return text

        val sb = StringBuilder()
        var idx = 0
        while (idx < text.length) {
            val found = upperText.indexOf(upperQuery, idx)
            if (found < 0) {
                sb.append(text, idx, text.length)
                break
            }
            sb.append(text, idx, found)
            sb.append("<span style='background-color:${hex(COLOR_HIGHLIGHT_BG)}; color:#000000'>")
            sb.append(text, found, found + escapedQuery.length)
            sb.append("</span>")
            idx = found + escapedQuery.length
        }
        return sb.toString()
    }

    private fun hex(c: JBColor) = String.format("#%02x%02x%02x", (c as Color).red, c.green, c.blue)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Plain display text for a node (used by tree cell editor for selectable copy). */
    fun getDisplayText(node: DefaultMutableTreeNode): String {
        val data = node.userObject as? JsonTreeNodeData ?: return node.userObject?.toString() ?: ""
        return when {
            data.value != null -> "${data.key} : ${data.value}"
            data.type == JsonNodeType.OBJECT -> "${data.key} {${data.childCount}}"
            data.type == JsonNodeType.ARRAY -> "${data.key} [${data.childCount}]"
            else -> data.key
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Tree cell editor — selectable text field when a node is selected
// ──────────────────────────────────────────────────────────────────────────────

class JsonTreeCellEditor(
    private val renderer: JsonTreeCellRenderer
) : AbstractCellEditor(), TreeCellEditor {

    private val textField = JTextField().apply {
        isEditable = false
        border = JBUI.Borders.empty(2, 1)
        font = Font("JetBrains Mono", Font.PLAIN, 13).let { f ->
            if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (!e.isTemporary) stopCellEditing()
            }
        })
    }

    override fun getTreeCellEditorComponent(
        tree: javax.swing.JTree,
        value: Any?,
        isSelected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int
    ): Component {
        val node = value as? DefaultMutableTreeNode ?: return textField
        textField.text = renderer.getDisplayText(node)
        textField.background = if (isSelected) UIManager.getColor("Tree.selectionBackground") ?: tree.background else tree.background
        textField.foreground = if (isSelected) UIManager.getColor("Tree.selectionForeground") ?: tree.foreground else tree.foreground
        return textField
    }

    override fun getCellEditorValue(): Any = textField.text
}

// ──────────────────────────────────────────────────────────────────────────────
// Search panel (bottom bar in viewer)
// ──────────────────────────────────────────────────────────────────────────────

class SearchPanel : JPanel(FlowLayout(FlowLayout.LEFT, 8, 3)) {

    private val searchField = JBTextField()
    private val statusLabel = JBLabel("")
    private var debounceTimer: Timer? = null

    var onSearch: (String) -> Unit = {}
    var onNext: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onClose: () -> Unit = {}

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)

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

    private fun debounce() {
        debounceTimer?.stop()
        debounceTimer = Timer(150) { onSearch(searchField.text) }.also {
            it.isRepeats = false; it.start()
        }
    }

    private fun navBtn(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            isFocusPainted = false
            margin = JBUI.insets(2, 6)
            font = font.deriveFont(Font.PLAIN, 11f)
            addActionListener { action() }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Text processing utilities
// ──────────────────────────────────────────────────────────────────────────────

object TextProcessor {

    /** Pretty-print. Works on any text — character-level state machine. */
    fun format(text: String): String {
        val input = text.replace("\r", "").replace("\n", " ")
        val out = StringBuilder()
        var depth = 0
        var inString: Char? = null

        for (i in input.indices) {
            val ch = input[i]
            when {
                inString != null && ch == inString && (i == 0 || input[i - 1] != '\\') -> {
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
        val out = StringBuilder()
        var inString: Char? = null

        for (i in input.indices) {
            val ch = input[i]
            when {
                inString != null && ch == inString && (i == 0 || input[i - 1] != '\\') -> {
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

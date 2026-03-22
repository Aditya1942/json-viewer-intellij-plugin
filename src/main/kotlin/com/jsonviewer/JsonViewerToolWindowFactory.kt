package com.jsonviewer

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.jsonviewer.ui.SearchPanel
import com.jsonviewer.ui.Searchable
import com.jsonviewer.ui.TextContentPanel
import com.jsonviewer.ui.ViewerContentPanel
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.*

// ──────────────────────────────────────────────────────────────────────────────
// Factory
// ──────────────────────────────────────────────────────────────────────────────

class JsonViewerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JsonViewerPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Root panel — tab bar on top, header below:
//
//  ┌──────────────────────────────────────────────────────┐
//  │  <tab title>                                      [✕] │ ← tab bar
//  ├──────────────────────────────────────────────────────┤
//  │ [📄][🌲] | [+] | [‹][›] error?   [📋][📑][◫][▣]      │ ← header
//  ├──────────────────────────────────────────────────────┤
//  │                                                      │
//  │  content (text editor  OR  tree viewer)              │
//  │                                                      │
//  └──────────────────────────────────────────────────────┘
// ──────────────────────────────────────────────────────────────────────────────

class JsonViewerPanel : JPanel(BorderLayout()), Disposable {

    companion object {
        private val LOG = Logger.getInstance(JsonViewerPanel::class.java)
    }

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

    // ── Stored listener reference for cleanup ──
    private var storageListener: TabStorageListener? = null

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

        // Left: Text/Viewer | New tab | Prev/Next | error
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(6))
        }
        headerLeft.add(textBtn)
        headerLeft.add(viewerBtn)
        headerLeft.add(headerVerticalSeparator())
        headerLeft.add(newTabBtn)
        headerLeft.add(headerVerticalSeparator())
        headerLeft.add(prevTabBtn)
        headerLeft.add(nextTabBtn)
        headerLeft.add(errorLabel)

        // Right: action icon buttons (Paste, Copy, Format, Minify)
        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), JBUI.scale(2)).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(6))
        }
        headerRight.add(actionIconButton(AllIcons.Actions.MenuPaste, "Paste") { pasteFromClipboard() })
        headerRight.add(actionIconButton(AllIcons.Actions.Copy, "Copy") { copyToClipboard() })
        headerRight.add(actionIconButton(AllIcons.Diff.MagicResolveToolbar, "Format") { formatText() })
        headerRight.add(actionIconButton(minifyIcon(), "Minify") { minifyText() })

        header.add(headerLeft, BorderLayout.WEST)
        header.add(headerRight, BorderLayout.EAST)

        // ── Tab bar: tab title + delete ───────────────────────────────────────
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
        storageListener = TabStorageListener { updatedTabs, updatedActiveId ->
            SwingUtilities.invokeLater { applyTabs(updatedTabs, updatedActiveId) }
        }
        storageService.addListener(storageListener!!)
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
        val tabName = activeTab()?.name?.trim().orEmpty().ifEmpty { "Untitled" }
        val confirmed = Messages.showYesNoDialog(
            this,
            "Delete tab \"$tabName\"?",
            "Delete Tab",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!confirmed) return

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
        } catch (e: Exception) {
            LOG.warn("Failed to paste from clipboard", e)
        }
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

    private fun headerVerticalSeparator(): JSeparator {
        val h = JBUI.scale(18)
        return JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(JBUI.scale(10), h)
            maximumSize = Dimension(JBUI.scale(12), h)
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

    // ── Disposable — clean up listeners and timers ─────────────────────────

    override fun dispose() {
        // Remove storage listener to prevent memory leak
        storageListener?.let { storageService.removeListener(it) }
        storageListener = null
        // Stop debounce timers to prevent firing after dispose
        searchPanel.stopTimers()
        // Dispose the editor component (releases IntelliJ Editor resources)
        Disposer.dispose(textContent)
    }
}

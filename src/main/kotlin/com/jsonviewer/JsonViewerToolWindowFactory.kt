package com.jsonviewer

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.jsonviewer.ui.ideSeparatorColor
import com.jsonviewer.ui.SearchPanel
import com.jsonviewer.ui.Searchable
import com.jsonviewer.ui.NoteHighlightMode
import com.jsonviewer.ui.NoteSyntaxHighlightUi
import com.jsonviewer.ui.SyntaxHighlightComboItem
import com.jsonviewer.ui.TextContentPanel
import com.jsonviewer.dev.DevIconsExplorerDialog
import com.jsonviewer.dev.DevMode
import com.jsonviewer.ui.ViewerContentPanel
import com.jsonviewer.ui.installIconButtonHover
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// ──────────────────────────────────────────────────────────────────────────────
// Factory
// ──────────────────────────────────────────────────────────────────────────────

class JsonViewerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JsonViewerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Root panel — two modes (CardLayout):
//
//  Main: tab bar → header → text/viewer + search bar
//
//  All notes: full-area overlay (replaces tab bar + header + editor), [← Back] row on top
// ──────────────────────────────────────────────────────────────────────────────

class JsonViewerPanel(
    private val project: Project,
    /**
     * Main editor only: [com.intellij.openapi.fileEditor.FileDocumentManager] document for [JsonNotesVirtualFile].
     * Tool window uses null and a standalone document.
     */
    private val sharedEditorDocument: Document? = null
) : JPanel(BorderLayout()), Disposable, DataProvider {

    companion object {
        private val LOG = Logger.getInstance(JsonViewerPanel::class.java)

        /** Matches [com.intellij.toolWindow] id in plugin.xml. */
        const val TOOL_WINDOW_ID: String = "JSON Notes"

        private const val ROOT_CARD_MAIN = "main"
        private const val ROOT_CARD_NOTES_LIST = "notesListFull"
        private const val ROOT_CARD_SETTINGS = "settingsFull"
        private const val NOTES_HEADER_DEFAULT = "notesHeaderDefault"
        private const val NOTES_HEADER_SEARCH = "notesHeaderSearch"

        internal fun findJsonViewerPanel(project: Project): JsonViewerPanel? {
            var c: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            while (c != null) {
                if (c is JsonViewerPanel) return c
                c = c.parent
            }
            val selected = FileEditorManager.getInstance(project).selectedEditor
            if (selected is JsonNotesFileEditor) {
                return selected.viewerPanel()
            }
            return findJsonViewerPanelFromToolWindow(project)
        }

        internal fun findJsonViewerPanelFromToolWindow(project: Project): JsonViewerPanel? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            val contents = tw.contentManager.contents
            if (contents.isEmpty()) return null
            return contents[0].component as? JsonViewerPanel
        }
    }

    // ── View mode ──
    private enum class ViewMode { TEXT, VIEWER }
    private var viewMode = ViewMode.TEXT

    // ── Tab storage ──
    private val storageService = TabStorageService.getInstance()
    private val uiSettings = JsonViewerUiSettings.getInstance()
    private val tabs = mutableListOf<SavedTab>()
    private var activeTabId: String = ""

    // ── Content panels ──
    private val textContent = TextContentPanel(
        project = project,
        onTextChanged = { text -> onActiveTabTextChanged(text) },
        sharedEditorDocument = sharedEditorDocument
    )
    private val viewerContent = ViewerContentPanel()

    /**
     * Main-editor [FileEditor] tabs build data context from the file editor component; without this,
     * global Undo/Redo does not resolve the embedded [TextContentPanel] editor.
     */
    override fun getData(dataId: String): Any? {
        if (CommonDataKeys.PROJECT.`is`(dataId)) return project
        if (CommonDataKeys.EDITOR.`is`(dataId)) {
            return if (viewMode == ViewMode.TEXT) textContent.editor else null
        }
        return null
    }

    /** Used by [JsonNotesFileEditor] implementing [com.intellij.openapi.fileEditor.TextEditor]. */
    internal fun embeddedEditor(): Editor = textContent.editor
    private val contentStack = JPanel(CardLayout())
    /** Main chrome (tab bar + toolbar + editor) vs full-tool-window notes list. */
    private val rootStack = JPanel(CardLayout())
    private var notesListOverlayOpen = false
    private var notesListSearchMode = false
    private var settingsOverlayOpen = false

    private val notesListBackBtn = tabNavIconButton(AllIcons.Actions.Back, "Back to editor")
    private val notesListRowsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val notesListSearchGeneration = AtomicInteger(0)
    private val notesListContentDebounceTimer = Timer(200, null).apply {
        isRepeats = false
        addActionListener {
            val q = notesListSearchField.text.trim()
            val g = notesListSearchGeneration.get()
            runNotesListContentPhase(q, g)
        }
    }
    private val notesListSearchField = JBTextField().apply {
        emptyText.text = "Search by title or content…"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                scheduleNotesListSearch()
            }

            override fun removeUpdate(e: DocumentEvent) {
                scheduleNotesListSearch()
            }

            override fun changedUpdate(e: DocumentEvent) {
                scheduleNotesListSearch()
            }
        })
    }
    private val notesListClearSearchBtn = tabNavIconButton(AllIcons.Actions.Cancel, "Clear search").apply {
        addActionListener {
            notesListSearchField.text = ""
            scheduleNotesListSearch()
        }
    }
    private val notesListSearchBackBtn = tabNavIconButton(AllIcons.Actions.Back, "Close search").apply {
        addActionListener { exitNotesListSearchMode() }
    }
    private val notesListSearchToggleBtn = tabNavIconButton(AllIcons.Actions.Find, "Search notes").apply {
        addActionListener { enterNotesListSearchMode() }
    }
    private val notesListTopBar = JPanel(CardLayout()).apply {
        border = JBUI.Borders.customLine(ideSeparatorColor(), 0, 0, 1, 0)
        minimumSize = Dimension(0, JBUI.scale(36))
    }

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
    private val openInEditorBtn = tabNavIconButton(AllIcons.General.FitContent, "Open in main editor")
    private val deleteTabBtn = tabNavIconButton(deleteIcon(), "Delete tab")
    private val listTabsBtn = tabNavIconButton(AllIcons.General.Tree, "All notes (list)")
    private val settingsTabsBtn = tabNavIconButton(AllIcons.General.Settings, "Settings")
    private val settingsBackBtn = tabNavIconButton(AllIcons.Actions.Back, "Back to editor")
    private val settingsFontFamilyCombo = JComboBox<String>()
    private val settingsFontSizeSpinner = JSpinner(
        SpinnerNumberModel(13, JsonViewerUiSettings.MIN_FONT_SIZE, JsonViewerUiSettings.MAX_FONT_SIZE, 1)
    )
    private val settingsOkBtn = JButton("OK").apply {
        margin = JBUI.insets(4, 16, 4, 16)
    }
    private val settingsCancelBtn = JButton("Cancel").apply {
        margin = JBUI.insets(4, 16, 4, 16)
    }
    private val settingsHideCopyCb = JCheckBox("Hide copy")
    private val settingsHidePasteCb = JCheckBox("Hide paste")
    private val settingsHideFormatCb = JCheckBox("Hide format")
    private val settingsHideMinifyCb = JCheckBox("Hide minify")
    private val settingsHideViewerCb = JCheckBox("Hide viewer")
    private val settingsHideOpenInEditorCb = JCheckBox("Hide open in main editor")
    /** Keyboard shortcuts table model; rows match [JsonNotesShortcutsUi.ACTION_ROWS]. */
    private var settingsShortcutsTableModel: DefaultTableModel? = null

    private val pasteBtn = actionIconButton(AllIcons.Actions.MenuPaste, "Paste") { pasteFromClipboard() }
    private val copyBtn = actionIconButton(AllIcons.Actions.Copy, "Copy") { copyToClipboard() }
    private val formatBtn = actionIconButton(AllIcons.Diff.MagicResolveToolbar, "Format") { formatText() }
    private val minifyBtn = actionIconButton(minifyIcon(), "Minify") { minifyText() }

    private val syntaxHighlightComboModel = NoteSyntaxHighlightUi.createComboBoxModel()

    /** Vertical rule between Text/Viewer toggles and the New tab group; hidden when mode toggles are hidden. */
    private val headerAfterTextViewerSeparator = headerVerticalSeparator()

    init {
        // ── Header (no title; responsive wrap) ──────────────────────────────
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(ideSeparatorColor(), 0, 0, 1, 0)
            minimumSize = Dimension(0, JBUI.scale(28))
        }

        // Left: Text/Viewer | New tab | Prev/Next | error
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(4))
        }
        headerLeft.add(textBtn)
        headerLeft.add(viewerBtn)
        headerLeft.add(headerAfterTextViewerSeparator)
        headerLeft.add(newTabBtn)
        headerLeft.add(headerVerticalSeparator())
        headerLeft.add(prevTabBtn)
        headerLeft.add(nextTabBtn)
        headerLeft.add(errorLabel)

        // Right: action icon buttons (Paste, Copy, Format, Minify)
        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(4))
        }
        headerRight.add(pasteBtn)
        headerRight.add(copyBtn)
        headerRight.add(formatBtn)
        headerRight.add(minifyBtn)

        header.add(headerLeft, BorderLayout.WEST)
        header.add(headerRight, BorderLayout.EAST)

        // ── Tab bar: tab title + delete ───────────────────────────────────────
        val tabBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(ideSeparatorColor(), 0, 0, 1, 0)
            minimumSize = Dimension(0, JBUI.scale(28))
        }

        val tabBarLeft = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(JBUI.scale(6))
        }
        tabTitleLabel.verticalAlignment = SwingConstants.CENTER
        tabTitleLabel.horizontalAlignment = SwingConstants.LEFT
        tabBarLeft.add(tabTitleLabel, BorderLayout.CENTER)

        val tabBarRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0).apply { alignOnBaseline = true }).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(4))
        }
        tabBarRight.add(openInEditorBtn)
        tabBarRight.add(deleteTabBtn)
        tabBarRight.add(headerVerticalSeparator())
        tabBarRight.add(listTabsBtn)
        tabBarRight.add(settingsTabsBtn)

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

        // ── Full-area notes list (replaces tab bar + toolbar + editor + search) ─
        val notesListTitleLabel = JBLabel("All notes").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            verticalAlignment = SwingConstants.CENTER
            alignmentX = Component.LEFT_ALIGNMENT
            alignmentY = Component.CENTER_ALIGNMENT
        }
        val notesListHeaderDefault = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, JBUI.scale(6), 4, JBUI.scale(6))
            add(notesListBackBtn.apply { alignmentY = Component.CENTER_ALIGNMENT })
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(notesListTitleLabel)
            add(Box.createHorizontalGlue())
            add(notesListSearchToggleBtn.apply { alignmentY = Component.CENTER_ALIGNMENT })
        }
        val notesListHeaderSearch = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, JBUI.scale(8), 4, JBUI.scale(8))
            isOpaque = false
            add(notesListSearchBackBtn.apply { alignmentY = Component.CENTER_ALIGNMENT })
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(
                notesListSearchField.apply {
                    alignmentY = Component.CENTER_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
                }
            )
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(notesListClearSearchBtn.apply { alignmentY = Component.CENTER_ALIGNMENT })
        }
        notesListTopBar.add(notesListHeaderDefault, NOTES_HEADER_DEFAULT)
        notesListTopBar.add(notesListHeaderSearch, NOTES_HEADER_SEARCH)

        val notesListScroll = JBScrollPane(notesListRowsPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        val notesListBody = JPanel(BorderLayout()).apply {
            add(notesListScroll, BorderLayout.CENTER)
        }

        val notesListOverlay = JPanel(BorderLayout()).apply {
            add(notesListTopBar, BorderLayout.NORTH)
            add(notesListBody, BorderLayout.CENTER)
        }
        notesListBackBtn.addActionListener { hideNotesListOverlay() }
        notesListOverlay.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            "closeNotesOverlay"
        )
        notesListOverlay.actionMap.put(
            "closeNotesOverlay",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    handleNotesListOverlayEscape()
                }
            }
        )

        // ── Full-area settings (same shell as All notes) ─────────────────────
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
            .sorted()
            .forEach { settingsFontFamilyCombo.addItem(it) }
        settingsFontFamilyCombo.maximumRowCount = 16

        val settingsTitleLabel = JBLabel("Settings").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            verticalAlignment = SwingConstants.CENTER
            alignmentX = Component.LEFT_ALIGNMENT
            alignmentY = Component.CENTER_ALIGNMENT
        }
        val settingsHeaderRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, JBUI.scale(6), 4, JBUI.scale(6))
            add(settingsBackBtn.apply { alignmentY = Component.CENTER_ALIGNMENT })
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(settingsTitleLabel)
            add(Box.createHorizontalGlue())
        }
        val fontSectionTitle = JBLabel("Font").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val settingsFontFamilyRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            add(JBLabel("Font family:"))
            add(
                settingsFontFamilyCombo.apply {
                    alignmentY = Component.CENTER_ALIGNMENT
                }
            )
        }
        val settingsFontSizeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            add(JBLabel("Font size:"))
            add(settingsFontSizeSpinner)
        }
        val toolbarSectionTitle = JBLabel("Toolbar").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val toolbarCheckboxGrid = JPanel(GridLayout(3, 2, JBUI.scale(12), JBUI.scale(6))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(settingsHideCopyCb)
            add(settingsHidePasteCb)
            add(settingsHideFormatCb)
            add(settingsHideMinifyCb)
            add(settingsHideViewerCb)
            add(settingsHideOpenInEditorCb)
        }
        val settingsScrollContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, JBUI.scale(12), 8, JBUI.scale(12))
            add(fontSectionTitle)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(settingsFontFamilyRow)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(settingsFontSizeRow)
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(toolbarSectionTitle)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
                    add(toolbarCheckboxGrid)
                }
            )
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(
                JBLabel("Keyboard shortcuts").apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(
                JBLabel(
                    "Manage shortcuts in IDE Settings → Keymap (search \"JSON Notes\"). " +
                        "Changes apply after you close the Keymap dialog.",
                ).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(
                JBScrollPane(
                    JBTable(
                        object : DefaultTableModel(
                            JsonNotesShortcutsUi.ACTION_ROWS.map { row ->
                                arrayOf<Any>(
                                    row.second,
                                    JsonNotesShortcutsUi.shortcutText(row.first),
                                )
                            }.toTypedArray(),
                            arrayOf("Action", "Shortcut"),
                        ) {
                            override fun isCellEditable(row: Int, column: Int): Boolean = false
                        }.also { settingsShortcutsTableModel = it },
                    ).apply {
                        setShowGrid(true)
                        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
                        rowHeight = JBUI.scale(22)
                        preferredScrollableViewportSize = Dimension(
                            Int.MAX_VALUE,
                            JBUI.scale(22) * JsonNotesShortcutsUi.ACTION_ROWS.size.coerceAtMost(10) + JBUI.scale(24),
                        )
                        columnModel.getColumn(0).preferredWidth = JBUI.scale(280)
                        columnModel.getColumn(1).preferredWidth = JBUI.scale(220)
                    },
                ).apply {
                    border = JBUI.Borders.empty()
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22) * JsonNotesShortcutsUi.ACTION_ROWS.size.coerceAtMost(10) + JBUI.scale(40))
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
                    add(
                        JButton("Edit keymap…").apply {
                            addActionListener { openIdeKeymapSettings() }
                        },
                    )
                },
            )
            if (DevMode.isDevIconsExplorerEnabled()) {
                add(Box.createVerticalStrut(JBUI.scale(16)))
                add(
                    JBLabel("Development").apply {
                        font = font.deriveFont(Font.BOLD, 13f)
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                )
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
                        isOpaque = false
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
                        add(
                            JButton("Browse AllIcons (dev)…").apply {
                                addActionListener {
                                    DevIconsExplorerDialog(project).show()
                                }
                            }
                        )
                    }
                )
            }
            add(Box.createVerticalGlue())
        }
        val settingsScroll = JBScrollPane(settingsScrollContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        val settingsFooterRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, JBUI.scale(12), 8, JBUI.scale(12))
            add(settingsCancelBtn)
            add(settingsOkBtn)
        }
        val settingsOverlay = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.customLine(ideSeparatorColor(), 0, 0, 1, 0)
                    minimumSize = Dimension(0, JBUI.scale(36))
                    add(settingsHeaderRow, BorderLayout.CENTER)
                },
                BorderLayout.NORTH
            )
            add(settingsScroll, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.customLine(ideSeparatorColor(), 1, 0, 0, 0)
                    add(settingsFooterRow, BorderLayout.EAST)
                },
                BorderLayout.SOUTH
            )
        }
        settingsBackBtn.addActionListener { cancelSettingsOverlay() }
        settingsOkBtn.addActionListener { applySettingsAndClose() }
        settingsCancelBtn.addActionListener { cancelSettingsOverlay() }
        settingsOverlay.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            "closeSettingsOverlay"
        )
        settingsOverlay.actionMap.put(
            "closeSettingsOverlay",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    cancelSettingsOverlay()
                }
            }
        )

        // ── Shared search bar (hidden by default) ─────────────────────────
        searchPanel.isVisible = false
        searchPanel.onSearch = { query -> dispatchSearch(query) }
        searchPanel.onNext = { dispatchNavigate(+1) }
        searchPanel.onPrevious = { dispatchNavigate(-1) }
        searchPanel.onClose = { closeSearch() }

        val mainViewPanel = JPanel(BorderLayout()).apply {
            add(topChrome, BorderLayout.NORTH)
            add(contentStack, BorderLayout.CENTER)
            add(searchPanel, BorderLayout.SOUTH)
        }

        rootStack.add(mainViewPanel, ROOT_CARD_MAIN)
        rootStack.add(notesListOverlay, ROOT_CARD_NOTES_LIST)
        rootStack.add(settingsOverlay, ROOT_CARD_SETTINGS)

        // ── Root ────────────────────────────────────────────────────────────
        add(rootStack, BorderLayout.CENTER)

        // ── Wire buttons ────────────────────────────────────────────────────
        textBtn.addActionListener { switchViewMode(ViewMode.TEXT) }
        viewerBtn.addActionListener { switchViewMode(ViewMode.VIEWER) }
        prevTabBtn.addActionListener { navigateTab(-1) }
        nextTabBtn.addActionListener { navigateTab(+1) }
        newTabBtn.addActionListener { newTab() }
        openInEditorBtn.addActionListener { openInMainEditor() }
        deleteTabBtn.addActionListener { deleteTab() }
        listTabsBtn.addActionListener { showNotesListOverlay() }
        settingsTabsBtn.addActionListener { showSettingsOverlay() }

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

        applyUiSettingsToEditor()
        applyHeaderToolbarVisibility()

        textContent.setSyntaxHighlightMenuHandler { anchor -> openSyntaxHighlightPopup(anchor) }
    }

    private fun openSyntaxHighlightPopup(anchor: Component) {
        val items = (0 until syntaxHighlightComboModel.size).map { syntaxHighlightComboModel.getElementAt(it) }
        NoteSyntaxHighlightUi.showHighlightModePopup(anchor, items) { picked ->
            if (picked is SyntaxHighlightComboItem.AllTypesAction) {
                SwingUtilities.invokeLater {
                    NoteSyntaxHighlightUi.showAllFileTypesPopup(anchor) { ft ->
                        applySyntaxHighlightMode(NoteHighlightMode.Explicit(ft))
                    }
                }
                return@showHighlightModePopup
            }
            val mode = picked.toNoteHighlightMode() ?: return@showHighlightModePopup
            applySyntaxHighlightMode(mode)
        }
    }

    private fun applySyntaxHighlightMode(mode: NoteHighlightMode) {
        textContent.setHighlightMode(mode)
        val serialized = NoteHighlightMode.toSerialized(mode)
        storageService.updateTab(activeTabId, highlightMode = serialized)
        activeTab()?.highlightMode = serialized
        syncSyntaxHighlightControl(mode)
    }

    private fun syncSyntaxHighlightControl(mode: NoteHighlightMode) {
        val elements = (0 until syntaxHighlightComboModel.size).map { syntaxHighlightComboModel.getElementAt(it) }
        var target = SyntaxHighlightComboItem.findItemForMode(elements, mode)
        if (target is SyntaxHighlightComboItem.Explicit) {
            val exists = elements.any {
                it is SyntaxHighlightComboItem.Explicit && it.fileType.name == target.fileType.name
            }
            if (!exists) {
                val allIdx = elements.indexOf(SyntaxHighlightComboItem.AllTypesAction)
                if (allIdx >= 0) {
                    syntaxHighlightComboModel.insertElementAt(target, allIdx)
                } else {
                    syntaxHighlightComboModel.addElement(target)
                }
            }
        }
        textContent.updateSyntaxHighlightPresentation(mode)
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
        if (notesListOverlayOpen) {
            scheduleNotesListSearch()
        }
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

    /** Switch to a tab from the All notes list (including search results) and return to the editor. */
    private fun openNoteFromList(tabId: String) {
        if (tabs.none { it.id == tabId }) return
        if (activeTabId != tabId) {
            activeTabId = tabId
            storageService.setActiveTab(activeTabId)
            switchToTextMode()
            refreshActiveTabContent()
            refreshTabBarState()
            reapplySearch()
        }
        hideNotesListOverlay()
    }

    private fun installNotesListRowOpenHandlers(root: JComponent, tabId: String) {
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                openNoteFromList(tabId)
            }
        }
        val queue = ArrayDeque<JComponent>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            c.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            c.addMouseListener(listener)
            for (i in 0 until c.componentCount) {
                val child = c.getComponent(i)
                if (child is JComponent) queue.add(child)
            }
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

    private fun openInMainEditor() {
        openJsonNotesInMainEditor(project)
    }

    internal fun performNewTab() {
        newTab()
    }

    internal fun performNavigateTab(delta: Int) {
        navigateTab(delta)
    }

    internal fun performOpenInMainEditor() {
        openInMainEditor()
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

    private fun showRootCard(name: String) {
        (rootStack.layout as CardLayout).show(rootStack, name)
    }

    private fun showNotesListHeaderCard(name: String) {
        (notesListTopBar.layout as CardLayout).show(notesListTopBar, name)
    }

    private fun showNotesListOverlay() {
        closeSearch()
        settingsOverlayOpen = false
        showRootCard(ROOT_CARD_NOTES_LIST)
        notesListOverlayOpen = true
        notesListSearchMode = false
        notesListSearchField.text = ""
        showNotesListHeaderCard(NOTES_HEADER_DEFAULT)
        scheduleNotesListSearch()
        notesListBackBtn.requestFocusInWindow()
    }

    private fun hideNotesListOverlay() {
        notesListContentDebounceTimer.stop()
        notesListSearchGeneration.incrementAndGet()
        notesListOverlayOpen = false
        notesListSearchMode = false
        notesListSearchField.text = ""
        showNotesListHeaderCard(NOTES_HEADER_DEFAULT)
        showRootCard(ROOT_CARD_MAIN)
        reapplySearch()
    }

    private fun showSettingsOverlay() {
        closeSearch()
        if (notesListOverlayOpen) {
            notesListContentDebounceTimer.stop()
            notesListSearchGeneration.incrementAndGet()
            notesListOverlayOpen = false
            notesListSearchMode = false
            notesListSearchField.text = ""
            showNotesListHeaderCard(NOTES_HEADER_DEFAULT)
        }
        settingsOverlayOpen = true
        syncSettingsUiFromState()
        showRootCard(ROOT_CARD_SETTINGS)
        settingsOkBtn.requestFocusInWindow()
    }

    private fun hideSettingsOverlay() {
        settingsOverlayOpen = false
        showRootCard(ROOT_CARD_MAIN)
        reapplySearch()
    }

    /** Discard edits and close (Back, Cancel, Escape). */
    private fun cancelSettingsOverlay() {
        if (!settingsOverlayOpen) return
        syncSettingsUiFromState()
        hideSettingsOverlay()
    }

    /** Persist font from the form, apply to the text editor, and close. */
    private fun applySettingsAndClose() {
        if (!settingsOverlayOpen) return
        persistFontFromSettingsUi()
        persistToolbarFromSettingsUi()
        hideSettingsOverlay()
    }

    private fun syncSettingsUiFromState() {
        val fam = uiSettings.fontFamily()
        var idx = -1
        for (i in 0 until settingsFontFamilyCombo.itemCount) {
            if (settingsFontFamilyCombo.getItemAt(i) == fam) {
                idx = i
                break
            }
        }
        settingsFontFamilyCombo.selectedIndex = if (idx >= 0) idx else 0
        settingsFontSizeSpinner.value = uiSettings.fontSize()
        settingsHideCopyCb.isSelected = uiSettings.hideCopy()
        settingsHidePasteCb.isSelected = uiSettings.hidePaste()
        settingsHideFormatCb.isSelected = uiSettings.hideFormat()
        settingsHideMinifyCb.isSelected = uiSettings.hideMinify()
        settingsHideViewerCb.isSelected = uiSettings.hideViewer()
        settingsHideOpenInEditorCb.isSelected = uiSettings.hideOpenInMainEditor()
        val tm = settingsShortcutsTableModel
        if (tm != null) {
            for (i in JsonNotesShortcutsUi.ACTION_ROWS.indices) {
                val actionId = JsonNotesShortcutsUi.ACTION_ROWS[i].first
                tm.setValueAt(JsonNotesShortcutsUi.shortcutText(actionId), i, 1)
            }
        }
    }

    /** Opens IDE Settings → Keymap (non-modal) so it works from the full-screen settings overlay. */
    private fun openIdeKeymapSettings() {
        val p = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            ?: if (!project.isDisposed) project else ProjectManager.getInstance().defaultProject
        if (p.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            ShowSettingsUtil.getInstance().showSettingsDialog(p, "preferences.keymap")
        }
    }

    private fun persistFontFromSettingsUi() {
        val family = settingsFontFamilyCombo.selectedItem as? String ?: return
        val size = (settingsFontSizeSpinner.value as? Number)?.toInt() ?: return
        uiSettings.updateFont(family, size)
        textContent.applyFontSettings(uiSettings.fontFamily(), uiSettings.fontSize())
    }

    private fun persistToolbarFromSettingsUi() {
        uiSettings.updateToolbarVisibility(
            hideCopy = settingsHideCopyCb.isSelected,
            hidePaste = settingsHidePasteCb.isSelected,
            hideFormat = settingsHideFormatCb.isSelected,
            hideMinify = settingsHideMinifyCb.isSelected,
            hideViewer = settingsHideViewerCb.isSelected,
            hideOpenInMainEditor = settingsHideOpenInEditorCb.isSelected,
        )
        applyHeaderToolbarVisibility()
    }

    /** Show or hide header icon buttons according to persisted settings. */
    private fun applyHeaderToolbarVisibility() {
        copyBtn.isVisible = !uiSettings.hideCopy()
        pasteBtn.isVisible = !uiSettings.hidePaste()
        formatBtn.isVisible = !uiSettings.hideFormat()
        minifyBtn.isVisible = !uiSettings.hideMinify()
        val hideTextViewer = uiSettings.hideViewer()
        textBtn.isVisible = !hideTextViewer
        viewerBtn.isVisible = !hideTextViewer
        headerAfterTextViewerSeparator.isVisible = !hideTextViewer
        openInEditorBtn.isVisible = !uiSettings.hideOpenInMainEditor()
        if (uiSettings.hideViewer() && viewMode == ViewMode.VIEWER) {
            switchToTextMode()
        } else {
            updateToggleState()
        }
        revalidate()
        repaint()
    }

    private fun applyUiSettingsToEditor() {
        textContent.applyFontSettings(uiSettings.fontFamily(), uiSettings.fontSize())
    }

    private fun enterNotesListSearchMode() {
        if (!notesListOverlayOpen) return
        notesListSearchMode = true
        showNotesListHeaderCard(NOTES_HEADER_SEARCH)
        notesListSearchField.requestFocusInWindow()
    }

    private fun exitNotesListSearchMode() {
        notesListSearchField.text = ""
        scheduleNotesListSearch()
        notesListSearchMode = false
        showNotesListHeaderCard(NOTES_HEADER_DEFAULT)
    }

    private fun handleNotesListOverlayEscape() {
        if (notesListSearchMode) {
            exitNotesListSearchMode()
        } else {
            hideNotesListOverlay()
        }
    }

    /**
     * Title matches first (sync), then after a short debounce runs async content scan
     * and appends matches one-by-one (secondary priority).
     */
    private fun scheduleNotesListSearch() {
        if (!notesListOverlayOpen) return
        val query = notesListSearchField.text.trim()
        val gen = notesListSearchGeneration.incrementAndGet()
        runNotesListTitlePhase(query, gen)
        notesListContentDebounceTimer.stop()
        if (query.isNotEmpty()) {
            notesListContentDebounceTimer.start()
        }
    }

    private fun runNotesListTitlePhase(query: String, gen: Int) {
        if (gen != notesListSearchGeneration.get()) return
        notesListRowsPanel.removeAll()
        val allTabs = storageService.getTabs()
        val activeId = storageService.getActiveTabId()
        if (query.isEmpty()) {
            for (tab in allTabs) {
                addNotesListRow(tab, activeId, "")
            }
            notesListRowsPanel.add(Box.createVerticalGlue())
            notesListRowsPanel.revalidate()
            notesListRowsPanel.repaint()
            return
        }
        val queryLower = query.lowercase(Locale.getDefault())
        for (tab in allTabs) {
            val name = tab.name.trim().ifEmpty { "Untitled" }
            if (name.lowercase(Locale.getDefault()).contains(queryLower)) {
                addNotesListRow(tab, activeId, query)
            }
        }
        notesListRowsPanel.add(Box.createVerticalGlue())
        notesListRowsPanel.revalidate()
        notesListRowsPanel.repaint()
    }

    private fun runNotesListContentPhase(query: String, gen: Int) {
        if (query.isEmpty()) return
        if (gen != notesListSearchGeneration.get()) return
        val queryLower = query.lowercase(Locale.getDefault())
        val allTabs = storageService.getTabs()
        val titleMatchedIds = allTabs.asSequence()
            .filter { tab ->
                (tab.name.trim().ifEmpty { "Untitled" }).lowercase(Locale.getDefault()).contains(queryLower)
            }
            .map { it.id }
            .toSet()
        val candidates = allTabs.filter { tab ->
            tab.id !in titleMatchedIds && tab.jsonText.contains(query, ignoreCase = true)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            for (tab in candidates) {
                if (gen != notesListSearchGeneration.get()) return@executeOnPooledThread
                SwingUtilities.invokeLater {
                    if (gen != notesListSearchGeneration.get()) return@invokeLater
                    insertNotesListRowBeforeGlue(tab, query)
                }
                try {
                    Thread.sleep(12)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@executeOnPooledThread
                }
            }
        }
    }

    private fun addNotesListRow(tab: SavedTab, activeId: String?, query: String) {
        val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(56))
        }
        val name = tab.name.trim().ifEmpty { "Untitled" }
        val titleColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }
        val titleLabel = JBLabel(name).apply {
            if (tab.id == activeId) {
                font = font.deriveFont(Font.BOLD)
            }
            alignmentX = Component.LEFT_ALIGNMENT
        }
        titleColumn.add(titleLabel)
        if (query.isNotEmpty()) {
            val n = countContentMatches(tab.jsonText, query)
            if (n > 0) {
                val hint = JBLabel(
                    if (n == 1) "Found 1 match in content" else "Found $n matches in content"
                ).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor(Color(0x6E, 0x6E, 0x6E), Color(0x9A, 0x9A, 0x9A))
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                titleColumn.add(hint)
            }
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        val editBtn = tabNavIconButton(AllIcons.Actions.Edit, "Rename")
        val delBtn = tabNavIconButton(deleteIcon(), "Delete tab")
        editBtn.addActionListener { renameTabFromList(tab.id) }
        delBtn.addActionListener { deleteTabFromList(tab.id) }
        actions.add(editBtn)
        actions.add(delBtn)
        row.add(titleColumn, BorderLayout.CENTER)
        row.add(actions, BorderLayout.EAST)
        installNotesListRowOpenHandlers(titleColumn, tab.id)
        notesListRowsPanel.add(row)
    }

    /** Non-overlapping, case-insensitive occurrences of [query] in [jsonText]. */
    private fun countContentMatches(jsonText: String, query: String): Int {
        if (query.isEmpty()) return 0
        val hay = jsonText.lowercase(Locale.getDefault())
        val nd = query.lowercase(Locale.getDefault())
        var count = 0
        var from = 0
        while (true) {
            val idx = hay.indexOf(nd, from)
            if (idx < 0) break
            count++
            from = idx + nd.length
        }
        return count
    }

    private fun stripNotesListTrailingGlue() {
        val p = notesListRowsPanel
        if (p.componentCount == 0) return
        val last = p.componentCount - 1
        val c = p.getComponent(last)
        if (c is Box.Filler) {
            p.remove(last)
        }
    }

    private fun insertNotesListRowBeforeGlue(tab: SavedTab, query: String) {
        val activeId = storageService.getActiveTabId()
        stripNotesListTrailingGlue()
        addNotesListRow(tab, activeId, query)
        notesListRowsPanel.add(Box.createVerticalGlue())
        notesListRowsPanel.revalidate()
        notesListRowsPanel.repaint()
    }

    private fun renameTabFromList(tabId: String) {
        val tab = storageService.getTabs().find { it.id == tabId } ?: return
        val newName = Messages.showInputDialog(
            project,
            "Enter a new title for this tab:",
            "Rename Tab",
            Messages.getQuestionIcon(),
            tab.name,
            null
        ) ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        storageService.updateTab(tabId, name = trimmed)
        scheduleNotesListSearch()
    }

    private fun deleteTabFromList(tabId: String) {
        val allTabs = storageService.getTabs()
        val tab = allTabs.find { it.id == tabId } ?: return
        val tabName = tab.name.trim().ifEmpty { "Untitled" }

        if (allTabs.size <= 1) {
            val confirmed = Messages.showYesNoDialog(
                this,
                "Clear \"$tabName\"? This removes all content and resets the tab title.",
                "Clear Tab",
                Messages.getQuestionIcon()
            ) == Messages.YES
            if (!confirmed) return
            val name = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("MMM dd, yyyy h:mm a", Locale.US)
            )
            textContent.setText("")
            storageService.updateTab(tabId, name = name, jsonText = "")
            scheduleNotesListSearch()
            return
        }

        val confirmed = Messages.showYesNoDialog(
            this,
            "Delete tab \"$tabName\"?",
            "Delete Tab",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!confirmed) return

        storageService.removeTab(tabId)
        scheduleNotesListSearch()
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
        val mode = NoteHighlightMode.fromSerialized(tab?.highlightMode)
        textContent.setHighlightMode(mode)
        textContent.setTextSilently(tab?.jsonText ?: "")
        syncSyntaxHighlightControl(mode)
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
            installIconButtonHover { updateToggleState() }
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
            installIconButtonHover()
            addActionListener { action() }
        }
    }

    /**
     * Vertical rule between toolbar groups. Painted explicitly so width/height stay stable in
     * [FlowLayout]. Color: [ideSeparatorColor] (same as platform toolbar separators).
     */
    private fun headerVerticalSeparator(): JComponent {
        val lineHeight = JBUI.scale(16)
        val padH = JBUI.scale(2)
        val lineW = JBUI.scale(1).coerceAtLeast(1)
        val totalW = lineW + 2 * padH
        return object : JComponent() {
            init {
                isOpaque = false
            }

            override fun getPreferredSize(): Dimension = Dimension(totalW, lineHeight)
            override fun getMaximumSize(): Dimension = Dimension(totalW, lineHeight)
            override fun getMinimumSize(): Dimension = Dimension(totalW, lineHeight)

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                    g2.color = headerSeparatorLineColor()
                    val y = (height - lineHeight) / 2
                    g2.fillRect(padH, y, lineW, lineHeight)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    private fun headerSeparatorLineColor(): Color = ideSeparatorColor()

    private fun tabNavIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            minimumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            margin = JBUI.insets(0)
            installIconButtonHover()
        }
    }

    // ── Init: apply initial toggle state ─────────────────────────────────────

    init {
        updateToggleState()
    }

    override fun validateTree() {
        // Do not wrap in runReadAction: super.validateTree() lays out the editor scroll pane,
        // which runs WriteIntentReadAction — that must not run inside ReadAction (IJ threading).
        super.validateTree()
    }

    // ── Disposable — clean up listeners and timers ─────────────────────────

    override fun dispose() {
        // Remove storage listener to prevent memory leak
        storageListener?.let { storageService.removeListener(it) }
        storageListener = null
        // Stop debounce timers to prevent firing after dispose
        searchPanel.stopTimers()
        notesListContentDebounceTimer.stop()
        // Dispose the editor component (releases IntelliJ Editor resources)
        Disposer.dispose(textContent)
    }
}

/** Opens JSON Notes in the main editor and hides the bottom tool window. */
internal fun openJsonNotesInMainEditor(project: Project) {
    val file = JsonNotesEditorVirtualFileService.getInstance(project).getOrCreateFile()
    FileEditorManager.getInstance(project).openFile(file, true)
    ToolWindowManager.getInstance(project).getToolWindow(JsonViewerPanel.TOOL_WINDOW_ID)?.hide()
}

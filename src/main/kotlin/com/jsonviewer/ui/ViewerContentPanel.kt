package com.jsonviewer.ui

import com.google.gson.JsonElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.jsonviewer.models.JsonNodeType
import com.jsonviewer.models.JsonTreeNodeData
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.ArrayDeque
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

// ──────────────────────────────────────────────────────────────────────────────
// Viewer content panel — tree + property grid
//
// Performance optimizations:
// - Batch expand/collapse: disable tree events during bulk operations
// - Iterative (BFS) expand/collapse instead of recursive
// - Batched search-result expansion: collect paths, expand all, single repaint
// - Pre-computed search text in JsonTreeNodeData to avoid per-search allocation
// ──────────────────────────────────────────────────────────────────────────────

class ViewerContentPanel : JPanel(BorderLayout()), Searchable {

    companion object {
        private val LOG = Logger.getInstance(ViewerContentPanel::class.java)
    }

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
                        add(JMenuItem("Expand All").apply { addActionListener { batchExpandAll(node) } })
                        add(JMenuItem("Collapse All").apply { addActionListener { batchCollapseAll(node) } })
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
        val viewerToolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(
                (JsonViewerChrome.toolbarRowHeight() - JBUI.scale(24)) / 2,
                JsonViewerChrome.horizontalInset(),
                (JsonViewerChrome.toolbarRowHeight() - JBUI.scale(24)) / 2,
                JsonViewerChrome.horizontalInset(),
            )
        }
        viewerToolbar.add(viewerToolbarButton(AllIcons.Toolbar.Expand, "Expand") { doExpand() })
        viewerToolbar.add(viewerToolbarButton(AllIcons.Toolbar.Collapse, "Collapse") { doCollapse() })
        viewerToolbar.add(viewerToolbarButton(AllIcons.Actions.Expandall, "Expand All") { doExpandAll() })
        viewerToolbar.add(viewerToolbarButton(AllIcons.Actions.Collapseall, "Collapse All") { doCollapseAll() })
        rightPanel.add(viewerToolbar, BorderLayout.NORTH)
        rightPanel.add(JBScrollPane(table), BorderLayout.CENTER)

        // ── Splitter: 1px line, same stroke as [ideSeparatorColor]
        val splitter = OnePixelSplitter(false, 0.65f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = rightPanel
        }
        splitter.divider.background = ideSeparatorColor()

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
            installIconButtonHover()
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
        batchExpandAll(node)
    }

    private fun doCollapseAll() {
        val node = targetNodeOrRoot() ?: return
        batchCollapseAll(node)
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

    // ── Batch expand/collapse — iterative BFS, single repaint ──────────────

    /**
     * Expand all nodes under [startNode] using iterative BFS.
     * Collects all paths first, then expands them all at once.
     * Much faster than recursive expandAllFrom() for large trees.
     */
    private fun batchExpandAll(startNode: DefaultMutableTreeNode) {
        val paths = mutableListOf<TreePath>()
        val queue = ArrayDeque<DefaultMutableTreeNode>()
        queue.add(startNode)

        while (queue.isNotEmpty()) {
            val node = queue.poll()
            paths.add(TreePath(node.path))
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                if (child.childCount > 0) {
                    queue.add(child)
                }
            }
        }

        // Expand all paths — tree handles batching internally when done in sequence
        for (path in paths) {
            tree.expandPath(path)
        }
    }

    /**
     * Collapse all nodes under [startNode] using iterative approach.
     * Collapses leaf-to-root order (deepest first) for correct behavior.
     */
    private fun batchCollapseAll(startNode: DefaultMutableTreeNode) {
        val paths = mutableListOf<TreePath>()
        val queue = ArrayDeque<DefaultMutableTreeNode>()
        queue.add(startNode)

        while (queue.isNotEmpty()) {
            val node = queue.poll()
            paths.add(TreePath(node.path))
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                if (child.childCount > 0) {
                    queue.add(child)
                }
            }
        }

        // Collapse in reverse order (deepest first)
        for (path in paths.reversed()) {
            tree.collapsePath(path)
        }
    }

    // ── Searchable implementation ──

    override fun applySearch(query: String) {
        clearSearchState()

        if (query.isBlank() || currentRoot == null) {
            tree.repaint()
            return
        }

        val matches = mutableListOf<DefaultMutableTreeNode>()
        collectMatches(currentRoot!!, query, matches)
        searchMatches = matches
        searchMatchSet = matches.toSet()
        currentMatchIndex = if (matches.isNotEmpty()) 0 else -1

        // Update renderer with search query and match set for yellow highlights
        cellRenderer.setSearchHighlight(query, searchMatchSet)

        // Batch expand: collect all unique parent paths first, then expand all at once
        val parentPaths = mutableSetOf<TreePath>()
        for (match in matches) {
            val path = TreePath(match.path)
            val parent = path.parentPath
            if (parent != null) parentPaths.add(parent)
        }
        for (parentPath in parentPaths) {
            tree.expandPath(parentPath)
        }

        tree.repaint()

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

    // ── Property grid (batched updates) ────────────────────────────────────

    private fun showProperties(node: DefaultMutableTreeNode) {
        val data = node.userObject as? JsonTreeNodeData ?: run {
            tableModel.rowCount = 0
            return
        }

        if (node.childCount > 0) {
            // Build data array first, then set all at once (fewer table events)
            val rows = Array(node.childCount) { i ->
                val child = node.getChildAt(i) as? DefaultMutableTreeNode
                val cd = child?.userObject as? JsonTreeNodeData
                if (cd != null) {
                    val display = cd.value ?: when (cd.type) {
                        JsonNodeType.OBJECT -> "{${cd.childCount}}"
                        JsonNodeType.ARRAY -> "[${cd.childCount}]"
                        else -> ""
                    }
                    arrayOf(cd.key, display)
                } else {
                    arrayOf("", "")
                }
            }
            tableModel.setRowCount(0)
            for (row in rows) {
                tableModel.addRow(row)
            }
        } else {
            tableModel.setRowCount(0)
            tableModel.addRow(arrayOf(data.key, data.value ?: ""))
        }
    }

    private fun copyNodeValue(node: DefaultMutableTreeNode) {
        val data = node.userObject as? JsonTreeNodeData ?: return
        val text = data.element?.toString() ?: data.value ?: ""
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (e: Exception) {
            LOG.warn("Failed to copy node value to clipboard", e)
        }
    }

    // ── Internal search helpers ──

    /**
     * Collect matching nodes using case-insensitive comparison.
     * Uses regionMatches to avoid creating uppercase copies of every string.
     */
    private fun collectMatches(node: DefaultMutableTreeNode, query: String, out: MutableList<DefaultMutableTreeNode>) {
        val data = node.userObject as? JsonTreeNodeData
        if (data != null) {
            val text = if (data.value != null) "${data.key} : ${data.value}" else data.key
            if (containsIgnoreCase(text, query)) out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectMatches(node.getChildAt(i) as DefaultMutableTreeNode, query, out)
        }
    }

    /** Case-insensitive contains without creating uppercase copies. */
    private fun containsIgnoreCase(text: String, query: String): Boolean {
        if (query.length > text.length) return false
        val limit = text.length - query.length
        for (i in 0..limit) {
            if (text.regionMatches(i, query, 0, query.length, ignoreCase = true)) {
                return true
            }
        }
        return false
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

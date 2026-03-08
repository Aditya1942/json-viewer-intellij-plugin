package com.jsonviewer.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.jsonviewer.models.JsonNodeType
import com.jsonviewer.models.JsonTreeNodeData
import java.awt.Color
import java.awt.Component
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

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

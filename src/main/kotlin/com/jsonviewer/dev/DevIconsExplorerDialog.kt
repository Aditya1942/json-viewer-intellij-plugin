package com.jsonviewer.dev

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.lang.reflect.Modifier
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Icon
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private data class IconEntry(val fqName: String, val icon: Icon)

private const val ALL_ICONS_MARKER = "AllIcons."

/** Text shown in the grid: everything after `AllIcons.` (e.g. `Actions.Add`). */
private fun pathAfterAllIcons(fqName: String): String {
    val i = fqName.indexOf(ALL_ICONS_MARKER)
    if (i < 0) return fqName
    return fqName.substring(i + ALL_ICONS_MARKER.length)
}

/**
 * Modal dialog listing every static [Icon] reachable under [AllIcons] (nested classes, recursive).
 * Icons are shown in a grid at medium size; labels use the short path after `AllIcons.`.
 * Click copies the **full** qualified path to the clipboard.
 */
class DevIconsExplorerDialog(project: Project?) : DialogWrapper(project) {

    private val allEntries: List<IconEntry> = collectAllIconsRecursive()
    private var visibleEntries: List<IconEntry> = emptyList()
    private lateinit var gridPanel: JPanel
    private lateinit var filterField: JBTextField
    private lateinit var countLabel: JBLabel

    init {
        title = "AllIcons (dev)"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        countLabel = JBLabel().apply {
            border = JBUI.Borders.emptyTop(4)
        }

        gridPanel = JPanel(
            GridLayout(0, GRID_COLS, JBUI.scale(8), JBUI.scale(12))
        )

        filterField = JBTextField().apply {
            emptyText.text = "Filter by path…"
            preferredSize = Dimension(JBUI.scale(400), minimumSize.height)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) {
                    applyFilter()
                }

                override fun removeUpdate(e: DocumentEvent) {
                    applyFilter()
                }

                override fun changedUpdate(e: DocumentEvent) {
                    applyFilter()
                }
            })
        }

        visibleEntries = allEntries
        rebuildGrid()
        updateCountLabel()

        val scroll = JBScrollPane(gridPanel).apply {
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(420))
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(filterField, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JBLabel("Click an icon to copy the full path (e.g. com.intellij.icons.AllIcons.…).").apply {
                            font = font.deriveFont(font.size2D - 1f)
                        },
                        BorderLayout.NORTH
                    )
                    add(countLabel, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = filterField

    override fun getDimensionServiceKey(): String = "JsonNotes.DevIconsExplorer"

    private fun rebuildGrid() {
        gridPanel.removeAll()
        for (e in visibleEntries) {
            gridPanel.add(createIconCell(e))
        }
        gridPanel.revalidate()
        gridPanel.repaint()
    }

    private fun createIconCell(entry: IconEntry): JComponent {
        val short = pathAfterAllIcons(entry.fqName)
        val safe = StringUtil.escapeXmlEntities(short)
        val scaled = IconUtil.scale(entry.icon, null, ICON_SCALE_FACTOR)
        val iconLabel = JLabel(scaled).apply {
            horizontalAlignment = SwingConstants.CENTER
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val textLabel = JBLabel(
            "<html><div style='width:${JBUI.scale(CELL_LABEL_WIDTH)}px;text-align:center'>$safe</div></html>"
        ).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            alignmentX = Component.CENTER_ALIGNMENT
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.CENTER_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4),
                BorderFactory.createLineBorder(JBUI.CurrentTheme.DefaultTabs.borderColor(), 1)
            )
            preferredSize = Dimension(JBUI.scale(CELL_WIDTH), JBUI.scale(CELL_HEIGHT))
            maximumSize = Dimension(JBUI.scale(CELL_WIDTH), JBUI.scale(CELL_HEIGHT))
            add(iconLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(textLabel)
            toolTipText = entry.fqName
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    copyPathToClipboard(entry.fqName)
                }
            })
        }
    }

    private fun applyFilter() {
        val q = filterField.text.trim()
        visibleEntries = if (q.isEmpty()) {
            allEntries
        } else {
            val ql = q.lowercase()
            allEntries.filter { matches(it, ql) }
        }
        rebuildGrid()
        updateCountLabel()
    }

    private fun matches(entry: IconEntry, qLower: String): Boolean {
        if (entry.fqName.lowercase().contains(qLower)) return true
        return pathAfterAllIcons(entry.fqName).lowercase().contains(qLower)
    }

    private fun updateCountLabel() {
        val n = visibleEntries.size
        val q = if (this::filterField.isInitialized) filterField.text.trim() else ""
        countLabel.text = if (q.isEmpty()) {
            "$n icons"
        } else {
            "Showing $n of ${allEntries.size} icons"
        }
    }

    companion object {
        /** ~28px logical from a 16px base icon — reads as “medium” in the grid. */
        private const val ICON_SCALE_FACTOR = 1.75f
        private const val GRID_COLS = 6
        private const val CELL_WIDTH = 108
        private const val CELL_HEIGHT = 120
        private const val CELL_LABEL_WIDTH = 96

        private fun collectAllIconsRecursive(): List<IconEntry> {
            val out = mutableListOf<IconEntry>()
            fun walk(clazz: Class<*>, path: String) {
                for (field in clazz.declaredFields) {
                    if (!Modifier.isStatic(field.modifiers)) continue
                    if (!Icon::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val icon = try {
                        field.get(null) as? Icon
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    out.add(IconEntry("${path}.${field.name}", icon))
                }
                for (inner in clazz.declaredClasses) {
                    if (!Modifier.isStatic(inner.modifiers)) continue
                    walk(inner, "${path}.${inner.simpleName}")
                }
            }
            walk(AllIcons::class.java, "com.intellij.icons.AllIcons")
            return out.sortedBy { it.fqName }
        }

        private fun copyPathToClipboard(text: String) {
            val sel = java.awt.datatransfer.StringSelection(text)
            try {
                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(sel)
            } catch (_: Exception) {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
            }
        }
    }
}

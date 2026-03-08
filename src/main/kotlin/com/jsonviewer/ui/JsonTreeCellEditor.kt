package com.jsonviewer.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.AbstractCellEditor
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellEditor

// ──────────────────────────────────────────────────────────────────────────────
// Tree cell editor — selectable text field when a node is selected
// ──────────────────────────────────────────────────────────────────────────────

class JsonTreeCellEditor(
    private val renderer: JsonTreeCellRenderer
) : AbstractCellEditor(), TreeCellEditor {

    private val textField = JTextField().apply {
        isEditable = false
        border = JBUI.Borders.empty(2, 1)
        font = PluginFonts.MONO
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

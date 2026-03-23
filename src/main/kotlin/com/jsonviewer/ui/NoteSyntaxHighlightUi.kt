package com.jsonviewer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Toolbar combo for [NoteHighlightMode] / explicit [FileType], plus "All types…" popup.
 */
sealed class SyntaxHighlightComboItem {
    data object Plain : SyntaxHighlightComboItem()
    data object Auto : SyntaxHighlightComboItem()
    data object AllTypesAction : SyntaxHighlightComboItem()
    data class Explicit(val fileType: FileType) : SyntaxHighlightComboItem()

    fun toNoteHighlightMode(): NoteHighlightMode? = when (this) {
        is Plain -> NoteHighlightMode.Plain
        is Auto -> NoteHighlightMode.Auto
        is AllTypesAction -> null
        is Explicit -> NoteHighlightMode.Explicit(fileType)
    }

    companion object {
        fun label(item: SyntaxHighlightComboItem): String = when (item) {
            is Plain -> "Plain"
            is Auto -> "Auto"
            is AllTypesAction -> "All types…"
            is Explicit -> item.fileType.name
        }

        fun icon(item: SyntaxHighlightComboItem): Icon = when (item) {
            is Plain -> AllIcons.FileTypes.Text
            is Auto -> AllIcons.FileTypes.Any_type
            is AllTypesAction -> AllIcons.Actions.More
            is Explicit -> item.fileType.icon ?: AllIcons.FileTypes.Unknown
        }

        fun forMode(mode: NoteHighlightMode): SyntaxHighlightComboItem = when (mode) {
            is NoteHighlightMode.Plain -> Plain
            is NoteHighlightMode.Auto -> Auto
            is NoteHighlightMode.Explicit -> Explicit(mode.fileType)
        }

        fun buildDefaultItems(): List<SyntaxHighlightComboItem> = buildList {
            add(Plain)
            add(Auto)
            val seen = mutableSetOf<String>()
            for (ext in NoteHighlightFileTypeResolver.popularExtensions) {
                val ft = NoteHighlightFileTypeResolver.getByExtension(ext) ?: continue
                if (ft.isBinary) continue
                if (!seen.add(ft.name)) continue
                add(Explicit(ft))
            }
            add(AllTypesAction)
        }

        fun findItemForMode(list: List<SyntaxHighlightComboItem>, mode: NoteHighlightMode): SyntaxHighlightComboItem {
            when (mode) {
                is NoteHighlightMode.Plain -> return Plain
                is NoteHighlightMode.Auto -> return Auto
                is NoteHighlightMode.Explicit -> {
                    if (list.any { it is SyntaxHighlightComboItem.Explicit && it.fileType.name == mode.fileType.name }) {
                        return list.first {
                            it is SyntaxHighlightComboItem.Explicit && it.fileType.name == mode.fileType.name
                        }
                    }
                    return Explicit(mode.fileType)
                }
            }
        }
    }
}

object NoteSyntaxHighlightUi {

    fun iconForMode(mode: NoteHighlightMode): Icon = when (mode) {
        is NoteHighlightMode.Plain -> AllIcons.FileTypes.Text
        is NoteHighlightMode.Auto -> AllIcons.FileTypes.Any_type
        is NoteHighlightMode.Explicit -> mode.fileType.icon ?: AllIcons.FileTypes.Unknown
    }

    fun tooltipForMode(mode: NoteHighlightMode): String = when (mode) {
        is NoteHighlightMode.Plain -> "Syntax: Plain (JSON when valid)"
        is NoteHighlightMode.Auto -> "Syntax: Auto-detect from content"
        is NoteHighlightMode.Explicit -> "Syntax: ${mode.fileType.name}"
    }

    /**
     * Same entries as the former combo: Plain, Auto, popular types, All types…
     */
    fun showHighlightModePopup(
        anchor: Component,
        items: List<SyntaxHighlightComboItem>,
        onPick: (SyntaxHighlightComboItem) -> Unit,
    ) {
        if (items.isEmpty()) return
        val step = object : BaseListPopupStep<SyntaxHighlightComboItem>("Syntax highlighting", items) {
            override fun getTextFor(value: SyntaxHighlightComboItem): String = SyntaxHighlightComboItem.label(value)

            override fun getIconFor(value: SyntaxHighlightComboItem) = SyntaxHighlightComboItem.icon(value)

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: SyntaxHighlightComboItem, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    onPick(selectedValue)
                }
                return null
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
    }

    fun createComboBoxRenderer(): ListCellRenderer<SyntaxHighlightComboItem> =
        object : DefaultListCellRenderer(), ListCellRenderer<SyntaxHighlightComboItem> {
            override fun getListCellRendererComponent(
                list: JList<out SyntaxHighlightComboItem>?,
                value: SyntaxHighlightComboItem?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val item = value
                if (item == null) {
                    label.text = ""
                    return label
                }
                label.text = SyntaxHighlightComboItem.label(item)
                label.icon = SyntaxHighlightComboItem.icon(item)
                label.border = JBUI.Borders.empty(2, 6)
                return label
            }
        }

    fun showAllFileTypesPopup(anchor: Component, onChosen: (FileType) -> Unit) {
        val types = NoteHighlightFileTypeResolver.searchableEditorFileTypes()
        if (types.isEmpty()) return
        val step = object : BaseListPopupStep<FileType>("All file types", types) {
            override fun getTextFor(value: FileType): String = value.name

            override fun getIconFor(value: FileType) = value.icon

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: FileType, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    onChosen(selectedValue)
                }
                return null
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
    }

    fun createComboBoxModel(): DefaultComboBoxModel<SyntaxHighlightComboItem> {
        val items = SyntaxHighlightComboItem.buildDefaultItems()
        return DefaultComboBoxModel(items.toTypedArray())
    }

    fun createComboBox(model: DefaultComboBoxModel<SyntaxHighlightComboItem>): JComboBox<SyntaxHighlightComboItem> {
        val combo = JComboBox(model)
        combo.renderer = createComboBoxRenderer()
        combo.toolTipText = "Syntax highlighting: Plain (JSON when valid), Auto, or language"
        return combo
    }
}

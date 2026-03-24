package com.jsonviewer.ui

import com.intellij.util.ui.JBUI
import javax.swing.border.Border

/**
 * Shared layout tokens for the JSON Notes tool window chrome (tab bar, headers, overlays, search).
 */
object JsonViewerChrome {
    /** Toolbar row height — fits 24px icon buttons with minimal vertical room. */
    fun toolbarRowHeight(): Int = JBUI.scale(28)

    /** Horizontal padding inside chrome rows (overlay headers, search, etc.). */
    fun horizontalInset(): Int = JBUI.scale(8)

    /**
     * Horizontal inset for full-page overlay body content (settings, notes list) and overlay headers.
     * Same value as the main editor search bar and viewer chrome (see SearchPanel, ViewerContentPanel).
     */
    fun contentPanelHorizontalInset(): Int = horizontalInset()

    /** Small gap before the tab title only (top chrome left column). */
    fun tabTitleLeadingInset(): Int = JBUI.scale(4)

    /** Small vertical padding inside overlay header rows (back + title / search). */
    fun overlayHeaderVerticalPadding(): Int = JBUI.scale(4)

    /** Bottom edge: 1px separator matching [ideSeparatorColor]. */
    fun bottomToolbarBorder(): Border =
        JBUI.Borders.customLine(ideSeparatorColor(), 0, 0, 1, 0)

    /** Top edge: 1px separator (e.g. search bar above content). */
    fun topToolbarBorder(): Border =
        JBUI.Borders.customLine(ideSeparatorColor(), 1, 0, 0, 0)
}

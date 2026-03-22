package com.jsonviewer.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton

/** Subtle toolbar hover (light / dark). */
private val ICON_BUTTON_HOVER_BG = JBColor(
    Color(0xE8, 0xE8, 0xE8),
    Color(0x4A, 0x4A, 0x4A)
)

/**
 * Flat icon [JButton]s: show background on hover. Use [onExitRestore] when exit must
 * re-apply another style (e.g. Text/Viewer mode toggles).
 */
fun JButton.installIconButtonHover(onExitRestore: (() -> Unit)? = null) {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            if (!isEnabled) return
            isOpaque = true
            background = ICON_BUTTON_HOVER_BG
            repaint()
        }

        override fun mouseExited(e: MouseEvent) {
            if (onExitRestore != null) {
                onExitRestore()
            } else {
                isOpaque = false
                background = null
                repaint()
            }
        }
    })
}
